package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.mask.VFXCustomShape;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskPrimitive;
import dev.vfxweaver.mask.VFXMaskShapeKind;
import dev.vfxweaver.mask.VFXMaskSlots;
import dev.vfxweaver.mask.VFXMaskSpace;
import dev.vfxweaver.mask.VFXShapeRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Matrix4fc;

/**
 * Writes and sizes the coverage prepass's {@code Config} UBO. The field order here and in
 * {@code post/mask_coverage.fsh} is positional and must be edited together (AGENTS.md). The shape
 * parameter order is the shared shape library's; this class only packs it. A slot authored as a
 * world-coordinate binding ({@code { "bind": ... }}) is resolved here, once per distinct mask per
 * frame, through {@link VFXWorldBindings} — never in the shader.
 *
 * <p>std140 note: {@code Std140Builder.putMat4f} is package-private, so the inverse
 * view-projection matrix is written as four column {@code vec4}s, which is the identical std140
 * layout.
 */
public final class VFXMaskUniforms {
	private VFXMaskUniforms() {
	}

	/**
	 * The value of one numeric mask slot: a world-coordinate binding wins over the definition's
	 * literal/graph value, and a binding that cannot be resolved falls back to the literal default.
	 */
	private static float slotValue(final VFXMask mask, final VFXActiveEffect effect, final String slotName, final float fallback) {
		final VFXMask.MaskSlot slot = mask.slots().get(slotName);
		if (slot != null && slot.binding() != null) {
			return VFXWorldBindings.evaluate(slot.binding(), fallback);
		}
		return effect.getParam(slotName, fallback);
	}

	/** The std140 byte size of the coverage {@code Config} block. */
	public static int uboSize() {
		final Std140SizeCalculator calculator = new Std140SizeCalculator();
		calculator.putMat4f();
		calculator.putVec4();
		calculator.putFloat();
		calculator.putFloat();
		calculator.putFloat();
		calculator.putFloat();
		for (int i = 0; i < VFXMask.MAX_PRIMITIVES; i++) {
			calculator.putVec4();
			calculator.putVec4();
			calculator.putVec4();
			calculator.putVec4();
			calculator.putVec4();
			calculator.putVec4();
		}
		for (int i = 0; i < VFXCustomShape.MAX_CUSTOM_LEAVES; i++) {
			calculator.putVec4(); // custom_op
		}
		for (int i = 0; i < VFXCustomShape.MAX_CUSTOM_LEAVES * VFXCustomShape.MAX_CUSTOM_PARTS; i++) {
			calculator.putVec4(); // custom_kind
			calculator.putVec4(); // custom_center
			calculator.putVec4(); // custom_params
		}
		return calculator.get();
	}

	/**
	 * Fills the coverage {@code Config} UBO (field order mirrors {@code post/mask_coverage.fsh}).
	 * Unused primitives are written neutral and ignored because {@code mask_count} gates the loop.
	 * A world leaf needs the matrix and camera; a purely screen mask ignores them. A composed custom
	 * leaf's fixed parts are packed into the {@code custom_*} rows and its leaf slot stores the row.
	 */
	public static void writeCoverage(
		final Std140Builder builder,
		final VFXActiveEffect effect,
		final VFXMask mask,
		final Matrix4fc invViewProj,
		final float camX,
		final float camY,
		final float camZ,
		final float time
	) {
		// mat4 as four column vec4s (std140-identical to Std140Builder.putMat4f).
		builder.putVec4(invViewProj.m00(), invViewProj.m01(), invViewProj.m02(), invViewProj.m03());
		builder.putVec4(invViewProj.m10(), invViewProj.m11(), invViewProj.m12(), invViewProj.m13());
		builder.putVec4(invViewProj.m20(), invViewProj.m21(), invViewProj.m22(), invViewProj.m23());
		builder.putVec4(invViewProj.m30(), invViewProj.m31(), invViewProj.m32(), invViewProj.m33());
		builder.putVec4(camX, camY, camZ, 0.0F);
		builder.putFloat(mask.invert() ? 1.0F : 0.0F);
		builder.putFloat(mask.primitives().size());
		builder.putFloat(mask.needsDepth() ? 1.0F : 0.0F);
		builder.putFloat(time);

		// Assign each distinct custom leaf a row (first-use order), bounded by MAX_CUSTOM_LEAVES.
		final Map<String, Integer> customRows = new LinkedHashMap<>();
		for (final VFXMaskPrimitive primitive : mask.primitives()) {
			if (primitive.family() == VFXMaskPrimitive.Family.CUSTOM && customRows.size() < VFXCustomShape.MAX_CUSTOM_LEAVES) {
				customRows.putIfAbsent(primitive.customShape(), customRows.size());
			}
		}

		// std140 lays every declared array out contiguously (all shape_op, then all field_params,
		// ...), so the six per-primitive rows must be emitted grouped by field, not interleaved
		// per primitive. Compute each primitive's rows first, then write them field by field; an
		// absent primitive leaves its rows zeroed, which the mask_count gate ignores anyway.
		final float[][][] rows = new float[VFXMask.MAX_PRIMITIVES][6][4];
		for (int i = 0; i < VFXMask.MAX_PRIMITIVES; i++) {
			final VFXMaskPrimitive primitive = i < mask.primitives().size() ? mask.primitives().get(i) : null;
			if (primitive == null) {
				continue;
			}
			final float operation = i == 0 ? 0.0F : mask.ops().get(i - 1).ordinal();
			final Integer customRow = primitive.family() == VFXMaskPrimitive.Family.CUSTOM ? customRows.get(primitive.customShape()) : null;
			rows[i][0] = new float[]{primitive.kindCode(), operation, primitive.softnessDefault(), primitive.field().ordinal()};
			rows[i][1] = new float[]{
				slotValue(mask, effect, primitive.fieldAmountSlot(), primitive.fieldAmountDefault()),
				slotValue(mask, effect, primitive.fieldScaleSlot(), primitive.fieldScaleDefault()),
				primitive.fieldSeed(),
				primitive.space() == VFXMaskSpace.WORLD ? 1.0F : 0.0F
			};
			// A bound centre overrides the literal/graph centre; a derived screen rectangle overrides
			// the centre AND the half-extents. Resolution is cached per frame by VFXWorldBindings.
			final BoundParam centerBinding = primitive.centerBinding();
			final float[] centerPoint = centerBinding != null && centerBinding.kind() == BoundParam.Kind.POINT
				? VFXWorldBindings.evaluatePoint(centerBinding) : null;
			// The derived screen rectangle's empty sentinel is {0,0,-1,-1}; a negative half-extent
			// means the entity is absent/off-screen and the leaf falls back to its literal values.
			final float[] rawScreenRect = centerBinding != null && centerBinding.kind() == BoundParam.Kind.SCREEN_RECT
				? VFXWorldBindings.evaluateScreenRect(centerBinding) : null;
			final float[] screenRect = rawScreenRect != null && rawScreenRect[2] >= 0.0F && rawScreenRect[3] >= 0.0F ? rawScreenRect : null;
			rows[i][2] = new float[]{
				centerPoint != null ? centerPoint[0] : (screenRect != null ? screenRect[0] : slotValue(mask, effect, primitive.centerSlots()[0], primitive.centerDefaults()[0])),
				centerPoint != null ? centerPoint[1] : (screenRect != null ? screenRect[1] : slotValue(mask, effect, primitive.centerSlots()[1], primitive.centerDefaults()[1])),
				centerPoint != null ? centerPoint[2] : (screenRect != null ? 0.0F : (primitive.centerSlots().length > 2 ? slotValue(mask, effect, primitive.centerSlots()[2], primitive.centerDefaults()[2]) : 0.0F)),
				slotValue(mask, effect, primitive.rotationSlot(), primitive.rotationDefault())
			};
			final float[] parameters = new float[VFXMaskSlots.MAX_LEAF_PARAMS];
			for (int j = 0; j < primitive.parameterSlots().length && j < VFXMaskSlots.MAX_LEAF_PARAMS; j++) {
				parameters[j] = slotValue(mask, effect, primitive.parameterSlots()[j], primitive.parameterDefaults()[j]);
			}
			if (screenRect != null && primitive.shape() == VFXMaskShapeKind.RECT) {
				final int halfWidth = VFXMaskShapeKind.RECT.parameterNames().indexOf("half_width");
				final int halfHeight = VFXMaskShapeKind.RECT.parameterNames().indexOf("half_height");
				if (halfWidth >= 0 && halfWidth < parameters.length) {
					parameters[halfWidth] = screenRect[2];
				}
				if (halfHeight >= 0 && halfHeight < parameters.length) {
					parameters[halfHeight] = screenRect[3];
				}
			}
			rows[i][3] = new float[]{parameters[0], parameters[1], parameters[2], parameters[3]};
			rows[i][4] = new float[]{parameters[4], parameters[5], parameters[6], parameters[7]};
			rows[i][5] = new float[]{primitive.fill().ordinal(), slotValue(mask, effect, primitive.strokeSlot(), primitive.strokeDefault()), i, customRow == null ? -1.0F : customRow};
		}
		for (int row = 0; row < 6; row++) {
			for (int i = 0; i < VFXMask.MAX_PRIMITIVES; i++) {
				builder.putVec4(rows[i][row][0], rows[i][row][1], rows[i][row][2], rows[i][row][3]);
			}
		}

		// custom_op per row: x=family (0 composed, 1 plugin), y=part count, z/w=the first two ops.
		final VFXCustomShape[] rowShapes = new VFXCustomShape[VFXCustomShape.MAX_CUSTOM_LEAVES];
		for (final Map.Entry<String, Integer> entry : customRows.entrySet()) {
			rowShapes[entry.getValue()] = VFXShapeRegistry.get().get(entry.getKey());
		}
		for (int row = 0; row < VFXCustomShape.MAX_CUSTOM_LEAVES; row++) {
			final VFXCustomShape shape = rowShapes[row];
			final boolean plugin = shape != null && shape.family() == VFXCustomShape.Family.GLSL_PLUGIN;
			final int parts = shape == null || plugin ? 0 : shape.parts().size();
			final float op0 = parts > 1 ? shape.ops().get(0).ordinal() : 0.0F;
			final float op1 = parts > 2 ? shape.ops().get(1).ordinal() : 0.0F;
			builder.putVec4(plugin ? 1.0F : 0.0F, parts, op0, op1);
		}
		// Same std140 grouping for the composed parts: all custom_kind, then custom_center, then
		// custom_params, each indexed row * MAX_CUSTOM_PARTS + part (as the shader reads it).
		final float[][][][] partRows = new float[VFXCustomShape.MAX_CUSTOM_LEAVES][VFXCustomShape.MAX_CUSTOM_PARTS][3][4];
		for (int row = 0; row < VFXCustomShape.MAX_CUSTOM_LEAVES; row++) {
			final VFXCustomShape shape = rowShapes[row];
			for (int p = 0; p < VFXCustomShape.MAX_CUSTOM_PARTS; p++) {
				if (shape == null || shape.family() != VFXCustomShape.Family.COMPOSED || p >= shape.parts().size()) {
					continue;
				}
				final VFXCustomShape.Part part = shape.parts().get(p);
				partRows[row][p][0] = new float[]{part.shape().ordinal(), part.space() == VFXMaskSpace.WORLD ? 1.0F : 0.0F, part.rounding(), part.repeat()};
				partRows[row][p][1] = new float[]{part.center()[0], part.center()[1], part.center().length > 2 ? part.center()[2] : 0.0F, part.rotation()};
				partRows[row][p][2] = new float[]{part.params()[0], part.params().length > 1 ? part.params()[1] : 0.0F, part.params().length > 2 ? part.params()[2] : 0.0F, 0.0F};
			}
		}
		for (int field = 0; field < 3; field++) {
			for (int row = 0; row < VFXCustomShape.MAX_CUSTOM_LEAVES; row++) {
				for (int p = 0; p < VFXCustomShape.MAX_CUSTOM_PARTS; p++) {
					builder.putVec4(partRows[row][p][field][0], partRows[row][p][field][1], partRows[row][p][field][2], partRows[row][p][field][3]);
				}
			}
		}
	}
}

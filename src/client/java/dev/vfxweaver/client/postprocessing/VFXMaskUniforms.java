package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.buffers.Std140Builder;
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
import java.util.List;
import java.util.Map;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

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
	 * literal/graph value. A binding that cannot be resolved yields the literal default here, but
	 * {@link #primitiveResolved} drops the owning leaf before any packed value is used.
	 *
	 * <p>Package-private so the block-geometry pass resolves a block leaf's slots with the same
	 * precedence.
	 */
	static float slotValue(final VFXMask mask, final VFXActiveEffect effect, final String slotName, final float fallback) {
		final VFXMask.MaskSlot slot = mask.slots().get(slotName);
		if (slot != null && slot.binding() != null) {
			return VFXWorldBindings.evaluate(slot.binding(), fallback);
		}
		return effect.getParam(slotName, fallback);
	}

	/**
	 * True when every world-coordinate binding one leaf uses resolves this frame. A leaf with an
	 * unresolved source (an entity that is absent, off-screen or outside the client's tracking
	 * range, no camera or player state) must contribute zero coverage rather than fall through to
	 * its literal slot defaults: the literal default of an unbound screen {@code rect} is a
	 * full-screen rectangle, which is why an absent entity used to tint everything. Fail-closed is
	 * per leaf, so an unresolved screen leaf no longer takes a still-resolved world leaf down with
	 * it. A binding with no world source (literal {@code pos}) is always resolved.
	 */
	private static boolean primitiveResolved(final VFXMask mask, final VFXMaskPrimitive primitive, final int primitiveIndex) {
		if (!bindingResolved(primitive.centerBinding())) {
			return false;
		}
		if (!VFXWorldBindings.isSourceResolved(primitive.sizeBinding())) {
			return false;
		}
		for (final String slot : primitive.centerSlots()) {
			if (!slotResolved(mask, slot)) {
				return false;
			}
		}
		if (!slotResolved(mask, primitive.rotationSlot())) {
			return false;
		}
		for (final String slot : primitive.parameterSlots()) {
			if (!slotResolved(mask, slot)) {
				return false;
			}
		}
		// A bound dynamic-data slot (only custom leaves register them) fails the leaf closed too.
		for (int j = 0; j < VFXMaskSlots.MAX_LEAF_DATA; j++) {
			if (!slotResolved(mask, VFXMaskSlots.data(primitiveIndex, j))) {
				return false;
			}
		}
		return slotResolved(mask, primitive.strokeSlot())
			&& slotResolved(mask, primitive.softnessSlot())
			&& slotResolved(mask, primitive.fieldAmountSlot())
			&& slotResolved(mask, primitive.fieldScaleSlot());
	}

	/**
	 * True when one binding resolves. A derived {@code SCREEN_RECT} is unresolved when its entity
	 * box is absent, behind the camera or fully off-screen (the empty {@code {0,0,-1,-1}}
	 * sentinel); every other binding delegates to its world source.
	 */
	private static boolean bindingResolved(final @Nullable BoundParam binding) {
		if (binding == null) {
			return true;
		}
		if (binding.kind() == BoundParam.Kind.SCREEN_RECT) {
			final float[] rect = VFXWorldBindings.evaluateScreenRect(binding);
			return rect[2] >= 0.0F && rect[3] >= 0.0F;
		}
		return VFXWorldBindings.isSourceResolved(binding);
	}

	/** True when a reserved slot's binding (if any) resolves; a missing or unbound slot is resolved. */
	private static boolean slotResolved(final VFXMask mask, final String slotName) {
		final VFXMask.MaskSlot slot = mask.slots().get(slotName);
		return slot == null || bindingResolved(slot.binding());
	}

	/**
	 * One field of the coverage {@code Config} block, in the order both the writer and the shader
	 * declare it.
	 *
	 * @param name        the GLSL field name
	 * @param glslType    {@code mat4}, {@code vec4} or {@code float}
	 * @param arrayLength the element count ({@code 1} for a scalar or matrix)
	 */
	public record ConfigField(String name, String glslType, int arrayLength) {
	}

	/**
	 * The coverage {@code Config} block fields in write order. This is the writer's single source of
	 * truth for both the std140 size ({@link #uboSize()}) and the emission order; the standalone
	 * {@code scripts/check-mask-ubo.ps1} compares it against the layout declared in
	 * {@code post/mask_coverage.fsh}.
	 */
	private static final List<ConfigField> CONFIG_LAYOUT = List.of(
		new ConfigField("invViewProj", "mat4", 1),
		new ConfigField("camPos", "vec4", 1),
		new ConfigField("mask_invert", "float", 1),
		new ConfigField("mask_count", "float", 1),
		new ConfigField("mask_needs_depth", "float", 1),
		new ConfigField("mask_time", "float", 1),
		new ConfigField("shape_op", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("field_params", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("shape_center", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("shape_params0", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("shape_params1", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("shape_misc", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("shape_volume", "vec4", VFXMask.MAX_PRIMITIVES),
		new ConfigField("custom_op", "vec4", VFXCustomShape.MAX_CUSTOM_LEAVES),
		new ConfigField("custom_kind", "vec4", VFXCustomShape.MAX_CUSTOM_LEAVES * VFXCustomShape.MAX_CUSTOM_PARTS),
		new ConfigField("custom_center", "vec4", VFXCustomShape.MAX_CUSTOM_LEAVES * VFXCustomShape.MAX_CUSTOM_PARTS),
		new ConfigField("custom_params", "vec4", VFXCustomShape.MAX_CUSTOM_LEAVES * VFXCustomShape.MAX_CUSTOM_PARTS),
		// Per-leaf dynamic float data, vec4-packed: primitive i owns the slice
		// [i * MAX_LEAF_DATA, (i + 1) * MAX_LEAF_DATA). Appended last (never reorder the existing
		// fields). A GLSL plugin reads it through vfx_mask_data(i * MAX_LEAF_DATA + j).
		new ConfigField("shape_data", "vec4", VFXMask.MAX_PRIMITIVES * VFXMaskSlots.MAX_LEAF_DATA_VEC4)
	);

	/**
	 * The coverage {@code Config} block fields in write order, for the standalone size/order check.
	 *
	 * @return the writer's declared layout, unmodifiable
	 */
	public static List<ConfigField> configLayout() {
		return CONFIG_LAYOUT;
	}

	/** The std140 byte size of the coverage {@code Config} block. */
	public static int uboSize() {
		int size = 0;
		for (final ConfigField field : CONFIG_LAYOUT) {
			size += std140ElementSize(field.glslType()) * field.arrayLength();
		}
		return size;
	}

	private static int std140ElementSize(final String glslType) {
		return switch (glslType) {
			case "mat4" -> 64;
			case "vec4" -> 16;
			case "float" -> 4;
			default -> throw new IllegalArgumentException("unsupported Config field type: " + glslType);
		};
	}

	/**
	 * Fills the coverage {@code Config} UBO (field order mirrors {@code post/mask_coverage.fsh}).
	 * Unused primitives are written neutral and ignored because {@code mask_count} gates the loop.
	 * A world leaf needs the matrix and camera; a purely screen mask ignores them. A composed custom
	 * leaf's fixed parts are packed into the {@code custom_*} rows and its leaf slot stores the row.
	 * An unresolved binding fails closed per leaf: {@code shape_volume[i].y} marks that leaf
	 * unresolved and {@code post/mask_coverage.fsh} zeroes only its coverage, never the whole mask;
	 * the shader also refuses to invert an empty result that an unresolved leaf could have caused,
	 * so a dropped binding can never expand coverage to full screen.
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
		// Fail closed per leaf: a leaf whose binding cannot be resolved is flagged unresolved
		// (shape_volume[i].y) and the shader drops only that leaf's coverage, so a bound screen rect
		// falling outside the view no longer zeroes a still-resolved world leaf beside it. The flag
		// is set below, per primitive.
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
		// ... through shape_volume), so the seven per-primitive rows must be emitted grouped by
		// field, not interleaved per primitive. Compute each primitive's rows first, then write them
		// field by field; an absent primitive leaves its rows zeroed, which the mask_count gate
		// ignores anyway.
		final float[][][] rows = new float[VFXMask.MAX_PRIMITIVES][7][4];
		for (int i = 0; i < VFXMask.MAX_PRIMITIVES; i++) {
			final VFXMaskPrimitive primitive = i < mask.primitives().size() ? mask.primitives().get(i) : null;
			if (primitive == null) {
				continue;
			}
			final boolean leafResolved = primitiveResolved(mask, primitive, i);
			final float operation = i == 0 ? 0.0F : mask.ops().get(i - 1).ordinal();
			final Integer customRow = primitive.family() == VFXMaskPrimitive.Family.CUSTOM ? customRows.get(primitive.customShape()) : null;
			// z = the animated falloff: the leaf's softness slot wins over the parse-time default
			// (the .soft slot is registered as a parameter, so keyframes/graph/setParam reach it).
			rows[i][0] = new float[]{primitive.kindCode(), operation, slotValue(mask, effect, primitive.softnessSlot(), primitive.softnessDefault()), primitive.field().ordinal()};
			rows[i][1] = new float[]{
				slotValue(mask, effect, primitive.fieldAmountSlot(), primitive.fieldAmountDefault()),
				slotValue(mask, effect, primitive.fieldScaleSlot(), primitive.fieldScaleDefault()),
				primitive.fieldSeed(),
				primitive.space() == VFXMaskSpace.WORLD ? 1.0F : (primitive.space() == VFXMaskSpace.DOME ? 2.0F : 0.0F)
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
			// x = world-volume mode (0 surface, 1 aura); only a world sphere/box ever sets 1.
			// y = 1 when this leaf's world binding could not be resolved (the shader drops its coverage).
			rows[i][6] = new float[]{primitive.volumeMode().code(), leafResolved ? 0.0F : 1.0F, 0.0F, 0.0F};
		}
		for (int row = 0; row < 7; row++) {
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

		// Per-leaf dynamic float data (vec4-packed, std140 array stride 16): primitive i owns the
		// contiguous slice [i * MAX_LEAF_DATA, (i + 1) * MAX_LEAF_DATA). A GLSL plugin reads it
		// live through vfx_mask_data(i * MAX_LEAF_DATA + j); the values are ordinary animatable
		// mask slots, so setParam/sendSetParam updates them every tick without recompiling the
		// shader variant (the variant is keyed by the plugin-id set, never by a param value).
		for (int i = 0; i < VFXMask.MAX_PRIMITIVES; i++) {
			// Only a custom leaf registers data slots; every other leaf writes a zero slice without
			// a per-slot lookup (the array still must be written positionally).
			final VFXMaskPrimitive primitive = i < mask.primitives().size() ? mask.primitives().get(i) : null;
			final boolean custom = primitive != null && primitive.family() == VFXMaskPrimitive.Family.CUSTOM;
			for (int g = 0; g < VFXMaskSlots.MAX_LEAF_DATA_VEC4; g++) {
				if (!custom) {
					builder.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
					continue;
				}
				final int base = g * 4;
				builder.putVec4(
					slotValue(mask, effect, VFXMaskSlots.data(i, base), 0.0F),
					slotValue(mask, effect, VFXMaskSlots.data(i, base + 1), 0.0F),
					slotValue(mask, effect, VFXMaskSlots.data(i, base + 2), 0.0F),
					slotValue(mask, effect, VFXMaskSlots.data(i, base + 3), 0.0F));
			}
		}
	}
}

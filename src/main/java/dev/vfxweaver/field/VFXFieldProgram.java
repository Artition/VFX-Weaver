package dev.vfxweaver.field;

import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
import dev.vfxweaver.graph.VFXGraphInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * A {@link VFXField} flattened into the fixed uniform program the shader interprets. Packing
 * happens once per definition instance; the per-frame write only resolves graph parameters and
 * pushes values, so it allocates nothing.
 *
 * <p><b>Contract (AGENTS.md UBO field-order rule):</b> {@link #write} emits fields in exactly the
 * order {@code assets/vfxweaver/shaders/include/field.glsl} declares them, and
 * {@code VFXShaderPrograms.FIELD_CONFIG_SIZE} sizes the same block. The three must change together
 * — std140 offsets are positional.
 */
public final class VFXFieldProgram {
	private final float[] leafFn;
	private final float[] leafSpace;
	private final float[] leafChannel;
	/** Shape primitive code per leaf ({@code circle}=0, {@code ellipse}=1, {@code rect}=2, {@code polygon}=3). */
	private final float[] leafPrimitive;
	/** Shape fill code per leaf ({@code solid}=0, {@code stroke}=1). */
	private final float[] leafFill;
	/** Literal value per packed parameter ({@link VFXField#MAX_PARAMS} per leaf). */
	private final float[] paramLiteral;
	/** Graph slot per packed parameter, or -1 for a literal. */
	private final int[] paramNode;
	/** True when the parameter is an integer and must be rounded after evaluation. */
	private final boolean[] paramInteger;
	private final float[] curveTimes;
	private final float[] curveValues;
	private final int curveCount;
	/** Post-order program: a value {@code <= -1} pushes leaf {@code (-value - 1)}; {@code >= 0} combines. */
	private final float[] program;
	private final int programLength;
	private final int leafCount;
	private final VFXFieldType outputType;
	private final boolean needsDepth;
	private final @Nullable String texture;
	private final String inputName;

	private VFXFieldProgram(final float[] leafFn, final float[] leafSpace, final float[] leafChannel, final float[] leafPrimitive, final float[] leafFill, final float[] paramLiteral, final int[] paramNode, final boolean[] paramInteger, final float[] curveTimes, final float[] curveValues, final int curveCount, final float[] program, final int programLength, final int leafCount, final VFXFieldType outputType, final boolean needsDepth, final @Nullable String texture, final String inputName) {
		this.leafFn = leafFn;
		this.leafSpace = leafSpace;
		this.leafChannel = leafChannel;
		this.leafPrimitive = leafPrimitive;
		this.leafFill = leafFill;
		this.paramLiteral = paramLiteral;
		this.paramNode = paramNode;
		this.paramInteger = paramInteger;
		this.curveTimes = curveTimes;
		this.curveValues = curveValues;
		this.curveCount = curveCount;
		this.program = program;
		this.programLength = programLength;
		this.leafCount = leafCount;
		this.outputType = outputType;
		this.needsDepth = needsDepth;
		this.texture = texture;
		this.inputName = inputName;
	}

	/**
	 * Flattens a field tree.
	 *
	 * @param inputName the effect input (for diagnostics)
	 * @param field     the parsed field
	 * @param graph     the definition's graph, or {@code null} (then no parameter may reference a node)
	 * @return the packed program, never {@code null}
	 */
	public static VFXFieldProgram of(final String inputName, final VFXField field, final @Nullable VFXGraph graph) {
		final float[] leafFn = new float[VFXField.MAX_LEAVES];
		final float[] leafSpace = new float[VFXField.MAX_LEAVES];
		final float[] leafChannel = new float[VFXField.MAX_LEAVES];
		final float[] leafPrimitive = new float[VFXField.MAX_LEAVES];
		final float[] leafFill = new float[VFXField.MAX_LEAVES];
		final float[] paramLiteral = new float[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS];
		final int[] paramNode = new int[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS];
		final boolean[] paramInteger = new boolean[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS];
		Arrays.fill(paramNode, -1);
		final float[] curveTimes = new float[VFXField.MAX_CURVE_POINTS];
		final float[] curveValues = new float[VFXField.MAX_CURVE_POINTS];
		final int[] curveCount = new int[1];
		final List<Float> program = new ArrayList<>(VFXField.MAX_PROGRAM);
		final int[] leafIndex = new int[1];
		final String[] texture = new String[1];
		flatten(field, leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		final float[] programArray = new float[VFXField.MAX_PROGRAM];
		// Unused slots are NaN so the shader stops at the first one (the program is not padded
		// with a valid op).
		Arrays.fill(programArray, Float.NaN);
		for (int i = 0; i < program.size(); i++) {
			programArray[i] = program.get(i);
		}
		return new VFXFieldProgram(leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount[0], programArray, program.size(), leafIndex[0], field.outputType(), field.needsDepth(), texture[0], inputName);
	}

	/**
	 * A neutral program with no leaves: the shader evaluates it to {@code vec3(1.0)}, so an effect
	 * that declares no field keeps its uniform value unchanged.
	 */
	public static VFXFieldProgram empty() {
		return new VFXFieldProgram(
			new float[VFXField.MAX_LEAVES], new float[VFXField.MAX_LEAVES], new float[VFXField.MAX_LEAVES],
			new float[VFXField.MAX_LEAVES], new float[VFXField.MAX_LEAVES],
			new float[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS], filled(VFXField.MAX_LEAVES * VFXField.MAX_PARAMS, -1), new boolean[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS],
			new float[VFXField.MAX_CURVE_POINTS], new float[VFXField.MAX_CURVE_POINTS], 0,
			new float[VFXField.MAX_PROGRAM], 0, 0, VFXFieldType.FLOAT, false, null, "");
	}

	private static int[] filled(final int length, final int value) {
		final int[] array = new int[length];
		Arrays.fill(array, value);
		return array;
	}

	private static void flatten(final VFXField field, final float[] leafFn, final float[] leafSpace, final float[] leafChannel, final float[] leafPrimitive, final float[] leafFill, final float[] paramLiteral, final int[] paramNode, final boolean[] paramInteger, final float[] curveTimes, final float[] curveValues, final int[] curveCount, final List<Float> program, final int[] leafIndex, final String[] texture, final @Nullable VFXGraph graph) {
		if (field.fn() != null) {
			final int leaf = leafIndex[0]++;
			leafFn[leaf] = field.fn().ordinal();
			leafSpace[leaf] = field.space() == VFXField.Space.WORLD ? 1.0F : 0.0F;
			leafChannel[leaf] = channelCode(field.channel());
			leafPrimitive[leaf] = primitiveCode(field.primitive());
			leafFill[leaf] = fillCode(field.fill());
			final List<String> names = field.fn().paramNames();
			for (int i = 0; i < names.size() && i < VFXField.MAX_PARAMS; i++) {
				final VFXGraphInput input = field.params().get(names.get(i));
				final int slot = leaf * VFXField.MAX_PARAMS + i;
				paramLiteral[slot] = input == null ? field.fn().defaultParam(names.get(i)) : input.literal();
				if (input != null && input.reference() && graph != null) {
					final Integer node = graph.indexOf(input.node());
					paramNode[slot] = node == null ? -1 : node;
				}
				paramInteger[slot] = field.fn().integerParam(names.get(i));
			}
			if (field.fn() == VFXFieldFn.CURVE) {
				final int count = Math.min(field.curveTimes().length, VFXField.MAX_CURVE_POINTS);
				System.arraycopy(field.curveTimes(), 0, curveTimes, 0, count);
				System.arraycopy(field.curveValues(), 0, curveValues, 0, count);
				curveCount[0] = count;
			}
			if (field.fn() == VFXFieldFn.TEXTURE && texture[0] == null) {
				texture[0] = field.texture();
			}
			program.add(-1.0F - leaf);
			return;
		}
		flatten(field.a(), leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		flatten(field.b(), leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		if (field.factor() != null) {
			flatten(field.factor(), leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		}
		program.add((float) field.op().ordinal());
	}

	private static float channelCode(final @Nullable String channel) {
		if (channel == null) {
			return 5.0F;
		}
		return switch (channel) {
			case "r" -> 0.0F;
			case "g" -> 1.0F;
			case "b" -> 2.0F;
			case "a" -> 3.0F;
			default -> 4.0F;
		};
	}

	private static float primitiveCode(final @Nullable String primitive) {
		if (primitive == null) {
			return 0.0F;
		}
		return switch (primitive) {
			case "ellipse" -> 1.0F;
			case "rect" -> 2.0F;
			case "polygon" -> 3.0F;
			default -> 0.0F;
		};
	}

	private static float fillCode(final @Nullable String fill) {
		return "stroke".equals(fill) ? 1.0F : 0.0F;
	}

	/**
	 * Writes the {@code FieldConfig} block in the shader's declaration order.
	 *
	 * @param out         the write target
	 * @param evaluator   per-frame graph evaluator, or {@code null} when no parameter references a node
	 * @param uniform     the input's uniform-domain value (already fade-weighted)
	 * @param depthValid  1 when the scene depth is valid for this pass, else 0
	 * @param invWidth    1 / output width
	 * @param invHeight   1 / output height
	 * @param invViewProj inverse (projection * viewRotation) with the camera translation applied
	 * @param camX        camera world X
	 * @param camY        camera world Y
	 * @param camZ        camera world Z
	 */
	public void write(final VFXFieldValueWriter out, final @Nullable VFXGraphEvaluator evaluator, final float uniform, final float depthValid, final float invWidth, final float invHeight, final Matrix4fc invViewProj, final float camX, final float camY, final float camZ) {
		out.putFloat(uniform);
		out.putFloat(depthValid);
		out.putFloat(this.leafCount);
		out.putVec4(this.leafFn[0], this.leafFn[1], this.leafFn[2], this.leafFn[3]);
		out.putVec4(this.leafSpace[0], this.leafSpace[1], this.leafSpace[2], this.leafSpace[3]);
		out.putVec4(this.leafChannel[0], this.leafChannel[1], this.leafChannel[2], this.leafChannel[3]);
		out.putVec4(this.leafPrimitive[0], this.leafPrimitive[1], this.leafPrimitive[2], this.leafPrimitive[3]);
		out.putVec4(this.leafFill[0], this.leafFill[1], this.leafFill[2], this.leafFill[3]);
		for (int leaf = 0; leaf < VFXField.MAX_LEAVES; leaf++) {
			for (int group = 0; group < VFXField.MAX_PARAMS / 4; group++) {
				out.putVec4(
					param(leaf, group * 4, evaluator),
					param(leaf, group * 4 + 1, evaluator),
					param(leaf, group * 4 + 2, evaluator),
					param(leaf, group * 4 + 3, evaluator)
				);
			}
		}
		out.putFloat(this.curveCount);
		for (int group = 0; group < 4; group++) {
			final int i = group * 2;
			out.putVec4(this.curveTimes[i], this.curveValues[i], this.curveTimes[i + 1], this.curveValues[i + 1]);
		}
		for (int i = 0; i < VFXField.MAX_PROGRAM; i++) {
			out.putFloat(this.program[i]);
		}
		out.putVec4(invViewProj.m00(), invViewProj.m01(), invViewProj.m02(), invViewProj.m03());
		out.putVec4(invViewProj.m10(), invViewProj.m11(), invViewProj.m12(), invViewProj.m13());
		out.putVec4(invViewProj.m20(), invViewProj.m21(), invViewProj.m22(), invViewProj.m23());
		out.putVec4(invViewProj.m30(), invViewProj.m31(), invViewProj.m32(), invViewProj.m33());
		out.putVec4(camX, camY, camZ, 0.0F);
		out.putVec4(invWidth, invHeight, 0.0F, 0.0F);
	}

	private float param(final int leaf, final int slot, final @Nullable VFXGraphEvaluator evaluator) {
		final int index = leaf * VFXField.MAX_PARAMS + slot;
		final float value;
		if (this.paramNode[index] >= 0 && evaluator != null) {
			value = evaluator.evaluateIndex(this.paramNode[index], this.paramLiteral[index]);
		} else {
			value = this.paramLiteral[index];
		}
		return this.paramInteger[index] ? Math.round(value) : value;
	}

	/** Number of function leaves in the program. */
	public float leafCount() {
		return this.leafCount;
	}

	/** The output type (Java-side only; the shader always computes a vec3). */
	public VFXFieldType outputType() {
		return this.outputType;
	}

	/** True when the shader needs the scene depth for this program. */
	public boolean needsDepth() {
		return this.needsDepth;
	}

	/** The texture resource id, or {@code null}. */
	public @Nullable String texture() {
		return this.texture;
	}

	/** True when a texture leaf is present. */
	public boolean textureLeaf() {
		return this.texture != null;
	}

	/** The effect input this program belongs to. */
	public String inputName() {
		return this.inputName;
	}

	/** Total program length (leaf pushes plus combine instructions). */
	public int programLength() {
		return this.programLength;
	}
}

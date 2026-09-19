package dev.vfxweaver.field;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The built-in per-pixel field functions (spec §2, §9 step 4). Each function has a fixed output
 * type and at most {@link VFXField#MAX_PARAMS} numeric parameters, addressed by name in JSON and
 * packed positionally into the shader's generic parameter vector.
 */
public enum VFXFieldFn {
	CONSTANT("constant", VFXFieldType.FLOAT, false, false, false, false, List.of("value"), new float[]{1.0F}),
	NOISE("noise", VFXFieldType.FLOAT, true, false, false, false, List.of("scale", "octaves", "gain", "lacunarity"), new float[]{1.0F, 1.0F, 0.5F, 2.0F}),
	// The shared shape primitive set: the v1 field function uses the 2D screen kinds; the same
	// library also owns the 3D world helpers (sphere/box) consumed where a 3D coordinate exists.
	SHAPE("shape", VFXFieldType.FLOAT, true, false, false, false,
		List.of("center_x", "center_y", "rotation", "radius", "radius_x", "radius_y", "half_width", "half_height", "corner_radius", "sides", "stroke_width", "softness", "repeat_x", "repeat_y"),
		new float[]{0.5F, 0.5F, 0.0F, 0.35F, 0.35F, 0.35F, 0.25F, 0.25F, 0.0F, 6.0F, 0.05F, 0.01F, 1.0F, 1.0F}),
	GRADIENT("gradient", VFXFieldType.FLOAT, true, false, false, false, List.of("angle", "offset", "scale", "softness"), new float[]{0.0F, 0.0F, 1.0F, 0.0F}),
	CURVE("curve", VFXFieldType.FLOAT, true, false, false, true, List.of("scale"), new float[]{1.0F}),
	TEXTURE("texture", VFXFieldType.VEC3, true, true, false, false, List.of("scale_x", "scale_y", "offset_x", "offset_y"), new float[]{1.0F, 1.0F, 0.0F, 0.0F}),
	// The four functions below read the scene depth directly (see `geom` in field.glsl): they must
	// mark the depth requirement so VFXTimeline.fieldNeedsDepth() is correct and the layer-0
	// warning fires. `world_pos` is not spatial (`space` is meaningless — it is always world).
	DEPTH("depth", VFXFieldType.FLOAT, false, false, true, false, List.of("near", "far"), new float[]{0.0F, 1.0F}),
	DEPTH_GRADIENT("depth_gradient", VFXFieldType.FLOAT, false, false, true, false, List.of("near", "far"), new float[]{0.0F, 1.0F}),
	NORMAL_FACING("normal_facing", VFXFieldType.FLOAT, false, false, true, false, List.of("axis_x", "axis_y", "axis_z", "threshold"), new float[]{0.0F, 1.0F, 0.0F, 0.5F}),
	SCREEN_UV("screen_uv", VFXFieldType.VEC2, false, false, false, false, List.of(), new float[0]),
	WORLD_POS("world_pos", VFXFieldType.VEC3, false, false, true, false, List.of(), new float[0]);

	private final String id;
	private final VFXFieldType outputType;
	private final boolean spatial;
	private final boolean hasChannel;
	private final boolean needsDepth;
	private final boolean hasCurve;
	private final List<String> paramNames;
	private final float[] defaultParams;

	VFXFieldFn(final String id, final VFXFieldType outputType, final boolean spatial, final boolean hasChannel, final boolean needsDepth, final boolean hasCurve, final List<String> paramNames, final float[] defaultParams) {
		this.id = id;
		this.outputType = outputType;
		this.spatial = spatial;
		this.hasChannel = hasChannel;
		this.needsDepth = needsDepth;
		this.hasCurve = hasCurve;
		this.paramNames = paramNames;
		this.defaultParams = defaultParams;
	}

	/** The datapack spelling of this function. */
	public String id() {
		return this.id;
	}

	/** The type this function produces before any channel selection. */
	public VFXFieldType outputType() {
		return this.outputType;
	}

	/** True when {@code space} is meaningful for this function (spec §2). */
	public boolean spatial() {
		return this.spatial;
	}

	/** True when {@code channel} is meaningful for this function. */
	public boolean hasChannel() {
		return this.hasChannel;
	}

	/** True when this function reads the scene depth. */
	public boolean needsDepth() {
		return this.needsDepth;
	}

	/** True when this function needs a {@code points} array. */
	public boolean hasCurve() {
		return this.hasCurve;
	}

	/** True when this function samples a texture. */
	public boolean hasTexture() {
		return this == TEXTURE;
	}

	/** True when this function selects a shape primitive and a fill mode. */
	public boolean hasShape() {
		return this == SHAPE;
	}

	/** Numeric parameter names, in packing order. */
	public List<String> paramNames() {
		return this.paramNames;
	}

	/**
	 * The packing slot of a numeric parameter.
	 *
	 * @return the slot, or {@code -1} when {@code name} is not a parameter of this function
	 */
	public int paramIndex(final String name) {
		return this.paramNames.indexOf(name);
	}

	/**
	 * True when the parameter is an integer (rounded after graph evaluation, spec §2).
	 */
	public boolean integerParam(final String name) {
		if (this == NOISE) {
			return "octaves".equals(name);
		}
		if (this == SHAPE) {
			return "sides".equals(name) || "repeat_x".equals(name) || "repeat_y".equals(name);
		}
		return false;
	}

	/**
	 * The default of a numeric parameter.
	 *
	 * @return the default, or {@code 0} when {@code name} is not a parameter of this function
	 */
	public float defaultParam(final String name) {
		final int index = paramIndex(name);
		return index < 0 ? 0.0F : this.defaultParams[index];
	}

	/**
	 * Resolves a function from its datapack spelling. {@code grid}, {@code ring} and {@code radial}
	 * are not functions: a grid is a {@code shape} with {@code repeat}, a ring is a {@code shape}
	 * with {@code primitive: ellipse} and {@code fill: stroke}.
	 *
	 * @param name raw string, e.g. {@code "noise"}
	 * @return the matching function, or {@code null} when unknown
	 */
	public static @Nullable VFXFieldFn fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXFieldFn fn : values()) {
			if (fn.id.equals(normalized)) {
				return fn;
			}
		}
		return null;
	}
}

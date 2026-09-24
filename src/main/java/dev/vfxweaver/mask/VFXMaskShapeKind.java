package dev.vfxweaver.mask;

import java.util.List;
import java.util.Locale;

/**
 * A mask leaf shape (shared shape contract). The {@link #ordinal()} is uploaded to the coverage
 * prepass and is the shared shape library's dispatch id; it must stay stable: CIRCLE=0, ELLIPSE=1,
 * RECT=2, POLYGON=3, SPHERE=4, BOX=5. The two 3D kinds are world-only and are ordinary 3D distance
 * functions against the position reconstructed from depth. {@link #SKY} is the depth-gated
 * far-depth leaf and is dome-only; the 2D kinds may also be authored in dome space.
 *
 * <p>The signed-distance implementation of every kind lives in the shared shape/field library
 * ({@code shaders/include/field.glsl}); this enum only mirrors the JSON names, the default space,
 * the per-kind numeric parameter names/defaults and the world-only flag. Do not add distance math
 * here. The block-geometry and custom-shape families are not enum kinds (see
 * {@link VFXMaskPrimitive}); they carry a {@link VFXMaskBlockSelection} or a registry id.
 */
public enum VFXMaskShapeKind {
	CIRCLE("circle", VFXMaskSpace.SCREEN, false, List.of("radius"), new float[]{0.5F}),
	ELLIPSE("ellipse", VFXMaskSpace.SCREEN, false, List.of("radius_x", "radius_y"), new float[]{0.5F, 0.3F}),
	RECT("rect", VFXMaskSpace.SCREEN, false, List.of("half_width", "half_height", "corner_radius"), new float[]{0.5F, 0.5F, 0.0F}),
	POLYGON("polygon", VFXMaskSpace.SCREEN, false, List.of("radius", "sides"), new float[]{0.5F, 6.0F}),
	SPHERE("sphere", VFXMaskSpace.WORLD, true, List.of("radius"), new float[]{8.0F}),
	BOX("box", VFXMaskSpace.WORLD, true, List.of("half_width", "half_height", "half_depth"), new float[]{4.0F, 4.0F, 4.0F}),
	SKY("sky", VFXMaskSpace.DOME, true, List.of(), new float[]{});

	private final String id;
	private final VFXMaskSpace space;
	private final boolean worldOnly;
	private final List<String> parameterNames;
	private final float[] parameterDefaults;

	VFXMaskShapeKind(final String id, final VFXMaskSpace space, final boolean worldOnly, final List<String> parameterNames, final float[] parameterDefaults) {
		this.id = id;
		this.space = space;
		this.worldOnly = worldOnly;
		this.parameterNames = parameterNames;
		this.parameterDefaults = parameterDefaults;
	}

	/** The datapack spelling of this shape. */
	public String id() {
		return this.id;
	}

	/** The space a leaf defaults to when neither it nor the top-level mask names one. */
	public VFXMaskSpace space() {
		return this.space;
	}

	/** True when the shape is only meaningful in world space (the 3D volumes). */
	public boolean worldOnly() {
		return this.worldOnly;
	}

	/** The per-kind numeric parameter names, in the shared library's packing order. */
	public List<String> parameterNames() {
		return this.parameterNames;
	}

	/** The per-kind default for each name in {@link #parameterNames()}. */
	public float[] parameterDefaults() {
		return this.parameterDefaults.clone();
	}

	/**
	 * Resolves a shape from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "polygon"}
	 * @return the matching shape, or {@code null} when unknown
	 */
	public static VFXMaskShapeKind fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXMaskShapeKind kind : values()) {
			if (kind.id.equals(normalized)) {
				return kind;
			}
		}
		return null;
	}
}

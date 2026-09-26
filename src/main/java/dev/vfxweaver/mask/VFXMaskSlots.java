package dev.vfxweaver.mask;

/**
 * Reserved numeric slot names for mask parameters. Every numeric mask leaf is lowered to one of
 * these names and registered as an effect parameter, so a mask number animates through the same
 * {@code { "from": "<node>" }} path as any effect input (spec §4). The {@code mask.} prefix is
 * reserved: a user parameter with that prefix would be shadowed, not merged.
 *
 * <p>The counts here mirror {@code post/mask_coverage.fsh} and {@code VFXMaskUniforms}; change them
 * together.
 */
public final class VFXMaskSlots {
	/** Mirrors {@code VFXMask.MAX_PRIMITIVES}. */
	public static final int MAX_PRIMITIVES = 8;
	/** The per-leaf numeric parameter cap (mirrors the shared shape library's packing). */
	public static final int MAX_LEAF_PARAMS = 8;
	/**
	 * The per-leaf dynamic float-data cap: {@code mask.p<N>.d0 .. d(K-1)}. Delivered to a GLSL
	 * plugin through the coverage {@code shape_data} UBO array (a {@code vec4[]}-packed layout, so
	 * the array costs {@code MAX_PRIMITIVES * MAX_LEAF_DATA} floats = 1 KiB) and read with the
	 * {@code vfx_mask_data(index)} helper. 32 floats let one plugin leaf drive ten 3-float
	 * primitives (centre + radius), well past the eight animatable {@code p<J>} params.
	 */
	public static final int MAX_LEAF_DATA = 32;
	/** The {@code vec4} count the {@link #MAX_LEAF_DATA} floats are packed into (std140 array stride). */
	public static final int MAX_LEAF_DATA_VEC4 = MAX_LEAF_DATA / 4;

	/** The centre component ({@code x}/{@code y}/{@code z}) slot of primitive {@code i}. */
	public static String center(final int i, final String axis) {
		return "mask.p" + i + ".center_" + axis;
	}

	/**
	 * The dynamic float-data slot {@code j} of primitive {@code i} ({@code mask.p<N>.d<J>}), read by
	 * a GLSL plugin through {@code vfx_mask_data(i * MAX_LEAF_DATA + j)}.
	 */
	public static String data(final int i, final int j) {
		return "mask.p" + i + ".d" + j;
	}

	/** The rotation slot of primitive {@code i} (degrees, shared shape convention). */
	public static String rotation(final int i) {
		return "mask.p" + i + ".rotation";
	}

	/** The generic shape parameter slot {@code j} (0-based, per kind) of primitive {@code i}. */
	public static String param(final int i, final int j) {
		return "mask.p" + i + ".p" + j;
	}

	/** The edge-falloff slot of primitive {@code i}. */
	public static String soft(final int i) {
		return "mask.p" + i + ".soft";
	}

	/**
	 * The occlusion-ramp width slot of primitive {@code i}, in world blocks. Only an aura leaf that
	 * authors {@code "occlusion_softness"} gets one; otherwise the ramp keeps following the leaf's own
	 * {@link #soft(int)} (the pre-existing behaviour, animation included).
	 */
	public static String occSoft(final int i) {
		return "mask.p" + i + ".occ_soft";
	}

	/** The stroke-width slot of primitive {@code i}. */
	public static String stroke(final int i) {
		return "mask.p" + i + ".stroke";
	}

	/** The field amount slot of primitive {@code i} (distance-space perturbation). */
	public static String fieldAmount(final int i) {
		return "mask.p" + i + ".field_amount";
	}

	/** The field scale slot of primitive {@code i}. */
	public static String fieldScale(final int i) {
		return "mask.p" + i + ".field_scale";
	}

	private VFXMaskSlots() {
	}
}

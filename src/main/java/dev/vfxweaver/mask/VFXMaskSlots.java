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

	/** The centre component ({@code x}/{@code y}/{@code z}) slot of primitive {@code i}. */
	public static String center(final int i, final String axis) {
		return "mask.p" + i + ".center_" + axis;
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

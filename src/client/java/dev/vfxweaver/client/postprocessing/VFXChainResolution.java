package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.util.VFXSettings;

/**
 * The post-chain resolution policy (spec &sect;8): whether runs of scalable passes render at half
 * resolution, wrapped in a downsample/upsample conversion pair.
 *
 * <p>Stateless. {@link #scale()} reads {@link VFXSettings#chainResolution()}, which is {@code 0.5F}
 * by default (on) and only accepts exactly {@code 1.0} or {@code 0.5}; {@code 1.0} disables the
 * half-resolution runs. Reading the settings on every call means a change applies without a restart.
 */
public final class VFXChainResolution {
	/**
	 * @return the chain resolution scale: {@code 1.0F} (off) or {@code 0.5F} (scalable runs at half
	 *         resolution)
	 */
	public static float scale() {
		return VFXSettings.get().chainResolution();
	}

	/** @return true when scalable runs render at half resolution ({@link #scale()} below {@code 1.0F}) */
	public static boolean half() {
		return scale() < 1.0F;
	}

	/**
	 * The minimum summed tap count that justifies wrapping a scalable run in a downsample/upsample
	 * pair (spec &sect;4).
	 *
	 * <p>The conversion pair costs roughly 2.5-3 full-resolution single-tap equivalents (Down is 4
	 * fetches over a quarter of the fragments; Up is 4 fetches from an L2-resident source, nearer
	 * 1.5), so a scaled run only becomes cheaper than its full-resolution equivalent at about 4
	 * taps. It is a <b>minimum to justify the conversion pair, not a budget</b>: any run whose tap
	 * sum reaches this qualifies, of any length, so three adjacent scalable stages merge into one
	 * run. It is a single named constant calibrated by the per-effect bisect, because a paper
	 * estimate of fetch cost is exactly what hardware disagrees with.
	 */
	public static final int MIN_RUN_TAPS = 8;

	private VFXChainResolution() {
	}
}

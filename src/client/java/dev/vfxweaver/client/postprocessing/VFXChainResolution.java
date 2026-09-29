package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.util.VFXLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The post-chain resolution policy (spec &sect;8): whether runs of scalable passes render at half
 * resolution, wrapped in a downsample/upsample conversion pair.
 *
 * <p>Stateless. {@link #SCALE} is read once from {@code -Dvfxweaver.chainres} (default
 * {@code "0.5"}, i.e. on); the only accepted values are exactly {@code "1.0"} and {@code "0.5"}.
 * {@code "1.0"} disables the half-resolution runs; any other value is refused with a warn-once and
 * treated as the default {@code "0.5"}, so a typo cannot silently disable the path.
 */
public final class VFXChainResolution {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/post");

	/**
	 * The chain resolution scale: {@code 1.0F} (off) or {@code 0.5F} (scalable runs at half
	 * resolution), from {@code -Dvfxweaver.chainres}.
	 */
	public static final float SCALE = parseScale();

	/** True when scalable runs render at half resolution ({@link #SCALE} below {@code 1.0F}). */
	public static final boolean HALF = SCALE < 1.0F;

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

	private static float parseScale() {
		final String raw = System.getProperty("vfxweaver.chainres", "0.5");
		if ("1.0".equals(raw)) {
			return 1.0F;
		}
		if ("0.5".equals(raw)) {
			return 0.5F;
		}
		VFXLog.warnOnce(LOGGER, "chainres:value", "Unknown vfxweaver.chainres value '{}'; only 1.0 and 0.5 are accepted, using the default 0.5", raw);
		return 0.5F;
	}

	private VFXChainResolution() {
	}
}

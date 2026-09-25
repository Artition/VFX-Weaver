package dev.vfxweaver.util;

import java.util.List;

/**
 * The MC-free combination maths for the {@code fog_modifier} effect.
 *
 * <p>The effect is a <b>value modifier at the source of the vanilla fog</b> (the way
 * {@code fov_modifier} modifies the FOV), not a post-processing pass: the {@code VFXEffectManager}
 * collects every active {@code fog_modifier} instance into a {@link Contribution} and the client
 * mixin feeds the combined {@link Result} back into the vanilla fog before its uniforms are written.
 * Keeping the arithmetic here (no Minecraft types) lets it be unit-checked headlessly.
 *
 * <p>Scales combine <b>additively</b> like the FOV delta: {@code scale = 1 + Σ((s_i − 1) · w_i)},
 * so two effects that each pull the fog to 2x give 3x, not 4x. Colour is the <b>weighted average</b>
 * of the authored colours, blended from the vanilla colour by the clamped total weight; each
 * instance's colour weight is scaled by {@code fog_color_amount} ({@code 0} leaves the colour
 * untouched, unauthored = the full authored colour). Both are sums, so the result is independent of
 * the order the effects are iterated in.
 *
 * <p>Absent colour params must leave the vanilla fog untouched: pass the vanilla channel values in
 * and an unauthored ({@code NaN}) component is returned unchanged with {@link Result#hasColor()} off.
 */
public final class VFXFogModifier {
	private VFXFogModifier() {
	}

	/**
	 * One active {@code fog_modifier} instance's contribution. {@code startScale}/{@code endScale}
	 * are neutral at {@code 1.0}; a colour component of {@code NaN} means "not authored";
	 * {@code colorAmount} is how far the colour moves from vanilla in {@code [0, 1]} ({@code NaN} =
	 * unauthored, treated as {@code 1.0}); {@code weight} is the instance's fade weight in
	 * {@code [0, 1]} (or more, for stacked live edits).
	 */
	public record Contribution(float startScale, float endScale, float r, float g, float b, float colorAmount, float weight) {
	}

	/**
	 * The combined per-frame fog modification. {@code active} is false only when no instance
	 * contributed; {@code hasColor} is true only when an authored colour actually moved the vanilla
	 * colour. {@code r}/{@code g}/{@code b} are the final clamped channels (vanilla when not authored).
	 */
	public record Result(boolean active, float startScale, float endScale, boolean hasColor, float r, float g, float b) {
	}

	/**
	 * Combines the active contributions.
	 *
	 * @param contributions the active instances (empty means the fog is untouched)
	 * @param vanillaR the vanilla fog colour red channel
	 * @param vanillaG the vanilla fog colour green channel
	 * @param vanillaB the vanilla fog colour blue channel
	 * @return the combined modification
	 */
	public static Result combine(final List<Contribution> contributions, final float vanillaR, final float vanillaG, final float vanillaB) {
		if (contributions == null || contributions.isEmpty()) {
			return new Result(false, 1.0F, 1.0F, false, vanillaR, vanillaG, vanillaB);
		}

		float start = 1.0F;
		float end = 1.0F;
		float sumR = 0.0F;
		float sumG = 0.0F;
		float sumB = 0.0F;
		float weightR = 0.0F;
		float weightG = 0.0F;
		float weightB = 0.0F;
		for (final Contribution c : contributions) {
			start += (c.startScale() - 1.0F) * c.weight();
			end += (c.endScale() - 1.0F) * c.weight();
			// fog_color_amount scales the per-instance COLOUR weight only: 0 = vanilla (never
			// touched), unauthored (NaN) = the full authored colour. Accumulating (not sequencing)
			// keeps the blend order-independent. The distance scales are untouched.
			final float amount = Float.isNaN(c.colorAmount())
				? 1.0F
				: Math.min(1.0F, Math.max(0.0F, c.colorAmount()));
			final float w = c.weight() * amount;
			if (!Float.isNaN(c.r())) {
				sumR += c.r() * w;
				weightR += w;
			}
			if (!Float.isNaN(c.g())) {
				sumG += c.g() * w;
				weightG += w;
			}
			if (!Float.isNaN(c.b())) {
				sumB += c.b() * w;
				weightB += w;
			}
		}

		final float r = blend(vanillaR, sumR, weightR);
		final float g = blend(vanillaG, sumG, weightG);
		final float b = blend(vanillaB, sumB, weightB);
		final boolean hasColor = weightR > 0.0F || weightG > 0.0F || weightB > 0.0F;
		return new Result(true, start, end, hasColor, r, g, b);
	}

	/** Weighted-average channel blended from the vanilla value by the clamped total weight. */
	private static float blend(final float vanilla, final float weightedSum, final float weight) {
		if (weight <= 0.0F) {
			return vanilla;
		}
		final float target = weightedSum / weight;
		if (!Float.isFinite(target)) {
			return vanilla;
		}
		final float factor = Math.min(1.0F, weight);
		return Math.min(1.0F, Math.max(0.0F, vanilla + (target - vanilla) * factor));
	}

	/**
	 * Pulls a scaled fog <b>start</b> just below its <b>end</b> when scaling inverted the pair.
	 * Vanilla thick fog may legitimately start negative, so only the inverted case
	 * ({@code start >= end}) is corrected — a GLSL fog range with {@code start > end} is undefined.
	 *
	 * @param start the scaled fog start distance
	 * @param end the scaled fog end distance
	 * @return {@code start} unchanged when healthy, otherwise the float just below {@code end}
	 */
	public static float pullBelow(final float start, final float end) {
		return start >= end ? Math.nextDown(end) : start;
	}
}

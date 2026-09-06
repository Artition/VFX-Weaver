package dev.vfxweaver.client.shake;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import net.minecraft.util.Mth;

/**
 * Computes the combined camera roll from active {@code camera_roll} effects: a fixed dutch
 * angle plus an optional slow sinusoidal wobble. Applied right after the shake offsets so the
 * two compose on the same camera transform.
 */
public final class VFXCameraRoll {
	private VFXCameraRoll() {
	}

	/**
	 * Sums the roll (in degrees) contributed by all active {@code camera_roll} effects, each
	 * weighted by its fade weight. Wobble is a sinusoidal sway around the base angle.
	 *
	 * @param manager the effect manager
	 * @return total roll in degrees (already wrapped)
	 */
	public static float compute(final VFXEffectManager manager) {
		float roll = 0.0F;
		for (VFXActiveEffect effect : manager.getActiveCameraRolls()) {
			float weight = effect.getWeight();
			float t = effect.getElapsed() / 20.0F;
			float amplitude = effect.getParam("angle", 0.0F);
			float wobble = effect.getParam("wobble", 0.0F);
			float wobbleSpeed = effect.getParam("wobble_speed", 0.2F);
			roll += (amplitude + wobble * (float) Math.sin(t * wobbleSpeed * 6.2831853)) * weight;
		}
		return Mth.wrapDegrees(roll);
	}
}
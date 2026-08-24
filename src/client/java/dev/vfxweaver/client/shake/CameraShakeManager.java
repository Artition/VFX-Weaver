package dev.vfxweaver.client.shake;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.noise.SimplexNoise;
import net.minecraft.util.Mth;

/**
 * Computes camera shake offsets from the active {@code camera_shake} effects. Each effect
 * contributes a position offset (x/y/z blocks) and a rotation offset (yaw/pitch/roll degrees),
 * both modulated by a simplex noise sample and a smooth fade-out envelope.
 */
public final class CameraShakeManager {
	/** Default noise frequency when the {@code frequency} parameter is not set. */
	private static final float DEFAULT_FREQUENCY = 7.0F;

	private CameraShakeManager() {
	}

	/**
	 * Computes the combined shake offset for the given effect manager at the current time.
	 */
	public static Offset compute(final VFXEffectManager manager) {
		double dx = 0.0;
		double dy = 0.0;
		double dz = 0.0;
		float yaw = 0.0F;
		float pitch = 0.0F;
		float roll = 0.0F;

		for (VFXActiveEffect effect : manager.getActiveShakes()) {
			Offset offset = sample(effect);
			dx += offset.dx();
			dy += offset.dy();
			dz += offset.dz();
			yaw += offset.yaw();
			pitch += offset.pitch();
			roll += offset.roll();
		}

		return new Offset(dx, dy, dz, yaw, pitch, roll);
	}

	/**
	 * Samples one shake effect.
	 */
	public static Offset sample(final VFXActiveEffect effect) {
		float elapsedTicks = effect.getElapsed();
		double timeSeconds = elapsedTicks / 20.0;
		float envelope = EasingType.SMOOTHSTEP.apply(1.0F - effect.getProgress());
		// A per-instance seed shifts the noise domain, so every /vfx play of the same shake
		// produces a different, non-repeating pattern. The seed is masked to 32 bits: a raw
		// 64-bit seed * 0.0001 overflows fastFloor's int cast and collapses every noise sample
		// to exactly zero, which silently kills the shake.
		double phase = effect.getStartTime() + (effect.getInstanceSeed() & 0xFFFFFFFFL) * 0.0001;

		// Noise frequency (oscillations per second); clamped to a small positive value so a zero
		// frequency cannot freeze every sample onto one noise point.
		double frequency = Math.max(effect.getParam("frequency", DEFAULT_FREQUENCY), 0.01F);

		double n1 = SimplexNoise.noise(timeSeconds * frequency, phase, 0.0);
		double n2 = SimplexNoise.noise(0.0, timeSeconds * frequency, phase);
		double n3 = SimplexNoise.noise(phase, 0.0, timeSeconds * frequency);
		double n4 = SimplexNoise.noise(timeSeconds * frequency + 1000.0, phase, 0.0);
		double n5 = SimplexNoise.noise(0.0, timeSeconds * frequency + 1000.0, phase);
		double n6 = SimplexNoise.noise(phase, 0.0, timeSeconds * frequency + 1000.0);

		float amplitudeX = effect.getParam("amplitude_x", 0.0F);
		float amplitudeY = effect.getParam("amplitude_y", 0.0F);
		float amplitudeZ = effect.getParam("amplitude_z", 0.0F);
		float yawAmp = effect.getParam("yaw", 0.0F);
		float pitchAmp = effect.getParam("pitch", 0.0F);
		float rollAmp = effect.getParam("roll", 0.0F);

		return new Offset(
			amplitudeX * envelope * n1,
			amplitudeY * envelope * n2,
			amplitudeZ * envelope * n3,
			Mth.wrapDegrees(yawAmp * envelope * (float) n4),
			Mth.wrapDegrees(pitchAmp * envelope * (float) n5),
			Mth.wrapDegrees(rollAmp * envelope * (float) n6)
		);
	}

	/**
	 * Combined shake offsets: position offset in blocks and rotation offset in degrees.
	 */
	public record Offset(double dx, double dy, double dz, float yaw, float pitch, float roll) {
	}
}

package dev.vfxweaver.client.audio;

import java.util.Random;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One looping noise voice: mono 16-bit PCM white noise synthesised straight into a
 * {@link SourceDataLine}, so a noise needs no audio file in any resource pack and works
 * identically on every supported Minecraft version.
 *
 * <p>Each sample starts from white noise (a fresh uniform draw per hold step). The
 * {@code brightness} param blends toward a softened copy: {@code 1} is crisp white noise,
 * {@code 0} is the one-pole lowpassed version. The {@code jitter}/{@code jitter_rate}
 * params drive a per-tick random walk: the playback-rate wander (how fast new white draws
 * replace the held sample, mean-reverted to 1x) and a gentler volume wander. A changed
 * {@code seed} reseeds the walk, so the texture is reproducible per authored value.
 *
 * <p>Clock semantics mirror {@link VFXHumVoice} exactly: each {@link #tick} emits only the
 * samples the shared effect clock advanced since the previous tick, so a paused game emits
 * nothing and the line drains on its own, and a replay seek bigger than
 * {@value #MAX_SAMPLES_PER_CALL} samples resyncs silently instead of bursting.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). The per-frame chunk (one game tick at most) is far
 * smaller than the line buffer, so {@code write} never blocks the frame.
 */
final class VFXNoiseVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.25F);
	/** One-pole softening toward the mellow copy {@code brightness} blends from. */
	private static final float SOFT_COEFF = 0.15F;

	private SourceDataLine line;
	private boolean failed;
	private float lastClock = Float.NaN;
	private long seed = Long.MIN_VALUE;
	private final Random random = new Random();
	private float pitchWalk = 1.0F;
	private float volWalk = 1.0F;
	private double holdPhase;
	private float white;
	private float soft;
	private float currentGain;

	/**
	 * Emits the samples for the clock interval ending now. A zero gain with a settled line
	 * emits nothing.
	 *
	 * @param clockTicks  the shared effect clock, in ticks
	 * @param targetGain  the linear gain for this frame (already weighted and attenuated)
	 * @param brightness  the crisp/soft blend, {@code 1} white, {@code 0} lowpassed
	 * @param jitter      the random-walk step size for the rate/volume wander
	 * @param jitterRate  the wander speed multiplier
	 * @param seed        the walk seed; a change reseeds and restarts the texture
	 */
	void tick(final float clockTicks, final float targetGain, final float brightness,
			final float jitter, final float jitterRate, final float seedParam) {
		if (this.failed) {
			return;
		}
		if (Float.isNaN(this.lastClock)) {
			this.lastClock = clockTicks;
			return;
		}
		float elapsed = clockTicks - this.lastClock;
		this.lastClock = clockTicks;
		int samples = (int) (elapsed * SAMPLES_PER_TICK);
		if (samples <= 0 || samples > MAX_SAMPLES_PER_CALL) {
			return;
		}
		float gain = Float.isFinite(targetGain) ? Math.max(0.0F, targetGain) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			return;
		}
		if (!this.ensureLine()) {
			return;
		}
		final long bits = Float.isFinite(seedParam) ? (long) seedParam : 0L;
		if (bits != this.seed) {
			this.seed = bits;
			this.random.setSeed(bits);
			this.pitchWalk = 1.0F;
			this.volWalk = 1.0F;
			this.holdPhase = 0.0;
			this.white = 0.0F;
			this.soft = 0.0F;
		}
		final float bright = Float.isFinite(brightness) ? Math.min(1.0F, Math.max(0.0F, brightness)) : 0.5F;
		final float step = Math.max(0.0F, Float.isFinite(jitter) ? jitter : 0.0F)
			* Math.max(0.0F, Float.isFinite(jitterRate) ? jitterRate : 0.0F);
		if (step > 0.0F) {
			this.pitchWalk += (this.random.nextFloat() * 2.0F - 1.0F) * step;
			this.pitchWalk = Math.min(4.0F, Math.max(0.25F, this.pitchWalk));
			this.pitchWalk += (1.0F - this.pitchWalk) * 0.02F;
			this.volWalk += (this.random.nextFloat() * 2.0F - 1.0F) * step * 0.5F;
			this.volWalk = Math.min(2.0F, Math.max(0.0F, this.volWalk));
			this.volWalk += (1.0F - this.volWalk) * 0.02F;
		}
		final float frameGain = gain * this.volWalk;
		byte[] out = new byte[samples * 2];
		for (int i = 0; i < samples; i++) {
			this.holdPhase += this.pitchWalk;
			while (this.holdPhase >= 1.0) {
				this.holdPhase -= 1.0;
				this.white = this.random.nextFloat() * 2.0F - 1.0F;
			}
			this.soft += (this.white - this.soft) * SOFT_COEFF;
			float s = this.soft + (this.white - this.soft) * bright;
			float g = this.currentGain + (frameGain - this.currentGain) * (i + 1) / samples;
			short pcm = (short) (s * g * Short.MAX_VALUE);
			out[i * 2] = (byte) pcm;
			out[i * 2 + 1] = (byte) (pcm >> 8);
		}
		this.currentGain = frameGain;
		this.line.write(out, 0, out.length);
	}

	/**
	 * Releases the line. Abrupt by design (mirrors {@code VFXWindowManager.closeAll}): a
	 * faded-out effect is already silent when it is removed, and a {@code stopAll} cuts
	 * immediately.
	 */
	void close() {
		if (this.line != null) {
			try {
				this.line.stop();
			} catch (Exception ignored) {
			}
			try {
				this.line.close();
			} catch (Exception ignored) {
			}
			this.line = null;
		}
		this.currentGain = 0.0F;
	}

	private boolean ensureLine() {
		if (this.line != null) {
			return true;
		}
		try {
			AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
			SourceDataLine fresh = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
			fresh.open(format, BUFFER_BYTES);
			fresh.start();
			this.line = fresh;
			return true;
		} catch (Exception e) {
			// Headless JVM or no mixer: stay muted for the rest of the session, never loud.
			this.failed = true;
			LOGGER.warn("VFX noise voice disabled: no audio line ({})", e.toString());
			return false;
		}
	}
}

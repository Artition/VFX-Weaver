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
 * params drive a random walk: the playback-rate wander (how fast new white draws replace the
 * held sample, mean-reverted to 1x) and a gentler volume wander. A changed {@code seed}
 * reseeds the walk, so the texture is reproducible per authored value.
 *
 * <p>The voice is driven by the {@link VFXAudioEngine} on the audio thread, in fixed blocks,
 * so the frame rate does not reach the sound. The render thread only publishes the
 * parameters ({@link #setParams}); the texture, the walks, the gain ramp and the line belong
 * to the audio thread.
 *
 * <p>The gain ramps from the previous block's end to the published one, so a change (a fade,
 * a distance step) is a ramp and not a step - a step reads as a click. A voice that resumes
 * after the line ran dry fades in over 5 ms, and a voice whose effect ended fades out and
 * releases its line once it has drained.
 */
final class VFXNoiseVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.5F);
	/** The fade-in after the line ran dry, in samples (5 ms). */
	private static final int RESUME_FADE = 110;
	/** How far ahead of its playhead the line stays, so a stall cannot starve it. */
	private static final int TARGET_AHEAD = (int) (SAMPLE_RATE * 0.06F);
	/** One-pole softening toward the mellow copy {@code brightness} blends from. */
	private static final float SOFT_COEFF = 0.15F;

	private SourceDataLine line;
	private boolean failed;
	private long seed = Long.MIN_VALUE;
	private final Random random = new Random();
	private float pitchWalk = 1.0F;
	private float volWalk = 1.0F;
	private double holdPhase;
	private float white;
	private float soft;
	private float currentGain;
	/** The parameters the render thread publishes; the audio thread reads them. */
	private volatile float targetGain;
	private volatile float targetBrightness = 0.5F;
	private volatile float targetJitter;
	private volatile float targetJitterRate;
	private volatile float targetSeed;
	/** True when the effect ended: the sound fades out and the line is released. */
	private volatile boolean closing;

	/**
	 * Publishes the parameters for the next block. Called from the render thread.
	 *
	 * @param gain       the linear gain for this frame (already weighted and attenuated)
	 * @param brightness the crisp/soft blend, {@code 1} white, {@code 0} lowpassed
	 * @param jitter     the random-walk step size for the rate/volume wander
	 * @param jitterRate the wander speed multiplier
	 * @param seed       the walk seed; a change reseeds and restarts the texture
	 */
	void setParams(final float gain, final float brightness, final float jitter,
			final float jitterRate, final float seed) {
		this.targetGain = gain;
		this.targetBrightness = brightness;
		this.targetJitter = jitter;
		this.targetJitterRate = jitterRate;
		this.targetSeed = seed;
		// The effect is alive again, so the sound plays again.
		this.closing = false;
	}

	/** Starts the graceful close: the sound fades out and the line is released once it drained. */
	void beginClose() {
		this.closing = true;
	}

	/**
	 * Renders one block into the line.
	 *
	 * @param samples the block length in samples
	 * @param paused  true while the effect clock is frozen: silence, but the line keeps its lead
	 * @return false when the voice is done and can be dropped
	 */
	boolean render(final int samples, final boolean paused) {
		if (this.failed) {
			return false;
		}
		if (!this.ensureLine()) {
			return false;
		}
		final int free = this.line.available() / 2;
		final int count = Math.min(samples, free);
		if (count <= 0) {
			return true;
		}
		// The line had played everything it was given: the stream restarts from silence and
		// has to fade in, or the first sample clicks.
		final boolean resumed = this.line.available() >= BUFFER_BYTES;
		final float gain = Float.isFinite(this.targetGain) ? Math.max(0.0F, this.targetGain) : 0.0F;
		final float bright = Float.isFinite(this.targetBrightness)
			? Math.min(1.0F, Math.max(0.0F, this.targetBrightness)) : 0.5F;
		final long bits = Float.isFinite(this.targetSeed) ? (long) this.targetSeed : 0L;
		if (bits != this.seed) {
			this.seed = bits;
			this.random.setSeed(bits);
			this.pitchWalk = 1.0F;
			this.volWalk = 1.0F;
			this.holdPhase = 0.0;
			this.white = 0.0F;
			this.soft = 0.0F;
		}
		final float step = Math.max(0.0F, Float.isFinite(this.targetJitter) ? this.targetJitter : 0.0F)
			* Math.max(0.0F, Float.isFinite(this.targetJitterRate) ? this.targetJitterRate : 0.0F);
		if (step > 0.0F) {
			this.pitchWalk += (this.random.nextFloat() * 2.0F - 1.0F) * step;
			this.pitchWalk = Math.min(4.0F, Math.max(0.25F, this.pitchWalk));
			this.pitchWalk += (1.0F - this.pitchWalk) * 0.02F;
			this.volWalk += (this.random.nextFloat() * 2.0F - 1.0F) * step * 0.5F;
			this.volWalk = Math.min(2.0F, Math.max(0.0F, this.volWalk));
			this.volWalk += (1.0F - this.volWalk) * 0.02F;
		}
		final float frameGain = gain * this.volWalk;
		final boolean closing = this.closing;
		byte[] out = new byte[count * 2];
		for (int i = 0; i < count; i++) {
			float v = 0.0F;
			if (!paused) {
				this.holdPhase += this.pitchWalk;
				while (this.holdPhase >= 1.0) {
					this.holdPhase -= 1.0;
					this.white = this.random.nextFloat() * 2.0F - 1.0F;
				}
				this.soft += (this.white - this.soft) * SOFT_COEFF;
				final float s = this.soft + (this.white - this.soft) * bright;
				final float g = closing
					? this.currentGain * (1.0F - (float) (i + 1) / count)
					: this.currentGain + (frameGain - this.currentGain) * (i + 1) / count;
				v = s * g;
			}
			if (resumed && i < RESUME_FADE) {
				v *= (float) i / RESUME_FADE;
			}
			// Clamp before the cast: an above-1 gain (volume/wander have no upper cap) must
			// saturate, never wrap around the short range (that wrap reads as a harsh click).
			v = Math.min(1.0F, Math.max(-1.0F, v));
			short pcm = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) pcm;
			out[i * 2 + 1] = (byte) (pcm >> 8);
		}
		if (!paused) {
			this.currentGain = closing ? 0.0F : frameGain;
		}
		this.line.write(out, 0, out.length);
		return !closing || !this.drained();
	}

	/** The samples the line is missing to stay ahead of its playhead. */
	int deficit() {
		if (this.line == null) {
			return 0;
		}
		final int queued = (BUFFER_BYTES - this.line.available()) / 2;
		return Math.max(0, TARGET_AHEAD - queued);
	}

	/** True when the line has played everything it was given. */
	private boolean drained() {
		return this.line == null || this.line.available() >= BUFFER_BYTES;
	}

	/** Releases the line. The graceful path is the closing fade followed by this. */
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

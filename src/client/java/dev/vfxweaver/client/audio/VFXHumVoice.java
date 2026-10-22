package dev.vfxweaver.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One looping sine voice: a mono 16-bit PCM stream synthesised straight into a
 * {@link SourceDataLine}, so a hum needs no audio file in any resource pack and works
 * identically on every supported Minecraft version.
 *
 * <p>The voice is driven by the {@link VFXAudioEngine} on the audio thread, in fixed blocks,
 * so the frame rate does not reach the sound. The render thread only publishes the
 * parameters ({@link #setParams}); the phase, the gain ramp and the line belong to the audio
 * thread.
 *
 * <p>The gain ramps from the previous block's end to the published one, so a change (a fade,
 * a distance step) is a ramp and not a step - a step reads as a click. A voice that resumes
 * after the line ran dry fades in over 5 ms, and a voice whose effect ended fades out and
 * releases its line once it has drained.
 */
final class VFXHumVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	/** The sample rate of every voice line. */
	static final float SAMPLE_RATE = 22050.0F;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.5F);
	/** The fade-in after the line ran dry, in samples (5 ms). */
	private static final int RESUME_FADE = 110;
	/** How far ahead of its playhead the line stays, so a stall cannot starve it. */
	private static final int TARGET_AHEAD = (int) (SAMPLE_RATE * 0.06F);

	private SourceDataLine line;
	private boolean failed;
	private double phase;
	private float currentGain;
	/** The frequency of the last tone, so a fade-out keeps the pitch it was playing. */
	private float lastFrequency;
	/** The parameters the render thread publishes; the audio thread reads them. */
	private volatile float targetGain;
	private volatile float targetFrequency;
	/** True when the effect ended: the sound fades out and the line is released. */
	private volatile boolean closing;
	/** True when the last block was silent because the game was paused. */
	private boolean wasPaused;

	/**
	 * Publishes the parameters for the next block. Called from the render thread.
	 *
	 * @param frequency the sine frequency in Hz
	 * @param gain      the linear gain for this frame (already weighted and attenuated)
	 */
	void setParams(final float frequency, final float gain) {
		this.targetFrequency = frequency;
		this.targetGain = gain;
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
		if (this.closing && this.currentGain <= 0.0F) {
			// The fade is over: the line drains on its own and the voice is dropped once it
			// is empty. Writing more silence would keep the line from ever draining.
			return !this.drained();
		}
		final int free = this.line.available() / 2;
		final int count = Math.min(samples, free);
		if (count <= 0) {
			return true;
		}
		// The stream restarts from silence after a pause or an underrun: it has to fade in,
		// or the first sample clicks.
		final boolean resumed = (!paused && this.wasPaused) || this.line.available() >= BUFFER_BYTES;
		this.wasPaused = paused;
		final float gain = Float.isFinite(this.targetGain) ? Math.max(0.0F, this.targetGain) : 0.0F;
		float freq = Float.isFinite(this.targetFrequency) ? Math.max(0.0F, this.targetFrequency) : 0.0F;
		if (freq > 0.0F) {
			this.lastFrequency = freq;
		} else {
			// No tone: keep the last one, the gain ramp fades the sound out instead of
			// stopping it dead.
			freq = this.lastFrequency;
		}
		final boolean closing = this.closing;
		byte[] out = new byte[count * 2];
		for (int i = 0; i < count; i++) {
			float v = 0.0F;
			if (!paused) {
				this.phase += 2.0 * Math.PI * freq / SAMPLE_RATE;
				if (this.phase > 2.0 * Math.PI) {
					this.phase -= 2.0 * Math.PI;
				}
				final float g = closing
					? this.currentGain * (1.0F - (float) (i + 1) / count)
					: this.currentGain + (gain - this.currentGain) * (i + 1) / count;
				v = (float) (Math.sin(this.phase) * g);
			}
			if (resumed && i < RESUME_FADE) {
				v *= (float) i / RESUME_FADE;
			}
			// Clamp before the cast: an above-1 gain (volume has no upper cap) must saturate,
			// never wrap around the short range (that wrap reads as a harsh click).
			v = Math.min(1.0F, Math.max(-1.0F, v));
			short s = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) s;
			out[i * 2 + 1] = (byte) (s >> 8);
		}
		if (!paused) {
			this.currentGain = closing ? 0.0F : gain;
		}
		this.line.write(out, 0, out.length);
		return !closing || !this.drained();
	}

	/** The samples the line is missing to stay ahead of its playhead. */
	int deficit() {
		if (this.line == null || this.closing) {
			// A closing voice is fading out: it must not be topped up, or the line never
			// drains and the voice is never released.
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
			LOGGER.warn("VFX hum voice disabled: no audio line ({})", e.toString());
			return false;
		}
	}

}

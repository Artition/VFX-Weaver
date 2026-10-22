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
 * <p>Sample timing runs on the voice's own steady clock (see {@link VFXVoiceClock}): each
 * {@link #tick} emits what wall time owes since the previous tick, at any frame rate, while
 * the shared effect clock still gates <i>whether</i> anything is emitted. A paused game freezes
 * the effect clock, so a paused game emits nothing and the line drains into silence on its own
 * — no pause handling here. A replay seek or a slow (unfocused-window) frame resyncs
 * seamlessly: the phase integrates per emitted sample, and the gain ramps linearly from the
 * previous chunk's end, so there are no bursts, jumps or boundary clicks.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). Chunks are sized to the line's free buffer space, so
 * {@code write} never blocks the frame.
 */
final class VFXHumVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.5F);
	/** The fade-in after a silence gap, in samples (5 ms). */
	private static final int RESUME_FADE = 110;

	private SourceDataLine line;
	private boolean failed;
	private final VFXVoiceClock clock = new VFXVoiceClock(SAMPLE_RATE);
	private double phase;
	private float currentGain;
	/** The frequency of the last tone, so a fade-out keeps the pitch it was playing. */
	private float lastFrequency;
	/** True when the last tick wrote nothing into a line that had run dry. */
	private boolean gap;

	/**
	 * Emits the samples for the clock interval ending now. A non-positive frequency fades the
	 * sound out over the chunk (the tone keeps its pitch, the gain ramps to zero); a zero gain
	 * with a settled line emits nothing.
	 *
	 * @param clockTicks  the shared effect clock, in ticks
	 * @param frequency   the sine frequency in Hz
	 * @param targetGain  the linear gain for this frame (already weighted and attenuated)
	 */
	void tick(final float clockTicks, final float frequency, final float targetGain) {
		if (this.failed) {
			return;
		}
		float gain = Float.isFinite(targetGain) ? Math.max(0.0F, targetGain) : 0.0F;
		float freq = Float.isFinite(frequency) ? Math.max(0.0F, frequency) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			this.clock.idle(clockTicks);
			this.gap = true;
			return;
		}
		if (freq > 0.0F) {
			this.lastFrequency = freq;
		} else {
			// No tone: keep the last one, the gain ramp fades the sound out instead of
			// stopping it dead.
			freq = this.lastFrequency;
		}
		if (!this.ensureLine()) {
			this.clock.idle(clockTicks);
			return;
		}
		int samples = this.clock.claim(clockTicks, SAMPLES_PER_TICK, MAX_SAMPLES_PER_CALL, this.line.available() / 2);
		if (samples <= 0) {
			// Nothing written: if the line had run dry, the stream restarts from silence and
			// has to fade in, or the first sample clicks.
			this.gap = this.line.available() * 2 >= BUFFER_BYTES;
			return;
		}
		byte[] out = new byte[samples * 2];
		double step = 2.0 * Math.PI * freq / SAMPLE_RATE;
		for (int i = 0; i < samples; i++) {
			this.phase += step;
			if (this.phase > 2.0 * Math.PI) {
				this.phase -= 2.0 * Math.PI;
			}
			float g = this.currentGain + (gain - this.currentGain) * (i + 1) / samples;
			float v = (float) (Math.sin(this.phase) * g);
			if (this.gap && i < RESUME_FADE) {
				v *= (float) i / RESUME_FADE;
			}
			// Clamp before the cast: an above-1 gain (volume has no upper cap) must saturate,
			// never wrap around the short range (that wrap reads as a harsh click).
			v = Math.min(1.0F, Math.max(-1.0F, v));
			short s = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) s;
			out[i * 2 + 1] = (byte) (s >> 8);
		}
		this.gap = false;
		this.currentGain = gain;
		this.line.write(out, 0, out.length);
	}

	/**
	 * Fades the voice out after its effect ended. The line is released once it has played
	 * everything, so the stop is a fade and not a cut.
	 *
	 * @param clockTicks the shared effect clock, in ticks
	 */
	void tickClosing(final float clockTicks) {
		if (this.failed || this.line == null) {
			this.clock.idle(clockTicks);
			return;
		}
		if (this.currentGain <= 0.0F) {
			this.clock.idle(clockTicks);
			return;
		}
		int samples = this.clock.claim(clockTicks, SAMPLES_PER_TICK, MAX_SAMPLES_PER_CALL, this.line.available() / 2);
		if (samples <= 0) {
			return;
		}
		byte[] out = new byte[samples * 2];
		double step = 2.0 * Math.PI * this.lastFrequency / SAMPLE_RATE;
		for (int i = 0; i < samples; i++) {
			this.phase += step;
			if (this.phase > 2.0 * Math.PI) {
				this.phase -= 2.0 * Math.PI;
			}
			float g = this.currentGain * (1.0F - (float) (i + 1) / samples);
			float v = Math.min(1.0F, Math.max(-1.0F, (float) (Math.sin(this.phase) * g)));
			short s = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) s;
			out[i * 2 + 1] = (byte) (s >> 8);
		}
		this.currentGain = 0.0F;
		this.line.write(out, 0, out.length);
	}

	/** True when the line has played everything it was given and can be released. */
	boolean isDrained() {
		return this.line == null || this.line.available() >= BUFFER_BYTES;
	}

	/**
	 * Releases the line. The graceful path is {@link #tickClosing(float)} followed by this
	 * once the line has drained; the abrupt one is a {@code stopAll}, which cuts at once.
	 */
	void close() {
		this.clock.reset();
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

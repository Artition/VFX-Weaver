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
 * <p>The voice is driven by the shared effect clock (see {@link VFXVoiceManager}): each
 * {@link #tick} emits exactly the samples the clock advanced since the previous tick. A
 * paused game freezes the clock, so a paused game emits nothing and the line drains into
 * silence on its own — no pause handling here. A replay seek jumps the clock, so a jump
 * bigger than {@value #MAX_SAMPLES_PER_CALL} samples resyncs silently instead of bursting.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). The per-frame chunk (one game tick at most) is far
 * smaller than the line buffer, so {@code write} never blocks the frame.
 */
final class VFXHumVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.25F);

	private SourceDataLine line;
	private boolean failed;
	private float lastClock = Float.NaN;
	private double phase;
	private float currentGain;

	/**
	 * Emits the samples for the clock interval ending now. A non-positive frequency is
	 * silence (the phase resets, so no DC offset leaks); a zero gain with a settled line
	 * emits nothing.
	 *
	 * @param clockTicks  the shared effect clock, in ticks
	 * @param frequency   the sine frequency in Hz
	 * @param targetGain  the linear gain for this frame (already weighted and attenuated)
	 */
	void tick(final float clockTicks, final float frequency, final float targetGain) {
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
		float freq = Float.isFinite(frequency) ? Math.max(0.0F, frequency) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			return;
		}
		if (freq <= 0.0F) {
			this.phase = 0.0;
			this.currentGain = 0.0F;
			return;
		}
		if (!this.ensureLine()) {
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
			short s = (short) (Math.sin(this.phase) * g * Short.MAX_VALUE);
			out[i * 2] = (byte) s;
			out[i * 2 + 1] = (byte) (s >> 8);
		}
		this.currentGain = gain;
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
			LOGGER.warn("VFX hum voice disabled: no audio line ({})", e.toString());
			return false;
		}
	}
}

package dev.vfxweaver.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One looping heartbeat voice: a low sine thump gated by a beat-rate LFO,
 * synthesised straight into a {@link SourceDataLine}, so a heartbeat needs no
 * audio file in any resource pack and works identically on every supported
 * Minecraft version.
 *
 * <p>Each beat is a "lub-dub" pair: the beat phase advances {@code rate / 60}
 * cycles per second and gates a decaying {@code pitch} sine, the second thump
 * landing 0.16 of a beat later at 0.6 of the amplitude. Both {@code rate} and
 * {@code pitch} are read fresh every {@link #tick}, so keyframes, {@code expr}
 * and bindings ride them live; the phase integrates per sample, so a rate
 * change glides instead of jumping.
 *
 * <p>Clock semantics mirror {@link VFXHumVoice} exactly: each {@link #tick}
 * emits only the samples the shared effect clock advanced since the previous
 * tick, so a paused game emits nothing and the line drains on its own, and a
 * replay seek bigger than {@value #MAX_SAMPLES_PER_CALL} samples resyncs
 * silently instead of bursting.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). The per-frame chunk (one game tick at
 * most) is far smaller than the line buffer, so {@code write} never blocks
 * the frame.
 */
final class VFXHeartbeatVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.25F);
	/** The second ("dub") thump lands this far into the beat, at 0.6 amplitude. */
	private static final double DUB_OFFSET = 0.16;

	private SourceDataLine line;
	private boolean failed;
	private float lastClock = Float.NaN;
	private double beatPhase;
	private double tonePhase;
	private float currentGain;

	/**
	 * Emits the samples for the clock interval ending now. A non-positive rate
	 * or pitch is silence (the phases reset, so no DC offset leaks); a zero
	 * gain with a settled line emits nothing.
	 *
	 * @param clockTicks  the shared effect clock, in ticks
	 * @param targetGain  the linear gain for this frame (already weighted and attenuated)
	 * @param rateBpm     the beat rate in beats per minute
	 * @param pitchHz     the thump tone frequency in Hz
	 */
	void tick(final float clockTicks, final float targetGain, final float rateBpm, final float pitchHz) {
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
		float bpm = Float.isFinite(rateBpm) ? Math.max(0.0F, rateBpm) : 0.0F;
		float pitch = Float.isFinite(pitchHz) ? Math.max(0.0F, pitchHz) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			return;
		}
		if (bpm <= 0.0F || pitch <= 0.0F) {
			this.beatPhase = 0.0;
			this.tonePhase = 0.0;
			this.currentGain = 0.0F;
			return;
		}
		if (!this.ensureLine()) {
			return;
		}
		byte[] out = new byte[samples * 2];
		double beatStep = bpm / 60.0 / SAMPLE_RATE;
		double toneStep = 2.0 * Math.PI * pitch / SAMPLE_RATE;
		for (int i = 0; i < samples; i++) {
			this.beatPhase += beatStep;
			if (this.beatPhase >= 1.0) {
				this.beatPhase -= 1.0;
			}
			this.tonePhase += toneStep;
			if (this.tonePhase > 2.0 * Math.PI) {
				this.tonePhase -= 2.0 * Math.PI;
			}
			double envelope = Math.exp(-this.beatPhase * 10.0);
			if (this.beatPhase >= DUB_OFFSET) {
				envelope += 0.6 * Math.exp(-(this.beatPhase - DUB_OFFSET) * 12.0);
			}
			float g = this.currentGain + (gain - this.currentGain) * (i + 1) / samples;
			short s = (short) (Math.sin(this.tonePhase) * envelope * g * Short.MAX_VALUE);
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
			LOGGER.warn("VFX heartbeat voice disabled: no audio line ({})", e.toString());
			return false;
		}
	}
}

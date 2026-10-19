package dev.vfxweaver.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One looping heartbeat voice: a low sine thump gated by a beat-rate trigger, synthesised
 * straight into a {@link SourceDataLine}, so a heartbeat needs no audio file in any resource
 * pack and works identically on every supported Minecraft version.
 *
 * <p>Each beat is a "lub-dub" pair: the beat phase advances {@code rate / 60} cycles per
 * second, and every wrap fires a lub trigger now plus a dub trigger {@value #DUB_DELAY_S}s
 * later at {@value #DUB_AMPLITUDE} of the amplitude. Each thump has its own envelope — a fast
 * linear attack then an exponential decay of a {@code pitch} sine — so the sound is a real
 * heartbeat: two soft knocks, the second fainter, with silence between beats. Envelopes always
 * start at zero and overlap by addition, so beats never click: not at the wrap, not when a
 * dub lands on the next lub, not when {@code rate} animates. Both {@code rate} and
 * {@code pitch} are read fresh every {@link #tick}, so keyframes, {@code expr} and bindings
 * ride them live; the beat phase integrates per sample, so a rate change glides instead of
 * jumping.
 *
 * <p>Sample timing runs on the voice's own steady clock (see {@link VFXVoiceClock}): each
 * {@link #tick} emits what wall time owes since the previous tick, at any frame rate, while
 * the shared effect clock still gates <i>whether</i> anything is emitted. A paused game freezes
 * the effect clock, so a paused game emits nothing and the line drains on its own. A replay
 * seek or a slow (unfocused-window) frame resyncs seamlessly: triggers and phases persist, and
 * the gain ramps linearly from the previous chunk's end.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). Chunks are sized to the line's free buffer space, so
 * {@code write} never blocks the frame.
 */
final class VFXHeartbeatVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.5F);
	/** The second ("dub") thump lands this many seconds after the lub, at {@link #DUB_AMPLITUDE}. */
	private static final double DUB_DELAY_S = 0.27;
	private static final double DUB_AMPLITUDE = 0.6;
	/** Thump attack: linear ramp from zero, fast enough to knock, slow enough to never click. */
	private static final double ATTACK_S = 0.005;
	/** Thump decay: exponential time constant (a thump is ~1% after ~0.37s). */
	private static final double DECAY_TAU_S = 0.08;
	/** Trigger slots: two per beat; eight covers four beats, past any audible tail. */
	private static final int MAX_TRIGGERS = 8;

	private SourceDataLine line;
	private boolean failed;
	private final VFXVoiceClock clock = new VFXVoiceClock(SAMPLE_RATE);
	private double beatPhase;
	private double tonePhase;
	private final double[] triggerAge = new double[MAX_TRIGGERS];
	private final float[] triggerAmp = new float[MAX_TRIGGERS];
	private int nextTrigger;
	private float currentGain;

	VFXHeartbeatVoice() {
		for (int i = 0; i < MAX_TRIGGERS; i++) {
			this.triggerAge[i] = Double.POSITIVE_INFINITY;
		}
	}

	/**
	 * Emits the samples for the clock interval ending now. A non-positive rate
	 * or pitch is silence (triggers and phases reset, so no DC offset leaks); a zero
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
		float gain = Float.isFinite(targetGain) ? Math.max(0.0F, targetGain) : 0.0F;
		float bpm = Float.isFinite(rateBpm) ? Math.max(0.0F, rateBpm) : 0.0F;
		float pitch = Float.isFinite(pitchHz) ? Math.max(0.0F, pitchHz) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			this.clock.idle(clockTicks);
			return;
		}
		if (bpm <= 0.0F || pitch <= 0.0F) {
			this.clearTriggers();
			this.beatPhase = 0.0;
			this.tonePhase = 0.0;
			this.currentGain = 0.0F;
			this.clock.idle(clockTicks);
			return;
		}
		if (!this.ensureLine()) {
			this.clock.idle(clockTicks);
			return;
		}
		int samples = this.clock.claim(clockTicks, SAMPLES_PER_TICK, MAX_SAMPLES_PER_CALL, this.line.available() / 2);
		if (samples <= 0) {
			return;
		}
		byte[] out = new byte[samples * 2];
		double beatStep = bpm / 60.0 / SAMPLE_RATE;
		double toneStep = 2.0 * Math.PI * pitch / SAMPLE_RATE;
		double sampleStep = 1.0 / SAMPLE_RATE;
		for (int i = 0; i < samples; i++) {
			this.beatPhase += beatStep;
			if (this.beatPhase >= 1.0) {
				this.beatPhase -= 1.0;
				this.fire(1.0F, 0.0);
				this.fire((float) DUB_AMPLITUDE, -DUB_DELAY_S);
			}
			this.advanceTriggers(sampleStep);
			this.tonePhase += toneStep;
			if (this.tonePhase > 2.0 * Math.PI) {
				this.tonePhase -= 2.0 * Math.PI;
			}
			float g = this.currentGain + (gain - this.currentGain) * (i + 1) / samples;
			// Clamp before the cast: overlapping lub+dub tails can sum past 1 at high rates,
			// and that must saturate, never wrap around the short range (a harsh click).
			float v = Math.min(1.0F, Math.max(-1.0F, (float) (Math.sin(this.tonePhase) * this.envelope() * g)));
			short s = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) s;
			out[i * 2 + 1] = (byte) (s >> 8);
		}
		this.currentGain = gain;
		this.line.write(out, 0, out.length);
	}

	/** Schedules one thump: full amplitude now, or a pending dub when {@code delayS} is negative. */
	private void fire(final float amplitude, final double delayS) {
		this.triggerAge[this.nextTrigger] = delayS;
		this.triggerAmp[this.nextTrigger] = amplitude;
		this.nextTrigger = (this.nextTrigger + 1) % MAX_TRIGGERS;
	}

	/** Ages every trigger by one sample; tails past audibility free their slot. */
	private void advanceTriggers(final double sampleStep) {
		for (int i = 0; i < MAX_TRIGGERS; i++) {
			if (this.triggerAmp[i] == 0.0F) {
				continue;
			}
			this.triggerAge[i] += sampleStep;
			if (this.triggerAge[i] > 1.5) {
				this.triggerAmp[i] = 0.0F;
				this.triggerAge[i] = Double.POSITIVE_INFINITY;
			}
		}
	}

	/** The summed thump envelopes: each a 5ms attack from zero times an exponential decay. */
	private double envelope() {
		double envelope = 0.0;
		for (int i = 0; i < MAX_TRIGGERS; i++) {
			final float amp = this.triggerAmp[i];
			final double age = this.triggerAge[i];
			if (amp == 0.0F || age < 0.0) {
				continue;
			}
			final double attack = Math.min(1.0, age / ATTACK_S);
			envelope += amp * attack * Math.exp(-age / DECAY_TAU_S);
		}
		return envelope;
	}

	private void clearTriggers() {
		for (int i = 0; i < MAX_TRIGGERS; i++) {
			this.triggerAge[i] = Double.POSITIVE_INFINITY;
			this.triggerAmp[i] = 0.0F;
		}
	}

	/**
	 * Releases the line. Abrupt by design (mirrors {@code VFXWindowManager.closeAll}): a
	 * faded-out effect is already silent when it is removed, and a {@code stopAll} cuts
	 * immediately.
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
			LOGGER.warn("VFX heartbeat voice disabled: no audio line ({})", e.toString());
			return false;
		}
	}
}

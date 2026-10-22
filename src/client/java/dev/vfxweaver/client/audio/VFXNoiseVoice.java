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
 * <p>Sample timing runs on the voice's own steady clock (see {@link VFXVoiceClock}): each
 * {@link #tick} emits what wall time owes since the previous tick, at any frame rate, while
 * the shared effect clock still gates <i>whether</i> anything is emitted. A paused game freezes
 * the effect clock, so a paused game emits nothing and the line drains on its own. A replay
 * seek or a slow (unfocused-window) frame resyncs seamlessly: the held sample, the softened
 * copy and the walks persist across the resync, and the gain ramps linearly from the previous
 * chunk's end, so there are no bursts, jumps or boundary clicks.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). Chunks are sized to the line's free buffer space, so
 * {@code write} never blocks the frame.
 */
final class VFXNoiseVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.5F);
	/** One-pole softening toward the mellow copy {@code brightness} blends from. */
	private static final float SOFT_COEFF = 0.15F;
	/** The fade-in after a silence gap, in samples (5 ms). */
	private static final int RESUME_FADE = 110;

	private SourceDataLine line;
	private boolean failed;
	private final VFXVoiceClock clock = new VFXVoiceClock(SAMPLE_RATE);
	private long seed = Long.MIN_VALUE;
	private final Random random = new Random();
	private float pitchWalk = 1.0F;
	private float volWalk = 1.0F;
	private double holdPhase;
	private float white;
	private float soft;
	private float currentGain;
	/** True when the last tick wrote nothing into a line that had run dry. */
	private boolean gap;
	/** The last brightness, so the closing fade keeps the texture it was playing. */
	private float lastBrightness = 0.5F;

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
		float gain = Float.isFinite(targetGain) ? Math.max(0.0F, targetGain) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			this.clock.idle(clockTicks);
			this.gap = true;
			return;
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
		this.lastBrightness = bright;
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
			float v = s * g;
			if (this.gap && i < RESUME_FADE) {
				v *= (float) i / RESUME_FADE;
			}
			// Clamp before the cast: an above-1 gain (volume/wander have no upper cap) must
			// saturate, never wrap around the short range (that wrap reads as a harsh click).
			v = Math.min(1.0F, Math.max(-1.0F, v));
			short pcm = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) pcm;
			out[i * 2 + 1] = (byte) (pcm >> 8);
		}
		this.gap = false;
		this.currentGain = frameGain;
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
		for (int i = 0; i < samples; i++) {
			this.holdPhase += this.pitchWalk;
			while (this.holdPhase >= 1.0) {
				this.holdPhase -= 1.0;
				this.white = this.random.nextFloat() * 2.0F - 1.0F;
			}
			this.soft += (this.white - this.soft) * SOFT_COEFF;
			float g = this.currentGain * (1.0F - (float) (i + 1) / samples);
			float v = Math.min(1.0F, Math.max(-1.0F, (this.soft + (this.white - this.soft) * this.lastBrightness) * g));
			short pcm = (short) (v * Short.MAX_VALUE);
			out[i * 2] = (byte) pcm;
			out[i * 2 + 1] = (byte) (pcm >> 8);
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
			LOGGER.warn("VFX noise voice disabled: no audio line ({})", e.toString());
			return false;
		}
	}
}

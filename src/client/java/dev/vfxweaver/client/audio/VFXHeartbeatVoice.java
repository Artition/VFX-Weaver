package dev.vfxweaver.client.audio;

import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One looping heartbeat voice: a bundled recording of a single lub-dub, played back at a
 * rate-driven speed, so a faster heart is a faster recording with a shorter gap between
 * beats - like tape. The recording ships inside the jar, so no resource pack is needed and
 * the sound is identical on every Minecraft line.
 *
 * <p>The beat phase advances {@code rate / 60} cycles per second; every wrap restarts the
 * recording. Its playback speed is {@code rate / 60} times the {@code pitch} param (a
 * multiplier, 1 = as recorded), so at 60 bpm the recording plays at its original speed and
 * at 120 bpm it plays twice as fast, with the lub-dub inside it compressed the same way -
 * the internal mini-pause shrinks with the tempo instead of being re-timed separately.
 *
 * <p>Sample timing runs on the voice's own steady clock (see {@link VFXVoiceClock}): each
 * {@link #tick} emits what wall time owes since the previous tick, at any frame rate, while
 * the shared effect clock still gates <i>whether</i> anything is emitted. A paused game
 * freezes the effect clock, so a paused game emits nothing and the line drains on its own. A
 * replay seek or a slow (unfocused-window) frame resyncs seamlessly: the playback position
 * and the gain ramp persist across chunks.
 *
 * <p>All calls must run on the render thread (the manager calls from
 * {@code VFXEffectManager.update()}). Chunks are sized to the line's free buffer space, so
 * {@code write} never blocks the frame.
 */
final class VFXHeartbeatVoice {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	/** The bundled recording: one lub-dub, mono 16-bit PCM inside the jar. */
	private static final String SAMPLE_RESOURCE = "/assets/vfxweaver/sounds/heartbeat.wav";
	private static final float SAMPLE_RATE = 22050.0F;
	private static final int SAMPLES_PER_TICK = (int) (SAMPLE_RATE / 20.0F);
	private static final int MAX_SAMPLES_PER_CALL = SAMPLES_PER_TICK * 4;
	private static final int BUFFER_BYTES = (int) (SAMPLE_RATE * 2 * 0.5F);
	/** Fade applied to a playback that is cut short, so a restart never clicks (5 ms). */
	private static final int INTERRUPT_FADE = 110;
	/** Fade applied to the start of every playback (5 ms). */
	private static final int ATTACK_FADE = 110;

	/** The recording, decoded once and shared by every voice. Null when it cannot be read. */
	private static float[] sample;
	private static boolean sampleLoaded;

	private SourceDataLine line;
	private boolean failed;
	private final VFXVoiceClock clock = new VFXVoiceClock(SAMPLE_RATE);
	private double beatPhase;
	/** Playback position in sample frames. */
	private double position;
	private boolean playing;
	/** Speed of the recording relative to its original rate. */
	private double speed = 1.0;
	private int attack;
	private int interrupt;
	private float currentGain;

	/**
	 * Emits the samples for the clock interval ending now. A non-positive rate or pitch is
	 * silence (the phase resets, so no DC offset leaks); a zero gain with a settled line
	 * emits nothing.
	 *
	 * @param clockTicks  the shared effect clock, in ticks
	 * @param targetGain  the linear gain for this frame (already weighted and attenuated)
	 * @param rateBpm     the beat rate in beats per minute
	 * @param pitchScale  playback-speed multiplier, 1 = as recorded
	 */
	void tick(final float clockTicks, final float targetGain, final float rateBpm, final float pitchScale) {
		if (this.failed) {
			return;
		}
		float gain = Float.isFinite(targetGain) ? Math.max(0.0F, targetGain) : 0.0F;
		float rate = Float.isFinite(rateBpm) ? Math.max(0.0F, rateBpm) : 0.0F;
		float pitch = Float.isFinite(pitchScale) ? Math.max(0.0F, pitchScale) : 0.0F;
		if (gain <= 0.0F && this.currentGain <= 0.0F) {
			this.clock.idle(clockTicks);
			return;
		}
		if (rate <= 0.0F || pitch <= 0.0F) {
			this.reset();
			this.clock.idle(clockTicks);
			return;
		}
		final float[] recording = loadSample();
		if (recording == null) {
			this.failed = true;
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
		double beatStep = rate / 60.0 / SAMPLE_RATE;
		// Tape-like tempo: the recording itself plays faster as the rate climbs, so the
		// lub-dub inside it compresses together with the gap between beats.
		this.speed = rate / 60.0 * pitch;
		for (int i = 0; i < samples; i++) {
			this.beatPhase += beatStep;
			if (this.beatPhase >= 1.0) {
				this.beatPhase -= 1.0;
				this.restart();
			}
			float v = 0.0F;
			if (this.playing) {
				final int index = (int) this.position;
				if (index + 1 >= recording.length) {
					this.playing = false;
				} else {
					final float frac = (float) (this.position - index);
					v = recording[index] * (1.0F - frac) + recording[index + 1] * frac;
					this.position += this.speed;
				}
			}
			if (this.attack > 0) {
				this.attack--;
				v *= 1.0F - (float) this.attack / ATTACK_FADE;
			}
			if (this.interrupt > 0) {
				this.interrupt--;
				v *= (float) this.interrupt / INTERRUPT_FADE;
			}
			float g = this.currentGain + (gain - this.currentGain) * (i + 1) / samples;
			// Clamp before the cast: a cut playback can still overshoot, and that must
			// saturate, never wrap around the short range (a harsh click).
			float s = Math.min(1.0F, Math.max(-1.0F, v * g));
			short pcm = (short) (s * Short.MAX_VALUE);
			out[i * 2] = (byte) pcm;
			out[i * 2 + 1] = (byte) (pcm >> 8);
		}
		this.currentGain = gain;
		this.line.write(out, 0, out.length);
	}

	/** Starts the recording from its first sample, cutting whatever was playing softly. */
	private void restart() {
		if (this.playing) {
			this.interrupt = INTERRUPT_FADE;
		}
		this.position = 0.0;
		this.playing = true;
		this.attack = ATTACK_FADE;
	}

	private void reset() {
		this.beatPhase = 0.0;
		this.position = 0.0;
		this.playing = false;
		this.attack = 0;
		this.interrupt = 0;
		this.currentGain = 0.0F;
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
		this.reset();
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

	/**
	 * Decodes the bundled recording once, into mono floats at {@link #SAMPLE_RATE}. The WAV is
	 * parsed by hand (a RIFF header and a PCM16 data chunk) so no audio SPI is needed.
	 *
	 * @return the samples, or null when the resource is missing or not PCM16
	 */
	private static synchronized float[] loadSample() {
		if (sampleLoaded) {
			return sample;
		}
		sampleLoaded = true;
		try (InputStream in = VFXHeartbeatVoice.class.getResourceAsStream(SAMPLE_RESOURCE)) {
			if (in == null) {
				LOGGER.warn("VFX heartbeat recording missing ({})", SAMPLE_RESOURCE);
				return null;
			}
			sample = decodeWav(in.readAllBytes());
		} catch (IOException e) {
			LOGGER.warn("VFX heartbeat recording unreadable ({})", e.toString());
			return null;
		}
		if (sample == null) {
			LOGGER.warn("VFX heartbeat recording is not PCM16 mono ({})", SAMPLE_RESOURCE);
		}
		return sample;
	}

	/** Parses a RIFF/WAVE byte array into mono floats resampled to {@link #SAMPLE_RATE}. */
	private static float[] decodeWav(final byte[] bytes) {
		if (bytes.length < 44 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F') {
			return null;
		}
		int channels = 0;
		int rate = 0;
		int bits = 0;
		int offset = 12;
		while (offset + 8 <= bytes.length) {
			final String id = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
			final int size = readInt(bytes, offset + 4);
			final int body = offset + 8;
			if ("fmt ".equals(id)) {
				channels = readShort(bytes, body + 2);
				rate = readInt(bytes, body + 4);
				bits = readShort(bytes, body + 14);
			} else if ("data".equals(id)) {
				if (channels <= 0 || rate <= 0 || bits != 16) {
					return null;
				}
				final int frames = Math.min(size, bytes.length - body) / (channels * 2);
				final float[] mono = new float[frames];
				for (int i = 0; i < frames; i++) {
					float sum = 0.0F;
					for (int c = 0; c < channels; c++) {
						sum += readShort(bytes, body + (i * channels + c) * 2) / 32768.0F;
					}
					mono[i] = sum / channels;
				}
				return resample(mono, rate);
			}
			offset = body + size + (size & 1);
		}
		return null;
	}

	/** Linear resample to the mix rate, so the recording plays at its original pitch. */
	private static float[] resample(final float[] input, final int rate) {
		if (rate == (int) SAMPLE_RATE) {
			return input;
		}
		final float[] output = new float[Math.max(1, (int) (input.length * SAMPLE_RATE / rate))];
		for (int i = 0; i < output.length; i++) {
			final double source = i * (double) rate / SAMPLE_RATE;
			final int index = (int) source;
			if (index + 1 >= input.length) {
				output[i] = input[input.length - 1];
				continue;
			}
			final float frac = (float) (source - index);
			output[i] = input[index] * (1.0F - frac) + input[index + 1] * frac;
		}
		return output;
	}

	private static int readInt(final byte[] bytes, final int offset) {
		return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
				| ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
	}

	private static int readShort(final byte[] bytes, final int offset) {
		return (short) ((bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8));
	}
}

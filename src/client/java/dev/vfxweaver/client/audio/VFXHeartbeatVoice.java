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
 * One looping heartbeat voice: a bundled recording of a single lub-dub, time-stretched to
 * the effect's tempo, so a faster heart is a faster recording on the same pitch - the
 * recording compresses into the beat instead of being replayed faster. The recording ships
 * inside the jar, so no resource pack is needed and the sound is identical on every
 * Minecraft line.
 *
 * <p>The beat phase advances {@code rate / 60} cycles per second and every wrap restarts
 * the recording. The stretch factor is {@code rate / 60}, so the recording always fits its
 * beat: at 60 bpm it plays as recorded (0.85 s in a 1 s beat) and at 140 bpm it is
 * compressed to about 0.36 s in a 0.43 s beat, with the pause inside the lub-dub closing
 * up in the same proportion. The stretch is an overlap-add time compression, so the pitch
 * does not move with the tempo; the {@code pitch} param is a separate tone multiplier
 * that does shift the pitch (and the length with it).
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
	/** Synthesis hop of the time-scale (11.6 ms) and the grain length (twice the hop). */
	private static final int GRAIN_HOP = 256;
	private static final int GRAIN = GRAIN_HOP * 2;

	/** The recording, decoded once and shared by every voice. Null when it cannot be read. */
	private static float[] sample;
	private static boolean sampleLoaded;

	private SourceDataLine line;
	private boolean failed;
	private final VFXVoiceClock clock = new VFXVoiceClock(SAMPLE_RATE);
	private double beatPhase;
	/** The recording stretched to the current tempo; the one being played. */
	private float[] playing;
	/** The recording stretched for the next beat, prepared ahead of the switch. */
	private float[] prepared;
	/** The stretch the prepared buffer was made for. */
	private double preparedStretch;
	private double preparedPitch;
	private int position;
	private boolean playingNow;
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
	 * @param pitchScale  tone multiplier, 1 = the recorded pitch
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
		// The tempo compresses the recording in time (pitch kept); `pitch` shifts the tone.
		final double stretch = rate / 60.0;
		this.prepare(recording, stretch, pitch);
		for (int i = 0; i < samples; i++) {
			this.beatPhase += beatStep;
			if (this.beatPhase >= 1.0) {
				this.beatPhase -= 1.0;
				this.restart();
			}
			float v = 0.0F;
			if (this.playingNow && this.playing != null) {
				if (this.position >= this.playing.length) {
					this.playingNow = false;
				} else {
					v = this.playing[this.position];
					this.position++;
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

	/**
	 * Prepares the recording for the next beat: the recording is time-compressed by the
	 * stretch factor (overlap-add, so the pitch stays), then resampled by the tone
	 * multiplier. The work happens once per beat, not per sample.
	 */
	private void prepare(final float[] recording, final double stretch, final double pitch) {
		if (this.prepared != null && this.preparedStretch == stretch && this.preparedPitch == pitch) {
			return;
		}
		this.preparedStretch = stretch;
		this.preparedPitch = pitch;
		this.prepared = resample(scale(recording, stretch), pitch);
	}

	/** Starts the recording from its first sample, cutting whatever was playing softly. */
	private void restart() {
		if (this.playingNow) {
			this.interrupt = INTERRUPT_FADE;
		}
		if (this.prepared != null) {
			this.playing = this.prepared;
			this.prepared = null;
		}
		this.position = 0;
		this.playingNow = true;
		this.attack = ATTACK_FADE;
	}

	private void reset() {
		this.beatPhase = 0.0;
		this.position = 0;
		this.playingNow = false;
		this.playing = null;
		this.prepared = null;
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
				return resample(mono, rate / SAMPLE_RATE);
			}
			offset = body + size + (size & 1);
		}
		return null;
	}

	/**
	 * Time-scales the recording with a wave-form-similarity overlap-add: the output is
	 * shorter (or longer) while the tone stays where it was, so a faster heart is a
	 * faster recording on the same pitch. Each grain is placed at a synthesis hop and the
	 * source position is searched around the nominal one for the offset that best
	 * continues what was already written, so the waveform stays continuous.
	 */
	private static float[] scale(final float[] input, final double factor) {
		if (Math.abs(factor - 1.0) < 0.001) {
			return input;
		}
		final int outHop = GRAIN_HOP;
		final int srcHop = Math.max(1, (int) Math.round(GRAIN_HOP * factor));
		final int grains = (input.length + srcHop - 1) / srcHop;
		final int outLength = Math.max(1, grains * outHop);
		final float[] out = new float[outLength];
		final float[] weight = new float[outLength];
		final int search = Math.min(srcHop, 256);
		final int correlation = outHop / 2;
		for (int g = 0; g < grains; g++) {
			final int nominal = g * srcHop;
			final int outStart = g * outHop;
			int best = nominal;
			if (g > 0) {
				// The tail of the output so far is the template the next grain continues.
				final int tailStart = outStart - outHop;
				double bestScore = Double.NEGATIVE_INFINITY;
				for (int delta = -search; delta <= search; delta++) {
					final int start = nominal + delta;
					if (start < 0 || start + correlation > input.length || tailStart + correlation > outLength) {
						continue;
					}
					double score = 0.0;
					for (int i = 0; i < correlation; i++) {
						score += input[start + i] * out[tailStart + i];
					}
					// Prefer the smallest shift, so the material stays intact.
					score -= Math.abs(delta) * 1.0e-4 * Math.abs(score);
					if (score > bestScore) {
						bestScore = score;
						best = start;
					}
				}
			}
			for (int i = 0; i < GRAIN; i++) {
				final int src = best + i;
				final int dst = outStart + i;
				if (src < 0 || src >= input.length || dst >= outLength) {
					continue;
				}
				final double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / GRAIN);
				out[dst] += input[src] * (float) w;
				weight[dst] += (float) w;
			}
		}
		for (int i = 0; i < outLength; i++) {
			if (weight[i] > 1.0e-4F) {
				out[i] /= weight[i];
			}
		}
		return out;
	}

	/** Resamples to {@link #SAMPLE_RATE} (used for the source rate) or by a tone factor. */
	private static float[] resample(final float[] input, final double factor) {
		if (Math.abs(factor - 1.0) < 0.001) {
			return input;
		}
		final int outLength = Math.max(1, (int) Math.round(input.length / factor));
		final float[] output = new float[outLength];
		for (int i = 0; i < outLength; i++) {
			final double source = i * factor;
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

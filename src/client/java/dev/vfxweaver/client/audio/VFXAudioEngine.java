package dev.vfxweaver.client.audio;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audio thread: it owns the voices and their lines and synthesises them continuously,
 * whatever the frame rate does.
 *
 * <p><b>Why a thread of its own.</b> The voices used to be synthesised on the render thread,
 * one chunk per frame, so the frame rate leaked into the sound: the gain ramps were one
 * frame long, a long frame could starve the line's buffer (that is the crackling), and the
 * heartbeat's time-scale work spiked the frame. Here the thread generates fixed blocks from
 * its own wall clock, so a lost frame or a long frame cannot reach the sound at all.
 *
 * <p><b>The lead.</b> Every line stays about 100 ms ahead of its playhead: the thread tops a
 * line up again when it falls behind, and primes a new line with that much silence. A stall
 * in this thread (a GC pause) is then absorbed instead of starving the line and clicking.
 *
 * <p><b>The clock.</b> The render thread publishes the shared effect clock; the thread uses
 * it only to know that the game is paused (a frozen clock), and then renders silence without
 * advancing the voices. The pacing is the wall clock, so the sound runs at the real rate and
 * a replay seek cannot burst it.
 *
 * <p><b>Ownership.</b> All the voice state belongs to this thread. The render thread only
 * writes the published parameter fields (volatile) and the closing flag; it never touches
 * the state, so there is nothing to lock.
 */
final class VFXAudioEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	/** The generation block: 5 ms at 22.05 kHz. */
	private static final int BLOCK_SAMPLES = 110;
	/** The block period in nanoseconds. */
	private static final long BLOCK_NANOS = 5_000_000L;
	/**
	 * A clock that has not moved for this long means the game is paused. The threshold is
	 * generous on purpose: a slow frame (the render thread publishes the clock once per
	 * frame) must not be mistaken for a pause.
	 */
	private static final long PAUSE_NANOS = 250_000_000L;

	private final Map<Long, VFXHumVoice> hums = new ConcurrentHashMap<>();
	private final Map<Long, VFXNoiseVoice> noises = new ConcurrentHashMap<>();
	private final Map<Long, VFXHeartbeatVoice> heartbeats = new ConcurrentHashMap<>();
	private final Thread thread;

	/** The shared effect clock, published by the render thread. */
	private volatile float clock = Float.NaN;
	/** True while the thread should keep running. */
	private volatile boolean running = true;
	/** The wall-clock anchor of the current block. */
	private long anchorNanos;
	/** The fractional samples carried between blocks. */
	private double owed;
	/** When the effect clock last moved. */
	private long clockMovedNanos;
	/** The last effect clock the thread saw. */
	private float lastClock = Float.NaN;

	VFXAudioEngine() {
		this.thread = new Thread(this::run, "vfxweaver-audio");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.NORM_PRIORITY + 1);
		this.thread.start();
	}

	/**
	 * Publishes the shared effect clock. The thread only looks at it to notice a pause.
	 *
	 * @param clockTicks the shared effect clock, in ticks
	 */
	void setClock(final float clockTicks) {
		this.clock = clockTicks;
	}

	/**
	 * Creates or updates a hum voice.
	 *
	 * @param id       the effect instance id
	 * @param frequency the sine frequency in Hz
	 * @param gain      the linear gain for this frame
	 */
	void hum(final long id, final float frequency, final float gain) {
		this.hums.computeIfAbsent(id, ignored -> new VFXHumVoice()).setParams(frequency, gain);
	}

	/**
	 * Creates or updates a noise voice.
	 *
	 * @param id         the effect instance id
	 * @param gain       the linear gain for this frame
	 * @param brightness the crisp/soft blend
	 * @param jitter     the random-walk step size
	 * @param jitterRate the wander speed multiplier
	 * @param seed       the walk seed
	 */
	void noise(final long id, final float gain, final float brightness, final float jitter,
			final float jitterRate, final float seed) {
		this.noises.computeIfAbsent(id, ignored -> new VFXNoiseVoice())
			.setParams(gain, brightness, jitter, jitterRate, seed);
	}

	/**
	 * Creates or updates a heartbeat voice.
	 *
	 * @param id      the effect instance id
	 * @param gain    the linear gain for this frame
	 * @param rateBpm the beat rate in beats per minute
	 * @param pitch   the tone multiplier
	 */
	void heartbeat(final long id, final float gain, final float rateBpm, final float pitch) {
		this.heartbeats.computeIfAbsent(id, ignored -> new VFXHeartbeatVoice())
			.setParams(gain, rateBpm, pitch);
	}

	/**
	 * Stops a voice: the sound fades out (the heartbeat plays its beat out) and the line is
	 * released once it has drained.
	 *
	 * @param type the effect type of the voice to stop
	 * @param id   the effect instance id
	 */
	void stop(final dev.vfxweaver.effect.VFXEffectType type, final long id) {
		if (type == dev.vfxweaver.effect.VFXEffectType.HUM) {
			final VFXHumVoice voice = this.hums.get(id);
			if (voice != null) {
				voice.beginClose();
			}
		} else if (type == dev.vfxweaver.effect.VFXEffectType.NOISE) {
			final VFXNoiseVoice voice = this.noises.get(id);
			if (voice != null) {
				voice.beginClose();
			}
		} else if (type == dev.vfxweaver.effect.VFXEffectType.HEARTBEAT) {
			final VFXHeartbeatVoice voice = this.heartbeats.get(id);
			if (voice != null) {
				voice.beginClose();
			}
		}
	}

	/**
	 * Marks the voices whose effect is gone: each one fades out (the heartbeat plays its beat
	 * out) and the audio thread releases its line once it has drained.
	 *
	 * @param humIds        the ids of the live hum effects
	 * @param noiseIds      the ids of the live noise effects
	 * @param heartbeatIds  the ids of the live heartbeat effects
	 */
	void stopMissing(final Set<Long> humIds, final Set<Long> noiseIds, final Set<Long> heartbeatIds) {
		for (final Long id : this.hums.keySet()) {
			if (!humIds.contains(id)) {
				this.hums.get(id).beginClose();
			}
		}
		for (final Long id : this.noises.keySet()) {
			if (!noiseIds.contains(id)) {
				this.noises.get(id).beginClose();
			}
		}
		for (final Long id : this.heartbeats.keySet()) {
			if (!heartbeatIds.contains(id)) {
				this.heartbeats.get(id).beginClose();
			}
		}
	}

	/** Stops every voice: each one fades out and releases its line. */
	void stopAll() {
		for (final VFXHumVoice voice : this.hums.values()) {
			voice.beginClose();
		}
		for (final VFXNoiseVoice voice : this.noises.values()) {
			voice.beginClose();
		}
		for (final VFXHeartbeatVoice voice : this.heartbeats.values()) {
			voice.beginClose();
		}
	}

	/**
	 * Stops the thread and releases every line. Must run on the render thread (the client
	 * shutdown path).
	 */
	void shutdown() {
		this.running = false;
		this.thread.interrupt();
		try {
			this.thread.join(2000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The generation loop: fixed blocks on the wall clock, the voices own the audio. */
	private void run() {
		this.anchorNanos = System.nanoTime();
		this.clockMovedNanos = this.anchorNanos;
		while (this.running) {
			final long now = System.nanoTime();
			// The wall-clock debt: the samples the sound owes since the last block.
			this.owed += (now - this.anchorNanos) / 1_000_000_000.0 * VFXHumVoice.SAMPLE_RATE;
			this.anchorNanos = now;
			// A line that fell behind (a stall in this thread) is topped up again, so the
			// lead is always there.
			this.owed += this.deficit();
			final int samples = (int) Math.min(this.owed, BLOCK_SAMPLES * 8);
			this.owed -= samples;
			final boolean paused = this.paused(now);
			if (samples > 0) {
				this.render(samples, paused);
			}
			final long sleep = BLOCK_NANOS - (System.nanoTime() - this.anchorNanos);
			if (sleep > 0) {
				LockSupport.parkNanos(sleep);
			}
		}
		this.release();
	}

	/** Renders one block into every voice and drops the ones that are done. */
	private void render(final int samples, final boolean paused) {
		for (final Map.Entry<Long, VFXHumVoice> entry : this.hums.entrySet()) {
			if (!entry.getValue().render(samples, paused)) {
				entry.getValue().close();
				this.hums.remove(entry.getKey());
			}
		}
		for (final Map.Entry<Long, VFXNoiseVoice> entry : this.noises.entrySet()) {
			if (!entry.getValue().render(samples, paused)) {
				entry.getValue().close();
				this.noises.remove(entry.getKey());
			}
		}
		for (final Map.Entry<Long, VFXHeartbeatVoice> entry : this.heartbeats.entrySet()) {
			if (!entry.getValue().render(samples, paused)) {
				entry.getValue().close();
				this.heartbeats.remove(entry.getKey());
			}
		}
	}

	/** The samples the lines are missing to stay ahead of their playheads. */
	private int deficit() {
		int total = 0;
		for (final VFXHumVoice voice : this.hums.values()) {
			total += voice.deficit();
		}
		for (final VFXNoiseVoice voice : this.noises.values()) {
			total += voice.deficit();
		}
		for (final VFXHeartbeatVoice voice : this.heartbeats.values()) {
			total += voice.deficit();
		}
		return total;
	}

	/** True while the effect clock is frozen: the game is paused and the voices go quiet. */
	private boolean paused(final long now) {
		final float clock = this.clock;
		if (Float.isNaN(clock)) {
			return true;
		}
		if (Float.isNaN(this.lastClock) || clock > this.lastClock) {
			this.lastClock = clock;
			this.clockMovedNanos = now;
			return false;
		}
		return now - this.clockMovedNanos > PAUSE_NANOS;
	}

	/** Releases every line; the thread is stopping. */
	private void release() {
		for (final VFXHumVoice voice : this.hums.values()) {
			voice.close();
		}
		for (final VFXNoiseVoice voice : this.noises.values()) {
			voice.close();
		}
		for (final VFXHeartbeatVoice voice : this.heartbeats.values()) {
			voice.close();
		}
		this.hums.clear();
		this.noises.clear();
		this.heartbeats.clear();
		LOGGER.debug("vfxweaver audio thread stopped");
	}
}

package dev.vfxweaver.client.audio;

/**
 * The steady sample-count clock behind the synthesised voices. The shared effect clock drives
 * <i>what</i> to play (params, fade weight, pause); this clock decides <i>how many samples</i>
 * that is, from wall time instead of frame ticks.
 *
 * <p>Why: the voices are synthesised on the render thread, so their old per-frame sample count
 * ({@code elapsedTicks × samplesPerTick}) collapsed whenever frames did — an unfocused window
 * drops to a few frames per second, each frame's elapsed jumps past the old 4-tick cap, and the
 * voice emitted nothing: chunk-gap-chunk stutter. Counting wall time instead keeps the line fed
 * at any frame rate, while a frozen effect clock still means pause (pause menu, paused replay):
 * no samples are emitted and the wall anchor is reset, so the resume emits no catch-up burst —
 * the frozen-on-pause behaviour is unchanged.
 *
 * <p>Slow frames and replay seeks share one path: the clock jumped further than a capped chunk
 * covers, so the voice emits its wall-clock debt sized to what the line can take without
 * blocking (never more than the free buffer space, so {@code write} never stalls the frame).
 * Phases stay continuous across the resync — no burst, no click.
 */
final class VFXVoiceClock {
	private final float sampleRate;
	private float lastClock = Float.NaN;
	private long anchorNanos;

	VFXVoiceClock(final float sampleRate) {
		this.sampleRate = sampleRate;
	}

	/**
	 * Notes a silent frame (settled gain, unresolvable params): advances the effect clock without
	 * emitting, and re-anchors to wall time so no debt accrues while silent.
	 *
	 * @param clockTicks the shared effect clock, in ticks
	 */
	void idle(final float clockTicks) {
		this.lastClock = clockTicks;
		this.anchorNanos = System.nanoTime();
	}

	/**
	 * Samples to emit this frame ({@code 0} means emit nothing).
	 *
	 * @param clockTicks     the shared effect clock, in ticks
	 * @param samplesPerTick the voice's samples per effect tick
	 * @param maxSamples     the normal-path cap, in samples (a few ticks' worth)
	 * @param freeSamples    the line's free buffer space, in samples (never overfilled)
	 * @return the sample count to synthesise and write, already capped
	 */
	int claim(final float clockTicks, final int samplesPerTick, final int maxSamples, final int freeSamples) {
		if (Float.isNaN(this.lastClock)) {
			this.idle(clockTicks);
			return 0;
		}
		final float elapsed = clockTicks - this.lastClock;
		this.lastClock = clockTicks;
		if (!(elapsed > 0.0F)) {
			// Frozen clock: pause menu or paused replay. Emit nothing, re-anchor.
			this.anchorNanos = System.nanoTime();
			return 0;
		}
		final int normal = (int) (elapsed * samplesPerTick);
		if (normal <= 0) {
			this.anchorNanos = System.nanoTime();
			return 0;
		}
		if (normal <= maxSamples) {
			// Full-rate frame: exact clock coverage, as before.
			this.anchorNanos = System.nanoTime();
			return Math.min(normal, Math.max(freeSamples, 0));
		}
		// Slow frame (unfocused window) or replay seek: emit the wall-clock debt instead of
		// going silent, sized to the free buffer so the write never blocks the frame.
		final long now = System.nanoTime();
		final long due = (now - this.anchorNanos) * (long) this.sampleRate / 1_000_000_000L;
		final int samples = (int) Math.min(Math.max(due, 0L), (long) Math.max(freeSamples, 0));
		this.anchorNanos += (long) samples * 1_000_000_000L / (long) this.sampleRate;
		return samples;
	}

	/** Forgets all timing state: the next claim re-anchors instead of resyncing. */
	void reset() {
		this.lastClock = Float.NaN;
	}
}

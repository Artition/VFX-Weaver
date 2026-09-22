package dev.vfxweaver.effect;

/**
 * Pure replay-timeline math for a recorded effect event. No Minecraft types, so the same
 * placement rules the replay controller uses can be asserted in a standalone check.
 *
 * <p>An effect is a pure function of the replay time and the tick at which its play action was
 * recorded: before the trigger it is not playing, from the trigger until the timeline duration it
 * is playing at {@code replayTick - triggerTick} (wrapped for a loop), and after that it has
 * ended. Looping and persistent definitions never end.
 */
public final class VFXReplayClock {
	private VFXReplayClock() {
	}

	/** Where a recorded effect is at a given replay time. */
	public enum Phase {
		/** The trigger tick has not been reached yet. */
		BEFORE,
		/** The effect should be playing at its corresponding age. */
		ACTIVE,
		/** The effect has reached the end of its timeline. */
		AFTER
	}

	/**
	 * Whether a change of the replay time is a seek (a rebuild) rather than normal playback.
	 * The first frame is always a seek; a backward move is always a seek (scrubbing back must
	 * drop effects that have not started yet, even one tick at a time while paused); a forward
	 * move is a seek only once it exceeds the threshold, so ordinary playback stays incremental.
	 *
	 * @param previousTick replay time on the previous frame ({@code NaN} before the first frame)
	 * @param newTick      replay time now
	 * @param thresholdTicks forward jump above which the change counts as a seek
	 * @return true when the controller should rebuild the effect set
	 */
	public static boolean isSeek(final double previousTick, final double newTick, final double thresholdTicks) {
		if (Double.isNaN(previousTick)) {
			return true;
		}
		return newTick < previousTick || newTick - previousTick > thresholdTicks;
	}

	/**
	 * Resolves the lifecycle phase of a recorded effect at the given replay time.
	 *
	 * @param replayTick  current replay time in ticks (fractional between ticks)
	 * @param triggerTick replay tick the play action was recorded at
	 * @param duration    effective timeline duration in ticks (ignored when looping/persistent)
	 * @param looping     true when the timeline restarts once it reaches its duration
	 * @param persistent  true when the instance never ends on its own
	 * @return the phase
	 */
	public static Phase phaseAt(final double replayTick, final float triggerTick, final float duration, final boolean looping, final boolean persistent) {
		if (replayTick < triggerTick) {
			return Phase.BEFORE;
		}
		if (looping || persistent) {
			return Phase.ACTIVE;
		}
		return replayTick >= triggerTick + Math.max(duration, 1.0F) ? Phase.AFTER : Phase.ACTIVE;
	}

	/**
	 * Age of an ACTIVE effect in ticks at the given replay time: unwrapped time since the trigger,
	 * wrapped around the duration when the effect loops.
	 *
	 * @param replayTick  current replay time in ticks
	 * @param triggerTick replay tick the play action was recorded at
	 * @param duration    effective timeline duration in ticks
	 * @param looping     true when the timeline restarts once it reaches its duration
	 * @return the age in ticks, never negative
	 */
	public static float ageAt(final double replayTick, final float triggerTick, final float duration, final boolean looping) {
		final float age = Math.max(0.0F, (float) (replayTick - triggerTick));
		if (looping) {
			return age % Math.max(duration, 1.0F);
		}
		return age;
	}
}

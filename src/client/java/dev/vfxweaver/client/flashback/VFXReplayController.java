package dev.vfxweaver.client.flashback;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives VFX effects from the Flashback replay timeline instead of the wall clock. It keeps the
 * recorded VFX events (plays, stops and live edits) with the replay tick each was recorded at and,
 * once per rendered frame, places every effect in the state it should have at the replay's current
 * time.
 *
 * <p>Playback is incremental: an effect is created the frame its trigger tick is reached, at its
 * recorded start time, so its age is always {@code replayTick - triggerTick}. A seek (the replay
 * time jumping by more than one tick) rebuilds the whole set from the recorded events, so scrubbing
 * back and forth shows the same frame and never restarts an effect; effects whose trigger is in the
 * future or already past are simply absent. Pausing the replay freezes the replay time, so the
 * effects hold.
 *
 * <p>Flashback fires the recorded actions again while seeking (from the chunk snapshot or its
 * start), so events are de-duplicated by (trigger tick, effect id) - a re-fired action is the same
 * event, not a new one. Events that Flashback skips over during a large forward seek (it jumps to a
 * snapshot and only replays the destination chunk) cannot be recovered; an effect whose trigger
 * falls in the skipped window will not appear until its chunk is played. This is the one known
 * limitation of the available API.
 *
 * <p>All mutation happens on the render thread (the Flashback action handler hops to it before
 * calling in), so no synchronization is needed.
 */
public final class VFXReplayController {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/flashback");
	private static final VFXReplayController INSTANCE = new VFXReplayController();
	/** Safety cap on the recorded events kept for one replay. */
	private static final int MAX_EVENTS = 4096;
	/** A replay-time jump larger than this many ticks is treated as a seek, not normal playback. */
	private static final double SEEK_THRESHOLD_TICKS = 1.5;

	private final Map<EventKey, PlayEvent> plays = new LinkedHashMap<>();
	private final List<StopEvent> stops = new ArrayList<>();
	private final List<EditEvent> edits = new ArrayList<>();
	/** Instance ids this controller created; removed wholesale when a seek rebuilds the set. */
	private final Set<Long> liveInstances = new HashSet<>();
	private @Nullable Object replayServer;
	private double lastReplayTick = Double.NaN;
	private boolean overflowWarned;

	private VFXReplayController() {
	}

	public static VFXReplayController get() {
		return INSTANCE;
	}

	/** Records a play action at its replay tick. Re-fired events (a seek) are ignored. */
	public void onPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingFunction easing, final @Nullable Vec3 anchor, final int triggerTick) {
		if (triggerTick < 0 || !this.reserve()) {
			return;
		}
		final EventKey key = new EventKey(triggerTick, effectId);
		if (this.plays.containsKey(key)) {
			return;
		}
		this.plays.put(key, new PlayEvent(effectId, triggerTick, durationTicks, params, easing, anchor));
	}

	/** Records a stop action at its replay tick. */
	public void onStop(final Identifier effectId, final int tick) {
		if (tick < 0 || !this.reserve()) {
			return;
		}
		this.stops.add(new StopEvent(effectId, tick));
	}

	/** Records a live set-param edit at its replay tick. */
	public void onSetParam(final Identifier effectId, final String name, final float value, final int tick) {
		if (tick < 0 || !this.reserve()) {
			return;
		}
		this.edits.add(new EditEvent(effectId, tick, EditKind.PARAM, name, 0, value, null, null));
	}

	/** Records a live keyframe edit at its replay tick. */
	public void onKeyframe(final Identifier effectId, final String name, final int time, final float value, final @Nullable EasingFunction easing, final int tick) {
		if (tick < 0 || !this.reserve()) {
			return;
		}
		this.edits.add(new EditEvent(effectId, tick, EditKind.KEYFRAME, name, time, value, easing, null));
	}

	/** Records a live expression swap at its replay tick. */
	public void onSetExpr(final Identifier effectId, final String name, final @Nullable String exprSource, final int tick) {
		if (tick < 0 || !this.reserve()) {
			return;
		}
		this.edits.add(new EditEvent(effectId, tick, EditKind.EXPR, name, 0, 0.0F, null, exprSource));
	}

	/**
	 * Places every recorded effect in the state it should have at {@code replayTick}. Called once
	 * per frame while a replay is being played back.
	 *
	 * @param replayTick current replay time in ticks
	 * @param paused     whether the replay is paused (only affects nothing: a paused replay's time
	 *                   does not change, so the set is left as is)
	 */
	public void apply(final float replayTick, final boolean paused) {
		final Object server = FlashbackCompat.replayServer();
		if (server == null) {
			this.reset();
			return;
		}
		if (server != this.replayServer) {
			this.replayServer = server;
			this.reset();
		}
		if (paused && !Double.isNaN(this.lastReplayTick) && replayTick == this.lastReplayTick) {
			return;
		}
		final boolean seek = Double.isNaN(this.lastReplayTick) || Math.abs(replayTick - this.lastReplayTick) > SEEK_THRESHOLD_TICKS;
		this.lastReplayTick = replayTick;
		if (seek) {
			this.rebuild(replayTick);
		} else {
			this.place(replayTick, false);
		}
	}

	/** Clears the recorded timeline (leaving a replay or opening a different one). */
	private void reset() {
		this.plays.clear();
		this.stops.clear();
		this.edits.clear();
		this.liveInstances.clear();
		this.lastReplayTick = Double.NaN;
	}

	/**
	 * Drops every replay-created effect and the recorded timeline. Called once per client tick
	 * while no replay is open, so a replay's effects never leak into normal gameplay.
	 */
	public void clear() {
		if (this.liveInstances.isEmpty() && this.plays.isEmpty() && this.stops.isEmpty() && this.edits.isEmpty()) {
			return;
		}
		VFXEffectManager.get().removeInstances(this.liveInstances);
		this.reset();
	}

	/** Rebuilds the effect set from the recorded events at the new replay time (a seek). */
	private void rebuild(final float replayTick) {
		VFXEffectManager.get().removeInstances(this.liveInstances);
		VFXEffectManager.get().clearScheduled();
		this.liveInstances.clear();
		for (PlayEvent play : this.plays.values()) {
			play.started = false;
			// An effect already past its trigger when the seek lands must not re-play its sound;
			// one still ahead keeps its chance to play it when the replay reaches the trigger.
			play.soundPlayed = play.soundPlayed || play.triggerTick <= replayTick;
		}
		for (EditEvent edit : this.edits) {
			edit.applied = false;
		}
		for (StopEvent stop : this.stops) {
			stop.applied = false;
		}
		this.place(replayTick, true);
	}

	/** Creates newly-active effects and applies due stops/edits. */
	private void place(final float replayTick, final boolean rebuilding) {
		final VFXEffectManager manager = VFXEffectManager.get();
		for (PlayEvent play : this.plays.values()) {
			final boolean shouldPlay = !this.stoppedBefore(play, replayTick)
				&& manager.replayEffectActive(play.effectId, play.durationTicks, play.triggerTick, replayTick);
			if (play.started) {
				if (!shouldPlay) {
					// The replay moved before the trigger (scrub back) or past the end: drop it.
					manager.removeInstance(play.instanceId);
					this.liveInstances.remove(play.instanceId);
					play.started = false;
				}
				continue;
			}
			if (!shouldPlay) {
				continue;
			}
			final long instanceId = manager.allocateInstanceId();
			manager.playReplay(play.effectId, play.durationTicks, play.triggerTick, instanceId, play.anchor, play.params, play.easing, !rebuilding && !play.soundPlayed);
			play.instanceId = instanceId;
			play.started = true;
			play.soundPlayed = true;
			this.liveInstances.add(instanceId);
		}
		for (EditEvent edit : this.edits) {
			if (edit.applied || edit.tick > replayTick) {
				continue;
			}
			this.applyEdit(edit);
			edit.applied = true;
		}
		for (StopEvent stop : this.stops) {
			if (stop.applied || stop.tick > replayTick) {
				continue;
			}
			manager.stop(stop.effectId);
			stop.applied = true;
		}
	}

	/** Applies one recorded live edit to the running instances of its effect. */
	private void applyEdit(final EditEvent edit) {
		final VFXEffectManager manager = VFXEffectManager.get();
		switch (edit.kind) {
			case PARAM -> manager.applyParam(edit.effectId, edit.name, edit.value);
			case KEYFRAME -> manager.setKeyframe(edit.effectId, edit.name, edit.time, edit.value, edit.easing);
			case EXPR -> manager.setExpression(edit.effectId, edit.name, edit.exprSource);
		}
	}

	/** True when a stop for the same effect falls between the play's trigger and the replay time. */
	private boolean stoppedBefore(final PlayEvent play, final float replayTick) {
		for (StopEvent stop : this.stops) {
			if (stop.effectId.equals(play.effectId) && stop.tick >= play.triggerTick && stop.tick <= replayTick) {
				return true;
			}
		}
		return false;
	}

	/** Bounds the recorded event set; returns false when the cap is reached (warned once). */
	private boolean reserve() {
		if (this.plays.size() + this.stops.size() + this.edits.size() < MAX_EVENTS) {
			return true;
		}
		if (!this.overflowWarned) {
			this.overflowWarned = true;
			VFXLog.warnOnce(LOGGER, "flashback-replay-events", "VFX replay event limit ({}) reached; further recorded effects are ignored", MAX_EVENTS);
		}
		return false;
	}

	/** Stable identity of a recorded play: its trigger tick and effect id. */
	private record EventKey(int triggerTick, Identifier effectId) {
	}

	/** One recorded play action. */
	private static final class PlayEvent {
		private final Identifier effectId;
		private final float triggerTick;
		private final int durationTicks;
		private final Map<String, Float> params;
		private final @Nullable EasingFunction easing;
		private final @Nullable Vec3 anchor;
		private long instanceId;
		private boolean started;
		private boolean soundPlayed;

		private PlayEvent(final Identifier effectId, final int triggerTick, final int durationTicks, final Map<String, Float> params, final @Nullable EasingFunction easing, final @Nullable Vec3 anchor) {
			this.effectId = effectId;
			this.triggerTick = triggerTick;
			this.durationTicks = durationTicks;
			this.params = params;
			this.easing = easing;
			this.anchor = anchor;
		}
	}

	/** One recorded stop action. */
	private static final class StopEvent {
		private final Identifier effectId;
		private final int tick;
		private boolean applied;

		private StopEvent(final Identifier effectId, final int tick) {
			this.effectId = effectId;
			this.tick = tick;
		}
	}

	/** One recorded live edit. */
	private static final class EditEvent {
		private final Identifier effectId;
		private final int tick;
		private final EditKind kind;
		private final String name;
		private final int time;
		private final float value;
		private final @Nullable EasingFunction easing;
		private final @Nullable String exprSource;
		private boolean applied;

		private EditEvent(final Identifier effectId, final int tick, final EditKind kind, final String name, final int time, final float value, final @Nullable EasingFunction easing, final @Nullable String exprSource) {
			this.effectId = effectId;
			this.tick = tick;
			this.kind = kind;
			this.name = name;
			this.time = time;
			this.value = value;
			this.easing = easing;
			this.exprSource = exprSource;
		}
	}

	/** Kind of a recorded live edit. */
	private enum EditKind {
		PARAM,
		KEYFRAME,
		EXPR
	}
}

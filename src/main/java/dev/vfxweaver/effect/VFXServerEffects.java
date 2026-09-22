package dev.vfxweaver.effect;

import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.platform.VFXNetwork;
import dev.vfxweaver.platform.VFXPlatform;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side memory of effects sent to each player, so an effect is re-applied when the player
 * reconnects (or joins) while it is still running. {@code VFXAPI.sendEffect} records every play;
 * on player join the still-active ones are re-sent with their elapsed offset.
 *
 * <p>Keyed per {@code player -> effectId}, keeping the latest play of each effect (a repeat
 * replaces the previous entry — matching how {@code /vfx stop} stops every instance of an id).
 * Persistent (negative duration) and looping effects are always re-applied; finite ones only while
 * their duration has not elapsed.
 *
 * <p>Time keeps running while a player is offline: on re-join an effect's age is the full
 * wall-clock time since it started, so an effect that was 30 % through comes back further along by
 * however long the player was away. A finite effect that reached the end of its timeline during the
 * absence is pruned instead of resurrected; a looping one resumes at its current phase (the client
 * wraps the elapsed time modulo the period) and a persistent one comes back as if it had never
 * stopped. The age is computed from the recorded start time, so no frozen clock is stored.
 *
 * <p>The store is bounded in two independent ways: per player ({@link #MAX_EFFECTS_PER_PLAYER}) and
 * globally ({@link #MAX_TRACKED_PLAYERS} distinct UUIDs, the least recently touched evicted first).
 * The global cap is the load-bearing bound — it caps the persistent effects of players who never
 * return; no separate offline expiry is needed now that the age no longer depends on a stored
 * disconnect instant.
 *
 * <p>Everything is disabled during Flashback replay playback: the replay already carries the
 * effects (as packets or custom actions) and re-injecting them from the live registry would
 * double them. The guard is reflective so this mod stays free of a Flashback dependency.
 */
public final class VFXServerEffects {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/server-effects");
	/** Safety cap on tracked effects per player (external input, see AGENTS.md). */
	private static final int MAX_EFFECTS_PER_PLAYER = 32;
	/** Safety cap on recorded keyframes per effect (external input, see AGENTS.md). */
	private static final int MAX_KEYS_PER_EFFECT = 32;
	/**
	 * Safety cap on distinct players with remembered effects. A long-lived server with many unique
	 * UUIDs evicts the least recently touched player instead of growing forever. This is the
	 * load-bearing bound: it caps the persistent effects of players who never return.
	 */
	private static final int MAX_TRACKED_PLAYERS = 256;
	private static final VFXServerEffects INSTANCE = new VFXServerEffects();

	/**
	 * Reflective handle to {@code Flashback.isInReplay()}, resolved once on first use so the
	 * per-call {@code record}/{@code stop}/{@code applyTo} path skips the costly lookup.
	 */
	private static final Method FLASHBACK_IS_IN_REPLAY = resolveIsInReplay();

	private final Map<UUID, PlayerEffects> byPlayer = new HashMap<>();

	private VFXServerEffects() {
	}

	public static VFXServerEffects get() {
		return INSTANCE;
	}

	private static @Nullable Method resolveIsInReplay() {
		try {
			if (!VFXPlatform.isModLoaded("flashback")) {
				return null;
			}
			Class<?> flashback = Class.forName("com.moulberry.flashback.Flashback");
			return flashback.getMethod("isInReplay");
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * True while a Flashback replay is being played back (the effects are already being replayed
	 * by Flashback itself). {@code false} when Flashback is not installed.
	 */
	private static boolean flashbackIsReplaying() {
		Method isInReplay = FLASHBACK_IS_IN_REPLAY;
		if (isInReplay == null) {
			return false;
		}
		try {
			return (Boolean) isInReplay.invoke(null);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * A recorded effect play: everything needed to re-send it later, plus the wall-clock time it
	 * started at so the age can be computed. Wall clock (not the server tick counter) is used
	 * because the static memory outlives the server instance in singleplayer - the tick counter
	 * resets on every world reload, which made elapsed time collapse to zero and replayed effects
	 * never expire. {@code keys} carries the keyframes added after the play (via {@code /vfx key})
	 * so a reconnect resumes the same animation instead of restarting from the definition defaults.
	 *
	 * @param neverExpires true for persistent and looping definitions: they are re-applied no
	 *                     matter how long the player was away
	 */
	private record ActiveEffect(
		Identifier effectId,
		int durationTicks,
		boolean neverExpires,
		long instanceId,
		@Nullable Vec3 worldPos,
		List<UUID> entityUuids,
		Map<String, Float> params,
		String easing,
		long startMillis,
		List<RecordedKey> keys
	) {
	}

	/**
	 * One keyframe applied to a recorded effect after it started.
	 */
	private record RecordedKey(String param, float time, float value, String easing) {
	}

	/**
	 * Per-player memory: the recorded effects plus the wall-clock instant the player was last seen
	 * (record/join/disconnect), used to pick an eviction victim.
	 */
	private static final class PlayerEffects {
		private final Map<Identifier, ActiveEffect> effects = new HashMap<>();
		/** Wall-clock millis of the last record/join/disconnect, used to pick an eviction victim. */
		private long lastTouchedMillis;
	}

	/**
	 * Records an effect play sent to the player. Replaces any previous entry of the same effect id.
	 */
	public void record(
		final ServerPlayer player,
		final Identifier effectId,
		final int durationTicks,
		final long instanceId,
		final @Nullable Vec3 worldPos,
		final List<UUID> entityUuids,
		final Map<String, Float> params,
		final String easing
	) {
		if (flashbackIsReplaying()) {
			return;
		}
		final long now = System.currentTimeMillis();
		final PlayerEffects state = playerState(player.getUUID(), now);
		state.lastTouchedMillis = now;
		final Map<Identifier, ActiveEffect> effects = state.effects;
		if (!effects.containsKey(effectId) && effects.size() >= MAX_EFFECTS_PER_PLAYER) {
			// Oldest entries get evicted so a misbehaving caller cannot pin unbounded memory.
			Iterator<ActiveEffect> it = effects.values().iterator();
			if (it.hasNext()) {
				it.next();
				it.remove();
			}
		}
		effects.put(effectId, new ActiveEffect(effectId, durationTicks, neverExpires(effectId, durationTicks), instanceId, worldPos, List.copyOf(entityUuids), Map.copyOf(params), easing, now, List.of()));
	}

	/**
	 * Records a keyframe applied to an already-recorded effect play (mirrors
	 * {@code VFXAPI.sendKeyframe}). Replaces any key of the same parameter at the same time.
	 * Ignored when the effect has no recorded play for this player.
	 */
	public void recordKeyframe(final ServerPlayer player, final Identifier effectId, final String param, final float time, final float value, final String easing) {
		if (flashbackIsReplaying()) {
			return;
		}
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		final ActiveEffect active = state == null ? null : state.effects.get(effectId);
		if (active == null) {
			return;
		}
		List<RecordedKey> keys = new ArrayList<>(active.keys());
		keys.removeIf(key -> key.param().equals(param) && Float.compare(key.time(), time) == 0);
		if (keys.size() >= MAX_KEYS_PER_EFFECT) {
			// Oldest key evicted first so key spam cannot grow the entry unbounded.
			keys.remove(0);
		}
		keys.add(new RecordedKey(param, time, value, easing));
		state.effects.put(effectId, new ActiveEffect(active.effectId(), active.durationTicks(), active.neverExpires(), active.instanceId(), active.worldPos(), active.entityUuids(), active.params(), active.easing(), active.startMillis(), List.copyOf(keys)));
	}

	/**
	 * Drops every recorded instance of the effect for the player (mirrors {@code sendStop}).
	 */
	public void stop(final ServerPlayer player, final Identifier effectId) {
		if (flashbackIsReplaying()) {
			return;
		}
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		if (state != null) {
			state.effects.remove(effectId);
		}
	}

	/**
	 * Drops the recorded instance with the given id for the player (mirrors the instance-targeted
	 * {@code sendStop}).
	 */
	public void stop(final ServerPlayer player, final Identifier effectId, final long instanceId) {
		if (flashbackIsReplaying()) {
			return;
		}
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		if (state == null) {
			return;
		}
		final ActiveEffect active = state.effects.get(effectId);
		if (active != null && active.instanceId() == instanceId) {
			state.effects.remove(effectId);
		}
	}

	/**
	 * The effect ids currently recorded for the player, as a bounded copy. Used by
	 * {@code VFXAPI.sendStopAll} to stop every active effect through the existing per-effect stop
	 * payload. Empty when the player has no recorded effect.
	 *
	 * @param player the player to enumerate
	 * @return a copy of the recorded effect ids
	 */
	public Set<Identifier> activeEffects(final ServerPlayer player) {
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		return state == null || state.effects.isEmpty() ? Set.of() : Set.copyOf(state.effects.keySet());
	}

	/**
	 * Keeps the player's effect memory (time keeps running while they are away) and prunes anything
	 * already expired, instead of wiping the state. The entry is kept, bounded globally by
	 * {@link #MAX_TRACKED_PLAYERS}, so a re-join can resume it.
	 *
	 * @param player the player that left
	 */
	public void onPlayerDisconnect(final ServerPlayer player) {
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		if (state == null) {
			return;
		}
		final long now = System.currentTimeMillis();
		state.lastTouchedMillis = now;
		pruneExpiredEffects(state, now);
		if (state.effects.isEmpty()) {
			this.byPlayer.remove(player.getUUID());
		}
	}

	/**
	 * Re-sends the still-active effects to a (re)joining player. Each play carries the elapsed
	 * offset since the effect started, so offline time counts and the client resumes at the age the
	 * effect would have reached (a looping one wraps to its current phase), followed by the recorded
	 * keyframes so runtime edits survive the reconnect. Called after the datapack definitions have
	 * been synced so the client can resolve the ids. Entries that finished during the absence are
	 * pruned on the way.
	 */
	public void applyTo(final ServerPlayer player) {
		if (flashbackIsReplaying()) {
			return;
		}
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		if (state == null || state.effects.isEmpty()) {
			return;
		}
		// Time keeps running offline: the age is the full wall-clock elapsed since the effect
		// started, not frozen at the disconnect instant.
		final long now = System.currentTimeMillis();
		final Iterator<Map.Entry<Identifier, ActiveEffect>> it = state.effects.entrySet().iterator();
		while (it.hasNext()) {
			final ActiveEffect active = it.next().getValue();
			final int elapsed = elapsedTicksAt(active.startMillis(), now);
			final int remaining = active.neverExpires()
				? active.durationTicks()
				: remainingTicksFor(active.durationTicks(), elapsed, lastKeyTime(active));
			if (!active.neverExpires() && remaining < 0) {
				it.remove();
				continue;
			}
			VFXNetwork.sendToPlayer(player, VFXTriggerPayload.play(
				active.effectId(), remaining, elapsed, active.instanceId(), active.worldPos(), active.entityUuids(), active.params(), active.easing()
			));
			for (final RecordedKey key : active.keys()) {
				VFXNetwork.sendToPlayer(player, VFXTriggerPayload.keyframe(
					active.effectId(), key.param(), (int) key.time(), key.value(), key.easing()
				));
			}
			final VFXDefinition definition = VFXDefinitionManager.get().get(active.effectId());
			if (definition != null) {
				// Re-establish the scoreboard subscriptions the effect had (they were released when
				// the player left); the remaining duration keeps the subscription expiry correct.
				VFXScoreboardSync.onEffectPlayed(player, definition, remaining);
			}
		}
		state.lastTouchedMillis = System.currentTimeMillis();
		if (state.effects.isEmpty()) {
			this.byPlayer.remove(player.getUUID());
		}
	}

	/**
	 * Elapsed ticks between the effect start and the given wall-clock instant (1 tick = 50 ms).
	 */
	static int elapsedTicksAt(final long startMillis, final long resumeAtMillis) {
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (resumeAtMillis - startMillis) / 50L));
	}

	/**
	 * Ticks a finite effect still has left, or {@code -1} when it has expired. Keyframes past the
	 * nominal duration extend the effective lifetime up to the last key.
	 */
	static int remainingTicksFor(final int durationTicks, final int elapsedTicks, final int lastKeyTime) {
		if (durationTicks < 0) {
			return durationTicks;
		}
		final float effectiveDuration = Math.max(durationTicks, lastKeyTime);
		final long remaining = (long) effectiveDuration - elapsedTicks;
		return remaining <= 0L ? -1 : (int) Math.min(remaining, Integer.MAX_VALUE);
	}

	/** The last keyframe time of a recorded effect, or {@code 0} when it has none. */
	private static int lastKeyTime(final ActiveEffect active) {
		int last = 0;
		for (final RecordedKey key : active.keys()) {
			last = Math.max(last, (int) key.time());
		}
		return last;
	}

	/** True when the effect never ends on its own (persistent definition or negative duration). */
	private static boolean neverExpires(final Identifier effectId, final int durationTicks) {
		if (durationTicks < 0) {
			return true;
		}
		final VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
		return definition != null && (definition.isPersistent() || definition.isLoop());
	}

	/** Removes already-finished finite effects from one player's memory. */
	private static void pruneExpiredEffects(final PlayerEffects state, final long now) {
		state.effects.values().removeIf(active ->
			!active.neverExpires()
				&& remainingTicksFor(active.durationTicks(), elapsedTicksAt(active.startMillis(), now), lastKeyTime(active)) < 0
		);
	}

	/** Returns (creating and bounding if needed) the memory for one player. */
	private PlayerEffects playerState(final UUID uuid, final long now) {
		PlayerEffects state = this.byPlayer.get(uuid);
		if (state == null) {
			if (this.byPlayer.size() >= MAX_TRACKED_PLAYERS) {
				evictOldest();
			}
			state = new PlayerEffects();
			state.lastTouchedMillis = now;
			this.byPlayer.put(uuid, state);
		}
		return state;
	}

	/** Evicts the least recently touched player when the global cap is hit. */
	private void evictOldest() {
		UUID victim = null;
		long oldest = Long.MAX_VALUE;
		for (final Map.Entry<UUID, PlayerEffects> entry : this.byPlayer.entrySet()) {
			final long touched = entry.getValue().lastTouchedMillis;
			if (touched < oldest) {
				oldest = touched;
				victim = entry.getKey();
			}
		}
		if (victim != null) {
			final PlayerEffects removed = this.byPlayer.remove(victim);
			LOGGER.info("Evicting player {} from effect memory ({} tracked players limit, {} effect(s))", victim, MAX_TRACKED_PLAYERS, removed == null ? 0 : removed.effects.size());
		}
	}
}

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
 * <p>A player's age is <b>frozen at the instant they disconnect</b>, so an effect that was 30 %
 * through comes back 30 % through no matter how long the player was away; time spent offline never
 * advances an effect. Finite effects that already reached the end of their timeline are not
 * resurrected.
 *
 * <p>The store is bounded in two independent ways: per player ({@link #MAX_EFFECTS_PER_PLAYER}) and
 * globally ({@link #MAX_TRACKED_PLAYERS} distinct UUIDs, with entries that have been offline longer
 * than {@link #MAX_OFFLINE_MILLIS} pruned first). This keeps the deliberate leak fix of the
 * previous disconnect wipe while still letting a player's effects survive a re-login.
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
	 * UUIDs evicts the least recently active player instead of growing forever.
	 */
	private static final int MAX_TRACKED_PLAYERS = 256;
	/**
	 * A disconnected player's memory is kept for this long (wall clock) before being pruned. One
	 * day covers "logged out for the night and came back"; finite effects expire by their own
	 * remaining time long before this, so only persistent/looping ones use the full window. The
	 * value is a safety net, the real bound is {@link #MAX_TRACKED_PLAYERS}.
	 */
	static final long MAX_OFFLINE_MILLIS = 24L * 60L * 60L * 1000L;
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
	 * Per-player memory: the recorded effects plus the wall-clock instant the age is frozen at.
	 */
	private static final class PlayerEffects {
		private final Map<Identifier, ActiveEffect> effects = new HashMap<>();
		/** Wall-clock millis the age is frozen at, or {@code 0} while the player is online. */
		private long disconnectedAtMillis;
		/** Wall-clock millis of the last record/join, used to pick an eviction victim. */
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
		state.disconnectedAtMillis = 0L;
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
	 * Freezes the player's effect ages at the moment they left and prunes anything already expired,
	 * instead of wiping the state. The entry is kept (bounded globally by
	 * {@link #MAX_TRACKED_PLAYERS} and {@link #MAX_OFFLINE_MILLIS}) so a re-join can resume it.
	 *
	 * @param player the player that left
	 */
	public void onPlayerDisconnect(final ServerPlayer player) {
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		if (state == null) {
			return;
		}
		final long now = System.currentTimeMillis();
		state.disconnectedAtMillis = now;
		state.lastTouchedMillis = now;
		pruneExpiredEffects(state, now);
		if (state.effects.isEmpty()) {
			this.byPlayer.remove(player.getUUID());
			return;
		}
		pruneExpired(now);
	}

	/**
	 * Freezes every still-tracked player when the server stops. A singleplayer world reload does not
	 * fire a disconnect, so without this the offline time would advance the effects' age.
	 */
	public void onServerStopping() {
		final long now = System.currentTimeMillis();
		final Iterator<Map.Entry<UUID, PlayerEffects>> it = this.byPlayer.entrySet().iterator();
		while (it.hasNext()) {
			final PlayerEffects state = it.next().getValue();
			if (state.disconnectedAtMillis == 0L) {
				state.disconnectedAtMillis = now;
			}
			pruneExpiredEffects(state, now);
			if (state.effects.isEmpty()) {
				it.remove();
			}
		}
	}

	/**
	 * Re-sends the still-active effects to a (re)joining player. Each play carries the elapsed
	 * offset (frozen at the disconnect instant, so offline time does not advance it) so the client
	 * resumes mid-animation, followed by the recorded keyframes so runtime edits survive the
	 * reconnect. Called after the datapack definitions have been synced so the client can resolve
	 * the ids. Already-finished finite entries are pruned on the way.
	 */
	public void applyTo(final ServerPlayer player) {
		if (flashbackIsReplaying()) {
			return;
		}
		final PlayerEffects state = this.byPlayer.get(player.getUUID());
		if (state == null || state.effects.isEmpty()) {
			return;
		}
		// While offline the age is frozen at the disconnect instant; an effect that was 30 % through
		// comes back 30 % through regardless of how long the player was away.
		final long now = resumeClock(state.disconnectedAtMillis, System.currentTimeMillis());
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
		state.disconnectedAtMillis = 0L;
		state.lastTouchedMillis = System.currentTimeMillis();
		if (state.effects.isEmpty()) {
			this.byPlayer.remove(player.getUUID());
		}
	}

	/**
	 * The clock an effect's age is measured against: the frozen disconnect instant while the player
	 * is offline, otherwise the current time.
	 */
	static long resumeClock(final long disconnectedAtMillis, final long nowMillis) {
		return disconnectedAtMillis != 0L ? disconnectedAtMillis : nowMillis;
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

	/**
	 * True when a disconnected player's memory has outlived {@link #MAX_OFFLINE_MILLIS} and should
	 * be pruned.
	 */
	static boolean isOfflineExpired(final long disconnectedAtMillis, final long nowMillis) {
		return disconnectedAtMillis != 0L && nowMillis - disconnectedAtMillis > MAX_OFFLINE_MILLIS;
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
			pruneExpired(now);
			if (this.byPlayer.size() >= MAX_TRACKED_PLAYERS) {
				evictOldest();
			}
			state = new PlayerEffects();
			state.lastTouchedMillis = now;
			this.byPlayer.put(uuid, state);
		}
		return state;
	}

	/** Drops every player whose offline memory has outlived {@link #MAX_OFFLINE_MILLIS}. */
	private void pruneExpired(final long now) {
		final Iterator<Map.Entry<UUID, PlayerEffects>> it = this.byPlayer.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<UUID, PlayerEffects> entry = it.next();
			if (isOfflineExpired(entry.getValue().disconnectedAtMillis, now)) {
				it.remove();
				LOGGER.info("Forgetting {} effect(s) for player {} after {} ms offline (offline memory limit)", entry.getValue().effects.size(), entry.getKey(), MAX_OFFLINE_MILLIS);
			}
		}
	}

	/** Evicts the longest-offline (else least recently touched) player when the global cap is hit. */
	private void evictOldest() {
		UUID victim = null;
		long bestRank = Long.MAX_VALUE;
		for (final Map.Entry<UUID, PlayerEffects> entry : this.byPlayer.entrySet()) {
			final PlayerEffects state = entry.getValue();
			// Disconnected players rank below online ones (their disconnect instant is far below
			// Long.MAX_VALUE - lastTouched), and within each group the oldest ranks first.
			final long rank = state.disconnectedAtMillis != 0L
				? state.disconnectedAtMillis
				: Long.MAX_VALUE - state.lastTouchedMillis;
			if (rank < bestRank) {
				bestRank = rank;
				victim = entry.getKey();
			}
		}
		if (victim != null) {
			final PlayerEffects removed = this.byPlayer.remove(victim);
			LOGGER.info("Evicting player {} from effect memory ({} tracked players limit, {} effect(s))", victim, MAX_TRACKED_PLAYERS, removed == null ? 0 : removed.effects.size());
		}
	}
}

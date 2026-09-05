package dev.vfxweaver.effect;

import dev.vfxweaver.network.VFXTriggerPayload;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side memory of effects sent to each player, so an effect is re-applied when the player
 * reconnects (or joins) while it is still running. {@code VFXAPI.sendEffect} records every play;
 * on player join the still-active ones are re-sent with their remaining duration.
 *
 * <p>Keyed per {@code player -> effectId}, keeping the latest play of each effect (a repeat
 * replaces the previous entry — matching how {@code /vfx stop} stops every instance of an id).
 * Persistent (negative duration) effects are always re-applied; finite ones only while their
 * duration has not elapsed. The map is bounded per player (see {@link #MAX_EFFECTS_PER_PLAYER}).
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
	private static final VFXServerEffects INSTANCE = new VFXServerEffects();

	/**
	 * Reflective handle to {@code Flashback.isInReplay()}, resolved once on first use so the
	 * per-call {@code record}/{@code stop}/{@code applyTo} path skips the costly lookup.
	 */
	private static final Method FLASHBACK_IS_IN_REPLAY = resolveIsInReplay();

	private final Map<UUID, Map<Identifier, ActiveEffect>> byPlayer = new HashMap<>();

	private VFXServerEffects() {
	}

	public static VFXServerEffects get() {
		return INSTANCE;
	}

	private static @Nullable Method resolveIsInReplay() {
		if (!FabricLoader.getInstance().isModLoaded("flashback")) {
			return null;
		}
		try {
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
	 * started at so the remaining duration can be computed. Wall clock (not the server tick
	 * counter) is used because the static memory outlives the server instance in singleplayer -
	 * the tick counter resets on every world reload, which made elapsed time collapse to zero and
	 * replayed effects never expire. {@code keys} carries the keyframes added after the play (via
	 * {@code /vfx key}) so a reconnect resumes the same animation instead of restarting from the
	 * definition defaults.
	 */
	private record ActiveEffect(
		Identifier effectId,
		int durationTicks,
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
		Map<Identifier, ActiveEffect> effects = this.byPlayer.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
		if (!effects.containsKey(effectId) && effects.size() >= MAX_EFFECTS_PER_PLAYER) {
			// Oldest entries get evicted so a misbehaving caller cannot pin unbounded memory.
			Iterator<ActiveEffect> it = effects.values().iterator();
			if (it.hasNext()) {
				it.next();
				it.remove();
			}
		}
		effects.put(effectId, new ActiveEffect(effectId, durationTicks, instanceId, worldPos, List.copyOf(entityUuids), Map.copyOf(params), easing, System.currentTimeMillis(), List.of()));
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
		Map<Identifier, ActiveEffect> effects = this.byPlayer.get(player.getUUID());
		ActiveEffect active = effects == null ? null : effects.get(effectId);
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
		effects.put(effectId, new ActiveEffect(active.effectId(), active.durationTicks(), active.instanceId(), active.worldPos(), active.entityUuids(), active.params(), active.easing(), active.startMillis(), List.copyOf(keys)));
	}

	/**
	 * Drops every recorded instance of the effect for the player (mirrors {@code sendStop}).
	 */
	public void stop(final ServerPlayer player, final Identifier effectId) {
		if (flashbackIsReplaying()) {
			return;
		}
		Map<Identifier, ActiveEffect> effects = this.byPlayer.get(player.getUUID());
		if (effects != null) {
			effects.remove(effectId);
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
		Map<Identifier, ActiveEffect> effects = this.byPlayer.get(player.getUUID());
		if (effects == null) {
			return;
		}
		ActiveEffect active = effects.get(effectId);
		if (active != null && active.instanceId() == instanceId) {
			effects.remove(effectId);
		}
	}

	/**
	 * Re-sends the still-active effects to a (re)joining player. Each play carries the elapsed
	 * offset so the client resumes mid-animation, followed by the recorded keyframes so runtime
	 * edits survive the reconnect. Called after the datapack definitions have been synced so the
	 * client can resolve the ids. Expired entries are pruned on the way.
	 */
	public void applyTo(final ServerPlayer player) {
		if (flashbackIsReplaying()) {
			return;
		}
		Map<Identifier, ActiveEffect> effects = this.byPlayer.get(player.getUUID());
		if (effects == null || effects.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Identifier, ActiveEffect>> it = effects.entrySet().iterator();
		while (it.hasNext()) {
			ActiveEffect active = it.next().getValue();
			boolean persistent = active.durationTicks() < 0;
			int remaining = remainingTicks(active, now);
			if (!persistent && remaining < 0) {
				it.remove();
				continue;
			}
			int elapsed = persistent ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (now - active.startMillis()) / 50L));
			ServerPlayNetworking.send(player, VFXTriggerPayload.play(
				active.effectId(), remaining, elapsed, active.instanceId(), active.worldPos(), active.entityUuids(), active.params(), active.easing()
			));
			for (RecordedKey key : active.keys()) {
				ServerPlayNetworking.send(player, VFXTriggerPayload.keyframe(
					active.effectId(), key.param(), (int) key.time(), key.value(), key.easing()
				));
			}
		}
		if (effects.isEmpty()) {
			this.byPlayer.remove(player.getUUID());
		}
	}

	/**
	 * The number of ticks the effect still has left, or {@code -1} when it has expired. Persistent
	 * effects (negative duration) never expire. Keyframes past the nominal duration extend the
	 * effective lifetime up to the last key.
	 */
	private static int remainingTicks(final ActiveEffect active, final long now) {
		if (active.durationTicks() < 0) {
			return active.durationTicks();
		}
		float effectiveDuration = active.durationTicks();
		for (RecordedKey key : active.keys()) {
			effectiveDuration = Math.max(effectiveDuration, key.time());
		}
		// `now` is wall-clock millis; convert elapsed time to ticks (1 tick = 50 ms).
		long elapsedTicks = Math.max(0L, (now - active.startMillis()) / 50L);
		long remaining = (long) effectiveDuration - elapsedTicks;
		if (remaining <= 0L) {
			return -1;
		}
		return (int) Math.min(remaining, Integer.MAX_VALUE);
	}
}
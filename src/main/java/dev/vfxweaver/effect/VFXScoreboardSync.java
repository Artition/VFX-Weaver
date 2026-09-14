package dev.vfxweaver.effect;

import dev.vfxweaver.network.VFXScoreboardPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side scoreboard synchronization for {@code scoreboard} bindings.
 *
 * <p>The vanilla client only mirrors objectives that are displayed in a slot, so a client-side
 * {@code scoreboard} bind is unreliable. Instead the server tracks the {@code (objective, holder)}
 * pairs referenced by the effects it sends to each player and pushes their values (diffed once per
 * tick). The client keeps a cache ({@code VFXScoreboardCache}); the vanilla mirror stays a fallback.
 *
 * <p>Pairs are derived from the same {@link VFXDefinition} the server already sends, so the client
 * never names an objective itself — there is no C2S channel and no trust surface to validate.
 * All collections are bounded (see {@link #MAX_TRACKED_PER_PLAYER}).
 */
public final class VFXScoreboardSync {
	/** Safety cap on tracked pairs per player (external input, see AGENTS.md). */
	public static final int MAX_TRACKED_PER_PLAYER = 128;
	/** Max updates per packet (shared with the payload cap). */
	public static final int MAX_UPDATES_PER_TICK = VFXScoreboardPayload.MAX_UPDATES;
	/** Keep a subscription this many ticks past the effect duration to avoid flicker on the tail. */
	private static final long GRACE_TICKS = 40L;

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/scoreboard-sync");
	private static final Map<UUID, PlayerState> STATES = new HashMap<>();

	private VFXScoreboardSync() {
	}

	private record ScoreKey(String objective, String holder) {
	}

	private static final class Tracked {
		private int refs;
		private long expiryTick;
		/** {@code null} = never sent yet. */
		private @Nullable OptionalInt lastSent;
	}

	private static final class PlayerState {
		private final Map<ScoreKey, Tracked> tracked = new HashMap<>();
		private boolean warnedCap;
	}

	/**
	 * Tracks the scoreboard bindings of a definition just played to a player. Call next to every
	 * {@code VFXTriggerPayload.play(...)}.
	 *
	 * @param durationTicks the effect duration in ticks, or a negative value for a persistent effect
	 */
	public static void onEffectPlayed(final ServerPlayer player, final VFXDefinition definition, final long durationTicks) {
		List<BoundParam> bindings = definition.scoreboardBindings();
		if (bindings.isEmpty()) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		PlayerState state = STATES.computeIfAbsent(player.getUUID(), uuid -> new PlayerState());
		long expiry = durationTicks < 0L
			? Long.MAX_VALUE
			: server.getTickCount() + Math.max(1L, durationTicks) + GRACE_TICKS;
		boolean added = false;
		for (BoundParam binding : bindings) {
			ScoreKey key = new ScoreKey(binding.objective(), resolveHolder(player, binding.holder()));
			Tracked tracked = state.tracked.get(key);
			if (tracked == null) {
				if (state.tracked.size() >= MAX_TRACKED_PER_PLAYER) {
					if (!state.warnedCap) {
						state.warnedCap = true;
						LOGGER.warn("Player {} hit the {} tracked scoreboard bindings limit; extra bindings stay at 0",
							player.getScoreboardName(), MAX_TRACKED_PER_PLAYER);
					}
					continue;
				}
				tracked = new Tracked();
				state.tracked.put(key, tracked);
				added = true;
			}
			tracked.refs++;
			tracked.expiryTick = Math.max(tracked.expiryTick, expiry);
		}
		if (added) {
			// Send the first values immediately instead of waiting for the tick hook.
			List<VFXScoreboardPayload.ScoreUpdate> updates = collectChanges(server, state, null);
			if (updates != null) {
				ServerPlayNetworking.send(player, new VFXScoreboardPayload(updates));
			}
		}
	}

	/**
	 * Releases the scoreboard bindings of a definition stopped for a player. Call next to a STOP
	 * trigger when the definition is known; a persistent subscription without this call is only
	 * released when the player disconnects, so prefer calling it.
	 */
	public static void onEffectStopped(final ServerPlayer player, final VFXDefinition definition) {
		PlayerState state = STATES.get(player.getUUID());
		if (state == null) {
			return;
		}
		List<VFXScoreboardPayload.ScoreUpdate> removals = null;
		for (BoundParam binding : definition.scoreboardBindings()) {
			ScoreKey key = new ScoreKey(binding.objective(), resolveHolder(player, binding.holder()));
			Tracked tracked = state.tracked.get(key);
			if (tracked != null && --tracked.refs <= 0) {
				state.tracked.remove(key);
				if (removals == null) {
					removals = new ArrayList<>();
				}
				removals.add(new VFXScoreboardPayload.ScoreUpdate(key.objective(), key.holder(), OptionalInt.empty()));
			}
		}
		if (removals != null) {
			ServerPlayNetworking.send(player, new VFXScoreboardPayload(removals));
		}
		if (state.tracked.isEmpty()) {
			STATES.remove(player.getUUID());
		}
	}

	/** Registered on {@code ServerTickEvents.END_SERVER_TICK}. */
	public static void tick(final MinecraftServer server) {
		if (STATES.isEmpty()) {
			return;
		}
		long now = server.getTickCount();
		Iterator<Map.Entry<UUID, PlayerState>> players = STATES.entrySet().iterator();
		while (players.hasNext()) {
			Map.Entry<UUID, PlayerState> entry = players.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			PlayerState state = entry.getValue();
			if (player == null) {
				players.remove();
				continue;
			}
			List<VFXScoreboardPayload.ScoreUpdate> updates = null;
			// 1) expired subscriptions: release and tell the client to drop the cache entry
			Iterator<Map.Entry<ScoreKey, Tracked>> tracked = state.tracked.entrySet().iterator();
			while (tracked.hasNext()) {
				Map.Entry<ScoreKey, Tracked> trackedEntry = tracked.next();
				if (now > trackedEntry.getValue().expiryTick) {
					if (updates == null) {
						updates = new ArrayList<>();
					}
					updates.add(new VFXScoreboardPayload.ScoreUpdate(trackedEntry.getKey().objective(), trackedEntry.getKey().holder(), OptionalInt.empty()));
					tracked.remove();
				}
			}
			// 2) changed values
			updates = collectChanges(server, state, updates);
			if (updates != null) {
				ServerPlayNetworking.send(player, new VFXScoreboardPayload(updates));
			}
			if (state.tracked.isEmpty()) {
				players.remove();
			}
		}
	}

	/** Registered on {@code ServerPlayConnectionEvents.DISCONNECT}. */
	public static void onPlayerLeft(final ServerPlayer player) {
		STATES.remove(player.getUUID());
	}

	/** Registered on {@code ServerLifecycleEvents.SERVER_STOPPED}. */
	public static void clear() {
		STATES.clear();
	}

	private static String resolveHolder(final ServerPlayer player, final @Nullable String holder) {
		return holder != null ? holder : player.getScoreboardName();
	}

	private static @Nullable List<VFXScoreboardPayload.ScoreUpdate> collectChanges(
		final MinecraftServer server,
		final PlayerState state,
		final @Nullable List<VFXScoreboardPayload.ScoreUpdate> updates
	) {
		List<VFXScoreboardPayload.ScoreUpdate> out = updates;
		for (Map.Entry<ScoreKey, Tracked> entry : state.tracked.entrySet()) {
			if (out != null && out.size() >= MAX_UPDATES_PER_TICK) {
				break; // the rest goes out on the next tick
			}
			Tracked tracked = entry.getValue();
			OptionalInt current = readScore(server, entry.getKey());
			if (tracked.lastSent == null || !tracked.lastSent.equals(current)) {
				tracked.lastSent = current;
				if (out == null) {
					out = new ArrayList<>();
				}
				out.add(new VFXScoreboardPayload.ScoreUpdate(entry.getKey().objective(), entry.getKey().holder(), current));
			}
		}
		return out;
	}

	private static OptionalInt readScore(final MinecraftServer server, final ScoreKey key) {
		ServerScoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(key.objective());
		if (objective == null || !isSyncable(objective)) {
			return OptionalInt.empty();
		}
		ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly(key.holder()), objective);
		return info != null ? OptionalInt.of(info.value()) : OptionalInt.empty();
	}

	/**
	 * The single visibility policy point. The client never names an objective — pairs come from the
	 * definitions the server itself sent, so by default everything mentioned in a VFX definition is
	 * allowed. Tighten here (display slots, config, whitelist) if needed.
	 */
	private static boolean isSyncable(final Objective objective) {
		return true;
	}
}

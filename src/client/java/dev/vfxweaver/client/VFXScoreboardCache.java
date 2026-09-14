package dev.vfxweaver.client;

import dev.vfxweaver.network.VFXScoreboardPayload;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Client-side cache of the scoreboard values pushed by the server for {@code scoreboard} bindings
 * (see {@code VFXScoreboardSync}). Read by {@code VFXClient.readScoreboard} before the vanilla
 * client-scoreboard fallback. Applied on the client thread only.
 */
public final class VFXScoreboardCache {
	/** Hard ceiling against a foreign/broken server; ours sends at most MAX_TRACKED_PER_PLAYER. */
	private static final int MAX_ENTRIES = 1024;

	private static final Map<Key, Integer> VALUES = new HashMap<>();

	private VFXScoreboardCache() {
	}

	private record Key(String objective, String holder) {
	}

	/**
	 * Applies one server update: a present value is cached, an absent value removes the entry.
	 */
	public static void apply(final VFXScoreboardPayload payload) {
		for (VFXScoreboardPayload.ScoreUpdate update : payload.updates()) {
			Key key = new Key(update.objective(), update.holder());
			if (update.value().isPresent()) {
				if (VALUES.size() < MAX_ENTRIES || VALUES.containsKey(key)) {
					VALUES.put(key, Integer.valueOf(update.value().getAsInt()));
				}
			} else {
				VALUES.remove(key);
			}
		}
	}

	/**
	 * Returns the server-pushed score, or {@code null} when the key is not tracked.
	 */
	public static @Nullable Integer get(final String objectiveName, final String holderName) {
		return VALUES.get(new Key(objectiveName, holderName));
	}

	public static void clear() {
		VALUES.clear();
	}
}

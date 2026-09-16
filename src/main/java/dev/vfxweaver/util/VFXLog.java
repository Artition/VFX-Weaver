package dev.vfxweaver.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Bounded "warn once" helper.
 *
 * <p>An integration (another mod or a command) can call the API or send a packet every tick, so a
 * repeated problem must not be able to flood the game log. Each distinct key is warned about at
 * most once, and the key set is capped so it cannot grow without bound.</p>
 */
public final class VFXLog {
	private static final int MAX_KEYS = 256;
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private VFXLog() {
	}

	/**
	 * Logs {@code message} at WARN level the first time {@code key} is seen.
	 *
	 * @param logger  the logger to warn on
	 * @param key     a stable identifier for the problem (part of the deduplication)
	 * @param message the SLF4J message pattern
	 * @param args    the SLF4J message arguments
	 */
	public static void warnOnce(final Logger logger, final String key, final String message, final Object... args) {
		if (WARNED.size() < MAX_KEYS && WARNED.add(key)) {
			logger.warn(message, args);
		}
	}
}

package dev.vfxweaver.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A bounded, reload-safe cache of values derived from a reloadable descriptor.
 *
 * <p>On {@code /reload} or a resource-pack change the texture and atlas managers re-stitch and
 * {@code AbstractTexture} closes and recreates its {@code GpuTextureView}; the descriptor object
 * itself (an {@code AbstractTexture}/{@code TextureAtlas}) is reused, but the view handed out
 * before the reload is closed. A sprite's UV rect can also change after a re-stitch. Caching the
 * derived value therefore hands out a dangling handle; caching the loader and re-deriving on every
 * read does not.
 *
 * <p>This class deliberately caches the <em>loader</em> and never the loaded value — caching the
 * value is exactly the resource-reload bug it exists to prevent. The key set is bounded (external
 * datapack input, AGENTS.md); past the cap a key is resolved without being cached, which stays
 * correct.
 *
 * @param <V> the derived value type (e.g. a texture view)
 */
public final class VFXReloadSafeCache<V> {
	/** Safety cap on cached loaders (external datapack input, AGENTS.md bounded-collection rule). */
	public static final int MAX_ENTRIES = 256;

	private final Map<String, Supplier<V>> loaders = new HashMap<>();

	/**
	 * Returns the value for {@code key}, invoking {@code loader} on every call so a descriptor
	 * whose view changed since the last call is re-derived.
	 *
	 * @param key    the descriptor key
	 * @param loader derives the current value from the descriptor; it is invoked on every read
	 * @return the freshly derived value
	 */
	public @Nullable V get(final String key, final Supplier<V> loader) {
		Supplier<V> cached = this.loaders.get(key);
		if (cached == null) {
			if (this.loaders.size() >= MAX_ENTRIES) {
				// ponytail: stop growing past the cap; correctness does not depend on the cache.
				return loader.get();
			}
			this.loaders.put(key, loader);
			cached = loader;
		}
		return cached.get();
	}

	/** The number of cached loaders (diagnostics/tests only). */
	public int size() {
		return this.loaders.size();
	}
}

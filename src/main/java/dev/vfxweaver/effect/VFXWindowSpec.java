package dev.vfxweaver.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The structural half of an aux-window effect ({@code window_create} / {@code window_control}): the
 * window name every window effect addresses, an optional picture resource and the OS-title list
 * whose animated {@code title_index} selects one entry.
 *
 * <p>The animatable numbers ({@code pos_x}/{@code pos_y}/{@code size_w}/{@code size_h},
 * {@code opacity}, {@code frames}/{@code frame_time} and {@code title_index}) live in ordinary
 * {@code params}, like every other effect; only the strings are structural. Strings stay in the
 * definition and never travel on the wire in v1, so the block is additive: it is invisible to an
 * older mod and adds no field to the network payload.
 */
public final class VFXWindowSpec {
	/** Safety cap on the parsed {@code titles} list (external datapack input). */
	public static final int MAX_TITLES = 64;

	private final String id;
	private final @Nullable Identifier texture;
	private final List<String> titles;

	private VFXWindowSpec(final String id, final @Nullable Identifier texture, final List<String> titles) {
		this.id = id;
		this.texture = texture;
		this.titles = titles;
	}

	/**
	 * Parses and validates a window block from the top-level definition JSON.
	 *
	 * @param json the effect object
	 * @return the parsed spec, never {@code null}
	 * @throws IllegalArgumentException when {@code id} is missing/blank, {@code texture} is blank,
	 *         {@code titles} is not an array of strings, or the title list exceeds {@link #MAX_TITLES}
	 */
	public static VFXWindowSpec parse(final JsonObject json) {
		if (json == null) {
			throw new IllegalArgumentException("window: expected an object");
		}
		final String id = string(json, "id");
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("window: 'id' is required and must be a non-blank window name");
		}
		Identifier texture = null;
		if (json.has("texture") && !json.get("texture").isJsonNull()) {
			final String raw = string(json, "texture");
			if (raw == null || raw.isBlank()) {
				throw new IllegalArgumentException("window: 'texture' must be a non-blank resource id");
			}
			texture = Identifier.parse(raw);
		}
		final List<String> titles = new ArrayList<>();
		if (json.has("titles") && !json.get("titles").isJsonNull()) {
			final JsonElement element = json.get("titles");
			if (!element.isJsonArray()) {
				throw new IllegalArgumentException("window: 'titles' must be an array of strings");
			}
			for (final JsonElement entry : element.getAsJsonArray()) {
				if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
					throw new IllegalArgumentException("window: every 'titles' entry must be a string");
				}
				if (titles.size() >= MAX_TITLES) {
					throw new IllegalArgumentException("window: 'titles' exceeds the cap of " + MAX_TITLES);
				}
				titles.add(entry.getAsString());
			}
		}
		return new VFXWindowSpec(id, texture, List.copyOf(titles));
	}

	/** The window name this effect targets (the registry key). */
	public String id() {
		return this.id;
	}

	/** The optional picture resource id, or {@code null} for a title-only window. */
	public @Nullable Identifier texture() {
		return this.texture;
	}

	/** The OS-title list in authored order (empty when the definition declares none). */
	public List<String> titles() {
		return this.titles;
	}

	/**
	 * The title at an animated {@code title_index}, clamped (held) at the nearest valid entry: a
	 * negative index selects the first title and an index past the end the last, so an out-of-range
	 * index never fails.
	 *
	 * @param index the (usually animated) title index
	 * @return the selected title, or {@code null} when the definition declares no titles
	 */
	public @Nullable String titleAt(final float index) {
		if (this.titles.isEmpty()) {
			return null;
		}
		final int rounded = Math.round(index);
		final int clamped = Math.max(0, Math.min(this.titles.size() - 1, rounded));
		return this.titles.get(clamped);
	}

	private static @Nullable String string(final JsonObject json, final String key) {
		final JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("window: '" + key + "' must be a string");
		}
		return element.getAsString();
	}
}

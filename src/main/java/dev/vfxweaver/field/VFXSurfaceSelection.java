package dev.vfxweaver.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/**
 * The CPU half of the {@code surface_pattern} surface selection: the structural top-level
 * {@code surface} block. It expands the authored face tokens into a 6-bit face mask and carries an
 * optional inclusive band along the fragment's dominant normal axis. It computes nothing — the
 * shader selects the projection plane from the reconstructed world normal and tests the mask; this
 * only validates the authored values and carries them.
 *
 * <p>Face ids (one bit each, fixed order): {@code 0} up (+Y), {@code 1} down (−Y), {@code 2}
 * north (−Z), {@code 3} south (+Z), {@code 4} west (−X), {@code 5} east (+X). Minecraft's axis
 * convention: +X = east, +Z = south, north = −Z, west = −X.
 *
 * <p>This block is optional and additive: when it is absent the shader keeps the legacy numeric
 * {@code normal_mask} behaviour and no band applies. Strings/enums are structural and never live in
 * {@code params} (numeric only), following the same rule as the {@code pattern} block.
 */
public final class VFXSurfaceSelection {
	/** Face id of an upward (+Y) surface. */
	public static final int FACE_UP = 0;
	/** Face id of a downward (−Y) surface. */
	public static final int FACE_DOWN = 1;
	/** Face id of a northward (−Z) surface. */
	public static final int FACE_NORTH = 2;
	/** Face id of a southward (+Z) surface. */
	public static final int FACE_SOUTH = 3;
	/** Face id of a westward (−X) surface. */
	public static final int FACE_WEST = 4;
	/** Face id of an eastward (+X) surface. */
	public static final int FACE_EAST = 5;

	/** Inclusive lower band bound used when {@code min} is omitted (band always open). */
	public static final float UNBOUNDED_MIN = -1.0e30F;
	/** Inclusive upper band bound used when {@code max} is omitted (band always open). */
	public static final float UNBOUNDED_MAX = 1.0e30F;

	/** Safety cap on authored face tokens before expansion (external datapack input). */
	public static final int MAX_TOKENS = 8;

	/** Every face selected — the {@code all} group. */
	private static final int ALL = (1 << 6) - 1;

	private final int faceMask;
	private final float min;
	private final float max;

	private VFXSurfaceSelection(final int faceMask, final float min, final float max) {
		this.faceMask = faceMask;
		this.min = min;
		this.max = max;
	}

	/**
	 * Parses and validates a structural {@code surface} block.
	 *
	 * @param json the {@code surface} object (e.g. {@code {"faces": ["up"], "min": 64, "max": 96}})
	 * @return the parsed selection, never {@code null}
	 * @throws IllegalArgumentException on an unknown key/face token, more than {@link #MAX_TOKENS}
	 *         tokens, a non-finite bound, or {@code min > max}
	 */
	public static VFXSurfaceSelection parse(final JsonObject json) {
		for (final String key : json.keySet()) {
			if (!"faces".equals(key) && !"min".equals(key) && !"max".equals(key)) {
				throw new IllegalArgumentException("surface: unknown key '" + key + "' (expected faces, min, max)");
			}
		}

		// A surface block without "faces" selects the floor only (the common case).
		int faceMask = 1 << FACE_UP;
		if (json.has("faces") && !json.get("faces").isJsonNull()) {
			final JsonArray array = asArray(json.get("faces"));
			if (array.size() > MAX_TOKENS) {
				throw new IllegalArgumentException("surface: 'faces' may declare at most " + MAX_TOKENS + " tokens, got " + array.size());
			}
			faceMask = 0;
			for (final JsonElement entry : array) {
				final String token = asString(entry).toLowerCase(Locale.ROOT);
				final int bits = expand(token);
				if (bits == 0) {
					throw new IllegalArgumentException("surface: unknown face '" + token + "' (up/down/north/south/east/west, x/y/z, horizontal/vertical/all)");
				}
				faceMask |= bits;
			}
		}

		final float min = json.has("min") && !json.get("min").isJsonNull() ? number(json.get("min"), "min") : UNBOUNDED_MIN;
		final float max = json.has("max") && !json.get("max").isJsonNull() ? number(json.get("max"), "max") : UNBOUNDED_MAX;
		if (min > max) {
			throw new IllegalArgumentException("surface: 'min' (" + min + ") must not be greater than 'max' (" + max + ")");
		}

		return new VFXSurfaceSelection(faceMask, min, max);
	}

	/** The 6-bit face mask (bit {@code i} = face id {@code i}). */
	public int faceMask() {
		return this.faceMask;
	}

	/** Inclusive lower bound of the band, along the fragment's dominant normal axis. */
	public float min() {
		return this.min;
	}

	/** Inclusive upper bound of the band, along the fragment's dominant normal axis. */
	public float max() {
		return this.max;
	}

	/** Expands one token to its face bits, or {@code 0} when the token is unknown. */
	private static int expand(final String token) {
		return switch (token) {
			case "up" -> 1 << FACE_UP;
			case "down" -> 1 << FACE_DOWN;
			case "north" -> 1 << FACE_NORTH;
			case "south" -> 1 << FACE_SOUTH;
			case "west" -> 1 << FACE_WEST;
			case "east" -> 1 << FACE_EAST;
			case "x" -> (1 << FACE_WEST) | (1 << FACE_EAST);
			case "y" -> (1 << FACE_UP) | (1 << FACE_DOWN);
			case "z" -> (1 << FACE_NORTH) | (1 << FACE_SOUTH);
			case "horizontal" -> (1 << FACE_UP) | (1 << FACE_DOWN);
			case "vertical" -> (1 << FACE_NORTH) | (1 << FACE_SOUTH) | (1 << FACE_WEST) | (1 << FACE_EAST);
			case "all" -> ALL;
			default -> 0;
		};
	}

	private static JsonArray asArray(final JsonElement element) {
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException("surface: 'faces' must be an array of face tokens");
		}
		return element.getAsJsonArray();
	}

	private static String asString(final JsonElement element) {
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("surface: every 'faces' entry must be a string token");
		}
		return element.getAsString();
	}

	private static float number(final JsonElement element, final String key) {
		if (!element.isJsonPrimitive() || !((JsonPrimitive) element).isNumber()) {
			throw new IllegalArgumentException("surface: '" + key + "' must be a number");
		}
		final float value = element.getAsFloat();
		if (!Float.isFinite(value)) {
			throw new IllegalArgumentException("surface: '" + key + "' must be finite, got " + value);
		}
		return value;
	}
}

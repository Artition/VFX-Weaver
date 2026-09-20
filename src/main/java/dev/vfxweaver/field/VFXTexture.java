package dev.vfxweaver.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The CPU half of a textured {@code surface_pattern} figure: the structural {@code pattern.texture}
 * block. A texture is the figure's alternative source — its coverage comes from a texture channel,
 * its colour from the texture RGB — and an authored {@code figure} becomes a mask over it. This
 * class computes nothing; it validates the authored block and carries the values the shader and the
 * client resolver need.
 *
 * <p>The {@code source} is inferred from the id when omitted ({@code …/textures/…} → standalone,
 * {@code item/…} → item, {@code block/…} → block); anything else must name one of
 * {@code block}/{@code item}/{@code atlas}/{@code standalone}. {@code atlas} is required only for
 * {@code source: "atlas"} and rejected otherwise. An unknown key, source, channel, aspect or a bad
 * sheet is a per-file parse error (see {@code VFXDefinitionManager.prepare} isolation).
 *
 * <p>The channel codes shared with the shader match the field library's
 * ({@code VFXFieldProgram.channelCode}): {@code r=0, g=1, b=2, alpha=3, luminance=4}.
 */
public final class VFXTexture {
	/** Upper bound of one {@code sheet} component. */
	public static final int MAX_SHEET_DIM = 16;
	/** Upper bound of {@code cols * rows} for a {@code sheet} (bounded-collection rule). */
	public static final int MAX_SHEET_FRAMES = 256;

	/** Which manager resolves the id: an atlas source stamps a sprite UV sub-rect, standalone is {@code 0..1}. */
	public enum Source {
		BLOCK, ITEM, ATLAS, STANDALONE;

		/**
		 * Resolves a source spelling.
		 *
		 * @param name raw string, e.g. {@code "block"}
		 * @return the matching source, or {@code null} when unknown
		 */
		public static @Nullable Source fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final Source source : values()) {
				if (source.name().equalsIgnoreCase(name.trim())) {
					return source;
				}
			}
			return null;
		}
	}

	/** Which texture component is the coverage; codes are shared positionally with the shader. */
	public enum Channel {
		R(0), G(1), B(2), ALPHA(3), LUMINANCE(4);

		private final int code;

		Channel(final int code) {
			this.code = code;
		}

		/** The shader code of this channel. */
		public int code() {
			return this.code;
		}

		/**
		 * Resolves a channel spelling.
		 *
		 * @param name raw string, e.g. {@code "alpha"}
		 * @return the matching channel, or {@code null} when unknown
		 */
		public static @Nullable Channel fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final Channel channel : values()) {
				if (channel.name().equalsIgnoreCase(name.trim())) {
					return channel;
				}
			}
			return null;
		}
	}

	/** Whether one repeat covers the cell's longer axis only ({@code PRESERVE}) or both ({@code STRETCH}). */
	public enum Aspect {
		PRESERVE, STRETCH;

		/**
		 * Resolves an aspect spelling.
		 *
		 * @param name raw string, e.g. {@code "preserve"}
		 * @return the matching aspect, or {@code null} when unknown
		 */
		public static @Nullable Aspect fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final Aspect aspect : values()) {
				if (aspect.name().equalsIgnoreCase(name.trim())) {
					return aspect;
				}
			}
			return null;
		}
	}

	private final String id;
	private final Source source;
	private final @Nullable String atlas;
	private final Channel channel;
	private final int sheetCols;
	private final int sheetRows;
	private final Aspect aspect;
	/** The id parsed once at parse time; the per-frame resolver reuses it instead of re-parsing. */
	private final Identifier parsedId;
	/** The atlas id parsed once at parse time, or {@code null} when no atlas is authored. */
	private final @Nullable Identifier parsedAtlasId;

	private VFXTexture(final String id, final Source source, final @Nullable String atlas, final Channel channel, final int sheetCols, final int sheetRows, final Aspect aspect, final Identifier parsedId, final @Nullable Identifier parsedAtlasId) {
		this.id = id;
		this.source = source;
		this.atlas = atlas;
		this.channel = channel;
		this.sheetCols = sheetCols;
		this.sheetRows = sheetRows;
		this.aspect = aspect;
		this.parsedId = parsedId;
		this.parsedAtlasId = parsedAtlasId;
	}

	/**
	 * Parses and validates a structural {@code pattern.texture} block.
	 *
	 * @param json the {@code texture} object (e.g. {@code {"id": "minecraft:block/nether_portal", "channel": "alpha"}})
	 * @return the parsed texture, never {@code null}
	 * @throws IllegalArgumentException on an unknown key/source/channel/aspect, a blank id, a bad
	 *         sheet, or an {@code atlas} key on a non-atlas source
	 */
	public static VFXTexture parse(final JsonObject json) {
		if (json == null) {
			throw new IllegalArgumentException("pattern.texture: expected an object");
		}
		for (final String key : json.keySet()) {
			if (!"id".equals(key) && !"source".equals(key) && !"atlas".equals(key)
				&& !"channel".equals(key) && !"sheet".equals(key) && !"aspect".equals(key)) {
				throw new IllegalArgumentException("pattern.texture: unknown key '" + key + "' (id, source, atlas, channel, sheet, aspect)");
			}
		}

		final String id = string(json, "id", null);
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("pattern.texture: 'id' is required and must be a non-blank resource id");
		}
		// Validate the id syntax at parse time: an unparseable id otherwise failed closed per effect
		// at render and logged every frame (a per-file parse error is the definition-level contract).
		final Identifier parsedId = Identifier.tryParse(id);
		if (parsedId == null) {
			throw new IllegalArgumentException("pattern.texture: 'id' is not a valid resource id: '" + id + "'");
		}

		Source source = null;
		if (json.has("source") && !json.get("source").isJsonNull()) {
			final String raw = string(json, "source", "");
			source = Source.fromString(raw);
			if (source == null) {
				throw new IllegalArgumentException("pattern.texture: unknown 'source' '" + raw + "' (block, item, atlas, standalone)");
			}
		}
		if (source == null) {
			source = inferSource(id);
			if (source == null) {
				throw new IllegalArgumentException("pattern.texture: cannot infer 'source' from id '" + id + "'; name one of block/item/atlas/standalone");
			}
		}

		String atlas = null;
		Identifier parsedAtlasId = null;
		if (json.has("atlas") && !json.get("atlas").isJsonNull()) {
			atlas = string(json, "atlas", "");
			if (source != Source.ATLAS) {
				throw new IllegalArgumentException("pattern.texture: 'atlas' is only valid for source 'atlas', got source '" + source.name().toLowerCase(Locale.ROOT) + "'");
			}
			if (atlas.isBlank()) {
				throw new IllegalArgumentException("pattern.texture: 'atlas' must be a non-blank resource id");
			}
			parsedAtlasId = Identifier.tryParse(atlas);
			if (parsedAtlasId == null) {
				throw new IllegalArgumentException("pattern.texture: 'atlas' is not a valid resource id: '" + atlas + "'");
			}
		}
		if (source == Source.ATLAS && atlas == null) {
			throw new IllegalArgumentException("pattern.texture: source 'atlas' needs an 'atlas' resource id");
		}

		Channel channel = Channel.ALPHA;
		if (json.has("channel") && !json.get("channel").isJsonNull()) {
			final String raw = string(json, "channel", "");
			channel = Channel.fromString(raw);
			if (channel == null) {
				throw new IllegalArgumentException("pattern.texture: unknown 'channel' '" + raw + "' (alpha, luminance, r, g, b)");
			}
		}

		int cols = 1;
		int rows = 1;
		if (json.has("sheet") && !json.get("sheet").isJsonNull()) {
			final JsonElement element = json.get("sheet");
			if (!element.isJsonArray() || element.getAsJsonArray().size() != 2) {
				throw new IllegalArgumentException("pattern.texture: 'sheet' must be an array of [columns, rows]");
			}
			final JsonArray array = element.getAsJsonArray();
			cols = integer(array.get(0), "sheet");
			rows = integer(array.get(1), "sheet");
			if (cols < 1 || cols > MAX_SHEET_DIM || rows < 1 || rows > MAX_SHEET_DIM) {
				throw new IllegalArgumentException("pattern.texture: 'sheet' components must be within [1, " + MAX_SHEET_DIM + "], got [" + cols + ", " + rows + "]");
			}
			if ((long) cols * rows > MAX_SHEET_FRAMES) {
				throw new IllegalArgumentException("pattern.texture: 'sheet' " + cols + "x" + rows + " exceeds " + MAX_SHEET_FRAMES + " frames");
			}
		}

		Aspect aspect = Aspect.PRESERVE;
		if (json.has("aspect") && !json.get("aspect").isJsonNull()) {
			final String raw = string(json, "aspect", "");
			aspect = Aspect.fromString(raw);
			if (aspect == null) {
				throw new IllegalArgumentException("pattern.texture: unknown 'aspect' '" + raw + "' (preserve, stretch)");
			}
		}

		return new VFXTexture(id, source, atlas, channel, cols, rows, aspect, parsedId, parsedAtlasId);
	}

	/** The sprite id (atlas sources) or texture id (standalone). */
	public String id() {
		return this.id;
	}

	/** The parsed {@code id} (never {@code null}); reused by the per-frame resolver. */
	public Identifier parsedId() {
		return this.parsedId;
	}

	/** The parsed {@code atlas} id, or {@code null} when no atlas is authored. */
	public @Nullable Identifier parsedAtlasId() {
		return this.parsedAtlasId;
	}

	/** Which manager resolves the id. */
	public Source source() {
		return this.source;
	}

	/** The atlas resource id for {@code source: "atlas"}, else {@code null}. */
	public @Nullable String atlas() {
		return this.atlas;
	}

	/** Which texture component is the coverage. */
	public Channel channel() {
		return this.channel;
	}

	/** Sprite-sheet column count ({@code 1..16}). */
	public int sheetCols() {
		return this.sheetCols;
	}

	/** Sprite-sheet row count ({@code 1..16}). */
	public int sheetRows() {
		return this.sheetRows;
	}

	/** Whether the texture's pixel aspect is preserved or stretched over the cell. */
	public Aspect aspect() {
		return this.aspect;
	}

	/** True when {@code aspect} is {@code preserve} (the default). */
	public boolean preserveAspect() {
		return this.aspect == Aspect.PRESERVE;
	}

	/** True when the source resolves through an atlas (block/item/atlas), false for standalone. */
	public boolean atlasSource() {
		return this.source != Source.STANDALONE;
	}

	/** The id's inferred source, or {@code null} when the id's shape is ambiguous. */
	private static @Nullable Source inferSource(final String id) {
		if (id.contains("textures/")) {
			return Source.STANDALONE;
		}
		final int colon = id.indexOf(':');
		final String path = colon >= 0 ? id.substring(colon + 1) : id;
		if (path.startsWith("item/")) {
			return Source.ITEM;
		}
		if (path.startsWith("block/")) {
			return Source.BLOCK;
		}
		return null;
	}

	private static int integer(final JsonElement element, final String key) {
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("pattern.texture: '" + key + "' entries must be numbers");
		}
		return Math.round(element.getAsFloat());
	}

	private static String string(final JsonObject json, final String key, final @Nullable String fallback) {
		final JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("pattern.texture: '" + key + "' must be a string");
		}
		return element.getAsString();
	}
}

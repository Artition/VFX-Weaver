package dev.vfxweaver.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * The structural half of a {@code text_overlay} effect: what the text says and how it is shaped as a
 * block of characters. The animatable numbers (position, scale, reveal, exit) stay in ordinary
 * {@code params}. Kept structural on purpose - {@code mode} selects a different render path, and a
 * render path cannot be interpolated between two values; {@code split} selects how many entities the
 * text becomes, which is fixed for the life of a block for the same reason.
 */
public final class VFXTextSpec {
	/**
	 * The font id spellings the renderer accepts. Only Minecraft's built-in font is ever drawn (the
	 * renderer always uses {@code Minecraft.getInstance().font}), so any other name would silently
	 * be ignored - the parser rejects it instead. The editor's font combo is generated from this
	 * constant, so the two cannot drift.
	 */
	public static final List<String> FONT_IDS = List.of("default", "minecraft:default");

	/** Where the characters live. */
	public enum Mode { FLAT, WORLD }

	/** Horizontal placement of a line inside the block. */
	public enum Align { LEFT, CENTER, RIGHT }

	/** Which point of the block sits on the anchor position. */
	public enum Anchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

	/**
	 * How the text becomes display entities: one per character, one per word, or one for the whole
	 * block. Everything that animates a display - reveal, scramble, exit, physics - then works per
	 * unit, never per glyph, because a coarse mode has fewer entities than characters.
	 */
	public enum Split { CHAR, WORD, BLOCK }

	private final String content;
	private final Mode mode;
	private final String font;
	private final Align align;
	private final Anchor anchor;
	private final Split split;

	private VFXTextSpec(final String content, final Mode mode, final String font,
			final Align align, final Anchor anchor, final Split split) {
		this.content = content;
		this.mode = mode;
		this.font = font;
		this.align = align;
		this.anchor = anchor;
		this.split = split;
	}

	/**
	 * Parses the top-level {@code text} object of a {@code text_overlay} definition.
	 *
	 * @param json the effect object
	 * @return the parsed spec, never {@code null}
	 * @throws IllegalArgumentException when {@code text} is missing, {@code content} is blank, or a
	 *                                  structural value ({@code mode}, {@code font}, {@code split},
	 *                                  {@code align}, {@code anchor}) is unknown
	 */
	public static VFXTextSpec parse(final JsonObject json) {
		if (json == null) {
			throw new IllegalArgumentException("text_overlay: expected an object");
		}
		final JsonElement block = json.get("text");
		if (block == null || block.isJsonNull() || !block.isJsonObject()) {
			throw new IllegalArgumentException("text_overlay: 'text' must be an object");
		}
		final JsonObject obj = block.getAsJsonObject();
		final JsonElement contentElement = obj.get("content");
		if (contentElement == null || contentElement.isJsonNull()
				|| !contentElement.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("text_overlay: 'text.content' is required and must be a string");
		}
		final String content = contentElement.getAsString();
		if (content.isBlank()) {
			throw new IllegalArgumentException("text_overlay: 'text.content' must not be blank");
		}
		final String mode = str(obj, "mode", "flat");
		final String font = str(obj, "font", "default");
		final String split = str(obj, "split", "char");
		final String align = str(obj, "align", "center");
		final String anchor = str(obj, "anchor", "center");
		return new VFXTextSpec(content, parseMode(mode), parseFont(font), parseAlign(align),
			parseAnchor(anchor), parseSplit(split));
	}

	private static String str(final JsonObject obj, final String key, final String fallback) {
		final JsonElement element = obj.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static Mode parseMode(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "flat" -> Mode.FLAT;
			case "world" -> Mode.WORLD;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.mode '" + raw + "'");
		};
	}

	// Only Minecraft's built-in font is ever drawn: the renderer always uses
	// Minecraft.getInstance().font, so any other name would silently be ignored. Reject it at parse
	// time instead of drawing something other than what the definition asked for.
	private static String parseFont(final String raw) {
		final String font = raw.toLowerCase(java.util.Locale.ROOT);
		if (FONT_IDS.contains(font)) {
			return "default";
		}
		throw new IllegalArgumentException("text_overlay: text.font '" + raw
			+ "' is not supported - only " + FONT_IDS + " is drawn "
			+ "(a resource-pack font is not supported yet)");
	}

	// 'split' decides how many entities the text becomes, so an unknown value would silently draw
	// the block as something the definition did not ask for. Rejected at parse, like 'mode'.
	private static Split parseSplit(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "char" -> Split.CHAR;
			case "word" -> Split.WORD;
			case "block" -> Split.BLOCK;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.split '" + raw
				+ "' (expected 'char', 'word' or 'block')");
		};
	}

	private static Align parseAlign(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "left" -> Align.LEFT;
			case "center" -> Align.CENTER;
			case "right" -> Align.RIGHT;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.align '" + raw + "'");
		};
	}

	private static Anchor parseAnchor(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "top_left" -> Anchor.TOP_LEFT;
			case "top" -> Anchor.TOP;
			case "top_right" -> Anchor.TOP_RIGHT;
			case "left" -> Anchor.LEFT;
			case "center" -> Anchor.CENTER;
			case "right" -> Anchor.RIGHT;
			case "bottom_left" -> Anchor.BOTTOM_LEFT;
			case "bottom" -> Anchor.BOTTOM;
			case "bottom_right" -> Anchor.BOTTOM_RIGHT;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.anchor '" + raw + "'");
		};
	}

	public String content() { return this.content; }
	public Mode mode() { return this.mode; }
	public String font() { return this.font; }
	public Align align() { return this.align; }
	public Anchor anchor() { return this.anchor; }
	public Split split() { return this.split; }

	/**
	 * Returns a spec with the same {@code mode}, {@code font}, {@code split}, {@code align} and
	 * {@code anchor} and the given content - the call-time text override keeps every layout decision
	 * from the file.
	 *
	 * @param content the replacement line (one line; the command language has no newline escape)
	 * @return a new spec, never {@code null}
	 */
	public VFXTextSpec withContent(final String content) {
		return new VFXTextSpec(content, this.mode, this.font, this.align, this.anchor, this.split);
	}
}

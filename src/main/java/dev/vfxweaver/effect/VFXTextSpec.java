package dev.vfxweaver.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The structural half of a {@code text_overlay} effect: what the text says and how it is shaped as a
 * block of characters. The animatable numbers (position, scale, reveal, exit) stay in ordinary
 * {@code params}. Kept structural on purpose - {@code mode} selects a different render path, and a
 * render path cannot be interpolated between two values.
 */
public final class VFXTextSpec {
	/** Where the characters live. */
	public enum Mode { FLAT, WORLD }

	/** Horizontal placement of a line inside the block. */
	public enum Align { LEFT, CENTER, RIGHT }

	/** Which point of the block sits on the anchor position. */
	public enum Anchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

	private final String content;
	private final Mode mode;
	private final String font;
	private final Align align;
	private final Anchor anchor;

	private VFXTextSpec(final String content, final Mode mode, final String font,
			final Align align, final Anchor anchor) {
		this.content = content;
		this.mode = mode;
		this.font = font;
		this.align = align;
		this.anchor = anchor;
	}

	/**
	 * Parses the top-level {@code text} object of a {@code text_overlay} definition.
	 *
	 * @param json the effect object
	 * @return the parsed spec, never {@code null}
	 * @throws IllegalArgumentException when {@code text} is missing, {@code content} is blank, or a
	 *                                  structural value ({@code mode}, {@code font}, {@code align},
	 *                                  {@code anchor}) is unknown
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
		final String align = str(obj, "align", "center");
		final String anchor = str(obj, "anchor", "center");
		return new VFXTextSpec(content, parseMode(mode), parseFont(font), parseAlign(align), parseAnchor(anchor));
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

	private static String parseFont(final String raw) {
		final String font = raw.toLowerCase(java.util.Locale.ROOT);
		return switch (font) {
			case "default", "monospace" -> font;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.font '" + raw + "'");
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
}

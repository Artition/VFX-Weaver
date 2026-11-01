package dev.vfxweaver.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Measures a text block once and hands back one entry per visible character with its rest position.
 *
 * <p>Minecraft's own wrapping lives in {@code Font.split} and returns {@code FormattedCharSequence},
 * which discards the per-character positions we need for per-character physics. So the block is laid
 * out here instead: hard breaks on {@code \n}, soft breaks on spaces when {@code wrapWidth > 0}, one
 * {@link Glyph} per character in reading order.
 *
 * <p>Positions are in layout units - {@code scale = 1} pixels - so the same layout serves a 2D
 * overlay at any GUI scale and a 3D block at any size in the world.
 */
public final class VFXTextLayout {

	/** One visible character and where it rests before anything animates. */
	public record Glyph(char ch, int index, float x, float y, float advance) {
	}

	private final List<Glyph> glyphs;
	private final float width;
	private final float height;

	private VFXTextLayout(final List<Glyph> glyphs, final float width, final float height) {
		this.glyphs = List.copyOf(glyphs);
		this.width = width;
		this.height = height;
	}

	public static VFXTextLayout measure(final Font font, final String content,
			final float scale, final float lineHeight, final float wrapWidth) {
		final List<Glyph> glyphs = new ArrayList<>(content.length());
		final List<String> lines = wrap(font, content, wrapWidth, scale);
		final float lineStep = scale * lineHeight;
		float index = 0.0F;
		float widest = 0.0F;
		for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
			final String line = lines.get(lineNo);
			final float lineWidth = font.width(line) * scale;
			widest = Math.max(widest, lineWidth);
			float cursor = 0.0F;
			for (int i = 0; i < line.length(); i++) {
				final char ch = line.charAt(i);
				final int advance = font.width(String.valueOf(ch));
				glyphs.add(new Glyph(ch, (int) index++, cursor, lineNo * lineStep, advance * scale));
				cursor += advance * scale;
			}
		}
		final float totalHeight = Math.max(0.0F, (lines.size() - 1) * lineStep + scale);
		return new VFXTextLayout(glyphs, widest, totalHeight);
	}

	/**
	 * Breaks the content into lines: {@code \n} always breaks, spaces break when the next word would
	 * pass {@code wrapWidth} (0 disables soft wrapping).
	 */
	private static List<String> wrap(final Font font, final String content,
			final float wrapWidth, final float scale) {
		final List<String> lines = new ArrayList<>();
		for (final String hardLine : content.split("\n", -1)) {
			if (wrapWidth <= 0.0F) {
				lines.add(hardLine);
				continue;
			}
			final StringBuilder current = new StringBuilder();
			for (final String word : hardLine.split(" ", -1)) {
				final String candidate = current.isEmpty() ? word : current + " " + word;
				final float candidateWidth = font.width(candidate) * scale;
				if (!current.isEmpty() && candidateWidth > wrapWidth) {
					lines.add(current.toString());
					current.setLength(0);
					current.append(word);
				} else {
					current.setLength(0);
					current.append(candidate);
				}
			}
			lines.add(current.toString());
		}
		return lines;
	}

	public List<Glyph> glyphs() { return this.glyphs; }
	public float width() { return this.width; }
	public float height() { return this.height; }
}

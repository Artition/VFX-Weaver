package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXTextSpec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Measures a text block once and hands back one entry per visible character with its rest position,
 * plus one entry per <em>unit</em> - the text one display entity carries.
 *
 * <p>Minecraft's own wrapping lives in {@code Font.split} and returns {@code FormattedCharSequence},
 * which discards the per-character positions we need for per-character physics. So the block is laid
 * out here instead: hard breaks on {@code \n}, soft breaks on spaces when {@code wrapWidth > 0}, one
 * {@link Glyph} per character in reading order.
 *
 * <p>A unit is the granularity of every animation: {@link VFXTextSpec.Split#CHAR} makes one unit per
 * character (the block's layout is exactly the same as with no units at all), {@code WORD} makes one
 * per word - spaces advance the cursor but are not units - and {@code BLOCK} makes a single unit that
 * holds the whole content including its newlines. Each unit knows the rest position of its first
 * glyph and how many glyphs precede it, so a consumer can index either list.
 *
 * <p>Positions are in layout units already multiplied by {@code scale}: pixels at {@code scale = 1}.
 * Consumers must not apply the scale a second time - only {@code advance}, {@code x}, {@code y},
 * {@code width} and {@code height} are consistent with each other.
 */
public final class VFXTextLayout {

	/** One visible character and where it rests before anything animates. */
	public record Glyph(char ch, int index, float x, float y, float advance) {
	}

	/**
	 * One display entity's worth of the block and where it rests before anything animates: the text
	 * it carries, the position of its first glyph, that glyph's index in {@link #glyphs()}, and how
	 * many lines of the block it spans.
	 */
	public record Unit(String text, float x, float y, int firstGlyph, int lines) {
	}

	private final List<Glyph> glyphs;
	private final List<Unit> units;
	private final float width;
	private final float height;

	private VFXTextLayout(final List<Glyph> glyphs, final List<Unit> units,
			final float width, final float height) {
		this.glyphs = List.copyOf(glyphs);
		this.units = List.copyOf(units);
		this.width = width;
		this.height = height;
	}

	/** The per-character layout: one unit per visible character. */
	public static VFXTextLayout measure(final Font font, final String content,
			final float scale, final float lineHeight, final float wrapWidth) {
		return measure(font, content, scale, lineHeight, wrapWidth, VFXTextSpec.Split.CHAR);
	}

	public static VFXTextLayout measure(final Font font, final String content,
			final float scale, final float lineHeight, final float wrapWidth,
			final VFXTextSpec.Split split) {
		final List<Glyph> glyphs = new ArrayList<>(content.length());
		final List<Unit> units = new ArrayList<>();
		final List<String> lines = wrap(font, content, wrapWidth, scale);
		final float lineStep = scale * lineHeight;
		int index = 0;
		float widest = 0.0F;
		for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
			final String line = lines.get(lineNo);
			final float lineWidth = font.width(line) * scale;
			widest = Math.max(widest, lineWidth);
			final float restY = lineNo * lineStep;
			float cursor = 0.0F;
			// The word being collected in 'word' mode, and where it started on this line.
			final StringBuilder word = new StringBuilder();
			int wordStart = -1;
			float wordX = 0.0F;
			for (int i = 0; i < line.length(); i++) {
				final char ch = line.charAt(i);
				final int advance = font.width(String.valueOf(ch));
				glyphs.add(new Glyph(ch, index, cursor, restY, advance * scale));
				final float glyphX = cursor;
				cursor += advance * scale;
				if (split == VFXTextSpec.Split.CHAR) {
					units.add(new Unit(String.valueOf(ch), glyphX, restY, index, 1));
				} else if (split == VFXTextSpec.Split.WORD) {
					// A space only advances the cursor: it is never a unit of its own, or two words
					// would cost three entities.
					if (ch == ' ') {
						if (wordStart >= 0) {
							units.add(new Unit(word.toString(), wordX, restY, wordStart, 1));
							word.setLength(0);
							wordStart = -1;
						}
					} else {
						if (wordStart < 0) {
							wordStart = index;
							wordX = glyphX;
						}
						word.append(ch);
					}
				}
				index++;
			}
			// A word that ran to the end of the line - a soft break only ever happens at a space, so
			// a word never spans two lines and every word unit is exactly one line tall.
			if (wordStart >= 0) {
				units.add(new Unit(word.toString(), wordX, restY, wordStart, 1));
			}
		}
		if (split == VFXTextSpec.Split.BLOCK) {
			// One entity for the whole block: vanilla's own wrapping breaks it at the line width it
			// is given, so the unit carries the content with its newlines and the block's first glyph
			// as its rest position.
			final float restX = glyphs.isEmpty() ? 0.0F : glyphs.get(0).x();
			final float restY = glyphs.isEmpty() ? 0.0F : glyphs.get(0).y();
			units.add(new Unit(content, restX, restY, 0, lines.size()));
		}
		final float totalHeight = Math.max(0.0F, (lines.size() - 1) * lineStep + scale);
		return new VFXTextLayout(glyphs, units, widest, totalHeight);
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
	public List<Unit> units() { return this.units; }
	public float width() { return this.width; }
	public float height() { return this.height; }
}

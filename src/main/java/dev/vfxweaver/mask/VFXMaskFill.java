package dev.vfxweaver.mask;

import java.util.Locale;

/**
 * A mask leaf's fill (shared shape contract). {@link #ordinal()} is uploaded as the fill code and
 * must stay stable: SOLID=0, STROKE=1, HOLLOW=2. {@code stroke} requires {@code stroke_width}.
 *
 * <p>{@link #HOLLOW} is only valid on a {@code block_list} leaf: it is the outline band of the
 * dilated block texture alpha (the dilated alpha minus the original alpha), i.e. the same band a
 * {@code stroke} draws from a distance field, but taken from real model geometry. Every other leaf
 * keeps {@code solid}/{@code stroke}; the coverage shader treats any non-{@code solid} fill as the
 * band, so the two spellings stay distinguishable only in the parser.
 */
public enum VFXMaskFill {
	SOLID("solid"),
	STROKE("stroke"),
	HOLLOW("hollow");

	private final String id;

	VFXMaskFill(final String id) {
		this.id = id;
	}

	/** The datapack spelling of this fill. */
	public String id() {
		return this.id;
	}

	/**
	 * Resolves a fill from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "stroke"}
	 * @return the matching fill, or {@code null} when unknown
	 */
	public static VFXMaskFill fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXMaskFill fill : values()) {
			if (fill.id.equals(normalized)) {
				return fill;
			}
		}
		return null;
	}
}

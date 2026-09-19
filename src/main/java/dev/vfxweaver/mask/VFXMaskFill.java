package dev.vfxweaver.mask;

import java.util.Locale;

/**
 * A mask leaf's fill (shared shape contract). {@link #ordinal()} is uploaded as the fill code and
 * must stay stable: SOLID=0, STROKE=1. {@code stroke} requires {@code stroke_width}.
 */
public enum VFXMaskFill {
	SOLID("solid"),
	STROKE("stroke");

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

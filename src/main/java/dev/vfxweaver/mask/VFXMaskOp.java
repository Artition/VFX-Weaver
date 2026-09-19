package dev.vfxweaver.mask;

import java.util.Locale;

/**
 * A composition operator between two masks (spec §4). The {@link #ordinal()} is uploaded and must
 * stay stable: UNION=0, INTERSECTION=1, DIFFERENCE=2.
 * Composition is left-associative and not symmetric ({@code a - b}, never {@code b - a}).
 */
public enum VFXMaskOp {
	UNION("union"),
	INTERSECTION("intersection"),
	DIFFERENCE("difference");

	private final String id;

	VFXMaskOp(final String id) {
		this.id = id;
	}

	/** The datapack spelling of this operator. */
	public String id() {
		return this.id;
	}

	/**
	 * Resolves an operator from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "difference"}
	 * @return the matching operator, or {@code null} when unknown
	 */
	public static VFXMaskOp fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXMaskOp op : values()) {
			if (op.id.equals(normalized)) {
				return op;
			}
		}
		return null;
	}
}

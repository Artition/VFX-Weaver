package dev.vfxweaver.mask;

import java.util.Locale;

/**
 * The optional per-leaf edge-perturbation field (spec §4). The {@link #ordinal()} is uploaded and
 * must stay stable: NONE=0, NOISE=1.
 *
 * <p>A field perturbs only its own leaf's signed distance, before composition:
 * {@code d' = d + field * amount}; the falloff is applied once afterwards (the "corrected
 * distance-space edge perturbation" of spec §4).
 */
public enum VFXMaskField {
	NONE("none"),
	NOISE("noise");

	private final String id;

	VFXMaskField(final String id) {
		this.id = id;
	}

	/** The datapack spelling of this field. */
	public String id() {
		return this.id;
	}

	/**
	 * Resolves a field from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "noise"}
	 * @return the matching field, or {@code null} when unknown
	 */
	public static VFXMaskField fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXMaskField field : values()) {
			if (field.id.equals(normalized)) {
				return field;
			}
		}
		return null;
	}
}

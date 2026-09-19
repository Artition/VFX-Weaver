package dev.vfxweaver.mask;

import java.util.Locale;

/**
 * The coordinate space a mask leaf is classified in (spec §4, shared shape contract).
 *
 * <p>{@link #WORLD} reconstructs the world position behind a pixel from the scene depth buffer,
 * which is only valid in the coverage prepass at screen layer 0 (see the depth findings note);
 * {@link #SCREEN} classifies in normalized UV and needs no depth. A mask may mix spaces across
 * leaves; the composition is in coverage space and is space-agnostic.
 */
public enum VFXMaskSpace {
	SCREEN("screen"),
	WORLD("world");

	private final String id;

	VFXMaskSpace(final String id) {
		this.id = id;
	}

	/** The datapack spelling of this space. */
	public String id() {
		return this.id;
	}

	/**
	 * Resolves a space from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "world"}
	 * @return the matching space, or {@code null} when unknown
	 */
	public static VFXMaskSpace fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXMaskSpace space : values()) {
			if (space.id.equals(normalized)) {
				return space;
			}
		}
		return null;
	}
}

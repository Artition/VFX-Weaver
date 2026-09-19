package dev.vfxweaver.mask;

import java.util.Locale;

/**
 * How a world-volume mask leaf ({@code sphere}/{@code box}) is evaluated against the scene. Both
 * modes are first-class looks; the default is {@link #SURFACE}, which keeps the original behaviour.
 *
 * <p>{@link #SURFACE} classifies the depth-reconstructed visible surface point: a pixel is covered
 * where that point lies inside the volume, so the tint only lands on geometry and air/sky inside the
 * volume stays untouched. {@link #AURA} casts a per-pixel ray at the volume and fills the whole
 * volume - including air and sky - everywhere a nearer surface does not occlude it.
 *
 * <p>The {@link #ordinal()} is uploaded to the coverage prepass ({@code shape_volume[i].x}); keep it
 * stable: SURFACE=0, AURA=1.
 */
public enum VFXMaskVolumeMode {
	SURFACE("surface"),
	AURA("aura");

	private final String id;

	VFXMaskVolumeMode(final String id) {
		this.id = id;
	}

	/** The datapack spelling of this mode. */
	public String id() {
		return this.id;
	}

	/** The value uploaded to the coverage prepass. */
	public int code() {
		return this.ordinal();
	}

	/**
	 * Resolves a mode from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "aura"}
	 * @return the matching mode, or {@code null} when unknown
	 */
	public static VFXMaskVolumeMode fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXMaskVolumeMode mode : values()) {
			if (mode.id.equals(normalized)) {
				return mode;
			}
		}
		return null;
	}
}

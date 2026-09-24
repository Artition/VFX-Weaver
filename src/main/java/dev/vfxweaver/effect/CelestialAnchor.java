package dev.vfxweaver.effect;

/**
 * The optional top-level {@code anchor} of a {@code sky_pattern}: which vanilla sky body the
 * pattern follows (design spec §4.4/§6.1-6.2, stage S4).
 *
 * <p>{@link #DOME} (the default when the field is absent) is the world-fixed
 * {@code anchor_yaw}/{@code anchor_pitch} authoring. {@link #SUN}/{@link #MOON}/{@link #STARS}
 * are resolved entirely on the CPU from the client's sky render state and feed the same three
 * existing uniforms ({@code anchor_yaw}, {@code anchor_pitch}, {@code dome_rotation}), so the pass
 * needs no shader and no mixin and therefore composes under Iris like any post effect. There is no
 * {@code player} value yet; a non-sky type carrying the field is a parse error.
 */
public enum CelestialAnchor {
	/** The literal {@code anchor_yaw}/{@code anchor_pitch} - no celestial body (the default). */
	DOME("dome"),
	/** Follow the sun; the CPU writes {@code anchor_yaw}/{@code anchor_pitch} from {@code sunAngle}. */
	SUN("sun"),
	/** Follow the moon; the CPU writes {@code anchor_yaw}/{@code anchor_pitch} from {@code moonAngle}. */
	MOON("moon"),
	/** Lock to the rotating star sphere; the CPU writes {@code dome_rotation} from {@code starAngle}. */
	STARS("stars");

	private final String key;

	CelestialAnchor(final String key) {
		this.key = key;
	}

	/** The datapack spelling of this anchor. */
	public String key() {
		return this.key;
	}

	/** True when this anchor reads a live sky body (every value except {@link #DOME}). */
	public boolean isCelestial() {
		return this != DOME;
	}

	/** True for the body anchors that drive the anchor position (sun and moon). */
	public boolean isSunOrMoon() {
		return this == SUN || this == MOON;
	}

	/**
	 * Resolves a datapack {@code anchor} string (case-insensitive).
	 *
	 * @param name the raw value
	 * @return the matching anchor
	 * @throws IllegalArgumentException on an unknown value, naming the accepted ones
	 */
	public static CelestialAnchor fromString(final String name) {
		final String trimmed = name == null ? "" : name.trim();
		for (final CelestialAnchor anchor : values()) {
			if (anchor.key.equalsIgnoreCase(trimmed)) {
				return anchor;
			}
		}
		throw new IllegalArgumentException("Unknown anchor '" + name + "' (expected dome, sun, moon or stars)");
	}
}

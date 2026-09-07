package dev.vfxweaver.client.noise;

/**
 * Tiny CPU hash helpers for the vertex-displacement effect ({@code entity_displace}). Coordinates
 * are hashed in 3D with a per-cell seed; the result is quantised into 21 discrete steps in
 * {@code [-1, 1]}, which gives the "snap-glitch" look when the {@code seed} parameter is stepped.
 */
public final class VFXNoise {
	private VFXNoise() {
	}

	/**
	 * Quantised hash in {@code [-1, 1]}. Local coordinates stay small, so no wrapping is needed
	 * here; for world coordinates call {@link #wrap(float)} first.
	 *
	 * @param x    hash coordinate
	 * @param y    hash coordinate
	 * @param z    hash coordinate
	 * @param seed per-effect seed (drive it to animate the field)
	 * @return a value in {@code [-1, 1]}
	 */
	public static float vhash(final float x, final float y, final float z, final float seed) {
		float h = x * 37.719F + y * 71.317F + z * 151.589F + seed * 31.7F;
		return (float) Math.floor(fract(h) * 21.0F) / 21.0F * 2.0F - 1.0F;
	}

	private static float fract(final float v) {
		return v - (float) Math.floor(v);
	}

	/**
	 * Wraps a world coordinate into {@code [-4096, 4096)} so hashing stays float-precision-safe
	 * even at ±30M block coordinates (an unwrapped multiply would lose all low bits to rounding).
	 *
	 * @param v the world coordinate
	 * @return the wrapped value
	 */
	public static float wrap(final float v) {
		return ((v % 4096.0F) + 4096.0F) % 4096.0F;
	}
}
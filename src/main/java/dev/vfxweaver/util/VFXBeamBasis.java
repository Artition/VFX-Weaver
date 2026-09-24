package dev.vfxweaver.util;

/**
 * The orthonormal basis of an arbitrary beam axis, in the order the world-overlay beam shell places
 * its ring vertices.
 *
 * <p>A {@code light_beam} was built on the world Y axis only. An arbitrary direction needs a
 * perpendicular pair {@code (right, up)} to place the ring, and the choice matters: the vertical case
 * is pinned to {@code right = +X, up = +Z} so the default direction {@code (0, 1, 0)} reproduces the
 * shipped geometry exactly, while every other axis uses the standard reference-vector construction.
 */
public final class VFXBeamBasis {
	/** Floats returned by {@link #of}: the axis, right and up unit vectors, three components each. */
	public static final int LENGTH = 9;

	private VFXBeamBasis() {
	}

	/**
	 * Computes a unit axis and a perpendicular unit pair for a beam direction.
	 *
	 * @param dirX raw direction X
	 * @param dirY raw direction Y
	 * @param dirZ raw direction Z
	 * @return nine floats, {@code axis xyz, right xyz, up xyz}; a zero-length direction falls back to
	 *         {@code (0, 1, 0)} so a beam is never degenerate
	 */
	public static float[] of(final float dirX, final float dirY, final float dirZ) {
		float ax = dirX;
		float ay = dirY;
		float az = dirZ;
		float length = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (length < 1.0e-6F) {
			ax = 0.0F;
			ay = 1.0F;
			az = 0.0F;
			length = 1.0F;
		}
		ax /= length;
		ay /= length;
		az /= length;
		if (Math.abs(ay) > 0.99F) {
			// Vertical: pinned so the default direction matches the shipped vertical geometry
			// vertex for vertex (the general construction would only reach it up to a phase shift).
			return new float[]{ax, ay, az, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F};
		}
		// right = normalize(cross(axis, +Y)) - perpendicular to the axis, and never degenerate here
		// because the vertical case was taken above.
		final float crossX = -az;
		final float crossZ = ax;
		final float crossLength = (float) Math.sqrt(crossX * crossX + crossZ * crossZ);
		final float rx = crossX / crossLength;
		final float rz = crossZ / crossLength;
		// up = cross(right, axis), also unit because right and axis are unit and perpendicular.
		final float ux = -rz * ay;
		final float uy = rz * ax - rx * az;
		final float uz = rx * ay;
		return new float[]{ax, ay, az, rx, 0.0F, rz, ux, uy, uz};
	}
}

package dev.vfxweaver.util;

/**
 * The pure maths that turns a vanilla sky-body angle into the {@code [yaw, pitch]} degrees the
 * {@code sky_pattern} dome anchor uses, and the star angle into a {@code dome_rotation}.
 *
 * <p>This mirrors {@code include/dome.glsl}: {@code vfx_dome_anchor_dir} maps an authored
 * {@code [yaw, pitch]} to a world direction as {@code (-sin(yaw)cos(pitch), -sin(pitch),
 * cos(yaw)cos(pitch))} in the Minecraft axes (yaw 0 = +Z south, 90 = -X west; pitch -90 =
 * zenith), and {@link #yawPitch} is its exact inverse. It is Minecraft-free so the guard can run
 * it headless and assert the round trip against a copy of the shader helper.
 */
public final class VFXSkyAnchor {
	private VFXSkyAnchor() {
	}

	/**
	 * The authored {@code [yaw, pitch]} degrees of a world direction, the exact convention
	 * {@code vfx_dome_uv}/{@code vfx_dome_anchor_dir} use. The direction need not be unit length.
	 *
	 * @param dx world direction x
	 * @param dy world direction y
	 * @param dz world direction z
	 * @return {@code {yawDeg, pitchDeg}}; yaw 0 = +Z south, 90 = -X west; pitch -90 = zenith
	 */
	public static float[] yawPitch(final float dx, final float dy, final float dz) {
		final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0e-8F) {
			return new float[]{0.0F, 0.0F};
		}
		final float nx = dx / len;
		final float ny = dy / len;
		// atan2 is scale-invariant, but asin needs the unit y, exactly like the shader's
		// vfx_dome_uv (which receives an already-normalised view direction).
		final float yaw = (float) Math.toDegrees(Math.atan2(-nx, dz / len));
		final float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, ny))));
		return new float[]{yaw, pitch};
	}

	/**
	 * The dome anchor of the sun or the moon for its vanilla celestial angle. Vanilla rotates the
	 * body about world X on a rig pre-rotated -90 deg about Y (verified in the 26.1.2/26.2/1.21.11
	 * {@code SkyRenderer.renderSunMoonAndStars} sources), so the body's world direction is
	 * {@code (-sin(angle), cos(angle), 0)}: angle 0 is the zenith, -pi/2 due east, +pi/2 due west.
	 *
	 * @param angleRadians {@code SkyRenderState.sunAngle}/{@code moonAngle} (radians)
	 * @return {@code {yawDeg, pitchDeg}} for {@code anchor_yaw}/{@code anchor_pitch}
	 */
	public static float[] bodyAnchor(final float angleRadians) {
		return yawPitch(-(float) Math.sin(angleRadians), (float) Math.cos(angleRadians), 0.0F);
	}

	/**
	 * The {@code dome_rotation} that spins a {@code stars} pattern at the star sphere's rate.
	 *
	 * <p>Documented offset, not an exact lock: the shader's {@code dome_rotation} is a spin about
	 * world Y, while vanilla rotates the star sphere about world X (its rig is Y(-90) then
	 * X(starAngle)). No single world-Y spin reproduces an X rotation, so the angle is mapped
	 * straight through - the pattern rotates at the star rate in the closest axis the shader
	 * offers.
	 *
	 * @param starAngleRadians {@code SkyRenderState.starAngle} (radians)
	 * @return the {@code dome_rotation} in degrees
	 */
	public static float starRotationDegrees(final float starAngleRadians) {
		return (float) Math.toDegrees(starAngleRadians);
	}
}

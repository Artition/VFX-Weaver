package dev.vfxweaver.effect;

/**
 * A parameter bound to a world-space or camera-space source instead of a fixed or time-animated
 * value. The client evaluates every bound parameter per frame using the current camera state
 * (position, rotation, field of view) — for example the on-screen position of a world
 * coordinate, the distance between the camera and that coordinate, or how closely the camera
 * looks at a given direction.
 *
 * @param kind   what to derive
 * @param x      world X of the anchor point
 * @param y      world Y of the anchor point
 * @param z      world Z of the anchor point
 * @param yaw    target yaw in degrees (for {@link Kind#LOOK})
 * @param pitch  target pitch in degrees (for {@link Kind#LOOK})
 * @param range  falloff extent: distance for {@link Kind#PROXIMITY}, angle in degrees for {@link Kind#LOOK}
 * @param invert for {@link Kind#PROXIMITY}/{@link Kind#LOOK}: 0 near / 1 far instead of 1 near / 0 far
 * @param scale  multiplier applied to the evaluated value
 */
public record BoundParam(Kind kind, double x, double y, double z, float yaw, float pitch, float range, boolean invert, float scale) {
	public BoundParam {
		if (scale == 0.0F) {
			scale = 1.0F;
		}
	}

	/**
	 * The quantity derived from the anchor point or the camera state.
	 */
	public enum Kind {
		/** Horizontal on-screen position of the anchor, in UV coordinates (0..1, -1 when behind the camera). */
		SCREEN_X("screen_x"),
		/** Vertical on-screen position of the anchor, in UV coordinates (0..1, -1 when behind the camera). */
		SCREEN_Y("screen_y"),
		/** 1 near the anchor, smoothly reaching 0 at {@code range} (0 behind the camera unless inverted). */
		PROXIMITY("proximity"),
		/** 1 when the camera looks exactly at the given yaw/pitch, 0 when the angle difference reaches {@code range} degrees. */
		LOOK("look"),
		/** 1 when the camera looks exactly at the world {@code pos} anchor, 0 when the angle difference reaches {@code range} degrees. */
		LOOK_AT("look_at"),
		/** Raw Euclidean distance from the camera to the anchor, in blocks (not the 0..1 falloff of {@link #PROXIMITY}). */
		DISTANCE("distance"),
		/** X component of the camera's forward (look) direction. */
		LOOK_X("look_x"),
		/** Y component of the camera's forward (look) direction. */
		LOOK_Y("look_y"),
		/** Z component of the camera's forward (look) direction. */
		LOOK_Z("look_z"),
		/** The local player's world X position. */
		PLAYER_X("player_x"),
		/** The local player's world Y position. */
		PLAYER_Y("player_y"),
		/** The local player's world Z position. */
		PLAYER_Z("player_z"),
		/** Camera yaw delta in degrees per tick (positive = turned right). */
		CAMERA_YAW_DELTA("camera_yaw_delta"),
		/** Camera pitch delta in degrees per tick (positive = turned up). */
		CAMERA_PITCH_DELTA("camera_pitch_delta"),
		/** Player health fraction, 0..1 (health / max health). */
		HEALTH("health"),
		/** Player hunger fraction, 0..1 (food / 20). */
		HUNGER("hunger"),
		/** Player horizontal speed in blocks per second, divided by {@code range} (default 5 = sprint). */
		SPEED("speed"),
		/** Light level at the player's position, 0..1 (level / 15). */
		LIGHT_LEVEL("light_level"),
		/** Fraction of the day cycle, 0..1 (0 = sunrise of day 0). */
		TIME_OF_DAY("time_of_day");

		/**
		 * True when this kind needs a world {@code pos} anchor.
		 */
		public boolean needsPos() {
			return this == SCREEN_X || this == SCREEN_Y || this == PROXIMITY || this == DISTANCE || this == LOOK_AT;
		}

		private final String id;

		Kind(final String id) {
			this.id = id;
		}

		/**
		 * Resolves a binding kind from its datapack name.
		 *
		 * @param name raw string, e.g. {@code "screen_x"}
		 * @return the matching kind
		 * @throws IllegalArgumentException when the name is unknown
		 */
		public static Kind fromString(final String name) {
			for (Kind kind : values()) {
				if (kind.id.equalsIgnoreCase(name.trim())) {
					return kind;
				}
			}
			throw new IllegalArgumentException("Unknown binding '" + name + "'");
		}
	}
}

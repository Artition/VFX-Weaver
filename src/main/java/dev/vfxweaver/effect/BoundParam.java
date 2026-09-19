package dev.vfxweaver.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A parameter bound to a world-space or camera-space source instead of a fixed or time-animated
 * value. The client evaluates every bound parameter per frame using the current camera state
 * (position, rotation, field of view) — for example the on-screen position of a world
 * coordinate, the distance between the camera and that coordinate, or how closely the camera
 * looks at a given direction. Scoreboard bindings instead follow a scoreboard value read from
 * the client scoreboard.
 *
 * @param kind      what to derive
 * @param x         world X of the anchor point
 * @param y         world Y of the anchor point
 * @param z         world Z of the anchor point
 * @param yaw       target yaw in degrees (for {@link Kind#LOOK})
 * @param pitch     target pitch in degrees (for {@link Kind#LOOK})
 * @param range     falloff extent: distance for {@link Kind#PROXIMITY}, angle in degrees for {@link Kind#LOOK}, raw-score divisor for {@link Kind#SCOREBOARD}
 * @param invert    for {@link Kind#PROXIMITY}/{@link Kind#LOOK}: 0 near / 1 far instead of 1 near / 0 far
 * @param scale     multiplier applied to the evaluated value
 * @param objective scoreboard objective name (for {@link Kind#SCOREBOARD}); {@code null} otherwise
 * @param holder    scoreholder name whose score is read (for {@link Kind#SCOREBOARD}); {@code null} = the local viewing player
 * @param source    the world-point origin of the binding's anchor (camera/player/entity/point/block),
 *                  or {@code null} when the binding uses the literal {@code x}/{@code y}/{@code z}
 */
public record BoundParam(Kind kind, double x, double y, double z, float yaw, float pitch, float range, boolean invert, float scale, @Nullable String objective, @Nullable String holder, @Nullable Source source) {
	public BoundParam {
		if (scale == 0.0F) {
			scale = 1.0F;
		}
	}

	/**
	 * Parses a {@code {"bind": "...", ...}} object with plain Gson (no Minecraft types), so the
	 * graph module can reuse it while staying MC-free.
	 *
	 * @param object the binding object, e.g. {@code {"bind":"proximity","pos":[0,0,0],"range":8}}
	 * @throws IllegalArgumentException when a required field is missing or malformed
	 */
	public static BoundParam parse(final JsonObject object) {
		final String bindName = str(object, "bind", "");
		final SourceKind sourceKind = SourceKind.fromString(bindName);
		final Kind kind;
		Source source = null;
		if (sourceKind != null) {
			source = Source.parse(object);
			final String derive = str(object, "derive", "point").trim().toLowerCase(Locale.ROOT);
			kind = "screen_rect".equals(derive) ? Kind.SCREEN_RECT
				: ("point".equals(derive) ? Kind.POINT : Kind.fromString(derive));
		} else {
			kind = Kind.fromString(bindName);
		}
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		if (kind.needsPos() && source == null) {
			final JsonElement posElement = object.get("pos");
			if (posElement == null || !posElement.isJsonArray() || posElement.getAsJsonArray().size() != 3) {
				throw new IllegalArgumentException("Binding 'pos' must be an array of [x, y, z]: " + object);
			}
			final JsonArray pos = posElement.getAsJsonArray();
			x = pos.get(0).getAsDouble();
			y = pos.get(1).getAsDouble();
			z = pos.get(2).getAsDouble();
		}
		String objective = null;
		String holder = null;
		if (kind == Kind.SCOREBOARD) {
			objective = str(object, "objective", "");
			if (objective.isBlank()) {
				throw new IllegalArgumentException("Binding 'scoreboard' needs a non-blank 'objective': " + object);
			}
			final JsonElement holderElement = object.get("holder");
			holder = holderElement != null && !holderElement.isJsonNull() ? holderElement.getAsString() : null;
			if (holder != null && holder.isBlank()) {
				holder = null;
			}
		}
		final float defaultRange = switch (kind) {
			case LOOK, LOOK_AT -> 90.0F;
			case SPEED -> 5.0F;
			case SCOREBOARD -> 16.0F;
			default -> 16.0F;
		};
		final float range = flt(object, "range", defaultRange);
		final boolean invert = boo(object, "invert", false);
		final float scale = flt(object, "scale", 1.0F);
		final float yaw = flt(object, "yaw", 0.0F);
		final float pitch = flt(object, "pitch", 0.0F);
		return new BoundParam(kind, x, y, z, yaw, pitch, range, invert, scale, objective, holder, source);
	}

	/**
	 * The origin of a binding's world anchor. Resolved client-side once per frame by
	 * {@link VFXWorldBindings} and cached; a selector is never scanned per pixel or per primitive.
	 */
	public enum SourceKind {
		/** The active camera position. */
		CAMERA("camera"),
		/** The local player's position. */
		PLAYER("player"),
		/** An entity, by selector or UUID, at {@code feet}/{@code center}/{@code eyes} plus {@code offset}. */
		ENTITY("entity"),
		/** A fixed world point. */
		POINT("point"),
		/** A block position (resolved to the block's centre). */
		BLOCK("block");

		private final String id;

		SourceKind(final String id) {
			this.id = id;
		}

		/** The datapack spelling. */
		public String id() {
			return this.id;
		}

		/**
		 * Resolves a source kind from its datapack spelling.
		 *
		 * @return the kind, or {@code null} when {@code name} is not a source (it is then a {@link Kind})
		 */
		public static @Nullable SourceKind fromString(final String name) {
			if (name == null) {
				return null;
			}
			final String normalized = name.trim().toLowerCase(Locale.ROOT);
			for (final SourceKind kind : values()) {
				if (kind.id.equals(normalized)) {
					return kind;
				}
			}
			return null;
		}
	}

	/**
	 * A world-point source for a binding's anchor.
	 *
	 * @param kind     where the point comes from
	 * @param selector entity selector (ENTITY only, alternative to {@code uuid}), or {@code null}
	 * @param uuid     entity UUID string (ENTITY only), or {@code null}
	 * @param point    reference point on the entity: {@code feet}/{@code center}/{@code eyes}
	 * @param offset   offset applied to the resolved point (ENTITY only)
	 * @param x        fixed X (POINT/BLOCK only)
	 * @param y        fixed Y (POINT/BLOCK only)
	 * @param z        fixed Z (POINT/BLOCK only)
	 */
	public record Source(SourceKind kind, @Nullable String selector, @Nullable String uuid, String point, float[] offset, double x, double y, double z) {
		public Source {
			offset = offset.clone();
		}

		/**
		 * Parses a source from a {@code { "bind": "<source>", ... }} object.
		 *
		 * @throws IllegalArgumentException when a required field is missing or malformed
		 */
		public static Source parse(final JsonObject object) {
			final SourceKind kind = SourceKind.fromString(str(object, "bind", ""));
			if (kind == null) {
				throw new IllegalArgumentException("Unknown binding source: " + object);
			}
			String selector = null;
			String uuid = null;
			String point = "center";
			float[] offset = new float[]{0.0F, 0.0F, 0.0F};
			double x = 0.0;
			double y = 0.0;
			double z = 0.0;
			switch (kind) {
				case ENTITY -> {
					if (object.has("uuid") && !object.get("uuid").isJsonNull()) {
						uuid = object.get("uuid").getAsString();
					} else if (object.has("selector") && !object.get("selector").isJsonNull()) {
						selector = object.get("selector").getAsString();
					} else {
						throw new IllegalArgumentException("Binding source 'entity' needs 'selector' or 'uuid': " + object);
					}
					point = str(object, "point", "center").trim().toLowerCase(Locale.ROOT);
					if (!point.equals("feet") && !point.equals("center") && !point.equals("eyes")) {
						throw new IllegalArgumentException("Binding source 'entity' 'point' must be feet, center or eyes: " + object);
					}
					final JsonElement offsetElement = object.get("offset");
					if (offsetElement != null && !offsetElement.isJsonNull()) {
						if (!offsetElement.isJsonArray() || offsetElement.getAsJsonArray().size() != 3) {
							throw new IllegalArgumentException("Binding source 'entity' 'offset' must be [x, y, z]: " + object);
						}
						final JsonArray array = offsetElement.getAsJsonArray();
						offset = new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
					}
				}
				case POINT, BLOCK -> {
					final JsonElement posElement = object.get("pos");
					if (posElement == null || !posElement.isJsonArray() || posElement.getAsJsonArray().size() != 3) {
						throw new IllegalArgumentException("Binding source '" + kind.id() + "' needs a 'pos' array of [x, y, z]: " + object);
					}
					final JsonArray pos = posElement.getAsJsonArray();
					x = pos.get(0).getAsDouble();
					y = pos.get(1).getAsDouble();
					z = pos.get(2).getAsDouble();
				}
				case CAMERA, PLAYER -> {
				}
			}
			return new Source(kind, selector, uuid, point, offset, x, y, z);
		}

		/** A stable key for the per-frame resolution cache. */
		public String cacheKey() {
			return switch (this.kind) {
				case CAMERA -> "camera";
				case PLAYER -> "player";
				case ENTITY -> "entity:" + (this.uuid != null ? this.uuid : this.selector) + ":" + this.point + ":" + java.util.Arrays.toString(this.offset);
				case POINT, BLOCK -> this.kind.id() + ":" + this.x + "," + this.y + "," + this.z;
			};
		}
	}

	private static String str(final JsonObject object, final String key, final String fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static float flt(final JsonObject object, final String key, final float fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsFloat() : fallback;
	}

	private static boolean boo(final JsonObject object, final String key, final boolean fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsBoolean() : fallback;
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
		TIME_OF_DAY("time_of_day"),
		/** A scoreholder's score on a scoreboard objective, divided by {@code range} (default 16). */
		SCOREBOARD("scoreboard"),
		/** A world point (vec3); only meaningful on a centre/point parameter. */
		POINT("point"),
		/** A derived entity screen rectangle (vec4: centre u, centre v, half-width, half-height). */
		SCREEN_RECT("screen_rect");

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

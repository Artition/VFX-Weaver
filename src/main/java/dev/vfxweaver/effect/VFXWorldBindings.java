package dev.vfxweaver.effect;

import dev.vfxweaver.effect.BoundParam.Source;
import dev.vfxweaver.effect.BoundParam.SourceKind;
import dev.vfxweaver.util.VFXLog;
import java.util.HashMap;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates {@link BoundParam}s against the active camera. The client feeds the camera state
 * (position plus the view-rotation-projection matrix) every frame before post-processing runs;
 * evaluation itself is plain math and stays side-agnostic. Without a camera (e.g. on a
 * dedicated server) every binding resolves to its fallback. Scoreboard bindings read the
 * client scoreboard through a {@link ScoreboardReader} hook registered by the client.
 */
public final class VFXWorldBindings {
	private static final float SMOOTHING_RATE = 8.0F;
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/bindings");
	/** Per-frame source/rect cache, keyed by {@link BoundParam.Source#cacheKey()}; cleared by {@link #update}. */
	private static final Map<String, float[]> SOURCE_CACHE = new HashMap<>();
	private static final Vector4f PROJECTION_SCRATCH = new Vector4f();
	private static volatile @Nullable Frame frame;
	private static volatile @Nullable PlayerState playerState;
	private static volatile @Nullable ScoreboardReader scoreboardReader;
	private static volatile @Nullable EntityReader entityReader;
	private static float lastYaw;
	private static float lastPitch;
	private static float smoothedYawDelta;
	private static float smoothedPitchDelta;

	/**
	 * Client-side scoreboard access, registered once by the client entrypoint (the client
	 * scoreboard lives behind client-only code, so {@code VFXWorldBindings} itself cannot
	 * reach it).
	 */
	public interface ScoreboardReader {
		/**
		 * Reads the raw score of a scoreholder on a scoreboard objective.
		 *
		 * @param objectiveName scoreboard objective name
		 * @param holderName    scoreholder name whose score is read, or {@code null} for the local viewing player
		 * @return the raw score, or {@code null} when the objective, holder or score is missing
		 */
		@Nullable Integer score(final String objectiveName, final @Nullable String holderName);
	}

	/**
	 * Snapshot of the local player's state for one frame (player-state bindings).
	 *
	 * @param health    health fraction 0..1
	 * @param hunger    hunger fraction 0..1
	 * @param speed     horizontal speed in blocks per second
	 * @param light     light level 0..1 at the player's position
	 * @param timeOfDay day-cycle fraction 0..1
	 * @param px        player world X
	 * @param py        player world Y
	 * @param pz        player world Z
	 */
	public record PlayerState(float health, float hunger, float speed, float light, float timeOfDay, float px, float py, float pz) {
	}

	/**
	 * Client-side entity lookup for world-coordinate bindings, registered once by the client
	 * entrypoint (entities live behind client-only code). An implementation resolves a UUID
	 * directly and a selector through the client level, choosing the matching loaded entity
	 * nearest the camera (ties broken by UUID text), and returns {@code null} when nothing matches.
	 */
	public interface EntityReader {
		/**
		 * The entity's anchor point.
		 *
		 * @param selector selector string, or {@code null} when {@code uuid} is used
		 * @param uuid     entity UUID string, or {@code null} when {@code selector} is used
		 * @param point    {@code feet}, {@code center} or {@code eyes}
		 * @return world {x, y, z}, or {@code null} when no entity matches
		 */
		@Nullable float[] point(@Nullable String selector, @Nullable String uuid, String point);

		/**
		 * The entity's world bounding box.
		 *
		 * @return {minX, minY, minZ, maxX, maxY, maxZ}, or {@code null} when no entity matches
		 */
		@Nullable float[] bounds(@Nullable String selector, @Nullable String uuid);
	}

	/** Registers the client entity reader; {@code null} clears it. */
	public static void setEntityReader(final @Nullable EntityReader reader) {
		entityReader = reader;
		SOURCE_CACHE.clear();
	}

	/**
	 * Resolves a source to a world point ({@code null} when it cannot be resolved), cached for the
	 * frame. A missing entity emits exactly one {@link VFXLog#warnOnce} per source key.
	 */
	public static float @Nullable [] resolveSource(final Source source) {
		if (SOURCE_CACHE.containsKey(source.cacheKey())) {
			return SOURCE_CACHE.get(source.cacheKey());
		}
		final float[] resolved = switch (source.kind()) {
			case CAMERA -> frame == null ? null : new float[]{frame.camX(), frame.camY(), frame.camZ()};
			case PLAYER -> playerState == null ? null : new float[]{playerState.px(), playerState.py(), playerState.pz()};
			case POINT -> new float[]{(float) source.x(), (float) source.y(), (float) source.z()};
			case BLOCK -> new float[]{(float) source.x() + 0.5F, (float) source.y() + 0.5F, (float) source.z() + 0.5F};
			case ENTITY -> {
				final EntityReader reader = entityReader;
				if (reader == null) {
					yield null;
				}
				final float[] base = reader.point(source.selector(), source.uuid(), source.point());
				yield base == null ? null : new float[]{base[0] + source.offset()[0], base[1] + source.offset()[1], base[2] + source.offset()[2]};
			}
		};
		if (resolved == null) {
			VFXLog.warnOnce(LOGGER, "binding:missing:" + source.cacheKey(), "Binding source '{}' could not be resolved; the literal fallback is used", source.cacheKey());
		}
		SOURCE_CACHE.put(source.cacheKey(), resolved);
		return resolved;
	}

	/** The world point of a {@code POINT}-kind binding, or {@code null}. */
	public static float @Nullable [] evaluatePoint(final BoundParam binding) {
		final Source source = binding.source();
		return source == null ? null : resolveSource(source);
	}

	/**
	 * The derived screen rectangle of a {@code SCREEN_RECT} binding, in UV: {centreU, centreV,
	 * halfWidth, halfHeight}. Returns {0, 0, -1, -1} when there is no frame, no entity reader, no
	 * matching entity, a corner behind the camera, or the whole box outside the screen.
	 */
	public static float[] evaluateScreenRect(final BoundParam binding) {
		final float[] empty = {0.0F, 0.0F, -1.0F, -1.0F};
		final Frame current = frame;
		final Source source = binding.source();
		final EntityReader reader = entityReader;
		if (current == null || source == null || source.kind() != SourceKind.ENTITY || reader == null) {
			return empty;
		}
		final String key = "rect:" + source.cacheKey();
		if (SOURCE_CACHE.containsKey(key)) {
			return SOURCE_CACHE.get(key);
		}
		final float[] box = reader.bounds(source.selector(), source.uuid());
		if (box == null) {
			SOURCE_CACHE.put(key, empty);
			VFXLog.warnOnce(LOGGER, "binding:missing:" + source.cacheKey(), "Binding source '{}' could not be resolved; the literal fallback is used", source.cacheKey());
			return empty;
		}
		float minU = Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int corner = 0; corner < 8; corner++) {
			final float dx = ((corner & 1) == 0 ? box[0] : box[3]) - current.camX();
			final float dy = ((corner & 2) == 0 ? box[1] : box[4]) - current.camY();
			final float dz = ((corner & 4) == 0 ? box[2] : box[5]) - current.camZ();
			final Vector4f clip = current.viewRotProj().transform(PROJECTION_SCRATCH.set(dx, dy, dz, 1.0F));
			if (clip.w <= 0.05F) {
				SOURCE_CACHE.put(key, empty);
				return empty;
			}
			final float u = clip.x / clip.w * 0.5F + 0.5F;
			final float v = clip.y / clip.w * 0.5F + 0.5F;
			minU = Math.min(minU, u);
			minV = Math.min(minV, v);
			maxU = Math.max(maxU, u);
			maxV = Math.max(maxV, v);
		}
		if (maxU < 0.0F || minU > 1.0F || maxV < 0.0F || minV > 1.0F) {
			SOURCE_CACHE.put(key, empty);
			return empty;
		}
		final float[] rect = {(minU + maxU) * 0.5F, (minV + maxV) * 0.5F, (maxU - minU) * 0.5F, (maxV - minV) * 0.5F};
		SOURCE_CACHE.put(key, rect);
		return rect;
	}

	private VFXWorldBindings() {
	}

	/**
	 * Snapshot of the camera for one frame.
	 *
	 * @param camX        camera world X
	 * @param camY        camera world Y
	 * @param camZ        camera world Z
	 * @param yaw         camera yaw in degrees
	 * @param pitch       camera pitch in degrees
	 * @param viewRotProj view-rotation-projection matrix (world-relative directions to clip space)
	 */
	public record Frame(float camX, float camY, float camZ, float yaw, float pitch, Matrix4f viewRotProj) {
	}

	/**
	 * Publishes the camera state for the current frame (client only).
	 *
	 * @param deltaTicks elapsed game ticks since the previous frame
	 */
	public static void update(final float camX, final float camY, final float camZ, final float yaw, final float pitch, final Matrix4fc viewRotProj, final float deltaTicks) {
		float rawYawDelta = frame == null ? 0.0F : yaw - lastYaw;
		float rawPitchDelta = frame == null ? 0.0F : pitch - lastPitch;
		// Keep the deltas in a reasonable range to avoid huge spikes on world load/teleport.
		if (Math.abs(rawYawDelta) > 180.0F) {
			rawYawDelta = 0.0F;
		}
		if (Math.abs(rawPitchDelta) > 180.0F) {
			rawPitchDelta = 0.0F;
		}

		float alpha = deltaTicks > 0.0F ? 1.0F - (float) Math.exp(-SMOOTHING_RATE * deltaTicks) : 1.0F;
		smoothedYawDelta += (rawYawDelta - smoothedYawDelta) * alpha;
		smoothedPitchDelta += (rawPitchDelta - smoothedPitchDelta) * alpha;

		lastYaw = yaw;
		lastPitch = pitch;
		SOURCE_CACHE.clear();
		frame = new Frame(camX, camY, camZ, yaw, pitch, new Matrix4f(viewRotProj));
	}

	/**
	 * Publishes the local player state for the current frame (client only).
	 */
	public static void updatePlayerState(final float health, final float hunger, final float speed, final float light, final float timeOfDay, final float px, final float py, final float pz) {
		playerState = new PlayerState(health, hunger, speed, light, timeOfDay, px, py, pz);
	}

	/**
	 * Registers the client-side scoreboard reader (see {@link ScoreboardReader}). Until one is
	 * set — e.g. on a dedicated server — scoreboard bindings evaluate to 0.0.
	 */
	public static void setScoreboardReader(final @Nullable ScoreboardReader reader) {
		scoreboardReader = reader;
	}

	/**
	 * Drops the camera and player state (e.g. when leaving a world).
	 */
	public static void clear() {
		frame = null;
		playerState = null;
		lastYaw = 0.0F;
		lastPitch = 0.0F;
		smoothedYawDelta = 0.0F;
		smoothedPitchDelta = 0.0F;
		SOURCE_CACHE.clear();
	}

	/**
	 * Evaluates a binding against the current camera state.
	 *
	 * @param binding  the bound parameter
	 * @param fallback value returned when no camera state is available
	 * @return the evaluated value
	 */
	public static float evaluate(final BoundParam binding, final float fallback) {
		Frame current = frame;
		if (current == null) {
			return fallback;
		}
		return switch (binding.kind()) {
			case SCREEN_X, SCREEN_Y, PROXIMITY, LOOK, LOOK_AT, DISTANCE -> evaluateSpatial(binding, current, fallback);
			case CAMERA_YAW_DELTA -> smoothedYawDelta * binding.scale();
			case CAMERA_PITCH_DELTA -> smoothedPitchDelta * binding.scale();
			case LOOK_X, LOOK_Y, LOOK_Z -> evaluateLookDirection(binding, current, fallback);
			case HEALTH, HUNGER, SPEED, LIGHT_LEVEL, TIME_OF_DAY, PLAYER_X, PLAYER_Y, PLAYER_Z -> evaluatePlayer(binding, fallback);
			case SCOREBOARD -> evaluateScoreboard(binding);
			case POINT, SCREEN_RECT -> fallback; // vector outputs; callers use evaluatePoint/evaluateScreenRect
		};
	}

	/**
	 * Evaluates a scoreboard binding: the raw score divided by {@code range}, clamped to 0..1,
	 * flipped when inverted, then scaled — the same normalization as the numeric player binds.
	 * A missing client hook, objective, holder or score (never set yet) resolves to 0.0,
	 * silently, like the missing-camera fallbacks above.
	 */
	private static float evaluateScoreboard(final BoundParam binding) {
		final ScoreboardReader reader = scoreboardReader;
		if (reader == null) {
			return 0.0F;
		}
		final Integer raw = reader.score(binding.objective(), binding.holder());
		if (raw == null) {
			return 0.0F;
		}
		float t = Math.min(Math.max(raw / Math.max(binding.range(), 1.0e-4F), 0.0F), 1.0F);
		return (binding.invert() ? 1.0F - t : t) * binding.scale();
	}

	/**
	 * Returns a component of the camera's forward (look) direction unit vector.
	 */
	private static float evaluateLookDirection(final BoundParam binding, final Frame current, final float fallback) {
		float yawRad = (float) Math.toRadians(current.yaw());
		float pitchRad = (float) Math.toRadians(current.pitch());
		float x = forwardX(yawRad, pitchRad);
		float y = forwardY(yawRad, pitchRad);
		float z = forwardZ(yawRad, pitchRad);
		float value = switch (binding.kind()) {
			case LOOK_X -> x;
			case LOOK_Y -> y;
			case LOOK_Z -> z;
			default -> fallback;
		};
		return value * binding.scale();
	}

	/**
	 * Returns the current local player state, or {@code null} when none is available.
	 */
	public static @Nullable PlayerState playerState() {
		return playerState;
	}

	/**
	 * Returns the current camera world position as {@code [x, y, z]}, or {@code [0,0,0]} when no
	 * camera state is available. Used to supply {@code x}/{@code y}/{@code z} to math-expression
	 * parameters ({@code expr}).
	 */
	public static float[] cameraPosition() {
		Frame current = frame;
		if (current == null) {
			return new float[]{0.0F, 0.0F, 0.0F};
		}
		return new float[]{current.camX(), current.camY(), current.camZ()};
	}

	/**
	 * The camera snapshot published for the current frame, or {@code null} when none is available
	 * (e.g. on a dedicated server). Read without allocation by the uniform-graph evaluator.
	 */
	public static @Nullable Frame currentFrame() {
		return frame;
	}

	private static float evaluatePlayer(final BoundParam binding, final float fallback) {
		PlayerState state = playerState;
		if (state == null) {
			return fallback;
		}
		float value = switch (binding.kind()) {
			case HEALTH -> state.health();
			case HUNGER -> state.hunger();
			case SPEED -> Math.min(state.speed() / Math.max(binding.range(), 1.0e-4F), 1.0F);
			case LIGHT_LEVEL -> state.light();
			case TIME_OF_DAY -> state.timeOfDay();
			case PLAYER_X -> state.px();
			case PLAYER_Y -> state.py();
			case PLAYER_Z -> state.pz();
			default -> fallback;
		};
		if (binding.invert()) {
			value = 1.0F - value;
		}
		return value * binding.scale();
	}

	private static float evaluateSpatial(final BoundParam binding, final Frame current, final float fallback) {
		final float[] anchor = anchor(binding);
		if (anchor == null) {
			return fallback;
		}
		float dx = anchor[0] - current.camX();
		float dy = anchor[1] - current.camY();
		float dz = anchor[2] - current.camZ();
		Vector4f clip = current.viewRotProj().transform(new Vector4f(dx, dy, dz, 1.0F));
		boolean inFront = clip.w > 0.05F;
		return switch (binding.kind()) {
			case SCREEN_X -> inFront ? (clip.x / clip.w * 0.5F + 0.5F) * binding.scale() : -binding.scale();
			case SCREEN_Y -> inFront ? (clip.y / clip.w * 0.5F + 0.5F) * binding.scale() : -binding.scale();
			case PROXIMITY -> {
				if (!inFront && !binding.invert()) {
					yield 0.0F;
				}
				float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float t = Math.min(distance / Math.max(binding.range(), 1.0e-4F), 1.0F);
				yield (binding.invert() ? t : 1.0F - t) * binding.scale();
			}
			case LOOK, LOOK_AT -> {
				// Angle between the camera forward vector and the target direction (degrees).
				float camYawRad = (float) Math.toRadians(current.yaw());
				float camPitchRad = (float) Math.toRadians(current.pitch());
				float dot;
				if (binding.kind() == BoundParam.Kind.LOOK_AT) {
					// Target direction derived from the world anchor position (pos) relative to the camera.
					float inv = 1.0F / Math.max((float) Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0e-4F);
					float tx = (float) dx * inv;
					float ty = (float) dy * inv;
					float tz = (float) dz * inv;
					dot = forwardX(camYawRad, camPitchRad) * tx + forwardY(camYawRad, camPitchRad) * ty + forwardZ(camYawRad, camPitchRad) * tz;
				} else {
					float tgtYawRad = (float) Math.toRadians(binding.yaw());
					float tgtPitchRad = (float) Math.toRadians(binding.pitch());
					dot = forwardY(camYawRad, camPitchRad) * forwardY(tgtYawRad, tgtPitchRad)
						+ forwardX(camYawRad, camPitchRad) * forwardX(tgtYawRad, tgtPitchRad)
						+ forwardZ(camYawRad, camPitchRad) * forwardZ(tgtYawRad, tgtPitchRad);
				}
				float angle = (float) Math.toDegrees(Math.acos(Math.max(-1.0F, Math.min(1.0F, dot))));
				float t = Math.min(angle / Math.max(binding.range(), 1.0e-4F), 1.0F);
				yield (binding.invert() ? t : 1.0F - t) * binding.scale();
			}
			case DISTANCE -> {
				float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				yield distance * binding.scale();
			}
			default -> fallback;
		};
	}

	private static float @Nullable [] anchor(final BoundParam binding) {
		if (binding.source() != null) {
			return resolveSource(binding.source());
		}
		return new float[]{(float) binding.x(), (float) binding.y(), (float) binding.z()};
	}

	private static float forwardX(final float yawRad, final float pitchRad) {
		return -(float) (Math.sin(yawRad) * Math.cos(pitchRad));
	}

	private static float forwardY(final float yawRad, final float pitchRad) {
		return -(float) Math.sin(pitchRad);
	}

	private static float forwardZ(final float yawRad, final float pitchRad) {
		return (float) (Math.cos(yawRad) * Math.cos(pitchRad));
	}
}

package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import dev.vfxweaver.effect.VFXSparkSpec;
import dev.vfxweaver.resource.VFXBlockParticleManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Simulates additive spark sprites for the {@code particles} effect's spark mode
 * ({@code "particle": "spark"} or a {@code vfx_particles} preset with {@code "kind": "spark"}).
 *
 * <p>Mirrors {@link VFXBlockParticleEngine}'s pattern: one bucket per running effect instance plus
 * a standalone bucket for API one-shot spawns, a fixed 1-tick integration driven by the shared
 * effect clock (gravity, air drag, optional world collision with bounce), and a prev-to-pos
 * interpolation the renderer samples this frame. It draws nothing itself — the render callback
 * calls {@link #tick}, {@link #emit} and then {@link VFXWorldOverlayRenderer#renderSparks} reads
 * {@link #views(long)} and writes camera-facing quads into the additive glow render type.</p>
 */
public final class VFXSparkEngine {
	/** Global cap on live sparks. */
	private static final int MAX_SPARKS = 4096;
	/** Per-instance cap. */
	private static final int MAX_SPARKS_PER_INSTANCE = 1024;
	/** Cap on simultaneously tracked effect instances. */
	private static final int MAX_BUCKETS = 512;
	/** Fixed integration steps per frame; the rest of the backlog is dropped. */
	private static final int MAX_STEPS_PER_FRAME = 4;
	/** Above this many ticks of backlog the frame is treated as a hitch and the backlog is dropped. */
	private static final float MAX_CATCHUP_TICKS = 8.0F;
	/** Air drag per tick, {@code 0..1} (1 = no drag). A fixed constant keeps the spec small. */
	private static final float AIR_DRAG = 0.97F;
	/** Smallest collision query half-extent, so a tiny spark still depenetrates. */
	private static final double MIN_COLLISION_PADDING = 0.01;

	private static final Map<Long, Bucket> EFFECT_BUCKETS = new HashMap<>();
	private static final Bucket STANDALONE = new Bucket();

	private static @Nullable ClientLevel lastLevel;
	private static float lastClock = Float.NaN;
	private static float accumulator;
	private static int liveCount;

	private VFXSparkEngine() {
	}

	/** One rendered spark, interpolated to the current frame and ready for the renderer's quads. */
	public record SparkView(float x, float y, float z, float size, int rgb, float alpha, boolean hasTrail, float trailX, float trailY, float trailZ) {
	}

	/** One simulated spark. The spec is a per-spark snapshot so animated params never resample old sparks. */
	private static final class Spark {
		final VFXSparkSpec spec;
		final @Nullable Vec3[] history;
		Vec3 pos;
		Vec3 prev;
		Vec3 velocity;
		Vec3 trailFrom;
		float age;
		int historyHead;

		Spark(final VFXSparkSpec spec, final Vec3 position, final Vec3 velocity) {
			this.spec = spec;
			this.pos = position;
			this.prev = position;
			this.velocity = velocity;
			this.trailFrom = position;
			this.history = spec.trail() > 0 ? new Vec3[spec.trail()] : null;
			if (this.history != null) {
				Arrays.fill(this.history, position);
			}
		}
	}

	/** Per-instance spark list plus emission bookkeeping. */
	private static final class Bucket {
		final List<Spark> sparks = new ArrayList<>();
		float lastAge;
		float budget;
	}

	/**
	 * True when the effect's {@code particle} field selects spark mode: the literal
	 * {@code spark}/{@code minecraft:spark} or a registered spark preset id.
	 */
	public static boolean isSparkMode(final VFXActiveEffect effect) {
		final String particleId = effect.getParticleId();
		if (particleId == null || particleId.isBlank()) {
			return false;
		}
		if (particleId.equals("spark") || particleId.equals("minecraft:spark")) {
			return true;
		}
		final Identifier id = Identifier.tryParse(particleId);
		return id != null && VFXBlockParticleManager.get().getSpark(id) != null;
	}

	/**
	 * Resolves the effect's spark spec: the literal {@code spark} defaults, or a registered preset,
	 * with the effect's numeric params laid over it.
	 *
	 * @param effect the running effect
	 * @return the resolved spec, or {@code null} when the preset id is unknown
	 */
	public static @Nullable VFXSparkSpec resolve(final VFXActiveEffect effect) {
		final String particleId = effect.getParticleId();
		final VFXSparkSpec base;
		if (particleId == null || particleId.equals("spark") || particleId.equals("minecraft:spark")) {
			base = VFXSparkSpec.DEFAULT;
		} else {
			final Identifier id = Identifier.tryParse(particleId);
			base = id == null ? null : VFXBlockParticleManager.get().getSpark(id);
		}
		return base == null ? null : base.withOverrides(paramOverrides(effect));
	}

	/**
	 * Emits this frame's sparks for one effect, reusing the effect's shape/positions plumbing.
	 * Emission is budgeted so {@code count} sparks are spread over the effect's whole duration.
	 *
	 * @param effect the running effect
	 * @param level  the client level (for anchored/bound positions)
	 * @param spec   the resolved spec (from {@link #resolve})
	 */
	public static void emit(final VFXActiveEffect effect, final ClientLevel level, final VFXSparkSpec spec) {
		final List<Vec3> anchors = VFXWorldOverlayRenderer.effectPositions(effect, level);
		if (anchors.isEmpty()) {
			return;
		}
		final long key = effect.getInstanceId();
		Bucket bucket = EFFECT_BUCKETS.get(key);
		if (bucket == null) {
			if (EFFECT_BUCKETS.size() >= MAX_BUCKETS) {
				return;
			}
			bucket = new Bucket();
			EFFECT_BUCKETS.put(key, bucket);
		}
		final float age = effect.getAge();
		final float delta = Math.max(0.0F, age - bucket.lastAge);
		bucket.lastAge = age;
		final float weight = effect.getWeight();
		if (weight <= 0.0F || spec.count() <= 0) {
			return;
		}
		final float duration = Math.max(1.0F, effect.getDuration());
		bucket.budget += spec.count() * delta / duration * weight;
		final int count = (int) Math.min(bucket.budget, (float) MAX_SPARKS_PER_INSTANCE);
		if (count <= 0) {
			return;
		}
		bucket.budget -= count;

		boolean aimed = effect.getParam("aim", 0.0F) >= 0.5F;
		Vec3 aimDir = null;
		if (aimed && anchors.size() > 1) {
			final Vec3 to = anchors.get(1).subtract(anchors.get(0));
			if (to.lengthSqr() > 1.0e-6) {
				aimDir = to.normalize();
			}
		}
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		final String shape = effect.getShape() == null ? "sphere" : effect.getShape();
		final float radius = Mth.clamp(effect.getParam("radius", 2.0F), 0.05F, 32.0F);
		final float height = Mth.clamp(effect.getParam("height", 3.0F), 0.5F, 32.0F);
		final float turns = Mth.clamp(effect.getParam("turns", 2.0F), 0.25F, 16.0F);
		final float elapsed = effect.getElapsed() / 20.0F;
		for (int i = 0; i < count; i++) {
			final Vec3 position = VFXWorldOverlayRenderer.sampleShape(shape, anchors, radius, height, turns, elapsed, random);
			if (position == null) {
				return;
			}
			final Vec3 direction;
			if (aimDir != null) {
				Vec3 dir = aimDir;
				if (spec.spread() > 0.0F) {
					final Vec3 jitter = new Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0));
					final Vec3 mixed = dir.add(jitter.scale(spec.spread()));
					if (mixed.lengthSqr() > 1.0e-6) {
						dir = mixed.normalize();
					}
				}
				direction = dir;
			} else {
				final double theta = random.nextDouble() * 6.2831853;
				final double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
				direction = new Vec3(Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta));
			}
			addSpark(bucket, spec, position, direction.scale(spec.speed()));
		}
	}

	/**
	 * Spawns a one-shot spark into the engine's standalone bucket (the API path). No-op when the
	 * global cap is reached or there is no client level.
	 *
	 * @param spec     the spark spec
	 * @param position world position of the spark
	 * @param velocity initial velocity in blocks per tick
	 */
	public static void spawn(final VFXSparkSpec spec, final Vec3 position, final Vec3 velocity) {
		if (spec == null) {
			return;
		}
		final ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		handleLevelChange(level);
		addSpark(STANDALONE, spec, position, velocity);
	}

	/**
	 * Advances the engine by the shared clock: drops buckets whose effect stopped, then integrates
	 * every spark at a fixed 1-tick step.
	 *
	 * @param level           the client level (collision source)
	 * @param clock           the shared effect clock, in ticks
	 * @param activeInstances instance ids of the running spark-mode effects
	 */
	public static void tick(final ClientLevel level, final float clock, final Set<Long> activeInstances) {
		handleLevelChange(level);
		EFFECT_BUCKETS.keySet().removeIf(key -> !activeInstances.contains(key));
		if (Float.isNaN(lastClock)) {
			lastClock = clock;
			return;
		}
		final float delta = clock - lastClock;
		lastClock = clock;
		if (delta <= 0.0F) {
			return;
		}
		accumulator += delta;
		if (delta > MAX_CATCHUP_TICKS) {
			accumulator = 0.0F;
		}
		final int steps = Math.min((int) accumulator, MAX_STEPS_PER_FRAME);
		accumulator -= steps;
		for (int step = 0; step < steps; step++) {
			for (final Bucket bucket : EFFECT_BUCKETS.values()) {
				integrateBucket(level, bucket);
			}
			integrateBucket(level, STANDALONE);
		}
	}

	/**
	 * Drops a running effect instance's sparks. The next {@link #tick} would prune it anyway; this
	 * lets a caller free them immediately.
	 *
	 * @param instanceKey the effect instance id
	 */
	public static void clear(final long instanceKey) {
		EFFECT_BUCKETS.remove(instanceKey);
	}

	/**
	 * The interpolated render list for one effect instance (empty when it has no live sparks).
	 *
	 * @param instanceKey the effect instance id
	 * @return the spark views for this frame
	 */
	public static List<SparkView> views(final long instanceKey) {
		final Bucket bucket = EFFECT_BUCKETS.get(instanceKey);
		if (bucket == null || bucket.sparks.isEmpty()) {
			return List.of();
		}
		final float fraction = Mth.clamp(accumulator, 0.0F, 1.0F);
		final List<SparkView> out = new ArrayList<>(bucket.sparks.size());
		for (final Spark spark : bucket.sparks) {
			final float t = Mth.clamp(spark.age / Math.max(1.0F, spark.spec.life()), 0.0F, 1.0F);
			final float multiplier = spark.spec.sizeAt(t);
			final float size = spark.spec.size() * multiplier;
			if (size <= 0.0F) {
				continue;
			}
			final float x = (float) Mth.lerp(fraction, spark.prev.x, spark.pos.x);
			final float y = (float) Mth.lerp(fraction, spark.prev.y, spark.pos.y);
			final float z = (float) Mth.lerp(fraction, spark.prev.z, spark.pos.z);
			final boolean hasTrail = spark.history != null && spark.history.length > 0;
			final Vec3 trail = spark.trailFrom != null ? spark.trailFrom : spark.pos;
			out.add(new SparkView(x, y, z, size, spark.spec.colorAt(t), Mth.clamp(multiplier, 0.0F, 1.0F), hasTrail,
				(float) trail.x, (float) trail.y, (float) trail.z));
		}
		return out;
	}

	private static void addSpark(final Bucket bucket, final VFXSparkSpec spec, final Vec3 position, final Vec3 velocity) {
		if (liveCount >= MAX_SPARKS || bucket.sparks.size() >= MAX_SPARKS_PER_INSTANCE) {
			return;
		}
		bucket.sparks.add(new Spark(spec, position, velocity));
		liveCount++;
	}

	private static void integrateBucket(final ClientLevel level, final Bucket bucket) {
		for (int i = bucket.sparks.size() - 1; i >= 0; i--) {
			final Spark spark = bucket.sparks.get(i);
			integrate(level, spark);
			if (spark.age >= spark.spec.life()) {
				bucket.sparks.remove(i);
				liveCount--;
			}
		}
	}

	private static void integrate(final ClientLevel level, final Spark spark) {
		final VFXSparkSpec spec = spark.spec;
		spark.age += 1.0F;
		spark.prev = spark.pos;
		double vx = spark.velocity.x;
		double vy = spark.velocity.y - VFXBlockParticleSpec.GRAVITY_STEP * spec.gravity();
		double vz = spark.velocity.z;
		vx *= AIR_DRAG;
		vy *= AIR_DRAG;
		vz *= AIR_DRAG;
		Vec3 next = new Vec3(spark.pos.x + vx, spark.pos.y + vy, spark.pos.z + vz);
		final double padding = Math.max(MIN_COLLISION_PADDING, spec.size() * 0.5);
		final Vec3 resolved = VFXWorldCollision.resolve(level, next, padding, spark.prev);
		if (resolved.distanceToSqr(next) > 1.0e-12) {
			final Vec3 normal = resolved.subtract(next).normalize();
			final double vn = vx * normal.x + vy * normal.y + vz * normal.z;
			if (vn < 0.0) {
				// Reflect the normal component by the restitution; the tangential part keeps its drag.
				final double bounce = Mth.clamp(spec.bounce(), 0.0F, 1.0F);
				vx -= normal.x * vn * (1.0 + bounce);
				vy -= normal.y * vn * (1.0 + bounce);
				vz -= normal.z * vn * (1.0 + bounce);
			}
			next = resolved;
		}
		spark.velocity = new Vec3(vx, vy, vz);
		spark.pos = next;
		if (spark.history != null && spark.history.length > 0) {
			spark.history[spark.historyHead] = spark.pos;
			spark.historyHead = (spark.historyHead + 1) % spark.history.length;
			spark.trailFrom = spark.history[spark.historyHead];
		}
	}

	private static Map<String, Float> paramOverrides(final VFXActiveEffect effect) {
		final Map<String, Float> params = new HashMap<>(8);
		putParam(params, effect, "count");
		putParam(params, effect, "speed");
		putParam(params, effect, "spread");
		putParam(params, effect, "life");
		putParam(params, effect, "gravity");
		putParam(params, effect, "bounce");
		putParam(params, effect, "size");
		putParam(params, effect, "trail");
		return params;
	}

	private static void putParam(final Map<String, Float> out, final VFXActiveEffect effect, final String name) {
		final float value = effect.getParam(name, Float.NaN);
		if (!Float.isNaN(value)) {
			out.put(name, value);
		}
	}

	private static void handleLevelChange(final ClientLevel level) {
		if (lastLevel == level) {
			return;
		}
		lastLevel = level;
		EFFECT_BUCKETS.clear();
		STANDALONE.sparks.clear();
		liveCount = 0;
		accumulator = 0.0F;
		lastClock = Float.NaN;
	}
}

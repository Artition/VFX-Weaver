package dev.vfxweaver.client.render;

import com.mojang.math.Transformation;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import dev.vfxweaver.resource.VFXBlockParticleManager;
import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
//? if >=26.2 {
/*import java.util.concurrent.atomic.AtomicInteger;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
//? if <26.2 {
import net.minecraft.world.entity.EntityType;
//?} else {
/*import net.minecraft.world.entity.EntityTypes;
*///?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulates real block- and item-model particles (the {@code particles} effect's model mode and
 * {@link dev.vfxweaver.api.VFXAPI#spawnBlockParticle}): each particle carries either a full block
 * state or an item stack, integrated through gravity, air drag, optional world collision, lifetime
 * and spin.
 *
 * <p>Rendering goes through one <b>client-side display entity</b> per live particle — a
 * {@link Display.BlockDisplay} for a block spec and a {@link Display.ItemDisplay} for an item spec
 * — added to the {@link ClientLevel} with {@link ClientLevel#addEntity}. The entity's brightness
 * override is the spec's packed brightness (world light when {@code -1}), its transformation
 * carries the spec's {@code size} scale and the particle's tumbling orientation, and its
 * position/rotation are refreshed every frame from the interpolated simulation state (with the
 * old position pinned via {@link net.minecraft.world.entity.Entity#setOldPosAndRot}) so vanilla
 * renders smooth motion instead of the stepped submits the engine used before. The previous
 * submit-pipeline rendering (and its pose/light bookkeeping) is gone; the linear physics
 * (gravity/drag/collision) is unchanged.</p>
 *
 * <p>Each particle has a random full-3D initial orientation and a random tumble axis; the spec's
 * {@code spin} (degrees/tick) is the magnitude of its angular velocity (stored as radians/tick).
 * A contact with a surface damps the angular velocity by that block's friction, so a cube lands
 * and settles instead of spinning forever. The rendered orientation is the slerp of the previous
 * and current tick by the fractional tick.</p>
 *
 * <p>State is per running effect instance (keyed by {@link VFXActiveEffect#getInstanceId()}) plus a
 * single bucket for API one-shot spawns. Integration is a fixed 1-tick step driven by the shared
 * effect clock, with the leftover fraction used to interpolate the displayed position and rotation.
 * Every collection is bounded and every entity is removed when its particle dies, its effect stops
 * or the level changes.</p>
 */
public final class VFXBlockParticleEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/block-particles");
	/** Global cap on live particles (and therefore live display entities); a runaway effect cannot flood the frame. */
	private static final int MAX_BLOCK_PARTICLES = 2048;
	/** Per-instance cap. */
	private static final int MAX_PARTICLES_PER_INSTANCE = 512;
	/** Cap on simultaneously tracked effect instances. */
	private static final int MAX_BUCKETS = 512;
	/** Fixed integration steps per frame; the rest of the backlog is dropped. */
	private static final int MAX_STEPS_PER_FRAME = 4;
	/** Above this many ticks of backlog the frame is treated as a hitch and the backlog is dropped. */
	private static final float MAX_CATCHUP_TICKS = 8.0F;
	/** Smallest collision query half-extent, so a tiny particle still depenetrates. */
	private static final double MIN_COLLISION_PADDING = 0.01;
	/** The spec's {@code spin} is degrees/tick; the engine stores angular velocity in radians/tick. */
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
	/** Fraction of the tangential slip converted to roll on contact, in radians/tick per block/tick. */
	private static final float CONTACT_ROLL_TRANSFER = 0.5F;
	/** Slip (blocks/tick) cap for the contact roll bleed, so a fast slide cannot spin a cube up absurdly. */
	private static final float MAX_ROLL_SLIP = 1.0F;

	private static final Map<Long, Bucket> EFFECT_BUCKETS = new HashMap<>();
	private static final Bucket STANDALONE = new Bucket();

	private static @Nullable ClientLevel lastLevel;
	private static float lastClock = Float.NaN;
	private static float accumulator;
	private static int liveCount;
	/** Block states already checked for baked geometry, so the model query runs once each. */
	private static final Map<BlockState, Boolean> MODEL_GEOMETRY = new HashMap<>();
	private static final int MAX_MODEL_CHECKS = 256;
	//? if >=26.2 {
	/*// Client-local display-entity ids. 26.2 assigns entity ids from the level, but neither
	// ClientLevel nor a base Level has a counter (Level.getNextEntityId() returns 0 and only
	// ServerLevel overrides it), so a locally created display would make ClientLevel.addEntity
	// throw on Entity.getId(). This counter starts at Integer.MAX_VALUE and walks down, so it can
	// never collide with a server entity id (ServerLevel.ENTITY_COUNTER starts at 0 and walks up).
	private static final AtomicInteger LOCAL_ENTITY_IDS = new AtomicInteger(Integer.MAX_VALUE);
	*///?}

	private VFXBlockParticleEngine() {
	}

	/** One simulated particle; {@code spec} is shared and immutable and {@code display} is its live entity. */
	private static final class Particle {
		Vec3 pos;
		Vec3 prev;
		Vec3 velocity;
		float age;
		/** Current orientation; a full 3D rotation, not a yaw. */
		final Quaternionf orientation;
		/** Orientation at the start of the current tick, slerped from for display interpolation. */
		final Quaternionf prevOrientation;
		/** Angular velocity in radians per tick; its direction is the (body-fixed) tumble axis. */
		final Vector3f angularVelocity;
		final VFXBlockParticleSpec spec;
		final @Nullable Display display;

		Particle(final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity, final Quaternionf orientation, final Vector3f angularVelocity, final @Nullable Display display) {
			this.spec = spec;
			this.pos = position;
			this.prev = position;
			this.velocity = velocity;
			this.orientation = new Quaternionf(orientation);
			this.prevOrientation = new Quaternionf(orientation);
			this.angularVelocity = new Vector3f(angularVelocity);
			this.display = display;
		}
	}

	/** Per-instance particle list plus emission bookkeeping for effects. */
	private static final class Bucket {
		final List<Particle> particles = new ArrayList<>();
		float lastAge;
		float budget;
	}

	/**
	 * True when the effect's {@code particle} field selects the model mode: the literal
	 * {@code block}/{@code minecraft:block}, the literal {@code item}/{@code minecraft:item}, or
	 * the id of a registered preset (block- or item-based).
	 */
	public static boolean isModelMode(final VFXActiveEffect effect) {
		final String particleId = effect.getParticleId();
		if (particleId == null || particleId.isBlank()) {
			return false;
		}
		if (particleId.equals("block") || particleId.equals("minecraft:block")
			|| particleId.equals("item") || particleId.equals("minecraft:item")) {
			return true;
		}
		final Identifier id = Identifier.tryParse(particleId);
		return id != null && VFXBlockParticleManager.get().get(id) != null;
	}

	/**
	 * Emits this frame's block particles for one {@code particles} effect, reusing the effect's
	 * shape, rate, positions/bindings and aimed-mode plumbing. The preset (or inline block field)
	 * supplies the base spec; the effect's params override it.
	 *
	 * @param effect the running effect
	 * @param level  the client level (for anchored/bound positions)
	 */
	public static void emit(final VFXActiveEffect effect, final ClientLevel level) {
		final VFXBlockParticleSpec spec = resolveSpec(effect);
		if (spec == null) {
			return;
		}
		final List<Vec3> anchors = VFXWorldOverlayRenderer.effectPositions(effect, level);
		if (anchors.isEmpty()) {
			return;
		}
		final float radius = Mth.clamp(effect.getParam("radius", 2.0F), 0.05F, 32.0F);
		final float height = Mth.clamp(effect.getParam("height", 3.0F), 0.5F, 32.0F);
		final float turns = Mth.clamp(effect.getParam("turns", 2.0F), 0.25F, 16.0F);
		final float speed = Mth.clamp(effect.getParam("speed", 0.0F), 0.0F, 8.0F);
		final float velY = Mth.clamp(effect.getParam("vel_y", 0.0F), -4.0F, 4.0F);
		final float spread = Mth.clamp(effect.getParam("spread", 0.15F), 0.0F, 1.0F);
		final boolean aimed = effect.getParam("aim", 0.0F) >= 0.5F;

		Vec3 aimDir = null;
		if (aimed && anchors.size() > 1) {
			final Vec3 to = anchors.get(1).subtract(anchors.get(0));
			if (to.lengthSqr() > 1.0e-6) {
				aimDir = to.normalize();
			}
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
		final float rate = Mth.clamp(effect.getParam("rate", 40.0F), 0.0F, VFXWorldOverlayRenderer.MAX_PARTICLE_RATE) * effect.getWeight();
		if (rate <= 0.0F) {
			return;
		}
		bucket.budget += rate * delta / 20.0F;
		int count = (int) Math.min(bucket.budget, (float) VFXWorldOverlayRenderer.MAX_PARTICLES_PER_FRAME);
		if (count <= 0) {
			return;
		}
		bucket.budget -= count;

		final String shape = effect.getShape() == null ? "sphere" : effect.getShape();
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		// The block-mode 'spin' param is the tumble speed (degrees/tick); the shape keeps elapsed.
		final float elapsed = effect.getElapsed() / 20.0F;
		for (int i = 0; i < count; i++) {
			final Vec3 position = VFXWorldOverlayRenderer.sampleShape(shape, anchors, radius, height, turns, elapsed, random);
			if (position == null) {
				return;
			}
			double vx = 0.0;
			double vy = velY;
			double vz = 0.0;
			if (aimDir != null) {
				Vec3 dir = aimDir;
				if (spread > 0.0F) {
					final Vec3 jitter = new Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0));
					final Vec3 mixed = dir.add(jitter.scale(spread));
					if (mixed.lengthSqr() > 1.0e-6) {
						dir = mixed.normalize();
					}
				}
				vx = dir.x * speed;
				vy += dir.y * speed;
				vz = dir.z * speed;
			} else if (speed > 0.0F) {
				final double theta = random.nextDouble() * 6.2831853;
				final double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
				vx = Math.sin(phi) * Math.cos(theta) * speed;
				vy += Math.cos(phi) * speed;
				vz = Math.sin(phi) * Math.sin(theta) * speed;
			}
			final Vec3 velocity = new Vec3(vx, vy, vz);
			addParticle(bucket, level, spec, position, velocity);
		}
	}

	/**
	 * Spawns a one-shot block particle into the engine's standalone bucket (the API path). No-op
	 * when the global cap is reached, the spec has no drawable block, or there is no client level.
	 *
	 * @param spec     the particle's block/item and physics
	 * @param position world position of the particle centre
	 * @param velocity initial velocity in blocks per tick
	 */
	public static void spawn(final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
		if (spec == null || (!spec.hasBlock() && !spec.hasItem())) {
			return;
		}
		final ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		handleLevelChange(level);
		addParticle(STANDALONE, level, spec, position, velocity);
	}

	/**
	 * Advances the engine by the shared clock: drops buckets whose effect stopped (removing their
	 * displays), then integrates every particle at a fixed 1-tick step (gravity, air drag,
	 * collision/bounce, lifetime, tumble) and drives every display entity from the interpolated
	 * simulation state.
	 *
	 * @param level           the client level (collision source)
	 * @param clock           the shared effect clock, in ticks
	 * @param activeInstances instance ids of the running block-mode effects
	 */
	public static void tick(final ClientLevel level, final float clock, final Set<Long> activeInstances) {
		handleLevelChange(level);
		EFFECT_BUCKETS.entrySet().removeIf(entry -> {
			if (activeInstances.contains(entry.getKey())) {
				return false;
			}
			removeDisplays(entry.getValue());
			return true;
		});
		if (Float.isNaN(lastClock)) {
			lastClock = clock;
			return;
		}
		final float delta = clock - lastClock;
		lastClock = clock;
		if (delta > 0.0F) {
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
		// Refresh every display entity from prev -> pos (and prev orientation -> orientation) by the
		// leftover tick fraction, pinning the old position so vanilla renders exactly this value.
		final float fraction = Mth.clamp(accumulator, 0.0F, 1.0F);
		for (final Bucket bucket : EFFECT_BUCKETS.values()) {
			for (final Particle particle : bucket.particles) {
				updateDisplay(particle, fraction);
			}
		}
		for (final Particle particle : STANDALONE.particles) {
			updateDisplay(particle, fraction);
		}
	}

	/**
	 * Drops a running effect instance's particles (the effect stopped), removing their displays.
	 * The next {@link #tick} would prune it anyway; this lets a caller free them immediately.
	 *
	 * @param instanceKey the effect instance id
	 */
	public static void clear(final long instanceKey) {
		final Bucket removed = EFFECT_BUCKETS.remove(instanceKey);
		if (removed != null) {
			removeDisplays(removed);
		}
	}

	/**
	 * Drops all state (and display references) when the client level changes. The previous level's
	 * entities are discarded with that level, so only the bookkeeping needs clearing.
	 */
	private static void handleLevelChange(final ClientLevel level) {
		if (lastLevel == level) {
			return;
		}
		lastLevel = level;
		EFFECT_BUCKETS.clear();
		STANDALONE.particles.clear();
		liveCount = 0;
		accumulator = 0.0F;
		lastClock = Float.NaN;
	}

	private static void integrateBucket(final ClientLevel level, final Bucket bucket) {
		final List<Particle> particles = bucket.particles;
		for (int i = particles.size() - 1; i >= 0; i--) {
			final Particle particle = particles.get(i);
			integrate(level, particle);
			if (particle.age >= particle.spec.life()) {
				removeDisplay(particle);
				particles.remove(i);
				liveCount--;
			}
		}
	}

	private static void integrate(final ClientLevel level, final Particle particle) {
		final VFXBlockParticleSpec spec = particle.spec;
		particle.age += 1.0F;
		particle.prev = particle.pos;
		particle.prevOrientation.set(particle.orientation);
		double vx = particle.velocity.x;
		double vy = particle.velocity.y - VFXBlockParticleSpec.GRAVITY_STEP * spec.gravity();
		double vz = particle.velocity.z;
		final float friction = Mth.clamp(spec.friction(), 0.0F, 1.0F);
		vx *= friction;
		vy *= friction;
		vz *= friction;
		Vec3 next = new Vec3(particle.pos.x + vx, particle.pos.y + vy, particle.pos.z + vz);
		if (spec.collide() > 0.0F) {
			final double padding = Math.max(MIN_COLLISION_PADDING, spec.size() * 0.5);
			final Vec3 resolved = VFXWorldCollision.resolve(level, next, padding, particle.prev);
			if (resolved.distanceToSqr(next) > 1.0e-12) {
				final Vec3 normal = resolved.subtract(next).normalize();
				final double vn = vx * normal.x + vy * normal.y + vz * normal.z;
				if (vn < 0.0) {
					// Drop the inward normal component, damp the tangential one by 'collide' and
					// reflect the normal part by 'bounce' (restitution).
					final double tx = vx - normal.x * vn;
					final double ty = vy - normal.y * vn;
					final double tz = vz - normal.z * vn;
					final double retain = 1.0 - Mth.clamp(spec.collide(), 0.0F, 1.0F);
					final double bounce = Mth.clamp(spec.bounce(), 0.0F, 1.0F);
					vx = tx * retain - normal.x * vn * bounce;
					vy = ty * retain - normal.y * vn * bounce;
					vz = tz * retain - normal.z * vn * bounce;
					// A little of the tangential slip becomes roll about n x v_t, so a cube that
					// lands skidding rolls instead of sliding flat. Optional heuristic: capped and
					// zero when there is no slip (a particle spawned embedded in geometry has none).
					final double slip = Math.sqrt(tx * tx + ty * ty + tz * tz);
					if (slip > 1.0e-4) {
						final float invSlip = (float) (1.0 / slip);
						final float ax = (float) (normal.y * tz - normal.z * ty) * invSlip;
						final float ay = (float) (normal.z * tx - normal.x * tz) * invSlip;
						final float az = (float) (normal.x * ty - normal.y * tx) * invSlip;
						final float gain = (float) Math.min(slip, MAX_ROLL_SLIP) * CONTACT_ROLL_TRANSFER;
						particle.angularVelocity.x += gain * ax;
						particle.angularVelocity.y += gain * ay;
						particle.angularVelocity.z += gain * az;
					}
				}
				// A touched surface damps the tumble by its own friction (stone 0.6 grips, ice
				// 0.98 glides - the same rule the block_chain rope uses), so a cube settles.
				final float surfaceFriction = (float) VFXWorldOverlayRenderer.contactFriction(level, resolved, normal);
				particle.angularVelocity.mul(surfaceFriction);
				next = resolved;
			}
		}
		particle.velocity = new Vec3(vx, vy, vz);
		particle.pos = next;
		// Integrate the orientation about the (body-fixed) tumble axis. joml's rotateAxis uses the
		// axis direction (normalised internally) and the given angle, so the raw angular-velocity
		// vector works: angle = |w| * dt with dt = 1 tick.
		final float speed = particle.angularVelocity.length();
		if (speed > 1.0e-6F) {
			particle.orientation.rotateAxis(speed, particle.angularVelocity.x, particle.angularVelocity.y, particle.angularVelocity.z);
		}
	}

	/** Positions and rotates one particle's display entity at the interpolated simulation state. */
	private static void updateDisplay(final Particle particle, final float fraction) {
		final Display display = particle.display;
		if (display == null) {
			return;
		}
		final Vec3 position = new Vec3(
			Mth.lerp(fraction, particle.prev.x, particle.pos.x),
			Mth.lerp(fraction, particle.prev.y, particle.pos.y),
			Mth.lerp(fraction, particle.prev.z, particle.pos.z)
		);
		display.setPos(position.x, position.y, position.z);
		// Slerp the orientation by the leftover tick fraction so the tumble is smooth between ticks.
		final Quaternionf orientation = new Quaternionf(particle.prevOrientation).slerp(particle.orientation, fraction);
		applyTransform(display, particle.spec.size(), orientation);
		// Re-anchor the one-tick interpolation window to the current tick every frame (the setter
		// forces the synched-data update), so the display slerps from the previously rendered
		// transformation to this frame's target instead of holding it until the next entity tick.
		display.setTransformationInterpolationDelay(0);
		// Pin the old position to the value just set, so the frame renders this exact interpolation
		// instead of a second (vanilla) lerp between this and the previous tick's target.
		display.setOldPosAndRot();
	}

	/**
	 * Applies the spec's scale and the particle's orientation to a display, centred on the particle.
	 * The two display kinds have different pivots:
	 *
	 * <p>A {@link Display.BlockDisplay} draws the raw block model (geometry spans 0..1), so its
	 * pivot is the bottom-north-west corner; it must be translated by half its scaled size to put
	 * its centre on the display's position. {@link Transformation} composes as {@code translation *
	 * leftRotation * scale * rightRotation} (a point is transformed right-to-left), so the translation
	 * must be pre-rotated by the orientation or the model would orbit instead of spinning in place.
	 * With the centre {@code c = (0.5, 0.5, 0.5) * size}, {@code T = -L * c} maps {@code c} to the
	 * display origin for any orientation.</p>
	 *
	 * <p>A {@link Display.ItemDisplay} is already centred: {@code ItemDisplayContext.NONE} selects
	 * {@link net.minecraft.client.resources.model.cuboid.ItemTransform#NO_TRANSFORM}, whose
	 * {@code apply} translates the model by {@code (-0.5, -0.5, -0.5)} in
	 * {@code ItemStackRenderState}, and the item renderer adds a 180° Y flip — both inside this
	 * transformation, so the item's centre is at the display origin and needs no translation. (That
	 * built-in flip is the item's own base orientation; the tumble composes on top of it.)</p>
	 */
	private static void applyTransform(final Display display, final float size, final Quaternionf orientation) {
		final Vector3f scale = new Vector3f(size, size, size);
		final Vector3f translation = display instanceof Display.ItemDisplay
			? new Vector3f()
			: orientation.transform(new Vector3f(0.5F * size, 0.5F * size, 0.5F * size)).negate();
		display.setTransformation(new Transformation(translation, new Quaternionf(orientation), scale, new Quaternionf()));
	}

	/** Creates and adds the display entity for one particle; {@code null} when the spec cannot draw. */
	private static @Nullable Display createDisplay(final ClientLevel level, final VFXBlockParticleSpec spec, final Quaternionf orientation) {
		final Display display;
		if (spec.hasItem()) {
			//? if <26.2 {
			final Display.ItemDisplay item = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			//?} else {
			/*final Display.ItemDisplay item = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
			*///?}
			item.setItemStack(spec.item());
			display = item;
		} else {
			final BlockState state = spec.block();
			if (state == null) {
				return null;
			}
			//? if <26.2 {
			final Display.BlockDisplay blockDisplay = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
			//?} else {
			/*final Display.BlockDisplay blockDisplay = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			*///?}
			blockDisplay.setBlockState(state);
			display = blockDisplay;
		}
		if (spec.brightness() >= 0) {
			display.setBrightnessOverride(Brightness.unpack(spec.brightness()));
		}
		applyTransform(display, spec.size(), orientation);
		// Make the display interpolate its transformation over exactly one tick. Without this the
		// render state is only rebuilt on the entity tick, so a transformation refreshed every frame
		// still renders as the old ~20 Hz stepped spin.
		display.setTransformationInterpolationDuration(1);
		display.setTransformationInterpolationDelay(0);
		return display;
	}

	private static void addParticle(final Bucket bucket, final ClientLevel level, final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
		if (liveCount >= MAX_BLOCK_PARTICLES || bucket.particles.size() >= MAX_PARTICLES_PER_INSTANCE) {
			return;
		}
		if (!isRenderable(spec)) {
			return;
		}
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		final Quaternionf orientation = randomOrientation(random);
		final Vector3f angularVelocity = randomAngularVelocity(random, spec.spin());
		final Display display = createDisplay(level, spec, orientation);
		if (display == null) {
			return;
		}
		display.setPos(position.x, position.y, position.z);
		display.setOldPosAndRot();
		try {
			//? if >=26.2 {
			/*display.setId(LOCAL_ENTITY_IDS.getAndDecrement());
			*///?}
			level.addEntity(display);
		} catch (Exception e) {
			VFXLog.warnOnce(LOGGER, "spawn-failed", "Failed to add a block/item particle display to the client level; dropping the particle", e);
			display.discard();
			return;
		}
		bucket.particles.add(new Particle(spec, position, velocity, orientation, angularVelocity, display));
		liveCount++;
	}

	/**
	 * A random full-3D orientation: three random Euler angles folded into one quaternion. Not the
	 * Haar-uniform distribution on SO(3), but visually indistinguishable for a particle.
	 */
	private static Quaternionf randomOrientation(final ThreadLocalRandom random) {
		final float twoPi = (float) (Math.PI * 2.0);
		return new Quaternionf().rotateXYZ(
			random.nextFloat() * twoPi,
			random.nextFloat() * twoPi,
			random.nextFloat() * twoPi
		);
	}

	/**
	 * A random tumble: a random unit axis times the spec's {@code spin} magnitude. {@code spin} is
	 * degrees/tick; the returned angular velocity is radians/tick, so the integration angle per tick
	 * is the spin converted to radians. Zero spin yields a static (but randomly oriented) particle.
	 */
	private static Vector3f randomAngularVelocity(final ThreadLocalRandom random, final float spinDegrees) {
		if (spinDegrees == 0.0F) {
			return new Vector3f();
		}
		float x;
		float y;
		float z;
		float lenSqr;
		do {
			x = random.nextFloat() * 2.0F - 1.0F;
			y = random.nextFloat() * 2.0F - 1.0F;
			z = random.nextFloat() * 2.0F - 1.0F;
			lenSqr = x * x + y * y + z * z;
		} while (lenSqr < 1.0e-6F);
		final float inv = (float) (1.0 / Math.sqrt(lenSqr));
		final float speed = spinDegrees * DEG_TO_RAD;
		return new Vector3f(x * inv * speed, y * inv * speed, z * inv * speed);
	}

	private static void removeDisplays(final Bucket bucket) {
		for (final Particle particle : bucket.particles) {
			removeDisplay(particle);
		}
		liveCount -= bucket.particles.size();
		bucket.particles.clear();
	}

	private static void removeDisplay(final Particle particle) {
		if (particle.display != null && !particle.display.isRemoved()) {
			particle.display.discard();
		}
	}

	/**
	 * True when the spec can actually draw: an item spec (its model is resolved by the item
	 * display), or a block whose baked block model is non-empty — otherwise a block display renders
	 * nothing (see {@link VFXWorldOverlayRenderer#hasBlockModelGeometry}). Warns once per block so
	 * an unusable block is not a silent no-op. Cached per state.
	 */
	private static boolean isRenderable(final VFXBlockParticleSpec spec) {
		if (spec.hasItem()) {
			return true;
		}
		final BlockState block = spec.block();
		if (block == null) {
			return false;
		}
		final Boolean cached = MODEL_GEOMETRY.get(block);
		if (cached != null) {
			return cached;
		}
		final boolean renderable = VFXWorldOverlayRenderer.hasBlockModelGeometry(block);
		if (MODEL_GEOMETRY.size() >= MAX_MODEL_CHECKS) {
			MODEL_GEOMETRY.clear();
		}
		MODEL_GEOMETRY.put(block, renderable);
		if (!renderable) {
			VFXLog.warnOnce(LOGGER, "no-model:" + block, "Block-particle block '{}' has no baked block model (its world shape is drawn by a block-entity renderer, e.g. a skull), so a block display renders nothing for it; use the item form instead", block);
		}
		return renderable;
	}

	/**
	 * Resolves the effect's block mode into a spec: the inline {@code block} field with the
	 * renderer defaults for {@code particle: "block"}, or a registered preset's spec. The effect's
	 * spec params are laid over the base.
	 */
	private static @Nullable VFXBlockParticleSpec resolveSpec(final VFXActiveEffect effect) {
		final String particleId = effect.getParticleId();
		VFXBlockParticleSpec base;
		if (particleId == null || particleId.equals("block") || particleId.equals("minecraft:block")
			|| particleId.equals("item") || particleId.equals("minecraft:item")) {
			base = inlineSpec(effect, particleId);
		} else {
			final Identifier id = Identifier.tryParse(particleId);
			base = id == null ? null : VFXBlockParticleManager.get().get(id);
		}
		return base == null ? null : base.withOverrides(paramOverrides(effect));
	}

	/**
	 * Builds the inline spec from the effect's {@code block} field (block mode, defaulting to
	 * stone) or {@code item} field (item mode). Returns {@code null} when the item id is missing or
	 * unknown, with a one-time warning.
	 */
	private static @Nullable VFXBlockParticleSpec inlineSpec(final VFXActiveEffect effect, final @Nullable String particleId) {
		if (particleId != null && (particleId.equals("item") || particleId.equals("minecraft:item"))) {
			final String itemId = effect.getItemId();
			if (itemId == null || itemId.isBlank()) {
				VFXLog.warnOnce(LOGGER, "item:none", "Item-particle effect '{}' has no 'item' id; nothing is spawned", effect.getId());
				return null;
			}
			final ItemStack stack = VFXBlockParticleSpec.parseItem(itemId);
			if (stack == null) {
				VFXLog.warnOnce(LOGGER, "item:" + itemId, "Unknown particle item '{}' in effect '{}'; nothing is spawned", itemId, effect.getId());
				return null;
			}
			return VFXBlockParticleSpec.item(stack);
		}
		final String blockId = effect.getBlockId();
		if (blockId == null || blockId.isBlank()) {
			return VFXBlockParticleSpec.DEFAULT;
		}
		final BlockState state = VFXBlockParticleSpec.parseBlockState(blockId);
		if (state == null) {
			VFXLog.warnOnce(LOGGER, "block:" + blockId, "Unknown block-particle block '{}' in effect '{}'; using stone", blockId, effect.getId());
			return VFXBlockParticleSpec.DEFAULT;
		}
		return VFXBlockParticleSpec.builder(state).build();
	}

	private static Map<String, Float> paramOverrides(final VFXActiveEffect effect) {
		final Map<String, Float> params = new HashMap<>(8);
		putParam(params, effect, "brightness");
		putParam(params, effect, "gravity");
		putParam(params, effect, "friction");
		putParam(params, effect, "collide");
		putParam(params, effect, "bounce");
		putParam(params, effect, "size");
		putParam(params, effect, "life");
		putParam(params, effect, "spin");
		return params;
	}

	private static void putParam(final Map<String, Float> out, final VFXActiveEffect effect, final String name) {
		final float value = effect.getParam(name, Float.NaN);
		if (!Float.isNaN(value)) {
			out.put(name, value);
		}
	}
}

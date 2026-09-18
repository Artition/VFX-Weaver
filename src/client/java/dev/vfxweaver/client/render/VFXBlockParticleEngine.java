package dev.vfxweaver.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if <26.1 {
/*import net.minecraft.client.renderer.state.CameraRenderState;
*///?} else {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulates and submits real block- and item-model particles (the {@code particles} effect's model
 * mode and {@link dev.vfxweaver.api.VFXAPI#spawnBlockParticle}): each particle carries either a
 * full block state (rendered through the {@code block_chain} moving-block submit path) or an item
 * stack (rendered through the vanilla item submit path), integrated through gravity, air drag,
 * optional world collision, lifetime and spin.
 *
 * <p>State is per running effect instance (keyed by {@link VFXActiveEffect#getInstanceId()}) plus a
 * single bucket for API one-shot spawns. Integration is a fixed 1-tick step driven by the shared
 * effect clock, with the leftover fraction used to interpolate the rendered position (the rope
 * idiom), so fast particles do not stutter at low tick rates. Every collection is bounded.</p>
 */
public final class VFXBlockParticleEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/block-particles");
	/** Global cap on live particles; a runaway effect cannot flood the frame. */
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

	private static final Map<Long, Bucket> EFFECT_BUCKETS = new HashMap<>();
	private static final Bucket STANDALONE = new Bucket();

	private static @Nullable ClientLevel lastLevel;
	private static float lastClock = Float.NaN;
	private static float accumulator;
	private static int liveCount;
	/** Block states already checked for baked geometry, so the model query runs once each. */
	private static final Map<BlockState, Boolean> MODEL_GEOMETRY = new HashMap<>();
	private static final int MAX_MODEL_CHECKS = 256;
	/**
	 * Baked item models per item (shared by every particle of that item). Built once on the render
	 * thread; an animated item model (clock/compass) therefore samples once, which is fine for a
	 * short-lived particle. Bounded and cleared on level change.
	 * ponytail: per-item cache, refresh per frame if an animated particle item is ever requested.
	 */
	private static final Map<Item, ItemStackRenderState> ITEM_STATES = new HashMap<>();
	private static final int MAX_ITEM_STATES = 256;

	private VFXBlockParticleEngine() {
	}

	/** One simulated particle; {@code spec} is shared and immutable. */
	private static final class Particle {
		Vec3 pos;
		Vec3 prev;
		Vec3 velocity;
		float age;
		float rotation;
		final VFXBlockParticleSpec spec;

		Particle(final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
			this.spec = spec;
			this.pos = position;
			this.prev = position;
			this.velocity = velocity;
			this.rotation = (float) (ThreadLocalRandom.current().nextDouble() * 360.0);
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
		// The block-mode 'spin' param is the particle yaw (degrees/tick); the shape keeps elapsed.
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
			addParticle(bucket, spec, position, velocity);
		}
	}

	/**
	 * Spawns a one-shot block particle into the engine's standalone bucket (the API path). No-op
	 * when the global cap is reached or the spec has no drawable block.
	 *
	 * @param spec     the particle's block and physics
	 * @param position world position of the particle centre
	 * @param velocity initial velocity in blocks per tick
	 */
	public static void spawn(final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
		if (spec == null || (!spec.hasBlock() && !spec.hasItem())) {
			return;
		}
		addParticle(STANDALONE, spec, position, velocity);
	}

	/**
	 * Advances the engine by the shared clock: drops buckets whose effect stopped, then integrates
	 * every particle at a fixed 1-tick step (gravity, air drag, collision/bounce, lifetime, spin).
	 *
	 * @param level           the client level (collision source)
	 * @param clock           the shared effect clock, in ticks
	 * @param activeInstances instance ids of the running block-mode effects
	 */
	public static void tick(final ClientLevel level, final float clock, final Set<Long> activeInstances) {
		if (lastLevel != level) {
			lastLevel = level;
			EFFECT_BUCKETS.clear();
			STANDALONE.particles.clear();
			ITEM_STATES.clear();
			liveCount = 0;
			accumulator = 0.0F;
			lastClock = Float.NaN;
		}
		EFFECT_BUCKETS.entrySet().removeIf(entry -> {
			if (activeInstances.contains(entry.getKey())) {
				return false;
			}
			liveCount -= entry.getValue().particles.size();
			return true;
		});
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
	 * Submits every live particle through the shared block-model submit path, from the interpolated
	 * {@code prev -> pos} position by this frame's leftover tick fraction.
	 *
	 * @param collector the current submit node collector
	 * @param camera    the current camera state (poses are camera-relative)
	 * @param level     the client level (light + biome source)
	 */
	public static void render(final SubmitNodeCollector collector, final CameraRenderState camera, final ClientLevel level) {
		if (liveCount == 0) {
			return;
		}
		final float fraction = Mth.clamp(accumulator, 0.0F, 1.0F);
		for (final Bucket bucket : EFFECT_BUCKETS.values()) {
			submitBucket(collector, camera, level, bucket, fraction);
		}
		submitBucket(collector, camera, level, STANDALONE, fraction);
	}

	/**
	 * Drops a running effect instance's particles (the effect stopped). The next {@link #tick} would
	 * prune it anyway; this lets a caller free them immediately.
	 *
	 * @param instanceKey the effect instance id
	 */
	public static void clear(final long instanceKey) {
		final Bucket removed = EFFECT_BUCKETS.remove(instanceKey);
		if (removed != null) {
			liveCount -= removed.particles.size();
		}
	}

	private static void integrateBucket(final ClientLevel level, final Bucket bucket) {
		final List<Particle> particles = bucket.particles;
		for (int i = particles.size() - 1; i >= 0; i--) {
			final Particle particle = particles.get(i);
			integrate(level, particle);
			if (particle.age >= particle.spec.life()) {
				particles.remove(i);
				liveCount--;
			}
		}
	}

	private static void integrate(final ClientLevel level, final Particle particle) {
		final VFXBlockParticleSpec spec = particle.spec;
		particle.age += 1.0F;
		particle.prev = particle.pos;
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
				}
				next = resolved;
			}
		}
		particle.velocity = new Vec3(vx, vy, vz);
		particle.pos = next;
		particle.rotation += spec.spin();
	}

	private static void submitBucket(final SubmitNodeCollector collector, final CameraRenderState camera, final ClientLevel level, final Bucket bucket, final float fraction) {
		for (final Particle particle : bucket.particles) {
			final Vec3 position = new Vec3(
				Mth.lerp(fraction, particle.prev.x, particle.pos.x),
				Mth.lerp(fraction, particle.prev.y, particle.pos.y),
				Mth.lerp(fraction, particle.prev.z, particle.pos.z)
			);
			final BlockPos lightPos = BlockPos.containing(position.x, position.y, position.z);
			final PoseStack pose = new PoseStack();
			pose.pushPose();
			pose.translate(position.x - camera.pos.x, position.y - camera.pos.y, position.z - camera.pos.z);
			if (particle.rotation != 0.0F) {
				pose.mulPose(new Quaternionf().rotationY((float) Math.toRadians(particle.rotation)));
			}
			final float scale = particle.spec.size();
			pose.scale(scale, scale, scale);
			pose.translate(-0.5, -0.5, -0.5);
			if (particle.spec.hasItem()) {
				submitItem(collector, pose, level, lightPos, particle.spec);
			} else {
				final ParticleBlockState state = new ParticleBlockState();
				state.blockState = particle.spec.block();
				state.blockPos = lightPos;
				state.randomSeedPos = lightPos;
				state.biome = level.getBiome(lightPos);
				//? if <26.1 {
				/*state.level = level;
				*///?} else {
				state.cardinalLighting = level.cardinalLighting();
				state.lightEngine = level.getLightEngine();
				//?}
				if (particle.spec.brightness() >= 0) {
					state.packedBrightness = particle.spec.brightness();
				}
				VFXWorldOverlayRenderer.submitMovingBlock(collector, pose, state);
			}
			pose.popPose();
		}
	}

	/**
	 * Submits one item-model particle through the vanilla item submit path (the same
	 * {@code ItemStackRenderState.submit} route dropped items and item frames use). The baked model
	 * is cached per item; a built-but-empty model warns once instead of drawing nothing silently.
	 * The light is the spec's packed override or the world light at the particle, matching the
	 * block path's brightness semantics.
	 */
	private static void submitItem(final SubmitNodeCollector collector, final PoseStack pose, final ClientLevel level, final BlockPos lightPos, final VFXBlockParticleSpec spec) {
		final ItemStack stack = spec.item();
		final Item item = stack.getItem();
		ItemStackRenderState state = ITEM_STATES.get(item);
		if (state == null) {
			state = new ItemStackRenderState();
			final Minecraft minecraft = Minecraft.getInstance();
			minecraft.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.NONE, level, minecraft.player, 0);
			if (ITEM_STATES.size() >= MAX_ITEM_STATES) {
				ITEM_STATES.clear();
			}
			ITEM_STATES.put(item, state);
		}
		if (state.isEmpty()) {
			VFXLog.warnOnce(LOGGER, "no-item-model:" + item, "Item-particle item '{}' has no baked item model; nothing is drawn", stack);
			return;
		}
		final int light = spec.brightness() >= 0 ? spec.brightness() : worldLight(level, lightPos);
		state.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
	}

	/** Packs the world light at {@code pos} into the renderer's packed-light int (block/sky). */
	private static int worldLight(final ClientLevel level, final BlockPos pos) {
		final int block = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
		final int sky = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos);
		return VFXBlockParticleSpec.packBrightness(block, sky);
	}

	private static void addParticle(final Bucket bucket, final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
		if (liveCount >= MAX_BLOCK_PARTICLES || bucket.particles.size() >= MAX_PARTICLES_PER_INSTANCE) {
			return;
		}
		if (!isRenderable(spec)) {
			return;
		}
		bucket.particles.add(new Particle(spec, position, velocity));
		liveCount++;
	}

	/**
	 * True when the spec can actually draw: an item spec (its model is resolved lazily at submit;
	 * an unmodelled item warns there), or a block whose baked block model is non-empty — otherwise
	 * the moving-block submit would silently draw nothing (see
	 * {@link VFXWorldOverlayRenderer#hasBlockModelGeometry}). Warns once per block so an unusable
	 * block is not a silent no-op. Cached per state.
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
			VFXLog.warnOnce(LOGGER, "no-model:" + block, "Block-particle block '{}' has no baked block model (its world shape is drawn by a block-entity renderer, e.g. a skull); moving-block particles cannot draw it. Use a block with a normal model.", block);
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

	/**
	 * A {@link MovingBlockRenderState} that can pin a packed light value: when
	 * {@code packedBrightness} is set the model renders at that light regardless of the world
	 * (the block-display brightness semantics); otherwise it falls back to the world light.
	 */
	private static final class ParticleBlockState extends MovingBlockRenderState {
		int packedBrightness = VFXBlockParticleSpec.NO_BRIGHTNESS;

		@Override
		public int getBrightness(final LightLayer layer, final BlockPos pos) {
			if (this.packedBrightness >= 0) {
				return layer == LightLayer.BLOCK
					? VFXBlockParticleSpec.blockLight(this.packedBrightness)
					: VFXBlockParticleSpec.skyLight(this.packedBrightness);
			}
			return this.getLightEngine().getLayerListener(layer).getLightValue(pos);
		}
	}
}

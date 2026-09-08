package dev.vfxweaver.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the world-space {@code block_tint} and {@code block_outline} effects into the level
 * frame, after the translucent terrain.
 *
 * <p>Both effects reuse the vanilla {@code core/position_color} shader pair with the
 * {@code POSITION_COLOR} format. The pipelines declare the vanilla {@code DynamicTransforms}
 * and {@code Projection} uniform buffers (exactly like {@code RenderPipelines.DEBUG_FILLED_BOX}),
 * so {@code ModelViewMat}/{@code ProjMat} are bound by the standard draw path. Vertices are
 * transformed on the CPU through a fresh {@link PoseStack} translated by {@code pos - camPos}
 * (block-local coordinates), mirroring how block entities are rendered.</p>
 *
 * <p>The boolean {@code through_blocks} parameter selects between two depth modes: visible
 * through other blocks ({@code ALWAYS_PASS}) or occluded by them ({@code LESS_THAN_OR_EQUAL}
 * against the terrain depth buffer). It defaults to {@code true} for {@code block_tint} and
 * {@code false} for {@code block_outline}.</p>
 *
 * <p>{@code block_outline} has two modes selected by the boolean {@code shell} parameter.
 * The default (0) builds "walls": every model quad is extruded outwards along its normal by
 * {@code width / 2}, always projecting outside the block silhouette. Mode 1 is the classic
 * scaled shell drawn back-face-only: the inflated model is emitted with reversed winding under
 * back-face culling, so only the far side is rasterised and the block's own depth clips the
 * shell interior, leaving a rim.</p>
 */
public final class VFXWorldOverlayRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/overlay");
	private static final RandomSource RAND = RandomSource.create(42L);

	/** The 6 faces of the unit cube: 4 corners (CCW from outside) + outward normal. */
	private static final float[][] CUBE_FACES = {
		{0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0, 0, -1, 0}, // down
		{0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0}, // up
		{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, -1}, // north (z=min)
		{0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1}, // south
		{0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, -1, 0, 0}, // west (x=min)
		{1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0}, // east
	};

	/**
	 * Every custom {@code core/position_color} pipeline registered by this class. Iris needs each
	 * one mapped to a shaderpack program explicitly (via {@code VfxIrisCompat}), otherwise its
	 * override lookup returns null and the effect silently disappears under shaders.
	 */
	private static final List<RenderPipeline> IRIS_PIPELINES = new ArrayList<>(9);

	private static RenderPipeline blockPipeline(final CompareOp depthOp, final boolean cull, final String locationSuffix) {
		RenderPipeline pipeline = RenderPipelines.register(
			RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/block_" + locationSuffix))
				.withVertexShader("core/position_color")
				.withFragmentShader("core/position_color")
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
				.withDepthStencilState(new DepthStencilState(depthOp, false))
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(cull)
				.build()
		);
		IRIS_PIPELINES.add(pipeline);
		return pipeline;
	}

	private static final RenderType TINT_VISIBLE = RenderType.create(
		"vfxweaver_block_tint_visible",
		RenderSetup.builder(blockPipeline(CompareOp.ALWAYS_PASS, false, "tint_visible")).createRenderSetup()
	);

	private static final RenderType TINT_OCCLUDED = RenderType.create(
		"vfxweaver_block_tint_occluded",
		RenderSetup.builder(blockPipeline(CompareOp.LESS_THAN_OR_EQUAL, false, "tint_occluded")).createRenderSetup()
	);

	/** Wall outline: extruded quads, no culling needed. */
	private static final RenderType OUTLINE_WALLS_VISIBLE = RenderType.create(
		"vfxweaver_block_outline_walls_visible",
		RenderSetup.builder(blockPipeline(CompareOp.ALWAYS_PASS, false, "outline_walls_visible")).createRenderSetup()
	);

	private static final RenderType OUTLINE_WALLS_OCCLUDED = RenderType.create(
		"vfxweaver_block_outline_walls_occluded",
		RenderSetup.builder(blockPipeline(CompareOp.LESS_THAN_OR_EQUAL, false, "outline_walls_occluded")).createRenderSetup()
	);

	/** Shell outline: back-face culling + reversed winding = far side only, clipped by the block. */
	private static final RenderType OUTLINE_SHELL_VISIBLE = RenderType.create(
		"vfxweaver_block_outline_shell_visible",
		RenderSetup.builder(blockPipeline(CompareOp.ALWAYS_PASS, true, "outline_shell_visible")).createRenderSetup()
	);

	private static final RenderType OUTLINE_SHELL_OCCLUDED = RenderType.create(
		"vfxweaver_block_outline_shell_occluded",
		RenderSetup.builder(blockPipeline(CompareOp.LESS_THAN_OR_EQUAL, true, "outline_shell_occluded")).createRenderSetup()
	);

	/**
	 * Additive "glow" pipeline shared by the world quad effects ({@code light_beam},
	 * {@code pulse_ring}, {@code scan_sweep}, {@code guide_line}).
	 */
	private static RenderPipeline glowPipeline(final CompareOp depthOp, final String suffix) {
		RenderPipeline pipeline = RenderPipelines.register(
			RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/glow_" + suffix))
				.withVertexShader("core/position_color")
				.withFragmentShader("core/position_color")
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
				.withDepthStencilState(new DepthStencilState(depthOp, false))
				.withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
				.withCull(false)
				.build()
		);
		IRIS_PIPELINES.add(pipeline);
		return pipeline;
	}

	private static final RenderType GLOW_VISIBLE = RenderType.create(
		"vfxweaver_world_glow_visible",
		RenderSetup.builder(glowPipeline(CompareOp.ALWAYS_PASS, "visible")).createRenderSetup()
	);

	private static final RenderType GLOW_OCCLUDED = RenderType.create(
		"vfxweaver_world_glow_occluded",
		RenderSetup.builder(glowPipeline(CompareOp.LESS_THAN_OR_EQUAL, "occluded")).createRenderSetup()
	);

	/**
	 * Writes depth only (colour write disabled). Used to stamp the target block's volume into a
	 * cleared depth buffer before a through-walls outline, so the outline passes other blocks'
	 * depth (which was cleared away) but is still clipped by its own target.
	 */
	private static RenderPipeline depthMaskPipeline() {
		RenderPipeline pipeline = RenderPipelines.register(
			RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/block_depth_mask"))
				.withVertexShader("core/position_color")
				.withFragmentShader("core/position_color")
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
				.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
				.withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
				.build()
		);
		IRIS_PIPELINES.add(pipeline);
		return pipeline;
	}

	private static final RenderType BLOCK_DEPTH_MASK = RenderType.create(
		"vfxweaver_block_depth_mask",
		RenderSetup.builder(depthMaskPipeline()).createRenderSetup()
	);

	/** Tint geometry is pushed outwards from the block centre by this fraction (coplanar fix). */
	private static final float TINT_OUTSET = 0.002F;

	private static @Nullable TextureTarget depthScratch;
	private static int depthScratchWidth = -1;
	private static int depthScratchHeight = -1;

	private static @Nullable TextureTarget ensureDepthScratch(final int width, final int height) {
		if (depthScratch == null || depthScratchWidth != width || depthScratchHeight != height) {
			if (depthScratch != null) {
				depthScratch.destroyBuffers();
			}
			depthScratch = new TextureTarget("vfxweaver depth scratch", width, height, true);
			depthScratchWidth = width;
			depthScratchHeight = height;
		}
		return depthScratch;
	}

	/** Frees lazily allocated GPU resources (depth scratch target) on client shutdown. */
	public static void freeGpuResources() {
		if (depthScratch != null) {
			depthScratch.destroyBuffers();
			depthScratch = null;
		}
	}

	/**
	 * The custom pipelines that need an Iris override mapping so they keep rendering under a
	 * shaderpack. Called once at client init, before any world rendering.
	 */
	public static List<RenderPipeline> pipelinesForIris() {
		return List.copyOf(IRIS_PIPELINES);
	}

	private VFXWorldOverlayRenderer() {
	}

	/** Per-running-instance particle emission bookkeeping: {@code [lastAgeTicks, spawnBudget]}. */
	private static final Map<Long, float[]> PARTICLE_BUDGETS = new HashMap<>();
	/** Unknown particle ids / shapes we already warned about (capped to avoid unbounded growth). */
	private static final Set<String> PARTICLE_WARNINGS = new HashSet<>();
	private static final int MAX_PARTICLE_RATE = 1024;
	private static final int MAX_PARTICLES_PER_FRAME = 256;

	/**
	 * Spawns vanilla particles for one {@code particles} effect this frame. Emission is
	 * budgeted in particle-seconds ({@code rate} × delta age) so the visual rate is independent
	 * of framerate, multiplied by the effect's fade weight (fading out stops emission).
	 *
	 * <p>Shapes sample the effect's position slots (static block centres or exact entity-anchor
	 * points). {@code line} uses the first two slots like {@code guide_line}. {@code dust}
	 * particles take their colour/size from the animatable {@code color_r/g/b}/{@code size}
	 * params; every other supported particle is a plain registry id.</p>
	 */
	private static void emitParticles(final Minecraft minecraft, final VFXActiveEffect effect, final ClientLevel level) {
		float rate = Mth.clamp(effect.getParam("rate", 40.0F), 0.0F, MAX_PARTICLE_RATE) * effect.getWeight();
		if (rate <= 0.0F) {
			return;
		}
		ParticleOptions options = resolveParticleOptions(effect);
		if (options == null) {
			return;
		}
		float radius = Mth.clamp(effect.getParam("radius", 2.0F), 0.05F, 32.0F);
		float height = Mth.clamp(effect.getParam("height", 3.0F), 0.5F, 32.0F);
		float turns = Mth.clamp(effect.getParam("turns", 2.0F), 0.25F, 16.0F);
		float spin = Mth.clamp(effect.getParam("spin", 0.0F), -8.0F, 8.0F);
		float speed = Mth.clamp(effect.getParam("speed", 0.0F), 0.0F, 8.0F);
		float velY = Mth.clamp(effect.getParam("vel_y", 0.0F), -4.0F, 4.0F);

		List<Vec3> anchors = effectPositions(effect, level);
		if (anchors.isEmpty()) {
			return;
		}

		ParticleEngine engine = minecraft.particleEngine;
		float[] budget = PARTICLE_BUDGETS.computeIfAbsent(effect.getInstanceId(), key -> new float[2]);
		float age = effect.getAge();
		float delta = Math.max(0.0F, age - budget[0]);
		budget[0] = age;
		budget[1] += rate * delta / 20.0F;
		int count = Math.min((int) budget[1], MAX_PARTICLES_PER_FRAME);
		if (count <= 0) {
			return;
		}
		budget[1] -= count;
		if (PARTICLE_BUDGETS.size() > 512) {
			PARTICLE_BUDGETS.clear();
		}

		String shape = effect.getShape() == null ? "sphere" : effect.getShape();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		float elapsed = effect.getElapsed() / 20.0F;
		for (int i = 0; i < count; i++) {
			// Jitter the sample time within the frame so high rates fill spirals evenly.
			Vec3 p = sampleShape(shape, anchors, radius, height, turns, elapsed + spin, random);
			if (p == null) {
				return;
			}
			double vx = 0.0;
			double vy = velY;
			double vz = 0.0;
			if (speed > 0.0F) {
				double th = random.nextDouble() * 6.2831853;
				double ph = Math.acos(2.0 * random.nextDouble() - 1.0);
				vx = Math.sin(ph) * Math.cos(th) * speed;
				vy += Math.cos(ph) * speed;
				vz = Math.sin(ph) * Math.sin(th) * speed;
			}
			Particle particle = engine.createParticle(options, p.x, p.y, p.z, vx, vy, vz);
			if (particle != null) {
				engine.add(particle);
			}
		}
	}

	/**
	 * Samples one random point of the emission shape relative to the anchor list. Returns
	 * {@code null} for unknown shapes (the caller stops emitting; a one-time warning is logged).
	 * {@code rotationSeconds} = elapsed seconds + {@code spin} — drives the helix revolution.
	 */
	private static @Nullable Vec3 sampleShape(final String shape, final List<Vec3> anchors, final float radius, final float height, final float turns, final float rotationSeconds, final ThreadLocalRandom random) {
		Vec3 origin = anchors.get(0);
		switch (shape) {
			case "point":
				return origin;
			case "sphere": {
				double th = random.nextDouble() * 6.2831853;
				double ph = Math.acos(2.0 * random.nextDouble() - 1.0);
				return origin.add(Math.sin(ph) * Math.cos(th) * radius, Math.cos(ph) * radius, Math.sin(ph) * Math.sin(th) * radius);
			}
			case "ring": {
				double angle = random.nextDouble() * 6.2831853;
				return origin.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
			}
			case "helix": {
				double u = random.nextDouble();
				double angle = u * turns * 6.2831853 + rotationSeconds * 6.2831853;
				return origin.add(Math.cos(angle) * radius, u * height, Math.sin(angle) * radius);
			}
			case "cube": {
				int axis = random.nextInt(3);
				double a = random.nextDouble(-radius, radius);
				double b = random.nextDouble(-radius, radius);
				double side = radius * (random.nextBoolean() ? 1.0 : -1.0);
				double x = axis == 0 ? side : a;
				double y = axis == 1 ? side : a;
				double z = axis == 2 ? side : b;
				return origin.add(x, y, z);
			}
			case "line": {
				Vec3 a = origin;
				Vec3 b = anchors.size() > 1 ? anchors.get(1) : origin.add(10.0, 0.0, 0.0);
				double u = random.nextDouble();
				return new Vec3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u);
			}
			default:
				if (PARTICLE_WARNINGS.add("shape:" + shape) && PARTICLE_WARNINGS.size() < 64) {
					LOGGER.warn("Unknown particle shape '{}'; use sphere/ring/helix/line/cube/point", shape);
				}
				return null;
		}
	}

	/**
	 * Resolves the effect's vanilla particle into {@link ParticleOptions}. {@code dust} is
	 * built from the animatable colour/size params; plain registry ids must be
	 * {@link SimpleParticleType}s (option-carrying types like {@code block}/{@code item} are
	 * rejected with a one-time warning).
	 */
	private static @Nullable ParticleOptions resolveParticleOptions(final VFXActiveEffect effect) {
		String id = effect.getParticleId();
		if (id == null || id.isBlank()) {
			id = "minecraft:end_rod";
		}
		if (id.equals("dust") || id.equals("minecraft:dust")) {
			int rgb = rgb(effect.getParam("color_r", 1.0F), effect.getParam("color_g", 1.0F), effect.getParam("color_b", 1.0F));
			float size = Mth.clamp(effect.getParam("size", 1.0F), 0.05F, 4.0F);
			return new DustParticleOptions(rgb, size);
		}
		Identifier pid = Identifier.tryParse(id);
		if (pid == null) {
			warnParticleOnce(id, effect);
			return null;
		}
		ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(pid);
		if (type instanceof SimpleParticleType simple) {
			return simple;
		}
		warnParticleOnce(id, effect);
		return null;
	}

	private static void warnParticleOnce(final String id, final VFXActiveEffect effect) {
		if (PARTICLE_WARNINGS.add("particle:" + id) && PARTICLE_WARNINGS.size() < 64) {
			LOGGER.warn("Unsupported particle id '{}' in effect '{}'; use simple vanilla particles or 'dust'", id, effect.getId());
		}
	}

	public static void register() {
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(VFXWorldOverlayRenderer::render);
	}

	private static void render(final LevelRenderContext context) {
		List<VFXActiveEffect> effects = VFXEffectManager.get().getActiveWorldEffects();
		if (effects.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		ClientLevel level = minecraft.level;
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (!camera.initialized) {
			return;
		}

		MultiBufferSource.BufferSource buffers = context.bufferSource();
		List<RenderType> drawn = new ArrayList<>(4);

		for (VFXActiveEffect effect : effects) {
			try {
				if (effect.getType() == VFXEffectType.BLOCK_TINT) {
					boolean through = effect.getParam("through_blocks", 1.0F) >= 0.5F;
					RenderType type = through ? TINT_VISIBLE : TINT_OCCLUDED;
					if (renderEffect(buffers, camera, effect, level, minecraft, type, 0.5F, 0.0F, false, TINT_OUTSET)) {
						drawn.add(type);
					}
				} else if (effect.getType() == VFXEffectType.LIGHT_BEAM) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderLightBeams(buffers, camera, effect, level, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.PULSE_RING) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderPulseRings(buffers, camera, effect, level, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.GUIDE_LINE) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderGuideLines(buffers, camera, effect, level, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.PARTICLES) {
					emitParticles(minecraft, effect, level);
				} else if (effect.getType() == VFXEffectType.BLOCK_OUTLINE) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					boolean shell = effect.getParam("shell", 0.0F) >= 0.5F;
					float width = Mth.clamp(effect.getParam("width", 0.05F), 0.0F, 1.0F);
					float amount = shell ? width : width * 0.5F;
					RenderType outlineType = shell ? OUTLINE_SHELL_OCCLUDED : OUTLINE_WALLS_OCCLUDED;
					if (through) {
						renderThroughOutline(buffers, camera, effect, level, minecraft, outlineType, amount, shell);
					} else if (renderEffect(buffers, camera, effect, level, minecraft, outlineType, 1.0F, amount, shell, 0.0F)) {
						drawn.add(outlineType);
					}
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to render world overlay '{}'", effect.getId(), e);
			}
		}

		try {
			for (RenderType type : drawn) {
				buffers.endBatch(type);
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to flush world overlay buffers", e);
		}
	}

	/**
	 * Draws one tint/outline effect: a fresh camera-relative {@link PoseStack} per slot and the
	 * looked-up block's baked model quads in block-local coordinates. The model is looked up at
	 * {@link BlockPos#containing(double, double, double)} of the slot vec, while the pose is
	 * translated to the exact slot position minus its baked X/Z block-centre offset: static
	 * slots keep their historical integer corner translate, anchored/moved slots track the
	 * exact point instead of snapping to a block (the tint cube ends up centred on the slot's
	 * X/Z). {@code amount > 0} with {@code shell = false} extrudes quads along their normals
	 * (wall outline); with {@code shell = true} it scales the whole model around the block
	 * centre (shell outline, reversed winding so back-face culling keeps only the far side).
	 */
	private static boolean renderEffect(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final ClientLevel level,
		final Minecraft minecraft,
		final RenderType renderType,
		final float defaultAlpha,
		final float amount,
		final boolean shell,
		final float outset
	) {
		float alpha = clamp01(effect.getParam("alpha", defaultAlpha)) * effect.getWeight();
		if (alpha <= 0.0F) {
			return false;
		}
		int color = argb(effect, alpha);
		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;

		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		for (Vec3 vec : effectPositions(effect, level)) {
			poseStack.pushPose();
			try {
				// Slot vecs carry the block-centre offset on X/Z while model quads are
				// corner-origin block-local: shifting back half a block keeps static slots on
				// their historical integer corner and centres anchored/moved slots on the exact
				// point (sub-block smooth instead of snapped to a block).
				poseStack.translate(vec.x - 0.5, vec.y, vec.z - 0.5);
				if (shell && amount > 0.0F) {
					float scale = 1.0F + amount;
					poseStack.translate(0.5F, 0.5F, 0.5F);
					poseStack.scale(scale, scale, scale);
					poseStack.translate(-0.5F, -0.5F, -0.5F);
				}
				PoseStack.Pose pose = poseStack.last();
				List<BakedQuad> quads = getModelQuads(minecraft, BlockPos.containing(vec.x, vec.y, vec.z));
				if (amount > 0.0F) {
					if (shell) {
						if (quads.isEmpty()) {
							emitCubeFill(buffer, pose, color, true, outset);
						} else {
							emitQuads(buffer, pose, quads, color, true, outset);
						}
					} else {
						if (quads.isEmpty()) {
							emitCubeWalls(buffer, pose, color, amount);
						} else {
							emitQuadWalls(buffer, pose, quads, color, amount);
						}
					}
				} else {
					if (quads.isEmpty()) {
						emitCubeFill(buffer, pose, color, false, outset);
					} else {
						emitQuads(buffer, pose, quads, color, false, outset);
					}
				}
			} finally {
				poseStack.popPose();
			}
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/**
	 * Renders one {@code light_beam}: a glowing vertical shaft descending onto each anchor, built
	 * from {@code layers} concentric cone shells (a bright tight core plus progressively wider,
	 * fainter shells). {@code top_scale} scales the TOP base of the cones: 1 = cylinder, 2 = cone
	 * whose top radius is 2x the bottom. {@code softness} increases the number of shells and fades
	 * their alpha (cubic), so higher values read as a soft/blurry column (many thin shells) instead
	 * of two hard tubes. {@code top_fade}/{@code bottom_fade} fade the alpha toward the top/bottom
	 * of the column.
	 */
	private static boolean renderLightBeams(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final ClientLevel level,
		final RenderType renderType
	) {
		float intensity = clamp01(effect.getParam("intensity", 1.0F)) * effect.getWeight();
		if (intensity <= 0.0F) {
			return false;
		}
		float radius = Mth.clamp(effect.getParam("radius", 1.5F), 0.1F, 16.0F);
		float height = Mth.clamp(effect.getParam("height", 48.0F), 1.0F, 256.0F);
		float topFade = Mth.clamp(effect.getParam("top_fade", 0.4F), 0.0F, 1.0F);
		float bottomFade = Mth.clamp(effect.getParam("bottom_fade", 0.0F), 0.0F, 1.0F);
		float topScale = Mth.clamp(effect.getParam("top_scale", 1.0F), 0.1F, 8.0F);
		float softness = Mth.clamp(effect.getParam("softness", 0.6F), 0.0F, 4.0F);
		int rgb = rgb(effect.getParam("red", 1.0F), effect.getParam("green", 0.95F), effect.getParam("blue", 0.75F));

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();

		// Concentric shells: the innermost is the bright core, each next shell sits a bit wider
		// and fainter. More softness = more, thinner shells (a smooth gradient instead of two
		// distinct tubes).
		int layers = Math.max(2, Math.round(2.0F + softness * 4.0F));
		for (Vec3 vec : effectPositions(effect, level)) {
			float cx = (float) vec.x;
			float cz = (float) vec.z;
			float y0 = (float) vec.y;
			float y1 = y0 + height;
			for (int i = 0; i < layers; i++) {
				float t = i / (float) (layers - 1);              // 0 = core .. 1 = outer edge
				float shellR = radius * (0.25F + 0.85F * t);     // core at 0.25r, outermost ~1.1r
				// Cubic alpha falloff: the outer shells fade much faster than the core, so the
				// column keeps a strong centre and dissolves at the edge.
				float shellA = intensity * (1.0F - t * t * t);
				emitConeShell(buffer, pose, cx, cz, y0, y1, shellR, shellR * topScale, 8, topFade, bottomFade, shellA, rgb);
			}
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/** Number of segments around a {@code pulse_ring}. */
	private static final int RING_SEGMENTS = 32;

	/**
	 * Renders one {@code pulse_ring}: a glowing ring with a radial band thickness. With
	 * {@code billboard}=1 the ring always faces the camera (a perfect circle from any angle);
	 * with {@code billboard}=0 it stays in a fixed plane defined by {@code rot_x/rot_y/rot_z}
	 * (degrees, rotation about the world axes).
	 */
	private static boolean renderPulseRings(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final ClientLevel level,
		final RenderType renderType
	) {
		float intensity = clamp01(effect.getParam("intensity", 1.0F)) * effect.getWeight();
		if (intensity <= 0.0F) {
			return false;
		}
		float radius = Mth.clamp(effect.getParam("radius", 6.0F), 0.0F, 64.0F);
		float thickness = Mth.clamp(effect.getParam("thickness", 0.5F), 0.05F, 4.0F);
		boolean billboard = effect.getParam("billboard", 1.0F) >= 0.5F;
		float rotX = (float) Math.toRadians(Mth.clamp(effect.getParam("rot_x", 0.0F), -360.0F, 360.0F));
		float rotY = (float) Math.toRadians(Mth.clamp(effect.getParam("rot_y", 0.0F), -360.0F, 360.0F));
		float rotZ = (float) Math.toRadians(Mth.clamp(effect.getParam("rot_z", 0.0F), -360.0F, 360.0F));
		int rgb = rgb(effect.getParam("red", 1.0F), effect.getParam("green", 0.35F), effect.getParam("blue", 0.1F));

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();
		float inner = Math.max(radius - thickness / 2.0F, 0.01F);
		float outer = radius + thickness / 2.0F;

		for (Vec3 vec : effectPositions(effect, level)) {
			float cx = (float) vec.x;
			float cz = (float) vec.z;
			// Rings sit at a slot's vertical centre; static slot vecs keep the block base on Y.
			float cy = (float) vec.y + 0.5F;

			// Two orthonormal axes spanning the ring plane: (rx,ry,rz) and (ux,uy,uz).
			float rx, ry, rz, ux, uy, uz;
			if (billboard) {
				// Camera-facing: build the basis from the VIEW DIRECTION (camera -> ring centre).
				float vx = (float) (cx - camera.pos.x);
				float vy = (float) (cy - camera.pos.y);
				float vz = (float) (cz - camera.pos.z);
				float vlen = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
				if (vlen < 1.0e-5F) {
					vlen = 1.0F;
				}
				vx /= vlen;
				vy /= vlen;
				vz /= vlen;
				// right = normalize(cross(viewDir, worldUp)); worldUp = (0,1,0).
				rx = -vz;
				rz = vx;
				float rl = (float) Math.sqrt(rx * rx + rz * rz);
				if (rl < 1.0e-5F) {
					rx = 1.0F;
					rz = 0.0F;
					rl = 1.0F;
				}
				rx /= rl;
				rz /= rl;
				ry = 0.0F;
				// up = cross(right, viewDir).
				ux = ry * vz - rz * vy;
				uy = rz * vx - rx * vz;
				uz = rx * vy - ry * vx;
			} else {
				// Fixed world axes rotated by rot_x/rot_y/rot_z. Start from the XZ plane
				// (right = +X, up = +Y) and rotate.
				rx = 1.0F;
				ry = 0.0F;
				rz = 0.0F;
				ux = 0.0F;
				uy = 1.0F;
				uz = 0.0F;
				float[] r = rotateXyz(rx, ry, rz, rotX, rotY, rotZ);
				rx = r[0];
				ry = r[1];
				rz = r[2];
				float[] u = rotateXyz(ux, uy, uz, rotX, rotY, rotZ);
				ux = u[0];
				uy = u[1];
				uz = u[2];
			}

			for (int i = 0; i < RING_SEGMENTS; i++) {
				float a0 = (float) (i * 6.2831853 / RING_SEGMENTS);
				float a1 = (float) ((i + 1) * 6.2831853 / RING_SEGMENTS);
				float co0 = (float) Math.cos(a0);
				float si0 = (float) Math.sin(a0);
				float co1 = (float) Math.cos(a1);
				float si1 = (float) Math.sin(a1);
				// Inner and outer vertices in the ring plane.
				float ix0 = cx + (co0 * inner) * rx + (si0 * inner) * ux;
				float iy0 = cy + (co0 * inner) * ry + (si0 * inner) * uy;
				float iz0 = cz + (co0 * inner) * rz + (si0 * inner) * uz;
				float ox0 = cx + (co0 * outer) * rx + (si0 * outer) * ux;
				float oy0 = cy + (co0 * outer) * ry + (si0 * outer) * uy;
				float oz0 = cz + (co0 * outer) * rz + (si0 * outer) * uz;
				float ix1 = cx + (co1 * inner) * rx + (si1 * inner) * ux;
				float iy1 = cy + (co1 * inner) * ry + (si1 * inner) * uy;
				float iz1 = cz + (co1 * inner) * rz + (si1 * inner) * uz;
				float ox1 = cx + (co1 * outer) * rx + (si1 * outer) * ux;
				float oy1 = cy + (co1 * outer) * ry + (si1 * outer) * uy;
				float oz1 = cz + (co1 * outer) * rz + (si1 * outer) * uz;
				glowVertex(buffer, pose, ix0, iy0, iz0, intensity, rgb);
				glowVertex(buffer, pose, ox0, oy0, oz0, intensity, rgb);
				glowVertex(buffer, pose, ox1, oy1, oz1, intensity, rgb);
				glowVertex(buffer, pose, ix1, iy1, iz1, intensity, rgb);
			}
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/** Rotates a vector by yaw (Y), then pitch (X), then roll (Z), in degrees. */
	private static float[] rotateXyz(final float x, final float y, final float z, final float rotX, final float rotY, final float rotZ) {
		float vx = x;
		float vy = y;
		float vz = z;
		// Yaw about Y.
		float cy = (float) Math.cos(rotY);
		float sy = (float) Math.sin(rotY);
		float tx = vx * cy + vz * sy;
		float tz = -vx * sy + vz * cy;
		vx = tx;
		vz = tz;
		// Pitch about X.
		float cx = (float) Math.cos(rotX);
		float sx = (float) Math.sin(rotX);
		float ty = vy * cx - vz * sx;
		tz = vy * sx + vz * cx;
		vy = ty;
		vz = tz;
		// Roll about Z.
		float cz = (float) Math.cos(rotZ);
		float sz = (float) Math.sin(rotZ);
		tx = vx * cz - vy * sz;
		ty = vx * sz + vy * cz;
		vx = tx;
		vy = ty;
		return new float[]{vx, vy, vz};
	}

	/**
	 * Renders one {@code guide_line}: a glowing dashed line along a parabola (apex +{@code arc})
	 * between the first two anchors, dashes crawling with {@code speed}.
	 */
	private static boolean renderGuideLines(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final ClientLevel level,
		final RenderType renderType
	) {
		float intensity = clamp01(effect.getParam("intensity", 1.0F)) * effect.getWeight();
		if (intensity <= 0.0F) {
			return false;
		}
		float width = Mth.clamp(effect.getParam("width", 0.15F), 0.02F, 1.0F);
		float dashLength = Mth.clamp(effect.getParam("dash_length", 0.6F), 0.1F, 8.0F);
		float gap = Mth.clamp(effect.getParam("gap", 0.6F), 0.0F, 8.0F);
		float speed = effect.getParam("speed", 2.0F);
		float arc = effect.getParam("arc", 1.5F);
		int rgb = rgb(effect.getParam("red", 0.25F), effect.getParam("green", 1.0F), effect.getParam("blue", 0.45F));

		List<Vec3> positions = effectPositions(effect, level);
		if (positions.isEmpty()) {
			// Entity-anchored endpoints not tracked this frame (or a legacy empty definition).
			return false;
		}
		Vec3 a = positions.get(0);
		Vec3 b = positions.size() > 1 ? positions.get(1) : a.add(10.0, 0.0, 0.0);
		float t = effect.getElapsed() / 20.0F;

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		int segments = 48;
		Vector3f[] pts = new Vector3f[segments + 1];
		for (int i = 0; i <= segments; i++) {
			float u = i / (float) segments;
			pts[i] = new Vector3f(
				(float) (a.x + (b.x - a.x) * u),
				// Lines sit at a slot's vertical centre; static slot vecs keep the block base on Y.
				(float) (a.y + 0.5 + (b.y - a.y) * u + arc * 4.0F * u * (1.0F - u)),
				(float) (a.z + (b.z - a.z) * u)
			);
		}

		// Ribbon: a camera-facing strip built from per-point side vectors
		// (cross(tangent, viewDir)), so the line reads as a solid path from any angle.
		Vector3f cam = new Vector3f((float) camera.pos.x, (float) camera.pos.y, (float) camera.pos.z);
		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();

		// Cumulative arc-length and per-point side vectors.
		float[] cum = new float[segments + 1];
		Vector3f[] side = new Vector3f[segments + 1];
		for (int i = 0; i <= segments; i++) {
			if (i > 0) {
				cum[i] = cum[i - 1] + pts[i].distance(pts[i - 1]);
			}
			Vector3f tan = new Vector3f(pts[Math.min(i + 1, segments)]).sub(pts[Math.max(i - 1, 0)]).normalize();
			Vector3f view = new Vector3f(cam).sub(pts[i]).normalize();
			Vector3f s = new Vector3f(tan).cross(view).normalize();
			if (s.lengthSquared() < 1.0e-6F) {
				s.set(1.0F, 0.0F, 0.0F);
			}
			side[i] = s;
		}
		float pathLen = Math.max(cum[segments], 1.0e-4F);
		float period = Math.max(dashLength + gap, 0.01F);
		float hw = width / 2.0F;
		for (int i = 0; i < segments; i++) {
			float a0 = dashAlpha(cum[i] / pathLen * 3.0F, dashLength, gap, t, speed);
			float a1 = dashAlpha(cum[i + 1] / pathLen * 3.0F, dashLength, gap, t, speed);
			float al0 = a0 * intensity;
			float al1 = a1 * intensity;
			if (al0 <= 0.001F && al1 <= 0.001F) {
				continue;
			}
			glowVertex(buffer, pose,
				pts[i].x - side[i].x * hw, pts[i].y, pts[i].z - side[i].z * hw, al0, rgb);
			glowVertex(buffer, pose,
				pts[i].x + side[i].x * hw, pts[i].y, pts[i].z + side[i].z * hw, al0, rgb);
			glowVertex(buffer, pose,
				pts[i + 1].x + side[i + 1].x * hw, pts[i + 1].y, pts[i + 1].z + side[i + 1].z * hw, al1, rgb);
			glowVertex(buffer, pose,
				pts[i + 1].x - side[i + 1].x * hw, pts[i + 1].y, pts[i + 1].z - side[i + 1].z * hw, al1, rgb);
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/** Dash alpha along the ribbon: 1 inside each dash, 0 in the gaps, ramping at the edges. */
	private static float dashAlpha(final float v, final float dashLength, final float gap, final float t, final float speed) {
		float period = Math.max(dashLength + gap, 0.01F);
		float phase = (float) ((t * speed) % period);
		float pos = (v + phase) % period;
		if (pos >= dashLength) {
			return 0.0F;
		}
		float ramp = Math.min(Math.min(pos, dashLength - pos) / (0.2F * dashLength + 0.02F), 1.0F);
		return ramp;
	}

	/** Vertical slices a faded beam shell is split into (see {@link #emitConeShell}). */
	private static final int FADE_SLICES = 32;

	/**
	 * Emits one vertical cylinder/cone shell: {@code segments} quads around the anchor circle,
	 * alpha fading toward the top ({@code topFade}) and bottom ({@code bottomFade}) of the shell.
	 *
	 * <p>When either fade is active the shell is split into {@link #FADE_SLICES} stacked slices,
	 * and every slice quad carries ONE uniform colour (the fade value at its midpoint). Shaderpacks
	 * that declare vertex colour {@code flat} (e.g. Complementary) take each triangle's colour from
	 * a single vertex, so any bottom/top colour difference inside a quad renders as two visibly
	 * different triangles; a uniform quad is immune to that. In vanilla the ramp becomes a subtle
	 * stepped gradient (1/{@link #FADE_SLICES} of the fade range per slice) instead of perfectly
	 * linear - imperceptible in an additive glow.</p>
	 */
	private static void emitConeShell(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final float cx,
		final float cz,
		final float y0,
		final float y1,
		final float radiusBot,
		final float radiusTop,
		final int segments,
		final float topFade,
		final float bottomFade,
		final float alpha,
		final int rgb
	) {
		int slices = (topFade > 0.0F || bottomFade > 0.0F) ? FADE_SLICES : 1;
		for (int i = 0; i < segments; i++) {
			float a0 = (float) (i * 6.2831853 / segments);
			float a1 = (float) ((i + 1) * 6.2831853 / segments);
			float cos0 = (float) Math.cos(a0);
			float sin0 = (float) Math.sin(a0);
			float cos1 = (float) Math.cos(a1);
			float sin1 = (float) Math.sin(a1);
			float uPrev = 0.0F;
			for (int j = 1; j <= slices; j++) {
				float u = j / (float) slices;
				int a = alpha255(fadeAlpha(alpha, topFade, bottomFade, (uPrev + u) * 0.5F));
				float r0 = radiusBot + (radiusTop - radiusBot) * uPrev;
				float r1 = radiusBot + (radiusTop - radiusBot) * u;
				float y0s = y0 + (y1 - y0) * uPrev;
				float y1s = y0 + (y1 - y0) * u;
				glowVertexA(buffer, pose, cx + cos0 * r0, y0s, cz + sin0 * r0, a, rgb);
				glowVertexA(buffer, pose, cx + cos1 * r0, y0s, cz + sin1 * r0, a, rgb);
				glowVertexA(buffer, pose, cx + cos1 * r1, y1s, cz + sin1 * r1, a, rgb);
				glowVertexA(buffer, pose, cx + cos0 * r1, y1s, cz + sin0 * r1, a, rgb);
				uPrev = u;
			}
		}
	}

	/** Shell alpha at normalised height {@code u} (0 = bottom, 1 = top) with both fades applied. */
	private static float fadeAlpha(final float alpha, final float topFade, final float bottomFade, final float u) {
		return alpha * (1.0F - topFade * u) * (1.0F - bottomFade * (1.0F - u));
	}

	/** Clamps/rounds a 0..1 alpha to a 0..255 byte. */
	private static int alpha255(final float a) {
		return Mth.clamp(Math.round(a * 255.0F), 0, 255);
	}

	private static void glowVertexA(final VertexConsumer buffer, final PoseStack.Pose pose, final float x, final float y, final float z, final int alpha, final int rgb) {
		// Additive pipeline blends src.rgb + dst.rgb and IGNORES alpha, so the RGB channels must
		// be premultiplied by the alpha byte for fading (top_fade/softness/intensity) to work.
		float a = Mth.clamp(alpha / 255.0F, 0.0F, 1.0F);
		int r = (int) (((rgb >> 16) & 0xFF) * a);
		int g = (int) (((rgb >> 8) & 0xFF) * a);
		int b = (int) ((rgb & 0xFF) * a);
		buffer.addVertex(pose, x, y, z).setColor(255 << 24 | r << 16 | g << 8 | b);
	}

	private static void glowVertex(final VertexConsumer buffer, final PoseStack.Pose pose, final float x, final float y, final float z, final float alpha, final int rgb) {
		float a = Mth.clamp(alpha, 0.0F, 1.0F);
		int r = (int) ((((rgb >> 16) & 0xFF) * a));
		int g = (int) ((((rgb >> 8) & 0xFF) * a));
		int b = (int) (((rgb & 0xFF) * a));
		buffer.addVertex(pose, x, y, z).setColor(255 << 24 | r << 16 | g << 8 | b);
	}

	/** Packs three 0..1 colour channels into an RGB int (alpha filled per-vertex). */
	private static int rgb(final float r, final float g, final float b) {
		return (Mth.clamp((int) (r * 255.0F), 0, 255) << 16)
			| (Mth.clamp((int) (g * 255.0F), 0, 255) << 8)
			| Mth.clamp((int) (b * 255.0F), 0, 255);
	}

	/**
	 * Through-walls block outline that still sits under its own target block. The world depth
	 * buffer is swapped for a cleared one, the target blocks are stamped back into it as a depth
	 * mask, and the outline is drawn occluded against that - so it passes other blocks (their
	 * depth was cleared) but never covers its own target. The original depth is restored after.
	 */
	private static void renderThroughOutline(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final ClientLevel level,
		final Minecraft minecraft,
		final RenderType outlineType,
		final float amount,
		final boolean shell
	) {
		RenderTarget main = minecraft.getMainRenderTarget();
		TextureTarget scratch = ensureDepthScratch(main.width, main.height);
		if (scratch == null) {
			return;
		}
		try {
			scratch.copyDepthFrom(main);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.clearDepthTexture(main.getDepthTexture(), 1.0);

			// Stamp the target blocks' volume into the fresh depth buffer.
			VertexConsumer maskBuffer = buffers.getBuffer(BLOCK_DEPTH_MASK);
			PoseStack poseStack = new PoseStack();
			poseStack.pushPose();
			poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
			for (Vec3 vec : effectPositions(effect, level)) {
				poseStack.pushPose();
				try {
					// Same translate as renderEffect so the depth mask matches the outline volume.
					poseStack.translate(vec.x - 0.5, vec.y, vec.z - 0.5);
					emitCubeFill(maskBuffer, poseStack.last(), 0, false, 0.0F);
				} finally {
					poseStack.popPose();
				}
			}
			poseStack.popPose();
			buffers.endBatch(BLOCK_DEPTH_MASK);

			// Outline now only hides behind its own target's depth.
			if (renderEffect(buffers, camera, effect, level, minecraft, outlineType, 1.0F, amount, shell, 0.0F)) {
				buffers.endBatch(outlineType);
			}
		} finally {
			main.copyDepthFrom(scratch);
		}
	}

	/** Static definition slots are block-anchored: block-centre X/Z, block base Y. */
	private static Vec3 centeredSlot(final BlockPos pos) {
		return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	/**
	 * Resolves the world-space draw positions of one effect for this frame, in slot order.
	 *
	 * <p>Slot conversion semantics:</p>
	 * <ul>
	 *   <li><b>Static definition slots</b> are block-anchored: the historical block-centre
	 *       offset is baked in on X/Z ({@code x + 0.5, y, z + 0.5}) so static visuals stay
	 *       identical; Y keeps the block's base line.</li>
	 *   <li><b>Entity-anchored slots</b> are exact points: the tracked entity's feet position
	 *       plus the anchor offset, with no flooring or centring, so overlays follow entities
	 *       at sub-block precision. If any anchor's entity is untracked this frame the result
	 *       is empty and callers skip the whole effect.</li>
	 *   <li><b>Legacy {@code pos_x/pos_y/pos_z} fallback</b> uses the static-slot convention
	 *       (block-centre X/Z).</li>
	 *   <li><b>Move positions</b> ({@link VFXActiveEffect#getMovePosition()}) are exact points,
	 *       returned as-is with no offset. They win over every other slot source, mirroring the
	 *       play-time payload position override.</li>
	 * </ul>
	 */
	private static List<Vec3> effectPositions(final VFXActiveEffect effect, final ClientLevel level) {
		// A runtime move re-anchors the whole effect; nothing else is consulted.
		Vec3 moved = effect.getMovePosition();
		if (moved != null) {
			return List.of(moved);
		}
		List<BlockPos> list = effect.getPositions();
		List<VFXActiveEffect.ResolvedAnchor> anchors = effect.getAnchors();
		if (anchors.isEmpty()) {
			if (!list.isEmpty()) {
				List<Vec3> centered = new ArrayList<>(list.size());
				for (BlockPos pos : list) {
					centered.add(centeredSlot(pos));
				}
				return List.copyOf(centered);
			}
			return List.of(new Vec3(effect.getParam("pos_x", 0.0F) + 0.5, effect.getParam("pos_y", 0.0F), effect.getParam("pos_z", 0.0F) + 0.5));
		}
		if (list.isEmpty()) {
			return List.of();
		}
		// Entity-anchored slots: substitute the tracked entity's exact current position
		// (+ offset) for the placeholder block. If any anchor's entity is untracked this frame
		// (dead, out of range, not yet spawned) the effect is skipped entirely — a half-tracked
		// effect would render e.g. a guide line with one endpoint stuck at the placeholder
		// origin.
		List<Vec3> resolved = new ArrayList<>(list.size());
		for (BlockPos pos : list) {
			resolved.add(centeredSlot(pos));
		}
		for (VFXActiveEffect.ResolvedAnchor anchor : anchors) {
			if (anchor.slot() >= resolved.size()) {
				return List.of();
			}
			Entity entity = level.getEntity(anchor.uuid());
			if (entity == null) {
				return List.of();
			}
			resolved.set(anchor.slot(), entity.position().add(anchor.offset()));
		}
		return List.copyOf(resolved);
	}

	private static List<BakedQuad> getModelQuads(final Minecraft minecraft, final BlockPos pos) {
		try {
			var state = minecraft.level.getBlockState(pos);
			List<BlockStateModelPart> parts = new ArrayList<>();
			minecraft.getModelManager().getBlockStateModelSet().get(state).collectParts(RAND, parts);
			List<BakedQuad> quads = new ArrayList<>();
			for (BlockStateModelPart part : parts) {
				List<BakedQuad> own = part.getQuads(null);
				if (own != null && !own.isEmpty()) {
					quads.addAll(own);
				}
				for (Direction direction : Direction.values()) {
					List<BakedQuad> sided = part.getQuads(direction);
					if (sided != null && !sided.isEmpty()) {
						quads.addAll(sided);
					}
				}
			}
			return quads;
		} catch (Exception e) {
			LOGGER.debug("Failed to collect model quads for block overlay at {}", pos, e);
			return List.of();
		}
	}

	private static void emitQuads(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final List<BakedQuad> quads,
		final int color,
		final boolean reverse,
		final float outset
	) {
		for (BakedQuad quad : quads) {
			if (reverse) {
				for (int i = 3; i >= 0; i--) {
					var p = outset(quad.position(i), outset);
					buffer.addVertex(pose, p.x(), p.y(), p.z()).setColor(color);
				}
			} else {
				for (int i = 0; i < 4; i++) {
					var p = outset(quad.position(i), outset);
					buffer.addVertex(pose, p.x(), p.y(), p.z()).setColor(color);
				}
			}
		}
	}

	/** Pushes a block-local vertex outwards from the block centre (coplanar depth fix). */
	private static Vector3fc outset(final Vector3fc v, final float outset) {
		if (outset <= 0.0F) {
			return v;
		}
		return new Vector3f(v).sub(0.5F, 0.5F, 0.5F).mul(1.0F + outset).add(0.5F, 0.5F, 0.5F);
	}

	/**
	 * Outline walls for model quads: every edge of every quad is extruded outwards along the
	 * quad normal. Walls only ever project outside the block silhouette, so the block itself is
	 * never covered by its own outline.
	 */
	private static void emitQuadWalls(final VertexConsumer buffer, final PoseStack.Pose pose, final List<BakedQuad> quads, final int color, final float extrude) {
		Vector3f normal = new Vector3f();
		Vector3f e1 = new Vector3f();
		Vector3f e2 = new Vector3f();
		Vector3f a = new Vector3f();
		Vector3f b = new Vector3f();
		for (BakedQuad quad : quads) {
			quad.position(1).sub(quad.position(0), e1);
			quad.position(3).sub(quad.position(0), e2);
			e1.cross(e2, normal);
			if (normal.lengthSquared() < 1.0e-8F) {
				Direction dir = quad.direction();
				if (dir == null) {
					continue;
				}
				normal.set(dir.getUnitVec3f());
			} else {
				normal.normalize();
			}
			for (int i = 0; i < 4; i++) {
				Vector3fc p0 = quad.position(i);
				Vector3fc p1 = quad.position((i + 1) & 3);
				a.set(p0).add(normal.x * extrude, normal.y * extrude, normal.z * extrude);
				b.set(p1).add(normal.x * extrude, normal.y * extrude, normal.z * extrude);
				buffer.addVertex(pose, p0.x(), p0.y(), p0.z()).setColor(color);
				buffer.addVertex(pose, p1.x(), p1.y(), p1.z()).setColor(color);
				buffer.addVertex(pose, b.x(), b.y(), b.z()).setColor(color);
				buffer.addVertex(pose, a.x(), a.y(), a.z()).setColor(color);
			}
		}
	}

	private static void emitCubeFill(final VertexConsumer buffer, final PoseStack.Pose pose, final int color, final boolean reverse, final float outset) {
		for (float[] face : CUBE_FACES) {
			if (reverse) {
				for (int i = 3; i >= 0; i--) {
					buffer.addVertex(pose, outset(face[i * 3], outset), outset(face[i * 3 + 1], outset), outset(face[i * 3 + 2], outset)).setColor(color);
				}
			} else {
				for (int i = 0; i < 4; i++) {
					buffer.addVertex(pose, outset(face[i * 3], outset), outset(face[i * 3 + 1], outset), outset(face[i * 3 + 2], outset)).setColor(color);
				}
			}
		}
	}

	private static float outset(final float c, final float f) {
		return 0.5F + (c - 0.5F) * (1.0F + f);
	}

	private static void emitCubeWalls(final VertexConsumer buffer, final PoseStack.Pose pose, final int color, final float extrude) {
		for (float[] face : CUBE_FACES) {
			float nx = face[12] * extrude;
			float ny = face[13] * extrude;
			float nz = face[14] * extrude;
			for (int i = 0; i < 4; i++) {
				int j = (i + 1) & 3;
				buffer.addVertex(pose, face[i * 3], face[i * 3 + 1], face[i * 3 + 2]).setColor(color);
				buffer.addVertex(pose, face[j * 3], face[j * 3 + 1], face[j * 3 + 2]).setColor(color);
				buffer.addVertex(pose, face[j * 3] + nx, face[j * 3 + 1] + ny, face[j * 3 + 2] + nz).setColor(color);
				buffer.addVertex(pose, face[i * 3] + nx, face[i * 3 + 1] + ny, face[i * 3 + 2] + nz).setColor(color);
			}
		}
	}

	private static int argb(final VFXActiveEffect effect, final float alpha) {
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		int r = Mth.clamp((int) (clamp01(effect.getParam("color_r", 1.0F)) * 255.0F), 0, 255);
		int g = Mth.clamp((int) (clamp01(effect.getParam("color_g", 1.0F)) * 255.0F), 0, 255);
		int b = Mth.clamp((int) (clamp01(effect.getParam("color_b", 1.0F)) * 255.0F), 0, 255);
		return a << 24 | r << 16 | g << 8 | b;
	}

	private static float clamp01(final float value) {
		return Mth.clamp(value, 0.0F, 1.0F);
	}
}

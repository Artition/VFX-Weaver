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
import dev.vfxweaver.client.noise.VFXNoise;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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

	private static RenderPipeline blockPipeline(final CompareOp depthOp, final boolean cull, final String locationSuffix) {
		return RenderPipelines.register(
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

	/** Displaced echo variants: _VISIBLE = ALWAYS_PASS (shows through terrain), _OCCLUDED = LEQUAL. */
	private static final RenderType DISPLACE_VISIBLE = RenderType.create(
		"vfxweaver_block_displace_visible",
		RenderSetup.builder(blockPipeline(CompareOp.ALWAYS_PASS, false, "displace_visible")).createRenderSetup()
	);

	private static final RenderType DISPLACE_OCCLUDED = RenderType.create(
		"vfxweaver_block_displace_occluded",
		RenderSetup.builder(blockPipeline(CompareOp.LESS_THAN_OR_EQUAL, false, "displace_occluded")).createRenderSetup()
	);

	/**
	 * Additive "glow" pipeline shared by the world quad effects ({@code light_beam},
	 * {@code pulse_ring}, {@code scan_sweep}, {@code guide_line}).
	 */
	private static RenderPipeline glowPipeline(final CompareOp depthOp, final String suffix) {
		return RenderPipelines.register(
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
	private static final RenderType BLOCK_DEPTH_MASK = RenderType.create(
		"vfxweaver_block_depth_mask",
		RenderSetup.builder(
			RenderPipelines.register(
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
			)
		).createRenderSetup()
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

	private VFXWorldOverlayRenderer() {
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
					if (renderEffect(buffers, camera, effect, minecraft, type, 0.5F, 0.0F, false, TINT_OUTSET)) {
						drawn.add(type);
					}
				} else if (effect.getType() == VFXEffectType.BLOCK_DISPLACE) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					RenderType displaceType = through ? DISPLACE_VISIBLE : DISPLACE_OCCLUDED;
					if (renderDisplaced(buffers, camera, effect, minecraft, displaceType)) {
						drawn.add(displaceType);
					}
				} else if (effect.getType() == VFXEffectType.LIGHT_BEAM) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderLightBeams(buffers, camera, effect, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.PULSE_RING) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderPulseRings(buffers, camera, effect, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.SCAN_SWEEP) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderScanSweeps(buffers, camera, effect, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.GUIDE_LINE) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					if (renderGuideLines(buffers, camera, effect, through ? GLOW_VISIBLE : GLOW_OCCLUDED)) {
						drawn.add(through ? GLOW_VISIBLE : GLOW_OCCLUDED);
					}
				} else if (effect.getType() == VFXEffectType.BLOCK_OUTLINE) {
					boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
					boolean shell = effect.getParam("shell", 0.0F) >= 0.5F;
					float width = Mth.clamp(effect.getParam("width", 0.05F), 0.0F, 1.0F);
					float amount = shell ? width : width * 0.5F;
					RenderType outlineType = shell ? OUTLINE_SHELL_OCCLUDED : OUTLINE_WALLS_OCCLUDED;
					if (through) {
						renderThroughOutline(buffers, camera, effect, minecraft, outlineType, amount, shell);
					} else if (renderEffect(buffers, camera, effect, minecraft, outlineType, 1.0F, amount, shell, 0.0F)) {
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
	 * Draws one tint/outline effect: a fresh camera-relative {@link PoseStack} per block and the
	 * block's baked model quads in block-local coordinates. {@code amount > 0} with
	 * {@code shell = false} extrudes quads along their normals (wall outline); with
	 * {@code shell = true} it scales the whole model around the block centre (shell outline,
	 * reversed winding so back-face culling keeps only the far side).
	 */
	private static boolean renderEffect(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
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
		for (BlockPos pos : effectPositions(effect)) {
			poseStack.pushPose();
			try {
				poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
				if (shell && amount > 0.0F) {
					float scale = 1.0F + amount;
					poseStack.translate(0.5F, 0.5F, 0.5F);
					poseStack.scale(scale, scale, scale);
					poseStack.translate(-0.5F, -0.5F, -0.5F);
				}
				PoseStack.Pose pose = poseStack.last();
				List<BakedQuad> quads = getModelQuads(minecraft, pos);
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
	 * Draws one {@code block_displace} effect: the baked model quads of every targeted block are
	 * re-emitted in block-local space with a per-vertex hash displacement (a corrupted echo on
	 * top of the intact block). Mirrors {@link #renderEffect} but applies no outset/extrusion.
	 */
	private static boolean renderDisplaced(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final Minecraft minecraft,
		final RenderType renderType
	) {
		float alpha = clamp01(effect.getParam("alpha", 1.0F)) * effect.getWeight();
		if (alpha <= 0.0F) {
			return false;
		}
		float amplitude = clamp01(effect.getParam("amplitude", 0.1F)) * effect.getWeight();
		if (amplitude <= 0.0F) {
			return false;
		}
		float scale = Math.max(effect.getParam("scale", 4.0F), 0.5F);
		float seed = effect.getParam("seed", 0.0F);
		int color = argb(effect, alpha);
		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;

		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		for (BlockPos pos : effectPositions(effect)) {
			poseStack.pushPose();
			try {
				poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
				PoseStack.Pose pose = poseStack.last();
				List<BakedQuad> quads = getModelQuads(minecraft, pos);
				if (quads.isEmpty()) {
					emitCubeFillDisplaced(buffer, pose, color, pos, amplitude, scale, seed);
				} else {
					emitQuadsDisplaced(buffer, pose, quads, color, pos, amplitude, scale, seed);
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
	 * Re-emits model quads with a per-vertex hash displacement. The hash inputs are the
	 * world-space vertex coordinates ({@code pos + local}), wrapped for float precision, so
	 * neighbouring blocks diverge instead of repeating the same 1-block pattern.
	 */
	private static void emitQuadsDisplaced(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final List<BakedQuad> quads,
		final int color,
		final BlockPos pos,
		final float amplitude,
		final float scale,
		final float seed
	) {
		for (BakedQuad quad : quads) {
			for (int i = 0; i < 4; i++) {
				Vector3fc p = quad.position(i);
				float wx = VFXNoise.wrap((pos.getX() + p.x()) * scale);
				float wy = VFXNoise.wrap((pos.getY() + p.y()) * scale);
				float wz = VFXNoise.wrap((pos.getZ() + p.z()) * scale);
				float ox = VFXNoise.vhash(wx, wy, wz, seed) * amplitude;
				float oy = VFXNoise.vhash(wy, wz, wx, seed + 3.14F) * amplitude;
				float oz = VFXNoise.vhash(wz, wx, wy, seed + 6.28F) * amplitude;
				buffer.addVertex(pose, p.x() + ox, p.y() + oy, p.z() + oz).setColor(color);
			}
		}
	}

	/** Fallback displaced emitter for blocks whose model has no quads: a full cube, same hash. */
	private static void emitCubeFillDisplaced(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final int color,
		final BlockPos pos,
		final float amplitude,
		final float scale,
		final float seed
	) {
		for (float[] face : CUBE_FACES) {
			for (int i = 0; i < 4; i++) {
				float px = face[i * 3];
				float py = face[i * 3 + 1];
				float pz = face[i * 3 + 2];
				float wx = VFXNoise.wrap((pos.getX() + px) * scale);
				float wy = VFXNoise.wrap((pos.getY() + py) * scale);
				float wz = VFXNoise.wrap((pos.getZ() + pz) * scale);
				float ox = VFXNoise.vhash(wx, wy, wz, seed) * amplitude;
				float oy = VFXNoise.vhash(wy, wz, wx, seed + 3.14F) * amplitude;
				float oz = VFXNoise.vhash(wz, wx, wy, seed + 6.28F) * amplitude;
				buffer.addVertex(pose, px + ox, py + oy, pz + oz).setColor(color);
			}
		}
	}

	/**
	 * Renders one {@code light_beam}: a glowing vertical cylinder descending onto each anchor,
	 * built from two cylinder shells (a bright core at {@code 0.35 * radius} plus a translucent
	 * halo at {@code radius}) so the silhouette and brightness are identical from every azimuth,
	 * alpha fading toward the top with an optional slow sway of the top ring.
	 */
	private static boolean renderLightBeams(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final RenderType renderType
	) {
		float intensity = clamp01(effect.getParam("intensity", 1.0F)) * effect.getWeight();
		if (intensity <= 0.0F) {
			return false;
		}
		float radius = Mth.clamp(effect.getParam("radius", 1.5F), 0.1F, 16.0F);
		float height = Mth.clamp(effect.getParam("height", 48.0F), 1.0F, 256.0F);
		float topFade = Mth.clamp(effect.getParam("top_fade", 0.4F), 0.0F, 1.0F);
		float sway = effect.getParam("sway", 0.0F);
		float swaySpeed = effect.getParam("sway_speed", 0.4F);
		int rgb = rgb(effect.getParam("red", 1.0F), effect.getParam("green", 0.95F), effect.getParam("blue", 0.75F));
		float t = effect.getElapsed() / 20.0F;

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();
		for (BlockPos pos : effectPositions(effect)) {
			float cx = pos.getX() + 0.5F;
			float cz = pos.getZ() + 0.5F;
			float y0 = pos.getY();
			float y1 = y0 + height;
			// Core shell is bright and tight, halo is wider and translucent: together (additive)
			// they read as a smooth radial falloff from every angle.
			emitCylinderShell(buffer, pose, cx, cz, y0, y1, radius * 0.35F, 8, topFade, sway, swaySpeed, t, intensity, rgb);
			emitCylinderShell(buffer, pose, cx, cz, y0, y1, radius, 12, topFade, sway, swaySpeed, t, intensity * 0.4F, rgb);
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/** Number of segments around a {@code pulse_ring}. */
	private static final int RING_SEGMENTS = 24;

	/**
	 * Renders one {@code pulse_ring}: a glowing annulus around each anchor, each segment
	 * billboarded toward the camera (in the XZ plane) so the ring is visible edge-on from any
	 * angle, alpha fading at the band edges. {@code tilt} pitches the ring plane (3D torus-like).
	 */
	private static boolean renderPulseRings(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final RenderType renderType
	) {
		float intensity = clamp01(effect.getParam("intensity", 1.0F)) * effect.getWeight();
		if (intensity <= 0.0F) {
			return false;
		}
		float radius = Mth.clamp(effect.getParam("radius", 6.0F), 0.0F, 64.0F);
		float thickness = Mth.clamp(effect.getParam("thickness", 0.5F), 0.05F, 4.0F);
		float tilt = (float) Math.toRadians(Mth.clamp(effect.getParam("tilt", 0.0F), -90.0F, 90.0F));
		int rgb = rgb(effect.getParam("red", 1.0F), effect.getParam("green", 0.35F), effect.getParam("blue", 0.1F));

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();
		float sinT = (float) Math.sin(tilt);
		float hw = thickness / 2.0F;
		// Billboard axis: horizontal, pointing at the camera.
		float toCamX = (float) -camera.pos.x;
		float toCamZ = (float) -camera.pos.z;
		float camLen = (float) Math.sqrt(toCamX * toCamX + toCamZ * toCamZ);
		float ux = camLen > 1.0e-5F ? toCamX / camLen : 1.0F;
		float uz = camLen > 1.0e-5F ? toCamZ / camLen : 0.0F;
		for (BlockPos pos : effectPositions(effect)) {
			float cx = pos.getX() + 0.5F;
			float cy = pos.getY() + 0.5F;
			float cz = pos.getZ() + 0.5F;
			for (int i = 0; i < RING_SEGMENTS; i++) {
				float a0 = (float) (i * 6.2831853 / RING_SEGMENTS);
				float a1 = (float) ((i + 1) * 6.2831853 / RING_SEGMENTS);
				// Ring point in the tilted plane (Y axis tilts into the ring normal).
				float baseX = cx + (float) Math.cos(a0) * radius;
				float baseZ = cz + (float) Math.sin(a0) * radius;
				float baseY = cy + (float) (Math.sin(a0) * radius) * sinT;
				float nX = cx + (float) Math.cos(a1) * radius;
				float nZ = cz + (float) Math.sin(a1) * radius;
				float nY = cy + (float) (Math.sin(a1) * radius) * sinT;
				float alpha0 = intensity;
				float alpha1 = intensity;
				// Each ring point is extruded along the camera axis by the band width -> a
				// billboarded annulus visible even edge-on.
				glowVertex(buffer, pose, baseX - ux * hw, baseY, baseZ - uz * hw, alpha0, rgb);
				glowVertex(buffer, pose, baseX + ux * hw, baseY, baseZ + uz * hw, alpha0, rgb);
				glowVertex(buffer, pose, nX + ux * hw, nY, nZ + uz * hw, alpha1, rgb);
				glowVertex(buffer, pose, nX - ux * hw, nY, nZ - uz * hw, alpha1, rgb);
			}
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/**
	 * Renders one {@code scan_sweep}: a glowing band swept across the TARGET BLOCK's own model
	 * quads (like block_tint) with an animated front band and an exponential trail behind it,
	 * instead of a giant region-wide sheet.
	 */
	private static boolean renderScanSweeps(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
		final RenderType renderType
	) {
		float intensity = clamp01(effect.getParam("intensity", 1.0F)) * effect.getWeight();
		if (intensity <= 0.0F) {
			return false;
		}
		int axis = Mth.clamp((int) effect.getParam("axis", 1.0F), 0, 2);
		float progress = Mth.clamp(effect.getParam("progress", 0.5F), 0.0F, 1.0F);
		float band = Math.max(effect.getParam("band", 0.4F), 0.05F);
		float trail = Mth.clamp(effect.getParam("trail", 0.25F), 0.0F, 1.0F);
		int resolution = Mth.clamp((int) effect.getParam("resolution", 4.0F), 1, 8);
		int rgb = rgb(effect.getParam("red", 0.3F), effect.getParam("green", 1.0F), effect.getParam("blue", 0.9F));

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();
		Minecraft mc = Minecraft.getInstance();
		for (BlockPos pos : effectPositions(effect)) {
			List<BakedQuad> quads = getModelQuads(mc, pos);
			if (quads.isEmpty()) {
				continue;
			}
			for (BakedQuad quad : quads) {
				scanQuad(buffer, pose, pos, quad, axis, resolution, progress, band, trail, intensity, rgb);
			}
			drew = true;
		}
		poseStack.popPose();
		return drew;
	}

	/**
	 * Subdivides a target-block quad along the sweep axis into {@code res} slices and emits them
	 * with an alpha that follows the animated progress: a bright band at the front plus an
	 * exponential trail behind it.
	 */
	private static void scanQuad(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final BlockPos pos,
		final BakedQuad quad,
		final int axis,
		final int res,
		final float progress,
		final float band,
		final float trail,
		final float intensity,
		final int rgb
	) {
		Vector3f c0 = new Vector3f(quad.position(0));
		Vector3f c1 = new Vector3f(quad.position(1));
		Vector3f c2 = new Vector3f(quad.position(2));
		Vector3f c3 = new Vector3f(quad.position(3));
		for (int k = 0; k < res; k++) {
			float v0 = k / (float) res;
			float v1 = (k + 1) / (float) res;
			for (int corner = 0; corner < 4; corner++) {
				float u = (corner == 1 || corner == 2) ? 1.0F : 0.0F;
				float vv = (corner >= 2) ? v1 : v0;
				float lx = Mth.lerp(vv, Mth.lerp(u, c0.x(), c1.x()), Mth.lerp(u, c3.x(), c2.x())) + pos.getX();
				float ly = Mth.lerp(vv, Mth.lerp(u, c0.y(), c1.y()), Mth.lerp(u, c3.y(), c2.y())) + pos.getY();
				float lz = Mth.lerp(vv, Mth.lerp(u, c0.z(), c1.z()), Mth.lerp(u, c3.z(), c2.z())) + pos.getZ();
				float a = scanAlpha(progress, axisCoord(axis, lx, ly, lz, pos), band, trail) * intensity;
				glowVertex(buffer, pose, lx, ly, lz, a, rgb);
			}
		}
	}

	/** Returns the vertex position along the sweep axis (block-local 0..1). */
	private static float axisCoord(final int axis, final float x, final float y, final float z, final BlockPos pos) {
		return switch (axis) {
			case 0 -> Mth.lerp(0.0F, 1.0F, x - pos.getX());
			case 2 -> Mth.lerp(0.0F, 1.0F, z - pos.getZ());
			default -> Mth.lerp(0.0F, 1.0F, y - pos.getY());
		};
	}

	/** 1 at the scan front (u = progress), fading exponentially behind it. */
	private static float scanAlpha(final float progress, final float a, final float band, final float trail) {
		float d = progress - a;
		float front = Math.max(0.0F, 1.0F - Math.abs(d) / Math.max(band, 0.02F));
		float tail = d > 0.0F ? (float) Math.exp(-d / Math.max(trail, 0.02F)) : 0.0F;
		return Math.min(front + tail * 0.6F, 1.0F);
	}

	/**
	 * Renders one {@code guide_line}: a glowing dashed line along a parabola (apex +{@code arc})
	 * between the first two anchors, dashes crawling with {@code speed}.
	 */
	private static boolean renderGuideLines(
		final MultiBufferSource.BufferSource buffers,
		final CameraRenderState camera,
		final VFXActiveEffect effect,
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

		List<BlockPos> positions = effectPositions(effect);
		BlockPos a = positions.get(0);
		BlockPos b = positions.size() > 1 ? positions.get(1) : a.offset(10, 0, 0);
		float t = effect.getElapsed() / 20.0F;

		VertexConsumer buffer = buffers.getBuffer(renderType);
		boolean drew = false;
		int segments = 48;
		Vector3f[] pts = new Vector3f[segments + 1];
		for (int i = 0; i <= segments; i++) {
			float u = i / (float) segments;
			pts[i] = new Vector3f(
				a.getX() + 0.5F + (b.getX() - a.getX()) * u,
				a.getY() + 0.5F + (b.getY() - a.getY()) * u + arc * 4.0F * u * (1.0F - u),
				a.getZ() + 0.5F + (b.getZ() - a.getZ()) * u
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

	/**
	 * Emits one vertical cylinder shell: {@code segments} quads around the anchor circle, alpha
	 * fading toward the top and an optional sinusoidal sway of the top ring. The outer (halo)
	 * shell uses the same structure, so the beam reads as a cylinder from every azimuth.
	 */
	private static void emitCylinderShell(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final float cx,
		final float cz,
		final float y0,
		final float y1,
		final float radius,
		final int segments,
		final float topFade,
		final float sway,
		final float swaySpeed,
		final float t,
		final float alpha,
		final int rgb
	) {
		int aBot = alpha255(alpha);
		int aTop = alpha255(alpha * (1.0F - topFade));
		for (int i = 0; i < segments; i++) {
			float a0 = (float) (i * 6.2831853 / segments);
			float a1 = (float) ((i + 1) * 6.2831853 / segments);
			float sw0 = (float) Math.sin(t * swaySpeed + a0) * sway;
			float sw1 = (float) Math.sin(t * swaySpeed + a1) * sway;
			float x00 = cx + (float) Math.cos(a0) * radius;
			float z00 = cz + (float) Math.sin(a0) * radius;
			float x01 = cx + (float) Math.cos(a1) * radius;
			float z01 = cz + (float) Math.sin(a1) * radius;
			glowVertexA(buffer, pose, x00, y0, z00, aBot, rgb);
			glowVertexA(buffer, pose, x01, y0, z01, aBot, rgb);
			glowVertexA(buffer, pose, x01 + sw1, y1, z01 + sw1, aTop, rgb);
			glowVertexA(buffer, pose, x00 + sw0, y1, z00 + sw0, aTop, rgb);
		}
	}

	/** Clamps/rounds a 0..1 alpha to a 0..255 byte. */
	private static int alpha255(final float a) {
		return Mth.clamp(Math.round(a * 255.0F), 0, 255);
	}

	private static void glowVertexA(final VertexConsumer buffer, final PoseStack.Pose pose, final float x, final float y, final float z, final int alpha, final int rgb) {
		buffer.addVertex(pose, x, y, z).setColor(alpha << 24 | rgb);
	}

	private static void glowVertex(final VertexConsumer buffer, final PoseStack.Pose pose, final float x, final float y, final float z, final float alpha, final int rgb) {
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		buffer.addVertex(pose, x, y, z).setColor(a << 24 | rgb);
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
			for (BlockPos pos : effectPositions(effect)) {
				poseStack.pushPose();
				try {
					poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
					emitCubeFill(maskBuffer, poseStack.last(), 0, false, 0.0F);
				} finally {
					poseStack.popPose();
				}
			}
			poseStack.popPose();
			buffers.endBatch(BLOCK_DEPTH_MASK);

			// Outline now only hides behind its own target's depth.
			if (renderEffect(buffers, camera, effect, minecraft, outlineType, 1.0F, amount, shell, 0.0F)) {
				buffers.endBatch(outlineType);
			}
		} finally {
			main.copyDepthFrom(scratch);
		}
	}

	private static List<BlockPos> effectPositions(final VFXActiveEffect effect) {
		List<BlockPos> list = effect.getPositions();
		if (!list.isEmpty()) {
			return list;
		}
		return List.of(BlockPos.containing(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F), effect.getParam("pos_z", 0.0F)));
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

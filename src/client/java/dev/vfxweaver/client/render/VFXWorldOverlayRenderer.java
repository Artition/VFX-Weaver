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

	/** Tint quads are inset towards the block centre by this fraction to avoid coplanar fighting. */
	private static final float TINT_INSET = 0.002F;

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
					if (renderEffect(buffers, camera, effect, minecraft, type, 0.5F, 0.0F, false, TINT_INSET)) {
						drawn.add(type);
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
		final float inset
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
							emitCubeFill(buffer, pose, color, true, inset);
						} else {
							emitQuads(buffer, pose, quads, color, true, inset);
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
						emitCubeFill(buffer, pose, color, false, inset);
					} else {
						emitQuads(buffer, pose, quads, color, false, inset);
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
		final float inset
	) {
		for (BakedQuad quad : quads) {
			if (reverse) {
				for (int i = 3; i >= 0; i--) {
					var p = inset(quad.position(i), inset);
					buffer.addVertex(pose, p.x(), p.y(), p.z()).setColor(color);
				}
			} else {
				for (int i = 0; i < 4; i++) {
					var p = inset(quad.position(i), inset);
					buffer.addVertex(pose, p.x(), p.y(), p.z()).setColor(color);
				}
			}
		}
	}

	/** Pulls a block-local vertex towards the block centre by {@code inset} (coplanar fix). */
	private static Vector3fc inset(final Vector3fc v, final float inset) {
		if (inset <= 0.0F) {
			return v;
		}
		return new Vector3f(v).lerp(new Vector3f(0.5F, 0.5F, 0.5F), inset);
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

	private static void emitCubeFill(final VertexConsumer buffer, final PoseStack.Pose pose, final int color, final boolean reverse, final float inset) {
		for (float[] face : CUBE_FACES) {
			if (reverse) {
				for (int i = 3; i >= 0; i--) {
					buffer.addVertex(pose, inset(face[i * 3], inset), inset(face[i * 3 + 1], inset), inset(face[i * 3 + 2], inset)).setColor(color);
				}
			} else {
				for (int i = 0; i < 4; i++) {
					buffer.addVertex(pose, inset(face[i * 3], inset), inset(face[i * 3 + 1], inset), inset(face[i * 3 + 2], inset)).setColor(color);
				}
			}
		}
	}

	private static float inset(final float c, final float inset) {
		return c + (0.5F - c) * inset;
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

package dev.vfxweaver.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second-pass renderer for {@code entity_tint} and {@code entity_outline} effects. Both effects
 * re-submit the entity's own model (same {@link DefaultVertexFormat.ENTITY} vertices) through a
 * custom {@link RenderType}, so the geometry is rendered a second time without touching the
 * vanilla render type or its textures.
 *
 * <p>Both effects bind the entity's texture as {@code Sampler0} and use it as a transparency
 * mask (like the vanilla {@code rendertype_outline} shader): texels with zero alpha are discarded,
 * so the effect follows the silhouette of the texture instead of a flat box around the model.
 * Render types are therefore memoized per entity texture {@link Identifier}.</p>
 *
 * <p><b>Tint</b> has two modes selected by the boolean {@code texture} parameter. With
 * {@code texture = 1} (default) the texture is multiplied by the effect colour — the entity keeps
 * its look but is recoloured (and its own alpha is preserved). With {@code texture = 0} the tint
 * is a flat fill colour with the texture used only as the alpha mask (vanilla-outline style).
 * Depth is {@code LEQUAL} by default ({@code through_blocks = 0}) or {@code ALWAYS_PASS} when the
 * effect should be visible through terrain.</p>
 *
 * <p><b>Outline</b> is an inverted hull: every model cube is re-emitted scaled around its <em>own</em>
 * centre via {@code submitCustomGeometry}, so the shell grows from each part independently
 * (expanding the whole model from its middle drifted the rim downwards on tall/asymmetric
 * models). Only back-facing fragments are kept (front faces are discarded in the fragment shader
 * — the pipeline API only offers back-face culling, no front-face mode). The output is a flat fill
 * colour with the texture as the alpha mask, so the rim follows the texture contour. With a
 * {@code LEQUAL} depth test the inflated shell stays behind the entity's own surface, leaving a
 * clean rim around the silhouette; with {@code ALWAYS_PASS} it becomes a see-through glow. The
 * {@code width} parameter is an absolute rim thickness in world units: each cube face is grown
 * outwards by {@code width}, independent of the cube's size.
 * // ponytail: thickness is achieved by per-axis scaling around the cube centre (an absolute
 * // per-face offset), which is exact for axis-aligned boxes; a true normal-offset expansion that
 * // also handles arbitrary rotation would need a per-draw UBO.
 */
public final class VFXEntityEffectRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/entity-fx-render");

	private static RenderPipeline entityFxPipeline(final String suffix, final CompareOp depthOp, final String define) {
		return RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/entity_" + suffix))
				.withVertexShader(Identifier.fromNamespaceAndPath("vfxweaver", "core/entity_fx"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "core/entity_fx"))
				.withSampler("Sampler0")
				.withShaderDefine(define)
				.withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
				.withDepthStencilState(new DepthStencilState(depthOp, false))
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(false)
				.build()
		);
	}

	// Pipeline per (mode, through_blocks) combination; RenderTypes then memoize per entity texture.
	private static final RenderPipeline TINT_MULTIPLY_VISIBLE_P = entityFxPipeline("tint_multiply_visible", CompareOp.ALWAYS_PASS, "TINT_MULTIPLY");
	private static final RenderPipeline TINT_MULTIPLY_OCCLUDED_P = entityFxPipeline("tint_multiply_occluded", CompareOp.LESS_THAN_OR_EQUAL, "TINT_MULTIPLY");
	private static final RenderPipeline TINT_MASK_VISIBLE_P = entityFxPipeline("tint_mask_visible", CompareOp.ALWAYS_PASS, "TINT_MASK");
	private static final RenderPipeline TINT_MASK_OCCLUDED_P = entityFxPipeline("tint_mask_occluded", CompareOp.LESS_THAN_OR_EQUAL, "TINT_MASK");
	private static final RenderPipeline OUTLINE_OCCLUDED_P = entityFxPipeline("outline_occluded", CompareOp.LESS_THAN_OR_EQUAL, "OUTLINE");

	/**
	 * Through-walls outline variant: opaque (no blending) so it routes into the solid feature
	 * phase, submitted at submit order {@code -1} — before the entity's own order-0 pass. The
	 * entity body then paints over the shell interior while the rim around the silhouette survives
	 * on top of terrain: the outline is under its target yet still visible through walls.
	 */
	private static final RenderPipeline OUTLINE_THROUGH_P = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/entity_outline_through"))
			.withVertexShader(Identifier.fromNamespaceAndPath("vfxweaver", "core/entity_fx"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "core/entity_fx"))
			.withSampler("Sampler0")
			.withShaderDefine("OUTLINE")
			.withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withCull(false)
			.build()
	);

	/**
	 * One concrete render variant: its pipeline, the base render-type name and the memoized
	 * per-texture render types. The {@code types} map is accessed only from the render thread
	 * (submitted during entity rendering), so a plain {@link HashMap} is safe.
	 */
	private record FxType(RenderPipeline pipeline, String name, Map<Identifier, RenderType> types) {
		RenderType forTexture(final Identifier texture) {
			return this.types.computeIfAbsent(texture, id -> RenderType.create(
				this.name + ":" + id,
				RenderSetup.builder(this.pipeline).withTexture("Sampler0", id).createRenderSetup()
			));
		}
	}

	private static final FxType TINT_MULTIPLY_VISIBLE = new FxType(TINT_MULTIPLY_VISIBLE_P, "vfxweaver_entity_tint_multiply_visible", new HashMap<>());
	private static final FxType TINT_MULTIPLY_OCCLUDED = new FxType(TINT_MULTIPLY_OCCLUDED_P, "vfxweaver_entity_tint_multiply_occluded", new HashMap<>());
	private static final FxType TINT_MASK_VISIBLE = new FxType(TINT_MASK_VISIBLE_P, "vfxweaver_entity_tint_mask_visible", new HashMap<>());
	private static final FxType TINT_MASK_OCCLUDED = new FxType(TINT_MASK_OCCLUDED_P, "vfxweaver_entity_tint_mask_occluded", new HashMap<>());
	private static final FxType OUTLINE_OCCLUDED = new FxType(OUTLINE_OCCLUDED_P, "vfxweaver_entity_outline_occluded", new HashMap<>());
	private static final FxType OUTLINE_THROUGH = new FxType(OUTLINE_THROUGH_P, "vfxweaver_entity_outline_through", new HashMap<>());

	private VFXEntityEffectRenderer() {
	}

	/**
	 * Forces class initialisation (pipeline registration) at client startup, before the first
	 * resource reload precompiles the shaders. Invoking this static method triggers the class
	 * initializer, which runs {@code RenderPipelines.register(...)} for every pipeline.
	 */
	public static void register() {
	}

	private static <S extends LivingEntityRenderState> void submit(
		final S state,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final Model<? super S> model,
		final RenderType renderType,
		final int color
	) {
		submitNodeCollector.submitModel(model, state, poseStack, renderType, state.lightCoords, OverlayTexture.NO_OVERLAY, color, null, 0, null);
	}

	/**
	 * Tint pass. {@code textureMode}: 1 = multiply the entity texture by the effect colour,
	 * 0 = flat fill colour with the texture as the alpha mask. Both follow the texture silhouette.
	 */
	public static <S extends LivingEntityRenderState> void renderTint(
		final VFXActiveEffect effect,
		final S state,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final Model<? super S> model,
		final Identifier texture
	) {
		float alpha = clamp01(effect.getParam("alpha", 0.5F)) * effect.getWeight();
		if (alpha <= 0.0F) {
			return;
		}
		boolean through = effect.getParam("through_blocks", 1.0F) >= 0.5F;
		boolean multiply = effect.getParam("texture", 1.0F) >= 0.5F;
		FxType fx = multiply
			? (through ? TINT_MULTIPLY_VISIBLE : TINT_MULTIPLY_OCCLUDED)
			: (through ? TINT_MASK_VISIBLE : TINT_MASK_OCCLUDED);
		submit(state, poseStack, submitNodeCollector, model, fx.forTexture(texture), argb(effect, alpha));
	}

	/**
	 * Outline pass as an inverted hull via {@code submitCustomGeometry}: the model's own cubes are
	 * re-emitted, each scaled around its <em>own</em> centre (so the outline grows from every part
	 * independently instead of expanding the whole model from its middle — which drifted the rim
	 * downwards on tall/asymmetric models). The fragment shader keeps only back-facing fragments
	 * (inverted hull) and the entity texture acts as the alpha mask.
	 */
	public static <S extends LivingEntityRenderState> void renderOutline(
		final VFXActiveEffect effect,
		final S state,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final Model<? super S> model,
		final Identifier texture
	) {
		float alpha = clamp01(effect.getParam("alpha", 1.0F)) * effect.getWeight();
		if (alpha <= 0.0F) {
			return;
		}
		boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
		float width = Mth.clamp(effect.getParam("width", 0.05F), 0.0F, 1.0F);
		int color = argb(effect, alpha);
		FxType fx = through ? OUTLINE_THROUGH : OUTLINE_OCCLUDED;
		RenderType renderType = fx.forTexture(texture);
		// Thickness in world units: each cube face grows outwards by `width`, independent of the
		// cube's own size (see emitOutlineCube).
		float thickness = width;

		// Animate the model now (submit time) so the deferred custom-geometry draw reads the
		// correct pose for this state/frame without calling setupAnim inside the draw callback
		// (which would mutate shared model state while drawing). Vanilla's own body pass later
		// sets the same pose for the same state, so this is idempotent.
		model.setupAnim(state);
		// Through-walls outlines must sit UNDER their target: submit at order -1 with the opaque
		// pipeline so the solid feature phase flushes the shell before the entity's own body pass,
		// letting the body paint over the shell interior. Alpha is forced opaque there.
		if (through) {
			if (submitNodeCollector instanceof SubmitNodeStorage storage) {
				int throughColor = argb(effect, 1.0F);
				storage.order(-1).submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
					PoseStack stack = new PoseStack();
					stack.last().set(pose);
					model.root().visit(stack, (partPose, path, cubeIndex, cube) ->
						emitOutlineCube(partPose, buffer, cube, thickness, throughColor, state.lightCoords));
				});
				return;
			}
			// Fallback (unexpected collector type): translucent rim hugging the entity.
			FxType fallback = OUTLINE_OCCLUDED;
			renderType = fallback.forTexture(texture);
		}
		submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			model.root().visit(stack, (partPose, path, cubeIndex, cube) ->
				emitOutlineCube(partPose, buffer, cube, thickness, color, state.lightCoords));
		});
	}

	/**
	 * Emits one model cube expanded around its own centre. Each axis is scaled so that every face
	 * moves outwards by a fixed {@code thickness} (world units) — i.e. the rim thickness is
	 * independent of the cube's size, so small cubes (fingers, ears) get the same rim as the torso
	 * instead of a proportionally tiny one. Vertices are transformed by the part's matrix
	 * ({@code pose}), keeping the cube's normals, UVs and texture alpha (the shader masks
	 * transparent texels and discards front faces).
	 */
	private static void emitOutlineCube(
		final PoseStack.Pose pose,
		final VertexConsumer buffer,
		final ModelPart.Cube cube,
		final float thickness,
		final int color,
		final int lightCoords
	) {
		// Local cube centre and half-extents in world units (cube coords are in 1/16 texture units).
		float cx = (cube.minX + cube.maxX) / 32.0F;
		float cy = (cube.minY + cube.maxY) / 32.0F;
		float cz = (cube.minZ + cube.maxZ) / 32.0F;
		float hx = (cube.maxX - cube.minX) / 32.0F;
		float hy = (cube.maxY - cube.minY) / 32.0F;
		float hz = (cube.maxZ - cube.minZ) / 32.0F;
		float sx = growth(hx, thickness);
		float sy = growth(hy, thickness);
		float sz = growth(hz, thickness);
		Vector3f pos = new Vector3f();
		Vector3f normal = new Vector3f();
		for (ModelPart.Polygon polygon : cube.polygons) {
			pose.transformNormal(polygon.normal(), normal);
			for (ModelPart.Vertex v : polygon.vertices()) {
				float x = cx + (v.worldX() - cx) * sx;
				float y = cy + (v.worldY() - cy) * sy;
				float z = cz + (v.worldZ() - cz) * sz;
				pose.pose().transformPosition(x, y, z, pos);
				buffer.addVertex(pos.x(), pos.y(), pos.z(), color, v.u(), v.v(), OverlayTexture.NO_OVERLAY, lightCoords, normal.x(), normal.y(), normal.z());
			}
		}
	}

	/** Minimum half-extent (world units) below which a cube axis is treated as degenerate/flat. */
	private static final float MIN_HALF_EXTENT = 1.0E-4F;

	/** Scale factor that grows each face of a cube of the given half-extent by {@code thickness}. */
	private static float growth(final float halfExtent, final float thickness) {
		// Degenerate (flat) cubes along an axis are not expanded on that axis to avoid exploding.
		return halfExtent > MIN_HALF_EXTENT ? 1.0F + thickness / halfExtent : 1.0F;
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

	/**
	 * Renders active entity tint/outline effects on the first-person hand. The hand bypasses
	 * {@code LivingEntityRenderer.submit} entirely ({@code ItemInHandRenderer} submits the arm
	 * {@link ModelPart} directly), so the avatar renderer mixin calls this from
	 * {@code renderRightHand}/{@code renderLeftHand}. Only the local player's own effects apply —
	 * the hand always belongs to them. The hand renders in its own stage on top of the world, so
	 * {@code through_blocks} is ignored here and the visible (always-pass) pipeline variants are used.
	 *
	 * @param model      the player model (arm parts are taken from it)
	 * @param rightArm   true for the main-hand arm, false for the off-hand arm
	 * @param poseStack  the pose stack the vanilla hand pass used
	 * @param lightCoords the hand's packed light coords
	 * @param texture    the skin texture the vanilla hand pass renders with
	 */
	public static void renderHandEffects(
		final PlayerModel model,
		final boolean rightArm,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final Identifier texture
	) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		List<VFXActiveEffect> effects = VFXEffectManager.get().getActiveEntityEffects(player.getUUID());
		if (effects.isEmpty()) {
			return;
		}
		ModelPart arm = rightArm ? model.rightArm : model.leftArm;
		for (VFXActiveEffect effect : effects) {
			try {
				if (effect.getType() == VFXEffectType.ENTITY_TINT) {
					renderTintPart(effect, arm, poseStack, submitNodeCollector, lightCoords, texture);
				} else if (effect.getType() == VFXEffectType.ENTITY_OUTLINE) {
					renderOutlinePart(effect, arm, poseStack, submitNodeCollector, lightCoords, texture);
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to apply entity effect on hand '{}'", effect.getId(), e);
			}
		}
	}

	private static void renderTintPart(
		final VFXActiveEffect effect,
		final ModelPart arm,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final Identifier texture
	) {
		float alpha = clamp01(effect.getParam("alpha", 0.5F)) * effect.getWeight();
		if (alpha <= 0.0F) {
			return;
		}
		boolean multiply = effect.getParam("texture", 1.0F) >= 0.5F;
		FxType fx = multiply ? TINT_MULTIPLY_VISIBLE : TINT_MASK_VISIBLE;
		int color = argb(effect, alpha);
		// Emit the arm cubes manually instead of submitModelPart: custom geometry is the path
		// proven to render in the hand stage (the outline uses it), landing after the vanilla arm.
		submitNodeCollector.submitCustomGeometry(poseStack, fx.forTexture(texture), (pose, buffer) -> {
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			arm.visit(stack, (partPose, path, cubeIndex, cube) -> emitOutlineCube(partPose, buffer, cube, 0.0F, color, lightCoords));
		});
	}

	private static void renderOutlinePart(
		final VFXActiveEffect effect,
		final ModelPart arm,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final Identifier texture
	) {
		float alpha = clamp01(effect.getParam("alpha", 1.0F)) * effect.getWeight();
		if (alpha <= 0.0F) {
			return;
		}
		float width = Mth.clamp(effect.getParam("width", 0.05F), 0.0F, 1.0F);
		int color = argb(effect, alpha);
		// Always the occluded (LEQUAL) variant: the vanilla hand writes depth, which clips the
		// inflated shell to the rim around the silhouette — an always-pass shell would cover the
		// whole hand. The hand sits on top of the world anyway, so through_blocks is meaningless here.
		submitNodeCollector.submitCustomGeometry(poseStack, OUTLINE_OCCLUDED.forTexture(texture), (pose, buffer) -> {
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			// Visit only the arm subtree — the outline must wrap the hand, not the whole body.
			arm.visit(stack, (partPose, path, cubeIndex, cube) -> emitOutlineCube(partPose, buffer, cube, width, color, lightCoords));
		});
	}
}
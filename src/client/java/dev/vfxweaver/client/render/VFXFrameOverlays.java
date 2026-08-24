package dev.vfxweaver.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Flat-quad overlays for non-model entities (item frames). The frame's local coordinate space is
 * a square in XY with +Z pointing out of the wall: the tint is a single quad just in front of the
 * board and the outline is a rectangular ring slightly further out.
 */
public final class VFXFrameOverlays {
	private static RenderPipeline quadPipeline(final CompareOp depthOp, final String suffix) {
		return RenderPipelines.register(
			RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "frame/" + suffix))
				.withVertexShader("core/position_color")
				.withFragmentShader("core/position_color")
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
				.withDepthStencilState(new DepthStencilState(depthOp, false))
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(false)
				.build()
		);
	}

	private static final RenderType QUAD_VISIBLE = RenderType.create(
		"vfxweaver_frame_quad_visible",
		RenderSetup.builder(quadPipeline(CompareOp.ALWAYS_PASS, "visible")).createRenderSetup()
	);
	private static final RenderType QUAD_OCCLUDED = RenderType.create(
		"vfxweaver_frame_quad_occluded",
		RenderSetup.builder(quadPipeline(CompareOp.LESS_THAN_OR_EQUAL, "occluded")).createRenderSetup()
	);

	/** Tracks which overlay render types were fed during the current hand/frame stage batch. */
	private static final Map<RenderType, Boolean> SEEN = new HashMap<>();

	private VFXFrameOverlays() {
	}

	public static void renderEffects(
		final int lightCoords,
		final PoseStack.Pose pose,
		final SubmitNodeCollector submitNodeCollector,
		final List<VFXActiveEffect> effects
	) {
		for (VFXActiveEffect effect : effects) {
			try {
				boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
				boolean tint = effect.getType() == VFXEffectType.ENTITY_TINT;
				float alpha = clamp01(effect.getParam("alpha", tint ? 0.5F : 1.0F)) * effect.getWeight();
				if (alpha <= 0.0F) {
					continue;
				}
				int color = argb(effect, alpha);
				RenderType type = through ? QUAD_VISIBLE : QUAD_OCCLUDED;
				PoseStack local = new PoseStack();
				local.last().set(pose);
				// Model 0..1 space: the frame is a flat plane at z = 0 (entitySolidZOffsetForward),
				// so overlays draw just in front of it. The passed pose already includes -0.5 centring.
				if (tint) {
					submitNodeCollector.submitCustomGeometry(local, type, (captured, buffer) ->
						emitRect(buffer, captured, 0.0F, 0.0F, 1.0F, 1.0F, 0.02F, color, lightCoords));
				} else {
					float w = Math.min(Mth.clamp(effect.getParam("width", 0.05F), 0.01F, 0.2F) * 2.0F, 0.49F);
					submitNodeCollector.submitCustomGeometry(local, type, (captured, buffer) -> {
						emitRect(buffer, captured, 0.0F, 1.0F - w, 1.0F, 1.0F, 0.03F, color, lightCoords);  // top
						emitRect(buffer, captured, 0.0F, 0.0F, 1.0F, w, 0.03F, color, lightCoords);         // bottom
						emitRect(buffer, captured, 0.0F, w, w, 1.0F - w, 0.03F, color, lightCoords);       // left
						emitRect(buffer, captured, 1.0F - w, w, 1.0F, 1.0F - w, 0.03F, color, lightCoords); // right
					});
				}
			} catch (Exception ignored) {
			}
		}
	}

	private static void emitRect(
		final VertexConsumer consumer,
		final PoseStack.Pose pose,
		final float minX,
		final float minY,
		final float maxX,
		final float maxY,
		final float z,
		final int color,
		final int lightCoords
	) {
		Vector3f pos = new Vector3f();
		float[][] corners = {{minX, minY, z}, {maxX, minY, z}, {maxX, maxY, z}, {minX, maxY, z}};
		for (float[] corner : corners) {
			pos.set(corner[0], corner[1], corner[2]);
			pose.pose().transformPosition(pos.x(), pos.y(), pos.z(), pos);
			consumer.addVertex(pos.x(), pos.y(), pos.z(), color, 0.0F, 0.0F, 0, lightCoords, 0.0F, 1.0F, 0.0F);
		}
	}

	private static int argb(final VFXActiveEffect effect, final float alpha) {
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		int r = Mth.clamp((int) (Mth.clamp(effect.getParam("color_r", 1.0F), 0.0F, 1.0F) * 255.0F), 0, 255);
		int g = Mth.clamp((int) (Mth.clamp(effect.getParam("color_g", 1.0F), 0.0F, 1.0F) * 255.0F), 0, 255);
		int b = Mth.clamp((int) (Mth.clamp(effect.getParam("color_b", 1.0F), 0.0F, 1.0F) * 255.0F), 0, 255);
		return a << 24 | r << 16 | g << 8 | b;
	}

	private static float clamp01(final float value) {
		return Mth.clamp(value, 0.0F, 1.0F);
	}
}

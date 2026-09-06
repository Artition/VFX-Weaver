package dev.vfxweaver.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vfxweaver.effect.VFXActiveEffect;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Renders the {@code god_rays} effect: {@code count} additive vertical light beams rising out of
 * the target entity's body. Each beam is a camera-facing vertical quad (a spherical billboard:
 * its horizontal axis is perpendicular to the camera in world space), so it reads as a light
 * pillar from every angle instead of a flat face on the ground.
 */
public final class VFXBeamsRenderer {
	private VFXBeamsRenderer() {
	}

	/**
	 * Submits the beams for one active {@code god_rays} effect.
	 *
	 * @param effect              the running effect
	 * @param state               the entity render state (anchor height, light coords)
	 * @param poseStack           the entity's pose stack (must be current at submit time)
	 * @param submitNodeCollector the collector to submit custom geometry into
	 */
	public static <S extends LivingEntityRenderState> void render(
		final VFXActiveEffect effect,
		final S state,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector
	) {
		float intensity = Mth.clamp(effect.getParam("intensity", 1.0F), 0.0F, 1.0F) * effect.getWeight();
		if (intensity <= 0.0F) {
			return;
		}
		boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
		int count = Mth.clamp((int) effect.getParam("count", 6.0F), 1, 16);
		float height = Mth.clamp(effect.getParam("height", 12.0F), 1.0F, 64.0F);
		float spread = Mth.clamp(effect.getParam("spread", 0.6F), 0.0F, 4.0F);
		float speed = Mth.clamp(effect.getParam("speed", 2.0F), 0.5F, 8.0F);
		float width = Mth.clamp(effect.getParam("width", 0.12F), 0.02F, 1.0F);
		int red = (int) (Mth.clamp(effect.getParam("red", 0.6F), 0.0F, 1.0F) * 255.0F);
		int green = (int) (Mth.clamp(effect.getParam("green", 0.2F), 0.0F, 1.0F) * 255.0F);
		int blue = (int) (Mth.clamp(effect.getParam("blue", 0.9F), 0.0F, 1.0F) * 255.0F);
		int rgb = (red << 16) | (green << 8) | blue;

		float t = effect.getElapsed() / 20.0F;
		float centerY = Math.max(state.boundingBoxHeight * 0.5F, 0.05F);
		// The camera in the pose's camera-relative space sits at the origin; the entity's world
		// offset from the camera is what pose already encodes, so the "towards camera" direction
		// is the entity's render-state yRot + 180 in the XZ plane. Simpler and robust: build beams
		// in pose space by remembering that -camera is the origin, so the horizontal direction
		// from each beam base to the camera is just -(base.x, base.z) normalized (with the base in
		// camera-relative coords, i.e. pose space). Since the pose is camera-relative, transform
		// the base through it first, then compute the billboard axis in that same space.
		int light = 0xF000F0;
		final int lightF = light;
		submitNodeCollector.submitCustomGeometry(poseStack, VFXEntityEffectRenderer.beamsRenderType(through), (pose, buffer) -> {
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			Vector3f base = new Vector3f();
			Vector3f camSpace = new Vector3f();
			for (int i = 0; i < count; i++) {
				float theta = (float) (i * 6.2831853 / count) + 0.7F;
				base.set((float) Math.cos(theta) * spread, centerY, (float) Math.sin(theta) * spread);
				// Convert the base to camera-relative space (pose space): the camera is at the
				// origin there, so the horizontal "to camera" vector is -base (normalized).
				pose.pose().transformPosition(base, camSpace);
				camSpace.set(-camSpace.x, 0.0F, -camSpace.z);
				camSpace.normalize();
				Vector3f side = new Vector3f(-camSpace.z, 0.0F, camSpace.x);
				float phase = (float) ((t * speed + i / (float) count) % 1.0);
				if (phase < 0.0F) {
					phase += 1.0F;
				}
				float h = height * phase;
				int aBot = alpha255(intensity * (float) Math.sin(phase * Math.PI));
				emitBeamQuad(buffer, stack.last(), camSpace, base, side, width, h, aBot, rgb, lightF);
			}
		});
	}

	private static int alpha255(final float a) {
		return Mth.clamp(Math.round(a * 255.0F), 0, 255);
	}

	/**
	 * Emits one vertical beam quad: four vertices in pose (camera-relative) space, base at the
	 * entity anchor, rising to {@code top} with an alpha fade-out at the tip.
	 */
	private static void emitBeamQuad(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final Vector3f toCam,
		final Vector3f base,
		final Vector3f side,
		final float width,
		final float top,
		final int aBot,
		final int rgb,
		final int light
	) {
		float hw = width * 0.5F;
		Vector3f p = new Vector3f();
		// base is entity-local in the pose; transform to camera-relative coordinates on the fly.
		p.set(base.x() - side.x() * hw, base.y(), base.z() - side.z() * hw);
		pose.pose().transformPosition(p);
		buffer.addVertex(p.x(), p.y(), p.z(), (aBot << 24) | rgb, 0.0F, 0.0F, OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
		p.set(base.x() + side.x() * hw, base.y(), base.z() + side.z() * hw);
		pose.pose().transformPosition(p);
		buffer.addVertex(p.x(), p.y(), p.z(), (aBot << 24) | rgb, 0.0F, 0.0F, OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
		p.set(base.x() + side.x() * hw, base.y() + top, base.z() + side.z() * hw);
		pose.pose().transformPosition(p);
		buffer.addVertex(p.x(), p.y(), p.z(), rgb, 0.0F, 0.0F, OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
		p.set(base.x() - side.x() * hw, base.y() + top, base.z() - side.z() * hw);
		pose.pose().transformPosition(p);
		buffer.addVertex(p.x(), p.y(), p.z(), rgb, 0.0F, 0.0F, OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
	}
}
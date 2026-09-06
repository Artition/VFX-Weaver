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
 * the target entity's body, billboarded toward the camera, alpha fading toward the tip and
 * swaying sideways over time. Emitted in the entity's own pose space via
 * {@link SubmitNodeCollector#submitCustomGeometry}, so beams follow the entity as it moves.
 * The beams are pure additive fills (no texture sampling) — see {@code core/beams.fsh}.
 */
public final class VFXBeamsRenderer {
	private VFXBeamsRenderer() {
	}

	/**
	 * Submits the beams for one active {@code god_rays} effect.
	 *
	 * @param effect               the running effect
	 * @param state                the entity render state (anchor height, light coords)
	 * @param poseStack            the entity's pose stack (must be current at submit time)
	 * @param submitNodeCollector  the collector to submit custom geometry into
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
		float sway = Mth.clamp(effect.getParam("sway", 0.5F), 0.0F, 4.0F);
		float width = Mth.clamp(effect.getParam("width", 0.12F), 0.02F, 1.0F);
		int red = (int) (Mth.clamp(effect.getParam("red", 0.6F), 0.0F, 1.0F) * 255.0F);
		int green = (int) (Mth.clamp(effect.getParam("green", 0.2F), 0.0F, 1.0F) * 255.0F);
		int blue = (int) (Mth.clamp(effect.getParam("blue", 0.9F), 0.0F, 1.0F) * 255.0F);

		float t = effect.getElapsed() / 20.0F;
		float rise = (t * speed) % Math.max(height, 0.001F);
		if (rise <= 0.001F) {
			return;
		}
		float centerY = Math.max(state.boundingBoxHeight * 0.5F, 0.05F);

		submitNodeCollector.submitCustomGeometry(poseStack, VFXEntityEffectRenderer.beamsRenderType(through), (pose, buffer) -> {
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			Vector3f anchor = new Vector3f();
			Vector3f axis = new Vector3f();
			Vector3f toCam = new Vector3f();
			for (int i = 0; i < count; i++) {
				float theta = (float) (i * 6.2831853 / count) + 0.7F;
				anchor.set((float) Math.cos(theta) * spread, centerY, (float) Math.sin(theta) * spread);
				// Billboard: the quad faces the camera in the XZ plane. The camera sits at the
				// origin of the pose's camera-relative space.
				pose.pose().transformPosition(anchor, toCam);
				toCam.set(-toCam.x, 0.0F, -toCam.z);
				toCam.normalize();
				axis.set(-toCam.z, 0.0F, toCam.x);
				float swayPhase = (float) Math.sin(t * 1.5 + i * 2.4) * sway;
				emitQuad(buffer, stack.last(), anchor, axis, toCam, width, rise, centerY, swayPhase, intensity, red, green, blue);
			}
		});
	}

	/**
	 * Emits one vertical light beam as a quad: two triangles from the anchor up to the rising tip,
	 * alpha fading toward the tip, with horizontal sway (perpendicular to the beam) growing with
	 * height.
	 */
	private static void emitQuad(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final Vector3f anchor,
		final Vector3f axis,
		final Vector3f toCam,
		final float width,
		final float rise,
		final float centerY,
		final float sway,
		final float intensity,
		final int red,
		final int green,
		final int blue
	) {
		float hw = width * 0.5F;
		float baseAlpha = Math.round(intensity * 255.0F);
		int light = 0xF000F0;
		corner(buffer, pose, anchor, axis, toCam, -hw, centerY, 0.0F, 0.0F, baseAlpha, red, green, blue, light);
		corner(buffer, pose, anchor, axis, toCam, +hw, centerY, 0.0F, 0.0F, baseAlpha, red, green, blue, light);
		corner(buffer, pose, anchor, axis, toCam, +hw, centerY + rise, 1.0F, sway, 0.0F, red, green, blue, light);
		corner(buffer, pose, anchor, axis, toCam, -hw, centerY + rise, 1.0F, sway, 0.0F, red, green, blue, light);
	}

	private static void corner(
		final VertexConsumer buffer,
		final PoseStack.Pose pose,
		final Vector3f anchor,
		final Vector3f axis,
		final Vector3f toCam,
		final float offset,
		final float y,
		final float swayFactor,
		final float sway,
		final float alpha,
		final int red,
		final int green,
		final int blue,
		final int light
	) {
		float x = anchor.x() + axis.x() * offset + toCam.x() * sway * swayFactor;
		float z = anchor.z() + axis.z() * offset + toCam.z() * sway * swayFactor;
		int a = (int) alpha;
		int color = (a << 24) | (red << 16) | (green << 8) | blue;
		Vector3f pos = new Vector3f(x, y, z);
		pose.pose().transformPosition(pos);
		buffer.addVertex(pos.x(), pos.y(), pos.z(), color, 0.0F, 0.0F, OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
	}
}
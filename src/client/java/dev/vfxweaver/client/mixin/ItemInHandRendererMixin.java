package dev.vfxweaver.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.shake.CameraShakeManager;
import dev.vfxweaver.client.shake.VFXCameraRoll;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the camera shake onto the first-person hand. In 26.1 the hand renders from a pose that
 * cancels the shaken camera rotation (so it floats steady in front of a shaking world); this mixin
 * re-applies the shake offset (position + roll) on top, so the hand visibly shakes together with
 * the world instead of standing still. The HEAD/RETURN push/pop pair below keeps the hand's
 * PoseStack balanced.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	/** Rough pixel-per-block factor for the hand-space shake feet. */
	private static final float HAND_SHAKE_SCALE = 2.0F;

	@Inject(
		method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V",
		at = @At("HEAD")
	)
	private void vfxweaver$applyShakeToHandBegin(
		final float partialTicks,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final LocalPlayer player,
		final int packedLight,
		final CallbackInfo ci
	) {
		// Always push, so the matching RETURN pop keeps the stack balanced even when the shake is
		// currently zero.
		poseStack.pushPose();
		VFXEffectManager manager = VFXEffectManager.get();
		CameraShakeManager.Offset offset = CameraShakeManager.compute(manager);
		float roll = offset.roll() + VFXCameraRoll.compute(manager);
		if (offset.dx() == 0.0 && offset.dy() == 0.0 && offset.dz() == 0.0 && roll == 0.0F) {
			return;
		}
		poseStack.translate((float) offset.dx() * HAND_SHAKE_SCALE, (float) offset.dy() * HAND_SHAKE_SCALE, (float) offset.dz() * HAND_SHAKE_SCALE);
		if (roll != 0.0F) {
			poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
		}
	}

	@Inject(
		method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V",
		at = @At("RETURN")
	)
	private void vfxweaver$applyShakeToHandEnd(
		final float partialTicks,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final LocalPlayer player,
		final int packedLight,
		final CallbackInfo ci
	) {
		poseStack.popPose();
	}
}
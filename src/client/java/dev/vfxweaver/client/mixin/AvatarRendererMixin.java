package dev.vfxweaver.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vfxweaver.client.render.VFXEntityEffectRenderer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders active entity tint/outline effects on the first-person hand. The hand never goes through
 * {@code LivingEntityRenderer.submit} — {@code ItemInHandRenderer} submits the arm part directly
 * from {@code renderRightHand}/{@code renderLeftHand} — so those two methods are the hook points.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Shadow
	public abstract EntityModel<?> getModel();

	@Inject(method = "renderRightHand", at = @At("TAIL"))
	private void vfxweaver$effectsOnRightHand(
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final Identifier skinTexture,
		final boolean hasSleeve,
		final CallbackInfo ci
	) {
		VFXEntityEffectRenderer.renderHandEffects((PlayerModel) this.getModel(), true, poseStack, submitNodeCollector, lightCoords, skinTexture);
	}

	@Inject(method = "renderLeftHand", at = @At("TAIL"))
	private void vfxweaver$effectsOnLeftHand(
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final Identifier skinTexture,
		final boolean hasSleeve,
		final CallbackInfo ci
	) {
		VFXEntityEffectRenderer.renderHandEffects((PlayerModel) this.getModel(), false, poseStack, submitNodeCollector, lightCoords, skinTexture);
	}
}

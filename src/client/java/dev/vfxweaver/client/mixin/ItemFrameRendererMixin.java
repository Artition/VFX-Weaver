package dev.vfxweaver.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vfxweaver.client.access.IVFXWeaverEntityState;
import dev.vfxweaver.client.render.VFXFrameOverlays;
import dev.vfxweaver.effect.VFXActiveEffect;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders entity tint/outline effects on item frames. Frames are not living entities - they never
 * reach {@code LivingEntityRenderer.submit} - so the UUID is stored during this renderer's own
 * {@code extractRenderState} and the effects are drawn right before the frame's pose is popped.
 */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void vfxweaver$storeUuid(final ItemFrame entity, final ItemFrameRenderState state, final float partialTicks, final CallbackInfo ci) {
		((IVFXWeaverEntityState) state).vfxweaver$setUuid(entity.getUUID());
	}

	@Inject(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"))
	private void vfxweaver$effectsOnFrame(
		final ItemFrameRenderState state,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final CameraRenderState camera,
		final CallbackInfo ci
	) {
		UUID uuid = ((IVFXWeaverEntityState) state).vfxweaver$getUuid();
		if (uuid == null) {
			return;
		}
		List<VFXActiveEffect> effects = dev.vfxweaver.client.effect.VFXEffectManager.get().getActiveEntityEffects(uuid);
		if (effects.isEmpty()) {
			return;
		}
		VFXFrameOverlays.renderEffects(state.direction, state.lightCoords, poseStack.last(), submitNodeCollector, effects);
	}
}

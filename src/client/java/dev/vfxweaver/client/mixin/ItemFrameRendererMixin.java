package dev.vfxweaver.client.mixin;

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
 * {@code extractRenderState}. The overlay is drawn right before the frame model is submitted,
 * reusing the exact model pose (which already carries the entity offset and the -0.5 centring),
 * so the quads sit perfectly on the frame plane in model 0..1 space.
 */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void vfxweaver$storeUuid(final ItemFrame entity, final ItemFrameRenderState state, final float partialTicks, final CallbackInfo ci) {
		((IVFXWeaverEntityState) state).vfxweaver$setUuid(entity.getUUID());
	}

	@Inject(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockModelRenderState;submitWithZOffset(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
			shift = At.Shift.BEFORE
		),
		require = 0
	)
	private void vfxweaver$effectsOnFrame(
		final ItemFrameRenderState state,
		final com.mojang.blaze3d.vertex.PoseStack poseStack,
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
		VFXFrameOverlays.renderEffects(state.lightCoords, poseStack.last(), submitNodeCollector, effects);
	}
}
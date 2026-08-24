package dev.vfxweaver.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.vfxweaver.client.access.IVFXWeaverEntityState;
import dev.vfxweaver.client.render.VFXFrameOverlays;
import dev.vfxweaver.effect.VFXActiveEffect;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders entity tint/outline effects on item frames. Frames are not living entities - they never
 * reach {@code LivingEntityRenderer.submit} - so the UUID is stored during this renderer's own
 * {@code extractRenderState}. The overlay pose is rebuilt from the attachment {@code direction}
 * at the head of {@code submit} (mirroring the vanilla transforms), so the overlay draws exactly
 * once in the frame's local plane regardless of how many push/pop pairs the vanilla path uses.
 */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void vfxweaver$storeUuid(final ItemFrame entity, final ItemFrameRenderState state, final float partialTicks, final CallbackInfo ci) {
		((IVFXWeaverEntityState) state).vfxweaver$setUuid(entity.getUUID());
	}

	@Inject(method = "submit", at = @At("HEAD"))
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
		PoseStack local = frameLocalPose(state.direction);
		VFXFrameOverlays.renderEffects(state.lightCoords, local.last(), submitNodeCollector, effects);
	}

	/** Replicates the vanilla frame transform: render offset, 0.46875 push-off the wall, rotations. */
	private static PoseStack frameLocalPose(final Direction direction) {
		Vec3 offset = new Vec3(direction.getStepX() * 0.3F, -0.25, direction.getStepZ() * 0.3F);
		PoseStack poseStack = new PoseStack();
		poseStack.translate(-offset.x(), -offset.y(), -offset.z());
		poseStack.translate(direction.getStepX() * 0.46875F, direction.getStepY() * 0.46875F, direction.getStepZ() * 0.46875F);
		float xRot;
		float yRot;
		if (direction.getAxis().isHorizontal()) {
			xRot = 0.0F;
			yRot = 180.0F - direction.toYRot();
		} else {
			xRot = -90 * direction.getAxisDirection().getStep();
			yRot = 180.0F;
		}
		poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
		poseStack.mulPose(Axis.YP.rotationDegrees(yRot));
		return poseStack;
	}
}
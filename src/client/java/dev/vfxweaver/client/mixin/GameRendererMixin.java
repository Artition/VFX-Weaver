package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.postprocessing.VFXPostProcessingManager;
import dev.vfxweaver.effect.VFXWorldBindings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the effect clock and applies the post-processing chain right before the game GUI is
 * drawn (i.e. after the world and the vanilla post chain have been rendered into the main target,
 * but before the overlays that should stay unaffected).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(
		method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
			shift = At.Shift.BEFORE
		)
	)
	private void vfxweaver$render(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		VFXEffectManager manager = VFXEffectManager.get();
		if (minecraft.level == null) {
			manager.stopAll();
			VFXWorldBindings.clear();
			return;
		}

		float deltaTicks = minecraft.isPaused() ? 0.0F : deltaTracker.getGameTimeDeltaTicks();

		Camera camera = minecraft.gameRenderer.getMainCamera();
		if (camera.isInitialized()) {
			Vec3 camPos = camera.position();
			Matrix4f viewRotProj = camera.getViewRotationProjectionMatrix(new Matrix4f());
			VFXWorldBindings.update((float) camPos.x, (float) camPos.y, (float) camPos.z, camera.yRot(), camera.xRot(), viewRotProj, deltaTicks);
		}
		if (minecraft.player != null) {
			var player = minecraft.player;
			var playerLevel = minecraft.level;
			var delta = player.getDeltaMovement();
			float speed = (float) Math.sqrt(delta.x * delta.x + delta.z * delta.z) * 20.0F;
			var lightPos = player.blockPosition();
			int blockLight = playerLevel.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, lightPos);
			int skyLight = Math.max(0, playerLevel.getBrightness(net.minecraft.world.level.LightLayer.SKY, lightPos) - playerLevel.getSkyDarken());
			VFXWorldBindings.updatePlayerState(
				player.getHealth() / Math.max(player.getMaxHealth(), 1.0e-4F),
				player.getFoodData().getFoodLevel() / 20.0F,
				speed,
				Math.max(blockLight, skyLight) / 15.0F,
				(playerLevel.getOverworldClockTime() % 24000L) / 24000.0F,
				(float) player.getX(),
				(float) player.getY(),
				(float) player.getZ()
			);
		}
		manager.advance(deltaTicks);
		manager.update();

		// Layer 1: above the world and the first-person hand, below the GUI (default).
		VFXPostProcessingManager.get().process(manager, minecraft.getMainRenderTarget(), 1);
	}

	/**
	 * Layer 0 screen effects run right before the first-person hand is rendered, so they affect
	 * only the world frame and stay under the hand and the GUI.
	 */
	@Inject(
		method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"
		)
	)
	private void vfxweaver$renderLayer0(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		VFXPostProcessingManager.get().process(VFXEffectManager.get(), minecraft.getMainRenderTarget(), 0);
	}

	/**
	 * Layer 2 screen effects run at the very end of the frame, after the GUI, so they cover
	 * everything on screen.
	 */
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void vfxweaver$renderLayer2(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		VFXPostProcessingManager.get().process(VFXEffectManager.get(), minecraft.getMainRenderTarget(), 2);
	}
}

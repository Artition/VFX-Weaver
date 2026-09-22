package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.flashback.VFXReplayController;
import dev.vfxweaver.client.postprocessing.VFXPostProcessingManager;
import dev.vfxweaver.effect.VFXWorldBindings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
//? if <26.1 {
/*import org.joml.Quaternionf;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <26.1 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Drives the effect clock and applies the post-processing chain right before the game GUI is
 * drawn (i.e. after the world and the vanilla post chain have been rendered into the main target,
 * but before the overlays that should stay unaffected).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * FOV actually used for the last gameplay frame (captured by the 1.21.11 {@code getFov} hook,
	 * including the FOV-modifier/sprint modulation). Used to rebuild the view-rotation-projection
	 * matrix so the screen bindings follow the rendered FOV; {@code -1} means "not captured yet".
	 * 1.21.11 only.
	 */
	//? if <26.1
	/*private static float vfxweaver$lastFov = -1.0F;*/

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

		// The effect clock and the camera/player snapshots are refreshed by vfxweaver$renderLayer0,
		// which runs inside renderLevel BEFORE this hook (renderLevel is called at the top of
		// render, FogRenderer.endFrame at the end). Layer 0 therefore sees the current frame's time
		// and player state; layers 1/2 (here and at TAIL) reuse the same snapshot. Advancing here
		// again would double-advance the clock.

		// Layer 1: above the world and the first-person hand, below the GUI (default).
		//? if <26.2 {
		VFXPostProcessingManager.get().process(manager, minecraft.getMainRenderTarget(), 1);
		//?} else {
		/*VFXPostProcessingManager.get().process(manager, minecraft.gameRenderer.mainRenderTarget(), 1);
		*///?}
	}

	/**
	 * Layer 0 screen effects run right before the first-person hand is rendered, so they affect
	 * only the world frame and stay under the hand and the GUI.
	 *
	 * <p>This hook also refreshes the shared effect clock and the camera/player snapshots: it runs
	 * earlier in the frame than the {@code FogRenderer.endFrame} hook (which only applies layer 1),
	 * so layer 0 — where {@code surface_pattern} runs by default — sees the current frame's clock
	 * and the interpolated player position instead of trailing them by one frame.
	 */
	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At(
		value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
	))
	private void vfxweaver$renderLayer0(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		vfxweaver$updateFrame(minecraft, deltaTracker);
		//? if <26.2 {
		VFXPostProcessingManager.get().process(VFXEffectManager.get(), minecraft.getMainRenderTarget(), 0);
		//?} else {
		/*VFXPostProcessingManager.get().process(VFXEffectManager.get(), minecraft.gameRenderer.mainRenderTarget(), 0);
		*///?}
	}

	/**
	 * Advances the shared effect clock and republishes the camera and local-player snapshots for
	 * the current frame. Called once per frame from the layer-0 hook, before any layer processes.
	 *
	 * @param minecraft the client (a level is guaranteed to be loaded)
	 * @param deltaTracker the frame's delta tracker
	 */
	private void vfxweaver$updateFrame(final Minecraft minecraft, final DeltaTracker deltaTracker) {
		float deltaTicks = minecraft.isPaused() ? 0.0F : deltaTracker.getGameTimeDeltaTicks();
		// Interpolate the player position across the current frame so player-bound overlays move
		// smoothly instead of stepping at the 20 Hz game tick.
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

		//? if <26.2 {
		Camera camera = minecraft.gameRenderer.getMainCamera();
		//?} else {
		/*Camera camera = minecraft.gameRenderer.mainCamera();
		*///?}
		if (camera.isInitialized()) {
			Vec3 camPos = camera.position();
			//? if <26.1 {
/*			// Prefer the FOV captured by the getFov hook (rendered, modulated). It may be one frame
			// stale because getFov can run after this hook, which is still better than the base option.
			float renderedFov = vfxweaver$lastFov > 0.0F ? vfxweaver$lastFov : minecraft.options.fov().get().floatValue();
			Matrix4f viewRotProj = minecraft.gameRenderer.getProjectionMatrix(renderedFov);
			viewRotProj.mul(new Matrix4f().rotation(new Quaternionf(camera.rotation()).conjugate()));
*///?} else {
			Matrix4f viewRotProj = camera.getViewRotationProjectionMatrix(new Matrix4f());
//?}
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
				//? if <26.1 {
/*				(playerLevel.getDayTime() % 24000L) / 24000.0F,
*///?} else {
				(playerLevel.getOverworldClockTime() % 24000L) / 24000.0F,
//?}
				(float) Mth.lerp(partialTick, player.xo, player.getX()),
				(float) Mth.lerp(partialTick, player.yo, player.getY()),
				(float) Mth.lerp(partialTick, player.zo, player.getZ())
			);
		}
		// While a Flashback replay is open the effect clock is the replay's own time position, so
		// pausing and seeking the replay pause and move the effects with it. Outside a replay the
		// wall clock is used exactly as before.
		if (FlashbackCompat.isReplayActive()) {
			float replayTick = (float) FlashbackCompat.getReplayTimeTicks();
			VFXEffectManager.get().setClock(replayTick);
			VFXReplayController.get().apply(replayTick, FlashbackCompat.isReplayPaused());
		} else {
			VFXEffectManager.get().advance(deltaTicks);
		}
		VFXEffectManager.get().update();
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
		//? if <26.2 {
		VFXPostProcessingManager.get().process(VFXEffectManager.get(), minecraft.getMainRenderTarget(), 2);
		//?} else {
		/*VFXPostProcessingManager.get().process(VFXEffectManager.get(), minecraft.gameRenderer.mainRenderTarget(), 2);
		*///?}
	}

	/**
	 * Applies the {@code fov_modifier} effect. 26.1 exposes the delta through {@code Camera.calculateFov};
	 * 1.21.11 computes the render FOV in {@code GameRenderer.getFov}, so the delta is added there instead.
	 */
	//? if <26.1 {
/*	@Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
	private void vfxweaver$modifyFov(final Camera camera, final float partialTick, final boolean useFovSetting, final CallbackInfoReturnable<Float> cir) {
		// getFov is also called for culling with useFovSetting=false; the FOV effect must only touch
		// the gameplay FOV, so skip (and do not capture) the culling call.
		if (!useFovSetting) {
			return;
		}
		float delta = VFXEffectManager.get().getActiveFovDelta();
		float fov = cir.getReturnValue() + delta;
		vfxweaver$lastFov = fov;
		if (delta != 0.0F) {
			cir.setReturnValue(fov);
		}
	}
*///?}
}

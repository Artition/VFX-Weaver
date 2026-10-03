package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.window.VFXWindowManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Presents the aux picture windows once per frame, after the game has presented its own frame.
 *
 * <p>The game's own present happens inside its frame method - {@code Minecraft.renderFrame} on
 * {@code >=26.1} and {@code Minecraft.runTick} on {@code <26.1} (1.21.11) - so a {@code TAIL}
 * injection there is the first point that is genuinely after the game's own present; the aux windows
 * are then swapped through their own GL contexts by {@link VFXWindowManager#apply()}.
 *
 * <p><b>Render thread only.</b> Both methods run on the client render thread, and the call reaches
 * GLFW/GL through the controller, which is not thread-safe.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	//? if <26.1 {
	/*@Inject(method = "runTick(Z)V", at = @At("TAIL"))
	private void vfxweaver$presentAuxWindows(final boolean render, final CallbackInfo ci) {
		VFXWindowManager.get().apply();
	}
	*///?} else {
	@Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
	private void vfxweaver$presentAuxWindows(final boolean render, final CallbackInfo ci) {
		VFXWindowManager.get().apply();
	}
	//?}
}

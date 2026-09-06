package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.hud.HudFadeState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the in-game HUD render pass in {@link Gui#extractRenderState}. {@code GuiRenderStateMixin}
 * only fades blits submitted while this flag is set, so a {@code Screen} (inventory, pause, ...)
 * is rendered outside that window and never touched.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void vfxweaver$hudBegin(final GuiGraphicsExtractor extractor, final DeltaTracker deltaTracker, final CallbackInfo ci) {
		HudFadeState.setInHud(true);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
	private void vfxweaver$hudEnd(final GuiGraphicsExtractor extractor, final DeltaTracker deltaTracker, final CallbackInfo ci) {
		HudFadeState.setInHud(false);
	}
}
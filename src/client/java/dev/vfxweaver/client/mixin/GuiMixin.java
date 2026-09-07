package dev.vfxweaver.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.vfxweaver.client.hud.HudFadeState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks the in-game HUD render pass with a guaranteed try/finally. The flag is always cleared -
 * even if {@code Gui.extractRenderState} throws - so a {@code Screen} (inventory, pause, ...)
 * rendered right after it in {@code GameRenderer.extractGui} is never faded by a stuck flag.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
	private void vfxweaver$hudScope(final GuiGraphicsExtractor extractor, final DeltaTracker deltaTracker, final Operation<Void> original) {
		HudFadeState.beginHud();
		try {
			original.call(extractor, deltaTracker);
		} finally {
			HudFadeState.endHud();
		}
	}
}
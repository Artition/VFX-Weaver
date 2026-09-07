package dev.vfxweaver.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.vfxweaver.client.hud.HudFadeState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Intercepts the single point where every GUI element (HUD, overlay, screen) enters the
 * {@code GuiRenderState}: {@code addGuiElement}. When the in-game HUD pass runs
 * ({@link HudFadeState#isInHud()}, set by {@link GuiMixin}) and the HUD is hidden, the element is
 * not added at all - a binary F1-style hide that covers every element (hotbar item icons, glyphs,
 * text) instead of the old per-pipeline alpha fade; outside that window (screens) it is passed
 * through untouched.
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
	@WrapMethod(method = "addGuiElement(Lnet/minecraft/client/renderer/state/gui/GuiElementRenderState;)V")
	private void vfxweaver$maybeAdd(final GuiElementRenderState element, final Operation<Void> original) {
		if (HudFadeState.isHidden()) {
			return;
		}
		original.call(element);
	}
}
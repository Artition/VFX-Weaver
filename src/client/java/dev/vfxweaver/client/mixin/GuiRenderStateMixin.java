package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.hud.HudFadeState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Applies the {@code hud_fade} effect to the state-based 26.1 HUD. Every blit layer submitted
 * through {@code GuiRenderState} gets its tint alpha multiplied by the effective HUD opacity
 * when a {@code hud_fade} effect is active (hotbar, hearts, XP bar, crosshair, boss bar — cover
 * blit-drawn layers).
 *
 * <p>Text layers ({@code GuiTextRenderState}) cannot be reconstructed here (its {@code
 * includeEmpty} field is package-private), so plain-text HUD (chat messages, XP numbers,
 * tooltips) is not faded by this hook. Per the plan's spike-first decision, the remaining
 * coverage (a dedicated chat/text hook, ideally via the chat opacity stack) must be verified
 * and added after a {@code runClient} pass.
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
	@ModifyArg(
		method = "addBlitToCurrentLayer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState$Node;addGuiElement(Lnet/minecraft/client/renderer/state/gui/GuiElementRenderState;)V"
		),
		index = 0
	)
	private GuiElementRenderState vfxweaver$fadeBlit(final GuiElementRenderState element) {
		float opacity = HudFadeState.hudOpacity();
		if (opacity >= 1.0F || !(element instanceof BlitRenderState state)) {
			return element;
		}
		return new BlitRenderState(
			state.pipeline(), state.textureSetup(), state.pose(),
			state.x0(), state.y0(), state.x1(), state.y1(),
			state.u0(), state.v0(), state.u1(), state.v1(),
			multiplyAlpha(state.color(), opacity), state.scissorArea()
		);
	}

	private static int multiplyAlpha(final int argb, final float opacity) {
		int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * opacity), 0, 255);
		return (a << 24) | (argb & 0x00FFFFFF);
	}
}
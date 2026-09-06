package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.hud.HudFadeState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Applies the {@code hud_fade} effect to the state-based 26.1 HUD. Every blit added into the
 * {@code GuiRenderState} (from {@code GuiGraphicsExtractor.innerBlit}) gets its tint alpha
 * multiplied while the in-game HUD pass runs ({@link HudFadeState#isInHud()}, set by
 * {@link GuiMixin}) - so hotbar, hearts, XP bar, crosshair, boss bar and other BlitRenderState
 * layers fade, while {@code Screen}s (inventory, pause, ...) are rendered outside that window and
 * stay untouched.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
	@ModifyArg(
		method = "innerBlit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;IIIIFFFFI)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addGuiElement(Lnet/minecraft/client/renderer/state/gui/GuiElementRenderState;)V"
		),
		index = 0
	)
	private GuiElementRenderState vfxweaver$fadeBlit(final GuiElementRenderState element) {
		if (!HudFadeState.isInHud()) {
			return element;
		}
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
		int a = Math.round(((argb >>> 24) & 0xFF) * Mth.clamp(opacity, 0.0F, 1.0F));
		return (Math.min(a, 255) << 24) | (argb & 0x00FFFFFF);
	}
}
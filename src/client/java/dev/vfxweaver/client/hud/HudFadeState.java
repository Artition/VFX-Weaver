package dev.vfxweaver.client.hud;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import net.minecraft.util.Mth;

/**
 * Computes the effective HUD opacity from all active {@code hud_fade} effects. Each effect hides
 * the HUD by {@code (1 - opacity)} and, when {@code chat} is 1, also fades the chat layer. The
 * {@code GuiRenderState} mixin multiplies the alpha of every HUD text/blit layer by these values.
 */
public final class HudFadeState {
	private static final float[] CACHE = new float[2];

	private HudFadeState() {
	}

	/**
	 * Effective HUD opacity in {@code [0, 1]} (1 = fully visible).
	 */
	public static float hudOpacity() {
		recompute();
		return CACHE[0];
	}

	/**
	 * Effective chat opacity in {@code [0, 1]} (1 = fully visible).
	 */
	public static float chatOpacity() {
		recompute();
		return CACHE[1];
	}

	private static void recompute() {
		float opacity = 1.0F;
		float chat = 1.0F;
		for (VFXActiveEffect effect : VFXEffectManager.get().getActiveHudFades()) {
			float fade = (1.0F - Mth.clamp(effect.getParam("opacity", 0.0F), 0.0F, 1.0F)) * effect.getWeight();
			opacity *= (1.0F - fade);
			chat *= (1.0F - fade * Mth.clamp(effect.getParam("chat", 1.0F), 0.0F, 1.0F));
		}
		CACHE[0] = opacity;
		CACHE[1] = chat;
	}
}
package dev.vfxweaver.client.hud;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether the in-game HUD (and optionally the first-person hand) is hidden by the active
 * {@code hud_fade} effects. This is a binary on/off hide (like vanilla F1 / {@code hideHud}): the
 * HUD is either fully shown or fully hidden - never translucent - so no texture can break.
 *
 * <p>{@code opacity} is the timeline-driven binary switch: hidden while the combined evaluated
 * opacity is below {@value #HIDE_THRESHOLD} (the built-in default animates 0 -> 1, i.e. hidden at
 * the start of the effect). The {@code hide_hand} param of any active {@code hud_fade} extends the
 * hide to the first-person hand.
 *
 * <p>The flag is only set during the in-game HUD pass ({@link GuiMixin} wraps
 * {@code Gui.extractRenderState} in try/finally), so screens are never touched.
 */
public final class HudFadeState {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/hud_fade");
	/** Binary switch threshold: a combined opacity below this hides the HUD. */
	public static final float HIDE_THRESHOLD = 0.5F;

	private static boolean inHud;
	private static boolean hidden;
	private static boolean hideHand;

	private HudFadeState() {
	}

	public static void beginHud() {
		if (inHud) {
			LOGGER.warn("[hud_fade] flag stuck: previous Gui.extractRenderState did not complete (exception path?)");
		}
		inHud = true;
		hidden = computeOpacity() < HIDE_THRESHOLD;
		hideHand = hidden && wantsHandHidden();
	}

	public static void endHud() {
		inHud = false;
		hidden = false;
		hideHand = false;
	}

	/** Whether the in-game HUD render pass is currently running. */
	public static boolean isInHud() {
		return inHud;
	}

	/** Whether the HUD should be hidden this frame (F1-style on/off). */
	public static boolean isHidden() {
		return inHud && hidden;
	}

	/** Whether the first-person hand should be hidden too. */
	public static boolean shouldHideHand() {
		return inHud && hideHand;
	}

	private static float computeOpacity() {
		float opacity = 1.0F;
		for (VFXActiveEffect effect : VFXEffectManager.get().getActiveHudFades()) {
			float fade = (1.0F - Mth.clamp(effect.getParam("opacity", 0.0F), 0.0F, 1.0F)) * effect.getWeight();
			opacity *= 1.0F - Mth.clamp(fade, 0.0F, 1.0F);
		}
		return Mth.clamp(opacity, 0.0F, 1.0F);
	}

	private static boolean wantsHandHidden() {
		for (VFXActiveEffect effect : VFXEffectManager.get().getActiveHudFades()) {
			if (effect.getParam("hide_hand", 1.0F) >= HIDE_THRESHOLD) {
				return true;
			}
		}
		return false;
	}
}
package dev.vfxweaver.client.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the effective HUD opacity from all active {@code hud_fade} effects and fades a GUI
 * element per-pipeline. The colour format ({@code BlitRenderState.color()}) is the same ARGB int,
 * but each pipeline interprets it differently, so the multiplier must be per-pipeline:
 *
 * <ul>
 *   <li>{@code GUI_TEXTURED} (straight alpha): scale only the alpha byte - exact lerp to the
 *       background ({@code out = rgb * (a*f) + dst * (1 - a*f)}).</li>
 *   <li>{@code GUI_TEXTURED_PREMULTIPLIED_ALPHA}: scale the whole ARGB - RGB already carries the
 *       alpha, so alpha-only scaling would leave it "overexposed" against its alpha (broken
 *       translucent edges).</li>
 *   <li>{@code CROSSHAIR} (INVERT blend): scale only RGB toward zero - the blend ignores src.a,
 *       so scaling alpha would not fade it and would write garbage into the target's alpha
 *       channel (darkened screen); {@code rgb -> 0} is the identity for the INVERT blend.</li>
 * </ul>
 *
 * The flag is only set during the in-game HUD pass ({@link GuiMixin} wraps
 * {@code Gui.extractRenderState} in try/finally), so screens are never touched.
 */
public final class HudFadeState {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/hud_fade");
	public static final boolean DEBUG = System.getProperty("vfxweaver.hudfade.debug") != null;

	private static boolean inHud;
	private static float opacity = 1.0F;
	private static int fadedBlits;

	private HudFadeState() {
	}

	public static void beginHud() {
		if (inHud) {
			LOGGER.warn("[hud_fade] flag stuck: previous Gui.extractRenderState did not complete (exception path?)");
		}
		inHud = true;
		opacity = computeOpacity();
	}

	public static void endHud() {
		inHud = false;
		if (DEBUG && fadedBlits > 0) {
			LOGGER.debug("[hud_fade] blits faded this frame: {}", fadedBlits);
		}
		fadedBlits = 0;
	}

	/** Whether the in-game HUD render pass is currently running. */
	public static boolean isInHud() {
		return inHud;
	}

	/**
	 * Fades a GUI element during the HUD pass. Non-blit elements (items, glyphs) are passed
	 * through for now - they need per-class tint fields to be faded.
	 */
	public static GuiElementRenderState fade(final GuiElementRenderState element) {
		if (!inHud || opacity >= 1.0F) {
			return element;
		}
		if (element instanceof BlitRenderState blit) {
			if (DEBUG) {
				fadedBlits++;
			}
			return fadeBlit(blit);
		}
		return element;
	}

	private static BlitRenderState fadeBlit(final BlitRenderState state) {
		final float f = opacity;
		final RenderPipeline pipeline = state.pipeline();
		final int color;
		if (pipeline == RenderPipelines.CROSSHAIR) {
			color = scaleRgb(state.color(), f);
		} else if (pipeline == RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA) {
			color = scaleAll(state.color(), f);
		} else {
			color = scaleAlpha(state.color(), f);
		}
		return new BlitRenderState(
			state.pipeline(), state.textureSetup(), state.pose(),
			state.x0(), state.y0(), state.x1(), state.y1(),
			state.u0(), state.v0(), state.u1(), state.v1(),
			color, state.scissorArea()
		);
	}

	private static float computeOpacity() {
		float opacity = 1.0F;
		for (VFXActiveEffect effect : VFXEffectManager.get().getActiveHudFades()) {
			float fade = (1.0F - Mth.clamp(effect.getParam("opacity", 0.0F), 0.0F, 1.0F)) * effect.getWeight();
			opacity *= 1.0F - Mth.clamp(fade, 0.0F, 1.0F);
		}
		return Mth.clamp(opacity, 0.0F, 1.0F);
	}

	private static int scaleAlpha(final int argb, final float f) {
		final int a = Math.min(255, Math.round(((argb >>> 24) & 0xFF) * Mth.clamp(f, 0.0F, 1.0F)));
		return (a << 24) | (argb & 0x00FF_FFFF);
	}

	private static int scaleAll(final int argb, final float f) {
		final float g = Mth.clamp(f, 0.0F, 1.0F);
		final int a = Math.min(255, Math.round(((argb >>> 24) & 0xFF) * g));
		final int r = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * g));
		final int gr = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * g));
		final int b = Math.min(255, Math.round((argb & 0xFF) * g));
		return (a << 24) | (r << 16) | (gr << 8) | b;
	}

	private static int scaleRgb(final int argb, final float f) {
		final float g = Mth.clamp(f, 0.0F, 1.0F);
		final int r = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * g));
		final int gr = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * g));
		final int b = Math.min(255, Math.round((argb & 0xFF) * g));
		return (argb & 0xFF00_0000) | (r << 16) | (gr << 8) | b;
	}
}
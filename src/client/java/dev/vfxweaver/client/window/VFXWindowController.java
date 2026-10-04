package dev.vfxweaver.client.window;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-window controller: applies one effect frame's geometry, opacity and picture to an aux
 * window.
 *
 * <p><b>Geometry.</b> The effect carries {@code pos_x}, {@code pos_y}, {@code size_w} and
 * {@code size_h} as 0..1 fractions of the pinned monitor's work area. The window is the whole work
 * area, so the work area is its own logical (screen) rect: {@code pos} and {@code size} are each
 * clamped into {@code [0, 1]}, {@code size} is scaled into it and {@code pos} is mapped over the
 * remaining free space with the spec's formula {@code x = wa.x + pos_x * max(0, wa.w - w)}, and the
 * same for {@code y}. That screen rectangle is normalized into a bottom-left window rect
 * ({@code rectY = 1 - rectTop - rectH}) and drawn into by the window's content. Nothing is moved or
 * resized on the OS window - the canvas geometry never changes - so the per-frame OS-call count on
 * this path is zero.
 *
 * <p><b>Opacity and frame.</b> The animated opacity is clamped into {@code [0, 1]} and pushed
 * through {@link VFXWindow#setOpacity(float)} only when it actually changed since the last call, so
 * a steady frame does no opacity call either. The frame is drawn into the mapped rectangle through
 * the window's own {@link VFXWindowContent#drawFrame(int, float, float, float, float)} and the
 * window is then presented by {@link VFXWindow#present()}.
 *
 * <p><b>Reads, does not own.</b> This controller is given its window and its content; it creates,
 * stores and destroys neither. The registry owns the windows and the lifecycle owns the content.
 *
 * <p><b>Render thread only, and after the game's own present.</b> Every path here calls GLFW and
 * delegates GL through {@link VFXWindowContent#drawFrame(int, float, float, float, float)} and
 * {@link VFXWindow#present()},
 * which save and restore the game's current GL context; GLFW and GL are not thread-safe and a
 * context may only be current on one thread, so {@link #apply} must run on the render thread and
 * after the game's own present.
 */
public final class VFXWindowController {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/window");

	private final VFXWindowContent content;
	private boolean hasOpacity;
	private float lastOpacity;

	/**
	 * Creates the controller for one window's content. Must be used on the render thread.
	 *
	 * @param content the window's picture content this controller draws each frame; owned by the
	 *                caller, never replaced or destroyed here
	 */
	public VFXWindowController(final VFXWindowContent content) {
		this.content = content;
	}

	/**
	 * Applies one frame of a window effect: maps the 0..1 geometry into the work area, sets the
	 * clamped opacity when it changed, draws the selected frame into the mapped rectangle through
	 * the window's content and presents the window. A null or closed window is a no-op, and no
	 * OS-move/resize call is ever made here. Must run on the render thread and after the game's own
	 * present.
	 *
	 * @param w          the window to drive; no-op when {@code null} or closed
	 * @param posX       the picture's left edge as a 0..1 fraction of the free work-area space,
	 *                   clamped into {@code [0, 1]}
	 * @param posY       the picture's top edge as a 0..1 fraction of the free work-area space,
	 *                   clamped into {@code [0, 1]}
	 * @param sizeW      the picture's width as a 0..1 fraction of the work area, clamped into
	 *                   {@code [0, 1]}
	 * @param sizeH      the picture's height as a 0..1 fraction of the work area, clamped into
	 *                   {@code [0, 1]}
	 * @param opacity    the animated window opacity, clamped into {@code [0, 1]}
	 * @param frameIndex the sheet frame to draw; any index is wrapped by the content
	 */
	public void apply(final VFXWindow w, final float posX, final float posY, final float sizeW, final float sizeH, final float opacity, final int frameIndex) {
		if (w == null || w.closed()) {
			return;
		}
		final int[] waX = new int[1];
		final int[] waY = new int[1];
		final int[] waW = new int[1];
		final int[] waH = new int[1];
		GLFW.glfwGetWindowPos(w.handle(), waX, waY);
		GLFW.glfwGetWindowSize(w.handle(), waW, waH);
		final float rectW = clampUnit(sizeW);
		final float rectH = clampUnit(sizeH);
		// size_w : size_h are the picture's OWN proportions, not fractions of the monitor's axes:
		// both are taken in one common unit (the smaller work-area side), so size_w == size_h is a
		// square on any screen and a deliberate difference is a stretch - the monitor's aspect never
		// distorts the shape. Fill draws the picture into this pixel box (no aspect fit).
		final float base = Math.max(1.0F, Math.min(waW[0], waH[0]));
		final float picW = rectW * base;
		final float picH = rectH * base;
		final float picX = waX[0] + clampUnit(posX) * Math.max(0.0F, waW[0] - picW);
		final float picY = waY[0] + clampUnit(posY) * Math.max(0.0F, waH[0] - picH);
		// The picture's pixel box is (size_w : size_h) in the common base, so draw it as that box
		// expressed in canvas fractions (picW/waW by picH/waH). Passing the raw size_w/size_h here
		// instead would draw size_w*waW by size_h*waH - the axis-fraction stretch this avoids.
		final float drawW = picW / (float) waW[0];
		final float drawH = picH / (float) waH[0];
		final float rectX = (picX - waX[0]) / (float) waW[0];
		final float rectTop = (picY - waY[0]) / (float) waH[0];
		final float rectY = 1.0F - rectTop - drawH;
		final float clampedOpacity = clampUnit(opacity);
		if (!this.hasOpacity || clampedOpacity != this.lastOpacity) {
			w.setOpacity(clampedOpacity);
			this.hasOpacity = true;
			this.lastOpacity = clampedOpacity;
		}
		this.content.drawFrame(frameIndex, rectX, rectY, drawW, drawH);
		w.present();
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("window picture at ({}, {}) {}x{} in canvas {}x{} at ({}, {})", picX, picY, picW, picH, waW[0], waH[0], waX[0], waY[0]);
		}
	}

	private static float clampUnit(final float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}

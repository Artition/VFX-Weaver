package dev.vfxweaver.client.window;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * One borderless, transparent, click-through canvas window with its own GL context.
 *
 * <p>The window is created hidden, undecorated, non-resizable and always-on-top, sized to the pinned
 * monitor's work area, shown once setup completes, and never resized or moved per frame - all
 * position/size animation is quad geometry inside the canvas. It never takes focus
 * ({@code GLFW_FOCUS_ON_SHOW} is false) and never becomes exclusive fullscreen. Clicks pass through
 * to whatever is beneath it ({@code GLFW_MOUSE_PASSTHROUGH}); without that a full-work-area window
 * would block the desktop.
 *
 * <p>The window owns a GL context of its own (created with no share): on 26.2 the game may run on
 * Vulkan, so a context "shared with the game's backend" does not exist. Creating the window makes
 * its context current long enough to set {@code glfwSwapInterval(0)} and then restores whatever
 * context was current; {@link #present()} saves and restores the same way so the game's context is
 * left current.
 *
 * <p><b>Render thread only.</b> Every method here calls GLFW (and {@link #present()} calls GL), so
 * every method must be called on the render thread; GLFW is not thread-safe and a context may only
 * be current on one thread at a time.
 */
public final class VFXWindow {

	private final long handle;
	private final GLCapabilities caps;
	private boolean closed;

	private VFXWindow(final long handle, final GLCapabilities caps) {
		this.handle = handle;
		this.caps = caps;
	}

	/**
	 * Creates the canvas: hidden, undecorated, non-resizable, always-on-top, transparent and
	 * click-through when the platform allows, sized to the pinned monitor's work area, with its own
	 * GL context ({@code glfwSwapInterval(0)}) and shown last. Must run on the render thread.
	 *
	 * @param id    the registry key this window was opened under; the registry owns the mapping, so
	 *              the window keeps no copy
	 * @param workX the pinned monitor work-area left edge in screen coordinates
	 * @param workY the pinned monitor work-area top edge in screen coordinates
	 * @param workW the pinned monitor work-area width; clamped away from 0 so the canvas is never 0x0
	 * @param workH the pinned monitor work-area height; clamped away from 0 so the canvas is never 0x0
	 * @param title the initial window title
	 * @return the created window, or {@code null} when GLFW could not create it
	 */
	public static VFXWindow create(final String id, final int workX, final int workY, final int workW, final int workH, final String title) {
		final long previous = GLFW.glfwGetCurrentContext();
		GLFW.glfwDefaultWindowHints();
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
		if (VFXWindowPlatform.hasFocusOnShow()) {
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		}
		if (VFXWindowPlatform.hasTransparentFramebuffer()) {
			GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
		}
		final int width = Math.max(1, workW);
		final int height = Math.max(1, workH);
		final long handle = GLFW.glfwCreateWindow(width, height, title, 0L, 0L);
		if (handle == 0L) {
			return null;
		}
		GLFW.glfwSetWindowPos(handle, workX, workY);
		if (VFXWindowPlatform.hasPassthrough()) {
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);
		}
		GLFW.glfwMakeContextCurrent(handle);
		// LWJGL resolves every GL entry point per context: the game loaded them for its own
		// context, and this window's fresh context has none until createCapabilities runs here.
		// Without it the first GL11 call (e.g. glBegin in the draw) aborts the JVM with "No
		// context is current". Keep the game's capabilities and restore them for its context.
		final GLCapabilities previousCaps = GL.getCapabilities();
		final GLCapabilities caps = GL.createCapabilities();
		GLFW.glfwSwapInterval(0);
		GLFW.glfwMakeContextCurrent(previous);
		GL.setCapabilities(previousCaps);
		GLFW.glfwShowWindow(handle);
		return new VFXWindow(handle, caps);
	}

	/**
	 * The raw GLFW window handle, the pointer the later registration and render tasks pass to GLFW.
	 *
	 * @return the native {@code GLFWwindow*} address, or 0 if the window was never created
	 */
	public long handle() {
		return this.handle;
	}

	/**
	 * The LWJGL capabilities loaded for this window's own GL context. Every GL caller must set
	 * these with {@link GL#setCapabilities} while this window's context is current (and restore the
	 * previous ones after), because LWJGL resolves GL entry points per context.
	 *
	 * @return this window's GL capabilities, loaded in {@link #create}
	 */
	GLCapabilities caps() {
		return this.caps;
	}

	/**
	 * Whether the window (and therefore its GL context and its textures) has been destroyed.
	 *
	 * @return true once {@link #close()} ran; render-thread-only like every other method here
	 */
	public boolean closed() {
		return this.closed;
	}

	/**
	 * Sets the window title. Must run on the render thread.
	 *
	 * @param title the new title; {@code null} becomes an empty title
	 */
	public void setTitle(final String title) {
		if (this.closed) {
			return;
		}
		GLFW.glfwSetWindowTitle(this.handle, title == null ? "" : title);
	}

	/**
	 * Sets the window opacity, the one cheap per-frame OS channel. The value is clamped into
	 * {@code [0, 1]} because the datapack's animatable opacity may overshoot. Must run on the render
	 * thread.
	 *
	 * @param opacity the desired opacity, clamped into {@code [0, 1]}
	 */
	public void setOpacity(final float opacity) {
		if (this.closed) {
			return;
		}
		final float clamped = Math.max(0.0F, Math.min(1.0F, opacity));
		GLFW.glfwSetWindowOpacity(this.handle, clamped);
	}

	/**
	 * Presents the aux window: makes its context current, swaps its buffers, then restores the
	 * context that was current so the game keeps its own. Must run on the render thread and after
	 * the game's own present.
	 */
	public void present() {
		if (this.closed) {
			return;
		}
		final long previous = GLFW.glfwGetCurrentContext();
		GLFW.glfwMakeContextCurrent(this.handle);
		GLFW.glfwSwapBuffers(this.handle);
		GLFW.glfwMakeContextCurrent(previous);
	}

	/**
	 * Destroys the OS window and its GL context. Idempotent; further calls are no-ops. Must run on
	 * the render thread.
	 */
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		GLFW.glfwDestroyWindow(this.handle);
	}
}

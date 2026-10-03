package dev.vfxweaver.client.window;

import org.lwjgl.glfw.GLFW;

/**
 * The per-node platform capability gate for the aux windows.
 *
 * <p>Each node ships its own GLFW bundle, so every OS-window feature the subsystem needs is probed
 * here instead of assumed; an unsupported feature is a no-op, never an error or frame damage. All
 * probes must be called on the render thread, because GLFW is not thread-safe.
 */
public final class VFXWindowPlatform {

	private VFXWindowPlatform() {
	}

	/**
	 * Whether the running session is Wayland, where the compositor owns window placement and the
	 * absolute-position path is only best-effort.
	 *
	 * @return true when {@code glfwGetPlatform()} reports Wayland; when the binding cannot answer,
	 *         the {@code XDG_SESSION_TYPE} environment variable is consulted instead
	 */
	public static boolean wayland() {
		try {
			return GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND;
		} catch (final Throwable ignored) {
			return "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
		}
	}

	/**
	 * Whether this node can make a window click-through ({@code GLFW_MOUSE_PASSTHROUGH}).
	 *
	 * @return true when the attribute is available, false on a binding that predates it
	 */
	public static boolean hasPassthrough() {
		try {
			final long handle = GLFW.glfwGetCurrentContext();
			return handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH) >= 0;
		} catch (final Throwable ignored) {
			return false;
		}
	}

	/**
	 * Whether this node can give a window a transparent framebuffer
	 * ({@code GLFW_TRANSPARENT_FRAMEBUFFER}).
	 *
	 * @return true when the attribute is available, false on a binding that predates it
	 */
	public static boolean hasTransparentFramebuffer() {
		try {
			final long handle = GLFW.glfwGetCurrentContext();
			return handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_TRANSPARENT_FRAMEBUFFER) >= 0;
		} catch (final Throwable ignored) {
			return false;
		}
	}

	/**
	 * Whether this node can keep focus away from a window shown at creation
	 * ({@code GLFW_FOCUS_ON_SHOW}), the footgun that would otherwise steal the game's focus.
	 *
	 * @return true when the attribute is available, false on a binding that predates it
	 */
	public static boolean hasFocusOnShow() {
		try {
			final long handle = GLFW.glfwGetCurrentContext();
			return handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUS_ON_SHOW) >= 0;
		} catch (final Throwable ignored) {
			return false;
		}
	}
}

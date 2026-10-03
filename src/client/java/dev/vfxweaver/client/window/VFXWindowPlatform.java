package dev.vfxweaver.client.window;

import org.lwjgl.glfw.GLFW;

/**
 * The per-node platform capability gate for the aux windows.
 *
 * <p>Each node ships its own GLFW bundle, so every OS-window feature the subsystem needs is probed
 * here instead of assumed; an unsupported feature is a no-op, never an error or frame damage. An
 * attribute is gated on the bundled GLFW version, because {@code glfwGetWindowAttrib} returns 0 for
 * an attribute the running GLFW does not know, which is indistinguishable from a supported
 * attribute. All probes must be called on the render thread, because GLFW is not thread-safe.
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
	 * Whether this node can make a window click-through ({@code GLFW_MOUSE_PASSTHROUGH}), which the
	 * bundled GLFW provides from 3.4.
	 *
	 * @return true when the bundled GLFW runtime is at least 3.4; false when it is older or its
	 *         version cannot be read
	 */
	public static boolean hasPassthrough() {
		try {
			final int[] major = new int[1];
			final int[] minor = new int[1];
			GLFW.glfwGetVersion(major, minor, new int[1]);
			return major[0] > 3 || (major[0] == 3 && minor[0] >= 4);
		} catch (final Throwable ignored) {
			return false;
		}
	}

	/**
	 * Whether this node can give a window a transparent framebuffer
	 * ({@code GLFW_TRANSPARENT_FRAMEBUFFER}), which the bundled GLFW provides from 3.3.
	 *
	 * @return true when the bundled GLFW runtime is at least 3.3; false when it is older or its
	 *         version cannot be read
	 */
	public static boolean hasTransparentFramebuffer() {
		try {
			final int[] major = new int[1];
			final int[] minor = new int[1];
			GLFW.glfwGetVersion(major, minor, new int[1]);
			return major[0] > 3 || (major[0] == 3 && minor[0] >= 3);
		} catch (final Throwable ignored) {
			return false;
		}
	}

	/**
	 * Whether this node can keep focus away from a window shown at creation
	 * ({@code GLFW_FOCUS_ON_SHOW}), the footgun that would otherwise steal the game's focus, which
	 * the bundled GLFW provides from 3.3.
	 *
	 * @return true when the bundled GLFW runtime is at least 3.3; false when it is older or its
	 *         version cannot be read
	 */
	public static boolean hasFocusOnShow() {
		try {
			final int[] major = new int[1];
			final int[] minor = new int[1];
			GLFW.glfwGetVersion(major, minor, new int[1]);
			return major[0] > 3 || (major[0] == 3 && minor[0] >= 3);
		} catch (final Throwable ignored) {
			return false;
		}
	}
}

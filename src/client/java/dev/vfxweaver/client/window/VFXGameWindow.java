package dev.vfxweaver.client.window;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The driver for the reserved window id {@code "0"}: the Minecraft game window itself.
 *
 * <p><b>Why its own class.</b> An aux window is a borderless GL canvas with a context of its own
 * ({@link VFXWindow}); the game window is none of that. {@code javap} against the client jar of every
 * node (26.1.2, 26.2 and 1.21.11) shows {@code com.mojang.blaze3d.platform.Window} exposes
 * {@code handle()}, {@code isFullscreen()}, {@code setWindowed(int, int)}, {@code getX()}/{@code getY()},
 * {@code getWidth()}/{@code getHeight()} and {@code getScreenWidth()}/{@code getScreenHeight()} - and
 * <b>no position setter</b> - and that the class itself only ever calls {@code glfwSetWindowMonitor}
 * plus one {@code glfwGetWindowPos}, never {@code glfwSetWindowPos} or {@code glfwSetWindowSize}. So
 * the size goes through the game's own {@code setWindowed} and the position through raw GLFW on
 * {@code handle()}.
 *
 * <p><b>Leaving fullscreen.</b> {@code Window.setWindowed(w, h)} is the game's own path out of
 * exclusive fullscreen: its bytecode writes {@code windowedWidth}/{@code windowedHeight}, clears the
 * {@code fullscreen} flag and calls the private {@code setMode()}, whose windowed branch calls
 * {@code glfwSetWindowMonitor(handle, 0L, x, y, w, h, GLFW_DONT_CARE)} (on 26.x through
 * {@code allowedWindowMinSize}, i.e. {@code max(1, n)}, on 1.21.11 stored raw). Going through it keeps
 * Minecraft's own fullscreen flag and cached windowed size truthful - a bare
 * {@code glfwSetWindowMonitor} would put the window back in windowed mode while the game still
 * believed it was fullscreen, and the next F11 toggle would jump. {@code setWindowed} places the
 * window at the position Minecraft last remembered, so the requested position is pushed after it.
 *
 * <p><b>Pinned work area.</b> The four 0..1 params map with the same work area the aux controller
 * reads, but <b>position is the window's centre</b>, not a free-space corner: {@code pos_x}/{@code pos_y}
 * place the centre at that fraction of the pinned work area and {@code size_w}/{@code size_h} grow and
 * shrink evenly about it, so a resize never pins a corner. {@link #capture()} pins the rect into a
 * final field, because a work area read from the game window's own rect would feed back into itself:
 * {@code size_w = 0.5} would halve the window, and the window again on the next frame.
 *
 * <p><b>Never 0x0.</b> The size is clamped into {@link #MIN_WIDTH}x{@link #MIN_HEIGHT} and into the
 * work area, so a zero, negative or tiny request still leaves a window the game can render into.
 *
 * <p><b>An ordinary window.</b> Nothing here creates, destroys, resizes through GLFW,
 * monitor-switches, shows, focuses, decorates, floats, fades or retitles the game window: it stays a
 * normal decorated Minecraft window and only its size and its screen position are driven. There is no
 * picture, no OS title and no opacity for id {@code "0"} - the game window has no picture surface to
 * draw into and its own title and decorations are the player's.
 *
 * <p><b>A stop leaves it where it is.</b> Dropping the id-0 binding moves nothing: the player keeps the
 * size and position the effect left and can resize or move the window themselves, because a jump back
 * to the pre-effect rect reads as a bug.
 *
 * <p><b>Render thread only, and after the game's own present.</b> {@link #apply} calls GLFW and the
 * game's own window mode, GLFW is not thread-safe and the game's window belongs to the render thread,
 * so it must run on the render thread and after the game's own present (see {@code MinecraftMixin}),
 * which is where {@link VFXWindowManager} drives it from.
 */
public final class VFXGameWindow {

	/** The smallest driven width, below which the game's own GUI stops being usable. */
	private static final int MIN_WIDTH = 320;

	/** The smallest driven height, below which the game's own GUI stops being usable. */
	private static final int MIN_HEIGHT = 240;

	private final int[] area;

	private VFXGameWindow(final int[] area) {
		this.area = area;
	}

	/**
	 * Pins the monitor work area of the monitor the game window is on, for the whole life of one
	 * id-0 binding. Must run on the render thread.
	 *
	 * @return the driver for this binding; every {@link #apply} maps into the same work area
	 */
	public static VFXGameWindow capture() {
		return new VFXGameWindow(workArea());
	}

	/**
	 * Applies one frame of the driving effect: maps the 0..1 geometry into the pinned work area,
	 * leaves exclusive fullscreen through the game's own path when the game window is fullscreen,
	 * then resizes and moves the window when it is not already at the target size and position. A
	 * steady frame makes no GLFW call at all. Must run on the render thread and after the game's own
	 * present.
	 *
	 * @param posX  the window's <b>centre</b> x as a 0..1 fraction of the work area, clamped into
	 *              {@code [0, 1]}
	 * @param posY  the window's <b>centre</b> y as a 0..1 fraction of the work area, clamped into
	 *              {@code [0, 1]}
	 * @param sizeW the window's width as a 0..1 fraction of the work area, clamped into {@code [0, 1]}
	 *              and then into {@link #MIN_WIDTH} and the work area; the size is split evenly about
	 *              the centre, so a resize is symmetric
	 * @param sizeH the window's height as a 0..1 fraction of the work area, clamped into {@code [0, 1]}
	 *              and then into {@link #MIN_HEIGHT} and the work area; the size is split evenly about
	 *              the centre, so a resize is symmetric
	 */
	public void apply(final float posX, final float posY, final float sizeW, final float sizeH) {
		final Window window = Minecraft.getInstance().getWindow();
		final int width = size(clampUnit(sizeW), this.area[2], MIN_WIDTH);
		final int height = size(clampUnit(sizeH), this.area[3], MIN_HEIGHT);
		final int x = center(this.area[0], this.area[2], clampUnit(posX), width);
		final int y = center(this.area[1], this.area[3], clampUnit(posY), height);
		if (window.isFullscreen()) {
			window.setWindowed(width, height);
		} else if (window.getWidth() != width || window.getHeight() != height) {
			GLFW.glfwSetWindowSize(window.handle(), width, height);
		}
		if (window.getX() != x || window.getY() != y) {
			GLFW.glfwSetWindowPos(window.handle(), x, y);
		}
	}

	/**
	 * The work area of the monitor the game window is on, as {@code [x, y, width, height]}, falling
	 * back to the game window's own logical rect when no monitor is found (or the platform reports
	 * none), so the mapping base is never empty.
	 *
	 * @return the work area as {@code [x, y, width, height]}
	 */
	private static int[] workArea() {
		final Window window = Minecraft.getInstance().getWindow();
		final int[] x = new int[1];
		final int[] y = new int[1];
		final int[] width = new int[1];
		final int[] height = new int[1];
		final long handle = monitorHandle(window.findBestMonitor());
		if (handle != 0L) {
			GLFW.glfwGetMonitorWorkarea(handle, x, y, width, height);
		}
		if (width[0] <= 0 || height[0] <= 0) {
			x[0] = window.getX();
			y[0] = window.getY();
			width[0] = Math.max(1, window.getScreenWidth());
			height[0] = Math.max(1, window.getScreenHeight());
		}
		return new int[]{x[0], y[0], width[0], height[0]};
	}

	/**
	 * The raw GLFW monitor of the given monitor, or the primary monitor when the game window is on
	 * none. {@code Monitor} is a plain class with {@code getMonitor()} on {@code <26.2} and a record
	 * with {@code monitor()} on {@code >=26.2}, so only the accessor differs.
	 *
	 * @param monitor the monitor the game window is on, or {@code null} when the game found none
	 * @return the {@code GLFWmonitor*} address, 0 only when the platform reports no monitor at all
	 */
	private static long monitorHandle(final @Nullable Monitor monitor) {
		//? if <26.2 {
		return monitor == null ? GLFW.glfwGetPrimaryMonitor() : monitor.getMonitor();
		//?} else {
		/*return monitor == null ? GLFW.glfwGetPrimaryMonitor() : monitor.monitor();
		*///?}
	}

	/**
	 * The window size for one 0..1 work-area fraction, never 0: the fraction is rounded to pixels and
	 * clamped up into {@code minimum} and down into the work area, so a zero or tiny request is still
	 * a usable window and a full request never leaves the work area.
	 *
	 * @param fraction the clamped 0..1 fraction of the work area
	 * @param extent   the work area's extent in pixels along the same axis
	 * @param minimum  the smallest size allowed on this axis
	 * @return the size in pixels, at least 1
	 */
	private static int size(final float fraction, final int extent, final int minimum) {
		return Math.min(extent, Math.max(minimum, Math.round(fraction * extent)));
	}

	/**
	 * The window origin for one axis so the window's <b>centre</b> sits at the requested 0..1
	 * fraction of the work area: the size is split evenly about that centre, so a resize grows and
	 * shrinks symmetrically instead of pinning the top-left corner. The result is clamped so the
	 * window never leaves the work area.
	 *
	 * @param origin   the work area's origin in pixels along this axis
	 * @param extent   the work area's extent in pixels along this axis
	 * @param fraction the clamped 0..1 centre of the window within the work area
	 * @param size     the window size in pixels along this axis, already clamped into the work area
	 * @return the window origin in pixels along this axis
	 */
	private static int center(final int origin, final int extent, final float fraction, final int size) {
		final int centre = origin + Math.round(fraction * extent);
		return Math.max(origin, Math.min(origin + extent - size, centre - size / 2));
	}

	private static float clampUnit(final float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
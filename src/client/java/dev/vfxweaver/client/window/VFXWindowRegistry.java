package dev.vfxweaver.client.window;

import dev.vfxweaver.util.VFXLog;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry of aux windows, keyed by name.
 *
 * <p>The map is bounded by {@link #MAX_WINDOWS}: opening over the cap warns once through
 * {@link VFXLog} and drops the new window rather than evicting a running one. Stopped windows stay
 * in the map until {@link #prune()}, so {@link #get(String)} still reports a closed name (the
 * window's own methods are no-ops once closed) and a controller can no-op on it;
 * {@link #close(String)} on a missing or already stopped name warns once and no-ops.
 *
 * <p><b>The live-duplicate rule.</b> Opening a name that already holds a <b>running</b> window is
 * first-creator-wins: {@link #open} warns once and returns {@code null} without touching the
 * running window. This is the rule chosen from
 * {@code docs/superpowers/specs/2026-10-03-custom-windows-design.md} line 133 ("Two creators on
 * one name - the first wins, the second warns-once and no-ops"): the registry cannot tell two
 * creators apart, so a running window must never be replaced under its owner. The neighbouring
 * "same id - replace (close + recreate)" rule (line 132) is the <b>same</b> creator stopping and
 * restarting, which the lifecycle drives as {@link #close(String)} then {@link #open}; a stopped
 * same-id entry still in the map is dropped by {@link #open} before it recreates, so it is
 * replaced too.
 *
 * <p><b>Render thread only.</b> The held windows drive GLFW, which is not thread-safe, so every
 * method here must be called on the render thread and the map is a plain {@link HashMap} with no
 * extra synchronisation.
 */
public final class VFXWindowRegistry {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/window");

	/** The cap on the number of live windows the registry may hold at once. */
	private static final int MAX_WINDOWS = 8;

	private static final VFXWindowRegistry INSTANCE = new VFXWindowRegistry();

	private final Map<String, VFXWindow> windows = new HashMap<>();

	private VFXWindowRegistry() {
	}

	/**
	 * The one registry instance. Must be used on the render thread.
	 *
	 * @return the process-wide registry
	 */
	public static VFXWindowRegistry get() {
		return INSTANCE;
	}

	/**
	 * Opens a window under {@code id}, or no-ops when it may not own the name. A name already held
	 * by a running window is first-creator-wins: this call warns once and returns {@code null}
	 * without touching the running window. A name whose window has stopped is replaced (dropped and
	 * recreated). Over the {@link #MAX_WINDOWS} cap, or when GLFW cannot create the window, the
	 * call warns once and returns {@code null}. The work area is passed straight through to
	 * {@link VFXWindow#create(String, int, int, int, int, String)}, which clamps it away from 0x0.
	 * Must run on the render thread.
	 *
	 * @param id    the window name; the registry owns the mapping
	 * @param workX the pinned monitor work-area left edge in screen coordinates
	 * @param workY the pinned monitor work-area top edge in screen coordinates
	 * @param workW the pinned monitor work-area width
	 * @param workH the pinned monitor work-area height
	 * @param title the initial window title
	 * @return the created or recreated window, or {@code null} when the open was dropped or GLFW
	 *         failed
	 */
	public @Nullable VFXWindow open(final String id, final int workX, final int workY, final int workW, final int workH, final String title) {
		final VFXWindow existing = this.windows.get(id);
		if (existing != null && !existing.closed()) {
			VFXLog.warnOnce(LOGGER, "window:duplicate:" + id, "window '{}' is already open; the first creator keeps it", id);
			return null;
		}
		if (existing != null) {
			this.windows.remove(id);
		}
		prune();
		if (this.windows.size() >= MAX_WINDOWS) {
			VFXLog.warnOnce(LOGGER, "window:cap", "window cap ({}) reached; dropping window '{}'", MAX_WINDOWS, id);
			return null;
		}
		final VFXWindow window = VFXWindow.create(id, workX, workY, workW, workH, title);
		if (window == null) {
			VFXLog.warnOnce(LOGGER, "window:create:" + id, "could not create window '{}'", id);
			return null;
		}
		this.windows.put(id, window);
		return window;
	}

	/**
	 * The window registered under {@code id}. A stopped window is still returned until
	 * {@link #prune()} sweeps it; the caller may check {@link VFXWindow#closed()}, and the window's
	 * own methods are no-ops once closed.
	 *
	 * @param id the window name
	 * @return the registered window, or {@code null} when the name is unknown
	 */
	public @Nullable VFXWindow get(final String id) {
		return this.windows.get(id);
	}

	/**
	 * Destroys the running window registered under {@code id} and leaves its now-stopped entry for
	 * {@link #prune()}. A name that is unknown or already stopped warns once and is left alone.
	 * Must run on the render thread.
	 *
	 * @param id the window name
	 */
	public void close(final String id) {
		final VFXWindow window = this.windows.get(id);
		if (window == null || window.closed()) {
			VFXLog.warnOnce(LOGGER, "window:missing:" + id, "no open window '{}' to close", id);
			return;
		}
		window.close();
	}

	/**
	 * Drops every stopped window from the map, the sweep run on a datapack reload or a client
	 * dispose. A running window is untouched; it is destroyed by {@link #close(String)} when its
	 * creator stops. Must run on the render thread.
	 */
	public void prune() {
		this.windows.values().removeIf(VFXWindow::closed);
	}
}

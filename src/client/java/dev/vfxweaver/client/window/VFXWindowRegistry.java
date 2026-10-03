package dev.vfxweaver.client.window;

import dev.vfxweaver.util.VFXLog;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry of live aux windows, keyed by name.
 *
 * <p>The map is bounded by {@link #MAX_WINDOWS}: opening over the cap warns once through
 * {@link VFXLog} and drops the new window. Ownership of a name follows two rules. A name that
 * already has a <b>running</b> window is first-creator-wins - the second creator warns once and
 * no-ops, so a live window is never replaced under its owner. A name whose window has stopped
 * (its {@link VFXWindow#closed()} is true) is replaced: the stale entry is dropped and a fresh
 * window is created. Closing a name that is missing or already closed warns once and no-ops;
 * closing a running window destroys it but leaves a stopped entry behind, so a later open on the
 * same name takes the replace path.
 *
 * <p>{@link #closeAll()} is the teardown (datapack reload / client dispose): it closes every
 * window it owns and clears the map, so no GLFW handle - and with it no GL context or texture -
 * leaks.
 *
 * <p><b>Render thread only.</b> The held windows drive GLFW, which is not thread-safe, so every
 * method here must be called on the render thread and the map is a plain {@link HashMap} with no
 * extra synchronisation.
 */
public final class VFXWindowRegistry {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/window");

	/** The cap on the number of windows the registry may hold at once. */
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
	 * Opens a window under {@code id}, or no-ops when it may not own the name. A name that is
	 * already held by a running window is first-creator-wins: this call warns once and returns
	 * {@code null} without touching the running window. A name whose window has stopped is
	 * replaced. Over the {@link #MAX_WINDOWS} cap, or when GLFW cannot create the window, the call
	 * warns once and returns {@code null}. The work area is passed straight through to
	 * {@link VFXWindow#create(String, int, int, int, int, String)}, which clamps it away from 0x0.
	 * Must run on the render thread.
	 *
	 * @param id    the window name; the registry owns the mapping
	 * @param workX the pinned monitor work-area left edge in screen coordinates
	 * @param workY the pinned monitor work-area top edge in screen coordinates
	 * @param workW the pinned monitor work-area width
	 * @param workH the pinned monitor work-area height
	 * @param title the initial window title
	 * @return the created window, the running window's replacement, or {@code null} when the open
	 *         was dropped or GLFW failed
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
		pruneClosed();
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
	 * The window registered under {@code id}, live or stopped.
	 *
	 * @param id the window name
	 * @return the registered window, or {@code null} when the name is unknown
	 */
	public @Nullable VFXWindow get(final String id) {
		return this.windows.get(id);
	}

	/**
	 * Destroys the running window registered under {@code id}. A name that is unknown or already
	 * stopped warns once and is left alone. Must run on the render thread.
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
	 * Closes every window the registry owns and clears the map, the teardown for a datapack reload
	 * or a client dispose. Must run on the render thread.
	 */
	public void closeAll() {
		for (final VFXWindow window : this.windows.values()) {
			window.close();
		}
		this.windows.clear();
	}

	private void pruneClosed() {
		this.windows.values().removeIf(VFXWindow::closed);
	}
}

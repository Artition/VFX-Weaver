package dev.vfxweaver.client.window;

import com.mojang.blaze3d.platform.Window;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXWindowSpec;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * The lifecycle bridge between the aux-window effects and the registry/controller: it owns the
 * per-window {@link VFXWindowContent} and {@link VFXWindowController} (the registry owns only the
 * window itself) and drives them from the running effects.
 *
 * <p><b>Open / close, driven by the active set.</b> {@link #reconcile(List)} is called once per
 * frame from {@code VFXEffectManager.update()} and directly from the stop path (so a world exit,
 * where {@code update()} does not run because there is no level, still closes the windows): for
 * every active {@code window_create} that is not fading out (a stopped creator must not reopen its
 * window) and whose definition carries a {@link VFXWindowSpec} it opens the registry window under
 * the spec's {@code id} (pinned to the game window's monitor work area, titled by the animated
 * {@code title_index} and loaded with the spec's texture/{@code frames}), or binds the reserved id to a {@link VFXGameWindow}; it then closes every window
 * whose creating effect is no longer active and reaps the stopped entries through
 * {@link VFXWindowRegistry#prune()}. Because it reconciles against the live set, every removal path
 * (stop, fade-out expiry, {@code stopAll}, a replay seek) closes the window without each path having
 * to know about windows.
 *
* <p><b>Per-frame drive.</b> {@link #apply()} is called once per frame after the game's own present
 * (see {@code MinecraftMixin}). For each bound window it walks the live {@code window_create} and
 * {@code window_control} effects for that name in active order (the creator first, then each control
 * last-writer-wins), reads the animated {@code pos_x}/{@code pos_y}/{@code size_w}/{@code size_h}/
 * {@code opacity}/frame values with {@link VFXActiveEffect#getParam(String, float)} and hands them to
 * the window's {@link VFXWindowController#apply. It returns immediately when no window is bound, so a
 * session with no window effect leaves the game frame untouched.
 *
 * <p><b>The reserved id {@code "0"}.</b> A window effect whose id is {@link #GAME_WINDOW_ID} does not
 * open an aux window: the binding is a {@link VFXGameWindow}, which drives the Minecraft game window's
 * own rect instead of a picture inside a canvas, and the registry is never asked to open or close that
 * name. Only an effect that actually declares one of the four geometry params (in its definition or as
 * a live override) drives it, so a title-only id-0 effect leaves the window alone. Dropping the binding
 * moves nothing - the game window stays where the effect left it.
 *
 * <p><b>Render thread only.</b> Opening and closing call GLFW, and {@link #apply()} reaches GLFW/GL
 * through the controller, so every method here must run on the render thread; GLFW is not thread-safe
 * and a GL context may only be current on one thread at a time. The held content and controller are
 * never shared across threads.
 */
public final class VFXWindowManager {

	/**
	 * The reserved window name: {@code window_create} / {@code window_control} with this id drive the
	 * Minecraft game window itself through {@link VFXGameWindow} instead of opening an aux window.
	 */
	public static final String GAME_WINDOW_ID = "0";

	/** The geometry params that opt an id-0 effect into driving the game window. */
	private static final List<String> GEOMETRY_PARAMS = List.of("pos_x", "pos_y", "size_w", "size_h");

	private static final VFXWindowManager INSTANCE = new VFXWindowManager();

	private final Map<String, Binding> bindings = new HashMap<>();

	private VFXWindowManager() {
	}

	/**
	 * The one manager instance. Must be used on the render thread.
	 *
	 * @return the process-wide manager
	 */
	public static VFXWindowManager get() {
		return INSTANCE;
	}

	/**
	 * Opens a window for every active {@code window_create} that has none yet and closes every
	 * window whose creating effect has stopped or expired, then reaps the stopped registry entries.
	 * Must run on the render thread.
	 *
	 * @param active the current active effects (the creator owns the name while it is in the list)
	 */
	public void reconcile(final List<VFXActiveEffect> active) {
		if (FlashbackCompat.isReplayActive()) {
			// A Flashback replay carries no window effects (they are never recorded), and a replay
			// must not show client-side OS picture windows at all: close any open aux window and do
			// not open new ones. This also covers a window spawned as a child of a replayed
			// collection, which the recording filter cannot see.
			final Iterator<Map.Entry<String, Binding>> replayIterator = this.bindings.entrySet().iterator();
			while (replayIterator.hasNext()) {
				final String name = replayIterator.next().getKey();
				if (!GAME_WINDOW_ID.equals(name)) {
					VFXWindowRegistry.get().close(name);
				}
				replayIterator.remove();
			}
			VFXWindowRegistry.get().prune();
			return;
		}
		final Map<String, VFXActiveEffect> owners = new HashMap<>();
		for (final VFXActiveEffect effect : active) {
			if (effect.getType() != VFXEffectType.WINDOW_CREATE || effect.isFadingOut()) {
				continue;
			}
			final VFXWindowSpec spec = spec(effect.getId());
			if (spec != null) {
				owners.putIfAbsent(spec.id(), effect);
			}
		}
		for (final Map.Entry<String, VFXActiveEffect> entry : owners.entrySet()) {
			if (!this.bindings.containsKey(entry.getKey())) {
				open(entry.getKey(), entry.getValue());
			}
		}
		final Iterator<Map.Entry<String, Binding>> iterator = this.bindings.entrySet().iterator();
		while (iterator.hasNext()) {
			final String name = iterator.next().getKey();
			if (!owners.containsKey(name)) {
				if (!GAME_WINDOW_ID.equals(name)) {
					VFXWindowRegistry.get().close(name);
				}
				iterator.remove();
			}
		}
		VFXWindowRegistry.get().prune();
	}

	private void open(final String name, final VFXActiveEffect effect) {
		final VFXWindowSpec spec = spec(effect.getId());
		if (spec == null) {
			return;
		}
		if (GAME_WINDOW_ID.equals(name)) {
			this.bindings.put(name, new Binding(null, null, null, VFXGameWindow.capture(), null));
			return;
		}
		final int[] area = workArea();
		@Nullable final String title = spec.titleAt(effect.getParam("title_index", 0.0F));
		final VFXWindow window = VFXWindowRegistry.get().open(name, area[0], area[1], area[2], area[3], title != null ? title : name);
		if (window == null) {
			return;
		}
		final VFXWindowContent content = new VFXWindowContent(window);
		if (spec.texture() != null) {
			content.load(spec.texture(), Math.max(1, (int) effect.getParam("frames", 1.0F)));
		}
		final VFXWindowController controller = new VFXWindowController(content);
		this.bindings.put(name, new Binding(window, content, controller, null, title));
	}

	/**
	 * Drives every bound window for the current frame: reconciles the bindings against the live set
	 * (closing a window whose creator stopped, world exit included), then reads the live
	 * creator/controller parameters
	 * (creator first, each control last-writer-wins) and applies them through the window's
	 * controller, then retitles the window when the animated title changed. The reserved
	 * {@link #GAME_WINDOW_ID} instead hands the geometry to the binding's {@link VFXGameWindow}, and
	 * only when one of the driving effects declares a geometry. A no-op when no window is
	 * bound. Must run on the render thread and after the game's own present.
	 */
	public void apply() {
		final List<VFXActiveEffect> active = VFXEffectManager.get().getActive();
		reconcile(active);
		if (this.bindings.isEmpty()) {
			return;
		}
		for (final Map.Entry<String, Binding> entry : this.bindings.entrySet()) {
			final String name = entry.getKey();
			final Binding binding = entry.getValue();
			float posX = 0.0F;
			float posY = 0.0F;
			float sizeW = 1.0F;
			float sizeH = 1.0F;
			float opacity = 1.0F;
			long timeTicks = 0L;
			float frameTime = 0.0F;
			boolean geometry = false;
			@Nullable String title = binding.title();
			for (final VFXActiveEffect effect : active) {
				if (effect.getType() != VFXEffectType.WINDOW_CREATE && effect.getType() != VFXEffectType.WINDOW_CONTROL) {
					continue;
				}
				final VFXWindowSpec spec = spec(effect.getId());
				if (spec == null || !spec.id().equals(name)) {
					continue;
				}
				geometry |= declaresGeometry(effect);
				posX = effect.getParam("pos_x", posX);
				posY = effect.getParam("pos_y", posY);
				sizeW = effect.getParam("size_w", sizeW);
				sizeH = effect.getParam("size_h", sizeH);
				opacity = effect.getParam("opacity", opacity);
				timeTicks = (long) effect.getElapsed();
				frameTime = effect.getParam("frame_time", 0.0F);
				@Nullable final String effectTitle = spec.titleAt(effect.getParam("title_index", 0.0F));
				if (effectTitle != null) {
					title = effectTitle;
				}
			}
			if (GAME_WINDOW_ID.equals(name)) {
				if (geometry && binding.gameWindow() != null) {
					binding.gameWindow().apply(posX, posY, sizeW, sizeH);
				}
				continue;
			}
			if (!Objects.equals(title, binding.title())) {
				binding.window().setTitle(title);
				entry.setValue(new Binding(binding.window(), binding.content(), binding.controller(), null, title));
			}
			binding.controller().apply(binding.window(), posX, posY, sizeW, sizeH, opacity, timeTicks, frameTime);
		}
	}

	/**
	 * Closes and drops every bound window and reaps the stopped registry entries, run on a client
	 * dispose so no aux window or texture outlives the game session. The reserved
	 * {@link #GAME_WINDOW_ID} owns no OS window, so it is only dropped. Must run on the render thread.
	 */
	public void closeAll() {
		for (final String name : this.bindings.keySet()) {
			if (GAME_WINDOW_ID.equals(name)) {
				continue;
			}
			VFXWindowRegistry.get().close(name);
		}
		this.bindings.clear();
		VFXWindowRegistry.get().prune();
	}

	/**
	 * Whether the effect declares or live-overrides one of the four geometry params, which is what opts
	 * an id-0 effect into moving the game window: an effect that only renames or fades something has
	 * no business resizing the player's window.
	 *
	 * @param effect the driving {@code window_create} or {@code window_control}
	 * @return true when any of {@code pos_x}/{@code pos_y}/{@code size_w}/{@code size_h} is present
	 */
	private static boolean declaresGeometry(final VFXActiveEffect effect) {
		for (final String param : GEOMETRY_PARAMS) {
			if (effect.getTimeline().getValues().containsKey(param) || effect.getTimeline().getOverrideNames().contains(param)) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable VFXWindowSpec spec(final Identifier id) {
		final VFXDefinition definition = VFXDefinitionManager.get().get(id);
		return definition == null ? null : definition.getWindow();
	}

	/**
	 * The work area of the monitor the game window is on, so an aux canvas covers that whole screen
	 * and not just the game window. {@code glfwGetWindowMonitor} only reports a monitor for a
	 * fullscreen window - for a windowed one it returns 0 - so the monitor is then found from the
	 * window's centre; only if no monitor is found at all does it fall back to the game window's
	 * logical rect (never 0x0). This matters because the {@code "0"} effect can shrink the game
	 * window: sizing the canvas from the game window would shrink the canvas with it and cap how far
	 * a picture can travel. Reads the game window; it never mutates it.
	 *
	 * @return the work area as {@code [x, y, width, height]}
	 */
	private static int[] workArea() {
		final Window gameWindow = Minecraft.getInstance().getWindow();
		final int[] x = new int[1];
		final int[] y = new int[1];
		final int[] width = new int[1];
		final int[] height = new int[1];
		long monitor = GLFW.glfwGetWindowMonitor(gameWindow.handle());
		if (monitor == 0L) {
			monitor = monitorAt(
				gameWindow.getX() + Math.max(1, gameWindow.getScreenWidth()) / 2,
				gameWindow.getY() + Math.max(1, gameWindow.getScreenHeight()) / 2);
		}
		if (monitor != 0L) {
			GLFW.glfwGetMonitorWorkarea(monitor, x, y, width, height);
		}
		if (width[0] <= 0 || height[0] <= 0) {
			x[0] = gameWindow.getX();
			y[0] = gameWindow.getY();
			width[0] = Math.max(1, gameWindow.getScreenWidth());
			height[0] = Math.max(1, gameWindow.getScreenHeight());
		}
		return new int[]{x[0], y[0], width[0], height[0]};
	}

	/**
	 * The monitor whose full-screen rect contains the given point, or the primary monitor when none
	 * does. GLFW owns the returned monitor array, so it is read and not freed.
	 *
	 * @param px point x in virtual-screen coordinates
	 * @param py point y in virtual-screen coordinates
	 * @return a monitor handle, never 0 on a normal desktop
	 */
	private static long monitorAt(final int px, final int py) {
		final PointerBuffer monitors = GLFW.glfwGetMonitors();
		if (monitors != null) {
			for (int i = 0; i < monitors.limit(); i++) {
				final long candidate = monitors.get(i);
				final int[] mx = new int[1];
				final int[] my = new int[1];
				GLFW.glfwGetMonitorPos(candidate, mx, my);
				final GLFWVidMode mode = GLFW.glfwGetVideoMode(candidate);
				if (mode != null
					&& px >= mx[0] && px < mx[0] + mode.width()
					&& py >= my[0] && py < my[0] + mode.height()) {
					return candidate;
				}
			}
		}
		return GLFW.glfwGetPrimaryMonitor();
	}

	/**
	 * One window's wiring: the window (owned by the registry), its picture content and its
	 * controller, plus the last title set so a steady frame makes no retitle call. For the reserved
	 * {@link #GAME_WINDOW_ID} the aux trio is {@code null} and {@code gameWindow} drives the Minecraft
	 * window instead - a binding is either one or the other, never both.
	 */
	private record Binding(@Nullable VFXWindow window, @Nullable VFXWindowContent content, @Nullable VFXWindowController controller, @Nullable VFXGameWindow gameWindow, @Nullable String title) {
	}
}

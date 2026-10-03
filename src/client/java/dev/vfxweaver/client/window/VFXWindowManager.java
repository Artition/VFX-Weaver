package dev.vfxweaver.client.window;

import com.mojang.blaze3d.platform.Window;
import dev.vfxweaver.client.effect.VFXEffectManager;
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
import org.lwjgl.glfw.GLFW;

/**
 * The lifecycle bridge between the aux-window effects and the registry/controller: it owns the
 * per-window {@link VFXWindowContent} and {@link VFXWindowController} (the registry owns only the
 * window itself) and drives them from the running effects.
 *
 * <p><b>Open / close, driven by the active set.</b> {@link #reconcile(List)} is called once per
 * frame from {@code VFXEffectManager.update()}, the one point where a play, a stop and an expiry all
 * converge. For every active {@code window_create} whose definition carries a {@link VFXWindowSpec}
 * it opens the registry window under the spec's {@code id} (pinned to the game window's monitor work
 * area, titled by the animated {@code title_index} and loaded with the spec's texture/{@code frames});
 * it then closes every window whose creating effect is no longer active and reaps the stopped entries
 * through {@link VFXWindowRegistry#prune()}. Because it reconciles against the live set, every removal
 * path (stop, fade-out expiry, {@code stopAll}, a replay seek) closes the window without each path
 * having to know about windows.
 *
 * <p><b>Per-frame drive.</b> {@link #apply()} is called once per frame after the game's own present
 * (see {@code MinecraftMixin}). For each bound window it walks the live {@code window_create} and
 * {@code window_control} effects for that name in active order (the creator first, then each control
 * last-writer-wins), reads the animated {@code pos_x}/{@code pos_y}/{@code size_w}/{@code size_h}/
 * {@code opacity}/frame values with {@link VFXActiveEffect#getParam(String, float)} and hands them to
 * the window's {@link VFXWindowController#apply}. It returns immediately when no window is bound, so a
 * session with no window effect leaves the game frame untouched.
 *
 * <p><b>Render thread only.</b> Opening and closing call GLFW, and {@link #apply()} reaches GLFW/GL
 * through the controller, so every method here must run on the render thread; GLFW is not thread-safe
 * and a GL context may only be current on one thread at a time. The held content and controller are
 * never shared across threads.
 */
public final class VFXWindowManager {

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
		final Map<String, VFXActiveEffect> owners = new HashMap<>();
		for (final VFXActiveEffect effect : active) {
			if (effect.getType() != VFXEffectType.WINDOW_CREATE) {
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
				VFXWindowRegistry.get().close(name);
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
		this.bindings.put(name, new Binding(window, content, controller, title));
	}

	/**
	 * Drives every bound window for the current frame: reads the live creator/controller parameters
	 * (creator first, each control last-writer-wins) and applies them through the window's
	 * controller, then retitles the window when the animated title changed. A no-op when no window is
	 * bound. Must run on the render thread and after the game's own present.
	 */
	public void apply() {
		if (this.bindings.isEmpty()) {
			return;
		}
		final List<VFXActiveEffect> active = VFXEffectManager.get().getActive();
		for (final Map.Entry<String, Binding> entry : this.bindings.entrySet()) {
			final String name = entry.getKey();
			final Binding binding = entry.getValue();
			float posX = 0.0F;
			float posY = 0.0F;
			float sizeW = 1.0F;
			float sizeH = 1.0F;
			float opacity = 1.0F;
			int frame = 0;
			@Nullable String title = binding.title();
			for (final VFXActiveEffect effect : active) {
				if (effect.getType() != VFXEffectType.WINDOW_CREATE && effect.getType() != VFXEffectType.WINDOW_CONTROL) {
					continue;
				}
				final VFXWindowSpec spec = spec(effect.getId());
				if (spec == null || !spec.id().equals(name)) {
					continue;
				}
				posX = effect.getParam("pos_x", posX);
				posY = effect.getParam("pos_y", posY);
				sizeW = effect.getParam("size_w", sizeW);
				sizeH = effect.getParam("size_h", sizeH);
				opacity = effect.getParam("opacity", opacity);
				final float frameTime = effect.getParam("frame_time", 0.0F);
				frame = frameTime > 0.0F ? (int) (effect.getElapsed() / frameTime) : 0;
				@Nullable final String effectTitle = spec.titleAt(effect.getParam("title_index", 0.0F));
				if (effectTitle != null) {
					title = effectTitle;
				}
			}
			if (!Objects.equals(title, binding.title())) {
				binding.window().setTitle(title);
				entry.setValue(new Binding(binding.window(), binding.content(), binding.controller(), title));
			}
			binding.controller().apply(binding.window(), posX, posY, sizeW, sizeH, opacity, frame);
		}
	}

	/**
	 * Closes and drops every bound window and reaps the stopped registry entries, run on a client
	 * dispose so no aux window or texture outlives the game session. Must run on the render thread.
	 */
	public void closeAll() {
		for (final String name : this.bindings.keySet()) {
			VFXWindowRegistry.get().close(name);
		}
		this.bindings.clear();
		VFXWindowRegistry.get().prune();
	}

	private static @Nullable VFXWindowSpec spec(final Identifier id) {
		final VFXDefinition definition = VFXDefinitionManager.get().get(id);
		return definition == null ? null : definition.getWindow();
	}

	/**
	 * The pinned monitor work area, read from the game window's own monitor. Falls back to the game
	 * window's logical rect when the window has no monitor (or the platform reports none), so the
	 * canvas is never 0x0. Reads the game window; it never mutates it.
	 *
	 * @return the work area as {@code [x, y, width, height]}
	 */
	private static int[] workArea() {
		final Window gameWindow = Minecraft.getInstance().getWindow();
		final int[] x = new int[1];
		final int[] y = new int[1];
		final int[] width = new int[1];
		final int[] height = new int[1];
		final long monitor = GLFW.glfwGetWindowMonitor(gameWindow.handle());
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
	 * One window's wiring: the window (owned by the registry), its picture content and its
	 * controller, plus the last title set so a steady frame makes no retitle call.
	 */
	private record Binding(VFXWindow window, VFXWindowContent content, VFXWindowController controller, @Nullable String title) {
	}
}

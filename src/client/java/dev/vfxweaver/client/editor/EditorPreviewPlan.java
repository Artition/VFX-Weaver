package dev.vfxweaver.client.editor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure (Minecraft-free) planning used by {@link EditorPreviewController}: the definition diff, the
 * exact-mode visibility/window projection and the ordering lookup. Deliberately free of Minecraft
 * and game types so it can be exercised on a plain JVM; {@link EditorPreviewController} owns the
 * game-touching orchestration and calls into this class.
 */
final class EditorPreviewPlan {
	private EditorPreviewPlan() {
	}

	/**
	 * One preview child: the stable key, its definition id and its tab timing.
	 *
	 * @param key      stable node key (the viewstate addresses the child by this)
	 * @param def      definition id under the private preview namespace
	 * @param delay    ticks from the tab start before the child becomes due
	 * @param duration child lifetime in ticks ({@code <= 0} = definition default)
	 * @param isPost   true for a screen/post-processing child (the only kind that uses {@code order})
	 */
	record Child(String key, String def, float delay, float duration, boolean isPost) {
	}

	/**
	 * The difference between the currently applied definitions and the incoming ones, by string
	 * equality of the raw JSON.
	 *
	 * @param removed ids present in {@code applied} but not in {@code incoming} (tombstones)
	 * @param changed id to new raw JSON for every new or textually different definition
	 */
	record Diff(Set<String> removed, LinkedHashMap<String, String> changed) {
	}

	/**
	 * Diff two raw-definition maps.
	 *
	 * @param applied  the definitions currently registered by the controller
	 * @param incoming the definitions the client just sent for this tab
	 * @return the removed ids and the changed/new id to JSON entries
	 */
	static Diff diff(final Map<String, String> applied, final Map<String, String> incoming) {
		final Set<String> removed = new LinkedHashSet<>(applied.keySet());
		removed.removeAll(incoming.keySet());
		final LinkedHashMap<String, String> changed = new LinkedHashMap<>();
		for (final Map.Entry<String, String> entry : incoming.entrySet()) {
			final String old = applied.get(entry.getKey());
			if (old == null || !old.equals(entry.getValue())) {
				changed.put(entry.getKey(), entry.getValue());
			}
		}
		return new Diff(removed, changed);
	}

	/**
	 * True when the child is not muted and either no solo is active or this child is in it.
	 *
	 * @param child the preview child
	 * @param muted the muted node keys
	 * @param solo  the soloed node keys (empty = no solo)
	 */
	static boolean visible(final Child child, final Set<String> muted, final Set<String> solo) {
		if (muted.contains(child.key())) {
			return false;
		}
		return solo.isEmpty() || solo.contains(child.key());
	}

	/**
	 * True when the child's tab window contains the tick: {@code delay <= tick < delay + duration}
	 * (a non-positive duration means "until the tab ends").
	 *
	 * @param child the preview child
	 * @param tick  the controller tick in the tab's own time base
	 */
	static boolean due(final Child child, final float tick) {
		if (tick < child.delay()) {
			return false;
		}
		return child.duration() <= 0.0F || tick < child.delay() + child.duration();
	}

	/**
	 * The age (ticks into the child timeline) at which the child should be shown at {@code tick}.
	 *
	 * @param child the preview child
	 * @param tick  the controller tick
	 * @return {@code tick - delay}, clamped to {@code >= 0}
	 */
	static float age(final Child child, final float tick) {
		return Math.max(0.0F, tick - child.delay());
	}

	/**
	 * The child's rank in the explicit order list; keys not listed (or with no order) sort last so
	 * a missing entry degrades to the previous order rather than to a random one.
	 *
	 * @param order the node keys in compositing order (lowest first)
	 * @param key   the child key
	 */
	static int orderIndex(final List<String> order, final String key) {
		final int index = order.indexOf(key);
		return index < 0 ? Integer.MAX_VALUE : index;
	}
}

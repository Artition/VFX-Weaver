package dev.vfxweaver.mask;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The bounded custom-shape registry (expanded design). Code-registered shapes live in a local
 * layer that survives reloads and is never synced; a datapack/resource-provided shape with the
 * same id wins (mirroring {@code VFXDefinitionManager}'s two layers). Shapes are registered from
 * {@code VFXAPI}; the mask parser resolves a leaf's shape id here.
 */
public final class VFXShapeRegistry {
	/** The most custom shapes that may be registered at once. */
	public static final int MAX_SHAPES = 64;

	private static final VFXShapeRegistry INSTANCE = new VFXShapeRegistry();

	private final Map<String, VFXCustomShape> shapes = new HashMap<>();
	private final Map<String, VFXMaskShapeGlsl> plugins = new HashMap<>();
	/** Notified on every registration change so the client can drop its compiled-shape shader variants. */
	private @Nullable Runnable changeListener;

	private VFXShapeRegistry() {
		// Built-in composed shapes live in the shared registry (registry metadata is MC-free) so a
		// datapack referencing them validates during server-side parsing too; the GPU coverage is
		// client-only. The demo mask_custom_demo.json references this id.
		this.shapes.put("vfxweaver:ringed_volume", VFXCustomShape.composed("vfxweaver:ringed_volume", VFXMaskSpace.WORLD)
			.sphere(new float[]{0.0F, 64.0F, 0.0F}, 12.0F)
			.op(VFXMaskOp.DIFFERENCE)
			.box(new float[]{0.0F, 64.0F, 0.0F}, 4.0F, 4.0F, 4.0F)
			.build());
		// A built-in GLSL-plugin shape (the "literally any shape" path): a screen-space ring whose
		// centreline radius is modulated by a petal wave, so with the demo's params it reads as an
		// 8-petal flower outline (unmistakably not a circle). Its source is data, so it lives in the
		// shared registry (a datapack demo referencing it validates server-side); only the client
		// compiles it. `p0.xy` = centre (UV), `p0.z` = ring radius, `p0.w` = ring half-width,
		// `p1.x` = petal count, `p1.y` = petal depth as a fraction of the radius.
		this.plugins.put("vfxweaver:ringed_glsl", () ->
			"float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) {\n"
				+ "    vec2 rel = uv - p0.xy;\n"
				+ "    float radius = max(p0.z, 1.0e-3);\n"
				+ "    float halfWidth = max(p0.w, 1.0e-3);\n"
				+ "    float petals = max(p1.x, 1.0);\n"
				+ "    float wave = p1.y * sin(petals * atan(rel.y, rel.x));\n"
				+ "    return abs(length(rel) - radius * (1.0 + wave)) - halfWidth;\n"
				+ "}\n");
		this.shapes.put("vfxweaver:ringed_glsl", new VFXCustomShape("vfxweaver:ringed_glsl", VFXMaskSpace.SCREEN, VFXCustomShape.Family.GLSL_PLUGIN, java.util.List.of(), java.util.List.of()));
	}

	/** The singleton registry. */
	public static VFXShapeRegistry get() {
		return INSTANCE;
	}

	/**
	 * Sets the listener notified whenever a shape is registered or removed. The client uses it to
	 * invalidate its compiled custom-shape shader variants (a re-registration must not keep serving
	 * the old GLSL). Loader-agnostic: the registry itself never references the client.
	 *
	 * @param listener the listener, or {@code null} to clear it
	 */
	public void setChangeListener(final @Nullable Runnable listener) {
		this.changeListener = listener;
	}

	private void notifyChanged() {
		if (this.changeListener != null) {
			this.changeListener.run();
		}
	}

	/**
	 * Registers a composed custom shape.
	 *
	 * @return {@code false} when the registry is full
	 */
	public boolean register(final String id, final VFXCustomShape shape) {
		if (this.shapes.size() >= MAX_SHAPES && !this.shapes.containsKey(id)) {
			return false;
		}
		this.shapes.put(id, shape);
		notifyChanged();
		return true;
	}

	/** Removes a custom shape; {@code true} when one existed. */
	public boolean unregister(final String id) {
		final boolean existed = this.shapes.remove(id) != null;
		this.plugins.remove(id);
		if (existed) {
			notifyChanged();
		}
		return existed;
	}

	/** The shape with this id, or {@code null}. */
	public @Nullable VFXCustomShape get(final String id) {
		return this.shapes.get(id);
	}

	/**
	 * Registers a GLSL-plugin shape. The plugin's id becomes a custom shape whose {@code family()}
	 * is {@link VFXCustomShape.Family#GLSL_PLUGIN}; the client compiles it on demand.
	 *
	 * @return {@code false} when the registry is full
	 */
	public boolean registerGlsl(final String id, final VFXMaskShapeGlsl plugin) {
		if (this.shapes.size() >= MAX_SHAPES && !this.shapes.containsKey(id)) {
			return false;
		}
		this.plugins.put(id, plugin);
		this.shapes.put(id, new VFXCustomShape(id, VFXMaskSpace.SCREEN, VFXCustomShape.Family.GLSL_PLUGIN, java.util.List.of(), java.util.List.of()));
		notifyChanged();
		return true;
	}

	/** The registered plugin source for this id, or {@code null}. */
	public @Nullable VFXMaskShapeGlsl plugin(final String id) {
		return this.plugins.get(id);
	}

	/** The registered ids (for validation and diagnostics). */
	public Set<String> ids() {
		return Set.copyOf(this.shapes.keySet());
	}
}

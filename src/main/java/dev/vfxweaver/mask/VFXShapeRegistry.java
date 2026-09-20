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
		// edge is modulated by a petal wave. Its source is data, so it lives in the shared registry
		// (a datapack demo referencing it validates server-side); only the client compiles it.
		// `p0.xy` = centre (UV), `p0.z` = radius, `p1.x` = petal count.
		this.plugins.put("vfxweaver:ringed_glsl", () ->
			"float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) {\n"
				+ "    vec2 rel = uv - p0.xy;\n"
				+ "    float radius = max(p0.z, 1.0e-3);\n"
				+ "    float petals = max(p1.x, 1.0);\n"
				+ "    float wave = 0.12 * sin(petals * atan(rel.y, rel.x));\n"
				+ "    return length(rel) - radius * (1.0 + wave);\n"
				+ "}\n");
		this.shapes.put("vfxweaver:ringed_glsl", new VFXCustomShape("vfxweaver:ringed_glsl", VFXMaskSpace.SCREEN, VFXCustomShape.Family.GLSL_PLUGIN, java.util.List.of(), java.util.List.of()));
	}

	/** The singleton registry. */
	public static VFXShapeRegistry get() {
		return INSTANCE;
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
		return true;
	}

	/** Removes a custom shape; {@code true} when one existed. */
	public boolean unregister(final String id) {
		final boolean existed = this.shapes.remove(id) != null;
		this.plugins.remove(id);
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

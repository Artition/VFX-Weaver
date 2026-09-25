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
	/** The declared Lipschitz bound per plugin id; absent means the conservative default of 1.0. */
	private final Map<String, Float> pluginLipschitz = new HashMap<>();
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
		// The built-in WORLD GLSL plugin (the reference for a genuine 3D plugin): a union of up to
		// VFX_BLOBS_MAX world-space spheres whose centres/radii come from the leaf's dynamic data
		// slots (d[4k..4k+3] = sphere k's centre.xyz + radius; radius <= 0 skips the sphere) and
		// whose global controls come from the leaf's p0..p7 (p0.xyz = centre offset, p0.w = radius
		// scale (0 -> 1), p1.x = radius bias, p1.y = sphere count override (0 -> all data spheres)).
		// The SDF reads `vfx_mask_data` and `vfx_shape_params0/1` (declared by the coverage shader),
		// so it must not declare them. The optional `vfx_shape_custom_bounds()` broad phase returns
		// the enclosing sphere of the same (data + params) extent so an aura march is cheap;
		// w < 0 = unbounded (no active sphere). Because the params move/scale the extent, the bounds
		// reads them too (the coverage shader publishes the calling leaf's p0/p1).
		this.plugins.put("vfxweaver:blobs_glsl", () ->
			"#define VFX_BLOBS_MAX 8\n"
				+ "float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) {\n"
				+ "    float scale = (abs(p0.w) < 1.0e-6) ? 1.0 : p0.w;\n"
				+ "    float bias = p1.x;\n"
				+ "    vec3 offset = p0.xyz;\n"
				+ "    int count = int(floor(p1.y + 0.5));\n"
				+ "    if (count <= 0 || count > VFX_BLOBS_MAX) {\n"
				+ "        count = VFX_BLOBS_MAX;\n"
				+ "    }\n"
				+ "    float d = 1.0e6;\n"
				+ "    for (int k = 0; k < VFX_BLOBS_MAX; k++) {\n"
				+ "        if (k >= count) {\n"
				+ "            break;\n"
				+ "        }\n"
				+ "        int b = vfx_shape_data_base + k * 4;\n"
				+ "        vec3 c = vec3(vfx_mask_data(b), vfx_mask_data(b + 1), vfx_mask_data(b + 2)) + offset;\n"
				+ "        float r = vfx_mask_data(b + 3) * scale + bias;\n"
				+ "        if (r <= 0.0) {\n"
				+ "            continue;\n"
				+ "        }\n"
				+ "        d = min(d, length(world - c) - r);\n"
				+ "    }\n"
				+ "    return d;\n"
				+ "}\n"
				+ "vec4 vfx_shape_custom_bounds() {\n"
				+ "    float scale = (abs(vfx_shape_params0.w) < 1.0e-6) ? 1.0 : vfx_shape_params0.w;\n"
				+ "    float bias = vfx_shape_params1.x;\n"
				+ "    vec3 offset = vfx_shape_params0.xyz;\n"
				+ "    int count = int(floor(vfx_shape_params1.y + 0.5));\n"
				+ "    if (count <= 0 || count > VFX_BLOBS_MAX) {\n"
				+ "        count = VFX_BLOBS_MAX;\n"
				+ "    }\n"
				+ "    vec3 lo = vec3(1.0e9);\n"
				+ "    vec3 hi = vec3(-1.0e9);\n"
				+ "    bool hasBlob = false;\n"
				+ "    for (int k = 0; k < VFX_BLOBS_MAX; k++) {\n"
				+ "        if (k >= count) {\n"
				+ "            break;\n"
				+ "        }\n"
				+ "        int b = vfx_shape_data_base + k * 4;\n"
				+ "        vec3 c = vec3(vfx_mask_data(b), vfx_mask_data(b + 1), vfx_mask_data(b + 2)) + offset;\n"
				+ "        float r = vfx_mask_data(b + 3) * scale + bias;\n"
				+ "        if (r <= 0.0) {\n"
				+ "            continue;\n"
				+ "        }\n"
				+ "        lo = min(lo, c - vec3(r));\n"
				+ "        hi = max(hi, c + vec3(r));\n"
				+ "        hasBlob = true;\n"
				+ "    }\n"
				+ "    if (!hasBlob) {\n"
				+ "        return vec4(0.0, 0.0, 0.0, -1.0);\n"
				+ "    }\n"
				+ "    vec3 centre = 0.5 * (lo + hi);\n"
				+ "    return vec4(centre, length(hi - centre));\n"
				+ "}\n");
		this.shapes.put("vfxweaver:blobs_glsl", new VFXCustomShape("vfxweaver:blobs_glsl", VFXMaskSpace.WORLD, VFXCustomShape.Family.GLSL_PLUGIN, java.util.List.of(), java.util.List.of()));
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
		this.pluginLipschitz.remove(id);
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
		return this.registerGlsl(id, plugin, 1.0F);
	}

	/**
	 * Registers a GLSL-plugin shape with a declared Lipschitz upper bound on the field's gradient
	 * ({@code |grad d| <= lipschitz}). The client marches the plugin SDF by sphere tracing and derives
	 * a Lipschitz cone-envelope of the field; a plugin whose field over-estimates the distance (e.g. a
	 * noise-perturbed SDF) declares the real bound here so the march and the envelope stay correct.
	 * Values are clamped to at least {@code 1.0} (a distance bound can never be super-gradient), and
	 * a variant containing several plugins compiles against the max of their declared bounds.
	 *
	 * @param lipschitz an upper bound on {@code |grad d|}; {@code 1.0} (the default) means the plugin
	 * 	already returns a conservative distance field
	 * @return {@code false} when the registry is full
	 */
	public boolean registerGlsl(final String id, final VFXMaskShapeGlsl plugin, final float lipschitz) {
		if (this.shapes.size() >= MAX_SHAPES && !this.shapes.containsKey(id)) {
			return false;
		}
		final float bound = Math.max(1.0F, lipschitz);
		this.plugins.put(id, plugin);
		this.pluginLipschitz.put(id, bound);
		this.shapes.put(id, new VFXCustomShape(id, VFXMaskSpace.SCREEN, VFXCustomShape.Family.GLSL_PLUGIN, java.util.List.of(), java.util.List.of()));
		notifyChanged();
		return true;
	}

	/** The registered plugin source for this id, or {@code null}. */
	public @Nullable VFXMaskShapeGlsl plugin(final String id) {
		return this.plugins.get(id);
	}

	/**
	 * The declared Lipschitz bound for this plugin, or {@code null} when the plugin was registered
	 * without one (the conservative-distance default of {@code 1.0} then applies).
	 */
	public @Nullable Float pluginLipschitz(final String id) {
		return this.pluginLipschitz.get(id);
	}

	/** The registered ids (for validation and diagnostics). */
	public Set<String> ids() {
		return Set.copyOf(this.shapes.keySet());
	}
}

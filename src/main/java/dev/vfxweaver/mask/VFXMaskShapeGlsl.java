package dev.vfxweaver.mask;

/**
 * The GLSL-plugin contract for a custom mask shape (expanded design). A mod or datapack supplies
 * exactly one function; the client compiles it into a bounded shader variant for the masks that
 * reference the shape. The source must define the exact function:
 *
 * <pre>{@code
 * // Negative inside, positive outside, in the leaf's space (world units for world leaves).
 * float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1);
 * }</pre>
 *
 * <p>{@code world} is the depth-reconstructed world position (zero for a screen leaf), {@code uv}
 * the normalized screen coordinate, and {@code p0}/{@code p1} the leaf's eight numeric params
 * (animated values resolved by the timeline). The function must not declare uniforms or samplers,
 * must not use {@code #version}, and must be pure (no side effects); the variant wrapper supplies
 * the prelude and the dispatch. Compilation is isolated per variant: a plugin that fails to compile
 * falls back to neutral coverage for that leaf and is reported as a validation error naming the
 * shape.
 *
 * <p><b>Dynamic per-leaf data.</b> A leaf may also carry {@code K = 32} dynamic floats, reserved
 * as the ordinary animatable params {@code mask.p<N>.d0 .. d31} (authored as a {@code "data": [...]}
 * array on the mask leaf, or set live with {@code setParam}/{@code sendSetParam}/{@code maskData}).
 * The coverage shader points {@code vfx_shape_data_base} at the calling leaf's slice before the
 * call, so the plugin reads its own leaf's values through the helper:
 *
 * <pre>{@code
 * float radius = vfx_mask_data(vfx_shape_data_base + 0);
 * float cx     = vfx_mask_data(vfx_shape_data_base + 1);
 * }</pre>
 *
 * <p>The plugin must not declare {@code vfx_mask_data} or {@code vfx_shape_data_base} (the wrapper
 * declares them). A plugin that never calls the helper compiles and behaves exactly as before; a
 * {@code mask.p<N>.d<J>} update is an ordinary param edit and never recompiles the shader variant
 * (the variant is keyed only by the set of plugin ids).
 *
 * <p><b>World plugins and the optional broad phase.</b> A plugin whose shape has a real 3D extent
 * (a {@code space: "world"} plugin, e.g. used with {@code volume: "aura"}) should also define:
 *
 * <pre>{@code
 * // World centre (xyz) plus an enclosing radius; a negative w means "unbounded" (never cull).
 * vec4 vfx_shape_custom_bounds();
 * }</pre>
 *
 * <p>The coverage shader calls it before an aura march and skips the march where the view ray
 * misses the sphere, which is what keeps the march cheap. It may read {@code vfx_mask_data(...)}
 * and the calling leaf's params through the wrapper-declared globals {@code vfx_shape_params0} /
 * {@code vfx_shape_params1} (the same values the call site passes to {@code vfx_shape_custom}), and
 * it must not declare any of those four symbols. The function is optional: a plugin that omits it
 * is treated as unbounded. The built-in reference is {@code vfxweaver:blobs_glsl} (a union of world
 * spheres): the SDF and its bounds both read the same data, so the broad phase is conservative.
 *
 * <p><b>The distance-bound obligation.</b> The aura mode marches the field by sphere tracing and
 * derives a Lipschitz cone-envelope of the field along the view ray, so the returned value must be
 * a <em>conservative</em> distance: negative inside, positive outside, and never larger than the true
 * distance to the boundary, i.e. {@code |grad d| <= 1} everywhere. A {@code max}/{@code min}
 * composition of such fields is still conservative (though creased, which the envelope handles
 * exactly), so C1 smoothness is <em>not</em> required. A field perturbed by a noise term over-estimates
 * the distance and breaks the bound; either scale the whole returned value down until it is
 * conservative, or register the shape with the real bound via
 * {@code VFXAPI.registerMaskShapeGlsl(id, plugin, lipschitz)} (clamped to {@code >= 1.0}; a variant of
 * several plugins uses the max). Under-declaring keeps the previous over-stepping behaviour, so the
 * bound must be a true upper bound on {@code |grad d|}.
 */
@FunctionalInterface
public interface VFXMaskShapeGlsl {
	/**
	 * @return GLSL 330 source defining {@code float vfx_shape_custom(vec3, vec2, vec4, vec4)}
	 */
	String glsl();

	/**
	 * The optional containment box for the aura march, emitted into the same builder the variant
	 * generator already collects plugin GLSL in. Append this plugin's source for
	 *
	 * <pre>{@code
	 * bool vfx_shape_custom_bounds_box(out vec3 bmin, out vec3 bmax);
	 * }</pre>
	 *
	 * <p><b>Contract: the box must CONTAIN the whole region where the field is {@code <= 0}.</b>
	 * Anything the box leaves out is real coverage the march would no longer find. A box around a
	 * hole (the inverse of this contract) is a declaration bug, not a cull: it buys nothing, because
	 * the coverage region outside it is unbounded. Unbounded axes are declared with sentinels
	 * ({@code -1e9} / {@code +1e9}); the shader clamps them to the reachable range.
	 *
	 * <p>The two contracts are mirrors, and swapping them is the inversion that made an earlier
	 * revision of the design a lie: {@link #customSkipBounds(StringBuilder)} declares a box that lies
	 * entirely inside the region where the field is {@code > 0} - a hole - and must be inscribed in it,
	 * so the AABB of a set of circles does not qualify (its corners lie in the {@code <= 0} region).
	 *
	 * <p>Precedence is skip box, then this box, then {@link #glsl()}'s
	 * {@code vfx_shape_custom_bounds()} sphere, then no bounds, so this box is worth declaring only
	 * for a bounded coverage region.
	 *
	 * @param out the builder the variant generator collects plugin GLSL in
	 * @return {@code true} when this plugin appended its function, {@code false} for "not provided"
	 *         (the default, which appends nothing and leaves today's behaviour untouched)
	 */
	default boolean customBoundsBox(final StringBuilder out) {
		return false;
	}

	/**
	 * The optional skip box for the aura march, emitted into the same builder the variant generator
	 * already collects plugin GLSL in. Append this plugin's source for
	 *
	 * <pre>{@code
	 * bool vfx_shape_custom_skip_bounds(out vec3 bmin, out vec3 bmax);
	 * }</pre>
	 *
	 * <p><b>Contract: the box must lie ENTIRELY inside the region where the field is {@code > 0}</b>
	 * - a hole - and must be <em>inscribed</em> in it. The AABB of a set of circles does <em>not</em>
	 * qualify: its corners lie outside the circles, i.e. inside the coverage region
	 * ({@code field <= 0}), and would skip real coverage. The inscribed square of the largest circle
	 * does qualify. The shader only advances the march when the camera is inside the box and pads
	 * the slab test, so a plugin does not need to know {@code softness}.
	 *
	 * <p>This is the lever that fits an exterior field (unbounded coverage): the march may then start
	 * at the box's far side instead of at the camera.
	 *
	 * @param out the builder the variant generator collects plugin GLSL in
	 * @return {@code true} when this plugin appended its function, {@code false} for "not provided"
	 *         (the default, which appends nothing and leaves today's behaviour untouched)
	 */
	default boolean customSkipBounds(final StringBuilder out) {
		return false;
	}
}

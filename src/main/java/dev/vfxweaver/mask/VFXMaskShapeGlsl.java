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
 */
@FunctionalInterface
public interface VFXMaskShapeGlsl {
	/**
	 * @return GLSL 330 source defining {@code float vfx_shape_custom(vec3, vec2, vec4, vec4)}
	 */
	String glsl();
}

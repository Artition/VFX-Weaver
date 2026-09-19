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
 */
@FunctionalInterface
public interface VFXMaskShapeGlsl {
	/**
	 * @return GLSL 330 source defining {@code float vfx_shape_custom(vec3, vec2, vec4, vec4)}
	 */
	String glsl();
}

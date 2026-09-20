#version 330

// surface_pattern (spec §6.2, §9 step 5): a world-anchored shape pattern projected onto whatever
// surface is behind the pixel. The scene depth is reconstructed into a world position with the
// verified reversed-depth recipe, mapped into the shape's cell-local coordinate and painted with
// the shared shape library's coverage. The primitives (circle/ellipse/rect/polygon), their fill,
// rotation, stroke/softness and the repeat/tile modifier are NOT implemented here: this shader
// imports <vfxweaver:shape.glsl> and calls vfx_shape_pattern_coverage, so there is no figure maths
// and no grid/ring branch on this side.
//
// Run at screen layer 0 only (the manager forces it): the single scene depth buffer is intact there.
// The Config block declares mat4 inv_view_proj first, then the floats in the order registered by
// VFXShaderPrograms.registerDepthPost — std140 offsets are positional.
#moj_import <vfxweaver:shape.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    mat4 inv_view_proj;
    float tile_scale;
    float color_r;
    float color_g;
    float color_b;
    float opacity;
    float fade_radius;
    float normal_mask;
    float distort;
    float center_x;
    float center_y;
    float center_z;
    float shape;
    float fill;
    float rotation;
    float stroke_width;
    float softness;
    float repeat_x;
    float repeat_y;
    float radius;
    float radius_x;
    float radius_y;
    float half_width;
    float half_height;
    float corner_radius;
    float sides;
    float time;
};

out vec4 fragColor;

void main() {
    vec4 base = texture(InSampler, texCoord);

    // Reversed depth: near = 1, far/sky = 0. Nothing behind the pixel -> passthrough.
    float sceneDepth = texture(DepthSampler, texCoord).r;
    if (sceneDepth <= 1.0e-6) {
        fragColor = base;
        return;
    }

    // spec §5 / depth findings: the sampled value is NDC z, fed to the inverse as-is.
    vec4 clip = vec4(texCoord * 2.0 - 1.0, sceneDepth, 1.0);
    vec4 world4 = inv_view_proj * clip;
    if (abs(world4.w) < 1.0e-6) {
        fragColor = base;
        return;
    }
    vec3 world = world4.xyz / world4.w;

    vec2 p = world.xz;
    if (distort != 0.0) {
        // ponytail: cheap sine warp; the field-library's real noise distortion arrives with step 4.
        p += distort * vec2(sin(world.y * 0.7 + time * 0.05), cos(world.y * 0.7 - time * 0.05));
    }

    // Cell-local coordinate: centre on the anchor, scale to a `tile_scale` cell. The shared shape
    // library owns every figure's signed-distance function, the fill, the rotation and the repeat
    // modifier — there is no figure branch here.
    vec2 cell = (p - vec2(center_x, center_z)) / max(tile_scale, 1.0e-4);
    vec4 shape0 = vec4(radius, radius_x, radius_y, half_width);
    vec4 shape1 = vec4(half_height, corner_radius, sides, rotation);
    float shapeCoverage = vfx_shape_pattern_coverage(int(shape + 0.5), int(fill + 0.5), cell,
        vec2(repeat_x, repeat_y), shape0, shape1, stroke_width, softness);

    // Distance fade from the anchor (fade_radius <= 0 disables it).
    float fade = 1.0;
    if (fade_radius > 0.0) {
        float dist = length(p - vec2(center_x, center_z));
        fade = 1.0 - smoothstep(fade_radius * 0.5, fade_radius, dist);
    }

    // Surface orientation from the depth-reconstructed world position. |n.y| treats floor and
    // ceiling alike: the depth-derived normal sign is ambiguous without extra camera state.
    vec3 n = normalize(cross(dFdx(world), dFdy(world)));
    float upness = abs(n.y);
    float mask = normal_mask <= 0.0 ? 1.0 : smoothstep(normal_mask, min(normal_mask + 0.2, 1.0), upness);

    float coverage = clamp(shapeCoverage * fade * mask * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
    fragColor = vec4(mix(base.rgb, vec3(color_r, color_g, color_b), coverage), base.a);
}

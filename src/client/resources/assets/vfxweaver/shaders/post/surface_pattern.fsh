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
#moj_import <vfxweaver:camera.glsl>

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
    float face_mask;
    float band_min;
    float band_max;
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

    // spec §5 / depth findings: reconstruct the visible world position with the shared recipe (one
    // implementation, also used by the field library). The normal comes from neighbouring depth
    // taps (camera.glsl) rather than screen-space derivatives of the reconstruction: at an edge or
    // a silhouette a derivative mixes two surfaces (or the sky's far-plane position) and flips.
    vec3 world = vfx_world_from_depth(texCoord, sceneDepth, inv_view_proj);
    vec3 eye = vfx_world_from_depth(texCoord, 1.0, inv_view_proj);
    vec2 texel = vec2(1.0 / max(InSize.x, 1.0), 1.0 / max(InSize.y, 1.0));
    vec3 nRaw = vfx_depth_normal(DepthSampler, texCoord, sceneDepth, inv_view_proj, texel, eye);

    // A block face is axis-aligned, so snapping the normal to the nearest of the six axes is exact,
    // not an approximation, and it removes the residual noise. The snapped axis drives the
    // projection plane, the face set and the band; the unsnapped normal keeps the legacy numeric
    // normal_mask meaning. The switch is hard (not blended) because blending projected coordinates
    // shears the figure and blending coverage double-paints it.
    vec3 n = vfx_snap_normal(nRaw);
    int faceId = vfx_face_id(n);

    // Project onto the chosen plane: horizontal faces keep world XZ (today's result); a wall uses
    // the horizontal tangent u = cross(worldUp, n) across and world Y up. With the snapped normal u
    // is exactly ±X/±Z, so the figure is upright, un-mirrored and stable as the camera moves:
    //   south (+Z) u=+X  p=( x, y)   north (-Z) u=-X  p=(-x, y)
    //   east  (+X) u=-Z  p=(-z, y)   west  (-X) u=+Z  p=( z, y)
    // Both axes are in world units, so the figure's aspect is preserved on walls. The band runs
    // along the axis the projection picked: Y for up/down, X for east/west, Z for north/south.
    vec3 center = vec3(center_x, center_y, center_z);
    vec2 p;
    vec2 centerP;
    float bandAxis;
    if (faceId <= 1) {
        p = world.xz;
        centerP = center.xz;
        bandAxis = world.y;
    } else {
        vec3 u = cross(vec3(0.0, 1.0, 0.0), n);
        p = vec2(dot(world, u), world.y);
        centerP = vec2(dot(center, u), center.y);
        bandAxis = faceId >= 4 ? world.x : world.z;
    }

    // Distort warps the chosen plane; the phase is the remaining world component along the normal
    // (world Y on a floor, the wall's out-of-plane component on a wall) — a flat floor keeps the
    // exact original warp because there n is +Y.
    vec2 q = p;
    if (distort != 0.0) {
        // ponytail: cheap sine warp; the field-library's real noise distortion arrives with step 4.
        float phase = dot(world, n);
        q += distort * vec2(sin(phase * 0.7 + time * 0.05), cos(phase * 0.7 - time * 0.05));
    }

    // Cell-local coordinate: centre on the anchor, scale to a `tile_scale` cell. The shared shape
    // library owns every figure's signed-distance function, the fill, the rotation and the repeat
    // modifier — there is no figure branch here.
    vec2 cell = (q - centerP) / max(tile_scale, 1.0e-4);
    vec4 shape0 = vec4(radius, radius_x, radius_y, half_width);
    vec4 shape1 = vec4(half_height, corner_radius, sides, rotation);
    float shapeCoverage = vfx_shape_pattern_coverage(int(shape + 0.5), int(fill + 0.5), cell,
        vec2(repeat_x, repeat_y), shape0, shape1, stroke_width, softness);

    // 3-D distance fade from the anchor (fade_radius <= 0 disables it); works on walls too.
    float fade = 1.0;
    if (fade_radius > 0.0) {
        float dist = length(world - center);
        fade = 1.0 - smoothstep(fade_radius * 0.5, fade_radius, dist);
    }

    // Orientation: with a surface block (face_mask >= 0) the selected faces pass as a hard set;
    // without one, the legacy numeric normal_mask keeps its exact meaning (0 = all, 0.6 = floors).
    float faceCov;
    if (face_mask >= 0.0) {
        faceCov = mod(floor(face_mask / exp2(float(faceId))), 2.0) >= 0.5 ? 1.0 : 0.0;
    } else {
        faceCov = normal_mask <= 0.0 ? 1.0 : smoothstep(normal_mask, min(normal_mask + 0.2, 1.0), abs(nRaw.y));
    }
    // Inclusive band along the dominant axis (unbounded by default).
    float bandCov = (bandAxis >= band_min && bandAxis <= band_max) ? 1.0 : 0.0;

    float coverage = clamp(shapeCoverage * fade * faceCov * bandCov * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
    fragColor = vec4(mix(base.rgb, vec3(color_r, color_g, color_b), coverage), base.a);
}

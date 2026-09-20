#version 330

// surface_pattern (spec §6.2, §9 step 5): a world-anchored shape pattern projected onto whatever
// surface is behind the pixel. The scene depth is reconstructed into a world position with the
// verified reversed-depth recipe, mapped into the shape's cell-local coordinate and painted with
// the shared shape library's coverage. The primitives (circle/ellipse/rect/polygon), their fill,
// rotation, stroke/softness and the repeat/tile modifier are NOT implemented here: this shader
// imports <vfxweaver:shape.glsl> and calls vfx_shape_pattern_coverage, so there is no figure maths
// and no grid/ring branch on this side.
//
// Defaults to screen layer 0 — the layer where the single scene depth buffer is intact; a definition
// may still override screen_layer, in which case the depth read is the hand's, not the scene's.
// Registered on 26.2 only: the reversed-depth recipe is verified there (26.1.2/1.21.11 use the other
// depth convention, so the pass is not registered and the effect draws nothing).
// The Config block declares mat4 inv_view_proj first, then the floats in the order registered by
// VFXShaderPrograms.registerDepthPost — std140 offsets are positional.
#moj_import <vfxweaver:shape.glsl>
#moj_import <vfxweaver:camera.glsl>
#moj_import <vfxweaver:texture.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
// The pattern texture (block/item/atlas sprite or standalone). Bound to the input target when the
// effect has no texture, so the pipeline layout is always satisfied.
uniform sampler2D PatternSampler;

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
    float band_softness;
    // Appended after band_softness (VFXShaderPrograms.registerDepthPost, positional std140).
    // shape_present: 1 when the definition authored a figure, so a texture-only pattern is not
    // clipped to the defaulted circle. tex_*: the resolved texture rect (atlas sub-rect or 0..1),
    // pixel aspect, sheet grid, frame, channel code and a present/preserve flag bitmask.
    float shape_present;
    float tex_u0;
    float tex_v0;
    float tex_u1;
    float tex_v1;
    float tex_aspect;
    float tex_cols;
    float tex_rows;
    float tex_frame;
    float tex_flags;
    float tex_channel;
    float texture_tint;
    // Appended after texture_tint: the sprite/texture pixel size, used for the half-texel sheet
    // inset (0.5 / pixels) so a filtered sample of a cell edge cannot bleed into the next cell.
    float tex_px_w;
    float tex_px_h;
};

out vec4 fragColor;

// Coverage of a single (non-repeating) pattern tile: falls off over `softness` (cell units) outside
// the [0,1] tile. A procedural figure is bounded by its SDF radius; a texture cell is not, and the
// clamps inside vfx_texture_sheet_uv would smear the cell's edge texel across the surface.
float vfx_pattern_tile_coverage(vec2 uv, float softness) {
	vec2 outside = max(abs(uv - vec2(0.5)) - vec2(0.5), vec2(0.0));
	return 1.0 - clamp(max(outside.x, outside.y) / max(softness, 1.0e-4), 0.0, 1.0);
}

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

    // Distort warps the chosen plane. The phase must vary across the plane, so it is the in-plane
    // coordinate p (world XZ on a floor, the wall's horizontal tangent + world Y on a wall). Driving
    // it from dot(world, n) was constant on a flat axis-aligned surface — the snapped normal is
    // constant there — so it translated the pattern instead of warping it. A definition that authored
    // a non-zero distort on a flat floor now sees the intended sine warp where it previously saw a
    // constant offset.
    vec2 q = p;
    if (distort != 0.0) {
        // ponytail: cheap sine warp; the field-library's real noise distortion arrives with step 4.
        float phase = p.x + p.y;
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

    // Textured figure (pattern.texture): the texture is the figure, and an authored figure becomes
    // its mask — coverage = texture channel * shape coverage, so a texture with no figure is not
    // clipped. The texture reuses the exact figure cell (vfx_shape_cell: rotate -> repeat -> cell),
    // so it rotates and tiles with the figure. tex_flags bits: 1 = texture authored, 2 = resolved,
    // 4 = preserve aspect. An authored-but-unresolved texture is fail-closed (coverage 0, never the
    // procedural figure). The atlas/sheet rect comes from the CPU (a fragment has no atlas
    // knowledge) in 0..1.
    vec3 patternRGB = vec3(color_r, color_g, color_b);
    float bodyCoverage = shapeCoverage;
    if (mod(floor(tex_flags), 2.0) >= 0.5) {
        float texCoverage = 0.0;
        if (mod(floor(tex_flags / 2.0), 2.0) >= 0.5) {
            vec2 uv = vfx_shape_cell(cell, vec2(repeat_x, repeat_y), rotation) + 0.5;
            // A single tile (repeat <= 1 on both axes) is unbounded: vfx_shape_cell skips its
            // fract, so uv runs far outside [0,1]. Bound the tile here from the raw coordinate
            // (the whole [0,1]^2 cell, before the aspect letterbox) and clamp so the edge texel
            // cannot smear; a tiling pattern stays in [0,1) via the fract and needs neither.
            float tileCov = 1.0;
            if (repeat_x <= 1.0 && repeat_y <= 1.0) {
                tileCov = vfx_pattern_tile_coverage(uv, softness);
                uv = clamp(uv, 0.0, 1.0);
            }
            if (mod(floor(tex_flags / 4.0), 2.0) >= 0.5) {
                // preserve: keep the *cell's* pixel aspect, not the whole sheet's — a non-square
                // sheet cell (cols != rows) must not stretch the figure. cell aspect =
                // (W/cols)/(H/rows) = (W/H) * rows / cols.
                uv = vfx_texture_aspect(uv, tex_aspect * (tex_rows / max(tex_cols, 1.0)));
            }
            // Half a texel in sprite-normalized UV: the inset that keeps a sheet cell's edge
            // from sampling its neighbour (texture.glsl vfx_texture_sheet_uv).
            vec2 halfTexel = vec2(0.5) / max(vec2(tex_px_w, tex_px_h), vec2(1.0));
            vec4 texel = vfx_texture_sample(PatternSampler, uv,
                vec4(tex_u0, tex_v0, tex_u1, tex_v1), vec2(tex_cols, tex_rows), tex_frame, halfTexel);
            texCoverage = clamp(vfx_texture_channel(texel, int(tex_channel + 0.5)), 0.0, 1.0);
            texCoverage *= tileCov;
            patternRGB = mix(texel.rgb, texel.rgb * vec3(color_r, color_g, color_b), clamp(texture_tint, 0.0, 1.0));
        }
        if (shape_present >= 0.5) {
            texCoverage *= shapeCoverage;
        }
        bodyCoverage = texCoverage;
    }

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
        // Clamp into the meaningful range and only take the smoothstep branch when the upper edge is
        // strictly above the lower one. At normal_mask == 1.0 the old min(normal_mask + 0.2, 1.0)
        // collapsed the two edges to the same value, and smoothstep(edge0 == edge1, ...) is
        // undefined in GLSL; the fallback is the exact hard test (only a vertical normal passes).
        float nm = clamp(normal_mask, 0.0, 1.0);
        float nmUpper = min(nm + 0.2, 1.0);
        faceCov = nm <= 0.0 ? 1.0 : (nmUpper > nm ? smoothstep(nm, nmUpper, abs(nRaw.y)) : (abs(nRaw.y) >= nm ? 1.0 : 0.0));
    }
    // Band along the dominant axis, with an optional soft edge of half-width `band_softness`
    // (world units): the reconstructed axis coordinate of a surface lying exactly on a bound
    // jitters across the hard inclusive test from pixel to pixel, so the surface flickers. The
    // fade is centred on each authored bound (interior stays 1, outside 0); a surface on a bound
    // sits at half coverage. The ±1e29 guards skip the fade for an `unbounded` sentinel (±1e30),
    // where the two smoothstep edges would collapse to one value; `band_softness` 0 keeps the
    // exact hard test, so definitions that omit it are unchanged.
    float bandCov;
    if (band_softness > 0.0) {
        float lower = band_min > -1.0e29 ? smoothstep(band_min - band_softness, band_min + band_softness, bandAxis) : 1.0;
        float upper = band_max < 1.0e29 ? 1.0 - smoothstep(band_max - band_softness, band_max + band_softness, bandAxis) : 1.0;
        bandCov = lower * upper;
    } else {
        bandCov = (bandAxis >= band_min && bandAxis <= band_max) ? 1.0 : 0.0;
    }

    float coverage = clamp(bodyCoverage * fade * faceCov * bandCov * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
    fragColor = vec4(mix(base.rgb, patternRGB, coverage), base.a);
}

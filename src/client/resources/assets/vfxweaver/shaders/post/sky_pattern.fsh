#version 330

// sky_pattern (spec §3.1, §3.2, §4.1, stage S3): a datapack figure/texture painted on the sky
// dome. It is surface_pattern's sibling — same structural `pattern` block, same shared shape
// library (shape.glsl) and texture addressing (texture.glsl) — but the projection target is the
// dome instead of a depth-reconstructed world surface. The pixel's view ray is reconstructed with
// vfx_view_dir (include/dome.glsl) and optionally spun about world Y by `dome_rotation`; how that
// ray addresses the pattern is chosen by the `sky_mode` param:
//
//   patch (0) — a gnomonic (tangent-plane) decal at the authored anchor. The ray is projected onto
//               the tangent plane at the anchor (vfx_dome_patch_cell); `tile_scale` is the cell
//               scale on that plane. The decal is a flat sign with **no built-in clip**: it runs
//               out to the tangent-plane horizon, where a pixel behind the decal (`facing <= 0`)
//               is discarded as a hard backstop. A developer who needs a clean edge adds a mask;
//               `fill` is what wraps the whole sky. This is the default: a figure at a spot (a flat
//               sign, not a whole-sky wrap).
//   fill  (1) — the whole sphere by three orthographic charts blended with a sharpened partition of
//               unity (vfx_dome_fill_cells). No pole convergence and no seam anywhere; the charts'
//               coverage and colour are blended (never their UVs — they are incomparable frames),
//               and the colour is renormalised by the blended coverage so texture texels stay
//               saturated inside the crossfade ribbons.
//
// There is no equirectangular mode: a single global chart of a sphere has a pole singularity and a
// mandatory seam — topological, not fixable — so the legacy `dome` addressing was removed before
// release. A true skybox cube (six authored faces) is a separate future feature.
//
// The whole pass is gated on VFX_DEPTH_IS_SKY, so it can only ever paint far-depth sky pixels: it
// never touches geometry, the first-person hand or the GUI (spec §3.1). There is no geometry
// reconstruction, no normal and no face logic here — that is the entire difference from
// surface_pattern.
//
// Registered on every node through VFXShaderPrograms.registerDepthPost, which injects the per-node
// VFX_DEPTH_REVERSED define (26.2 reversed, 26.1.2/1.21.11 standard) so the same source serves all
// nodes. Defaults to screen layer 0, the layer where the scene depth is intact.
//
// The Config block declares mat4 inv_view_proj first, then vec4 cam_pos (the camera world position
// vfx_view_dir subtracts from the far-plane point), then the float params in the exact order
// registered by VFXShaderPrograms.registerDepthPost — std140 offsets are positional.
#moj_import <vfxweaver:shape.glsl>
#moj_import <vfxweaver:dome.glsl>
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
    vec4 cam_pos;
    float anchor_yaw;
    float anchor_pitch;
    float dome_rotation;
    float tile_scale;
    float color_r;
    float color_g;
    float color_b;
    float opacity;
    float distort;
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
    // The resolved texture rect (atlas sub-rect or 0..1), pixel aspect, sheet grid, frame, channel
    // code and a present/preserve flag bitmask (same surface as surface_pattern).
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
    // The sprite/texture pixel size, for the half-texel sheet inset (0.5 / pixels).
    float tex_px_w;
    float tex_px_h;
    // Projection mode: 0 = patch (gnomonic decal, the default), 1 = fill (three orthographic
    // charts). Appended last so no earlier std140 offset shifts.
    float sky_mode;
};

out vec4 fragColor;

// Coverage of a single (non-repeating) tile: falls off over `softness` (cell units) outside the
// [0,1] tile. A procedural figure is bounded by its SDF radius; a texture cell is not, and the
// clamps inside vfx_texture_sheet_uv would smear the cell's edge texel across the dome. Copied
// from surface_pattern (the fix is consumer-owned; the shared shape library stays unbounded).
float vfx_pattern_tile_coverage(vec2 uv, float softness) {
	vec2 outside = max(abs(uv - vec2(0.5)) - vec2(0.5), vec2(0.0));
	return 1.0 - clamp(max(outside.x, outside.y) / max(softness, 1.0e-4), 0.0, 1.0);
}

// The shared pattern evaluation for one cell: the shape coverage and the texture addressing, both
// consumer-owned (see vfx_pattern_tile_coverage). Returns (bodyCoverage, patternRGB) so the caller
// applies `opacity` once and, in fill mode, blends several charts before compositing.
vec4 vfx_sky_pattern_eval(vec2 cell) {
    // The shared shape library owns every figure's SDF, the fill, the rotation and the repeat
    // modifier.
    vec4 shape0 = vec4(radius, radius_x, radius_y, half_width);
    vec4 shape1 = vec4(half_height, corner_radius, sides, rotation);
    float shapeCoverage = vfx_shape_pattern_coverage(int(shape + 0.5), int(fill + 0.5), cell,
        vec2(repeat_x, repeat_y), shape0, shape1, stroke_width, softness);

    // Textured figure (pattern.texture): the texture is the figure and an authored figure becomes
    // its mask. Same addressing as surface_pattern: rotate -> repeat -> cell, half-texel sheet
    // inset, preserve aspect, channel coverage. tex_flags bits: 1 = texture authored, 2 = resolved,
    // 4 = preserve aspect; an authored-but-unresolved texture is fail-closed (coverage 0).
    vec3 patternRGB = vec3(color_r, color_g, color_b);
    float bodyCoverage = shapeCoverage;
    if (mod(floor(tex_flags), 2.0) >= 0.5) {
        float texCoverage = 0.0;
        if (mod(floor(tex_flags / 2.0), 2.0) >= 0.5) {
            vec2 uv = vfx_shape_cell(cell, vec2(repeat_x, repeat_y), rotation) + 0.5;
            float tileCov = 1.0;
            if (repeat_x <= 1.0 && repeat_y <= 1.0) {
                tileCov = vfx_pattern_tile_coverage(uv, softness);
                uv = clamp(uv, 0.0, 1.0);
            }
            if (mod(floor(tex_flags / 4.0), 2.0) >= 0.5) {
                uv = vfx_texture_aspect(uv, tex_aspect * (tex_rows / max(tex_cols, 1.0)));
            }
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
    return vec4(bodyCoverage, patternRGB);
}

// The cheap sine warp on a cell coordinate (patch/fill modes). Same shape as surface_pattern's
// distort.
vec2 vfx_sky_pattern_distort(vec2 cell) {
    if (distort == 0.0) {
        return cell;
    }
    float phase = cell.x + cell.y;
    return cell + distort * vec2(sin(phase * 0.7 + time * 0.05), cos(phase * 0.7 - time * 0.05));
}

void main() {
    vec4 base = texture(InSampler, texCoord);

    // Sky only. A real surface, the hand or the GUI is not sky and passes through untouched, so a
    // sky_pattern can never paint over them (spec §3.1).
    float sceneDepth = texture(DepthSampler, texCoord).r;
    if (!VFX_DEPTH_IS_SKY(sceneDepth)) {
        fragColor = base;
        return;
    }

    // View direction, spun about world Y by dome_rotation so an image can be locked to the rotating
    // star sphere (spec §3.2); the default 0 is world-fixed.
    vec3 dir = vfx_view_dir(texCoord, inv_view_proj, cam_pos.xyz);
    float rot = radians(dome_rotation);
    float cr = cos(rot);
    float sr = sin(rot);
    vec3 spun = vec3(cr * dir.x - sr * dir.z, dir.y, sr * dir.x + cr * dir.z);

    int mode = int(sky_mode + 0.5);
    float bodyCoverage;
    vec3 patternRGB;

    if (mode == 1) {
        // fill: three orthographic charts. Blend coverage and colour (never the UVs — they are
        // incomparable frames), and renormalise the colour by the blended coverage so texels stay
        // saturated inside the crossfade ribbons. The anchor is a tiling phase shift in cell units
        // (a knob, not a position); dome_rotation already spins the whole field via `spun`.
        vec2 cellX;
        vec2 cellY;
        vec2 cellZ;
        vec3 w3;
        vfx_dome_fill_cells(spun, tile_scale, 8.0, cellX, cellY, cellZ, w3);
        vec2 phase = vec2(anchor_yaw, anchor_pitch) * (1.0 / 90.0);
        vec4 rX = vfx_sky_pattern_eval(vfx_sky_pattern_distort(cellX + phase));
        vec4 rY = vfx_sky_pattern_eval(vfx_sky_pattern_distort(cellY + phase));
        vec4 rZ = vfx_sky_pattern_eval(vfx_sky_pattern_distort(cellZ + phase));
        bodyCoverage = w3.x * rX.x + w3.y * rY.x + w3.z * rZ.x;
        patternRGB = (w3.x * rX.x * rX.yzw + w3.y * rY.x * rY.yzw + w3.z * rZ.x * rZ.yzw)
            / max(bodyCoverage, 1.0e-4);
    } else {
        // patch (mode 0, the default): a gnomonic decal at the anchor. w <= 0 is on or behind the
        // decal horizon, so the pixel is cleanly discarded (no wrap, no built-in clip).
        // `facing > 1.0e-3` is the only bound: the decal runs out to the tangent-plane horizon.
        // Add a mask for a clean edge.
        vec3 anchor = vfx_dome_anchor_dir(anchor_yaw, anchor_pitch);
        float facing;
        vec2 cell = vfx_dome_patch_cell(spun, anchor, anchor_yaw, tile_scale, facing);
        if (facing > 1.0e-3) {
            vec4 r = vfx_sky_pattern_eval(vfx_sky_pattern_distort(cell));
            bodyCoverage = r.x;
            patternRGB = r.yzw;
        } else {
            bodyCoverage = 0.0;
            patternRGB = vec3(color_r, color_g, color_b);
        }
    }

    float coverage = clamp(bodyCoverage * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
    fragColor = vec4(mix(base.rgb, patternRGB, coverage), base.a);
}

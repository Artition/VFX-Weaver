#moj_import <vfxweaver:camera.glsl>

vec3 vfx_view_dir(vec2 uv, mat4 invViewProj, vec3 camPos) {
    vec3 farWorld = vfx_world_from_depth(uv, VFX_DEPTH_FAR_RAW, invViewProj);
    return normalize(farWorld - camPos);
}

vec2 vfx_dome_uv(vec3 direction) {
    float yaw = degrees(atan(-direction.x, direction.z));
    float pitch = degrees(-asin(clamp(direction.y, -1.0, 1.0)));
    return vec2((yaw + 180.0) / 360.0, (pitch + 90.0) / 180.0);
}

// Anchor direction authored [yaw, pitch] degrees, same convention as vfx_dome_uv
// (yaw 0 = +Z, yaw 90 = -X; pitch -90 = zenith, +90 = nadir).
vec3 vfx_dome_anchor_dir(float yawDeg, float pitchDeg) {
    float y = radians(yawDeg);
    float p = radians(pitchDeg);
    float cp = cos(p);
    return vec3(-sin(y) * cp, -sin(p), cos(y) * cp);
}

// PATCH mode: gnomonic (tangent-plane) decal cell at the anchor. The frame matches the local
// equirect axes of vfx_dome_uv (u+ = increasing yaw, v+ = increasing pitch), so a figure keeps
// the orientation it had in legacy mode. tileScale is the cell scale on that plane. In-plane
// distortion is 1/cos(angle from the anchor): ~1.41 at 45 deg, ~2 at 60 deg - keep tileScale <= 1.0
// for clean decals. This is a flat sign, not a whole-sky wrap (use fill for content that must cover
// the sphere), and it has no built-in clip - the decal runs out to the tangent-plane horizon; add a
// mask for a clean edge. w = dot(dir, anchor); w <= 0 is on or behind the decal horizon (a tangent
// plane covers one hemisphere), so the caller discards the pixel as a hard backstop.
vec2 vfx_dome_patch_cell(vec3 dir, vec3 anchor, float yawDeg, float tileScale, out float w) {
    float y = radians(yawDeg);
    vec3 uAxis = vec3(-cos(y), 0.0, -sin(y));   // toward increasing yaw
    vec3 vAxis = cross(anchor, uAxis);          // toward increasing pitch (down)
    w = dot(dir, anchor);
    float wSafe = max(w, 1.0e-4);
    return vec2(dot(dir, uAxis), dot(dir, vAxis)) / (wSafe * clamp(tileScale, 0.01, 4.0));
}

// The vanilla star sphere is drawn with the pose Ry(-90 deg) * Rx(starAngle)
// (SkyRenderer.renderSunMoonAndStars, verified with javap on 26.2 / 26.1.2 / 1.21.11). To lock a
// pattern to the star material, take a WORLD view direction into that pose's local frame with the
// inverse Rx(-starAngle) * Ry(+90 deg): the pattern is then addressed in the star's own frame and
// rotates with the field. `starAngleDegrees` is SkyRenderState.starAngle in degrees.
// Derivation: Ry(90) maps (x, y, z) -> (z, y, -x); Rx(-a) then maps (X, Y, Z) ->
// (X, cos a * Y + sin a * Z, -sin a * Y + cos a * Z).
vec3 vfx_star_local_dir(vec3 dir, float starAngleDegrees) {
    float a = radians(starAngleDegrees);
    float c = cos(a);
    float s = sin(a);
    vec3 y90 = vec3(dir.z, dir.y, -dir.x);
    return vec3(y90.x, c * y90.y + s * y90.z, -s * y90.y + c * y90.z);
}

// FILL mode: whole-sphere tiling by three orthographic charts blended with a sharpened partition
// of unity ("triplanar on a sphere"). No pole convergence and no seam anywhere; `repeat` tiling
// lives inside each chart. `sharp` trades the blend-ribbon width against crossfade ghosting:
// 8.0 gives roughly +/-8 deg ribbons along the |x|=|y|, |y|=|z|, |z|=|x| great circles, and within
// about 40 deg of a chart pole (the zenith included) the own-chart weight is > 0.9, i.e. a single
// chart with no blending. uv units: 1.0 = 90 deg from the chart pole; near a pole the chart is
// isometric (uv radius = sin(angle)), so tileScale is the tile half-size in those units.
void vfx_dome_fill_cells(vec3 dir, float tileScale, float sharp,
                         out vec2 cellX, out vec2 cellY, out vec2 cellZ, out vec3 w) {
    vec3 a = abs(dir);
    w = pow(a, vec3(sharp));
    w /= (w.x + w.y + w.z);
    float inv = 1.0 / clamp(tileScale, 0.01, 4.0);
    cellX = vec2(dir.z, dir.y) * inv;   // chart along X
    cellY = vec2(dir.x, dir.z) * inv;   // chart along Y (zenith/nadir at its centre)
    cellZ = vec2(dir.x, dir.y) * inv;   // chart along Z
}



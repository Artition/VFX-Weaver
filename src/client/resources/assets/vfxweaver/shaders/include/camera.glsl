// Shared depth/world reconstruction and camera-facing surface normal (design 2026-09-20, debt fix).
//
// The reversed-depth world recipe was duplicated inline in field.glsl and surface_pattern.fsh; it
// lives here once. No uniforms are declared here: the caller passes its own inverse view-projection
// matrix, so this include can be imported by a pass whose Config block names it `inv_view_proj`
// (surface_pattern) and by the field library's `fld_inv_view_proj`.
//
// Verified recipe (depth findings): with 26.x reversed depth the sampled value is already NDC z
// (near = 1, far = 0) and is fed to the inverse as-is.

// Reconstructs the world position of the visible surface at screen uv for the given NDC depth.
vec3 vfx_world_from_depth(vec2 uv, float depthNdc, mat4 invViewProj) {
	vec4 clip = vec4(uv * 2.0 - 1.0, depthNdc, 1.0);
	vec4 world = invViewProj * clip;
	return world.xyz / world.w;
}

// Outward normal of the visible surface at `world`, forced to face the eye.
//
// A depth-derived normal has an ambiguous sign; `eyeToFace` is (world - eye) and resolves it: when
// the crossed normal points away from the eye (dot > 0) it is flipped. `dX`/`dY` are the surface's
// tangents (screen-space derivatives or finite differences of `world`). The 1e-6 z term keeps the
// cross product non-zero on a perfectly flat screen-space gradient.
vec3 vfx_camera_normal(vec3 world, vec3 dX, vec3 dY, vec3 eyeToFace) {
	vec3 n = normalize(cross(dX, dY) + vec3(0.0, 0.0, 1.0e-6));
	return dot(n, eyeToFace) > 0.0 ? -n : n;
}

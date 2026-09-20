// Shared depth/world reconstruction and robust surface normal (design 2026-09-20: normal fix).
//
// The reversed-depth world recipe was duplicated inline in field.glsl and surface_pattern.fsh; it
// lives here once. No uniforms are declared here: the caller passes its own inverse view-projection
// matrix and its own depth sampler, so this include can be imported by a pass whose Config block
// names the matrix `inv_view_proj` (surface_pattern) and by the field library's `fld_inv_view_proj`.
//
// Verified recipe (depth findings): with 26.x reversed depth the sampled value is already NDC z
// (near = 1, far = 0) and is fed to the inverse as-is.

// Reconstructs the world position of the visible surface at screen uv for the given NDC depth.
vec3 vfx_world_from_depth(vec2 uv, float depthNdc, mat4 invViewProj) {
	vec4 clip = vec4(uv * 2.0 - 1.0, depthNdc, 1.0);
	vec4 world = invViewProj * clip;
	return world.xyz / world.w;
}

// Picks the tangent along one screen axis from its two one-sided world steps (screen +axis).
//
// Both sides on the same surface give two nearly equal steps, so their sum is the central
// difference. A face edge, a depth discontinuity or the sky makes one side jump to a different
// surface; the relative disagreement then exceeds `depthSlack` and the shorter (same-surface) step
// is used instead of a corrupted central difference. A missing side (its tap is not a real
// surface) is skipped. Returns vec3(0.0) when neither side is usable.
vec3 vfx_surface_step(vec3 forward, vec3 backward, bool hasForward, bool hasBackward, float depthSlack) {
	if (hasForward && hasBackward) {
		float magForward = length(forward);
		float magBackward = length(backward);
		float scale = max(magForward, magBackward) + 1.0e-6;
		if (length(forward - backward) <= depthSlack * scale) {
			return forward + backward;
		}
		return magForward <= magBackward ? forward : backward;
	}
	return hasForward ? forward : (hasBackward ? backward : vec3(0.0));
}

// Robust outward (camera-facing) normal of the visible surface at `uv`.
//
// Screen-space derivatives (dFdx/dFdy) of the reconstructed world position are unstable: they are
// noisy, blow up at grazing angles and, at a face edge or silhouette, mix two surfaces (or the sky
// at depth 0, whose reconstruction is the far plane), so the normal flips between orientations.
// This reconstructs the tangents from one-texel neighbour depth taps instead and rejects the
// across-edge side per axis (the aura code's relative-slack idea: `depthSlack` = 0.5, `1.0e-6` =
// the reversed-depth sky floor), so an edge pixel falls back to the valid one-sided tangent instead
// of a corrupted cross product. The result is forced to face the eye (a depth-derived normal has an
// ambiguous sign).
vec3 vfx_depth_normal(sampler2D depthTex, vec2 uv, float depthNdc, mat4 invViewProj, vec2 texel, vec3 eye) {
	vec3 world = vfx_world_from_depth(uv, depthNdc, invViewProj);
	float dR = texture(depthTex, uv + vec2(texel.x, 0.0)).r;
	float dL = texture(depthTex, uv - vec2(texel.x, 0.0)).r;
	float dU = texture(depthTex, uv + vec2(0.0, texel.y)).r;
	float dD = texture(depthTex, uv - vec2(0.0, texel.y)).r;
	bool okR = dR > 1.0e-6;
	bool okL = dL > 1.0e-6;
	bool okU = dU > 1.0e-6;
	bool okD = dD > 1.0e-6;

	vec3 stepR = okR ? vfx_world_from_depth(uv + vec2(texel.x, 0.0), dR, invViewProj) - world : vec3(0.0);
	vec3 stepL = okL ? world - vfx_world_from_depth(uv - vec2(texel.x, 0.0), dL, invViewProj) : vec3(0.0);
	vec3 stepU = okU ? vfx_world_from_depth(uv + vec2(0.0, texel.y), dU, invViewProj) - world : vec3(0.0);
	vec3 stepD = okD ? world - vfx_world_from_depth(uv - vec2(0.0, texel.y), dD, invViewProj) : vec3(0.0);

	vec3 tangentU = vfx_surface_step(stepR, stepL, okR, okL, 0.5);
	vec3 tangentV = vfx_surface_step(stepU, stepD, okU, okD, 0.5);
	vec3 n = cross(tangentU, tangentV);
	if (dot(n, n) <= 1.0e-12) {
		n = vec3(0.0, 0.0, 1.0e-6);
	}
	n = normalize(n);
	return dot(n, world - eye) > 0.0 ? -n : n;
}

// Snaps an arbitrary normal to the nearest of the six world-axis directions. An axis-aligned block
// face has an exact axis normal, so this quantisation is exact for it and removes the residual
// derivative noise; the dominant-axis priority (Y, then X, then Z) matches the projection choice.
vec3 vfx_snap_normal(vec3 n) {
	vec3 a = abs(n);
	if (a.y >= a.x && a.y >= a.z) {
		return vec3(0.0, n.y >= 0.0 ? 1.0 : -1.0, 0.0);
	}
	if (a.x >= a.z) {
		return vec3(n.x >= 0.0 ? 1.0 : -1.0, 0.0, 0.0);
	}
	return vec3(0.0, 0.0, n.z >= 0.0 ? 1.0 : -1.0);
}

// Face id of a snapped axis normal (spec §2): 0 up, 1 down, 2 north, 3 south, 4 west, 5 east.
int vfx_face_id(vec3 n) {
	if (n.y != 0.0) {
		return n.y > 0.0 ? 0 : 1;
	}
	if (n.x != 0.0) {
		return n.x > 0.0 ? 5 : 4;
	}
	return n.z > 0.0 ? 3 : 2;
}

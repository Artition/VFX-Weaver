// Shared depth/world reconstruction and robust surface normal (design 2026-09-20: normal fix).
//
// The world recipe was duplicated inline in field.glsl, surface_pattern.fsh and mask_coverage.fsh;
// it lives here once. No uniforms are declared here: the caller passes its own inverse view-projection
// matrix and its own depth sampler, so this include can be imported by a pass whose Config block
// names the matrix `inv_view_proj` (surface_pattern) and by the field library's `fld_inv_view_proj`.
//
// Per-node depth convention (depth findings, proven with javap against the real client jars). The
// raw value in the depth buffer is window depth in [0,1] on every node, but the NDC z it maps to
// differs:
//   * 26.2 calls glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE) and builds a near/far-swapped
//     projection, so the raw value IS NDC z with near = 1, far = 0 (reversed);
//   * 26.1.2 and 1.21.11 build a standard projection (no clip control), so raw 0 = near, raw 1 =
//     far and NDC z = raw * 2 - 1 (standard).
// VFXShaderPrograms injects the VFX_DEPTH_REVERSED define (1 on 26.2, 0 otherwise) into every
// pipeline that imports this include, so one shader source serves all nodes. A pipeline that reads
// depth but forgets the define gets the standard convention by default.
#ifndef VFX_DEPTH_REVERSED
#define VFX_DEPTH_REVERSED 0
#endif

#if VFX_DEPTH_REVERSED
#define VFX_DEPTH_TO_NDC(rawDepth) (rawDepth)
#define VFX_DEPTH_IS_SKY(rawDepth) ((rawDepth) <= 1.0e-6)
#define VFX_DEPTH_NEAR_RAW 1.0
#define VFX_DEPTH_FAR_RAW 0.0
#else
#define VFX_DEPTH_TO_NDC(rawDepth) ((rawDepth) * 2.0 - 1.0)
#define VFX_DEPTH_IS_SKY(rawDepth) ((rawDepth) >= 1.0 - 1.0e-6)
#define VFX_DEPTH_NEAR_RAW 0.0
#define VFX_DEPTH_FAR_RAW 1.0
#endif

// Reconstructs the world position of the visible surface at screen uv for the given *raw* sampled
// depth (the value in the depth buffer; the per-node convention is applied inside).
vec3 vfx_world_from_depth(vec2 uv, float rawDepth, mat4 invViewProj) {
	vec4 clip = vec4(uv * 2.0 - 1.0, VFX_DEPTH_TO_NDC(rawDepth), 1.0);
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
// noisy, blow up at grazing angles and, at a face edge or silhouette, mix two surfaces (or the sky,
// whose reconstruction is the far plane), so the normal flips between orientations.
// This reconstructs the tangents from one-texel neighbour depth taps instead and rejects the
// across-edge side per axis (the aura code's relative-slack idea: `depthSlack` = 0.5), so an edge
// pixel falls back to the valid one-sided tangent instead of a corrupted cross product. The result
// is forced to face the eye (a depth-derived normal has an ambiguous sign). A tap that is sky is
// skipped via VFX_DEPTH_IS_SKY, so the per-node convention is handled in one place.
vec3 vfx_depth_normal(sampler2D depthTex, vec2 uv, float rawDepth, mat4 invViewProj, vec2 texel, vec3 eye) {
	vec3 world = vfx_world_from_depth(uv, rawDepth, invViewProj);
	float dR = texture(depthTex, uv + vec2(texel.x, 0.0)).r;
	float dL = texture(depthTex, uv - vec2(texel.x, 0.0)).r;
	float dU = texture(depthTex, uv + vec2(0.0, texel.y)).r;
	float dD = texture(depthTex, uv - vec2(0.0, texel.y)).r;
	bool okR = !VFX_DEPTH_IS_SKY(dR);
	bool okL = !VFX_DEPTH_IS_SKY(dL);
	bool okU = !VFX_DEPTH_IS_SKY(dU);
	bool okD = !VFX_DEPTH_IS_SKY(dD);

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

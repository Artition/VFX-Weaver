// Shared shape primitives (owned by the field library, spec §4). Extracted verbatim from
// field.glsl so that the mask coverage prepass can consume the exact same distance functions
// without pulling in the field program's DepthSampler/FieldConfig uniforms. field.glsl imports
// this file; masks import it too. Do not duplicate any distance math elsewhere.

// Parameter slots, matching VFXFieldFn.SHAPE's paramNames order.
#define VFX_SHAPE_CX 0
#define VFX_SHAPE_CY 1
#define VFX_SHAPE_ROT 2
#define VFX_SHAPE_RADIUS 3
#define VFX_SHAPE_RX 4
#define VFX_SHAPE_RY 5
#define VFX_SHAPE_HW 6
#define VFX_SHAPE_HH 7
#define VFX_SHAPE_CR 8
#define VFX_SHAPE_SIDES 9
#define VFX_SHAPE_STROKE 10
#define VFX_SHAPE_SOFT 11
#define VFX_SHAPE_REPX 12
#define VFX_SHAPE_REPY 13

// Raw distance to a primitive in the shape's local space: negative inside, positive outside.
// Masks perturb their edge in this distance domain (mask design §4) before applying softness.
float vfx_shape_sdf(int primitive, vec2 p, float radius, float rx, float ry, float halfW, float halfH, float corner, float sides) {
	if (primitive == 0) {
		return length(p) - radius;
	}
	if (primitive == 1) {
		float a = max(rx, 1.0e-4);
		float b = max(ry, 1.0e-4);
		return (length(p / vec2(a, b)) - 1.0) * min(a, b);
	}
	if (primitive == 2) {
		vec2 d = abs(p) - vec2(halfW, halfH) + corner;
		return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - corner;
	}
	float n = max(sides, 3.0);
	float seg = 3.14159265 / n;
	float snapped = floor(0.5 + atan(p.y, p.x) / (2.0 * seg)) * 2.0 * seg - atan(p.y, p.x);
	return length(p) * cos(snapped) - radius;
}

// --- 3D world primitives (owned by this library too) ---
// Used wherever a 3D coordinate exists: the masks plan reconstructs a full world position from
// depth and evaluates `sphere`/`box` against it. `p` is centre-relative with the rotation already
// applied. Negative inside, positive outside, in world units.

float vfx_shape_sphere_sdf(vec3 p, float radius) {
	return length(p) - radius;
}

float vfx_shape_box_sdf(vec3 p, vec3 halfExtents) {
	vec3 d = abs(p) - halfExtents;
	return length(max(d, vec3(0.0))) + min(max(d.x, max(d.y, d.z)), 0.0);
}

// Shared 3D dispatcher. Kind 4 = sphere (`radius`), kind 5 = box (`halfExtents`). The masks call
// this instead of re-implementing any 3D distance.
float vfx_shape_sdf_3d(int primitive, vec3 p, float radius, vec3 halfExtents) {
	if (primitive == 4) {
		return vfx_shape_sphere_sdf(p, radius);
	}
	return vfx_shape_box_sdf(p, halfExtents);
}

// Ray/volume entry for the mask aura mode. `origin`/`dir` are world space (`dir` normalized) and
// the volume is the same sphere/box the SDF dispatcher uses, with the same Y rotation. Returns true
// when the forward ray (t >= 0) enters the volume; `tEnter` is the entry distance (0 when the origin
// is inside) and `tExit` the exit distance. On a miss it returns false and still sets `tEnter`/`tExit`
// to a ray parameter near the closest approach (the real part of the sphere roots, or the slab
// "waist" for a box) so the caller can fade the silhouette from both sides of the edge.
bool vfx_volume_ray(int primitive, vec3 origin, vec3 dir, vec3 center, float rotationDeg, vec4 p0, out float tEnter, out float tExit) {
	float c = cos(radians(rotationDeg));
	float s = sin(radians(rotationDeg));
	// The SDF dispatcher rotates world->local; apply the same rotation to the origin and direction.
	vec3 delta = origin - center;
	vec2 dxz = mat2(c, s, -s, c) * delta.xz;
	vec2 ddxz = mat2(c, s, -s, c) * dir.xz;
	vec3 lo = vec3(dxz.x, delta.y, dxz.y);
	vec3 ld = vec3(ddxz.x, dir.y, ddxz.y);
	if (primitive == 4) {
		float radius = max(p0.x, 1.0e-4);
		float b = dot(lo, ld);
		float cc = dot(lo, lo) - radius * radius;
		float disc = b * b - cc;
		if (disc < 0.0) {
			tEnter = max(-b, 0.0);
			tExit = -b;
			return false;
		}
		float sq = sqrt(disc);
		tEnter = -b - sq;
		tExit = -b + sq;
		return tExit >= 0.0;
	}
	vec3 halfExtents = max(p0.xyz, vec3(1.0e-4));
	vec3 inv = 1.0 / ld;
	vec3 t1 = (-halfExtents - lo) * inv;
	vec3 t2 = (halfExtents - lo) * inv;
	vec3 tmin = min(t1, t2);
	vec3 tmax = max(t1, t2);
	tEnter = max(max(tmin.x, tmin.y), tmin.z);
	tExit = min(min(tmax.x, tmax.y), tmax.z);
	if (isnan(tEnter) || isnan(tExit)) {
		tEnter = 0.0;
		tExit = -1.0;
		return false;
	}
	return tEnter <= tExit && tExit >= 0.0;
}

// Mask-facing dispatcher: choose the 2D or 3D shared helper from the kind/space and apply the
// leaf centre/rotation. A mask's coverage shader calls exactly this; it never branches on shapes.
// `p0`/`p1` are the packed per-kind parameters (CIRCLE radius= p0.x; ELLIPSE p0.x/p0.y;
// RECT p0.x/p0.y/p0.z; POLYGON p0.x/p0.y; SPHERE p0.x; BOX p0.x/p0.y/p0.z).
float vfx_shape_sdf_dispatch(int kind, int space, vec2 uv, vec3 world, vec3 center, float rotationDeg, vec4 p0, vec4 p1) {
	float r = radians(rotationDeg);
	if (kind == 4 || kind == 5) {
		float c = cos(r);
		float s = sin(r);
		vec3 local = world - center;
		vec2 rotated = mat2(c, -s, s, c) * local.xz;
		local = vec3(rotated.x, local.y, rotated.y);
		return vfx_shape_sdf_3d(kind, local, p0.x, vec3(p0.x, p0.y, p0.z));
	}
	vec2 local = uv - center.xy;
	float c = cos(r);
	float s = sin(r);
	local = mat2(c, -s, s, c) * local;
	return vfx_shape_sdf(kind, local, p0.x, p0.x, p0.y, p0.x, p0.y, p0.z, p0.y);
}

// Coverage in [0,1] after fill and softness. `fill` 0 = solid, 1 = stroke.
float vfx_shape_coverage(float sdf, int fill, float strokeWidth, float softness) {
	float soft = max(softness, 1.0e-4);
	if (fill == 1) {
		float band = abs(sdf) - max(strokeWidth, 1.0e-4) * 0.5;
		return 1.0 - smoothstep(-soft, soft, band);
	}
	return 1.0 - smoothstep(-soft, soft, sdf);
}

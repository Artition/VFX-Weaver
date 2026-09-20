// Per-pixel field library (spec §2, §9 step 4).
//
// Contract (AGENTS.md UBO field-order rule): the FieldConfig members below are in the same order
// as VFXFieldProgram.write and VFXShaderPrograms.FIELD_CONFIG_SIZE. The function ordinals match
// VFXFieldFn (CONSTANT..WORLD_POS = 0..10), the op ordinals match VFXFieldOp
// (MULTIPLY=0, ADD=1, SUBTRACT=2, MIX=3, MIN=4, MAX=5), the channel codes match
// VFXFieldProgram.channelCode (r=0,g=1,b=2,a=3,luminance=4,none=5), the shape primitive codes
// match VFXFieldProgram.primitiveCode (circle=0,ellipse=1,rect=2,polygon=3) and the fill codes
// match VFXFieldProgram.fillCode (solid=0,stroke=1).
//
// The shape primitives (`vfx_shape_sdf`/`vfx_shape_coverage`, and the 3D `vfx_shape_sdf_3d` with
// `vfx_shape_sphere_sdf`/`vfx_shape_box_sdf`) are the shared implementation masks and
// surface_pattern consume — grids are a shape with `repeat`, rings are an ellipse with
// `fill: stroke`. Do not duplicate them in another shader.
//
// `fld_leaf_count == 0` means "no field": vfx_field_eval returns vec3(1.0).
#moj_import <vfxweaver:camera.glsl>
#moj_import <vfxweaver:texture.glsl>

layout(std140) uniform FieldConfig {
	float fld_uniform;        // uniform-domain value of the input (fade-weighted)
	float fld_depth_valid;    // 1 when the bound depth is usable, else 0
	float fld_leaf_count;
	float fld_weight;         // effect fade weight, 0..1: blends the field against neutral 1.0
	vec4 fld_leaf_fn;
	vec4 fld_leaf_space;      // 0 = screen, 1 = world
	vec4 fld_leaf_channel;
	vec4 fld_leaf_primitive;  // circle=0, ellipse=1, rect=2, polygon=3
	vec4 fld_leaf_fill;       // solid=0, stroke=1
	// Four parameter vec4 per leaf (MAX_PARAMS = 16); only `shape` uses all of them.
	vec4 fld_l0_p0; vec4 fld_l0_p1; vec4 fld_l0_p2; vec4 fld_l0_p3;
	vec4 fld_l1_p0; vec4 fld_l1_p1; vec4 fld_l1_p2; vec4 fld_l1_p3;
	vec4 fld_l2_p0; vec4 fld_l2_p1; vec4 fld_l2_p2; vec4 fld_l2_p3;
	vec4 fld_l3_p0; vec4 fld_l3_p1; vec4 fld_l3_p2; vec4 fld_l3_p3;
	float fld_curve_count;
	vec4 fld_curve0;          // (t0, v0, t1, v1)
	vec4 fld_curve1;
	vec4 fld_curve2;
	vec4 fld_curve3;
	float fld_prog0;
	float fld_prog1;
	float fld_prog2;
	float fld_prog3;
	float fld_prog4;
	float fld_prog5;
	float fld_prog6;
	float fld_prog7;
	mat4 fld_inv_view_proj;
	vec4 fld_camera_pos;      // xyz
	vec4 fld_inv_size;        // xy = 1 / screen size
};

uniform sampler2D DepthSampler;
uniform sampler2D fld_tex0;

// --- generic uniform accessors (no arrays: std140 + Std140Builder have no array support) ---

float vfx_leaf_fn(int i) {
	if (i == 0) return fld_leaf_fn.x;
	if (i == 1) return fld_leaf_fn.y;
	if (i == 2) return fld_leaf_fn.z;
	return fld_leaf_fn.w;
}

int vfx_leaf_space(int i) {
	if (i == 0) return int(fld_leaf_space.x + 0.5);
	if (i == 1) return int(fld_leaf_space.y + 0.5);
	if (i == 2) return int(fld_leaf_space.z + 0.5);
	return int(fld_leaf_space.w + 0.5);
}

int vfx_leaf_channel(int i) {
	if (i == 0) return int(fld_leaf_channel.x + 0.5);
	if (i == 1) return int(fld_leaf_channel.y + 0.5);
	if (i == 2) return int(fld_leaf_channel.z + 0.5);
	return int(fld_leaf_channel.w + 0.5);
}

int vfx_leaf_primitive(int i) {
	if (i == 0) return int(fld_leaf_primitive.x + 0.5);
	if (i == 1) return int(fld_leaf_primitive.y + 0.5);
	if (i == 2) return int(fld_leaf_primitive.z + 0.5);
	return int(fld_leaf_primitive.w + 0.5);
}

int vfx_leaf_fill(int i) {
	if (i == 0) return int(fld_leaf_fill.x + 0.5);
	if (i == 1) return int(fld_leaf_fill.y + 0.5);
	if (i == 2) return int(fld_leaf_fill.z + 0.5);
	return int(fld_leaf_fill.w + 0.5);
}

// Parameter group g (0..3) of leaf i holds slots g*4 .. g*4+3.
vec4 vfx_leaf_pgroup(int i, int g) {
	if (i == 0) {
		if (g == 0) return fld_l0_p0;
		if (g == 1) return fld_l0_p1;
		if (g == 2) return fld_l0_p2;
		return fld_l0_p3;
	}
	if (i == 1) {
		if (g == 0) return fld_l1_p0;
		if (g == 1) return fld_l1_p1;
		if (g == 2) return fld_l1_p2;
		return fld_l1_p3;
	}
	if (i == 2) {
		if (g == 0) return fld_l2_p0;
		if (g == 1) return fld_l2_p1;
		if (g == 2) return fld_l2_p2;
		return fld_l2_p3;
	}
	if (g == 0) return fld_l3_p0;
	if (g == 1) return fld_l3_p1;
	if (g == 2) return fld_l3_p2;
	return fld_l3_p3;
}

// The first four parameters: what every non-shape function reads.
vec4 vfx_leaf_p(int i) {
	return vfx_leaf_pgroup(i, 0);
}

float vfx_leaf_param(int i, int slot) {
	vec4 g = vfx_leaf_pgroup(i, slot / 4);
	int c = slot - (slot / 4) * 4;
	if (c == 0) return g.x;
	if (c == 1) return g.y;
	if (c == 2) return g.z;
	return g.w;
}

float vfx_prog(int k) {
	if (k == 0) return fld_prog0;
	if (k == 1) return fld_prog1;
	if (k == 2) return fld_prog2;
	if (k == 3) return fld_prog3;
	if (k == 4) return fld_prog4;
	if (k == 5) return fld_prog5;
	if (k == 6) return fld_prog6;
	return fld_prog7;
}

vec4 vfx_curve_group(int g) {
	if (g == 0) return fld_curve0;
	if (g == 1) return fld_curve1;
	if (g == 2) return fld_curve2;
	return fld_curve3;
}

float vfx_curve_t(int i) {
	vec4 g = vfx_curve_group(i / 2);
	return (i % 2) == 0 ? g.x : g.z;
}

float vfx_curve_v(int i) {
	vec4 g = vfx_curve_group(i / 2);
	return (i % 2) == 0 ? g.y : g.w;
}

float vfx_curve_sample(float u) {
	int count = int(fld_curve_count + 0.5);
	if (count <= 0) return u;
	if (u <= vfx_curve_t(0)) return vfx_curve_v(0);
	if (u >= vfx_curve_t(count - 1)) return vfx_curve_v(count - 1);
	for (int i = 0; i < count - 1; i++) {
		float t0 = vfx_curve_t(i);
		float t1 = vfx_curve_t(i + 1);
		if (u >= t0 && u <= t1) {
			float span = max(t1 - t0, 1.0e-6);
			return mix(vfx_curve_v(i), vfx_curve_v(i + 1), (u - t0) / span);
		}
	}
	return vfx_curve_v(count - 1);
}

// --- depth / world reconstruction (verified recipe, depth findings note) ---

float vfx_raw_depth(vec2 uv) {
	return texture(DepthSampler, uv).r;
}

vec3 vfx_world_pos(vec2 uv) {
	// Raw depth is converted to NDC z per node inside the shared recipe (include/camera.glsl,
	// VFX_DEPTH_REVERSED: 26.2 reversed, 26.1.2/1.21.11 standard). Shared with surface_pattern
	// and mask_coverage via <vfxweaver:camera.glsl> (one recipe, three consumers).
	return vfx_world_from_depth(uv, vfx_raw_depth(uv), fld_inv_view_proj);
}

float vfx_linear_depth(float d, float near, float far) {
	if (far <= near) return d;
	return (near * far) / ((far - near) * d + near);
}

// --- noise (shared with noise_warp.fsh) ---

vec3 vfx_hash33(vec3 p3) {
	p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
	p3 += dot(p3, p3.yxz + 33.33);
	return fract((p3.xxy + p3.yxx) * p3.zyx) * 2.0 - 1.0;
}

float vfx_gnoise(vec3 p) {
	vec3 i = floor(p);
	vec3 f = fract(p);
	vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	return mix(
		mix(mix(dot(vfx_hash33(i), f),
			dot(vfx_hash33(i + vec3(1, 0, 0)), f - vec3(1, 0, 0)), u.x),
			mix(dot(vfx_hash33(i + vec3(0, 1, 0)), f - vec3(0, 1, 0)),
				dot(vfx_hash33(i + vec3(1, 1, 0)), f - vec3(1, 1, 0)), u.x), u.y),
		mix(mix(dot(vfx_hash33(i + vec3(0, 0, 1)), f - vec3(0, 0, 1)),
			dot(vfx_hash33(i + vec3(1, 0, 1)), f - vec3(1, 0, 1)), u.x),
			mix(dot(vfx_hash33(i + vec3(0, 1, 1)), f - vec3(0, 1, 1)),
				dot(vfx_hash33(i + vec3(1, 1, 1)), f - vec3(1, 1, 1)), u.x), u.y),
		u.z);
}

float vfx_fbm(vec3 p, int octaves, float gain, float lacunarity) {
	float sum = 0.0;
	float amplitude = 1.0;
	float norm = 0.0;
	for (int i = 0; i < octaves; i++) {
		sum += amplitude * vfx_gnoise(p);
		norm += amplitude;
		p *= lacunarity;
		amplitude *= gain;
	}
	return norm > 0.0 ? sum / norm * 0.5 + 0.5 : 0.5;
}

// --- shared shape primitives (owned by this library; masks/surface_pattern consume them) ---
// The distance functions and the mask-facing dispatchers live in shapes.glsl so the mask coverage
// prepass can import exactly them without the field program's DepthSampler/FieldConfig uniforms.
#moj_import <vfxweaver:shapes.glsl>

// --- one field leaf ---
// `scale` semantics: a spatial function multiplies its sampling coordinate by `scale` (noise the
// noise input, gradient the projection, curve/texture the coordinate). A LARGER scale therefore
// means FINER detail, and a smaller scale means larger patches.

vec3 vfx_field_leaf(int i, vec2 uv) {
	float fn = vfx_leaf_fn(i);
	int space = vfx_leaf_space(i);
	vec4 p = vfx_leaf_p(i);
	bool geom = fn == 6.0 || fn == 7.0 || fn == 8.0 || fn == 10.0;
	vec3 world = vec3(0.0);
	if (geom || space == 1) {
		if (fld_depth_valid < 0.5) {
			return vec3(1.0);
		}
		world = vfx_world_pos(uv);
	}
	vec2 coord = space == 1 ? world.xz : uv;
	if (fn == 0.0) {
		return vec3(p.x);
	}
	if (fn == 1.0) {
		vec3 samplePos = space == 1 ? world.xyz : vec3(uv, 0.0);
		int octaves = max(1, int(p.y + 0.5));
		return vec3(vfx_fbm(samplePos * max(p.x, 1.0e-4), octaves, p.z, max(p.w, 1.0e-4)));
	}
	if (fn == 2.0) {
		// Shared shape primitive: coverage in [0,1]. `repeat` tiles it (grid); fill stroke + an
		// ellipse is a ring.
		vec2 local = coord - vec2(vfx_leaf_param(i, VFX_SHAPE_CX), vfx_leaf_param(i, VFX_SHAPE_CY));
		float rot = radians(vfx_leaf_param(i, VFX_SHAPE_ROT));
		float c = cos(rot);
		float s = sin(rot);
		local = mat2(c, -s, s, c) * local;
		vec2 repeat = vec2(vfx_leaf_param(i, VFX_SHAPE_REPX), vfx_leaf_param(i, VFX_SHAPE_REPY));
		if (repeat.x > 1.0 || repeat.y > 1.0) {
			local = fract(local * repeat) - 0.5;
		}
		float sdf = vfx_shape_sdf(vfx_leaf_primitive(i), local,
			vfx_leaf_param(i, VFX_SHAPE_RADIUS),
			vfx_leaf_param(i, VFX_SHAPE_RX),
			vfx_leaf_param(i, VFX_SHAPE_RY),
			vfx_leaf_param(i, VFX_SHAPE_HW),
			vfx_leaf_param(i, VFX_SHAPE_HH),
			vfx_leaf_param(i, VFX_SHAPE_CR),
			vfx_leaf_param(i, VFX_SHAPE_SIDES));
		return vec3(vfx_shape_coverage(sdf, vfx_leaf_fill(i),
			vfx_leaf_param(i, VFX_SHAPE_STROKE), vfx_leaf_param(i, VFX_SHAPE_SOFT)));
	}
	if (fn == 3.0) {
		vec2 dir = vec2(cos(p.x), sin(p.x));
		return vec3(clamp(dot(coord, dir) * p.z + p.y, 0.0, 1.0));
	}
	if (fn == 4.0) {
		float u = space == 1 ? fract(coord.x * max(p.x, 1.0e-4)) : clamp(coord.x * max(p.x, 1.0e-4), 0.0, 1.0);
		return vec3(vfx_curve_sample(u));
	}
	if (fn == 5.0) {
		// Shared texture include: rect = the whole texture, no sheet, frame 0. The channel codes
		// (r=0,g=1,b=2,a=3,luminance=4,none=5) are the include's and VFXFieldProgram.channelCode's.
		vec4 tex = vfx_texture_sample(fld_tex0, coord * vec2(p.x, p.y) + vec2(p.z, p.w),
			vec4(0.0, 0.0, 1.0, 1.0), vec2(1.0, 1.0), 0.0, vec2(0.0));
		int channel = vfx_leaf_channel(i);
		if (channel == 5) return tex.rgb;
		return vec3(vfx_texture_channel(tex, channel));
	}
	if (fn == 6.0) {
		return vec3(vfx_linear_depth(vfx_raw_depth(uv), p.x, p.y));
	}
	if (fn == 7.0) {
		vec2 texelStep = fld_inv_size.xy;
		float d0 = vfx_linear_depth(vfx_raw_depth(uv), p.x, p.y);
		float dx = vfx_linear_depth(vfx_raw_depth(uv + vec2(texelStep.x, 0.0)), p.x, p.y) - d0;
		float dy = vfx_linear_depth(vfx_raw_depth(uv + vec2(0.0, texelStep.y)), p.x, p.y) - d0;
		float range = max(abs(p.y - p.x), 1.0e-4);
		return vec3(clamp(length(vec2(dx, dy)) / range, 0.0, 1.0));
	}
	if (fn == 8.0) {
		// Same robust normal as surface_pattern: neighbouring depth taps, not screen derivatives.
		vec3 normal = vfx_depth_normal(DepthSampler, uv, vfx_raw_depth(uv), fld_inv_view_proj,
			fld_inv_size.xy, fld_camera_pos.xyz);
		vec3 axis = normalize(vec3(p.x, p.y, p.z) + vec3(1.0e-6));
		float facing = dot(normal, axis);
		float softness = 0.05;
		return vec3(smoothstep(p.w - softness, p.w + softness, facing));
	}
	if (fn == 9.0) {
		return vec3(uv, 0.0);
	}
	return world;
}

// --- composition (post-order program, mixed arity) ---

void vfx_combine(int op, inout vec3 stack[4], inout int sp) {
	if (op == 3) {
		vec3 f = stack[--sp];
		vec3 b = stack[--sp];
		vec3 a = stack[--sp];
		stack[sp++] = mix(a, b, f.x);
	} else {
		vec3 b = stack[--sp];
		vec3 a = stack[--sp];
		vec3 r = a * b;
		if (op == 1) r = a + b;
		else if (op == 2) r = a - b;
		else if (op == 4) r = min(a, b);
		else if (op == 5) r = max(a, b);
		stack[sp++] = r;
	}
}

vec3 vfx_field_eval(vec2 uv) {
	if (fld_leaf_count < 0.5) {
		return vec3(1.0);
	}
	vec3 stack[4];
	int sp = 0;
	for (int k = 0; k < 8; k++) {
		float c = vfx_prog(k);
		if (isnan(c)) {
			break;
		}
		if (c <= -1.0) {
			stack[sp++] = vfx_field_leaf(int(-c) - 1, uv);
		} else {
			vfx_combine(int(c), stack, sp);
		}
	}
	return sp > 0 ? stack[0] : vec3(1.0);
}

// The effective multiplier of a field-capable input: the fade-weighted uniform value times the
// per-pixel field, itself blended against its neutral value (1.0) by the fade weight. At weight 0
// this is exactly 1.0 (a field-driven input fades out just like a plain one); at weight 1 it is
// `fld_uniform * field`; in between it moves monotonically between the two.
float vfx_field_intensity(vec2 uv) {
	float field = mix(1.0, vfx_field_eval(uv).x, fld_weight);
	return fld_uniform * field;
}

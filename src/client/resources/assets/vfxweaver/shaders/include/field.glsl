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

// The declarations above are per-effect state, so they cannot be shared by a fused pass; the
// maths that closes over them lives in field_body.glsl and is imported here, after them, to keep
// the resolved order identical to the single file this was split from.
#moj_import <vfxweaver:field_body.glsl>


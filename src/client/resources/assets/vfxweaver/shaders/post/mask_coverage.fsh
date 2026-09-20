#version 330

// The coverage prepass (spec §4, expanded design). Run at screen layer 0, once per distinct mask
// per frame, into a small single-channel target. It is the ONLY masked thing that reads scene
// depth; the consumer (post/mask_apply) just samples the result. World leaves (including the
// sphere/box volumes and custom world shapes) reconstruct the world position with the verified
// recipe (reversed depth, inverse view-rotation-projection). Screen leaves use UV and need no
// depth. A world sphere/box leaf with `volume: "aura"` (shape_volume[i].x) instead casts the
// pixel's view ray at the volume (vfx_volume_ray) and fills it wherever the scene does not occlude
// it, so air and sky inside the volume tint too. Block leaves are NOT evaluated here: their
// coverage was rasterised into GeometryCoverageSampler by the block-geometry draw that runs before
// this pass.
//
// The Config block order MUST match VFXMaskUniforms.writeCoverage(...) exactly. vec3 is written as
// vec4 because Std140Builder pads vec3 to 16 bytes; never use a bare vec3 or a scalar array here.
// The 2D AND 3D shape distance math is NOT defined here: vfx_shape_sdf_dispatch (over the shared
// 2D/3D helpers) comes from the shared shape library (shaders/include/shapes.glsl); a custom GLSL
// plugin is spliced in as `vfx_shape_custom` by VFXMaskShaderVariants (a neutral stub when the mask
// has no plugin leaf).

#moj_import <vfxweaver:shapes.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D GeometryCoverageSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

#define MASK_MAX_PRIMITIVES 8
#define MASK_MAX_CUSTOM_PARTS 3
#define MASK_MAX_CUSTOM_LEAVES 2

layout(std140) uniform Config {
    mat4 invViewProj;
    vec4 camPos;
    float mask_invert;
    float mask_count;
    float mask_needs_depth;
    float mask_time;
    vec4 shape_op[MASK_MAX_PRIMITIVES];       // x=kind, y=op, z=softness, w=field
    vec4 field_params[MASK_MAX_PRIMITIVES];   // x=amount, y=scale, z=seed, w=space
    vec4 shape_center[MASK_MAX_PRIMITIVES];   // xyz=centre, w=rotation
    vec4 shape_params0[MASK_MAX_PRIMITIVES];  // p0..p3
    vec4 shape_params1[MASK_MAX_PRIMITIVES];  // p4..p7
    vec4 shape_misc[MASK_MAX_PRIMITIVES];     // x=fill, y=stroke_width, z=leaf index, w=custom row (-1 if none)
    vec4 shape_volume[MASK_MAX_PRIMITIVES];   // x=world-volume mode (0 surface, 1 aura); yzw unused
    vec4 custom_op[MASK_MAX_CUSTOM_LEAVES];             // x=family(0 composed,1 plugin), y=part count, z/w=op codes
    vec4 custom_kind[MASK_MAX_CUSTOM_LEAVES * MASK_MAX_CUSTOM_PARTS];   // x=kind, y=space, z=rounding, w=repeat
    vec4 custom_center[MASK_MAX_CUSTOM_LEAVES * MASK_MAX_CUSTOM_PARTS]; // xyz=centre, w=rotation
    vec4 custom_params[MASK_MAX_CUSTOM_LEAVES * MASK_MAX_CUSTOM_PARTS]; // x/y/z/w = p0..p3 of the part
};

out vec4 fragColor;

// Screen/3D kinds: CIRCLE=0, ELLIPSE=1, RECT=2, POLYGON=3, SPHERE=4, BOX=5; block leaf=6,
// custom leaf=7 (the last two are kind codes, not VFXMaskShapeKind ordinals). Ops: UNION=0,
// INTERSECTION=1, DIFFERENCE=2. Fields: NONE=0, NOISE=1. Spaces: screen=0, world=1. Fills:
// solid=0, stroke=1.

float hash31(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float valueNoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z) * 2.0 - 1.0;
}

float fieldValue(int field, vec3 samplePos, float scale, float seed) {
    if (field == 1) {
        return valueNoise(samplePos * scale + vec3(seed, seed * 1.7, seed * 2.3));
    }
    return 0.0;
}

// A neutral GLSL-plugin stub. VFXMaskShaderVariants replaces this function with the registered
// `float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1)` in the variant compiled for a
// mask that references plugin shapes; the base shader (no plugin leaf) never calls it with meaning.
float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) {
    return 1.0e6;
}

// A composed custom shape (registered through VFXAPI): its fixed parts are packed per custom leaf
// row. Parts use the shared 2D/3D SDF; ops 0/1/2 are union/intersection/difference.
float vfx_composed_leaf(int row, vec3 world, vec2 uv) {
    int parts = int(custom_op[row].y + 0.5);
    float acc = 0.0;
    for (int p = 0; p < MASK_MAX_CUSTOM_PARTS; p++) {
        if (p >= parts) {
            break;
        }
        int partIndex = row * MASK_MAX_CUSTOM_PARTS + p;
        int kind = int(custom_kind[partIndex].x + 0.5);
        int partSpace = int(custom_kind[partIndex].y + 0.5);
        float rounding = custom_kind[partIndex].z;
        float repeat = custom_kind[partIndex].w;
        vec2 partUv = uv;
        if (repeat > 1.0) {
            vec2 rel = uv - custom_center[partIndex].xy;
            partUv = custom_center[partIndex].xy + (fract(rel * repeat) - 0.5) / repeat;
        }
        float d = vfx_shape_sdf_dispatch(kind, partSpace, partUv, world, custom_center[partIndex].xyz, custom_center[partIndex].w, custom_params[partIndex], vec4(0.0)) - rounding;
        float cov = clamp(0.5 - d / 0.25, 0.0, 1.0);
        if (p == 0) {
            acc = cov;
        } else {
            int op = (p == 1) ? int(custom_op[row].z + 0.5) : int(custom_op[row].w + 0.5);
            if (op == 0) {
                acc = max(acc, cov);
            } else if (op == 1) {
                acc = min(acc, cov);
            } else {
                acc = acc * (1.0 - cov);
            }
        }
    }
    return acc;
}

void main() {
    vec3 world = vec3(0.0);
    // Distance to the visible surface, used by the aura mode's occlusion test. Reversed depth:
    // near=1, far=0, so a sky/far pixel (depth 0) reconstructs at the far plane but must not occlude
    // anything - it is treated as "nothing nearer" and the aura still fills the volume.
    float sceneDist = 1.0e9;
    if (mask_needs_depth > 0.5) {
        // Verified reversed-depth recipe (findings note): depth is already NDC z (near=1, far=0).
        float depthRaw = texture(DepthSampler, texCoord).r;
        vec4 clip = vec4(texCoord * 2.0 - 1.0, depthRaw, 1.0);
        vec4 surfacePoint = invViewProj * clip;
        world = surfacePoint.xyz / surfacePoint.w;
        if (depthRaw > 0.0) {
            sceneDist = length(world - camPos.xyz);
        }
    }

    float accumulator = 0.0;
    for (int i = 0; i < MASK_MAX_PRIMITIVES; i++) {
        if (float(i) >= mask_count) {
            break;
        }
        vec4 so = shape_op[i];
        int kind = int(so.x + 0.5);
        int leafSpace = int(field_params[i].w + 0.5);
        float cov;
        if (kind == 6) {
            // Block-geometry leaf: coverage was rasterised by the block-geometry draw before this
            // pass (its actual model geometry, not the voxel cell); no field/softness here.
            cov = texture(GeometryCoverageSampler, texCoord).r;
        } else if (kind == 7) {
            int row = int(shape_misc[i].w + 0.5);
            if (int(custom_op[row].x + 0.5) == 1) {
                // GLSL plugin: a distance like any built-in, so field/softness apply.
                float d = vfx_shape_custom(world, texCoord, shape_params0[i], shape_params1[i]);
                d += fieldValue(int(so.w + 0.5), (leafSpace == 1) ? world : vec3(texCoord, mask_time), field_params[i].y, field_params[i].z) * field_params[i].x;
                cov = clamp(0.5 - d / max(so.z, 1.0e-4), 0.0, 1.0);
            } else {
                // Composed SDF: its parts already apply their own falloff.
                cov = vfx_composed_leaf(row, world, texCoord);
            }
        } else if ((kind == 4 || kind == 5) && shape_volume[i].x > 0.5) {
            // Aura: cast the pixel's view ray at the volume. Coverage is the volume's own silhouette,
            // sampled at the deepest point of the chord, masked by scene occlusion. The reconstructed
            // point (the far plane for sky) gives the view direction.
            vec3 viewDir = normalize(world - camPos.xyz);
            float tEnter;
            float tExit;
            vfx_volume_ray(kind, camPos.xyz, viewDir, shape_center[i].xyz, shape_center[i].w, shape_params0[i], tEnter, tExit);
            // A degenerate chord - the sphere tangent at the camera (cc == 0: the radius is bound to
            // the camera distance, so the camera sits ON the surface) or entirely behind it - can
            // leave tExit at 0 or a rounding pair of tEnter. Such a chord is not a visible volume;
            // sampling it would put the point on the boundary (SDF distance 0) and paint a spurious
            // half tint over the whole view. Reject it.
            float chordEps = 1.0e-4 * max(1.0, length(shape_center[i].xyz - camPos.xyz));
            if (tExit <= chordEps) {
                cov = 0.0;
            } else {
                // Sample the volume SDF at the midpoint of the visible chord - the sphere's closest
                // approach (the chord centre for a box) - clamped into the forward chord so a camera
                // inside the volume samples at itself (still inside, full coverage). Deliberately NOT
                // clamped to sceneDist: that sampled the SDF at a point reconstructed from the depth
                // buffer, so depth error became a coverage step and banded the silhouette into rings.
                float tRef = clamp(0.5 * (tEnter + tExit), 0.0, tExit);
                vec3 volumePoint = camPos.xyz + viewDir * tRef;
                float d = vfx_shape_sdf_dispatch(kind, leafSpace, texCoord, volumePoint, shape_center[i].xyz, shape_center[i].w, shape_params0[i], shape_params1[i]);
                if (shape_misc[i].x > 0.5) {
                    // stroke: an outline of width stroke_width around the shape boundary.
                    d = abs(d) - 0.5 * shape_misc[i].y;
                }
                vec3 auraFieldPos = (leafSpace == 1) ? volumePoint : vec3(texCoord, mask_time);
                d += fieldValue(int(so.w + 0.5), auraFieldPos, field_params[i].y, field_params[i].z) * field_params[i].x;
                float softness = max(so.z, 1.0e-4);
                float silhouette = clamp(0.5 - d / softness, 0.0, 1.0);
                // Occlusion: a visible surface nearer than the volume entry hides the aura. The entry
                // is compared against a distance reconstructed from the depth buffer, so the
                // threshold carries a slack proportional to that distance (a fixed world bias cannot
                // survive large distances); the relative slack keeps depth error from banding the
                // edge, and stays well below softness at close range. A camera inside the volume
                // (tEnter <= 0) is never occluded - the volume surrounds it.
                float occluded = 1.0;
                if (tEnter > 0.0) {
                    float depthSlack = sceneDist * 2.0e-3;
                    occluded = clamp(0.5 - (tEnter - sceneDist - depthSlack) / softness, 0.0, 1.0);
                }
                cov = silhouette * occluded;
            }
        } else {
            // The shared library's 2D/3D dispatcher: (kind, space, uv, world, centre, rotation, p0, p1).
            float d = vfx_shape_sdf_dispatch(kind, leafSpace, texCoord, world, shape_center[i].xyz, shape_center[i].w, shape_params0[i], shape_params1[i]);
            if (shape_misc[i].x > 0.5) {
                // stroke: an outline of width stroke_width around the shape boundary.
                d = abs(d) - 0.5 * shape_misc[i].y;
            }
            vec3 fieldPos = (leafSpace == 1) ? world : vec3(texCoord, mask_time);
            d += fieldValue(int(so.w + 0.5), fieldPos, field_params[i].y, field_params[i].z) * field_params[i].x;
            float softness = max(so.z, 1.0e-4);
            cov = clamp(0.5 - d / softness, 0.0, 1.0);
        }
        if (i == 0) {
            accumulator = cov;
        } else {
            int op = int(so.y + 0.5);
            if (op == 0) {
                accumulator = max(accumulator, cov);
            } else if (op == 1) {
                accumulator = min(accumulator, cov);
            } else {
                accumulator = accumulator * (1.0 - cov);
            }
        }
    }
    if (mask_invert > 0.5) {
        accumulator = 1.0 - accumulator;
    }
    fragColor = vec4(clamp(accumulator, 0.0, 1.0), 0.0, 0.0, 1.0);
}

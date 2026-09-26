#version 330

// The coverage prepass (spec §4, expanded design). Run at screen layer 0, once per distinct mask
// per frame, into a small single-channel target. It is the ONLY masked thing that reads scene
// depth; the consumer (post/mask_apply) just samples the result. World leaves (including the
// sphere/box volumes and custom world shapes) reconstruct the world position with the shared
// recipe (include/camera.glsl: raw depth converted to NDC z per node, VFX_DEPTH_REVERSED), so one
// shader source serves 26.2 (reversed) and 26.1.2/1.21.11 (standard). Screen leaves use UV and
// need no depth. A world sphere/box leaf with `volume: "aura"` (shape_volume[i].x) instead casts
// the pixel's view ray at the volume (vfx_volume_ray) and fills it wherever the scene does not
// occlude it, so air and sky inside the volume tint too. Block leaves are NOT evaluated here: their
// coverage was rasterised into GeometryCoverageSampler by the block-geometry draw that runs before
// this pass.
//
// Fail-closed is per leaf: VFXMaskUniforms writes shape_volume[i].y = 1 for a leaf whose world
// binding could not be resolved this frame, and the loop drops only that leaf's coverage (never the
// whole mask). The invert at the end refuses to turn an empty result into full-screen coverage when
// an unresolved leaf could have caused the emptiness.
//
// The Config block order MUST match VFXMaskUniforms.writeCoverage(...) exactly. vec3 is written as
// vec4 because Std140Builder pads vec3 to 16 bytes; never use a bare vec3 or a scalar array here.
// The 2D AND 3D shape distance math is NOT defined here: vfx_shape_sdf_dispatch (over the shared
// 2D/3D helpers) comes from the shared shape library (shaders/include/shapes.glsl); a custom GLSL
// plugin is spliced in as `vfx_shape_custom` by VFXMaskShaderVariants (a neutral stub when the mask
// has no plugin leaf).

#moj_import <vfxweaver:shapes.glsl>
#moj_import <vfxweaver:camera.glsl>
#moj_import <vfxweaver:dome.glsl>

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
#define MASK_MAX_LEAF_DATA_VEC4 8
#define VFX_PLUGIN_AURA_MAX_RANGE 1024.0
#define VFX_PLUGIN_AURA_ENTRY_STEPS 40
#define VFX_PLUGIN_AURA_EXIT_STEPS 16
#define VFX_PLUGIN_AURA_REFINE_STEPS 4
#define VFX_PLUGIN_AURA_MIN_STEP 0.5
#define VFX_PLUGIN_AURA_MAX_STEP 64.0

// Declared per-plugin Lipschitz upper bound for the custom field (|grad d| <= L). Injected into
// the variant prelude (same mechanism as VFX_CUSTOM_HAS_BOUNDS) as the MAX over the variant's
// plugins when any declares one; the default keeps old plugins identical. Values below 1 are
// clamped to 1 - the contract is "distance bound", L is the escape hatch for noisier fields.
#ifndef VFX_CUSTOM_FIELD_LIPSCHITZ
#define VFX_CUSTOM_FIELD_LIPSCHITZ 1.0
#endif

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
    vec4 shape_data[MASK_MAX_PRIMITIVES * MASK_MAX_LEAF_DATA_VEC4];     // per-leaf dynamic data, vec4-packed
};

out vec4 fragColor;

// Screen/3D kinds: CIRCLE=0, ELLIPSE=1, RECT=2, POLYGON=3, SPHERE=4, BOX=5; block leaf=6,
// custom leaf=7 (the last two are kind codes, not VFXMaskShapeKind ordinals). Ops: UNION=0,
// INTERSECTION=1, DIFFERENCE=2. Fields: NONE=0, NOISE=1. Spaces: screen=0, world=1, dome=2. Fills:
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

// Per-leaf dynamic float data. The Config `shape_data` array is vec4-packed; this helper flattens
// it so a GLSL plugin reads its own leaf's K floats (K = MASK_MAX_LEAF_DATA_VEC4 * 4 = 32) as
// vfx_mask_data(vfx_shape_data_base + j). `vfx_shape_data_base` is set by the coverage loop to the
// calling leaf's slice start (i * K) just before vfx_shape_custom is invoked, so the same plugin
// source serves every leaf. Both symbols are declared here (outside the injection markers) and are
// read by the injected plugin; the plugin never declares them. A plugin that ignores them behaves
// exactly as before.
int vfx_shape_data_base = 0;

float vfx_mask_data(int index) {
    return shape_data[index >> 2][index & 3];
}

// The calling leaf's eight animatable params, published with the data base just before the plugin
// call so an injected plugin (and its optional vfx_shape_custom_bounds broad phase) can read the
// same p0/p1 the call site passes to vfx_shape_custom. A plugin that ignores them is unchanged.
vec4 vfx_shape_params0 = vec4(0.0);
vec4 vfx_shape_params1 = vec4(0.0);

// A neutral GLSL-plugin stub. VFXMaskShaderVariants replaces the marked region below with the
// registered `float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1)` in the variant
// compiled for a mask that references plugin shapes; the base shader (no plugin leaf) never calls
// it with meaning. The markers are comments, so the GLSL preprocessor preserves them verbatim.
// >>> vfx_mask_custom_inject:begin
float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) {
    return 1.0e6;
}
// <<< vfx_mask_custom_inject:end

// Optional broad phase declared by the injected plugin. Keeping the fallback outside the marked
// region means an older plugin source remains byte-for-byte unchanged; the variant prelude defines
// VFX_CUSTOM_HAS_BOUNDS only after finding the real function signature.
vec4 vfx_custom_bounds() {
#ifdef VFX_CUSTOM_HAS_BOUNDS
    return vfx_shape_custom_bounds();
#else
    return vec4(0.0, 0.0, 0.0, -1.0);
#endif
}

// A composed custom shape (registered through VFXAPI): its fixed parts are packed per custom leaf
// row. Parts use the shared 2D/3D SDF; ops 0/1/2 are union/intersection/difference. The parts are
// literal-only (the leaf's animatable p0..p7 are not threaded into a composed shape); the leaf's
// softness (so.z) is the composed result's edge falloff.
float vfx_composed_leaf(int row, vec3 world, vec2 uv, float softness) {
    int parts = int(custom_op[row].y + 0.5);
    float falloff = max(softness, 1.0e-4);
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
        float cov = clamp(0.5 - d / falloff, 0.0, 1.0);
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

float vfx_aura_cover(float d, float tEnter, float tExit, float chordEps, float softness, float sceneDist) {
    if (tExit <= chordEps) {
        return 0.0;
    }
    float silhouette = clamp(0.5 - d / softness, 0.0, 1.0);
    // A vanishing chord fades in over the same world-space softness as the silhouette.
    float horizonFade = clamp((tExit - chordEps) / softness, 0.0, 1.0);
    // Depth is reconstructed, so entry occlusion uses a relative rather than fixed slack.
    float occluded = 1.0;
    if (tEnter > 0.0) {
        float depthSlack = sceneDist * 2.0e-3;
        occluded = clamp(0.5 - (tEnter - sceneDist - depthSlack) / softness, 0.0, 1.0);
    }
    return silhouette * occluded * horizonFade;
}

void main() {
    vec3 world = vec3(0.0);
    // Distance to the visible surface, used by the aura mode's occlusion test. Sky/far reconstructs
    // at the far plane but must not occlude anything - it is treated as "nothing nearer" and the
    // aura still fills the volume. VFX_DEPTH_IS_SKY follows the per-node convention.
    float sceneDist = 1.0e9;
    bool isSky = false;
    if (mask_needs_depth > 0.5) {
        // Shared recipe (include/camera.glsl): the raw depth is converted to NDC z per node.
        float depthRaw = texture(DepthSampler, texCoord).r;
        world = vfx_world_from_depth(texCoord, depthRaw, invViewProj);
        isSky = VFX_DEPTH_IS_SKY(depthRaw);
        if (!isSky) {
            sceneDist = length(world - camPos.xyz);
        }
    }

    float accumulator = 0.0;
    // Set when any leaf in range was flagged unresolved (shape_volume[i].y, written per leaf by
    // VFXMaskUniforms): only that leaf's coverage is dropped, never the whole mask. Tracked so the
    // invert below cannot turn an all-unresolved (empty) result into full-screen coverage.
    bool anyUnresolved = false;
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
            // pass (its actual model geometry, not the voxel cell). That coverage is full or absent
            // per fragment, so softness has no meaningful distance to ramp here and the edge is
            // hard; softness/field are ignored for the block family by design.
            cov = texture(GeometryCoverageSampler, texCoord).r;
        } else if (kind == 7) {
            vfx_shape_data_base = i * (MASK_MAX_LEAF_DATA_VEC4 * 4);
            vfx_shape_params0 = shape_params0[i];
            vfx_shape_params1 = shape_params1[i];
            int row = int(shape_misc[i].w + 0.5);
            if (row < 0 || row >= MASK_MAX_CUSTOM_LEAVES) {
                // A malformed/over-cap custom row must contribute nothing, never alias row 0
                // (int(-0.5) == 0 would silently render the first custom shape).
                cov = 0.0;
            } else if (shape_volume[i].x <= 0.5 && isSky) {
                // Surface masks classify only what the depth buffer contains. A far-plane/sky point
                // carries no real surface and an unbounded plugin SDF would otherwise paint it.
                cov = 0.0;
            } else if (int(custom_op[row].x + 0.5) == 1) {
                float softness = max(so.z, 1.0e-4);
                if (shape_volume[i].x > 0.5) {
                    // Aura: march the plugin's raw world-space SDF and reuse the built-in aura maths.
                    vec3 viewDir = normalize(world - camPos.xyz);
                    float tLimit = min(sceneDist + sceneDist * 2.0e-3 + 0.5 * softness, VFX_PLUGIN_AURA_MAX_RANGE);
                    float tStart = 0.0;
                    vec4 bounds = vfx_custom_bounds();
                    bool boundsHit = true;
                    if (bounds.w >= 0.0) {
                        vec3 boundOffset = camPos.xyz - bounds.xyz;
                        float boundB = dot(boundOffset, viewDir);
                        float boundC = dot(boundOffset, boundOffset) - bounds.w * bounds.w;
                        float boundDisc = boundB * boundB - boundC;
                        if (boundDisc < 0.0) {
                            boundsHit = false;
                        } else {
                            float boundRoot = sqrt(boundDisc);
                            float tNear = -boundB - boundRoot;
                            float tFar = -boundB + boundRoot;
                            if (tFar < 0.0) {
                                boundsHit = false;
                            } else {
                                tStart = max(tNear, 0.0);
                                // Past the bounds sphere plus the soft band the field cannot
                                // contribute, so the march never crawls to the scene-distance cap.
                                tLimit = min(tLimit, tFar + softness);
                            }
                        }
                    }
                    if (!boundsHit || tStart >= tLimit) {
                        cov = 0.0;
                    } else {
                        float lip = max(VFX_CUSTOM_FIELD_LIPSCHITZ, 1.0);
                        float t = tStart;
                        float tEnter = -1.0;
                        float tExit = 0.0;
                        float dMin = 0.0;
                        float chordEps = 1.0e-4;
                        // Cone-envelope minimum of the field along the ray: the crossing of the
                        // Lipschitz cones of two consecutive samples lower-bounds the field between
                        // them, so the minimum crossing lower-bounds the ray's signed closest
                        // approach (negative = deepest penetration, positive = miss distance).
                        // Continuous in screen space (kills the softness-spaced banding), exact
                        // for creased fields (max/min of distance bounds), never thinner than the
                        // truth. The step rule (h <= d/L) keeps it >= 0 on misses, <= 0 on hits.
                        float dBound = 1.0e9;
                        float tBound = tStart;
                        float tPrev = tStart;
                        float dPrev = 0.0;
                        bool havePrev = false;
                        for (int s = 0; s < VFX_PLUGIN_AURA_ENTRY_STEPS; s++) {
                            float d = vfx_shape_custom(camPos.xyz + viewDir * t, texCoord, shape_params0[i], shape_params1[i]);
                            if (havePrev) {
                                float dCross = 0.5 * (dPrev + d - lip * (t - tPrev));
                                if (dCross < dBound) {
                                    dBound = dCross;
                                    tBound = tPrev + (dPrev - dCross) / lip;
                                }
                            }
                            // A sample is the field's exact value at its own position, so it seeds the
                            // estimate when no bracket exists yet: with the camera inside the volume the
                            // first sample already saturates, and without this the envelope would keep its
                            // initial value and paint nothing.
                            if (d < dBound) {
                                dBound = d;
                                tBound = t;
                            }
                            if (d <= 0.0) {
                                // False-position entry inside the last bracket: the raw first-inside
                                // sample grid quantises tEnter, and tEnter feeds the occlusion ramp.
                                tEnter = havePrev ? tPrev + (t - tPrev) * dPrev / max(dPrev - d, 1.0e-4) : t;
                                dMin = d;
                                break;
                            }
                            havePrev = true;
                            tPrev = t;
                            dPrev = d;
                            // Even a straight dive from here stays above the soft band, so the rest
                            // of the ray cannot change the answer. Perf only.
                            if (d - lip * (tLimit - t) > softness) {
                                break;
                            }
                            t += clamp(d / lip, VFX_PLUGIN_AURA_MIN_STEP, VFX_PLUGIN_AURA_MAX_STEP);
                            if (t >= tLimit) {
                                break;
                            }
                        }
                        if (tEnter < 0.0) {
                            // Near-miss fade: vfx_aura_cover gives any entered ray >= 0.5 and gave
                            // a miss a hard 0 - a designed-in cliff at the silhouette. The same
                            // cover factors on the envelope bound (a chord shrunk to the closest
                            // approach point) fade the outside over the same world-space softness;
                            // the bound tends to 0 from both sides of the tangent.
                            cov = vfx_aura_cover(dBound, tBound, tBound, chordEps, softness, sceneDist);
                        } else {
                            // t still holds the first inside sample: the exit march must start on a
                            // point proven inside, the false-position tEnter only feeds the gates.
                            float tInside = t;
                            float tPrevIn = t;
                            float dPrevIn = dMin;
                            bool haveIn = false;
                            bool exitFound = false;
                            bool saturated = false;
                            for (int s = 0; s < VFX_PLUGIN_AURA_EXIT_STEPS; s++) {
                                float d = vfx_shape_custom(camPos.xyz + viewDir * t, texCoord, shape_params0[i], shape_params1[i]);
                                if (haveIn) {
                                    float dCross = 0.5 * (dPrevIn + d - lip * (t - tPrevIn));
                                    if (dCross < dBound) {
                                        dBound = dCross;
                                        tBound = tPrevIn + (dPrevIn - dCross) / lip;
                                    }
                                }
                                if (d < dBound) {
                                    dBound = d;
                                    tBound = t;
                                }
                                if (d > 0.0) {
                                    tExit = t;
                                    exitFound = true;
                                    break;
                                }
                                haveIn = true;
                                tPrevIn = t;
                                dPrevIn = d;
                                if (d < dMin) {
                                    dMin = d;
                                }
                                tInside = t;
                                if (dMin <= -0.5 * softness) {
                                    saturated = true;
                                    break;
                                }
                                t += clamp(-d / lip, VFX_PLUGIN_AURA_MIN_STEP, VFX_PLUGIN_AURA_MAX_STEP);
                                if (t >= tLimit) {
                                    break;
                                }
                            }
                            if (saturated) {
                                tExit = tLimit;
                            } else if (exitFound) {
                                for (int s = 0; s < VFX_PLUGIN_AURA_REFINE_STEPS; s++) {
                                    float tMid = 0.5 * (tInside + tExit);
                                    float dMid = vfx_shape_custom(camPos.xyz + viewDir * tMid, texCoord, shape_params0[i], shape_params1[i]);
                                    if (dMid <= 0.0) {
                                        tInside = tMid;
                                    } else {
                                        tExit = tMid;
                                    }
                                }
                            } else {
                                tExit = tLimit;
                            }
                            if (shape_misc[i].x > 0.5) {
                                dBound = abs(dBound) - 0.5 * shape_misc[i].y;
                            }
                            vec3 auraFieldPos = (leafSpace == 1) ? camPos.xyz + viewDir * tBound : vec3(texCoord, mask_time);
                            dBound += fieldValue(int(so.w + 0.5), auraFieldPos, field_params[i].y, field_params[i].z) * field_params[i].x;
                            cov = vfx_aura_cover(dBound, tEnter, tExit, chordEps, softness, sceneDist);
                        }
                    }
                } else {
                    // Surface plugin: classify the depth-reconstructed point, with fill and field.
                    float d = vfx_shape_custom(world, texCoord, shape_params0[i], shape_params1[i]);
                    d += fieldValue(int(so.w + 0.5), (leafSpace == 1) ? world : vec3(texCoord, mask_time), field_params[i].y, field_params[i].z) * field_params[i].x;
                    cov = clamp(0.5 - d / softness, 0.0, 1.0);
                }
            } else {
                // Composed SDF: its parts already apply their own falloff, scaled by the leaf's softness.
                cov = vfx_composed_leaf(row, world, texCoord, so.z);
            }
        } else if (kind == 8) {
            // Sky leaf: the whole sky dome and nothing else. The depth sky test IS the coverage, so a
            // pixel that is not sky contributes zero - a pack that leaves no trustworthy far depth
            // simply never matches, which is the fail-closed behaviour.
            cov = isSky ? 1.0 : 0.0;
        } else if (leafSpace == 2) {
            // Dome space: a 2D shape addressed in equirectangular dome UV, sky-occluded by construction
            // (geometry occupying a dome direction is not sky, so it is never covered). The leaf centre
            // is authored as [yaw, pitch] degrees and mapped into the same UV space as the projection;
            // `rotation` stays the shape's own in-plane rotation.
            vec2 domeUv = vfx_dome_uv(vfx_view_dir(texCoord, invViewProj, camPos.xyz));
            vec2 domeCenter = vec2((shape_center[i].x + 180.0) / 360.0, (shape_center[i].y + 90.0) / 180.0);
            float d = vfx_shape_sdf_dispatch(kind, 0, domeUv, world, vec3(domeCenter, 0.0), shape_center[i].w, shape_params0[i], shape_params1[i]);
            if (shape_misc[i].x > 0.5) {
                d = abs(d) - 0.5 * shape_misc[i].y;
            }
            d += fieldValue(int(so.w + 0.5), vec3(domeUv, mask_time), field_params[i].y, field_params[i].z) * field_params[i].x;
            float softness = max(so.z, 1.0e-4);
            cov = isSky ? clamp(0.5 - d / softness, 0.0, 1.0) : 0.0;
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
                cov = vfx_aura_cover(d, tEnter, tExit, chordEps, softness, sceneDist);
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
        // Per-leaf fail closed: a leaf whose world binding could not be resolved contributes zero
        // coverage and never falls back to its literal default (an unbound screen rect's default is
        // the whole screen). Only this leaf is dropped; the other leaves keep their coverage, so an
        // off-screen entity bound to one screen leaf no longer kills a resolved world leaf beside
        // it. An unresolved leaf can never expand coverage (zero is neutral for union/difference
        // and contracts intersection), and the invert guard below keeps it from filling the screen.
        if (shape_volume[i].y > 0.5) {
            cov = 0.0;
            anyUnresolved = true;
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
        // An empty composed result would invert to full-screen coverage. When that emptiness may
        // come from an unresolved leaf, keep it empty instead of flipping it into "everywhere";
        // inverting a result that some resolved leaf genuinely produced is still allowed.
        if (!(anyUnresolved && accumulator <= 0.0)) {
            accumulator = 1.0 - accumulator;
        }
    }
    fragColor = vec4(clamp(accumulator, 0.0, 1.0), 0.0, 0.0, 1.0);
}

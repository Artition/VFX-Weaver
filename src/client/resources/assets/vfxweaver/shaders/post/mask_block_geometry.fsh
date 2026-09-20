#version 330

// The block-geometry contribution (expanded design). Rendered into the per-mask geometry scratch
// with the vanilla core/position_color vertex shader and the POSITION_COLOR format, so it reuses
// the real block-overlay model path (baked model quads, camera-relative pose, DynamicTransforms/
// Projection). Every rasterised fragment is full coverage; the coverage shader samples this
// scratch as a block leaf's coverage. It is NOT a per-pixel block lookup.
//
// Occlusion: only when the mask's block leaf sets `"occlude": true` (the default) is that leaf's
// geometry depth-tested against the scene. The vertex colour's alpha carries the flag for the
// fragment: the geometry pass emits white with alpha 1 for an occluding leaf and alpha 0 for an
// x-ray one. DepthSampler is the main target's depth view, sampled with texelFetch so the exact
// pixel of this fragment is compared. A fragment farther than the scene surface at that pixel (a
// wall in front) is discarded; the fragment's own surface sits at the scene depth, so a small bias
// keeps it.
//
// The comparison follows the per-node depth convention, injected as the VFX_DEPTH_REVERSED define
// by VFXShaderPrograms (see include/camera.glsl):
//   * reversed (26.2, near = 1, far = 0): a fragment farther than the scene has a SMALLER
//     gl_FragCoord.z, so discard when gl_FragCoord.z < sceneDepth - bias;
//   * standard (26.1.2 / 1.21.11, near = 0, far = 1): a fragment farther has a LARGER z, so discard
//     when gl_FragCoord.z > sceneDepth + bias.
// On a node where the depth recipe is not trusted the CPU pass disables occlusion (emits alpha 0
// for every block), so neither branch is taken there - see VFXPostProcessingManager /
// VFXMaskBlockGeometry.
#ifndef VFX_DEPTH_REVERSED
#define VFX_DEPTH_REVERSED 0
#endif

uniform sampler2D DepthSampler;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a > 0.5) {
        float sceneDepth = texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r;
#if VFX_DEPTH_REVERSED
        if (gl_FragCoord.z < sceneDepth - 1.0e-4) {
            discard;
        }
#else
        if (gl_FragCoord.z > sceneDepth + 1.0e-4) {
            discard;
        }
#endif
    }
    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
}

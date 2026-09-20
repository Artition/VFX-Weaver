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
// x-ray one. DepthSampler is the main target's depth view (reversed depth, GL_ZERO_TO_ONE:
// near = 1, far = 0), sampled with texelFetch so the exact pixel of this fragment is compared.
// A fragment farther than the scene surface at that pixel (a wall in front) is discarded; the
// fragment's own surface sits at the scene depth, so a small bias keeps it.

uniform sampler2D DepthSampler;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a > 0.5) {
        float sceneDepth = texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r;
        if (gl_FragCoord.z < sceneDepth - 1.0e-4) {
            discard;
        }
    }
    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
}

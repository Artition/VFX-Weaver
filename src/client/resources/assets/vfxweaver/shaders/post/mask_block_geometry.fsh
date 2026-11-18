#version 330

// The block-geometry contribution (expanded design). Rendered into the per-mask geometry scratch
// with the world/mask_block_geometry vertex stage and the POSITION_COLOR_TEX format, so it reuses
// the real block-overlay model path (baked model quads, camera-relative pose,
// DynamicTransforms/Projection) and can still read the sprite's own alpha. The coverage shader
// samples this scratch as a block leaf's coverage. It is NOT a per-pixel block lookup.
//
// Vertex colour bits (written by VFXMaskBlockGeometry.passColor):
//   * red   - 1 = the outer (dilated) pass, 0 = the inner pass of a hollow fill;
//   * blue  - 1 = read the block texture atlas alpha (a block_list leaf), 0 = constant coverage of 1
//             (the legacy `block` leaf, whose coverage has always ignored the texture);
//   * alpha - 1 = depth-test this fragment against the scene, 0 = the x-ray look.
//
// Channel encoding. The scratch is RGBA8 and the coverage shader picks one channel by the leaf's
// fill: red = the outer alpha (a solid fill), green = outer * (1 - inner) (the hollow band). It is
// produced by two blended passes rather than a third full-screen one, with the standard
// TRANSLUCENT blend (srcAlpha, 1-srcAlpha):
//   * outer pass writes rgb = (1, 1) and a = outer alpha          -> dst.r = dst.g = outer;
//   * inner pass writes rgb = (1, 0) and a = inner alpha          -> dst.r = outer (inner sits
//     inside the outer geometry, so it can only re-write the same value), dst.g = outer * (1 - inner).
// A solid fill never draws the inner pass, so it reads the outer alpha directly.
//
// Occlusion: only when the mask's block leaf sets `"occlude": true` (the default) is that leaf's
// geometry depth-tested against the scene. DepthSampler is the main target's depth view, sampled
// with texelFetch so the exact pixel of this fragment is compared. A fragment farther than the
// scene surface at that pixel (a wall in front) is discarded; the fragment's own surface sits at the
// scene depth, so a small bias keeps it.
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
// The block texture atlas (minecraft:textures/atlas/blocks.png): the alpha of the listed blocks'
// own sprites, so a cutout leaf, a plant's cross or a pane contributes where its texture does.
uniform sampler2D BlockAtlasSampler;

in vec2 texCoord0;
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
    float coverage = vertexColor.b > 0.5 ? texture(BlockAtlasSampler, texCoord0).a : 1.0;
    // Outer pass: R = G = the (dilated) alpha. Inner pass: R = 1 (it can only re-write the outer
    // value it sits inside), G = 0, which the blend turns into outer * (1 - inner).
    fragColor = vec4(1.0, vertexColor.r > 0.5 ? 1.0 : 0.0, 0.0, coverage);
}

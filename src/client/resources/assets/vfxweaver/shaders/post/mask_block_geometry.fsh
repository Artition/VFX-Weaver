#version 330

// The block-geometry contribution (expanded design). Rendered into the per-mask geometry scratch
// with the world/mask_block_geometry vertex stage and the POSITION_TEX_COLOR format, so it reuses
// the real block-overlay model path (baked model quads, camera-relative vertices, the pass's own
// Projection uniform) and can still read the sprite's own alpha. The coverage shader samples this
// scratch as a block leaf's coverage. It is NOT a per-pixel block lookup.
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
// geometry depth-tested against the scene. DepthSampler is the main target's depth view, sampled with
// texelFetch so the exact pixel of this fragment is compared.
//
// THE COMPARE IS IN WORLD UNITS, NOT IN DEPTH UNITS. The mask draws the very geometry it masks, so
// its surface is coplanar with the block's; a window- or NDC-space bias can never separate the two
// reliably - it is a sliver up close and a whole block at range, which is why the old fixed epsilon
// flickered at coincident faces. Instead both depths are turned back into view distances with the
// frame's own projection: for a perspective projection `zNdc = a + b / distance`, i.e.
// `distance = b / (zNdc - a)`, with (a, b) uploaded by the CPU from the pure projection
// (`a = -m22`, `b = m32`, see VFXFieldEnv.lineariseDepth). The mask is discarded only when it is
// FARTHER than the scene by more than VFX_MASK_DEPTH_TOLERANCE blocks. That tolerance is the whole
// mechanism:
//   * a coincident face (the mask's own block) is off by sub-millimetre rounding, far inside 5 cm, so
//     the mask always wins - no flicker, at any distance and at any angle;
//   * a wall is a whole block (1 m) or more in front, twenty times the tolerance, so it still hides
//     the mask wherever it is more than a sliver on screen.
// Because the margin is a fixed distance, its behaviour does not drift with range the way an NDC
// epsilon does.
//
// The window-to-NDC step follows the per-node depth convention, injected as the VFX_DEPTH_REVERSED
// define by VFXShaderPrograms (see include/camera.glsl):
//   * reversed (26.2, near = 1, far = 0): the raw depth IS NDC z;
//   * standard (26.1.2 / 1.21.11, near = 0, far = 1): NDC z = raw * 2 - 1 (VFX_DEPTH_TO_NDC).
// The linearisation itself is convention-free: it inverts whatever projection the frame used. A pixel
// with no usable scene distance (sky, or a depth that does not linearise to a positive distance) is
// not an occluder, so the mask is kept there. On a node where the depth recipe is not trusted the CPU
// pass disables occlusion (emits alpha 0 for every block), so the branch is not taken at all - see
// VFXPostProcessingManager / VFXMaskBlockGeometry.
#ifndef VFX_DEPTH_REVERSED
#define VFX_DEPTH_REVERSED 0
#endif

// How much farther than the scene the mask may be and still win, in blocks. A coincident face is
// within a millimetre; a wall is a block or more away. 5 cm sits between the two with two orders of
// magnitude of room on the flicker side and twenty times on the wall side.
#ifndef VFX_MASK_DEPTH_TOLERANCE
#define VFX_MASK_DEPTH_TOLERANCE 0.05
#endif

#moj_import <vfxweaver:camera.glsl>
#moj_import <minecraft:projection.glsl>

uniform sampler2D DepthSampler;
// The block texture atlas (minecraft:textures/atlas/blocks.png): the alpha of the listed blocks'
// own sprites, so a cutout leaf, a plant's cross or a pane contributes where its texture does.
uniform sampler2D BlockAtlasSampler;
// x = a, y = b of `zNdc = a + b / distance`, uploaded from the frame's pure projection.
layout(std140) uniform MaskDepth {
    vec4 depthLinear;
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a > 0.5) {
        float sceneRaw = texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r;
#if VFX_DEPTH_REVERSED
        float maskNdc = gl_FragCoord.z;
#else
        float maskNdc = gl_FragCoord.z * 2.0 - 1.0;
#endif
        float sceneNdc = VFX_DEPTH_TO_NDC(sceneRaw);
        float a = depthLinear.x;
        float b = depthLinear.y;
        // Guard the division: a pixel whose depth does not linearise to a positive distance (sky, or a
        // degenerate projection at that sample) is not an occluder, so it cannot hide the mask.
        float maskDivisor = maskNdc - a;
        float sceneDivisor = sceneNdc - a;
        if (abs(maskDivisor) > 1.0e-6 && abs(sceneDivisor) > 1.0e-6) {
            float maskDistance = b / maskDivisor;
            float sceneDistance = b / sceneDivisor;
            if (maskDistance > 0.0 && sceneDistance > 0.0 && maskDistance > sceneDistance + VFX_MASK_DEPTH_TOLERANCE) {
                discard;
            }
        }
    }
    float coverage = vertexColor.b > 0.5 ? texture(BlockAtlasSampler, texCoord0).a : 1.0;
    // Outer pass: R = G = the (dilated) alpha. Inner pass: R = 1 (it can only re-write the outer
    // value it sits inside), G = 0, which the blend turns into outer * (1 - inner).
    fragColor = vec4(1.0, vertexColor.r > 0.5 ? 1.0 : 0.0, 0.0, coverage);
}

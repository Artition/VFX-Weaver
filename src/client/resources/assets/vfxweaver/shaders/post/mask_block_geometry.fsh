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
// geometry depth-tested against the scene. DepthSampler is the main target's depth view, sampled
// with texelFetch so the exact pixel of this fragment is compared. A fragment farther than the
// scene surface at that pixel (a wall in front) is discarded.
//
// The mask's own surface sits exactly where the block's surface is, so this compare used to be a
// coin flip: two pipelines (ours and vanilla's chunk renderer) rasterise the same plane from
// different vertex buffers and matrices, and their window depths differ by a few ulps in either
// direction - which is what let a masked block draw over the mask "sometimes". The fix is a
// clip-space depth bias in the vertex stage (see world/mask_block_geometry.vsh): the mask's clip z is
// pushed VFX_MASK_DEPTH_BIAS of a w toward the camera, with x and y untouched, so the mask surface is
// strictly in front of the block it masks - in both depth conventions, at every distance, and with
// no parallax while the camera moves. The sliver below only has to absorb float quantisation.
//
// Why this cannot leak a wall: the bias is a fraction of NDC depth, which in world terms is ~0.8 mm
// at 4 blocks, ~2 cm at 10 blocks and still under a block at 100 - anything genuinely in front is a
// whole block or more away and keeps occluding. The tolerance below (a few dozen float ulps of the
// depth buffer) is far too small to bridge a block wherever a block's depth is representable, and at
// the very far end, where both surfaces quantise to the same value, the compare keeps the mask, which
// is the wanted answer: a block-sized sliver at that distance is not a wall.
//
// The comparison follows the per-node depth convention, injected as the VFX_DEPTH_REVERSED define
// by VFXShaderPrograms (see include/camera.glsl):
//   * reversed (26.2, near = 1, far = 0): a fragment farther than the scene has a SMALLER
//     gl_FragCoord.z, so discard when gl_FragCoord.z < sceneDepth - VFX_MASK_DEPTH_EPS;
//   * standard (26.1.2 / 1.21.11, near = 0, far = 1): a fragment farther has a LARGER z, so discard
//     when gl_FragCoord.z > sceneDepth + VFX_MASK_DEPTH_EPS.
// On a node where the depth recipe is not trusted the CPU pass disables occlusion (emits alpha 0
// for every block), so neither branch is taken there - see VFXPostProcessingManager /
// VFXMaskBlockGeometry.
#ifndef VFX_DEPTH_REVERSED
#define VFX_DEPTH_REVERSED 0
#endif

// Float quantisation only (about a dozen ulps at z ~ 1, where the buffer is coarsest); the world
// space margin comes from the vertex stage's clip-z bias, not from here.
#ifndef VFX_MASK_DEPTH_EPS
#define VFX_MASK_DEPTH_EPS 1.0e-6
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
        if (gl_FragCoord.z < sceneDepth - VFX_MASK_DEPTH_EPS) {
            discard;
        }
#else
        if (gl_FragCoord.z > sceneDepth + VFX_MASK_DEPTH_EPS) {
            discard;
        }
#endif
    }
    float coverage = vertexColor.b > 0.5 ? texture(BlockAtlasSampler, texCoord0).a : 1.0;
    // Outer pass: R = G = the (dilated) alpha. Inner pass: R = 1 (it can only re-write the outer
    // value it sits inside), G = 0, which the blend turns into outer * (1 - inner).
    fragColor = vec4(1.0, vertexColor.r > 0.5 ? 1.0 : 0.0, 0.0, coverage);
}

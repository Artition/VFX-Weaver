#version 330

// The block-geometry vertex stage. The vanilla core/position_color shader carried this pass until
// the block-list leaf needed the sprite UV; this stage adds the quad's baked atlas UV so the fragment
// stage can read the block texture's own alpha.
//
// One transform only: `Projection` is overridden per draw with the pass's full matrix, which is
// `projection * viewRotation` (no translation), applied to the camera-relative vertices the CPU pass
// emits (blockPos - cameraPos). `ModelViewMat` is deliberately NOT read: the frame's ModelViewMat
// already carries the camera translation, so reading it here would subtract the camera twice - the
// geometry would slide across the blocks as the camera moves instead of staying world-locked. Keeping
// the whole transform in the one uniform the pass owns also keeps it out of the shared
// DynamicTransforms arena, where a slice written for another pass could be read instead.
//
// The quad UVs are absolute atlas UVs (the model bakery applies the sprite's sub-rect when it
// packs them), so the atlas is sampled directly - no per-sprite remap here or in the fragment.
//
// WHY THE CLIP-Z BIAS. The mask draws the very geometry it masks, so its surface is coplanar with
// the block's and the fragment stage's depth compare against the scene would be decided by a few
// float ulps that two different rasterisation paths do not agree on - which let a masked block draw
// over the mask "sometimes". The mask therefore has to sit slightly in FRONT of the surface it
// masks, and it has to do so without moving: biasing the world-space position (moving the vertices
// toward the eye by a couple of centimetres) changes the projected silhouette as the camera moves
// and reads as the mask sliding sideways over the blocks. So the bias is applied to the clip-space
// depth ONLY, after the projection:
//
//     vec4 clip = ProjMat * vec4(Position, 1.0);
//     clip.z -= VFX_MASK_DEPTH_BIAS * clip.w;     // standard: smaller z = nearer
//
// A constant NDC-z offset (`clip.z / clip.w` drops by exactly VFX_MASK_DEPTH_BIAS) with x and y
// byte-identical to the unbiased projection - zero parallax, at any camera motion, for the life of
// the pass. VFX_DEPTH_REVERSED (injected by VFXShaderPrograms for the whole program, see
// include/camera.glsl) flips the sign: on 26.2 near = 1, far = 0, so a nearer fragment has a LARGER
// z and the bias is added.
//
// How much: VFX_MASK_DEPTH_BIAS = 1.0e-6 in NDC z. The disagreement between the two rasterisation
// paths is float precision in the vertices (a few 1e-7 blocks at 4 blocks distance) mapped through
// the projection, i.e. ~1e-8 NDC, and the depth buffer stores float32, whose quantisation near z ~ 1
// is ~6e-8 - both far below 1e-6, so the mask reliably wins at its own surface. In world terms the
// offset grows with distance (a perspective depth is non-linear): ~0.8 mm at 4 blocks, ~2 cm at 10
// blocks, ~0.2 blocks at 100 blocks - still under a block, so a wall one block in front stays in
// front and keeps occluding, at every distance where a masked block is more than a pixel.
#ifndef VFX_DEPTH_REVERSED
#define VFX_DEPTH_REVERSED 0
#endif

#ifndef VFX_MASK_DEPTH_BIAS
#define VFX_MASK_DEPTH_BIAS 1.0e-6
#endif

#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec4 clip = ProjMat * vec4(Position, 1.0);
    // Depth only: x/y stay exactly what the unbiased projection produced.
#if VFX_DEPTH_REVERSED
    clip.z += VFX_MASK_DEPTH_BIAS * clip.w;
#else
    clip.z -= VFX_MASK_DEPTH_BIAS * clip.w;
#endif
    gl_Position = clip;

    texCoord0 = UV0;
    vertexColor = Color;
}

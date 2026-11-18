#version 330

// The block-geometry vertex stage. The vanilla core/position_color shader carried this pass until
// the block-list leaf needed the sprite UV; this stage adds the quad's baked atlas UV so the fragment
// stage can read the block texture's own alpha.
//
// One transform only: `Projection` is overridden per draw with the pass's full matrix, which is
// `projection * bob * viewRotation` (no translation), applied to the camera-relative vertices the CPU
// pass emits (blockPos - cameraPos). `ModelViewMat` is deliberately NOT read: the frame's ModelViewMat
// already carries the camera translation, so reading it here would subtract the camera twice - the
// geometry would slide across the blocks as the camera moves instead of staying world-locked. Keeping
// the whole transform in the one uniform the pass owns also keeps it out of the shared
// DynamicTransforms arena, where a slice written for another pass could be read instead.
//
// The geometry is emitted EXACTLY where the block is - no positional or clip-space nudge. Ordering
// against the mask's own blocks is decided in the fragment stage, in world units (see
// post/mask_block_geometry.fsh): a depth bias here could only be expressed in NDC, which is a sliver
// up close and a whole block at range, so it either flickers or leaks a wall depending on where you
// look. Leaving the vertices untouched also means zero parallax, by construction.
//
// The quad UVs are absolute atlas UVs (the model bakery applies the sprite's sub-rect when it
// packs them), so the atlas is sampled directly - no per-sprite remap here or in the fragment.

#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
}

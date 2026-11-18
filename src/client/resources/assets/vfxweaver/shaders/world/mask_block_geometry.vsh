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
// WHY THE NUDGE. The mask draws the very geometry it masks, so its surface is coplanar with the
// block's and the fragment stage's depth compare against the scene is decided by a few float ulps
// that two different rasterisation paths do not agree on - which let a masked block draw over the
// mask "sometimes". Every vertex is therefore pulled VFX_MASK_DEPTH_NUDGE blocks toward the camera
// along its own view ray (the vertices are camera-relative, so that ray is just `Position`), which
// puts the mask strictly in front of the surface it masks in both depth conventions and at every
// distance, with no per-node depth maths. Two centimetres is 1/50 of a block: a wall genuinely in
// front is a block or more away and still wins, and the projected silhouette moves by far less than
// a pixel (the shift is along the view ray, so it only changes the scale by nudge/distance).

#ifndef VFX_MASK_DEPTH_NUDGE
#define VFX_MASK_DEPTH_NUDGE 0.02
#endif

#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 nudged = Position;
    float viewerDistance = length(Position);
    // A vertex closer to the eye than the nudge itself (the camera inside a masked block) is left
    // alone rather than pushed through the camera.
    if (viewerDistance > VFX_MASK_DEPTH_NUDGE) {
        nudged -= Position * (VFX_MASK_DEPTH_NUDGE / viewerDistance);
    }
    gl_Position = ProjMat * vec4(nudged, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
}

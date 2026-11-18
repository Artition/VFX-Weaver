#version 330

// The block-geometry vertex stage. The vanilla core/position_color shader carried this pass until
// the block-list leaf needed the sprite UV: this is the same transform (DynamicTransforms
// ModelViewMat with the camera-relative vertices, identity Projection) plus the quad's baked atlas
// UV, so the fragment stage can read the block texture's own alpha.
//
// The quad UVs are absolute atlas UVs (the model bakery applies the sprite's sub-rect when it
// packs them), so the atlas is sampled directly - no per-sprite remap here or in the fragment.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
}

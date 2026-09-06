#version 330

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // Emissive additive fill: no texture, no fog (beams shouldn't fade into sky fog).
    if (vertexColor.a <= 0.0) {
        discard;
    }
    fragColor = vertexColor;
}
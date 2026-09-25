#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float cell_size;
};

out vec4 fragColor;

void main() {
    vec2 cell = max(vec2(cell_size), vec2(1.0e-4)) * InSize;
    // The cell grid is centred on the screen, so a changing `cell_size` grows the
    // blocks outwards from the middle instead of pushing them out of the corner.
    vec2 grid = (texCoord - 0.5) * InSize / cell;
    vec2 uv = (floor(grid + 0.5) * cell + InSize * 0.5) / InSize;
    fragColor = texture(InSampler, uv);
}

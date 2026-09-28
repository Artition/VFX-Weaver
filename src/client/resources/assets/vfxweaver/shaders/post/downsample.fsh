#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float vfxPad;
};

out vec4 fragColor;

void main() {
    // This fragment is exactly one 2x2 block of the input (OutSize == InSize/2): the fragment
    // centre lands on the seam between the block's texel pairs. Four exact-texel-centre fetches
    // return those texels under ANY sampler filter mode, so this is the exact 2x2 box filter
    // without depending on how the cache binds the source.
    vec2 p = texCoord * InSize;
    vec2 base = floor(p - 0.5) + 0.5;
    vec4 c = texture(InSampler, (base) / InSize)
           + texture(InSampler, (base + vec2(1.0, 0.0)) / InSize)
           + texture(InSampler, (base + vec2(0.0, 1.0)) / InSize)
           + texture(InSampler, (base + vec2(1.0, 1.0)) / InSize);
    fragColor = c * 0.25;
}

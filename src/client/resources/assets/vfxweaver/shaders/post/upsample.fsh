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
    // Manual bilinear of the half-res source via exact texel-centre fetches: identical to
    // hardware LINEAR + CLAMP_TO_EDGE, but correct under any bound filter mode and a ready
    // place for a sharpening kernel later.
    vec2 p = texCoord * InSize - 0.5;
    vec2 b = floor(p);
    vec2 f = p - b;
    vec2 maxC = (InSize - 0.5) / InSize;
    vec2 c0 = min((b + 0.5) / InSize, maxC);
    vec2 c1 = min((b + 1.5) / InSize, maxC);
    fragColor = mix(
        mix(texture(InSampler, vec2(c0.x, c0.y)), texture(InSampler, vec2(c1.x, c0.y)), f.x),
        mix(texture(InSampler, vec2(c0.x, c1.y)), texture(InSampler, vec2(c1.x, c1.y)), f.x), f.y);
}

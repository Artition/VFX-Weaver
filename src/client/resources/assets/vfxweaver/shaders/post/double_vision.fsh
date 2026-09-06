#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float offset;
    float ghost_opacity;
    float drift;
    float intensity;
    float time;
};

out vec4 fragColor;

void main() {
    float g = ghost_opacity * intensity;
    float driftOff = sin((time / 20.0) * 1.2) * drift;
    vec2 base = vec2(offset + driftOff, 0.0);
    vec4 c = (texture(InSampler, texCoord)
            + texture(InSampler, texCoord + base) * g
            + texture(InSampler, texCoord - base) * g) / (1.0 + 2.0 * g);
    fragColor = vec4(c.rgb, 1.0);
}
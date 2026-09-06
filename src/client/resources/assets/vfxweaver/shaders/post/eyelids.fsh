#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float openness;
    float softness;
    float curve;
};

out vec4 fragColor;

void main() {
    float closure = clamp(1.0 - openness, 0.0, 1.0);
    float bulge = curve * 0.5 * (1.0 - 4.0 * pow(texCoord.x - 0.5, 2.0)) * closure;
    float travel = closure * (0.5 + 2.0 * softness);
    float lidTop = 1.0 + softness - travel + bulge;
    float lidBot = -softness + travel - bulge;
    float t = smoothstep(lidTop - softness, lidTop + softness, texCoord.y);
    float b = 1.0 - smoothstep(lidBot - softness, lidBot + softness, texCoord.y);
    float mask = clamp(t + b, 0.0, 1.0);
    fragColor = vec4(mix(texture(InSampler, texCoord).rgb, vec3(0.0), mask), 1.0);
}
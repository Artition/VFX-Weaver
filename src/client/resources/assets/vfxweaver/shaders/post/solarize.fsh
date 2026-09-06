#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float threshold;
    float softness;
    float intensity;
};

out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    float t = smoothstep(threshold - softness / 2.0 - 1.0e-4, threshold + softness / 2.0 + 1.0e-4, luma);
    fragColor = vec4(mix(c.rgb, 1.0 - c.rgb, t * intensity), c.a);
}
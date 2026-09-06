#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float center_x;
    float center_y;
    float radius;
    float width;
    float amplitude;
    float sharpness;
};

out vec4 fragColor;

void main() {
    vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
    vec2 corr = (texCoord - vec2(center_x, center_y)) * aspect;
    float dist = length(corr);
    float d = (dist - radius) / max(width, 1.0e-3);
    float profile = cos(d * 3.14159265 / max(sharpness, 1.0e-3)) * exp(-d * d);
    vec2 dir = normalize(corr + vec2(1.0e-5));
    vec4 c = texture(InSampler, texCoord + dir * amplitude * profile);
    fragColor = vec4(mix(texture(InSampler, texCoord).rgb, c.rgb, 1.0), 1.0);
}
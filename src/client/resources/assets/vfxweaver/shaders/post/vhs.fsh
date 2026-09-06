#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float tracking;
    float band_height;
    float band_speed;
    float bleed;
    float wobble;
    float intensity;
    float time;
};

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float t = time / 20.0;
    float bandCenter = fract(t * band_speed);
    float dy = abs(texCoord.y - bandCenter);
    dy = min(dy, 1.0 - dy);
    float inBand = 1.0 - smoothstep(band_height * 0.5, band_height, dy);
    float wob = (hash(vec2(floor(texCoord.y * InSize.y), floor(t * 60.0))) - 0.5) * wobble;
    float shift = (hash(vec2(floor(texCoord.y * InSize.y), floor(t * 12.0))) - 0.5) * tracking * inBand;
    vec2 uvG = texCoord + vec2(shift + wob, 0.0);
    vec4 c;
    c.r = texture(InSampler, uvG + vec2(bleed, 0.0)).r;
    c.g = texture(InSampler, uvG).g;
    c.b = texture(InSampler, uvG - vec2(bleed * 2.0, 0.0)).b;
    c.a = 1.0;
    c.rgb = (c.rgb - 0.08) / 0.92;
    fragColor = vec4(mix(texture(InSampler, texCoord).rgb, c.rgb, intensity), 1.0);
}
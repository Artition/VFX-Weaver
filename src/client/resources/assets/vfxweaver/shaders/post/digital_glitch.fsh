#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float block;
    float displacement;
    float rate;
    float chroma;
    float seed;
    float chance;
    float intensity;
    float time;
};

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float t = time / 20.0;
    float band = floor(texCoord.y / max(block, 1.0e-3));
    float slot = floor(t * rate);
    float gate = step(1.0 - chance, hash(vec2(slot, seed)));
    float burst = gate * step(0.5, hash(vec2(band, slot + seed)));
    float shift = (hash(vec2(band, slot + seed + 99.0)) - 0.5) * displacement * burst;
    vec2 uvG = texCoord + vec2(shift, 0.0);
    vec4 c;
    c.r = texture(InSampler, uvG + vec2(chroma * burst, 0.0)).r;
    c.g = texture(InSampler, uvG).g;
    c.b = texture(InSampler, uvG - vec2(chroma * burst, 0.0)).b;
    c.a = 1.0;
    fragColor = mix(texture(InSampler, texCoord), c, intensity);
}
#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;
uniform sampler2D MotionSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float smear;
    float chroma;
    float iframe;
    float time;
};

out vec4 fragColor;

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

uint pcg(uint v) {
    uint s = v * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

float hash01(ivec2 org, int size) {
    uint h = pcg(uint(org.x) + pcg(uint(org.y) + pcg(uint(size))));
    return float(h) * (1.0 / 4294967296.0);
}

// The static hash quadtree: pieces of 8..64 px, different sizes next to each other.
// Must stay identical to tileOf in datamosh_motion.fsh.
void tileOf(ivec2 p, out ivec2 org, out int size) {
    size = 64;
    org = (p / 64) * 64;
    for (int i = 0; i < 3; i++) {
        if (hash01(org, size) > 0.6) {
            break;
        }
        size >>= 1;
        org = (p / size) * size;
    }
}

void main() {
    ivec2 sz = textureSize(HistSampler, 0);
    ivec2 p = clamp(ivec2(floor(texCoord * OutSize)), ivec2(0), sz - 1);

    vec3 live = texture(InSampler, texCoord).rgb;
    float liveLum = lum(live);

    ivec2 org;
    int size;
    tileOf(p, org, size);

    // The start of the effect, and the per-piece refresh: the piece shows the real picture.
    // Every piece has its own phase, so there is no global flash.
    bool refresh = time < 1.0;
    if (!refresh && iframe > 0.0) {
        float period = iframe * 20.0;
        refresh = fract(time / period + hash01(org.yx, size)) < 1.0 / period;
    }
    if (refresh) {
        fragColor = vec4(live, liveLum);
        return;
    }

    // The vector of this piece, found once per 8x8 cell by the motion pass.
    ivec2 mcell = clamp(p >> 3, ivec2(0), textureSize(MotionSampler, 0) - 1);
    vec2 enc = texelFetch(MotionSampler, mcell, 0).rg;
    ivec2 best = ivec2(floor(enc * 255.0 + 0.5)) - 16;

    ivec2 m = ivec2(round(vec2(best) * max(smear, 0.0)));
    ivec2 mc = ivec2(round(vec2(m) * (1.0 + max(chroma, 0.0))));

    // The stale reference, shifted by the vector. Point sampled, no filter, no residual:
    // the pixels are carried over as they are, which is what a broken decoder does.
    vec3 a = texelFetch(HistSampler, clamp(p - m, ivec2(0), sz - 1), 0).rgb;
    vec3 b = texelFetch(HistSampler, clamp(p - mc, ivec2(0), sz - 1), 0).rgb;
    vec3 moshed = clamp(vec3(lum(a)) + (b - lum(b)), 0.0, 1.0);

    fragColor = vec4(moshed, liveLum);
}

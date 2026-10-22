#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float time;
};

out vec4 fragColor;

// The search penalty for a non-zero vector, per pixel of shift. It gives exactly zero
// vector where nothing moves, so a still picture does not crawl.
const float BIAS = 0.003;
const ivec2 DIR[4] = ivec2[4](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1));

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
// Must stay identical to tileOf in datamosh_update.fsh.
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

// The sum of absolute differences between the live picture and the previous live picture
// (carried in the alpha of the history), displaced by the candidate vector.
float cost(ivec2 m, float l[9], ivec2 q[9], ivec2 sz) {
    float s = 0.0;
    for (int i = 0; i < 9; i++) {
        ivec2 r = clamp(q[i] - m, ivec2(0), sz - 1);
        s += abs(l[i] - texelFetch(HistSampler, r, 0).a);
    }
    return s + BIAS * float(abs(m.x) + abs(m.y));
}

// The vector is in -15..15, stored as (v + 16) / 255: exact in RGBA8 and in RGBA16F.
vec4 encode(ivec2 v) {
    return vec4((vec2(v) + 16.0) / 255.0, 0.0, 1.0);
}

void main() {
    ivec2 sz = textureSize(HistSampler, 0);
    // One texel = one 8x8 cell; every tile (8..64, aligned) is a whole number of cells.
    ivec2 p = clamp(ivec2(floor(texCoord * OutSize)) * 8, ivec2(0), sz - 1);

    // The update pass refreshes everything at the start, the vector is not read.
    if (time < 1.0) {
        fragColor = encode(ivec2(0));
        return;
    }

    ivec2 org;
    int size;
    tileOf(p, org, size);

    float l[9];
    ivec2 q[9];
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            ivec2 pnt = clamp(org + (size * (ivec2(i, j) * 2 + 1)) / 6, ivec2(0), sz - 1);
            q[j * 3 + i] = pnt;
            l[j * 3 + i] = lum(texture(InSampler, (vec2(pnt) + 0.5) / vec2(sz)).rgb);
        }
    }

    bool allOne = true;
    bool allZero = true;
    float cost0 = 0.0;
    for (int i = 0; i < 9; i++) {
        float a = texelFetch(HistSampler, q[i], 0).a;
        allOne = allOne && a >= 0.999;
        allZero = allZero && a <= 0.001;
        cost0 += abs(l[i] - a);
    }
    bool alphaUsable = true;
    if (allOne || allZero) {
        bool liveConstant = true;
        for (int i = 1; i < 9; i++) {
            liveConstant = liveConstant && abs(l[i] - l[0]) < 0.002;
        }
        alphaUsable = liveConstant;
    }

    ivec2 best = ivec2(0);
    if (alphaUsable) {
        float bestC = cost0;
        for (int s = 8; s >= 1; s >>= 1) {
            for (int k = 0; k < 4; k++) {
                ivec2 cand = best + DIR[k] * s;
                if (BIAS * float(abs(cand.x) + abs(cand.y)) >= bestC) {
                    continue;
                }
                float c = cost(cand, l, q, sz);
                if (c < bestC) {
                    bestC = c;
                    best = cand;
                }
            }
        }
    }

    fragColor = encode(best);
}

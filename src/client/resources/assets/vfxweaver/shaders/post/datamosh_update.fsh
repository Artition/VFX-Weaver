#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

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

    // The 3x3 points inside the piece: the luma of the live picture.
    float l[9];
    ivec2 q[9];
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            ivec2 pnt = clamp(org + (size * (ivec2(i, j) * 2 + 1)) / 6, ivec2(0), sz - 1);
            q[j * 3 + i] = pnt;
            l[j * 3 + i] = lum(texture(InSampler, (vec2(pnt) + 0.5) / vec2(sz)).rgb);
        }
    }

    // The history carries the previous live picture in its alpha. If the render target does
    // not keep it, the estimate would be garbage - stand still instead of tearing.
    bool alphaUsable = true;
    bool allOne = true;
    bool allZero = true;
    for (int i = 0; i < 9; i++) {
        float a = texelFetch(HistSampler, q[i], 0).a;
        allOne = allOne && a >= 0.999;
        allZero = allZero && a <= 0.001;
    }
    if (allOne || allZero) {
        bool liveConstant = true;
        for (int i = 1; i < 9; i++) {
            liveConstant = liveConstant && abs(l[i] - l[0]) < 0.002;
        }
        alphaUsable = liveConstant;
    }

    // The vector: live(t-1) -> live(t), a greedy search over 8, 4, 2 and 1 pixel.
    ivec2 best = ivec2(0);
    if (alphaUsable) {
        float bestC = cost(best, l, q, sz);
        for (int s = 8; s >= 1; s >>= 1) {
            for (int k = 0; k < 4; k++) {
                ivec2 cand = best + DIR[k] * s;
                float c = cost(cand, l, q, sz);
                if (c < bestC) {
                    bestC = c;
                    best = cand;
                }
            }
        }
    }

    ivec2 m = ivec2(round(vec2(best) * max(smear, 0.0)));
    ivec2 mc = ivec2(round(vec2(m) * (1.0 + max(chroma, 0.0))));

    // The stale reference, shifted by the vector. Point sampled, no filter, no residual:
    // the pixels are carried over as they are, which is what a broken decoder does.
    vec3 a = texelFetch(HistSampler, clamp(p - m, ivec2(0), sz - 1), 0).rgb;
    vec3 b = texelFetch(HistSampler, clamp(p - mc, ivec2(0), sz - 1), 0).rgb;
    vec3 moshed = clamp(vec3(lum(a)) + (b - lum(b)), 0.0, 1.0);

    fragColor = vec4(moshed, liveLum);

    // DEBUG (uncomment to check the alpha and the vectors):
    // fragColor = vec4(vec2(best) / 32.0 + 0.5, 0.5, liveLum);
}

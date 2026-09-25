#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float intensity;
    float size;
    float time;
    float chroma;
};

out vec4 fragColor;

// Integer bit-mix (PCG). A fract(sin(dot())) hash evaluated on an integer
// lattice repeats in a fixed visible pattern - the grain looked like one 3x3
// tile stamped over the whole frame - so the cells are hashed with a bit-mix
// instead, which has no such structure.
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float hash(uvec2 p) {
    return float(pcg(p.x + pcg(p.y))) * (1.0 / 4294967296.0);
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    // Animated per-frame noise: the grain cell pattern is offset every tick via `time`
    // (the effect's age in ticks), so the grain flickers instead of standing still.
    vec2 cell = texCoord * OutSize / max(size, 1.0);
    uvec2 id = uvec2(cell) + uvec2(uint(time * 0.7317) * 9781u, uint(time * 0.3943) * 6151u);
    float mono = hash(id);
    // `chroma` gives each channel its own grain instead of one shared value:
    // 0.0 is monochrome, 1.0 fully coloured.
    vec3 grain = mix(vec3(mono), vec3(hash(id + uvec2(1u, 0u)), mono, hash(id + uvec2(0u, 1u))),
                     clamp(chroma, 0.0, 1.0));
    fragColor = vec4(base.rgb + (grain - 0.5) * clamp(intensity, 0.0, 1.0), base.a);
}

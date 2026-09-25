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
    // The cell grid is centred on the screen, so a changing `size` grows the cells
    // outwards from the middle instead of pushing them out of the corner; the fixed
    // offset is only there to keep the cell index positive.
    vec2 grid = (texCoord - 0.5) * OutSize / max(size, 1.0);
    uvec2 id = uvec2(ivec2(floor(grid)) + 1048576)
             + uvec2(uint(time * 0.7317) * 9781u, uint(time * 0.3943) * 6151u);
    float mono = hash(id);
    // `chroma` mixes an independent per-channel grain into the shared one, capped
    // halfway: at chroma = 1 the monochrome grain still carries the structure, so
    // the result reads as coloured film grain instead of colour noise. The three
    // channels are hashed off one interleaved index (id * 3 + channel) rather than
    // a cell offset, so no channel is a displaced copy of another.
    vec3 tint = vec3(hash(uvec2(id.x * 3u, id.y)),
                      hash(uvec2(id.x * 3u + 1u, id.y)),
                      hash(uvec2(id.x * 3u + 2u, id.y)));
    vec3 grain = mono + (tint - mono) * clamp(chroma, 0.0, 1.0) * 0.5;
    fragColor = vec4(base.rgb + (grain - 0.5) * clamp(intensity, 0.0, 1.0), base.a);
}

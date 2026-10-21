#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float block_size;
    float strength;
    float smear;
    float residual;
    float time;
};

out vec4 fragColor;

// The reference hash (the demo mod's common.glsl), copied verbatim: the broken blocks
// have to be reproducible from the block index alone.
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

void main() {
    vec3 fresh = texture(InSampler, texCoord).rgb;
    float bs = max(block_size, 4.0);
    vec2 block = floor(texCoord * OutSize / bs);
    // The breakage of a block is re-rolled twice a second, so a broken block lives for a
    // while instead of flickering every frame.
    float epoch = floor(time / 10.0);
    vec2 seed = block + vec2(epoch * 13.0, epoch * 7.0);

    // How many blocks break, and how far each one is dragged.
    float broken = step(1.0 - clamp(strength, 0.0, 1.0), hash11(seed.x * 1.7 + seed.y * 3.1 + 0.5));
    vec2 rnd = vec2(hash11(seed.x * 5.3 + seed.y * 11.0), hash11(seed.x * 13.0 + seed.y * 2.7));
    vec2 motion = (rnd * 2.0 - 1.0) * max(smear, 0.0);

    // A broken block is a rigid copy of the history, dragged by its motion vector: the stale
    // picture smears, exactly like a P-frame whose reference frame never arrived.
    vec2 centre = (block + 0.5) * bs / OutSize;
    vec3 stale = texture(HistSampler, centre - motion / OutSize).rgb;

    // The residual: the bit of the current picture that still lands in the block. It anchors
    // the melt, so the picture drifts instead of dissolving into noise.
    float keep = mix(1.0, clamp(residual, 0.0, 1.0), broken);
    vec3 melted = mix(stale, fresh, keep);

    // A few blocks break completely into flat colour: the codec's garbage.
    float garbage = step(0.985, hash11(seed.x * 23.0 + seed.y * 29.0 + 3.0));
    melted = mix(melted, vec3(0.05, 0.9, 0.35), garbage * 0.8);

    fragColor = vec4(melted, 1.0);
}

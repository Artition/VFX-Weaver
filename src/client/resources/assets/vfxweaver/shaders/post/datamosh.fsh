#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float amount;
    float time;
};

out vec4 fragColor;

// The reference hash pair (the demo mod's common.glsl), copied verbatim: the datamosh
// block layout is only reproducible with these exact hashes.
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// The reference sample helper: it clamps the uv so a knock or a slide never
// reaches outside the frame.
vec3 px(vec2 uv) {
    return texture(InSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
    vec3 base = texture(InSampler, texCoord).rgb;
    // `time` is in ticks; the reference beat clock runs at 20 Hz in seconds.
    float t = time / 20.0;
    float beat = floor(t * 20.0);

    // Horizontal band slide: an 18 px band row slides sideways per beat.
    float band = floor(texCoord.y * OutSize.y / 18.0);
    float slide = (hash11(band * 13.1 + beat) - 0.5) * 0.16 * step(0.5, hash11(band * 7.7 + beat * 3.0));

    // Knock offset: a 64x36 px block is displaced on the rare blocks that hash past 0.86.
    vec2 blk = floor(texCoord * OutSize / vec2(64.0, 36.0));
    vec2 knock = (vec2(hash12(blk + beat), hash12(blk * 1.7 + beat)) - 0.5) * 0.08 * step(0.86, hash12(blk * 3.1 + beat));

    vec2 g = texCoord + vec2(slide, 0.0) + knock;

    // RGB split.
    float split = 0.006 + 0.01 * hash11(beat);
    vec3 gc = vec3(px(g + vec2(split, 0.0)).r, px(g).g, px(g - vec2(split, 0.0)).b);
    float lv = lum(gc);

    // Three-tone posterisation: black / ember / white.
    vec3 black = vec3(0.012, 0.006, 0.006);
    vec3 ember = vec3(1.0, 0.33, 0.06);
    vec3 white = vec3(1.0, 0.93, 0.82);
    vec3 frame = lv < 0.25 ? black : (lv < 0.6 ? ember : white);
    frame.r += max(gc.r - lv, 0.0) * 1.2;
    frame.gb += max(gc.b - lv, 0.0) * vec2(0.9, 1.4);

    // 3 px scanline band.
    frame *= 0.72 + 0.28 * step(0.5, fract(texCoord.y * OutSize.y / 3.0));

    // The data block: ~2.5% of the 24x8 px cells snap to white or cyan.
    float data = step(0.975, hash12(floor(texCoord * OutSize / vec2(24.0, 8.0)) + beat * 7.0));
    frame = mix(frame, (hash11(beat + blk.x) > 0.5 ? white : vec3(0.3, 0.9, 1.0)), data * 0.85);

    fragColor = vec4(mix(base, frame, clamp(amount, 0.0, 1.0)), 1.0);
}

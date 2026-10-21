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
    float cell_size;
    float width;
    float speed;
    float core;
};

out vec4 fragColor;

// The reference prism palette (the demo mod's common.glsl), copied verbatim: the rainbow
// ramp and the seven spectral stops are only reproducible with these exact colours.
vec3 prismStop(int i) {
    return i == 0 ? vec3(1.0, 0.07, 0.1) : i == 1 ? vec3(1.0, 0.42, 0.03) : i == 2 ? vec3(1.0, 0.88, 0.06)
    : i == 3 ? vec3(0.1, 0.95, 0.28) : i == 4 ? vec3(0.06, 0.48, 1.0) : i == 5 ? vec3(0.28, 0.14, 1.0)
    : vec3(0.74, 0.16, 1.0);
}

vec3 prism7(float h) {
    float x = fract(h) * 7.0;
    int i = int(floor(x));
    float f = x - float(i);
    f = f * f * (3.0 - 2.0 * f);
    return mix(prismStop(i), prismStop(i == 6 ? 0 : i + 1), f);
}

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 base = texture(InSampler, texCoord).rgb;
    // `time` is in ticks; the reference clock runs at 20 Hz in seconds.
    float t = time / 20.0;

    // The triangular grid: three line directions (two at 60 degrees, one vertical) on a
    // cell_size cell, anchored at the screen centre.
    vec2 sp = (texCoord - 0.5) * OutSize / max(cell_size, 1.0);
    float gridD = 1.0;
    gridD = min(gridD, abs(fract(dot(sp, vec2(-0.5, 0.866))) - 0.5));
    gridD = min(gridD, abs(fract(dot(sp, vec2(0.5, 0.866))) - 0.5));
    gridD = min(gridD, abs(fract(sp.x * 1.1547) - 0.5));
    float grid = smoothstep(max(width, 0.0001), 0.0, gridD);

    // The colour by screen position: the prism ramp over the position, drifting with time.
    vec3 hue = prism7(dot(texCoord, vec2(0.6, 0.4)) + t * speed);

    // The highlights are absolute (a pixel brighter than `core`), not local: a local mean
    // would put a hard outline around every brightness step, which is the halo the
    // halftone/comic effects lost for the same reason.
    vec3 frame = vec3(0.01, 0.008, 0.02) + hue * grid * 0.28;
    frame = mix(frame, vec3(1.0), step(core, lum(base)) * 0.85);

    fragColor = vec4(mix(base, frame, clamp(amount, 0.0, 1.0)), 1.0);
}

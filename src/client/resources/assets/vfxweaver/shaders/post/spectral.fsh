#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float amount;
    float mode;
    float time;
    float center_x;
    float center_y;
    float mid;
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
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    int variant = int(clamp(mode, 0.0, 2.0));

    // The brightness split is absolute, not local: a pixel brighter than `mid` is lit, the
    // ones above `core` are the highlights. A local mean would put a hard outline around
    // every brightness step, which is the halo the halftone/comic effects lost for the same
    // reason.
    float l = lum(base);
    float lit = step(mid, l);
    float hot = step(core, l);

    // The reference geometry: the point-relative, aspect-corrected screen position.
    vec2 d = (texCoord - vec2(center_x, center_y)) * vec2(aspect, 1.0);
    float ang = atan(d.y, d.x);
    float dist = length(d);

    vec3 frame;
    if (variant == 1) {
        // Reference mode 33: the chromatic smear. Seven taps along the outward direction,
        // each tap's edge painted with its own prism stop. The edge ramp is wide, so the
        // smear stays a gradient instead of a drawn outline.
        vec2 outward = normalize(d + 1e-5) / vec2(aspect, 1.0);
        vec2 sobel = 1.5 / max(OutSize, vec2(1.0));
        vec3 acc = vec3(0.0);
        for (int k = 0; k < 7; k++) {
            vec2 o = texCoord + outward * float(k - 3) * (0.0035 + 0.004 * dist);
            float ex = lum(texture(InSampler, o + vec2(sobel.x, 0.0)).rgb) - lum(texture(InSampler, o - vec2(sobel.x, 0.0)).rgb);
            float ey = lum(texture(InSampler, o + vec2(0.0, sobel.y)).rgb) - lum(texture(InSampler, o - vec2(0.0, sobel.y)).rgb);
            acc += prism7(float(k) / 7.0) * smoothstep(0.02, 0.30, length(vec2(ex, ey)));
        }
        frame = vec3(0.01, 0.008, 0.02) + acc * 0.75;
        frame = mix(frame, vec3(1.0), hot * 0.9);
    } else if (variant == 2) {
        // Reference mode 34: the spectral ring. The luminance picks a band, the distance
        // picks the ring, and the two together pick a spectral stop.
        float band = floor(clamp(l, 0.0, 1.0) * 6.999);
        int ring = int(floor(dist * 9.0 - t * 6.0));
        frame = prismStop(int(mod(float(6 - int(band) + ring), 7.0)));
        frame = mix(frame, vec3(1.0), hot);
    } else {
        // Reference mode 29: the duotone frame with the prism colour in place of the flat
        // accent - prism7 over the angle + distance - time.
        vec3 prism = prism7(ang / 6.28318531 + dist * 0.9 - t * 0.7);
        frame = mix(vec3(0.012, 0.006, 0.006), prism, lit);
        frame = mix(frame, vec3(1.0), hot);
    }

    fragColor = vec4(mix(base, frame, clamp(amount, 0.0, 1.0)), 1.0);
}

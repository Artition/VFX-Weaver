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

// The reference sample helper: it clamps the uv so a tap never reaches outside the frame.
vec3 px(vec2 uv) {
    return texture(InSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
    vec3 base = texture(InSampler, texCoord).rgb;
    // `time` is in ticks; the reference clock runs at 20 Hz in seconds.
    float t = time / 20.0;
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    int variant = int(clamp(mode, 0.0, 2.0));

    // The reference duotone base: the local mean of an 8-tap ring (radius 0.045, x divided
    // by the aspect), the Sobel contour and the bright/dark split (step(avg + 0.004, luma)).
    float l = lum(px(texCoord));
    float avg = 0.0;
    for (int i = 0; i < 8; i++) {
        float ring = float(i) * 0.785398;
        avg += lum(px(texCoord + vec2(cos(ring) / aspect, sin(ring)) * 0.045));
    }
    avg /= 8.0;
    float two = step(avg + 0.004, l);

    vec2 sobel = 1.5 / max(OutSize, vec2(1.0));
    float gx = lum(px(texCoord + vec2(sobel.x, 0.0))) - lum(px(texCoord - vec2(sobel.x, 0.0)));
    float gy = lum(px(texCoord + vec2(0.0, sobel.y))) - lum(px(texCoord - vec2(0.0, sobel.y)));
    float ink = smoothstep(0.06, 0.18, length(vec2(gx, gy)));
    float core = step(avg + 0.18, l);

    // The reference geometry: the point-relative, aspect-corrected screen position.
    vec2 d = (texCoord - vec2(center_x, center_y)) * vec2(aspect, 1.0);
    float ang = atan(d.y, d.x);
    float dist = length(d);

    vec3 frame;
    if (variant == 1) {
        // Reference mode 33: the chromatic smear. Seven taps along the outward direction,
        // each tap's Sobel edge painted with its own prism stop.
        vec2 outward = normalize(d + 1e-5) / vec2(aspect, 1.0);
        vec3 acc = vec3(0.0);
        for (int k = 0; k < 7; k++) {
            vec2 o = texCoord + outward * float(k - 3) * (0.0035 + 0.004 * dist);
            float ex = lum(px(o + vec2(sobel.x, 0.0))) - lum(px(o - vec2(sobel.x, 0.0)));
            float ey = lum(px(o + vec2(0.0, sobel.y))) - lum(px(o - vec2(0.0, sobel.y)));
            acc += prism7(float(k) / 7.0) * smoothstep(0.05, 0.16, length(vec2(ex, ey)));
        }
        frame = vec3(0.01, 0.008, 0.02) + acc * 0.75;
        frame = mix(frame, vec3(1.0), core * 0.9);
    } else if (variant == 2) {
        // Reference mode 34: the spectral ring. The luminance picks a band, the distance
        // picks the ring, and the two together pick a spectral stop.
        float band = floor(clamp((l - avg) * 2.4 + 0.5, 0.0, 1.0) * 6.999);
        int ring = int(floor(dist * 9.0 - t * 6.0));
        frame = prismStop(int(mod(float(6 - int(band) + ring), 7.0)));
        frame = mix(frame, vec3(1.0), core);
        frame = mix(frame, vec3(0.01, 0.008, 0.02), ink * 0.9);
    } else {
        // Reference mode 29: the duotone frame with the prism colour in place of the flat
        // accent - prism7 over the angle + distance - time.
        vec3 prism = prism7(ang / 6.28318531 + dist * 0.9 - t * 0.7);
        frame = mix(vec3(0.012, 0.006, 0.006), prism, two);
        frame = mix(frame, vec3(1.0), core);
        frame = mix(frame, vec3(0.012, 0.006, 0.006) * 0.5, ink * two);
    }

    fragColor = vec4(mix(base, frame, clamp(amount, 0.0, 1.0)), 1.0);
}

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
    float speed;
    float center_x;
    float center_y;
    float ragged;
    float glow;
};

out vec4 fragColor;

// The reference hash/noise pair (the demo mod's common.glsl), copied verbatim: the tear
// angle and the ragged edge are only reproducible with these exact hashes.
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

float vnoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x),
        mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
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
    // The tear angle is re-rolled on this clock (20 beats per second by default).
    float beat = floor(t * max(speed, 0.0));
    float aspect = OutSize.x / max(OutSize.y, 1.0);

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

    // The tear: a fresh random angle every beat, a ragged noise offset across the tear and
    // the two sides at opposite brightness.
    float ang = hash11(beat + 3.0) * 3.14159265;
    vec2 nrm = vec2(cos(ang), sin(ang));
    vec2 d = (texCoord - vec2(center_x, center_y)) * vec2(aspect, 1.0);
    float along = dot(d, vec2(-nrm.y, nrm.x));
    float side = dot(d, nrm) + (vnoise2(vec2(along * 38.0, beat)) - 0.5) * ragged;
    float a = step(0.0, side);
    float lit = mix(two, 1.0 - two, a);

    vec3 black = vec3(0.012, 0.006, 0.006);
    vec3 ember = vec3(1.0, 0.33, 0.06);
    vec3 white = vec3(1.0, 0.93, 0.82);
    vec3 frame = mix(black, ember, lit);
    frame = mix(frame, a > 0.5 ? black : ember, ink * lit);
    // The glowing line along the tear: an exponential falloff of 0.0035 UV, scaled by 1.6.
    frame = mix(frame, white, clamp(exp(-abs(side) / max(glow, 0.0005)) * 1.6, 0.0, 1.0));

    fragColor = vec4(mix(base, frame, clamp(amount, 0.0, 1.0)), 1.0);
}

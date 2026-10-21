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
    float mid;
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

void main() {
    vec3 base = texture(InSampler, texCoord).rgb;
    // `time` is in ticks; the reference clock runs at 20 Hz in seconds.
    float t = time / 20.0;
    // The tear angle is re-rolled on this clock (20 beats per second by default).
    float beat = floor(t * max(speed, 0.0));
    float aspect = OutSize.x / max(OutSize.y, 1.0);

    // The brightness split is absolute, not local: a pixel brighter than `mid` is lit. A local
    // mean would put a hard outline around every brightness step, which is the halo the
    // halftone/comic effects lost for the same reason.
    float lit = step(mid, lum(base));

    // The tear: a fresh random angle every beat, a ragged noise offset across the tear and
    // the two sides at opposite brightness.
    float ang = hash11(beat + 3.0) * 3.14159265;
    vec2 nrm = vec2(cos(ang), sin(ang));
    vec2 d = (texCoord - vec2(center_x, center_y)) * vec2(aspect, 1.0);
    float along = dot(d, vec2(-nrm.y, nrm.x));
    float side = dot(d, nrm) + (vnoise2(vec2(along * 38.0, beat)) - 0.5) * ragged;
    float a = step(0.0, side);
    float bright = mix(lit, 1.0 - lit, a);

    vec3 black = vec3(0.012, 0.006, 0.006);
    vec3 ember = vec3(1.0, 0.33, 0.06);
    vec3 white = vec3(1.0, 0.93, 0.82);
    vec3 frame = mix(black, ember, bright);
    // The glowing line along the tear: an exponential falloff of 0.0035 UV, scaled by 1.6.
    frame = mix(frame, white, clamp(exp(-abs(side) / max(glow, 0.0005)) * 1.6, 0.0, 1.0));

    fragColor = vec4(mix(base, frame, clamp(amount, 0.0, 1.0)), 1.0);
}

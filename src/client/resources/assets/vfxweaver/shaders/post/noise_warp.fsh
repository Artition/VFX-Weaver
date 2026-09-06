#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float scale;
    float amplitude;
    float contrast;
    float coherence;
    float speed;
    float drift_x;
    float drift_y;
    float time;
};

out vec4 fragColor;

float hash(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float vnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 s = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec3(1, 0, 0));
    float c = hash(i + vec3(0, 1, 0));
    float d = hash(i + vec3(1, 1, 0));
    float e = hash(i + vec3(0, 0, 1));
    float f2 = hash(i + vec3(1, 0, 1));
    float g = hash(i + vec3(0, 1, 1));
    float h = hash(i + vec3(1, 1, 1));
    return mix(
        mix(mix(a, b, s.x), mix(c, d, s.x), s.y),
        mix(mix(e, f2, s.x), mix(g, h, s.x), s.y),
        s.z
    );
}

void main() {
    // `time` is the effect age in ticks (see VFXPostProcessingManager); convert to seconds so
    // `speed` is cycles per second and `drift_*` fractions per second. The time-driven third
    // coordinate animates the field continuously (no flicker, no frozen pattern).
    float t = time / 20.0;
    vec2 corr = texCoord * scale;
    corr.x *= InSize.x / InSize.y;
    vec3 field = vec3(corr + vec2(drift_x, drift_y) * t, t * speed);

    // Magnitude shaped by the contrast curve: >1 gathers the warp into distinct patches.
    float n = vnoise(field);
    float mag = pow(clamp(n, 0.0, 1.0), max(contrast, 0.1));

    // Direction: coherence 1 follows the noise gradient (fluid bulges); 0 pulls each patch in
    // its own smoothly-varying direction. Sampling the same value-noise for directions keeps
    // them continuous (no seams between cells).
    vec2 grad = vec2(
        vnoise(field + vec3(0.05, 0.0, 0.0)) - vnoise(field - vec3(0.05, 0.0, 0.0)),
        vnoise(field + vec3(0.0, 0.05, 0.0)) - vnoise(field - vec3(0.0, 0.05, 0.0))
    );
    vec2 rndDir = normalize(vec2(
        vnoise(field + vec3(13.7, 0.0, 0.0)) - 0.5,
        vnoise(field + vec3(71.3, 0.0, 0.0)) - 0.5
    ) + vec2(1.0e-5));
    vec2 dir = normalize(mix(rndDir, grad, clamp(coherence, 0.0, 1.0)) + vec2(1.0e-5));

    fragColor = texture(InSampler, texCoord + dir * mag * amplitude);
}
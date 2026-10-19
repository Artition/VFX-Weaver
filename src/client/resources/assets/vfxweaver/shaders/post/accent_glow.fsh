#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float accent_r;
    float accent_g;
    float accent_b;
    float strength;
    float amount;
};

out vec4 fragColor;

void main() {
    vec4 base = texture(InSampler, texCoord);
    float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    // absolute split at mid grey: no local reference, so no halos by construction
    float two = smoothstep(0.485, 0.515, luma);
    float core = smoothstep(0.66, 0.70, luma);
    // halftone dots in the mid tones: 5px cells rotated 45 degrees
    // absolute mid-tone for the dots: same scale as the split above
    float tone = clamp(luma, 0.0, 1.0);
    float a = radians(45.0);
    vec2 cellpx = texCoord * OutSize;
    vec2 rp = vec2(cos(a) * cellpx.x - sin(a) * cellpx.y, sin(a) * cellpx.x + cos(a) * cellpx.y) / 5.0;
    float mid = smoothstep(0.15, 0.35, tone) * (1.0 - smoothstep(0.65, 0.85, tone));
    float dots = step(length(fract(rp) - 0.5), sqrt(1.0 - tone) * 0.62);
    vec3 accent = vec3(accent_r, accent_g, accent_b);
    vec3 glow = (accent * two + vec3(1.0) * core + accent * dots * mid * 0.75) * strength;
    fragColor = vec4(mix(base.rgb, glow, clamp(amount, 0.0, 1.0)), base.a);
}

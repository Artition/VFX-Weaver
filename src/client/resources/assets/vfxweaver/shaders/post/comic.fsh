#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float paper_r;
    float paper_g;
    float paper_b;
    float ink_r;
    float ink_g;
    float ink_b;
    float amount;
};

out vec4 fragColor;

float vfx_luma(vec2 uv) {
    return dot(texture(InSampler, uv).rgb, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    // absolute two-tone split at mid grey: no local reference, so no halos by construction.
    // the ink contour below still draws precise edges.
    float two = smoothstep(0.485, 0.515, luma);
    // Sobel contour (ink tap step)
    vec2 px = 1.5 / max(OutSize, vec2(1.0));
    float tl = vfx_luma(texCoord + vec2(-px.x, -px.y));
    float tp = vfx_luma(texCoord + vec2(0.0, -px.y));
    float tr = vfx_luma(texCoord + vec2(px.x, -px.y));
    float lf = vfx_luma(texCoord + vec2(-px.x, 0.0));
    float rt = vfx_luma(texCoord + vec2(px.x, 0.0));
    float bl = vfx_luma(texCoord + vec2(-px.x, px.y));
    float bt = vfx_luma(texCoord + vec2(0.0, px.y));
    float br = vfx_luma(texCoord + vec2(px.x, px.y));
    float gx = -tl - 2.0 * lf - bl + tr + 2.0 * rt + br;
    float gy = -tl - 2.0 * tp - tr + bl + 2.0 * bt + br;
    float contour = smoothstep(0.06, 0.18, length(vec2(gx, gy)));
    // halftone dots in the mid tones: 5px cells rotated 45 degrees
    // absolute mid-tone for the dots: same scale as the two-tone split above
    float tone = clamp(luma, 0.0, 1.0);
    float a = radians(45.0);
    vec2 cellpx = texCoord * OutSize;
    vec2 rp = vec2(cos(a) * cellpx.x - sin(a) * cellpx.y, sin(a) * cellpx.x + cos(a) * cellpx.y) / 5.0;
    float band = smoothstep(0.15, 0.35, tone) * (1.0 - smoothstep(0.65, 0.85, tone));
    float dots = step(length(fract(rp) - 0.5), sqrt(1.0 - tone) * 0.62) * band * 0.8;
    vec3 paper = vec3(paper_r, paper_g, paper_b);
    vec3 inkColor = vec3(ink_r, ink_g, ink_b);
    vec3 duotone = paper * (1.0 - two) + inkColor * two + inkColor * dots;
    vec3 comic = duotone * (1.0 - contour);
    fragColor = vec4(mix(base.rgb, comic, clamp(amount, 0.0, 1.0)), base.a);
}

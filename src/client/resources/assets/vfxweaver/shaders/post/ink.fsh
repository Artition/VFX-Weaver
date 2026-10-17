#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float edge_low;
    float edge_high;
    float amount;
};

out vec4 fragColor;

float vfx_luma(vec2 uv) {
    return dot(texture(InSampler, uv).rgb, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    vec2 px = 1.5 / max(OutSize, vec2(1.0));
    float tl = vfx_luma(texCoord + vec2(-px.x, -px.y));
    float t = vfx_luma(texCoord + vec2(0.0, -px.y));
    float tr = vfx_luma(texCoord + vec2(px.x, -px.y));
    float l = vfx_luma(texCoord + vec2(-px.x, 0.0));
    float r = vfx_luma(texCoord + vec2(px.x, 0.0));
    float bl = vfx_luma(texCoord + vec2(-px.x, px.y));
    float b = vfx_luma(texCoord + vec2(0.0, px.y));
    float br = vfx_luma(texCoord + vec2(px.x, px.y));
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    float edge = smoothstep(edge_low, edge_high, length(vec2(gx, gy)));
    vec3 ink = mix(base.rgb, vec3(0.0), clamp(edge * amount, 0.0, 1.0));
    fragColor = vec4(ink, base.a);
}

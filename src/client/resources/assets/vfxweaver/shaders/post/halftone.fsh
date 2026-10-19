#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float cell_size;
    float angle;
    float gain;
    float amount;
};

out vec4 fragColor;

void main() {
    vec4 base = texture(InSampler, texCoord);
    float lum0 = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    // absolute tone: contrast around mid grey, no local reference, so no halos by construction.
    // gain 2.5 is neutral (tone == luma); higher values add punch, 0 flattens to mid grey.
    float tone = clamp(0.5 + (lum0 - 0.5) * gain * 0.4, 0.0, 1.0);
    float cell = max(cell_size, 1.0);
    float a = radians(angle);
    float ca = cos(a);
    float sa = sin(a);
    vec2 px = (texCoord - 0.5) * OutSize;
    vec2 tp = vec2(ca * px.x - sa * px.y, sa * px.x + ca * px.y) / cell;
    float dd = length(fract(tp) - 0.5);
    float m = step(dd, sqrt(1.0 - tone) * 0.62);
    vec3 dots = vec3(1.0) * (1.0 - m);
    fragColor = vec4(mix(base.rgb, dots, clamp(amount, 0.0, 1.0)), base.a);
}

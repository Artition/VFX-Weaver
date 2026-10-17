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
    float aspect = max(OutSize.x, 1.0) / max(OutSize.y, 1.0);
    float lum0 = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    float acc = 0.0;
    for (int i = 0; i < 8; i++) {
        float t = 6.2831853 * float(i) / 8.0;
        vec2 off = vec2(cos(t) * 0.045 / aspect, sin(t) * 0.045);
        acc += dot(texture(InSampler, texCoord + off).rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    float tone = clamp((lum0 - acc * 0.125) * gain + 0.5, 0.0, 1.0);
    float cell = max(cell_size, 1.0);
    float a = radians(angle);
    float ca = cos(a);
    float sa = sin(a);
    vec2 px = texCoord * OutSize;
    vec2 tp = vec2(ca * px.x - sa * px.y, sa * px.x + ca * px.y) / cell;
    float dd = length(fract(tp) - 0.5);
    float m = step(dd, sqrt(1.0 - tone) * 0.62);
    vec3 dots = vec3(1.0) * (1.0 - m);
    fragColor = vec4(mix(base.rgb, dots, clamp(amount, 0.0, 1.0)), base.a);
}

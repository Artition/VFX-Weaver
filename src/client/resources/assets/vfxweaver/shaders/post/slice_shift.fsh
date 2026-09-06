#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float angle;
    float offset;
    float shift;
    float mirror;
};

out vec4 fragColor;

void main() {
    // Aspect-corrected UV space: a screen fraction means the same length in both axes,
    // so "45 degrees" is visually 45 degrees and `offset` is symmetric.
    vec2 asp = vec2(InSize.x / InSize.y, 1.0);
    vec2 uv = texCoord;
    vec2 ac = uv * asp;
    float a = radians(angle);
    vec2 n = vec2(cos(a + 1.5707963), sin(a + 1.5707963));
    vec2 lp = vec2(0.5, 0.5) * asp + n * offset;
    float side = sign(dot(ac - lp, n));
    if (side == 0.0) {
        side = 1.0;
    }
    vec2 shifted = (ac - side * shift * vec2(cos(a), sin(a))) / asp;
    vec2 wrapped = fract(shifted);
    vec2 mirrored = 1.0 - abs(2.0 * fract(shifted * 0.5) - 1.0);
    vec2 finalUV = mix(wrapped, mirrored, mirror);
    fragColor = texture(InSampler, finalUV);
}
#version 330

#moj_import <vfxweaver:field.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float saturation;
    float contrast;
    float brightness;
    float tint_g;
    float tint_b;
};

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 graded = mix(vec3(luma), color.rgb, saturation);
    graded = (graded - 0.5) * contrast + 0.5;
    graded = graded * brightness;
    // tint_r is field-capable: the per-pixel value (default 1.0 = no field) multiplies the
    // animated tint_r; every other channel stays a uniform Config value.
    graded *= vec3(vfx_field_intensity(texCoord), tint_g, tint_b);
    fragColor = vec4(clamp(graded, 0.0, 1.0), color.a);
}

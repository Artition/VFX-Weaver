#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float decay;
    float blend;
    float drift;
    float desat;
    float intensity;
};

out vec4 fragColor;

void main() {
    // Composite pass: blend the (updated) history echo over the live frame, desaturated.
    vec4 hist = texture(HistSampler, texCoord);
    float luma = dot(hist.rgb, vec3(0.299, 0.587, 0.114));
    vec4 live = texture(InSampler, texCoord);
    fragColor = mix(live, vec4(mix(hist.rgb, vec3(luma), desat), 1.0), intensity);
}
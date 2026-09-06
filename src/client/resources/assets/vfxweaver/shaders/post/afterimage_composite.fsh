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
    // Composite pass: blend the (desaturated) reasonant history echo OVER the live frame as a
    // weighted mix - no screen/additive brightening, so the effect reads as a pure ghosting trail
    // behind motion instead of washing the whole picture out.
    vec4 hist = texture(HistSampler, texCoord);
    float luma = dot(hist.rgb, vec3(0.299, 0.587, 0.114));
    vec3 histC = mix(hist.rgb, vec3(luma), desat);
    vec3 live = texture(InSampler, texCoord).rgb;
    vec3 outC = mix(live, histC, clamp(intensity, 0.0, 1.0));
    fragColor = vec4(outC, 1.0);
}
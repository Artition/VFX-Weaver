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
    // Composite pass: screen-blend the (desaturated) history echo OVER the live frame. A decayed
    // ghost brightens the frame behind fast motion instead of replacing it with a muted copy, so
    // the result reads as a moving trail rather than a global desaturation.
    vec4 hist = texture(HistSampler, texCoord);
    float luma = dot(hist.rgb, vec3(0.299, 0.587, 0.114));
    vec3 histC = mix(hist.rgb, vec3(luma), desat);
    vec3 live = texture(InSampler, texCoord).rgb;
    vec3 outC = 1.0 - (1.0 - live) * (1.0 - histC * intensity);   // screen blend
    fragColor = vec4(outC, 1.0);
}
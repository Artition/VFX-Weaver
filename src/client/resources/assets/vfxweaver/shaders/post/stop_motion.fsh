#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float fps;
    float hold;
};

out vec4 fragColor;

void main() {
    // Hold gating from the CPU: `hold` is 1 while the current frame repeats a previously
    // captured copy of the main target (see VFXPostProcessingManager). Low fps freezes the
    // picture in steps; fps <= 1 disables the effect (hold always 0).
    fragColor = hold < 0.5 ? texture(InSampler, texCoord) : texture(HistSampler, texCoord);
}
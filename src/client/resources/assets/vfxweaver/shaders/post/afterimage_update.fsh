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
};

out vec4 fragColor;

void main() {
    // History update pass: mix the previous history (decaying, optionally zoomed by drift)
    // with the current frame, writing the *next* history target.
    vec2 uv = (texCoord - 0.5) * (1.0 + drift) + 0.5;
    vec4 prev = texture(HistSampler, uv) * decay;
    fragColor = mix(prev, texture(InSampler, texCoord), blend);
}
#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float amount;
    float time;
};

out vec4 fragColor;

void main() {
    vec3 live = texture(InSampler, texCoord).rgb;
    ivec2 sz = textureSize(HistSampler, 0);
    ivec2 p = clamp(ivec2(floor(texCoord * OutSize)), ivec2(0), sz - 1);
    // Point sampled: the mosh is carried over as it is, never filtered.
    vec3 moshed = texelFetch(HistSampler, p, 0).rgb;
    // The melt fades in over the first half second, so a restart never flashes the history
    // left over from the previous run.
    float ramp = smoothstep(0.0, 12.0, time);
    fragColor = vec4(mix(live, moshed, clamp(amount, 0.0, 1.0) * ramp), 1.0);
}

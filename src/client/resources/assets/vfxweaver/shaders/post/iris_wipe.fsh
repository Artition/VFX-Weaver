#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float radius;
    float softness;
    float center_x;
    float center_y;
    float zoom;
};

out vec4 fragColor;

void main() {
    vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
    vec2 corr = (texCoord - vec2(center_x, center_y)) * aspect;
    float dist = length(corr);
    float mask = smoothstep(radius - softness, radius + softness, dist);
    float zoomFactor = 1.0 - zoom * (1.0 - clamp(dist / max(radius, 1.0e-4), 0.0, 1.0));
    vec2 zoomed = vec2(center_x, center_y) + (texCoord - vec2(center_x, center_y)) * zoomFactor;
    vec4 inner = texture(InSampler, zoomed);
    fragColor = mix(inner, vec4(0.0, 0.0, 0.0, 1.0), mask);
}
#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
	vec2 OutSize;
	vec2 InSize;
};

layout(std140) uniform Config {
	float openness;
	float softness;
	float curve;
	float red;
	float green;
	float blue;
};

out vec4 fragColor;

void main() {
	// Seam model: both lids travel toward the SAME seam curve, so at openness=0 the two half-plane
	// masks sum to 1 everywhere and the screen is fully covered (solid black/colour, no gap).
	float c = clamp(1.0 - openness, 0.0, 1.0);
	float x = texCoord.x - 0.5;
	// Line of closure: positive curve bows it downward toward the centre (concave open eye).
	float seam = 0.5 + curve * 0.3 * (1.0 - 4.0 * x * x);
	float topEdge = mix(1.0 + softness, seam, c);
	float botEdge = mix(-softness, seam, c);

	float t = smoothstep(topEdge - softness, topEdge + softness, texCoord.y);
	float b = 1.0 - smoothstep(botEdge - softness, botEdge + softness, texCoord.y);
	float mask = clamp(t + b, 0.0, 1.0);

	fragColor = vec4(mix(texture(InSampler, texCoord).rgb, vec3(red, green, blue), mask), 1.0);
}
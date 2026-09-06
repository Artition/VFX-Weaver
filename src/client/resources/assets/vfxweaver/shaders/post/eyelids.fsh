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
	// Two lids bow toward the CENTRE from opposite sides: the top lid curves downward (concave
	// toward the eye), the bottom lid curves upward. The concavity amplitude shrinks as the eye
	// closes (c -> 1), so at openness:0 both edges meet exactly on a straight line and the screen
	// is fully covered (solid colour, no gap).
	float c = clamp(1.0 - openness, 0.0, 1.0);
	float x = texCoord.x - 0.5;
	float bow = (1.0 - 4.0 * x * x);          // 1 at centre, 0 at the sides
	float amp = curve * 0.35 * (1.0 - c);     // concavity present only while opening
	float topEdge = mix(1.0 + softness, 0.5, c) - amp * bow;
	float botEdge = mix(-softness, 0.5, c) + amp * bow;

	float t = smoothstep(topEdge - softness, topEdge + softness, texCoord.y);
	float b = 1.0 - smoothstep(botEdge - softness, botEdge + softness, texCoord.y);
	float mask = clamp(t + b, 0.0, 1.0);

	fragColor = vec4(mix(texture(InSampler, texCoord).rgb, vec3(red, green, blue), mask), 1.0);
}
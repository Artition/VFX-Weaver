#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
	vec2 OutSize;
	vec2 InSize;
};

layout(std140) uniform Config {
	float scale;
	float amplitude;
	float contrast;
	float coherence;
	float speed;
	float drift_x;
	float drift_y;
	float seed;
	float time;
};

out vec4 fragColor;

vec3 hash33(vec3 p3) {
	p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
	p3 += dot(p3, p3.yxz + 33.33);
	return fract((p3.xxy + p3.yxx) * p3.zyx) * 2.0 - 1.0;
}

float gnoise(vec3 p) {
	vec3 i = floor(p);
	vec3 f = fract(p);
	vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	return mix(
		mix(mix(dot(hash33(i), f),
			dot(hash33(i + vec3(1, 0, 0)), f - vec3(1, 0, 0)), u.x),
			mix(dot(hash33(i + vec3(0, 1, 0)), f - vec3(0, 1, 0)),
				dot(hash33(i + vec3(1, 1, 0)), f - vec3(1, 1, 0)), u.x), u.y),
		mix(mix(dot(hash33(i + vec3(0, 0, 1)), f - vec3(0, 0, 1)),
			dot(hash33(i + vec3(1, 0, 1)), f - vec3(1, 0, 1)), u.x),
			mix(dot(hash33(i + vec3(0, 1, 1)), f - vec3(0, 1, 1)),
				dot(hash33(i + vec3(1, 1, 1)), f - vec3(1, 1, 1)), u.x), u.y),
		u.z);
}

void main() {
	// `time` is the effect age in ticks; convert to seconds so `speed` is cycles per second.
	// `seed` shifts the noise domain, so stepping/animating it drifts the pattern independently
	// of `speed`. mod() keeps the field coordinates inside the hash's precision-safe range.
	float t = mod(time / 20.0, 600.0);
	float sd = mod(seed, 64.0);
	vec2 asp = vec2(InSize.x / InSize.y, 1.0);
	vec3 field = vec3(texCoord * asp * scale + vec2(drift_x, drift_y) * t, t * speed)
		+ vec3(sd * 19.19, sd * 7.7, sd * 3.3);

	// Gradient (Perlin) noise sampled at quintic-faded lattice points: soft isotropic bulges
	// instead of the axis-aligned blocks of value noise.
	float n = gnoise(field);
	float mag = pow(clamp(n * 0.7071 + 0.5, 0.0, 1.0), max(contrast, 0.1));

	// Direction: coherence 1 follows the gradient field (fluid), 0 pulls each patch its own way.
	float e = 0.35;
	vec2 gradDir;
	gradDir.x = gnoise(field + vec3(e, 0.0, 0.0)) - n;
	gradDir.y = gnoise(field + vec3(0.0, e, 0.0)) - n;
	vec2 rndDir;
	rndDir.x = gnoise(field + vec3(31.41, 0.0, 0.0));
	rndDir.y = gnoise(field + vec3(0.0, 47.21, 0.0));
	vec2 dir = mix(normalize(rndDir + 1.0e-6), normalize(gradDir + 1.0e-6), clamp(coherence, 0.0, 1.0));

	fragColor = texture(InSampler, texCoord + dir * mag * amplitude / asp);
}
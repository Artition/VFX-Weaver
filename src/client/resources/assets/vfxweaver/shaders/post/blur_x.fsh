#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float radius;
};

out vec4 fragColor;

// Fixed tap count per side (25 taps per axis, 50 across the X+Y passes). The Gaussian weights
// below are constants and only the sample spacing scales with `radius`, so an animated radius
// changes the result continuously. A tap count derived from the radius would step when the
// animated value crossed a threshold (a sample pair appearing in one frame changes the
// normalisation), which read as a sharp jump in strength.
const int TAPS = 12;
const float SPAN = 3.0;

void main() {
    float r = max(radius, 0.0);
    if (r <= 0.0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }
    // The Gaussian sigma stays equal to the radius (unchanged meaning); the taps span +/-SPAN
    // sigma, spaced SPAN/TAPS sigma apart so the kernel is well sampled at every radius.
    float spacing = r * SPAN / float(TAPS);
    vec2 offset = vec2(spacing / InSize.x, 0.0);

    vec4 color = texture(InSampler, texCoord);
    float total = 1.0;
    for (int i = 1; i <= TAPS; i++) {
        float d = float(i) * SPAN / float(TAPS);
        float w = exp(-0.5 * d * d);
        color += texture(InSampler, texCoord + offset * float(i)) * w;
        color += texture(InSampler, texCoord - offset * float(i)) * w;
        total += 2.0 * w;
    }
    fragColor = color / total;
}

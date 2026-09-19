#version 330

// The shared coverage-read consumer (spec §4). Appended after any masked effect, at any screen
// layer, now that the coverage is precomputed by post/mask_coverage at layer 0. It does no shape,
// depth or composition work: it reads the coverage texture and blends the effect output over the
// pre-effect image. Where coverage is 0 it early-outs. Honest limit: the fragment shader still
// runs at coverage 0; skipping the draw entirely needs stencil/scissor (deferred, Task 5).

uniform sampler2D InSampler;       // the effect output ("after")
uniform sampler2D HistSampler;     // the pre-effect image ("before"), preserved by the manager
uniform sampler2D CoverageSampler; // the precomputed coverage, R channel, [0,1]

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

void main() {
    vec4 before = texture(HistSampler, texCoord);
    float cov = texture(CoverageSampler, texCoord).r;
    if (cov <= 0.0) {
        fragColor = before;
        return;
    }
    vec4 after = texture(InSampler, texCoord);
    fragColor = vec4(mix(before.rgb, after.rgb, clamp(cov, 0.0, 1.0)), after.a);
}

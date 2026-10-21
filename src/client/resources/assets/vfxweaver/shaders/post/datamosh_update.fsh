#version 330

uniform sampler2D InSampler;
uniform sampler2D HistSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float residual;
    float smear;
    float chroma;
    float iframe;
    float time;
};

out vec4 fragColor;

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 live = texture(InSampler, texCoord).rgb;
    vec2 texel = 1.0 / OutSize;

    // The I-frame: once per interval the decoder gets a key frame and the picture snaps
    // back to the real screen. Without it the melt would only ever grow.
    if (iframe > 0.0 && mod(time, iframe * 20.0) < 1.0) {
        fragColor = vec4(live, 1.0);
        return;
    }

    // The motion vector, read at the macroblock centre the way a codec reads its vectors:
    // the whole 16 px block moves as one piece.
    vec2 blockUV = (floor(texCoord * OutSize / 16.0) + 0.5) * 16.0 / OutSize;
    float ix = (lum(texture(InSampler, blockUV + vec2(texel.x, 0.0)).rgb)
        - lum(texture(InSampler, blockUV - vec2(texel.x, 0.0)).rgb)) * 0.5;
    float iy = (lum(texture(InSampler, blockUV + vec2(0.0, texel.y)).rgb)
        - lum(texture(InSampler, blockUV - vec2(0.0, texel.y)).rgb)) * 0.5;
    float dt = lum(texture(InSampler, blockUV).rgb) - lum(texture(HistSampler, blockUV).rgb);
    float denom = ix * ix + iy * iy + 1.0e-4;
    // The estimate covers the whole lag of the reference (about 1 / residual frames), so it
    // is scaled back to the movement of a single frame before it is applied.
    vec2 vel = -vec2(ix, iy) * (ix * dt + iy * dt) / denom * clamp(residual, 0.02, 1.0);
    vec2 motion = vel * max(smear, 0.0);

    // The glitch threshold: below half a pixel the pixel is stuck where it is, so the still
    // parts of the picture do not crawl.
    if (length(motion) < 0.5) {
        motion = vec2(0.0);
    }

    // The warped previous frame. The colour planes drift apart along the motion, the way
    // they do in a 4:2:0 stream that was decoded against the wrong reference.
    vec2 uv0 = texCoord - motion * texel;
    vec2 uv1 = texCoord - motion * (1.0 + 0.5 * chroma) * texel;
    vec2 uv2 = texCoord - motion * (1.0 + chroma) * texel;
    vec3 moshed;
    if (any(lessThan(uv0, vec2(0.0))) || any(greaterThan(uv0, vec2(1.0)))) {
        moshed = live;
    } else {
        vec3 c0 = texture(HistSampler, uv0).rgb;
        vec3 c1 = texture(HistSampler, uv1).rgb;
        vec3 c2 = texture(HistSampler, uv2).rgb;
        moshed = vec3(c0.r, c1.g, c2.b);
    }

    // A little of the live picture still lands, the way a P-frame adds its residual to the
    // reference. It is what keeps the reference from dissolving.
    fragColor = vec4(mix(moshed, live, clamp(residual, 0.0, 1.0)), 1.0);
}

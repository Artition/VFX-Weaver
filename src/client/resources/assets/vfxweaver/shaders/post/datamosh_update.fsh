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
    float time;
};

out vec4 fragColor;

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// The reference rgb -> hsv, so the chroma plane can be rotated without touching the luma.
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

void main() {
    vec3 live = texture(InSampler, texCoord).rgb;
    vec2 texel = 1.0 / OutSize;

    // The decoder works on a macroblock: one motion vector for the whole block, so the
    // picture moves in whole pieces instead of crawling pixel by pixel.
    vec2 centre = (floor(texCoord * OutSize / 8.0) + 0.5) * 8.0 / OutSize;

    // The reference: the history. It barely refreshes, so it holds the screen as it was
    // half a second ago - the key frame that never arrives.
    vec3 ref = texture(HistSampler, centre).rgb;

    // The movement, one Lucas-Kanade step at the block centre.
    float ix = (lum(texture(InSampler, centre + vec2(texel.x, 0.0)).rgb)
        - lum(texture(InSampler, centre - vec2(texel.x, 0.0)).rgb)) * 0.5;
    float iy = (lum(texture(InSampler, centre + vec2(0.0, texel.y)).rgb)
        - lum(texture(InSampler, centre - vec2(0.0, texel.y)).rgb)) * 0.5;
    float dt = lum(texture(InSampler, centre).rgb) - lum(ref);
    float denom = ix * ix + iy * iy + 1.0e-4;
    // The estimate covers the whole lag of the reference (about 1 / residual frames), so it
    // is scaled back to the movement of a single frame before it is smeared out.
    vec2 vel = -vec2(ix, iy) * (ix * dt + iy * dt) / denom * clamp(residual, 0.02, 1.0);

    // The drag, snapped to a 4 px lattice the way a codec snaps its vectors, so the picture
    // comes apart in whole steps and the blocks hold still between them.
    vec2 drag = vel * max(smear, 0.0);
    drag = floor(drag / 4.0 + 0.5) * 4.0;
    drag *= min(1.0, 0.25 * min(OutSize.x, OutSize.y) / max(length(drag), 1.0e-4));

    // The decoder paints the old picture, dragged along the movement.
    vec3 dragged = texture(HistSampler, texCoord - drag * texel).rgb;

    // How wrong this block is: the old picture no longer matches what is on screen. It is
    // measured once per block, so the corruption does not shimmer from pixel to pixel.
    float damage = clamp(abs(lum(texture(InSampler, centre).rgb) - lum(ref)) * 0.5, 0.0, 1.0);

    // The chroma plane of a mismatched block is wrong: the decoder rotates the hue and
    // overstates the saturation. The luma is untouched, so the picture stays readable and
    // never turns into grey mush.
    vec3 hsv = rgb2hsv(dragged);
    hsv.x = fract(hsv.x + damage * chroma * 2.0);
    hsv.y = clamp(hsv.y + damage * chroma * 0.6, 0.0, 1.0);
    vec3 melted = hsv2rgb(hsv);

    // The little bit of the live picture that still lands, the way a P-frame adds its
    // residual to the reference. It is what keeps the reference from dissolving.
    melted = mix(melted, live, clamp(residual, 0.0, 1.0));

    fragColor = vec4(melted, 1.0);
}

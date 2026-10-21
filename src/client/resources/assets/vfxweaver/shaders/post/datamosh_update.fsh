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
    float break_level;
    float chroma;
    float time;
};

out vec4 fragColor;

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 live = texture(InSampler, texCoord).rgb;
    vec2 texel = 1.0 / OutSize;

    // The reference: the history. It barely refreshes - only the `residual` of the live
    // picture leaks into it - so it holds the screen as it was a moment ago. This is the
    // key frame that never arrives.
    vec3 prev = texture(HistSampler, texCoord).rgb;

    // The movement, one Lucas-Kanade step between the live picture and the reference. It is
    // the only thing that moves the frame: with nothing moving in the scene the estimate is
    // zero and the picture stays whole.
    float ix = (lum(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb)
        - lum(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb)) * 0.5;
    float iy = (lum(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb)
        - lum(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb)) * 0.5;
    float dt = lum(live) - lum(prev);
    float denom = ix * ix + iy * iy + 1.0e-4;
    // The estimate covers the whole lag of the reference (about 1 / residual frames), so it
    // is scaled back to the movement of a single frame before it is smeared out.
    vec2 vel = -vec2(ix, iy) * (ix * dt + iy * dt) / denom * clamp(residual, 0.02, 1.0);

    // The drag: the content is pulled from where it came from, `smear` frames deep. It is
    // snapped to a 2 px lattice, the way a codec snaps its vectors, so the picture comes
    // apart in whole steps instead of turning into a blur.
    vec2 drag = vel * max(smear, 0.0);
    drag = floor(drag / 2.0 + 0.5) * 2.0;
    drag *= min(1.0, 0.25 * min(OutSize.x, OutSize.y) / max(length(drag), 1.0e-4));

    // The decoder paints the old picture, dragged along the movement.
    vec3 dragged = texture(HistSampler, texCoord - drag * texel).rgb;

    // The damage: where the old picture no longer matches, the decoder is painting the
    // wrong block here.
    float damage = clamp(length(live - dragged) * 0.5, 0.0, 1.0);
    float level = clamp(break_level, 0.0, 1.0);
    float broken = smoothstep(level, level + 0.12, damage);

    // The residual of a block that no longer matches is applied with a broken scale: the
    // difference is blown up, so the picture comes apart into colour exactly where it
    // changed. No random noise - every colour is the picture's own.
    vec3 melted = dragged + (live - dragged) * (1.0 + 2.5 * clamp(chroma, 0.0, 1.0) * broken);
    // The little bit of the live picture that still lands, the way a P-frame adds its
    // residual to the reference.
    melted = mix(melted, live, clamp(residual, 0.0, 1.0));

    fragColor = vec4(melted, 1.0);
}

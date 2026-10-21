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

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 live = texture(InSampler, texCoord).rgb;
    vec2 texel = 1.0 / OutSize;

    // The reference: the history, the picture as it was a couple of frames ago.
    vec3 prev = texture(HistSampler, texCoord).rgb;

    // One Lucas-Kanade step: the velocity that best explains what changed between the
    // reference and the live picture. This is the only thing that moves the frame - with
    // nothing moving in the scene the estimate is zero and the picture stays whole.
    float ix = (lum(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb)
        - lum(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb)) * 0.5;
    float iy = (lum(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb)
        - lum(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb)) * 0.5;
    float dt = lum(live) - lum(prev);
    float denom = ix * ix + iy * iy + 1.0e-4;
    // The estimate covers the whole lag of the reference (about 1 / residual frames), so it
    // is scaled back to the movement of a single frame before it is smeared out.
    vec2 vel = -vec2(ix, iy) * (ix * dt + iy * dt) / denom * clamp(residual, 0.05, 1.0);

    // The drag: the content is pulled from where it came from, `smear` frames deep. It is
    // capped, so a noisy estimate cannot throw the picture off the screen.
    vec2 drag = vel * max(smear, 0.0);
    drag *= min(1.0, 0.2 * min(OutSize.x, OutSize.y) / max(length(drag), 1.0e-4));

    // The reconstruction: the old picture, dragged along the movement.
    vec3 dragged = texture(HistSampler, texCoord - drag * texel).rgb;
    vec3 melted = mix(dragged, live, clamp(residual, 0.0, 1.0));

    // The damage: where the picture moved, what the decoder painted here is wrong.
    float damage = clamp(length(live - dragged) * 0.5, 0.0, 1.0);
    float level = clamp(break_level, 0.0, 1.0);
    float broken = smoothstep(level, level + 0.12, damage);

    // The broken pixels fall apart into colour, one hue per pixel; a faint 8 px block tint
    // keeps the shape of the codec's blocks without drawing a grid.
    float hue = hash12(texCoord * OutSize + floor(time / 3.0) * 13.0);
    float tint = hash12(floor(texCoord * OutSize / 8.0) + floor(time / 8.0) * 7.0);
    vec3 garbage = mix(hsv2rgb(vec3(hue, 0.85, 1.0)), hsv2rgb(vec3(tint, 0.9, 1.0)), 0.12);
    melted = mix(melted, garbage, broken * clamp(chroma, 0.0, 1.0));

    fragColor = vec4(melted, 1.0);
}

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
    float flow;
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

float vnoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x),
        mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

void main() {
    vec3 live = texture(InSampler, texCoord).rgb;
    vec2 pixel = texCoord * OutSize;

    // The flow field: a slow, smooth wander that drags the picture. It is what a codec's
    // motion vectors do, minus the part where they still match the picture.
    float t = time / 20.0;
    vec2 field = vec2(
        vnoise2(texCoord * 2.3 + vec2(t * 0.35, 0.0)),
        vnoise2(texCoord * 2.3 + vec2(0.0, t * 0.35) + 17.3));
    vec2 drag = (field * 2.0 - 1.0) * max(flow, 0.0) / OutSize;

    // The reference: the history, dragged. With the key frame gone, the decoder paints this
    // instead of the current picture.
    vec3 dragged = texture(HistSampler, texCoord - drag).rgb;

    // The reconstruction: the old picture plus the little residual that still lands.
    vec3 melted = mix(dragged, live, clamp(residual, 0.0, 1.0));

    // The damage: where the picture moved or changed, what the decoder painted here is wrong.
    float damage = clamp(length(live - dragged) * 0.5, 0.0, 1.0);
    float level = clamp(break_level, 0.0, 1.0);
    float broken = smoothstep(level, level + 0.12, damage);

    // The broken pixels fall apart into colour: a hue per pixel, tinted by the block.
    float hue = hash12(pixel + floor(time / 3.0) * 13.0);
    float tint = hash12(floor(pixel / 16.0) + floor(time / 8.0) * 7.0);
    vec3 garbage = mix(hsv2rgb(vec3(hue, 0.85, 1.0)), hsv2rgb(vec3(tint, 0.9, 1.0)), 0.35);
    melted = mix(melted, garbage, broken * clamp(chroma, 0.0, 1.0));

    fragColor = vec4(melted, 1.0);
}

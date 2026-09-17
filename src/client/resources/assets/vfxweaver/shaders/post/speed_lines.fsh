#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float center_x;
    float center_y;
    float count;
    // NB: this uniform cannot be called `length` - a uniform with that name shadows the built-in
    // length() function and the shader fails to compile ("cannot call a non-function").
    float line_length;
    float length_rand;
    float pos_rand;
    float width;
    float seed;
    float color_r;
    float color_g;
    float color_b;
    float intensity;
};

out vec4 fragColor;

void main() {
    vec4 texColor = texture(InSampler, texCoord);

    // UV relative to the effect centre, corrected for aspect so the lines stay circular.
    float aspect = max(OutSize.x / max(OutSize.y, 1.0e-4), 1.0e-4);
    vec2 centeredUV = vec2(texCoord.x - center_x, texCoord.y - center_y);
    centeredUV.x *= aspect;

    float dist = length(centeredUV);
    float angle = atan(centeredUV.y, centeredUV.x);

    // Normalise the angle to [0, 1] and split the screen into sectors.
    float normAngle = (angle / 6.2831853) + 0.5;
    float sectors = max(count, 1.0);
    float segmentSize = 1.0 / sectors;
    float segIndex = floor(normAngle / segmentSize);

    // Per-sector pseudo-random value, seeded by the (possibly animated) seed.
    float rand = fract(sin(segIndex * 12.9898 + seed * 78.233) * 43758.5453);

    // Distance from the centre to the screen border along this ray.
    vec2 dir = vec2(cos(angle), sin(angle));
    float tx = dir.x > 0.0 ? (1.0 - center_x) * aspect / max(dir.x, 1.0e-4)
             : dir.x < 0.0 ? -center_x * aspect / min(dir.x, -1.0e-4)
             : 1.0e9;
    float ty = dir.y > 0.0 ? (1.0 - center_y) / max(dir.y, 1.0e-4)
             : dir.y < 0.0 ? -center_y / min(dir.y, -1.0e-4)
             : 1.0e9;
    float borderDist = min(tx, ty);

    // Random length: the configured length (a fraction of the ray to the border) is
    // scaled by the sector random value; length_rand (0..1) controls how much.
    float randomFactor = mix(1.0, rand, clamp(length_rand, 0.0, 1.0));
    float lineLen = clamp(line_length, 0.0, 1.0) * borderDist * randomFactor;

    // Lines emanate from the screen border and point inward towards the centre.
    float inner = max(borderDist - lineLen, 0.0);

    // Position along the line: 0 = inner tip, 1 = border. Each line is a wedge:
    // full width at the border (the thick part is cut off by the screen edge) and
    // tapering to a point towards the centre, with a sharp (step) edge.
    float linePos = clamp((dist - inner) / max(lineLen, 1.0e-4), 0.0, 1.0);
    float taper = linePos;
    float w = clamp(width, 0.0, 1.0);
    // Uneven spacing: each line's angular centre is jittered inside its own sector by a per-sector
    // random value (seeded, so animating seed slides the lines around). pos_rand scales it:
    // 0 = the old even spacing, 1 = the line may sit anywhere in its sector. The offset is measured
    // to the NEAREST centre (wrapped), so a line pushed against a sector boundary is not cut off.
    float posRand = fract(sin(segIndex * 91.173 + seed * 41.331) * 24634.6345);
    float lineCenter = (segIndex + 0.5 + (posRand - 0.5) * clamp(pos_rand, 0.0, 1.0)) * segmentSize;
    float angularDelta = (normAngle - lineCenter) / segmentSize;
    angularDelta -= floor(angularDelta + 0.5);
    float widthMask = step(abs(angularDelta) * 2.0, w * taper);

    // Hard radial clip: only the [inner, borderDist] band is drawn.
    float distMask = step(inner, dist) * (1.0 - step(borderDist, dist));

    float lineMask = widthMask * distMask;

    vec3 lineColor = vec3(clamp(color_r, 0.0, 1.0), clamp(color_g, 0.0, 1.0), clamp(color_b, 0.0, 1.0));
    float a = clamp(intensity, 0.0, 1.0) * lineMask;
    fragColor = vec4(mix(texColor.rgb, lineColor, a), texColor.a);
}
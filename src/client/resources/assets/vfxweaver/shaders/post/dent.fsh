#version 330

#moj_import <vfxweaver:field.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    float strength;
    float radius;
    float center_x;
    float center_y;
    float line_mode;
    float x0;
    float y0;
    float x1;
    float y1;
};

out vec4 fragColor;

void main() {
    // Point mode (default) collapses the segment into a single center point.
    vec2 a = vec2(center_x, center_y);
    vec2 b = a;
    if (line_mode > 0.5) {
        a = vec2(x0, y0);
        b = vec2(x1, y1);
    }

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    // Closest point on the segment a-b to this fragment, in aspect-corrected space.
    vec2 p = vec2(texCoord.x * aspect, texCoord.y);
    vec2 aa = vec2(a.x * aspect, a.y);
    vec2 ab = vec2(b.x * aspect, b.y) - aa;
    float abLen2 = dot(ab, ab);
    float t = abLen2 < 1.0e-8 ? 0.0 : clamp(dot(p - aa, ab) / abLen2, 0.0, 1.0);
    vec2 closest = aa + ab * t;
    vec2 closestUv = vec2(closest.x / aspect, closest.y);
    vec2 d = p - closest;
    float dist = length(d);
    float r = max(radius, 1.0e-4);

    if (dist >= r || strength == 0.0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // Strongest at the segment itself, smoothly reaching zero at the edge of the circle.
    float falloff = 1.0 - dist / r;
    falloff *= falloff;

    // strength > 0 shrinks the offset (pixels pulled INTO the point = dent),
    // strength < 0 grows it (pixels pushed OUT of the point = bulge).
    // The field multiplies the animated strength per pixel (default 1.0 = no field).
    float intensity = vfx_field_intensity(texCoord);
    float scale = 1.0 - strength * intensity * falloff;

    vec2 uv = closestUv + vec2(d.x * scale / aspect, d.y * scale);

    // Blend back to the original UV near the radius to hide any clamping.
    float edgeFade = 1.0 - smoothstep(0.7, 1.0, dist / r);
    uv = mix(texCoord, uv, edgeFade);

    fragColor = texture(InSampler, uv);
}

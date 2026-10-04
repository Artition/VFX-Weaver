#version 330

// screen_image: composites a picture over the current frame inside a normalized rect. The rect,
// the frame's sub-rect of the whole image, the opacity and a resolved flag come from the Config UBO.
// `flags` is 0 when the texture did not resolve, so the pass is a passthrough. Config field order
// mirrors VFXShaderPrograms.registerScreenImagePost (std140 offsets are positional).
uniform sampler2D InSampler;
uniform sampler2D ImageSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform Config {
    vec4 rect;      // x0, y0, x1, y1 in [0,1] UV
    vec4 frame_uv;  // u0, v0, u1, v1 of the frame inside the whole image
    float opacity;
    float flags;    // 1 = the image resolved
};

out vec4 fragColor;

void main() {
    vec4 base = texture(InSampler, texCoord);
    if (flags < 0.5) {
        fragColor = base;
        return;
    }
    vec2 lo = min(rect.xy, rect.zw);
    vec2 hi = max(rect.xy, rect.zw);
    // A zero-area rect (size_w/size_h animated to 0, a zero pixel box) would divide by zero below
    // and sample NaN coordinates; fail closed as a passthrough instead.
    if (hi.x - lo.x <= 0.0 || hi.y - lo.y <= 0.0) {
        fragColor = base;
        return;
    }
    if (texCoord.x < lo.x || texCoord.x > hi.x || texCoord.y < lo.y || texCoord.y > hi.y) {
        fragColor = base;
        return;
    }
    vec2 local = (texCoord - lo) / (hi - lo);
    vec2 uv = mix(frame_uv.xy, frame_uv.zw, local);
    vec4 img = texture(ImageSampler, uv);
    fragColor = vec4(mix(base.rgb, img.rgb, clamp(img.a * opacity, 0.0, 1.0)), base.a);
}
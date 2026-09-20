// Shared texture sampling / sprite-sheet / channel helpers. One implementation, two consumers: the
// field library's `texture` function (fn 5) and the textured `surface_pattern` figure. A consumer
// with no atlas passes rect = (0, 0, 1, 1), sheet = (1, 1), frame = 0 and gets the plain sample.
//
// Channel codes match VFXFieldProgram.channelCode and VFXTexture.Channel (positional contract):
//   r = 0, g = 1, b = 2, alpha = 3, luminance = 4, none = 5 (the field-only "full RGB" mode).

// Maps a 0..1 cell coordinate into a texture's 0..1 rect (an atlas sprite's sub-rect, or 0..1 for a
// standalone texture). The coordinate is clamped so a repeat never bleeds into a neighbouring atlas
// sprite; the bound sampler stays CLAMP_TO_EDGE.
vec2 vfx_texture_rect_uv(vec2 uv, vec4 rect) {
	return mix(rect.xy, rect.zw, clamp(uv, 0.0, 1.0));
}

// Selects one cell of a cols x rows sprite sheet. The coordinate is clamped into [0,1], wrapped
// `frame` is shifted into its cell and only then mapped, so a frame never samples the next sheet
// cell. `frame` is wrapped into [0, cols*rows) so a runaway graph value cannot index out of range.
vec2 vfx_texture_sheet_uv(vec2 uv, vec2 sheet, float frame) {
	float cells = max(sheet.x * sheet.y, 1.0);
	if (cells <= 1.0) {
		return clamp(uv, 0.0, 1.0);
	}
	float f = mod(floor(frame + 0.5), cells);
	vec2 cell = vec2(mod(f, sheet.x), floor(f / sheet.x));
	return (clamp(uv, 0.0, 1.0) + cell) / sheet;
}

// Samples the texture at a 0..1 cell coordinate within the given rect and sheet cell.
vec4 vfx_texture_sample(sampler2D tex, vec2 uv, vec4 rect, vec2 sheet, float frame) {
	return texture(tex, vfx_texture_rect_uv(vfx_texture_sheet_uv(uv, sheet, frame), rect));
}

// Extracts the coverage component. Codes 0..4 are r/g/b/alpha/luminance; anything else (the field
// library's "none" = 5) is handled by the caller as the full RGB.
float vfx_texture_channel(vec4 texel, int channel) {
	if (channel == 0) return texel.r;
	if (channel == 1) return texel.g;
	if (channel == 2) return texel.b;
	if (channel == 3) return texel.a;
	return dot(texel.rgb, vec3(0.2126, 0.7152, 0.0722));
}

// Keeps the texture's pixel aspect inside a square cell (`aspect: preserve`): the cell's shorter
// axis is scaled so the texture's longer pixel axis spans the full cell. `texAspect` = width /
// height. Values outside the resulting 0..1 band clamp to the texture edge.
vec2 vfx_texture_aspect(vec2 uv, float texAspect) {
	float a = max(texAspect, 1.0e-4);
	if (a >= 1.0) {
		return vec2(uv.x, (uv.y - 0.5) * a + 0.5);
	}
	return vec2((uv.x - 0.5) / a + 0.5, uv.y);
}

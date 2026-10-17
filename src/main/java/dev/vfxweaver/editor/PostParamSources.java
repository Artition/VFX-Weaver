package dev.vfxweaver.editor;

import dev.vfxweaver.effect.VFXEffectType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The verbatim parameter-name lists of every post-processing effect, copied one-for-one and in
 * order from the {@code VFXShaderPrograms.registerPost*}/{@code registerFeedbackEffects} call
 * sites in {@code src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java}.
 *
 * <p>This is a parallel source of the std140 {@code Config} parameter contract: it is what
 * {@link VFXEffectSchema#registerBuiltIns()} turns into editor descriptors and what the
 * {@code scripts/check-vfx-schema.ps1} drift check compares against the registrations. The client
 * registration is the source of truth — <b>never</b> reorder or edit {@code VFXShaderPrograms}; if
 * a name here drifts, the check fails. A {@code registerFieldPost} field-capable input (its third
 * argument) is a real logical effect input and is appended after that type's {@code Config} names.
 */
final class PostParamSources {
	/** The ordered parameter names of every post-processing type ({@code SCREEN_IMAGE} is empty). */
	static final Map<VFXEffectType, String[]> PARAMS;

	static {
		final Map<VFXEffectType, String[]> params = new LinkedHashMap<>();
		// order = VFXShaderPrograms.registerPost(CHROMATIC_ABERRATION); do not reorder
		params.put(VFXEffectType.CHROMATIC_ABERRATION, new String[]{"intensity", "radius"});
		// order = VFXShaderPrograms.registerFieldPost(COLOR_GRADE); do not reorder.
		// tint_r (last) is the registerFieldPost fieldInput, appended after the Config order on purpose.
		params.put(VFXEffectType.COLOR_GRADE, new String[]{"saturation", "contrast", "brightness", "tint_g", "tint_b", "tint_r"});
		// order = VFXShaderPrograms.registerPost(DISTORTION); do not reorder
		params.put(VFXEffectType.DISTORTION, new String[]{"amount", "radius"});
		// order = VFXShaderPrograms.registerFieldPost(DENT); do not reorder.
		// intensity (last) is the registerFieldPost fieldInput, appended after the Config order on purpose.
		params.put(VFXEffectType.DENT, new String[]{"strength", "radius", "center_x", "center_y", "line_mode", "x0", "y0", "x1", "y1", "intensity"});
		// order = VFXShaderPrograms.registerPost(GRADIENT_MAP); do not reorder
		params.put(VFXEffectType.GRADIENT_MAP, new String[]{"from_r", "from_g", "from_b", "to_r", "to_g", "to_b", "intensity", "mode", "pos"});
		// order = VFXShaderPrograms.registerPost(POSTERIZE); do not reorder
		params.put(VFXEffectType.POSTERIZE, new String[]{"strength"});
		// order = VFXShaderPrograms.registerMultiPass(BLUR); do not reorder
		params.put(VFXEffectType.BLUR, new String[]{"radius"});
		// order = VFXShaderPrograms.registerPost(PIXELATE); do not reorder
		params.put(VFXEffectType.PIXELATE, new String[]{"cell_size"});
		// order = VFXShaderPrograms.registerPost(HUE_ISOLATION); do not reorder
		params.put(VFXEffectType.HUE_ISOLATION, new String[]{"hue", "tolerance", "intensity"});
		// order = VFXShaderPrograms.registerPost(VIGNETTE); do not reorder
		params.put(VFXEffectType.VIGNETTE, new String[]{"intensity", "color_r", "color_g", "color_b"});
		// order = VFXShaderPrograms.registerPost(SCREEN_FLASH); do not reorder
		params.put(VFXEffectType.SCREEN_FLASH, new String[]{"alpha", "color_r", "color_g", "color_b"});
		// order = VFXShaderPrograms.registerPost(MOTION_BLUR); do not reorder
		params.put(VFXEffectType.MOTION_BLUR, new String[]{"intensity", "yaw_delta", "pitch_delta"});
		// order = VFXShaderPrograms.registerPost(BLOOM); do not reorder
		params.put(VFXEffectType.BLOOM, new String[]{"intensity", "threshold", "radius"});
		// order = VFXShaderPrograms.registerPost(FILM_GRAIN); do not reorder
		params.put(VFXEffectType.FILM_GRAIN, new String[]{"intensity", "size", "time", "chroma"});
		// order = VFXShaderPrograms.registerPost(SCANLINES); do not reorder
		params.put(VFXEffectType.SCANLINES, new String[]{"intensity", "line_count", "speed", "time"});
		// order = VFXShaderPrograms.registerPost(HALFTONE); do not reorder
		params.put(VFXEffectType.HALFTONE, new String[]{"cell_size", "angle", "gain", "amount"});
		// order = VFXShaderPrograms.registerPost(DEPTH_OF_FIELD); do not reorder
		params.put(VFXEffectType.DEPTH_OF_FIELD, new String[]{"intensity", "focus_center", "focus_range"});
		// order = VFXShaderPrograms.registerPost(LETTERBOX); do not reorder
		params.put(VFXEffectType.LETTERBOX, new String[]{"height", "color_r", "color_g", "color_b"});
		// order = VFXShaderPrograms.registerPost(INVERT); do not reorder
		params.put(VFXEffectType.INVERT, new String[]{"intensity"});
		// order = VFXShaderPrograms.registerPost(VORTEX); do not reorder
		params.put(VFXEffectType.VORTEX, new String[]{"strength", "radius", "center_x", "center_y"});
		// order = VFXShaderPrograms.registerPost(SPEED_LINES); do not reorder
		params.put(VFXEffectType.SPEED_LINES, new String[]{"center_x", "center_y", "count", "length", "length_rand", "pos_rand", "width", "seed", "color_r", "color_g", "color_b", "intensity"});
		// order = VFXShaderPrograms.registerPost(SLICE_SHIFT); do not reorder
		params.put(VFXEffectType.SLICE_SHIFT, new String[]{"angle", "offset", "shift", "mirror"});
		// order = VFXShaderPrograms.registerPost(SOLARIZE); do not reorder
		params.put(VFXEffectType.SOLARIZE, new String[]{"threshold", "softness", "intensity"});
		// order = VFXShaderPrograms.registerPost(DOUBLE_VISION); do not reorder
		params.put(VFXEffectType.DOUBLE_VISION, new String[]{"offset", "ghost_opacity", "drift", "intensity", "time"});
		// order = VFXShaderPrograms.registerPost(EYELIDS); do not reorder
		params.put(VFXEffectType.EYELIDS, new String[]{"openness", "softness", "curve", "red", "green", "blue"});
		// order = VFXShaderPrograms.registerPost(IRIS_WIPE); do not reorder
		params.put(VFXEffectType.IRIS_WIPE, new String[]{"radius", "softness", "center_x", "center_y", "zoom"});
		// order = VFXShaderPrograms.registerPost(DIGITAL_GLITCH); do not reorder
		params.put(VFXEffectType.DIGITAL_GLITCH, new String[]{"block", "displacement", "rate", "chroma", "seed", "chance", "intensity", "time"});
		// order = VFXShaderPrograms.registerPost(VHS); do not reorder
		params.put(VFXEffectType.VHS, new String[]{"tracking", "band_height", "band_speed", "bleed", "wobble", "intensity", "time"});
		// order = VFXShaderPrograms.registerPost(SHOCKWAVE); do not reorder
		params.put(VFXEffectType.SHOCKWAVE, new String[]{"center_x", "center_y", "radius", "width", "amplitude", "sharpness"});
		// order = VFXShaderPrograms.registerFeedbackEffects(AFTERIMAGE); do not reorder
		params.put(VFXEffectType.AFTERIMAGE, new String[]{"decay", "blend", "drift", "desat", "intensity"});
		// order = VFXShaderPrograms.registerFeedbackEffects(STOP_MOTION); do not reorder
		params.put(VFXEffectType.STOP_MOTION, new String[]{"fps", "hold"});
		// order = VFXShaderPrograms.registerPost(NOISE_WARP); do not reorder
		params.put(VFXEffectType.NOISE_WARP, new String[]{"scale", "amplitude", "contrast", "coherence", "speed", "drift_x", "drift_y", "seed", "time"});
		// order = VFXShaderPrograms.registerScreenImagePost(SCREEN_IMAGE); do not reorder
		// The manager writes this Config positionally, so it carries no per-param names here.
		params.put(VFXEffectType.SCREEN_IMAGE, new String[]{});
		// order = VFXShaderPrograms.registerDepthPost(SURFACE_PATTERN); do not reorder
		params.put(VFXEffectType.SURFACE_PATTERN, new String[]{"tile_scale", "color_r", "color_g", "color_b", "opacity", "fade_radius", "normal_mask", "distort", "center_x", "center_y", "center_z", "shape", "fill", "rotation", "stroke_width", "softness", "repeat_x", "repeat_y", "radius", "radius_x", "radius_y", "half_width", "half_height", "corner_radius", "sides", "time", "face_mask", "band_min", "band_max", "band_softness", "shape_present", "tex_u0", "tex_v0", "tex_u1", "tex_v1", "tex_aspect", "tex_cols", "tex_rows", "tex_frame", "tex_flags", "tex_channel", "texture_tint", "tex_px_w", "tex_px_h", "stitch"});
		// order = VFXShaderPrograms.registerDepthPost(SKY_PATTERN, true); do not reorder
		params.put(VFXEffectType.SKY_PATTERN, new String[]{"anchor_yaw", "anchor_pitch", "dome_rotation", "tile_scale", "color_r", "color_g", "color_b", "opacity", "distort", "shape", "fill", "rotation", "stroke_width", "softness", "repeat_x", "repeat_y", "radius", "radius_x", "radius_y", "half_width", "half_height", "corner_radius", "sides", "time", "shape_present", "tex_u0", "tex_v0", "tex_u1", "tex_v1", "tex_aspect", "tex_cols", "tex_rows", "tex_frame", "tex_flags", "tex_channel", "texture_tint", "tex_px_w", "tex_px_h", "sky_mode", "anchor_stars", "star_angle"});
		PARAMS = Collections.unmodifiableMap(params);
	}

	private PostParamSources() {
	}
}

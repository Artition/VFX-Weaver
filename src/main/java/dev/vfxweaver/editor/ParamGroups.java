package dev.vfxweaver.editor;

import dev.vfxweaver.effect.VFXEffectType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The editor's parameter-group table: which display group each parameter of an effect type
 * belongs to. Groups are presentation-only (design §14: the UI generates from schema flags) —
 * the compiler never reads them and the canvas sockets stay flat.
 *
 * <p>Keys are short stable group ids (e.g. {@code anchor}, {@code dome}, {@code tile},
 * {@code color}, {@code texture}, {@code shape}); the inspector labels a section with the id.
 * An effect type absent from this table (or a param absent from its row) renders exactly as
 * before: its params land in the default group ({@code ""}).
 */
final class ParamGroups {
	/** The {@code param name -> group id} row of every grouped effect type. */
	static final Map<VFXEffectType, Map<String, String>> GROUPS;

	static {
		final Map<VFXEffectType, Map<String, String>> groups = new LinkedHashMap<>();
		// sky_pattern (41 params): anchor/dome placement, tiling, tint, figure, texture sheet, time.
		final Map<String, String> sky = new LinkedHashMap<>();
		sky.put("anchor_yaw", "anchor");
		sky.put("anchor_pitch", "anchor");
		sky.put("sky_mode", "anchor");
		sky.put("anchor_stars", "anchor");
		sky.put("star_angle", "anchor");
		sky.put("dome_rotation", "dome");
		sky.put("tile_scale", "tile");
		sky.put("repeat_x", "tile");
		sky.put("repeat_y", "tile");
		sky.put("color_r", "color");
		sky.put("color_g", "color");
		sky.put("color_b", "color");
		sky.put("opacity", "color");
		sky.put("texture_tint", "color");
		sky.put("distort", "shape");
		sky.put("shape", "shape");
		sky.put("fill", "shape");
		sky.put("rotation", "shape");
		sky.put("stroke_width", "shape");
		sky.put("softness", "shape");
		sky.put("radius", "shape");
		sky.put("radius_x", "shape");
		sky.put("radius_y", "shape");
		sky.put("half_width", "shape");
		sky.put("half_height", "shape");
		sky.put("corner_radius", "shape");
		sky.put("sides", "shape");
		sky.put("shape_present", "shape");
		sky.put("tex_u0", "texture");
		sky.put("tex_v0", "texture");
		sky.put("tex_u1", "texture");
		sky.put("tex_v1", "texture");
		sky.put("tex_aspect", "texture");
		sky.put("tex_cols", "texture");
		sky.put("tex_rows", "texture");
		sky.put("tex_frame", "texture");
		sky.put("tex_flags", "texture");
		sky.put("tex_channel", "texture");
		sky.put("tex_px_w", "texture");
		sky.put("tex_px_h", "texture");
		sky.put("time", "time");
		groups.put(VFXEffectType.SKY_PATTERN, Collections.unmodifiableMap(sky));
		// surface_pattern shares the figure/texture vocabulary; its own placement lands in "surface".
		final Map<String, String> surface = new LinkedHashMap<>();
		surface.put("center_x", "surface");
		surface.put("center_y", "surface");
		surface.put("center_z", "surface");
		surface.put("face_mask", "surface");
		surface.put("band_min", "surface");
		surface.put("band_max", "surface");
		surface.put("band_softness", "surface");
		surface.put("normal_mask", "surface");
		surface.put("fade_radius", "surface");
		surface.put("stitch", "surface");
		surface.put("tile_scale", "tile");
		surface.put("repeat_x", "tile");
		surface.put("repeat_y", "tile");
		surface.put("color_r", "color");
		surface.put("color_g", "color");
		surface.put("color_b", "color");
		surface.put("opacity", "color");
		surface.put("texture_tint", "color");
		surface.put("distort", "shape");
		surface.put("shape", "shape");
		surface.put("fill", "shape");
		surface.put("rotation", "shape");
		surface.put("stroke_width", "shape");
		surface.put("softness", "shape");
		surface.put("radius", "shape");
		surface.put("radius_x", "shape");
		surface.put("radius_y", "shape");
		surface.put("half_width", "shape");
		surface.put("half_height", "shape");
		surface.put("corner_radius", "shape");
		surface.put("sides", "shape");
		surface.put("shape_present", "shape");
		surface.put("tex_u0", "texture");
		surface.put("tex_v0", "texture");
		surface.put("tex_u1", "texture");
		surface.put("tex_v1", "texture");
		surface.put("tex_aspect", "texture");
		surface.put("tex_cols", "texture");
		surface.put("tex_rows", "texture");
		surface.put("tex_frame", "texture");
		surface.put("tex_flags", "texture");
		surface.put("tex_channel", "texture");
		surface.put("tex_px_w", "texture");
		surface.put("tex_px_h", "texture");
		surface.put("time", "time");
		groups.put(VFXEffectType.SURFACE_PATTERN, Collections.unmodifiableMap(surface));
		GROUPS = Collections.unmodifiableMap(groups);
	}

	private ParamGroups() {
	}

	/**
	 * Returns the group id of a parameter, or {@code ""} for the default (ungrouped) group.
	 *
	 * @param type the effect type
	 * @param name the parameter name
	 * @return the group id, never {@code null}
	 */
	static String groupOf(final VFXEffectType type, final String name) {
		final Map<String, String> row = GROUPS.get(type);
		if (row == null) {
			return "";
		}
		return row.getOrDefault(name, "");
	}
}

package dev.vfxweaver.effect;

import java.util.Locale;

/**
 * The built-in visual effect kinds this mod can render. Each kind maps to a fragment
 * shader (post-processing passes) or to a camera behaviour ({@link #CAMERA_SHAKE}).
 */
public enum VFXEffectType {
	/** Red/cyan channel separation that grows towards the screen edges. */
	CHROMATIC_ABERRATION("chromatic_aberration"),
	/** Saturation, contrast and brightness adjustment with a tint. */
	COLOR_GRADE("color_grade"),
	/** Barrel/pincushion distortion. */
	DISTORTION("distortion"),
	/** Localized "dent": pixels are pulled into (positive strength) or pushed out of (negative strength) a point, or along a segment when {@code line_mode} is set. */
	DENT("dent"),
	/** Maps the luminance to a two-colour gradient. */
	GRADIENT_MAP("gradient_map"),
	/** Reduces the number of distinct colour levels (posterization) with dithering noise. */
	POSTERIZE("posterize"),
	/** Directional Gaussian blur. */
	BLUR("blur"),
	/** Pixelation / downsampling. */
	PIXELATE("pixelate"),
	/** Keeps only the colour that matches a target hue, everything else becomes grayscale. */
	HUE_ISOLATION("hue_isolation"),
	/** Darkens or colours the edges of the screen. */
	VIGNETTE("vignette"),
	/** Full-screen colour overlay for flashes. */
	SCREEN_FLASH("screen_flash"),
	/** Directional blur driven by camera rotation speed. */
	MOTION_BLUR("motion_blur"),
	/** Single-pass glow around bright areas (threshold + soft halo). */
	BLOOM("bloom"),
	/** Animated film grain noise. */
	FILM_GRAIN("film_grain"),
	/** CRT-style horizontal scanlines that can drift over time. */
	SCANLINES("scanlines"),
	/** Screen-space "tilt-shift" depth of field: a sharp focus band that blurs away from it. */
	DEPTH_OF_FIELD("depth_of_field"),
	/** Cinematic black bars at the top and bottom of the screen. */
	LETTERBOX("letterbox"),
	/** Inverts the screen colours. */
	INVERT("invert"),
	/** Swirls pixels around a point: strongest at the centre, fading out to the radius. */
	VORTEX("vortex"),
	/** Modifies the camera field of view. */
	FOV_MODIFIER("fov_modifier"),
	/** Camera shake driven by simplex noise. */
	CAMERA_SHAKE("camera_shake"),
	/** Fixed camera roll around the view axis with an optional sinusoidal wobble. */
	CAMERA_ROLL("camera_roll"),
	/** Renders a coloured outline around a block (world overlay, not a shader pass). */
	BLOCK_OUTLINE("block_outline"),
	/** Renders a solid-colour fill over a block (world overlay, not a shader pass). */
	BLOCK_TINT("block_tint"),
	/** Re-emits the targeted block's baked model quads as a flat, per-vertex displaced echo. */
	BLOCK_DISPLACE("block_displace"),
	/** Renders a solid-colour tint over the targeted entities. */
	ENTITY_TINT("entity_tint"),
	/** Renders an outline around the targeted entities. */
	ENTITY_OUTLINE("entity_outline"),
	/** Re-emits the targeted entity's model as a flat, per-vertex displaced echo (ghost copy). */
	ENTITY_DISPLACE("entity_displace"),
	/** Radial speed lines emanating from a centre point (post-processing pass). */
	SPEED_LINES("speed_lines"),
	/** The frame is cut by a straight line and the halves slide past each other along it. */
	SLICE_SHIFT("slice_shift"),
	/** Bright pixels invert, dark pixels stay untouched. */
	SOLARIZE("solarize"),
	/** Two ghost copies of the frame offset left/right with a slow drift. */
	DOUBLE_VISION("double_vision"),
	/** Two soft curved dark lids slide in from the top and bottom of the screen. */
	EYELIDS("eyelids"),
	/** Old-film iris transition: everything outside a circle goes black. */
	IRIS_WIPE("iris_wipe"),
	/** The frame tears into horizontal bands with RGB-split spikes in bursts. */
	DIGITAL_GLITCH("digital_glitch"),
	/** Worn VHS playback: wobble, a crawling tracking band and colour bleed. */
	VHS("vhs"),
	/** A single refraction ring ripples outward from a point. */
	SHOCKWAVE("shockwave"),
	/** Additive light beams rising out of the targeted entity's body (dragon-death style). */
	GOD_RAYS("god_rays"),
	/** An animated value-noise field warps the picture in soft fluid patches. */
	NOISE_WARP("noise_warp"),
	/** Not an effect itself: plays a list of child effects with per-child delays. */
	COLLECTION("collection");

	private final String name;

	VFXEffectType(final String name) {
		this.name = name;
	}

	/**
	 * The base identifier of this effect type, also used as the fragment shader name in
	 * {@code assets/<ns>/shaders/post/}.
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * True for the effect types that render a fullscreen post-processing pass.
	 */
	public boolean isPostProcessing() {
		return this != CAMERA_SHAKE && this != CAMERA_ROLL && this != BLOCK_OUTLINE && this != BLOCK_TINT && this != ENTITY_TINT && this != ENTITY_OUTLINE && this != ENTITY_DISPLACE && this != GOD_RAYS && this != FOV_MODIFIER && this != COLLECTION;
	}

	/**
	 * True for the effect types that render world-space geometry each frame.
	 */
	public boolean isWorldOverlay() {
		return this == BLOCK_OUTLINE || this == BLOCK_TINT || this == BLOCK_DISPLACE || this == ENTITY_TINT || this == ENTITY_OUTLINE;
	}

	/**
	 * The parameter value that leaves the scene visually unchanged ("neutral"). Used to fade
	 * persistent effects in and out: the animated value is blended towards this neutral value
	 * by the effect's current fade weight. Returns {@code Float.NaN} for parameters that must
	 * not be faded (e.g. positions and target colours).
	 *
	 * @param parameter the Config parameter name
	 */
	public float neutralValue(final String parameter) {
		return switch (this) {
			case CHROMATIC_ABERRATION -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
			case COLOR_GRADE -> switch (parameter) {
				case "saturation", "contrast", "brightness", "tint_r", "tint_g", "tint_b" -> 1.0F;
				default -> Float.NaN;
			};
			case DISTORTION -> "amount".equals(parameter) ? 0.0F : Float.NaN;
			case DENT -> "strength".equals(parameter) || "radius".equals(parameter) ? 0.0F : Float.NaN;
			case GRADIENT_MAP -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
			case POSTERIZE -> "strength".equals(parameter) ? 0.0F : Float.NaN;
		case BLUR -> "radius".equals(parameter) ? 0.0F : Float.NaN;
		case PIXELATE -> "cell_size".equals(parameter) ? 0.0005F : Float.NaN;
		case HUE_ISOLATION -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case VIGNETTE -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case SCREEN_FLASH -> "alpha".equals(parameter) ? 0.0F : Float.NaN;
		case MOTION_BLUR -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case BLOOM -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case FILM_GRAIN -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case SCANLINES -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case DEPTH_OF_FIELD -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case LETTERBOX -> "height".equals(parameter) ? 0.0F : Float.NaN;
		case INVERT -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case VORTEX -> "strength".equals(parameter) ? 0.0F : Float.NaN;
		case SPEED_LINES -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case FOV_MODIFIER -> "fov_delta".equals(parameter) ? 0.0F : Float.NaN;
		case SLICE_SHIFT -> "shift".equals(parameter) ? 0.0F : Float.NaN;
		case NOISE_WARP -> "amplitude".equals(parameter) ? 0.0F : Float.NaN;
		case SOLARIZE -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case DOUBLE_VISION -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case EYELIDS -> "openness".equals(parameter) ? 1.0F : Float.NaN;
		case IRIS_WIPE -> "radius".equals(parameter) ? 1.4F : Float.NaN;
		case DIGITAL_GLITCH -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case VHS -> "intensity".equals(parameter) ? 0.0F : Float.NaN;
		case SHOCKWAVE -> {
			if ("amplitude".equals(parameter)) {
				yield 0.0F;
			}
			if ("radius".equals(parameter)) {
				yield 1.5F;
			}
			yield Float.NaN;
		}
		default -> Float.NaN;
		};
	}

	/**
	 * Resolves an effect type from a raw datapack string. Accepts both the enum name
	 * ({@code "CHROMATIC_ABERRATION"}) and the shader name ({@code "chromatic_aberration"}).
	 *
	 * @param name raw type string
	 * @return the matching effect type, or {@code null} if unknown
	 */
	public static VFXEffectType fromString(final String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
		try {
			return valueOf(normalized);
		} catch (IllegalArgumentException ignored) {
			for (VFXEffectType type : values()) {
				if (type.name.equalsIgnoreCase(name.trim())) {
					return type;
				}
			}
			return null;
		}
	}
}

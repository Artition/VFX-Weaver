package dev.vfxweaver.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of {@link VFXDefinition}s. Contains the built-in effects (always available) and is
 * refreshed from datapack {@code data/<namespace>/vfx/<effect>.json} files on every (server)
 * data reload. On a dedicated server the raw datapack definitions are sent to connecting
 * clients via {@code VFXSyncPayload} (see {@link #applySynced(Map)}), so datapack effects work
 * on clients that have no datapack themselves; single player loads them directly.
 */
public class VFXDefinitionManager extends SimplePreparableReloadListener<Map<Identifier, String>> {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/vfx-defs");
	private static final FileToIdConverter FILE_CONVERTER = FileToIdConverter.json("vfx");

	private static final VFXDefinitionManager INSTANCE = new VFXDefinitionManager();

	private final Map<Identifier, VFXDefinition> builtIns = new LinkedHashMap<>();
	private volatile Map<Identifier, String> rawDefinitions = Map.of();
	private volatile Map<Identifier, VFXDefinition> definitions = new LinkedHashMap<>();
	/** Effect ids whose datapack JSON failed to parse, mapped to the error message. */
	private volatile Map<Identifier, String> parseErrors = Map.of();

	private VFXDefinitionManager() {
		registerBuiltIns();
		this.definitions.putAll(this.builtIns);
	}

	public static VFXDefinitionManager get() {
		return INSTANCE;
	}

	/**
	 * Returns the effect definition for the given id (built-in or datapack), or {@code null}.
	 */
	public VFXDefinition get(final Identifier id) {
		return this.definitions.get(id);
	}

	/**
	 * All currently known effect definitions.
	 */
	public Map<Identifier, VFXDefinition> getDefinitions() {
		return Map.copyOf(this.definitions);
	}

	public boolean contains(final Identifier id) {
		return this.definitions.containsKey(id);
	}

	/**
	 * Raw JSON source of the datapack-defined effects (used by the server to synchronize them
	 * to clients over {@code VFXSyncPayload}).
	 */
	public Map<Identifier, String> getRawDefinitions() {
		return this.rawDefinitions;
	}

	@Override
	protected Map<Identifier, String> prepare(final ResourceManager manager, final ProfilerFiller profiler) {
		Map<Identifier, String> loaded = new HashMap<>();
		for (Entry<Identifier, Resource> entry : FILE_CONVERTER.listMatchingResources(manager).entrySet()) {
			Identifier fileId = entry.getKey();
			Identifier effectId = FILE_CONVERTER.fileToId(fileId);
			try (Reader reader = entry.getValue().openAsReader()) {
				StringBuilder sb = new StringBuilder();
				char[] buf = new char[4096];
				int n;
				while ((n = reader.read(buf)) != -1) {
					sb.append(buf, 0, n);
				}
				loaded.put(effectId, sb.toString());
			} catch (IOException e) {
				LOGGER.error("Couldn't read VFX definition '{}' from '{}'", effectId, fileId, e);
			}
		}
		return loaded;
	}

	@Override
	protected void apply(final Map<Identifier, String> loaded, final ResourceManager manager, final ProfilerFiller profiler) {
		this.rawDefinitions = Map.copyOf(loaded);
		reload(loaded);
	}

	private void reload(final Map<Identifier, String> raw) {
		Map<Identifier, VFXDefinition> merged = new LinkedHashMap<>(this.builtIns);
		Map<Identifier, String> errors = new LinkedHashMap<>();
		int loadedCount = 0;
		for (Entry<Identifier, String> entry : raw.entrySet()) {
			try {
				JsonObject json = StrictJsonParser.parse(entry.getValue()).getAsJsonObject();
				merged.put(entry.getKey(), VFXDefinition.parse(entry.getKey(), json));
				loadedCount++;
			} catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
				// A single bad definition is logged and skipped so the rest keep loading.
				// The error is recorded so /vfx list can surface which file is broken.
				errors.put(entry.getKey(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
				LOGGER.error("Couldn't parse VFX definition '{}'", entry.getKey(), e);
			}
		}
		this.definitions = merged;
		this.parseErrors = Map.copyOf(errors);
		LOGGER.info("Loaded {} VFX effect definitions ({} from datapacks)", this.definitions.size(), loadedCount);
	}

	/**
	 * The datapack effect ids that failed to parse on the last reload, mapped to their error
	 * messages. Used by {@code /vfx list} to surface broken files.
	 */
	public Map<Identifier, String> getParseErrors() {
		return this.parseErrors;
	}

	/**
	 * Merges datapack definitions received from the server (over {@code VFXSyncPayload}) on top
	 * of the built-in effects. Called on the client when it has no datapack of its own (dedicated
	 * server); malformed entries from the server are logged and skipped.
	 */
	public void applySynced(final Map<Identifier, String> synced) {
		this.rawDefinitions = Map.copyOf(synced);
		reload(synced);
	}

	private void registerBuiltIns() {
		// Post-processing effects use their uniform block names as parameter names.
		// Animated parameters fade the effect back to neutral over the effect duration.
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "chromatic_aberration"),
			builtIn("vfxweaver", "chromatic_aberration", VFXEffectType.CHROMATIC_ABERRATION, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 0.8F, 0.0F), param("radius", 4.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "color_grade"),
			builtIn("vfxweaver", "color_grade", VFXEffectType.COLOR_GRADE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("saturation", 0.7F, 1.0F), param("contrast", 1.05F, 1.0F), param("brightness", 1.0F),
				param("tint_r", 1.0F), param("tint_g", 0.9F, 1.0F), param("tint_b", 1.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "distortion"),
			builtIn("vfxweaver", "distortion", VFXEffectType.DISTORTION, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("amount", 0.2F, 0.0F), param("radius", 0.8F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "dent"),
			builtIn("vfxweaver", "dent", VFXEffectType.DENT, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("strength", 0.6F, 0.0F), param("radius", 0.25F), param("center_x", 0.5F), param("center_y", 0.5F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "gradient_map"),
			builtIn("vfxweaver", "gradient_map", VFXEffectType.GRADIENT_MAP, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("from_r", 0.1F), param("from_g", 0.0F), param("from_b", 0.2F),
				param("to_r", 1.0F), param("to_g", 0.2F), param("to_b", 0.1F), param("intensity", 1.0F, 0.0F),
				param("mode", 0.0F), param("pos", 0.5F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "posterize"),
			builtIn("vfxweaver", "posterize", VFXEffectType.POSTERIZE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("strength", 0.25F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "blur"),
			builtIn("vfxweaver", "blur", VFXEffectType.BLUR, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("radius", 4.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "pixelate"),
			builtIn("vfxweaver", "pixelate", VFXEffectType.PIXELATE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("cell_size", 0.012F, 0.0005F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "hue_isolation"),
			builtIn("vfxweaver", "hue_isolation", VFXEffectType.HUE_ISOLATION, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("hue", 0.0F), param("tolerance", 0.2F), param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "vignette"),
			builtIn("vfxweaver", "vignette", VFXEffectType.VIGNETTE, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 0.7F, 0.0F), param("color_r", 0.0F), param("color_g", 0.0F), param("color_b", 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "screen_flash"),
			builtIn("vfxweaver", "screen_flash", VFXEffectType.SCREEN_FLASH, 20, EasingType.EASE_OUT_QUAD,
				param("alpha", 0.8F, 0.0F), param("color_r", 1.0F), param("color_g", 1.0F), param("color_b", 1.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "motion_blur"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "motion_blur"),
				VFXEffectType.MOTION_BLUR,
				40,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"intensity", VFXDefinition.ParamSpec.animated(0.35F, 0.0F),
					"yaw_delta", VFXDefinition.ParamSpec.bound(new BoundParam(BoundParam.Kind.CAMERA_YAW_DELTA, 0.0, 0.0, 0.0, 0.0F, 0.0F, 1.0F, false, 1.0F)),
					"pitch_delta", VFXDefinition.ParamSpec.bound(new BoundParam(BoundParam.Kind.CAMERA_PITCH_DELTA, 0.0, 0.0, 0.0, 0.0F, 0.0F, 1.0F, false, 1.0F))
				),
				false, false, 0, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "bloom"),
			builtIn("vfxweaver", "bloom", VFXEffectType.BLOOM, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 0.6F, 0.0F), param("threshold", 0.7F), param("radius", 3.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "film_grain"),
			builtIn("vfxweaver", "film_grain", VFXEffectType.FILM_GRAIN, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 0.08F, 0.0F), param("size", 2.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "scanlines"),
			builtIn("vfxweaver", "scanlines", VFXEffectType.SCANLINES, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 0.3F, 0.0F), param("line_count", 3.0F), param("speed", 0.5F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "depth_of_field"),
			builtIn("vfxweaver", "depth_of_field", VFXEffectType.DEPTH_OF_FIELD, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 0.5F, 0.0F), param("focus_center", 0.5F), param("focus_range", 0.15F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "letterbox"),
			builtIn("vfxweaver", "letterbox", VFXEffectType.LETTERBOX, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("height", 0.12F, 0.0F), param("color_r", 0.0F), param("color_g", 0.0F), param("color_b", 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "invert"),
			builtIn("vfxweaver", "invert", VFXEffectType.INVERT, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "vortex"),
			builtIn("vfxweaver", "vortex", VFXEffectType.VORTEX, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("strength", 2.5F, 0.0F), param("radius", 0.5F), param("center_x", 0.5F), param("center_y", 0.5F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "speed_lines"),
			builtIn("vfxweaver", "speed_lines", VFXEffectType.SPEED_LINES, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("center_x", 0.5F), param("center_y", 0.5F),
				param("count", 50.0F), param("length", 0.5F), param("length_rand", 0.7F), param("width", 0.5F), param("seed", 0.0F),
				param("color_r", 1.0F), param("color_g", 1.0F), param("color_b", 1.0F),
				param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "slice_shift"),
			builtIn("vfxweaver", "slice_shift", VFXEffectType.SLICE_SHIFT, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("angle", 0.0F), param("offset", 0.0F), param("shift", 0.05F, 0.0F), param("mirror", 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "noise_warp"),
			builtIn("vfxweaver", "noise_warp", VFXEffectType.NOISE_WARP, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("scale", 8.0F), param("amplitude", 0.03F, 0.0F), param("contrast", 2.0F),
				param("coherence", 1.0F), param("speed", 0.5F), param("drift_x", 0.0F), param("drift_y", 0.0F),
				param("seed", 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "solarize"),
			builtIn("vfxweaver", "solarize", VFXEffectType.SOLARIZE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("threshold", 0.5F), param("softness", 0.0F), param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "double_vision"),
			builtIn("vfxweaver", "double_vision", VFXEffectType.DOUBLE_VISION, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("offset", 0.04F), param("ghost_opacity", 0.5F), param("drift", 0.0F), param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "eyelids"),
			builtIn("vfxweaver", "eyelids", VFXEffectType.EYELIDS, 120, EasingType.EASE_IN_OUT_CUBIC,
				param("openness", 0.5F), param("softness", 0.15F), param("curve", 0.35F),
				param("red", 0.0F), param("green", 0.0F), param("blue", 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "iris_wipe"),
			builtIn("vfxweaver", "iris_wipe", VFXEffectType.IRIS_WIPE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("radius", 0.4F, 1.4F), param("softness", 0.05F), param("center_x", 0.5F), param("center_y", 0.5F),
				param("zoom", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "digital_glitch"),
			builtIn("vfxweaver", "digital_glitch", VFXEffectType.DIGITAL_GLITCH, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("block", 0.06F), param("displacement", 0.08F), param("rate", 6.0F), param("chroma", 0.5F),
				param("seed", 0.0F), param("chance", 0.4F), param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "vhs"),
			builtIn("vfxweaver", "vhs", VFXEffectType.VHS, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("tracking", 0.35F), param("band_height", 0.08F), param("band_speed", 0.15F),
				param("bleed", 0.02F), param("wobble", 0.004F), param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "shockwave"),
			builtIn("vfxweaver", "shockwave", VFXEffectType.SHOCKWAVE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("center_x", 0.5F), param("center_y", 0.5F), param("radius", 0.4F, 1.5F),
				param("width", 0.15F), param("amplitude", 0.12F, 0.0F), param("sharpness", 1.5F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "afterimage"),
			builtIn("vfxweaver", "afterimage", VFXEffectType.AFTERIMAGE, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("decay", 0.97F), param("blend", 0.05F), param("drift", 0.0F),
				param("desat", 0.35F), param("intensity", 1.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "stop_motion"),
			builtIn("vfxweaver", "stop_motion", VFXEffectType.STOP_MOTION, 60, EasingType.EASE_IN_OUT_CUBIC,
				param("fps", 12.0F, 0.0F))
		);
		// Camera shake parameters (the shake itself is already enveloped by the shake manager).
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "camera_shake"),
			builtIn("vfxweaver", "camera_shake", VFXEffectType.CAMERA_SHAKE, 40, EasingType.EASE_OUT_CUBIC,
				param("amplitude_x", 0.12F), param("amplitude_y", 0.12F), param("amplitude_z", 0.04F),
				param("yaw", 0.8F), param("pitch", 0.6F), param("roll", 0.4F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "fov_modifier"),
			builtIn("vfxweaver", "fov_modifier", VFXEffectType.FOV_MODIFIER, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("fov_delta", 10.0F, 0.0F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "camera_roll"),
			builtIn("vfxweaver", "camera_roll", VFXEffectType.CAMERA_ROLL, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("angle", 15.0F, 0.0F), param("wobble", 0.0F), param("wobble_speed", 0.2F))
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "hud_fade"),
			builtIn("vfxweaver", "hud_fade", VFXEffectType.HUD_FADE, 40, EasingType.EASE_IN_OUT_CUBIC,
				param("opacity", 0.0F, 1.0F), param("chat", 1.0F))
		);
		// World-space block highlighting.
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "block_tint"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "block_tint"),
				VFXEffectType.BLOCK_TINT,
				60,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"color_r", VFXDefinition.ParamSpec.constant(0.2F),
					"color_g", VFXDefinition.ParamSpec.constant(0.6F),
					"color_b", VFXDefinition.ParamSpec.constant(1.0F),
					"alpha", VFXDefinition.ParamSpec.constant(0.35F),
					"through_blocks", VFXDefinition.ParamSpec.constant(1.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "block_outline"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "block_outline"),
				VFXEffectType.BLOCK_OUTLINE,
				60,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"color_r", VFXDefinition.ParamSpec.constant(1.0F),
					"color_g", VFXDefinition.ParamSpec.constant(0.85F),
					"color_b", VFXDefinition.ParamSpec.constant(0.2F),
					"alpha", VFXDefinition.ParamSpec.constant(0.9F),
					"width", VFXDefinition.ParamSpec.constant(0.05F),
					"through_blocks", VFXDefinition.ParamSpec.constant(1.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "block_displace"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "block_displace"),
				VFXEffectType.BLOCK_DISPLACE,
				40,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"amplitude", VFXDefinition.ParamSpec.animated(0.15F, 0.0F),
					"scale", VFXDefinition.ParamSpec.constant(4.0F),
					"seed", VFXDefinition.ParamSpec.constant(0.0F),
					"alpha", VFXDefinition.ParamSpec.constant(1.0F),
					"through_blocks", VFXDefinition.ParamSpec.constant(0.0F),
					"color_r", VFXDefinition.ParamSpec.constant(1.0F),
					"color_g", VFXDefinition.ParamSpec.constant(1.0F),
					"color_b", VFXDefinition.ParamSpec.constant(1.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "light_beam"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "light_beam"),
				VFXEffectType.LIGHT_BEAM,
				60,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.ofEntries(
					Map.entry("red", VFXDefinition.ParamSpec.constant(1.0F)),
					Map.entry("green", VFXDefinition.ParamSpec.constant(0.95F)),
					Map.entry("blue", VFXDefinition.ParamSpec.constant(0.75F)),
					Map.entry("radius", VFXDefinition.ParamSpec.constant(1.5F)),
					Map.entry("height", VFXDefinition.ParamSpec.constant(48.0F)),
					Map.entry("softness", VFXDefinition.ParamSpec.constant(0.6F)),
					Map.entry("top_scale", VFXDefinition.ParamSpec.constant(1.0F)),
					Map.entry("top_fade", VFXDefinition.ParamSpec.constant(0.4F)),
					Map.entry("through_blocks", VFXDefinition.ParamSpec.constant(0.0F)),
					Map.entry("intensity", VFXDefinition.ParamSpec.animated(1.0F, 0.0F))
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "pulse_ring"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "pulse_ring"),
				VFXEffectType.PULSE_RING,
				60,
				EasingFunction.builtIn(EasingType.EASE_OUT_CUBIC),
				Map.of(
					"red", VFXDefinition.ParamSpec.constant(1.0F),
					"green", VFXDefinition.ParamSpec.constant(0.35F),
					"blue", VFXDefinition.ParamSpec.constant(0.1F),
					"radius", VFXDefinition.ParamSpec.animated(0.0F, 6.0F),
					"thickness", VFXDefinition.ParamSpec.constant(0.5F),
					"tilt", VFXDefinition.ParamSpec.constant(0.0F),
					"through_blocks", VFXDefinition.ParamSpec.constant(0.0F),
					"intensity", VFXDefinition.ParamSpec.animated(1.0F, 0.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "guide_line"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "guide_line"),
				VFXEffectType.GUIDE_LINE,
				60,
				EasingFunction.builtIn(EasingType.EASE_OUT_CUBIC),
				Map.of(
					"red", VFXDefinition.ParamSpec.constant(0.25F),
					"green", VFXDefinition.ParamSpec.constant(1.0F),
					"blue", VFXDefinition.ParamSpec.constant(0.45F),
					"width", VFXDefinition.ParamSpec.constant(0.15F),
					"dash_length", VFXDefinition.ParamSpec.constant(0.6F),
					"gap", VFXDefinition.ParamSpec.constant(0.6F),
					"speed", VFXDefinition.ParamSpec.constant(2.0F),
					"arc", VFXDefinition.ParamSpec.constant(1.5F),
					"through_blocks", VFXDefinition.ParamSpec.constant(0.0F),
					"intensity", VFXDefinition.ParamSpec.animated(1.0F, 0.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "entity_tint"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "entity_tint"),
				VFXEffectType.ENTITY_TINT,
				40,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"color_r", VFXDefinition.ParamSpec.constant(0.2F),
					"color_g", VFXDefinition.ParamSpec.constant(0.6F),
					"color_b", VFXDefinition.ParamSpec.constant(1.0F),
					"alpha", VFXDefinition.ParamSpec.constant(0.5F),
					"texture", VFXDefinition.ParamSpec.constant(1.0F),
					"through_blocks", VFXDefinition.ParamSpec.constant(1.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "entity_outline"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "entity_outline"),
				VFXEffectType.ENTITY_OUTLINE,
				40,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"color_r", VFXDefinition.ParamSpec.constant(1.0F),
					"color_g", VFXDefinition.ParamSpec.constant(0.85F),
					"color_b", VFXDefinition.ParamSpec.constant(0.2F),
					"alpha", VFXDefinition.ParamSpec.constant(1.0F),
					"width", VFXDefinition.ParamSpec.constant(0.05F),
					"through_blocks", VFXDefinition.ParamSpec.constant(0.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
		this.builtIns.put(
			Identifier.fromNamespaceAndPath("vfxweaver", "entity_displace"),
			VFXDefinition.create(
				Identifier.fromNamespaceAndPath("vfxweaver", "entity_displace"),
				VFXEffectType.ENTITY_DISPLACE,
				40,
				EasingFunction.builtIn(EasingType.EASE_IN_OUT_CUBIC),
				Map.of(
					"amplitude", VFXDefinition.ParamSpec.animated(0.1F, 0.0F),
					"scale", VFXDefinition.ParamSpec.constant(4.0F),
					"seed", VFXDefinition.ParamSpec.constant(0.0F),
					"alpha", VFXDefinition.ParamSpec.constant(1.0F),
					"through_blocks", VFXDefinition.ParamSpec.constant(0.0F),
					"color_r", VFXDefinition.ParamSpec.constant(1.0F),
					"color_g", VFXDefinition.ParamSpec.constant(1.0F),
					"color_b", VFXDefinition.ParamSpec.constant(1.0F)
				),
				false, false, 10, List.of(), List.of(), null
			)
		);
	}

	@SafeVarargs
	private static VFXDefinition builtIn(
		final String namespace,
		final String path,
		final VFXEffectType type,
		final int duration,
		final EasingType easing,
		final Entry<String, VFXDefinition.ParamSpec>... params
	) {
		Map<String, VFXDefinition.ParamSpec> map = new LinkedHashMap<>();
		for (Entry<String, VFXDefinition.ParamSpec> param : params) {
			map.put(param.getKey(), param.getValue());
		}
		return VFXDefinition.create(Identifier.fromNamespaceAndPath(namespace, path), type, duration, EasingFunction.builtIn(easing), map);
	}

	private static Entry<String, VFXDefinition.ParamSpec> param(final String name, final float value) {
		return Map.entry(name, VFXDefinition.ParamSpec.constant(value));
	}

	private static Entry<String, VFXDefinition.ParamSpec> param(final String name, final float start, final float end) {
		return Map.entry(name, VFXDefinition.ParamSpec.animated(start, end));
	}

}

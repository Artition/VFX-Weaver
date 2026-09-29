package dev.vfxweaver.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.vfxweaver.platform.VFXPlatform;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-game settings holder for the post chain: chain resolution, fusion and the shifted-read
 * remap. A singleton with no client and no loader import; the config file lives at
 * {@code <config>/vfxweaver.json} ({@link VFXPlatform#configDir()} is the loader's config directory,
 * the game directory's {@code config} folder on both loaders).
 *
 * <p>Each value is read through a getter that applies the precedence <b>JVM system property over
 * file over default</b>: {@code -Dvfxweaver.chainres} ({@code "1.0"} or {@code "0.5"}),
 * {@code -Dvfxweaver.fusion} ({@code "false"} disables) and {@code -Dvfxweaver.fusion.remap}
 * ({@code "false"} disables). A malformed or absent value falls through to the next source, with a
 * warn-once for a malformed one, so a typo cannot silently change or disable a path. The file
 * defaults - {@code 0.5F}, {@code true} and {@code true} - are the shipped behaviour; the remap
 * default was flipped to on after the owner's in-game A/B (a frozen frame with {@code color_grade}
 * followed by {@code distortion}, off versus on) found no visible difference.
 *
 * <p>The file is read once at client init ({@link #load()}) and written by every setter.
 */
public final class VFXSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/settings");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final VFXSettings INSTANCE = new VFXSettings();

	/** The default chain resolution: half resolution on. */
	private static final float DEFAULT_CHAIN_RESOLUTION = 0.5F;
	/** The default fusion switch: on. */
	private static final boolean DEFAULT_FUSION = true;
	/**
	 * The default remap switch: on. The owner's in-game A/B (a frozen frame with {@code color_grade}
	 * followed by {@code distortion}, remap off versus on) found no visible difference, so the
	 * filter-exact gate is passed; {@code -Dvfxweaver.fusion.remap=false} or the settings screen turns
	 * it off.
	 */
	private static final boolean DEFAULT_REMAP = true;

	private float chainResolution = DEFAULT_CHAIN_RESOLUTION;
	private boolean fusion = DEFAULT_FUSION;
	private boolean remap = DEFAULT_REMAP;

	private VFXSettings() {
	}

	/** @return the settings singleton */
	public static VFXSettings get() {
		return INSTANCE;
	}

	/**
	 * Reads {@code vfxweaver.json}, leaving each field at its default when the file, a field or a
	 * value is missing or malformed.
	 */
	public void load() {
		final Path path = path();
		if (!Files.isRegularFile(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			final JsonObject json = GSON.fromJson(reader, JsonObject.class);
			if (json == null) {
				return;
			}
			readChainResolution(json, path);
			readSwitch(json, "fusion", path);
			readSwitch(json, "remap", path);
		} catch (Exception e) {
			VFXLog.warnOnce(LOGGER, "settings:load", "Could not read {}; using the defaults: {}", path, e.toString());
		}
	}

	private void readChainResolution(final JsonObject json, final Path path) {
		if (!json.has("chainResolution")) {
			return;
		}
		try {
			final float value = json.get("chainResolution").getAsFloat();
			if (value == 1.0F || value == 0.5F) {
				this.chainResolution = value;
			} else {
				VFXLog.warnOnce(LOGGER, "settings:chainres:file", "Unknown chainResolution {} in {}; using the default 0.5", value, path);
			}
		} catch (RuntimeException e) {
			VFXLog.warnOnce(LOGGER, "settings:chainres:file", "Malformed chainResolution in {}; using the default 0.5", path);
		}
	}

	/**
	 * Reads one boolean switch from the file. A field that is present but not a JSON boolean is
	 * warned about once and skipped, so the default stays.
	 */
	private void readSwitch(final JsonObject json, final String key, final Path path) {
		if (!json.has(key)) {
			return;
		}
		if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isBoolean()) {
			final boolean value = json.get(key).getAsBoolean();
			if ("fusion".equals(key)) {
				this.fusion = value;
			} else {
				this.remap = value;
			}
		} else {
			VFXLog.warnOnce(LOGGER, "settings:switch:file", "Malformed {} in {}; using the default", key, path);
		}
	}

	/**
	 * @return the chain resolution: {@code 1.0F} (full) or {@code 0.5F} (half). The system property
	 *         {@code -Dvfxweaver.chainres} wins over the file, which wins over the default
	 */
	public float chainResolution() {
		final String raw = System.getProperty("vfxweaver.chainres");
		if (raw == null) {
			return this.chainResolution;
		}
		if ("1.0".equals(raw)) {
			return 1.0F;
		}
		if ("0.5".equals(raw)) {
			return 0.5F;
		}
		VFXLog.warnOnce(LOGGER, "settings:chainres:prop", "Unknown -Dvfxweaver.chainres value '{}'; only 1.0 and 0.5 are accepted, using the file/default value", raw);
		return this.chainResolution;
	}

	/**
	 * @return whether post-chain fusion is on ({@code -Dvfxweaver.fusion=false} disables it)
	 */
	public boolean fusion() {
		return parseSwitch("vfxweaver.fusion", this.fusion);
	}

	/**
	 * @return whether the {@code UV_REMAP} fusion is on (default on; {@code -Dvfxweaver.fusion.remap=false}
	 *         disables it, as does the settings screen)
	 */
	public boolean remap() {
		return parseSwitch("vfxweaver.fusion.remap", this.remap);
	}

	private boolean parseSwitch(final String property, final boolean fallback) {
		final String raw = System.getProperty(property);
		if (raw == null) {
			return fallback;
		}
		if ("true".equalsIgnoreCase(raw)) {
			return true;
		}
		if ("false".equalsIgnoreCase(raw)) {
			return false;
		}
		VFXLog.warnOnce(LOGGER, "settings:switch:prop:" + property, "Unknown -D{} value '{}'; only true and false are accepted, using the file/default value", property, raw);
		return fallback;
	}

	/**
	 * Sets the chain resolution and persists; any value other than exactly {@code 1.0F} is stored as
	 * the {@code 0.5F} default.
	 *
	 * @param value the new chain resolution
	 */
	public void setChainResolution(final float value) {
		this.chainResolution = value == 1.0F ? 1.0F : DEFAULT_CHAIN_RESOLUTION;
		save();
	}

	/**
	 * Sets the fusion switch and persists.
	 *
	 * @param value the new value
	 */
	public void setFusion(final boolean value) {
		this.fusion = value;
		save();
	}

	/**
	 * Sets the remap switch and persists.
	 *
	 * @param value the new value
	 */
	public void setRemap(final boolean value) {
		this.remap = value;
		save();
	}

	/** Writes the three file-backed values to {@code vfxweaver.json}. */
	public void save() {
		final Path path = path();
		try {
			Files.createDirectories(path.getParent());
			final JsonObject json = new JsonObject();
			json.addProperty("chainResolution", this.chainResolution);
			json.addProperty("fusion", this.fusion);
			json.addProperty("remap", this.remap);
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
			}
		} catch (Exception e) {
			VFXLog.warnOnce(LOGGER, "settings:save", "Could not write {}: {}", path, e.toString());
		}
	}

	private static Path path() {
		return VFXPlatform.configDir().resolve("vfxweaver.json");
	}
}

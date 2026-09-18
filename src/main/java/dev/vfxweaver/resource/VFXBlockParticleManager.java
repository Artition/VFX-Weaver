package dev.vfxweaver.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of block-particle presets ({@link VFXBlockParticleSpec}), refreshed from datapack
 * {@code data/<namespace>/vfx_particles/<name>.json} files on every (server) data reload.
 *
 * <p>Like {@link VFXDefinitionManager} it keeps two layers: the datapack set (replaced by every
 * reload) and a code-registered local set written through {@code VFXAPI.registerBlockParticle}.
 * The local layer survives a reload and the datapack layer wins for the same id. Unlike effect
 * definitions, presets are never synchronized to other players: a {@code particles} effect that
 * names a preset resolves it on the client that has it.</p>
 *
 * <p>Both layers are bounded by {@link #MAX_SPECS} and each file is parsed individually, so one
 * broken JSON is reported (for {@code /vfx validate}) without taking down the rest.</p>
 */
public class VFXBlockParticleManager extends SimplePreparableReloadListener<Map<Identifier, String>>
		//? if <26.1 && fabric
		/*implements net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener*/
		{
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/block-particles");
	private static final FileToIdConverter FILE_CONVERTER = FileToIdConverter.json("vfx_particles");
	private static final int MAX_SPECS = 256;

	private static final VFXBlockParticleManager INSTANCE = new VFXBlockParticleManager();

	private volatile Map<Identifier, VFXBlockParticleSpec> specs = Map.of();
	/** Preset ids whose datapack JSON failed to parse, mapped to the error message. */
	private volatile Map<Identifier, String> parseErrors = Map.of();

	/** Code-registered presets (the local layer): never replaced by a reload, datapack wins per id. */
	private final Map<Identifier, VFXBlockParticleSpec> localSpecs = new ConcurrentHashMap<>();
	private final Map<Identifier, String> localErrors = new ConcurrentHashMap<>();

	private VFXBlockParticleManager() {
	}

	//? if <26.1 && fabric {
	/*@Override
	public Identifier getFabricId() {
		return Identifier.fromNamespaceAndPath("vfxweaver", "vfx_particles");
	}
	*///?}

	public static VFXBlockParticleManager get() {
		return INSTANCE;
	}

	/**
	 * Returns the block-particle preset for the given id (datapack or code-registered), or
	 * {@code null}. The datapack layer wins over a local registration for the same id.
	 */
	public VFXBlockParticleSpec get(final Identifier id) {
		VFXBlockParticleSpec loaded = this.specs.get(id);
		return loaded != null ? loaded : this.localSpecs.get(id);
	}

	/**
	 * All currently known presets: the datapack set plus the code-registered ones (the datapack
	 * entry wins on a collision).
	 */
	public Map<Identifier, VFXBlockParticleSpec> getSpecs() {
		Map<Identifier, VFXBlockParticleSpec> merged = new LinkedHashMap<>(this.localSpecs);
		merged.putAll(this.specs);
		return Map.copyOf(merged);
	}

	/**
	 * Every preset id that failed to parse (datapack or code-registered), mapped to its error
	 * message; for {@code /vfx validate}.
	 */
	public Map<Identifier, String> getParseErrors() {
		if (this.localErrors.isEmpty()) {
			return this.parseErrors;
		}
		Map<Identifier, String> merged = new LinkedHashMap<>(this.parseErrors);
		merged.putAll(this.localErrors);
		return Map.copyOf(merged);
	}

	@Override
	protected Map<Identifier, String> prepare(final ResourceManager manager, final ProfilerFiller profiler) {
		Map<Identifier, String> loaded = new HashMap<>();
		for (Entry<Identifier, Resource> entry : FILE_CONVERTER.listMatchingResources(manager).entrySet()) {
			Identifier fileId = entry.getKey();
			Identifier specId = FILE_CONVERTER.fileToId(fileId);
			try (Reader reader = entry.getValue().openAsReader()) {
				StringBuilder sb = new StringBuilder();
				char[] buf = new char[4096];
				int n;
				while ((n = reader.read(buf)) != -1) {
					sb.append(buf, 0, n);
				}
				loaded.put(specId, sb.toString());
			} catch (IOException e) {
				LOGGER.error("Couldn't read block-particle preset '{}' from '{}'", specId, fileId, e);
			}
		}
		return loaded;
	}

	@Override
	protected void apply(final Map<Identifier, String> loaded, final ResourceManager manager, final ProfilerFiller profiler) {
		apply(loaded);
	}

	/**
	 * Replaces the datapack layer with the given raw JSON and re-parses it. The code-registered
	 * local layer is untouched. Called by the reload listener.
	 *
	 * @param rawJsons preset id to JSON source
	 */
	public void apply(final Map<Identifier, String> rawJsons) {
		Map<Identifier, VFXBlockParticleSpec> parsed = new LinkedHashMap<>();
		Map<Identifier, String> errors = new LinkedHashMap<>();
		for (Entry<Identifier, String> entry : rawJsons.entrySet()) {
			if (parsed.size() >= MAX_SPECS) {
				LOGGER.warn("Datapack block-particle preset limit ({}) reached; '{}' and later files are ignored", MAX_SPECS, entry.getKey());
				break;
			}
			try {
				parsed.put(entry.getKey(), parseSpec(entry.getValue()));
			} catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
				errors.put(entry.getKey(), errorMessage(e));
				LOGGER.error("Couldn't parse block-particle preset '{}'", entry.getKey(), e);
			}
		}
		this.specs = Map.copyOf(parsed);
		this.parseErrors = Map.copyOf(errors);
		LOGGER.info("Loaded {} block-particle presets", this.specs.size());
	}

	/**
	 * Registers a preset supplied in code (the local layer). The layer is bounded by
	 * {@link #MAX_SPECS} and is never touched by a datapack reload.
	 *
	 * @param id   the preset id
	 * @param spec the preset
	 * @return {@code false} when the layer is full and the registration was dropped
	 */
	public boolean registerLocal(final Identifier id, final VFXBlockParticleSpec spec) {
		if (!this.localSpecs.containsKey(id) && this.localSpecs.size() >= MAX_SPECS) {
			LOGGER.warn("Local block-particle preset limit ({}) reached; '{}' is ignored", MAX_SPECS, id);
			return false;
		}
		this.localSpecs.put(id, spec);
		this.localErrors.remove(id);
		return true;
	}

	/**
	 * Removes a preset registered through {@link #registerLocal(Identifier, VFXBlockParticleSpec)}.
	 * Datapack presets are not affected.
	 *
	 * @param id the preset id
	 * @return {@code true} when a local preset with that id existed
	 */
	public boolean unregisterLocal(final Identifier id) {
		this.localErrors.remove(id);
		return this.localSpecs.remove(id) != null;
	}

	/**
	 * Parses one datapack preset; throws on a missing/ambiguous model, an unknown block state or
	 * item, or a bad value. Exactly one of {@code block} / {@code item} must be present.
	 */
	private static VFXBlockParticleSpec parseSpec(final String json) {
		JsonObject object = StrictJsonParser.parse(json).getAsJsonObject();
		boolean hasBlock = object.has("block") && !object.get("block").isJsonNull();
		boolean hasItem = object.has("item") && !object.get("item").isJsonNull();
		if (hasBlock == hasItem) {
			throw new IllegalArgumentException("Exactly one of 'block' or 'item' is required");
		}
		VFXBlockParticleSpec.Builder builder;
		if (hasBlock) {
			String blockId = GsonHelper.getAsString(object, "block");
			BlockState block = VFXBlockParticleSpec.parseBlockState(blockId);
			if (block == null) {
				throw new IllegalArgumentException("Unknown block state '" + blockId + "'");
			}
			builder = VFXBlockParticleSpec.builder(block);
		} else {
			String itemId = GsonHelper.getAsString(object, "item");
			ItemStack item = VFXBlockParticleSpec.parseItem(itemId);
			if (item == null) {
				throw new IllegalArgumentException("Unknown item '" + itemId + "'");
			}
			builder = VFXBlockParticleSpec.builder(item);
		}
		builder = builder
			.brightness(parseBrightness(object.get("brightness")))
			.gravity(optFloat(object, "gravity", 1.0F))
			.friction(optFloat(object, "friction", 0.94F))
			.collide(optFloat(object, "collide", 1.0F))
			.bounce(optFloat(object, "bounce", 0.0F))
			.size(optFloat(object, "size", 0.25F))
			.life(optInt(object, "life", 60))
			.spin(optFloat(object, "spin", 0.0F))
			.spinMode(parseSpinMode(object.get("spin_mode")))
			.spinAxis(parseSpinAxis(object.get("spin_axis")))
			.spinRandom(clamp01(optFloat(object, "spin_random", 1.0F)))
			.spinFriction(clamp01(optFloat(object, "spin_friction", 1.0F)))
			.spinRoll(clamp01(optFloat(object, "spin_roll", 0.5F)));
		return builder.build();
	}

	/**
	 * Parses {@code spin_mode}: {@code tumble} (default), {@code yaw} or {@code none}, case-insensitive.
	 * An unknown value is a per-file parse error.
	 */
	private static VFXBlockParticleSpec.SpinMode parseSpinMode(final @Nullable JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return VFXBlockParticleSpec.SpinMode.TUMBLE;
		}
		final VFXBlockParticleSpec.SpinMode mode = VFXBlockParticleSpec.SpinMode.byName(element.getAsString());
		if (mode == null) {
			throw new IllegalArgumentException("'spin_mode' must be tumble, yaw or none: " + element);
		}
		return mode;
	}

	/**
	 * Parses {@code spin_axis}: {@code random} (default), {@code x}, {@code y}, {@code z} or a
	 * {@code [x, y, z]} array. Returns {@code null} for a random axis; an unknown name or a
	 * zero-length vector is a per-file parse error.
	 */
	private static @Nullable Vector3f parseSpinAxis(final @Nullable JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonArray()) {
			final JsonArray array = element.getAsJsonArray();
			if (array.size() != 3) {
				throw new IllegalArgumentException("'spin_axis' must be random, x, y, z or an array of [x, y, z]");
			}
			final Vector3f axis = new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
			if (axis.lengthSquared() < 1.0e-8F) {
				throw new IllegalArgumentException("'spin_axis' vector must be non-zero");
			}
			return axis.normalize();
		}
		switch (element.getAsString().trim().toLowerCase(Locale.ROOT)) {
			case "random":
				return null;
			case "x":
				return new Vector3f(1.0F, 0.0F, 0.0F);
			case "y":
				return new Vector3f(0.0F, 1.0F, 0.0F);
			case "z":
				return new Vector3f(0.0F, 0.0F, 1.0F);
			default:
				throw new IllegalArgumentException("'spin_axis' must be random, x, y, z or an array of [x, y, z]: " + element);
		}
	}

	private static float clamp01(final float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	/**
	 * Parses the {@code brightness} field: absent/{@code -1} = world light, an integer =
	 * {@code pack(level, level)}, or a two-element {@code [blockLight, skyLight]} array.
	 */
	private static int parseBrightness(final JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return VFXBlockParticleSpec.NO_BRIGHTNESS;
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			if (array.size() != 2) {
				throw new IllegalArgumentException("'brightness' must be -1, an integer or [blockLight, skyLight]");
			}
			return VFXBlockParticleSpec.packBrightness(array.get(0).getAsInt(), array.get(1).getAsInt());
		}
		int level = element.getAsInt();
		return level < 0 ? VFXBlockParticleSpec.NO_BRIGHTNESS : VFXBlockParticleSpec.packBrightness(level, level);
	}

	private static float optFloat(final JsonObject object, final String key, final float fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsFloat() : fallback;
	}

	private static int optInt(final JsonObject object, final String key, final int fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
	}

	private static String errorMessage(final Throwable e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}
}

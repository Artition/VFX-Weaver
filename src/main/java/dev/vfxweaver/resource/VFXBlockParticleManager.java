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
import net.minecraft.world.level.block.state.BlockState;
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

	/** Parses one datapack preset; throws on a missing block, an unknown block state or a bad value. */
	private static VFXBlockParticleSpec parseSpec(final String json) {
		JsonObject object = StrictJsonParser.parse(json).getAsJsonObject();
		String blockId = GsonHelper.getAsString(object, "block");
		BlockState block = VFXBlockParticleSpec.parseBlockState(blockId);
		if (block == null) {
			throw new IllegalArgumentException("Unknown block state '" + blockId + "'");
		}
		VFXBlockParticleSpec.Builder builder = VFXBlockParticleSpec.builder(block)
			.brightness(parseBrightness(object.get("brightness")))
			.gravity(optFloat(object, "gravity", 1.0F))
			.friction(optFloat(object, "friction", 0.94F))
			.collide(optFloat(object, "collide", 1.0F))
			.bounce(optFloat(object, "bounce", 0.0F))
			.size(optFloat(object, "size", 0.25F))
			.life(optInt(object, "life", 60))
			.spin(optFloat(object, "spin", 0.0F));
		return builder.build();
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

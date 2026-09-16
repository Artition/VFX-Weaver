package dev.vfxweaver.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.vfxweaver.effect.VFXDefinition;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Registry of {@link VFXDefinition}s, refreshed from datapack
 * {@code data/<namespace>/vfx/<effect>.json} files on every (server) data reload. The built-in
 * effects ship as JSON resources in the mod jar under the same path, so they load like any
 * datapack file. On a dedicated server the raw datapack definitions are sent to connecting
 * clients via {@code VFXSyncPayload} (see {@link #applySynced(Map)}), so datapack effects work
 * on clients that have no datapack themselves; single player loads them directly.
 *
 * <p>Definitions registered in code ({@link #registerLocal(Map)}) live in a separate local layer
 * that a datapack reload or a server sync never replaces, which is how a client-only mod can own
 * effect ids while playing on a server. The datapack/server layer wins for the same id.</p>
 */
public class VFXDefinitionManager extends SimplePreparableReloadListener<Map<Identifier, String>>
		//? if <26.1
		/*implements net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener*/
		{
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/vfx-defs");
	private static final FileToIdConverter FILE_CONVERTER = FileToIdConverter.json("vfx");

	private static final VFXDefinitionManager INSTANCE = new VFXDefinitionManager();

	private volatile Map<Identifier, String> rawDefinitions = Map.of();
	private volatile Map<Identifier, VFXDefinition> definitions = new LinkedHashMap<>();
	/** Effect ids whose datapack JSON failed to parse, mapped to the error message. */
	private volatile Map<Identifier, String> parseErrors = Map.of();

	/**
	 * Code-registered definitions (the "local" layer): written through
	 * {@code VFXAPI.registerDefinitions}, typically by a client-only mod. It is deliberately
	 * separate from {@link #definitions} so a datapack reload ({@link #apply}) or a server sync
	 * ({@link #applySynced}) cannot delete it. The datapack/server layer wins for the same id.
	 */
	private static final int MAX_LOCAL_DEFINITIONS = 256;
	private final Map<Identifier, String> localRaw = new ConcurrentHashMap<>();
	private final Map<Identifier, VFXDefinition> localDefinitions = new ConcurrentHashMap<>();
	private final Map<Identifier, String> localErrors = new ConcurrentHashMap<>();

	private VFXDefinitionManager() {
	}

	//? if <26.1 {
	/*@Override
	public Identifier getFabricId() {
		return Identifier.fromNamespaceAndPath("vfxweaver", "vfx_definitions");
	}
	*///?}

	public static VFXDefinitionManager get() {
		return INSTANCE;
	}

	/**
	 * Returns the effect definition for the given id (built-in, datapack, server-synced or
	 * code-registered), or {@code null}. The datapack/server layer wins over a local registration
	 * for the same id.
	 */
	public VFXDefinition get(final Identifier id) {
		VFXDefinition loaded = this.definitions.get(id);
		return loaded != null ? loaded : this.localDefinitions.get(id);
	}

	/**
	 * All currently known effect definitions: the datapack/server set plus the code-registered
	 * ones (the datapack/server entry wins on a collision).
	 */
	public Map<Identifier, VFXDefinition> getDefinitions() {
		Map<Identifier, VFXDefinition> merged = new LinkedHashMap<>(this.localDefinitions);
		merged.putAll(this.definitions);
		return Map.copyOf(merged);
	}

	public boolean contains(final Identifier id) {
		return this.definitions.containsKey(id) || this.localDefinitions.containsKey(id);
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
		Map<Identifier, VFXDefinition> merged = new LinkedHashMap<>();
		Map<Identifier, String> errors = new LinkedHashMap<>();
		int loadedCount = 0;
		for (Entry<Identifier, String> entry : raw.entrySet()) {
			try {
				merged.put(entry.getKey(), parseDefinition(entry.getKey(), entry.getValue()));
				loadedCount++;
			} catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
				// A single bad definition is logged and skipped so the rest keep loading.
				// The error is recorded so /vfx list can surface which file is broken.
				errors.put(entry.getKey(), errorMessage(e));
				LOGGER.error("Couldn't parse VFX definition '{}'", entry.getKey(), e);
			}
		}
		this.definitions = merged;
		this.parseErrors = Map.copyOf(errors);
		LOGGER.info("Loaded {} VFX effect definitions ({} from datapacks)", this.definitions.size(), loadedCount);
	}

	/**
	 * Registers definitions supplied in code (the local layer). Every entry is parsed with exactly
	 * the datapack validation; a bad entry is logged, recorded for {@code /vfx validate} and
	 * skipped so the others still register. The layer is bounded by {@link #MAX_LOCAL_DEFINITIONS}
	 * and is never touched by a datapack reload or a server sync.
	 *
	 * @param rawJson effect id to definition JSON
	 * @return the ids that were rejected (empty when everything registered)
	 */
	public Set<Identifier> registerLocal(final Map<Identifier, String> rawJson) {
		Set<Identifier> failed = new LinkedHashSet<>();
		for (Entry<Identifier, String> entry : rawJson.entrySet()) {
			Identifier id = entry.getKey();
			if (!this.localDefinitions.containsKey(id) && this.localDefinitions.size() >= MAX_LOCAL_DEFINITIONS) {
				LOGGER.warn("Local VFX definition limit ({}) reached; '{}' is ignored", MAX_LOCAL_DEFINITIONS, id);
				failed.add(id);
				continue;
			}
			try {
				this.localDefinitions.put(id, parseDefinition(id, entry.getValue()));
				this.localErrors.remove(id);
			} catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
				this.localErrors.put(id, errorMessage(e));
				failed.add(id);
				LOGGER.error("Couldn't parse local VFX definition '{}'", id, e);
			}
		}
		return Set.copyOf(failed);
	}

	/**
	 * Removes a definition registered through {@link #registerLocal(Map)}. Datapack, built-in and
	 * server-synced definitions are not affected.
	 *
	 * @return {@code true} when a local definition with that id existed
	 */
	public boolean unregisterLocal(final Identifier id) {
		this.localErrors.remove(id);
		return this.localDefinitions.remove(id) != null;
	}

	/** Parses one definition with the datapack validation rules; throws on a bad entry. */
	private static VFXDefinition parseDefinition(final Identifier id, final String json) {
		JsonObject object = StrictJsonParser.parse(json).getAsJsonObject();
		return VFXDefinition.parse(id, object);
	}

	private static String errorMessage(final Throwable e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}

	/**
	 * Every effect id that failed to parse (datapack, server-synced or code-registered), mapped to
	 * its error message. Used by {@code /vfx list} and {@code /vfx validate}.
	 */
	public Map<Identifier, String> getParseErrors() {
		if (this.localErrors.isEmpty()) {
			return this.parseErrors;
		}
		Map<Identifier, String> merged = new LinkedHashMap<>(this.parseErrors);
		merged.putAll(this.localErrors);
		return Map.copyOf(merged);
	}

	/**
	 * Replaces the locally loaded definitions with the ones received from the server (over
	 * {@code VFXSyncPayload}); the synced set includes the built-in effects, since the server
	 * loads them from its own mod jar. Called on the client when it has no datapack of its own (dedicated
	 * server); malformed entries from the server are logged and skipped.
	 */
	public void applySynced(final Map<Identifier, String> synced) {
		this.rawDefinitions = Map.copyOf(synced);
		reload(synced);
	}

}

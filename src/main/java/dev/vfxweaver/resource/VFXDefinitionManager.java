package dev.vfxweaver.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.vfxweaver.effect.VFXDefinition;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Registry of {@link VFXDefinition}s, refreshed from datapack
 * {@code data/<namespace>/vfx/<effect>.json} files on every (server) data reload. The built-in
 * effects ship as JSON resources in the mod jar under the same path, so they load like any
 * datapack file. On a dedicated server the raw datapack definitions are sent to connecting
 * clients via {@code VFXSyncPayload} (see {@link #applySynced(Map)}), so datapack effects work
 * on clients that have no datapack themselves; single player loads them directly.
 */
public class VFXDefinitionManager extends SimplePreparableReloadListener<Map<Identifier, String>> {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/vfx-defs");
	private static final FileToIdConverter FILE_CONVERTER = FileToIdConverter.json("vfx");

	private static final VFXDefinitionManager INSTANCE = new VFXDefinitionManager();

	private volatile Map<Identifier, String> rawDefinitions = Map.of();
	private volatile Map<Identifier, VFXDefinition> definitions = new LinkedHashMap<>();
	/** Effect ids whose datapack JSON failed to parse, mapped to the error message. */
	private volatile Map<Identifier, String> parseErrors = Map.of();

	private VFXDefinitionManager() {
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
		Map<Identifier, VFXDefinition> merged = new LinkedHashMap<>();
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

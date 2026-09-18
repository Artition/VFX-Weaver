package dev.vfxweaver.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
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
 * Registry of custom easing curves from datapack {@code data/<namespace>/vfx_curves/<name>.json}
 * files, refreshed on every (server) data reload together with {@link VFXDefinitionManager}.
 * On a dedicated server the raw curves are sent to connecting clients via {@code VFXSyncPayload}
 * (see {@link #applySynced(Map)}). One malformed curve file is logged and skipped, the rest keep loading.
 */
public class VFXCurveManager extends SimplePreparableReloadListener<Map<Identifier, String>>
		//? if <26.1 && fabric
		/*implements net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener*/
		{
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/vfx-curves");
	private static final FileToIdConverter FILE_CONVERTER = FileToIdConverter.json("vfx_curves");

	private static final VFXCurveManager INSTANCE = new VFXCurveManager();

	private volatile Map<Identifier, String> rawCurves = Map.of();
	private volatile Map<Identifier, VFXCurve> curves = Map.of();

	private VFXCurveManager() {
	}

	//? if <26.1 && fabric {
	/*@Override
	public Identifier getFabricId() {
		return Identifier.fromNamespaceAndPath("vfxweaver", "vfx_curves");
	}
	*///?}

	public static VFXCurveManager get() {
		return INSTANCE;
	}

	/**
	 * Returns the curve with the given id, or {@code null}. A bare name (no namespace) is looked
	 * up in the {@code minecraft} namespace, matching how plain effect names resolve.
	 */
	public VFXCurve get(final String name) {
		return this.curves.get(Identifier.parse(name));
	}

	public Map<Identifier, VFXCurve> getCurves() {
		return Map.copyOf(this.curves);
	}

	/**
	 * Raw JSON source of the datapack-defined curves (used by the server to synchronize them
	 * to clients over {@code VFXSyncPayload}).
	 */
	public Map<Identifier, String> getRawCurves() {
		return this.rawCurves;
	}

	@Override
	protected Map<Identifier, String> prepare(final ResourceManager manager, final ProfilerFiller profiler) {
		Map<Identifier, String> loaded = new HashMap<>();
		for (Entry<Identifier, Resource> entry : FILE_CONVERTER.listMatchingResources(manager).entrySet()) {
			Identifier fileId = entry.getKey();
			Identifier curveId = FILE_CONVERTER.fileToId(fileId);
			try (Reader reader = entry.getValue().openAsReader()) {
				StringBuilder sb = new StringBuilder();
				char[] buf = new char[4096];
				int n;
				while ((n = reader.read(buf)) != -1) {
					sb.append(buf, 0, n);
				}
				loaded.put(curveId, sb.toString());
			} catch (IOException e) {
				LOGGER.error("Couldn't read VFX curve '{}' from '{}'", curveId, fileId, e);
			}
		}
		return loaded;
	}

	@Override
	protected void apply(final Map<Identifier, String> loaded, final ResourceManager manager, final ProfilerFiller profiler) {
		this.rawCurves = Map.copyOf(loaded);
		this.curves = reload(loaded);
		LOGGER.info("Loaded {} VFX curves from datapacks", loaded.size());
	}

	private Map<Identifier, VFXCurve> reload(final Map<Identifier, String> raw) {
		Map<Identifier, VFXCurve> result = new HashMap<>();
		for (Entry<Identifier, String> entry : raw.entrySet()) {
			try {
				JsonObject json = StrictJsonParser.parse(entry.getValue()).getAsJsonObject();
				if (json.has("cubicBezier")) {
					JsonArray bezier = GsonHelper.getAsJsonArray(json, "cubicBezier");
					if (bezier.size() != 4) {
						throw new IllegalArgumentException("'cubicBezier' must be [x1,y1,x2,y2]");
					}
					result.put(entry.getKey(), new VFXCurve(entry.getKey(), EasingFunction.cubicBezier(entry.getKey().toString(),
							bezier.get(0).getAsFloat(), bezier.get(1).getAsFloat(), bezier.get(2).getAsFloat(), bezier.get(3).getAsFloat())));
					continue;
				}
				float[] ts = new float[0];
				float[] vs = new float[0];
				if (json.has("points")) {
					JsonArray points = GsonHelper.getAsJsonArray(json, "points");
					ts = new float[points.size()];
					vs = new float[points.size()];
					for (int i = 0; i < points.size(); i++) {
						JsonArray point = GsonHelper.convertToJsonArray(points.get(i), "point");
						if (point.size() != 2) {
							throw new IllegalArgumentException("Curve point must be [t, v]: " + point);
						}
						ts[i] = point.get(0).getAsFloat();
						vs[i] = point.get(1).getAsFloat();
						if (i > 0 && ts[i] <= ts[i - 1]) {
							throw new IllegalArgumentException("Curve times must be strictly ascending: " + point);
						}
					}
					if (ts.length < 2 || Math.abs(ts[0]) > 1.0e-4F || Math.abs(ts[ts.length - 1] - 1.0F) > 1.0e-4F) {
						throw new IllegalArgumentException("Curve times must start at 0 and end at 1");
					}
				}
				result.put(entry.getKey(), new VFXCurve(entry.getKey(), EasingFunction.curve(entry.getKey().toString(), ts, vs)));
			} catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
				LOGGER.error("Couldn't parse VFX curve '{}'", entry.getKey(), e);
			}
		}
		return Map.copyOf(result);
	}

	/**
	 * Merges curves received from the server (over {@code VFXSyncPayload}) on the client.
	 */
	public void applySynced(final Map<Identifier, String> synced) {
		this.rawCurves = Map.copyOf(synced);
		this.curves = reload(synced);
	}
}
package dev.vfxweaver.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphInput;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One parsed per-pixel field: either a built-in function leaf or a composition of two (or, for
 * {@code mix}, three) sub-fields (spec §2, §3.2). The tree is immutable and validated at parse
 * time: type coercion, composition depth/leaf caps, the texture-leaf cap and graph references on
 * numeric parameters are all checked here, so the packer and shader can assume a well-typed tree.
 */
public final class VFXField {
	public static final int MAX_DEPTH = 3;
	public static final int MAX_LEAVES = 4;
	public static final int MAX_PROGRAM = 8;
	public static final int MAX_CURVE_POINTS = 8;
	public static final int MAX_TEXTURE_LEAVES = 1;
	/**
	 * Numeric parameters packed into the generic shader parameter vector, per leaf. Sized for the
	 * widest function, {@code shape} (center, rotation, four radii/half-extents, corner radius,
	 * sides, stroke width, softness and the repeat factors); every other function uses the first
	 * four slots. Four leaves × sixteen slots = sixteen {@code vec4} in the {@code FieldConfig} UBO.
	 */
	public static final int MAX_PARAMS = 16;

	/** The coordinate space a spatial function is evaluated in (spec §2). */
	public enum Space {
		SCREEN, WORLD;

		/**
		 * Resolves a space from its datapack spelling.
		 *
		 * @return the matching space, or {@code null} when unknown
		 */
		public static @Nullable Space fromString(final String name) {
			if (name == null) {
				return null;
			}
			final String normalized = name.trim().toLowerCase(Locale.ROOT);
			return "screen".equals(normalized) ? SCREEN : ("world".equals(normalized) ? WORLD : null);
		}
	}

	private final @Nullable VFXFieldFn fn;
	private final @Nullable VFXFieldOp op;
	private final @Nullable VFXField a;
	private final @Nullable VFXField b;
	private final @Nullable VFXField factor;
	private final Space space;
	private final Map<String, VFXGraphInput> params;
	private final @Nullable String channel;
	private final @Nullable String texture;
	private final @Nullable String primitive;
	private final @Nullable String fill;
	private final float[] curveTimes;
	private final float[] curveValues;
	private final VFXFieldType outputType;
	private final int leaves;
	private final int depth;
	private final int nodes;
	private final boolean needsDepth;
	private final int textureLeaves;

	private VFXField(final @Nullable VFXFieldFn fn, final @Nullable VFXFieldOp op, final @Nullable VFXField a, final @Nullable VFXField b, final @Nullable VFXField factor, final Space space, final Map<String, VFXGraphInput> params, final @Nullable String channel, final @Nullable String texture, final @Nullable String primitive, final @Nullable String fill, final float[] curveTimes, final float[] curveValues, final VFXFieldType outputType, final int leaves, final int depth, final int nodes, final boolean needsDepth, final int textureLeaves) {
		this.fn = fn;
		this.op = op;
		this.a = a;
		this.b = b;
		this.factor = factor;
		this.space = space;
		this.params = Map.copyOf(params);
		this.channel = channel;
		this.texture = texture;
		this.primitive = primitive;
		this.fill = fill;
		this.curveTimes = curveTimes;
		this.curveValues = curveValues;
		this.outputType = outputType;
		this.leaves = leaves;
		this.depth = depth;
		this.nodes = nodes;
		this.needsDepth = needsDepth;
		this.textureLeaves = textureLeaves;
	}

	/**
	 * Parses and validates a field object.
	 *
	 * @param inputName the effect input this field belongs to, used in error messages
	 * @param json      the {@code { "field": ... }} or {@code { "op": ... }} object
	 * @param graph     the definition's graph, needed to validate {@code { "from": ... }} parameters
	 *                  ({@code null} when the definition has no graph)
	 * @return the parsed field, never {@code null}
	 * @throws IllegalArgumentException on any validation fault
	 */
	public static VFXField parse(final String inputName, final JsonObject json, final @Nullable VFXGraph graph) {
		if (json.has("op") && json.has("field")) {
			throw new IllegalArgumentException("input '" + inputName + "': a field object has either 'field' or 'op', not both");
		}
		if (json.has("op")) {
			return parseComposition(inputName, json, graph);
		}
		if (json.has("field")) {
			return parseFunction(inputName, json, graph);
		}
		throw new IllegalArgumentException("input '" + inputName + "': a field needs either 'field' or 'op'");
	}

	private static VFXField parseComposition(final String inputName, final JsonObject json, final @Nullable VFXGraph graph) {
		if (json.has("space")) {
			throw new IllegalArgumentException("input '" + inputName + "': 'space' belongs to a function, not to a composition");
		}
		final String opName = str(json, "op", "");
		final VFXFieldOp op = VFXFieldOp.fromString(opName);
		if (op == null) {
			throw new IllegalArgumentException("input '" + inputName + "': unknown field op '" + opName + "'");
		}
		final VFXField a = parse(inputName, object(inputName, json, "a"), graph);
		final VFXField b = parse(inputName, object(inputName, json, "b"), graph);
		VFXField factor = null;
		if (op.arity() == 3) {
			factor = parse(inputName, object(inputName, json, "factor"), graph);
			if (factor.outputType != VFXFieldType.FLOAT) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'mix': 'factor' must be a float field, got " + factor.outputType);
			}
		} else if (json.has("factor")) {
			throw new IllegalArgumentException("input '" + inputName + "': field '" + opName + "': 'factor' is only valid on 'mix'");
		}
		final VFXFieldType type = VFXFieldType.combine(a.outputType, b.outputType);
		if (type == null) {
			throw new IllegalArgumentException("input '" + inputName + "': cannot combine " + a.outputType + " and " + b.outputType + " with '" + opName + "'");
		}
		final int leaves = a.leaves + b.leaves + (factor == null ? 0 : factor.leaves);
		final int depth = 1 + Math.max(a.depth, Math.max(b.depth, factor == null ? 0 : factor.depth));
		final int nodes = a.nodes + b.nodes + (factor == null ? 0 : factor.nodes) + 1;
		final int textureLeaves = a.textureLeaves + b.textureLeaves + (factor == null ? 0 : factor.textureLeaves);
		checkCaps(inputName, leaves, depth, nodes, textureLeaves);
		final boolean needsDepth = a.needsDepth || b.needsDepth || (factor != null && factor.needsDepth);
		return new VFXField(null, op, a, b, factor, Space.SCREEN, Map.of(), null, null, null, null, new float[0], new float[0], type, leaves, depth, nodes, needsDepth, textureLeaves);
	}

	private static VFXField parseFunction(final String inputName, final JsonObject json, final @Nullable VFXGraph graph) {
		final String fnName = str(json, "field", "");
		final VFXFieldFn fn = VFXFieldFn.fromString(fnName);
		if (fn == null) {
			throw new IllegalArgumentException("input '" + inputName + "': unknown field function '" + fnName + "'");
		}
		final Space space;
		if (json.has("space")) {
			if (!fn.spatial()) {
				throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': 'space' is not valid on this function");
			}
			space = Space.fromString(str(json, "space", ""));
			if (space == null) {
				throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': 'space' must be 'screen' or 'world'");
			}
		} else {
			// World is the default for scene-anchored functions (spec §2); non-spatial functions ignore it.
			space = fn.spatial() ? Space.WORLD : Space.SCREEN;
		}

		final Map<String, VFXGraphInput> params = new LinkedHashMap<>();
		for (final String name : fn.paramNames()) {
			params.put(name, VFXGraphInput.literal(fn.defaultParam(name)));
		}

		String channel = null;
		if (json.has("channel")) {
			if (!fn.hasChannel()) {
				throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': 'channel' is only valid on 'texture'");
			}
			channel = str(json, "channel", "").trim().toLowerCase(Locale.ROOT);
			if (!"r".equals(channel) && !"g".equals(channel) && !"b".equals(channel) && !"a".equals(channel) && !"luminance".equals(channel)) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'texture': 'channel' must be r, g, b, a or luminance");
			}
		}

		String texture = null;
		if (fn.hasTexture()) {
			texture = str(json, "texture", "");
			if (texture.isBlank()) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'texture': 'texture' must be a non-blank resource id");
			}
		} else if (json.has("texture")) {
			throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': 'texture' is only valid on 'texture'");
		}

		String primitive = null;
		String fill = null;
		if (fn.hasShape()) {
			primitive = str(json, "primitive", "circle").trim().toLowerCase(Locale.ROOT);
			if (!"circle".equals(primitive) && !"ellipse".equals(primitive) && !"rect".equals(primitive) && !"polygon".equals(primitive)) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'shape': 'primitive' must be circle, ellipse, rect or polygon");
			}
			fill = str(json, "fill", "solid").trim().toLowerCase(Locale.ROOT);
			if (!"solid".equals(fill) && !"stroke".equals(fill)) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'shape': 'fill' must be 'solid' or 'stroke'");
			}
		} else if (json.has("primitive") || json.has("fill")) {
			throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': 'primitive' and 'fill' are only valid on 'shape'");
		}

		float[] curveTimes = new float[0];
		float[] curveValues = new float[0];
		if (fn.hasCurve()) {
			final JsonElement pointsElement = json.get("points");
			final JsonArray points = pointsElement != null && pointsElement.isJsonArray() ? pointsElement.getAsJsonArray() : null;
			if (points == null || points.isEmpty()) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'curve': 'points' must be a non-empty array");
			}
			if (points.size() > MAX_CURVE_POINTS) {
				throw new IllegalArgumentException("input '" + inputName + "': field 'curve': " + points.size() + " points exceed the limit of " + MAX_CURVE_POINTS);
			}
			curveTimes = new float[points.size()];
			curveValues = new float[points.size()];
			for (int i = 0; i < points.size(); i++) {
				if (!points.get(i).isJsonObject()) {
					throw new IllegalArgumentException("input '" + inputName + "': field 'curve': every point must be an object");
				}
				final JsonObject point = points.get(i).getAsJsonObject();
				curveTimes[i] = flt(point, "time", 0.0F);
				curveValues[i] = flt(point, "value", 0.0F);
				if (i > 0 && curveTimes[i] <= curveTimes[i - 1]) {
					throw new IllegalArgumentException("input '" + inputName + "': field 'curve': point 'time' must be strictly ascending");
				}
			}
		} else if (json.has("points")) {
			throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': 'points' is only valid on 'curve'");
		}

		for (final Map.Entry<String, JsonElement> entry : json.entrySet()) {
			final String name = entry.getKey();
			if ("field".equals(name) || "space".equals(name) || "channel".equals(name) || "texture".equals(name) || "points".equals(name) || "primitive".equals(name) || "fill".equals(name)) {
				continue;
			}
			if (fn.hasShape() && "center".equals(name)) {
				final JsonArray center = pair(inputName, fnName, name, entry.getValue());
				params.put("center_x", parseParameter(inputName, fnName, "center_x", center.get(0), graph));
				params.put("center_y", parseParameter(inputName, fnName, "center_y", center.get(1), graph));
				continue;
			}
			if (fn.hasShape() && "repeat".equals(name)) {
				final JsonArray repeat = pair(inputName, fnName, name, entry.getValue());
				params.put("repeat_x", parseParameter(inputName, fnName, "repeat_x", repeat.get(0), graph));
				params.put("repeat_y", parseParameter(inputName, fnName, "repeat_y", repeat.get(1), graph));
				continue;
			}
			if (fn.paramIndex(name) < 0) {
				throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': unknown parameter '" + name + "'");
			}
			params.put(name, parseParameter(inputName, fnName, name, entry.getValue(), graph));
		}

		final VFXFieldType outputType = fn.hasChannel() && channel != null ? VFXFieldType.FLOAT : fn.outputType();
		final boolean needsDepth = fn.needsDepth() || (fn.spatial() && space == Space.WORLD);
		final int textureLeaves = fn.hasTexture() ? 1 : 0;
		checkCaps(inputName, 1, 1, 1, textureLeaves);
		return new VFXField(fn, null, null, null, null, space, params, channel, texture, primitive, fill, curveTimes, curveValues, outputType, 1, 1, 1, needsDepth, textureLeaves);
	}

	private static VFXGraphInput parseParameter(final String inputName, final String fnName, final String param, final JsonElement value, final @Nullable VFXGraph graph) {
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			return VFXGraphInput.literal(value.getAsFloat());
		}
		if (value.isJsonObject()) {
			final JsonElement from = value.getAsJsonObject().get("from");
			if (from != null && !from.isJsonNull()) {
				final String nodeId = from.getAsString();
				if (graph == null || graph.node(nodeId) == null) {
					throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': parameter '" + param + "': unknown node '" + nodeId + "'");
				}
				return VFXGraphInput.reference(nodeId);
			}
		}
		throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': parameter '" + param + "' must be a number or { \"from\": \"<node>\" }");
	}

	/**
	 * Reads a two-component parameter ({@code center}, {@code repeat}); each element is a number or
	 * a graph reference, validated by {@link #parseParameter}.
	 */
	private static JsonArray pair(final String inputName, final String fnName, final String param, final JsonElement value) {
		if (!value.isJsonArray() || value.getAsJsonArray().size() != 2) {
			throw new IllegalArgumentException("input '" + inputName + "': field '" + fnName + "': '" + param + "' must be an array of two numbers or { \"from\": \"<node>\" }");
		}
		return value.getAsJsonArray();
	}

	private static void checkCaps(final String inputName, final int leaves, final int depth, final int nodes, final int textureLeaves) {
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException("input '" + inputName + "': field composition depth exceeds " + MAX_DEPTH);
		}
		if (leaves > MAX_LEAVES) {
			throw new IllegalArgumentException("input '" + inputName + "': field leaves (" + leaves + ") exceed the limit of " + MAX_LEAVES);
		}
		if (nodes > MAX_PROGRAM) {
			throw new IllegalArgumentException("input '" + inputName + "': field nodes (" + nodes + ") exceed the limit of " + MAX_PROGRAM);
		}
		if (textureLeaves > MAX_TEXTURE_LEAVES) {
			throw new IllegalArgumentException("input '" + inputName + "': at most " + MAX_TEXTURE_LEAVES + " texture field is allowed per input");
		}
	}

	private static JsonObject object(final String inputName, final JsonObject json, final String key) {
		final JsonElement element = json.get(key);
		if (element == null || !element.isJsonObject()) {
			throw new IllegalArgumentException("input '" + inputName + "': a composition needs an object '" + key + "'");
		}
		return element.getAsJsonObject();
	}

	private static String str(final JsonObject object, final String key, final String fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static float flt(final JsonObject object, final String key, final float fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsFloat() : fallback;
	}

	/** The function of a leaf, or {@code null} for a composition. */
	public @Nullable VFXFieldFn fn() {
		return this.fn;
	}

	/** The operator of a composition, or {@code null} for a leaf. */
	public @Nullable VFXFieldOp op() {
		return this.op;
	}

	public @Nullable VFXField a() {
		return this.a;
	}

	public @Nullable VFXField b() {
		return this.b;
	}

	/** The {@code mix} factor field, or {@code null}. */
	public @Nullable VFXField factor() {
		return this.factor;
	}

	/** The evaluation space of a leaf; {@link Space#SCREEN} for a composition. */
	public Space space() {
		return this.space;
	}

	/** Numeric parameters in function order (literals or graph references). */
	public Map<String, VFXGraphInput> params() {
		return this.params;
	}

	/** The selected texture channel, or {@code null}. */
	public @Nullable String channel() {
		return this.channel;
	}

	/** The texture resource id string, or {@code null}. */
	public @Nullable String texture() {
		return this.texture;
	}

	/** The shape primitive ({@code circle}/{@code ellipse}/{@code rect}/{@code polygon}), or {@code null}. */
	public @Nullable String primitive() {
		return this.primitive;
	}

	/** The shape fill mode ({@code solid}/{@code stroke}), or {@code null}. */
	public @Nullable String fill() {
		return this.fill;
	}

	/** Curve point times (empty unless {@link #fn()} is {@code curve}). */
	public float[] curveTimes() {
		return this.curveTimes;
	}

	/** Curve point values (empty unless {@link #fn()} is {@code curve}). */
	public float[] curveValues() {
		return this.curveValues;
	}

	/** The inferred output type (spec §2). */
	public VFXFieldType outputType() {
		return this.outputType;
	}

	/** Number of function leaves in this tree. */
	public int leaves() {
		return this.leaves;
	}

	/** Composition depth (1 for a leaf). */
	public int depth() {
		return this.depth;
	}

	/** Total function/composition nodes (the shader program length). */
	public int nodes() {
		return this.nodes;
	}

	/** True when any part of the tree needs the scene depth (a geometry function or world space). */
	public boolean needsDepth() {
		return this.needsDepth;
	}

	/** Number of texture leaves in this tree (0 or 1). */
	public int textureLeaves() {
		return this.textureLeaves;
	}
}

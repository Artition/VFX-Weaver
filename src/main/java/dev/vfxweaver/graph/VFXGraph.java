package dev.vfxweaver.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.MathExpression;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A parsed, validated uniform graph from the optional top-level {@code graph} block of an effect
 * definition (spec §3.1). Parsing is strict and per-file: any fault (unknown kind, cycle, cap,
 * unconnected required input, bad reference) throws {@link IllegalArgumentException} naming the
 * offending node id and input, which {@code VFXDefinitionManager} catches so one broken file does
 * not take down the pack.
 *
 * <p>Nodes are stored in topological order; {@link #indexOf(String)} maps a node id to its cache
 * slot for {@link VFXGraphEvaluator}. Edges whose {@code to} is not a node id address an effect
 * input and are exposed through {@link #effectInputRefs()}.
 */
public final class VFXGraph {
	/** The only graph format version this build understands. */
	public static final int FORMAT_VERSION = 1;
	public static final int MAX_NODES = 128;
	public static final int MAX_EDGES = 512;
	public static final int MAX_DEPTH = 32;
	public static final int MAX_EXPR_SOURCE = 1024;
	public static final int MAX_CURVE_POINTS = 64;
	/** Upper bound on {@code noise} octaves; the evaluator clamps instead of refusing the file. */
	public static final int MAX_NOISE_OCTAVES = 8;

	private final String owner;
	private final int version;
	private final List<VFXGraphNode> nodes;
	private final Map<String, Integer> indices;
	private final Map<String, String> effectInputRefs;

	private VFXGraph(final String owner, final int version, final List<VFXGraphNode> nodes, final Map<String, Integer> indices, final Map<String, String> effectInputRefs) {
		this.owner = owner;
		this.version = version;
		this.nodes = List.copyOf(nodes);
		this.indices = Map.copyOf(indices);
		this.effectInputRefs = Map.copyOf(effectInputRefs);
	}

	/**
	 * Parses and validates a {@code graph} block.
	 *
	 * @param owner the effect id, used only in error messages
	 * @param json  the value of the top-level {@code graph} field
	 * @return the parsed graph, never {@code null}
	 * @throws IllegalArgumentException on any validation fault
	 */
	public static VFXGraph parse(final String owner, final JsonObject json) {
		final int version = intOr(json, "version", FORMAT_VERSION);
		if (version != FORMAT_VERSION) {
			throw new IllegalArgumentException("graph: unsupported version " + version + " (this build supports " + FORMAT_VERSION + ")");
		}
		final JsonArray nodeArray = json.has("nodes") && json.get("nodes").isJsonArray() ? json.getAsJsonArray("nodes") : null;
		if (nodeArray == null || nodeArray.isEmpty()) {
			throw new IllegalArgumentException("graph: 'nodes' must be a non-empty array");
		}
		if (nodeArray.size() > MAX_NODES) {
			throw new IllegalArgumentException("graph: " + nodeArray.size() + " nodes exceed the limit of " + MAX_NODES);
		}

		final Map<String, VFXGraphNode> byId = new LinkedHashMap<>();
		final Set<String> seenIds = new HashSet<>();
		for (final JsonElement element : nodeArray) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("graph: every node must be an object, got " + element);
			}
			final VFXGraphNode node = parseNode(element.getAsJsonObject());
			if (!seenIds.add(node.id())) {
				throw new IllegalArgumentException("node '" + node.id() + "': duplicate id");
			}
			byId.put(node.id(), node);
		}

		final Map<String, String> effectRefs = new LinkedHashMap<>();
		final JsonArray edgeArray = json.has("edges") && json.get("edges").isJsonArray() ? json.getAsJsonArray("edges") : new JsonArray();
		if (edgeArray.size() > MAX_EDGES) {
			throw new IllegalArgumentException("graph: " + edgeArray.size() + " edges exceed the limit of " + MAX_EDGES);
		}
		for (final JsonElement element : edgeArray) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("graph: every edge must be an object, got " + element);
			}
			final JsonObject edge = element.getAsJsonObject();
			final String from = strOr(edge, "from", "");
			final String to = strOr(edge, "to", "");
			if (!byId.containsKey(from)) {
				throw new IllegalArgumentException("edge from '" + from + "': unknown node");
			}
			if (byId.containsKey(to)) {
				final VFXGraphNode target = byId.get(to);
				final String input = strOr(edge, "input", "");
				if (input.isBlank()) {
					throw new IllegalArgumentException("edge '" + from + "' -> '" + to + "': an 'input' name is required");
				}
				if (!target.kind().acceptsNodeInput(input)) {
					throw new IllegalArgumentException("node '" + to + "': unknown input '" + input + "'");
				}
				if (target.inputs().putIfAbsent(input, VFXGraphInput.reference(from)) != null) {
					throw new IllegalArgumentException("node '" + to + "': input '" + input + "' receives two sources");
				}
			} else {
				if (edge.has("input")) {
					throw new IllegalArgumentException("effect input edge '" + from + "' -> '" + to + "': 'input' is not allowed");
				}
				if (effectRefs.putIfAbsent(to, from) != null) {
					throw new IllegalArgumentException("effect input '" + to + "': receives two sources");
				}
			}
		}

		for (final VFXGraphNode node : byId.values()) {
			for (final String required : node.kind().requiredInputs()) {
				// Structural fields (points/expr/bind) are validated in their kind-specific branch,
				// not as edge inputs; only edge-acceptable required inputs are checked here.
				if (node.kind().acceptsNodeInput(required) && !node.inputs().containsKey(required)) {
					throw new IllegalArgumentException("node '" + node.id() + "': input '" + required + "' is not connected and has no default");
				}
			}
		}

		final List<VFXGraphNode> ordered = sort(byId);
		final Map<String, Integer> indices = new LinkedHashMap<>();
		for (int i = 0; i < ordered.size(); i++) {
			indices.put(ordered.get(i).id(), i);
		}
		return new VFXGraph(owner, version, ordered, indices, effectRefs);
	}

	private static VFXGraphNode parseNode(final JsonObject nodeJson) {
		final String id = strOr(nodeJson, "id", "");
		if (id.isBlank()) {
			throw new IllegalArgumentException("graph: a node is missing its 'id'");
		}
		final String kindName = strOr(nodeJson, "kind", "");
		final VFXNodeKind kind = VFXNodeKind.fromString(kindName);
		if (kind == null) {
			throw new IllegalArgumentException("node '" + id + "': unknown kind '" + kindName + "'");
		}
		final JsonObject inputsJson = nodeJson.has("inputs") && nodeJson.get("inputs").isJsonObject() ? nodeJson.getAsJsonObject("inputs") : new JsonObject();
		final Map<String, VFXGraphInput> inputs = new LinkedHashMap<>();

		String exprSource = null;
		VFXGraphNode.MathOp mathOp = null;
		float[] curveTimes = new float[0];
		float[] curveValues = new float[0];
		EasingFunction[] curveEasings = new EasingFunction[0];
		BoundParam bound = null;

		for (final Map.Entry<String, JsonElement> entry : inputsJson.entrySet()) {
			final String name = entry.getKey();
			if (kind == VFXNodeKind.CURVE && name.equals("points")) {
				continue;
			}
			if (kind == VFXNodeKind.EXPR && name.equals("expr")) {
				continue;
			}
			if (kind == VFXNodeKind.BIND) {
				continue;
			}
			if (!kind.acceptsNodeInput(name)) {
				throw new IllegalArgumentException("node '" + id + "': unknown input '" + name + "'");
			}
			inputs.put(name, parseInput(id, name, entry.getValue()));
		}

		switch (kind) {
			case MATH -> {
				final String opName = strOr(nodeJson, "op", "");
				mathOp = VFXGraphNode.MathOp.fromString(opName);
				if (mathOp == null) {
					throw new IllegalArgumentException("node '" + id + "': unknown math op '" + opName + "'");
				}
			}
			case EXPR -> {
				final JsonElement exprElement = inputsJson.get("expr");
				if (exprElement == null || !exprElement.isJsonPrimitive()) {
					throw new IllegalArgumentException("node '" + id + "': 'expr' must be a string");
				}
				exprSource = exprElement.getAsString();
				if (exprSource.isBlank()) {
					throw new IllegalArgumentException("node '" + id + "': 'expr' must not be blank");
				}
				if (exprSource.length() > MAX_EXPR_SOURCE) {
					throw new IllegalArgumentException("node '" + id + "': 'expr' is longer than " + MAX_EXPR_SOURCE + " characters");
				}
				if (MathExpression.compile(0L, exprSource) == null) {
					throw new IllegalArgumentException("node '" + id + "': invalid expr '" + exprSource + "'");
				}
			}
			case CURVE -> {
				final JsonElement pointsElement = inputsJson.get("points");
				final JsonArray points = pointsElement != null && pointsElement.isJsonArray() ? pointsElement.getAsJsonArray() : null;
				if (points == null || points.isEmpty()) {
					throw new IllegalArgumentException("node '" + id + "': 'points' must be a non-empty array");
				}
				if (points.size() > MAX_CURVE_POINTS) {
					throw new IllegalArgumentException("node '" + id + "': " + points.size() + " curve points exceed the limit of " + MAX_CURVE_POINTS);
				}
				curveTimes = new float[points.size()];
				curveValues = new float[points.size()];
				curveEasings = new EasingFunction[points.size()];
				for (int i = 0; i < points.size(); i++) {
					if (!points.get(i).isJsonObject()) {
						throw new IllegalArgumentException("node '" + id + "': every curve point must be an object");
					}
					final JsonObject point = points.get(i).getAsJsonObject();
					curveTimes[i] = fltOr(point, "time", 0.0F);
					curveValues[i] = fltOr(point, "value", 0.0F);
					if (i > 0 && curveTimes[i] <= curveTimes[i - 1]) {
						throw new IllegalArgumentException("node '" + id + "': curve 'time' must be strictly ascending");
					}
					final JsonElement easing = point.get("easing");
					curveEasings[i] = easing != null && !easing.isJsonNull()
						? EasingFunction.fromString(easing.getAsString())
						: EasingFunction.builtIn(EasingType.LINEAR);
				}
			}
			case BIND -> {
				try {
					bound = BoundParam.parse(inputsJson);
				} catch (final IllegalArgumentException e) {
					throw new IllegalArgumentException("node '" + id + "': " + e.getMessage());
				}
				final JsonElement fallback = inputsJson.get("fallback");
				if (fallback != null && fallback.isJsonPrimitive()) {
					inputs.put("fallback", parseInput(id, "fallback", fallback));
				}
			}
			default -> {
			}
		}
		return new VFXGraphNode(id, kind, inputs, exprSource, mathOp, curveTimes, curveValues, curveEasings, bound);
	}

	private static VFXGraphInput parseInput(final String nodeId, final String name, final JsonElement value) {
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			return VFXGraphInput.literal(value.getAsFloat());
		}
		if (value.isJsonObject()) {
			final JsonElement from = value.getAsJsonObject().get("from");
			if (from != null && !from.isJsonNull()) {
				final String ref = from.getAsString();
				if (ref.isBlank()) {
					throw new IllegalArgumentException("node '" + nodeId + "': input '" + name + "' has a blank 'from'");
				}
				return VFXGraphInput.reference(ref);
			}
		}
		throw new IllegalArgumentException("node '" + nodeId + "': input '" + name + "' must be a number or { \"from\": \"<node>\" }");
	}

	/**
	 * Kahn topological sort. Also detects cycles and enforces {@link #MAX_DEPTH}; references to
	 * unknown nodes are reported here.
	 */
	private static List<VFXGraphNode> sort(final Map<String, VFXGraphNode> byId) {
		final Map<String, Integer> indegree = new LinkedHashMap<>();
		for (final VFXGraphNode node : byId.values()) {
			indegree.put(node.id(), 0);
		}
		for (final VFXGraphNode node : byId.values()) {
			for (final VFXGraphInput input : node.inputs().values()) {
				if (input.reference()) {
					if (!byId.containsKey(input.node())) {
						throw new IllegalArgumentException("node '" + node.id() + "': input references unknown node '" + input.node() + "'");
					}
					indegree.merge(node.id(), 1, Integer::sum);
				}
			}
		}
		final Map<String, Integer> depth = new LinkedHashMap<>();
		final ArrayDeque<String> queue = new ArrayDeque<>();
		for (final Map.Entry<String, Integer> entry : indegree.entrySet()) {
			if (entry.getValue() == 0) {
				queue.add(entry.getKey());
				depth.put(entry.getKey(), 1);
			}
		}
		final List<VFXGraphNode> ordered = new ArrayList<>();
		while (!queue.isEmpty()) {
			final String id = queue.remove();
			ordered.add(byId.get(id));
			for (final VFXGraphNode other : byId.values()) {
				for (final VFXGraphInput input : other.inputs().values()) {
					if (input.reference() && input.node().equals(id)) {
						if (indegree.merge(other.id(), -1, Integer::sum) == 0) {
							final int nextDepth = depth.getOrDefault(id, 1) + 1;
							depth.put(other.id(), nextDepth);
							if (nextDepth > MAX_DEPTH) {
								throw new IllegalArgumentException("node '" + other.id() + "': graph depth exceeds " + MAX_DEPTH);
							}
							queue.add(other.id());
						}
					}
				}
			}
		}
		if (ordered.size() != byId.size()) {
			for (final VFXGraphNode node : byId.values()) {
				if (!ordered.contains(node)) {
					throw new IllegalArgumentException("graph contains a cycle (node '" + node.id() + "')");
				}
			}
		}
		return ordered;
	}

	public String owner() {
		return this.owner;
	}

	public int version() {
		return this.version;
	}

	/** All nodes in topological order. */
	public List<VFXGraphNode> nodes() {
		return this.nodes;
	}

	public int nodeCount() {
		return this.nodes.size();
	}

	/**
	 * Looks up a node by id.
	 *
	 * @param id stable node id
	 * @return the node, or {@code null} when absent
	 */
	public @Nullable VFXGraphNode node(final String id) {
		final Integer index = this.indices.get(id);
		return index == null ? null : this.nodes.get(index);
	}

	/**
	 * The cache slot of a node in {@link VFXGraphEvaluator}, or {@code null} when absent.
	 */
	public @Nullable Integer indexOf(final String id) {
		return this.indices.get(id);
	}

	/**
	 * Effect-input wiring declared through edges ({@code { "from": "n3", "to": "radius" }}), as
	 * input name to source node id.
	 */
	public Map<String, String> effectInputRefs() {
		return this.effectInputRefs;
	}

	private static String strOr(final JsonObject object, final String key, final String fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static int intOr(final JsonObject object, final String key, final int fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
	}

	private static float fltOr(final JsonObject object, final String key, final float fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsFloat() : fallback;
	}
}

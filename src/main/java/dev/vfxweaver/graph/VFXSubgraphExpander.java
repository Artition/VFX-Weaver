package dev.vfxweaver.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Expands the optional top-level {@code subgraphs} block (spec §3.1.1) into a flat
 * {@link VFXGraph}. A {@code subgraph} node is replaced at load time by a prefixed copy of the
 * macro's nodes and edges, {@code $param} placeholders are substituted with the instance's
 * bindings, and internal references are rewritten, so the runtime evaluator only ever sees a flat
 * graph. A definition without a {@code subgraphs} block and without {@code subgraph} nodes is
 * handed to {@link VFXGraph#parse} unchanged, which keeps the step-2a path byte-for-byte.
 *
 * <p>Node ids inside a macro are local; on expansion they are prefixed with the instance id
 * ({@code n1.s2}). A fault inside a macro is reported with both ends, e.g.
 * {@code subgraph 'fade_noise' (node 'n1') → node 's3': input 'a' is not connected and has no default}.
 */
public final class VFXSubgraphExpander {
	/** Upper bound on the number of subgraph definitions in one file. */
	public static final int MAX_SUBGRAPHS = 64;
	/** Upper bound on subgraph nesting (a macro instancing another macro). */
	public static final int MAX_MACRO_DEPTH = 8;

	private static final Pattern PARAMETER = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

	/**
	 * The flat graph plus the aliases needed to resolve top-level references to a subgraph
	 * instance from outside {@code graph} (the effect's {@code inputs} block).
	 *
	 * @param graph           the expanded, parsed graph
	 * @param topLevelOutputs subgraph instance id to the id of its first declared output node
	 */
	public record Result(VFXGraph graph, Map<String, String> topLevelOutputs) {
	}

	private record Macro(String id, Map<String, JsonElement> parameters, List<JsonObject> nodes, List<JsonObject> edges, Map<String, String> outputs) {
	}

	private static final class Inlined {
		final List<JsonObject> nodes = new ArrayList<>();
		final List<JsonObject> edges = new ArrayList<>();
		final Map<String, String> outputs = new LinkedHashMap<>();
	}

	@FunctionalInterface
	private interface RefResolver {
		@Nullable String resolve(String ref, @Nullable String outputName, String context);
	}

	private VFXSubgraphExpander() {
	}

	/**
	 * Expands {@code subgraphs} into {@code graphJson} and parses the result.
	 *
	 * @param owner         the effect id, used in error messages
	 * @param graphJson     the top-level {@code graph} object
	 * @param subgraphsJson the top-level {@code subgraphs} array, or {@code null} when absent
	 * @return the flat graph plus top-level instance aliases
	 * @throws IllegalArgumentException on any validation fault, naming the macro instance and the
	 *                                  inner node where applicable
	 */
	public static Result expand(final String owner, final JsonObject graphJson, final @Nullable JsonArray subgraphsJson) {
		if (subgraphsJson == null || subgraphsJson.isEmpty()) {
			rejectUndeclaredSubgraphs(graphJson);
			return new Result(VFXGraph.parse(owner, graphJson), Map.of());
		}
		final Map<String, Macro> registry = macroRegistry(subgraphsJson);
		final Map<String, Map<String, String>> instanceOutputs = new LinkedHashMap<>();
		final Map<String, String> attribution = new LinkedHashMap<>();
		final Set<String> topLevelIds = new LinkedHashSet<>();
		final List<JsonObject> plainNodes = new ArrayList<>();
		final List<JsonObject> expandedNodes = new ArrayList<>();
		final List<JsonObject> expandedEdges = new ArrayList<>();
		final RefResolver topResolver = (ref, outputName, context) -> {
			final Map<String, String> outputs = instanceOutputs.get(ref);
			return outputs == null ? null : outputFor(outputs, outputName, context);
		};

		for (final JsonElement element : arrayOf(graphJson, "nodes")) {
			final JsonObject node = asObject(element, "graph: every node must be an object, got ");
			final String id = requireString(node, "id", "graph: a node is missing its 'id'");
			if (!topLevelIds.add(id)) {
				throw new IllegalArgumentException("node '" + id + "': duplicate id");
			}
			if (!"subgraph".equals(kindOf(node))) {
				plainNodes.add(node);
				continue;
			}
			final String macroId = requireString(node, "subgraph", "node '" + id + "': 'subgraph' is missing");
			final Macro macro = registry.get(macroId);
			if (macro == null) {
				throw new IllegalArgumentException("node '" + id + "': unknown subgraph '" + macroId + "'");
			}
			final Map<String, JsonElement> bindings = bindings(macro, node, Map.of(), topResolver, "node '" + id + "'");
			final Inlined inlined = inline(id, macro, bindings, 1, new ArrayDeque<>(), registry, attribution);
			expandedNodes.addAll(inlined.nodes);
			expandedEdges.addAll(inlined.edges);
			instanceOutputs.put(id, inlined.outputs);
			cap(expandedNodes.size(), expandedEdges.size());
		}

		final Map<String, String> aliases = new LinkedHashMap<>();
		for (final Map.Entry<String, Map<String, String>> entry : instanceOutputs.entrySet()) {
			aliases.put(entry.getKey(), firstOutput(entry.getValue(), "node '" + entry.getKey() + "'"));
		}
		for (final JsonObject node : plainNodes) {
			final String id = requireString(node, "id", "graph: a node is missing its 'id'");
			expandedNodes.add(rewriteNode(node, id, Map.of(), (ref, outputName, context) -> aliases.get(ref), "node '" + id + "'"));
		}
		for (final JsonElement element : arrayOf(graphJson, "edges")) {
			expandedEdges.add(remapTopLevelEdge(asObject(element, "graph: every edge must be an object, got "), instanceOutputs));
		}
		cap(expandedNodes.size(), expandedEdges.size());

		final JsonObject flat = new JsonObject();
		flat.addProperty("version", intOr(graphJson, "version", VFXGraph.FORMAT_VERSION));
		final JsonArray nodesArray = new JsonArray();
		for (final JsonObject node : expandedNodes) {
			nodesArray.add(node);
		}
		flat.add("nodes", nodesArray);
		final JsonArray edgesArray = new JsonArray();
		for (final JsonObject edge : expandedEdges) {
			edgesArray.add(edge);
		}
		flat.add("edges", edgesArray);
		try {
			return new Result(VFXGraph.parse(owner, flat), aliases);
		} catch (final IllegalArgumentException e) {
			throw new IllegalArgumentException(attribute(e.getMessage(), attribution));
		}
	}

	private static Inlined inline(final String prefix, final Macro macro, final Map<String, JsonElement> bindings, final int depth, final Deque<String> active, final Map<String, Macro> registry, final Map<String, String> attribution) {
		if (depth > MAX_MACRO_DEPTH) {
			throw new IllegalArgumentException("subgraph '" + macro.id() + "' (node '" + prefix + "'): macro nesting exceeds " + MAX_MACRO_DEPTH);
		}
		if (active.contains(macro.id())) {
			throw new IllegalArgumentException("subgraph '" + macro.id() + "' (node '" + prefix + "'): recursive subgraph reference");
		}
		active.addLast(macro.id());
		try {
			final Inlined result = new Inlined();
			final Map<String, String> locals = new LinkedHashMap<>();
			final List<JsonObject> macroNodes = new ArrayList<>();
			for (final JsonElement element : macro.nodes()) {
				final JsonObject node = asObject(element, "subgraph '" + macro.id() + "': every node must be an object, got ");
				macroNodes.add(node);
				final String localId = requireString(node, "id", "subgraph '" + macro.id() + "': a node is missing its 'id'");
				final String fullId = prefix + "." + localId;
				if (locals.putIfAbsent(localId, fullId) != null) {
					throw new IllegalArgumentException("subgraph '" + macro.id() + "' (node '" + prefix + "'): duplicate local node id '" + localId + "'");
				}
				attribution.putIfAbsent(fullId, "subgraph '" + macro.id() + "' (node '" + prefix + "')");
			}

			final Map<String, Map<String, String>> nested = new LinkedHashMap<>();
			for (final JsonObject node : macroNodes) {
				final String localId = requireString(node, "id", "subgraph '" + macro.id() + "': a node is missing its 'id'");
				final String fullId = locals.get(localId);
				if ("subgraph".equals(kindOf(node))) {
					final String nestedId = requireString(node, "subgraph", context(macro, prefix, localId) + ": 'subgraph' is missing");
					final Macro nestedMacro = registry.get(nestedId);
					if (nestedMacro == null) {
						throw new IllegalArgumentException(context(macro, prefix, localId) + ": unknown subgraph '" + nestedId + "'");
					}
					final Map<String, JsonElement> nestedBindings = bindings(nestedMacro, node, bindings, macroResolver(locals, nested), context(macro, prefix, localId));
					final Inlined sub = inline(fullId, nestedMacro, nestedBindings, depth + 1, active, registry, attribution);
					result.nodes.addAll(sub.nodes);
					result.edges.addAll(sub.edges);
					nested.put(localId, sub.outputs);
				} else {
					result.nodes.add(rewriteNode(node, fullId, bindings, macroResolver(locals, nested), context(macro, prefix, localId)));
				}
				cap(result.nodes.size(), result.edges.size());
			}

			for (final JsonElement element : macro.edges()) {
				final JsonObject edge = asObject(element, "subgraph '" + macro.id() + "': every edge must be an object, got ");
				final String from = str(edge, "from");
				final String to = str(edge, "to");
				final RefResolver resolver = macroResolver(locals, nested);
				final String fromFull = resolver.resolve(from, outputName(edge),
					"subgraph '" + macro.id() + "' (node '" + prefix + "') → edge from '" + from + "'");
				final String toFull = locals.get(to);
				if (toFull == null) {
					if (nested.containsKey(to)) {
						throw new IllegalArgumentException(context(macro, prefix, to) + ": a macro instance cannot be wired with an edge; fill its 'inputs' block");
					}
					throw new IllegalArgumentException("subgraph '" + macro.id() + "' (node '" + prefix + "') → edge to '" + to + "': unknown node");
				}
				final JsonObject rewritten = new JsonObject();
				rewritten.addProperty("from", fromFull);
				rewritten.addProperty("to", toFull);
				if (edge.has("input") && !edge.get("input").isJsonNull()) {
					rewritten.add("input", edge.get("input"));
				}
				result.edges.add(rewritten);
			}
			cap(result.nodes.size(), result.edges.size());

			for (final Map.Entry<String, String> output : macro.outputs().entrySet()) {
				final Map<String, String> nestedOutputs = nested.get(output.getValue());
				if (nestedOutputs != null) {
					result.outputs.put(output.getKey(), firstOutput(nestedOutputs, context(macro, prefix, output.getKey())));
					continue;
				}
				final String localTarget = locals.get(output.getValue());
				if (localTarget != null) {
					result.outputs.put(output.getKey(), localTarget);
					continue;
				}
				throw new IllegalArgumentException("subgraph '" + macro.id() + "' (node '" + prefix + "'): output '" + output.getKey() + "' references unknown node '" + output.getValue() + "'");
			}
			return result;
		} finally {
			active.removeLast();
		}
	}

	private static RefResolver macroResolver(final Map<String, String> locals, final Map<String, Map<String, String>> nested) {
		return (ref, outputName, context) -> {
			final String local = locals.get(ref);
			if (local != null) {
				return local;
			}
			final Map<String, String> outputs = nested.get(ref);
			if (outputs != null) {
				return outputFor(outputs, outputName, context);
			}
			throw new IllegalArgumentException(context + ": unknown node '" + ref + "'");
		};
	}

	private static Map<String, JsonElement> bindings(final Macro macro, final JsonObject instance, final Map<String, JsonElement> parentBindings, final RefResolver parentResolver, final String context) {
		final Map<String, JsonElement> bindings = new LinkedHashMap<>(macro.parameters());
		if (instance.has("inputs") && instance.get("inputs").isJsonObject()) {
			for (final Map.Entry<String, JsonElement> entry : instance.getAsJsonObject("inputs").entrySet()) {
				if (!macro.parameters().containsKey(entry.getKey())) {
					throw new IllegalArgumentException(context + ": unknown parameter '" + entry.getKey() + "'");
				}
				bindings.put(entry.getKey(), rewriteValue(entry.getValue(), parentBindings, parentResolver, context + ": parameter '" + entry.getKey() + "'"));
			}
		}
		return bindings;
	}

	private static JsonObject rewriteNode(final JsonObject node, final String fullId, final Map<String, JsonElement> bindings, final RefResolver resolver, final String context) {
		final JsonObject rewritten = node.deepCopy();
		rewritten.addProperty("id", fullId);
		if (rewritten.has("inputs") && rewritten.get("inputs").isJsonObject()) {
			final JsonObject inputs = rewritten.getAsJsonObject("inputs");
			final JsonObject out = new JsonObject();
			for (final Map.Entry<String, JsonElement> entry : inputs.entrySet()) {
				out.add(entry.getKey(), rewriteValue(entry.getValue(), bindings, resolver, context + ": input '" + entry.getKey() + "'"));
			}
			rewritten.add("inputs", out);
		}
		return rewritten;
	}

	private static JsonElement rewriteValue(final JsonElement value, final Map<String, JsonElement> bindings, final RefResolver resolver, final String context) {
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			final String text = value.getAsString();
			if (PARAMETER.matcher(text).matches()) {
				final String name = text.substring(1);
				final JsonElement bound = bindings.get(name);
				if (bound == null) {
					throw new IllegalArgumentException(context + ": parameter '" + name + "' has no value and no default");
				}
				return bound.deepCopy();
			}
			return value.deepCopy();
		}
		if (value.isJsonObject()) {
			final JsonObject object = value.getAsJsonObject();
			final JsonElement from = object.get("from");
			if (from != null && !from.isJsonNull()) {
				final JsonObject rewritten = object.deepCopy();
				final String resolved = resolver.resolve(from.getAsString(), outputName(object), context);
				if (resolved != null) {
					rewritten.addProperty("from", resolved);
				}
				return rewritten;
			}
			return value.deepCopy();
		}
		return value.deepCopy();
	}

	private static JsonObject remapTopLevelEdge(final JsonObject edge, final Map<String, Map<String, String>> instanceOutputs) {
		final JsonObject rewritten = edge.deepCopy();
		final String from = str(edge, "from");
		final String to = str(edge, "to");
		if (instanceOutputs.containsKey(from)) {
			rewritten.addProperty("from", outputFor(instanceOutputs.get(from), outputName(edge), "edge from '" + from + "'"));
		} else if (edge.has("output") && !edge.get("output").isJsonNull()) {
			throw new IllegalArgumentException("edge from '" + from + "': 'output' is only valid when 'from' is a subgraph instance");
		}
		if (instanceOutputs.containsKey(to)) {
			throw new IllegalArgumentException("effect input edge '" + from + "' -> '" + to + "': a subgraph instance cannot be an edge target; fill its 'inputs' block");
		}
		return rewritten;
	}

	private static Map<String, Macro> macroRegistry(final JsonArray subgraphsJson) {
		if (subgraphsJson.size() > MAX_SUBGRAPHS) {
			throw new IllegalArgumentException("subgraphs: " + subgraphsJson.size() + " exceed the limit of " + MAX_SUBGRAPHS);
		}
		final Map<String, Macro> registry = new LinkedHashMap<>();
		for (final JsonElement element : subgraphsJson) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("subgraphs: every entry must be an object, got " + element);
			}
			final JsonObject object = element.getAsJsonObject();
			final String id = requireString(object, "id", "subgraphs: an entry is missing its 'id'");
			final Map<String, JsonElement> parameters = new LinkedHashMap<>();
			if (object.has("inputs") && object.get("inputs").isJsonObject()) {
				for (final Map.Entry<String, JsonElement> entry : object.getAsJsonObject("inputs").entrySet()) {
					parameters.put(entry.getKey(), entry.getValue());
				}
			}
			final List<JsonObject> nodes = new ArrayList<>();
			for (final JsonElement node : arrayOf(object, "nodes")) {
				nodes.add(asObject(node, "subgraph '" + id + "': every node must be an object, got "));
			}
			if (nodes.isEmpty()) {
				throw new IllegalArgumentException("subgraph '" + id + "': 'nodes' must be a non-empty array");
			}
			if (!object.has("outputs") || !object.get("outputs").isJsonObject() || object.getAsJsonObject("outputs").isEmpty()) {
				throw new IllegalArgumentException("subgraph '" + id + "': 'outputs' must be a non-empty object");
			}
			final Map<String, String> outputs = new LinkedHashMap<>();
			for (final Map.Entry<String, JsonElement> entry : object.getAsJsonObject("outputs").entrySet()) {
				outputs.put(entry.getKey(), entry.getValue().getAsString());
			}
			final List<JsonObject> edges = new ArrayList<>();
			for (final JsonElement edge : arrayOf(object, "edges")) {
				edges.add(asObject(edge, "subgraph '" + id + "': every edge must be an object, got "));
			}
			if (registry.putIfAbsent(id, new Macro(id, parameters, nodes, edges, outputs)) != null) {
				throw new IllegalArgumentException("subgraphs: duplicate id '" + id + "'");
			}
		}
		return registry;
	}

	private static void rejectUndeclaredSubgraphs(final JsonObject graphJson) {
		for (final JsonElement element : arrayOf(graphJson, "nodes")) {
			if (element.isJsonObject() && "subgraph".equals(kindOf(element.getAsJsonObject()))) {
				throw new IllegalArgumentException("node '" + str(element.getAsJsonObject(), "id")
					+ "': a 'subgraph' node needs a top-level 'subgraphs' block");
			}
		}
	}

	private static String attribute(final String message, final Map<String, String> attribution) {
		String best = null;
		for (final String id : attribution.keySet()) {
			if (message.contains("'" + id + "'") && (best == null || id.length() > best.length())) {
				best = id;
			}
		}
		if (best == null) {
			return message;
		}
		final String local = best.substring(best.lastIndexOf('.') + 1);
		return attribution.get(best) + " → " + message.replace("'" + best + "'", "'" + local + "'");
	}

	private static String outputFor(final Map<String, String> outputs, final @Nullable String name, final String context) {
		if (name == null) {
			return firstOutput(outputs, context);
		}
		final String resolved = outputs.get(name);
		if (resolved == null) {
			throw new IllegalArgumentException(context + ": unknown output '" + name + "'");
		}
		return resolved;
	}

	private static String firstOutput(final Map<String, String> outputs, final String context) {
		if (outputs.isEmpty()) {
			throw new IllegalArgumentException(context + ": subgraph has no outputs");
		}
		return outputs.values().iterator().next();
	}

	private static @Nullable String outputName(final JsonObject object) {
		final JsonElement output = object.get("output");
		return output != null && !output.isJsonNull() ? output.getAsString() : null;
	}

	private static void cap(final int nodes, final int edges) {
		if (nodes > VFXGraph.MAX_NODES) {
			throw new IllegalArgumentException("graph: expanded " + nodes + " nodes exceed the limit of " + VFXGraph.MAX_NODES + " (subgraph expansion)");
		}
		if (edges > VFXGraph.MAX_EDGES) {
			throw new IllegalArgumentException("graph: expanded " + edges + " edges exceed the limit of " + VFXGraph.MAX_EDGES + " (subgraph expansion)");
		}
	}

	private static String context(final Macro macro, final String prefix, final String localId) {
		return "subgraph '" + macro.id() + "' (node '" + prefix + "') → node '" + localId + "'";
	}

	private static JsonArray arrayOf(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}

	private static JsonObject asObject(final JsonElement element, final String message) {
		if (!element.isJsonObject()) {
			throw new IllegalArgumentException(message + element);
		}
		return element.getAsJsonObject();
	}

	private static String requireString(final JsonObject object, final String key, final String message) {
		final JsonElement element = object.get(key);
		if (element == null || element.isJsonNull() || element.getAsString().isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return element.getAsString();
	}

	private static String kindOf(final JsonObject node) {
		final JsonElement kind = node.get("kind");
		return kind != null && !kind.isJsonNull() ? kind.getAsString() : "";
	}

	private static @Nullable String str(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : null;
	}

	private static int intOr(final JsonObject object, final String key, final int fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
	}
}

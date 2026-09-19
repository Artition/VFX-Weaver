# Step 2a — Uniform Graph Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the additive `graph` block to the datapack effect format, a CPU pull-based evaluator for the twelve uniform node kinds, `{ "from": "<node>" }` on effect inputs with a numeric default kept everywhere, per-file error isolation and caps — then prove the whole path in-game by driving an existing numeric parameter (`blur.radius`) from a graph.

**Architecture:** A new MC-free module `dev.vfxweaver.graph` (node model, JSON parser, CPU evaluator) lives in shared `src/main/java/` because datapack parsing runs on the dedicated server too. `VFXDefinition` parses the optional `graph` and `inputs` blocks, `VFXTimeline` holds the per-instance evaluator, and `VFXActiveEffect.update` drives it once per frame; renderers keep reading `effect.getParam(name, fallback)` unchanged, so the graph reaches a shader uniform through the existing one-line-per-param `VFXPostProcessingManager` path. `bind` reuses the shared `VFXWorldBindings` camera snapshot; per-frame consumption stays client-side. Nothing about the graph changes the format's required fields or the network protocol.

**Tech Stack:** Java 25 (JDK 26 build), Gradle 9.5.1, Stonecutter 0.9.8, Gson (already a Minecraft/gradle dependency — no new dependency is added), `com.google.gson` used directly in the graph module so it stays free of `net.minecraft.*`.

**Spec:** `docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md` (§2 evaluation domains, §3.1 graph block, §3.2 sockets, §7 backward compatibility, §8 caps/errors, §9 step 2a). Depth prerequisite: `docs/superpowers/specs/notes/2026-09-19-depth-findings.md` (not needed by this plan — no depth access here).

## Global Constraints

- One shared `src/`; no `net.fabricmc.*` / `net.neoforged.*` imports outside `dev.vfxweaver.platform` and `dev.vfxweaver.client.platform`.
- The graph module (`dev.vfxweaver.graph`) lives in shared `src/main/java/` and must stay MC-free: no `net.minecraft.client.*`, and no `net.minecraft.*` at all — parse JSON with plain Gson, not `net.minecraft.util.GsonHelper`. Datapack parsing happens on the dedicated server.
- `src/main` must never reference `src/client`. Per-frame consumption of the graph (the update call already inside the client `VFXEffectManager.update`) is client-side; uniform upload stays in `client.postprocessing`.
- Indentation is tabs; non-reassigned params/locals are `final`; public classes and non-trivial public methods carry javadoc.
- No new dependencies.
- The datapack format is **additive**: `graph` and `inputs` are optional; no `PROTOCOL_VERSION` bump (spec §7). A definition without these blocks behaves exactly as today; every effect input keeps a numeric default.
- Bounded collections (AGENTS.md): node/edge/depth/expr/caps are constants on `VFXGraph`; violations refuse **that file only** via `IllegalArgumentException`, which `VFXDefinitionManager.reload`/`registerLocal` already catch (`VFXDefinitionManager.java:145`, `:178`).
- Parse errors name the offending node id and input (spec §8), e.g. `node 'n3': input 'b' is not connected and has no default`.
- There is **no test suite**: every task is verified by (a) building all six nodes, (b) a throwaway `main()` compiled against the built classes with assertions (AGENTS.md "Build and verify" method 2), and (c) an in-game check by the human partner. Never claim a visual result.
- Build commands (from `AGENTS.md`): `.\gradlew.bat :<node>:build`; Fabric nodes are `26.2`, `26.1.2`, `1.21.11`; NeoForge nodes are the same names with `-neoforge`.
- `javap` against the real deobf jar is the method for any uncertain MC API; the graph work is expected to need none.

## File Structure

- `src/main/java/dev/vfxweaver/graph/VFXNodeKind.java` — create: the v1 node-kind enum (kinds, required inputs, edge-acceptable inputs).
- `src/main/java/dev/vfxweaver/graph/VFXGraphInput.java` — create: a node input (literal or node reference).
- `src/main/java/dev/vfxweaver/graph/VFXGraphNode.java` — create: one parsed node with kind-specific config.
- `src/main/java/dev/vfxweaver/graph/VFXGraph.java` — create: parser, caps, cycle/depth checks, topological order, effect-input refs.
- `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java` — create: per-instance pull evaluator with per-frame memo.
- `src/main/java/dev/vfxweaver/effect/BoundParam.java` — modify: add MC-free `parse(JsonObject)`.
- `src/main/java/dev/vfxweaver/effect/VFXDefinition.java` — modify: delegate `parseBound` to `BoundParam.parse`; parse `graph`/`inputs`; expose `getGraph`/`getGraphInputs`; pass them into the timeline.
- `src/main/java/dev/vfxweaver/effect/VFXWorldBindings.java` — modify: add `currentFrame()`.
- `src/main/java/dev/vfxweaver/effect/VFXTimeline.java` — modify: carry the graph + input refs, add `updateGraph`, resolve graph inputs in `getValue`.
- `src/main/java/dev/vfxweaver/effect/VFXActiveEffect.java` — modify: call `timeline.updateGraph(elapsed)` from `update`.
- `src/main/resources/data/vfxweaver/vfx/graph_demo.json` — create: the built-in consumer (blur driven by a time→curve graph).
- `docs/GUIDE.md`, `docs/CHANGELOG.md` — modify: document the format.

---

### Task 1: Parse and validate the `graph` block

**Files:**
- Create: `src/main/java/dev/vfxweaver/graph/VFXNodeKind.java`
- Create: `src/main/java/dev/vfxweaver/graph/VFXGraphInput.java`
- Create: `src/main/java/dev/vfxweaver/graph/VFXGraphNode.java`
- Create: `src/main/java/dev/vfxweaver/graph/VFXGraph.java`
- Modify: `src/main/java/dev/vfxweaver/effect/BoundParam.java` (add `parse(JsonObject)`)
- Modify: `src/main/java/dev/vfxweaver/effect/VFXDefinition.java:479-518` (delegate `parseBound`)
- Test: `%TEMP%\vfxcheck\Check.java` (throwaway, not committed)

**Interfaces:**
- Consumes: `BoundParam.Kind.fromString(String)`, `EasingFunction.fromString(String)`, `EasingType.LINEAR`, `MathExpression.compile(long, String)`.
- Produces:
  - `VFXGraph.parse(String owner, com.google.gson.JsonObject json) : VFXGraph` (throws `IllegalArgumentException` naming the node id/input on every fault).
  - `VFXGraph.nodeCount() : int`, `VFXGraph.nodes() : List<VFXGraphNode>`, `VFXGraph.node(String id) : @Nullable VFXGraphNode`, `VFXGraph.indexOf(String id) : @Nullable Integer`, `VFXGraph.effectInputRefs() : Map<String, String>`, `VFXGraph.version() : int`, `VFXGraph.owner() : String`.
  - `VFXGraph.MAX_NODES = 128`, `MAX_EDGES = 512`, `MAX_DEPTH = 32`, `MAX_EXPR_SOURCE = 1024`, `MAX_CURVE_POINTS = 64`, `MAX_NOISE_OCTAVES = 8`, `FORMAT_VERSION = 1`.
  - `VFXNodeKind.CONSTANT/TIME/RANDOM/NOISE/CURVE/MATH/MIX/CLAMP/REMAP/BIND/EXPR`, `VFXNodeKind.id()`, `requiredInputs() : List<String>`, `acceptsNodeInput(String) : boolean`.
  - `VFXGraphInput.literal(float)`, `VFXGraphInput.reference(String)`, accessors `reference()/literal()/node()`.
  - `VFXGraphNode.id()/kind()/inputs()/exprSource()/mathOp()/curveTimes()/curveValues()/curveEasings()/bound()`, nested `enum MathOp { ADD, SUBTRACT, MULTIPLY, DIVIDE, MIN, MAX, POW, MOD }` with `fromString(String)`.

- [ ] **Step 1: Write the failing check**

Create `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.graph.VFXGraph;

public class Check {
	static int passed = 0;

	static void expectThrows(final String label, final Runnable runnable, final String expectedFragment) {
		try {
			runnable.run();
			throw new AssertionError(label + ": expected a parse error, got none");
		} catch (IllegalArgumentException e) {
			if (!e.getMessage().contains(expectedFragment)) {
				throw new AssertionError(label + ": message '" + e.getMessage() + "' does not contain '" + expectedFragment + "'");
			}
			passed++;
		}
	}

	static JsonObject graph(final String body) {
		return JsonParser.parseString("{\"version\":1," + body + "}").getAsJsonObject();
	}

	static void assertTrue(final String label, final boolean condition) {
		if (!condition) {
			throw new AssertionError(label);
		}
		passed++;
	}

	public static void main(final String[] args) {
		VFXGraph valid = VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"n1\",\"kind\":\"constant\",\"inputs\":{\"value\":2}},"
				+ "{\"id\":\"n2\",\"kind\":\"math\",\"op\":\"multiply\",\"inputs\":{\"a\":3}}],"
				+ "\"edges\":[{\"from\":\"n1\",\"to\":\"n2\",\"input\":\"b\"},"
				+ "{\"from\":\"n2\",\"to\":\"radius\"}]"));
		assertTrue("node count", valid.nodeCount() == 2);
		assertTrue("edge to effect input", "n2".equals(valid.effectInputRefs().get("radius")));
		assertTrue("topological order", "n2".equals(valid.nodes().get(1).id()));

		expectThrows("unknown kind", () -> VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"n1\",\"kind\":\"teleport\"}]")), "unknown kind 'teleport'");
		expectThrows("duplicate id", () -> VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"n1\",\"kind\":\"time\"},{\"id\":\"n1\",\"kind\":\"time\"}]")), "duplicate id");
		expectThrows("missing input", () -> VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"n3\",\"kind\":\"math\",\"op\":\"multiply\",\"inputs\":{\"a\":0.5}}]")),
			"input 'b' is not connected");
		expectThrows("cycle", () -> VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"a\",\"kind\":\"math\",\"op\":\"add\",\"inputs\":{\"a\":1,\"b\":{\"from\":\"b\"}}},"
				+ "{\"id\":\"b\",\"kind\":\"math\",\"op\":\"add\",\"inputs\":{\"a\":1,\"b\":{\"from\":\"a\"}}}]")),
			"cycle");
		expectThrows("unknown ref", () -> VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"a\",\"kind\":\"math\",\"op\":\"add\",\"inputs\":{\"a\":{\"from\":\"ghost\"},\"b\":1}}]")),
			"unknown node 'ghost'");
		expectThrows("bad version", () -> VFXGraph.parse("test:demo",
			JsonParser.parseString("{\"version\":9,\"nodes\":[{\"id\":\"a\",\"kind\":\"time\"}]}").getAsJsonObject()),
			"unsupported version 9");
		expectThrows("unknown input name", () -> VFXGraph.parse("test:demo", graph(
			"\"nodes\":[{\"id\":\"a\",\"kind\":\"clamp\",\"inputs\":{\"value\":1,\"bogus\":2}}]")),
			"unknown input 'bogus'");

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run (from the repo root; the classes do not exist yet, so `javac` reports "package dev.vfxweaver.graph does not exist"):

```powershell
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
```

Expected: non-zero exit, errors mentioning `dev.vfxweaver.graph.VFXGraph`.

- [ ] **Step 3: Create the node kind enum**

Create `src/main/java/dev/vfxweaver/graph/VFXNodeKind.java`:

```java
package dev.vfxweaver.graph;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The uniform-graph node kinds supported in format version 1 (spec §9 step 2a).
 * The logic set ({@code compare}/{@code boolean}/{@code if}/{@code switch}) and the subgraph
 * kind are deliberately absent: they land in step 2b, additively.
 */
public enum VFXNodeKind {
	CONSTANT("constant", List.of(), Set.of("value")),
	TIME("time", List.of(), Set.of("speed", "offset")),
	RANDOM("random", List.of(), Set.of("min", "max", "index")),
	NOISE("noise", List.of(), Set.of("x", "y", "z", "scale", "octaves", "gain", "lacunarity")),
	CURVE("curve", List.of("points"), Set.of("time")),
	MATH("math", List.of("a", "b"), Set.of("a", "b")),
	MIX("mix", List.of("a", "b"), Set.of("a", "b", "factor")),
	CLAMP("clamp", List.of("value"), Set.of("value", "min", "max")),
	REMAP("remap", List.of("value"), Set.of("value", "in_min", "in_max", "out_min", "out_max")),
	BIND("bind", List.of("bind"), Set.of("fallback")),
	EXPR("expr", List.of("expr"), Set.of());

	private final String id;
	private final List<String> requiredInputs;
	private final Set<String> numericInputs;

	VFXNodeKind(final String id, final List<String> requiredInputs, final Set<String> numericInputs) {
		this.id = id;
		this.requiredInputs = requiredInputs;
		this.numericInputs = numericInputs;
	}

	/** The datapack spelling of this kind. */
	public String id() {
		return this.id;
	}

	/**
	 * Input names that must be present (as a literal or a reference) or the file is refused.
	 * Structural fields ({@code points}, {@code expr}, {@code bind}) are listed here but are not
	 * edge targets — see {@link #acceptsNodeInput(String)}.
	 */
	public List<String> requiredInputs() {
		return this.requiredInputs;
	}

	/**
	 * True when {@code name} may be fed by a node edge or a {@code { "from": ... }} object.
	 */
	public boolean acceptsNodeInput(final String name) {
		return this.numericInputs.contains(name);
	}

	/**
	 * Resolves a kind from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "noise"}
	 * @return the matching kind, or {@code null} when unknown
	 */
	public static VFXNodeKind fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXNodeKind kind : values()) {
			if (kind.id.equals(normalized)) {
				return kind;
			}
		}
		return null;
	}
}
```

- [ ] **Step 4: Create the node input type**

Create `src/main/java/dev/vfxweaver/graph/VFXGraphInput.java`:

```java
package dev.vfxweaver.graph;

import org.jspecify.annotations.Nullable;

/**
 * One node input: either a literal number or a reference to the output of another node.
 *
 * @param reference true for a node reference, false for a literal
 * @param literal   the literal value (0 when {@code reference} is true)
 * @param node      the source node id, or {@code null} for a literal
 */
public record VFXGraphInput(boolean reference, float literal, @Nullable String node) {
	/**
	 * A constant input value.
	 */
	public static VFXGraphInput literal(final float value) {
		return new VFXGraphInput(false, value, null);
	}

	/**
	 * An input fed by another node's output.
	 */
	public static VFXGraphInput reference(final String nodeId) {
		return new VFXGraphInput(true, 0.0F, nodeId);
	}
}
```

- [ ] **Step 5: Create the parsed node type**

Create `src/main/java/dev/vfxweaver/graph/VFXGraphNode.java`:

```java
package dev.vfxweaver.graph;

import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.EasingFunction;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One parsed uniform-graph node. Kind-specific configuration lives in the nullable fields
 * below; a node only uses the fields matching its {@link #kind()}. Immutable after parse.
 */
public final class VFXGraphNode {
	/** The arithmetic operators accepted by a {@code math} node. */
	public enum MathOp {
		ADD("add"), SUBTRACT("subtract"), MULTIPLY("multiply"), DIVIDE("divide"),
		MIN("min"), MAX("max"), POW("pow"), MOD("mod");

		private final String id;

		MathOp(final String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		/**
		 * Resolves an operator from its datapack spelling.
		 *
		 * @param name raw string, e.g. {@code "multiply"}
		 * @return the matching operator, or {@code null} when unknown
		 */
		public static @Nullable MathOp fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final MathOp op : values()) {
				if (op.id.equalsIgnoreCase(name.trim())) {
					return op;
				}
			}
			return null;
		}
	}

	private final String id;
	private final VFXNodeKind kind;
	private final Map<String, VFXGraphInput> inputs;
	private final @Nullable String exprSource;
	private final @Nullable MathOp mathOp;
	private final float[] curveTimes;
	private final float[] curveValues;
	private final EasingFunction[] curveEasings;
	private final @Nullable BoundParam bound;

	VFXGraphNode(final String id, final VFXNodeKind kind, final Map<String, VFXGraphInput> inputs, final @Nullable String exprSource, final @Nullable MathOp mathOp, final float[] curveTimes, final float[] curveValues, final EasingFunction[] curveEasings, final @Nullable BoundParam bound) {
		this.id = id;
		this.kind = kind;
		this.inputs = inputs;
		this.exprSource = exprSource;
		this.mathOp = mathOp;
		this.curveTimes = curveTimes;
		this.curveValues = curveValues;
		this.curveEasings = curveEasings;
		this.bound = bound;
	}

	public String id() {
		return this.id;
	}

	public VFXNodeKind kind() {
		return this.kind;
	}

	public Map<String, VFXGraphInput> inputs() {
		return this.inputs;
	}

	public @Nullable String exprSource() {
		return this.exprSource;
	}

	public @Nullable MathOp mathOp() {
		return this.mathOp;
	}

	/** Curve control-point times in ascending order; empty for non-curve nodes. */
	public float[] curveTimes() {
		return this.curveTimes;
	}

	/** Curve control-point values; empty for non-curve nodes. */
	public float[] curveValues() {
		return this.curveValues;
	}

	/** Per-segment easing (segment i eases from point i to point i+1); empty for non-curve nodes. */
	public EasingFunction[] curveEasings() {
		return this.curveEasings;
	}

	public @Nullable BoundParam bound() {
		return this.bound;
	}
}
```

- [ ] **Step 6: Add `BoundParam.parse` and delegate `VFXDefinition.parseBound` to it**

In `src/main/java/dev/vfxweaver/effect/BoundParam.java`, add the imports and the method, at the end of the record body (after the `Kind` enum is fine; place it before `Kind` if preferred — it only uses `Kind`, `JsonObject`, `JsonArray`, `JsonElement`):

```java
	import com.google.gson.JsonArray;
	import com.google.gson.JsonElement;
	import com.google.gson.JsonObject;
```

```java
	/**
	 * Parses a {@code {"bind": "...", ...}} object with plain Gson (no Minecraft types), so the
	 * graph module can reuse it while staying MC-free.
	 *
	 * @param object the binding object, e.g. {@code {"bind":"proximity","pos":[0,0,0],"range":8}}
	 * @throws IllegalArgumentException when a required field is missing or malformed
	 */
	public static BoundParam parse(final JsonObject object) {
		final Kind kind = Kind.fromString(str(object, "bind", ""));
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		if (kind.needsPos()) {
			final JsonElement posElement = object.get("pos");
			if (posElement == null || !posElement.isJsonArray() || posElement.getAsJsonArray().size() != 3) {
				throw new IllegalArgumentException("Binding 'pos' must be an array of [x, y, z]: " + object);
			}
			final JsonArray pos = posElement.getAsJsonArray();
			x = pos.get(0).getAsDouble();
			y = pos.get(1).getAsDouble();
			z = pos.get(2).getAsDouble();
		}
		String objective = null;
		String holder = null;
		if (kind == Kind.SCOREBOARD) {
			objective = str(object, "objective", "");
			if (objective.isBlank()) {
				throw new IllegalArgumentException("Binding 'scoreboard' needs a non-blank 'objective': " + object);
			}
			final JsonElement holderElement = object.get("holder");
			holder = holderElement != null && !holderElement.isJsonNull() ? holderElement.getAsString() : null;
			if (holder != null && holder.isBlank()) {
				holder = null;
			}
		}
		final float defaultRange = switch (kind) {
			case LOOK, LOOK_AT -> 90.0F;
			case SPEED -> 5.0F;
			case SCOREBOARD -> 16.0F;
			default -> 16.0F;
		};
		final float range = flt(object, "range", defaultRange);
		final boolean invert = boo(object, "invert", false);
		final float scale = flt(object, "scale", 1.0F);
		final float yaw = flt(object, "yaw", 0.0F);
		final float pitch = flt(object, "pitch", 0.0F);
		return new BoundParam(kind, x, y, z, yaw, pitch, range, invert, scale, objective, holder);
	}

	private static String str(final JsonObject object, final String key, final String fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static float flt(final JsonObject object, final String key, final float fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsFloat() : fallback;
	}

	private static boolean boo(final JsonObject object, final String key, final boolean fallback) {
		final JsonElement element = object.get(key);
		return element != null && !element.isJsonNull() ? element.getAsBoolean() : fallback;
	}
```

Then replace the whole body of the existing `private static BoundParam parseBound(final JsonObject object)` in `VFXDefinition.java` (lines 479–518) with a one-line delegation, keeping the same signature so `parseParam` and the `multiply` branch are untouched:

```java
	private static BoundParam parseBound(final JsonObject object) {
		return BoundParam.parse(object);
	}
```

- [ ] **Step 7: Create the graph parser**

Create `src/main/java/dev/vfxweaver/graph/VFXGraph.java`:

```java
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
				if (!node.inputs().containsKey(required)) {
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
```

- [ ] **Step 8: Build the active node and run the check**

Run (build first so the classes exist, then compile and run the check):

```powershell
.\gradlew.bat :26.1.2:build --console=plain
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
& 'C:\Program Files\Java\jdk-26\bin\java.exe' -cp "$classes;$mc;$gson;$env:TEMP\vfxcheck" Check
```

Expected: `BUILD SUCCESSFUL` then `Check OK: 11 assertions`.

- [ ] **Step 9: Confirm per-file error isolation**

Read `src/main/java/dev/vfxweaver/resource/VFXDefinitionManager.java:137-155` (`reload`) and `:166-185` (`registerLocal`). Both catch `JsonParseException | IllegalStateException | IllegalArgumentException` per file and continue; `VFXGraph.parse` throws `IllegalArgumentException` only. No code change is needed — record in the commit message that a bad graph fails that file only.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/dev/vfxweaver/graph/VFXNodeKind.java src/main/java/dev/vfxweaver/graph/VFXGraphInput.java src/main/java/dev/vfxweaver/graph/VFXGraphNode.java src/main/java/dev/vfxweaver/graph/VFXGraph.java src/main/java/dev/vfxweaver/effect/BoundParam.java src/main/java/dev/vfxweaver/effect/VFXDefinition.java
git commit -m "feat(graph): parse and validate the uniform-graph format (step 2a)"
```

---

### Task 2: CPU graph evaluator

**Files:**
- Create: `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXWorldBindings.java` (add `currentFrame()`)
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the evaluator check)

**Interfaces:**
- Consumes: `VFXGraph.nodes()/indexOf(String)/nodeCount()`, `VFXGraphNode` accessors from Task 1, `SimplexNoise.noise(double,double,double)`, `MathExpression.compile(long,String)`/`eval(float,float,float,float)`, `VFXWorldBindings.evaluate(BoundParam,float)`.
- Produces:
  - `VFXGraphEvaluator(VFXGraph graph, long seed)`.
  - `beginFrame(float elapsedTicks) : void` — reads the camera snapshot and invalidates the frame memo.
  - `evaluate(String nodeId, float fallback) : float` — pull with per-frame memo; allocation-free after construction.
  - `VFXWorldBindings.currentFrame() : @Nullable VFXWorldBindings.Frame`.

- [ ] **Step 1: Write the failing check**

Overwrite `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;

public class Check {
	static int passed = 0;

	static VFXGraphEvaluator evaluator(final String nodes, final long seed) {
		final JsonObject json = JsonParser.parseString("{\"version\":1,\"nodes\":" + nodes + "}").getAsJsonObject();
		return new VFXGraphEvaluator(VFXGraph.parse("test:eval", json), seed);
	}

	static void expect(final String label, final float actual, final float expected) {
		if (Math.abs(actual - expected) > 1.0e-4F) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
		passed++;
	}

	static void assertTrue(final String label, final boolean condition) {
		if (!condition) {
			throw new AssertionError(label);
		}
		passed++;
	}

	public static void main(final String[] args) {
		final VFXGraphEvaluator constant = evaluator("[{\"id\":\"c\",\"kind\":\"constant\",\"inputs\":{\"value\":3}}]", 1L);
		constant.beginFrame(0.0F);
		expect("constant", constant.evaluate("c", -1.0F), 3.0F);

		final VFXGraphEvaluator time = evaluator("[{\"id\":\"t\",\"kind\":\"time\",\"inputs\":{\"speed\":2,\"offset\":1}}]", 1L);
		time.beginFrame(5.0F);
		expect("time", time.evaluate("t", -1.0F), 11.0F);

		final VFXGraphEvaluator math = evaluator(
			"[{\"id\":\"a\",\"kind\":\"constant\",\"inputs\":{\"value\":2}},"
				+ "{\"id\":\"b\",\"kind\":\"constant\",\"inputs\":{\"value\":3}},"
				+ "{\"id\":\"m\",\"kind\":\"math\",\"op\":\"multiply\",\"inputs\":{\"a\":{\"from\":\"a\"},\"b\":{\"from\":\"b\"}}}]", 1L);
		math.beginFrame(0.0F);
		expect("math.multiply", math.evaluate("m", -1.0F), 6.0F);

		final VFXGraphEvaluator mix = evaluator("[{\"id\":\"m\",\"kind\":\"mix\",\"inputs\":{\"a\":0,\"b\":10,\"factor\":0.25}}]", 1L);
		mix.beginFrame(0.0F);
		expect("mix", mix.evaluate("m", -1.0F), 2.5F);

		final VFXGraphEvaluator clamp = evaluator("[{\"id\":\"c\",\"kind\":\"clamp\",\"inputs\":{\"value\":5,\"min\":0,\"max\":1}}]", 1L);
		clamp.beginFrame(0.0F);
		expect("clamp", clamp.evaluate("c", -1.0F), 1.0F);

		final VFXGraphEvaluator remap = evaluator("[{\"id\":\"r\",\"kind\":\"remap\",\"inputs\":{\"value\":5,\"in_min\":0,\"in_max\":10,\"out_min\":0,\"out_max\":100}}]", 1L);
		remap.beginFrame(0.0F);
		expect("remap", remap.evaluate("r", -1.0F), 50.0F);

		final VFXGraphEvaluator curve = evaluator("[{\"id\":\"k\",\"kind\":\"curve\",\"inputs\":{\"points\":[{\"time\":0,\"value\":0},{\"time\":10,\"value\":10}]}}]", 1L);
		curve.beginFrame(5.0F);
		expect("curve", curve.evaluate("k", -1.0F), 5.0F);

		final VFXGraphEvaluator expr = evaluator("[{\"id\":\"e\",\"kind\":\"expr\",\"inputs\":{\"expr\":\"t * 2\"}}]", 1L);
		expr.beginFrame(4.0F);
		expect("expr", expr.evaluate("e", -1.0F), 8.0F);

		final VFXGraphEvaluator noise = evaluator("[{\"id\":\"n\",\"kind\":\"noise\",\"inputs\":{\"x\":0,\"y\":0,\"z\":0,\"scale\":1}}]", 1L);
		noise.beginFrame(0.0F);
		expect("noise(0,0,0)", noise.evaluate("n", -1.0F), 0.0F);

		final VFXGraphEvaluator random = evaluator("[{\"id\":\"r\",\"kind\":\"random\",\"inputs\":{\"min\":0,\"max\":1}}]", 42L);
		random.beginFrame(0.0F);
		final float first = random.evaluate("r", -1.0F);
		random.beginFrame(1.0F);
		final float second = random.evaluate("r", -1.0F);
		expect("random stable", first, second);
		assertTrue("random range", first >= 0.0F && first <= 1.0F);

		final VFXGraphEvaluator bind = evaluator("[{\"id\":\"b\",\"kind\":\"bind\",\"inputs\":{\"bind\":\"proximity\",\"pos\":[0,0,0],\"range\":8}}]", 1L);
		bind.beginFrame(0.0F);
		expect("bind fallback without camera", bind.evaluate("b", 7.0F), 7.0F);

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:

```powershell
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$joml = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.joml\joml" -Recurse -Filter "joml-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson;$joml" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
```

Expected: `javac` errors on `dev.vfxweaver.graph.VFXGraphEvaluator`.

- [ ] **Step 3: Add the no-allocation camera accessor**

In `src/main/java/dev/vfxweaver/effect/VFXWorldBindings.java`, add after `cameraPosition()` (around line 201):

```java
	/**
	 * The camera snapshot published for the current frame, or {@code null} when none is available
	 * (e.g. on a dedicated server). Read without allocation by the uniform-graph evaluator.
	 */
	public static @Nullable Frame currentFrame() {
		return frame;
	}
```

- [ ] **Step 4: Create the evaluator**

Create `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java`:

```java
package dev.vfxweaver.graph;

import dev.vfxweaver.effect.MathExpression;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.noise.SimplexNoise;
import java.util.Arrays;

/**
 * Pull-based CPU evaluator for one {@link VFXGraph} instance. Created per running effect (in the
 * {@code VFXTimeline}), it evaluates each node at most once per frame using an epoch-stamped memo:
 * {@link #beginFrame(float)} invalidates the previous frame, and shared nodes are computed once
 * no matter how many inputs reference them. After construction the hot path allocates nothing
 * itself (the existing {@code MathExpression} and {@code VFXWorldBindings} implementations do
 * allocate internally, unchanged, for {@code expr} and spatial {@code bind} nodes).
 */
public final class VFXGraphEvaluator {
	private final VFXGraph graph;
	private final float[] cache;
	private final int[] stamp;
	private final MathExpression[] expressions;
	private final long seed;
	private int epoch;
	private float elapsed;
	private float camX;
	private float camY;
	private float camZ;

	/**
	 * Creates an evaluator for one effect instance.
	 *
	 * @param graph the parsed graph
	 * @param seed  per-instance seed driving {@code random} and {@code expr}'s {@code random()}
	 */
	public VFXGraphEvaluator(final VFXGraph graph, final long seed) {
		this.graph = graph;
		this.seed = seed;
		this.cache = new float[graph.nodeCount()];
		this.stamp = new int[graph.nodeCount()];
		Arrays.fill(this.stamp, -1);
		this.expressions = new MathExpression[graph.nodeCount()];
		for (int i = 0; i < graph.nodeCount(); i++) {
			final VFXGraphNode node = graph.nodes().get(i);
			if (node.kind() == VFXNodeKind.EXPR) {
				this.expressions[i] = MathExpression.compile(seed, node.exprSource());
			}
		}
	}

	/**
	 * Starts a new frame: captures the camera snapshot and invalidates the memo so every node is
	 * evaluated once against the new {@code elapsedTicks}.
	 *
	 * @param elapsedTicks elapsed effect time in ticks (same value the timeline animates on)
	 */
	public void beginFrame(final float elapsedTicks) {
		this.elapsed = elapsedTicks;
		final VFXWorldBindings.Frame frame = VFXWorldBindings.currentFrame();
		if (frame != null) {
			this.camX = frame.camX();
			this.camY = frame.camY();
			this.camZ = frame.camZ();
		} else {
			this.camX = 0.0F;
			this.camY = 0.0F;
			this.camZ = 0.0F;
		}
		if (++this.epoch == 0) {
			Arrays.fill(this.stamp, -1);
			this.epoch = 1;
		}
	}

	/**
	 * Evaluates a node for the current frame.
	 *
	 * @param nodeId   stable node id
	 * @param fallback value returned when the node does not exist
	 */
	public float evaluate(final String nodeId, final float fallback) {
		final Integer index = this.graph.indexOf(nodeId);
		return index == null ? fallback : eval(index);
	}

	private float eval(final int index) {
		if (this.stamp[index] == this.epoch) {
			return this.cache[index];
		}
		final float value = compute(this.graph.nodes().get(index), index);
		this.stamp[index] = this.epoch;
		this.cache[index] = value;
		return value;
	}

	private float input(final VFXGraphNode node, final String name, final float fallback) {
		final VFXGraphInput in = node.inputs().get(name);
		if (in == null) {
			return fallback;
		}
		if (!in.reference()) {
			return in.literal();
		}
		final Integer index = this.graph.indexOf(in.node());
		return index == null ? fallback : eval(index);
	}

	private float compute(final VFXGraphNode node, final int index) {
		return switch (node.kind()) {
			case CONSTANT -> input(node, "value", 0.0F);
			case TIME -> this.elapsed * input(node, "speed", 1.0F) + input(node, "offset", 0.0F);
			case RANDOM -> {
				final float min = input(node, "min", 0.0F);
				final float max = input(node, "max", 1.0F);
				final float indexValue = input(node, "index", 0.0F);
				yield min + (max - min) * hash01(this.seed, (long) indexValue);
			}
			case NOISE -> {
				final float x = input(node, "x", this.elapsed);
				final float y = input(node, "y", 0.0F);
				final float z = input(node, "z", 0.0F);
				final float scale = input(node, "scale", 1.0F);
				final int octaves = Math.max(1, Math.min(VFXGraph.MAX_NOISE_OCTAVES, Math.round(input(node, "octaves", 1.0F))));
				final float gain = input(node, "gain", 0.5F);
				final float lacunarity = input(node, "lacunarity", 2.0F);
				double sum = 0.0;
				double amplitude = 1.0;
				double frequency = 1.0;
				double norm = 0.0;
				for (int i = 0; i < octaves; i++) {
					sum += amplitude * SimplexNoise.noise(x * scale * frequency, y * scale * frequency, z * scale * frequency);
					norm += amplitude;
					amplitude *= gain;
					frequency *= lacunarity;
				}
				yield norm > 0.0 ? (float) (sum / norm) : 0.0F;
			}
			case CURVE -> curve(node, input(node, "time", this.elapsed));
			case MATH -> math(node);
			case MIX -> {
				final float a = input(node, "a", 0.0F);
				final float b = input(node, "b", 0.0F);
				yield a + (b - a) * input(node, "factor", 0.5F);
			}
			case CLAMP -> {
				final float value = input(node, "value", 0.0F);
				final float min = input(node, "min", 0.0F);
				final float max = input(node, "max", 1.0F);
				yield Math.max(min, Math.min(max, value));
			}
			case REMAP -> {
				final float value = input(node, "value", 0.0F);
				final float inMin = input(node, "in_min", 0.0F);
				final float inMax = input(node, "in_max", 1.0F);
				final float outMin = input(node, "out_min", 0.0F);
				final float outMax = input(node, "out_max", 1.0F);
				final float span = inMax - inMin;
				yield span == 0.0F ? outMin : outMin + (value - inMin) / span * (outMax - outMin);
			}
			case BIND -> VFXWorldBindings.evaluate(node.bound(), input(node, "fallback", 0.0F));
			case EXPR -> {
				final MathExpression expression = this.expressions[index];
				yield expression == null ? 0.0F : expression.eval(this.elapsed, this.camX, this.camY, this.camZ);
			}
		};
	}

	private float math(final VFXGraphNode node) {
		final float a = input(node, "a", 0.0F);
		final float b = input(node, "b", 0.0F);
		return switch (node.mathOp()) {
			case ADD -> a + b;
			case SUBTRACT -> a - b;
			case MULTIPLY -> a * b;
			case DIVIDE -> b == 0.0F ? 0.0F : a / b;
			case MIN -> Math.min(a, b);
			case MAX -> Math.max(a, b);
			case POW -> (float) Math.pow(a, b);
			case MOD -> b == 0.0F ? 0.0F : ((a % b) + b) % b;
		};
	}

	private static float curve(final VFXGraphNode node, final float time) {
		final float[] times = node.curveTimes();
		final float[] values = node.curveValues();
		if (time <= times[0]) {
			return values[0];
		}
		if (time >= times[times.length - 1]) {
			return values[values.length - 1];
		}
		for (int i = 0; i < times.length - 1; i++) {
			if (time >= times[i] && time <= times[i + 1]) {
				final float span = times[i + 1] - times[i];
				final float local = span <= 0.0F ? 1.0F : (time - times[i]) / span;
				return values[i] + (values[i + 1] - values[i]) * node.curveEasings()[i].apply(local);
			}
		}
		return values[values.length - 1];
	}

	private static float hash01(final long seed, final long index) {
		long h = seed ^ (index * 0x9E3779B97F4A7C15L);
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h = h ^ (h >>> 31);
		return (h & 0xFFFFFFFFL) / (float) 0x100000000L;
	}
}
```

- [ ] **Step 5: Build the active node and run the check**

Run:

```powershell
.\gradlew.bat :26.1.2:build --console=plain
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$joml = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.joml\joml" -Recurse -Filter "joml-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson;$joml" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
& 'C:\Program Files\Java\jdk-26\bin\java.exe' -cp "$classes;$mc;$gson;$joml;$env:TEMP\vfxcheck" Check
```

Expected: `BUILD SUCCESSFUL` then `Check OK: 12 assertions`.

- [ ] **Step 6: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. The evaluator is loader- and version-agnostic, so a failure here means an accidental `net.minecraft.*` import in `dev.vfxweaver.graph` — fix the import, not the build.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java src/main/java/dev/vfxweaver/effect/VFXWorldBindings.java
git commit -m "feat(graph): per-instance CPU evaluator with per-frame memo"
```

---

### Task 3: Wire `inputs` and `{ "from": node }` into the effect timeline

**Files:**
- Modify: `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXTimeline.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXActiveEffect.java:161-170`
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the wiring check)

**Interfaces:**
- Consumes: `VFXGraph.parse`/`node`/`effectInputRefs` (Task 1), `VFXGraphEvaluator` (Task 2).
- Produces:
  - `VFXDefinition.getGraph() : @Nullable VFXGraph`, `VFXDefinition.getGraphInputs() : Map<String, String>`.
  - `VFXTimeline(float, Map<String,AnimatedValue>, Map<String,BoundParam>, Map<String,BoundParam>, Map<String,MathExpression>, @Nullable VFXGraph, Map<String,String>, long)`.
  - `VFXTimeline.updateGraph(float nowTicks) : void`.
  - `VFXTimeline.getGraph() : @Nullable VFXGraph`.
  - `VFXTimeline.getValue(String, float)` resolves graph inputs after runtime overrides and before bindings.

- [ ] **Step 1: Write the failing check**

Overwrite `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXTimeline;
import java.util.Map;
import net.minecraft.resources.Identifier;

public class Check {
	public static void main(final String[] args) {
		final String json = "{"
			+ "\"type\":\"blur\",\"duration\":40,"
			+ "\"params\":{\"radius\":2.0},"
			+ "\"graph\":{\"version\":1,\"nodes\":["
			+ "{\"id\":\"half\",\"kind\":\"constant\",\"inputs\":{\"value\":6.0}}],"
			+ "\"edges\":[]},"
			+ "\"inputs\":{\"radius\":{\"from\":\"half\"}}}";
		final VFXDefinition definition = VFXDefinition.parse(Identifier.fromNamespaceAndPath("test", "graph_demo"),
			JsonParser.parseString(json).getAsJsonObject());
		if (definition.getGraph() == null) {
			throw new AssertionError("graph was not parsed");
		}
		if (definition.getGraph().nodeCount() != 1) {
			throw new AssertionError("graph node count");
		}
		if (!"half".equals(definition.getGraphInputs().get("radius"))) {
			throw new AssertionError("inputs block was not parsed: " + definition.getGraphInputs());
		}
		final VFXTimeline timeline = definition.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 7L);
		timeline.updateGraph(0.0F);
		final float value = timeline.getValue("radius", -1.0F);
		if (Math.abs(value - 6.0F) > 1.0e-4F) {
			throw new AssertionError("graph did not drive radius: " + value);
		}
		System.out.println("Check OK: graph input drives 'radius' = " + value);
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:

```powershell
.\gradlew.bat :26.1.2:build --console=plain
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$joml = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.joml\joml" -Recurse -Filter "joml-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson;$joml" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
```

Expected: `javac` errors on `definition.getGraph()` / `timeline.updateGraph(...)`.

- [ ] **Step 3: Add `getGraph`/`getGraphInputs` and the graph-aware `VFXTimeline` constructor**

In `src/main/java/dev/vfxweaver/effect/VFXTimeline.java`:

Add imports:

```java
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
```

Add fields next to the existing maps:

```java
	private final @Nullable VFXGraph graph;
	private final Map<String, String> graphInputs;
	private final @Nullable VFXGraphEvaluator graphEvaluator;
```

Replace the body of the existing fullest (5-argument) constructor with a delegation, and add the graph-aware constructor. The existing 5-arg constructor (currently lines 78–85) becomes:

```java
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values, final Map<String, BoundParam> bindings, final Map<String, BoundParam> multipliers, final Map<String, MathExpression> expressions) {
		this(duration, values, bindings, multipliers, expressions, null, Map.of(), 0L);
	}
```

Add this new constructor immediately after it:

```java
	/**
	 * Creates a timeline that also drives a uniform graph.
	 *
	 * @param graph       the definition's graph, or {@code null} for a definition without one
	 * @param graphInputs effect input name to source node id (inputs block plus graph edges)
	 * @param graphSeed   per-instance seed passed to the graph evaluator
	 */
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values, final Map<String, BoundParam> bindings, final Map<String, BoundParam> multipliers, final Map<String, MathExpression> expressions, final @Nullable VFXGraph graph, final Map<String, String> graphInputs, final long graphSeed) {
		this.duration = duration;
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
		this.multipliers = Collections.unmodifiableMap(new LinkedHashMap<>(multipliers));
		this.expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
		this.graph = graph;
		this.graphInputs = Map.copyOf(graphInputs);
		this.graphEvaluator = graph == null ? null : new VFXGraphEvaluator(graph, graphSeed);
		this.elapsed = 0.0F;
	}
```

Add the update/graph getter after `update(float now)`:

```java
	/**
	 * Advances the graph to the current frame. Call once per frame, after {@link #update(float)};
	 * a no-op when the definition has no graph.
	 *
	 * @param now elapsed effect time in ticks (the same value passed to {@link #update(float)})
	 */
	public void updateGraph(final float now) {
		if (this.graphEvaluator != null) {
			this.graphEvaluator.beginFrame(now);
		}
	}

	/**
	 * The definition's uniform graph, or {@code null}.
	 */
	public @Nullable VFXGraph getGraph() {
		return this.graph;
	}
```

In `getValue(String name, float fallback)`, insert the graph branch between the override check and the binding lookup:

```java
	public float getValue(final String name, final float fallback) {
		AnimatedValue override = this.overrides.get(name);
		if (override != null) {
			return override.get();
		}
		final String graphNode = this.graphInputs.get(name);
		if (graphNode != null && this.graphEvaluator != null) {
			return this.graphEvaluator.evaluate(graphNode, fallback);
		}
		BoundParam binding = this.bindings.get(name);
		// ... rest of the method unchanged
```

- [ ] **Step 4: Parse `graph` and `inputs` in `VFXDefinition`**

In `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`:

Add imports:

```java
import dev.vfxweaver.graph.VFXGraph;
```

Add fields after `itemId`:

```java
	private final @Nullable VFXGraph graph;
	private final Map<String, String> graphInputs;
```

Add two parameters to the private constructor signature (after `itemId`) and assign them:

```java
		final @Nullable VFXGraph graph,
		final Map<String, String> graphInputs
```

```java
		this.graph = graph;
		this.graphInputs = Map.copyOf(graphInputs);
```

Update the single `new VFXDefinition(...)` in the 12-argument `create(...)` (currently line 149) to append `null, Map.of()` as the last two arguments.

Update `withParams(...)` (currently line 532) to append `this.graph, this.graphInputs`.

In `parse(...)`, after the `sound_pos` block (currently ends at line 190) and before the `children` block, insert:

```java
		// Optional graph (spec §3.1) and inputs (spec §3.2). Both are additive: a definition
		// without them behaves exactly as before, and an older mod ignores them entirely
		// because graph references never live inside "params".
		VFXGraph graph = null;
		if (json.has("graph") && !json.get("graph").isJsonNull()) {
			graph = VFXGraph.parse(id.toString(), GsonHelper.getAsJsonObject(json, "graph"));
		}
		Map<String, String> graphInputs = new LinkedHashMap<>();
		if (json.has("inputs") && !json.get("inputs").isJsonNull()) {
			JsonObject inputsJson = GsonHelper.getAsJsonObject(json, "inputs");
			for (Map.Entry<String, JsonElement> entry : inputsJson.entrySet()) {
				String name = entry.getKey();
				JsonElement value = entry.getValue();
				if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
					params.put(name, ParamSpec.constant(value.getAsFloat()));
					continue;
				}
				if (value.isJsonObject()) {
					JsonObject object = value.getAsJsonObject();
					if (object.has("from")) {
						String nodeId = GsonHelper.getAsString(object, "from");
						if (graph == null || graph.node(nodeId) == null) {
							throw new IllegalArgumentException("input '" + name + "': graph node '" + nodeId + "' does not exist");
						}
						graphInputs.put(name, nodeId);
						params.putIfAbsent(name, ParamSpec.constant(0.0F));
						continue;
					}
					if (object.has("field")) {
						throw new IllegalArgumentException("input '" + name + "': field functions are not implemented yet (spec step 4)");
					}
				}
				throw new IllegalArgumentException("input '" + name + "' must be a number or { \"from\": \"<node>\" }");
			}
		}
		if (graph != null) {
			for (Map.Entry<String, String> entry : graph.effectInputRefs().entrySet()) {
				if (graphInputs.containsKey(entry.getKey())) {
					throw new IllegalArgumentException("input '" + entry.getKey() + "': wired twice (inputs block and graph edge)");
				}
				graphInputs.put(entry.getKey(), entry.getValue());
				params.putIfAbsent(entry.getKey(), ParamSpec.constant(0.0F));
			}
		}
```

Update the `return new VFXDefinition(...)` in `parse` (currently line 228) to append `graph, graphInputs`.

In `createTimeline(...)`, change the final `return new VFXTimeline(...)` (currently line 603) to pass the graph:

```java
		return new VFXTimeline(duration, values, bindings, multipliers, expressions, this.graph, this.graphInputs, instanceSeed);
```

Add the accessors near the other getters:

```java
	/**
	 * The optional uniform graph declared by this definition, or {@code null} when it has none.
	 */
	public @Nullable VFXGraph getGraph() {
		return this.graph;
	}

	/**
	 * Effect input name to source node id, from the {@code inputs} block and from graph edges
	 * whose target is an effect input. Empty when the definition has no graph wiring.
	 */
	public Map<String, String> getGraphInputs() {
		return this.graphInputs;
	}
```

- [ ] **Step 5: Drive the graph once per frame from the instance**

In `src/main/java/dev/vfxweaver/effect/VFXActiveEffect.java`, in `update(final float now)` (currently lines 161–170), add the graph update after the timeline update:

```java
		this.timeline.update(this.elapsed);
		this.timeline.updateGraph(this.elapsed);
```

- [ ] **Step 6: Build the active node and run the check**

Run:

```powershell
.\gradlew.bat :26.1.2:build --console=plain
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$joml = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.joml\joml" -Recurse -Filter "joml-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson;$joml" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
& 'C:\Program Files\Java\jdk-26\bin\java.exe' -cp "$classes;$mc;$gson;$joml;$env:TEMP\vfxcheck" Check
```

Expected: `BUILD SUCCESSFUL` then `Check OK: graph input drives 'radius' = 6.0`.

- [ ] **Step 7: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. If `VFXDefinition.parseBound`'s delegation changed behavior, the build still compiles; the in-game reload in Task 5 is the behavior check for existing bind params.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/vfxweaver/effect/VFXDefinition.java src/main/java/dev/vfxweaver/effect/VFXTimeline.java src/main/java/dev/vfxweaver/effect/VFXActiveEffect.java
git commit -m "feat(graph): wire {from:node} inputs into the effect timeline"
```

---

### Task 4: Prove the backward-compatibility contract

**Files:**
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the compatibility check)
- Modify: none (this task is a proof; if it finds a regression, fix it in Task 3's files)

**Interfaces:**
- Consumes: `VFXDefinition.parse`, `getGraph`, `createTimeline(float,Map,EasingFunction,long)`, `VFXTimeline.update(float)`, `updateGraph(float)`, `getValue(String,float)`.
- Produces: a runnable assertion that definitions without `graph`/`inputs` are unchanged and that a newer-datapack file still parses to today's params-only behavior when the graph layers are absent.

- [ ] **Step 1: Write the contract check**

Overwrite `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXTimeline;
import java.util.Map;
import net.minecraft.resources.Identifier;

public class Check {
	static VFXDefinition parse(final String json) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath("test", "compat"),
			JsonParser.parseString(json).getAsJsonObject());
	}

	public static void main(final String[] args) {
		// 1. Today's blur.json shape: an animated param, no graph, no inputs. Unchanged.
		final VFXDefinition plain = parse("{\"type\":\"blur\",\"duration\":40,\"easing\":\"ease_in_out_cubic\","
			+ "\"params\":{\"radius\":{\"start\":4.0,\"end\":0.0}}}");
		final VFXTimeline plainTimeline = plain.createTimeline(40.0F, Map.of(), plain.getDefaultEasing(), 5L);
		plainTimeline.update(0.0F);
		final float start = plainTimeline.getValue("radius", -1.0F);
		plainTimeline.update(40.0F);
		final float end = plainTimeline.getValue("radius", -1.0F);
		if (Math.abs(start - 4.0F) > 1.0e-4F || Math.abs(end) > 1.0e-4F) {
			throw new AssertionError("plain definition changed: start=" + start + " end=" + end);
		}
		if (plain.getGraph() != null || !plain.getGraphInputs().isEmpty()) {
			throw new AssertionError("plain definition gained graph state");
		}

		// 2. The same physical file, as an older mod sees it: the "graph"/"inputs" blocks are
		//    unknown top-level keys and never reach a required field, so parsing succeeds and the
		//    effect runs on its params-only defaults.
		final VFXDefinition newer = parse("{\"type\":\"blur\",\"duration\":40,"
			+ "\"params\":{\"radius\":2.0},"
			+ "\"graph\":{\"version\":1,\"nodes\":[{\"id\":\"x\",\"kind\":\"constant\",\"inputs\":{\"value\":9}}]},"
			+ "\"inputs\":{\"radius\":{\"from\":\"x\"}},"
			+ "\"a_future_block\":{\"anything\":true}}");
		final VFXTimeline olderView = newer.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 5L);
		olderView.update(0.0F);
		// The graph layer drives the new mod...
		olderView.updateGraph(0.0F);
		if (Math.abs(olderView.getValue("radius", -1.0F) - 9.0F) > 1.0e-4F) {
			throw new AssertionError("graph input did not drive radius");
		}
		// ...while a definition that never had the layers still resolves its numeric default.
		final VFXDefinition fallback = parse("{\"type\":\"blur\",\"duration\":40,\"params\":{\"radius\":2.0}}");
		final VFXTimeline fallbackTimeline = fallback.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 5L);
		fallbackTimeline.update(0.0F);
		if (Math.abs(fallbackTimeline.getValue("radius", -1.0F) - 2.0F) > 1.0e-4F) {
			throw new AssertionError("params-only fallback changed: " + fallbackTimeline.getValue("radius", -1.0F));
		}

		System.out.println("Check OK: backward-compatibility contract holds");
	}
}
```

- [ ] **Step 2: Build and run it**

Run:

```powershell
.\gradlew.bat :26.1.2:build --console=plain
$mc = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" -Recurse -Filter "minecraft-clientonly-deobf-26.1.2.jar" | Select-Object -First 1).FullName
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Select-Object -First 1).FullName
$joml = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.joml\joml" -Recurse -Filter "joml-*.jar" | Select-Object -First 1).FullName
$classes = "versions\26.1.2\build\classes\java\main"
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$classes;$mc;$gson;$joml" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
& 'C:\Program Files\Java\jdk-26\bin\java.exe' -cp "$classes;$mc;$gson;$joml;$env:TEMP\vfxcheck" Check
```

Expected: `BUILD SUCCESSFUL` then `Check OK: backward-compatibility contract holds`.

- [ ] **Step 3: Commit**

```bash
git commit --allow-empty -m "test(graph): prove the backward-compatibility contract for graph/inputs"
```

---

### Task 5: Ship a minimal consumer and verify it in-game

**Files:**
- Create: `src/main/resources/data/vfxweaver/vfx/graph_demo.json`
- Modify: none in Java (the consumer reuses the existing `blur` post pass and its `radius` param, registered at `VFXShaderPrograms.java:80` and read at `VFXPostProcessingManager.java:356`)

**Interfaces:**
- Consumes: everything from Tasks 1–3; the existing `blur` effect's `radius` parameter (`registerMultiPass(VFXEffectType.BLUR, List.of("blur_x","blur_y"), List.of(new String[]{"radius"}, new String[]{"radius"}))`).
- Produces: `/vfx play graph_demo` — a built-in effect whose blur radius is driven by a `time → curve` graph.

- [ ] **Step 1: Add the built-in consumer effect**

Create `src/main/resources/data/vfxweaver/vfx/graph_demo.json`:

```json
{
  "type": "blur",
  "duration": 400,
  "loop": true,
  "persistent": true,
  "fade_ticks": 20,
  "params": {
    "radius": 2.0
  },
  "graph": {
    "version": 1,
    "nodes": [
      { "id": "phase", "kind": "time", "inputs": { "speed": 0.25 } },
      {
        "id": "pulse",
        "kind": "curve",
        "inputs": {
          "points": [
            { "time": 0, "value": 0.0 },
            { "time": 50, "value": 8.0 },
            { "time": 100, "value": 0.0 }
          ]
        }
      }
    ],
    "edges": [
      { "from": "phase", "to": "pulse", "input": "time" }
    ]
  },
  "inputs": {
    "radius": { "from": "pulse" }
  },
  "meta": {
    "phase": { "pos": [40, 60] },
    "pulse": { "pos": [200, 60] }
  }
}
```

- [ ] **Step 2: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six, and `graph_demo.json` present in every jar (`jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists `data/vfxweaver/vfx/graph_demo.json`).

- [ ] **Step 3: In-game check (Fabric 26.2, human partner)**

Ask the human partner to install `versions/26.2/build/libs/vfxweaver-<version>+26.2.jar` into the `26.2test` instance, launch, run `/vfx play graph_demo`, and report whether the screen blur repeatedly ramps up and back down (~1000 ticks per cycle) instead of staying at the static `radius: 2.0`. Expected: a visible pulsing blur. A blur that never changes means the graph value is not reaching the uniform — check the log for a `graph` parse error or an unknown-uniform warning. Never claim this result; record what the partner reports.

- [ ] **Step 4: In-game per-file isolation check (human partner)**

Ask the human partner to create a throwaway datapack (or a second test instance datapack) with two files under `data/<ns>/vfx/`: one copy of `graph_demo.json`, and one `bad_graph.json` containing a cyclic graph (e.g. two `math` nodes referencing each other). Launch, and report the log line `Couldn't parse VFX definition '<ns>:bad_graph'` plus whether `graph_demo` still plays. Expected: exactly one parse error, `graph_demo` unaffected. If `bad_graph` prevents the pack from loading, record the exact log verbatim.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/data/vfxweaver/vfx/graph_demo.json
git commit -m "feat(graph): ship graph_demo, a blur driven by a time/curve graph"
```

---

### Task 6: Document the graph format

**Files:**
- Modify: `docs/GUIDE.md` (datapack effect format section + its changelog at the bottom)
- Modify: `docs/CHANGELOG.md` (release entry, users-visible only)

**Interfaces:**
- Consumes: the final format from Tasks 1–5.
- Produces: user-facing documentation of `graph`, `inputs`, the eleven node kinds, the caps and the error behavior.

- [ ] **Step 1: Add the format section to `docs/GUIDE.md`**

Add a subsection under the datapack effect format documenting, with the literal JSON:

- the optional top-level `graph` object (`version`, `nodes`, `edges`, `meta` ignored by the engine);
- the node kinds `constant`, `time`, `random`, `noise`, `curve`, `math`, `mix`, `clamp`, `remap`, `bind`, `expr`, with each kind's inputs and defaults exactly as in `VFXNodeKind` and `VFXGraphEvaluator`;
- the two ways to wire an effect input: `inputs: { "<name>": { "from": "<node>" } }`, and an edge `{ "from": "<node>", "to": "<input>" }`; both are optional and every input keeps its numeric default;
- the caps (`MAX_NODES = 128`, `MAX_EDGES = 512`, `MAX_DEPTH = 32`, `MAX_EXPR_SOURCE = 1024`, `MAX_CURVE_POINTS = 64`, octaves clamped to 8) and the rule that a bad graph fails that file only;
- the `curve` easing convention: a point's `easing` eases the segment from that point to the next (the last point's easing is unused), matching `Keyframe`/`AnimatedValue`;
- a worked example equal to `graph_demo.json`.

- [ ] **Step 2: Add a `### vN` entry to the changelog at the bottom of `docs/GUIDE.md`**

State that `graph`/`inputs` are additive, that an older mod ignores them, and that no existing datapack changes behavior.

- [ ] **Step 3: Add the `docs/CHANGELOG.md` entry**

Keep it to what users see: "Effect definitions may now drive a numeric parameter from an optional value graph (spec step 2a); existing definitions are unaffected."

- [ ] **Step 4: Commit**

```bash
git add docs/GUIDE.md docs/CHANGELOG.md
git commit -m "docs: document the uniform graph format (step 2a)"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| §3.1 `graph` block: `version`, stable string node ids, `nodes`, `edges`, `meta` ignored | Task 1 (parser ignores unknown keys; `meta` is simply never read) |
| §3.1 node kinds `constant`, `time`, `random`, `noise`, `curve`, `math`, `mix`, `clamp`, `remap`, `bind`, `expr` | Task 1 (`VFXNodeKind`) + Task 2 (semantics); `expr` reuses `MathExpression`, `bind` reuses `VFXWorldBindings` |
| §3.1 cycles are a per-file parse error | Task 1 `sort` + Step 8 check ("cycle") |
| §3.2 `{ "from": "<node>" }` on effect inputs; every input keeps a numeric default | Task 3 (`inputs` parse + `getValue` graph branch) + Task 4 (contract) |
| §7 additive/backward compatibility, no `PROTOCOL_VERSION` bump | Task 4; graph refs live only in `inputs`/edges, never in `params`, so an older mod's `parseParam` never sees them |
| §8 evaluated once per frame per instance, per-instance cache, dirty invalidation, no allocation added | Task 2 (`VFXGraphEvaluator` epoch memo; per-instance in `VFXTimeline`) |
| §8 caps: node count, edge count, graph depth, `expr` source length | Task 1 (`VFXGraph` constants + checks) + Step 8 check |
| §8 parse errors name the node id and input; bad file fails that file only | Task 1 messages + Step 9 (existing manager catch) + Task 5 Step 4 (in-game isolation) |
| §9 step 2a first consumer | Task 5 (`graph_demo.json`), without the beam |
| Documentation obligation (AGENTS.md) | Task 6 |

Explicitly **out of scope** for this plan (spec §9 steps 2b–5, and the task's own exclusions): subgraphs/macros (§3.1.1), the logic nodes `compare`/`boolean`/`if`/`switch`, masks (§4), the per-pixel field library and `field` inputs (§2, §9 step 4 — an `inputs` entry with `"field"` is refused with a named error in Task 3), the screen-space `beam`, `surface_pattern` and `sparks`, and any depth access. All are additive to this format version.

**Placeholder scan:** no "TBD"/"handle edge cases"; every step has a command or complete code. The throwaway `Check.java` is written out in full in every task that needs it (no "similar to Task N"). The `%TEMP%` check file is intentionally not committed.

**Type consistency:** `VFXGraph.parse(String, JsonObject)`, `VFXGraph.node(String)`, `VFXGraph.indexOf(String)`, `VFXGraph.effectInputRefs()` are used with the same names in Tasks 1, 2, 3 and 4. `VFXNodeKind.acceptsNodeInput` is the parser's only gate for edge targets. `VFXTimeline.getValue` precedence is runtime override → graph input → binding/expression/value, matching the Task 4 assertions. `VFXGraphEvaluator.beginFrame(float)` is called only from `VFXTimeline.updateGraph(float)`, which is called only from `VFXActiveEffect.update(float)`. The `curve` easing array is indexed by the *left* point of each segment in both the parser (`curveEasings[i]`) and the evaluator (`node.curveEasings()[i]`).

**Spec ambiguities hit (recorded, resolved here):**

1. **`inputs` vs `params`.** The code's existing surface is `params`; spec §3.2 introduces a separate top-level `inputs` block. Placing `{ "from": ... }` inside `params` would make an older mod's `parseParam` throw and violate §7. Resolved: graph wiring lives only in `inputs` (and effect-input edges); `inputs` literals override `params` of the same name on the new mod, and are invisible to old mods. Documented in Task 3 and Task 6.
2. **Edge to an effect input.** Spec §3.1 writes `{ "from": "n3", "to": "beam.rim_noise" }` while §3.2 writes `inputs`. Resolved: an edge whose `to` is not a node id is an effect-input edge and is merged with the `inputs` block (wiring the same input twice is a parse error). The `beam.` prefix in the spec example is read as the beam's input name; this plan's effect inputs are the timeline param names (`radius`, `intensity`, …).
3. **`noise` and `curve` time source.** Spec §3.1 gives `noise` no time input and `curve` no time edge. Resolved: both default their time coordinate to the effect's elapsed ticks, and both accept an optional `time`/`x` input to override it. Documented in Task 6.
4. **`curve` point `easing` direction.** The spec example puts `easing` on the last point. Resolved to the repo's existing `Keyframe` convention (a point's easing applies to the segment from that point to the next), so the last point's easing is unused; recorded as a documented convention rather than silently inventing a second one.
5. **`graph.version` unknown value.** Spec §7 only says unknown *node kinds* fail per-file. Resolved: a version other than `1` is a named per-file error so a newer author's file fails clearly instead of being misparsed.

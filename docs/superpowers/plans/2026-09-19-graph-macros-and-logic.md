# Step 2b — Graph Macros and Logic Nodes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the two additive step-2b features to the uniform graph: parse-time expansion of the top-level `subgraphs` block (spec §3.1.1) with `$param` substitution, prefixed local ids, named outputs and recursion/nesting caps, and the four logic node kinds `compare` / `boolean` / `if` / `switch` (spec §3.1) with short-circuit evaluation — then prove both in-game by driving the existing `blur.radius` uniform.

**Architecture:** Step 2a already merged the MC-free `dev.vfxweaver.graph` module (`VFXGraph`, `VFXGraphNode`, `VFXGraphInput`, `VFXNodeKind`, `VFXGraphEvaluator`) plus `VFXDefinition`/`VFXTimeline` wiring. This step touches only that module and the single `graph` entry point of `VFXDefinition.parse`: logic kinds are added to the existing enum/node/parser/evaluator, and a new `VFXSubgraphExpander` flattens `subgraphs` into a JSON graph that the unchanged `VFXGraph.parse` validates. Expansion is a load step; the evaluator only ever sees a flat graph. A definition without `subgraphs`/`graph` nodes is passed straight to `VFXGraph.parse`, so step-2a behaviour is byte-for-byte.

**Tech Stack:** Java 25 (JDK 26 build), Gradle 9.5.1, Stonecutter 0.9.8, Gson (already a dependency — no new dependency is added), `com.google.gson` used directly so the graph module stays free of `net.minecraft.*`.

**Spec:** `docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md` (§3.1 graph block, §3.1.1 subgraphs, §7 backward compatibility, §8 caps/errors, §9 step 2b). Format model for this document: `docs/superpowers/plans/2026-09-19-uniform-graph-core.md`.

**Dependency and commit-level state at the time of writing:** step 2a (node model, evaluator, `{ "from": node }` inputs, caps) is **merged** at HEAD `c1450ca7b5f3a893bd46ba4fb8962ccbe1ee2583` (`docs: document the uniform graph format (step 2a)`, on top of `83b2dc3` wiring, `5471e15` evaluator, `4d6948f` parser). This plan is additive to that exact state: it does not rename or change any existing public signature (`VFXGraph.parse`, `VFXGraph.node`, `VFXGraph.effectInputRefs`, `VFXGraphEvaluator.evaluate`, `VFXTimeline`, `VFXDefinition.getGraph`) — it only adds the new kinds, a new `VFXSubgraphExpander` class, and one extra call inside `VFXDefinition.parse`.

## Global Constraints

- One shared `src/`; no `net.fabricmc.*` / `net.neoforged.*` imports outside `dev.vfxweaver.platform` and `dev.vfxweaver.client.platform`.
- The graph module (`dev.vfxweaver.graph`) lives in shared `src/main/java/` and must stay MC-free: no `net.minecraft.*` at all — parse JSON with plain Gson, not `net.minecraft.util.GsonHelper`. Datapack parsing happens on the dedicated server.
- `src/main` must never reference `src/client`. Expansion and logic evaluation are pure CPU work inside the already-client-side `VFXGraphEvaluator`.
- Indentation is tabs; non-reassigned params/locals are `final`; public classes and non-trivial public methods carry javadoc.
- No new dependencies.
- The datapack format is **additive**: `subgraphs` and the logic kinds are optional; no `PROTOCOL_VERSION` bump (spec §7). A definition without them behaves exactly as today; every effect input keeps a numeric default.
- Bounded collections (AGENTS.md): cap constants live on `VFXGraph` (`MAX_NODES`, `MAX_EDGES`, `MAX_DEPTH`) and `VFXSubgraphExpander` (`MAX_SUBGRAPHS`, `MAX_MACRO_DEPTH`); violations throw `IllegalArgumentException`, which `VFXDefinitionManager.reload`/`registerLocal` already catch per file (`VFXDefinitionManager.java:145`, `:178`).
- Parse errors name the offending node id/input (spec §8). A fault inside a macro names both the instance and the inner node: `subgraph 'fade_noise' (node 'n1') → node 's3': input 'a' is not connected and has no default`.
- There is **no test suite**: every task is verified by (a) building all six nodes, (b) a throwaway `main()` compiled against the built classes with assertions (AGENTS.md "Build and verify" method 2), and (c) an in-game check by the human partner. Never claim a visual result.
- Build commands (from `AGENTS.md`): `.\gradlew.bat :<node>:build`; Fabric nodes are `26.2`, `26.1.2`, `1.21.11`; NeoForge nodes are the same names with `-neoforge`.
- `javap` against the real deobf jar is the method for any uncertain MC API; this step adds **no** Minecraft API call, so `javap` is not needed (the graph module is MC-free; `VFXDefinition` only calls Gson and the new expander).
- **Short-circuit caveat (recorded, not a placeholder):** the graph produces only `float` values and every node is pure, so an eagerly evaluated skipped branch and a short-circuited one yield the same output. Short-circuit is therefore enforced by construction (the `input(...)` call sits inside the not-taken branch) and verified by inspection; no test-only evaluation counter is added (AGENTS.md has no test hooks and YAGNI applies).

### Standalone compile/run checks (shared recipe)

Every task that compiles the throwaway `%TEMP%\vfxcheck\Check.java` uses this one recipe — do not hand-list jars. Ask Gradle for the node's real runtime classpath with a throwaway init script, then compile and run against that string:

```powershell
New-Item -ItemType Directory -Force "$env:TEMP\vfxcheck" | Out-Null
$init = "$env:TEMP\vfxcheck\dumpcp.init.gradle"
$initText = @'
allprojects {
	tasks.register('dumpRuntimeClasspath') {
		doLast {
			def out = new File(System.getProperty('java.io.tmpdir'), 'vfxcheck/runtimeClasspath.txt')
			out.setText(sourceSets.main.runtimeClasspath.asPath, 'UTF-8')
		}
	}
}
'@
[System.IO.File]::WriteAllText($init, $initText, (New-Object System.Text.UTF8Encoding($false)))
.\gradlew.bat :26.1.2:dumpRuntimeClasspath --init-script "$init" --console=plain
# Read as UTF-8 too: this repo path is non-ASCII and `Get-Content`'s default mangles it.
$cp = [System.IO.File]::ReadAllText("$env:TEMP\vfxcheck\runtimeClasspath.txt").Trim()
```

`$cp` already contains the built `versions\26.1.2\build\classes\java\main`, Minecraft, Gson, joml, slf4j, brigadier, guava and datafixerupper, so build the node first (`.\gradlew.bat :26.1.2:build --console=plain`) so the classes exist, then:

```powershell
& 'C:\Program Files\Java\jdk-26\bin\javac.exe' -cp "$cp" -d "$env:TEMP\vfxcheck" "$env:TEMP\vfxcheck\Check.java"
& 'C:\Program Files\Java\jdk-26\bin\java.exe' -cp "$cp;$env:TEMP\vfxcheck" Check
```

A "run it to verify it fails" step runs only the `javac` line (expected: the missing package/class/method); a "build and run" step runs both and expects a clean `Check OK` line.

**Assertion counts are approximate:** a check passes on "zero failures", not on the exact number in its `Expected` line.

## File Structure

- `src/main/java/dev/vfxweaver/graph/VFXNodeKind.java` — modify: add `COMPARE`, `BOOLEAN`, `IF`, `SWITCH`; accept dynamic `case_<n>` names on `SWITCH`; update the class javadoc.
- `src/main/java/dev/vfxweaver/graph/VFXGraphNode.java` — modify: add the `CompareOp` / `BooleanOp` enums, the two nullable op fields, accessors and `requiredInputs()`.
- `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java` — modify: add the four kind cases with short-circuit evaluation and a `resolve(VFXGraphInput,float)` helper.
- `src/main/java/dev/vfxweaver/graph/VFXGraph.java` — modify: parse the `compare` / `boolean` op fields; validate required inputs through `VFXGraphNode.requiredInputs()`.
- `src/main/java/dev/vfxweaver/graph/VFXSubgraphExpander.java` — create: parse `subgraphs`, expand `subgraph` nodes with `$param` substitution and prefixed ids, resolve named outputs, enforce caps, attribute inner faults.
- `src/main/java/dev/vfxweaver/effect/VFXDefinition.java` — modify: call the expander for the `graph` block and resolve `inputs` `{ "from": ... }` refs through instance-output aliases.
- `src/main/resources/data/vfxweaver/vfx/graph_logic_demo.json` — create: built-in consumer (blur driven by a logic-gated macro).
- `docs/GUIDE.md`, `docs/CHANGELOG.md` — modify: document `subgraphs` and the four logic kinds.

---

### Task 1: Logic node kinds — model, parse, evaluate

**Files:**
- Modify: `src/main/java/dev/vfxweaver/graph/VFXNodeKind.java`
- Modify: `src/main/java/dev/vfxweaver/graph/VFXGraphNode.java`
- Modify: `src/main/java/dev/vfxweaver/graph/VFXGraph.java:126-134` (required-input loop)
- Modify: `src/main/java/dev/vfxweaver/graph/VFXGraph.java:157-179,181-247` (parseNode op fields and switch)
- Modify: `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java:99-169` (`input`, `compute`)
- Test: `%TEMP%\vfxcheck\Check.java` (throwaway, not committed)

**Interfaces:**
- Consumes: `VFXGraph.parse(String, JsonObject)`, `VFXGraphEvaluator(VFXGraph, long)`, `beginFrame(float)`, `evaluate(String, float)` from step 2a.
- Produces:
  - `VFXNodeKind.COMPARE/BOOLEAN/IF/SWITCH`, `VFXNodeKind.acceptsNodeInput(String)` now accepts `case_<digits>` on `SWITCH`.
  - `VFXGraphNode.CompareOp { EQ, NE, LT, LE, GT, GE }` with `fromString`, `VFXGraphNode.BooleanOp { AND, OR, XOR, NOT }` with `fromString`.
  - `VFXGraphNode.compareOp() : @Nullable CompareOp`, `VFXGraphNode.booleanOp() : @Nullable BooleanOp`, `VFXGraphNode.requiredInputs() : List<String>`.
  - `VFXGraphEvaluator` outputs for the four kinds: `compare`/`boolean` return `1.0F`/`0.0F`; `if` returns `then` when `condition != 0` else `else`; `switch` returns `case_<round(index)>` or `default`.

- [ ] **Step 1: Write the failing check**

Create `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonParser;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;

public class Check {
	static int passed = 0;

	static VFXGraphEvaluator ev(final String nodes) {
		return new VFXGraphEvaluator(VFXGraph.parse("test:logic",
			JsonParser.parseString("{\"version\":1,\"nodes\":" + nodes + "}").getAsJsonObject()), 1L);
	}

	static VFXGraph parse(final String nodes) {
		return VFXGraph.parse("test:logic",
			JsonParser.parseString("{\"version\":1,\"nodes\":" + nodes + "}").getAsJsonObject());
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

	public static void main(final String[] args) {
		final VFXGraphEvaluator lt = ev("[{\"id\":\"c\",\"kind\":\"compare\",\"op\":\"lt\",\"inputs\":{\"a\":1,\"b\":2}}]");
		lt.beginFrame(0.0F);
		expect("compare lt", lt.evaluate("c", -1.0F), 1.0F);

		final VFXGraphEvaluator ge = ev("[{\"id\":\"c\",\"kind\":\"compare\",\"op\":\"ge\",\"inputs\":{\"a\":1,\"b\":2}}]");
		ge.beginFrame(0.0F);
		expect("compare ge", ge.evaluate("c", -1.0F), 0.0F);

		final VFXGraphEvaluator and = ev("[{\"id\":\"c\",\"kind\":\"boolean\",\"op\":\"and\",\"inputs\":{\"a\":1,\"b\":0}}]");
		and.beginFrame(0.0F);
		expect("boolean and", and.evaluate("c", -1.0F), 0.0F);

		final VFXGraphEvaluator or = ev("[{\"id\":\"c\",\"kind\":\"boolean\",\"op\":\"or\",\"inputs\":{\"a\":1,\"b\":0}}]");
		or.beginFrame(0.0F);
		expect("boolean or", or.evaluate("c", -1.0F), 1.0F);

		final VFXGraphEvaluator not = ev("[{\"id\":\"c\",\"kind\":\"boolean\",\"op\":\"not\",\"inputs\":{\"a\":0}}]");
		not.beginFrame(0.0F);
		expect("boolean not", not.evaluate("c", -1.0F), 1.0F);

		final VFXGraphEvaluator iff = ev("[{\"id\":\"c\",\"kind\":\"compare\",\"op\":\"gt\",\"inputs\":{\"a\":2,\"b\":1}},"
			+ "{\"id\":\"s\",\"kind\":\"if\",\"inputs\":{\"condition\":{\"from\":\"c\"},\"then\":5,\"else\":9}}]");
		iff.beginFrame(0.0F);
		expect("if then", iff.evaluate("s", -1.0F), 5.0F);

		final VFXGraphEvaluator els = ev("[{\"id\":\"c\",\"kind\":\"compare\",\"op\":\"gt\",\"inputs\":{\"a\":1,\"b\":2}},"
			+ "{\"id\":\"s\",\"kind\":\"if\",\"inputs\":{\"condition\":{\"from\":\"c\"},\"then\":5,\"else\":9}}]");
		els.beginFrame(0.0F);
		expect("if else", els.evaluate("s", -1.0F), 9.0F);

		final VFXGraphEvaluator sw = ev("[{\"id\":\"s\",\"kind\":\"switch\",\"inputs\":{\"index\":1,\"case_0\":10,\"case_1\":20,\"case_2\":30,\"default\":-1}}]");
		sw.beginFrame(0.0F);
		expect("switch case_1", sw.evaluate("s", -1.0F), 20.0F);

		final VFXGraphEvaluator swd = ev("[{\"id\":\"s\",\"kind\":\"switch\",\"inputs\":{\"index\":7,\"case_0\":10,\"default\":-1}}]");
		swd.beginFrame(0.0F);
		expect("switch default", swd.evaluate("s", -1.0F), -1.0F);

		final VFXGraphEvaluator swr = ev("[{\"id\":\"s\",\"kind\":\"switch\",\"inputs\":{\"index\":1.4,\"case_1\":42,\"default\":-1}}]");
		swr.beginFrame(0.0F);
		expect("switch rounds index", swr.evaluate("s", -1.0F), 42.0F);

		expectThrows("bad compare op", () -> parse(
			"[{\"id\":\"c\",\"kind\":\"compare\",\"op\":\"like\",\"inputs\":{\"a\":1,\"b\":1}}]"),
			"unknown compare op 'like'");
		expectThrows("bad boolean op", () -> parse(
			"[{\"id\":\"c\",\"kind\":\"boolean\",\"op\":\"nand\",\"inputs\":{\"a\":1,\"b\":1}}]"),
			"unknown boolean op 'nand'");
		expectThrows("boolean and missing b", () -> parse(
			"[{\"id\":\"c\",\"kind\":\"boolean\",\"op\":\"and\",\"inputs\":{\"a\":1}}]"),
			"input 'b' is not connected");
		expectThrows("switch missing index", () -> parse(
			"[{\"id\":\"s\",\"kind\":\"switch\",\"inputs\":{\"case_0\":1}}]"),
			"input 'index' is not connected");
		expectThrows("switch bad case name", () -> parse(
			"[{\"id\":\"s\",\"kind\":\"switch\",\"inputs\":{\"index\":0,\"case_x\":1}}]"),
			"unknown input 'case_x'");

		assertTrue("boolean not parses without b", parse(
			"[{\"id\":\"c\",\"kind\":\"boolean\",\"op\":\"not\",\"inputs\":{\"a\":1}}]").node("c") != null);

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the shared standalone-check recipe (Global Constraints) from the repo root, `javac` line first, then the `java` line.

`javac` succeeds (the check references no symbol added by this task). The `java` run must fail immediately at the first `ev(...)` call with `IllegalArgumentException: node 'c': unknown kind 'compare'` — the four kinds do not exist yet. If `javac` fails, that is also an acceptable failure signal.

- [ ] **Step 3: Add the four kinds to `VFXNodeKind`**

In `src/main/java/dev/vfxweaver/graph/VFXNodeKind.java`, replace the enum constant block:

```java
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
	EXPR("expr", List.of("expr"), Set.of()),
	COMPARE("compare", List.of("a", "b"), Set.of("a", "b")),
	BOOLEAN("boolean", List.of("a"), Set.of("a", "b")),
	IF("if", List.of("condition"), Set.of("condition", "then", "else")),
	SWITCH("switch", List.of("index"), Set.of("index", "default"));
```

Replace the `acceptsNodeInput` method:

```java
	/**
	 * True when {@code name} may be fed by a node edge or a {@code { "from": ... }} object.
	 * A {@code switch} additionally accepts indexed case inputs named {@code case_0},
	 * {@code case_1}, … .
	 */
	public boolean acceptsNodeInput(final String name) {
		if (this == SWITCH && name != null && name.startsWith("case_")) {
			final String digits = name.substring(5);
			return !digits.isEmpty() && digits.chars().allMatch(Character::isDigit);
		}
		return this.numericInputs.contains(name);
	}
```

Replace the class javadoc (currently mentions the logic set as absent):

```java
/**
 * The uniform-graph node kinds supported in format version 1 (spec §9 steps 2a and 2b).
 * {@code subgraph} is deliberately absent: it is a load-time macro handled by
 * {@link VFXSubgraphExpander} and never reaches the parser.
 */
```

- [ ] **Step 4: Add the compare/boolean operators to `VFXGraphNode`**

In `src/main/java/dev/vfxweaver/graph/VFXGraphNode.java`, add after the `MathOp` enum (before the `private final String id;` field):

```java
	/** Comparison operators accepted by a {@code compare} node. Output is {@code 1} or {@code 0}. */
	public enum CompareOp {
		EQ("eq"), NE("ne"), LT("lt"), LE("le"), GT("gt"), GE("ge");

		private final String id;

		CompareOp(final String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		/**
		 * Resolves an operator from its datapack spelling.
		 *
		 * @param name raw string, e.g. {@code "lt"}
		 * @return the matching operator, or {@code null} when unknown
		 */
		public static @Nullable CompareOp fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final CompareOp op : values()) {
				if (op.id.equalsIgnoreCase(name.trim())) {
					return op;
				}
			}
			return null;
		}
	}

	/** Boolean operators accepted by a {@code boolean} node. Output is {@code 1} or {@code 0}. */
	public enum BooleanOp {
		AND("and"), OR("or"), XOR("xor"), NOT("not");

		private final String id;

		BooleanOp(final String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		/**
		 * Resolves an operator from its datapack spelling.
		 *
		 * @param name raw string, e.g. {@code "and"}
		 * @return the matching operator, or {@code null} when unknown
		 */
		public static @Nullable BooleanOp fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final BooleanOp op : values()) {
				if (op.id.equalsIgnoreCase(name.trim())) {
					return op;
				}
			}
			return null;
		}
	}
```

Add the two fields after `private final @Nullable MathOp mathOp;`:

```java
	private final @Nullable CompareOp compareOp;
	private final @Nullable BooleanOp booleanOp;
```

Replace the constructor signature and body:

```java
	VFXGraphNode(final String id, final VFXNodeKind kind, final Map<String, VFXGraphInput> inputs, final @Nullable String exprSource, final @Nullable MathOp mathOp, final @Nullable CompareOp compareOp, final @Nullable BooleanOp booleanOp, final float[] curveTimes, final float[] curveValues, final EasingFunction[] curveEasings, final @Nullable BoundParam bound) {
		this.id = id;
		this.kind = kind;
		this.inputs = inputs;
		this.exprSource = exprSource;
		this.mathOp = mathOp;
		this.compareOp = compareOp;
		this.booleanOp = booleanOp;
		this.curveTimes = curveTimes;
		this.curveValues = curveValues;
		this.curveEasings = curveEasings;
		this.bound = bound;
	}
```

Add the accessors and the op-aware required-inputs method after `mathOp()`:

```java
	public @Nullable CompareOp compareOp() {
		return this.compareOp;
	}

	public @Nullable BooleanOp booleanOp() {
		return this.booleanOp;
	}

	/**
	 * The inputs that must be present (literal or reference) for a valid node. For
	 * {@code and}/{@code or}/{@code xor} the second operand is required; {@code not} uses only
	 * {@code a}, and every other kind delegates to {@link VFXNodeKind#requiredInputs()}.
	 */
	public List<String> requiredInputs() {
		if (this.kind == VFXNodeKind.BOOLEAN && this.booleanOp != BooleanOp.NOT) {
			return List.of("a", "b");
		}
		return this.kind.requiredInputs();
	}
```

Add `import java.util.List;` to the imports (next to `import java.util.Map;`).

- [ ] **Step 5: Parse the two new op fields in `VFXGraph`**

In `src/main/java/dev/vfxweaver/graph/VFXGraph.java`, in `parseNode`, add the two locals after `VFXGraphNode.MathOp mathOp = null;`:

```java
		VFXGraphNode.CompareOp compareOp = null;
		VFXGraphNode.BooleanOp booleanOp = null;
```

Add two cases to the `switch (kind)` after the `MATH` branch:

```java
			case COMPARE -> {
				final String opName = strOr(nodeJson, "op", "");
				compareOp = VFXGraphNode.CompareOp.fromString(opName);
				if (compareOp == null) {
					throw new IllegalArgumentException("node '" + id + "': unknown compare op '" + opName + "'");
				}
			}
			case BOOLEAN -> {
				final String opName = strOr(nodeJson, "op", "");
				booleanOp = VFXGraphNode.BooleanOp.fromString(opName);
				if (booleanOp == null) {
					throw new IllegalArgumentException("node '" + id + "': unknown boolean op '" + opName + "'");
				}
			}
```

Update the final `return new VFXGraphNode(...)` in `parseNode`:

```java
		return new VFXGraphNode(id, kind, inputs, exprSource, mathOp, compareOp, booleanOp, curveTimes, curveValues, curveEasings, bound);
```

Change the required-input loop in `parse` (currently lines 126–134) to use the node-aware list:

```java
		for (final VFXGraphNode node : byId.values()) {
			for (final String required : node.requiredInputs()) {
				// Structural fields (points/expr/bind) are validated in their kind-specific branch,
				// not as edge inputs; only edge-acceptable required inputs are checked here.
				if (node.kind().acceptsNodeInput(required) && !node.inputs().containsKey(required)) {
					throw new IllegalArgumentException("node '" + node.id() + "': input '" + required + "' is not connected and has no default");
				}
			}
		}
```

- [ ] **Step 6: Implement the four kinds in `VFXGraphEvaluator`**

In `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java`, add the import:

```java
import org.jspecify.annotations.Nullable;
```

Replace the `input` method with the `resolve` helper:

```java
	private float input(final VFXGraphNode node, final String name, final float fallback) {
		return resolve(node.inputs().get(name), fallback);
	}

	private float resolve(final @Nullable VFXGraphInput in, final float fallback) {
		if (in == null) {
			return fallback;
		}
		if (!in.reference()) {
			return in.literal();
		}
		final Integer index = this.graph.indexOf(in.node());
		return index == null ? fallback : eval(index);
	}
```

Add the four cases to `compute` (after `case EXPR`):

```java
			case COMPARE -> compare(node);
			case BOOLEAN -> bool(node);
			case IF -> input(node, "condition", 0.0F) != 0.0F
				? input(node, "then", 0.0F)
				: input(node, "else", 0.0F);
			case SWITCH -> {
				final int which = Math.round(input(node, "index", 0.0F));
				final VFXGraphInput chosen = node.inputs().get("case_" + which);
				yield chosen == null ? input(node, "default", 0.0F) : resolve(chosen, 0.0F);
			}
```

Add the two helper methods after `math`:

```java
	private float compare(final VFXGraphNode node) {
		final float a = input(node, "a", 0.0F);
		final float b = input(node, "b", 0.0F);
		final boolean result = switch (node.compareOp()) {
			case EQ -> a == b;
			case NE -> a != b;
			case LT -> a < b;
			case LE -> a <= b;
			case GT -> a > b;
			case GE -> a >= b;
		};
		return result ? 1.0F : 0.0F;
	}

	/**
	 * The boolean operators. {@code and}/{@code or} short-circuit: the {@code b} operand is
	 * evaluated only when it can change the result, so a branch hidden behind a constant gets no
	 * {@code eval} call at all.
	 */
	private float bool(final VFXGraphNode node) {
		final boolean a = input(node, "a", 0.0F) != 0.0F;
		final boolean result = switch (node.booleanOp()) {
			case AND -> a && input(node, "b", 0.0F) != 0.0F;
			case OR -> a || input(node, "b", 0.0F) != 0.0F;
			case XOR -> a ^ (input(node, "b", 0.0F) != 0.0F);
			case NOT -> !a;
		};
		return result ? 1.0F : 0.0F;
	}
```

- [ ] **Step 7: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line (assertion counts are approximate — zero failures is the pass condition).

- [ ] **Step 8: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`

Expected: `BUILD SUCCESSFUL` for all six. A failure means an accidental `net.minecraft.*` import in `dev.vfxweaver.graph` — fix the import, not the build.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dev/vfxweaver/graph/VFXNodeKind.java src/main/java/dev/vfxweaver/graph/VFXGraphNode.java src/main/java/dev/vfxweaver/graph/VFXGraph.java src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java
git commit -m "feat(graph): add compare/boolean/if/switch logic nodes with short-circuit evaluation"
```

---

### Task 2: `VFXSubgraphExpander` — parse-time macro expansion

**Files:**
- Create: `src/main/java/dev/vfxweaver/graph/VFXSubgraphExpander.java`
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the expander check)

**Interfaces:**
- Consumes: `VFXGraph.parse(String, JsonObject)`, `VFXGraph.MAX_NODES`, `VFXGraph.MAX_EDGES`, `VFXGraph.FORMAT_VERSION`, `VFXGraphEvaluator` (Task 1/step 2a).
- Produces:
  - `VFXSubgraphExpander.expand(String owner, JsonObject graphJson, @Nullable JsonArray subgraphsJson) : Result` — throws `IllegalArgumentException` naming the macro instance and inner node on faults inside a macro.
  - `VFXSubgraphExpander.Result.graph() : VFXGraph`, `VFXSubgraphExpander.Result.topLevelOutputs() : Map<String, String>` (top-level instance id → id of its first declared output node).
  - `VFXSubgraphExpander.MAX_SUBGRAPHS = 64`, `MAX_MACRO_DEPTH = 8`.

- [ ] **Step 1: Write the failing check**

Overwrite `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
import dev.vfxweaver.graph.VFXSubgraphExpander;

public class Check {
	static int passed = 0;

	static VFXSubgraphExpander.Result expand(final String nodes, final String edges, final String subgraphs) {
		final JsonObject graph = JsonParser.parseString(
			"{\"version\":1,\"nodes\":" + nodes + ",\"edges\":" + (edges == null ? "[]" : edges) + "}").getAsJsonObject();
		final JsonArray subs = subgraphs == null ? null : JsonParser.parseString("[" + subgraphs + "]").getAsJsonArray();
		return VFXSubgraphExpander.expand("test:macro", graph, subs);
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

	static void expectContains(final String label, final Runnable runnable, final String... fragments) {
		try {
			runnable.run();
			throw new AssertionError(label + ": expected a parse error, got none");
		} catch (IllegalArgumentException e) {
			for (final String fragment : fragments) {
				if (!e.getMessage().contains(fragment)) {
					throw new AssertionError(label + ": '" + e.getMessage() + "' lacks '" + fragment + "'");
				}
			}
			passed++;
		}
	}

	static final String PULSE =
		"{\"id\":\"pulse\",\"inputs\":{\"speed\":1.0,\"peak\":10.0},"
			+ "\"nodes\":["
			+ "{\"id\":\"t\",\"kind\":\"time\",\"inputs\":{\"speed\":\"$speed\"}},"
			+ "{\"id\":\"m\",\"kind\":\"math\",\"op\":\"multiply\",\"inputs\":{\"a\":{\"from\":\"t\"},\"b\":\"$peak\"}}],"
			+ "\"outputs\":{\"out\":\"m\"}}";

	static final String TWO =
		"{\"id\":\"two\",\"inputs\":{},\"nodes\":["
			+ "{\"id\":\"lo\",\"kind\":\"constant\",\"inputs\":{\"value\":1.0}},"
			+ "{\"id\":\"hi\",\"kind\":\"constant\",\"inputs\":{\"value\":9.0}}],"
			+ "\"outputs\":{\"lo\":\"lo\",\"hi\":\"hi\"}}";

	public static void main(final String[] args) {
		final VFXSubgraphExpander.Result r1 = expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"pulse\",\"inputs\":{\"speed\":2.0,\"peak\":3.0}}]",
			null, PULSE);
		final VFXGraphEvaluator e1 = new VFXGraphEvaluator(r1.graph(), 1L);
		e1.beginFrame(5.0F);
		expect("macro output", e1.evaluate("n1.m", -1.0F), 30.0F);

		final VFXSubgraphExpander.Result r2 = expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"pulse\",\"inputs\":{\"speed\":1.0}}]",
			null, PULSE);
		final VFXGraphEvaluator e2 = new VFXGraphEvaluator(r2.graph(), 1L);
		e2.beginFrame(5.0F);
		expect("macro default", e2.evaluate("n1.m", -1.0F), 50.0F);

		final VFXSubgraphExpander.Result r3 = expand(
			"[{\"id\":\"a\",\"kind\":\"subgraph\",\"subgraph\":\"pulse\",\"inputs\":{\"speed\":2.0,\"peak\":3.0}},"
				+ "{\"id\":\"b\",\"kind\":\"subgraph\",\"subgraph\":\"pulse\",\"inputs\":{\"speed\":4.0,\"peak\":5.0}}]",
			null, PULSE);
		assertTrue("instance a exists", r3.graph().node("a.m") != null);
		assertTrue("instance b exists", r3.graph().node("b.m") != null);
		final VFXGraphEvaluator e3 = new VFXGraphEvaluator(r3.graph(), 1L);
		e3.beginFrame(5.0F);
		expect("a value", e3.evaluate("a.m", -1.0F), 30.0F);
		expect("b value", e3.evaluate("b.m", -1.0F), 100.0F);

		final VFXSubgraphExpander.Result r4 = expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"two\"}]",
			"[{\"from\":\"n1\",\"output\":\"hi\",\"to\":\"radius\"}]", TWO);
		assertTrue("named output edge", "n1.hi".equals(r4.graph().effectInputRefs().get("radius")));

		final VFXSubgraphExpander.Result r5 = expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"two\"}]",
			"[{\"from\":\"n1\",\"to\":\"radius\"}]", TWO);
		assertTrue("default output edge", "n1.lo".equals(r5.graph().effectInputRefs().get("radius")));

		final VFXSubgraphExpander.Result r6 = expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"two\"},"
				+ "{\"id\":\"c\",\"kind\":\"clamp\",\"inputs\":{\"value\":{\"from\":\"n1\"},\"min\":0.0,\"max\":100.0}}]",
			null, TWO);
		final VFXGraphEvaluator e6 = new VFXGraphEvaluator(r6.graph(), 1L);
		e6.beginFrame(0.0F);
		expect("node ref uses first output", e6.evaluate("c", -1.0F), 1.0F);

		final String nested =
			"{\"id\":\"outer\",\"inputs\":{},\"nodes\":["
				+ "{\"id\":\"inner\",\"kind\":\"subgraph\",\"subgraph\":\"leaf\"}],"
				+ "\"outputs\":{\"out\":\"inner\"}}";
		final String leaf =
			"{\"id\":\"leaf\",\"inputs\":{},\"nodes\":["
				+ "{\"id\":\"v\",\"kind\":\"constant\",\"inputs\":{\"value\":7.0}}],"
				+ "\"outputs\":{\"out\":\"v\"}}";
		final VFXSubgraphExpander.Result r7 = expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"outer\"}]",
			null, nested + "," + leaf);
		final VFXGraphEvaluator e7 = new VFXGraphEvaluator(r7.graph(), 1L);
		e7.beginFrame(0.0F);
		expect("nested macro", e7.evaluate("n1.inner.v", -1.0F), 7.0F);

		final String bad =
			"{\"id\":\"bad\",\"inputs\":{},\"nodes\":["
				+ "{\"id\":\"s\",\"kind\":\"math\",\"op\":\"add\",\"inputs\":{\"a\":1.0}}],"
				+ "\"outputs\":{\"out\":\"s\"}}";
		expectContains("attribution", () -> expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"bad\"}]", null, bad),
			"subgraph 'bad' (node 'n1')", "node 's'", "input 'b' is not connected");

		final String recursive =
			"{\"id\":\"rec\",\"inputs\":{},\"nodes\":["
				+ "{\"id\":\"s\",\"kind\":\"subgraph\",\"subgraph\":\"rec\"}],"
				+ "\"outputs\":{\"out\":\"s\"}}";
		expectContains("recursion", () -> expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"rec\"}]", null, recursive),
			"recursive subgraph reference");

		final StringBuilder chain = new StringBuilder();
		for (int i = 0; i < 9; i++) {
			final String body = i < 8
				? "{\"id\":\"n\",\"kind\":\"subgraph\",\"subgraph\":\"m" + (i + 1) + "\"}"
				: "{\"id\":\"n\",\"kind\":\"constant\",\"inputs\":{\"value\":1.0}}";
			if (i > 0) {
				chain.append(',');
			}
			chain.append("{\"id\":\"m").append(i).append("\",\"inputs\":{},\"nodes\":[")
				.append(body).append("],\"outputs\":{\"out\":\"n\"}}");
		}
		expectContains("nesting cap", () -> expand(
			"[{\"id\":\"g\",\"kind\":\"subgraph\",\"subgraph\":\"m0\"}]", null, chain.toString()),
			"macro nesting exceeds 8");

		expectContains("undeclared subgraphs", () -> expand(
			"[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"pulse\"}]", null, null),
			"needs a top-level 'subgraphs' block");

		final VFXGraph direct = VFXGraph.parse("test:macro",
			JsonParser.parseString("{\"version\":1,\"nodes\":[{\"id\":\"c\",\"kind\":\"constant\",\"inputs\":{\"value\":4.0}}],\"edges\":[]}").getAsJsonObject());
		final VFXSubgraphExpander.Result passthrough = expand(
			"[{\"id\":\"c\",\"kind\":\"constant\",\"inputs\":{\"value\":4.0}}]", null, null);
		assertTrue("passthrough node", direct.node("c") != null && passthrough.graph().node("c") != null);
		assertTrue("passthrough count", direct.nodeCount() == passthrough.graph().nodeCount());

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the shared standalone-check recipe (Global Constraints), `javac` line only (after `.\gradlew.bat :26.1.2:build --console=plain` so the classes exist).

Expected: `javac` errors on `dev.vfxweaver.graph.VFXSubgraphExpander`.

- [ ] **Step 3: Create the expander**

Create `src/main/java/dev/vfxweaver/graph/VFXSubgraphExpander.java`:

```java
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
			expandedNodes.add(rewriteNode(node, Map.of(), (ref, outputName, context) -> aliases.get(ref),
				"node '" + requireString(node, "id", "graph: a node is missing its 'id'") + "'"));
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
					result.nodes.add(rewriteNode(node, bindings, macroResolver(locals, nested), context(macro, prefix, localId)));
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
				final String localTarget = locals.get(output.getValue());
				if (localTarget != null) {
					result.outputs.put(output.getKey(), localTarget);
					continue;
				}
				final Map<String, String> nestedOutputs = nested.get(output.getValue());
				if (nestedOutputs != null) {
					result.outputs.put(output.getKey(), firstOutput(nestedOutputs, context(macro, prefix, output.getKey())));
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

	private static JsonObject rewriteNode(final JsonObject node, final Map<String, JsonElement> bindings, final RefResolver resolver, final String context) {
		final JsonObject rewritten = node.deepCopy();
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
```

- [ ] **Step 4: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line.

- [ ] **Step 5: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`

Expected: `BUILD SUCCESSFUL` for all six. The expander uses only Gson and the graph module, so a failure here is an accidental `net.minecraft.*` import.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/vfxweaver/graph/VFXSubgraphExpander.java
git commit -m "feat(graph): expand subgraph macros at parse time with param substitution and prefixed ids"
```

---

### Task 3: Wire `subgraphs` into `VFXDefinition.parse`

**Files:**
- Modify: `src/main/java/dev/vfxweaver/effect/VFXDefinition.java:1-19` (imports), `:199-205` (graph parse), `:216-226` (inputs `from` branch)
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the definition check)

**Interfaces:**
- Consumes: `VFXSubgraphExpander.expand`, `VFXSubgraphExpander.Result.graph/topLevelOutputs` (Task 2), `VFXDefinition.parse`, `VFXDefinition.createTimeline`, `VFXTimeline.updateGraph/getValue` (step 2a).
- Produces: a definition whose `graph` block uses `subgraph` nodes parses, expands, and drives an input; `getGraphInputs()` stores expanded node ids so the evaluator resolves them.

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
	static int passed = 0;

	static VFXDefinition parse(final String inputsBlock, final String edges) {
		final String json = "{"
			+ "\"type\":\"blur\",\"duration\":40,"
			+ "\"params\":{\"radius\":2.0},"
			+ "\"subgraphs\":[{\"id\":\"c\",\"inputs\":{},\"nodes\":["
			+ "{\"id\":\"v\",\"kind\":\"constant\",\"inputs\":{\"value\":9.0}}],\"outputs\":{\"out\":\"v\"}}],"
			+ "\"graph\":{\"version\":1,\"nodes\":[{\"id\":\"n1\",\"kind\":\"subgraph\",\"subgraph\":\"c\"}],"
			+ "\"edges\":" + edges + "},"
			+ "\"inputs\":" + inputsBlock + "}";
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath("test", "macro_demo"),
			JsonParser.parseString(json).getAsJsonObject());
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
		final VFXDefinition viaInputs = parse("{\"radius\":{\"from\":\"n1\"}}", "[]");
		assertTrue("graph parsed", viaInputs.getGraph() != null);
		assertTrue("expanded node present", viaInputs.getGraph().node("n1.v") != null);
		assertTrue("input alias resolved", "n1.v".equals(viaInputs.getGraphInputs().get("radius")));
		final VFXTimeline tl = viaInputs.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 7L);
		tl.updateGraph(0.0F);
		expect("inputs block drives radius", tl.getValue("radius", -1.0F), 9.0F);

		final VFXDefinition viaEdge = parse("{}", "[{\"from\":\"n1\",\"to\":\"radius\"}]");
		assertTrue("edge alias resolved", "n1.v".equals(viaEdge.getGraphInputs().get("radius")));
		final VFXTimeline edgeTimeline = viaEdge.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 7L);
		edgeTimeline.updateGraph(0.0F);
		expect("edge drives radius", edgeTimeline.getValue("radius", -1.0F), 9.0F);

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run `.\gradlew.bat :26.1.2:build --console=plain`, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: the run fails on `input 'radius': graph node 'n1' does not exist` (the expander is not wired yet), or `javac` fails if class definitions differ.

- [ ] **Step 3: Call the expander and resolve instance aliases**

In `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`, add the import next to the existing `VFXGraph` import:

```java
import dev.vfxweaver.graph.VFXSubgraphExpander;
```

Replace the graph parse block (currently lines 199–205) with:

```java
		// Optional graph (spec §3.1) and inputs (spec §3.2). Both are additive: a definition
		// without them behaves exactly as before, and an older mod ignores them entirely
		// because graph references never live inside "params". `subgraphs` (spec §3.1.1) is a
		// load-time macro layer: the expander flattens it before VFXGraph validates the result.
		VFXGraph graph = null;
		Map<String, String> graphAliases = Map.of();
		if (json.has("graph") && !json.get("graph").isJsonNull()) {
			final JsonArray subgraphs = json.has("subgraphs") && json.get("subgraphs").isJsonArray()
				? json.getAsJsonArray("subgraphs") : null;
			final VFXSubgraphExpander.Result expanded = VFXSubgraphExpander.expand(
				id.toString(), GsonHelper.getAsJsonObject(json, "graph"), subgraphs);
			graph = expanded.graph();
			graphAliases = expanded.topLevelOutputs();
		}
```

In the `inputs` `from` branch (currently lines 218–225), resolve the authored id through the aliases and store the expanded id:

```java
					if (object.has("from")) {
						String nodeId = GsonHelper.getAsString(object, "from");
						String resolved = graphAliases.getOrDefault(nodeId, nodeId);
						if (graph == null || graph.node(resolved) == null) {
							throw new IllegalArgumentException("input '" + name + "': graph node '" + nodeId + "' does not exist");
						}
						graphInputs.put(name, resolved);
						params.putIfAbsent(name, ParamSpec.constant(0.0F));
						continue;
					}
```

- [ ] **Step 4: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line.

- [ ] **Step 5: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`

Expected: `BUILD SUCCESSFUL` for all six.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/vfxweaver/effect/VFXDefinition.java
git commit -m "feat(graph): expand subgraphs when parsing effect definitions"
```

---

### Task 4: Prove the additive/backward-compatibility contract

**Files:**
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the compatibility check)
- Modify: none (this task is a proof; if it finds a regression, fix it in Task 1–3 files)

**Interfaces:**
- Consumes: `VFXDefinition.parse`, `getGraph`, `createTimeline(float,Map,EasingFunction,long)`, `VFXTimeline.update/updateGraph/getValue`, `VFXGraph.parse`, `VFXSubgraphExpander.expand`.
- Produces: a runnable assertion that (a) a definition without `graph`/`subgraphs`/logic is unchanged, (b) the no-subgraph fast path and the direct step-2a parser produce the same graph, (c) an old step-2a graph using only the original kinds still evaluates identically.

- [ ] **Step 1: Write the contract check**

Overwrite `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXTimeline;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
import dev.vfxweaver.graph.VFXSubgraphExpander;
import java.util.Map;
import net.minecraft.resources.Identifier;

public class Check {
	static int passed = 0;

	static VFXDefinition parse(final String json) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath("test", "compat"),
			JsonParser.parseString(json).getAsJsonObject());
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
		// 1. Today's blur.json shape: an animated param, no graph, no subgraphs. Unchanged.
		final VFXDefinition plain = parse("{\"type\":\"blur\",\"duration\":40,\"easing\":\"ease_in_out_cubic\","
			+ "\"params\":{\"radius\":{\"start\":4.0,\"end\":0.0}}}");
		final VFXTimeline plainTimeline = plain.createTimeline(40.0F, Map.of(), plain.getDefaultEasing(), 5L);
		plainTimeline.update(0.0F);
		final float start = plainTimeline.getValue("radius", -1.0F);
		plainTimeline.update(40.0F);
		final float end = plainTimeline.getValue("radius", -1.0F);
		expect("plain start", start, 4.0F);
		expect("plain end", end, 0.0F);
		assertTrue("plain has no graph", plain.getGraph() == null && plain.getGraphInputs().isEmpty());

		// 2. The no-subgraph fast path is the step-2a parser, byte-for-byte.
		final String step2a =
			"{\"version\":1,\"nodes\":["
				+ "{\"id\":\"t\",\"kind\":\"time\",\"inputs\":{\"speed\":2.0}},"
				+ "{\"id\":\"m\",\"kind\":\"math\",\"op\":\"add\",\"inputs\":{\"a\":1.0,\"b\":{\"from\":\"t\"}}}],"
				+ "\"edges\":[{\"from\":\"m\",\"to\":\"radius\"}]}";
		final JsonObject graphJson = JsonParser.parseString(step2a).getAsJsonObject();
		final VFXGraph direct = VFXGraph.parse("test:compat", graphJson);
		final VFXSubgraphExpander.Result viaExpander = VFXSubgraphExpander.expand("test:compat", graphJson, null);
		assertTrue("same node count", direct.nodeCount() == viaExpander.graph().nodeCount());
		assertTrue("same refs", direct.effectInputRefs().equals(viaExpander.graph().effectInputRefs()));
		final VFXGraphEvaluator directEval = new VFXGraphEvaluator(direct, 3L);
		final VFXGraphEvaluator expanderEval = new VFXGraphEvaluator(viaExpander.graph(), 3L);
		directEval.beginFrame(4.0F);
		expanderEval.beginFrame(4.0F);
		expect("step-2a direct", directEval.evaluate("m", -1.0F), 9.0F);
		expect("step-2a expanded", expanderEval.evaluate("m", -1.0F), 9.0F);

		// 3. A step-2a definition (graph, no subgraphs) is unchanged through VFXDefinition.
		final VFXDefinition step2aDefinition = parse("{\"type\":\"blur\",\"duration\":40,"
			+ "\"params\":{\"radius\":2.0},"
			+ "\"graph\":{\"version\":1,\"nodes\":[{\"id\":\"half\",\"kind\":\"constant\",\"inputs\":{\"value\":6.0}}],\"edges\":[]},"
			+ "\"inputs\":{\"radius\":{\"from\":\"half\"}}}");
		final VFXTimeline step2aTimeline = step2aDefinition.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 5L);
		step2aTimeline.updateGraph(0.0F);
		expect("step-2a definition unchanged", step2aTimeline.getValue("radius", -1.0F), 6.0F);

		// 4. Unknown top-level blocks are ignored when there is no graph.
		final VFXDefinition future = parse("{\"type\":\"blur\",\"duration\":40,\"params\":{\"radius\":2.0},"
			+ "\"subgraphs\":[{\"id\":\"unused\",\"inputs\":{},\"nodes\":[{\"id\":\"v\",\"kind\":\"constant\",\"inputs\":{\"value\":1.0}}],\"outputs\":{\"out\":\"v\"}}]}");
		assertTrue("unused subgraphs ignored", future.getGraph() == null);

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Build and run it**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line.

- [ ] **Step 3: Commit**

```bash
git commit --allow-empty -m "test(graph): prove subgraphs/logic are additive to the step-2a format"
```

---

### Task 5: Ship a minimal consumer and verify it in-game

**Files:**
- Create: `src/main/resources/data/vfxweaver/vfx/graph_logic_demo.json`
- Modify: none in Java (the consumer reuses the existing `blur` post pass and its `radius` param, registered at `VFXShaderPrograms.java:80` and read at `VFXPostProcessingManager.java:356`)

**Interfaces:**
- Consumes: everything from Tasks 1–3; the existing `blur` effect's `radius` parameter.
- Produces: `/vfx play graph_logic_demo` — a built-in effect whose blur radius is gated by a macro that uses `time`, `math`, `compare`, `remap` and `if`, selected through a named macro output.

- [ ] **Step 1: Add the built-in consumer effect**

Create `src/main/resources/data/vfxweaver/vfx/graph_logic_demo.json`:

```json
{
  "type": "blur",
  "duration": 400,
  "loop": true,
  "persistent": true,
  "fade_ticks": 20,
  "params": {
    "radius": 0.0
  },
  "subgraphs": [
    {
      "id": "pulse",
      "inputs": {
        "period": 100.0,
        "peak": 8.0,
        "duty": 0.5
      },
      "nodes": [
        { "id": "t", "kind": "time", "inputs": { "speed": 1.0 } },
        { "id": "phase", "kind": "math", "op": "mod", "inputs": { "a": { "from": "t" }, "b": "$period" } },
        { "id": "half", "kind": "math", "op": "multiply", "inputs": { "a": "$period", "b": "$duty" } },
        { "id": "on", "kind": "compare", "op": "lt", "inputs": { "a": { "from": "phase" }, "b": { "from": "half" } } },
        { "id": "ramp", "kind": "remap", "inputs": { "value": { "from": "phase" }, "in_min": 0.0, "in_max": 50.0, "out_min": 0.0, "out_max": "$peak" } },
        { "id": "out", "kind": "if", "inputs": { "condition": { "from": "on" }, "then": { "from": "ramp" }, "else": 0.0 } }
      ],
      "outputs": {
        "amount": "out",
        "raw": "ramp"
      }
    }
  ],
  "graph": {
    "version": 1,
    "nodes": [
      { "id": "n1", "kind": "subgraph", "subgraph": "pulse", "inputs": { "period": 100.0, "peak": 8.0 } }
    ],
    "edges": [
      { "from": "n1", "output": "amount", "to": "radius" }
    ]
  },
  "meta": {
    "n1": { "pos": [60, 60] }
  }
}
```

- [ ] **Step 2: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`

Expected: `BUILD SUCCESSFUL` for all six, and `graph_logic_demo.json` present in every jar (`jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists `data/vfxweaver/vfx/graph_logic_demo.json`).

- [ ] **Step 3: In-game check (Fabric 26.2, human partner)**

Ask the human partner to install `versions/26.2/build/libs/vfxweaver-<version>+26.2.jar` into the `26.2test` instance, launch, run `/vfx play graph_logic_demo`, and report whether the screen blur ramps up to 8 over the first ~50 ticks of each 100-tick cycle and drops to 0 for the second half, repeatedly. Expected: a visible on/off blur pulse. A blur that never changes means the macro value is not reaching the uniform — check the log for a `subgraph` parse error or an unknown-uniform warning. Never claim this result; record what the partner reports.

- [ ] **Step 4: In-game isolation check (human partner)**

Ask the human partner to add a second effect file under `data/<ns>/vfx/` with a recursive `subgraphs` block (a macro whose node has `"kind": "subgraph", "subgraph": "<its own id>"`). Launch, and report the log line naming `recursive subgraph reference` and whether `graph_logic_demo` still plays. Expected: exactly one parse error, the demo unaffected. If the bad file prevents the pack from loading, record the exact log verbatim.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/data/vfxweaver/vfx/graph_logic_demo.json
git commit -m "feat(graph): ship graph_logic_demo, a logic-gated macro driving blur radius"
```

---

### Task 6: Document macros and logic nodes

**Files:**
- Modify: `docs/GUIDE.md` (datapack effect format section + its changelog at the bottom)
- Modify: `docs/CHANGELOG.md` (release entry, users-visible only)

**Interfaces:**
- Consumes: the final format from Tasks 1–5.
- Produces: user-facing documentation of `subgraphs`, `$param` substitution, prefixed ids, named outputs, `MAX_SUBGRAPHS`/`MAX_MACRO_DEPTH`, and the four logic kinds.

- [ ] **Step 1: Add the macros and logic sections to `docs/GUIDE.md`**

Under the existing uniform-graph subsection (added by step 2a), add a `#### Subgraphs (macros)` subsection. Insert exactly this content:

```markdown
#### Subgraphs (macros)

An effect file may declare reusable node blocks under a top-level `subgraphs` array. A graph node
of kind `subgraph` stamps one in; the loader expands it before the graph is validated, so macros
cost nothing per frame.

```json
{
  "subgraphs": [
    {
      "id": "fade_noise",
      "inputs": { "scale": 1.0, "speed": 1.0 },
      "nodes": [
        { "id": "s1", "kind": "time",  "inputs": { "speed": "$speed" } },
        { "id": "s2", "kind": "noise", "inputs": { "scale": "$scale", "octaves": 3 } },
        { "id": "s3", "kind": "curve", "inputs": { "points": [ { "time": 0, "value": 1.0 },
                                                              { "time": 100, "value": 0.0 } ] } }
      ],
      "edges": [ { "from": "s2", "to": "s3", "input": "a" } ],
      "outputs": { "out": "s3" }
    }
  ],
  "graph": {
    "nodes": [ { "id": "n1", "kind": "subgraph", "subgraph": "fade_noise",
                 "inputs": { "scale": 2.0, "speed": 0.5 } } ],
    "edges": [ { "from": "n1", "to": "radius" } ]
  }
}
```

- `inputs` on a subgraph declares parameters with defaults. A node value that is the whole string
  `"$name"` is replaced by the instance's binding (or the default). `"$name"` is only recognised as
  a whole node-input value; it is never substituted inside `expr` text or inside `points`.
- Node ids inside a subgraph are local. On expansion they are prefixed with the instance id
  (`n1.s2`), so the same macro can be used twice in one graph without collisions.
- `outputs` is a non-empty map of output name to a local node id. `outputs` order matters: the
  first entry is the default output. An edge may select one with
  `{ "from": "n1", "output": "intensity", "to": "beam.intensity" }`; with one output, or when
  `output` is omitted, the first declared output is used. An effect `inputs` entry
  `{ "from": "n1" }` always uses the first output.
- Subgraphs may nest and may not reference themselves, directly or transitively. The number of
  subgraph definitions is capped at 64 and nesting at 8; exceeding either fails that file only.
  The expanded node and edge counts must stay within the graph caps (128 / 512).
- A fault inside a macro names both ends, e.g.
  `subgraph 'fade_noise' (node 'n1') → node 's3': input 'a' is not connected and has no default`.
- A `subgraph` node without a `subgraphs` block is an error; macros are defined in the same file
  in this version.

#### Logic nodes

Four kinds produce a value from comparisons and selection. Booleans are `1.0` (true) and `0.0`
(false); any non-zero input is true.

| kind | structural field | inputs | result |
|---|---|---|---|
| `compare` | `op`: `eq`, `ne`, `lt`, `le`, `gt`, `ge` | `a`, `b` (both required) | `1.0` or `0.0` |
| `boolean` | `op`: `and`, `or`, `xor`, `not` | `a` (required); `b` required for `and`/`or`/`xor`, unused for `not` | `1.0` or `0.0` |
| `if` | — | `condition` (required), `then`, `else` (default `0`) | `then` when `condition` is non-zero, else `else` |
| `switch` | — | `index` (required), `case_0`, `case_1`, …, `default` (default `0`) | `case_<round(index)>` when present, else `default` |

```json
{ "id": "gate", "kind": "compare", "op": "gt", "inputs": { "a": { "from": "level" }, "b": 0.5 } },
{ "id": "out",  "kind": "if", "inputs": { "condition": { "from": "gate" }, "then": 1.0, "else": 0.0 } }
```

`if`, `switch`, `boolean and` and `boolean or` evaluate only the branch that can change the
result; the other side is never computed.
```

- [ ] **Step 2: Extend the existing `### v33` changelog bullet at the bottom of `docs/GUIDE.md`**

The step-2a value-graph entry already lives under `### v33` (the current unreleased guide version).
Append to that same entry, matching its one-bullet style:

```markdown
- **Value graphs** — effect can drive any numeric input from optional `graph` + `inputs` block (see [3.6](#36-value-graphs)): `constant`, `time`, `random`, `noise`, `curve`, `math`, `mix`, `clamp`, `remap`, `bind`, `expr` nodes plus the logic nodes `compare`, `boolean`, `if`, `switch`, and reusable `subgraphs` (macros with `$` parameters, local prefixed ids and named outputs), evaluated once per frame. All blocks additive — a definition without them behaves exactly as before; a broken graph fails its own file only.
```

(Keep the existing sentence about `graph_demo`; only add the macros/logic clause so the entry stays accurate.)

- [ ] **Step 3: Extend the `docs/CHANGELOG.md` entry**

Append to the existing `## Unreleased / Guide v33` → `### Added` bullet about value graphs (do **not** start a new version section — step 2b ships in the same unreleased set):

```markdown
- **Graph macros and logic nodes** — value graphs gained reusable `subgraphs` (parse-time macros with `$` parameters, local ids, named outputs, nesting cap 8) and the `compare` / `boolean` / `if` / `switch` logic kinds with short-circuit evaluation. Both are additive to the step-2a format: existing `graph`/`inputs` definitions and definitions without graphs are unaffected.
```

- [ ] **Step 4: Commit**

```bash
git add docs/GUIDE.md docs/CHANGELOG.md
git commit -m "docs: document subgraphs and logic nodes (step 2b)"
```

---

## Self-Review

**Scope — in:** parse-time subgraph expansion with `$param` substitution, local (prefixed) node ids (`n1.s2`), multiple named outputs with a first-output default, recursion and nesting caps (`MAX_MACRO_DEPTH`, `MAX_SUBGRAPHS`), and error messages naming both the macro instance and the inner node; the four logic node kinds `compare`, `boolean`, `if`, `switch` with short-circuit evaluation. Additive format only, with the compatibility contract proven in Task 4. Commit steps are included in Tasks 1–6.

**Scope — out (not in this plan, additive to the same format version):** masks (§4), per-pixel fields and the field library / `field` inputs (§2, §9 step 4), the screen-space `beam`, `surface_pattern` and `sparks` (§6), and the shared subgraph library file `data/<ns>/vfx_graphs/` (deferred in spec §12). The graph `meta` block stays engine-ignored (it is never read, by design).

**Dependency and commit-level state:** step 2a is merged at HEAD `c1450ca7b5f3a893bd46ba4fb8962ccbe1ee2583`; this plan adds to that exact state. No existing public signature changes. `VFXSubgraphExpander` is new; `VFXGraph.parse`, `VFXGraphEvaluator.evaluate`, `VFXTimeline` and `VFXDefinition.getGraph` are consumed unchanged. The previous step-2a plan explicitly listed subgraphs and logic as out of scope, so there is no overlap.

**Verification (this repo's):** every Java task builds the active node, runs a throwaway `Check.java` against the real runtime classpath (AGENTS.md "Build and verify" method 2) and then builds all six nodes. No `javap` step is required: the graph module is MC-free and `VFXDefinition` adds no Minecraft API call. In-game behaviour is checked by the human partner (Tasks 5 Steps 3–4) and never claimed by the author.

**Placeholder scan:** no "TBD"/"handle edge cases"; every code step contains the complete file or a complete replacement. `Check.java` is written out in full in each task that needs it (no "similar to Task N"). The `%TEMP%` check file is intentionally not committed.

**Type consistency:** `VFXNodeKind.COMPARE/BOOLEAN/IF/SWITCH` are used with the same names in Tasks 1 and 6. `VFXGraphNode.CompareOp`/`BooleanOp`/`compareOp()`/`booleanOp()`/`requiredInputs()` are referenced identically in the parser and evaluator. `VFXSubgraphExpander.expand(String, JsonObject, JsonArray)` and `Result.graph()`/`topLevelOutputs()` are used with the same signatures in Tasks 2, 3 and 4. `MAX_MACRO_DEPTH = 8` matches the Task 2 check's `macro nesting exceeds 8` and the Task 6 documentation. The `switch` case input spelling `case_<n>` is identical in `VFXNodeKind.acceptsNodeInput`, `VFXGraphEvaluator.compute` and the documentation.

**Spec ambiguities hit (recorded, resolved here):**

1. **Where `subgraphs` lives.** Spec §3.1.1 shows `subgraphs` and `graph` side by side without naming the enclosing object. Resolved: `subgraphs` is a **top-level** key, a sibling of `graph`, read in the same `VFXDefinition.parse` reach. (Task 3, Task 6.)
2. **Logic node JSON shape.** Spec §3.1 names the four kinds but gives no schema. Resolved: `compare`/`boolean` take a structural `op` exactly like `math`; `if` takes `condition`/`then`/`else`; `switch` takes `index` plus indexed `case_<n>` inputs and a `default`. Booleans are `1.0`/`0.0`. (Task 1, Task 6.)
3. **Multiple outputs in the effect `inputs` block.** Spec §3.1.1 documents `output` only on an edge. Resolved: an `inputs` entry always uses the instance's first declared output; named selection is available on graph edges. (Tasks 2, 3, 6.)
4. **Subgraph `outputs` optionality.** Spec implies it but does not say "required". Resolved: `outputs` must be a non-empty object; the first entry is the default output. (Task 2.)
5. **Scope of `$param` substitution.** Spec shows whole-value placeholders only. Resolved: `$name` is recognised only when it is the entire node-input string, never inside `expr` text or nested arrays. (Task 2, Task 6.)
6. **Edges inside a macro.** Spec does not say what a macro edge may target. Resolved: macro edges wire plain local nodes only; a nested macro instance is fed through its own `inputs` block, not an edge. (Task 2.)
7. **Short-circuit observability.** Pure `float` outputs make short-circuit indistinguishable from eager evaluation in an output-based check. Resolved: implemented by construction (the non-selected `input(...)` call is unreachable), verified by inspection; no test hook is added. (Global Constraints, Task 1 Step 6.)

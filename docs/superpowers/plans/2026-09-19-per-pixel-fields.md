# Step 4 — Per-Pixel Field Library — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `{ "field": "<fn>", ... }` per-pixel input form to the datapack effect format, a bounded field model (built-in functions **including a reusable shape/primitive set**, output types, coercion, composition) validated at parse time, a Java packer that flattens a field tree into a fixed uniform program, a GLSL library that evaluates it in a fragment shader, and the depth/world reconstruction the geometry functions need — then prove the whole path in-game by modulating `dent.intensity` per pixel with noise.

The field library **owns the shape primitives** — the 2D screen set `circle`, `ellipse`, `rect` and `polygon` (with `center`, `rotation`, `fill: solid|stroke`, `stroke_width`, `softness` and a `repeat` tiling modifier) **and the 3D world set `sphere` and `box`** (used wherever a 3D coordinate exists) — as the single implementation masks, `surface_pattern` and future effects consume. **Grids and rings are not separate features**: a grid is a tiled shape (`repeat`), a ring is an `ellipse` with `fill: stroke`.

**Architecture:** A new MC-free module `dev.vfxweaver.field` (function metadata, `VFXField` tree, `VFXFieldProgram` packer) lives in shared `src/main/java/` because datapack parsing runs on the dedicated server. `VFXDefinition` parses the `field` object on a *field-capable* input and stores it; `VFXTimeline` packs it once per instance into a flat `VFXFieldProgram` and exposes index-based graph evaluation for its numeric `{ "from": <node> }` parameters. On the client, a fixed-size `FieldConfig` UBO is written every frame from the packed program plus a reused inverse-view-projection matrix, the main target's depth attachment is bound as `DepthSampler`, and a GLSL include evaluates the built-in functions — including the shared shape primitives — and the composition program. No graph-driven GLSL codegen: the fragment shader contains the fixed library and selects a function by uniform index. Every definition without fields behaves exactly as today.

**Tech Stack:** Java 25 (JDK 26 build), Gradle 9.5.1, Stonecutter 0.9.8, Gson (already a dependency; plain `com.google.gson` in the field module so it stays free of `net.minecraft.*`), GLSL 330 with Mojang `#moj_import` includes (`com.mojang.blaze3d` / `net.minecraft.client.renderer` post pipeline).

**Spec:** `docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md` (§2 two evaluation domains, §3.2 sockets, §7 backward compatibility, §8 caps/errors, §9 step 4). Depth prerequisite and the verified world-position recipe: `docs/superpowers/specs/notes/2026-09-19-depth-findings.md`. Format model to match: `docs/superpowers/plans/2026-09-19-uniform-graph-core.md`. **Dependency order:** execute this plan (step 4) **before** step 3 (masks, `docs/superpowers/plans/2026-09-19-masks.md`), which consumes this library's shared shape/depth content.

## Global Constraints

- One shared `src/`; no `net.fabricmc.*` / `net.neoforged.*` imports outside `dev.vfxweaver.platform` and `dev.vfxweaver.client.platform`.
- The field model and packer (`dev.vfxweaver.field`) live in shared `src/main/java/` and must stay MC-free: no `net.minecraft.*` and no `com.mojang.blaze3d.*` — parse JSON with plain Gson, and write the packer against the small `VFXFieldValueWriter` interface (the `com.mojang` adapter lives in `client.postprocessing`).
- `src/main` must never reference `src/client`. Uniform upload, depth binding, camera matrices and texture resolution are client-side (`dev.vfxweaver.client.postprocessing`).
- Indentation is tabs; non-reassigned params/locals are `final`; public classes and non-trivial public methods carry javadoc.
- No new dependencies.
- The datapack format is **additive**: `field` is optional, every effect input keeps a numeric default, and a definition without fields behaves exactly as today. No `PROTOCOL_VERSION` bump (spec §7); fields never live inside `params`, so an older mod's `parseParam` never sees them.
- Bounded collections (AGENTS.md): every field tree is capped (`VFXField.MAX_DEPTH = 3`, `MAX_LEAVES = 4`, `MAX_PROGRAM = 8`, `MAX_CURVE_POINTS = 8`, `MAX_TEXTURE_LEAVES = 1`); violations refuse **that file only** via `IllegalArgumentException`, which `VFXDefinitionManager.reload`/`registerLocal` already catch (`VFXDefinitionManager.java:145`, `:178`).
- Parse errors name the offending input, field function and parameter (spec §8), e.g. `input 'intensity': field 'mix': 'factor' must be a float field`.
- The **UBO field-order rule** (AGENTS.md): a shader's parameter block and the Java list that writes it must be the same set of fields **in the same order** — std140 offsets are positional, so a mismatch silently shifts values. This applies to the existing `Config` block and to the new `FieldConfig` block defined in Tasks 4/5; `field.glsl`'s declaration order and `VFXFieldProgram.write`'s order are the single contract, stated in one place and cross-referenced. Never name a uniform after a GLSL built-in.
- **Shape primitives are owned by this library** (shared-ownership rule). The 2D `circle`/`ellipse`/`rect`/`polygon` (screen space) and the 3D `sphere`/`box` (world space, used where a 3D coordinate exists — the depth fields already reconstruct one), plus `fill: solid|stroke`, `stroke_width`, `softness` and the `repeat` tiling modifier, are implemented once in `field.glsl` — `vfx_shape_sdf` returns the raw distance (negative inside), dispatching to the 2D helpers for a screen coordinate and to the 3D helpers for a world coordinate; `vfx_shape_coverage` turns it into a `[0,1]` coverage after fill and softness. The masks plan and the `surface_pattern` plan **consume** these functions and must not re-implement shapes (their plans are edited in parallel to state so; the masks plan consumes the 3D helpers for `sphere`/`box`). `grid`/`ring`/`radial` are not separate features: a grid is a shape with `repeat`, a ring is an `ellipse` with `fill: stroke`.
- No per-frame allocation in hot paths: the inverse view-projection `Matrix4f`, the camera vector, the packed field arrays and the ring buffers are preallocated and reused; `FieldConfig` is written into a `MappableRingBuffer` exactly like `Config`.
- **Depth fields require screen layer 0** (spec §5, depth findings §"Verified in-game results"): `GameRenderer.renderLevel` clears the single scene depth buffer before the first-person hand, so at layer 1+ the depth buffer holds only the hand. The Java side sets `fld_depth_valid = 0` when the effect's `screen_layer != 0` (and on nodes other than 26.2, whose reversed-depth recipe is unverified), warns once per definition, and the shader returns the neutral value for any depth/world field. A `space: world` field or any of `depth`/`depth_gradient`/`normal_facing`/`world_pos` therefore runs only at layer 0.
- There is **no test suite** (`test NO-SOURCE`). Verify in this order (AGENTS.md "Build and verify"): (a) build all six nodes; (b) compile a throwaway `main()` against the built classes and assert (method 2); (c) `javap` against the real deobf jar for any uncertain MC API; (d) in-game testing by the human partner. **Never claim a visual or shader result** — record what the partner reports.
- Build commands: `.\gradlew.bat :<node>:build`; Fabric nodes are `26.2`, `26.1.2`, `1.21.11`; NeoForge nodes are the same names with `-neoforge`. All six must build for every task that touches shared code or a shader.
- **Execution order (step 4 before step 3).** This plan must be implemented **before** `docs/superpowers/plans/2026-09-19-masks.md` (step 3), even though the spec numbers step 3 first. The masks plan consumes this library's content directly: its coverage shader imports `assets/vfxweaver/shaders/include/field.glsl` and calls the shared 2D/3D `vfx_shape_sdf`/`vfx_shape_coverage`; the 3D `sphere`/`box` helpers (`vfx_shape_sphere_sdf`/`vfx_shape_box_sdf`, `vfx_shape_sdf_3d`) and the 2D/3D `vfx_shape_sdf_dispatch` are created here (Task 5) and only **consumed** by masks Task 4; and masks reuse the depth/world reconstruction this plan creates. Starting step 3 before this plan's `field.glsl` and `VFXField`/`VFXFieldProgram` exist would leave dangling `#moj_import`s and missing shape math. The spec's §9 step numbers are unchanged — only the execution order is; the masks plan's dependency section says the same.
- **Bindings are the shared layer, not this library's.** The shape primitives this library owns are positioned and sized by the world-coordinate binding layer added in the masks plan (`dev.vfxweaver.effect.BoundParam` + `VFXWorldBindings`, already shared by effect params and graph `bind` nodes). A mask may bind a shape `center` to `camera`/`player`/`entity`/`point`/`block` and derive scalars (e.g. `distance`) or the entity `screen_rect`; this library stays MC-free and does not implement that resolution, it only provides the SDFs the bound shape is evaluated with. Do not add a second binding path here.

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

A "run it to verify it fails" step runs only the `javac` line (expected: the missing package/class); a "build and run" step runs both and expects a clean `Check OK` line. Assertion counts are approximate — a check passes on "zero failures", not on an exact count.

## File Structure

- `src/main/java/dev/vfxweaver/field/VFXFieldType.java` — create: the three value types and the coercion table.
- `src/main/java/dev/vfxweaver/field/VFXFieldFn.java` — create: the built-in function set (including the `shape` primitive function), output types, per-function parameter names/defaults and integer flags.
- `src/main/java/dev/vfxweaver/field/VFXFieldOp.java` — create: the composition operators and their arity.
- `src/main/java/dev/vfxweaver/field/VFXField.java` — create: one parsed field node (function or composition), parser, caps, type inference, depth requirement.
- `src/main/java/dev/vfxweaver/field/VFXFieldValueWriter.java` — create: the MC-free write target the client adapts to `Std140Builder`.
- `src/main/java/dev/vfxweaver/field/VFXFieldProgram.java` — create: flatten a `VFXField` into the fixed uniform program (leaves, params, curve pool, post-order instruction list).
- `src/main/java/dev/vfxweaver/effect/VFXEffectType.java` — modify: `fieldCapableInputs()` / `acceptsField` / `fieldNeutral`.
- `src/main/java/dev/vfxweaver/effect/VFXDefinition.java` — modify: replace the "not implemented" field branch with `VFXField.parse`; store/expose `getFields()`.
- `src/main/java/dev/vfxweaver/effect/VFXTimeline.java` — modify: carry the fields, pack each once, add `getFieldProgram`, `fieldNeedsDepth`, `evaluateGraphIndex`.
- `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java` — modify: add `evaluateIndex(int, float)` for packed field parameters.
- `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` — modify: `FieldConfig`/`DepthSampler`/field-texture layouts, field-enabled DENT pipeline, `ProgramInfo` flags.
- `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java` — modify: second ring buffer, `FieldConfig` writer, depth bind, per-pass field environment.
- `src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldEnv.java` — create: reused inverse-view-projection matrix, camera position and screen size.
- `src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldValueWriterAdapter.java` — create: the `VFXFieldValueWriter` adapter over `Std140Builder`.
- `src/client/resources/assets/vfxweaver/shaders/include/field.glsl` — create: the built-in field functions and the composition interpreter.
- `src/client/resources/assets/vfxweaver/shaders/post/dent.fsh` — modify: import the library, multiply `strength` by the field result.
- `src/main/resources/data/vfxweaver/vfx/dent_field_demo.json` — create: the built-in consumer.
- `docs/GUIDE.md`, `docs/CHANGELOG.md`, `docs/ARCHITECTURE.md` — modify: document the field format.

---

### Task 1: Field model, functions and parser

**Files:**
- Create: `src/main/java/dev/vfxweaver/field/VFXFieldType.java`
- Create: `src/main/java/dev/vfxweaver/field/VFXFieldFn.java`
- Create: `src/main/java/dev/vfxweaver/field/VFXFieldOp.java`
- Create: `src/main/java/dev/vfxweaver/field/VFXField.java`
- Test: `%TEMP%\vfxcheck\Check.java` (throwaway, not committed)

**Interfaces:**
- Consumes: `VFXGraph.node(String)` / `VFXGraphInput.literal(float)` / `VFXGraphInput.reference(String)` (already implemented), Gson `JsonObject`/`JsonElement`/`JsonArray`.
- Produces:
  - `VFXFieldType.FLOAT/VEC2/VEC3`, `components() : int`, `combine(VFXFieldType, VFXFieldType) : @Nullable VFXFieldType`.
  - `VFXFieldFn` values `CONSTANT, NOISE, SHAPE, GRADIENT, CURVE, TEXTURE, DEPTH, DEPTH_GRADIENT, NORMAL_FACING, SCREEN_UV, WORLD_POS`; `id()`, `outputType()`, `spatial()`, `needsDepth()`, `hasChannel()`, `hasTexture()`, `hasCurve()`, `hasShape()`, `paramNames() : List<String>`, `paramIndex(String)`, `integerParam(String)`, `defaultParam(String) : float`, `fromString(String)`.
  - `VFXFieldOp.MULTIPLY/ADD/SUBTRACT/MIX/MIN/MAX`, `id()`, `arity()`, `fromString(String)`.
  - `VFXField.parse(String inputName, JsonObject json, @Nullable VFXGraph graph) : VFXField` (throws `IllegalArgumentException` naming the input/function/parameter).
  - `VFXField.fn()/op()/a()/b()/factor()/space()/params()/channel()/texture()/primitive()/fill()/curveTimes()/curveValues()/outputType()/leaves()/depth()/nodes()/needsDepth()/textureLeaves()`.
  - `VFXField.Space.SCREEN/WORLD`, `VFXField.Space.fromString(String)`.
  - `VFXField.MAX_DEPTH = 3`, `MAX_LEAVES = 4`, `MAX_PROGRAM = 8`, `MAX_CURVE_POINTS = 8`, `MAX_TEXTURE_LEAVES = 1`, `MAX_PARAMS = 16`.
  - Shape structural values: `primitive` ∈ {`circle`, `ellipse`, `rect`, `polygon`} (default `circle`), `fill` ∈ {`solid`, `stroke`} (default `solid`); `center: [x, y]` and `repeat: [nx, ny]` (default `[1, 1]`) desugar into the scalar param slots `center_x`/`center_y` and `repeat_x`/`repeat_y`, each element a literal or `{ "from": <node> }`. The **3D** primitives `sphere` (`radius`) and `box` (`half_width`/`half_height`/`half_depth`) are owned by the same library as standalone helpers and consumed where a 3D coordinate exists (the masks plan); the v1 `shape` field function stays the 2D screen set.

- [ ] **Step 1: Write the failing check**

Create `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.field.VFXField;
import dev.vfxweaver.field.VFXFieldType;
import dev.vfxweaver.graph.VFXGraph;

public class Check {
	static int passed = 0;

	static void assertTrue(final String label, final boolean condition) {
		if (!condition) {
			throw new AssertionError(label);
		}
		passed++;
	}

	static void expectThrows(final String label, final Runnable runnable, final String fragment) {
		try {
			runnable.run();
			throw new AssertionError(label + ": expected a parse error, got none");
		} catch (IllegalArgumentException e) {
			if (!e.getMessage().contains(fragment)) {
				throw new AssertionError(label + ": '" + e.getMessage() + "' does not contain '" + fragment + "'");
			}
			passed++;
		}
	}

	static VFXField field(final String body) {
		final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
		final JsonObject graphJson = JsonParser.parseString("{\"version\":1,\"nodes\":[{\"id\":\"n1\",\"kind\":\"constant\",\"inputs\":{\"value\":2}}]}").getAsJsonObject();
		return VFXField.parse("intensity", json, VFXGraph.parse("test:field", graphJson));
	}

	public static void main(final String[] args) {
		final VFXField noise = field("{\"field\":\"noise\",\"scale\":4.5,\"octaves\":3}");
		assertTrue("noise fn", noise.fn().id().equals("noise"));
		assertTrue("noise space defaults world", noise.space() == VFXField.Space.WORLD);
		assertTrue("noise type float", noise.outputType() == VFXFieldType.FLOAT);
		assertTrue("noise leaves", noise.leaves() == 1);
		assertTrue("noise needs depth (world)", noise.needsDepth());

		final VFXField screen = field("{\"field\":\"noise\",\"space\":\"screen\",\"scale\":2}");
		assertTrue("screen noise no depth", !screen.needsDepth());

		final VFXField circle = field("{\"field\":\"shape\",\"space\":\"screen\",\"primitive\":\"circle\",\"radius\":0.3}");
		assertTrue("shape fn", circle.fn().id().equals("shape"));
		assertTrue("shape primitive circle", "circle".equals(circle.primitive()));
		assertTrue("shape fill defaults solid", "solid".equals(circle.fill()));
		assertTrue("shape type float", circle.outputType() == VFXFieldType.FLOAT);

		final VFXField ring = field("{\"field\":\"shape\",\"space\":\"screen\",\"primitive\":\"ellipse\","
			+ "\"radius_x\":0.3,\"radius_y\":0.2,\"fill\":\"stroke\",\"stroke_width\":0.05}");
		assertTrue("ring is a stroked ellipse", "ellipse".equals(ring.primitive()) && "stroke".equals(ring.fill()));

		final VFXField tiled = field("{\"field\":\"shape\",\"space\":\"screen\",\"center\":[{\"from\":\"n1\"},0.75],\"repeat\":[{\"from\":\"n1\"},2]}");
		assertTrue("center_x from node", tiled.params().get("center_x").reference());
		assertTrue("repeat_x from node", tiled.params().get("repeat_x").reference());
		assertTrue("center_y parsed", tiled.params().containsKey("center_y"));

		final VFXField from = field("{\"field\":\"shape\",\"space\":\"screen\",\"radius\":{\"from\":\"n1\"}}");
		assertTrue("param from node", from.params().get("radius").reference());
		assertTrue("param node id", "n1".equals(from.params().get("radius").node()));

		final VFXField texture = field("{\"field\":\"texture\",\"space\":\"screen\",\"texture\":\"minecraft:textures/block/stone\",\"channel\":\"luminance\"}");
		assertTrue("texture channel float", texture.outputType() == VFXFieldType.FLOAT);
		assertTrue("texture leaves", texture.textureLeaves() == 1);

		final VFXField plainTexture = field("{\"field\":\"texture\",\"space\":\"screen\",\"texture\":\"minecraft:textures/block/stone\"}");
		assertTrue("texture no channel vec3", plainTexture.outputType() == VFXFieldType.VEC3);

		final VFXField uv = field("{\"field\":\"screen_uv\"}");
		assertTrue("screen_uv vec2", uv.outputType() == VFXFieldType.VEC2);

		final VFXField composed = field(
			"{\"op\":\"multiply\",\"a\":{\"field\":\"noise\",\"space\":\"screen\"},"
				+ "\"b\":{\"field\":\"gradient\",\"space\":\"screen\"}}");
		assertTrue("composition leaves", composed.leaves() == 2);
		assertTrue("composition depth", composed.depth() == 2);
		assertTrue("composition type", composed.outputType() == VFXFieldType.FLOAT);

		final VFXField mixed = field(
			"{\"op\":\"mix\",\"a\":{\"field\":\"noise\",\"space\":\"screen\"},"
				+ "\"b\":{\"field\":\"shape\",\"space\":\"screen\"},"
				+ "\"factor\":{\"field\":\"shape\",\"space\":\"screen\",\"fill\":\"stroke\"}}");
		assertTrue("mix ok", mixed.op().arity() == 3);

		expectThrows("unknown fn", () -> field("{\"field\":\"teleport\"}"), "unknown field function 'teleport'");
		expectThrows("unknown param", () -> field("{\"field\":\"noise\",\"bogus\":1}"), "unknown parameter 'bogus'");
		expectThrows("space on screen_uv", () -> field("{\"field\":\"screen_uv\",\"space\":\"screen\"}"), "'space'");
		expectThrows("bad primitive", () -> field("{\"field\":\"shape\",\"primitive\":\"star\"}"), "primitive");
		expectThrows("fill on noise", () -> field("{\"field\":\"noise\",\"fill\":\"stroke\"}"), "fill");
		expectThrows("center arity", () -> field("{\"field\":\"shape\",\"center\":[0.5]}"), "center");
		expectThrows("bad channel", () -> field("{\"field\":\"texture\",\"texture\":\"a:b\",\"channel\":\"q\"}"), "channel");
		expectThrows("channel without texture fn", () -> field("{\"field\":\"noise\",\"channel\":\"r\"}"), "channel");
		expectThrows("vec2 x vec3", () -> field(
			"{\"op\":\"multiply\",\"a\":{\"field\":\"screen_uv\"},\"b\":{\"field\":\"world_pos\"}}"),
			"cannot combine");
		expectThrows("mix factor non-float", () -> field(
			"{\"op\":\"mix\",\"a\":{\"field\":\"noise\",\"space\":\"screen\"},"
				+ "\"b\":{\"field\":\"shape\",\"space\":\"screen\"},"
				+ "\"factor\":{\"field\":\"world_pos\"}}"),
			"factor");
		expectThrows("unknown ref", () -> field("{\"field\":\"noise\",\"scale\":{\"from\":\"ghost\"}}"), "unknown node 'ghost'");
		expectThrows("texture leaf cap", () -> field(
			"{\"op\":\"add\","
				+ "\"a\":{\"field\":\"texture\",\"space\":\"screen\",\"texture\":\"a:b\"},"
				+ "\"b\":{\"field\":\"texture\",\"space\":\"screen\",\"texture\":\"a:c\"}}"),
			"texture");
		expectThrows("leaf cap", () -> field(
			"{\"op\":\"add\",\"a\":{\"op\":\"add\",\"a\":{\"field\":\"noise\",\"space\":\"screen\"},"
				+ "\"b\":{\"field\":\"noise\",\"space\":\"screen\"}},"
				+ "\"b\":{\"op\":\"add\",\"a\":{\"field\":\"noise\",\"space\":\"screen\"},"
				+ "\"b\":{\"field\":\"noise\",\"space\":\"screen\"}}}"),
			"leaves");

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the shared standalone-check recipe (Global Constraints), `javac` line only — the classes do not exist yet.

Expected: non-zero exit, errors mentioning `dev.vfxweaver.field.VFXField`.

- [ ] **Step 3: Create the value-type enum and coercion table**

Create `src/main/java/dev/vfxweaver/field/VFXFieldType.java`:

```java
package dev.vfxweaver.field;

import org.jspecify.annotations.Nullable;

/**
 * The value domain a field produces (spec §2). Types are fixed by the function, never declared
 * by the author.
 */
public enum VFXFieldType {
	FLOAT(1), VEC2(2), VEC3(3);

	private final int components;

	VFXFieldType(final int components) {
		this.components = components;
	}

	/** Number of scalar components (1, 2 or 3). */
	public int components() {
		return this.components;
	}

	/**
	 * The type of {@code a op b}, or {@code null} when the pair cannot be coerced.
	 * A {@code float} broadcasts against any vector; equal vector types combine componentwise;
	 * {@code vec2} against {@code vec3} is a parse error (spec §2).
	 */
	public static @Nullable VFXFieldType combine(final VFXFieldType a, final VFXFieldType b) {
		if (a == b) {
			return a;
		}
		if (a == FLOAT) {
			return b;
		}
		if (b == FLOAT) {
			return a;
		}
		return null;
	}
}
```

- [ ] **Step 4: Create the function metadata enum**

Create `src/main/java/dev/vfxweaver/field/VFXFieldFn.java`:

```java
package dev.vfxweaver.field;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The built-in per-pixel field functions (spec §2, §9 step 4). Each function has a fixed output
 * type and at most {@link VFXField#MAX_PARAMS} numeric parameters, addressed by name in JSON and
 * packed positionally into the shader's generic parameter vector.
 */
public enum VFXFieldFn {
	CONSTANT("constant", VFXFieldType.FLOAT, false, false, false, false, List.of("value"), new float[]{1.0F}),
	NOISE("noise", VFXFieldType.FLOAT, true, false, false, false, List.of("scale", "octaves", "gain", "lacunarity"), new float[]{1.0F, 1.0F, 0.5F, 2.0F}),
	// The shared shape primitive set: the v1 field function uses the 2D screen kinds; the same
	// library also owns the 3D world helpers (sphere/box) consumed where a 3D coordinate exists.
	SHAPE("shape", VFXFieldType.FLOAT, true, false, false, false,
		List.of("center_x", "center_y", "rotation", "radius", "radius_x", "radius_y", "half_width", "half_height", "corner_radius", "sides", "stroke_width", "softness", "repeat_x", "repeat_y"),
		new float[]{0.5F, 0.5F, 0.0F, 0.35F, 0.35F, 0.35F, 0.25F, 0.25F, 0.0F, 6.0F, 0.05F, 0.01F, 1.0F, 1.0F}),
	GRADIENT("gradient", VFXFieldType.FLOAT, true, false, false, false, List.of("angle", "offset", "scale", "softness"), new float[]{0.0F, 0.0F, 1.0F, 0.0F}),
	CURVE("curve", VFXFieldType.FLOAT, true, false, false, true, List.of("scale"), new float[]{1.0F}),
	TEXTURE("texture", VFXFieldType.VEC3, true, true, false, false, List.of("scale_x", "scale_y", "offset_x", "offset_y"), new float[]{1.0F, 1.0F, 0.0F, 0.0F}),
	DEPTH("depth", VFXFieldType.FLOAT, false, false, false, false, List.of("near", "far"), new float[]{0.0F, 1.0F}),
	DEPTH_GRADIENT("depth_gradient", VFXFieldType.FLOAT, false, false, false, false, List.of("near", "far"), new float[]{0.0F, 1.0F}),
	NORMAL_FACING("normal_facing", VFXFieldType.FLOAT, false, false, false, false, List.of("axis_x", "axis_y", "axis_z", "threshold"), new float[]{0.0F, 1.0F, 0.0F, 0.5F}),
	SCREEN_UV("screen_uv", VFXFieldType.VEC2, false, false, false, false, List.of(), new float[0]),
	WORLD_POS("world_pos", VFXFieldType.VEC3, false, false, false, false, List.of(), new float[0]);

	private final String id;
	private final VFXFieldType outputType;
	private final boolean spatial;
	private final boolean hasChannel;
	private final boolean needsDepth;
	private final boolean hasCurve;
	private final List<String> paramNames;
	private final float[] defaultParams;

	VFXFieldFn(final String id, final VFXFieldType outputType, final boolean spatial, final boolean hasChannel, final boolean needsDepth, final boolean hasCurve, final List<String> paramNames, final float[] defaultParams) {
		this.id = id;
		this.outputType = outputType;
		this.spatial = spatial;
		this.hasChannel = hasChannel;
		this.needsDepth = needsDepth;
		this.hasCurve = hasCurve;
		this.paramNames = paramNames;
		this.defaultParams = defaultParams;
	}

	/** The datapack spelling of this function. */
	public String id() {
		return this.id;
	}

	/** The type this function produces before any channel selection. */
	public VFXFieldType outputType() {
		return this.outputType;
	}

	/** True when {@code space} is meaningful for this function (spec §2). */
	public boolean spatial() {
		return this.spatial;
	}

	/** True when {@code channel} is meaningful for this function. */
	public boolean hasChannel() {
		return this.hasChannel;
	}

	/** True when this function reads the scene depth. */
	public boolean needsDepth() {
		return this.needsDepth;
	}

	/** True when this function needs a {@code points} array. */
	public boolean hasCurve() {
		return this.hasCurve;
	}

	/** True when this function samples a texture. */
	public boolean hasTexture() {
		return this == TEXTURE;
	}

	/** True when this function selects a shape primitive and a fill mode. */
	public boolean hasShape() {
		return this == SHAPE;
	}

	/** Numeric parameter names, in packing order. */
	public List<String> paramNames() {
		return this.paramNames;
	}

	/**
	 * The packing slot of a numeric parameter.
	 *
	 * @return the slot, or {@code -1} when {@code name} is not a parameter of this function
	 */
	public int paramIndex(final String name) {
		return this.paramNames.indexOf(name);
	}

	/**
	 * True when the parameter is an integer (rounded after graph evaluation, spec §2).
	 */
	public boolean integerParam(final String name) {
		if (this == NOISE) {
			return "octaves".equals(name);
		}
		if (this == SHAPE) {
			return "sides".equals(name) || "repeat_x".equals(name) || "repeat_y".equals(name);
		}
		return false;
	}

	/**
	 * The default of a numeric parameter.
	 *
	 * @return the default, or {@code 0} when {@code name} is not a parameter of this function
	 */
	public float defaultParam(final String name) {
		final int index = paramIndex(name);
		return index < 0 ? 0.0F : this.defaultParams[index];
	}

	/**
	 * Resolves a function from its datapack spelling. {@code grid}, {@code ring} and {@code radial}
	 * are not functions: a grid is a {@code shape} with {@code repeat}, a ring is a {@code shape}
	 * with {@code primitive: ellipse} and {@code fill: stroke}.
	 *
	 * @param name raw string, e.g. {@code "noise"}
	 * @return the matching function, or {@code null} when unknown
	 */
	public static @Nullable VFXFieldFn fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXFieldFn fn : values()) {
			if (fn.id.equals(normalized)) {
				return fn;
			}
		}
		return null;
	}
}
```

- [ ] **Step 5: Create the operator enum**

Create `src/main/java/dev/vfxweaver/field/VFXFieldOp.java`:

```java
package dev.vfxweaver.field;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The composition operators of a field tree (spec §2). {@code mix} is the only ternary operator
 * and requires a {@code float} factor.
 */
public enum VFXFieldOp {
	MULTIPLY("multiply", 2), ADD("add", 2), SUBTRACT("subtract", 2),
	MIX("mix", 3), MIN("min", 2), MAX("max", 2);

	private final String id;
	private final int arity;

	VFXFieldOp(final String id, final int arity) {
		this.id = id;
		this.arity = arity;
	}

	public String id() {
		return this.id;
	}

	public int arity() {
		return this.arity;
	}

	/**
	 * Resolves an operator from its datapack spelling.
	 *
	 * @return the matching operator, or {@code null} when unknown
	 */
	public static @Nullable VFXFieldOp fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXFieldOp op : values()) {
			if (op.id.equals(normalized)) {
				return op;
			}
		}
		return null;
	}
}
```

- [ ] **Step 6: Create the field tree and parser**

Create `src/main/java/dev/vfxweaver/field/VFXField.java`:

```java
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
```

- [ ] **Step 7: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line (assertion counts are approximate — zero failures is the pass condition).

- [ ] **Step 8: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. A failure means an accidental `net.minecraft.*` / `com.mojang.*` import in `dev.vfxweaver.field` — fix the import, not the build.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dev/vfxweaver/field/VFXFieldType.java src/main/java/dev/vfxweaver/field/VFXFieldFn.java src/main/java/dev/vfxweaver/field/VFXFieldOp.java src/main/java/dev/vfxweaver/field/VFXField.java
git commit -m "feat(field): parse and validate the per-pixel field model (step 4)"
```

---

### Task 2: Field program packer

**Files:**
- Create: `src/main/java/dev/vfxweaver/field/VFXFieldValueWriter.java`
- Create: `src/main/java/dev/vfxweaver/field/VFXFieldProgram.java`
- Modify: `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java` (add `evaluateIndex(int, float)`)
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the packer check)

**Interfaces:**
- Consumes: `VFXField` accessors, `VFXFieldFn`/`VFXFieldOp` metadata (Task 1), `VFXGraph.indexOf(String)`, `VFXGraphEvaluator`.
- Produces:
  - `VFXGraphEvaluator.evaluateIndex(int index, float fallback) : float` — pull-evaluate by precomputed slot (additive; `evaluate(String,float)` stays).
  - `VFXFieldValueWriter` with `void putFloat(float)` and `void putVec4(float, float, float, float)`.
  - `VFXFieldProgram.of(String inputName, VFXField field, @Nullable VFXGraph graph) : VFXFieldProgram`.
  - `VFXFieldProgram.write(VFXFieldValueWriter out, @Nullable VFXGraphEvaluator evaluator, float uniform, float depthValid, float invWidth, float invHeight, Matrix4fc invViewProj, float camX, float camY, float camZ) : void` — writes the exact `FieldConfig` field order the shader declares.
  - `VFXFieldProgram.leafCount()/outputType()/needsDepth()/texture()/textureLeaf()/inputName()/programLength()`.

- [ ] **Step 1: Write the failing check**

Overwrite `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonParser;
import dev.vfxweaver.field.VFXField;
import dev.vfxweaver.field.VFXFieldProgram;
import dev.vfxweaver.field.VFXFieldValueWriter;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;

public class Check {
	static int passed = 0;

	static void expect(final String label, final float actual, final float expected) {
		if (Math.abs(actual - expected) > 1.0e-4F) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
		passed++;
	}

	static class Recorder implements VFXFieldValueWriter {
		final List<Float> floats = new ArrayList<>();

		public void putFloat(final float value) {
			this.floats.add(value);
		}

		public void putVec4(final float x, final float y, final float z, final float w) {
			this.floats.add(x);
			this.floats.add(y);
			this.floats.add(z);
			this.floats.add(w);
		}
	}

	static VFXFieldProgram program(final String body, final VFXGraph graph) {
		return VFXFieldProgram.of("intensity", VFXField.parse("intensity", JsonParser.parseString(body).getAsJsonObject(), graph), graph);
	}

	public static void main(final String[] args) {
		final VFXGraph graph = VFXGraph.parse("test:field", JsonParser.parseString(
			"{\"version\":1,\"nodes\":[{\"id\":\"n1\",\"kind\":\"constant\",\"inputs\":{\"value\":3}}]}").getAsJsonObject());

		// A composition of noise * shape: two leaves then one multiply instruction.
		final VFXFieldProgram composed = program(
			"{\"op\":\"multiply\","
				+ "\"a\":{\"field\":\"noise\",\"space\":\"screen\",\"scale\":2,\"octaves\":3},"
				+ "\"b\":{\"field\":\"shape\",\"space\":\"screen\",\"primitive\":\"circle\",\"radius\":0.4}}", graph);
		expect("leaf count", composed.leafCount(), 2.0F);
		final Recorder recorder = new Recorder();
		composed.write(recorder, null, 1.0F, 1.0F, 1.0F / 1920.0F, 1.0F / 1080.0F, new Matrix4f(), 1.0F, 2.0F, 3.0F);
		final float[] f = new float[recorder.floats.size()];
		for (int i = 0; i < f.length; i++) {
			f[i] = recorder.floats.get(i);
		}
		// Write order (see VFXFieldProgram.write / field.glsl):
		// 0 uniform, 1 depth_valid, 2 leaf_count,
		// 3..6 fn vec4, 7..10 space vec4, 11..14 channel vec4,
		// 15..18 primitive vec4, 19..22 fill vec4,
		// 23..86 params (MAX_LEAVES * MAX_PARAMS = 4 * 16) with leaf 0 slots 0..15 at 23..38,
		// 87 curve_count, 88..103 four curve vec4, 104..111 program,
		// 112..127 mat4, 128..131 camera vec4, 132..135 inv_size vec4.
		expect("uniform", f[0], 1.0F);
		expect("depth valid", f[1], 1.0F);
		expect("leaf count", f[2], 2.0F);
		expect("leaf 0 fn NOISE", f[3], 1.0F);
		expect("leaf 1 fn SHAPE", f[4], 2.0F);
		expect("leaf 0 scale", f[23], 2.0F);
		expect("leaf 0 octaves", f[24], 3.0F);
		expect("prog 0 push leaf 0", f[104], -1.0F);
		expect("prog 1 push leaf 1", f[105], -2.0F);
		expect("prog 2 multiply", f[106], 0.0F);

		// Integer parameter rounding via a graph reference.
		final VFXFieldProgram rounded = program(
			"{\"field\":\"noise\",\"space\":\"screen\",\"octaves\":{\"from\":\"n1\"}}", graph);
		final VFXGraphEvaluator evaluator = new VFXGraphEvaluator(graph, 1L);
		evaluator.beginFrame(0.0F);
		final Recorder roundedRecorder = new Recorder();
		rounded.write(roundedRecorder, evaluator, 1.0F, 1.0F, 0.0F, 0.0F, new Matrix4f(), 0.0F, 0.0F, 0.0F);
		expect("rounded octaves", roundedRecorder.floats.get(24), 3.0F);

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the shared standalone-check recipe (Global Constraints), `javac` line only.

Expected: `javac` errors on `dev.vfxweaver.field.VFXFieldProgram`.

- [ ] **Step 3: Add index-based evaluation to the graph evaluator**

In `src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java`, add after `evaluate(String, float)`:

```java
	/**
	 * Evaluates a node by its precomputed cache slot, for packed consumers (the field program)
	 * that resolved {@code { "from": <node> }} ids once at pack time.
	 *
	 * @param index    slot from {@link VFXGraph#indexOf(String)}
	 * @param fallback value returned when the slot is out of range
	 */
	public float evaluateIndex(final int index, final float fallback) {
		return index < 0 || index >= this.cache.length ? fallback : eval(index);
	}
```

- [ ] **Step 4: Create the MC-free writer interface**

Create `src/main/java/dev/vfxweaver/field/VFXFieldValueWriter.java`:

```java
package dev.vfxweaver.field;

/**
 * The field-program write target. The client adapts {@code com.mojang.blaze3d.buffers.Std140Builder}
 * to this interface so the packer stays free of Minecraft types and remains unit-checkable.
 */
public interface VFXFieldValueWriter {
	/** Appends one scalar (std140, 4 bytes). */
	void putFloat(float value);

	/** Appends one vec4 (std140, 16-byte aligned). */
	void putVec4(float x, float y, float z, float w);
}
```

- [ ] **Step 5: Create the packer**

Create `src/main/java/dev/vfxweaver/field/VFXFieldProgram.java`:

```java
package dev.vfxweaver.field;

import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
import dev.vfxweaver.graph.VFXGraphInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * A {@link VFXField} flattened into the fixed uniform program the shader interprets. Packing
 * happens once per definition instance; the per-frame write only resolves graph parameters and
 * pushes values, so it allocates nothing.
 *
 * <p><b>Contract (AGENTS.md UBO field-order rule):</b> {@link #write} emits fields in exactly the
 * order {@code assets/vfxweaver/shaders/include/field.glsl} declares them, and
 * {@code VFXShaderPrograms.FIELD_CONFIG_SIZE} sizes the same block. The three must change together
 * — std140 offsets are positional.
 */
public final class VFXFieldProgram {
	private final float[] leafFn;
	private final float[] leafSpace;
	private final float[] leafChannel;
	/** Shape primitive code per leaf ({@code circle}=0, {@code ellipse}=1, {@code rect}=2, {@code polygon}=3). */
	private final float[] leafPrimitive;
	/** Shape fill code per leaf ({@code solid}=0, {@code stroke}=1). */
	private final float[] leafFill;
	/** Literal value per packed parameter ({@link VFXField#MAX_PARAMS} per leaf). */
	private final float[] paramLiteral;
	/** Graph slot per packed parameter, or -1 for a literal. */
	private final int[] paramNode;
	/** True when the parameter is an integer and must be rounded after evaluation. */
	private final boolean[] paramInteger;
	private final float[] curveTimes;
	private final float[] curveValues;
	private final int curveCount;
	/** Post-order program: a value {@code <= -1} pushes leaf {@code (-value - 1)}; {@code >= 0} combines. */
	private final float[] program;
	private final int programLength;
	private final int leafCount;
	private final VFXFieldType outputType;
	private final boolean needsDepth;
	private final @Nullable String texture;
	private final String inputName;

	private VFXFieldProgram(final float[] leafFn, final float[] leafSpace, final float[] leafChannel, final float[] leafPrimitive, final float[] leafFill, final float[] paramLiteral, final int[] paramNode, final boolean[] paramInteger, final float[] curveTimes, final float[] curveValues, final int curveCount, final float[] program, final int programLength, final int leafCount, final VFXFieldType outputType, final boolean needsDepth, final @Nullable String texture, final String inputName) {
		this.leafFn = leafFn;
		this.leafSpace = leafSpace;
		this.leafChannel = leafChannel;
		this.leafPrimitive = leafPrimitive;
		this.leafFill = leafFill;
		this.paramLiteral = paramLiteral;
		this.paramNode = paramNode;
		this.paramInteger = paramInteger;
		this.curveTimes = curveTimes;
		this.curveValues = curveValues;
		this.curveCount = curveCount;
		this.program = program;
		this.programLength = programLength;
		this.leafCount = leafCount;
		this.outputType = outputType;
		this.needsDepth = needsDepth;
		this.texture = texture;
		this.inputName = inputName;
	}

	/**
	 * Flattens a field tree.
	 *
	 * @param inputName the effect input (for diagnostics)
	 * @param field     the parsed field
	 * @param graph     the definition's graph, or {@code null} (then no parameter may reference a node)
	 * @return the packed program, never {@code null}
	 */
	public static VFXFieldProgram of(final String inputName, final VFXField field, final @Nullable VFXGraph graph) {
		final float[] leafFn = new float[VFXField.MAX_LEAVES];
		final float[] leafSpace = new float[VFXField.MAX_LEAVES];
		final float[] leafChannel = new float[VFXField.MAX_LEAVES];
		final float[] leafPrimitive = new float[VFXField.MAX_LEAVES];
		final float[] leafFill = new float[VFXField.MAX_LEAVES];
		final float[] paramLiteral = new float[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS];
		final int[] paramNode = new int[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS];
		final boolean[] paramInteger = new boolean[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS];
		Arrays.fill(paramNode, -1);
		final float[] curveTimes = new float[VFXField.MAX_CURVE_POINTS];
		final float[] curveValues = new float[VFXField.MAX_CURVE_POINTS];
		final int[] curveCount = new int[1];
		final List<Float> program = new ArrayList<>(VFXField.MAX_PROGRAM);
		final int[] leafIndex = new int[1];
		final String[] texture = new String[1];
		flatten(field, leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		final float[] programArray = new float[VFXField.MAX_PROGRAM];
		// Unused slots are NaN so the shader stops at the first one (the program is not padded
		// with a valid op).
		Arrays.fill(programArray, Float.NaN);
		for (int i = 0; i < program.size(); i++) {
			programArray[i] = program.get(i);
		}
		return new VFXFieldProgram(leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount[0], programArray, program.size(), leafIndex[0], field.outputType(), field.needsDepth(), texture[0], inputName);
	}

	private static void flatten(final VFXField field, final float[] leafFn, final float[] leafSpace, final float[] leafChannel, final float[] leafPrimitive, final float[] leafFill, final float[] paramLiteral, final int[] paramNode, final boolean[] paramInteger, final float[] curveTimes, final float[] curveValues, final int[] curveCount, final List<Float> program, final int[] leafIndex, final String[] texture, final @Nullable VFXGraph graph) {
		if (field.fn() != null) {
			final int leaf = leafIndex[0]++;
			leafFn[leaf] = field.fn().ordinal();
			leafSpace[leaf] = field.space() == VFXField.Space.WORLD ? 1.0F : 0.0F;
			leafChannel[leaf] = channelCode(field.channel());
			leafPrimitive[leaf] = primitiveCode(field.primitive());
			leafFill[leaf] = fillCode(field.fill());
			final List<String> names = field.fn().paramNames();
			for (int i = 0; i < names.size() && i < VFXField.MAX_PARAMS; i++) {
				final VFXGraphInput input = field.params().get(names.get(i));
				final int slot = leaf * VFXField.MAX_PARAMS + i;
				paramLiteral[slot] = input == null ? field.fn().defaultParam(names.get(i)) : input.literal();
				if (input != null && input.reference() && graph != null) {
					final Integer node = graph.indexOf(input.node());
					paramNode[slot] = node == null ? -1 : node;
				}
				paramInteger[slot] = field.fn().integerParam(names.get(i));
			}
			if (field.fn() == VFXFieldFn.CURVE) {
				final int count = Math.min(field.curveTimes().length, VFXField.MAX_CURVE_POINTS);
				System.arraycopy(field.curveTimes(), 0, curveTimes, 0, count);
				System.arraycopy(field.curveValues(), 0, curveValues, 0, count);
				curveCount[0] = count;
			}
			if (field.fn() == VFXFieldFn.TEXTURE && texture[0] == null) {
				texture[0] = field.texture();
			}
			program.add(-1.0F - leaf);
			return;
		}
		flatten(field.a(), leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		flatten(field.b(), leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		if (field.factor() != null) {
			flatten(field.factor(), leafFn, leafSpace, leafChannel, leafPrimitive, leafFill, paramLiteral, paramNode, paramInteger, curveTimes, curveValues, curveCount, program, leafIndex, texture, graph);
		}
		program.add((float) field.op().ordinal());
	}

	private static float channelCode(final @Nullable String channel) {
		if (channel == null) {
			return 5.0F;
		}
		return switch (channel) {
			case "r" -> 0.0F;
			case "g" -> 1.0F;
			case "b" -> 2.0F;
			case "a" -> 3.0F;
			default -> 4.0F;
		};
	}

	private static float primitiveCode(final @Nullable String primitive) {
		if (primitive == null) {
			return 0.0F;
		}
		return switch (primitive) {
			case "ellipse" -> 1.0F;
			case "rect" -> 2.0F;
			case "polygon" -> 3.0F;
			default -> 0.0F;
		};
	}

	private static float fillCode(final @Nullable String fill) {
		return "stroke".equals(fill) ? 1.0F : 0.0F;
	}

	/**
	 * Writes the {@code FieldConfig} block in the shader's declaration order.
	 *
	 * @param out         the write target
	 * @param evaluator   per-frame graph evaluator, or {@code null} when no parameter references a node
	 * @param uniform     the input's uniform-domain value (already fade-weighted)
	 * @param depthValid  1 when the scene depth is valid for this pass, else 0
	 * @param invWidth    1 / output width
	 * @param invHeight   1 / output height
	 * @param invViewProj inverse (projection * viewRotation) with the camera translation applied
	 * @param camX        camera world X
	 * @param camY        camera world Y
	 * @param camZ        camera world Z
	 */
	public void write(final VFXFieldValueWriter out, final @Nullable VFXGraphEvaluator evaluator, final float uniform, final float depthValid, final float invWidth, final float invHeight, final Matrix4fc invViewProj, final float camX, final float camY, final float camZ) {
		out.putFloat(uniform);
		out.putFloat(depthValid);
		out.putFloat(this.leafCount);
		out.putVec4(this.leafFn[0], this.leafFn[1], this.leafFn[2], this.leafFn[3]);
		out.putVec4(this.leafSpace[0], this.leafSpace[1], this.leafSpace[2], this.leafSpace[3]);
		out.putVec4(this.leafChannel[0], this.leafChannel[1], this.leafChannel[2], this.leafChannel[3]);
		out.putVec4(this.leafPrimitive[0], this.leafPrimitive[1], this.leafPrimitive[2], this.leafPrimitive[3]);
		out.putVec4(this.leafFill[0], this.leafFill[1], this.leafFill[2], this.leafFill[3]);
		for (int leaf = 0; leaf < VFXField.MAX_LEAVES; leaf++) {
			for (int group = 0; group < VFXField.MAX_PARAMS / 4; group++) {
				out.putVec4(
					param(leaf, group * 4, evaluator),
					param(leaf, group * 4 + 1, evaluator),
					param(leaf, group * 4 + 2, evaluator),
					param(leaf, group * 4 + 3, evaluator)
				);
			}
		}
		out.putFloat(this.curveCount);
		for (int group = 0; group < 4; group++) {
			final int i = group * 2;
			out.putVec4(this.curveTimes[i], this.curveValues[i], this.curveTimes[i + 1], this.curveValues[i + 1]);
		}
		for (int i = 0; i < VFXField.MAX_PROGRAM; i++) {
			out.putFloat(this.program[i]);
		}
		out.putVec4(invViewProj.m00(), invViewProj.m01(), invViewProj.m02(), invViewProj.m03());
		out.putVec4(invViewProj.m10(), invViewProj.m11(), invViewProj.m12(), invViewProj.m13());
		out.putVec4(invViewProj.m20(), invViewProj.m21(), invViewProj.m22(), invViewProj.m23());
		out.putVec4(invViewProj.m30(), invViewProj.m31(), invViewProj.m32(), invViewProj.m33());
		out.putVec4(camX, camY, camZ, 0.0F);
		out.putVec4(invWidth, invHeight, 0.0F, 0.0F);
	}

	private float param(final int leaf, final int slot, final @Nullable VFXGraphEvaluator evaluator) {
		final int index = leaf * VFXField.MAX_PARAMS + slot;
		final float value;
		if (this.paramNode[index] >= 0 && evaluator != null) {
			value = evaluator.evaluateIndex(this.paramNode[index], this.paramLiteral[index]);
		} else {
			value = this.paramLiteral[index];
		}
		return this.paramInteger[index] ? Math.round(value) : value;
	}

	/** Number of function leaves in the program. */
	public float leafCount() {
		return this.leafCount;
	}

	/** The output type (Java-side only; the shader always computes a vec3). */
	public VFXFieldType outputType() {
		return this.outputType;
	}

	/** True when the shader needs the scene depth for this program. */
	public boolean needsDepth() {
		return this.needsDepth;
	}

	/** The texture resource id, or {@code null}. */
	public @Nullable String texture() {
		return this.texture;
	}

	/** True when a texture leaf is present. */
	public boolean textureLeaf() {
		return this.texture != null;
	}

	/** The effect input this program belongs to. */
	public String inputName() {
		return this.inputName;
	}

	/** Total program length (leaf pushes plus combine instructions). */
	public int programLength() {
		return this.programLength;
	}
}
```

- [ ] **Step 6: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then `Check OK`. If an index assertion fails, re-derive it from the write order documented in Step 1 and fix the check's index — the `write` order is the shader contract.

- [ ] **Step 7: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six (`joml` `Matrix4fc` is already on the main classpath).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/vfxweaver/field/VFXFieldProgram.java src/main/java/dev/vfxweaver/field/VFXFieldValueWriter.java src/main/java/dev/vfxweaver/graph/VFXGraphEvaluator.java
git commit -m "feat(field): pack field trees into a fixed uniform program"
```

---

### Task 3: Field inputs in the effect format and timeline

**Files:**
- Modify: `src/main/java/dev/vfxweaver/effect/VFXEffectType.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXTimeline.java`
- Test: `%TEMP%\vfxcheck\Check.java` (overwrite with the wiring/backward-compat check)

**Interfaces:**
- Consumes: `VFXField.parse` (Task 1), `VFXFieldProgram.of` (Task 2), `VFXGraphEvaluator.evaluateIndex` (Task 2).
- Produces:
  - `VFXEffectType.fieldCapableInputs() : Set<String>`, `acceptsField(String) : boolean`, `fieldNeutral(String) : float`.
  - `VFXDefinition.getFields() : Map<String, VFXField>`; `VFXDefinition.createTimeline(...)` passes the fields to the timeline.
  - `VFXTimeline.getFields() : Map<String, VFXField>`, `VFXTimeline.getFieldProgram(String) : @Nullable VFXFieldProgram`, `VFXTimeline.fieldNeedsDepth() : boolean`, `VFXTimeline.evaluateGraphIndex(int, float) : float`.

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

	static void assertTrue(final String label, final boolean condition) {
		if (!condition) {
			throw new AssertionError(label);
		}
		passed++;
	}

	static void expectThrows(final String label, final Runnable runnable, final String fragment) {
		try {
			runnable.run();
			throw new AssertionError(label + ": expected a parse error, got none");
		} catch (IllegalArgumentException e) {
			if (!e.getMessage().contains(fragment)) {
				throw new AssertionError(label + ": '" + e.getMessage() + "' does not contain '" + fragment + "'");
			}
			passed++;
		}
	}

	static VFXDefinition parse(final String json) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath("test", "field"), JsonParser.parseString(json).getAsJsonObject());
	}

	public static void main(final String[] args) {
		// 1. A definition without fields is unchanged (additive contract).
		final VFXDefinition plain = parse("{\"type\":\"dent\",\"duration\":40,\"params\":{\"strength\":0.6,\"radius\":0.25}}");
		assertTrue("plain has no fields", plain.getFields().isEmpty());
		final VFXTimeline plainTimeline = plain.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 5L);
		plainTimeline.update(0.0F);
		assertTrue("plain strength unchanged", Math.abs(plainTimeline.getValue("strength", -1.0F) - 0.6F) < 1.0e-4F);
		assertTrue("plain intensity default 1", Math.abs(plainTimeline.getValue("intensity", 1.0F) - 1.0F) < 1.0e-4F);
		assertTrue("plain has no field programs", plainTimeline.getFieldProgram("intensity") == null);
		assertTrue("plain needs no depth", !plainTimeline.fieldNeedsDepth());

		// 2. A field on a field-capable input is parsed and packed.
		final VFXDefinition fielded = parse("{\"type\":\"dent\",\"duration\":40,\"params\":{\"strength\":0.6,\"radius\":0.25},"
			+ "\"inputs\":{\"intensity\":{\"field\":\"noise\",\"space\":\"screen\",\"scale\":3.0,\"octaves\":2}}}");
		assertTrue("field parsed", fielded.getFields().containsKey("intensity"));
		final VFXTimeline timeline = fielded.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 5L);
		timeline.update(0.0F);
		assertTrue("field program present", timeline.getFieldProgram("intensity") != null);
		assertTrue("field leaf count", timeline.getFieldProgram("intensity").leafCount() == 1.0F);
		assertTrue("field is screen, no depth", !timeline.fieldNeedsDepth());

		// 3. A graph parameter on a field resolves through the timeline evaluator.
		final VFXDefinition graphParam = parse("{\"type\":\"dent\",\"duration\":40,"
			+ "\"params\":{\"strength\":0.6},"
			+ "\"graph\":{\"version\":1,\"nodes\":[{\"id\":\"s\",\"kind\":\"constant\",\"inputs\":{\"value\":6.0}}]},"
			+ "\"inputs\":{\"intensity\":{\"field\":\"noise\",\"space\":\"screen\",\"scale\":{\"from\":\"s\"}}}}");
		final VFXTimeline graphTimeline = graphParam.createTimeline(40.0F, Map.of(), EasingFunction.builtIn(EasingType.LINEAR), 5L);
		graphTimeline.update(0.0F);
		graphTimeline.updateGraph(0.0F);
		final int slot = graphParam.getGraph().indexOf("s");
		assertTrue("graph index eval", Math.abs(graphTimeline.evaluateGraphIndex(slot, -1.0F) - 6.0F) < 1.0e-4F);

		// 4. A field on a non-capable input or effect is refused, naming the input.
		expectThrows("field on blur radius", () -> parse("{\"type\":\"blur\",\"duration\":40,\"params\":{\"radius\":1},"
			+ "\"inputs\":{\"radius\":{\"field\":\"noise\",\"space\":\"screen\"}}}"), "input 'radius'");
		expectThrows("field on dent strength", () -> parse("{\"type\":\"dent\",\"duration\":40,"
			+ "\"inputs\":{\"strength\":{\"field\":\"noise\",\"space\":\"screen\"}}}"), "input 'strength'");

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the shared standalone-check recipe (Global Constraints), `javac` line only (after `.\gradlew.bat :26.1.2:build --console=plain` so the classes exist).

Expected: `javac` errors on `definition.getFields()` / `timeline.getFieldProgram(...)`.

- [ ] **Step 3: Declare field-capable inputs on the effect type**

In `src/main/java/dev/vfxweaver/effect/VFXEffectType.java`, add the imports and, after `fromString(...)`, the map and methods:

```java
import java.util.Map;
import java.util.Set;
```

```java
	/**
	 * Inputs that accept the per-pixel {@code { "field": ... }} form (spec §2, §9 step 4). The
	 * documented subset starts with the {@code intensity}/amount-like inputs; every other input
	 * still accepts literals and graph references. Add an entry here together with the matching
	 * shader uniforms when wiring a new effect (see the field-enabled pipeline in
	 * {@code VFXShaderPrograms}).
	 */
	private static final Map<VFXEffectType, Set<String>> FIELD_INPUTS = Map.of(
		DENT, Set.of("intensity")
	);

	/**
	 * The input names of this effect that accept a per-pixel field (empty for most effects).
	 */
	public Set<String> fieldCapableInputs() {
		return FIELD_INPUTS.getOrDefault(this, Set.of());
	}

	/**
	 * True when {@code input} accepts a per-pixel field.
	 */
	public boolean acceptsField(final String input) {
		return fieldCapableInputs().contains(input);
	}

	/**
	 * The neutral value of a field-capable input (the value that leaves the effect unchanged).
	 */
	public float fieldNeutral(final String input) {
		return 1.0F;
	}
```

- [ ] **Step 4: Store fields on the definition and replace the "not implemented" branch**

In `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`:

Add the import:

```java
import dev.vfxweaver.field.VFXField;
```

Add the field after `graphInputs`:

```java
	private final Map<String, VFXField> fields;
```

Add the constructor parameter and assignment (after `graphInputs`):

```java
		final Map<String, VFXField> fields
```

```java
		this.fields = Map.copyOf(fields);
```

Update the 12-argument `create(...)` (currently `VFXDefinition.java:156`) to append `Map.of()` as the last argument of `new VFXDefinition(...)`, and update `withParams(...)` (currently `:547`) to append `this.fields`.

In `parse(...)`, add `final Map<String, VFXField> fields = new LinkedHashMap<>();` next to `graphInputs`, and replace the `object.has("field")` branch inside the `inputs` loop (currently `:227-229`) with:

```java
					if (object.has("field")) {
						if (object.has("from")) {
							throw new IllegalArgumentException("input '" + name + "': a field object must not also have 'from'");
						}
						if (!type.acceptsField(name)) {
							throw new IllegalArgumentException("input '" + name + "': fields are not supported here; field-capable inputs of '" + type.getName() + "' are " + type.fieldCapableInputs());
						}
						fields.put(name, VFXField.parse(name, object, graph));
						params.putIfAbsent(name, ParamSpec.constant(type.fieldNeutral(name)));
						continue;
					}
```

Append `fields` to the `return new VFXDefinition(...)` in `parse` (currently `:280`), and add the getter near `getGraphInputs()`:

```java
	/**
	 * Per-pixel fields declared on field-capable inputs, by input name. Empty when the definition
	 * declares none.
	 */
	public Map<String, VFXField> getFields() {
		return this.fields;
	}
```

In `createTimeline(...)`, change the final `return new VFXTimeline(...)` (currently `:618`) to pass `this.fields`:

```java
		return new VFXTimeline(duration, values, bindings, multipliers, expressions, this.graph, this.graphInputs, this.fields, instanceSeed);
```

- [ ] **Step 5: Pack the fields on the timeline**

In `src/main/java/dev/vfxweaver/effect/VFXTimeline.java`:

Add imports:

```java
import dev.vfxweaver.field.VFXField;
import dev.vfxweaver.field.VFXFieldProgram;
```

Add fields:

```java
	private final Map<String, VFXField> fields;
	private final Map<String, VFXFieldProgram> fieldPrograms;
	private final boolean fieldNeedsDepth;
```

Add the `fields` parameter to the graph-aware constructor between `graphInputs` and `graphSeed`, and in the body (after `graphEvaluator`):

```java
		this.fields = Map.copyOf(fields);
		final Map<String, VFXFieldProgram> packed = new LinkedHashMap<>();
		boolean needsDepth = false;
		for (final Map.Entry<String, VFXField> entry : this.fields.entrySet()) {
			packed.put(entry.getKey(), VFXFieldProgram.of(entry.getKey(), entry.getValue(), graph));
			needsDepth |= entry.getValue().needsDepth();
		}
		this.fieldPrograms = Map.copyOf(packed);
		this.fieldNeedsDepth = needsDepth;
```

Update the 5-argument constructor's delegation (currently `:85`) to pass `Map.of()` for `fields`. Add the accessors after `getGraph()`:

```java
	/**
	 * The per-pixel fields declared by the definition, by input name.
	 */
	public Map<String, VFXField> getFields() {
		return this.fields;
	}

	/**
	 * The packed shader program of a field-capable input, or {@code null} when it has no field.
	 */
	public @Nullable VFXFieldProgram getFieldProgram(final String input) {
		return this.fieldPrograms.get(input);
	}

	/**
	 * True when any declared field needs the scene depth (a geometry function or world space).
	 */
	public boolean fieldNeedsDepth() {
		return this.fieldNeedsDepth;
	}

	/**
	 * Evaluates a graph node by its precomputed slot for the current frame, used by the packed
	 * field programs. Returns {@code fallback} when the definition has no graph.
	 */
	public float evaluateGraphIndex(final int index, final float fallback) {
		return this.graphEvaluator == null ? fallback : this.graphEvaluator.evaluateIndex(index, fallback);
	}
```

- [ ] **Step 6: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line.

- [ ] **Step 7: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/vfxweaver/effect/VFXEffectType.java src/main/java/dev/vfxweaver/effect/VFXDefinition.java src/main/java/dev/vfxweaver/effect/VFXTimeline.java
git commit -m "feat(field): wire field inputs into the effect format and timeline"
```

---

### Task 4: Field uniforms and depth binding in the post pass

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldEnv.java`
- Create: `src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldValueWriterAdapter.java`
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java`
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java`
- Modify: `src/main/java/dev/vfxweaver/field/VFXFieldProgram.java` (add `empty()`)
- Modify: `src/main/java/dev/vfxweaver/effect/VFXTimeline.java` (add `getGraphEvaluator()`)
- Test: none standalone (client/shaders only) — verification is the six-node build, `javap` and the human check in Task 6.

**Interfaces:**
- Consumes: `VFXFieldProgram.write(...)`/`leafCount()`/`needsDepth()`/`texture()` (Task 2), `VFXTimeline.getFieldProgram(String)`/`fieldNeedsDepth()`/`getGraphEvaluator()` (Tasks 2–3), `RenderTarget.getDepthTextureView()` (depth findings §"Exact names/signatures"), `Camera.getViewRotationProjectionMatrix(Matrix4f)`/`Camera.position()` (26.2, verified with `javap`).
- Produces:
  - `VFXShaderPrograms.ProgramInfo` gains `boolean usesDepth`, `@Nullable String fieldInput` (with `usesField()` derived).
  - `VFXShaderPrograms.FIELD_CONFIG_SIZE : int` — the `FieldConfig` std140 byte size.
  - `VFXFieldEnv.capture(RenderTarget, boolean)` plus `depthValid()`, `invViewProj()`, `cameraX/Y/Z()`, `invWidth/Height()`.
  - `VFXPostProcessingManager.VFXPass.execute(..., @Nullable RenderTarget depthSource, @Nullable VFXFieldProgram fieldProgram)`.

- [ ] **Step 1: Add `empty()` to the packer and `getGraphEvaluator()` to the timeline**

In `src/main/java/dev/vfxweaver/field/VFXFieldProgram.java`, add next to `of(...)`:

```java
	/**
	 * A neutral program with no leaves: the shader evaluates it to {@code vec3(1.0)}, so an effect
	 * that declares no field keeps its uniform value unchanged.
	 */
	public static VFXFieldProgram empty() {
		return new VFXFieldProgram(
			new float[VFXField.MAX_LEAVES], new float[VFXField.MAX_LEAVES], new float[VFXField.MAX_LEAVES],
			new float[VFXField.MAX_LEAVES], new float[VFXField.MAX_LEAVES],
			new float[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS], filled(VFXField.MAX_LEAVES * VFXField.MAX_PARAMS, -1), new boolean[VFXField.MAX_LEAVES * VFXField.MAX_PARAMS],
			new float[VFXField.MAX_CURVE_POINTS], new float[VFXField.MAX_CURVE_POINTS], 0,
			new float[VFXField.MAX_PROGRAM], 0, 0, VFXFieldType.FLOAT, false, null, "");
	}

	private static int[] filled(final int length, final int value) {
		final int[] array = new int[length];
		Arrays.fill(array, value);
		return array;
	}
```

In `src/main/java/dev/vfxweaver/effect/VFXTimeline.java`, add after `evaluateGraphIndex(...)`:

```java
	/**
	 * The per-frame graph evaluator, or {@code null} when the definition has no graph. Read by the
	 * client field writer without allocation.
	 */
	public @Nullable VFXGraphEvaluator getGraphEvaluator() {
		return this.graphEvaluator;
	}
```

- [ ] **Step 2: Add the field layouts, size and field-enabled DENT pipeline**

In `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java`:

Add the import `import com.mojang.blaze3d.buffers.Std140SizeCalculator;` and replace the `ProgramInfo` record with:

```java
	public record ProgramInfo(RenderPipeline pipeline, String[] configParams, int configUboSize, PassRole role, boolean usesDepth, @Nullable String fieldInput) {
		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize, final PassRole role) {
			this(pipeline, configParams, configUboSize, role, false, null);
		}

		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize) {
			this(pipeline, configParams, configUboSize, PassRole.NORMAL, false, null);
		}

		/** True when this pipeline declares the {@code FieldConfig} uniform block. */
		public boolean usesField() {
			return this.fieldInput != null;
		}
	}
```

Add the layouts inside the existing `//? if >=26.2 {` block and the size constant outside it:

```java
	//? if >=26.2 {
	/*	// 26.2 bind-group layouts for the field pass. The sampler names must match field.glsl's
	// declarations (`DepthSampler`, `fld_tex0`) and the block name (`FieldConfig`) exactly.
	private static final BindGroupLayout DEPTH_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("DepthSampler")
		.build();
	private static final BindGroupLayout FIELD_TEXTURE_LAYOUT = BindGroupLayout.builder()
		.withSampler("fld_tex0")
		.build();
	private static final BindGroupLayout FIELD_CONFIG_LAYOUT = BindGroupLayout.builder()
		.withUniform("FieldConfig", UniformType.UNIFORM_BUFFER)
		.build();
	*///?}

	/**
	 * The std140 size of the {@code FieldConfig} block. <b>Contract</b> (AGENTS.md UBO field-order
	 * rule): this mirrors the declaration order in
	 * {@code assets/vfxweaver/shaders/include/field.glsl}, which {@link VFXFieldProgram#write}
	 * emits — change all three together.
	 */
	public static final int FIELD_CONFIG_SIZE = new Std140SizeCalculator()
		.putFloat().putFloat().putFloat()
		.putVec4().putVec4().putVec4()
		.putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putFloat()
		.putVec4().putVec4().putVec4().putVec4()
		.putFloat().putFloat().putFloat().putFloat().putFloat().putFloat().putFloat().putFloat()
		.putMat4f()
		.putVec4().putVec4()
		.get();
```

Replace `registerPost(VFXEffectType.DENT, "strength", ...)` with the field-enabled registration and add the builder:

```java
		registerFieldPost(VFXEffectType.DENT, new String[]{"strength", "radius", "center_x", "center_y", "line_mode", "x0", "y0", "x1", "y1"}, "intensity");
```

```java
	/**
	 * Registers a single-pass effect whose fragment shader imports the field library: the pipeline
	 * declares the depth sampler, the field texture sampler and the {@code FieldConfig} uniform
	 * block in addition to the standard inputs. {@code fieldInput} is the effect input whose field
	 * drives the shader.
	 */
	private static void registerFieldPost(final VFXEffectType type, final String[] params, final String fieldInput) {
		Identifier location = Identifier.fromNamespaceAndPath("vfxweaver", "post/" + type.getName());
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(location)
			.withVertexShader("core/screenquad")
			.withFragmentShader(location)
			//? if <26.2 {
			.withSampler("InSampler")
			.withSampler("DepthSampler")
			.withSampler("fld_tex0")
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.withUniform("Config", UniformType.UNIFORM_BUFFER)
			.withUniform("FieldConfig", UniformType.UNIFORM_BUFFER);
			//?} else {
			/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withBindGroupLayout(DEPTH_SAMPLER_LAYOUT)
			.withBindGroupLayout(FIELD_TEXTURE_LAYOUT)
			.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
			.withBindGroupLayout(FIELD_CONFIG_LAYOUT);
			*///?}
		RenderPipeline pipeline = RenderPipelines.register(builder.build());
		PROGRAMS.put(type, List.of(new ProgramInfo(pipeline, params, align16(params.length * 4), PassRole.NORMAL, true, fieldInput)));
	}
```

- [ ] **Step 3: Create the reused field environment**

Create `src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldEnv.java`:

```java
package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame camera state used by the field program: the inverse view-projection (the verified
 * world-position recipe from the depth findings), the camera position and the target size. All
 * scratch objects are preallocated so the render path allocates nothing.
 */
public final class VFXFieldEnv {
	private static final Matrix4f VIEW_ROTATION_PROJECTION = new Matrix4f();
	private static final Matrix4f INVERSE = new Matrix4f();
	private static final Vector3f CAMERA = new Vector3f();
	private static float invWidth;
	private static float invHeight;
	private static boolean depthValid;

	private VFXFieldEnv() {
	}

	/**
	 * Captures the current camera state. Call once per frame before the field passes.
	 *
	 * @param mainTarget the main render target whose depth drives the field
	 * @param valid      true when the depth buffer is valid for this pass (layer 0 on 26.2)
	 */
	public static void capture(final RenderTarget mainTarget, final boolean valid) {
		depthValid = valid;
		invWidth = mainTarget.width <= 0 ? 0.0F : 1.0F / mainTarget.width;
		invHeight = mainTarget.height <= 0 ? 0.0F : 1.0F / mainTarget.height;
		// Camera accessor: gameRenderer.mainCamera() on >=26.2, getMainCamera() on <26.2.
		//? if >=26.2 {
		final Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		//?} else {
		/*final Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		*///?}
		CAMERA.set(camera.position().x, camera.position().y, camera.position().z);
		// getViewRotationProjectionMatrix returns projection * viewRotation with no translation;
		// post-multiply translate(-cameraPos) then invert (depth findings, verified recipe).
		camera.getViewRotationProjectionMatrix(VIEW_ROTATION_PROJECTION)
			.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z)
			.invert(INVERSE);
	}

	/** True when the bound depth is usable this frame. */
	public static boolean depthValid() {
		return depthValid;
	}

	public static Matrix4f invViewProj() {
		return INVERSE;
	}

	public static float cameraX() {
		return CAMERA.x;
	}

	public static float cameraY() {
		return CAMERA.y;
	}

	public static float cameraZ() {
		return CAMERA.z;
	}

	public static float invWidth() {
		return invWidth;
	}

	public static float invHeight() {
		return invHeight;
	}
}
```

- [ ] **Step 4: Create the Std140 adapter**

Create `src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldValueWriterAdapter.java`:

```java
package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.vfxweaver.field.VFXFieldValueWriter;

/**
 * Adapts the MC-free {@link VFXFieldValueWriter} to {@code Std140Builder}, keeping the field
 * packer free of {@code com.mojang.*} types.
 */
public final class VFXFieldValueWriterAdapter implements VFXFieldValueWriter {
	private final Std140Builder builder;

	public VFXFieldValueWriterAdapter(final Std140Builder builder) {
		this.builder = builder;
	}

	@Override
	public void putFloat(final float value) {
		this.builder.putFloat(value);
	}

	@Override
	public void putVec4(final float x, final float y, final float z, final float w) {
		this.builder.putVec4(x, y, z, w);
	}
}
```

- [ ] **Step 5: Write the field UBO, bind depth/texture, and enforce layer 0**

In `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java`:

Add imports: `dev.vfxweaver.field.VFXFieldProgram`, `dev.vfxweaver.util.VFXLog`, `net.minecraft.client.Minecraft`, and `org.jspecify.annotations.Nullable` (already imported).

In `process(...)`, after `active` is built and non-empty, capture the environment and warn once:

```java
		boolean anyField = false;
		boolean anyDepthField = false;
		for (final VFXActiveEffect effect : active) {
			anyField |= !effect.getTimeline().getFields().isEmpty();
			anyDepthField |= effect.getTimeline().fieldNeedsDepth();
		}
		if (anyField) {
			final boolean valid = layer == 0 && depthRecipeVerified();
			VFXFieldEnv.capture(mainTarget, valid);
			if (!valid && anyDepthField) {
				for (final VFXActiveEffect effect : active) {
					if (effect.getTimeline().fieldNeedsDepth()) {
						VFXLog.warnOnce(LOGGER, "field:layer:" + effect.getId(),
							"Effect '{}' uses a depth/world field but runs at screen_layer {} — depth fields need layer 0 on 26.2; falling back to the neutral value",
							effect.getId(), layer);
					}
				}
			}
		}
```

Add the guard:

```java
	private static boolean depthRecipeVerified() {
		// The reversed-depth world reconstruction is only verified on 26.2 (depth findings); older
		// nodes bind depth but report it invalid so world/depth fields fall back to neutral.
		//? if >=26.2 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}
```

Extend `PassRun` with the field program of the effect's single field-capable input:

```java
	private record PassRun(VFXPass pass, VFXActiveEffect effect) {
		VFXShaderPrograms.PassRole role() {
			return this.pass.role();
		}

		@Nullable VFXFieldProgram fieldProgram() {
			final String input = this.pass.fieldInput();
			return input == null ? null : this.effect.getTimeline().getFieldProgram(input);
		}
	}
```

Pass the depth source and program through every `execute` call. The copy pass and passes without a field pass `null`; the effect passes pass `mainTarget` and `run.fieldProgram()`:

```java
			copy.execute(encoder, samplerCache, mainTarget, this.pingPong[0], null, null, null, null, null);
```

```java
					run.pass().execute(encoder, samplerCache, read, this.history[1], run.effect(), histPrev, null, null, null);
```

```java
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.stopMotionHold, hold, mainTarget, run.fieldProgram());
					} else if (role == VFXShaderPrograms.PassRole.FEEDBACK_COMPOSITE) {
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.history[1], null, null, null);
```

```java
					} else {
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), null, null, mainTarget, run.fieldProgram());
					}
```

Add the field fields and methods to `VFXPass`:

```java
		private final boolean usesDepth;
		private final @Nullable String fieldInput;
		private final @Nullable MappableRingBuffer fieldUbo;
		private final Map<String, GpuTextureView> textureCache = new HashMap<>();

		// in the constructor, after configUbo:
			this.usesDepth = info.usesDepth();
			this.fieldInput = info.fieldInput();
			this.fieldUbo = info.usesField()
				? new MappableRingBuffer(() -> this.pipeline.getLocation() + " FieldConfig", UBO_USAGE, Math.max(16, VFXShaderPrograms.FIELD_CONFIG_SIZE))
				: null;

		@Nullable String fieldInput() {
			return this.fieldInput;
		}
```

Write the FieldConfig block just before `createRenderPass` (inside `execute`, after the `Config` write, using the same `map(false, true)` pattern):

```java
			if (this.fieldUbo != null && effect != null) {
				final float weight = effect.getWeight();
				final float neutral = effect.getType().fieldNeutral(this.fieldInput);
				final float raw = effect.getParam(this.fieldInput, neutral);
				final float uniform = neutral + (raw - neutral) * weight;
				final VFXFieldProgram program = fieldProgram == null ? VFXFieldProgram.empty() : fieldProgram;
				//? if <26.2 {
				try (GpuBuffer.MappedView view = encoder.mapBuffer(this.fieldUbo.currentBuffer(), false, true)) {
				//?} else {
				/*try (GpuBufferSlice.MappedView view = this.fieldUbo.currentBuffer().map(false, true)) {
				*///?}
					program.write(new VFXFieldValueWriterAdapter(Std140Builder.intoBuffer(view.data())),
						effect.getTimeline().getGraphEvaluator(), uniform,
						VFXFieldEnv.depthValid() ? 1.0F : 0.0F,
						VFXFieldEnv.invWidth(), VFXFieldEnv.invHeight(),
						VFXFieldEnv.invViewProj(), VFXFieldEnv.cameraX(), VFXFieldEnv.cameraY(), VFXFieldEnv.cameraZ());
				}
			}
```

Bind the new resources next to the existing `InSampler` bind:

```java
				if (this.fieldUbo != null) {
					renderPass.setUniform("FieldConfig", this.fieldUbo.currentBuffer());
				}
				if (this.usesDepth && depthSource != null) {
					// Depth is non-filterable: NEAREST only (depth findings).
					renderPass.bindTexture("DepthSampler", depthSource.getDepthTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST));
				}
				if (this.fieldUbo != null) {
					final String texture = fieldProgram == null ? null : fieldProgram.texture();
					renderPass.bindTexture("fld_tex0", texture == null ? input.getColorTextureView() : resolveTexture(texture), samplerCache.getClampToEdge(FilterMode.LINEAR));
				}
```

Add the resolved-texture helper to `VFXPass` (cache keyed by resource id; `Identifier.parse` only on first sight):

```java
		/**
		 * Resolves a field texture to its view. Cached per pipeline; a datapack that swaps the
		 * texture id at runtime re-resolves once. ponytail: unbounded cache, capped in practice by
		 * the definition count (bounded by the datapack caps).
		 */
		private GpuTextureView resolveTexture(final String id) {
			return this.textureCache.computeIfAbsent(id, key ->
				Minecraft.getInstance().getTextureManager().getTexture(Identifier.parse(key)).getTextureView());
		}
```

Rotate the new ring buffer at the end of `execute`, next to `configUbo.rotate()`:

```java
			if (this.fieldUbo != null) {
				this.fieldUbo.rotate();
			}
```

- [ ] **Step 6: Build the active node and build all six**

Run: `.\gradlew.bat :26.1.2:build --console=plain`, then `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. `AbstractTexture.getTextureView()` and `Camera.getViewRotationProjectionMatrix(Matrix4f)` are verified against the 26.2 jar; if a name differs on 26.1.2/1.21.11, confirm with `javap -classpath <deobf jar> <Class>` and guard the call.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/vfxweaver/field/VFXFieldProgram.java src/main/java/dev/vfxweaver/effect/VFXTimeline.java src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldEnv.java src/client/java/dev/vfxweaver/client/postprocessing/VFXFieldValueWriterAdapter.java src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java
git commit -m "feat(field): upload field programs and bind scene depth in the post pass"
```

---

### Task 5: The GLSL field library and the dent consumer shader

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/include/field.glsl`
- Modify: `src/client/resources/assets/vfxweaver/shaders/post/dent.fsh`
- Test: none standalone — GLSL is compile-checked by the game; verification is the six-node build, `jar tf` and the human in-game check in Task 6.

**Interfaces:**
- Consumes: the `FieldConfig` layout and `FIELD_CONFIG_SIZE` decided in Task 4, the function ordinals (`CONSTANT=0 … WORLD_POS=10`), operator ordinals (`MULTIPLY=0, ADD=1, SUBTRACT=2, MIX=3, MIN=4, MAX=5`), channel codes (`r=0,g=1,b=2,a=3,luminance=4,none=5`), shape primitive codes (`circle=0,ellipse=1,rect=2,polygon=3`) and fill codes (`solid=0,stroke=1`) from Tasks 1–2.
- Produces: `field.glsl` with `vec3 vfx_field_eval(vec2 uv)`, `float vfx_field_intensity(vec2 uv)` and the shared shape primitives — the 2D `vfx_shape_sdf(int, vec2, …)` / `vfx_shape_coverage(float, int, float, float)` and the 3D `vfx_shape_sdf_3d(int, vec3, float, vec3)` (with `vfx_shape_sphere_sdf` / `vfx_shape_box_sdf`) — consumed by the masks and `surface_pattern` plans, never re-implemented there; `dent.fsh` multiplies its `strength` by the field result.

- [ ] **Step 1: Create the library**

Create `src/client/resources/assets/vfxweaver/shaders/include/field.glsl`. It has **no `#version` line** (the importer inlines it) and declares the block in exactly the order `VFXFieldProgram.write` emits; `VFXShaderPrograms.FIELD_CONFIG_SIZE` sizes it. Changing one of the three without the others breaks the positional std140 contract (AGENTS.md UBO field-order rule).

```glsl
// Per-pixel field library (spec §2, §9 step 4).
//
// Contract (AGENTS.md UBO field-order rule): the FieldConfig members below are in the same order
// as VFXFieldProgram.write and VFXShaderPrograms.FIELD_CONFIG_SIZE. The function ordinals match
// VFXFieldFn (CONSTANT..WORLD_POS = 0..10), the op ordinals match VFXFieldOp
// (MULTIPLY=0, ADD=1, SUBTRACT=2, MIX=3, MIN=4, MAX=5), the channel codes match
// VFXFieldProgram.channelCode (r=0,g=1,b=2,a=3,luminance=4,none=5), the shape primitive codes
// match VFXFieldProgram.primitiveCode (circle=0,ellipse=1,rect=2,polygon=3) and the fill codes
// match VFXFieldProgram.fillCode (solid=0,stroke=1).
//
// The shape primitives (`vfx_shape_sdf`/`vfx_shape_coverage`, and the 3D `vfx_shape_sdf_3d` with
// `vfx_shape_sphere_sdf`/`vfx_shape_box_sdf`) are the shared implementation masks and
// surface_pattern consume — grids are a shape with `repeat`, rings are an ellipse with
// `fill: stroke`. Do not duplicate them in another shader.
//
// `fld_leaf_count == 0` means "no field": vfx_field_eval returns vec3(1.0).
layout(std140) uniform FieldConfig {
	float fld_uniform;        // uniform-domain value of the input (fade-weighted)
	float fld_depth_valid;    // 1 when the bound depth is usable, else 0
	float fld_leaf_count;
	vec4 fld_leaf_fn;
	vec4 fld_leaf_space;      // 0 = screen, 1 = world
	vec4 fld_leaf_channel;
	vec4 fld_leaf_primitive;  // circle=0, ellipse=1, rect=2, polygon=3
	vec4 fld_leaf_fill;       // solid=0, stroke=1
	// Four parameter vec4 per leaf (MAX_PARAMS = 16); only `shape` uses all of them.
	vec4 fld_l0_p0; vec4 fld_l0_p1; vec4 fld_l0_p2; vec4 fld_l0_p3;
	vec4 fld_l1_p0; vec4 fld_l1_p1; vec4 fld_l1_p2; vec4 fld_l1_p3;
	vec4 fld_l2_p0; vec4 fld_l2_p1; vec4 fld_l2_p2; vec4 fld_l2_p3;
	vec4 fld_l3_p0; vec4 fld_l3_p1; vec4 fld_l3_p2; vec4 fld_l3_p3;
	float fld_curve_count;
	vec4 fld_curve0;          // (t0, v0, t1, v1)
	vec4 fld_curve1;
	vec4 fld_curve2;
	vec4 fld_curve3;
	float fld_prog0;
	float fld_prog1;
	float fld_prog2;
	float fld_prog3;
	float fld_prog4;
	float fld_prog5;
	float fld_prog6;
	float fld_prog7;
	mat4 fld_inv_view_proj;
	vec4 fld_camera_pos;      // xyz
	vec4 fld_inv_size;        // xy = 1 / screen size
};

uniform sampler2D DepthSampler;
uniform sampler2D fld_tex0;

// --- generic uniform accessors (no arrays: std140 + Std140Builder have no array support) ---

float vfx_leaf_fn(int i) {
	if (i == 0) return fld_leaf_fn.x;
	if (i == 1) return fld_leaf_fn.y;
	if (i == 2) return fld_leaf_fn.z;
	return fld_leaf_fn.w;
}

int vfx_leaf_space(int i) {
	if (i == 0) return int(fld_leaf_space.x + 0.5);
	if (i == 1) return int(fld_leaf_space.y + 0.5);
	if (i == 2) return int(fld_leaf_space.z + 0.5);
	return int(fld_leaf_space.w + 0.5);
}

int vfx_leaf_channel(int i) {
	if (i == 0) return int(fld_leaf_channel.x + 0.5);
	if (i == 1) return int(fld_leaf_channel.y + 0.5);
	if (i == 2) return int(fld_leaf_channel.z + 0.5);
	return int(fld_leaf_channel.w + 0.5);
}

int vfx_leaf_primitive(int i) {
	if (i == 0) return int(fld_leaf_primitive.x + 0.5);
	if (i == 1) return int(fld_leaf_primitive.y + 0.5);
	if (i == 2) return int(fld_leaf_primitive.z + 0.5);
	return int(fld_leaf_primitive.w + 0.5);
}

int vfx_leaf_fill(int i) {
	if (i == 0) return int(fld_leaf_fill.x + 0.5);
	if (i == 1) return int(fld_leaf_fill.y + 0.5);
	if (i == 2) return int(fld_leaf_fill.z + 0.5);
	return int(fld_leaf_fill.w + 0.5);
}

// Parameter group g (0..3) of leaf i holds slots g*4 .. g*4+3.
vec4 vfx_leaf_pgroup(int i, int g) {
	if (i == 0) {
		if (g == 0) return fld_l0_p0;
		if (g == 1) return fld_l0_p1;
		if (g == 2) return fld_l0_p2;
		return fld_l0_p3;
	}
	if (i == 1) {
		if (g == 0) return fld_l1_p0;
		if (g == 1) return fld_l1_p1;
		if (g == 2) return fld_l1_p2;
		return fld_l1_p3;
	}
	if (i == 2) {
		if (g == 0) return fld_l2_p0;
		if (g == 1) return fld_l2_p1;
		if (g == 2) return fld_l2_p2;
		return fld_l2_p3;
	}
	if (g == 0) return fld_l3_p0;
	if (g == 1) return fld_l3_p1;
	if (g == 2) return fld_l3_p2;
	return fld_l3_p3;
}

// The first four parameters: what every non-shape function reads.
vec4 vfx_leaf_p(int i) {
	return vfx_leaf_pgroup(i, 0);
}

float vfx_leaf_param(int i, int slot) {
	vec4 g = vfx_leaf_pgroup(i, slot / 4);
	int c = slot - (slot / 4) * 4;
	if (c == 0) return g.x;
	if (c == 1) return g.y;
	if (c == 2) return g.z;
	return g.w;
}

float vfx_prog(int k) {
	if (k == 0) return fld_prog0;
	if (k == 1) return fld_prog1;
	if (k == 2) return fld_prog2;
	if (k == 3) return fld_prog3;
	if (k == 4) return fld_prog4;
	if (k == 5) return fld_prog5;
	if (k == 6) return fld_prog6;
	return fld_prog7;
}

vec4 vfx_curve_group(int g) {
	if (g == 0) return fld_curve0;
	if (g == 1) return fld_curve1;
	if (g == 2) return fld_curve2;
	return fld_curve3;
}

float vfx_curve_t(int i) {
	vec4 g = vfx_curve_group(i / 2);
	return (i % 2) == 0 ? g.x : g.z;
}

float vfx_curve_v(int i) {
	vec4 g = vfx_curve_group(i / 2);
	return (i % 2) == 0 ? g.y : g.w;
}

float vfx_curve_sample(float u) {
	int count = int(fld_curve_count + 0.5);
	if (count <= 0) return u;
	if (u <= vfx_curve_t(0)) return vfx_curve_v(0);
	if (u >= vfx_curve_t(count - 1)) return vfx_curve_v(count - 1);
	for (int i = 0; i < count - 1; i++) {
		float t0 = vfx_curve_t(i);
		float t1 = vfx_curve_t(i + 1);
		if (u >= t0 && u <= t1) {
			float span = max(t1 - t0, 1.0e-6);
			return mix(vfx_curve_v(i), vfx_curve_v(i + 1), (u - t0) / span);
		}
	}
	return vfx_curve_v(count - 1);
}

// --- depth / world reconstruction (verified recipe, depth findings note) ---

float vfx_raw_depth(vec2 uv) {
	return texture(DepthSampler, uv).r;
}

vec3 vfx_world_pos(vec2 uv) {
	// 26.x reversed depth: the sampled value is already NDC z (near = 1, far = 0).
	float d = vfx_raw_depth(uv);
	vec4 clip = vec4(uv * 2.0 - 1.0, d, 1.0);
	vec4 world = fld_inv_view_proj * clip;
	return world.xyz / world.w;
}

float vfx_linear_depth(float d, float near, float far) {
	if (far <= near) return d;
	return (near * far) / ((far - near) * d + near);
}

// --- noise (shared with noise_warp.fsh) ---

vec3 vfx_hash33(vec3 p3) {
	p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
	p3 += dot(p3, p3.yxz + 33.33);
	return fract((p3.xxy + p3.yxx) * p3.zyx) * 2.0 - 1.0;
}

float vfx_gnoise(vec3 p) {
	vec3 i = floor(p);
	vec3 f = fract(p);
	vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	return mix(
		mix(mix(dot(vfx_hash33(i), f),
			dot(vfx_hash33(i + vec3(1, 0, 0)), f - vec3(1, 0, 0)), u.x),
			mix(dot(vfx_hash33(i + vec3(0, 1, 0)), f - vec3(0, 1, 0)),
				dot(vfx_hash33(i + vec3(1, 1, 0)), f - vec3(1, 1, 0)), u.x), u.y),
		mix(mix(dot(vfx_hash33(i + vec3(0, 0, 1)), f - vec3(0, 0, 1)),
			dot(vfx_hash33(i + vec3(1, 0, 1)), f - vec3(1, 0, 1)), u.x),
			mix(dot(vfx_hash33(i + vec3(0, 1, 1)), f - vec3(0, 1, 1)),
				dot(vfx_hash33(i + vec3(1, 1, 1)), f - vec3(1, 1, 1)), u.x), u.y),
		u.z);
}

float vfx_fbm(vec3 p, int octaves, float gain, float lacunarity) {
	float sum = 0.0;
	float amplitude = 1.0;
	float norm = 0.0;
	for (int i = 0; i < octaves; i++) {
		sum += amplitude * vfx_gnoise(p);
		norm += amplitude;
		p *= lacunarity;
		amplitude *= gain;
	}
	return norm > 0.0 ? sum / norm * 0.5 + 0.5 : 0.5;
}

// --- shared shape primitives (owned by this library; masks/surface_pattern consume them) ---

// Parameter slots, matching VFXFieldFn.SHAPE's paramNames order.
#define VFX_SHAPE_CX 0
#define VFX_SHAPE_CY 1
#define VFX_SHAPE_ROT 2
#define VFX_SHAPE_RADIUS 3
#define VFX_SHAPE_RX 4
#define VFX_SHAPE_RY 5
#define VFX_SHAPE_HW 6
#define VFX_SHAPE_HH 7
#define VFX_SHAPE_CR 8
#define VFX_SHAPE_SIDES 9
#define VFX_SHAPE_STROKE 10
#define VFX_SHAPE_SOFT 11
#define VFX_SHAPE_REPX 12
#define VFX_SHAPE_REPY 13

// Raw distance to a primitive in the shape's local space: negative inside, positive outside.
// Masks perturb their edge in this distance domain (mask design §4) before applying softness.
float vfx_shape_sdf(int primitive, vec2 p, float radius, float rx, float ry, float halfW, float halfH, float corner, float sides) {
	if (primitive == 0) {
		return length(p) - radius;
	}
	if (primitive == 1) {
		float a = max(rx, 1.0e-4);
		float b = max(ry, 1.0e-4);
		return (length(p / vec2(a, b)) - 1.0) * min(a, b);
	}
	if (primitive == 2) {
		vec2 d = abs(p) - vec2(halfW, halfH) + corner;
		return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - corner;
	}
	float n = max(sides, 3.0);
	float seg = 3.14159265 / n;
	float snapped = floor(0.5 + atan(p.y, p.x) / (2.0 * seg)) * 2.0 * seg - atan(p.y, p.x);
	return length(p) * cos(snapped) - radius;
}

// --- 3D world primitives (owned by this library too) ---
// Used wherever a 3D coordinate exists: the masks plan reconstructs a full world position from
// depth and evaluates `sphere`/`box` against it. `p` is centre-relative with the rotation already
// applied. Negative inside, positive outside, in world units.

float vfx_shape_sphere_sdf(vec3 p, float radius) {
	return length(p) - radius;
}

float vfx_shape_box_sdf(vec3 p, vec3 halfExtents) {
	vec3 d = abs(p) - halfExtents;
	return length(max(d, vec3(0.0))) + min(max(d.x, max(d.y, d.z)), 0.0);
}

// Shared 3D dispatcher. Kind 4 = sphere (`radius`), kind 5 = box (`halfExtents`). The masks call
// this instead of re-implementing any 3D distance.
float vfx_shape_sdf_3d(int primitive, vec3 p, float radius, vec3 halfExtents) {
	if (primitive == 4) {
		return vfx_shape_sphere_sdf(p, radius);
	}
	return vfx_shape_box_sdf(p, halfExtents);
}

// Mask-facing dispatcher: choose the 2D or 3D shared helper from the kind/space and apply the
// leaf centre/rotation. A mask's coverage shader calls exactly this; it never branches on shapes.
// `p0`/`p1` are the packed per-kind parameters (CIRCLE radius= p0.x; ELLIPSE p0.x/p0.y;
// RECT p0.x/p0.y/p0.z; POLYGON p0.x/p0.y; SPHERE p0.x; BOX p0.x/p0.y/p0.z).
float vfx_shape_sdf_dispatch(int kind, int space, vec2 uv, vec3 world, vec3 center, float rotationDeg, vec4 p0, vec4 p1) {
	float r = radians(rotationDeg);
	if (kind == 4 || kind == 5) {
		float c = cos(r);
		float s = sin(r);
		vec3 local = world - center;
		vec2 rotated = mat2(c, -s, s, c) * local.xz;
		local = vec3(rotated.x, local.y, rotated.y);
		return vfx_shape_sdf_3d(kind, local, p0.x, vec3(p0.x, p0.y, p0.z));
	}
	vec2 local = uv - center.xy;
	float c = cos(r);
	float s = sin(r);
	local = mat2(c, -s, s, c) * local;
	return vfx_shape_sdf(kind, local, p0.x, p0.x, p0.y, p0.x, p0.y, p0.z, p0.y);
}

// Coverage in [0,1] after fill and softness. `fill` 0 = solid, 1 = stroke.
float vfx_shape_coverage(float sdf, int fill, float strokeWidth, float softness) {
	float soft = max(softness, 1.0e-4);
	if (fill == 1) {
		float band = abs(sdf) - max(strokeWidth, 1.0e-4) * 0.5;
		return 1.0 - smoothstep(-soft, soft, band);
	}
	return 1.0 - smoothstep(-soft, soft, sdf);
}

// --- one field leaf ---

vec3 vfx_field_leaf(int i, vec2 uv) {
	float fn = vfx_leaf_fn(i);
	int space = vfx_leaf_space(i);
	vec4 p = vfx_leaf_p(i);
	bool geom = fn == 6.0 || fn == 7.0 || fn == 8.0 || fn == 10.0;
	vec3 world = vec3(0.0);
	if (geom || space == 1) {
		if (fld_depth_valid < 0.5) {
			return vec3(1.0);
		}
		world = vfx_world_pos(uv);
	}
	vec2 coord = space == 1 ? world.xz : uv;
	if (fn == 0.0) {
		return vec3(p.x);
	}
	if (fn == 1.0) {
		vec3 samplePos = space == 1 ? world.xyz : vec3(uv, 0.0);
		int octaves = max(1, int(p.y + 0.5));
		return vec3(vfx_fbm(samplePos * max(p.x, 1.0e-4), octaves, p.z, max(p.w, 1.0e-4)));
	}
	if (fn == 2.0) {
		// Shared shape primitive: coverage in [0,1]. `repeat` tiles it (grid); fill stroke + an
		// ellipse is a ring.
		vec2 local = coord - vec2(vfx_leaf_param(i, VFX_SHAPE_CX), vfx_leaf_param(i, VFX_SHAPE_CY));
		float rot = radians(vfx_leaf_param(i, VFX_SHAPE_ROT));
		float c = cos(rot);
		float s = sin(rot);
		local = mat2(c, -s, s, c) * local;
		vec2 repeat = vec2(vfx_leaf_param(i, VFX_SHAPE_REPX), vfx_leaf_param(i, VFX_SHAPE_REPY));
		if (repeat.x > 1.0 || repeat.y > 1.0) {
			local = fract(local * repeat) - 0.5;
		}
		float sdf = vfx_shape_sdf(vfx_leaf_primitive(i), local,
			vfx_leaf_param(i, VFX_SHAPE_RADIUS),
			vfx_leaf_param(i, VFX_SHAPE_RX),
			vfx_leaf_param(i, VFX_SHAPE_RY),
			vfx_leaf_param(i, VFX_SHAPE_HW),
			vfx_leaf_param(i, VFX_SHAPE_HH),
			vfx_leaf_param(i, VFX_SHAPE_CR),
			vfx_leaf_param(i, VFX_SHAPE_SIDES));
		return vec3(vfx_shape_coverage(sdf, vfx_leaf_fill(i),
			vfx_leaf_param(i, VFX_SHAPE_STROKE), vfx_leaf_param(i, VFX_SHAPE_SOFT)));
	}
	if (fn == 3.0) {
		vec2 dir = vec2(cos(p.x), sin(p.x));
		return vec3(clamp(dot(coord, dir) * p.z + p.y, 0.0, 1.0));
	}
	if (fn == 4.0) {
		float u = space == 1 ? fract(coord.x * max(p.x, 1.0e-4)) : clamp(coord.x * max(p.x, 1.0e-4), 0.0, 1.0);
		return vec3(vfx_curve_sample(u));
	}
	if (fn == 5.0) {
		vec4 tex = texture(fld_tex0, coord * vec2(p.x, p.y) + vec2(p.z, p.w));
		int channel = vfx_leaf_channel(i);
		if (channel == 0) return vec3(tex.r);
		if (channel == 1) return vec3(tex.g);
		if (channel == 2) return vec3(tex.b);
		if (channel == 3) return vec3(tex.a);
		if (channel == 4) return vec3(dot(tex.rgb, vec3(0.2126, 0.7152, 0.0722)));
		return tex.rgb;
	}
	if (fn == 6.0) {
		return vec3(vfx_linear_depth(vfx_raw_depth(uv), p.x, p.y));
	}
	if (fn == 7.0) {
		vec2 step = fld_inv_size.xy;
		float d0 = vfx_linear_depth(vfx_raw_depth(uv), p.x, p.y);
		float dx = vfx_linear_depth(vfx_raw_depth(uv + vec2(step.x, 0.0)), p.x, p.y) - d0;
		float dy = vfx_linear_depth(vfx_raw_depth(uv + vec2(0.0, step.y)), p.x, p.y) - d0;
		float range = max(abs(p.y - p.x), 1.0e-4);
		return vec3(clamp(length(vec2(dx, dy)) / range, 0.0, 1.0));
	}
	if (fn == 8.0) {
		vec2 step = fld_inv_size.xy;
		vec3 dx = vfx_world_pos(uv + vec2(step.x, 0.0)) - vfx_world_pos(uv);
		vec3 dy = vfx_world_pos(uv + vec2(0.0, step.y)) - vfx_world_pos(uv);
		vec3 normal = normalize(cross(dx, dy) + vec3(0.0, 0.0, 1.0e-6));
		vec3 axis = normalize(vec3(p.x, p.y, p.z) + vec3(1.0e-6));
		float facing = dot(normal, axis);
		float softness = 0.05;
		return vec3(smoothstep(p.w - softness, p.w + softness, facing));
	}
	if (fn == 9.0) {
		return vec3(uv, 0.0);
	}
	return world;
}

// --- composition (post-order program, mixed arity) ---

void vfx_combine(int op, inout vec3 stack[4], inout int sp) {
	if (op == 3) {
		vec3 f = stack[--sp];
		vec3 b = stack[--sp];
		vec3 a = stack[--sp];
		stack[sp++] = mix(a, b, f.x);
	} else {
		vec3 b = stack[--sp];
		vec3 a = stack[--sp];
		vec3 r = a * b;
		if (op == 1) r = a + b;
		else if (op == 2) r = a - b;
		else if (op == 4) r = min(a, b);
		else if (op == 5) r = max(a, b);
		stack[sp++] = r;
	}
}

vec3 vfx_field_eval(vec2 uv) {
	if (fld_leaf_count < 0.5) {
		return vec3(1.0);
	}
	vec3 stack[4];
	int sp = 0;
	for (int k = 0; k < 8; k++) {
		float c = vfx_prog(k);
		if (isnan(c)) {
			break;
		}
		if (c <= -1.0) {
			stack[sp++] = vfx_field_leaf(int(-c) - 1, uv);
		} else {
			vfx_combine(int(c), stack, sp);
		}
	}
	return sp > 0 ? stack[0] : vec3(1.0);
}

// The effective multiplier of a field-capable input: uniform value times the per-pixel field.
float vfx_field_intensity(vec2 uv) {
	return fld_uniform * vfx_field_eval(uv).x;
}
```

- [ ] **Step 2: Integrate the library into dent.fsh**

In `src/client/resources/assets/vfxweaver/shaders/post/dent.fsh`, add the import immediately after `#version 330`:

```glsl
#version 330

#moj_import <vfxweaver:field.glsl>
```

and multiply the dent strength by the per-pixel intensity (`intensity` defaults to 1.0, so a dent without a field is unchanged). Replace the `scale` line (currently `:59`):

```glsl
	// The field multiplies the animated strength per pixel (default 1.0 = no field).
	float intensity = vfx_field_intensity(texCoord);

	// strength > 0 shrinks the offset (pixels pulled INTO the point = dent),
	// strength < 0 grows it (pixels pushed OUT of the point = bulge).
	float scale = 1.0 - strength * intensity * falloff;
```

- [ ] **Step 3: Build all six nodes and confirm the include ships**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. Then confirm the include and shader are in the jar:

```powershell
& 'C:\Program Files\Java\jdk-26\bin\jar.exe' tf versions\26.2\build\libs\vfxweaver-*.jar | Select-String 'field.glsl|post/dent.fsh'
```

Expected: both paths listed. A missing `field.glsl` means the resource is not packaged (`build.gradle` excludes) — fix the resource path, do not inline the library.

- [ ] **Step 4: Static GLSL sanity check (best effort)**

GLSL cannot be compiled outside the game. Confirm the import name and include location match the vanilla convention by listing a vanilla post shader that imports (`assets/minecraft/shaders/post/box_blur.fsh` uses `#moj_import <minecraft:globals.glsl>` next to `assets/minecraft/shaders/include/globals.glsl`), i.e. our `<vfxweaver:field.glsl>` resolves to `assets/vfxweaver/shaders/include/field.glsl`. Record this reasoning in the commit body; the real compile check is the in-game run in Task 6.

- [ ] **Step 5: Commit**

```bash
git add src/client/resources/assets/vfxweaver/shaders/include/field.glsl src/client/resources/assets/vfxweaver/shaders/post/dent.fsh
git commit -m "feat(field): GLSL field library and per-pixel dent intensity"
```

---

### Task 6: Built-in consumer and in-game verification

**Files:**
- Create: `src/main/resources/data/vfxweaver/vfx/dent_field_demo.json`
- Test: human in-game checks (Fabric 26.2; optional NeoForge 26.2)

**Interfaces:**
- Consumes: everything from Tasks 1–5; the existing `dent` post pass and its `strength`/`radius`/`screen_layer` params (registered by `registerFieldPost` and read by `VFXPostProcessingManager`).
- Produces: `/vfx play dent_field_demo` — a dent whose strength is modulated per pixel by screen-space noise.

- [ ] **Step 1: Add the built-in consumer**

Create `src/main/resources/data/vfxweaver/vfx/dent_field_demo.json`:

```json
{
  "type": "dent",
  "duration": 200,
  "loop": true,
  "persistent": true,
  "fade_ticks": 10,
  "params": {
    "strength": { "start": 0.7, "end": 0.7 },
    "radius": 0.4,
    "center_x": 0.5,
    "center_y": 0.5,
    "screen_layer": 1
  },
  "inputs": {
    "intensity": {
      "field": "noise",
      "space": "screen",
      "scale": 18.0,
      "octaves": 3,
      "gain": 0.5,
      "lacunarity": 2.0
    }
  }
}
```

`screen_layer: 1` for this demo: a screen-space field needs no depth and works at any layer. The world/depth checks below use a layer-0 test definition.

- [ ] **Step 2: Build all six nodes and confirm the definition ships**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six; `jar tf versions\26.2\build\libs\vfxweaver-*.jar` lists `data/vfxweaver/vfx/dent_field_demo.json`.

- [ ] **Step 3: In-game backward-compatibility check (human partner)**

Ask the human partner to install `versions/26.2/build/libs/vfxweaver-<version>+26.2.jar` into the `26.2test` instance, launch, run `/vfx play dent`, and compare with the pre-change jar. Expected: the dent looks **identical** (same strength, same radius, no graininess). A no-field dent that changes shape means `vfx_field_intensity` is not returning 1.0 when `fld_leaf_count == 0`.

- [ ] **Step 4: In-game field check (human partner)**

Run `/vfx play dent_field_demo`. Expected: the dent is now **mottled** — its strength varies per pixel in soft noise patches, and the pattern stays fixed on screen while the camera turns (screen-anchored). If the dent is uniform, the field is not reaching the shader (check the log for a `FieldConfig` unknown-uniform warning, a dropped resource pack, or a black screen from a failed shader compile). If the screen is black, the shader failed to compile — report the log verbatim. Never claim this result; record what the partner reports.

- [ ] **Step 5: In-game shape/repeat check (human partner, throwaway datapack)**

Add a throwaway file `data/<ns>/vfx/shape_demo.json`:

```json
{
  "type": "dent",
  "duration": 200,
  "loop": true,
  "persistent": true,
  "fade_ticks": 10,
  "params": { "strength": { "start": 0.6, "end": 0.6 }, "radius": 0.5, "screen_layer": 1 },
  "inputs": {
    "intensity": {
      "field": "shape",
      "space": "screen",
      "primitive": "rect",
      "center": [0.5, 0.5],
      "rotation": 15.0,
      "half_width": 0.12,
      "half_height": 0.08,
      "corner_radius": 0.03,
      "fill": "stroke",
      "stroke_width": 0.04,
      "softness": 0.02,
      "repeat": [4, 4]
    }
  }
}
```

Expected: a 4×4 grid of rounded, rotated rectangle outlines — `repeat` is the old "grid" feature and `fill: stroke` with `corner_radius`/`rotation` all take effect. Then change to `"primitive": "ellipse"`, remove `half_width`/`half_height`/`corner_radius`, add `"radius_x": 0.2`, `"radius_y": 0.12` and keep `fill: stroke`: a grid of rings (the old `ring` feature). Record what the partner reports.

- [ ] **Step 6: In-game layer-0 / world-field check (human partner, throwaway datapack)**

Ask the human partner to add a throwaway datapack with one file under `data/<ns>/vfx/` that uses a world-space field, e.g.:

```json
{
  "type": "dent",
  "duration": 400,
  "loop": true,
  "persistent": true,
  "fade_ticks": 10,
  "params": { "strength": { "start": 0.7, "end": 0.7 }, "radius": 0.5, "screen_layer": 0 },
  "inputs": { "intensity": { "field": "noise", "space": "world", "scale": 1.5, "octaves": 2 } }
}
```

Expected at `screen_layer: 0`: the noise pattern stays anchored to the **world** while turning (correct). Then change it to `screen_layer: 1` and reload: expect a once-per-definition log warning that depth fields need layer 0, and a **uniform** dent (the field falls back to neutral). Record both observations. This is the graceful-fallback check (spec anti-pattern: never read depth without a fallback).

- [ ] **Step 7: In-game per-file isolation check (human partner)**

Add a second throwaway file that declares `"intensity": { "field": "teleport" }` (an unknown function). Expected: exactly one parse error naming `input 'intensity': unknown field function 'teleport'`, `dent_field_demo` still plays, and the pack still loads. Record the exact log verbatim.

- [ ] **Step 8: NeoForge check (human partner, optional but expected)**

Install `versions/26.2-neoforge/build/libs/vfxweaver-<version>+26.2-neoforge.jar`, repeat Steps 3–6. Expected: identical behaviour; no access transformer needed for depth (depth findings verdict).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/data/vfxweaver/vfx/dent_field_demo.json
git commit -m "feat(field): ship dent_field_demo, a noise-modulated dent intensity"
```

---

### Task 7: Document the field format

**Files:**
- Modify: `docs/GUIDE.md` (datapack effect format section + its changelog at the bottom)
- Modify: `docs/CHANGELOG.md` (release entry, users-visible only)
- Modify: `docs/ARCHITECTURE.md` (the field module and the UBO contract)

**Interfaces:**
- Consumes: the final format from Tasks 1–6.
- Produces: user-facing documentation of `field`, the built-in functions (including the shared `shape` primitives and `repeat`), `space`, `channel`, composition, caps and the layer-0 rule.

- [ ] **Step 1: Add the field section to `docs/GUIDE.md`**

Document, with the literal JSON:

- the `field` object on a **field-capable input** (currently `dent.intensity`); a field is per-pixel and only valid there, while every other input still takes a number or `{ "from": "<node>" }`;
- the functions `noise`, `shape`, `gradient`, `curve`, `texture` (with `channel: r|g|b|a|luminance`), `depth`, `depth_gradient`, `normal_facing`, `screen_uv`, `world_pos`, `constant`, each with its parameters and defaults exactly as in `VFXFieldFn`;
- the `shape` primitives (`circle`, `ellipse`, `rect`, `polygon`) with their parameters (`center`, `rotation`, `radius`, `radius_x`/`radius_y`, `half_width`/`half_height`, optional `corner_radius`, `sides` ≥ 3 clamped in the shader), `fill: solid|stroke` with `stroke_width`, `softness`, and the `repeat: [nx, ny]` tiling modifier — and that **`grid` is a tiled shape and a `ring` is an `ellipse` with `fill: stroke`**, not separate functions;
- that the shape primitives are **shared**: masks and `surface_pattern` consume the same `field.glsl` implementation (`vfx_shape_sdf` for the raw distance, `vfx_shape_coverage` for `[0,1]` coverage) and never re-implement shapes;
- `space: screen|world` (world is the default for scene functions) and its meaning;
- composition `{ "op": "multiply|add|subtract|mix|min|max", "a": {...}, "b": {...} }` (plus `factor` for `mix`), the coercion rules (float broadcasts, vec2×vec3 is an error), and the caps;
- that any numeric field parameter accepts a number or `{ "from": "<node>" }` (integers rounded), including each element of the `center`/`repeat` arrays, while `field`/`op`/`space`/`channel`/`texture`/`points`/`primitive`/`fill` are structural and not animatable;
- the layer-0 rule for `space: world` and the geometry functions, and the neutral fallback when depth is unavailable;
- a worked example equal to `dent_field_demo.json`.

- [ ] **Step 2: Add a `### vN` entry to the changelog at the bottom of `docs/GUIDE.md`**

State that `field` is additive, that a definition without fields is unchanged, and that fields currently apply to `dent.intensity`.

- [ ] **Step 3: Add the `docs/CHANGELOG.md` entry**

Keep it to what users see: "Effect inputs may now carry a per-pixel field (built-in noise, tileable shapes — circle/ellipse/rect/polygon with solid or stroke fill — gradient, curve, texture and depth/world functions, composable); first consumer: `dent.intensity`. Existing definitions are unaffected."

- [ ] **Step 4: Add the architecture note to `docs/ARCHITECTURE.md`**

One short section: the MC-free `dev.vfxweaver.field` model/packer, the fixed `FieldConfig` UBO and the AGENTS.md UBO field-order rule (shader `field.glsl` order == `VFXFieldProgram.write` order == `FIELD_CONFIG_SIZE`), and the layer-0 requirement for depth fields.

- [ ] **Step 5: Commit**

```bash
git add docs/GUIDE.md docs/CHANGELOG.md docs/ARCHITECTURE.md
git commit -m "docs: document the per-pixel field format (step 4)"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| §2 field domain: a value that varies per pixel is evaluated in-shader via a built-in function | Tasks 1–5 (`VFXField` → `VFXFieldProgram` → `field.glsl`) |
| §2 built-in functions `noise`, `shape` (primitives + `fill`/`stroke`/`softness`/`repeat`), `gradient`, `curve`, `texture sample`, `constant` | Task 1 (`VFXFieldFn`) + Task 5 (`vfx_field_leaf`, `vfx_shape_sdf`/`vfx_shape_coverage`) |
| **Design change — shared shape set.** The field library owns the reusable shape primitives — the 2D screen kinds **and the 3D `sphere`/`box` helpers**; masks and `surface_pattern` consume them and must not re-implement shapes; grids/rings are shape configurations (`repeat`; `ellipse` + `fill: stroke`), not separate features | Global Constraints (shared-ownership rule) + Task 1 (`SHAPE`, `primitive`/`fill`, `center`/`repeat`) + Task 5 (`vfx_shape_sdf`/`vfx_shape_coverage`, `vfx_shape_sdf_3d`/`vfx_shape_sphere_sdf`/`vfx_shape_box_sdf`) + Task 6 Step 5 + Task 7 Step 1 |
| §2 geometry fields `depth`, `depth_gradient(near,far)`, `normal_facing(axis,threshold)`, `screen_uv`, `world_pos` | Tasks 1/5 + Task 4/5 depth binding and world reconstruction |
| §2 per-function output types (`float`/`vec2`/`vec3`, texture `channel`) | Task 1 (`VFXFieldFn.outputType`, `VFXField.outputType`) |
| §2 composition `{ "op": ..., "a": ..., "b": ... }` with one operator, bounded | Task 1 (parse/caps) + Task 2 (post-order program) + Task 5 (`vfx_combine`) |
| §2 coercion validated at parse time (`float×float`, float broadcast, `vec3×vec3`, `vec2×vec3` error, `mix` float factor) | Task 1 (`VFXFieldType.combine` + `parseComposition`) + its check |
| §2 every numeric field parameter is a literal or `{ "from": <node> }`, integers rounded | Task 1 (`parseParameter`, `integerParam`) + Task 2 (`paramInteger`/`Math.round`) |
| §2 `field`/`fn`/`space` structural and not animatable | Task 1 (no graph reference accepted for them) |
| §2 field-capable inputs are a documented subset | Task 3 (`VFXEffectType.FIELD_INPUTS`) |
| §3.2 `{ "field": "<fn>", ... }` on inputs; everything optional, numeric defaults | Task 3 (definition wiring; `params.putIfAbsent(..., fieldNeutral)`) |
| §3.2 (existing block) `{ "from": "<node>" }` unchanged | Task 3 (the `from` branch is untouched) |
| §7 additive/backward compatibility, no `PROTOCOL_VERSION` bump, older mod ignores `field` | Task 3 check (plain definition unchanged) + Task 6 Step 3 (in-game) |
| §8 caps: field composition depth/leaf count; per-file refusal; errors name input/fn/param | Task 1 (`checkCaps`, messages) + Task 6 Step 7 |
| §8 field library evaluated in-shader, one eval per pixel, no per-frame allocation | Tasks 4–5 (single `FieldConfig` ring buffer, preallocated env, one `vfx_field_eval`) |
| §8 no per-frame allocation in hot paths | Tasks 2/4 (packed arrays, preallocated `Matrix4f`, `MappableRingBuffer`) |
| §9 step 4 first consumer `dent.intensity` modulated by noise | Task 6 (`dent_field_demo.json`) |
| AGENTS.md UBO field-order rule, stated where the contract is defined | Global Constraints + Task 4 (`FIELD_CONFIG_SIZE`) + Task 5 (`field.glsl` header) |
| AGENTS.md depth layer-0 constraint and graceful fallback | Global Constraints + Task 4 (`depthRecipeVerified`, `fld_depth_valid`) + Task 5 + Task 6 Step 6 |
| Documentation obligation | Task 7 |

**Out of scope (deliberately):**

- **GLSL codegen from the graph** — explicitly deferred by the spec (§1.4, §11); the field library is the substitute. This plan never generates shader source from a definition; the shader contains a fixed library selected by uniform index.
- **Custom field functions registered from Java** — deferred (§12, "GLSL codegen wearing a different hat"). `VFXFieldFn` is the closed built-in set.
- **Masks** (§4), **`surface_pattern`** and **`sparks`** (§6, §9 step 5), and **macros/logic nodes** (§3.1.1, step 2b) — separate plans; fields do not depend on them. The masks and `surface_pattern` plans **consume this library's shape primitives** (`vfx_shape_sdf`/`vfx_shape_coverage`) and must not re-implement shapes — the shared-ownership rule in Global Constraints. **The masks plan executes after this one** (step 4 before step 3), so this library's `field.glsl` (including the 3D `sphere`/`box` helpers and the `vfx_shape_sdf_dispatch`) exists first; masks only consume it.
- **Editors/UI** (out of scope for the whole project).
- Depth support on nodes other than 26.2 is bound but reported invalid (`fld_depth_valid = 0`) until the reversed-depth recipe is verified there; screen-space fields work on every node.

**Resolved spec ambiguities (recorded, as requested):**

1. **`dent.intensity` does not exist today** — the dent shader's input is `strength`. Resolved by *adding* an `intensity` field-capable input that multiplies `strength` (neutral `1.0`), leaving `strength` and every existing definition untouched. The shader change is a single multiply by `vfx_field_intensity`, which returns `1.0` when no field is declared.
2. **`curve` field semantics** — the spec lists `curve` as a source function but does not define its input coordinate or point cap. Resolved: a 1-D transfer over the chosen space's `x` coordinate (screen: `uv.x`; world: `fract(world.x * scale)`) with inline `points` (same shape as the graph `curve` node), capped at `MAX_CURVE_POINTS = 8`; control points are structural literals, not graph-animatable.
3. **`constant` field** — mentioned in spec §2 but omitted from the task's function list. Included (`{ "field": "constant", "value": n }`) so composition has a scalar source.
4. **`ring` vs `radial`** — superseded by item 10: neither is a function; a ring is `shape` with `primitive: ellipse` and `fill: stroke`.
5. **`space` default** — spec §2 says `world` is the default for scene-anchored functions; applied to all spatial functions (`noise`, `shape`, `gradient`, `curve`, `texture`); non-spatial functions reject `space`.
6. **`normal_facing` output** — defined as `smoothstep(threshold ± 0.05, dot(normalize(axis), n))`, with `n` from the cross product of reconstructed world positions.
7. **`depth`/`depth_gradient` linearization** — reversed-depth-aware: `near*far / ((far-near)*d + near)`, with `near`/`far` as the function's parameters (defaults 0/1 → raw depth).
8. **Texture sampling** — one texture leaf per input (sampler `fld_tex0`), enforced by `MAX_TEXTURE_LEAVES = 1`; `channel` omitted yields `vec3`.
9. **Depth on older nodes** — bound but marked invalid, so world/depth fields fall back to neutral rather than rendering with an unverified recipe.
10. **Shape primitives, grids and rings (design change approved by the project owner)** — the original `grid`/`ring` pattern set was too narrow. Resolved: one `shape` function with primitives `circle`/`ellipse`/`rect`/`polygon`, the common parameters `center`, `rotation`, `fill: solid|stroke`, `stroke_width`, `softness` and a `repeat: [nx, ny]` tiling modifier. **`grid` is a shape with `repeat`; a `ring` (and the old `radial` alias) is an `ellipse` with `fill: stroke`** — they are not separate features. The field library owns the single implementation (`vfx_shape_sdf` returns the raw distance, `vfx_shape_coverage` the `[0,1]` coverage, and `vfx_shape_sdf_3d` the 3D `sphere`/`box` distances for a 3D coordinate); the masks and `surface_pattern` plans consume it and are edited in parallel to state they must not re-implement shapes. The leaf output stays a `float` coverage, while the raw distance stays available for masks that perturb edges in distance space (mask design §4) — the two are kept distinct and documented.
11. **`center` and `repeat` are two-component** — the generic parameter vector is scalar, so the datapack spells them as two-element arrays whose elements are literals or `{ "from": "<node>" }`, desugared at parse time into the scalar slots `center_x`/`center_y` and `repeat_x`/`repeat_y`. `VFXField.MAX_PARAMS` grows from 4 to 16 to fit the widest function (`shape`), so the `FieldConfig` UBO gains a primitive `vec4`, a fill `vec4` and twelve more parameter `vec4`; `FIELD_CONFIG_SIZE`, `VFXFieldProgram.write` and `field.glsl` change together.

**Placeholder scan:** no "TBD"/"later"/"handle edge cases"; every step carries the code or the exact command. Verification steps never assert a visual result — they instruct the human partner and record the observation.

**Type consistency:** `VFXFieldProgram.write` order, `FIELD_CONFIG_SIZE` and `field.glsl`'s `FieldConfig` block are the same field list in the same order (uniform, depth_valid, leaf_count, fn, space, channel, primitive, fill, sixteen param vec4 — four per leaf, curve_count, four curve vec4, eight program floats, mat4, camera vec4, inv_size vec4). Function ordinals (`CONSTANT=0 … WORLD_POS=10`), operator ordinals (`MULTIPLY=0 … MAX=5`), channel codes (`r=0 … none=5`), shape primitive codes (`circle=0, ellipse=1, rect=2, polygon=3`) and fill codes (`solid=0, stroke=1`) are defined once in `VFXFieldFn`/`VFXFieldOp`/`VFXFieldProgram.channelCode`/`primitiveCode`/`fillCode` and consumed by `field.glsl`. `getFields`/`getFieldProgram`/`fieldNeedsDepth`/`evaluateGraphIndex`/`getGraphEvaluator` are used with the same signatures in Tasks 3, 4 and 6. `ProgramInfo.fieldInput` is set in `registerFieldPost` and read only through `VFXPass.fieldInput()`.


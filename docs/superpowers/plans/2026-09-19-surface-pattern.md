# Step 5 — `surface_pattern` (world-projected shape pattern) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the additive `surface_pattern` effect: a screen pass at **screen layer 0** that reads the main render target's depth, reconstructs the world position per pixel, and projects a **shape pattern** — `circle`, `ellipse`, `rect` or `polygon`, each with `center`, `rotation`, `fill: solid | stroke`, `stroke_width`, `softness` and an optional `repeat: [nx, ny]` tile modifier — onto whatever surface is behind the pixel. Driven by the numeric `params` `tile_scale`, `line_width`, `color_r/g/b`, `opacity`, `fade_radius`, `normal_mask`, `distort`; the figure/fill strings and the figure numbers live in a structural `pattern` block, never in `params`. The shape primitives and their signed-distance maths are **owned by the shared shape/field library** (extended in parallel by the per-pixel-fields edit); this effect is only the **world-projection consumer** of that library and contains no per-figure branch.

**Architecture:** The verified depth mechanism from `docs/superpowers/specs/notes/2026-09-19-depth-findings.md` is re-created permanently (the probe was removed): `VFXShaderPrograms` adds a depth-enabled pass layout (`InSampler` + `DepthSampler` + `SamplerInfo`/`Config`) and `VFXPostProcessingManager.VFXPass` gains a `mat4 inv_view_proj` prefix in the `Config` UBO (built per frame from the `VFXWorldBindings` camera snapshot: `viewRotProj * translate(-camPos)`, inverted) and binds `mainTarget.getDepthTextureView()` as `DepthSampler` with a `NEAREST` sampler. `surface_pattern` is registered only on `>=26.1`, forced to layer 0 so the single scene depth buffer is still populated, and degrades to a passthrough when the pass has no depth available. The structural `pattern` block is parsed in shared `src/main` (no client types) by delegating to the shared shape/field library's `dev.vfxweaver.field.VFXShape`, mirroring the existing top-level structural strings (`particle`, `shape`, `block`, `item`).

**Consumed from the shared shape/field library** (added by the parallel `docs/superpowers/plans/2026-09-19-per-pixel-fields.md` edit — do not re-implement here):

- CPU model in shared, MC-free `src/main/java/`:
  - `dev.vfxweaver.field.VFXShapeFigure` — `CIRCLE`, `ELLIPSE`, `RECT`, `POLYGON` (ordinal is the shader `figure` value); `fromString(String)`.
  - `dev.vfxweaver.field.VFXShapeFill` — `SOLID`, `STROKE` (ordinal is the shader `fill` value); `fromString(String)`.
  - `dev.vfxweaver.field.VFXShape` — `parse(JsonObject) : VFXShape`, validating the figure, its figure-specific parameters, the `[1,64]`-bounded `repeat` and the falloff; accessors `figure()`, `fill()`, `@Nullable float[] center()`, `rotation()`, `strokeWidth()`, `softness()`, `repeatX()`, `repeatY()`, `radius()`, `radiusX()`, `radiusY()`, `halfWidth()`, `halfHeight()`, `cornerRadius()`, `sides()`.
- GPU library `src/client/resources/assets/vfxweaver/shaders/include/shape.glsl` (imported as `<vfxweaver:shape.glsl>`; **pure functions, no UBO**, so a consumer keeps its own `Config`):

```glsl
// Owned by the shared shape/field library. Returns coverage in [0,1].
// figure/fill are the VFXShapeFigure/VFXShapeFill ordinals; p is the cell-local coordinate.
// shape0 = (radius, radius_x, radius_y, half_width); shape1 = (half_height, corner_radius, sides, rotation).
float vfx_shape_coverage(int figure, int fill, vec2 p, vec2 repeat,
                         vec4 shape0, vec4 shape1, float stroke_width, float softness);
```

`surface_pattern` calls that function and never contains figure maths. A **grid** is any figure with a `repeat` modifier greater than `1` (or a stroked `rect`); a **ring** is an `ellipse` with `fill: "stroke"`.

**Tech Stack:** Java 25 (JDK 26 build), Gradle 9.5.1, Stonecutter 0.9.8, GLSL 330 through `RenderPipelines.POST_PROCESSING_SNIPPET`, joml `Matrix4f`, `com.mojang.blaze3d.buffers.Std140Builder.putMat4f`, `net.minecraft.client.renderer.MappableRingBuffer`.

**Spec:** `docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md` (§5 Depth mechanism, §6.2 `surface_pattern`, §9 step 5, §11 anti-patterns). Depth facts: `docs/superpowers/specs/notes/2026-09-19-depth-findings.md` (binding, layer 0, reversed depth, inverse view-projection, Fabric/NeoForge/Iris). Shape/field library (parallel edit): `docs/superpowers/plans/2026-09-19-per-pixel-fields.md`. Masks: `docs/superpowers/plans/2026-09-19-masks.md`. Format model to match: `docs/superpowers/plans/2026-09-19-uniform-graph-core.md`.

## Global Constraints

- One shared `src/`; no `net.fabricmc.*` / `net.neoforged.*` imports outside `dev.vfxweaver.platform` and `dev.vfxweaver.client.platform`.
- The `pattern` block is parsed in `src/main` (`VFXDefinition`; `net.minecraft.resources.Identifier` and `net.minecraft.util.GsonHelper` are common types) and must stay free of `net.minecraft.client.*`. The shape model it delegates to (`dev.vfxweaver.field.VFXShape`) is also shared `src/main` and MC-free.
- **The shape maths is not written here.** `circle`/`ellipse`/`rect`/`polygon`, `fill`, `rotation`, `stroke_width`, `softness` and the `repeat` tile modifier are primitives of the shared shape/field library; the shader imports `<vfxweaver:shape.glsl>` and calls `vfx_shape_coverage`. The library's figure/fill ordinals are the single source of truth for the shader values. Do not add a grid/ring branch and do not duplicate a figure's signed-distance function in `surface_pattern.fsh`.
- **Any limiting of the pattern is the shared mask mechanism**, owned by `docs/superpowers/plans/2026-09-19-masks.md` (a layer-0 coverage prepass consumed by effects). This plan references it and must not define or contradict a second mask mechanism.
- Indentation is tabs; non-reassigned params/locals are `final`; public classes and non-trivial public methods carry javadoc.
- No new dependencies.
- The datapack format is **additive**: `pattern` is optional and unknown top-level keys are already ignored by older mods; no `PROTOCOL_VERSION` bump (spec §7). No existing effect changes behaviour.
- **A post shader's `Config` block is a std140 UBO: the fields in the shader and the names in `VFXShaderPrograms.register*Post(...)` must be the same set in the same order** — the offsets are positional, so a mismatch silently shifts values. The depth pass is the first with a `mat4` prefix: the shader declares `mat4 inv_view_proj;` first, the manager writes it first, and the named float params follow in order. Never name a uniform after a GLSL built-in (AGENTS.md).
- **The depth-reading pass must run at screen layer 0** (depth findings point 2). `surface_pattern` defaults its `screen_layer` to 0; at layer 1+ the single scene depth buffer holds only the first-person hand.
- **The shape `center` and the anchor rule are one thing.** A shape's structural `center` (optional literal `[x,y,z]`) is the anchor when present; when absent the anchor is the effect's first declared world `position` (block centre), else the camera snapshot, else `(0,0,0)`. All three are uploaded as the reserved `center_x/y/z` — positional data, never an effect `param`.
- Bounded collections (AGENTS.md): the figure/fill set is closed by the shared library and the `repeat` components are validated by it to `[1, 64]`; nothing collection-like is added by this effect.
- There is **no test suite**: every task is verified by (a) building all six nodes, (b) for the parse layer a throwaway `main()` compiled against the built classes with assertions (AGENTS.md "Build and verify" method 2), and (c) an in-game check by the human partner. Never claim a visual result.
- Build commands (from `AGENTS.md`): `.\gradlew.bat :<node>:build`; Fabric nodes are `26.2`, `26.1.2`, `1.21.11`; NeoForge nodes are the same names with `-neoforge`.
- Verified API (26.2 and 26.1.2, `javap`): `RenderTarget.getDepthTextureView() : GpuTextureView`, `RenderTarget.useDepth : boolean`, `Camera.position()`/`getViewRotationProjectionMatrix(Matrix4f)`, `GameRenderer.mainCamera()` (26.2) / `getMainCamera()` (26.1.2), `Std140Builder.putMat4f(Matrix4fc)`, `MappableRingBuffer(Supplier<String>, int, int)`. Do not guess new ones.
- The depth pass is registered only `>=26.1`; on `1.21.11` (`<26.1`) the effect type and built-in JSON still exist and build, but the pass is not registered (renders nothing). Depth was verified on 26.x only; do not invent 1.21.11 APIs. The consumed shape library is common code and ships on all nodes.

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

A "run it to verify it fails" step runs only the `javac` line (expected: the missing package/class); a "build and run" step runs both and expects a clean `Check OK` line.

**Assertion counts are approximate:** a check passes on "zero failures", not on the exact number in its `Expected` line.

## File Structure

- `src/main/java/dev/vfxweaver/effect/VFXDefinition.java` — modify: parse the optional top-level structural `pattern` object by delegating to the shared `VFXShape.parse`, expose `getPattern() : @Nullable VFXShape`, carry it through `withParams`. No shape maths.
- `src/main/java/dev/vfxweaver/effect/VFXEffectType.java` — modify: add `SURFACE_PATTERN("surface_pattern")` and its `neutralValue` case.
- `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` — modify: add the permanent `DepthSampler` bind-group layout, `ProgramInfo.depth`, and `registerDepthPost` (registered only `>=26.1`).
- `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java` — modify: `VFXPass` writes the inverse view-projection matrix, binds the depth sampler, resolves the reserved `center_*`/figure values from the shared `VFXShape`, and `process` forces `surface_pattern` to layer 0 + substitutes a passthrough when depth is unavailable.
- `src/client/resources/assets/vfxweaver/shaders/post/surface_pattern.fsh` — create: the fragment shader (imports the shared shape library, no figure maths).
- `src/main/resources/data/vfxweaver/vfx/surface_pattern.json` — create: the built-in consumer.
- `docs/GUIDE.md`, `docs/CHANGELOG.md` — modify: document the effect and the `pattern` block.

The shared shape/field library files (`dev.vfxweaver.field.VFXShape`/`VFXShapeFigure`/`VFXShapeFill` and `assets/vfxweaver/shaders/include/shape.glsl`) are **created by the parallel per-pixel-fields edit**, not by this plan.

---

### Task 1: Parse the structural `pattern` block in `VFXDefinition`

**Files:**
- Modify: `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`
- Test: `%TEMP%\vfxcheck\Check.java` (throwaway, not committed)

**Interfaces:**
- Consumes: the shared shape model `dev.vfxweaver.field.VFXShape` with `VFXShape.parse(JsonObject)`, `VFXShapeFigure`, `VFXShapeFill` (added by the parallel per-pixel-fields edit; this task depends on that edit having landed — the figure set, its per-figure validation and the `[1,64]` `repeat` bound are all the library's); `GsonHelper.getAsJsonObject(JsonObject, String)`.
- Produces:
  - The top-level optional `pattern` object parsed into a `@Nullable VFXShape`.
  - `VFXDefinition.getPattern() : @Nullable VFXShape`.

- [ ] **Step 1: Write the failing check**

Create `%TEMP%\vfxcheck\Check.java` with exactly this content:

```java
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.field.VFXShapeFill;
import dev.vfxweaver.field.VFXShapeFigure;
import net.minecraft.resources.Identifier;

public class Check {
	static int passed = 0;

	static VFXDefinition parse(final String body) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath("test", "pattern"),
			JsonParser.parseString(body).getAsJsonObject());
	}

	static void assertTrue(final String label, final boolean condition) {
		if (!condition) {
			throw new AssertionError(label);
		}
		passed++;
	}

	public static void main(final String[] args) {
		final VFXDefinition plain = parse("{\"type\":\"surface_pattern\"}");
		assertTrue("no pattern block is null", plain.getPattern() == null);

		// A circle is one figure with a radius; fill/repeat/center have structural defaults.
		final VFXDefinition circle = parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"circle\",\"radius\":0.4}}");
		assertTrue("circle figure", circle.getPattern().figure() == VFXShapeFigure.CIRCLE);
		assertTrue("circle radius", Math.abs(circle.getPattern().radius() - 0.4F) < 1.0e-4F);
		assertTrue("fill defaults solid", circle.getPattern().fill() == VFXShapeFill.SOLID);
		assertTrue("repeat defaults 1x1", circle.getPattern().repeatX() == 1 && circle.getPattern().repeatY() == 1);
		assertTrue("center defaults unset", circle.getPattern().center() == null);

		// A ring is an ellipse with fill: stroke (there is no separate 'ring' mode).
		final VFXDefinition ring = parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"ellipse\",\"radius_x\":0.45,\"radius_y\":0.3,\"fill\":\"stroke\",\"stroke_width\":0.03,\"center\":[10,64,-5]}}");
		assertTrue("ellipse figure", ring.getPattern().figure() == VFXShapeFigure.ELLIPSE);
		assertTrue("ellipse radii", Math.abs(ring.getPattern().radiusX() - 0.45F) < 1.0e-4F && Math.abs(ring.getPattern().radiusY() - 0.3F) < 1.0e-4F);
		assertTrue("ring is a stroked ellipse", ring.getPattern().fill() == VFXShapeFill.STROKE);
		assertTrue("ellipse stroke width", Math.abs(ring.getPattern().strokeWidth() - 0.03F) < 1.0e-4F);
		assertTrue("explicit center", ring.getPattern().center() != null && Math.abs(ring.getPattern().center()[0] - 10.0F) < 1.0e-4F);

		// A grid is a repeated figure, not a mode: a stroked rect tiled by 'repeat'.
		final VFXDefinition grid = parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"rect\",\"half_width\":0.5,\"half_height\":0.5,\"corner_radius\":0.1,\"fill\":\"stroke\",\"repeat\":[8,8]}}");
		assertTrue("rect figure", grid.getPattern().figure() == VFXShapeFigure.RECT);
		assertTrue("rect extents", Math.abs(grid.getPattern().halfWidth() - 0.5F) < 1.0e-4F && Math.abs(grid.getPattern().halfHeight() - 0.5F) < 1.0e-4F);
		assertTrue("rect corner radius", Math.abs(grid.getPattern().cornerRadius() - 0.1F) < 1.0e-4F);
		assertTrue("repeat 8x8", grid.getPattern().repeatX() == 8 && grid.getPattern().repeatY() == 8);

		final VFXDefinition polygon = parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"polygon\",\"sides\":6,\"radius\":0.4}}");
		assertTrue("polygon figure", polygon.getPattern().figure() == VFXShapeFigure.POLYGON);
		assertTrue("polygon sides", polygon.getPattern().sides() == 6);

		try {
			parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"triangle\"}}");
			throw new AssertionError("unknown figure must be refused");
		} catch (IllegalArgumentException e) {
			passed++;
		}
		try {
			parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"polygon\",\"sides\":2,\"radius\":0.4}}");
			throw new AssertionError("polygon with fewer than 3 sides must be refused");
		} catch (IllegalArgumentException e) {
			passed++;
		}
		try {
			parse("{\"type\":\"surface_pattern\",\"pattern\":{\"figure\":\"circle\",\"radius\":0.4,\"repeat\":[0,1]}}");
			throw new AssertionError("out-of-range repeat must be refused");
		} catch (IllegalArgumentException e) {
			passed++;
		}

		System.out.println("Check OK: " + passed + " assertions");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the shared standalone-check recipe (Global Constraints) from the repo root, `javac` line only (after `.\gradlew.bat :26.1.2:build --console=plain` so the classes exist).

Expected: `javac` errors on `VFXDefinition.getPattern()` / `dev.vfxweaver.field.VFXShape`.

- [ ] **Step 3: Parse the structural shape block (delegating to the shared library)**

The figure set, the per-figure parameter validation and the `repeat` bound live in `dev.vfxweaver.field.VFXShape`; this step only carries the parsed value through the definition. Do not add a figure switch here.

Add the import:

```java
import dev.vfxweaver.field.VFXShape;
```

- [ ] **Step 4: Carry `pattern` through the definition**

Add the field after `graphInputs` (line 46):

```java
	private final @Nullable VFXShape pattern;
```

Add the parameter at the **end** of the private constructor signature (after `final Map<String, String> graphInputs`) and assign it:

```java
		final @Nullable VFXShape pattern
```

```java
		this.pattern = pattern;
```

Append `null` as the last argument of the single `new VFXDefinition(...)` in the 12-argument `create(...)` (currently line 156).

Append `this.pattern` as the last argument of the `new VFXDefinition(...)` in `withParams(...)` (currently line 547).

In `parse(...)`, after the `itemId` block (currently line 278) and before the final `return`, insert:

```java
		// Optional structural shape pattern (spec §6.2, design change 2026-09-19). The figure model
		// (circle/ellipse/rect/polygon, fill, repeat) is owned by the shared shape/field library;
		// this only parses the structural block and carries it. Strings/enum choices never live in
		// "params" (numeric only) and the whole block is invisible to an older mod (unknown key).
		VFXShape pattern = json.has("pattern") && !json.get("pattern").isJsonNull()
			? VFXShape.parse(GsonHelper.getAsJsonObject(json, "pattern"))
			: null;
```

Append `pattern` as the last argument of the `return new VFXDefinition(...)` in `parse` (currently line 280).

Add the getter near `getItemId()`:

```java
	/**
	 * The optional structural shape pattern of a {@code surface_pattern} definition, or
	 * {@code null} when the definition has none (the renderer then draws nothing).
	 */
	public @Nullable VFXShape getPattern() {
		return this.pattern;
	}
```

- [ ] **Step 5: Build the active node and run the check**

Run `.\gradlew.bat :26.1.2:build --console=plain` first, then the shared standalone-check recipe (Global Constraints), both lines.

Expected: `BUILD SUCCESSFUL` then a clean `Check OK` line (assertion counts are approximate — zero failures is the pass condition).

- [ ] **Step 6: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. The delegation to `VFXShape` is pure common code, so a failure means an accidental `net.minecraft.client.*` import — fix the import, not the build.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/vfxweaver/effect/VFXDefinition.java
git commit -m "feat(surface_pattern): parse the structural shape pattern block"
```

---

### Task 2: Add the `surface_pattern` effect type

**Files:**
- Modify: `src/main/java/dev/vfxweaver/effect/VFXEffectType.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `VFXEffectType.SURFACE_PATTERN` whose `getName()` is `"surface_pattern"`.
  - `VFXEffectType.neutralValue("opacity")` returns `0.0F` for `SURFACE_PATTERN`, `Float.NaN` otherwise.

- [ ] **Step 1: Add the enum constant**

In `src/main/java/dev/vfxweaver/effect/VFXEffectType.java`, add after `NOISE_WARP` (before `COLLECTION`):

```java
	/** A world-anchored shape pattern (circle/ellipse/rect/polygon, tiled by `repeat`) projected onto the depth-reconstructed surface (post pass at screen layer 0). */
	SURFACE_PATTERN("surface_pattern"),
```

- [ ] **Step 2: Add the neutral value**

In the `neutralValue(String parameter)` switch, add a case next to the other post effects (before `default`):

```java
			case SURFACE_PATTERN -> "opacity".equals(parameter) ? 0.0F : Float.NaN;
```

- [ ] **Step 3: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. `SURFACE_PATTERN` is not in `isWorldOverlay`, so `isPostProcessing()` returns true by default (correct).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/vfxweaver/effect/VFXEffectType.java
git commit -m "feat(surface_pattern): add the effect type and its neutral value"
```

---

### Task 3: Depth pass plumbing in `VFXShaderPrograms`

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java`

**Interfaces:**
- Consumes: `VFXEffectType.SURFACE_PATTERN` (Task 2).
- Produces:
  - `ProgramInfo` gains the component `boolean depth`; the existing 3- and 4-argument convenience constructors default it to `false`.
  - `VFXShaderPrograms.registerDepthPost(VFXEffectType type, String... params)` — private, builds a pipeline with `InSampler` + `DepthSampler` + `SamplerInfo` + `Config` and stores a `ProgramInfo` with `depth = true` and `configUboSize = 64 + align16(params.length * 4)`.
  - `VFXEffectType.SURFACE_PATTERN` maps to exactly one `ProgramInfo` whose `configParams` are, in order: `tile_scale`, `color_r`, `color_g`, `color_b`, `opacity`, `fade_radius`, `normal_mask`, `distort`, `center_x`, `center_y`, `center_z`, `shape`, `fill`, `rotation`, `stroke_width`, `softness`, `repeat_x`, `repeat_y`, `radius`, `radius_x`, `radius_y`, `half_width`, `half_height`, `corner_radius`, `sides`, `time`. (`shape`/`fill` are the `VFXShapeFigure`/`VFXShapeFill` ordinals; every figure number is uploaded so the shared shape library dispatches on it.)

- [ ] **Step 1: Extend `ProgramInfo`**

In `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java`, replace the `ProgramInfo` record (currently lines 41-45) with:

```java
	/**
	 * Describes one effect shader: its pipeline plus the ordered float parameter names of the
	 * {@code Config} uniform block and its std140-aligned byte size.
	 *
	 * @param depth true when the pass reads the scene depth sampler (and the shader's {@code Config} starts with {@code mat4 inv_view_proj})
	 */
	public record ProgramInfo(RenderPipeline pipeline, String[] configParams, int configUboSize, PassRole role, boolean depth) {
		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize) {
			this(pipeline, configParams, configUboSize, PassRole.NORMAL, false);
		}

		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize, final PassRole role) {
			this(pipeline, configParams, configUboSize, role, false);
		}
	}
```

- [ ] **Step 2: Add the permanent bind-group layouts**

Extend the existing `//? if >=26.2` layout block (currently lines 50-62) so it also declares the depth layout:

```java
	//? if >=26.2 {
	/*	// 26.2 moved sampler/uniform declarations to explicit bind-group layouts.
	private static final BindGroupLayout HIST_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("HistSampler")
		.build();
	private static final BindGroupLayout DEPTH_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("DepthSampler")
		.build();
	private static final BindGroupLayout SAMPLER_INFO_LAYOUT = BindGroupLayout.builder()
		.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout SAMPLER_INFO_CONFIG_LAYOUT = BindGroupLayout.builder()
		.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
		.withUniform("Config", UniformType.UNIFORM_BUFFER)
		.build();
	*///?}
```

- [ ] **Step 3: Register the depth pass**

In `register()` (currently lines 70-119), after the `registerPost(VFXEffectType.NOISE_WARP, ...)` call and before `copyPipeline = ...`, insert:

```java
		// surface_pattern reads scene depth and reconstructs a world position: it is the only
		// pass that needs the depth mechanism (spec §5, §9 step 5). Registered on 26.x only —
		// the depth mechanism was verified on 26.1.2/26.2, and the pass is a no-op on 1.21.11.
		// Every shape number is uploaded; the shared shape library dispatches on it, so there is
		// no grid/ring branch on this side.
		//? if >=26.1 {
		registerDepthPost(VFXEffectType.SURFACE_PATTERN,
			"tile_scale", "color_r", "color_g", "color_b", "opacity",
			"fade_radius", "normal_mask", "distort",
			"center_x", "center_y", "center_z", "shape", "fill",
			"rotation", "stroke_width", "softness", "repeat_x", "repeat_y",
			"radius", "radius_x", "radius_y", "half_width", "half_height",
			"corner_radius", "sides", "time");
		//?}
```

- [ ] **Step 4: Add `registerDepthPost`**

Add the method next to `registerMultiPass` (currently lines 178-200):

```java
	/**
	 * Registers a single-pass effect that reads the scene depth as {@code DepthSampler}, in
	 * addition to the usual {@code InSampler} and the {@code SamplerInfo}/{@code Config} UBOs.
	 * The shader's {@code Config} block must declare {@code mat4 inv_view_proj;} first, then the
	 * {@code params} floats in the exact order given here (std140 offsets are positional). The
	 * shape itself is evaluated by the shared shape library; this pass supplies its numbers.
	 */
	private static void registerDepthPost(final VFXEffectType type, final String... params) {
		Identifier location = Identifier.fromNamespaceAndPath("vfxweaver", "post/" + type.getName());
		RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(location)
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "post/" + type.getName()))
			//? if <26.2 {
			.withSampler("InSampler")
			.withSampler("DepthSampler")
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.withUniform("Config", UniformType.UNIFORM_BUFFER)
			//?} else {
			/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withBindGroupLayout(DEPTH_SAMPLER_LAYOUT)
			.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
			*///?}
			.build();
		RenderPipelines.register(pipeline);
		PROGRAMS.put(type, List.of(new ProgramInfo(pipeline, params, 64 + align16(params.length * 4), PassRole.NORMAL, true)));
	}
```

- [ ] **Step 5: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six.

On `1.21.11` the `//? if >=26.1 { registerDepthPost(...); }` block is absent, so the `<26.1` half of `registerDepthPost` is compiled by no node and may look unreferenced — that is expected; the `26.1.2` node compiles the `<26.2` branch, the `26.2` nodes the `else` branch. If `26.1.2` fails on `DepthSampler`, the active Stonecutter branch is the wrong side — fix the guard, not the API call.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java
git commit -m "feat(surface_pattern): register the depth post pass (>=26.1)"
```

---

### Task 4: Bind depth and the inverse view-projection in `VFXPostProcessingManager`

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java`

**Interfaces:**
- Consumes: `ProgramInfo.depth()` (Task 3), `VFXDefinition.getPattern() : @Nullable VFXShape` (Task 1), `VFXWorldBindings.currentFrame()` (`Frame.camX()/camY()/camZ()/viewRotProj()`), `mainTarget.getDepthTextureView()/useDepth`.
- Produces:
  - `process` forces `surface_pattern` to screen layer 0 and substitutes the copy pass as a passthrough (with a `VFXLog.warnOnce`) when the main target has no usable depth.
  - `VFXPass` binds `DepthSampler` (NEAREST) and writes `mat4 inv_view_proj` as the first `Config` field for depth passes.
  - Reserved `Config` names resolved per effect: `center_x`/`center_y`/`center_z` (the shape `center` when present, else the first declared world `position`, else the camera snapshot, else 0), `shape`/`fill` (the `VFXShape` ordinals), the figure numbers (`rotation`, `stroke_width`, `softness`, `repeat_x`, `repeat_y`, `radius`, `radius_x`, `radius_y`, `half_width`, `half_height`, `corner_radius`, `sides`), and `time` (effect age).

- [ ] **Step 1: Add the imports**

In `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java`, add:

```java
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.field.VFXShape;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXLog;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
```

- [ ] **Step 2: Force layer 0 and add the depth-availability gate**

In `process(...)`, replace the active-effect filter (currently lines 112-117) with:

```java
		VFXWorldBindings.Frame cameraFrame = VFXWorldBindings.currentFrame();
		// The scene depth buffer is only intact at screen layer 0 (depth findings point 2), so
		// surface_pattern defaults there. Depth is also unusable when the main target has no
		// depth attachment or the camera snapshot is missing (e.g. the first frame after load):
		// the pass is then replaced by a passthrough below instead of reading garbage.
		boolean depthReady = mainTarget.useDepth && mainTarget.getDepthTextureView() != null && cameraFrame != null;
		List<VFXActiveEffect> active = new ArrayList<>();
		for (VFXActiveEffect effect : effects.getActivePostEffects()) {
			float defaultLayer = effect.getType() == VFXEffectType.SURFACE_PATTERN ? 0.0F : 1.0F;
			if (Math.round(Mth.clamp(effect.getParam("screen_layer", defaultLayer), 0.0F, 2.0F)) == layer) {
				active.add(effect);
			}
		}
```

Then replace the chain-expansion loop (currently lines 128-133) with:

```java
		List<PassRun> chain = new ArrayList<>();
		for (VFXActiveEffect effect : active) {
			for (VFXShaderPrograms.ProgramInfo info : VFXShaderPrograms.getPrograms(effect.getType())) {
				if (info.depth() && !depthReady) {
					VFXLog.warnOnce(LOGGER, "surface_pattern:nodepth:" + effect.getId(),
						"Effect '{}' needs scene depth but it is unavailable (main target depth missing or camera not ready); rendering a passthrough", effect.getId());
					chain.add(new PassRun(this.copyPass(), effect));
					continue;
				}
				VFXPass pass = this.pass(info);
				pass.setMainTarget(mainTarget);
				chain.add(new PassRun(pass, effect));
			}
		}
```

- [ ] **Step 3: Add the pass fields and constructor reads**

In `VFXPass`, add the fields next to the existing ones (currently lines 300-305):

```java
		private final boolean depth;
		private @Nullable RenderTarget mainTarget;
		private final Matrix4f scratchInvViewProj = new Matrix4f();
		private final Matrix4f scratchTranslate = new Matrix4f();
		private final Vector3f scratchAnchor = new Vector3f();
```

Add the setter next to `role()`:

```java
		void setMainTarget(final RenderTarget target) {
			this.mainTarget = target;
		}
```

In the `VFXPass` constructor, after `this.role = info.role();` (currently line 310), add:

```java
			this.depth = info.depth();
```

- [ ] **Step 4: Write the matrix prefix**

In `execute(...)`, after `Std140Builder builder = Std140Builder.intoBuffer(view.data());` (currently line 345), insert:

```java
					if (this.depth) {
						// spec §5 / depth findings: viewRotProj has no translation; post-multiply
						// translate(-camPos), then invert. Reversed depth means the sampled value
						// is fed to the inverse as-is.
						VFXWorldBindings.Frame frame = VFXWorldBindings.currentFrame();
						if (frame != null) {
							this.scratchTranslate.translation(-frame.camX(), -frame.camY(), -frame.camZ());
							this.scratchInvViewProj.set(frame.viewRotProj()).mul(this.scratchTranslate).invert();
						} else {
							this.scratchInvViewProj.identity();
						}
						builder.putMat4f(this.scratchInvViewProj);
					}
```

- [ ] **Step 5: Resolve the reserved params**

Immediately **before** the `if (this.configUbo != null && effect != null) {` block (currently line 338), insert the shape/anchor resolution — it must be outside that `if` so the param loop and the depth bind below can see `shape`:

```java
			final VFXShape shape = this.depth && effect != null ? shapeSpec(effect) : null;
			if (effect != null) {
				this.resolveAnchor(effect, shape);
			}
```

Then replace the whole param loop (currently lines 346-360) with:

```java
					for (String param : this.configParams) {
						// Reserved parameters never come from the timeline: "time"/"hold" are the
						// existing reserved names; the "center_*"/figure names describe the world
						// anchor and the structural shape block, all owned by the shared VFXShape.
						float raw;
						boolean reserved = "time".equals(param) || "hold".equals(param);
						if ("time".equals(param)) {
							raw = effect.getAge();
						} else if ("hold".equals(param)) {
							raw = hold != null ? hold : 0.0F;
						} else if ("center_x".equals(param)) {
							raw = this.scratchAnchor.x;
							reserved = true;
						} else if ("center_y".equals(param)) {
							raw = this.scratchAnchor.y;
							reserved = true;
						} else if ("center_z".equals(param)) {
							raw = this.scratchAnchor.z;
							reserved = true;
						} else if ("shape".equals(param)) {
							raw = shape == null ? 0.0F : shape.figure().ordinal();
							reserved = true;
						} else if ("fill".equals(param)) {
							raw = shape == null ? 0.0F : shape.fill().ordinal();
							reserved = true;
						} else if ("rotation".equals(param)) {
							raw = shape == null ? 0.0F : shape.rotation();
							reserved = true;
						} else if ("stroke_width".equals(param)) {
							// The numeric line_width param, when authored, overrides the shape's structural stroke width.
							raw = effect.getParam("line_width", shape == null ? 0.0F : shape.strokeWidth());
							reserved = true;
						} else if ("softness".equals(param)) {
							raw = shape == null ? 0.0F : shape.softness();
							reserved = true;
						} else if ("repeat_x".equals(param)) {
							raw = shape == null ? 1.0F : shape.repeatX();
							reserved = true;
						} else if ("repeat_y".equals(param)) {
							raw = shape == null ? 1.0F : shape.repeatY();
							reserved = true;
						} else if ("radius".equals(param)) {
							raw = shape == null ? 0.0F : shape.radius();
							reserved = true;
						} else if ("radius_x".equals(param)) {
							raw = shape == null ? 0.0F : shape.radiusX();
							reserved = true;
						} else if ("radius_y".equals(param)) {
							raw = shape == null ? 0.0F : shape.radiusY();
							reserved = true;
						} else if ("half_width".equals(param)) {
							raw = shape == null ? 0.0F : shape.halfWidth();
							reserved = true;
						} else if ("half_height".equals(param)) {
							raw = shape == null ? 0.0F : shape.halfHeight();
							reserved = true;
						} else if ("corner_radius".equals(param)) {
							raw = shape == null ? 0.0F : shape.cornerRadius();
							reserved = true;
						} else if ("sides".equals(param)) {
							raw = shape == null ? 3.0F : shape.sides();
							reserved = true;
						} else {
							raw = effect.getParam(param, 0.0F);
						}
						float neutral = reserved ? Float.NaN : effect.getType().neutralValue(param);
						builder.putFloat(Float.isNaN(neutral) ? raw : neutral + (raw - neutral) * weight);
					}
```

- [ ] **Step 6: Bind the depth sampler**

After `renderPass.bindTexture("InSampler", ...)` (currently line 379) and before the `history` bind, add:

```java
				if (this.depth && this.mainTarget != null && this.mainTarget.getDepthTextureView() != null) {
					// A depth texture is not filterable: bind the closest texel (depth findings).
					renderPass.bindTexture("DepthSampler", this.mainTarget.getDepthTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST));
				}
```

**Note:** `this.mainTarget` is set by `process` while building the chain.

- [ ] **Step 7: Add the two helpers**

Add to `VFXPass`:

```java
		private static @Nullable VFXShape shapeSpec(final VFXActiveEffect effect) {
			final VFXDefinition definition = VFXDefinitionManager.get().get(effect.getId());
			return definition == null ? null : definition.getPattern();
		}

		/** Fills {@link #scratchAnchor}: the shape's structural centre, else the first world position, else the camera, else 0. */
		private void resolveAnchor(final @Nullable VFXActiveEffect effect, final @Nullable VFXShape shape) {
			if (shape != null && shape.center() != null) {
				final float[] center = shape.center();
				this.scratchAnchor.set(center[0], center[1], center[2]);
				return;
			}
			if (effect != null && !effect.getPositions().isEmpty()) {
				BlockPos pos = effect.getPositions().get(0);
				this.scratchAnchor.set(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
				return;
			}
			VFXWorldBindings.Frame frame = VFXWorldBindings.currentFrame();
			if (frame != null) {
				this.scratchAnchor.set(frame.camX(), frame.camY(), frame.camZ());
			} else {
				this.scratchAnchor.set(0.0F, 0.0F, 0.0F);
			}
		}
```

- [ ] **Step 8: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. The manager file is shared: if `1.21.11` fails on a depth-only symbol, guard **just that statement** in place with `//? if >=26.1 { … //?}` rather than moving whole methods. `Matrix4f`, `VFXWorldBindings` and `VFXShape` are common and should compile everywhere; the only depth-gated statement that may need a guard on `<26.1` is the `mainTarget.getDepthTextureView()` bind (verify with the build, do not pre-emptively guard).

- [ ] **Step 9: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java
git commit -m "feat(surface_pattern): bind depth and upload the inverse view-projection"
```

---

### Task 5: The `surface_pattern` fragment shader

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/post/surface_pattern.fsh`

**Interfaces:**
- Consumes: the `Config` layout and reserved names from Tasks 3-4, and `vfx_shape_coverage` from the shared `assets/vfxweaver/shaders/include/shape.glsl` (parallel edit).
- Produces: a fragment shader that writes `vec4(mix(base.rgb, color, coverage), base.a)` with no figure branch of its own.

- [ ] **Step 1: Create the shader**

Create `src/client/resources/assets/vfxweaver/shaders/post/surface_pattern.fsh`:

```glsl
#version 330

// The shape primitives (circle/ellipse/rect/polygon), their fill, rotation, stroke/softness and
// the repeat/tile modifier are the shared shape/field library's. This shader only reconstructs the
// world position, maps it into the cell-local coordinate and paints the library's coverage; it
// contains no figure maths and no grid/ring branch.
#moj_import <vfxweaver:shape.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// The mat4 prefix is written by VFXPostProcessingManager for depth passes; the floats after it
// must match registerDepthPost(...) in the same order (std140 offsets are positional).
layout(std140) uniform Config {
    mat4 inv_view_proj;
    float tile_scale;
    float color_r;
    float color_g;
    float color_b;
    float opacity;
    float fade_radius;
    float normal_mask;
    float distort;
    float center_x;
    float center_y;
    float center_z;
    float shape;
    float fill;
    float rotation;
    float stroke_width;
    float softness;
    float repeat_x;
    float repeat_y;
    float radius;
    float radius_x;
    float radius_y;
    float half_width;
    float half_height;
    float corner_radius;
    float sides;
    float time;
};

out vec4 fragColor;

void main() {
    vec4 base = texture(InSampler, texCoord);

    // Reversed depth: near = 1, far/sky = 0. Nothing behind the pixel -> passthrough.
    float depth = texture(DepthSampler, texCoord).r;
    if (depth <= 1.0e-6) {
        fragColor = base;
        return;
    }

    // spec §5 / depth findings: the sampled value is NDC z, fed as-is.
    vec4 clip = vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec4 world4 = inv_view_proj * clip;
    if (abs(world4.w) < 1.0e-6) {
        fragColor = base;
        return;
    }
    vec3 world = world4.xyz / world4.w;

    vec2 p = world.xz;
    if (distort != 0.0) {
        // ponytail: cheap sine warp; field-library noise distortion arrives with spec step 4.
        p += distort * vec2(sin(world.y * 0.7 + time * 0.05), cos(world.y * 0.7 - time * 0.05));
    }

    // Cell-local coordinate: centre on the anchor, scale to a tile_scale cell. The shared shape
    // library owns every figure's signed-distance function, the fill, the rotation and the repeat
    // modifier -- there is no figure branch here.
    vec2 cell = (p - vec2(center_x, center_z)) / max(tile_scale, 1.0e-4);
    vec4 shape0 = vec4(radius, radius_x, radius_y, half_width);
    vec4 shape1 = vec4(half_height, corner_radius, sides, rotation);
    float shapeCoverage = vfx_shape_coverage(int(shape + 0.5), int(fill + 0.5), cell,
        vec2(repeat_x, repeat_y), shape0, shape1, stroke_width, softness);

    // Distance fade from the anchor (fade_radius <= 0 disables it).
    float fade = 1.0;
    if (fade_radius > 0.0) {
        float dist = length(p - vec2(center_x, center_z));
        fade = 1.0 - smoothstep(fade_radius * 0.5, fade_radius, dist);
    }

    // Surface orientation from the depth-reconstructed world position. |n.y| treats floor and
    // ceiling alike: the depth-derived normal sign is ambiguous without extra state (see Self-Review).
    vec3 n = normalize(cross(dFdx(world), dFdy(world)));
    float upness = abs(n.y);
    float mask = normal_mask <= 0.0 ? 1.0 : smoothstep(normal_mask, min(normal_mask + 0.2, 1.0), upness);

    float coverage = clamp(shapeCoverage * fade * mask * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
    fragColor = vec4(mix(base.rgb, vec3(color_r, color_g, color_b), coverage), base.a);
}
```

- [ ] **Step 2: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six (the shader is a resource; the build does not compile GLSL). Confirm it ships in every jar: `jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists `assets/vfxweaver/shaders/post/surface_pattern.fsh` and the consumed `assets/vfxweaver/shaders/include/shape.glsl`.

- [ ] **Step 3: Commit**

```bash
git add src/client/resources/assets/vfxweaver/shaders/post/surface_pattern.fsh
git commit -m "feat(surface_pattern): world-projected shape-pattern shader"
```

---

### Task 6: Ship the built-in `surface_pattern` effect

**Files:**
- Create: `src/main/resources/data/vfxweaver/vfx/surface_pattern.json`

**Interfaces:**
- Consumes: the effect type (Task 2), the pipeline (Tasks 3-4), the `VFXShape` model (Task 1) and the shader (Task 5).
- Produces: `/vfx play surface_pattern` — a persistent looping world grid, i.e. a stroked `rect` figure, at screen layer 0 with a fade radius and upward-face mask.

- [ ] **Step 1: Add the built-in effect**

Create `src/main/resources/data/vfxweaver/vfx/surface_pattern.json`:

```json
{
  "type": "surface_pattern",
  "duration": 200,
  "persistent": true,
  "loop": true,
  "fade_ticks": 10,
  "params": {
    "screen_layer": 0,
    "tile_scale": 2.0,
    "color_r": 0.3,
    "color_g": 0.9,
    "color_b": 1.0,
    "opacity": 0.6,
    "fade_radius": 32.0,
    "normal_mask": 0.6,
    "distort": 0.0
  },
  "pattern": {
    "figure": "rect",
    "fill": "stroke",
    "half_width": 0.5,
    "half_height": 0.5,
    "corner_radius": 0.0,
    "rotation": 0.0,
    "stroke_width": 0.04,
    "softness": 0.01,
    "repeat": [1, 1]
  }
}
```

- [ ] **Step 2: Build all six nodes**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six, and `surface_pattern.json` present in every jar (`jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists `data/vfxweaver/vfx/surface_pattern.json`).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/data/vfxweaver/vfx/surface_pattern.json
git commit -m "feat(surface_pattern): ship the built-in world grid (stroked rect)"
```

---

### Task 7: In-game verification (human partner)

**Files:**
- Modify: none (this task records the human partner's observations; if a fault is found, fix it in the task whose file is implicated and re-run this check)

**Interfaces:**
- Consumes: everything from Tasks 1-6.
- Produces: the recorded verdict on world-anchoring, normal masking, depth grace and cross-loader behaviour.

- [ ] **Step 1: Prepare the scene**

Ask the human partner to build a flat test area with a few blocks at different heights and a wall, then install `versions/26.2/build/libs/vfxweaver-<version>+26.2.jar` into the `26.2test` instance and launch.

- [ ] **Step 2: World-anchoring check**

Have the partner run `/vfx play surface_pattern` and report:
- the grid (a stroked `rect` figure) lies on the terrain and stays **fixed to world blocks** while turning and walking (correct) rather than swimming with the screen (the inverse matrix is wrong);
- no effect is drawn on the first-person hand (layer 0 is correct);
- the pattern fades out beyond ~32 blocks from the anchor and is absent on the wall and on downward-facing ceilings (the `normal_mask`);
- no black screen (a black screen means the shader failed to compile — check the log for the unknown-uniform warning and for dropped resource packs, per AGENTS.md).

Then have the partner load a throwaway copy of `surface_pattern.json` whose `pattern` is `{"figure":"ellipse","fill":"stroke","radius_x":0.45,"radius_y":0.3,"stroke_width":0.05}` (a ring) and a circle centred via `"center": [..]` or `positions`, and report that the figure changes without any code change (the shared shape library dispatched it) and that the centre matches the anchor.

Never claim this result; record exactly what the partner reports.

- [ ] **Step 3: Depth-grace check**

Have the partner trigger an effect at layer 1 explicitly (a throwaway datapack copy of `surface_pattern.json` with `"screen_layer": 1`) and report whether the screen stays intact (the pass runs but the depth is the hand-only buffer, so the pattern should be minimal/absent). Record the verbatim result, including whether the log shows the one-time `surface_pattern:nodepth` warning or a crash.- [ ] **Step 4: NeoForge check**

Have the partner install `versions/26.2-neoforge/build/libs/vfxweaver-<version>+26.2-neoforge.jar` into `26.2neoforge_test`, launch, and repeat Step 2. Expected: identical behaviour; if it differs, record the difference verbatim.

- [ ] **Step 5: Commit the recorded result**

```bash
git commit --allow-empty -m "test(surface_pattern): record in-game world-anchoring results"
```

---

### Task 8: Document the effect

**Files:**
- Modify: `docs/GUIDE.md` (datapack effect format section + its changelog at the bottom)
- Modify: `docs/CHANGELOG.md` (release entry, users-visible only)

**Interfaces:**
- Consumes: the final effect from Tasks 1-6.
- Produces: user-facing documentation of `surface_pattern`, its `params`, its structural `pattern` block and the layer-0 requirement.

- [ ] **Step 1: Add the effect section to `docs/GUIDE.md`**

Document, with the literal JSON:

- `"type": "surface_pattern"` and that it reads scene depth, so it only renders on 26.1.2/26.2 (not 1.21.11) and must run at `"screen_layer": 0`;
- the numeric `params`: `screen_layer` (0), `tile_scale` (world size of one cell, in blocks), `line_width` (numeric override of the shape's stroke width, in cell units), `color_r`/`color_g`/`color_b`, `opacity`, `fade_radius` (`0` disables the distance fade), `normal_mask` (`0` disables; otherwise a minimum `|normal.y|` so walls are excluded), `distort` (world-space sine warp, `0` = off);
- the structural top-level `pattern` block, owned by the shared shape library: `figure` (`circle` with `radius`; `ellipse` with `radius_x`/`radius_y`; `rect` with `half_width`/`half_height`/optional `corner_radius`; `polygon` with `sides` ≥ 3 and `radius`), plus `center` (optional `[x,y,z]`), `rotation` (degrees), `fill` (`solid`/`stroke`), `stroke_width`, `softness` and `repeat` (`[nx, ny]`, each `1..64`) — and that these strings/numbers intentionally live outside `params`, which stays numeric and animatable;
- that a grid is any figure with `repeat` greater than `1` (or a stroked `rect`) and a ring is an `ellipse` with `"fill": "stroke"` — there is no separate grid/ring mode;
- the anchor rule and `center`: the shape centres on its structural `center` when present, otherwise on the effect's first world `position` (e.g. via `/vfx playat`, `positions` or an entity anchor), otherwise on the camera;
- that limiting the pattern to a region is done with the shared `mask` block (the masks plan), not with a surface_pattern field;
- a worked example equal to `surface_pattern.json`.

- [ ] **Step 2: Add a `### vN` entry to the changelog at the bottom of `docs/GUIDE.md`**

State that `surface_pattern` is additive, works on 26.1.2+, and that no existing definition changes behaviour.

- [ ] **Step 3: Add the `docs/CHANGELOG.md` entry**

Keep it to what users see: "New `surface_pattern` effect: a world-anchored shape pattern (`circle`/`ellipse`/`rect`/`polygon`, tiled by a `repeat` modifier) projected onto terrain (26.1.2+; needs `screen_layer: 0`)."

- [ ] **Step 4: Commit**

```bash
git add docs/GUIDE.md docs/CHANGELOG.md
git commit -m "docs: document the surface_pattern effect (step 5)"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| §6.2 `surface_pattern` — projects a pattern in world space onto whatever is behind the pixel; grid, ring and "texture on blocks" are one effect with different parameters | Task 1 (structural `pattern` parsed into the shared `VFXShape`), Task 5 (shader calls `vfx_shape_coverage`; grid = repeated figure, ring = stroked ellipse), Task 6 (grid built-in). **Design change 2026-09-19:** the figure set is the shared shape library's `circle`/`ellipse`/`rect`/`polygon`; "texture on blocks" is the library's `texture` field (spec step 4), not a surface_pattern figure — see the design-change record |
| §6.2 parameters `tile_scale`, `line_width`, `color`, `opacity`, `fade_radius`, `normal_mask`, `distort` | Task 3 (`registerDepthPost` name list), Task 4 (reserved names + `params`), Task 5 (shader); `color` is `color_r/g/b` to match every other post effect; `line_width` remains the numeric stroke-width override |
| Shared shape/field library owns the primitives — this effect only consumes them (design change 2026-09-19) | Task 1 (`VFXShape.parse`), Tasks 3-4 (upload figure/fill/`repeat`/figure numbers), Task 5 (`#moj_import <vfxweaver:shape.glsl>` + `vfx_shape_coverage`); Global Constraints forbid a grid/ring branch |
| §5 depth mechanism: bind `mainTarget.getDepthTextureView()` as `DepthSampler` under its own bind group, `NEAREST` sampler | Task 3 (`DEPTH_SAMPLER_LAYOUT` / `withSampler`), Task 4 (bind) |
| §5 pass must run at screen layer 0 | Task 4 Step 2 (default forced to 0), Task 6 (`"screen_layer": 0`), Task 7 Step 2 (verified in-game) |
| §5 reversed depth / `ZERO_TO_ONE`; world position via `Camera.getViewRotationProjectionMatrix` + `-cameraPos`, inverted | Task 4 Steps 4/7 (matrix built from the `VFXWorldBindings` snapshot of that call), Task 5 (shader recipe) |
| §2 / §6.2 string/structural knobs never in numeric `params` | Task 1 (top-level `pattern` object, delegated to the library), Global Constraints, Task 8 (documented) |
| §11 anti-pattern: reading depth without a graceful fallback | Task 4 Step 2 (copy-pass passthrough + `VFXLog.warnOnce`), Task 5 (far-plane and `w≈0` passthrough) |
| §11 anti-pattern: screen-anchored effects that belong to the world — `space` chosen deliberately | Task 5 (reconstruction is absolute world XZ; the figure is block-anchored) |
| §4 masks / §9 step 3: any region limiting goes through the shared mask mechanism | referenced from Tasks 4/5/8 and the Global Constraints; no mask block is added here |
| §5 / §10.1 depth verified on Fabric 26.2, NeoForge 26.2 and Iris — re-create the removed plumbing | Tasks 3-4; in-game check on Fabric and NeoForge in Task 7 |
| Backward compatibility (spec §7), additive format, no protocol bump | Task 1 (optional key), Task 2 (new enum value), Global Constraints |
| Documentation obligation (AGENTS.md) | Task 8 |

**Explicitly out of scope** (the task's own exclusions and the spec's phasing): masks beyond what this effect needs — any region limiting is the shared mask mechanism owned by the masks plan (`docs/superpowers/plans/2026-09-19-masks.md`), which is being changed to a layer-0 coverage prepass consumed by effects; no `mask` block is added here and nothing in this plan contradicts that. Also out of scope: the shared shape/field library's own implementation (the parallel per-pixel-fields edit owns the figure signed-distance functions, `VFXShape`, `VFXShapeFigure`/`VFXShapeFill`, `shape.glsl` and the field library) — this plan only consumes it; `field`/`from` inputs on this effect (`distort` is a local sine warp, not the library); image/texture-on-blocks (the library's `texture` field, spec step 4, is the additive path, not a surface_pattern figure); the screen-space `beam` (§9 step 2a), `sparks` (§9 step 5's sibling), and macros/logic nodes (§3.1.1, step 2b). All are additive to the same format version.

**Placeholder scan:** no "TBD"/"handle edge cases"; every step has a command or complete code. The throwaway `Check.java` is written out in full. The `%TEMP%` check file is intentionally not committed. The only cross-plan dependency is the shared shape/field library (`VFXShape`, `VFXShapeFigure`/`VFXShapeFill`, `shape.glsl`), created by the parallel per-pixel-fields edit; the consumed names are stated exactly so the two edits meet, not left as a placeholder.

**Type consistency:** `VFXDefinition.getPattern()` returns the shared `@Nullable VFXShape` and is the only accessor; `VFXShape` / `VFXShapeFigure` / `VFXShapeFill` are used with the same names in Tasks 1, 4, 5 and 8 and are owned by the parallel edit. `ProgramInfo.depth()` is used identically in Task 3 (produced) and Task 4 (consumed); there is no `ProgramInfo.pattern`/`PatternSampler` anywhere. `registerDepthPost`'s param list order (`tile_scale` … `time`) is identical in Task 3, the resolver in Task 4 and the shader `Config` block in Task 5, and the `mat4` is written first by both sides. The `VFXShape` accessors used in Task 4 (`figure/fill/center/rotation/strokeWidth/softness/repeatX/repeatY/radius/radiusX/radiusY/halfWidth/halfHeight/cornerRadius/sides`) match the consumed-library list in the Architecture section, and `vfx_shape_coverage`'s argument order in Task 5 matches the consumed signature. `VFXWorldBindings.currentFrame()` returns the `Frame` record with `camX()/camY()/camZ()/viewRotProj()`, used in Task 4 only. `VFXPass.setMainTarget(RenderTarget)` is defined and called in Task 4.

**Design change recorded (approved 2026-09-19) and spec ambiguities resolved here:**

**Design change:** `surface_pattern` no longer hardcodes a grid/ring choice. It is the **world-projection consumer of the shared shape/field library**; the figure primitives, their signed-distance maths, fill/stroke rendering and the repeat/tile modifier are owned by that library (the parallel per-pixel-fields edit). This plan consumes `dev.vfxweaver.field.VFXShape`/`VFXShapeFigure`/`VFXShapeFill` and `#moj_import <vfxweaver:shape.glsl>`'s `vfx_shape_coverage`, uploads the shape numbers, and contains no per-figure branch. Resolution of the points the change raised:

1. **Repetition instead of a grid mode.** A grid is any figure plus a `repeat: [nx, ny]` modifier (or a stroked `rect`); a ring is an `ellipse` with `fill: "stroke"`. No `grid`/`ring`/`radial` enum survives and the old `grid`/`ring` shader branches are gone. (Supersedes the old `Shape {GRID, RING, IMAGE}` record and the old "one effect, different parameters" reading of §6.2.)
2. **Figure set is closed.** `circle` (`radius`), `ellipse` (`radius_x`, `radius_y`), `rect` (`half_width`, `half_height`, optional `corner_radius`), `polygon` (`sides` ≥ 3, `radius`), each with `center`, `rotation`, `fill: solid | stroke`, `stroke_width`, `softness`. The former `image` figure and its `texture`/`channel` fields are removed because they are not shape primitives; texture-on-blocks stays available through the library's `texture` field (spec step 4) as an additive later path.
3. **`center` and the anchor rule are consistent.** The shape's optional structural `center` `[x,y,z]` is the anchor when present; otherwise the anchor is the effect's first world `position` (block centre), else the camera snapshot, else `(0,0,0)`. All three feed the same reserved `center_x/y/z` uniforms (positional data, never an effect `param`).
4. **`line_width` vs `stroke_width`.** Both survive: `pattern.stroke_width` is the shape's structural stroke width (library model), and the numeric `params.line_width` overrides it when authored; Java resolves both into the single `stroke_width` uniform, so the shader and the library see one width.
5. **`tile_scale` and `repeat`.** `tile_scale` (blocks) is the world size of one cell; `repeat: [nx, ny]` (each `1..64`) tiles the figure inside that cell; the figure's own numbers are fractions of a cell, matching the old "fraction of a cell" semantics.
6. **Rotation and axis.** `rotation` is degrees in the world XZ plane; the figure is evaluated in `world.xz`, so `center.y` is accepted for consistency with the anchor/positions model but is not used by the projection.
7. **`normal_mask` sign.** A depth-derived normal's sign is ambiguous without camera state. Resolved: mask on `|normal.y|` (horizontal surfaces), which excludes walls; strictly `+Y`-only (ceiling exclusion) is deferred to the masks/fields plans.
8. **`distort` semantics.** §6.2 names it without defining it. Resolved: a cheap world-Y-phased sine warp on the projected XZ coordinate (`0` off); real noise distortion is the field library (spec step 4). Marked with a `ponytail:` comment.
9. **`surface_pattern` on 1.21.11.** Depth was verified on 26.x only. Resolved: register the pass on `>=26.1`; on `<26.1` the effect type, shader and built-in JSON exist and build, but `getPrograms` is empty so the effect renders nothing. The consumed shape library is common code and builds on all nodes. Documented in Task 8.
10. **`screen_layer`.** The spec says layer 0 is mandatory but `screen_layer` is otherwise a free numeric param. Resolved: `process` defaults `surface_pattern` to layer 0 (a user can still override, and Task 7 Step 3 exercises that).
11. **Masks.** Any limiting of the pattern uses the shared mask mechanism from the masks plan (a layer-0 coverage prepass consumed by effects); this plan references it and defines no second mask.
12. **Camera snapshot staleness.** The inverse matrix is built from `VFXWorldBindings`, which is updated at the end of the previous frame's `render` and is therefore one frame stale at layer 0. Accepted for v1 (the depth findings' live-camera path would add guarded cross-version camera access); noted here so a visible lag points at this, not at the recipe.

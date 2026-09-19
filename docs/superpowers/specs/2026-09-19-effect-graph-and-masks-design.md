# Effect Graph, Fields and Masks — Design Spec

Status: **design draft for review**. Date: 2026-09-19. Target line: MC 26.2 (Fabric + NeoForge).
Supersedes the "node editor" discussion: the editor is deliberately **out of scope** here.

This spec covers three coupled pieces that ship together, because each is useless without the others:

1. a **value/field graph** (node system) that can drive any effect input;
2. a **depth mechanism** (reconstruct world position in a screen pass) — needed by screen-space
   effects and by world-space masks;
3. **masks** — one shared coverage filter every effect can use, invertible.

First consumers: the screen-space **beam**, the **surface pattern** (grid/ring on terrain) and
**sparks**.

## 1. Decisions (locked)

1. **Value/field graph, not a render/compositing graph.** Nodes produce typed values/fields; they do
   not process images. The fixed post-processing pass chain stays as it is.
2. **Format + engine first**, visual editor later and as a **separate** client of the format (separate
   mod or external tool). Never inside the core mod — GUI code is the most version-sensitive part and
   the core builds 4 lines × 2 loaders with no test suite.
3. **Backward compatibility is a contract, not a best effort** (§7).
4. **Two evaluation domains** (§2). Per-pixel values are served by a **library of field functions**,
   not by generating GLSL from the graph. GLSL codegen is explicitly deferred, but the format and
   engine are shaped so it can be added later without breaking anything.
5. **Masks are shared infrastructure**, applied uniformly by post passes, overlays and geometry,
   with `invert`.
6. The screen-space beam is a **flat billboard with a depth cut**; the existing world-geometry
   `light_beam` is left untouched as a separate effect.

## 2. Two evaluation domains

This is the core constraint of the whole design: a value that differs per pixel cannot be a uniform.

| Domain | Consumes | Evaluated | Cost |
|---|---|---|---|
| **Uniform** | an input that is constant for the whole draw (beam radius, effect intensity, colour) | CPU, per frame per instance, via the full node graph | cheap |
| **Field** | an input that varies across the surface (`dent` intensity differing per pixel) | GPU, inside the effect's fragment shader, via a **built-in field function** | one extra eval per pixel |

- The **uniform graph** is arbitrary: nodes, math, `expr` (reusing the existing AST evaluator),
  curves, world binds. Result per frame is a set of uniforms.
- The **field library** is a fixed set of shader-side functions with uniform parameters:
  `noise` (our existing simplex), `grid`, `ring`/`radial`, `gradient`, `curve`, `texture sample`,
  and constant. Each has a `space` (`screen` UV or `world` reconstructed position) — world-anchored
  noise stays put when the camera turns, screen-anchored noise follows the screen. Both are needed;
  `world` is the default for things that belong to the scene.
- Fields are **composable**: `{ "op": "multiply", "a": {...field...}, "b": {...field...} }` combines
  field functions with one arithmetic/`mix`/`min`/`max` operator, bounded by a depth and leaf cap
  (§8). This is what makes `dent.intensity ← noise * gradient` possible **without** GLSL codegen —
  it is the deliberate substitute for it, and it stays inside the fixed-function library.
- **Types are per function, not per declaration.** Each field function has a known output type
  (documented with it): `noise`/`grid`/`ring`/`gradient`/`curve`/`depth*`/`normal_facing` → `float`;
  `world_pos`/`screen_uv` → `vec3`/`vec2`; a texture sample → its channel is selected with a
  `channel: r|g|b|a|luminance` parameter, so it also yields `float` (or `vec3` if `channel` is
  omitted). There is no mandatory `type` field in the JSON: the function already fixes it, and a
  second source of truth would only drift.
- **Coercion in composition** is explicit and validated at parse time:
  `float×float` scalar; `float×vec3` broadcasts; `vec3×vec3` componentwise; `vec2×vec3` is a **parse
  error**. `mix` requires a `float` factor. A mismatch that cannot be coerced fails that file only.
- **Geometry fields**: `depth`, `depth_gradient(near, far)`, `normal_facing(axis, threshold)`,
  `screen_uv`, `world_pos` — available only where depth access exists (§5); on the server, or in a
  pass without depth, they return their default and warn once per definition.
- **Every numeric field parameter** (scale, octaves, amount, threshold, …) accepts a literal number
  **or** a graph reference `{ "from": "<node>" }`, evaluated in the uniform domain and uploaded as a
  uniform; integer parameters are rounded. `field`/`fn` and `space` are **structural** and cannot be
  animated — animating them would mean recompiling the shader per frame.
- An effect input declares which domain it accepts. Field-capable inputs are a documented subset
  (starting with `intensity`/`amount`-like inputs and mask coverage).

Deferred (documented as a non-goal for v1, format left open for it): compiling arbitrary graph nodes
into the shader (`dent.intensity ← noise * curve + expr` per pixel). Until then, composing per-pixel
behaviour beyond the field library means writing a shader, exactly as effect shaders work today.

## 3. Format

### 3.1 Graph block (additive, top level)

```json
{
  "graph": {
    "version": 1,
    "nodes": [
      { "id": "n1", "kind": "noise", "inputs": { "scale": 4.0, "octaves": 3 }, "outputs": ["out"] },
      { "id": "n2", "kind": "time",  "inputs": { "speed": 0.5 } },
      { "id": "n3", "kind": "math",  "op": "multiply", "inputs": { "a": 0.5 } },
      { "id": "n4", "kind": "curve", "inputs": { "points": [ { "time": 0, "value": 0.0 },
                                                             { "time": 60, "value": 1.0, "easing": "ease_out" } ] } }
    ],
    "edges": [
      { "from": "n1", "to": "n3", "input": "b" },
      { "from": "n3", "to": "beam.rim_noise" }
    ],
    "meta": { "n1": { "pos": [40, 60] } }
  }
}
```

- `meta` (canvas positions, comments, groups) is **ignored by the engine** — it exists so a future
  editor can round-trip a graph without the engine knowing anything about the UI.
- Node kinds for v1: `constant`, `time`, `random`, `noise`, `curve`, `math`, `mix`, `clamp`,
  `remap`, `bind` (existing `VFXWorldBindings`), `expr` (existing `MathExpression`), plus the
  **logic** set `compare`, `boolean`, `if`, `switch` — cheap to evaluate on the CPU and enough to
  write reactive effects ("denser near the player") without code.
- Node ids are stable strings chosen by the author (not indices) — required for editor round-trips
  and for readable diffs.
- Cycles are a parse error (per-file, isolated like every other parse failure).

### 3.1.1 Subgraphs (macros)

A subgraph is a **reusable block of nodes** ("stamp"): defined once, inserted into any graph (in the
same file, and optionally from a shared library file), with its inputs filled in per use.

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
    "edges": [ { "from": "n1", "to": "beam.rim_noise" } ]
  }
}
```

Rules:

- Expansion happens **at parse/load time**, not at render time: the loader substitutes `$inputs`
  and inlines the nodes, so the runtime evaluator only ever sees a flat graph. Zero per-frame cost.
- **Local ids**: ids inside a subgraph are local to it. On expansion they are prefixed with the
  instance id (`n1.s2`), so the same subgraph can be used twice in one graph without collisions.
- **Error attribution**: a fault inside a macro reports both ends, e.g.
  `subgraph 'fade_noise' (node 'n1') → node 's3': input 'a' is not connected and has no default`.
- **Recursion** is capped (nesting depth); exceeding it refuses that file only. A subgraph may not
  reference itself.
- **Multiple outputs**: `outputs` is a map (`{ "intensity": "s5", "color": "s6" }`). An edge may
  name one (`{ "from": "n1", "output": "intensity", "to": "beam.intensity" }`); with one output, or
  when `output` is omitted, the first declared output is used.
- Caps apply to the **expanded** graph (node/edge count), not the authored file.
- The authored (unexpanded) form is what is kept and synced; expansion is a load step. A future
  editor therefore round-trips the original structure, not the expansion.
- A shared library file (`data/<ns>/vfx_graphs/<name>.json`) is an optional later addition; v1
  requires macros to be defined in the same file.

### 3.2 Sockets on effects

Effects declare **named inputs**. An input accepts either a literal value with the existing spec
syntax (`constant`, `start`/`end`, `keyframes`, `bind`, `expr`, `multiply`) **or** a graph reference:

```json
"params": {
  "duration": 80
},
"inputs": {
  "core_color": [0.0, 0.0, 0.0],
  "rim_color":  [1.0, 1.0, 1.0],
  "rim_noise": { "from": "n3" },
  "radius":    { "field": "noise", "space": "world", "scale": 2.5, "amount": 0.3 }
}
```

- `{ "from": "<node>" }` — take the value from the graph (uniform domain).
- `{ "field": "<fn>", ... }` — use a built-in field function (per-pixel domain); only valid on
  field-capable inputs.
- Everything is optional; **every input has a numeric default** (§7).

## 4. Masks

One shared mask, usable by every effect:

```json
"mask": {
  "space": "world",
  "shape": "sphere",
  "center": [120, 70, -40],
  "radius": 8.0,
  "invert": false,
  "softness": 0.15,
  "field": { "field": "noise", "scale": 3.0, "amount": 0.4 }
}
```

- `space`: `world` (classify by the world position reconstructed from depth) or `screen`
  (classify in UV).
- shapes — `world`: `sphere`, `box`, `block` (by block state / tag / position list); `screen`:
  `rect`, `radial`, `pattern`.
- `field`: optional field function that perturbs the mask edge → irregular, noisy boundaries on
  **any** effect. A graph reference is also accepted (`"from": "<node>"`).
- `invert`: show the effect everywhere **except** inside the mask.
- `softness`: edge falloff width.
- **Composition semantics** (must be exact — this is what caused the most ambiguity):
  a mask evaluates to a **coverage** in `[0,1]`. Each shape is expressed as a signed distance
  `d` (negative inside), the edge is perturbed by the mask's `field` **in distance space**
  (`d' = d + field * amount`), and the falloff is applied once:
  `coverage = clamp(0.5 - d' / max(softness, eps), 0, 1)`. Perturbing the coverage instead of the
  distance would fight the falloff — do not do it.
  - `union` = `max(a,b)`, `intersection` = `min(a,b)`, `difference` = `a * (1 - b)`
    (left-associative and **not** symmetric: `a - b`, not `b - a`).
  - A sub-mask's `field` perturbs **only its own** edge, before composition.
  - `invert` is applied **after** composition, once.
  - `softness` of a composed result is the **maximum** of its operands' (conservative: the softer
    edge wins so composition never sharpens a boundary).
- **Animated masks**: every numeric mask parameter (`center`, `radius`, `min`/`max`, rect bounds)
  accepts a graph reference (`{ "from": "<node>" }`) exactly like an effect input — so a mask can
  follow an entity, pulse, or expand as a wave, using the same uniform-graph machinery.
- **Composed masks**: `{ "op": "union" | "intersection" | "difference", "a": {...}, "b": {...} }`
  combines two masks (bounded depth, §8) — "inside the sphere **and** outside that box" without new
  shapes.
- `block` masks filter by block state / tag / property (not only by position list); the per-pixel
  block lookup cost is a measured risk (§10.4).
- Masks are evaluated in one place and consumed by post passes, world overlays and geometry alike —
  including for **screen-space** effects applied to specific **world blocks** (the `world + block`
  combination), which is the point of having both spaces on one mask.

## 5. Depth mechanism

Screen-space effects and world-space masks both need the world position behind a pixel. The
mechanism is **verified** (Fabric 26.2, NeoForge 26.2, and with Iris shaders enabled); the probe
that proved it is recorded in `docs/superpowers/specs/notes/2026-09-19-depth-findings.md`.

- The pass binds the main render target's depth attachment as an extra sampler:
  `renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST))`,
  declared by its own bind group (`BindGroupLayout.builder().withSampler("DepthSampler").build()` on
  26.2, `Builder.withSampler("DepthSampler")` on older nodes). The sampler must be `NEAREST` —
  a depth texture is not filterable.
- **The pass must run at screen layer 0.** `GameRenderer.renderLevel` clears the single scene depth
  buffer (`clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0)`) immediately before
  `renderItemInHand`, so at layer 1 and above the depth buffer holds only the first-person hand, not
  the world. Layer 0 runs before the hand, so the full scene depth is still present.
- 26.x uses **reversed depth** (`glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`), so the sampled value
  is already NDC z (near = 1.0, far = 0.0) and is fed to the inverse matrix as-is.
- World position is reconstructed from an inverse view-projection:
  ```glsl
  vec4 clip = vec4(uv * 2.0 - 1.0, depth, 1.0);
  vec4 world = inv_view_proj * clip;
  world /= world.w;
  ```
  The matrix is built from `Camera.getViewRotationProjectionMatrix(Matrix4f)`, which returns
  `projection * viewRotation` with **no translation** — post-multiply `translate(-cameraPos)` and
  invert.
- This step exposes world position, linear depth, and (optionally) the surface normal.
- Consumers: the beam (depth cut so it does not draw over terrain), the surface pattern (project a
  pattern in world space), `world` masks.
- It behaves the same on both loaders and does not break when Iris/shaders replace the pipeline —
  no access transformer or loader-specific binding is needed.

## 6. First consumers

1. **`beam` (screen-space)** — a billboard quad with a depth cut; parameters: `core_color`,
   `rim_color`, `rim_width`, `noise_*` (jagged rim), `radius`/`taper`, `softness`, `flicker`,
   `emissive`. Jagged rim = a noise field on the coverage/edge, not a mesh. `light_beam` (world
   geometry) stays as a separate effect.
2. **`surface_pattern` (grid / ring / radial / image)** — projects a pattern in world space onto
   whatever is behind the pixel (terrain, entities, water), with `tile_scale`, `line_width`, `color`,
   `opacity`, `fade_radius`, `normal_mask`, `distort`. Grid, ring and "texture on blocks" are one
   effect with different parameters.
3. **`sparks`** — a new particle kind in the existing spec-driven particle system (`count`, `speed`,
   `spread`, `life`, `size_curve`, `color_curve`, `gravity`, `bounce`, `glow`, `trail`). No new
   engine: the block-particle engine already proves the pattern.

## 7. Backward compatibility (contract)

- `graph`, `inputs`, `mask` are **optional refinement layers**. Every effect input always has a
  numeric default; a definition without these blocks behaves exactly as today.
- A datapack that uses the graph, loaded by an **older** mod: unknown fields are ignored and the
  effect renders on its defaults — it must not fail to load or crash.
- Datapacks written before this change keep working unchanged, including via the Java API
  (`registerDefinitions`), which stays signature-compatible.
- `graph.version` is per definition; an unknown node kind or field function fails **that file only**
  (the existing per-file parse isolation), never the whole pack.
- No `PROTOCOL_VERSION` bump: the graph travels inside the definition, which is already synced by
  `vfx_sync`.

## 8. Performance and caps

- The uniform graph is evaluated once per frame per instance; per-instance caches, dirty-flag
  invalidation, no allocation in the hot path.
- The field library is evaluated in-shader — one extra field evaluation per pixel per use, not per
  node.
- Caps (the repo's bounded-collection rule): node count, edge count, graph depth, node `expr` source
  length, mask field count, **field composition depth/leaf count** and **mask composition depth**,
  plus **subgraph nesting depth**. Caps apply to the graph **after macro expansion**.
  Violations refuse the definition at parse time with a clear error.
- Parse errors name the offending node id and input (e.g. `node 'n3': input 'b' is not connected and
  has no default`), so a datapack author can find the fault without a debugger.

## 9. Phasing

1. **Depth + world reconstruction** (prerequisite, small): prove scene depth is readable in a post
   pass on 26.2 Fabric and NeoForge; expose world position. — **Done / verified** on Fabric 26.2,
   NeoForge 26.2 and with Iris; the mechanism is in §5 and the findings note.
2a. **Uniform graph core**: node model, CPU evaluator, format parse/validate, `{ "from": node }` on
   inputs; first consumer — the **screen-space `beam`** driven by graph uniforms (radius, rim width,
   colours, flicker).
2b. **Macros + logic nodes**: subgraph expansion (§3.1.1) and `compare`/`boolean`/`if`/`switch`.
   Additive to 2a, same format version; split so the base evaluator is proven before the extra
   machinery. The per-pixel **jagged rim** still needs the field library and lands in step 4.
3. **Masks**: shared evaluation with `invert`/`softness`; `screen` and `world` shapes; wire masks to
   existing post effects so any effect can be limited (and inverted) without new shaders.
4. **Field library**: GPU-side `noise`/`grid`/`radial`/`gradient`/`curve`/`texture` with
   `space: world|screen`; first consumer — `dent.intensity` modulated by noise.
5. **`surface_pattern`**, then **`sparks`**.

Each step is shippable; the format is versioned from step 2 so later steps are additive.

## 10. Open items (verify before coding)

1. ~~**Scene depth access in a post pass on 26.2** (`RenderPipelines.POST_PROCESSING_SNIPPET`, the
   depth attachment/sampler available to our pass, and the same under NeoForge). This gates steps
   1/4 and the whole screen-space beam — do it first, as a spike.~~ **Closed (verified).** Depth is
   readable by binding the main target's depth view as an extra `DepthSampler` under its own bind
   group, **provided the pass runs at screen layer 0** (at layer 1+ the single depth buffer is
   cleared before the hand, leaving only the hand). Works on Fabric 26.2, NeoForge 26.2 and with
   Iris. See §5 and the depth findings note.
2. Correct 26.2 names for the post-pass input/depth binding in our `VFXShaderPrograms.registerPost`.
3. Whether a field-capable input can be implemented per effect without touching the shared pass chain
   (expected: each effect's fragment shader gains the field function + parameters as uniforms).
4. `world + block` mask cost: the block lookup per pixel (needs the client's world at the
   reconstructed position) — measure; fall back to shape-only masks if it is too hot.
5. ~~Iris behaviour with a depth-reading post pass.~~ **Closed (verified):** the depth-reading pass
   works with Iris shaders enabled (recorded in the depth findings note).
6. **GLSL broadcast**: confirm `vec3 * float` broadcast behaves identically to the CPU-side
   validation for the GLSL version we target.
7. **Macro expansion cost**: nested macros (depth 3+) with 100+ instances — measure load-time, no
   spike allowed.
8. **Chained masks vs shader limits**: a mask composed from 4+ operands — check the resulting
   fragment shader stays inside instruction limits; if not, cap composition depth (§8).

## 11. Anti-patterns

- Generating GLSL from the graph in v1 (deferred on purpose).
- Putting editor/UI code in the core mod.
- Making graph/mask mandatory, or giving any input no default (breaks §7).
- Reading depth without a graceful fallback when the pass has no depth available.
- Per-pixel graphs that re-evaluate the same node per pixel instead of using the field library.
- Screen-anchored noise for effects that belong to the world (and vice versa) — `space` must be
  chosen deliberately and documented per effect.
- Shipping a screen-space effect that ignores the mask (every screen effect must honour it).

## 12. Considered and deferred (with reasons)

Ideas reviewed and deliberately **not** in v1 — recorded so the format does not drift into them by
accident:

| Idea | Verdict | Reason |
|---|---|---|
| Subgraph macros (`subgraphs` with `$param`) | **adopted** (v1) | Parse-time expansion makes reuse cheap while the only authoring surface is JSON; see §3.1.1. |
| Shared subgraph library (`data/<ns>/vfx_graphs/`) | defer | v1 keeps macros inside the effect file; a shared library is an additive follow-up. |
| Runtime graph **structure** patching over the network | reject | New protocol surface for an unclear need. Node inputs will be addressable as named params, so the existing `setParam`/`setKeyframe`/`setParamExpr` already cover live control. |
| Per-field caching / `field_ref` | reject | Meaningless in the field domain: a fragment evaluates its field once per pixel. GPU-side reuse is a shader concern, not a cache. CPU-side caching for the uniform graph already exists. |
| Custom field functions registered from Java | defer | They require shader-side code — i.e. it is GLSL codegen wearing a different hat. Revisit together with codegen, not before. |
| Hi-Z / mip depth early-out | defer | Requires building a depth pyramid; we have no measurement showing the per-pixel depth read is the bottleneck. Measure first (§10.4). |
| Evaluating the uniform graph on a worker thread | reject | Thread-safety hazard inside the game/render loop for a per-frame graph of a handful of nodes. Premature optimization with a real crash risk. |
| Subgraph export/import API | defer | Macro reuse in-file plus `registerDefinitions` covers the practical need; a file format for shipping fragments is a later addition. |
| Field debug visualisation (`heatmap` mode) | defer | Useful tooling, but it is a debug shader path in the shipping mod. Prefer an external editor/debug client once the format exists. |

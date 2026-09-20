# `surface_pattern` Surface Selection — Design Spec

Status: **design draft for review — the parameter vocabulary in §2 is the approval gate**. Date: 2026-09-20. Target line: MC 26.2 (Fabric + NeoForge).
Scope: make the projection plane and the *set of surfaces* a `surface_pattern` draws on author-configurable — per-facing/per-axis selection, a world-Y level band, and a **topmost-block** filter. Additive to the existing `surface_pattern` (step 5, guide v38), the step-4 field library and the step-3 mask/coverage mechanism.

This spec is written to be implemented without further decisions. Only the **naming and accepted values** of the new vocabulary are intentionally open for the owner to approve or amend (§10). Everything else (mechanism, math, caps, failure behaviour, guards) is decided here.

## 1. What the owner asked for, and why the current effect cannot do it

Owner's words, translated: the pattern is drawn on surfaces at every level in view ("on blocks above me and below me") and there is no way to restrict it to the topmost blocks; he wants full flexibility — which orientations receive the pattern, a height/level filter including *topmost only*, and a projection that follows the **dominant normal axis** so a wall receives a correctly oriented figure.

What exists today (verified in the code, not guessed):

- The shader projects with **world X/Z only** (`surface_pattern.fsh:77,86`), so the figure only lands correctly on horizontal surfaces; a vertical face gets the same XZ projection sheared across the wall, not an oriented figure.
- The only orientation control is the numeric `normal_mask` param: `mask = normal_mask <= 0.0 ? 1.0 : smoothstep(normal_mask, min(normal_mask+0.2,1.0), abs(n.y))` (`surface_pattern.fsh:99-103`). So `{ "normal_mask": 0 }` (the default) disables the filter and lets *something* appear on walls too — but it is the sheared XZ figure, not a wall-oriented one. The built-in `surface_pattern.json` sets `0.6`, which keeps floors and ceilings (`|n.y|`) and excludes walls.
- `abs(n.y)` deliberately **merges floor and ceiling** because the depth-derived normal's sign is ambiguous (`surface_pattern.fsh:100`; plan self-review item 7). There is no way to select `up` without `down`, and no per-axis selection.
- There is **no level, height or topmost filter at all**. `fade_radius` and `distort` are the only spatial limiting terms, and both use XZ only.
- A screen fragment can read its reconstructed world position but **cannot read the world's blocks** — so the topmost test cannot be a GLSL per-fragment test.

The request is therefore three separable changes: (a) a **facing/axis selection**, (b) a **level filter** (a world-Y band, cheap per-fragment, plus a **topmost-block** test that needs a prepass), (c) **dominant-axis projection** with a signed, camera-facing normal.

## 2. Parameter vocabulary — the proposal (approve or amend)

Two kinds of knob, split by the repo's rule that **strings/enums are structural and live in a top-level block; `params` stays numeric and animatable** (spec §2, §3.2; the `pattern` block follows the same rule).

### 2.1 Structural block — top level `surface` (optional)

```json
"surface": {
  "faces": ["up", "north"],
  "level": "top",
  "min_y": 64,
  "max_y": 96
}
```

| Key | Type | Accepted values | Default | Meaning |
|---|---|---|---|---|
| `faces` | array of strings | single faces `"up"`, `"down"`, `"north"`, `"south"`, `"east"`, `"west"`; axis aliases `"x"` (= west+east), `"y"` (= up+down), `"z"` (= north+south); group aliases `"horizontal"` (= up+down), `"vertical"` (= north+south+east+west), `"all"` (= all six) | `["up"]` when `surface` is present and `faces` is omitted | which face orientations receive the pattern. Expanded at parse time to a 6-bit face mask |
| `level` | string | `"all"`, `"top"` | `"all"` | `"top"` = only where the visible surface belongs to the **topmost block of its world column** (the block above is air). See §5 |
| `min_y` | number, optional | any finite world Y | unbounded (−∞) | inclusive lower world-Y bound of the reconstructed surface |
| `max_y` | number, optional | any finite world Y | unbounded (+∞) | inclusive upper world-Y bound |

Face-id convention (one bit each, the order is fixed by this spec): `0` up (+Y), `1` down (−Y), `2` north (−Z), `3` south (+Z), `4` west (−X), `5` east (+X). Minecraft's own axis convention: +X = east, +Z = south, north = −Z, west = −X.

Rules:

- **`surface.faces` present replaces `normal_mask`** for orientation. `normal_mask` is then ignored (still parsed; see §3). If `faces` is omitted but the `surface` block is present, it defaults to `["up"]`.
- **`surface` absent entirely = legacy behaviour**: orientation is governed solely by the numeric `normal_mask` param (see §3), with its existing default `0` = all faces. This is the backward-compatible switch.
- `min_y` / `max_y` apply **in addition** to `level` and `faces`, and are also applied when `surface` is absent? No — they are part of the `surface` block only. (Adding `min_y`/`max_y` to a definition without a `surface` block is a parse error, so a typo cannot half-enable the feature.)
- `min_y > max_y` is a parse error for that file.
- Unknown face token or unknown `level` value is a parse error for that file (per-file isolation, as for an unknown figure).

### 2.2 Required JSON examples

Backward-compatible default — **no `surface` block**, this is today's built-in and behaves exactly as today (horizontal, `|n.y| ≥ 0.6`, all levels):

```json
{
  "type": "surface_pattern",
  "params": {
    "screen_layer": 0, "tile_scale": 2.0,
    "color_r": 0.3, "color_g": 0.9, "color_b": 1.0,
    "opacity": 0.6, "fade_radius": 32.0, "normal_mask": 0.6, "distort": 0.0
  },
  "pattern": { "figure": "rect", "fill": "stroke", "half_width": 0.5, "half_height": 0.5,
               "corner_radius": 0.0, "rotation": 0.0, "stroke_width": 0.04, "softness": 0.01,
               "repeat": [1, 1] }
}
```

Floor only (top faces, every level):

```json
"surface": { "faces": ["up"] }
```

Walls only (every vertical face, both signs of both horizontal axes, every level):

```json
"surface": { "faces": ["vertical"] }
```

All surfaces:

```json
"surface": { "faces": ["all"] }
```

Per-axis: only the east/west walls (both X signs), the figure oriented upright on each:

```json
"surface": { "faces": ["x"] }
```

Topmost only — the top face of each world column (floor / terrain surface):

```json
"surface": { "faces": ["up"], "level": "top" }
```

Topmost + walls — the top block of each column, its **vertical faces** (e.g. only the topmost course of a wall, or the side faces of a 1-block-high wall):

```json
"surface": { "faces": ["vertical"], "level": "top" }
```

Height band (world-Y filter only, no topmost prepass — cheap):

```json
"surface": { "faces": ["up"], "min_y": 64, "max_y": 96 }
```

Combined (walls of the topmost blocks inside a Y band):

```json
"surface": { "faces": ["vertical"], "level": "top", "min_y": 64, "max_y": 96 }
```

### 2.3 Reused (unchanged) vocabulary

The figure stays entirely owned by the shared shape library: the top-level `pattern` block (`figure`, `fill`, `stroke_width`, `softness`, `repeat`, `center`, `rotation`, and the figure numbers) is untouched and is still parsed by `dev.vfxweaver.field.VFXShape` (plan Task 1). No facing or level information goes into `pattern`.

## 3. Backward compatibility

- **`normal_mask` keeps its exact meaning and default** when `surface.faces` is absent. `normal_mask <= 0` remains "no orientation filter" (all faces). The built-in `surface_pattern.json` is **not edited**: it has no `surface` block, so it keeps drawing exactly as today. Renaming/removing a field is not done.
- A definition with a `surface` block is **additive**: an older mod ignores the unknown top-level key (the parser reads only the keys it knows), still parses the file, and renders with legacy `normal_mask` semantics. No `PROTOCOL_VERSION` bump: the definition is already carried inside the synced effect definition.
- `min_y`/`max_y`/`faces`/`level` are parsed in shared, MC-free `src/main` (a new `dev.vfxweaver.field.VFXSurfaceSelection` modelled on `VFXShape`), so the datapack model stays loader-agnostic.
- The three-place UBO contract still holds and is extended in lockstep: the shader's `Config` block, `VFXShaderPrograms.registerDepthPost(...)`, and the manager's reserved-name resolver are edited together (AGENTS.md). New Config names are added **after** the existing ones so no existing offset shifts; the `mat4 inv_view_proj` stays first.

## 4. Projection math

### 4.1 Signed, camera-facing normal

A depth-derived normal has an ambiguous sign (plan self-review item 7). Fix it once, in a shared helper, by forcing the outward normal to face the camera:

```glsl
vec3 n = normalize(cross(dFdx(world), dFdy(world)) + vec3(0.0, 0.0, 1.0e-6));
if (dot(n, world - camPos) > 0.0) n = -n;   // outward normal faces the camera
```

Now `n.y > 0` is a floor seen from above, `n.y < 0` a ceiling seen from below, and the four wall signs are meaningful. This replaces the `abs(n.y)`-only test and unlocks `up` vs `down` and `north` vs `south`.

The existing field library already computes a depth-derived normal for its `normal_facing` field function (`field.glsl`, fn 8). This spec **extracts that computation into a shared include** (`vfx_camera_normal(world, dX, dY, camPos)`) re-used by both `field.glsl` and `surface_pattern.fsh` — the normal is implemented once (see §6).

### 4.2 Dominant-axis selection and per-face coordinates

```glsl
vec3 a = abs(n);
int faceId;
if (a.y >= a.x && a.y >= a.z) {
    faceId = (n.y >= 0.0) ? 0 : 1;                       // up / down
} else if (a.x >= a.z) {
    faceId = (n.x >= 0.0) ? 5 : 4;                       // east / west
} else {
    faceId = (n.z >= 0.0) ? 3 : 2;                       // south / north
}

vec2 p;
if (faceId <= 1) {
    p = world.xz;                                        // horizontal: unchanged from today
} else {
    vec3 u = normalize(cross(vec3(0.0, 1.0, 0.0), n));   // horizontal tangent
    p = vec2(dot(world, u), world.y);                    // wall: +u across, world Y up
}
```

The tangent `u = normalize(cross(worldUp, n))` is chosen so the figure reads **un-mirrored from the visible (camera-facing) side**:

| Dominant face | Outward normal | Screen-right tangent `u` | Plane `p` | Result |
|---|---|---|---|---|
| up (floor) | +Y | +X | `(x, z)` | top-down, unchanged |
| down (ceiling) | −Y | +X | `(x, z)` | top-down, mirrored (symmetric figures unaffected) |
| south | +Z | +X | `(x, y)` | upright, reads correctly from +Z |
| north | −Z | −X | `(−x, y)` | upright, reads correctly from −Z |
| east | +X | −Z | `(−z, y)` | upright, reads correctly from +X |
| west | −X | +Z | `(z, y)` | upright, reads correctly from −X |

`world.y` is the figure's vertical axis on every wall, so a figure is always upright on a wall.

### 4.3 Axis seams: hard switch, not blend

On faces at ~45° (a diagonal wall, a stair nosing, a sloped hill) `|n.x| ≈ |n.z|` and the plane flips discontinuously, so a seam appears. **Decision: hard switch.** Reasoning: the figure is an SDF evaluated in `p`; blending *filtered coordinates* between two planes shears and stretches the figure across the seam, and blending the *resulting coverage* double-paints the figure near it. A hard switch keeps the figure rigid and predictable, and the seam only appears on genuinely diagonal geometry, where "which wall is this" is ambiguous anyway. This is also exactly the "dominant surface" the owner asked for.

`distort` and `fade_radius` must follow the chosen plane:
- `distort` warps `p` (today it hardcodes `world.xz`, `surface_pattern.fsh:80`) and the phase uses the remaining world component (`world.y` on walls, `world.z` on floors) so there is no discontinuity to introduce.
- `fade_radius` uses the **3-D** distance `length(world - anchor)` from the anchor (today it is XZ-only, `surface_pattern.fsh:95`), so the fade works on walls too.

### 4.4 Facing / level gate in the shader

```glsl
// faces: face_mask >= 0 is the new hard set; -1 is legacy normal_mask
float faceCov;
if (face_mask >= 0.0) {
    faceCov = mod(floor(face_mask / exp2(float(faceId))), 2.0) >= 0.5 ? 1.0 : 0.0;
} else {
    faceCov = normal_mask <= 0.0 ? 1.0
            : smoothstep(normal_mask, min(normal_mask + 0.2, 1.0), abs(n.y));
}
float levelCov  = level_top < 0.5 ? 1.0 : texture(TopCoverageSampler, texCoord).r;
float heightCov = (world.y >= level_min && world.y <= level_max) ? 1.0 : 0.0;

float coverage = clamp(shapeCoverage * fade * faceCov * levelCov * heightCov
                     * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
```

`face_mask`, `level_top`, `level_min`, `level_max` are reserved `Config` floats written by the manager from the parsed `VFXSurfaceSelection`; `level_min`/`level_max` default to `−1.0e30`/`1.0e30` (gate always open). `TopCoverageSampler` is the topmost coverage from §5. The band `level_top`/`min_y`/`max_y` apply even if a topmost prepass is not requested. The current `shapeCoverage` call (`vfx_shape_pattern_coverage` through `shape.glsl` → `shapes.glsl`) is unchanged: **the figure math is not touched.**

## 5. The topmost filter

A per-fragment world-block test is impossible in a screen pass: the GLSL fragment has no world access. Of the two honest options — a **geometry/coverage prepass** (the mask feature's `VFXMaskBlockGeometry` + coverage scratch + consumer shader) or a **bounded block-column probe** (bake a per-column top Y into a texture the shader samples) — this spec chooses the **geometry/coverage prepass**, because it reuses a proven subsystem end to end and is therefore the smaller, lower-risk diff. The rejected probe is recorded in §8.

### 5.1 Mechanism, and where it lives

New client class **`dev.vfxweaver.client.postprocessing.VFXSurfaceTopGeometry`**. It:

1. **Selects** the topmost block of each world column inside a bounded XZ region around the effect anchor (`center_x/z` resolved exactly as today by `VFXPass.resolveAnchor`). Radius = the effect's `fade_radius` when `> 0`, else the cap; clamped to `VFXMaskBlockSelection.MAX_RADIUS` (32). Per column it uses `ClientLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)` to jump to the top motion-blocking Y, then walks down at most `MAX_TOP_STEP` (4) blocks to the first block with `VFXWorldOverlayRenderer.hasBlockModelGeometry(state)` (skips plants / model-less tops whose above-block is still air). Air and model-less columns are skipped.
2. **Rasterises** those blocks' real baked model geometry (`VFXWorldOverlayRenderer.getModelQuads`, camera-relative, identity `Projection`, `DynamicTransforms`), exactly as `VFXMaskBlockGeometry.drawBlocks` already does, into a per-definition scratch `TextureTarget`. The draw reuses the existing **`blockGeometryProgram()` pipeline** and **`post/mask_block_geometry.fsh`**, with the occlude colour: the shader's `gl_FragCoord.z < sceneDepth - 1e-4` discard keeps only the **visible** surface of a topmost block, so a topmost block hidden behind a nearer block never marks the pixel.
3. Runs **once per distinct `surface_pattern` definition with `level: "top"` per frame, before the effect chain, at screen layer 0** — in the same prepass block as the mask geometry (`VFXPostProcessingManager.process`, `layer == 0`).

The scratch is a per-definition `TextureTarget` in a new `surfaceTopTargets` map mirroring `geometryTargets`, pruned to the live set each frame. `surface_pattern` is extended with `.withSampler("TopCoverageSampler")` / a new `TOP_COVERAGE_SAMPLER_LAYOUT` in the pipeline, and the manager binds the scratch (NEAREST) into `VFXPass.execute`; when the effect is not `level:"top"`, it binds a harmless placeholder (its input colour view) because `level_top` gates it.

Full model geometry is emitted (not only the top face), so the vertical faces of topmost blocks are marked too — which is what makes `topmost + walls` (§2.2) work: a 1-high wall's block is its column top, and its visible side face is covered.

### 5.2 Cost

- CPU: ≤ (2·32+1)² ≈ 4 225 heightmap lookups per distinct top effect per frame (a bounded scan, not a full column walk); ≤ `MAX_TOP_BLOCKS` (512) model builds and vertex emissions.
- GPU: one vertex-buffer write, one extra draw, one extra screen-sized scratch target and one NEAREST sample per fragment — the same order as a block mask (`VFXMaskBlockGeometry`), and only for definitions that opt in with `level:"top"`.

This is a deliberate per-frame rebuild: bounded and measurable. `ponytail`: rebuild every frame; add an anchor-dirty / tick cache only if measurement shows it is hot.

### 5.3 Fallback and failure

- Not at layer 0, or no usable depth, or no camera: the existing `surface_pattern:nodepth` path already replaces the pass with a **passthrough** (`VFXPostProcessingManager.process`); the topmost prepass is skipped with it.
- At layer 0 with valid depth but no topmost blocks found (all air/sky): the scratch stays cleared, so `level_top` coverage is 0 and the pattern is **absent** — fail-closed, never "everywhere".
- If the scratch was never allocated for a topmost effect, bind the placeholder and treat `level_top` as 0 coverage for that frame (fail-closed).

## 6. Relationship to the existing shape / field / mask vocabulary

- **`shapes.glsl` / `field.glsl` stay the single owner of figures.** `surface_pattern.fsh` keeps calling `vfx_shape_pattern_coverage` (which dispatches to `shapes.glsl`'s `vfx_shape_sdf` / `vfx_shape_coverage`). No SDF, fill or repeat math is written or duplicated by this change.
- **The depth/world-position recipe is unified.** Today `field.glsl` has `vfx_world_pos` while `surface_pattern.fsh` inlines the same reversed-depth recipe (`surface_pattern.fsh:68-75`). The new shared include (e.g. `assets/vfxweaver/shaders/include/surface.glsl`) exports `vfx_world_from_depth(vec2 uv, mat4 invViewProj)` and `vfx_camera_normal(...)`, imported by both `field.glsl` (replacing its inline copies, including fn 8's normal math) and `surface_pattern.fsh`. One implementation, two consumers.
- **The topmost coverage reuses the mask block-geometry mechanism**, not a second one: the same `blockGeometryProgram` pipeline, the same `post/mask_block_geometry.fsh`, the same `getModelQuads`/`hasBlockModelGeometry` and the same `VFXMaskBlockSelection.MAX_RADIUS`/`MAX_BLOCKS` caps. The only refactor is lifting `VFXMaskBlockGeometry.drawBlocks`'s private draw body to a package-private helper both callers use.
- **Region limiting is still the shared `mask` block** (spec §4). `surface.faces`/`surface.level`/`min_y`/`max_y` are *surface selection*, not a spatial mask; a `surface_pattern` with both uses the mask for region and the `surface` block for orientation/level.
- **Nothing new is invented**: no new SDF, no new depth mechanism, no second coverage mechanism, no graph/codegen.

## 7. Caps, bounds, failure behaviour, guards

- **Caps (AGENTS.md bounded-collection rule):**
  - `surface.faces` ≤ 8 tokens before expansion; expansion produces ≤ 6 face bits; unknown token = per-file parse error; duplicates collapse.
  - `surface.level` is a closed enum (`all`/`top`).
  - `min_y`/`max_y` must be finite and `min_y <= max_y`; parse error otherwise.
  - Topmost region radius ≤ `VFXMaskBlockSelection.MAX_RADIUS` (32); emitted blocks ≤ a new `MAX_TOP_BLOCKS` constant (reuse `VFXMaskBlockSelection.MAX_BLOCKS` = 512); one scratch per distinct topmost definition, pruned to the live set.
- **Failure behaviour:** facing/level/topmost filters are **fail-closed** — no match, no topmost coverage, or an unbuildable scratch yields coverage 0 (the pattern is absent), never full coverage. `min_y`/`max_y` open the filter when absent. The only neutral path is the existing no-depth passthrough. Parse failures stay per-file isolated (existing `VFXDefinitionManager.prepare` behaviour).
- **Guards to extend (the two the change touches):**
  1. **The `depthReady` / layer-0 gate** in `VFXPostProcessingManager.process` (the `surface_pattern:nodepth` fallback) — a `level:"top"` effect must also skip its prepass and blank its coverage off layer 0 / without depth.
  2. **The `//? if >=26.1` depth-pass registration guard** in `VFXShaderPrograms` (with its `<26.2` / `>=26.2` bind-group layouts) — the new `TopCoverageSampler` layout and the topmost prepass exist only where the depth pass is registered. Additionally the existing `depthRecipeVerified()` (`>=26.2` only) governs the prepass's validity on `26.1.2`.
  - On `1.21.11` nothing changes: the effect still builds and renders nothing (no depth pass registered).

## 8. Rejected alternatives (brief)

| Alternative | Verdict | Reason |
|---|---|---|
| Booleans `up: true, wall: true, …` in the block | reject | Cannot express per-axis or `north` vs `south` neatly, and grows a key per face; the token array + face mask is extensible and one field. |
| Per-axis numeric thresholds in `params` (e.g. `mask_x`, `mask_y`, `mask_z`) | reject | Mixes a structural choice (which faces) into the numeric/animatable domain; the depth normal's sign ambiguity also makes a scalar threshold unable to separate north from south. |
| Blending planes at axis seams (triplanar) | reject | Blended projected coordinates shear/distort the SDF figure; blended coverage double-paints. Hard switch keeps the figure rigid. |
| Per-fragment block lookup in GLSL | reject | A screen fragment has no world access; the world block state is not an input of the post pass. |
| Bounded block-column probe (heightmap texture sampled by the shader) | reject here | It is a new subsystem (per-effect texture, region→UV mapping, rebuild policy, new shader sampling) for the same result the existing block-geometry coverage path already produces; reuse wins. Recorded as the fallback if the prepass measurement is ever too hot. |
| Animatable `min_y`/`max_y` as `params` with a sentinel "off" | defer | The structural block is the coherent home for a non-animated bound and avoids a magic sentinel; if the owner needs a moving height filter, add `level_min`/`level_max` params as an additive follow-up (documented as off by `min_y > max_y`). |
| Bumping `PROTOCOL_VERSION` | reject | Additive datapack change; the definition is already synced. |
| Editing the built-in `surface_pattern.json` to the new vocabulary | reject (for now) | Would change shipped behaviour; the legacy path must stay exactly reproducible. The built-in can be migrated later as a separate decision. |

## 9. Verification

There is no test suite; the order is build → standalone parse check → in-game (AGENTS.md).

**Without the game (standalone check harness).** Compile a throwaway `Check.java` against the built classes (AGENTS.md method 2; the `dumpRuntimeClasspath` init-script recipe is in `docs/superpowers/plans/2026-09-19-surface-pattern.md`, "Standalone compile/run checks"). Assert:
- `VFXDefinition.parse` with no `surface` block yields `getSurface() == null` and unchanged `getPattern()`;
- each token and alias expands to the expected face mask (`"up"`, `"vertical"`, `"x"`, `"all"`), duplicate tokens collapse, and `"diagonal"` is refused with a per-file error;
- `level` defaults to `all`; `"top"` parses; `"bottom"` is refused;
- `min_y`/`max_y` round-trip and `min_y > max_y` is refused;
- a definition with `surface` and no `faces` defaults to `["up"]`.
Build every node and confirm the new shader include and sampler ship in each jar.

```powershell
.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain
```
Expected: `BUILD SUCCESSFUL` for all six. Static shader checks (no GLSL compiler standalone): `jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists the new include; the `Config` name order in the shader matches `registerDepthPost`; no uniform is named after a GLSL built-in.

**In game (owner).** On a scene with a flat floor, a 1-high wall, a tall wall, an overhang/ceiling and a floating platform, author throwaway definitions under the test pack's namespace `vfx_demos:` (never `vfxweaver:` — AGENTS.md), `/reload`, then `/vfx play vfx_demos:<name>` for each of: floor-only, walls-only, all, topmost-only, topmost+walls, the height band, and the unchanged built-in `/vfx play vfxweaver:surface_pattern`. Confirm: the figure is upright and un-mirrored on each wall; a tall wall shows the pattern only on its top course when `level:"top"`; an overhang underside and a cave ceiling show nothing; the built-in looks and behaves exactly as before. Repeat with the NeoForge jar. Rendering is never claimed from the build alone.

## 10. What the owner must decide

1. **Token names and defaults.** Are `faces` tokens (`up`/`down`/`north`/`south`/`east`/`west` plus `x`/`y`/`z`/`horizontal`/`vertical`/`all`) the vocabulary you want? Is the default `["up"]` right when `surface` is present but `faces` is omitted, and is "no `surface` block = legacy `normal_mask`" the right compatibility switch?
2. **Level filter shape.** Is `level: "all"|"top"` enough, with `min_y`/`max_y` as literal (non-animated) bounds? If you need an animated height filter, say so and it becomes `params.level_min`/`level_max` instead.
3. **Topmost semantics.** "Topmost" = highest block of each world column where the block above is air; the pattern then applies to that block's selected faces (top face and/or its vertical faces). Confirm this matches "topmost blocks" as you mean it. Also confirm the default region radius (the anchor's `fade_radius`, capped at 32 blocks) is the right bound.
4. **`normal_mask` fate.** Kept as the legacy fallback (recommended, preserves the built-in). Should it be marked deprecated in the guide but not removed (recommended), or migrated?

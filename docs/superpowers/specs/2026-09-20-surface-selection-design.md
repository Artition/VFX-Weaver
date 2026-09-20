# `surface_pattern` Surface Selection — Design Spec

Status: **approved by the owner with two amendments, applied below**. Date: 2026-09-20. Target line: MC 26.2 (Fabric + NeoForge).
Scope: make the projection plane and the *set of surfaces* a `surface_pattern` draws on author-configurable — per-facing/per-axis selection and a world-coordinate band **along the selected axis**. Additive to the existing `surface_pattern` (step 5, guide v38), the step-4 field library and the step-3 mask/coverage mechanism.

This spec is written to be implemented without further decisions. The vocabulary in §2 is the approved one.

### Owner amendments (2026-09-20)

1. **`level: "top"` is dropped entirely**, and with it the geometry/coverage prepass (`VFXSurfaceTopGeometry`, `TopCoverageSampler`, the `ClientLevel.getHeight(...)` selection and the extra scratch). Topmost tracking is unnecessary; a band restriction is enough. The mechanism in the original §5 is removed and **must not be built**.
2. **The band is not Y-only.** It is applied along **the axis selected by `faces`**: `up`/`down` → world Y, `east`/`west` → world X, `north`/`south` → world Z. When several faces/axes are selected the band is applied **per fragment along that fragment's dominant normal axis** — the same axis the triplanar projection picked. The keys are therefore renamed from `min_y`/`max_y` to **`min`/`max`** and documented as "along the selected axis".

## 1. What the owner asked for, and why the original effect could not do it

Owner's words, translated: the pattern is drawn on surfaces at every level in view ("on blocks above me and below me") and there is no way to restrict it; he wants full flexibility — which orientations receive the pattern, a height/level filter, and a projection that follows the **dominant normal axis** so a wall receives a correctly oriented figure.

What existed (verified in the code):

- The shader projected with **world X/Z only** (`surface_pattern.fsh:77,86`), so the figure only landed correctly on horizontal surfaces; a vertical face got the same XZ projection sheared across the wall.
- The only orientation control was the numeric `normal_mask` param: `mask = normal_mask <= 0.0 ? 1.0 : smoothstep(normal_mask, min(normal_mask+0.2,1.0), abs(n.y))` (`surface_pattern.fsh:99-103`). The built-in `surface_pattern.json` sets `0.6`, which keeps floors and ceilings (`|n.y|`) and excludes walls.
- `abs(n.y)` deliberately **merged floor and ceiling** because the depth-derived normal's sign is ambiguous. There was no way to select `up` without `down`, and no per-axis selection.
- There was **no level/height filter at all**. `fade_radius` and `distort` were the only spatial limiting terms, and both used XZ only.

The approved change is therefore two separable things: (a) a **facing/axis selection**, (b) a **band along the selected axis**, plus **dominant-axis projection** with a signed, camera-facing normal.

## 2. Parameter vocabulary (approved)

Two kinds of knob, split by the repo's rule that **strings/enums are structural and live in a top-level block; `params` stays numeric and animatable** (the `pattern` block follows the same rule).

### 2.1 Structural block — top level `surface` (optional)

```json
"surface": {
  "faces": ["up", "north"],
  "min": 64,
  "max": 96
}
```

| Key | Type | Accepted values | Default | Meaning |
|---|---|---|---|---|
| `faces` | array of strings | single faces `"up"`, `"down"`, `"north"`, `"south"`, `"east"`, `"west"`; axis aliases `"x"` (= west+east), `"y"` (= up+down), `"z"` (= north+south); group aliases `"horizontal"` (= up+down), `"vertical"` (= north+south+east+west), `"all"` (= all six) | `["up"]` when `surface` is present and `faces` is omitted | which face orientations receive the pattern. Expanded at parse time to a 6-bit face mask |
| `min` | number, optional | any finite value | unbounded (−∞) | inclusive lower band bound, **along the fragment's dominant normal axis** (see below) |
| `max` | number, optional | any finite value | unbounded (+∞) | inclusive upper band bound, **along the same axis as `min`** |

Face-id convention (one bit each, fixed order): `0` up (+Y), `1` down (−Y), `2` north (−Z), `3` south (+Z), `4` west (−X), `5` east (+X). Minecraft's axis convention: +X = east, +Z = south, north = −Z, west = −X.

**Band axis rule (amendment 2).** The band runs along the axis the projection plane picked for that fragment: `up`/`down` → world Y, `east`/`west` → world X, `north`/`south` → world Z. With several faces selected the axis is chosen **per fragment** from its dominant normal axis, so a `surface` that mixes planes gets the band on each plane's own axis. The shader computes the axis coordinate next to the projection choice; the CPU only carries `min`/`max`.

Rules:

- **`surface.faces` present replaces `normal_mask`** for orientation. `normal_mask` is then ignored (still parsed).
- **`surface` absent entirely = legacy behaviour**: orientation is governed solely by the numeric `normal_mask` param, with its existing default `0` = all faces. This is the backward-compatible switch.
- `min`/`max` are part of the `surface` block only; a top-level `min`/`max` (or a stale `min_y`/`max_y`/`level`) key is not read at all.
- An **unknown key inside `surface`** (including a stale `level`, `min_y` or `max_y`) is a parse error for that file, so a typo cannot half-enable the feature.
- `min > max` is a parse error for that file.
- Unknown face token is a parse error for that file (per-file isolation, as for an unknown figure).

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

Floor band (band on world **Y**, because the selected faces are `up`):

```json
"surface": { "faces": ["up"], "min": 60, "max": 72 }
```

Wall band along **X** (band on world X, because the selected faces are east/west):

```json
"surface": { "faces": ["x"], "min": -8, "max": 8 }
```

Wall band along **Z** (band on world Z, because the selected faces are north/south):

```json
"surface": { "faces": ["z"], "min": -8, "max": 8 }
```

### 2.3 Reused (unchanged) vocabulary

The figure stays entirely owned by the shared shape library: the top-level `pattern` block (`figure`, `fill`, `stroke_width`, `softness`, `repeat`, `center`, `rotation`, and the figure numbers) is untouched and is still parsed by `dev.vfxweaver.field.VFXShape`. No facing or band information goes into `pattern`.

## 3. Backward compatibility

- **`normal_mask` keeps its exact meaning and default** when the `surface` block is absent. `normal_mask <= 0` remains "no orientation filter" (all faces). The built-in `surface_pattern.json` is **not edited**: it has no `surface` block, so it keeps drawing exactly as today. Renaming/removing a field is not done.
- A definition with a `surface` block is **additive**: an older mod ignores the unknown top-level key (the parser reads only the keys it knows), still parses the file, and renders with legacy `normal_mask` semantics. No `PROTOCOL_VERSION` bump: the definition is already carried inside the synced effect definition.
- `faces`/`min`/`max` are parsed in shared, MC-free `src/main` (a new `dev.vfxweaver.field.VFXSurfaceSelection` modelled on `VFXShape`), so the datapack model stays loader-agnostic.
- The three-place UBO contract still holds and is extended in lockstep: the shader's `Config` block, `VFXShaderPrograms.registerDepthPost(...)`, and the manager's reserved-name resolver are edited together (AGENTS.md). New Config names are added **after** the existing ones so no existing offset shifts; the `mat4 inv_view_proj` stays first.

## 4. Projection math

### 4.1 Signed, camera-facing normal

A depth-derived normal has an ambiguous sign. Fix it once, in a shared helper, by forcing the outward normal to face the camera:

```glsl
vec3 n = normalize(cross(dX, dY) + vec3(0.0, 0.0, 1.0e-6));
if (dot(n, world - camPos) > 0.0) n = -n;   // outward normal faces the camera
```

Now `n.y > 0` is a floor seen from above, `n.y < 0` a ceiling seen from below, and the four wall signs are meaningful. This replaces the `abs(n.y)`-only test and unlocks `up` vs `down` and `north` vs `south`.

The existing field library already computed a depth-derived normal for its `normal_facing` field function (`field.glsl`, fn 8). This extracts that computation into a shared include, `vfxweaver:camera.glsl`, exporting `vfx_world_from_depth(...)` and `vfx_camera_normal(...)`, re-used by both `field.glsl` (fn 8) and `surface_pattern.fsh` (see §6). `field.glsl`'s `normal_facing` now returns an outward, camera-facing normal.

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
float bandAxis;
if (faceId <= 1) {
    p = world.xz;                                        // horizontal: unchanged from today
    bandAxis = world.y;                                  // band on Y
} else {
    vec3 u = normalize(cross(vec3(0.0, 1.0, 0.0), n));   // horizontal tangent
    p = vec2(dot(world, u), world.y);                    // wall: +u across, world Y up
    bandAxis = (faceId >= 4) ? world.x : world.z;        // east/west -> X, north/south -> Z
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

`world.y` is the figure's vertical axis on every wall, so a figure is always upright on a wall. The figure's anchor `center` is projected into the same plane before the cell subtraction.

### 4.3 Axis seams: hard switch, not blend

On faces at ~45° (a diagonal wall, a stair nosing, a sloped hill) `|n.x| ≈ |n.z|` and the plane flips discontinuously, so a seam appears. **Decision: hard switch.** Reasoning: the figure is an SDF evaluated in `p`; blending *filtered coordinates* between two planes shears and stretches the figure across the seam, and blending the *resulting coverage* double-paints the figure near it. A hard switch keeps the figure rigid and predictable, and the seam only appears on genuinely diagonal geometry, where "which wall is this" is ambiguous anyway.

`distort` and `fade_radius` follow the chosen plane:
- `distort` warps `p`; the phase is the remaining world component along the camera-facing normal (`dot(world, n)`), which is world Y on a floor (exactly the old warp) and the out-of-plane horizontal component on a wall.
- `fade_radius` uses the **3-D** distance `length(world - center)` from the anchor (was XZ-only), so the fade works on walls too.

### 4.4 Facing / band gate in the shader

```glsl
// faces: face_mask >= 0 is the new hard set; -1 is legacy normal_mask
float faceCov;
if (face_mask >= 0.0) {
    faceCov = mod(floor(face_mask / exp2(float(faceId))), 2.0) >= 0.5 ? 1.0 : 0.0;
} else {
    faceCov = normal_mask <= 0.0 ? 1.0
            : smoothstep(normal_mask, min(normal_mask + 0.2, 1.0), abs(n.y));
}

// Band along this fragment's dominant axis.
float bandCov = (bandAxis >= band_min && bandAxis <= band_max) ? 1.0 : 0.0;

float coverage = clamp(shapeCoverage * fade * faceCov * bandCov
                     * clamp(opacity, 0.0, 1.0), 0.0, 1.0);
```

`face_mask`, `band_min`, `band_max` are reserved `Config` floats written by the manager from the parsed `VFXSurfaceSelection`; `band_min`/`band_max` default to `−1.0e30`/`+1.0e30` (gate always open) and `face_mask` defaults to `−1` (legacy) when no `surface` block is present. The current `shapeCoverage` call (`vfx_shape_pattern_coverage` through `shape.glsl` → `shapes.glsl`) is unchanged: **the figure math is not touched.**

## 5. Topmost filter — dropped

The owner dropped the `level: "top"` requirement (amendment 1). No topmost filter, no geometry/coverage prepass, no `TopCoverageSampler`, no per-column heightmap scan, and no new scratch target are built. The mask block-geometry subsystem is left exactly as it is; it is not extended by this change. A band restriction (`min`/`max`) is the only spatial level control.

## 6. Relationship to the existing shape / field / mask vocabulary

- **`shapes.glsl` / `field.glsl` stay the single owner of figures.** `surface_pattern.fsh` keeps calling `vfx_shape_pattern_coverage` (which dispatches to `shapes.glsl`'s `vfx_shape_sdf` / `vfx_shape_coverage`). No SDF, fill or repeat math is written or duplicated by this change.
- **The depth/world-position recipe is unified.** The reversed-depth world recipe was duplicated inline in `field.glsl` (`vfx_world_pos`) and `surface_pattern.fsh` (`surface_pattern.fsh:68-75`). The new shared include `assets/vfxweaver/shaders/include/camera.glsl` exports `vfx_world_from_depth(vec2 uv, float depthNdc, mat4 invViewProj)` and `vfx_camera_normal(vec3 world, vec3 dX, vec3 dY, vec3 eyeToFace)`, imported by both `field.glsl` (replacing its inline copy and fn 8's normal math) and `surface_pattern.fsh`. One implementation, two consumers; no uniforms are declared in the include (the caller passes its matrix).
- **Region limiting is still the shared `mask` block**. `surface.faces`/`min`/`max` are *surface selection*, not a spatial mask; a `surface_pattern` with both uses the mask for region and the `surface` block for orientation/band.
- **Nothing new is invented**: no new SDF, no new depth mechanism, no coverage mechanism, no graph/codegen.

## 7. Caps, bounds, failure behaviour, guards

- **Caps (AGENTS.md bounded-collection rule):**
  - `surface.faces` ≤ 8 tokens before expansion; expansion produces ≤ 6 face bits; unknown token = per-file parse error; duplicates collapse.
  - `min`/`max` must be finite and `min <= max`; parse error otherwise.
- **Failure behaviour:** the facing/band filters are **fail-closed** — no match yields coverage 0 (the pattern is absent), never full coverage. `min`/`max` open the filter when absent. The only neutral path is the existing no-depth passthrough. Parse failures stay per-file isolated (existing `VFXDefinitionManager.prepare` behaviour).
- **Guards to keep (the change touches them):**
  1. The `depthReady` / layer-0 gate in `VFXPostProcessingManager.process` (the `surface_pattern:nodepth` fallback) is unchanged: with no usable depth the pass is still replaced by a passthrough. There is no prepass to skip.
  2. The `//? if >=26.1` depth-pass registration guard in `VFXShaderPrograms` (with its `<26.2` / `>=26.2` bind-group layouts). No new sampler layout is added.
  3. On `1.21.11` nothing changes: the effect still builds and renders nothing (no depth pass registered).

## 8. Rejected alternatives (brief)

| Alternative | Verdict | Reason |
|---|---|---|
| Booleans `up: true, wall: true, …` in the block | reject | Cannot express per-axis or `north` vs `south` neatly, and grows a key per face; the token array + face mask is extensible and one field. |
| Per-axis numeric thresholds in `params` (e.g. `mask_x`, `mask_y`, `mask_z`) | reject | Mixes a structural choice (which faces) into the numeric/animatable domain; the depth normal's sign ambiguity also makes a scalar threshold unable to separate north from south. |
| Blending planes at axis seams (triplanar) | reject | Blended projected coordinates shear/distort the SDF figure; blended coverage double-paints. Hard switch keeps the figure rigid. |
| Per-fragment block lookup in GLSL | reject | A screen fragment has no world access; the world block state is not an input of the post pass. |
| Geometry/coverage prepass for "topmost" | **dropped by owner** | Not needed: a band restriction is enough. |
| Bounded block-column probe (heightmap texture sampled by the shader) | reject | Same reason (topmost dropped); and it would be a new subsystem. |
| Keeping `min_y`/`max_y` names with an "along the axis" meaning | reject | Y-only names would contradict a band that is applied on X or Z; `min`/`max` say exactly "a bound on the selected axis". |
| Bumping `PROTOCOL_VERSION` | reject | Additive datapack change; the definition is already synced. |
| Editing the built-in `surface_pattern.json` to the new vocabulary | reject (for now) | Would change shipped behaviour; the legacy path must stay exactly reproducible. |

## 9. Verification

There is no test suite; the order is build → standalone parse check → in-game (AGENTS.md).

**Without the game (standalone check harness).** Compile a throwaway `Check.java` against the built classes (AGENTS.md method 2; the `dumpRuntimeClasspath` init-script recipe is in `docs/superpowers/plans/2026-09-19-surface-pattern.md`, "Standalone compile/run checks"). Assert:
- `VFXDefinition.parse` with no `surface` block yields `getSurface() == null` and unchanged `getPattern()`;
- each token and alias expands to the expected face mask (`"up"`, `"vertical"`, `"x"`, `"all"`), duplicate tokens collapse, `"up"` does not include `"down"`, and `"diagonal"` is refused with a per-file error;
- `min`/`max` round-trip and `min > max` is refused; a stale `level`/`min_y` key is refused;
- a definition with `surface` and no `faces` defaults to `["up"]`;
- the built-ins parse sweep parses every shipped definition.

Build every node and confirm the new shader include ships in each jar.

```powershell
.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain
```
Expected: `BUILD SUCCESSFUL` for all six. Static shader checks (no GLSL compiler standalone): `jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists the new include; the `Config` name order in the shader matches `registerDepthPost`; no uniform is named after a GLSL built-in (`scripts/check-shader-identifiers.ps1`); the mask coverage UBO contract is untouched (`scripts/check-mask-ubo.ps1`).

**In game (owner).** On a scene with a flat floor, a 1-high wall, a tall wall, an overhang/ceiling and a floating platform, author throwaway definitions under the test pack's namespace `vfx_demos:` (never `vfxweaver:` — AGENTS.md), `/reload`, then `/vfx play vfx_demos:<name>` for each of: floor-only, walls-only, all, a floor band, a wall band along X, a wall band along Z, and the unchanged built-in `/vfx play vfxweaver:surface_pattern`. Confirm: the figure is upright and un-mirrored on each wall; a band only shows in range on the expected axis; an overhang underside and a cave ceiling show nothing unless selected; the built-in looks and behaves exactly as before. Repeat with the NeoForge jar. Rendering is never claimed from the build alone.

## 10. What the owner decided

1. **Token names and defaults.** Approved: `faces` tokens (`up`/`down`/`north`/`south`/`east`/`west` plus `x`/`y`/`z`/`horizontal`/`vertical`/`all`), default `["up"]` when `surface` is present but `faces` omitted, and "no `surface` block = legacy `normal_mask`" as the compatibility switch.
2. **Level filter shape.** Amended: `level: "top"` dropped; the band is `min`/`max` along the selected axis (literal, non-animated).
3. **Topmost semantics.** Dropped with the prepass.
4. **`normal_mask` fate.** Kept as the legacy fallback (preserves the built-in); marked legacy in the guide, not removed.

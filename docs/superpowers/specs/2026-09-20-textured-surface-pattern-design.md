# Textured `surface_pattern` figures — Design Spec

Status: **proposal for the owner to approve or amend**. Date: 2026-09-20. Target line: MC 26.2 (Fabric + NeoForge), i.e. the `>=26.1` depth-pass nodes.
Scope: let a `surface_pattern`'s figure be a **real texture** — a block-atlas sprite, an item-atlas sprite, any other atlas sprite, or a standalone resource-pack texture — projected onto the same dominant-axis surface the procedural figure uses. Additive to the existing `surface_pattern` (step 5, guide v38), the `surface` axis-face selection (guide v39) and the shared shape/field library.

This spec is written to be implemented without further decisions **except the vocabulary in §2**, which is the explicit proposal this document exists to settle.

## 1. What the owner asked for

Owner's words, translated: the `pattern` figure should accept not only procedural figures but **full textures projected onto the ground** — textures from Minecraft itself (block/item textures) and from resource packs. The motivating scene is a pentagram texture on the ground for a demon-summoning animation, changing size and rotating, and so on.

What exists today (verified in the code):

- `surface_pattern`'s figure is a **structural `pattern` block** parsed by `VFXShape` (`src/main/java/dev/vfxweaver/field/VFXShape.java`): `figure` is one of `circle`/`ellipse`/`rect`/`polygon`, plus `fill`, `rotation`, `softness`, `stroke_width` and a `repeat: [nx, ny]` tile modifier. All of it is evaluated by the shared GPU library (`include/shapes.glsl` → `shape.glsl`), and the shader contains no figure maths.
- The field library already has a `texture` **field function** (`VFXFieldFn.TEXTURE`, `field.glsl` fn 5): it samples a single `sampler2D fld_tex0` at `coord * vec2(scale_x, scale_y) + vec2(offset_x, offset_y)` where `coord` is screen UV or **`world.xz`** (not the dominant-axis plane), selecting a channel (`r/g/b/a/luminance`) or returning the full `vec3`. Its `texture` resource id is a **standalone** texture (`"minecraft:textures/block/stone"`), resolved by `VFXPostProcessingManager.resolveTexture` through `Minecraft.getInstance().getTextureManager().getTexture(Identifier.parse(id)).getTextureView()`. At most one texture leaf per input (`VFXField.MAX_TEXTURE_LEAVES = 1`).
- That field texture has **no atlas concept** (no sprite UV rect), **no rotation**, **no sprite-sheet / frame**, and is not the `surface_pattern` figure (which is structural, not a field).

The requested feature is therefore a **texture figure** for `surface_pattern`: the same projection, faces, band, fade and opacity the procedural figure uses, but the coverage comes from a texture's alpha (or a selected channel) and the colour from the texture's RGB.

## 2. Parameter vocabulary (proposal)

Three rules are held throughout: **strings/enums are structural and live in a block; `params` stays numeric and animatable** (the same rule as `pattern`/`surface`); **new `Config` names are appended after the existing ones** so no std140 offset shifts; **an unknown key is a per-file parse error**, so a typo cannot half-enable the feature.

### 2.1 Structural: `pattern.texture` (new, optional, nested in the existing `pattern` block)

The texture is the **figure**, so it lives in `pattern` next to `figure`. When `pattern.texture` is present and `pattern.figure` is **omitted**, the whole cell is texture (no shape clip). When `figure` is also present, the figure becomes a **mask** that clips the texture (§5).

```json
"pattern": {
  "texture": {
    "id": "minecraft:block/nether_portal",
    "source": "block",
    "channel": "alpha"
  }
}
```

| Key | Type | Accepted values | Default | Meaning |
|---|---|---|---|---|
| `id` | string | a resource id (§3) | — (required) | the sprite id (atlas sources) or the texture id (standalone) |
| `source` | string | `"block"`, `"item"`, `"atlas"`, `"standalone"` | inferred from `id` (§2.1.1) | which atlas / the standalone texture manager resolves it |
| `atlas` | string | an atlas resource id, e.g. `"minecraft:particles"` | none | required only when `source` is `"atlas"`; must be absent otherwise |
| `channel` | string | `"alpha"`, `"luminance"`, `"r"`, `"g"`, `"b"` | `"alpha"` | which texture component is the coverage. `alpha` = transparent texels show nothing (the normal case); `luminance`/`r`/`g`/`b` derive coverage from colour, for textures with no alpha |
| `sheet` | array of 2 ints | each `1..16`; product `<= 256` | `[1, 1]` | sprite-sheet grid `[columns, rows]` inside the sprite/texture; a frame is selected by the numeric `frame` param (§6) |
| `aspect` | string | `"preserve"`, `"stretch"` | `"preserve"` | `preserve`: one repeat covers `tile_scale` blocks along the texture's **longer** pixel axis and scales the shorter axis by the texture aspect; `stretch`: one repeat covers `tile_scale` in both axes |

#### 2.1.1 `source` inference (when `source` is omitted)

The id's shape is unambiguous, so a missing `source` is inferred and never guessed:

- contains `"textures/"` → `standalone` (e.g. `minecraft:textures/block/stone`);
- path starts `"item/"` → `item` (e.g. `minecraft:item/apple`);
- path starts `"block/"` → `block` (e.g. `minecraft:block/nether_portal`);
- anything else → **parse error** ("texture '…': cannot infer source, name one of block/item/atlas/standalone").

### 2.2 Numeric, animatable: new `params` (existing names in §2.3)

| Param | Type | Accepts | Default | Meaning |
|---|---|---|---|---|
| `rotation` | number | degrees, any finite value | the structural `pattern.rotation` (`0`) | **existing reserved `Config` name, now overridable by a param** — rotates the whole pattern (figure and texture together). This is the spin knob |
| `frame` | number | `0 .. cols*rows-1` (rounded, then `mod`) | `0` | sprite-sheet frame index. May be a literal, a keyframe, an `expr`, or `{ "from": "<node>" }` — the graph `time`/`math`/`curve` nodes drive it (§6) |
| `texture_tint` | number | `0..1` | `0` | `0` = draw the texture's own RGB; `1` = multiply it by `color_r/color_g/color_b`. `opacity` always scales the coverage |
| `tile_scale` | number | `> 0`, world blocks | `2.0` (existing) | world size of one pattern cell / one texture repeat. This is the scale/pulse knob |

`frame`, `texture_tint` and `rotation` are normal `Config` floats: the manager reads them from the timeline (fade-weighted, except `rotation`/`line_width` which keep their existing reserved semantics). No new graph node, no new field function, no codegen.

### 2.3 Reused vocabulary (unchanged)

The top-level `pattern` figure block (`figure`, `fill`, `stroke_width`, `softness`, `repeat`, `center`, and the figure numbers) is untouched. The `surface` block (`faces`/`min`/`max`/`band_softness`), `fade_radius`, `distort`, `opacity`, `normal_mask`, `screen_layer`, `color_r/g/b` and the shared `mask` block all keep their exact meanings. The built-in `surface_pattern.json` is **not edited**: it has no `texture`, so it behaves bit-for-bit as today.

### 2.4 Required JSON examples

**Legacy (no texture) — unchanged.** Any existing definition keeps drawing its figure exactly as today.

**Pentagram, block-atlas sprite, full cell:**

```json
{
  "type": "surface_pattern",
  "params": { "tile_scale": 2.0, "color_b": 0.6, "opacity": 0.85, "fade_radius": 48.0, "screen_layer": 0 },
  "surface": { "faces": ["up"] },
  "pattern": {
    "figure": "ellipse", "radius_x": 0.5, "radius_y": 0.5, "fill": "solid", "softness": 0.01,
    "texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha" }
  }
}
```

Here the `ellipse` clips the pentagram to a circle (the texture has no alpha outside the sigil, but the figure makes it exact and gives a soft edge).

**Standalone resource-pack pentagram, texture only (no figure = no clip):**

```json
"pattern": {
  "texture": { "id": "mypack:textures/vfx/pentagram", "source": "standalone", "channel": "alpha", "aspect": "preserve" }
}
```

**Rotating and pulsing pentagram** (rotation and tile_scale animated, `frame` from a node — see §6):

```json
{
  "type": "surface_pattern",
  "params": {
    "screen_layer": 0, "opacity": 0.9, "fade_radius": 40.0,
    "rotation": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 200, "value": 360, "easing": "linear" } ] },
    "tile_scale": { "start": 1.0, "end": 3.0, "easing": "ease_in_out" }
  },
  "surface": { "faces": ["up"] },
  "pattern": {
    "figure": "circle", "radius": 0.5, "softness": 0.02,
    "texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha" }
  }
}
```

**Sprite-sheet animation** (a 4×4 sheet, frame 0..15 driven by the graph — §6):

```json
"pattern": {
  "texture": { "id": "mypack:textures/vfx/summon", "source": "standalone", "sheet": [4, 4], "channel": "alpha" }
},
"params": { "tile_scale": 3.0, "opacity": 0.9 },
"inputs": { "frame": { "from": "frame_t" } },
"graph": {
  "nodes": [
    { "id": "t", "kind": "time", "inputs": { "speed": 1.0 } },
    { "id": "frame_t", "kind": "math", "op": "floor", "inputs": { "a": { "from": "t" } } }
  ],
  "edges": [
    { "from": "t", "to": "frame_t", "input": "a" }
  ]
}
```

The `math` node's `floor` (and `mod`) is the existing logic set; **no new node or field is needed** for frame animation.

## 3. Texture sources: resolution, samplers, UVs

One texture per pattern (`MAX_TEXTURE_LEAVES = 1` is the intended cap). The CPU resolves a source into three things the shader needs: a **texture view**, a **sprite UV rect** `(u0, v0, u1, v1)` in `0..1`, and a **pixel aspect**. All three go into the pass `Config`; one new sampler is bound.

| `source` | `id` form | CPU resolution (client, `>=26.1`) | Sampler bind | UV rect handed to the shader |
|---|---|---|---|---|
| `block` | `minecraft:block/nether_portal` | `sprite = Minecraft.getInstance().getAtlasManager().get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.parse(id)))` | the block **atlas** texture view (`getAtlasManager().getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS)`, or `TextureManager.getTexture(LOCATION_BLOCKS)`) | `(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1())`; aspect = `sprite.contents().width()/height()` |
| `item` | `minecraft:item/apple` | same with `TextureAtlas.LOCATION_ITEMS` | the item atlas view | same |
| `atlas` | `mypack:my_atlas` (a sprite id is still required in `id`, with `atlas` naming the atlas) | `getAtlasManager().get(new SpriteId(Identifier.parse(atlas), Identifier.parse(id)))` | that atlas's view, via `getAtlasManager().getAtlasOrThrow(atlas)` | same |
| `standalone` | `minecraft:textures/block/stone` / `mypack:textures/vfx/pentagram` | `TextureManager.getTexture(Identifier.parse(id))` (the existing field-texture path) | the `AbstractTexture.getTextureView()` | `(0, 0, 1, 1)` |

Verified on the 26.2 and 26.1.2 client jars: `Minecraft.getAtlasManager()`, `AtlasManager.getAtlasOrThrow(Identifier) : TextureAtlas`, `AtlasManager.get(SpriteId) : TextureAtlasSprite`, `SpriteId(Identifier atlasLocation, Identifier texture)`, `TextureAtlasSprite.getU0/getV0/getU1/getV1`, `TextureAtlas.LOCATION_BLOCKS/LOCATION_ITEMS/LOCATION_PARTICLES`, `AbstractTexture.getTextureView()`. `TextureManager.getTexture(id)` verified: it returns the registered texture, else creates and synchronously loads a `SimpleTexture(id)` (missing resource logs once and uploads the missing contents; it does not throw per frame).

**Atlas sprites and UVs.** A block/item texture lives in a stitched atlas, so its UVs are a sub-rect, not `0..1`. The CPU has the rect (the shader never can — a fragment has no atlas knowledge) and the shader maps its pattern UV into the rect. **Animated sprites need no `frame`**: `TextureAtlas` is a `TickableTexture` whose `tick()` advances the atlas animation, so an animated block/item sprite is the current frame automatically. `frame`/`sheet` are for author-made sprite sheets.

**Reuse vs extend — the honest verdict.** The existing field `texture` function is **partially reused and must be extended**:

- **Reused:** the standalone id form (`…/textures/…`), the `TextureManager.getTexture(...).getTextureView()` resolution, the `sampler2D` + channel selection, and the "one texture per consumer" cap.
- **Extended into a shared include:** the atlas sprite-rect mapping, the sprite-sheet/frame UV sub-rect, and the channel extraction — extracted from `field.glsl` fn 5 into a new `include/texture.glsl`, called by **both** the field function (same behaviour when `rect=(0,0,1,1)`, `sheet=(1,1)`, `frame=0`) and `surface_pattern`.
- **New:** projection through the pattern's own rotated/tiled cell, aspect handling, tinting, and a `PatternSampler` bind in the depth pass. The field `texture` function keeps its `world.xz`/screen-space sampling; it does **not** gain the dominant-axis plane.

## 4. Projection and mapping

`surface_pattern.fsh` already reconstructs the world position, chooses the dominant-axis plane `p` (floor: `world.xz`; wall: `(dot(world,u), world.y)`; ceiling: `world.xz`) and builds the cell-local coordinate `local` by centring on the anchor, dividing by `tile_scale`, rotating and applying `repeat`. The texture reuses that **exact** coordinate:

```glsl
// The shared library gains one extraction so the figure and the texture agree on the cell:
vec2 vfx_shape_cell(vec2 p, vec2 repeat, float rotationDeg); // rotate -> repeat -> [-0.5, 0.5]
```

Then, with `local = vfx_shape_cell(p - centerP, repeat, rotation) / tile_scale` and the texture descriptor:

```glsl
vec2 uv = local + 0.5;                          // cell -> 0..1
if (preserve) uv = vfx_texture_aspect(uv, tex_aspect);   // §2.1 `aspect`
// sheet: divide by cells and add the frame cell (texture.glsl)
// atlas: mix(rect.xy, rect.zw, uv) after the sheet sub-rect
vec4 texel = texture(PatternSampler, atlasUv);
```

- **`(u, v)` become** the same plane axes as the figure: on a floor `u = +X`, `v = +Z`; on a wall `u` is the horizontal tangent and `v = world Y`, so a texture is upright on a wall and un-mirrored from the visible side, exactly like the figure.
- **Tiling vs clamp:** tiling is the pattern's `repeat` (the existing `fract` in the cell), not sampler wrap. The sampler stays `CLAMP_TO_EDGE` and the atlas rect is clamped, so a repeat never bleeds into a neighbouring atlas sprite.
- **Aspect:** `preserve` (default) keeps the texture's pixel aspect by scaling the shorter axis of `uv`; `stretch` maps the cell's full square to the sprite.
- **Relation to `tile_scale`/`repeat`:** unchanged from the figure. `tile_scale` is the world size of one repeat (the pulse knob); `repeat: [nx, ny]` tiles the texture within the pattern cell (the grid knob for a repeating texture).
- **Backward compatibility:** with no `pattern.texture`, the shader takes the current `shapeCoverage` path only; every arithmetic term is identical, so an existing figure behaves exactly as today.

## 5. Composition with figures — recommendation

**Recommendation: a texture is the figure's alternative source, and a `figure` becomes its mask.** Concretely, in `pattern`:

- no `texture`, `figure` present → today's procedural figure (unchanged);
- `texture`, no `figure` → the texture fills the cell (coverage = texture channel);
- `texture` **and** `figure` → `coverage = textureCoverage * shapeCoverage` (the shape clips the texture; solid fill = a hard clip, `softness` = a soft edge).

This is one rule, needs no combination syntax, and both requested examples fall out directly: the pentagram (above) and the circle-clipped texture. The shape is owned by the shared library; the shader only multiplies two coverages.

The rejected alternative — a new `figure: "texture"` value in the shared `VFXShapeFigure` enum — is in §10.

## 6. Animation

Everything the owner asked to animate already has a driver; no codegen and no new node kind:

| Property | Driver | Notes |
|---|---|---|
| **Rotation** | numeric `params.rotation` (keyframes / `expr` / `{ "from": node }`) | The manager resolves `rotation` as `effect.getParam("rotation", pattern.rotation())` (same pattern as the existing `line_width` override of `stroke_width`). The value already travels in the existing `rotation` `Config` float, so **no new uniform** |
| **Scale / pulse** | numeric `params.tile_scale` (existing, animatable) | one repeat = `tile_scale` blocks; keyframe it 1.0 → 3.0 → 1.0 |
| **Sprite-sheet frame** | numeric `params.frame` | integer; `{ "from": "<node>" }` with the graph `time` → `math:floor`/`math:mod` nodes (the existing logic set), or plain keyframes. Path in §2.4 |
| **Animated block/item sprite** | the atlas itself | `TextureAtlas.tick()` advances it; no `frame` needed |
| **Motion of the plane** | `distort` (existing), or the anchor / `positions` | `distort` warps the projected plane as today |

A rotating+pulsing pentagram is the §2.4 example: `rotation` keyframes spin it, `tile_scale` keyframes pulse it, and the block atlas animates if the chosen sprite is animated. A new graph node/field is **not** needed; if the owner later wants a dedicated `sprite_frame` field function, that is a separate addition.

## 7. Appearance

- **Alpha as coverage.** With `channel: "alpha"` (default), `coverage = texel.a * patternShapeCoverage`, so transparent texels show nothing (the pentagram's negative space). `luminance`/`r`/`g`/`b` read colour as coverage for textures without alpha.
- **Tinting/recolouring.** `texture_tint` blends the texture RGB toward `textureRGB * color_r/g/b` (0 = texture as-is, 1 = fully recoloured); `opacity` multiplies coverage. `color_r/g/b` still means the flat colour when there is no texture.
- **Blending with the existing path.** `coverage` is still multiplied by `fade`, the face set, the band and `opacity`, then composited as `mix(base.rgb, patternRGB, coverage)` with `base.a` preserved — so `fade_radius`, `distort`, `surface.faces`, `min/max/band_softness` and `mask` all work on a texture exactly as on a figure.
- **Lighting/emissive.** None needed and none added: the pattern is a screen-space blend at layer 0, already drawn at full brightness (effectively emissive). The texture is not lit by the world, by design; a "dimmed with distance" look is `fade_radius`.

## 8. Caps, failure behaviour, reload

**Caps (bounded-collection rule):**

- one texture leaf per pattern (`MAX_TEXTURE_LEAVES = 1`); `pattern.texture` is a single object, never an array;
- `sheet` components `1..16` and `cols*rows <= 256` (a new `MAX_SHEET_FRAMES = 256`); a larger sheet is a per-file parse error;
- `frame` is clamped into `[0, cols*rows)` by the shader (`mod`), so a runaway graph value cannot index out of range;
- resolution results are cached per pass, bounded by the active-effect cap (`MAX_ACTIVE_EFFECTS`); the cache is invalidated on a resource reload (below).

**Failure behaviour (fail-closed, never full-screen):**

- unknown `source`/`channel`/`aspect`, a blank `id`, `atlas` present on a non-`atlas` source (or absent on `atlas`), a bad sheet — **per-file parse error** in shared `src/main`, so only that definition is dropped (existing `VFXDefinitionManager.prepare` isolation);
- `atlas` id that no loaded pack defines → `getAtlasOrThrow` throws on the client; it is caught, `VFXLog.warnOnce(logger, "surface_pattern:texture:" + effect.getId(), …)` fires once per effect, `tex_flags` present-bit is cleared and **coverage is 0** (the pattern is absent, never a full-screen fill);
- sprite not in a valid atlas → `AtlasManager.get` returns the vanilla missing sprite; the pattern draws the missing texture (fail-visible, bounded) and warns once. This is the one deliberately visible failure, because a *valid* atlas with a mistyped sprite should be obvious;
- standalone PNG missing/unreadable → `TextureManager` logs its own "Failed to load texture" once and uploads missing contents; the pattern draws the missing texture and warns once;
- a texture defined only in some resource packs → resolves where present, fails closed where absent (same warnOnce path); no crash, no per-frame exception.

**Per-frame errors.** All of the above are resolved when the frame's pass chain is built (not per pixel); every repeat goes through `VFXLog.warnOnce` with a bounded key, so a running effect cannot flood the log.

**Reload behaviour.** On `/reload` or a resource-pack change, `TextureManager`/`AtlasManager` re-stitch and call `AbstractTexture.releaseTextures()`, which **closes and nulls the view**; a cached `GpuTextureView` would dangle. The implementation must therefore cache the resolved **descriptor** (or the `AbstractTexture`/`TextureAtlas`, whose instance is reused across reloads) and call `getTextureView()` each frame, and/or clear the cache on a resource reload. **This is a latent bug in the existing field `fld_tex0` cache too** (it caches `GpuTextureView` objects in `VFXPass.textureCache`); the fix should cover both. A `TextureAtlasSprite`'s rect can change on a re-stitch (pack order/assets changed), so the sprite is re-fetched after a reload rather than cached statically.

## 9. Cross-version and guards

- The feature is client-only and rides the **`>=26.1` depth pass**: `surface_pattern` is only registered there (the `//? if >=26.1` guard in `VFXShaderPrograms.register()`), so on `1.21.11` the effect already renders nothing and the new sampler/layout is not compiled.
- The parse model (`VFXShape`/`VFXTexture`) is shared, MC-free `src/main` and compiles on every node; no `net.minecraft.client.*` reference leaves the client source set.
- The new `PatternSampler` layout is a new bind group on `>=26.2` (`BindGroupLayout.builder().withSampler("PatternSampler")`) and `.withSampler("PatternSampler")` on `<26.2`, added only inside the `>=26.1`-guarded registration path.
- The `Config` block, the `registerDepthPost` name list and the shader declaration are edited in lockstep (AGENTS.md UBO rule); new names go after `band_softness`.

## 10. Rejected alternatives (brief)

| Alternative | Verdict | Reason |
|---|---|---|
| `"figure": "texture"` in the shared `VFXShapeFigure` enum | reject | A texture is not a signed distance; the enum is shared by the field `shape` function and the mask coverage prepass, and an ordinal cannot carry a source/id/sampler. It would pollute `shapes.glsl` for every consumer |
| Resolve all sources as standalone textures (`minecraft:block/x` → `textures/block/x`) | reject | Loses atlas animation (`nether_portal`) and atlas padding/filtering; animated strips would show the raw frame sheet |
| Sample the whole atlas with free UVs | reject | Would show the entire atlas; the sprite rect must come from the CPU |
| Compute sprite UVs in the shader/GPU | reject | No atlas knowledge exists per fragment; a lookup texture would be a new subsystem |
| Reuse the field `texture` function unchanged as the figure | reject | It projects `world.xz` only (no dominant-axis walls), has no atlas rect, rotation, sheet or frame |
| New GLSL codegen (`figure ← texture + noise + …`) | reject | The documented non-goal of the field design; a fixed library plus composition is the substitute |
| Resolve textures per frame on the render thread | reject | `TextureManager.getTexture` synchronously loads a new PNG; caching is required |
| `fract` wrap on the atlas UV | reject | Bleeds into neighbouring sprites; tile with the pattern `repeat` and clamp inside the rect |
| A separate `surface_texture` effect | reject | Duplicates the depth/projection/face/band machinery; the shared library owns figures, and `surface_pattern` is the world-projection consumer |

## 11. Verification

There is no test suite; the order is build → standalone parse check → in-game (AGENTS.md). Rendering is never claimed from a build.

**Without the game (standalone check harness).** Compile a throwaway `Check.java` against the built classes (AGENTS.md method 2; the `dumpRuntimeClasspath` recipe is in `docs/superpowers/plans/2026-09-19-surface-pattern.md`, "Standalone compile/run checks") and assert on `VFXShape.parse`/`VFXDefinition.parse`:

- `pattern` with a `block`/`item`/`standalone` texture parses; `VFXShape.texture()` round-trips `id`/`source`/`channel`/`sheet`/`aspect`;
- `source` inference: `minecraft:block/x` → `block`, `minecraft:item/x` → `item`, `…/textures/…` → `standalone`; an unprefixed ambiguous id is refused;
- unknown `source`/`channel`/`aspect`, a blank `id`, `atlas` on a `block` source, `sheet` `[0,1]`/`[17,17]`/`[16,16]` (product 256 is allowed) are refused per file; `cols*rows > 256` is refused;
- a `texture`-only `pattern` has no shape clip (`figure` absent); a `figure`-only `pattern` still yields the procedural figure and `getPattern().texture() == null`;
- the built-ins parse sweep still parses every shipped definition and the built-in `surface_pattern.json` is unchanged.

Build every node and confirm the new include ships:

```powershell
.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain
```

Expected `BUILD SUCCESSFUL` for all six. Then `jar tf versions/<node>/build/libs/vfxweaver-*.jar` lists `assets/vfxweaver/shaders/include/texture.glsl`; the `Config` name order in `surface_pattern.fsh` matches `registerDepthPost`; no uniform is named after a GLSL built-in (`scripts/check-shader-identifiers.ps1`); the mask coverage UBO contract is untouched (`scripts/check-mask-ubo.ps1`).

**In game (owner).** Author throwaway definitions under the test pack's namespace `vfx_demos:` (**never `vfxweaver:`** — AGENTS.md) in `C:\Users\Light Flight PC\AppData\Roaming\PrismLauncher\instances\26.2test\minecraft\saves\New World\datapacks\vfx_demos\data\vfx_demos\vfx\`, `/reload`, then `/vfx play vfx_demos:<name>` for each:

1. `tex_pentagram_block` — `minecraft:block/nether_portal`, `figure: ellipse`, floor only; confirm the pentagram is world-anchored, correctly oriented, transparent texels empty, and clipped to the ellipse;
2. `tex_pentagram_pack` — a standalone pack texture `mypack:textures/vfx/pentagram`; confirm a resource-pack texture resolves;
3. `tex_circle_clip` — a rectangular/no-alpha texture clipped by `figure: circle` with `softness`; confirm a soft circular edge;
4. `tex_spin_pulse` — `rotation` keyframes and `tile_scale` pulse (the §2.4 example); confirm it rotates about the anchor and grows/shrinks;
5. `tex_sheet` — a 4×4 sheet with `frame` driven by the graph; confirm frames advance and the sheet has no atlas bleed;
6. `tex_missing` — a deliberately absent sprite/atlas; confirm the one-time warning, no crash, no full-screen fill;
7. `/reload` **while effect 1 is running**, then a resource-pack toggle; confirm no crash and the texture reappears (cache invalidation).

Repeat with the NeoForge jar. Confirm `/vfx play vfxweaver:surface_pattern` (the built-in) is unchanged. Never claim these results; record exactly what the owner reports.

## 12. Implementation outline (ordered; each step independently verifiable)

1. **Shared parse model** — add `VFXTexture` (source enum, id, atlas, channel enum, sheet, aspect) and extend `VFXShape` with an optional `@Nullable VFXTexture texture()` plus a `figureAuthored` flag; `pattern.texture` is parsed in `src/main`, MC-free. *Verify:* the `Check.java` parse assertions + build all six.
2. **Shared shader include** — create `include/texture.glsl` (`vfx_texture_uv`, `vfx_texture_sample`, channel extraction); refactor `field.glsl` fn 5 to call it (identical behaviour); extract `vfx_shape_cell` in `shape.glsl`. *Verify:* build all six, jar listing, existing field demos parse unchanged (visual check deferred to step 7).
3. **Pass registration** — append the new `Config` floats (`shape_present`, `tex_u0/v0/u1/v1`, `tex_aspect`, `tex_cols`, `tex_rows`, `tex_frame`, `tex_flags`, `tex_channel`, `texture_tint`) to `registerDepthPost` and add the `PatternSampler` layout. *Verify:* build all six; config-name count matches the shader (step 5 must land with this).
4. **Manager resolution** — resolve `source` → texture view + sprite rect + aspect; bind `PatternSampler` (placeholder bind when absent); resolve `rotation` as `getParam("rotation", …)`; reload-safe cache; warnOnce + fail-closed. *Verify:* build all six; static review of the guarded `>=26.1` path.
5. **Shader** — `surface_pattern.fsh` samples `PatternSampler` at the shared cell coordinate, multiplies the shape coverage, applies tint, and keeps the exact legacy arithmetic when no texture is present. *Verify:* build all six + jar; step 3's Config order equals this declaration.
6. **Backward compatibility** — do **not** edit `src/main/resources/data/vfxweaver/vfx/surface_pattern.json`; confirm its parse and render are unchanged. *Verify:* the parse sweep + in-game built-in check.
7. **In-game verification** — the seven `vfx_demos:` effects above, Fabric then NeoForge (owner).
8. **Docs** — `docs/GUIDE.md` (the `pattern.texture` block, the new params, a pentagram example) and `docs/CHANGELOG.md` (user-visible entry). Additive; no `PROTOCOL_VERSION` bump (the definition is already synced).

## 13. What the owner must decide

1. **Where the texture lives:** nested `pattern.texture` (recommended here, because the texture *is* the figure and `VFXShape` is already the pattern model) vs. a top-level `texture` block sibling of `pattern`. Give the word; the rest of the vocabulary is unaffected.
2. **Source vocabulary:** `source: block|item|atlas|standalone` + `atlas` + the prefix inference rule (§2.1.1), and whether `source` should instead be mandatory (no inference).
3. **Channel vocabulary:** `channel: alpha|luminance|r|g|b` with `alpha` default.
4. **Composition:** confirm "texture is the figure; `figure` is a mask" (§5) over the alternative "figure XOR texture".
5. **Animation vocabulary:** `rotation` (param override), `tile_scale` (pulse), `frame` (sheet) and `texture_tint` — names, defaults and the `0..1` tint range.
6. **Missing-sprite behaviour:** fail-closed (drop the pattern + warn) vs. fail-visible (draw the missing texture + warn). This spec recommends **visible for a missing sprite inside a valid atlas** and **closed for an unresolvable atlas/source**.
7. **Aspect:** `aspect: preserve|stretch`, default `preserve`; whether `preserve` should instead be the only behaviour (no `stretch`).
8. **Scope:** whether to also fix the existing field `fld_tex0` reload-cache bug in the same change (recommended) or file it separately.

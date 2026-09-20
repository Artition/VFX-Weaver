# Surface pattern

## `surface_pattern`
A shape pattern projected onto the terrain behind each pixel: a world-anchored figure (circle, ellipse, rect or polygon) drawn on whatever surface the scene depth reconstructs, so it stays fixed to world blocks as you turn and walk. It reads scene depth through the shared world reconstruction, which converts the raw depth per node: **Minecraft 26.2** uses reversed depth (near = 1, far = 0) and **26.1.2 / 1.21.11** use standard depth (near = 0, far = 1). The effect renders on **all supported lines** (Fabric and NeoForge). It must run at `"screen_layer": 0` — the single scene depth buffer is only intact below the first-person hand.

| Param | Default | Description |
|---|---|---|
| `screen_layer` | 0 | Must be 0 for a meaningful result (depth is the hand-only buffer at layer 1+) |
| `tile_scale` | 1 | World size of one cell in blocks (larger = bigger figure) |
| `line_width` | — | Numeric override of the shape's structural `stroke_width` (cell units) |
| `color_r` / `color_g` / `color_b` | 1 / 1 / 1 | Pattern colour |
| `opacity` | 1 (fades to 0) | Overall strength |
| `fade_radius` | 0 | Distance from the anchor at which the pattern fades out, blocks (0 = no fade) |
| `normal_mask` | 0 | Legacy orientation filter: minimum absolute Y of the surface normal — `0.6` keeps floors/ceilings and excludes walls (0 = off). Ignored when a `surface` block is present |
| `distort` | 0 | World-space sine warp of the pattern coordinate (0 = off). The phase follows the in-plane coordinate, so it actually warps a flat floor/wall (it used to be constant there and merely translated the pattern) |
| `rotation` | — | Numeric override of the structural `pattern.rotation` in degrees (keyframes/`expr`/graph-driven like any param). Spins the figure and a texture together |
| `frame` | 0 | Sprite-sheet frame index when a `pattern.texture` `sheet` is set (rounded, wrapped into `0..cols*rows-1`); literal, keyframe, `expr` or `{ "from": "<node>" }` graph-driven |
| `texture_tint` | 0 | `0` = draw the texture's own RGB; `1` = multiply it by `color_r/g/b`. `opacity` always scales the coverage |

The figure is a top-level structural `pattern` block — the strings and figure numbers live there, never in `params` (which stays numeric and animatable), so an older mod ignores the whole block. It is owned by the shared shape library and uses the same figures as §3.7's `shape` field:

| Field | Default | Meaning |
|---|---|---|
| `figure` | `circle` | `circle` (`radius`); `ellipse` (`radius_x`/`radius_y`); `rect` (`half_width`/`half_height`, optional `corner_radius`); `polygon` (`sides` ≥ 3, `radius`) |
| `center` | — | Optional literal `[x, y, z]` world anchor. When absent the anchor is the effect's own world position: a Java-API move, else its first world `position` (e.g. via `/vfx playat` or `positions`), else the `pos_x/pos_y/pos_z` binds, else the local player for a player-anchored play. The camera is never the anchor, so a camera-only change (third-person/F5) does not move the pattern. `center_x`/`center_y`/`center_z` are **not** pattern fields (the shader's centre comes from this array) and are a parse error |
| `rotation` | 0 | Figure rotation in degrees in the surface plane. `center.y` anchors the 3-D `fade_radius` distance and the wall projection; on a floor (world-XZ projection) only `center.x`/`center.z` set the cell origin |
| `fill` | `solid` | `solid` or `stroke` |
| `stroke_width` | 0.05 | Stroke thickness when `fill: stroke` (overridable by `params.line_width`) |
| `softness` | 0.01 | Edge softness |
| `repeat` | `[1, 1]` | `[nx, ny]` tiling of the figure inside a cell, each `1..64` |

**A grid is not a mode** — it is any figure with a `repeat` greater than 1 (or a stroked `rect`); **a ring is not a mode** — it is `ellipse` with `"fill": "stroke"`. To limit the pattern to a region, use the shared `mask` block (§3.8), not a `surface_pattern` field.

The projection follows the fragment's **dominant world normal**: floors/ceilings keep world XZ, while a vertical wall is projected onto its horizontal tangent across and world Y up, so the figure reads upright and un-mirrored on each wall. The axis switch is hard (not blended) — a seam only shows on genuinely diagonal geometry, where "which wall this is" is ambiguous anyway.

An optional top-level structural `surface` block (strings/enums, never in `params`) selects which faces receive the pattern and an optional world-space band. **Without it the shader keeps the legacy numeric `normal_mask` exactly as before**, so existing definitions are unchanged; with it, `faces` replaces `normal_mask` for orientation (the numeric param is then ignored).

| Field | Default | Meaning |
|---|---|---|
| `faces` | `["up"]` | Which face orientations get the pattern. Tokens: `up`, `down`, `north`, `south`, `east`, `west`; axis aliases `x` (= west+east), `y` (= up+down), `z` (= north+south); groups `horizontal` (= up+down), `vertical` (= north+south+east+west), `all`. At most 8 tokens; duplicates collapse |
| `min` | −∞ | Inclusive lower bound of the band, **along the fragment's dominant axis**: Y for `up`/`down`, X for `east`/`west`, Z for `north`/`south` |
| `max` | +∞ | Inclusive upper bound of the band, same axis as `min` |
| `band_softness` | 0 | Half-width in blocks of a soft fade centred on `min` and `max` (`0..4`). A surface lying exactly on a bound otherwise shimmers, because the depth-reconstructed axis coordinate jitters across the hard test from pixel to pixel; a small value (e.g. `0.05`) removes it while keeping the band interior solid. A requested value wider than half the band (`max - min`) is clamped to half the band, so a narrow band never loses full coverage at its centre. `0` = the exact hard edge, so definitions that omit it are unchanged |
| `stitch` | `false` | Opt-in **planar (top-down) projection**. When `true`, every face samples the same top-down coordinate `p = world.xz`, so a wall pixel `(x, y, z)` shows exactly what the floor pixel at its wall base `(x, floor_y, z)` shows — the image's row at the wall line extruded vertically (a ring reaching a wall becomes two vertical stripes from its crossing points; a texture stretches its edge row up the wall). Because `p` never depends on `world.y`, the seam is continuous for **any anchor height** (the anchor's Y no longer matters) and a vertical corner (two walls sharing one XZ) cannot tear the pattern. `min`/`max` apply to world Y on every face, so they bound how far up the wall the image stretches. A ceiling (`down`) is excluded: a top-down projection cannot light a down-facing surface. The deliberate trade-off is that this is a 1D slice (vertically constant colour columns on a wall), not a 2D unwrapped image. Off (the default), the hard floor/wall plane switch is unchanged |

An unknown key or face token, an empty `faces` array, more than 8 tokens, a non-finite bound, `band_softness` outside `0..4`, or `min > max` is a per-file parse error. A `surface_pattern` whose `positions` contains an entity anchor is also a parse error — the pattern anchor is a world point (the shader would otherwise silently fall back to the player).

```json
"surface": { "faces": ["up"], "min": 60, "max": 72 }
```
```json
"surface": { "faces": ["x"], "min": -8, "max": 8 }
```
```json
"surface": { "faces": ["z"], "min": -8, "max": 8 }
```
```json
"surface": { "faces": ["up"], "min": 128, "max": 256, "band_softness": 0.05 }
```

The first is a floor band on world Y; the second is a band along world X on the east/west walls; the third is a band along world Z on the north/south walls (the axes follow `faces`, not always Y); the fourth is a floor band whose `min` bound sits exactly on the surface the player stands on, so its edge is faded over `band_softness` to stop it shimmering.

**Planar projection (`surface.stitch`).** By default a wall is projected onto its own plane (`u = cross(worldUp, n)` across, world Y up) and a floor onto world XZ — two different physical planes, so a figure can never be continuous across a floor/wall edge. `"stitch": true` removes that seam for the common floor+walls case: **every** face samples the same top-down coordinate `p = world.xz`, so a wall pixel `(x, y, z)` shows exactly what the floor pixel at the wall base `(x, floor_y, z)` shows — the image's row at the wall line, extruded vertically. A ring reaching a wall becomes two vertical stripes from its crossing points; a texture stretches its edge row up the wall. Because `p` never depends on `world.y`, the seam is continuous for **any anchor height** (the anchor's Y no longer matters), and neither edge-pixel normal flicker nor a vertical corner (two walls sharing one XZ) can tear the pattern. On every face `min`/`max` apply to world Y, so they become a height slab that bounds how far up the wall the image stretches.

It is additive and **off by default** — without it every existing definition renders exactly as before. The deliberate trade-off is that this is a **1D slice**: a wall shows vertically constant colour columns (the slice of the image along its base line), not a 2D unwrapped image — that is the intended "the image continues up the wall" semantics, not a defect. A ceiling is excluded: a top-down projection cannot light a down-facing surface, so `down` never receives the pattern in this mode whatever `faces` says.

```json
"surface": { "faces": ["up", "north", "south", "east", "west"], "stitch": true }
```

**Textured figure (`pattern.texture`).** An optional structural `texture` object inside `pattern` replaces the procedural figure with a real texture projected onto the same surface — the same faces, band, fade, `distort` and `opacity`. The texture **is** the figure; an authored `figure` becomes its mask (`coverage = texture channel × shape coverage`), so a texture with no `figure` is not clipped. Without a `texture` the block is bit-for-bit the procedural figure.

| Field | Default | Meaning |
|---|---|---|
| `id` | — (required) | The sprite id (atlas sources) or texture id (standalone), e.g. `minecraft:block/nether_portal`, `minecraft:item/apple`, `minecraft:textures/block/stone`, `mypack:textures/vfx/pentagram` |
| `source` | inferred | `block`, `item`, `atlas` or `standalone`. Inferred from the id when omitted: contains `textures/` → `standalone`; path starts `item/` → `item`; starts `block/` → `block`. An ambiguous id is a parse error |
| `atlas` | — | The atlas resource id, required only for `source: "atlas"` (e.g. `minecraft:particles`) and rejected otherwise |
| `channel` | `alpha` | Which component is coverage: `alpha` (transparent texels show nothing), `luminance`, `r`, `g` or `b` (for textures with no alpha) |
| `sheet` | `[1, 1]` | Sprite-sheet grid `[cols, rows]` inside the sprite/texture; each `1..16` and `cols*rows <= 256`. The displayed cell is the numeric `frame` param |
| `aspect` | `preserve` | `preserve` keeps the texture's pixel aspect (one repeat covers `tile_scale` blocks along the longer pixel axis; the shorter axis is scaled by the aspect); `stretch` maps the whole square cell to the sprite |

An atlas source (`block`/`item`/`atlas`) samples the stitched atlas at the sprite's own sub-rect and follows the atlas animation automatically (an animated block/item sprite needs no `frame`); `standalone` samples a resource-pack texture over `0..1`. Tiling is the figure's structural `repeat`, never sampler wrap, and the sampler clamps to the sprite rect so a repeat cannot bleed into a neighbouring sprite. Unknown keys, a bad `source`/`channel`/`aspect`, a blank or syntactically invalid `id`/`atlas`, `atlas` on a non-atlas source, a bad `sheet`, or an `id` whose source cannot be inferred are per-file parse errors. A sprite missing from a valid atlas is drawn as the vanilla missing texture and warned once (fail-visible); an unknown atlas draws nothing (fail-closed) and warns once, and a missing standalone PNG is uploaded as the missing texture and drawn, with one warning — never a full-screen fill. The sampler is **NEAREST with no mipmaps** (like vanilla's block atlas), so a pixel texture projected over `tile_scale` blocks stays crisp rather than bilinear-blurred. One repeat spans `tile_scale` world blocks, so one texel spans `tile_scale / pixels` blocks (a 16×16 sprite at `tile_scale: 3` is one texel per 0.1875 blocks). Sheet frames are row-major and wrapped into `0..cols*rows-1`; each cell is inset by half a texel so a frame border cannot bleed into the next cell. The texture is re-resolved every frame, so `/reload` and resource-pack changes pick up the re-stitched atlas and recreate the sampler view. The resolver is **per node** (26.2/26.1.2 use the `sprite` AtlasManager keyed by a sprite id, 1.21.11 the older model AtlasManager and `TextureAtlas.getSprite`), but the resolved descriptor — sprite rect, pixel aspect, sheet, channel and flags — is identical, so textured projections render on **all three lines (26.2, 26.1.2 and 1.21.11)** with the same fail-closed gate: an authored texture that does not resolve draws nothing, never the procedural figure.

```json
{
	"type": "surface_pattern",
	"params": { "screen_layer": 0, "tile_scale": 2.0, "color_b": 0.6, "opacity": 0.85, "fade_radius": 48.0 },
	"surface": { "faces": ["up"] },
	"pattern": {
		"figure": "circle", "radius": 0.5, "softness": 0.02,
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha" }
	}
}
```
```json
{
	"type": "surface_pattern",
	"duration": 200, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "opacity": 0.9, "fade_radius": 40.0,
		"rotation": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 200, "value": 360, "easing": "linear" } ] },
		"tile_scale": { "start": 1.0, "end": 3.0, "easing": "ease_in_out" }
	},
	"inputs": { "frame": { "from": "frame_t" } },
	"surface": { "faces": ["up"] },
	"pattern": {
		"texture": { "id": "mypack:textures/vfx/summon", "source": "standalone", "sheet": [4, 4], "channel": "alpha" }
	},
	"graph": {
		"nodes": [
			{ "id": "t", "kind": "time", "inputs": { "speed": 1.0 } },
			{ "id": "frame_t", "kind": "math", "op": "floor", "inputs": { "a": { "from": "t" } } }
		]
	}
}
```
The first clips a block-atlas sprite to a soft circle; the second spins (`rotation` keyframes) and pulses (`tile_scale`) a standalone 4×4 sheet whose `frame` is graph-driven (`time` → `math:floor`).

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

```
/vfx play vfxweaver:surface_pattern
```

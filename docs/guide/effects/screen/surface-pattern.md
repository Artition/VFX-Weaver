# surface_pattern

`type: "surface_pattern"`

Projects a world-anchored figure (or a texture) onto the terrain behind each pixel, using scene
depth, so it stays fixed to world blocks as you turn and walk. It renders on all supported lines
(Fabric and NeoForge) and **must run at `screen_layer: 0`** - the single scene depth buffer is only
intact below the first-person hand.

`screen_layer` is the only **param** that is structural by meaning: `0` is required for a meaningful
result.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `screen_layer` | float | 0 | Must be `0` (at layer 1+ the depth buffer no longer covers the scene) |
| `tile_scale` | float | 1 | World size of one cell, in blocks (larger = bigger figure) |
| `line_width` | float | — | Numeric override of the structural `pattern.stroke_width` (cell units) |
| `color_r` / `color_g` / `color_b` | float | 1 / 1 / 1 | Pattern colour |
| `opacity` | float | 1 (fades to 0) | Overall strength |
| `fade_radius` | float | 0 | Distance from the anchor at which the pattern fades out, in blocks (`0` = no fade) |
| `normal_mask` | float | 0 | Legacy orientation filter: minimum absolute Y of the surface normal - `0.6` keeps floors/ceilings and excludes walls (`0` = off). Ignored when a `surface` block is present |
| `distort` | float | 0 | World-space sine warp of the pattern coordinate (`0` = off); its phase follows the in-plane coordinate, so it really warps a flat floor/wall |
| `rotation` | float | — | Numeric override of the structural `pattern.rotation`, in degrees (keyframes / `expr` / graph-driven like any param); spins the figure and a texture together |
| `frame` | float | 0 | Sprite-sheet frame index when `pattern.texture.sheet` is set (rounded, wrapped into `0..cols*rows-1`); literal, keyframe, `expr` or graph-driven |
| `texture_tint` | float | 0 | `0` = draw the texture's own RGB; `1` = multiply it by `color_r/g/b`. `opacity` always scales coverage |

## `pattern` (structural)

The figure lives in a top-level structural `pattern` block - strings and ids, never in `params`, so
older builds ignore the whole block. It is the shared shape library used by
[Per-pixel fields](../../datapack/fields.md).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `figure` | string | `circle` | `circle` (`radius`), `ellipse` (`radius_x`/`radius_y`), `rect` (`half_width`/`half_height`, optional `corner_radius`) or `polygon` (`sides` ≥ 3, `radius`) |
| `radius` | float | 0.35 | `circle` / `polygon` radius (cell units) |
| `radius_x` / `radius_y` | float | 0.35 / 0.35 | `ellipse` radii |
| `half_width` / `half_height` | float | 0.25 / 0.25 | `rect` half-extents |
| `corner_radius` | float | 0 | `rect` corner rounding |
| `sides` | float | 6 | `polygon` sides (≥ 3) |
| `center` | array `[x, y, z]` | — | Optional literal world anchor. Without it the anchor is the effect's own world position (a Java-API move, else its first world `position`, else the `pos_x/y/z` binds, else the local player). The camera is never the anchor, so third-person/F5 does not move it. `center_x/center_y/center_z` are **not** pattern fields and are a parse error |
| `rotation` | float | 0 | Figure rotation in the surface plane, degrees. `center.y` anchors the 3-D `fade_radius` distance and the wall projection; on a floor only `center.x`/`center.z` set the cell origin |
| `fill` | string | `solid` | `solid` or `stroke` |
| `stroke_width` | float | 0.05 | Stroke thickness when `fill: stroke` (overridable by `params.line_width`) |
| `softness` | float | 0.01 | Edge softness |
| `repeat` | array `[nx, ny]` | `[1, 1]` | Tiling of the figure inside a cell; each entry `1..64` |

A **grid** is just a figure with `repeat > 1` (or a stroked `rect`); a **ring** is `ellipse` with
`"fill": "stroke"`. To limit the pattern to a region, use the shared
[`mask`](../../datapack/masks.md) block, not a `surface_pattern` field.

## `pattern.texture` (structural, optional)

Replaces the procedural figure with a real texture projected onto the same surface (same faces,
band, fade, `distort` and `opacity`). The texture **is** the figure; an authored `figure` becomes its
mask (`coverage = texture channel x shape coverage`), so a texture with no `figure` is not clipped.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | — (required) | Sprite id (atlas sources) or texture id (standalone), e.g. `minecraft:block/nether_portal`, `minecraft:item/apple`, `minecraft:textures/block/stone`, `mypack:textures/vfx/pentagram` |
| `source` | string | inferred | `block`, `item`, `atlas` or `standalone`. Inferred from `id`: contains `textures/` → `standalone`; path starts `item/` → `item`; starts `block/` → `block`. An ambiguous id is a parse error |
| `atlas` | string | — | The atlas id, required only for `source: "atlas"` (e.g. `minecraft:particles`) and rejected otherwise |
| `channel` | string | `alpha` | Coverage component: `alpha` (transparent texels show nothing), `luminance`, `r`, `g` or `b` |
| `sheet` | array `[cols, rows]` | `[1, 1]` | Sprite-sheet grid; each `1..16` and `cols*rows <= 256`. The displayed cell is the numeric `frame` param |
| `aspect` | string | `preserve` | `preserve` keeps the pixel aspect (one repeat covers `tile_scale` blocks along the longer pixel axis); `stretch` maps the whole square cell to the sprite |

## `surface` (structural, optional)

Selects which faces receive the pattern and an optional world-space band. Without the block the
legacy numeric `normal_mask` is kept exactly as before; with it, `faces` replaces `normal_mask` (the
numeric param is then ignored).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `faces` | array of string | `["up"]` | Face orientations that get the pattern. Tokens `up`, `down`, `north`, `south`, `east`, `west`; axis aliases `x` (= west+east), `y` (= up+down), `z` (= north+south); groups `horizontal` (= up+down), `vertical` (= north+south+east+west), `all`. At most 8 tokens; duplicates collapse |
| `min` | float | −∞ | Inclusive lower bound of the band, **along the fragment's dominant axis**: Y for `up`/`down`, X for `east`/`west`, Z for `north`/`south` |
| `max` | float | +∞ | Inclusive upper bound, same axis as `min` |
| `band_softness` | float | 0 | Half-width in blocks of a soft fade centred on `min` and `max` (`0..4`). A surface exactly on a bound otherwise shimmers, because the reconstructed axis coordinate jitters across the test; `0.05` removes it while keeping the interior solid. A value wider than half the band is clamped to half the band. `0` = the exact hard edge |
| `stitch` | bool | false | Opt-in **planar (top-down) projection**: every face samples `p = world.xz`, so a wall pixel `(x, y, z)` shows the floor pixel at its base `(x, floor_y, z)` - the image's wall-line row extruded vertically. `min`/`max` then apply to world Y on every face. A ceiling (`down`) is excluded. The trade-off: a wall shows a 1D slice (vertically constant colour columns), not a 2D unwrapped image |

## Example

Floor band (`min`/`max` on world Y):

```json
"surface": { "faces": ["up"], "min": 60, "max": 72 }
```

Band along world X on the east/west walls, band along world Z on the north/south walls, and a
floor band whose `min` sits exactly on the ground (its edge faded over `band_softness`):

```json
"surface": { "faces": ["x"], "min": -8, "max": 8 }
```
```json
"surface": { "faces": ["z"], "min": -8, "max": 8 }
```
```json
"surface": { "faces": ["up"], "min": 128, "max": 256, "band_softness": 0.05 }
```

Planar projection continuous across floor and walls:

```json
"surface": { "faces": ["up", "north", "south", "east", "west"], "stitch": true }
```

A block-atlas sprite clipped to a soft circle:

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

A standalone 4x4 sheet that spins (`rotation` keyframes) and pulses (`tile_scale`), its `frame`
graph-driven (`time` → `math:floor`):

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

A procedurally stroked rectangle on a floor band, still using the legacy `normal_mask`:

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

## Notes

- **Projection.** The figure follows the fragment's **dominant world normal**: floors/ceilings keep
  world XZ, while a vertical wall is projected onto its horizontal tangent across and world Y up, so
  the figure reads upright and un-mirrored on each wall. The axis switch is hard (not blended) - a
  seam only shows on genuinely diagonal geometry, where "which wall this is" is ambiguous anyway.
- **Stitch.** Because the planar coordinate never depends on `world.y`, the seam is continuous for
  any anchor height and a vertical corner cannot tear the pattern. A ring reaching a wall becomes two
  vertical stripes from its crossing points; a texture stretches its edge row up the wall.
- **Textures.** An atlas source (`block`/`item`/`atlas`) samples the stitched atlas at the sprite's
  own sub-rect and follows atlas animation (an animated sprite needs no `frame`); `standalone`
  samples a resource-pack texture over `0..1`. Tiling is the figure's `repeat`, never sampler wrap,
  and the sampler clamps to the sprite rect so a repeat cannot bleed into a neighbour. The sampler is
  NEAREST with no mipmaps, so a pixel texture stays crisp. One repeat spans `tile_scale` blocks, so
  one texel spans `tile_scale / pixels` blocks (a 16x16 sprite at `tile_scale: 3` is one texel per
  0.1875 blocks). Sheet frames are row-major, wrapped into `0..cols*rows-1`, each cell inset by half
  a texel. The texture is re-resolved every frame, so `/reload` and resource-pack changes take
  effect.
- **Fail behaviour.** A missing sprite in a valid atlas draws the vanilla missing texture and warns
  once (fail-visible); an unknown atlas draws nothing and warns once (fail-closed); a missing
  standalone PNG is uploaded as the missing texture and drawn, with one warning - never a full-screen
  fill. An authored texture that does not resolve draws nothing, never the procedural figure.
- **Parse errors.** An unknown key or face token, an empty `faces`, more than 8 tokens, a non-finite
  bound, `band_softness` outside `0..4`, `min > max`, an unknown `pattern` key, a bad
  `source`/`channel`/`aspect`, a blank or invalid `id`/`atlas`, `atlas` on a non-atlas source, a bad
  `sheet`, or an `id` whose source cannot be inferred is a per-file parse error. A `surface_pattern`
  whose `positions` contains an entity anchor is also a parse error - the pattern anchor is a world
  point, so the shader would otherwise silently fall back to the player.

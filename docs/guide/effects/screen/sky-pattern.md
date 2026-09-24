# sky_pattern

`type: "sky_pattern"`

Paints a figure (or a texture) on the **sky dome**. It is the sky sibling of
[`surface_pattern`](surface-pattern.md) - the same structural `pattern` block, shape library and
texture addressing - but it only ever touches **far-depth sky pixels**, so it can never paint over
terrain, the first-person hand or the GUI. It renders on all supported lines (Fabric and NeoForge)
and **must run at `screen_layer: 0`**, the layer where the scene depth buffer is intact.

## `sky_mode` - how the dome is addressed

How a pixel's view ray is turned into a pattern coordinate is chosen by the **`sky_mode`** field:

| `sky_mode` | Projection | Use it for |
|---|---|---|
| `patch` **(default)** | One **gnomonic** (tangent-plane) decal at the authored anchor - a local chart | A figure or image at one spot in the sky |
| `fill` | The **whole sphere** by three orthographic charts blended with a sharpened partition of unity ("triplanar on a sphere") | Content that must cover the whole sky (cracks, a full-sky fill) |
| `dome` | The original single **equirectangular** chart (legacy) | Existing content only - **do not author new content in it** |

The first release projected through one global equirectangular chart (`dome`). A single chart cannot
cover a sphere cleanly: `u` is undefined at the poles, so tiling there has infinite frequency and
the pattern winds into a **funnel at the zenith** - the worst place, because that is where players
look - and `u` has to wrap somewhere (±180° yaw), leaving a visible **seam / mirror axis**. Those
are properties of the chart, not formula bugs, so `patch` and `fill` replace the addressing with an
**atlas of local charts plus a smooth partition of unity** instead of trying to patch equirect.

- **`patch`** projects the ray onto the tangent plane at the anchor. A tangent plane covers one
  hemisphere, so a pixel on or behind the decal horizon (`dot(dir, anchor) <= 0`) is **cleanly
  discarded** - no sampler wrap, no edge-texel smear, no pole convergence. In-plane distortion is
  `1/cos(angle from the anchor)` (~1.41 at 45°, ~2 at 60°), so keep `tile_scale <= 1.0` for clean
  decals.
- **`fill`** tiles each of the three orthographic charts and blends them with a sharpened partition
  of unity (fixed sharpness 8). Within about 40° of a chart pole - the zenith included - a single
  chart has weight > 0.9, so there is **no pole convergence and no seam anywhere**. The charts'
  **coverage and colour** are blended (never their UVs - they are incomparable frames), and the
  colour is renormalised by the blended coverage so texture texels stay saturated inside the
  crossfade ribbons. `repeat` tiling lives inside each chart.
- **`dome`** is the legacy path, kept byte-for-byte so existing definitions render exactly as
  before. It still has the inherent pole funnel and the north seam.

A full skybox **cube** (six authored faces, not a tiling) is a separate future feature; `fill` is
the whole-sky tiling mode today.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `screen_layer` | float | 0 | Must be `0` (at layer 1+ the depth buffer no longer covers the scene) |
| `sky_mode` | string | `patch` | Projection: `patch` (gnomonic decal), `fill` (three-chart whole sphere) or `dome` (legacy equirect). Case-insensitive; an unknown value is a parse error. Set it explicitly |
| `anchor_yaw` | float | 0 | Dome yaw of the pattern centre, in degrees (yaw `0` = south, `90` = west, `±180` = north). Animatable. In `fill` mode it is a tiling **phase shift**, not a position |
| `anchor_pitch` | float | 0 | Dome pitch of the pattern centre, in degrees (pitch `-90` = straight up, `0` = horizon, `90` = straight down). Animatable. In `fill` mode a tiling **phase shift** |
| `dome_rotation` | float | 0 | Rotates the whole image about the world Y axis, in degrees, before it is projected (lock an image to the rotating star sphere). Animatable |
| `tile_scale` | float | 1 | Size of one cell. In `patch` mode it is `tan(half the decal's angular size)` (0.50 ≈ 26.6°, 0.577 = 30°, 1.0 = 45°); in `fill` mode it is the tile half-size in units where 1.0 = 90° from the chart pole. Larger = bigger cell |
| `line_width` | float | — | Numeric override of the structural `pattern.stroke_width` (cell units) |
| `color_r` / `color_g` / `color_b` | float | 1 / 1 / 1 | Pattern colour |
| `opacity` | float | 1 (fades to 0) | Overall strength |
| `distort` | float | 0 | Sine warp of the pattern coordinate (`0` = off). In `patch`/`fill` it warps the cell coordinate; in `dome` it warps the dome UV, as before |
| `rotation` | float | — | Numeric override of the structural `pattern.rotation`, in degrees; spins the figure and a texture together |
| `frame` | float | 0 | Sprite-sheet frame index when `pattern.texture.sheet` is set (rounded, wrapped into `0..cols*rows-1`); literal, keyframe, `expr` or graph-driven |
| `texture_tint` | float | 0 | `0` = draw the texture's own RGB; `1` = multiply it by `color_r/g/b`. `opacity` always scales coverage |

In `patch` and `fill` modes the mapping is **world-fixed**; use `dome_rotation` to follow the sky's
own rotation. The anchor frame matches the local equirect axes (`u`+ = increasing yaw, `v`+ =
increasing pitch), so a figure keeps the orientation it had in legacy mode.

## `pattern` (structural)

The figure lives in a top-level structural `pattern` block - the same shared shape library as
[`surface_pattern`](surface-pattern.md) and [Per-pixel fields](../../datapack/fields.md). All the
figure fields are identical (`figure`, `radius`, `radius_x`/`radius_y`, `half_width`/`half_height`,
`corner_radius`, `sides`, `rotation`, `fill`, `stroke_width`, `softness`, `repeat`); see the
[`surface_pattern` pattern table](surface-pattern.md#pattern-structural) for the full list.

The one difference: a `pattern.center` **world anchor is ignored** by `sky_pattern` - the anchor is
the dome position `anchor_yaw`/`anchor_pitch`, not a point in the world.

## `pattern.texture` (structural, optional)

Replaces the procedural figure with a real texture drawn on the same dome cell. The texture **is**
the figure; an authored `figure` becomes its mask (`coverage = texture channel x shape coverage`).
Every field is the same as [`surface_pattern`](surface-pattern.md#patterntexture-structural-optional)
(`id`, `source`, `atlas`, `channel`, `sheet`, `aspect`); an atlas source (`block`/`item`/`atlas`)
samples the sprite's own sub-rect, a `standalone` source samples a resource-pack texture over
`0..1`. A `sheet` selects a cell and the numeric `frame` param animates it.

## Examples

Nine red dots in one spot - a `patch` decal with a `circle` figure and `repeat: [3, 3]` (the
`repeat` tiles inside the one decal, so the nine dots stay together; no texture needed):

```json
{
	"type": "sky_pattern",
	"duration": 120, "persistent": true, "fade_ticks": 20,
	"params": {
		"screen_layer": 0, "sky_mode": "patch", "anchor_pitch": -25.0,
		"tile_scale": 0.9, "opacity": { "start": 0.0, "end": 1.0 },
		"color_r": 1.0, "color_g": 0.0, "color_b": 0.0
	},
	"pattern": {
		"figure": "circle", "radius": 0.12, "fill": "solid", "softness": 0.01, "repeat": [3, 3]
	}
}
```

A texture tiled across the whole sky - `fill` mode, where `repeat` tiles inside each chart:

```json
{
	"type": "sky_pattern",
	"duration": 200, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "sky_mode": "fill", "tile_scale": 1.5, "opacity": 0.9, "color_b": 0.8,
		"rotation": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 200, "value": 360, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [6, 3],
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha" }
	}
}
```

An animated sheet (`frame` keyframes) as a decal:

```json
{
	"type": "sky_pattern",
	"duration": -1, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "sky_mode": "patch", "anchor_pitch": -40.0, "tile_scale": 0.8, "opacity": 0.95,
		"frame": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 120, "value": 15, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [2, 2],
		"texture": { "id": "mypack:textures/vfx/cracks", "source": "standalone", "sheet": [4, 4], "channel": "alpha" }
	}
}
```

To shoot a **beam** from a point on the sky, pair the pixels with
[`light_beam`](../world/light-beam.md) aimed at that dome direction (`dir_x`/`dir_y`/`dir_z`) - the
pixels are a layer-0 post pass, the beam is world geometry.

## Notes

- **Sky only.** The pass is gated on the depth sky test: a non-sky pixel is passed through
  untouched, so a `sky_pattern` can never tint terrain, the hand or the GUI. A pack that leaves no
  trustworthy far depth makes the gate never match - the effect no-ops (fail-closed), never a
  full-screen fill.
- **`patch` limits.** Keep `tile_scale <= 1.0` for clean decals; past the decal horizon the pixel is
  discarded (a hard edge at the tangent-plane horizon, which is the intended "decal ends here").
- **`fill` limits.** The per-chart density varies by up to ~1.7x between a chart pole and a chart
  diagonal, and there are soft crossfade ribbons along the `|x|=|y|`, `|y|=|z|`, `|z|=|x|` great
  circles (roughly ±8°). For chaotic content such as cracks the ribbons read as slightly denser
  cracks, not as a cut or a seam.
- **`dome` is legacy.** It has an inherent pole funnel and a north seam; keep existing content on it,
  but author new content in `patch` or `fill`.
- **No depth reconstruction, no normals.** Unlike `surface_pattern` there is no surface selection:
  `normal_mask`, a `surface` block and `fade_radius` do not apply. Restrict the region with a
  [mask](../../datapack/masks.md) (`space: "dome"` or the `sky` leaf) if needed.
- **Textures.** Same resolver as `surface_pattern`: a missing sprite in a valid atlas draws the
  vanilla missing texture and warns once (fail-visible); an unknown atlas or unreadable standalone
  PNG draws nothing and warns once (fail-closed). The sampler is NEAREST with no mipmaps, so a
  pixel texture stays crisp. The texture is re-resolved every frame, so `/reload` takes effect.
- **Parse errors.** The same `pattern`/`pattern.texture` validation as `surface_pattern` (unknown
  key, bad `source`/`channel`/`aspect`/`sheet`, blank or invalid `id`/`atlas`), plus an unknown
  `sky_mode` string.

## Code

```
/vfx play vfx_demos:show_sky_pattern
```

A soft stroked ring at a spot in the sky (`patch`), gently animated. Its datapack definition:

```json
{
	"type": "sky_pattern",
	"duration": 200, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 12,
	"params": {
		"screen_layer": 0,
		"sky_mode": "patch", "anchor_yaw": 0.0, "anchor_pitch": -30.0,
		"tile_scale": { "keyframes": [ { "time": 0, "value": 0.55 }, { "time": 100, "value": 0.8 }, { "time": 200, "value": 0.55 } ] },
		"color_r": 0.35, "color_g": 0.85, "color_b": 1.0,
		"opacity": { "keyframes": [ { "time": 0, "value": 0.4 }, { "time": 100, "value": 0.9 }, { "time": 200, "value": 0.4 } ] },
		"dome_rotation": { "keyframes": [ { "time": 0, "value": 0.0 }, { "time": 200, "value": 360.0, "easing": "linear" } ] }
	},
	"pattern": {
		"figure": "ellipse", "fill": "stroke", "radius_x": 0.42, "radius_y": 0.42,
		"stroke_width": 0.03, "softness": 0.03, "repeat": [1, 1]
	}
}
```

## Variants

### `show_sky_pattern_texture`

```
/vfx play vfx_demos:show_sky_pattern_texture
```

A vanilla block sprite tiled inside a `patch` decal:

```json
{
	"type": "sky_pattern",
	"duration": 200, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 12,
	"params": {
		"screen_layer": 0, "sky_mode": "patch", "anchor_yaw": 0.0, "anchor_pitch": 0.0,
		"tile_scale": { "keyframes": [ { "time": 0, "value": 0.7 }, { "time": 100, "value": 1.0 }, { "time": 200, "value": 0.7 } ] },
		"opacity": 0.9, "distort": 0.0,
		"rotation": { "keyframes": [ { "time": 0, "value": 0.0 }, { "time": 200, "value": 360.0, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [4, 2],
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha", "aspect": "preserve" }
	}
}
```

### `sky_cracks`

```
/vfx play vfx_demos:sky_cracks
```

The whole-sky case: a `fill` with an integer `repeat` and an animated sprite sheet, so the cracks
cover the entire sky with no pole funnel:

```json
{
	"type": "sky_pattern",
	"duration": 120, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 10,
	"params": {
		"screen_layer": 0, "sky_mode": "fill", "tile_scale": 1.5, "opacity": 0.95,
		"color_r": 0.9, "color_g": 0.9, "color_b": 1.0, "texture_tint": 1.0,
		"frame": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 120, "value": 15, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [6, 3],
		"texture": { "id": "minecraft:block/cracked_stone_bricks", "source": "block", "sheet": [4, 4], "channel": "luminance", "aspect": "preserve" }
	}
}
```

To see the old look again, change `sky_mode` to `"dome"` in any of these.

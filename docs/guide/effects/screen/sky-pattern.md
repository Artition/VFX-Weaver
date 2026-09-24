# sky_pattern

`type: "sky_pattern"`

Paints a figure (or a texture) on the **sky dome**: the pixel's view ray is reconstructed, mapped to
an equirectangular dome coordinate, and the pattern is drawn there. It is the sky sibling of
[`surface_pattern`](surface-pattern.md) - the same structural `pattern` block, shape library and
texture addressing - but it only ever touches **far-depth sky pixels**, so it can never paint over
terrain, the first-person hand or the GUI. It renders on all supported lines (Fabric and NeoForge)
and **must run at `screen_layer: 0`**, the layer where the scene depth buffer is intact.

`sky_pattern` is stage S3 of the [skybox design](https://github.com/Artition/VFX-Weaver): the
celestial anchors (`"anchor": "sun"`/`"moon"`/`"stars"`, which track the real body) and the
`sky_layer` selector arrive in later stages. Today the anchor is always a fixed dome position,
authored as `anchor_yaw`/`anchor_pitch`.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `screen_layer` | float | 0 | Must be `0` (at layer 1+ the depth buffer no longer covers the scene) |
| `anchor_yaw` | float | 0 | Dome yaw of the pattern centre, in degrees (yaw `0` = south, `90` = west, `±180` = north). Animatable |
| `anchor_pitch` | float | 0 | Dome pitch of the pattern centre, in degrees (pitch `-90` = straight up, `0` = horizon, `90` = straight down). Animatable |
| `dome_rotation` | float | 0 | Rotates the whole dome image about the world Y axis, in degrees, before it is mapped (lock an image to the rotating star sphere). Animatable |
| `tile_scale` | float | 1 | Dome-UV size of one cell (larger = bigger figure). One cell spans `tile_scale` of the 0..1 dome height |
| `line_width` | float | — | Numeric override of the structural `pattern.stroke_width` (cell units) |
| `color_r` / `color_g` / `color_b` | float | 1 / 1 / 1 | Pattern colour |
| `opacity` | float | 1 (fades to 0) | Overall strength |
| `distort` | float | 0 | Dome-space sine warp of the pattern coordinate (`0` = off) |
| `rotation` | float | — | Numeric override of the structural `pattern.rotation`, in degrees; spins the figure and a texture together |
| `frame` | float | 0 | Sprite-sheet frame index when `pattern.texture.sheet` is set (rounded, wrapped into `0..cols*rows-1`); literal, keyframe, `expr` or graph-driven |
| `texture_tint` | float | 0 | `0` = draw the texture's own RGB; `1` = multiply it by `color_r/g/b`. `opacity` always scales coverage |

The dome map is **equirectangular** (the familiar "painted on a sphere" look): `u` is yaw and wraps
at the north seam, `v` is pitch and clamps at the poles, so a shape authored near a pole is
stretched in `u`. That distortion is inherent to the projection, not a bug. The mapping is
**world-fixed**; use `dome_rotation` to follow the sky's own rotation.

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

Nine red dots on the dome - a stroked/filled figure with `repeat: [3, 3]` (no texture needed):

```json
{
	"type": "sky_pattern",
	"duration": 120, "persistent": true, "fade_ticks": 20,
	"params": {
		"screen_layer": 0, "tile_scale": 0.75, "opacity": { "start": 0.0, "end": 1.0 },
		"color_r": 1.0, "color_g": 0.0, "color_b": 0.0
	},
	"pattern": {
		"figure": "circle", "radius": 0.12, "fill": "solid", "softness": 0.01, "repeat": [3, 3]
	}
}
```

A texture tiled across the dome with a slight rotation and pulse:

```json
{
	"type": "sky_pattern",
	"duration": 200, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "tile_scale": 1.5, "opacity": 0.9, "color_b": 0.8,
		"rotation": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 200, "value": 360, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [4, 2],
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha" }
	}
}
```

An animated sheet (`frame` keyframes) - the mechanism behind animated sky cracks:

```json
{
	"type": "sky_pattern",
	"duration": -1, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "tile_scale": 1.5, "opacity": 0.95,
		"frame": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 120, "value": 15, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [6, 3],
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
- **No depth reconstruction, no normals.** Unlike `surface_pattern` there is no surface selection:
  `normal_mask`, a `surface` block and `fade_radius` do not apply. Restrict the region with a
  [mask](../../datapack/masks.md) (`space: "dome"` or the `sky` leaf) if needed.
- **Textures.** Same resolver as `surface_pattern`: a missing sprite in a valid atlas draws the
  vanilla missing texture and warns once (fail-visible); an unknown atlas or unreadable standalone
  PNG draws nothing and warns once (fail-closed). The sampler is NEAREST with no mipmaps, so a
  pixel texture stays crisp. The texture is re-resolved every frame, so `/reload` takes effect.
- **Parse errors.** The same `pattern`/`pattern.texture` validation as `surface_pattern` (unknown
  key, bad `source`/`channel`/`aspect`/`sheet`, blank or invalid `id`/`atlas`).

## Code

```
/vfx play vfx_demos:show_sky_pattern
```

A soft stroked ring on the dome, gently animated. Its datapack definition:

```json
{
	"type": "sky_pattern",
	"duration": 200, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 12,
	"params": {
		"screen_layer": 0,
		"anchor_yaw": 0.0, "anchor_pitch": -30.0,
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

A vanilla block sprite tiled across the dome:

```json
{
	"type": "sky_pattern",
	"duration": 200, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 12,
	"params": {
		"screen_layer": 0, "anchor_yaw": 0.0, "anchor_pitch": 0.0,
		"tile_scale": { "keyframes": [ { "time": 0, "value": 1.2 }, { "time": 100, "value": 1.8 }, { "time": 200, "value": 1.2 } ] },
		"opacity": 0.9, "distort": 0.0,
		"rotation": { "keyframes": [ { "time": 0, "value": 0.0 }, { "time": 200, "value": 360.0, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [4, 2],
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha", "aspect": "preserve" }
	}
}
```

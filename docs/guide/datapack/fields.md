# Per-pixel fields


A **field** makes one numeric input vary *per pixel* instead of once per frame. It is written as an `inputs` entry with a `"field"` object — like a [value graph](graph.md) input, but evaluated inside the effect's shader instead of on the CPU:

```jsonc
"inputs": {
	"intensity": { "field": "noise", "space": "screen", "scale": 18.0, "octaves": 3 }
}
```

Fields are accepted only on **field-capable inputs**; today that is `dent.intensity`, a multiplier applied to the dent's `strength`, and `color_grade.tint_r`, the red tint channel (both neutral `1.0`, so an effect without a field is unchanged). Every other input still takes a number, an animation, a binding, an expression or `{ "from": "<node>" }`. A field on any other input or effect is a per-file parse error naming the input. A field-driven input fades with the effect's weight: at weight 0 it evaluates to its neutral value (bit-for-bit identical to an input without a field), at weight 1 to the full field, with a monotonic blend in between. The whole block is additive: a definition without `inputs`/`field` behaves exactly as before, and a mod that does not know fields ignores them entirely.

A field is either a **function leaf** (`"field": "<fn>"`) or a **composition** (`"op"`).

#### Functions

Every function has a fixed output type (`float`, `vec2` or `vec3`). `space` applies only to the *spatial* functions (`noise`, `shape`, `gradient`, `curve`, `texture`) and defaults to `world`.

| `field` | Params (default) | Result |
|---|---|---|
| `constant` | `value` (1) | The literal value — a scalar source for composition |
| `noise` | `scale` (1), `octaves` (1), `gain` (0.5), `lacunarity` (2) | Fractal 3D value noise, roughly `0..1` (`octaves` is an integer) |
| `shape` | see the shape table below | `[0,1]` coverage of a primitive |
| `gradient` | `angle` (0), `offset` (0), `scale` (1), `softness` (0) | A directional ramp along `angle` (radians) |
| `curve` | `scale` (1); structural `points` (required) | 1-D transfer: screen — `uv.x × scale`; world — `fract(world.x × scale)`, sampled through the inline curve |
| `texture` | `scale_x` (1), `scale_y` (1), `offset_x` (0), `offset_y` (0); structural `texture`, `channel` | A texture sample at the space coordinate |
| `depth` | `near` (0), `far` (1) | Linearized scene depth |
| `depth_gradient` | `near` (0), `far` (1) | Screen-space depth gradient magnitude, `0..1` |
| `normal_facing` | `axis_x` (0), `axis_y` (1), `axis_z` (0), `threshold` (0.5) | How much the reconstructed surface normal faces the given axis |
| `screen_uv` | — | The screen UV as `vec2` |
| `world_pos` | — | The reconstructed world position as `vec3` |

**`scale` is a frequency multiplier, not a size.** Every spatial function multiplies its sampling coordinate by `scale` (noise: the noise input; gradient: the projection; curve/texture: the coordinate), so a **larger** `scale` means **finer** detail and a smaller `scale` means larger, smoother patches. `tint_field_demo`'s `scale: 3.0` is therefore deliberately low, producing big colour patches.

A `curve`'s `points` are a non-empty array of `{ "time": <number>, "value": <number> }` with strictly ascending times, up to 8 — structural, not animatable. `texture`'s `texture` is a resource id (e.g. `"minecraft:textures/block/stone"`) and `channel` is `r`, `g`, `b`, `a` or `luminance`; omitting `channel` yields the full `vec3`. At most **one texture leaf** is allowed per input.

#### The shared shape set

`shape` (a `float` coverage in `[0,1]`) is the single shape implementation:

| Param | Default | Meaning |
|---|---|---|
| `primitive` | `circle` | `circle`, `ellipse`, `rect` or `polygon` |
| `center` | `[0.5, 0.5]` | Centre in the space coordinate (two elements) |
| `rotation` | 0 | Rotation in degrees |
| `radius` | 0.35 | `circle`/`polygon` radius |
| `radius_x`, `radius_y` | 0.35, 0.35 | `ellipse` radii |
| `half_width`, `half_height` | 0.25, 0.25 | `rect` half-extents |
| `corner_radius` | 0 | `rect` corner rounding |
| `sides` | 6 | `polygon` sides (≥ 3, integer) |
| `fill` | `solid` | `solid` or `stroke` |
| `stroke_width` | 0.05 | Stroke thickness when `fill: stroke` |
| `softness` | 0.01 | Edge softness |
| `repeat` | `[1, 1]` | `[nx, ny]` tiling — a grid |

`center` and `repeat` are two-element arrays whose elements are numbers or `{ "from": "<node>" }`. **`grid` is not a function: it is a `shape` with `repeat`. A `ring` is not a function: it is `shape` with `primitive: "ellipse"` and `fill: "stroke"`.**

These primitives — plus the 3D `sphere`/`box` helpers used wherever a 3D coordinate exists — are a **shared** implementation: masks and `surface_pattern` consume the same `field.glsl` (`vfx_shape_sdf` for the raw distance, `vfx_shape_coverage` for the `[0,1]` coverage) and never re-implement shapes.

#### Space, composition and caps

`space: "screen"` evaluates in screen UV; `space: "world"` reconstructs a world coordinate from the scene depth. `depth`, `depth_gradient`, `normal_facing` and `world_pos` always need that depth. **Depth/world fields only produce meaningful values at screen layer 0** (`"screen_layer": 0`); at any other layer no valid depth is bound, the field falls back to the neutral value (`1.0`), and a field that needs depth logs a once-per-definition warning. Screen-space fields work at every layer.

Composition combines fields, bounded like a small tree:

```jsonc
"intensity": {
	"op": "multiply",
	"a": { "field": "shape", "space": "screen", "primitive": "circle", "radius": 0.4 },
	"b": { "field": "noise", "space": "screen", "scale": 6.0, "octaves": 3 }
}
```

`op` is `multiply`, `add`, `subtract`, `mix`, `min` or `max`; `a` and `b` are required, and `mix` adds a `factor` (a `float` field). A `float` broadcasts against a vector; equal vector types combine componentwise; `vec2` against `vec3` is a parse error. Caps — a violation fails **that file only**, naming the input, function and parameter: composition depth 3, 4 leaves, 8 nodes, 8 curve points, one texture leaf per input.

Any numeric field parameter is a number or `{ "from": "<node>" }` (an integer parameter is rounded after evaluation). `field`, `op`, `space`, `channel`, `texture`, `points`, `primitive` and `fill` are structural and not animatable.

**Reference examples.** The built-in `vfxweaver:dent_field_demo` is a dent whose strength is mottled by screen-space noise — `/vfx play vfxweaver:dent_field_demo` — and `vfxweaver:tint_field_demo` drives `color_grade.tint_r` from large screen-space noise patches, so the whole screen tints between red and cyan — `/vfx play vfxweaver:tint_field_demo`:

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

The tint demo (`fade_ticks: 0` keeps the loop from pulsing; a field-driven input fades with the effect weight like any other input (see [fade](#space-composition-and-caps)), and `scale` is the sampling frequency, so a small value gives large patches):

```json
{
	"type": "color_grade",
	"duration": 200,
	"loop": true,
	"persistent": true,
	"fade_ticks": 0,
	"params": {
		"saturation": 1.0,
		"contrast": 1.0,
		"brightness": 1.0,
		"tint_r": 4.0,
		"tint_g": 1.0,
		"tint_b": 1.0,
		"screen_layer": 1
	},
	"inputs": {
		"tint_r": {
			"field": "noise",
			"space": "screen",
			"scale": 3.0,
			"octaves": 3,
			"gain": 0.5,
			"lacunarity": 2.0
		}
	}
}
```

## Showcase

### `show_tint_field_demo`

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/tint_field_demo.mp4" type="video/mp4"></video>

```
/vfx play vfx_demos:show_tint_field_demo
```

Its datapack definition:

```json
{
	"type": "color_grade",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 0,
	"params": {
		"saturation": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.6
				},
				{
					"time": 100,
					"value": 1.0
				},
				{
					"time": 200,
					"value": 0.6
				}
			]
		},
		"contrast": 1.0,
		"brightness": 1.0,
		"tint_r": 4.0,
		"tint_g": 1.0,
		"tint_b": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.0
				},
				{
					"time": 100,
					"value": 1.5
				},
				{
					"time": 200,
					"value": 1.0
				}
			]
		},
		"screen_layer": 1
	},
	"inputs": {
		"tint_r": {
			"field": "noise",
			"space": "screen",
			"scale": 3.0,
			"octaves": 3,
			"gain": 0.5,
			"lacunarity": 2.0
		}
	}
}
```

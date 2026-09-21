# dent

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/dent.mp4" type="video/mp4"></video>

`type: "dent"`

A local "dent" (lens warp) around a point, or along a segment in line mode.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `strength` | float | 0.6 (fades to 0) | Warp strength; positive pulls in, negative pushes out |
| `radius` | float | 0.25 | Dent size as a fraction of the screen |
| `center_x`, `center_y` | float | 0.5, 0.5 | Dent centre in UV (0..1) |
| `line_mode` | float | 0 | 1 = segment mode (below) |
| `x0`, `y0`, `x1`, `y1` | float | 0..1 UV | Segment ends for line mode; bind them to the world via `bind: screen_x` / `screen_y` |

## Example

```json
{
	"type": "dent",
	"duration": 60,
	"params": { "strength": 0.6, "radius": 0.25, "center_x": 0.5, "center_y": 0.5, "line_mode": 0, "x0": 0 }
}
```

```
/vfx play vfxweaver:dent {[strength:0.8],[radius:0.3]}
```

## Code

```
/vfx play vfx_demos:show_dent
```

Its datapack definition:

```json
{
	"type": "dent",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"strength": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.45
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"radius": 0.3,
		"center_x": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.35
				},
				{
					"time": 80,
					"value": 0.65
				},
				{
					"time": 160,
					"value": 0.35
				}
			]
		},
		"center_y": 0.5,
		"line_mode": 0.0
	}
}
```

## Variants

### `show_dent_field_demo`

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/dent_field_demo.mp4" type="video/mp4"></video>

```
/vfx play vfx_demos:show_dent_field_demo
```

Its datapack definition:

```json
{
	"type": "dent",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 10,
	"params": {
		"strength": 0.6,
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.3
				},
				{
					"time": 100,
					"value": 0.55
				},
				{
					"time": 200,
					"value": 0.3
				}
			]
		},
		"center_x": 0.5,
		"center_y": 0.5,
		"screen_layer": 1
	},
	"inputs": {
		"intensity": {
			"field": "noise",
			"space": "screen",
			"scale": 14.0,
			"octaves": 3,
			"gain": 0.5,
			"lacunarity": 2.0
		}
	}
}
```

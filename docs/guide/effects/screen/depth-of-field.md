# depth_of_field

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/depth_of_field.mp4" type="video/mp4"></video>

`type: "depth_of_field"`

Screen tilt-shift: a sharp band, blur away from it.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.5 (fades to 0) | Blur strength outside the sharp band |
| `focus_center` | float | 0.5 | Sharp band centre, UV Y (0.5 = screen middle) |
| `focus_range` | float | 0.15 | Sharp band half-width in UV |

## Example

```json
{
	"type": "depth_of_field",
	"duration": 60,
	"params": { "intensity": 0.5, "focus_center": 0.5, "focus_range": 0.15 }
}
```

```
/vfx play vfxweaver:depth_of_field
```

## Code

```
/vfx play vfx_demos:show_depth_of_field
```

Its datapack definition:

```json
{
	"type": "depth_of_field",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.65
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"focus_center": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.3
				},
				{
					"time": 80,
					"value": 0.7
				},
				{
					"time": 160,
					"value": 0.3
				}
			]
		},
		"focus_range": 0.15
	}
}
```

# color_grade

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/color_grade.mp4" type="video/mp4"></video>

`type: "color_grade"`

Colour grading: saturation, contrast, brightness and a colour tint.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `saturation` | float | 0.7 (fades to 1) | 0 = grayscale, 1 = neutral, >1 = oversaturated |
| `contrast` | float | 1.05 (fades to 1) | 1 = neutral; <1 = flatter, >1 = harsher |
| `brightness` | float | 1 | 1 = neutral; 0 = black |
| `tint_r/g/b` | float | 1 / 0.9 / 1 (fade to 1) | Per-channel multiplier; 1 = neutral |

## Example

```json
{
	"type": "color_grade",
	"duration": 60,
	"params": { "saturation": 0.7, "contrast": 1.05, "brightness": 1, "tint_r": 1, "tint_g": 0.9, "tint_b": 1 }
}
```

```
/vfx play vfxweaver:color_grade
```

## Code

```
/vfx play vfx_demos:show_color_grade
```

Its datapack definition:

```json
{
	"type": "color_grade",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"saturation": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.0
				},
				{
					"time": 80,
					"value": 0.25
				},
				{
					"time": 160,
					"value": 1.0
				}
			]
		},
		"contrast": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.0
				},
				{
					"time": 80,
					"value": 1.12
				},
				{
					"time": 160,
					"value": 1.0
				}
			]
		},
		"brightness": 1.0,
		"tint_r": 1.0,
		"tint_g": 1.0,
		"tint_b": 1.0
	}
}
```

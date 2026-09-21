# vignette

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/vignette.mp4" type="video/mp4"></video>

`type: "vignette"`

Darkens/colours the screen edges.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.7 (fades to 0) | Edge darkening strength |
| `color_r/g/b` | float | 0 / 0 / 0 | Edge colour (black by default) |

## Example

```json
{
	"type": "vignette",
	"duration": 60,
	"params": { "intensity": 0.7, "color_r": 0, "color_g": 0, "color_b": 0 }
}
```

```
/vfx play vfxweaver:vignette {[intensity:1]}
```

## Code

```
/vfx play vfx_demos:show_vignette
```

Its datapack definition:

```json
{
	"type": "vignette",
	"duration": 120,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 10,
	"params": {
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 60,
					"value": 0.95
				},
				{
					"time": 120,
					"value": 0.0
				}
			]
		},
		"color_r": 0.0,
		"color_g": 0.0,
		"color_b": 0.0
	}
}
```

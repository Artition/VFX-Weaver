# letterbox

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/letterbox.mp4" type="video/mp4"></video>

`type: "letterbox"`

Cinematic bars at the top and bottom of the screen.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `height` | float | 0.12 (fades to 0) | Bar height as a fraction of the screen half-height (max 0.5) |
| `color_r/g/b` | float | 0 / 0 / 0 | Bar colour (black by default) |

## Example

```json
{
	"type": "letterbox",
	"duration": 60,
	"params": { "height": 0.12, "color_r": 0, "color_g": 0, "color_b": 0 }
}
```

```
/vfx play vfxweaver:letterbox {[height:0.2]}
```

## Code

```
/vfx play vfx_demos:show_letterbox
```

Its datapack definition:

```json
{
	"type": "letterbox",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"height": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.18
				},
				{
					"time": 160,
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

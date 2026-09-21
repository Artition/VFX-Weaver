# screen_flash

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/screen_flash.mp4" type="video/mp4"></video>

`type: "screen_flash"`

Fullscreen colour overlay (flash).

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `alpha` | float | 0.8 (fades to 0) | Overlay opacity |
| `color_r/g/b` | float | 1 / 1 / 1 | Flash colour (white by default) |

## Example

```json
{
	"type": "screen_flash",
	"duration": 60,
	"params": { "alpha": 0.8, "color_r": 1, "color_g": 1, "color_b": 1 }
}
```

```
/vfx play vfxweaver:screen_flash {[color_r:1],[color_g:0],[color_b:0],[alpha:1]}
```

## Code

```
/vfx play vfx_demos:show_screen_flash
```

Its datapack definition:

```json
{
	"type": "screen_flash",
	"duration": 120,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 10,
	"params": {
		"alpha": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 60,
					"value": 0.45
				},
				{
					"time": 120,
					"value": 0.0
				}
			]
		},
		"color_r": 1.0,
		"color_g": 0.85,
		"color_b": 0.6
	}
}
```

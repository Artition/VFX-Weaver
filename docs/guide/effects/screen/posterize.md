# posterize

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/posterize.mp4" type="video/mp4"></video>

`type: "posterize"`

Posterization: reduces the number of colours on screen, clean quantization without dithering.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `strength` | float | 0.25 (fades to 0) | 0 = off, 1 = only 2 levels per channel |

## Example

```json
{
	"type": "posterize",
	"duration": 60,
	"params": { "strength": 0.25 }
}
```

```
/vfx play vfxweaver:posterize {[strength:0.6]}
```

## Code

```
/vfx play vfx_demos:show_posterize
```

Its datapack definition:

```json
{
	"type": "posterize",
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
					"value": 0.5
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		}
	}
}
```

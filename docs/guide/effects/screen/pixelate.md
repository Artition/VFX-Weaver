# pixelate

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/pixelate.mp4" type="video/mp4"></video>

`type: "pixelate"`

Pixelation.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `cell_size` | float | 0.012 (fades to 0.0005) | Cell size as a fraction of the screen (0.012 ~= 23px on 1080p) |

## Example

```json
{
	"type": "pixelate",
	"duration": 60,
	"params": { "cell_size": 0.012 }
}
```

```
/vfx play vfxweaver:pixelate {[cell_size:0.03]}
```

## Code

```
/vfx play vfx_demos:show_pixelate
```

Its datapack definition:

```json
{
	"type": "pixelate",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"cell_size": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0005
				},
				{
					"time": 100,
					"value": 0.02
				},
				{
					"time": 200,
					"value": 0.0005
				}
			]
		}
	}
}
```

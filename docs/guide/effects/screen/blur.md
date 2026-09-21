# blur

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/blur.mp4" type="video/mp4"></video>

`type: "blur"`

Two-pass adaptive Gaussian blur.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 4 (fades to 0) | Blur radius in pixels |

## Example

```json
{
	"type": "blur",
	"duration": 60,
	"params": { "radius": 4 }
}
```

```
/vfx play vfxweaver:blur {[radius:10]}
```

## Code

```
/vfx play vfx_demos:show_blur
```

Its datapack definition:

```json
{
	"type": "blur",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 9.0
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

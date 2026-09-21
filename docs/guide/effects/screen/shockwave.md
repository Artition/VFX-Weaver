# shockwave

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/shockwave.mp4" type="video/mp4"></video>

`type: "shockwave"`

A single refraction ring ripples outward from a point.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `center_x/y` | float | 0.5 / 0.5 | Wave origin in UV |
| `radius` | float | 0.4 -> 1.5 | Current ring radius, screen-height fractions (animate 0 -> 1.5) |
| `width` | float | 0.15 | Ring thickness |
| `amplitude` | float | 0.12 (fades to 0) | UV displacement at the ring crest |
| `sharpness` | float | 1.5 | Ring profile (1 = smooth sine ripple, 4 = hard glassy ring) |

## Example

```json
{
	"type": "shockwave",
	"duration": 60,
	"params": { "center_x": 0.5, "center_y": 0.5, "radius": 0.4, "width": 0.15, "amplitude": 0.12, "sharpness": 1.5 }
}
```

```
/vfx play vfxweaver:shockwave {[radius:0.8]}
```

## Code

```
/vfx play vfx_demos:show_shockwave
```

Its datapack definition:

```json
{
	"type": "shockwave",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"center_x": 0.5,
		"center_y": 0.5,
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.2
				},
				{
					"time": 80,
					"value": 1.2
				},
				{
					"time": 160,
					"value": 0.2
				}
			]
		},
		"width": 0.12,
		"amplitude": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.05
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"sharpness": 1.5
	}
}
```

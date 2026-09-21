# vortex

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/vortex.mp4" type="video/mp4"></video>

`type: "vortex"`

Swirls pixels into a funnel around a point.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `strength` | float | 2.5 (fades to 0) | Max swirl angle in radians; sign = direction |
| `radius` | float | 0.5 | Funnel radius as a screen fraction |
| `center_x`, `center_y` | float | 0.5, 0.5 | Funnel centre in UV |

## Example

```json
{
	"type": "vortex",
	"duration": 60,
	"params": { "strength": 2.5, "radius": 0.5, "center_x": 0.5, "center_y": 0.5 }
}
```

```
/vfx play vfxweaver:vortex {[strength:4]}
```

## Code

```
/vfx play vfx_demos:show_vortex
```

Its datapack definition:

```json
{
	"type": "vortex",
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
					"value": -1.2
				},
				{
					"time": 80,
					"value": 1.2
				},
				{
					"time": 160,
					"value": -1.2
				}
			]
		},
		"radius": 0.6,
		"center_x": 0.5,
		"center_y": 0.5
	}
}
```

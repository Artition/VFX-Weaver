# distortion

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/distortion.mp4" type="video/mp4"></video>

`type: "distortion"`

Barrel (`amount > 0`) / pincushion (`amount < 0`) distortion of the whole screen.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 0.2 (fades to 0) | Distortion strength; sign picks the direction |
| `radius` | float | 0.8 | Screen fraction affected from the centre (0..1) |

## Example

```json
{
	"type": "distortion",
	"duration": 60,
	"params": { "amount": 0.2, "radius": 0.8 }
}
```

```
/vfx play vfxweaver:distortion
```

## Code

```
/vfx play vfx_demos:show_distortion
```

Its datapack definition:

```json
{
	"type": "distortion",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"amount": {
			"keyframes": [
				{
					"time": 0,
					"value": -0.04
				},
				{
					"time": 80,
					"value": 0.1
				},
				{
					"time": 160,
					"value": -0.04
				}
			]
		},
		"radius": 0.9
	}
}
```

# double_vision

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/double_vision.mp4" type="video/mp4"></video>

`type: "double_vision"`

Two ghost copies of the frame offset left/right with a slow drift.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `offset` | float | 0.04 | Ghost distance from the centre, screen-width fractions (0..0.5) |
| `ghost_opacity` | float | 0.5 | Ghost opacity (0..1) |
| `drift` | float | 0 | Slow sinusoidal drift, screen fractions per second (0..0.2) |
| `intensity` | float | 1 (fades to 0) | Overall strength (0..1) |

## Example

```json
{
	"type": "double_vision",
	"duration": 60,
	"params": { "offset": 0.04, "ghost_opacity": 0.5, "drift": 0, "intensity": 1 }
}
```

```
/vfx play vfxweaver:double_vision {[offset:0.06],[ghost_opacity:0.7]}
```

## Code

```
/vfx play vfx_demos:show_double_vision
```

Its datapack definition:

```json
{
	"type": "double_vision",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"offset": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.01
				},
				{
					"time": 80,
					"value": 0.05
				},
				{
					"time": 160,
					"value": 0.01
				}
			]
		},
		"ghost_opacity": 0.4,
		"drift": 0.02,
		"intensity": 1.0
	}
}
```

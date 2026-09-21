# noise_warp

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/noise_warp.mp4" type="video/mp4"></video>

`type: "noise_warp"`

An animated value-noise field warps the picture in soft fluid patches; bright noise areas drag pixels the hardest.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `scale` | float | 8 | Noise cell detail across the screen (1..64) |
| `amplitude` | float | 0.03 (fades to 0) | Max pixel offset in the brightest areas, screen fractions (0..0.25) |
| `contrast` | float | 2 | Gathers the warp into distinct patches (>1) or flattens it (<1) |
| `coherence` | float | 1 | 1 = pixels flow along the noise gradient (liquid-glass), 0 = each patch pulls its own direction |
| `speed` | float | 0.5 | Field morph rate, cycles per second |
| `drift_x/y` | float | 0 / 0 | Pattern travel, screen fractions per second |

## Example

```json
{
	"type": "noise_warp",
	"duration": 60,
	"params": { "scale": 8, "amplitude": 0.03, "contrast": 2, "coherence": 1, "speed": 0.5, "drift_x": 0 }
}
```

```
/vfx play vfxweaver:noise_warp {[amplitude:0.06],[scale:4],[contrast:3]}
```

## Code

```
/vfx play vfx_demos:show_noise_warp
```

Its datapack definition:

```json
{
	"type": "noise_warp",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"scale": 6.0,
		"amplitude": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.035
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"contrast": 2.0,
		"coherence": 1.0,
		"speed": 0.3,
		"drift_x": 0.0,
		"drift_y": {
			"keyframes": [
				{
					"time": 0,
					"value": -0.01
				},
				{
					"time": 80,
					"value": 0.01
				},
				{
					"time": 160,
					"value": -0.01
				}
			]
		},
		"seed": 0.0
	}
}
```

# solarize

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/solarize.mp4" type="video/mp4"></video>

`type: "solarize"`

Bright pixels invert, dark stay untouched.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `threshold` | float | 0.5 | Luma above which colours invert (0..1) |
| `softness` | float | 0 | Rolloff width around the threshold (0..1) |
| `intensity` | float | 1 (fades to 0) | Blend original to solarized (0..1) |

## Example

```json
{
	"type": "solarize",
	"duration": 60,
	"params": { "threshold": 0.5, "softness": 0, "intensity": 1 }
}
```

```
/vfx play vfxweaver:solarize {[threshold:0.4]}
```

## Code

```
/vfx play vfx_demos:show_solarize
```

Its datapack definition:

```json
{
	"type": "solarize",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"threshold": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.3
				},
				{
					"time": 80,
					"value": 0.7
				},
				{
					"time": 160,
					"value": 0.3
				}
			]
		},
		"softness": 0.1,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.2
				},
				{
					"time": 80,
					"value": 0.9
				},
				{
					"time": 160,
					"value": 0.2
				}
			]
		}
	}
}
```

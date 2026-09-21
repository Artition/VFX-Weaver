# bloom

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/bloom.mp4" type="video/mp4"></video>

`type: "bloom"`

Glow around bright screen areas.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.6 (fades to 0) | Glow strength |
| `threshold` | float | 0.7 | Luminance above which pixels glow (0..1) |
| `radius` | float | 3 | Glow spread in pixels |

## Example

```json
{
	"type": "bloom",
	"duration": 60,
	"params": { "intensity": 0.6, "threshold": 0.7, "radius": 3 }
}
```

```
/vfx play vfxweaver:bloom {[threshold:0.5],[intensity:1]}
```

## Code

```
/vfx play vfx_demos:show_bloom
```

Its datapack definition:

```json
{
	"type": "bloom",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.1
				},
				{
					"time": 80,
					"value": 0.85
				},
				{
					"time": 160,
					"value": 0.1
				}
			]
		},
		"threshold": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.75
				},
				{
					"time": 80,
					"value": 0.55
				},
				{
					"time": 160,
					"value": 0.75
				}
			]
		},
		"radius": 3.0
	}
}
```

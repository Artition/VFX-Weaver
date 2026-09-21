# digital_glitch

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/digital_glitch.mp4" type="video/mp4"></video>

`type: "digital_glitch"`

The frame tears into horizontal bands with RGB-split spikes, in bursts (slot-gated, not constant tearing).

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `block` | float | 0.06 | Band height, screen-height fractions (0.01..0.5) |
| `displacement` | float | 0.08 | Max sideways band shift, screen-width fractions (0..0.5) |
| `rate` | float | 6 | Glitch quanta per second (slots) |
| `chroma` | float | 0.5 | RGB-split strength inside glitched bands (0..1) |
| `seed` | float | 0 | Pattern offset - change for a different tear layout |
| `chance` | float | 0.4 | Fraction of slots that burst (0..1) |
| `intensity` | float | 1 (fades to 0) | Overall strength (0..1) |

## Example

```json
{
	"type": "digital_glitch",
	"duration": 60,
	"params": { "block": 0.06, "displacement": 0.08, "rate": 6, "chroma": 0.5, "seed": 0, "chance": 0.4 }
}
```

```
/vfx play vfxweaver:digital_glitch {[chance:1],[displacement:0.15]}
```

## Code

```
/vfx play vfx_demos:show_digital_glitch
```

Its datapack definition:

```json
{
	"type": "digital_glitch",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"block": 0.03,
		"displacement": 0.035,
		"rate": 3.0,
		"chroma": 0.25,
		"seed": 0.0,
		"chance": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.05
				},
				{
					"time": 100,
					"value": 0.2
				},
				{
					"time": 200,
					"value": 0.05
				}
			]
		},
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.1
				},
				{
					"time": 100,
					"value": 0.4
				},
				{
					"time": 200,
					"value": 0.1
				}
			]
		}
	}
}
```

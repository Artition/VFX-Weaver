# vhs

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/vhs.mp4" type="video/mp4"></video>

`type: "vhs"`

Worn VHS playback: wobble, a crawling noise tracking band, colour bleed and washed contrast.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `tracking` | float | 0.35 | Tracking band horizontal jumps (0..1) |
| `band_height` | float | 0.08 | Noise band height, screen-height fractions |
| `band_speed` | float | 0.15 | Band travel speed, screen-heights per second |
| `bleed` | float | 0.02 | Chroma smear to the right, screen-width fractions (0..0.1) |
| `wobble` | float | 0.004 | Fine constant horizontal jitter (0..0.05) |
| `intensity` | float | 1 (fades to 0) | Master strength (0..1) |

## Example

```json
{
	"type": "vhs",
	"duration": 60,
	"params": { "tracking": 0.35, "band_height": 0.08, "band_speed": 0.15, "bleed": 0.02, "wobble": 0.004, "intensity": 1 }
}
```

```
/vfx play vfxweaver:vhs {[band_speed:0.3]}
```

## Code

```
/vfx play vfx_demos:show_vhs
```

Its datapack definition:

```json
{
	"type": "vhs",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"tracking": 0.2,
		"band_height": 0.05,
		"band_speed": 0.08,
		"bleed": 0.012,
		"wobble": 0.002,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.4
				},
				{
					"time": 100,
					"value": 0.9
				},
				{
					"time": 200,
					"value": 0.4
				}
			]
		}
	}
}
```

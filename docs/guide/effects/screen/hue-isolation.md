# hue_isolation

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/hue_isolation.mp4" type="video/mp4"></video>

`type: "hue_isolation"`

Keeps the chosen hue, everything else goes grayscale.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `hue` | float | 0 | Target hue on the colour wheel: 0 = red, 0.33 = green, 0.66 = blue, 0.5 = cyan/magenta boundary... (full circle 0..1) |
| `tolerance` | float | 0.2 | Hue match width: how far from `hue` (on the 0..1 wheel) a pixel may be and still keep its colour |
| `intensity` | float | 1 (fades to 0) | Strength of the grayscale conversion outside the tolerance |

## Example

```json
{
	"type": "hue_isolation",
	"duration": 60,
	"params": { "hue": 0, "tolerance": 0.2, "intensity": 1 }
}
```

```
/vfx play vfxweaver:hue_isolation {[hue:0.33],[tolerance:0.1]}
```

## Code

```
/vfx play vfx_demos:show_hue_isolation
```

Its datapack definition:

```json
{
	"type": "hue_isolation",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"hue": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 100,
					"value": 0.4
				},
				{
					"time": 200,
					"value": 0.0
				}
			]
		},
		"tolerance": 0.25,
		"intensity": 0.9
	}
}
```

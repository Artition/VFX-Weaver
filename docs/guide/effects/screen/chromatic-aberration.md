# chromatic_aberration

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/chromatic_aberration.mp4" type="video/mp4"></video>

`type: "chromatic_aberration"`

Splits the RGB channels towards the screen edges (RGB fringing).

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.8 (fades to 0) | Fringing strength; 0 = off |
| `radius` | float | 4 | Effect radius in pixels from the screen border |

## Example

```json
{
	"type": "chromatic_aberration",
	"duration": 60,
	"params": { "intensity": 0.8, "radius": 4 }
}
```

```
/vfx play vfxweaver:chromatic_aberration
```

## Code

```
/vfx play vfx_demos:show_chromatic_aberration
```

Its datapack definition:

```json
{
	"type": "chromatic_aberration",
	"duration": 120,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 10,
	"params": {
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.05
				},
				{
					"time": 60,
					"value": 1.8
				},
				{
					"time": 120,
					"value": 0.05
				}
			]
		},
		"radius": 10.0
	}
}
```

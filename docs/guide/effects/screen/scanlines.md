# scanlines

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/scanlines.mp4" type="video/mp4"></video>

`type: "scanlines"`

CRT bands drifting across the screen.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.3 (fades to 0) | Band visibility |
| `line_count` | float | 3 | Bands per 100 screen pixels |
| `speed` | float | 0.5 | Downward drift speed |

## Example

```json
{
	"type": "scanlines",
	"duration": 60,
	"params": { "intensity": 0.3, "line_count": 3, "speed": 0.5 }
}
```

```
/vfx play vfxweaver:scanlines {[line_count:6]}
```

## Code

```
/vfx play vfx_demos:show_scanlines
```

Its datapack definition:

```json
{
	"type": "scanlines",
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
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.22
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"line_count": 3.0,
		"speed": 0.4
	}
}
```

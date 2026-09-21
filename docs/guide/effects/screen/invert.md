# invert

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/invert.mp4" type="video/mp4"></video>

`type: "invert"`

Inverts the screen colours.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 1 (fades to 0) | 1 = fully inverted, 0.5 = halfway (washed out), 0 = off |

## Example

```json
{
	"type": "invert",
	"duration": 60,
	"params": { "intensity": 1 }
}
```

```
/vfx play vfxweaver:invert
```

## Code

```
/vfx play vfx_demos:show_invert
```

Its datapack definition:

```json
{
	"type": "invert",
	"duration": 200,
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
					"time": 100,
					"value": 0.85
				},
				{
					"time": 200,
					"value": 0.0
				}
			]
		}
	}
}
```

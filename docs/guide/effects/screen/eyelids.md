# eyelids

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/eyelids.mp4" type="video/mp4"></video>

`type: "eyelids"`

Two soft curved dark lids slide in from the top and bottom. Animate `openness` 1 -> 0 to close the eyes; the built-in is a static half-open template.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `openness` | float | 0.5 | 1 = wide open (lids off screen), 0 = fully closed |
| `softness` | float | 0.15 | Feathered lid edge width, screen-height fractions |
| `curve` | float | 0.35 | Lid bulge toward the centre: 0 = straight bars, 1 = heavy arc |

## Example

```json
{
	"type": "eyelids",
	"duration": 60,
	"params": { "openness": 0.5, "softness": 0.15, "curve": 0.35 }
}
```

```
/vfx play vfxweaver:eyelids {[openness:0.2]}
```

## Code

```
/vfx play vfx_demos:show_eyelids
```

Its datapack definition:

```json
{
	"type": "eyelids",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"openness": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.0
				},
				{
					"time": 100,
					"value": 0.25
				},
				{
					"time": 200,
					"value": 1.0
				}
			]
		},
		"softness": 0.2,
		"curve": 0.4
	}
}
```

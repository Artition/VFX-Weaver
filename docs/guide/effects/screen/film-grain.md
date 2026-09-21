# film_grain

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/film_grain.mp4" type="video/mp4"></video>

`type: "film_grain"`

Animated film grain.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.08 (fades to 0) | Grain strength |
| `size` | float | 2 | Grain size in pixels |

## Example

```json
{
	"type": "film_grain",
	"duration": 60,
	"params": { "intensity": 0.08, "size": 2 }
}
```

```
/vfx play vfxweaver:film_grain
```

## Code

```
/vfx play vfx_demos:show_film_grain
```

Its datapack definition:

```json
{
	"type": "film_grain",
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
					"value": 0.06
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"size": 3.0
	}
}
```

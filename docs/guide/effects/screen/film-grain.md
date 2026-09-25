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
| `chroma` | float | 0 | `0` is monochrome grain, `1` gives each colour channel its own grain |

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
	"duration": 240,
	"easing": "linear",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"intensity": { "keyframes": [ { "time": 0, "value": 0.15 }, { "time": 120, "value": 0.6 }, { "time": 240, "value": 0.15 } ] },
		"size": { "keyframes": [ { "time": 0, "value": 1.5 }, { "time": 120, "value": 3.5 }, { "time": 240, "value": 1.5 } ] },
		"chroma": { "keyframes": [ { "time": 0, "value": 0.0 }, { "time": 60, "value": 1.0 }, { "time": 100, "value": 0.0 }, { "time": 150, "value": 1.0 }, { "time": 190, "value": 0.0 }, { "time": 240, "value": 0.0 } ] }
	}
}
```

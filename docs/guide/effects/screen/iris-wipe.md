# iris_wipe

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/iris_wipe.mp4" type="video/mp4"></video>

`type: "iris_wipe"`

Everything outside a circle goes black - old-film iris transition.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 0.4 -> 1.4 | Circle radius in screen-height fractions (0 = closed, 1.4 = fully open) |
| `softness` | float | 0.05 | Edge feather |
| `center_x/y` | float | 0.5 / 0.5 | Circle centre in UV (bindable to `screen_x/ screen_y`) |
| `zoom` | float | 1 -> 0 | Push-in inside the circle (0..1) |

## Example

```json
{
	"type": "iris_wipe",
	"duration": 60,
	"params": { "radius": 0.4, "softness": 0.05, "center_x": 0.5, "center_y": 0.5, "zoom": 1 }
}
```

```
/vfx play vfxweaver:iris_wipe {[zoom:0]}
```

## Code

```
/vfx play vfx_demos:show_iris_wipe
```

Its datapack definition:

```json
{
	"type": "iris_wipe",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.4
				},
				{
					"time": 80,
					"value": 0.35
				},
				{
					"time": 160,
					"value": 1.4
				}
			]
		},
		"softness": 0.06,
		"center_x": 0.5,
		"center_y": 0.5,
		"zoom": 0.0
	}
}
```

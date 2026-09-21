# speed_lines

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/speed_lines.mp4" type="video/mp4"></video>

`type: "speed_lines"`

"Speed lines" emanating from the screen borders and pointing to the centre (or a given point).

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `center_x/y` | float | 0.5, 0.5 | Point the lines converge to (UV) |
| `count` | float | 50 | Number of lines (10..200) |
| `length` | float | 0.5 | Fraction of the ray to the border each line covers |
| `length_rand` | float | 0.7 | Per-line length variance: 0 = all equal, 1 = fully random |
| `pos_rand` | float | 1.0 | Per-line angular position variance: 0 = lines evenly spaced around the centre, 1 = each line may sit anywhere inside its own slice (uneven spacing, no overlap) |
| `width` | float | 0.5 | Line thickness |
| `seed` | float | 0 | Layout seed: drives each line's length **and** its position; animate via `expr` (e.g. `"t * 2.0"`) to make the layout churn |
| `color_r/g/b` | float | 1 / 1 / 1 | Line colour |
| `intensity` | float | 1 (fades to 0) | Visibility |

## Example

```json
{
	"type": "speed_lines",
	"duration": 60,
	"params": { "center_x": 0.5, "count": 50, "length": 0.5, "length_rand": 0.7, "pos_rand": 1.0, "width": 0.5 }
}
```

```
/vfx play vfxweaver:speed_lines {[count:120],[length:0.8]}
```

## Code

```
/vfx play vfx_demos:show_speed_lines
```

Its datapack definition:

```json
{
	"type": "speed_lines",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"center_x": 0.5,
		"center_y": 0.5,
		"count": 40.0,
		"length": 0.4,
		"length_rand": 0.6,
		"pos_rand": 0.8,
		"width": 0.4,
		"seed": 0.0,
		"color_r": 1.0,
		"color_g": 1.0,
		"color_b": 1.0,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.7
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		}
	}
}
```

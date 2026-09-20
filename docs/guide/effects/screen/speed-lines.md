# speed_lines

`type: "speed_lines"`

"Speed lines" emanating from the screen borders and pointing to the centre (or a given point).

## Fields

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

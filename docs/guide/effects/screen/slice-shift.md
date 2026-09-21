# slice_shift

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/slice_shift.mp4" type="video/mp4"></video>

`type: "slice_shift"`

The frame is cut by a straight line and the halves slide past each other along it; the exposed strips at the screen edges are filled with wrapped or mirrored copies of the world (no black gap).

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `angle` | float | 0 | Cut-line tilt in degrees from horizontal (0 = horizontal line, 90 = vertical) |
| `offset` | float | 0 | Pushes the line off the screen centre along its normal, in screen fractions (-0.5..0.5) |
| `shift` | float | 0.05 (fades to 0) | How far each half slides along the line, in screen fractions; halves diverge by 2x `shift`, negative swaps the sides (-1..1) |
| `mirror` | float | 0 | Fill of the exposed strips: 0 = repeat/wrap, 1 = mirrored copy |

## Example

```json
{
	"type": "slice_shift",
	"duration": 60,
	"params": { "angle": 0, "offset": 0, "shift": 0.05, "mirror": 0 }
}
```

```
/vfx play vfxweaver:slice_shift {[angle:25],[shift:0.12]}
```

## Code

```
/vfx play vfx_demos:show_slice_shift
```

Its datapack definition:

```json
{
	"type": "slice_shift",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"angle": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 20.0
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"offset": 0.0,
		"shift": {
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
		"mirror": 0.0
	}
}
```

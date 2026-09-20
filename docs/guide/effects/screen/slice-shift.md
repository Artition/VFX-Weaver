# slice_shift

`type: "slice_shift"`

The frame is cut by a straight line and the halves slide past each other along it; the exposed strips at the screen edges are filled with wrapped or mirrored copies of the world (no black gap).

## Fields

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

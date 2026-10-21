# torn_edge

`type: "torn_edge"`

A ragged tear splits the frame in two: the two halves sit at opposite brightness and a white
glowing line runs along the tear.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | Tear strength (0..1) |
| `speed` | float | 20 | How often the tear angle is re-rolled, per second |
| `center_x` | float | 0.5 | The point the tear runs through, screen-width fractions |
| `center_y` | float | 0.5 | The point the tear runs through, screen-height fractions |
| `ragged` | float | 0.02 | How far the ragged edge wanders, screen-height fractions |
| `glow` | float | 0.0035 | Width of the glowing line, screen-height fractions |

The tear is a straight line through `center_x`/`center_y` whose angle is re-rolled every beat
(20 per second by default, `speed`), so the whole frame cuts itself at a new angle each beat.
The edge is not straight: a value-noise ripple of `ragged` (0.02 of the screen height, sampled
at 38 cycles across the tear) makes it wander.

Each side of the tear is drawn at the opposite brightness: a pixel brighter than the local mean
of an 8-tap ring (radius 0.045, x divided by the aspect) is bright on one side and dark on the
other, so the picture reads as two halves at different exposure. The Sobel contour of the frame
(tap step 1.5 px, `smoothstep(0.06, 0.18)`) is drawn in black on one side and in the ember
colour on the other, and the tear itself is a white line: an exponential falloff of `glow`
(0.0035 of the screen height) around the cut, scaled by 1.6.

Only the lower guards exist: `speed` cannot be negative, `glow` is floored at 0.0005 so the
falloff never divides by zero. `ragged`, `glow` and `center_x`/`center_y` have no upper limit.

## Example

```json
{
	"type": "torn_edge",
	"duration": 60,
	"params": { "amount": 1.0, "ragged": 0.04 }
}
```

```
/vfx play vfxweaver:torn_edge {[amount:0.8],[glow:0.01]}
```

## Code

Its built-in datapack definition:

```json
{
	"type": "torn_edge",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"amount": { "start": 1.0, "end": 0.0 },
		"speed": 20.0,
		"center_x": 0.5,
		"center_y": 0.5,
		"ragged": 0.02,
		"glow": 0.0035
	}
}
```

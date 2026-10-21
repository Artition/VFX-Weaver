# hex_grid

`type: "hex_grid"`

A triangular grid over the frame, its lines coloured by the screen position.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | Effect strength (0..1) |
| `cell_size` | float | 46 | Cell size in pixels |
| `width` | float | 0.035 | Line half-width, cell fractions |
| `speed` | float | 0.5 | How fast the colours drift |

The grid is a triangular lattice: three line directions - two at 60 degrees to each other and
one vertical - drawn on a `cell_size` cell that is anchored at the screen centre, so the grid
grows from the middle of the frame and not from a corner. The lines are the bands where the
distance to the nearest lattice line is under `width` (a cell fraction, 0.035 by default).

The colour is the prism ramp picked by the screen position: `prism7(x * 0.6 + y * 0.4 + time *
speed)`, so the same hue runs diagonally across the frame and drifts with the clock (0.5 per
second by default). The grid lines are drawn at 28 % of that colour over near-black, the
Sobel contour of the picture (tap step 1.5 px, `smoothstep(0.06, 0.18)`) is drawn in the same
colour at 110 %, and the hottest pixels of the picture (`step(avg + 0.18, luma)`, where `avg`
is the local mean of an 8-tap ring of radius 0.045) go white.

Only the lower guards exist: `cell_size` cannot go under 1 px and `width` is floored at 0.0001
so the line falloff never degenerates. `cell_size`, `width` and `speed` have no upper limit.

## Example

```json
{
	"type": "hex_grid",
	"duration": 60,
	"params": { "amount": 1.0, "cell_size": 46 }
}
```

```
/vfx play vfxweaver:hex_grid {[cell_size:24],[speed:1.5]}
```

## Code

Its built-in datapack definition:

```json
{
	"type": "hex_grid",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"amount": { "start": 1.0, "end": 0.0 },
		"cell_size": 46.0,
		"width": 0.035,
		"speed": 0.5
	}
}
```

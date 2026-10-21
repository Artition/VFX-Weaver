# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame breaks into sliding blocks with an RGB split, a knock offset and
the occasional data block.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | Corruption strength (0..1) |

The corruption layout is fixed, so there is nothing else to tune: the clock ticks in 20 beats per
second and each beat re-rolls the picture. A horizontal band row (18 px tall) slides sideways by up
to 8 % of the screen width, a 64x36 px block is knocked by up to 4 % of the screen size, the colour
channels split by 0.6 % to 1.6 % of the screen width, a 3 px scanline pattern darkens two of every
three rows to 72 %, and about 2.5 % of the 24x8 px cells snap to a flat white or cyan data block.
The sampled picture is posterised into black / ember / white before the data blocks are drawn over it.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0 }
}
```

```
/vfx play vfxweaver:datamosh {[amount:0.8]}
```

## Code

Its built-in datapack definition:

```json
{
	"type": "datamosh",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"amount": { "start": 1.0, "end": 0.0 }
	}
}
```

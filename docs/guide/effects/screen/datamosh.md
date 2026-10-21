# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame keeps painting the old picture, the objects in it flow into
each other, and everything that moved breaks apart into coloured pixels - what a video shows
when the key frame is deleted and the decoder paints the P-frames against the wrong reference.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | How much of the melt is mixed over the live frame (0..1) |
| `residual` | float | 0.08 | The share of the live picture that still lands in the reconstruction (0..1) |
| `flow` | float | 24 | How far the old picture is dragged, in pixels |
| `break_level` | float | 0.35 | How much has to change before the pixels break apart (0..1) |
| `chroma` | float | 0.85 | How strongly the broken pixels turn into colour (0..1) |

## How it works

The effect is a **feedback effect**: it keeps the picture it produced last frame in the frame's
history buffer, and that history is the reference the decoder gets instead of a key frame.

- The **update pass** paints the history *dragged* by a slow, smooth flow field (`flow` pixels
  in any direction): the old picture slides and the objects in it flow into each other.
- `residual` is the little bit of the live picture that still lands on top, the way a P-frame
  adds its residual to the reference. It is what keeps the melt from dissolving into noise: the
  reconstruction is always a few frames behind the live picture, never a different one.
- Where the picture moved or changed, what the decoder painted is wrong. The size of that
  mistake is the **damage**; above `break_level` the pixels **break apart into colour** - a hue
  per pixel, tinted by the 16 px block, so the region reads as shattered colour rather than a
  drawn outline or a grid of squares. `chroma` is how far the pixels go into that colour.
- The **composite pass** mixes the melted picture over the live frame by `amount`, fading the
  melt in over the first half second so a restart never flashes the history of the previous run.

Only the lower guards exist: `flow` cannot go negative, `residual`, `break_level` and `chroma`
are clamped to 0..1. None of them has an upper cap.

## Honest limits

- The melt lives in the same **history buffer** as `afterimage`: when both run at the same
  time they chain (the datamosh melts the afterimage's trail and the other way round), and the
  trail of the last run of either one is still in the buffer when the other starts.
- The reference is the screen's own picture from a few frames ago, so the picture flows and
  breaks where *it* moves - it is not a cut-in from another shot.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0, "flow": 40.0, "break_level": 0.2 }
}
```

```
/vfx play vfxweaver:datamosh {[flow:60],[chroma:1.0]}
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
		"amount": { "start": 1.0, "end": 0.0 },
		"residual": 0.08,
		"flow": 24.0,
		"break_level": 0.35,
		"chroma": 0.85
	}
}
```

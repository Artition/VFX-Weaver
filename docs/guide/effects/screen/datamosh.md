# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame paints the old picture dragged along the movement, and every
pixel that moved breaks apart into colour - what a video shows when the key frame is deleted
and the decoder paints the P-frames against the wrong reference.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | How much of the melt is mixed over the live frame (0..1) |
| `residual` | float | 0.35 | The share of the live picture that still lands in the reconstruction (0..1) |
| `smear` | float | 4 | How many frames of movement the smear covers |
| `break_level` | float | 0.25 | How much has to change before the pixels break apart (0..1) |
| `chroma` | float | 0.85 | How strongly the broken pixels turn into colour (0..1) |

## How it works

The effect is a **feedback effect**: it keeps the picture it produced last frame in the frame's
history buffer, and that history is the reference the decoder gets instead of a key frame.

- The **update pass** estimates the movement, the way a codec searches its reference frame for
  the matching block: one Lucas-Kanade step between the history and the live picture gives the
  velocity of the content at every pixel. **This is the only thing that moves the frame** - with
  nothing moving in the scene the estimate is zero, the picture stays whole and nothing drifts.
- The old picture is then pulled from where the content came from, `smear` frames deep
  (`smear` = 4 by default), so moving objects leave a trail and flow into each other. The drag
  is capped, so a noisy estimate cannot throw the picture off the screen.
- `residual` is the share of the live picture that still lands on top, the way a P-frame adds
  its residual to the reference. It also sets how far the reference lags (about `1 / residual`
  frames), which is why the movement estimate is scaled back to a single frame before it is
  smeared out.
- Where the picture moved or changed, what the decoder painted is wrong. The size of that
  mistake is the **damage**; above `break_level` the pixels **break apart into colour**, one hue
  per pixel with only a faint 8 px block tint - so a moved region reads as shattered colour,
  not as a grid of bricks. `chroma` is how far the pixels go into that colour.
- The **composite pass** mixes the melted picture over the live frame by `amount`, fading the
  melt in over the first half second so a restart never flashes the history of the previous run.

Only the lower guards exist: `residual`, `break_level` and `chroma` are clamped to 0..1 and
`smear` cannot go negative. None of them has an upper cap.

## Honest limits

- The melt lives in the same **history buffer** as `afterimage`: when both run at the same
  time they chain (the datamosh melts the afterimage's trail and the other way round), and the
  trail of the last run of either one is still in the buffer when the other starts.
- The reference is the screen's own picture from a couple of frames ago, so the picture breaks
  where *it* moves - it is not a cut-in from another shot.
- The movement estimate needs a picture with some structure: in flat areas (sky, a plain wall)
  there is no gradient to measure, so nothing is dragged there.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0, "smear": 8.0, "break_level": 0.15 }
}
```

```
/vfx play vfxweaver:datamosh {[smear:10],[chroma:1.0]}
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
		"residual": 0.35,
		"smear": 4.0,
		"break_level": 0.25,
		"chroma": 0.85
	}
}
```

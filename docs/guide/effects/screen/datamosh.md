# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame is decoded against a reference that never arrives, so the
decoder paints the old screen, dragged along the movement, and every block that changed comes
apart in colour.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | How much of the melt is mixed over the live frame (0..1) |
| `residual` | float | 0.06 | How fast the reference refreshes - the lower it is, the older the picture the decoder paints (0..1) |
| `smear` | float | 4 | How many frames of movement the drag covers |
| `chroma` | float | 0.85 | How strongly the wrong blocks are put out of hue (0..1) |

## How it works

The effect is a **feedback effect**: it keeps the picture it produced last frame in the frame's
history buffer, and that history is the reference the decoder gets instead of a key frame.

- **The key frame never arrives.** The history barely refreshes: only `residual` of the live
  picture (6 %) leaks into it per frame, so it holds the screen as it was roughly half a
  second ago. That is the picture the decoder paints.
- **The movement** is estimated the way a codec searches its reference frame for the matching
  block: one Lucas-Kanade step between the live picture and the reference gives the velocity
  of the content. It is measured **once per 8 px macroblock**, so the whole block gets one
  motion vector and the picture moves in whole pieces instead of crawling pixel by pixel -
  that is what keeps it from flickering. This estimate is also the only thing that moves the
  frame: with nothing moving in the scene it is zero and the picture stays whole.
- **The decoder paints the old picture, dragged** along that movement, `smear` frames deep.
  The drag is snapped to a 4 px lattice, the way a codec snaps its vectors, so the picture
  comes apart in whole steps. Because the drag covers a few frames while the reference is half
  a second old, the old content lands in the **wrong place** - that misalignment is the tear.
- **Every block is decoded**, and the ones that no longer match get their **chroma plane
  wrong**: the hue is rotated and the saturation overstated, in proportion to how wrong the
  block is. There is no threshold - the whole frame is decoded, and the parts that changed come
  apart hardest. The luma is never touched, so the picture stays readable and never turns into
  grey mush.
- The **composite pass** mixes the melted picture over the live frame by `amount`, fading the
  melt in over the first half second so a restart never flashes the history of the previous run.

Only the lower guards exist: `residual` and `chroma` are clamped to 0..1 and `smear` cannot go
negative. None of them has an upper cap.

## Honest limits

- The melt lives in the same **history buffer** as `afterimage`: when both run at the same
  time they chain (the datamosh melts the afterimage's trail and the other way round), and the
  trail of the last run of either one is still in the buffer when the other starts.
- The reference is the screen's own picture from half a second ago, so the frame tears where
  *it* moves - it is not a cut-in from another shot.
- The movement estimate needs some structure in the picture: in flat areas (sky, a plain
  wall) there is no gradient to measure, so nothing is dragged there.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0, "residual": 0.03, "chroma": 1.0 }
}
```

```
/vfx play vfxweaver:datamosh {[residual:0.03],[smear:8]}
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
		"residual": 0.06,
		"smear": 4.0,
		"chroma": 0.85
	}
}
```

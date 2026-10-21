# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame is drawn from the previous frame, warped by the movement, so
the picture melts into a trail and the colour planes drift apart - what a video shows when the
key frames are gone and the decoder keeps painting the P-frames.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | How much of the melt is mixed over the live frame (0..1) |
| `residual` | float | 0.06 | How much of the live picture still lands in the reconstruction (0..1) |
| `smear` | float | 2 | How much the movement is stretched: 1 follows it exactly, 2 over-compensates and the picture tears away |
| `chroma` | float | 0.1 | How far the colour planes drift apart along the movement (0.1 = the 4:2:0 look) |
| `iframe` | float | 3 | How often the decoder gets a key frame and the picture snaps back, in seconds (0 = never) |

## How it works

The effect is a **feedback effect**: it keeps the picture it produced last frame in the frame's
history buffer, and that history is the reference the decoder gets instead of a key frame.

- **The picture is the previous frame, warped.** The update pass takes the history and pulls
  every pixel from where the movement says it came from. Because the next frame does the same
  to that result, the content is dragged again and again: the picture melts into a trail, the
  way a decoder paints P-frames against a reference that never arrives.
- **The movement** is measured the way a codec reads its vectors: the velocity is looked up at
  the centre of the **16 px macroblock**, so a whole block moves as one piece. It is estimated
  from the picture itself (one Lucas-Kanade step between the live frame and the history), so
  the effect only follows what actually moves - with nothing moving in the scene the picture
  stays whole.
- **The glitch threshold.** A movement below half a pixel leaves the pixel where it is, so the
  still parts of the picture do not crawl and the effect does not flicker.
- **The chroma smear.** The red, green and blue planes are read at slightly different offsets
  along the movement (`chroma` = 0.1 gives 1.00 / 1.05 / 1.10), the way a 4:2:0 stream
  smears colour when it is decoded against the wrong reference.
- **The I-frame.** Every `iframe` seconds the decoder gets a key frame and the picture snaps
  back to the real screen for a tick, then the melt starts building again. Set `iframe` to 0
  to never reset it.
- `residual` is the little bit of the live picture that still lands, the way a P-frame adds
  its residual to the reference. It is what keeps the reference from dissolving.

Only the lower guards exist: `residual` and `chroma` are clamped to 0..1, `smear` and `iframe`
cannot go negative. None of them has an upper cap.

## Honest limits

- The melt lives in the same **history buffer** as `afterimage`: when both run at the same
  time they chain (the datamosh melts the afterimage's trail and the other way round), and the
  trail of the last run of either one is still in the buffer when the other starts.
- The reference is the screen's own picture, so the melt is the screen's own past - it is not a
  cut-in from another shot.
- The movement estimate needs some structure in the picture: in flat areas (sky, a plain
  wall) there is no gradient to measure, so nothing is dragged there.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0, "smear": 3.0, "iframe": 2.0 }
}
```

```
/vfx play vfxweaver:datamosh {[smear:4],[chroma:0.2]}
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
		"smear": 2.0,
		"chroma": 0.1,
		"iframe": 3.0
	}
}
```

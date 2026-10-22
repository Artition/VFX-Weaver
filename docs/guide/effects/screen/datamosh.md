# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame is drawn from the previous frame, carried over pixel by pixel
along the movement - the picture breaks into pieces of old content instead of being smeared.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | How much of the melt is mixed over the live frame (0..1) |
| `smear` | float | 1 | The vector multiplier: 1 = the movement as measured, 2 = twice as far |
| `chroma` | float | 0.5 | How far the colour plane is pulled away from the luma along the movement |
| `iframe` | float | 3 | How often a piece returns to the real picture, in seconds (0 = never) |

## How it works

The effect is a **feedback effect**: it keeps the picture it produced last frame in the frame's
history buffer, and that history is the reference the decoder gets instead of a key frame.

- **The history carries two things.** The colour channels hold the melt - the picture the
  decoder painted so far. The **alpha channel holds the luma of the previous live frame**, so
  one sampler gives both the stale reference and the frame the movement is measured against.
  Without that, the movement would be measured against the already broken output, which would
  drag it back towards the live picture - a motion blur, not a datamosh.
- **The movement** is measured the way a codec measures it: for every piece, a greedy search
  (8, 4, 2 and 1 pixel) finds the integer vector that best matches the live picture against
  the previous live picture, over 9 points inside the piece. A penalty for a non-zero vector
  gives exactly zero where nothing moves, so a still picture does not crawl.
- **The search runs once per cell, not once per pixel.** Every pixel of a piece needs the same
  vector, so the search is done in a separate pass at 1/8 resolution (one texel per 8x8 cell) and
  the result is read back by the update pass. The picture is the same, but the search costs
  about 2.5 texture reads per pixel instead of 174.
- **The pieces are not a grid.** A static hash quadtree splits the screen into pieces of 8 to
  64 px, so neighbouring pieces differ in size and the layout never changes. Where the
  vectors of two neighbours differ, cracks and doubled pixels appear along the seam.
- **The decoder carries the pixels over as they are.** The melt is read point sampled (no
  filter, no interpolation, no leftover of the live frame), so a moving object drags sharp
  pixels of the old background with it and the new content never lands in the frame. That is
  the broken decoder.
- **The colour leaves the shape.** The luma is taken at the measured vector, the colour at
  `1 + chroma` times that vector, the way a 4:2:0 stream smears colour when it is decoded
  against the wrong reference. There is no random noise in it - every colour is the picture's
  own.
- **Every piece refreshes on its own.** Each `iframe` seconds a piece returns to the real
  picture for a tick, at a phase of its own, so there is no global flash. Set `iframe` to 0
  to never refresh.

Only the lower guards exist: `chroma` cannot go negative, `smear` and `iframe` cannot go
negative either. None of them has an upper cap.

## Honest limits

- The melt lives in the same **history buffer** as `afterimage`: when both run at the same time they chain, and each of them overwrites the alpha the other one needs - run one or the other, not both. With `afterimage` active the datamosh's motion estimate can go stale (the alpha it reads no longer carries the previous live picture), so the picture keeps dragging instead of tearing; the melt still runs, it just stops following the movement.
- The reference is the screen's own picture, so the melt is the screen's own past - it is not a cut-in from another shot.
- A clean camera pan does not break much, exactly like in a real codec: the breakage shows on the parallax, on the edges of objects, on mobs and at the edges of the screen, where the clamp pulls the streaks out.
- The movement estimate needs some structure in the picture: in flat areas (sky, a plain wall) there is no gradient to measure, so nothing is dragged there.
- The movement search runs at 1/8 resolution (one texel per 8x8 cell), which is about 2.5 texture reads per pixel for the search plus 4 for the update itself - roughly 6.5 in total, against 174 when the search ran per pixel. `smear` does not change the cost.
- The motion field is one texel per 8x8 cell, so the movement can only change every 8 px. A slower movement than that is not resolved; the picture still tears, but in 8 px steps.

## How to check it

- Stand still: the picture is the game, nothing crawls, nothing flickers.
- A mob walks past a wall: sharp pixels of the old background trail behind it, with no
  in-between colours (zoom in on a screenshot).
- Turn the camera: the layers at different depths drift apart, the edges of the screen pull
  into streaks, and cracks show along the piece seams.
- Every `iframe` seconds the pieces return to the real picture one by one, without a global
  flash.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0, "smear": 1.0, "chroma": 0.5 }
}
```

```
/vfx play vfxweaver:datamosh {[smear:2],[iframe:1.5]}
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
		"smear": 1.0,
		"chroma": 0.5,
		"iframe": 3.0
	}
}
```

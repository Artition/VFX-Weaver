# datamosh

`type: "datamosh"`

Data-mosh corruption: the frame breaks into macroblocks that drag stale picture out of the
screen's own history, the way a video decoder paints a P-frame whose reference frame never
arrived.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | How much of the melt is mixed over the live frame (0..1) |
| `block_size` | float | 16 | The macroblock edge in pixels |
| `strength` | float | 0.35 | The share of blocks that break (0..1) |
| `smear` | float | 96 | How far a broken block is dragged, in pixels |
| `residual` | float | 0.12 | The share of the current picture that still lands in a broken block (0..1) |

## How it works

The effect is a **feedback effect**: it keeps a copy of the picture it produced last frame.

- The **update pass** cuts the screen into `block_size` squares. A share of them (`strength`)
  breaks, and each broken block is drawn as a *rigid copy of the history*, shifted by its own
  motion vector (up to `smear` pixels in any direction). The block's breakage is re-rolled
  twice a second, so a broken block lives for a while instead of flickering every frame.
- Because the history is what the next frame reads, the displacement compounds: the stale
  picture smears and trails, which is the "bloom" of a duplicated P-frame. The `residual` is
  the little bit of the live picture that still lands in every block; it anchors the melt, so
  the picture drifts instead of dissolving into noise.
- A few blocks (about 1.5 %) break completely into flat green - the codec's garbage.
- The **composite pass** mixes that melted picture over the live frame by `amount`, fading the
  melt in over the first half second so a restart never flashes the history left over from the
  previous run.

Only the lower guards exist: `block_size` cannot go under 4 px, `smear` cannot go negative and
`residual` and `strength` are clamped to 0..1. None of them has an upper cap.

## Honest limits

- The melt lives in the same **history buffer** as `afterimage`: when both run at the same
  time they chain (the datamosh melts the afterimage's trail and the other way round), and the
  trail of the last run of either one is still in the buffer when the other starts.
- The history is the screen's own picture from the previous frame, so a broken block shows
  *this* scene a moment ago, not a different one. The look is a melting picture, not a
  cut-in from another shot.

## Example

```json
{
	"type": "datamosh",
	"duration": 60,
	"params": { "amount": 1.0, "strength": 0.6, "smear": 160.0 }
}
```

```
/vfx play vfxweaver:datamosh {[strength:0.8],[block_size:32]}
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
		"block_size": 16.0,
		"strength": 0.35,
		"smear": 96.0,
		"residual": 0.12
	}
}
```

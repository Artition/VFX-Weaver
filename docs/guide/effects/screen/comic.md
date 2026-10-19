# comic

`type: "comic"`

Duotone comic: a clean two-tone split at mid grey printed in paper/ink colours, with halftone dots in the mid tones and a Sobel contour overlay.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `paper_r/g/b` | float | 1 / 1 / 1 | Light-tone (paper) colour |
| `ink_r/g/b` | float | 0 / 0 / 0 | Dark-tone (ink) colour, also used for the dots and contour |
| `amount` | float | 1 (fades to 0) | Comic look visibility |

## Example

```json
{
	"type": "comic",
	"duration": 60,
	"params": { "paper_r": 1, "paper_g": 1, "paper_b": 1, "ink_r": 0, "ink_g": 0, "ink_b": 0, "amount": 1 }
}
```

```
/vfx play vfxweaver:comic {[amount:1]}
```

## Code

```
/vfx play vfxweaver:comic
```

Its datapack definition:

```json
{
	"type": "comic",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"paper_r": 1.0,
		"paper_g": 1.0,
		"paper_b": 1.0,
		"ink_r": 0.0,
		"ink_g": 0.0,
		"ink_b": 0.0,
		"amount": {
			"start": 1.0,
			"end": 0.0
		}
	}
}
```

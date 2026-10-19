# accent_glow

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/accent_glow.mp4" type="video/mp4"></video>

`type: "accent_glow"`

Dark accent glow on the highlights: pixels above mid grey print in the accent colour, the hottest pixels print white, and 5 px halftone dots rotated 45 degrees fill the mid tones — everything else goes black.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `accent_r/g/b` | float | 0.35 / 0.9 / 1.0 | Accent (glow) colour |
| `strength` | float | 1 | Glow brightness multiplier |
| `amount` | float | 1 (fades to 0) | Glow look visibility |
| `mid` | float | 0.5 | Highlight split point in luma |
| `core` | float | 0.68 | Hot-core threshold in luma |

## Example

```json
{
	"type": "accent_glow",
	"duration": 60,
	"params": { "accent_r": 0.35, "accent_g": 0.9, "accent_b": 1.0, "strength": 1, "amount": 1 }
}
```

```
/vfx play vfxweaver:accent_glow {[amount:1]}
```

## Code

```
/vfx play vfxweaver:accent_glow
```

Its datapack definition:

```json
{
	"type": "accent_glow",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"accent_r": 0.35,
		"accent_g": 0.9,
		"accent_b": 1.0,
		"strength": 1.0,
		"amount": {
			"start": 1.0,
			"end": 0.0
		}
	}
}
```

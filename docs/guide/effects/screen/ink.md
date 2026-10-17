# ink

`type: "ink"`

Sobel contour overlay: dark comic-ink outlines drawn where the local luminance changes.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `edge_low` | float | 0.06 | Sobel magnitude below which no ink is drawn |
| `edge_high` | float | 0.18 | Sobel magnitude at which the ink is fully black |
| `amount` | float | 1 (fades to 0) | Ink contour visibility |

## Example

```json
{
	"type": "ink",
	"duration": 60,
	"params": { "edge_low": 0.06, "edge_high": 0.18, "amount": 1 }
}
```

```
/vfx play vfxweaver:ink {[amount:1]}
```

## Code

```
/vfx play vfxweaver:ink
```

Its datapack definition:

```json
{
	"type": "ink",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"edge_low": 0.06,
		"edge_high": 0.18,
		"amount": {
			"start": 1.0,
			"end": 0.0
		}
	}
}
```

# gradient_map

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/gradient_map.mp4" type="video/mp4"></video>

`type: "gradient_map"`

Maps pixel luminance into a two-colour gradient `from -> to`. See the detailed subsection below the table.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `from_r/g/b` | float | 0.1 / 0 / 0.2 | Gradient colour for the dark end |
| `to_r/g/b` | float | 1 / 0.2 / 0.1 | Gradient colour for the bright end |
| `intensity` | float | 1 (fades to 0) | Mix strength: 0 = original, 1 = fully graded |
| `mode` | float | 0 | 0 = smooth linear gradient, 1 = hard threshold |
| `pos` | float | 0.5 | Transition centre (linear) / luminance threshold (threshold mode) |

```json
{ "type": "gradient_map", "from_r": 0, "from_g": 0, "from_b": 0,
  "to_r": 1, "to_g": 1, "to_b": 1, "intensity": 1, "mode": 0, "pos": 0.5 }
```

`mode: 0` (**linear**): `t = clamp(luma + (pos - 0.5), 0, 1)`, then `mix(from, to, t)`. `pos = 0.5` - no shift; `pos = 0` - dark areas turn into `to` sooner.
`mode: 1` (**threshold**): pixels brighter than `pos` -> `to`, darker -> `from`. No smooth transition.

**True grayscale:** linear mode, `from` = black, `to` = white, `pos = 0.5`, `intensity = 1`.
**Hard black/white mask:** threshold mode, `from` = black, `to` = white, `pos = 0.5`.

## Run it

```
/vfx play vfxweaver:gradient_map
```

## Code

```
/vfx play vfx_demos:show_gradient_map
```

Its datapack definition:

```json
{
	"type": "gradient_map",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"from_r": 0.05,
		"from_g": 0.0,
		"from_b": 0.1,
		"to_r": 1.0,
		"to_g": 0.45,
		"to_b": 0.15,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 1.0
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"mode": 0.0,
		"pos": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.4
				},
				{
					"time": 80,
					"value": 0.6
				},
				{
					"time": 160,
					"value": 0.4
				}
			]
		}
	}
}
```

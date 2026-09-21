# light_beam

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/light_beam.mp4" type="video/mp4"></video>

`type: "light_beam"`

A vertical glowing shaft of soft light descending onto each position. `top_scale` flares the top: 1 = cylinder, 2 = cone with twice the top radius. `softness` increases the number of concentric shells and fades their alpha: 0 = two hard tubes, higher = many thin, faint shells (a smooth blurred column).

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 1.5 | Beam radius in blocks (0.1..16) |
| `height` | float | 48 | Column height upward from the anchor (1..256) |
| `top_scale` | float | 1 | Top radius multiplier (0.1..8): 1 = cylinder, >1 = flared cone |
| `softness` | float | 0.6 | More concentric shells with lower alpha (0 = crisp, 2..4 = soft blurry column) |
| `top_fade` | float | 0.4 | Alpha at the top relative to the base (0..1) |
| `bottom_fade` | float | 0 | Alpha at the bottom relative to the base (0..1) |
| `red/green/blue` | float | 1 / 0.95 / 0.75 | Beam colour |
| `through_blocks` | float | 0 | 1 = visible through walls |
| `intensity` | float | 1 (fades to 0) | Beam opacity |

## Example

```json
{
	"type": "light_beam",
	"duration": 60,
	"params": { "radius": 1.5, "height": 48, "top_scale": 1, "softness": 0.6, "top_fade": 0.4, "bottom_fade": 0 }
}
```

```
/vfx playat vfxweaver:light_beam 8 70 8 {[radius:2],[top_scale:2],[softness:3]}
```

## Code

```
/vfx play vfx_demos:show_light_beam
```

Its datapack definition:

```json
{
	"type": "light_beam",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"positions": [
		[
			1996,
			100,
			1996
		]
	],
	"params": {
		"red": 1.0,
		"green": 0.95,
		"blue": 0.75,
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.0
				},
				{
					"time": 80,
					"value": 1.8
				},
				{
					"time": 160,
					"value": 1.0
				}
			]
		},
		"height": 16.0,
		"softness": 1.2,
		"top_scale": 1.5,
		"top_fade": 0.5,
		"bottom_fade": 0.0,
		"through_blocks": 0.0,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.25
				},
				{
					"time": 80,
					"value": 0.9
				},
				{
					"time": 160,
					"value": 0.25
				}
			]
		}
	}
}
```

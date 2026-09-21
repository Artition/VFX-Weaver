# guide_line

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/guide_line.mp4" type="video/mp4"></video>

`type: "guide_line"`

A glowing dashed line along a parabolic arc between two anchors.

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `width` | float | 0.15 | Line thickness, blocks |
| `dash_length` | float | 0.6 | Dash length, blocks |
| `gap` | float | 0.6 | Gap between dashes, blocks |
| `speed` | float | 2 | Dash crawl speed along the line, blocks per second (negative = reverse) |
| `arc` | float | 1.5 | Bows the path up (+) or droops it (-) at the midpoint, blocks |
| `red/green/blue` | float | 0.25 / 1 / 0.45 | Line colour |
| `through_blocks` | float | 0 | 1 = visible through walls |
| `intensity` | float | 1 (fades to 0) | Line opacity |

## Example

```json
{
	"type": "guide_line",
	"duration": 60,
	"params": { "width": 0.15, "dash_length": 0.6, "gap": 0.6, "speed": 2, "arc": 1.5, "red": 0.25 }
}
```

```
/vfx playat vfxweaver:guide_line 8 70 8 {[arc:3]}
```

## Code

```
/vfx play vfx_demos:show_guide_line
```

Its datapack definition:

```json
{
	"type": "guide_line",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"positions": [
		[
			1996,
			101,
			1996
		],
		[
			2004,
			101,
			1996
		]
	],
	"params": {
		"red": 0.25,
		"green": 1.0,
		"blue": 0.45,
		"width": 0.12,
		"dash_length": 0.5,
		"gap": 0.4,
		"speed": 2.0,
		"arc": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 1.6
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"through_blocks": 0.0,
		"intensity": 1.0
	}
}
```

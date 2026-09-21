# entity_outline

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/entity_outline.mp4" type="video/mp4"></video>

`type: "entity_outline"`

Silhouette outline of the "inverted hull" type: the model is expanded by `width`, only back faces remain - a thin rim sticks out. Follows the texture contour (no flat rectangle).

## Fields

> Entity effects are targeted with `/vfx playentity` or the **`entity_selector`** field and also accept the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `color_r/g/b` | float | 1 / 0.85 / 0.2 | Outline colour |
| `alpha` | float | 1 | Opacity |
| `width` | float | 0.05 | Rim thickness in blocks |
| `through_blocks` | float | 0 | 1 = the glow is visible through walls, 0 = occluded by them |

## Example

```json
{
	"type": "entity_outline",
	"duration": 60,
	"params": { "color_r": 1, "color_g": 0.85, "color_b": 0.2, "alpha": 1, "width": 0.05, "through_blocks": 0 }
}
```

```
/vfx playentity vfxweaver:entity_outline @e[type=pig,limit=1] {[width:0.1]}
```

## Code

```
/vfx play vfx_demos:show_entity_outline
```

Its datapack definition:

```json
{
	"type": "entity_outline",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"entity_selector": "@e[tag=vfx_showcase,limit=1]",
	"params": {
		"color_r": 1.0,
		"color_g": 0.85,
		"color_b": 0.2,
		"alpha": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.3
				},
				{
					"time": 80,
					"value": 1.0
				},
				{
					"time": 160,
					"value": 0.3
				}
			]
		},
		"width": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.02
				},
				{
					"time": 80,
					"value": 0.08
				},
				{
					"time": 160,
					"value": 0.02
				}
			]
		},
		"through_blocks": 0.0
	}
}
```

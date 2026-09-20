# entity_outline

`type: "entity_outline"`

Silhouette outline of the "inverted hull" type: the model is expanded by `width`, only back faces remain - a thin rim sticks out. Follows the texture contour (no flat rectangle).

## Fields

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

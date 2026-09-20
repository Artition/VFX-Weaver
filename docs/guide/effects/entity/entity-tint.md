# entity_tint

`type: "entity_tint"`

Fills the entity with the effect colour **accounting for its texture** (the texture is the alpha mask, so the effect follows the silhouette).

## Fields

> Entity effects are targeted with `/vfx playentity` or the **`entity_selector`** field and also accept the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `color_r/g/b` | float | 0.2 / 0.6 / 1.0 | Tint colour |
| `alpha` | float | 0.5 | Opacity |
| `texture` | float | 1 | 1 = recolour the texture (texture x colour, the pattern stays visible); 0 = flat colour, texture only as a mask |
| `through_blocks` | float | 1 | 1 = visible through walls, 0 = occluded |

## Example

```json
{
	"type": "entity_tint",
	"duration": 60,
	"params": { "color_r": 0.2, "color_g": 0.6, "color_b": 1.0, "alpha": 0.5, "texture": 1, "through_blocks": 1 }
}
```

```
/vfx playentity vfxweaver:entity_tint @e[type=pig,limit=1] {[alpha:0.8],[color_r:1]}
```

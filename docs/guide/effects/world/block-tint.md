# block_tint

`type: "block_tint"`

Translucent fill of the block model's visible faces.

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `color_r/g/b` | float | 0.2 / 0.6 / 1.0 | Fill colour |
| `alpha` | float | 0.35 | Fill opacity (0..1) |
| `through_blocks` | float | 1 | 1 = visible through other blocks, 0 = occluded by them |

## Example

```json
{
	"type": "block_tint",
	"duration": 60,
	"params": { "color_r": 0.2, "color_g": 0.6, "color_b": 1.0, "alpha": 0.35, "through_blocks": 1 }
}
```

```
/vfx play vfxweaver:block_tint {[alpha:0.6],[color_r:1]}
```

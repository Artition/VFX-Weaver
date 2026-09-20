# block_outline

`type: "block_outline"`

Block outline, two modes.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `color_r/g/b` | float | 1 / 0.85 / 0.2 | Outline colour |
| `alpha` | float | 0.9 | Opacity |
| `width` | float | 0.05 | Outline thickness in blocks |
| `shell` | float | 0 | 0 = each model face extruded outwards along its normal by `width/2` (cannot cover the block); 1 = scaled shell clipped by the block's own depth |
| `through_blocks` | float | 0 | 1 = visible through other blocks, 0 = occluded (the outline never covers its own target block) |


Both support a list of coordinates via `positions` (see [3.1](../../datapack/format.md#definition-fields)) or `region: [x0,y0,z0,x1,y1,z1]`. Without them a single position from `params.pos_x/y/z` is used - it can be a constant, an animation or a world binding. Positions may also be anchored to a live entity (see [3.1](../../datapack/format.md#definition-fields)) — the effect follows it every frame; `/vfx playat` or a network position override wins over anchors, same as over static positions.

## Example

```json
{
	"type": "block_outline",
	"duration": 60,
	"params": { "color_r": 1, "color_g": 0.85, "color_b": 0.2, "alpha": 0.9, "width": 0.05, "shell": 0 }
}
```

```
/vfx play vfxweaver:block_outline {[width:0.08],[shell:1]}
```

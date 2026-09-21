# block_outline

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/block_outline.mp4" type="video/mp4"></video>

`type: "block_outline"`

Block outline, two modes.

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `color_r/g/b` | float | 1 / 0.85 / 0.2 | Outline colour |
| `alpha` | float | 0.9 | Opacity |
| `width` | float | 0.05 | Outline thickness in blocks |
| `shell` | float | 0 | 0 = each model face extruded outwards along its normal by `width/2` (cannot cover the block); 1 = scaled shell clipped by the block's own depth |
| `through_blocks` | float | 0 | 1 = visible through other blocks, 0 = occluded (the outline never covers its own target block) |


Both support a list of coordinates via `positions` (see [Datapack format](../../datapack/format.md#definition-fields)) or `region: [x0,y0,z0,x1,y1,z1]`. Without them a single position from `params.pos_x/y/z` is used - it can be a constant, an animation or a world binding. Positions may also be anchored to a live entity (see [Datapack format](../../datapack/format.md#definition-fields)) — the effect follows it every frame; `/vfx playat` or a network position override wins over anchors, same as over static positions.

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

## Code

```
/vfx play vfx_demos:show_block_outline
```

Its datapack definition:

```json
{
	"type": "block_outline",
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
		],
		[
			2004,
			100,
			1996
		]
	],
	"params": {
		"color_r": 1.0,
		"color_g": 0.85,
		"color_b": 0.2,
		"alpha": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.25
				},
				{
					"time": 80,
					"value": 0.95
				},
				{
					"time": 160,
					"value": 0.25
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
					"value": 0.07
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

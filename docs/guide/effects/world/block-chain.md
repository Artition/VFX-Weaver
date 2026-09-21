# block_chain

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/block_chain.mp4" type="video/mp4"></video>

`type: "block_chain"`

A line of **real block-model links** between two anchors (like `guide_line`, but made of blocks) — the `block` definition field picks the block, links render with full vanilla textures/lighting and follow moving anchors every frame.

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `spacing` | float | 1 | Distance between links, blocks (0.25..8); links tile the path end-to-end and stretch to span it exactly |
| `arc` | float | 0 | Bows the path up (+) or down/hanging (−) at the midpoint, blocks (same as `guide_line`); ignored in physics mode |
| `scale` | float | 1 | Link block size (0.1..4) |
| `align` | float | 1 | 1 = each link's Y axis is rotated to the local path direction (chain follows the curve), 0 = upright blocks |
| `physics` | float | 0 | 1 = verlet rope simulation: gravity sag, swept world collision with surface friction (links catch and settle on blocks — the contacted block's own friction decides how much they grip: stone 0.6/tick, ice 0.98/tick), the local player pushes links away, `sway` wind wobble. With two anchors both ends are pinned; with one anchor the chain hangs from it (`length` blocks) |
| `length` | float | auto | Total chain length in blocks (physics mode). Single anchor: hanging length, default 6. Two anchors: unset (0) = the span distance; more than the span = deeper sag; less than the span = taut (links stretch) |
| `sway` | float | 0.3 | Wind wobble amplitude in physics mode (0..1) |

```json
{
	"type": "block_chain",
	"loop": true,
	"block": "minecraft:iron_chain",
	"positions": [[0, 72, 0], [12, 70, 6]],
	"params": { "spacing": 0.8, "arc": -1.5 }
}
```

Links are capped at 512 per effect; rendering happens in the vanilla submit pipeline (same path as falling blocks), so it works under shaderpacks. When the anchors are farther apart than the chain, links stretch along the path (up to 4×) to stay connected — a pulled-apart chain goes taut rather than showing gaps.

```json
{
	"type": "block_chain",
	"loop": true,
	"block": "minecraft:iron_chain",
	"positions": [{ "entity": "@s", "point": "center" }],
	"params": { "physics": 1, "length": 6, "sway": 0.4 }
}
```

Physics is a client-side visual simulation (verlet rope at a fixed tick rate) — it does not affect the server world or other entities beyond the push interaction with the local player. Physics chains settle on terrain instead of gliding forever: each joint sweeps the path since its previous position and is pushed back out through the face it entered (a pure displacement that injects no velocity), then on contact loses its inward velocity and has its tangential velocity damped each tick by the contacted block's own friction (`Block#getFriction`: stone and air 0.6, ice 0.98) — so a rope grips stone, glides on ice, rests on surfaces without sinking, a joint that spawns inside a wall comes free, and a moving anchor drags the chain across the ground. Multi-box collision shapes are approximated by their union box.

The built-in `vfxweaver:block_chain` demo hangs a 6-link `minecraft:iron_chain` from the local player (`pos_x/y/z` bound to `player_x/y/z`, `physics: 1`), so plain `/vfx play vfxweaver:block_chain` works without a datapack — the same self-anchoring pattern the `vfxweaver:particles` demo uses.

The builtin `vfxweaver:particles` demo binds its position to the local player (`pos_x/y/z` with `bind: player_x/y/z`), so plain `/vfx play vfxweaver:particles` spawns the helix around the viewer; `/vfx playat` and `positions` override that as usual.

## Code

```
/vfx play vfx_demos:show_block_chain
```

Its datapack definition:

```json
{
	"type": "block_chain",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"block": "minecraft:iron_chain",
	"positions": [
		[
			1994,
			105,
			2001
		],
		[
			2006,
			100,
			2001
		]
	],
	"params": {
		"spacing": 1.0,
		"scale": 1.0,
		"align": 1.0,
		"physics": 0.0,
		"arc": {
			"keyframes": [
				{
					"time": 0,
					"value": -1.2
				},
				{
					"time": 80,
					"value": 1.2
				},
				{
					"time": 160,
					"value": -1.2
				}
			]
		},
		"sway": 0.2
	}
}
```

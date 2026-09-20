# particles

`type: "particles"`

Emits **vanilla particles** in animated shapes — no custom textures, everything is datapack-driven and works under shaderpacks. Two definition-level string fields choose the look:

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `particle` | string | `minecraft:end_rod` | Any simple vanilla particle id (`minecraft:flame`, `minecraft:soul`, `minecraft:glow`, `minecraft:cloud`, ...). The special id `dust` builds a redstone-dust particle whose colour/size come from the animatable params below. Option-carrying types (`block`, `item`, ...) are not supported. |
| `shape` | string | `sphere` | `sphere` (random shell points), `ring` (flat circle on XZ), `helix` (rising spiral), `line` (between the first two `positions` slots, like `guide_line`), `cube` (random surface points), `point` (all at the anchor) |

| Field | Type | Default | Meaning |
|---|---|---|---|
| `rate` | float | 40 | Particles per second (× fade weight). Animate it — e.g. keyframes for a burst. |
| `radius` | float | 2 | Shape size, blocks (animate for a growing shockwave ring) |
| `height` | float | 3 | Helix height, blocks |
| `turns` | float | 2 | Helix revolutions over its height |
| `spin` | float | 0 | Helix rotation phase, revolutions |
| `speed` | float | 0 | Launch velocity (blocks/s): random radial by default, towards the second position slot when `aim:1` |
| `aim` | float | 0 | 1 = aimed flight: every particle is launched towards the second `positions` slot (works for any shape and particle, drag-free ballistics; with entity-anchored slots the stream tracks moving targets) |
| `spread` | float | 0.15 | Cone spread around the aim direction (0 = perfectly aimed, 1 = wide spray) |
| `accel` | float | 0 | Acceleration along the aim direction, blocks/tick² — particles speed up in flight |
| `lifetime` | float | 0 | Particle lifetime override in ticks (0 = particle default). Match it to the flight time so particles die at the target |
| `vel_y` | float | 0 | Constant upward velocity (rising auras) |
| `size` | float | 1 | `dust` particle size (0.05..4) |
| `color_r/g/b` | float | 1 / 1 / 1 | `dust` colour (any RGB) |

```json
{
	"type": "particles",
	"loop": true,
	"particle": "dust",
	"shape": "helix",
	"params": { "rate": 80, "radius": 1.2, "height": 3.0, "color_r": 1.0, "color_g": 0.85, "color_b": 0.3 }
}
```

Emission is budgeted per instance (clamped to 1024 particles/s, 256 per frame) and stops automatically as the effect fades out. Anchors work like for all world overlays: `positions` may be entity-anchored, so an aura follows a player smoothly.

Aimed stream example — accelerating shot from one block to another (put both points into `positions`, or entity-anchor either end):

```json
{
	"type": "particles",
	"particle": "dust",
	"shape": "point",
	"positions": [[0, 70, 0], [20, 70, 20]],
	"params": { "rate": 60, "speed": 0.4, "accel": 0.12, "aim": 1, "spread": 0.05, "lifetime": 40,
		"color_r": 1.0, "color_g": 0.3, "color_b": 0.1 }
}
```

**Block and item mode.** The `particles` effect can also spawn **real block or item models** instead of vanilla particles - inline (`"particle": "block"` / `"item"`) or from a reusable preset (`data/<namespace>/vfx_particles/<name>.json`, `VFXAPI.registerBlockParticle`). Each particle is a client-side `BlockDisplay`/`ItemDisplay` entity, so vanilla interpolates its motion; the model has block-display brightness, gravity, air friction, collision/bounce, size, lifetime and a configurable tumble. The full preset schema, the effect params that override it and the rendering/caps details are on **[Block and item particles](../../datapack/particles.md)**.

## Run it

```
/vfx play vfxweaver:particles
```

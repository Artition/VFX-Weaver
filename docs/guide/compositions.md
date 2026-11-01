# Composition: RIFT

A complete scene built **entirely by a datapack**: no block is placed by hand, no entity is
summoned by hand, and one command starts every effect at once. It exists to show what the effects,
the masks and a few hundred `setblock`s do together when they are composed rather than stacked.

## Install

Copy the pack directory into the world:

```
saves/<world>/datapacks/rift/      (pack.mcmeta, data/rift/...)
```

then in game:

```
/reload
/datapack list          # rift should be enabled
```

## Commands

| Command | What it does |
|---|---|
| `/function rift:go` | The whole thing: clear, build, spawn, teleport, night, play. One command. |
| `/function rift:stop` | Stop the effects only. The scene stays standing. |
| `/function rift:clear` | Stop, kill the entities, and remove every block in the bounding box. |
| `/function rift:build` | Geometry only (disc, obelisk, stones, bridge). |
| `/function rift:spawn` | Entities only (the seven turned blocks and the spirit). |
| `/function rift:tp` | Move the viewer to the platform at the end of the bridge. |
| `/function rift:night` | `time set midnight` + clear weather. The scene is built for a dark sky. |

`rift:go` is safe to run twice: `clear` runs first, so a rebuild never leaves two crowns in the air.

## The scene

Located in the overworld at **x 3000, y 200, z 3000**, so it does not disturb a normal world.

- **The disc** is 16 blocks across, dark stone in concentric rings with radial spokes, a single
  glowing circle at radius 11, and a rim that has been eaten away towards the edge. Below the rim
  two rings of deepslate give it thickness, so the island reads from the side.
- **The obelisk** is a 5x5 base narrowing to a single amethyst column, topped with a lantern.
- **Six standing stones** on a 12-block circle, each with a lantern on top. They are the anchors of
  the six beams.
- **Seven turned blocks** above the obelisk. These are `block_display` entities: they float, they
  can be tilted to any angle, and they cost nothing to place. A builder cannot make this shape with
  blocks.
- **A spirit** — an allay held still (`NoAI`, `NoGravity`, `Silent`) inside the light, outlined by
  the mod. It is the only living thing in the frame and the reason the eye stays in the middle.
- **A bridge to a watching platform** at the south edge: the only place the viewer stands.

## The effects

One `collection` definition (`data/rift/vfx/scene.json`) starts them in the order the eye should find
them: the grades first, then the beams in pairs, then the ring, the dust, the spirit, the outlines
and the prism.

| Definition | What it is |
|---|---|
| `beam` | Six wide, soft light columns, breathing over 12 s. |
| `pulse` | A flat ring (`rot_x 90`) that runs out over the disc and dies at the rim. |
| `dust` | A helix of violet dust climbing the obelisk, its `spin` animated so the spiral turns. |
| `motes` | A slow shell of end-rod motes around the base. |
| `outline` | Block outlines on the six lanterns, breathing with the beams. |
| `spirit` | An entity outline on the allay. |
| `grade_out` | The world outside the rift loses its colour. **Masked, inverted.** |
| `spectral` | Prism smear, **masked** to the rift's own volume. |
| `bloom`, `vignette` | Global, low: glow on the lanterns, a dark edge. |

### The masks

The two masked definitions share one sphere — centre `(3000, 208, 3000)`, radius 9, `volume: "aura"`, `softness: 1.8` —
and differ only by `"invert"`:

```json
"mask": { "a": { "shape": "sphere", "space": "world", "volume": "aura",
                 "center": [3000, 208, 3000], "radius": 9.0, "softness": 1.8 },
          "invert": true }
```

`aura` fills the volume, including air and sky, so the boundary is a sphere of light rather than a
coat of paint on the blocks. Everything outside it is desaturated and cooled by `grade_out`;
everything inside keeps its colour and gains a prism smear. The colour boundary *is* the mask edge —
that is the whole trick, and it is why the scene needs masks rather than more effects.

### The palette

Three colours, each meaning one thing: **violet** (amethyst, the rift), **cold white** (the beams and
the lanterns), **ink** (stone). Nothing else is allowed to introduce colour, and the two grades are
what make that rule visible: outside is grey, inside is violet.

## Honest limits

- The pack builds in the **overworld** at those coordinates. If something is already there, `/function
  rift:clear` removes it too: it fills its own bounding box with air.
- `rift:night` sets the time once; it does not freeze the daylight cycle. Run it again, or use
  `gamerule doDaylightCycle false` if you want the scene to stay lit.
- The turned blocks are `block_display` entities. They survive a rebuild (the `spawn` function kills
  the tagged ones first), but they are entities: `/kill @e[type=block_display]` in the area removes
  them, and a chunk unload/reload keeps them only as long as the scene's chunk is loaded normally.
- The mask needs scene depth. It is the standard case on 26.2, 26.1.2 and 1.21.11; a frame with no
  depth attachment (some replay/modded pipelines) fails the mask **closed**, which means no colour
  split rather than a wrong one.
- The scene is static on purpose. Nothing moves except the effects: the ring, the dust and the
  breathing light. If you want motion in the geometry, animate the display entities with a tick
  function — the pack deliberately does not ship one.

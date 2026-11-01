# Composition: RIFT

A complete scene built **entirely by a datapack**: no block is placed by hand, no entity is
summoned by hand, and one command starts every effect at once. It is composed for a **single
screenshot**, not for motion: every effect value is a constant, so the frame looks the same whenever
the shutter falls.

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
| `/function rift:go` | The whole thing: clear, build, spawn, teleport, dusk, play. One command. |
| `/function rift:stop` | Stop the effects only. The scene stays standing. |
| `/function rift:clear` | Stop, kill the entities, and remove every block in the bounding box. |
| `/function rift:build` | Geometry only (island, spire, pillars, approach). |
| `/function rift:spawn` | Entities only (the halo plates, the shards and the spirit). |
| `/function rift:tp` | Move the viewer to the platform at the end of the bridge. |
| `/function rift:atmosphere` | `time set 12400` + clear weather. The scene is built for dusk. |

`rift:go` is safe to run twice: `clear` runs first, so a rebuild never leaves two crowns in the air.

## The scene

Located in the overworld at **x 3000, y 200, z 3000**.

- **The island** is a 16-block disc of dark stone in concentric rings with radial spokes, one
  continuous glowing circle at radius 11, a rim that has been eaten away, and a cone of rock under
  it, so it reads as a mass rather than a coin.
- **The spire** is a 5x5 base narrowing twice, then an amethyst column: the light comes out of the
  stone.
- **Four leaning pillars** on a 9.5-block circle. They are `block_display` entities — one block
  stretched into an 11-block monolith and tilted nine degrees inward, which is a shape blocks
  cannot make. Their beams meet above the spire, so the four of them read as one broken vault.
- **The crown** is a halo of twelve turned plates (thin, tilted, four of them lit) plus three
  vertical shards, all `block_display`.
- **A spirit** — an allay held still (`NoAI`, `NoGravity`, `Silent`) inside the halo, outlined by
  the mod.
- **Two framing columns** on the disc, ten blocks tall, near the viewer: they give the frame depth
  and hold the eye in the middle.

## The effects

One `collection` (`data/rift/vfx/scene.json`, 16 children) starts everything at once. All values are
constants — no keyframes — because the piece is a still.

| Definition | What it is |
|---|---|
| `beam` | Four soft columns from the pillar tops, each aimed at a point 40 blocks up, so they converge. |
| `rift_light` | A narrow violet column out of the spire itself. |
| `dust` | A helix of violet dust climbing the spire. |
| `motes` | A slow shell of end-rod motes. |
| `outline` | Block outlines on the four pillar bases. |
| `spirit` | An entity outline on the allay. |
| `grade_out` | The world outside the rift cools and loses half its colour. **Masked, inverted.** |
| `grade_in` | The rift itself gains saturation and a violet cast. **Masked.** |
| `spectral` | Prism smear inside the rift. **Masked.** |
| `bloom`, `vignette`, `dof` | Glow on the lanterns, a dark edge, and a sharp band across the middle. |
| `fov` | `fov_delta -16`: a longer lens, so the spire fills the frame instead of sitting in it. |

### The masks

The colour is carried by **two grades over one sphere** — centre `(3000, 208, 3000)`, radius 11,
`volume: "aura"`, `softness: 2.5` — one of them inverted:

```json
"mask": { "a": { "shape": "sphere", "space": "world", "volume": "aura",
                 "center": [3000, 208, 3000], "radius": 11.0, "softness": 2.5 },
          "invert": true }
```

`aura` fills the volume including air and sky, so the boundary is a sphere of light rather than a
coat of paint on the blocks. Outside it the world cools and greys out; inside it the stone keeps its
violet and gains a prism smear. The colour boundary *is* the mask edge — that is the whole trick, and
it is why the scene needs masks rather than more effects.

### The palette

Three colours, each meaning one thing: **violet** (amethyst, the rift), **cold white** (the beams and
the lanterns), **ink** (stone). The dusk sky is the only other light in the frame, and the inverted
grade is what keeps it from competing: it cools the sky instead of colouring it.

## Honest limits

- The pack builds in the **overworld** at those coordinates. If something is already there,
  `/function rift:clear` removes it too: it fills its own bounding box with air.
- `rift:atmosphere` sets the time once; it does not freeze the daylight cycle. Run it again, or use
  `gamerule doDaylightCycle false` if you want the dusk to stay.
- The pillar and crown are `block_display` entities: they do not collide, they are not blocks, and
  `/kill @e[type=block_display]` in the area removes them.
- The mask needs scene depth. It is the standard case on 26.2, 26.1.2 and 1.21.11; a frame with no
  depth attachment (some replay/modded pipelines) fails the mask **closed**, which means no colour
  split rather than a wrong one.
- The scene is static on purpose. The dust and the motes are particles, so they are never twice the
  same, but nothing in the geometry moves.

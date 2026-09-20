# VFX Weaver

A client-side VFX library/API for Minecraft on Fabric and NeoForge: screen post-processing
(chromatic aberration, color grading, distortion, blur, pixelation, motion blur, speed lines and
more), camera shake, world block overlays (tint/outline), entity effects (tint/outline by UUID),
datapack-defined effects, server→client network triggers and a public Java API for other mods.
Authored by **Artition**.

## Download

- **[Modrinth](https://modrinth.com/mod/vfxweaver-api)** — the recommended download for modpack
  authors and players.
- **[GitHub Releases](https://github.com/Artition/VFX-Weaver/releases)** — every build CI publishes,
  including the six node jars.

The mod ships **six jars, one per (Minecraft line, loader)**. Pick the row that matches the world you
will play and the loader you already have installed:

| Minecraft line | Loader | Jar |
|---|---|---|
| 26.2 | Fabric | `vfxweaver-2.0.0+26.2.jar` |
| 26.2 | NeoForge | `vfxweaver-2.0.0+26.2-neoforge.jar` |
| 26.1 / 26.1.1 / 26.1.2 | Fabric | `vfxweaver-2.0.0+26.1.2.jar` |
| 26.1 / 26.1.1 / 26.1.2 | NeoForge | `vfxweaver-2.0.0+26.1.2-neoforge.jar` |
| 1.21.11 | Fabric | `vfxweaver-2.0.0+1.21.11.jar` |
| 1.21.11 | NeoForge | `vfxweaver-2.0.0+1.21.11-neoforge.jar` |

**Version scheme.** `2.0.0` in the file name is the **mod version** (the value released on GitHub and
Modrinth). The documentation also carries a **guide revision** (`Guide v47`); that number tracks the
guide itself and never appears in a jar name. So `vfxweaver-2.0.0+26.2.jar` = mod 2.0.0, Minecraft
26.2, Fabric; the 26.1.2 jar covers the whole 26.1.x line. Fabric jars need Fabric API; NeoForge jars
use the `-neoforge` suffix and are installed with [NeoForge](https://neoforged.net/).

## Features

- **Screen post-processing effects** (ping-pong FBO, screen-space): `chromatic_aberration`,
  `color_grade`, `distortion`, `dent`, `gradient_map`, `posterize`, `blur`, `pixelate`,
  `hue_isolation`, `vignette`, `screen_flash`, `motion_blur`, `bloom`, `film_grain`, `scanlines`,
  `depth_of_field`, `letterbox`, `invert`, `vortex`, `speed_lines`, `slice_shift`, `noise_warp`,
  `solarize`, `double_vision`, `eyelids`, `iris_wipe`, `digital_glitch`, `vhs`, `shockwave`,
  `afterimage`, `stop_motion`, `surface_pattern`.
- **Camera effects** — simplex-noise camera shake (`camera_shake`), dutch angle with wobble
  (`camera_roll`) and an FOV modifier (`fov_modifier`).
- **World overlays** — `block_tint`, `block_outline`, `light_beam`, `pulse_ring`, `guide_line`,
  `particles` (vanilla, block/item-model and glowing spark modes) and `block_chain` (real block-model
  links, optional rope physics), drawn as world-space geometry.
- **Entity effects** — `entity_tint`, `entity_outline` and `entity_displace` applied to entities by
  UUID (second-pass model render, texture-aware).
- **Collections** — `collection` plays several child effects from one command with per-child delays.
- **Datapack-defined effects** — declarative JSON (`data/<namespace>/vfx/<effect>.json`), animated
  params, keyframes, world/camera/player bindings, math expressions, value graphs, per-pixel fields,
  masks, custom particles, sounds.
- **Network triggers** — server→client `vfxweaver:vfx_trigger`, datapack sync over
  `vfxweaver:vfx_sync`.
- **Flashback compatibility** — client-local effects are recorded into
  [Flashback](https://modrinth.com/mod/flashback) replays (soft dependency, optional; Fabric only,
  Flashback has no NeoForge build).
- **Public Java API** — `VFXAPI` for other mods.

## Requirements

| | |
|---|---|
| Minecraft | 26.2, 26.1.2 (covers 26.1.x), 1.21.11 |
| Fabric Loader | >=0.18.4 (26.x) / >=0.17.3 (1.21.11) |
| Fabric API | required for the Fabric jars |
| NeoForge | 26.2, 26.1.2, 1.21.11 — jar `vfxweaver-<version>+<mc>-neoforge.jar` |
| Java to **run** | none to install — the launcher provides the runtime |
| Java to **build from source** | JDK 25 (26.2, 26.1.2) / JDK 21 (1.21.11), or one JDK 25+ via `--release` |
| Flashback | optional, Fabric only (records client-local effects into replays) |

The JDK line is only needed if you compile the mod yourself. Installing and playing a released jar
requires no Java install — Fabric/NeoForge and the launcher ship the matching runtime.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api),
   or [NeoForge](https://neoforged.net/) for the `-neoforge` jar.
2. Drop the matching `vfxweaver-<version>+<mc>[-neoforge].jar` (see the
   [download table](#download)) into your `mods/` folder.
3. Effects work client-side; a server only needs the mod for network triggers.

## Quick start

A two-minute loop: write a JSON file in a datapack, reload, play it with a command.

```json
{ "type": "blur", "duration": 100, "params": { "radius": 8 } }
```

Put that at `data/mymap/vfx/first_blur.json` in your datapack, run `/reload`, then
`/vfx play mymap:first_blur`. The screen blurs for five seconds and fades back.

New to datapacks? **[Your first datapack, from zero](guide/first-datapack.md)** walks through the
folder layout, `pack.mcmeta`, `/datapack list` and `/reload` with a complete copy-pasteable example.

## Documentation

- **[Getting started](guide/index.md)** — requirements, install and your first effect.
- **[Your first datapack, from zero](guide/first-datapack.md)** — datapack layout and a minimal pack.
- **[Effects](guide/effects/index.md)** — every effect type, one page each, in a browsable tree.
- **[Commands](guide/commands.md)** — every `/vfx` subcommand.
- **[Triggering effects](guide/recipes.md)** — recipes for firing effects from game events.
- **[Datapack format](guide/datapack/format.md)** — files, definition fields, positions, validation.
- **[Animating a param](guide/datapack/params.md)** and
  **[Expressions (`expr`)](guide/datapack/expr.md)** — keyframes, bindings, easings and formulas.
- **[Value graphs](guide/datapack/graph.md)**, **[Per-pixel fields](guide/datapack/fields.md)**,
  **[Masks](guide/datapack/masks.md)**, **[Custom particles](guide/datapack/particles.md)** — the
  advanced definition blocks.
- **[Java API](API.md)** — `VFXAPI` for other mods.
- **[Architecture](ARCHITECTURE.md)** — how rendering works under the hood.
- **[Changelog](CHANGELOG.md)** — versioned change history.

## License

MIT — the license header ships in the mod metadata.

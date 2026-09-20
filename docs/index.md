# VFX Weaver

A client-side VFX library/API for Minecraft on Fabric and NeoForge: screen post-processing
(chromatic aberration, color grading, distortion, blur, pixelation, motion blur, speed lines and
more), camera shake, world block overlays (tint/outline), entity effects (tint/outline by UUID),
datapack-defined effects, server→client network triggers and a public Java API for other mods.
Authored by **Artition**.

## Features

- **Post-processing effects** (ping-pong FBO, screen-space): `chromatic_aberration`, `color_grade`,
  `distortion`, `dent`, `gradient_map`, `posterize`, `blur`, `pixelate`, `hue_isolation`, `vignette`,
  `screen_flash`, `motion_blur`, `bloom`, `film_grain`, `scanlines`, `depth_of_field`, `letterbox`,
  `invert`, `vortex`, `speed_lines`.
- **Camera shake** — simplex-noise camera shake with a smooth envelope (`camera_shake`) and FOV
  modifier (`fov_modifier`).
- **World block overlays** — `block_tint` and `block_outline` rendered as world-space geometry.
- **Entity effects** — `entity_tint` and `entity_outline` applied to entities by UUID (second-pass
  model render, texture-aware).
- **Datapack-defined effects** — declarative JSON (`data/<namespace>/vfx/<effect>.json`), animated
  params, keyframes, world/camera/player bindings, math expressions, collections, sounds.
- **Network triggers** — server→client `vfxweaver:vfx_trigger`, datapack sync over
  `vfxweaver:vfx_sync`.
- **Flashback compatibility** — client-local effects are recorded into
  [Flashback](https://modrinth.com/mod/flashback) replays (soft dependency, optional; Fabric only,
  Flashback has no NeoForge build).
- **Public Java API** — `VFXAPI` for other mods.

## Requirements

| | |
|---|---|
| Minecraft | 26.2, 26.1.2, 1.21.11 |
| Fabric Loader | >=0.18.4 (26.x) / >=0.17.3 (1.21.11) |
| Fabric API | required |
| NeoForge | 26.2, 26.1.2, 1.21.11 — jar `vfxweaver-<version>+<mc>-neoforge.jar` |
| Java | JDK 25 (26.2, 26.1.2) / JDK 21 (1.21.11) |
| Flashback | optional, Fabric only (records client-local effects into replays) |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api),
   or [NeoForge](https://neoforged.net/) for the `-neoforge` jar.
2. Drop the matching `vfxweaver-<version>+<mc>[-neoforge].jar` into your `mods/` folder.
3. Effects work client-side; a server only needs the mod for network triggers.

## Quick start

Put a definition in your datapack at `data/mymap/vfx/first_blur.json`:

```json
{ "type": "blur", "duration": 100, "params": { "radius": 8 } }
```

Then in game: `/reload`, then `/vfx play mymap:first_blur`. The screen blurs for 5 seconds and
fades back.

## Documentation

- **[Getting started](guide/index.md)** — requirements, install and your first effect.
- **[Commands](guide/commands.md)** — every `/vfx` subcommand, persistent effects and collections.
- **[Effects and the datapack format](guide/effects.md)** — effect types, params, positions, children.
- **[Surface pattern](guide/surface-pattern.md)**, **[Value graphs](guide/graph.md)**,
  **[Per-pixel fields](guide/fields.md)**, **[Masks](guide/masks.md)**,
  **[Custom particles](guide/particles.md)** — the advanced definition blocks.
- **[Java API](API.md)** — `VFXAPI` for other mods.
- **[Architecture](ARCHITECTURE.md)** — how rendering works under the hood.
- **[Changelog](CHANGELOG.md)** — versioned change history.

## License

MIT — see the header in `fabric.mod.json` (`"license": "MIT"`).

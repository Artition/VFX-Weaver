# VFX Weaver API (vfxweaver)

A client-side VFX library/API for Minecraft on Fabric and NeoForge: screen post-processing (chromatic aberration, color grading, distortion, blur, pixelation, motion blur, speed lines and more), camera shake, world block overlays (tint/outline), entity effects (tint/outline by UUID), datapack-defined effects, server→client network triggers and a public Java API for other mods. Authored by **Artition**.

## Requirements

| | |
|---|---|
| Minecraft | 26.2, 26.1.2, 1.21.11 |
| Fabric Loader | >=0.18.4 (26.x) / >=0.17.3 (1.21.11) |
| Fabric API | required |
| NeoForge | 26.2, 26.1.2, 1.21.11 — jar `vfxweaver-<version>+<mc>-neoforge.jar` |
| Java | JDK 25 (26.2, 26.1.2) / JDK 21 (1.21.11) |
| Flashback | optional, Fabric only (records client-local effects into replays) |

## Quick start

```bash
git clone https://github.com/Artition/VFX-Weaver.git
cd TOMvfx
./gradlew build
```

The built jar is in `build/libs/`. Building requires JDK 25 for the `26.2` and `26.1.2` nodes and JDK 21 for the `1.21.11` node in `JAVA_HOME` (or `org.gradle.java.home` in `gradle.properties`); a single JDK 25+ (e.g. 26) can build all of them via `--release`.

Run a test client/server directly from the project:

```bash
./gradlew runClient
./gradlew runServer
```

## Features

- **Post-processing effects** (ping-pong FBO, screen-space): `chromatic_aberration`, `color_grade`, `distortion`, `dent`, `gradient_map`, `posterize`, `blur`, `pixelate`, `hue_isolation`, `vignette`, `screen_flash`, `motion_blur`, `bloom`, `film_grain`, `scanlines`, `depth_of_field`, `letterbox`, `invert`, `vortex`, `speed_lines`.
- **Camera shake** — simplex-noise camera shake with a smooth envelope (`camera_shake`) and FOV modifier (`fov_modifier`).
- **World block overlays** — `block_tint` and `block_outline` rendered as world-space geometry.
- **Entity effects** — `entity_tint` and `entity_outline` applied to entities by UUID (second-pass model render, texture-aware).
- **Datapack-defined effects** — declarative JSON (`data/<namespace>/vfx/<effect>.json`), animated params, keyframes, world/camera/player bindings, math expressions, collections, sounds.
- **Network triggers** — server→client `vfxweaver:vfx_trigger`, datapack sync over `vfxweaver:vfx_sync`.
- **Flashback compatibility** — client-local effects are recorded into [Flashback](https://modrinth.com/mod/flashback) replays (soft dependency, optional; Fabric only, Flashback has no NeoForge build).
- **Public Java API** — `VFXAPI` for other mods.

## Usage

The full guide on commands (`/vfx play`, `/vfx playat`, `/vfx playentity`, `/vfx stop`, `/vfx set`, `/vfx list`), built-in effect types and the datapack format (`data/<namespace>/vfx/<effect>.json`) is in **[docs/GUIDE.md](docs/GUIDE.md)**.

Minimal Java API example:

```java
// Server → client
VFXAPI.sendEffect(serverPlayer, Identifier.of("vfxweaver", "screen_flash"), Map.of(), null);

// Locally on the client
VFXAPI.playEffect(Identifier.of("vfxweaver", "camera_shake"), 20, Map.of("amplitude_x", 0.2F), null);
```

## Documentation

| File | Contents |
|---|---|
| [docs/GUIDE.md](docs/GUIDE.md) | Commands, effect types, datapack format, world/camera/player bindings |
| [docs/API.md](docs/API.md) | Java API (`VFXAPI`), network protocol `vfxweaver:vfx_trigger` |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How it works under the hood: render pipeline, data flow, load limits |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Versioned change history |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branch and commit conventions |
| [AGENTS.md](AGENTS.md) | Instructions for AI agents working in this repository |

## License

MIT — see the header in `fabric.mod.json` (`"license": "MIT"`).

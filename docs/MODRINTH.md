# VFX Weaver API

A **client-side VFX library/API for Minecraft 26.1 (Fabric)**. Drop-in cinematic effects — screen post-processing, camera shake, block/entity overlays — either from chat commands, from datapacks, or programmatically from your own mod through a tiny Java API.

Author: **Artition** · License: **MIT**

---

## What it does

- **30+ post-processing effects** rendered through a ping-pong FBO (fullscreen, per-pixel shaders): chromatic aberration, color grading, distortion, dent, gradient map, posterize, blur, pixelate, hue isolation, vignette, screen flash, motion blur, bloom, film grain, scanlines, depth of field, letterbox, invert, vortex, speed lines, slice shift, noise warp, solarize, double vision, eyelids, iris wipe, digital glitch, VHS, shockwave, afterimage, stop motion.
- **Camera effects** — simplex-noise shake with a smooth envelope, dutch-angle roll, FOV modifier.
- **World block overlays** — `block_tint` and `block_outline` drawn as world-space geometry around blocks.
- **Entity effects** — `entity_tint` / `entity_outline` applied to entities by UUID (second-pass model render, texture-aware).
- **Datapack-defined effects** — plain JSON, no code. Keyframe animation, easing curves, world/camera/player bindings, math expressions, collections, sounds.
- **Server → client triggers** — effects fired from a server command, or from your own mod over the network.
- **Public Java API** for other mods (`VFXAPI`).

## Requirements

| | |
|---|---|
| Minecraft | ~26.1 |
| Fabric Loader | >= 0.19.3 |
| Fabric API | required |
| Java | 25+ |

---

## Effect types

**Screen post-processing**

| Effect | What it does |
|---|---|
| `chromatic_aberration` | RGB channel separation toward the screen edges |
| `color_grade` | Saturation / contrast / brightness + tint |
| `distortion` | Barrel / pincushion distortion of the screen |
| `dent` | Local radial (or line) warp around a point / segment |
| `gradient_map` | Maps luminance into a two-color gradient (linear or hard threshold) |
| `posterize` | Reduces the number of colors (clean quantization) |
| `blur` | Two-pass Gaussian blur |
| `pixelate` | Pixelation |
| `hue_isolation` | Keeps one hue, the rest goes grayscale |
| `vignette` | Darkens / colors the screen edges |
| `screen_flash` | Fullscreen color overlay |
| `motion_blur` | Directional blur from camera rotation speed |
| `bloom` | Glow around bright areas |
| `film_grain` | Animated film grain |
| `scanlines` | CRT bands drifting across the screen |
| `depth_of_field` | Tilt-shift: sharp band, blur away from it |
| `letterbox` | Cinematic bars top/bottom |
| `invert` | Inverts the screen |
| `vortex` | Swirls pixels into a funnel around a point |
| `speed_lines` | Speed lines from the screen borders toward a point, random length per line |
| `slice_shift` | A straight line slices the frame; the halves slide along it |
| `noise_warp` | Animated value-noise field warps the picture in fluid patches |
| `solarize` | Bright pixels invert, dark stay untouched |
| `double_vision` | Two ghost copies of the frame with a slow drift |
| `eyelids` | Two soft curved dark lids slide in from top and bottom |
| `iris_wipe` | Old-film iris transition: everything outside a circle goes black |
| `digital_glitch` | Band tearing + RGB split in gated bursts |
| `vhs` | Worn tape: tracking band, wobble, colour bleed, washed contrast |
| `shockwave` | A refraction ring ripples outward from a point |
| `afterimage` | Decaying history echo — trails linger in a feedback buffer |
| `stop_motion` | The picture updates only a few times per second |

**Camera**

| Effect | What it does |
|---|---|
| `camera_shake` | Simplex-noise shake with smooth envelope |
| `camera_roll` | Dutch-angle tilt with optional sinusoidal wobble |
| `fov_modifier` | Field-of-view change |

**World overlays** — `block_tint`, `block_outline`, `light_beam` (soft vertical column), `pulse_ring` (expanding ring), `guide_line` (dashed parabola arc)
**Entity effects** — `entity_tint`, `entity_outline` (by UUID), `entity_displace` (per-vertex displaced echo)

---

## Quick start

All mutating commands require operator rights (`/vfx list` is open to all).

```
/vfx play vfxweaver:screen_flash
/vfx play vfxweaver:speed_lines {count:80, length:0.8, seed:"t * 2.0"}
/vfx playat vfxweaver:block_outline 100 64 200
/vfx playentity vfxweaver:entity_outline @e[type=!player,distance=..10]
/vfx stop vfxweaver:speed_lines
/vfx set vfxweaver:motion_blur {intensity:0.8}     # live override a running effect
/vfx list
```

## Datapack effects (no modding)

Drop a JSON into `data/<namespace>/vfx/<name>.json`, run `/reload`, and the effect id is `<namespace>:<name>`:

```json
{
  "type": "speed_lines",
  "duration": 60,
  "easing": "ease_out_cubic",
  "params": {
    "count": 80,
    "length": { "expr": "0.4 + 0.4 * t" },
    "seed": { "expr": "t * 2.0" },
    "intensity": 1.0
  }
}
```

- Animated params via keyframes (`t = 0..1` progress) or math expressions over player state (health, hunger, speed, light level, time of day, position).
- World/camera/player bindings (`bind: screen_x`, `bind: proximity`, ...) so effects follow the world instead of the screen.
- Collections — play several child effects with per-child delays.
- Definitions sync automatically to clients on join and after `/reload` on dedicated servers.

---

## Java API (for other mods)

Static entry point `dev.vfxweaver.api.VFXAPI` — a one-liner per call.

```java
// Server → client
VFXAPI.sendEffect(serverPlayer,
    Identifier.fromNamespaceAndPath("vfxweaver", "screen_flash"),
    Map.of("intensity", 0.8F), null);

// With a world anchor (block/entity effects follow the position)
VFXAPI.sendEffect(player, effectId, somePos, Map.of(), null);

// Locally on the client (no packet)
VFXAPI.playEffect(Identifier.fromNamespaceAndPath("vfxweaver", "camera_shake"),
    20, Map.of("amplitude_x", 0.2F), null);

// Stop / live-tune
VFXAPI.sendStop(player, effectId);
VFXAPI.sendSetParam(player, effectId, "intensity", 0.3F);
VFXAPI.sendKeyframe(player, effectId, "intensity", 10, 1.0F, EasingType.EASE_OUT_CUBIC);
```

The network layer (`vfxweaver:vfx_trigger`) is protocol-versioned; the client silently ignores packets with an incompatible version. Full API, packet layout and definition-registry access are documented in the repo's `docs/API.md`.

---

## Docs

Commands, effect params, datapack format, bindings — the full usage guide is in the project repo. Build it yourself with `./gradlew build` (requires JDK 25), or grab a release jar from the Releases tab.

- Repo: https://github.com/Artition/VFX-Weaver
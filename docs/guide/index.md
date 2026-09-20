# Getting started

A client-side VFX library for Minecraft 26.2 / 26.1.x / 1.21.11 (Fabric and NeoForge). Screen post-processing (ping-pong FBO), camera shake, world overlays (block tint/outline), entity effects (tint/outline by UUID), keyframe animation, world/camera/player bindings, datapacks, network triggers and a public Java API.

- Guide version: 35 (see [docs/CHANGELOG.md](../CHANGELOG.md) for history)
- Mod: `vfxweaver-1.2.0.jar` (one jar per Minecraft line and loader; Fabric requires Fabric API, NeoForge builds use the `-neoforge` suffix)

Files: `data/<namespace>/vfx/<name>.json` and `data/<namespace>/vfx_curves/<name>.json`. After edits — `/reload`. The effect id = `<namespace>:<name>`. On a dedicated server, definitions and curves are automatically synced to clients on player join and after `/reload`, so custom (datapack) effects work for all players, not just on the server.

The mutating commands `/vfx play`, `/vfx playat`, `/vfx playentity`, `/vfx stop`, `/vfx set` require operator rights (gamemaster level); `/vfx list` is open to everyone.

## Guide sections

- [Commands](commands.md) - every `/vfx` subcommand, persistent effects and collections
- [Effects](effects.md) - effect types and the datapack format
- [Surface pattern](surface-pattern.md) - the `surface` and `pattern` blocks
- [Value graphs](graph.md) - drive numeric inputs from a graph of nodes
- [Per-pixel fields](fields.md) - make one input vary per pixel
- [Masks](masks.md) - restrict where an effect applies
- [Custom particles](particles.md) - block/item model particles and glowing spark presets
- [Guide changelog](changelog.md) - versioned history of this guide
- [Java API](../API.md) - the `VFXAPI` reference
- [Architecture](../ARCHITECTURE.md) - how rendering works under the hood

## 1.1 Quickstart: first effect in 2 minutes

1. Create `data/mymap/vfx/first_blur.json` inside your datapack:
   ```json
   { "type": "blur", "duration": 100, "params": { "radius": 8 } }
   ```
2. In game: `/reload` (datapacks reload automatically on join).
3. Run `/vfx play mymap:first_blur` — the screen blurs for 5 seconds and fades back.
4. `/vfx list` shows all loaded effect ids (built-ins + your datapack ones).

That is the whole loop: **file → /reload → /vfx play**. Everything else in this guide is variations of it.

## 6. Built-in effects

Built-ins ship as regular datapack JSON inside the mod jar (`data/vfxweaver/vfx/*.json`) — they load, sync and can be overridden by higher-priority packs exactly like custom definitions, and a broken one shows up in `/vfx list`/`/vfx validate` like any other. To tweak a built-in, copy its JSON out of the jar (`vfxweaver-1.1.0.jar → data/vfxweaver/vfx/…`) into your datapack under a new id.

Post-processing: `vfxweaver:chromatic_aberration`, `vfxweaver:color_grade`, `vfxweaver:distortion`, `vfxweaver:dent`, `vfxweaver:gradient_map`, `vfxweaver:posterize`, `vfxweaver:blur`, `vfxweaver:pixelate`, `vfxweaver:hue_isolation`, `vfxweaver:vignette`, `vfxweaver:screen_flash`, `vfxweaver:motion_blur`, `vfxweaver:bloom`, `vfxweaver:film_grain`, `vfxweaver:scanlines`, `vfxweaver:depth_of_field`, `vfxweaver:letterbox`, `vfxweaver:invert`, `vfxweaver:vortex`, `vfxweaver:speed_lines`, `vfxweaver:slice_shift`, `vfxweaver:noise_warp`, `vfxweaver:solarize`, `vfxweaver:double_vision`, `vfxweaver:eyelids`, `vfxweaver:iris_wipe`, `vfxweaver:digital_glitch`, `vfxweaver:vhs`, `vfxweaver:shockwave`, `vfxweaver:afterimage`, `vfxweaver:stop_motion`, `vfxweaver:graph_demo`, `vfxweaver:dent_field_demo`.

World overlays: `vfxweaver:block_tint`, `vfxweaver:block_outline`, `vfxweaver:light_beam`, `vfxweaver:pulse_ring`, `vfxweaver:guide_line`, `vfxweaver:particles`, `vfxweaver:block_chain`.

Entity effects: `vfxweaver:entity_tint`, `vfxweaver:entity_outline`, `vfxweaver:entity_displace`.

Misc: `vfxweaver:camera_shake`, `vfxweaver:camera_roll`, `vfxweaver:fov_modifier`.

All have fade animation (40 ticks, except where noted); params can be overridden by collections.

## 7. Java API (for other mods)

Briefly:

```java
VFXAPI.sendEffect(serverPlayer, effectId, Map.of(), null); // server → client
VFXAPI.playEffect(effectId, 0, Map.of("radius", 8.0F), EasingType.EASE_OUT_CUBIC); // locally on the client
```

Full reference (all `VFXAPI` methods, `VFXLocalDispatcher`, the `vfxweaver:vfx_trigger` network packet format) — **[docs/API.md](../API.md)**.

Live-editing methods worth knowing:

```java
// Replace a running effect's parameter with a math expression (same syntax as the JSON "expr",
// per-instance seed), without restarting the timeline:
VFXAPI.sendSetParamExpr(player, effectId, "radius", "1.5 + 0.5*sin(t/10)");

// Smoothly move a running world-overlay instance to a new exact point (sub-block precision).
// Call it every tick from your own code to make the effect follow any scripted path:
VFXAPI.sendMove(player, effectId, instanceId, new Vec3(x, y, z));
```

A client mod may also **request** effects from the server with the serverbound `vfxweaver:vfx_request` packet (see API.md): without the `broadcast` flag the effect plays only for the requesting client; with `broadcast: true` it plays for every connected player — but the server grants broadcast only to operators (gamemaster level), so regular-player clients cannot spam effects at others. Note: custom named easing curves are not preserved on the request path (built-in easing names only).

Effects sent via `VFXAPI.sendEffect` are remembered server-side: if the player reconnects (or a new player joins) while the effect is still running, it is re-applied automatically with its remaining duration. Persistent (`-1`) effects are always re-applied. Stopping an effect (`sendStop`) also forgets it.

## 8. Flashback compatibility

[Flashback](https://modrinth.com/mod/flashback) is an optional companion (a soft dependency — the mod works fine without it). This compatibility is **Fabric-only**: Flashback has no NeoForge build, so on NeoForge the mod skips the recording layer entirely. When Flashback is installed, **every effect the client starts** — client-local ones (`VFXAPI.playEffect` and friends) as well as server-triggered ones — is written into the replay as a custom Flashback action, so it appears at the exact tick it was played, along with any live edits and the datapack definitions it needs. (Flashback cannot replay our custom payload packets on its own, which is why the client records server-triggered effects too.)

Things to know:

- Effects played with a **negative (persistent) duration** are not recorded — without a recorded stop event they would loop forever during playback.
- The recording requires no config: start a Flashback recording, play effects, done.
- No interaction with the Flashback editor keyframes; this is replay recording/playback only.

## 9. How it renders (for debugging)

Post-processing pipeline, world overlays, effect clock, load limits and fault tolerance — **[docs/ARCHITECTURE.md](../ARCHITECTURE.md)**.

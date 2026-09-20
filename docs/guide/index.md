# Getting started

A client-side VFX library for Minecraft 26.2 / 26.1.x / 1.21.11 (Fabric and NeoForge). Screen
post-processing (ping-pong FBO), camera shake, world overlays (block tint/outline), entity effects
(tint/outline by UUID), keyframe animation, world/camera/player bindings, datapacks, network triggers
and a public Java API.

- Guide version: 47 (see [docs/CHANGELOG.md](../CHANGELOG.md) for history)
- Mod: `vfxweaver-2.0.0.jar` (one jar per Minecraft line and loader; Fabric needs Fabric API,
  NeoForge builds use the `-neoforge` suffix)

Effects are files: `data/<namespace>/vfx/<name>.json` and `data/<namespace>/vfx_curves/<name>.json`.
After edits run `/reload`. The effect id = `<namespace>:<name>`. On a dedicated server, definitions
and curves sync to clients on join and after `/reload`, so custom effects work for all players.

The mutating commands `/vfx play`, `/vfx playat`, `/vfx playentity`, `/vfx stop` and `/vfx set`
require operator rights (gamemaster level); `/vfx list` is open to everyone.

## Guide sections

- [Effects](effects/index.md) - every effect type, one page each, in a browsable tree
- [Commands](commands.md) - every `/vfx` subcommand
- [Datapack format](datapack/format.md) - files, definition fields, positions, validation
- [Animating a param](datapack/params.md) - keyframes, bindings, easings
- [Expressions (`expr`)](datapack/expr.md) - drive a param from a formula
- [Value graphs](datapack/graph.md) - drive inputs from a graph of nodes
- [Per-pixel fields](datapack/fields.md) - make one input vary per pixel
- [Masks](datapack/masks.md) - restrict where an effect applies
- [Custom particles](datapack/particles.md) - block/item model particles and glowing spark presets
- [Guide changelog](changelog.md) - versioned history of this guide
- [Java API](../API.md) - the `VFXAPI` reference
- [Architecture](../ARCHITECTURE.md) - how rendering works under the hood

## Quickstart: first effect in 2 minutes

1. Create `data/mymap/vfx/first_blur.json` inside your datapack:
   ```json
   { "type": "blur", "duration": 100, "params": { "radius": 8 } }
   ```
2. In game: `/reload` (datapacks reload automatically on join).
3. Run `/vfx play mymap:first_blur` - the screen blurs for 5 seconds and fades back.
4. `/vfx list` shows all loaded effect ids (built-ins + your datapack ones).

That is the whole loop: **file → /reload → /vfx play**. Everything else is variations of it.

## Built-in effects

Built-ins ship as regular datapack JSON inside the mod jar (`data/vfxweaver/vfx/*.json`) and load,
sync and override like any custom definition. The full list is on the
[Effects page](effects/index.md#built-in-effects).

## Java API (for other mods)

```java
VFXAPI.sendEffect(serverPlayer, effectId, Map.of(), null); // server → client
VFXAPI.playEffect(effectId, 0, Map.of("radius", 8.0F), EasingType.EASE_OUT_CUBIC); // locally on the client
```

Full reference (all `VFXAPI` methods, `VFXLocalDispatcher`, the `vfxweaver:vfx_trigger` network packet
format) - **[docs/API.md](../API.md)**.

Live-editing methods worth knowing:

```java
// Replace a running effect's parameter with a math expression (same syntax as the JSON "expr",
// per-instance seed), without restarting the timeline:
VFXAPI.sendSetParamExpr(player, effectId, "radius", "1.5 + 0.5*sin(t/10)");

// Smoothly move a running world-overlay instance to a new exact point (sub-block precision).
// Call it every tick from your own code to make the effect follow any scripted path:
VFXAPI.sendMove(player, effectId, instanceId, new Vec3(x, y, z));
```

A client mod may also **request** effects from the server with the serverbound
`vfxweaver:vfx_request` packet (see API.md): without the `broadcast` flag the effect plays only for
the requesting client; with `broadcast: true` it plays for every connected player - but the server
grants broadcast only to operators (gamemaster level), so regular-player clients cannot spam effects
at others. Custom named easing curves are not preserved on the request path (built-in easing names
only).

Effects sent via `VFXAPI.sendEffect` are remembered server-side: if the player reconnects (or a new
player joins) while the effect is still running, it is re-applied with its remaining duration.
Persistent (`-1`) effects are always re-applied. Stopping an effect (`sendStop`) forgets it.

## Flashback compatibility

[Flashback](https://modrinth.com/mod/flashback) is an optional companion (a soft dependency - the mod
works without it). This compatibility is **Fabric-only**: Flashback has no NeoForge build, so on
NeoForge the recording layer is skipped. When Flashback is installed, **every effect the client
starts** - client-local ones and server-triggered ones - is written into the replay as a custom
Flashback action, at the exact tick it was played, along with any live edits and the datapack
definitions it needs. (Flashback cannot replay our custom payload packets on its own, which is why
the client records server-triggered effects too.)

Things to know:

- Effects played with a **negative (persistent) duration** are not recorded - without a recorded stop
  event they would loop forever during playback.
- The recording needs no config: start a Flashback recording, play effects, done.
- No interaction with the Flashback editor keyframes; this is replay recording/playback only.

## How it renders (for debugging)

Post-processing pipeline, world overlays, effect clock, load limits and fault tolerance -
**[docs/ARCHITECTURE.md](../ARCHITECTURE.md)**.

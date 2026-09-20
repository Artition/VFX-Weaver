# Spark presets

A spark is a glowing, camera-facing sprite for the `particles` effect. Select one with
`"particle": "<namespace>:<name>"` (a `vfx_particles` JSON with `"kind": "spark"`), or the
literal `"particle": "spark"` for the built-in defaults. Spark presets use the same two-layer
registry as block/item presets (datapack + `VFXAPI.registerSpark`, both capped at 256, never
synced) and share the `vfx_particles` directory; `"kind"` selects the parser. The shared directory,
two-layer rules, caps and per-file parse isolation are described on
[Block and item particles](../particles.md).

| field | type | default | meaning |
|---|---|---|---|
| `kind` | string | — | Must be `"spark"` to select the spark parser (block/item presets omit it) |
| `count` | int | `200` | Total sparks emitted across the effect's whole `duration` (spread evenly, scaled by the effect weight), `0..10000` |
| `speed` | float | `0.4` | Launch speed, blocks/tick (`0..16`) |
| `spread` | float | `0.6` | Aim-cone jitter `0..1`; only meaningful with a two-slot aimed emitter (`aim >= 0.5`). A non-aimed emitter launches in a uniformly random direction |
| `life` | int | `25` | Lifetime in ticks, clamped to `[1, 1200]` |
| `gravity` | float | `0.4` | Downward acceleration per tick, in multiples of `0.04` blocks/tick² (`0..64`) |
| `bounce` | float | `0.2` | Restitution `0..1` of the normal velocity on world contact |
| `size` | float | `0.08` | Peak sprite size in blocks, before the size curve (`0.01..4`) |
| `trail` | int | `0` | Tail length in ticks (`0` = off), clamped to `[0, 16]`; drawn as a stretched fading quad from the position that many ticks ago |
| `glow` | bool | `true` | `true` = additive blending (glow), `false` = translucent |
| `size_curve` | array | `0→0, 0.1→1, 1→0` | `{ "t": 0..1, "value": multiplier }` points, strictly ascending `t`; the value multiplies `size` (and is used as alpha). 2..32 points |
| `color_curve` | array | white→orange→ember | `{ "t": 0..1, "r": 0..1, "g": 0..1, "b": 0..1 }` points, strictly ascending `t`. 2..32 points |
| `radius` / `height` / `turns` | float | `2.0` / `3.0` / `2.0` | **Effect params**, not preset fields: the emitter shape sampling, reused from the `particles` effect |
| `shape` | string | `"sphere"` | **Effect field**: `point`/`sphere`/`ring`/`helix`/`line`/`cube`, reused from the `particles` effect |

Numeric knobs (`count`, `speed`, `spread`, `life`, `gravity`, `bounce`, `size`, `trail`) can be
overridden by effect `params`; `glow` and the curves are structural and cannot — animating them
would mean resampling every live spark. `kind` is the discriminator and is structural.

The curves are sampled by normalized life `t = age / life` in `[0,1]` with linear interpolation:
the size curve multiplies `size`, the colour curve supplies the RGB. A malformed curve (not an
array, fewer than two points, more than 32 points, or a `t` that is not strictly ascending) is a
per-file parse error.

**How it is simulated and rendered.** `VFXSparkEngine` (client) mirrors the block-particle engine:
one bucket per running effect instance plus a standalone bucket for API one-shot spawns, a fixed
**1-tick integration** driven by the shared effect clock — gravity (`0.04 × gravity` per tick), a
fixed air drag of `0.97`, optional world collision that reflects the normal velocity by `bounce` —
and a prev-to-pos interpolation the renderer samples this frame. Emission is budgeted so `count`
sparks are spread over the effect's whole duration and scaled by the effect's fade weight. Trails
keep a short ring buffer of past positions; a trail spark is drawn as a stretched, fading quad from
the position `trail` ticks ago. `VFXWorldOverlayRenderer.renderSparks` draws the sprites as
camera-facing quads: additive blending (`GLOW_OCCLUDED`) when `glow` is `true`, translucent
(`SPARK_OCCLUDED`) when it is `false`. Sparks are capped at **4096 live globally**, **1024 per
effect instance** and **512 tracked effect instances**, with at most 4 integration steps per frame.

**Two-layer rule and the Java API.** Like block/item presets: the datapack set (reloaded with
`/reload`) and a code-registered local set written with `VFXAPI.registerSpark(id, spec)`. The local
layer survives `/reload` and is private to this client; the datapack layer wins for the same id.
`VFXAPI.unregisterSpark(id)` removes a local preset and `VFXAPI.spark(id)` looks one up.
`VFXAPI.spawnSpark(spec, position, velocity)` spawns a single spark immediately on the client (no
packet, no effect instance).

```java
// Register a spark preset in code (survives /reload, never synced, datapack wins)
VFXAPI.registerSpark(Identifier.of("mymod", "ember"),
	VFXSparkSpec.builder().count(240).speed(0.55F).trail(3).glow(true).build());

VFXSparkSpec preset = VFXAPI.spark(Identifier.of("mymod", "ember"));
VFXAPI.unregisterSpark(Identifier.of("mymod", "ember"));

// Spawn a single spark immediately on this client
VFXAPI.spawnSpark(preset, new Vec3(x, y, z), new Vec3(0.0, 0.4, 0.0));
```

`VFXSparkSpec.builder()` sets `count`, `speed`, `spread`, `life`, `gravity`, `bounce`, `size`,
`trail`, `glow` and the `sizeCurve`/`colorCurve` arrays; see [docs/API.md](../../API.md).

The built-in `vfxweaver:ember` spark preset ships with two playable reference effects that
emit it: `vfxweaver:ember` (a tight burst, the preset's own `count`) and `vfxweaver:sparks`
(a wider burst that overrides `count`/`radius`). Play either with
`/vfx play vfxweaver:ember` or `/vfx play vfxweaver:sparks`; both spawn at the player.

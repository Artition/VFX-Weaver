# heartbeat

`type: "heartbeat"`

Plays a **periodic low thump** for the effect's lifetime — no audio file in any
resource pack. One voice per effect instance: it opens when the instance starts,
follows the animated `rate`, `volume` and `pitch` every frame, and closes when the
instance ends (or is stopped, faded out, or rebuilt by a replay seek).

Each beat is a "lub-dub" pair: the beat phase advances `rate / 60` cycles per
second, and every wrap fires a lub thump plus a dub thump at `0.6` of the amplitude,
~0.28 s later at rest and easing into even half-beat spacing as `rate` climbs —
slow hearts beat in pairs, fast hearts beat evenly. Each thump is a 5 ms attack from silence into an exponential decay
(time constant ~80 ms at rest, tightening to ~40 ms at high rates so fast beats stay crisp) of the `pitch` sine, so beats start and end at zero — no clicks,
even when the dub lands on the next lub or `rate` animates mid-beat.

## Fields

> Audio effects also accept the [shared definition fields](../index.md#shared-fields)
> (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`). They do **not**
> accept `screen_layer` or `order`, and they render no geometry.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `rate` | float | `60.0` | Beat rate in beats per minute. No upper cap; `0` or an unresolvable value is silence |
| `volume` | float | `0.0` | Linear gain, `0..1`. Fades with the effect weight, so `fade_ticks` fades the thump in and out |
| `pitch` | float | `55.0` | Thump tone frequency in Hz. No upper limit; `0` or an unresolvable value is silence |

All are ordinary animatable params: `start`/`end`, `keyframes`, `expr`, graphs, `setParam`
and `bind` all work. `volume`'s neutral is `0.0` (that is what the fade blends toward);
`rate` and `pitch` have no neutral and are read as authored. The beat phase integrates
per sample, so an animated `rate` glides instead of jumping.

The voice gain each frame is `volume × weight × distance`, where `weight` is the
effect's fade weight and `distance` is a linear falloff from the camera: full volume
within 8 blocks, silent past 48 blocks.

## Positioning

Like a world overlay, the voice is heard from a world point (mono — there is no stereo
panning, only distance). First source wins:

1. a runtime move (`sendMove` / network MOVE),
2. the first static `positions` entry (block-centred),
3. the first entity anchor's live position (an untracked entity is silent that frame),
4. the authored `pos_x` / `pos_y` / `pos_z` triple,
5. otherwise the **local-player position** (interpolated snapshot), so a plain
   `/vfx play vfxweaver:heartbeat` follows the viewer.

`positions` and `region` work as on [Datapack format](../../datapack/format.md#positions-and-entity-anchors).

## Example

```json
{
	"type": "heartbeat",
	"duration": 100,
	"fade_ticks": 20,
	"params": {
		"rate": 60.0,
		"volume": 0.5,
		"pitch": 55.0
	}
}
```

Five seconds of a resting thump that fades in and out over one second each. Race it
for a chase cue:

```json
{
	"type": "heartbeat",
	"loop": true,
	"persistent": true,
	"fade_ticks": 20,
	"params": {
		"rate": { "keyframes": [{ "time": 0, "value": 60.0 }, { "time": 200, "value": 140.0 }] },
		"volume": 0.4,
		"pitch": 55.0
	}
}
```

```
/vfx play vfxweaver:heartbeat
```

## Honest limits

- **Paused game, frozen heartbeat.** Sample timing runs on the voice's own steady
  clock, gated by the shared effect clock: pausing (or a paused replay) freezes the gate,
  so no new samples are synthesised and the buffered remainder drains in under half a
  second. An unfocused window no longer starves the voice — slow frames emit their
  wall-clock debt instead of going silent. A replay seek resyncs seamlessly instead of
  bursting.
- **The vanilla mixer is bypassed.** The thump is synthesised into the OS mixer directly,
  so the game's volume sliders, mute and resource-pack sounds do not affect it — control
  loudness with the `volume` param. A machine with no audio device (or a headless JVM)
  stays silent and logs once instead of failing.
- **`stopAll` cuts immediately.** Stopping one instance fades it out over `fade_ticks`;
  stopping everything (world exit, `/vfx stopall`, client shutdown) closes the voices at
  once, like the aux windows.

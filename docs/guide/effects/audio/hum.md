# hum

`type: "hum"`

Plays a **generated sine tone** for the effect's lifetime — no audio file in any resource
pack. One voice per effect instance: it opens when the instance starts, follows the
animated `frequency` and `volume` every frame, and closes when the instance ends (or is
stopped, faded out, or rebuilt by a replay seek).

## Fields

> Audio effects also accept the [shared definition fields](../index.md#shared-fields)
> (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`). They do **not**
> accept `screen_layer` or `order`, and they render no geometry.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `frequency` | float | `440.0` | Sine frequency in Hz. No upper limit; `0` or an unresolvable value is silence |
| `volume` | float | `0.0` | Linear gain, `0..1`. Fades with the effect weight, so `fade_ticks` fades the hum in and out |

Both are ordinary animatable params: `start`/`end`, `keyframes`, `expr`, graphs, `setParam`
and `bind` all work. `volume`'s neutral is `0.0` (that is what the fade blends toward);
`frequency` has no neutral and is read as authored.

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
   `/vfx play vfxweaver:hum` follows the viewer.

`positions` and `region` work as on [Datapack format](../../datapack/format.md#positions-and-entity-anchors).

## Example

```json
{
	"type": "hum",
	"duration": 100,
	"fade_ticks": 20,
	"params": {
		"frequency": 110.0,
		"volume": 0.5
	}
}
```

A five-second 110 Hz hum that fades in and out over one second each. Loop it for a drone:

```json
{
	"type": "hum",
	"loop": true,
	"persistent": true,
	"fade_ticks": 20,
	"params": {
		"frequency": { "keyframes": [{ "time": 0, "value": 55.0 }, { "time": 200, "value": 220.0 }] },
		"volume": 0.4
	}
}
```

```
/vfx play vfxweaver:hum
```

## Honest limits

- **Paused game, frozen hum.** Sample timing runs on the voice's own steady
  clock, gated by the shared effect clock: pausing (or a paused replay) freezes the gate,
  so no new samples are synthesised and the buffered remainder drains in under half a
  second. An unfocused window no longer starves the voice — slow frames emit their
  wall-clock debt instead of going silent. A replay seek resyncs seamlessly instead of
  bursting.
- **The vanilla mixer is bypassed.** The tone is synthesised into the OS mixer directly,
  so the game's volume sliders, mute and resource-pack sounds do not affect it — control
  loudness with the `volume` param. A machine with no audio device (or a headless JVM)
  stays silent and logs once instead of failing.
- **`stopAll` cuts immediately.** Stopping one instance fades it out over `fade_ticks`;
  stopping everything (world exit, `/vfx stopall`, client shutdown) closes the voices at
  once, like the aux windows.

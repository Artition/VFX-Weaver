# heartbeat

`type: "heartbeat"`

Plays a **periodic low thump** for the effect's lifetime — no audio file in any
resource pack. One voice per effect instance: it opens when the instance starts,
follows the animated `rate`, `volume` and `pitch` every frame, and closes when the
instance ends (or is stopped, faded out, or rebuilt by a replay seek).

Each beat is a "lub-dub" pair: the beat phase advances `rate / 60` cycles per
second and gates a decaying `pitch` sine, the second thump landing 0.16 of a beat
later at 0.6 of the amplitude.

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

`positions` and `region` work as on [Datapack format](../datapack/format.md#positions-and-entity-anchors).

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

- **Paused game, frozen heartbeat.** The voice is driven by the shared effect clock, so pausing
  (or a paused replay) stops new samples; the buffered remainder drains in a fraction of
  a second. A replay seek resyncs silently instead of bursting.
- **The vanilla mixer is bypassed.** The thump is synthesised into the OS mixer directly,
  so the game's volume sliders, mute and resource-pack sounds do not affect it — control
  loudness with the `volume` param. A machine with no audio device (or a headless JVM)
  stays silent and logs once instead of failing.
- **`stopAll` cuts immediately.** Stopping one instance fades it out over `fade_ticks`;
  stopping everything (world exit, `/vfx stopall`, client shutdown) closes the voices at
  once, like the aux windows.

# noise

`type: "noise"`

Plays **generated white noise** for the effect's lifetime — no audio file in any resource
pack. One voice per effect instance: it opens when the instance starts, follows the
animated `volume`, `brightness`, `jitter`, `jitter_rate` and `seed` every frame, and closes
when the instance ends (or is stopped, faded out, or rebuilt by a replay seek).

## Fields

> Audio effects also accept the [shared definition fields](../index.md#shared-fields)
> (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`). They do **not**
> accept `screen_layer` or `order`, and they render no geometry.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `volume` | float | `0.0` | Linear gain, `0..1`. Fades with the effect weight, so `fade_ticks` fades the noise in and out |
| `brightness` | float | `0.5` | Crisp/soft blend, `0..1`. `1` is raw white noise, `0` its lowpassed (mellow) copy |
| `jitter` | float | `0.0` | Random-walk step size for the playback-rate and volume wander. `0` is steady noise |
| `jitter_rate` | float | `1.0` | Wander speed multiplier: how fast the walk drifts per frame |
| `seed` | float | `0.0` | Walk seed. Changing it reseeds and restarts the texture, so a value replays the same wander |

All are ordinary animatable params: `start`/`end`, `keyframes`, `expr`, graphs, `setParam`
and `bind` all work. `volume`'s neutral is `0.0` (that is what the fade blends toward);
the rest have no neutral and are read as authored.

The voice gain each frame is `volume × weight × distance × wander`, where `weight` is the
effect's fade weight, `distance` is a linear falloff from the camera (full volume within
8 blocks, silent past 48 blocks), and `wander` is the gentle volume random walk (`jitter`
scaled, mean-reverted, clamped to `0..2` — silent only while `volume` or `weight` is).

## Positioning

Like a world overlay, the voice is heard from a world point (mono — there is no stereo
panning, only distance). First source wins:

1. a runtime move (`sendMove` / network MOVE),
2. the first static `positions` entry (block-centred),
3. the first entity anchor's live position (an untracked entity is silent that frame),
4. the authored `pos_x` / `pos_y` / `pos_z` triple,
5. otherwise the **local-player position** (interpolated snapshot), so a plain
   `/vfx play vfxweaver:noise` follows the viewer.

`positions` and `region` work as on [Datapack format](../../datapack/format.md#positions-and-entity-anchors).

## Example

```json
{
	"type": "noise",
	"duration": 100,
	"fade_ticks": 20,
	"params": {
		"volume": 0.4,
		"brightness": 0.8,
		"jitter": 0.05,
		"seed": 7.0
	}
}
```

Five seconds of bright radio static that fades in and out over one second each. Loop it for rain:

```json
{
	"type": "noise",
	"loop": true,
	"persistent": true,
	"fade_ticks": 40,
	"params": {
		"volume": 0.3,
		"brightness": 0.25,
		"jitter": 0.02,
		"jitter_rate": 0.5,
		"seed": 3.0
	}
}
```

```
/vfx play vfxweaver:noise
```

## Honest limits

- **Paused game, frozen noise.** The voice is driven by the shared effect clock, so pausing
  (or a paused replay) stops new samples; the buffered remainder drains in a fraction of
  a second. A replay seek resyncs silently instead of bursting.
- **The vanilla mixer is bypassed.** The noise is synthesised into the OS mixer directly,
  so the game's volume sliders, mute and resource-pack sounds do not affect it — control
  loudness with the `volume` param. A machine with no audio device (or a headless JVM)
  stays silent and logs once instead of failing.
- **`stopAll` cuts immediately.** Stopping one instance fades it out over `fade_ticks`;
  stopping everything (world exit, `/vfx stopall`, client shutdown) closes the voices at
  once, like the aux windows.

# volume_duck

`type: "volume_duck"`

Scales one vanilla sound category down while the effect is active — a global duck, not a
voice. There is no synthesis and no world position: every frame the effect sets its
channel's game-options level to the pre-duck level times `volume`, blended by the fade
weight, and hands the level back (restores the captured value) as soon as the duck ends.

## Fields

> Audio effects also accept the [shared definition fields](../index.md#shared-fields)
> (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`). They do **not**
> accept `screen_layer` or `order`, and they render no geometry. `volume_duck` additionally
> ignores `positions`/`region` and entity anchors: it is global, not positional.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `channel` | float or string | `0.0` | Which sound category to scale: a name (`master`, `music`, `records`, `weather`, `blocks`, `hostile`, `neutral`, `players`, `ambient`, `voice`, `ui`, case-insensitive) or a `SoundSource` ordinal `0..10` (see the table below). Rounded when numeric, then clamped into range |
| `volume` | float | `1.0` | The target multiplier, `0..1`, applied at full fade weight. `0.2` plays the channel at a fifth of its level |

Both are ordinary animatable params: `start`/`end`, `keyframes`, `expr`, graphs, `setParam`
and `bind` all work. `volume`'s neutral is `1.0` (that is what the fade blends toward: no
duck); `channel` has no neutral and is read as authored. The name form also works in
datapack JSON (`"channel": "music"`) and in commands (`/vfx play vfxweaver:volume_duck
{[channel:music],[volume:0.2]}`); an unknown name warns once and the duck safely does
nothing instead of ducking the wrong category.

The category level each frame is `base × (1 - (1 - volume) × weight)`, where `base` is the
level captured when the first duck touched that channel and `weight` is the effect's fade
weight — so `fade_ticks` fades the duck in and out. Several ducks on one channel resolve
last-writer-wins in active-list order.

| `channel` | Category |
|---|---|
| `0` | Master |
| `1` | Music |
| `2` | Records (jukebox / music discs) |
| `3` | Weather |
| `4` | Blocks |
| `5` | Hostile creatures |
| `6` | Neutral creatures |
| `7` | Players |
| `8` | Ambient / environment |
| `9` | Voice / speech |
| `10` | UI |

## Example

```json
{
	"type": "volume_duck",
	"duration": 100,
	"fade_ticks": 20,
	"params": {
		"channel": 1.0,
		"volume": 0.2
	}
}
```

Five seconds of music at a fifth of its level, fading in and out over one second each.
Duck the hostile mobs instead for a stealth cue:

```json
{
	"type": "volume_duck",
	"loop": true,
	"persistent": true,
	"fade_ticks": 40,
	"params": {
		"channel": 5.0,
		"volume": 0.0
	}
}
```

```
/vfx play vfxweaver:volume_duck
```

## Honest limits

- **Your sliders move under it.** While a duck is active its channel's options-screen slider
  shows (and plays) the scaled level; a slider drag on that channel is overwritten the next
  frame, and the pre-duck level is restored when the duck ends — including a change you made
  mid-duck, which is lost. Channels no duck touches are never written.
- **Nothing is saved.** The scaled levels are applied through the live options and never
  written to `options.txt`; a restart always loads your saved levels.
- **Recapture per activation.** The pre-duck level is captured when the first duck touches
  a channel. Two ducks on different channels each capture and restore their own; two on
  the same channel share one capture, last-writer-wins.
- **The ordinal table is vanilla's.** A numeric `channel` indexes `SoundSource.values()` in game
  order; a future Minecraft reorder would shift the mapping (the names above are 26.1's).
  Prefer names (`"channel": "music"`): they resolve through the enum whatever its order.
  `stopAll`, world exit and client shutdown restore every ducked channel at once.

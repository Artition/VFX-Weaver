# Animating a param

Params live in the definition's `params` object. Each value is one of the forms below; the effect
evaluates it every frame. A param keeps the effect type's default when omitted.

| Form | Value | Result |
|---|---|---|
| Constant | `4.0` | The literal value, every frame |
| Animation | `{ "start": 0.8, "end": 0.0 }` | Linear ramp over the duration, shaped by the definition's `easing` |
| Keyframes | `{ "keyframes": [ ... ] }` | Piecewise curve; each segment has its own optional `easing` |
| Binding | `{ "bind": "proximity", "pos": [8,80,8], "range": 32 }` | Recomputed every frame from world/camera/player state |
| Expression | `{ "expr": "abs(sin(t * 0.1)) * 0.8" }` | Compiled once per instance, evaluated every frame - see [expr](expr.md) |
| Multiplier | `{ "start": ..., "multiply": { "bind": ... } }` | Base (an above form) times a `multiply` spec |

A graph input (`{ "from": "<node>" }`) and a per-pixel `field` are the two other forms, documented on
[Value graphs](graph.md) and [Per-pixel fields](fields.md).

## Ways to set a param

```jsonc
"params": {
	// 1) Constant
	"radius": 4.0,

	// 2) Animation from start to end of the duration (definition easing)
	"intensity": { "start": 0.8, "end": 0.0 },

	// 3) Keyframes (time = ticks from start; each segment has its own easing)
	"brightness": { "keyframes": [
		{ "time": 0,  "value": 0.8,  "easing": "ease_out_quad" },
		{ "time": 30, "value": 1.25, "easing": "ease_in_quad" },
		{ "time": 60, "value": 0.8 }
	] },

	// 4) World/camera binding (recomputed every frame)
	"center_x": { "bind": "screen_x", "pos": [8, 80, 8] },
	"strength": { "bind": "proximity", "pos": [8, 80, 8], "range": 32, "scale": 0.9, "invert": false },

	// 5) Multiplier: base (keyframes/start-end/constant/binding) x multiplier
	"strength": {
		"keyframes": [ { "time": 0, "value": 0.8 }, { "time": 40, "value": 0.0 } ],
		"multiply": { "bind": "proximity", "pos": [8, 80, 8], "range": 64 }
	},

	// 6) Math expression
	"intensity": { "expr": "abs(sin(t * 0.1)) * 0.8 + random() * 0.2" }
}
```

A live keyframe with a **negative time** starts its segment at the current moment, pinning whatever
value the param has now - ramp a blur up, then fade it out from where it stands:

```java
VFXAPI.sendKeyframe(player, effect, "radius", -20, 0.0F, easing); // fade out over 20 ticks
```

Use a persistent effect (or a duration long enough to cover the segment) - a finished effect is
removed before the new segment can play. Command param-maps (`{[...]}`) accept floats only, so `expr`
works in datapack definitions, not on the command line.

## World and camera bindings

A binding value is recomputed every frame. Options: `pos` (for `screen_x`/`screen_y`, `proximity`,
`distance`, `look_at`), `yaw` + `pitch` (for `look`), `range`, `scale` (default 1) and `invert`.

| `bind` | Value | Meaning |
|---|---|---|
| `screen_x` / `screen_y` | UV 0..1 (−scale if behind the camera) | Screen position of the world point `pos` - the effect follows that point |
| `proximity` | 0..1 (x scale) | 1 near `pos`, smoothly to 0 at distance `range` (default 16). `invert: true` = 0 near, 1 far. Behind the camera = 0 |
| `look` | 0..1 (x scale) | 1 when the camera looks exactly at `yaw`/`pitch` (degrees), smoothly to 0 at angular deviation `range` (default 90°) |
| `look_at` | 0..1 (x scale) | Like `look`, but the target direction comes from a world `pos` anchor |
| `distance` | blocks (x scale) | Raw Euclidean distance from the camera to `pos` (real blocks) |
| `look_x` / `look_y` / `look_z` | −1..1 (x scale) | Components of the camera's look unit vector |
| `player_x` / `player_y` / `player_z` | world coords (x scale) | The local player's position along X/Y/Z |
| `camera_yaw_delta` | degrees/tick (x scale) | Camera yaw change between frames |
| `camera_pitch_delta` | degrees/tick (x scale) | Camera pitch change between frames |
| `health` | 0..1 (x scale) | Local player's health fraction (health / max). `invert: true` grows as HP drops |
| `hunger` | 0..1 (x scale) | Saturation fraction (food / 20) |
| `speed` | 0..1 (x scale) | Horizontal speed in blocks/s, normalized on `range` (default 5 = sprint) |
| `light_level` | 0..1 (x scale) | Light level at the player's position (block/sky light / 15) |
| `time_of_day` | 0..1 (x scale) | Fraction of the day cycle (0 = sunrise) |
| `scoreboard` | raw/range, clamped 0..1 (x scale) | A scoreboard value: `{ "bind": "scoreboard", "objective": "my_obj", "holder": "name" }`. Default holder = the local player's own score; normalized on `range` (default 16). Missing objective/score → 0. Works as a main value or a `multiply` |

A red vignette at low HP, and a blur while sprinting:

```jsonc
"intensity": { "bind": "health", "invert": true, "scale": 0.9 }
"radius":    { "bind": "speed", "range": 6, "scale": 8 }
```

A dent line stuck between two world points:

```jsonc
"params": {
	"line_mode": 1,
	"strength": 0.8,
	"radius": 0.1,
	"x0": { "bind": "screen_x", "pos": [8, 80, 8] },
	"y0": { "bind": "screen_y", "pos": [8, 80, 8] },
	"x1": { "bind": "screen_x", "pos": [12, 80, 8] },
	"y1": { "bind": "screen_y", "pos": [12, 80, 8] }
}
```

## Easings

Built-in names (case-insensitive, `-`/`_` equivalent):

`linear`, `ease_in_quad`, `ease_out_quad`, `ease_in_out_quad`, `ease_in_cubic`, `ease_out_cubic`,
`ease_in_out_cubic`, `ease_in_expo`, `ease_out_expo`, `smoothstep`.

Custom curves: a named datapack file `data/<namespace>/vfx_curves/<name>.json` with a `points` array
(`[t, v]` control points, `t` from 0 to 1), or an inline `curve` object:

```jsonc
"intensity": { "start": 0.0, "end": 1.0, "easing": { "curve": [[0, 0], [0.6, 0.9], [1, 1]] } }
```

Any `easing` field accepts a custom curve - the definition, a keyframe, a graph `curve` node or a
collection child.

## Sound params

The `sound` field plays a one-shot client sound. Its `volume`/`pitch` are reserved params
(constant, animation, bind or expression), read once at start:

```jsonc
{
	"type": "screen_flash",
	"sound": "minecraft:block.note_block.pling",
	"sound_pos": [8, 80, 8],
	"params": { "alpha": 0.3, "volume": 1.0, "pitch": 1.5 }
}
```

Without `sound_pos` the sound plays directly to the player. The position can be overridden through
the API with `sound_pos_x/y/z`.

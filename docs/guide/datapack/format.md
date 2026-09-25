# Datapack format

An effect is a JSON file at `data/<namespace>/vfx/<name>.json`; its id is `<namespace>:<name>`. Edit
the file and run `/reload` - no restart. On a dedicated server, definitions are synced to clients on
join and after `/reload`, so custom effects work for everyone.

| File | Holds |
|---|---|
| `data/<namespace>/vfx/<name>.json` | An effect definition (this page) |
| `data/<namespace>/vfx_curves/<name>.json` | A named easing curve used by `easing` fields |
| `data/<namespace>/vfx_particles/<name>.json` | A custom particle preset ([particles](particles.md)) |

The **effect id** = file path with `/` → `:`: `data/mymap/vfx/first_blur.json` → `mymap:first_blur`.
Built-ins (`data/vfxweaver/vfx/*.json`) use the same format and can be overridden by a
higher-priority pack. Test/demo packs should use their own namespace, never `vfxweaver:` (that would
shadow a shipped effect).

## Definition fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | string | — (required) | Effect type, one of the [effect pages](../effects/index.md) |
| `duration` | int | 40 | Duration in ticks (20 ticks = 1 s). For `loop`, the loop period |
| `easing` | string / object | `linear` | Curve for `start`→`end` params (see [easings](params.md#easings)) |
| `loop` | bool | false | The timeline plays in a circle and the effect is infinite until `/vfx stop` |
| `persistent` | bool | false | Infinite (params freeze at their final value) until `/vfx stop` |
| `fade_ticks` | int | 10 for persistent/loop, else 0 | Smooth fade-in on play and fade-out on stop. Drives params towards neutral values (brightness→1, radius→0, ...); positions are not distorted |
| `params` | object | — | Effect params ([param specs](params.md#ways-to-set-a-param)) |
| `effects` | array | — | Child effects for `type: collection` ([collection](../effects/collection.md)) |
| `sound` | string | — | Sound event id played on the client when the effect starts |
| `sound_pos` | array `[x,y,z]` | — | World coordinates for positional sound (like `/playsound ... x y z`: louder near, quieter far). Without it the sound plays directly to the player |
| `volume` | param | 1.0 | Sound volume (reserved param; constant, animation, bind or expression, read once at start) |
| `pitch` | param | 1.0 | Sound pitch (reserved param) |
| `positions` | array | — | World coordinates for world overlays and `surface_pattern` (see below). Without it `params.pos_x/y/z` is used |
| `particle` | string | — | `particles` effect: a vanilla id, the literal `"block"`/`"item"`, or a `vfx_particles` preset id |
| `shape` | string | — | `particles` emission shape: `sphere`/`ring`/`helix`/`line`/`cube`/`point` |
| `block` | string | — | `block_chain` block state, or the inline `particles` block mode |
| `item` | string | — | Inline `particles` item mode |
| `entity_selector` | string | — | Selector (e.g. `@e[type=minecraft:zombie,distance=..10]`) the server resolves into target UUIDs on every play, so a plain `/vfx play` fires an entity effect. For `entity_tint`/`entity_outline`/`entity_displace` |

Structural blocks used next to `params` (never inside it):

| Block | Purpose |
|---|---|
| [`pattern` / `surface`](../effects/screen/surface-pattern.md) | `surface_pattern` figure, texture, faces and band |
| [`graph` + `inputs`](graph.md) | Drive inputs from a node graph; `inputs` may also hold a per-pixel [`field`](fields.md) |
| [`mask`](masks.md) | Restrict where a post-processing effect applies (a world overlay ignores it) |

## `positions` and entity anchors

Each `positions` entry is either a plain `[x, y, z]` array or an entity anchor object:

```jsonc
{ "entity": "@s", "point": "feet", "offset": [0, 1, 0], "dir": "look", "distance": 24 }
```

| Key | Default | Meaning |
|---|---|---|
| `entity` | — (required) | Entity selector; the server resolves it once per play (first match wins, `/vfx play` fails if it matches nothing) |
| `point` | `feet` | Reference point on the entity: `feet`, `center` (bounding-box centre) or `eyes` |
| `offset` | `[0,0,0]` | Offset relative to the resolved anchor point |
| `dir` | `none` | `look` = the tracked entity's live look direction (e.g. eyes + look x 24 = where it is looking) |
| `distance` | 0 | Distance to push along `dir` |

The client substitutes the tracked entity's current anchor-point position every frame, so the effect
follows a moving entity. Slot order is preserved, so `guide_line` endpoints can mix static and
anchored entries. If a tracked entity disappears, the effect skips rendering until it is trackable
again. Static entries anchor to a block (block centre on X/Z); entity anchors and Java-API moves use
exact sub-block coordinates. `/vfx playat` or a network position override wins over anchors.

```json
{
	"type": "light_beam",
	"loop": true,
	"positions": [
		{ "entity": "@s" },
		[16, 64, 16]
	]
}
```

## Complete example

A persistent world overlay with a sound, a keyframed alpha and an entity-anchored position:

```json
{
	"type": "block_outline",
	"persistent": true,
	"fade_ticks": 15,
	"easing": "ease_out_cubic",
	"sound": "minecraft:block.note_block.pling",
	"positions": [{ "entity": "@s", "point": "center" }],
	"params": {
		"width": 0.05,
		"color_r": 1.0, "color_g": 0.85, "color_b": 0.2,
		"alpha": { "keyframes": [
			{ "time": 0, "value": 0.0 },
			{ "time": 20, "value": 0.9 },
			{ "time": 40, "value": 0.0 }
		] }
	}
}
```

```
/reload
/vfx play mymap:my_outline
```

## Persistent and looping

`persistent: true` with `fade_ticks`: `/vfx play` fades in over `fade_ticks` (weight 0→1), `/vfx stop`
fades out and removes the effect. Shader effects blend their params towards the type's neutral value
(brightness→1, radius→0, ...) by the current weight; world overlays multiply `alpha`. Positions are
never faded.

```json
{
	"type": "block_outline",
	"persistent": true,
	"fade_ticks": 15,
	"positions": [[8, 70, 8]],
	"params": { "width": 0.05, "color_r": 1.0, "color_g": 0.85, "color_b": 0.2, "alpha": 0.9 }
}
```

`loop: true`: the effect runs forever and the timeline (keyframes and `start`/`end`) plays in a circle
with period `duration`. Stopping is like persistent (`fade_ticks`).

```json
{
	"type": "block_outline",
	"loop": true,
	"fade_ticks": 10,
	"duration": 40,
	"positions": [[8, 70, 8]],
	"params": {
		"width": 0.05,
		"color_r": 0.2, "color_g": 0.6, "color_b": 1.0,
		"alpha": { "keyframes": [
			{ "time": 0, "value": 0.15 },
			{ "time": 20, "value": 1.0 },
			{ "time": 40, "value": 0.15 }
		] }
	}
}
```

## Validation and errors

- `/vfx validate [namespace]` prints how many definitions loaded and lists every broken file with its
  parse error (operator-only).
- Parsing is **per-file isolated**: one broken JSON is recorded (and shown by `/vfx validate`) while
  every other definition keeps loading - one bad file never takes down the pack.
- An unknown effect `type`, an unknown structural key, a wrong param type or a graph/field/mask cap
  violation is a per-file error naming the offending file/input.
- A datapack definition **overrides** a built-in with the same id (the datapack layer wins).

## See also

- [Animating a param](params.md) - constants, `start`/`end`, keyframes, bindings, `multiply`, easings.
- [Expressions (`expr`)](expr.md) - drive a param from a formula.
- [Value graphs](graph.md), [Per-pixel fields](fields.md), [Masks](masks.md),
  [Custom particles](particles.md).

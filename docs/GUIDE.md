# TOM Post Effects (vfxweaver) — Usage Guide

A client-side VFX library for Minecraft 26.1–26.1.2 (Fabric). Screen post-processing (ping-pong FBO), camera shake, world overlays (block tint/outline), entity effects (tint/outline by UUID), keyframe animation, world/camera/player bindings, datapacks, network triggers and a public Java API.

- Guide version: 21 (see [docs/CHANGELOG.md](CHANGELOG.md) for history)
- Mod: `vfxweaver-1.0.4.jar`, requires Fabric API

Files: `data/<namespace>/vfx/<name>.json` and `data/<namespace>/vfx_curves/<name>.json`. After edits — `/reload`. The effect id = `<namespace>:<name>`. On a dedicated server, definitions and curves are automatically synced to clients on player join and after `/reload`, so custom (datapack) effects work for all players, not just on the server.

The mutating commands `/vfx play`, `/vfx playat`, `/vfx playentity`, `/vfx stop`, `/vfx set` require operator rights (gamemaster level); `/vfx list` is open to everyone.

---

## Contents

1. [Commands](#1-commands)
2. [Effect types](#2-effect-types) — screen post-processing, world overlays, entity effects, misc
3. [Datapack format](#3-datapack-format) — definition fields, ways to set a param, world bindings, easings
4. [Persistent effects: on/off with animation](#4-persistent-effects-onoff-with-animation)
5. [Collections — several effects with one command](#5-collections--several-effects-with-one-command)
6. [Built-in effects (no datapack)](#6-built-in-effects-no-datapack)
7. [Java API (for other mods)](#7-java-api-for-other-mods)
8. [Flashback compatibility](#8-flashback-compatibility)
9. [How it renders (for debugging)](#9-how-it-renders-for-debugging)
10. [Changelog](#changelog)

---

## 1. Commands

| Command | Description |
|---|---|
| `/vfx play <effect> [{[param:value],...}] [players]` | Play an effect (default — to yourself). The optional param-map (like in `/vfx set`) overrides the definition's default params, including world coordinates (`pos_x/y/z`) — like `overrides` in the Java API. Tab autocomplete. |
| `/vfx playat <effect> <x> <y> <z> [{[...]}] [players]` | Play an effect anchored to world coordinates: the client re-anchors spatial bindings (`screen_x/y`, `proximity`) to that point and uses it for the effect's positions (for `block_tint`/`block_outline`). The optional param-map — overrides, like `play`. |
| `/vfx playentity <effect> [{[...]}] <targets> [players]` | Play an effect on selected entities (selector, e.g. `@e[type=!player,distance=..10]`). Targets are passed by UUID (up to 16) and apply to `entity_tint`/`entity_outline`. The optional param-map — overrides. The optional `[players]` — who sees the effect; default — the executing player. |
| `/vfx stop <effect> [players]` | Stop the effect (all its instances). Effects with `fade_ticks > 0` fade out smoothly. |
| `/vfx set <effect> {[param:value],...} [players]` | Live override of params of a **running** effect, without restarting the timeline. If the effect is not running — a new instance is started with those values and the definition's own `duration` (it ends on schedule like a normal play). Tab walks the syntax: `{` → `[` → param name → `:` value → `]` → `,` (new pair) or `}`. |
| `/vfx list` | List all loaded definitions (built-ins + datapack). |

On `/vfx stop` the effect is removed instantly if `fade_ticks` is not set or is 0; otherwise — a smooth fade to neutral values.

Repeated `/vfx play` of the same effect **does not replace** the playing instance — it adds another independent one (e.g. several dents on screen at once). `/vfx stop <effect>` stops all instances of that effect (stopping a single instance is only possible via the Java API, [7](#7-java-api-for-other-mods)); up to 64 effects play at once in total.

**Param-map caveats:** the param-map overrides *parameters only* — `duration` and `fade_ticks` are definition fields and cannot be changed from a command. To get a persistent built-in effect with a smooth exit, wrap it in a datapack definition (see [4](#4-persistent-effectsonoff-with-animation)).

---

## 1.1 Quickstart: first effect in 2 minutes

1. Create `data/mymap/vfx/first_blur.json` inside your datapack:
   ```json
   { "type": "blur", "duration": 100, "params": { "radius": 8 } }
   ```
2. In game: `/reload` (datapacks reload automatically on join).
3. Run `/vfx play mymap:first_blur` — the screen blurs for 5 seconds and fades back.
4. `/vfx list` shows all loaded effect ids (built-ins + your datapack ones).

That is the whole loop: **file → /reload → /vfx play**. Everything else in this guide is variations of it.

---

## 2. Effect types

How to read this section:

- Every parameter is a float. Most "intensity-like" parameters are `0..1`, where `0` = off.
- **Built-in animation:** many built-in effects animate their main parameter from the listed value **down to 0** over the effect duration (so the effect fades out on its own). When you override such a parameter (command param-map / `value` / API), it becomes a **constant** - no auto-fade - unless you animate it yourself (keyframes / `start`+`end` / `expr`).
- Every effect also accepts `screen_layer` (screen effects only): `0` = under the first-person hand and GUI, `1` = above the hand, below the GUI (default), `2` = above everything including the GUI.
- Examples are copy-pasteable commands. For datapack files, put the params into `"params": { ... }` (see [3. Datapack format](#3-datapack-format)).

### 2.1 Screen post-processing (shaders)

#### `chromatic_aberration`
Splits the RGB channels towards the screen edges (RGB fringing).

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.8 (fades to 0) | Fringing strength; 0 = off |
| `radius` | 4 | Effect radius in pixels from the screen border |

```
/vfx play vfxweaver:chromatic_aberration
```

#### `color_grade`
Colour grading: saturation, contrast, brightness and a colour tint.

| Param | Default | Description |
|---|---|---|
| `saturation` | 0.7 (fades to 1) | 0 = grayscale, 1 = neutral, >1 = oversaturated |
| `contrast` | 1.05 (fades to 1) | 1 = neutral; <1 = flatter, >1 = harsher |
| `brightness` | 1 | 1 = neutral; 0 = black |
| `tint_r/g/b` | 1 / 0.9 / 1 (fade to 1) | Per-channel multiplier; 1 = neutral |

```
/vfx play vfxweaver:color_grade
```

#### `distortion`
Barrel (`amount > 0`) / pincushion (`amount < 0`) distortion of the whole screen.

| Param | Default | Description |
|---|---|---|
| `amount` | 0.2 (fades to 0) | Distortion strength; sign picks the direction |
| `radius` | 0.8 | Screen fraction affected from the centre (0..1) |

```
/vfx play vfxweaver:distortion
```

#### `dent`
A local "dent" (lens warp) around a point, or along a segment in line mode.

| Param | Default | Description |
|---|---|---|
| `strength` | 0.6 (fades to 0) | Warp strength; positive pulls in, negative pushes out |
| `radius` | 0.25 | Dent size as a fraction of the screen |
| `center_x`, `center_y` | 0.5, 0.5 | Dent centre in UV (0..1) |
| `line_mode` | 0 | 1 = segment mode (below) |
| `x0`, `y0`, `x1`, `y1` | 0..1 UV | Segment ends for line mode; bind them to the world via `bind: screen_x` / `screen_y` |

```
/vfx play vfxweaver:dent {[strength:0.8],[radius:0.3]}
```

#### `gradient_map`
Maps pixel luminance into a two-colour gradient `from -> to`. See the detailed subsection below the table.

| Param | Default | Description |
|---|---|---|
| `from_r/g/b` | 0.1 / 0 / 0.2 | Gradient colour for the dark end |
| `to_r/g/b` | 1 / 0.2 / 0.1 | Gradient colour for the bright end |
| `intensity` | 1 (fades to 0) | Mix strength: 0 = original, 1 = fully graded |
| `mode` | 0 | 0 = smooth linear gradient, 1 = hard threshold |
| `pos` | 0.5 | Transition centre (linear) / luminance threshold (threshold mode) |

```json
{ "type": "gradient_map", "from_r": 0, "from_g": 0, "from_b": 0,
  "to_r": 1, "to_g": 1, "to_b": 1, "intensity": 1, "mode": 0, "pos": 0.5 }
```

`mode: 0` (**linear**): `t = clamp(luma + (pos - 0.5), 0, 1)`, then `mix(from, to, t)`. `pos = 0.5` - no shift; `pos = 0` - dark areas turn into `to` sooner.
`mode: 1` (**threshold**): pixels brighter than `pos` -> `to`, darker -> `from`. No smooth transition.

**True grayscale:** linear mode, `from` = black, `to` = white, `pos = 0.5`, `intensity = 1`.
**Hard black/white mask:** threshold mode, `from` = black, `to` = white, `pos = 0.5`.

#### `posterize`
Posterization: reduces the number of colours on screen, clean quantization without dithering.

| Param | Default | Description |
|---|---|---|
| `strength` | 0.25 (fades to 0) | 0 = off, 1 = only 2 levels per channel |

```
/vfx play vfxweaver:posterize {[strength:0.6]}
```

#### `blur`
Two-pass adaptive Gaussian blur.

| Param | Default | Description |
|---|---|---|
| `radius` | 4 (fades to 0) | Blur radius in pixels |

```
/vfx play vfxweaver:blur {[radius:10]}
```

#### `pixelate`
Pixelation.

| Param | Default | Description |
|---|---|---|
| `cell_size` | 0.012 (fades to 0.0005) | Cell size as a fraction of the screen (0.012 ~= 23px on 1080p) |

```
/vfx play vfxweaver:pixelate {[cell_size:0.03]}
```

#### `hue_isolation`
Keeps the chosen hue, everything else goes grayscale.

| Param | Default | Description |
|---|---|---|
| `hue` | 0 | Target hue on the colour wheel: 0 = red, 0.33 = green, 0.66 = blue, 0.5 = cyan/magenta boundary... (full circle 0..1) |
| `tolerance` | 0.2 | Hue match width: how far from `hue` (on the 0..1 wheel) a pixel may be and still keep its colour |
| `intensity` | 1 (fades to 0) | Strength of the grayscale conversion outside the tolerance |

```
/vfx play vfxweaver:hue_isolation {[hue:0.33],[tolerance:0.1]}
```

#### `vignette`
Darkens/colours the screen edges.

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.7 (fades to 0) | Edge darkening strength |
| `color_r/g/b` | 0 / 0 / 0 | Edge colour (black by default) |

```
/vfx play vfxweaver:vignette {[intensity:1]}
```

#### `screen_flash`
Fullscreen colour overlay (flash).

| Param | Default | Description |
|---|---|---|
| `alpha` | 0.8 (fades to 0) | Overlay opacity |
| `color_r/g/b` | 1 / 1 / 1 | Flash colour (white by default) |

```
/vfx play vfxweaver:screen_flash {[color_r:1],[color_g:0],[color_b:0],[alpha:1]}
```

#### `motion_blur`
Directional blur from camera rotation speed. The built-in tracks the camera itself.

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.35 (fades to 0) | Blur strength |
| `yaw_delta` | bound to camera | Yaw change between frames (deg/tick) - drives horizontal blur |
| `pitch_delta` | bound to camera | Pitch change between frames - drives vertical blur |

```
/vfx play vfxweaver:motion_blur
```

#### `bloom`
Glow around bright screen areas.

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.6 (fades to 0) | Glow strength |
| `threshold` | 0.7 | Luminance above which pixels glow (0..1) |
| `radius` | 3 | Glow spread in pixels |

```
/vfx play vfxweaver:bloom {[threshold:0.5],[intensity:1]}
```

#### `film_grain`
Animated film grain.

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.08 (fades to 0) | Grain strength |
| `size` | 2 | Grain size in pixels |

```
/vfx play vfxweaver:film_grain
```

#### `scanlines`
CRT bands drifting across the screen.

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.3 (fades to 0) | Band visibility |
| `line_count` | 3 | Bands per 100 screen pixels |
| `speed` | 0.5 | Downward drift speed |

```
/vfx play vfxweaver:scanlines {[line_count:6]}
```

#### `depth_of_field`
Screen tilt-shift: a sharp band, blur away from it.

| Param | Default | Description |
|---|---|---|
| `intensity` | 0.5 (fades to 0) | Blur strength outside the sharp band |
| `focus_center` | 0.5 | Sharp band centre, UV Y (0.5 = screen middle) |
| `focus_range` | 0.15 | Sharp band half-width in UV |

```
/vfx play vfxweaver:depth_of_field
```

#### `letterbox`
Cinematic bars at the top and bottom of the screen.

| Param | Default | Description |
|---|---|---|
| `height` | 0.12 (fades to 0) | Bar height as a fraction of the screen half-height (max 0.5) |
| `color_r/g/b` | 0 / 0 / 0 | Bar colour (black by default) |

```
/vfx play vfxweaver:letterbox {[height:0.2]}
```

#### `invert`
Inverts the screen colours.

| Param | Default | Description |
|---|---|---|
| `intensity` | 1 (fades to 0) | 1 = fully inverted, 0.5 = halfway (washed out), 0 = off |

```
/vfx play vfxweaver:invert
```

#### `vortex`
Swirls pixels into a funnel around a point.

| Param | Default | Description |
|---|---|---|
| `strength` | 2.5 (fades to 0) | Max swirl angle in radians; sign = direction |
| `radius` | 0.5 | Funnel radius as a screen fraction |
| `center_x`, `center_y` | 0.5, 0.5 | Funnel centre in UV |

```
/vfx play vfxweaver:vortex {[strength:4]}
```

#### `speed_lines`
"Speed lines" emanating from the screen borders and pointing to the centre (or a given point).

| Param | Default | Description |
|---|---|---|
| `center_x/y` | 0.5, 0.5 | Point the lines converge to (UV) |
| `count` | 50 | Number of lines (10..200) |
| `length` | 0.5 | Fraction of the ray to the border each line covers |
| `length_rand` | 0.7 | Per-line length variance: 0 = all equal, 1 = fully random |
| `width` | 0.5 | Line thickness |
| `seed` | 0 | Line layout seed; animate via `expr` (e.g. `"t * 2.0"`) to make lines swap chaotically |
| `color_r/g/b` | 1 / 1 / 1 | Line colour |
| `intensity` | 1 (fades to 0) | Visibility |

```
/vfx play vfxweaver:speed_lines {[count:120],[length:0.8]}
```

### 2.2 World overlays (block geometry)

#### `block_tint`
Translucent fill of the block model's visible faces.

| Param | Default | Description |
|---|---|---|
| `color_r/g/b` | 0.2 / 0.6 / 1.0 | Fill colour |
| `alpha` | 0.35 | Fill opacity (0..1) |
| `through_blocks` | 1 | 1 = visible through other blocks, 0 = occluded by them |

```
/vfx play vfxweaver:block_tint {[alpha:0.6],[color_r:1]}
```

#### `block_outline`
Block outline, two modes.

| Param | Default | Description |
|---|---|---|
| `color_r/g/b` | 1 / 0.85 / 0.2 | Outline colour |
| `alpha` | 0.9 | Opacity |
| `width` | 0.05 | Outline thickness in blocks |
| `shell` | 0 | 0 = each model face extruded outwards along its normal by `width/2` (cannot cover the block); 1 = scaled shell clipped by the block's own depth |
| `through_blocks` | 0 | 1 = visible through other blocks, 0 = occluded (the outline never covers its own target block) |

```
/vfx play vfxweaver:block_outline {[width:0.08],[shell:1]}
```

Both support a list of coordinates via `positions` (see [3.1](#31-definition-fields)) or `region: [x0,y0,z0,x1,y1,z1]`. Without them a single position from `params.pos_x/y/z` is used - it can be a constant, an animation or a world binding.

### 2.3 Entity effects (second model pass)

These target entities by UUID: the client stores the target's UUID on the render state and, in a second pass, redraws the entity with its own render type over the original (the vanilla texture and shader are not touched). Item frames are supported too (flat overlay on the frame plane).

#### `entity_tint`
Fills the entity with the effect colour **accounting for its texture** (the texture is the alpha mask, so the effect follows the silhouette).

| Param | Default | Description |
|---|---|---|
| `color_r/g/b` | 0.2 / 0.6 / 1.0 | Tint colour |
| `alpha` | 0.5 | Opacity |
| `texture` | 1 | 1 = recolour the texture (texture x colour, the pattern stays visible); 0 = flat colour, texture only as a mask |
| `through_blocks` | 1 | 1 = visible through walls, 0 = occluded |

```
/vfx playentity vfxweaver:entity_tint @e[type=pig,limit=1] {[alpha:0.8],[color_r:1]}
```

#### `entity_outline`
Silhouette outline of the "inverted hull" type: the model is expanded by `width`, only back faces remain - a thin rim sticks out. Follows the texture contour (no flat rectangle).

| Param | Default | Description |
|---|---|---|
| `color_r/g/b` | 1 / 0.85 / 0.2 | Outline colour |
| `alpha` | 1 | Opacity |
| `width` | 0.05 | Rim thickness in blocks |
| `through_blocks` | 0 | 1 = the glow is visible through walls, 0 = occluded by them |

```
/vfx playentity vfxweaver:entity_outline @e[type=pig,limit=1] {[width:0.1]}
```

Targets are set via `/vfx playentity <effect> <selector>`, via the Java API (see [7](#7-java-api-for-other-mods)) or via the `entity_selector` field in the definition (then `/vfx play <effect>` is enough - the server finds the targets itself). One effect can target up to 16 entities; several effects can hang on one entity. On the first-person hand the local player's own effects are rendered too (`through_blocks` is ignored there - the hand always draws on top).

### 2.4 Misc

#### `camera_shake`
Camera shake with simplex noise and a smooth fade-out envelope.

| Param | Default | Description |
|---|---|---|
| `amplitude_x/y/z` | 0.12 / 0.12 / 0.04 | Position shake amplitude per axis (blocks) |
| `yaw` | 0.8 | Rotation shake amplitude (degrees) |
| `pitch` | 0.6 | Rotation shake amplitude (degrees) |
| `roll` | 0.4 | Rotation shake amplitude (degrees) |
| `frequency` | 7 | Noise oscillations per second |

```
/vfx play vfxweaver:camera_shake {[amplitude_y:0.3],[frequency:20]}
```

#### `fov_modifier`
Changes the player's field of view.

| Param | Default | Description |
|---|---|---|
| `fov_delta` | 10 (fades to 0) | FOV change in degrees (positive = zoom out) |

```
/vfx play vfxweaver:fov_modifier {[fov_delta:-20]}
```

---

## 3. Datapack format

Files: `data/<namespace>/vfx/<name>.json`. After edits — `/reload`. Effect id = `<namespace>:<name>`.

```json
{
	"type": "dent",
	"duration": 60,
	"easing": "ease_out_cubic",
	"loop": false,
	"persistent": false,
	"fade_ticks": 10,
	"params": { "...": "..." },
	"sound": "minecraft:block.note_block.pling",
	"positions": [[8, 70, 8], [9, 70, 8]]
}
```

### 3.1 Definition fields

| Field | Type | Default | Description |
|---|---|---|---|
| `type` | string | — (required) | Effect type from §2 |
| `duration` | int | 40 | Duration in ticks (20 ticks = 1 s). For `loop` — the loop period. |
| `easing` | string | `linear` | Curve for `start`→`end` params |
| `loop` | bool | false | Loop the animation: the timeline plays in a circle, the effect is infinite until `/vfx stop`. |
| `persistent` | bool | false | The effect is infinite (param values freeze at their final), until `/vfx stop`. |
| `fade_ticks` | int | 10 for persistent/loop, else 0 | Smooth fade-in on play and fade-out on stop. The fade drives params towards **neutral** values (brightness→1, radius→0, etc.; positions are not distorted). |
| `params` | object | — | Effect params (see §3.2) |
| `effects` | array | — | Child effects for `collection` (see §5) |
| `sound` | string | — | Id of the sound event played on the client when the effect starts |
| `sound_pos` | array `[x,y,z]` | — | World coordinates for positional sound playback (vanilla mechanic, like `/playsound ... x y z` — louder near, quieter far). If not set — the sound plays directly to the player without coordinates |
| `volume` | param (see §3.2) | 1.0 | Sound volume (reserved param, can be a constant, animation, bind or expression) |
| `pitch` | param (see §3.2) | 1.0 | Sound pitch (reserved param) |
| `positions` | array `[x,y,z]` | — | World coordinate list for `block_tint`/`block_outline`. If not set — `params.pos_x/y/z` is used. Not used for entity effects (targets are set by UUID). |
| `entity_selector` | string | — | Entity selector (e.g. `"@e[type=minecraft:zombie,distance=..10]"`) that the server resolves into target UUIDs on every play. Lets you trigger an entity effect with plain `/vfx play` (no `playentity`): the effect finds its own targets. For entity effects (`entity_tint`/`entity_outline`). |

### 3.2 Ways to set a param

```jsonc
"params": {
	// 1) Constant
	"radius": 4.0,

	// 2) Animation from start to end of the duration (with the definition's easing)
	"intensity": { "start": 0.8, "end": 0.0 },

	// 3) Keyframes (time — ticks from start, each segment has its own easing)
	"brightness": { "keyframes": [
		{ "time": 0,  "value": 0.8,  "easing": "ease_out_quad" },
		{ "time": 30, "value": 1.25, "easing": "ease_in_quad" },
		{ "time": 60, "value": 0.8 }
	] },

	// 4) World/camera binding (recomputed every frame)
	"center_x": { "bind": "screen_x", "pos": [8, 80, 8] },
	"center_y": { "bind": "screen_y", "pos": [8, 80, 8] },
	"strength": { "bind": "proximity", "pos": [8, 80, 8], "range": 32, "scale": 0.9, "invert": false },

	// 5) Multiplier: base (keyframes/start-end/constant/binding) × multiplier.
	// Here the dent is animated by keyframes and additionally weakened with distance from the point.
	"strength": {
		"keyframes": [ { "time": 0, "value": 0.8 }, { "time": 40, "value": 0.0 } ],
		"multiply": { "bind": "proximity", "pos": [8, 80, 8], "range": 64 }
	},

	// 6) Math expression (compiled to an AST when the instance is created,
	//    evaluated every frame). Variables: t (ticks since start), x/y/z (camera coords),
	//    pi, e. Player variables: health, hunger, speed (blocks/s), light_level,
	//    time_of_day, player_x/y/z. Functions: sin, cos, abs, min, max, pow, sqrt,
	//    random() (0..1), noise(x,y,z) (simplex 3D, -1..1). random()/noise() are unique
	//    per instance.
	"intensity": { "expr": "abs(sin(t * 0.1)) * 0.8 + random() * 0.2" }
}
```

An effect sound can have its own volume and pitch via the reserved `volume`/`pitch` params (constant, animation, bind or expression). Values are read once at effect start:

```jsonc
{
	"type": "screen_flash",
	"sound": "minecraft:block.note_block.pling",
	"params": {
		"alpha": 0.3,
		"volume": { "bind": "proximity", "pos": [8, 80, 8], "range": 32 },
		"pitch": 1.5
	}
}
```

To play the sound in the world at coordinates (vanilla positional mechanic, like `/playsound ... x y z` — louder near, quieter far), set `sound_pos`:

```jsonc
{
	"type": "screen_flash",
	"sound": "minecraft:block.note_block.pling",
	"sound_pos": [8, 80, 8],
	"params": {
		"volume": 1.0,
		"pitch": 1.2
	}
}
```

Without `sound_pos` the sound plays directly to the player (no coordinate anchoring). The position can be overridden via the API by passing `sound_pos_x/y/z` in `sendEffect` overrides.

### 3.3 World and camera bindings

| `bind` | Value | Description |
|---|---|---|
| `screen_x` / `screen_y` | UV 0..1 (−scale if the point is behind the camera) | Screen position of the world point `pos: [x,y,z]` — the effect "follows" the point |
| `proximity` | 0..1 (×scale) | 1 near `pos`, smoothly to 0 at distance `range` (default 16). `invert: true` — the opposite (0 near, 1 far). Behind the camera = 0 (if not `invert`). |
| `look` | 0..1 (×scale) | 1 when the camera looks exactly in the `yaw`/`pitch` direction (degrees), smoothly to 0 at angular deviation `range` (default 90°). `invert: true` — the opposite. |
 | `look_at` | 0..1 (×scale) | Same as `look`, but the target direction is derived from a world `pos: [x,y,z]` anchor instead of explicit yaw/pitch. |
 | `distance` | blocks (×scale) | Raw Euclidean distance from the camera to `pos: [x,y,z]` (unlike `proximity` — not 0..1, but real blocks) |
| `look_x` / `look_y` / `look_z` | −1..1 (×scale) | Components of the camera's look unit vector (for "are we looking that way" / offset along the look direction) |
| `player_x` / `player_y` / `player_z` | world coords (×scale) | The local player's position along the X/Y/Z axes |
| `camera_yaw_delta` | degrees/tick (×scale) | Camera yaw change between frames |
| `camera_pitch_delta` | degrees/tick (×scale) | Camera pitch change between frames |
| `health` | 0..1 (×scale) | The local player's health fraction (health / max). `invert: true` — grows as HP drops. |
| `hunger` | 0..1 (×scale) | Saturation fraction (food / 20) |
| `speed` | 0..1 (×scale) | Horizontal speed (blocks/s), normalized on `range` (default 5 = sprint) |
| `light_level` | 0..1 (×scale) | Light level at the player's position (block/sky light / 15) |
| `time_of_day` | 0..1 (×scale) | Fraction of the day cycle (0 = sunrise) |

Example — red vignette at low HP:

```jsonc
"intensity": { "bind": "health", "invert": true, "scale": 0.9 }
```

Example — blur while sprinting:

```jsonc
"radius": { "bind": "speed", "range": 6, "scale": 8 }
```

Options: `pos` (for screen_x/y, proximity, distance, look_at), `yaw`, `pitch` (for look), `range`, `scale` (default 1), `invert`.

Example — a dent-line stuck to two world points (a dent "cuts" the screen between them):

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

Examples:
- `vfxweaver_test:dent_world` — a dent stuck to the coordinate `[8, 80, 8]`, strength drops to zero within 32 blocks;
- `vfxweaver_test:blur_look` — a blur (radius up to 10) that strengthens when looking south (yaw 0, pitch 0) and disappears past 60° deviation.

### 3.4 Easings

`linear`, `ease_in_quad`, `ease_out_quad`, `ease_in_out_quad`, `ease_in_cubic`, `ease_out_cubic`, `ease_in_out_cubic`, `ease_in_expo`, `ease_out_expo`, `smoothstep` (case-insensitive, `-`/`_` equivalent).

Besides the built-in names you can define your own curves: a named datapack file (`data/<namespace>/vfx_curves/<name>.json` with an array of control points `points`; control points look like `[t, v]`, `t` from 0 to 1) or an inline object with a `curve` array:

```jsonc
"intensity": { "start": 0.0, "end": 1.0, "easing": { "curve": [[0, 0], [0.6, 0.9], [1, 1]] } }
```

Such a value can be used in any `easing` field — the effect definition, an individual keyframe or a collection child effect.

---

## 4. Persistent effects: on/off with animation

```json
{
	"type": "block_outline",
	"persistent": true,
	"fade_ticks": 15,
	"positions": [[8, 70, 8]],
	"params": { "width": 0.05, "color_r": 1.0, "color_g": 0.85, "color_b": 0.2, "alpha": 0.9 }
}
```

- `/vfx play` → smooth fade-in over `fade_ticks` (weight 0→1);
- `/vfx stop` → smooth fade-out, then the effect is removed;
- for shader effects the weight blends params with neutral values (`VFXEffectType.neutralValue`);
- for overlays the weight multiplies `alpha`.

## 4.1 Looping

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

`loop` = the effect runs forever + the timeline (including keyframes and `start`/`end`) plays in a circle with period `duration`. Stopping — like persistent (`fade_ticks`).

## 4.2 Effect on entities

```json
{
	"type": "entity_outline",
	"duration": 120,
	"fade_ticks": 10,
	"params": { "width": 0.06, "color_r": 1.0, "color_g": 0.85, "color_b": 0.2, "alpha": 1.0, "through_blocks": 0 }
}
```

For a tint add `"texture": 1` (recolour the texture) or `"texture": 0` (flat colour with the texture as a mask):

```json
{
	"type": "entity_tint",
	"duration": 120,
	"fade_ticks": 10,
	"params": { "color_r": 0.2, "color_g": 0.6, "color_b": 1.0, "alpha": 0.5, "texture": 1, "through_blocks": 0 }
}
```

Trigger on nearby mobs (the selector picks targets, UUIDs are sent to the client):

```
/vfx playentity vfxweaver_test:test_entity_outline @e[type=!player,distance=..10]
```

The same from the Java API — see `VFXAPI.sendEffect(...)` with the `List<UUID> entityUuids` argument in [docs/API.md](API.md).

---

## 5. Collections — several effects with one command

```json
{
	"type": "collection",
	"effects": [
		{ "effect": "vfxweaver_test:marker_persistent", "delay": 0 },
		{ "effect": "vfxweaver_test:block_tint_demo", "delay": 10 },
		{ "effect": "vfxweaver_test:blur_grow", "delay": 20 },
		{ "effect": "vfxweaver_test:red_pulse", "delay": 40, "duration": 60 },
		{ "effect": "vfxweaver:chromatic_aberration", "delay": 55, "duration": 40, "params": { "intensity": 1.2 } }
	]
}
```

Child effect fields: `effect` (id, required), `delay` (ticks from collection start), `duration` (0 = definition default, −1 = persistent), `params` (numbers only — constant overrides), `easing`. Collection nesting — up to 4 levels. `/vfx stop <collection>` cancels not-yet-started children; already playing ones are stopped by their own `/vfx stop`.

---

## 6. Built-in effects (no datapack)

Post-processing: `vfxweaver:chromatic_aberration`, `vfxweaver:color_grade`, `vfxweaver:distortion`, `vfxweaver:dent`, `vfxweaver:gradient_map`, `vfxweaver:posterize`, `vfxweaver:blur`, `vfxweaver:pixelate`, `vfxweaver:hue_isolation`, `vfxweaver:vignette`, `vfxweaver:screen_flash`, `vfxweaver:motion_blur`, `vfxweaver:bloom`, `vfxweaver:film_grain`, `vfxweaver:scanlines`, `vfxweaver:depth_of_field`, `vfxweaver:letterbox`, `vfxweaver:invert`, `vfxweaver:vortex`, `vfxweaver:speed_lines`.

World overlays: `vfxweaver:block_tint`, `vfxweaver:block_outline`.

Entity effects: `vfxweaver:entity_tint`, `vfxweaver:entity_outline`.

Misc: `vfxweaver:camera_shake`, `vfxweaver:fov_modifier`.

All have fade animation (40 ticks, except where noted); params can be overridden by collections.

---

## 7. Java API (for other mods)

Briefly:

```java
VFXAPI.sendEffect(serverPlayer, effectId, Map.of(), null); // server → client
VFXAPI.playEffect(effectId, 0, Map.of("radius", 8.0F), EasingType.EASE_OUT_CUBIC); // locally on the client
```

Full reference (all `VFXAPI` methods, `VFXLocalDispatcher`, the `vfxweaver:vfx_trigger` network packet format) — **[docs/API.md](API.md)**.

Effects sent via `VFXAPI.sendEffect` are remembered server-side: if the player reconnects (or a new player joins) while the effect is still running, it is re-applied automatically with its remaining duration. Persistent (`-1`) effects are always re-applied. Stopping an effect (`sendStop`) also forgets it.

---

## 8. Flashback compatibility

[Flashback](https://modrinth.com/mod/flashback) is an optional companion (a soft dependency — the mod works fine without it). When Flashback is installed, **client-local effects** (started on the client, e.g. via `VFXAPI.playEffect` or other mods calling it) are automatically written into the replay as custom Flashback actions, so they appear in the replay at the exact tick they were played. Server-triggered effects travel as `vfxweaver:vfx_trigger` packets, which Flashback captures and replays on its own.

Things to know:

- Effects played with a **negative (persistent) duration** are not recorded — without a recorded stop event they would loop forever during playback.
- The recording requires no config: start a Flashback recording, play effects, done.
- No interaction with the Flashback editor keyframes; this is replay recording/playback only.

## 9. How it renders (for debugging)

Post-processing pipeline, world overlays, effect clock, load limits and fault tolerance — **[docs/ARCHITECTURE.md](ARCHITECTURE.md)**.

---

## Changelog

Versioned feature history — **[docs/CHANGELOG.md](CHANGELOG.md)**.

Guide version: 21 — see changelog below.

### v20
- Parameter overrides whose name the effect definition does not declare (e.g. `through_blocks` on the built-in effects) now apply as constant values instead of being silently dropped; `through_blocks` is declared on the built-in entity/block tint/outline.
- Entity tint/outline are rendered on the first-person hand as well (the arm bypasses the normal entity submit path).
- Outlines always render under their target now. Entity outline with `through_blocks:1` draws an opaque shell before the body (rim around the silhouette, visible through walls); block outlines ignore `through_blocks` and never cover the block itself.
- `camera_shake` gained a `frequency` parameter (oscillations per second, default 7).
- All screen effects accept `screen_layer`: `0` = under the first-person hand and GUI, `1` = above the hand below the GUI (default), `2` = above everything including the GUI.

### v18
- Effects survive a reconnect correctly: the server remembers runtime keyframes (Java API `sendKeyframe`) and resumes playback from the position it was left at (protocol version 5).
- Non-looping effects whose runtime edits animate to zero remove themselves instead of lingering invisibly.
- `/vfx set` on a non-running effect starts a normal instance with the definition's own `duration` instead of an immortal one.
- `/vfx playentity` accepts an optional `[players]` list — who sees the effect (default: the executing player).
- Removed the `/vfx key` command (nobody used it; `VFXAPI.sendKeyframe` and the network `KEYFRAME` action remain for mods).

### v17
- Flashback compatibility: client-local effects are recorded into Flashback replays and re-triggered during playback (soft dependency, reflection-based, no compile-time coupling).

### v16
- New effect types for entities: `entity_tint` (a solid translucent fill of the effect colour) and `entity_outline` (an "inverted hull" silhouette outline, thickness `width`). Targets are set by UUID.
- New command `/vfx playentity <effect> <targets>` — plays an effect on entities picked by a selector (up to 16 UUIDs).
- Both types support `through_blocks`: 0 — the effect hides behind walls, 1 — visible through them.

### v15
- New way to set a param — the math expression `"expr"` (variables `t`/`x`/`y`/`z`/`pi`/`e`, functions `sin`/`cos`/`abs`/`min`/`max`/`pow`/`sqrt`/`random`/`noise`). Compiled once, evaluated every frame; `random()`/`noise()` are unique per instance.
- Camera shake (`camera_shake`) is now unique per call (per-instance seed).

### v14
- The mutating `/vfx` commands (`play`, `playat`, `stop`, `set`, `key`) now require operator rights; `/vfx list` is open to all.
- Tighter client protection from a hostile/broken server: caps on network packet sizes (effect params, definition/curve sync), `vfx_sync` packet version check, cap on effect duration from the server, instance-id validation on stop.

### v13
- Datapack VFX effects and curves sync with dedicated-server clients (the `vfxweaver:vfx_sync` packet on join and after `/reload`) — custom effects now play for players on a dedicated server, like in single-player.

### v12
- `sendEffect` accepts a direct world position — no `pos_x/y/z` hack (the client immediately re-anchors spatial bindings to the point).
- Custom easing curves: files `data/<ns>/vfx_curves/<name>.json` or an inline object `{ "curve": [[t,v],...] }` in any `easing` field.
- Param multiplier: `"param": { keyframes/start-end/constant/binding + "multiply": { "bind": "proximity", ... } }` — final value = base × multiplier (e.g. an animated dent fading with distance from a point).
- `VFXAPI.playEffectId(...)` returns the instance id, `VFXAPI.stopEffect(long)` stops one specific instance; `sendStop(player, effectId, instanceId)` — over the network.
- Network protocol version 4: the packet carries an optional position and instance id.

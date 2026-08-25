# TOM Post Effects (vfxweaver) вЂ” Usage Guide

A client-side VFX library for Minecraft 26.1вЂ“26.1.2 (Fabric). Screen post-processing (ping-pong FBO), camera shake, world overlays (block tint/outline), entity effects (tint/outline by UUID), keyframe animation, world/camera/player bindings, datapacks, network triggers and a public Java API.

- Guide version: 20 (see [docs/CHANGELOG.md](CHANGELOG.md) for history)
- Mod: `vfxweaver-1.0.4.jar`, requires Fabric API

Files: `data/<namespace>/vfx/<name>.json` and `data/<namespace>/vfx_curves/<name>.json`. After edits вЂ” `/reload`. The effect id = `<namespace>:<name>`. On a dedicated server, definitions and curves are automatically synced to clients on player join and after `/reload`, so custom (datapack) effects work for all players, not just on the server.

The mutating commands `/vfx play`, `/vfx playat`, `/vfx playentity`, `/vfx stop`, `/vfx set` require operator rights (gamemaster level); `/vfx list` is open to everyone.

---

## Contents

1. [Commands](#1-commands)
2. [Effect types](#2-effect-types) вЂ” screen post-processing, world overlays, entity effects, misc
3. [Datapack format](#3-datapack-format) вЂ” definition fields, ways to set a param, world bindings, easings
4. [Persistent effects: on/off with animation](#4-persistent-effects-onoff-with-animation)
5. [Collections вЂ” several effects with one command](#5-collections--several-effects-with-one-command)
6. [Built-in effects (no datapack)](#6-built-in-effects-no-datapack)
7. [Java API (for other mods)](#7-java-api-for-other-mods)
8. [Flashback compatibility](#8-flashback-compatibility)
9. [How it renders (for debugging)](#9-how-it-renders-for-debugging)
10. [Changelog](#changelog)

---

## 1. Commands

| Command | Description |
|---|---|
| `/vfx play <effect> [{[param:value],...}] [players]` | Play an effect (default вЂ” to yourself). The optional param-map (like in `/vfx set`) overrides the definition's default params, including world coordinates (`pos_x/y/z`) вЂ” like `overrides` in the Java API. Tab autocomplete. |
| `/vfx playat <effect> <x> <y> <z> [{[...]}] [players]` | Play an effect anchored to world coordinates: the client re-anchors spatial bindings (`screen_x/y`, `proximity`) to that point and uses it for the effect's positions (for `block_tint`/`block_outline`). The optional param-map вЂ” overrides, like `play`. |
| `/vfx playentity <effect> [{[...]}] <targets> [players]` | Play an effect on selected entities (selector, e.g. `@e[type=!player,distance=..10]`). Targets are passed by UUID (up to 16) and apply to `entity_tint`/`entity_outline`. The optional param-map вЂ” overrides. The optional `[players]` вЂ” who sees the effect; default вЂ” the executing player. |
| `/vfx stop <effect> [players]` | Stop the effect (all its instances). Effects with `fade_ticks > 0` fade out smoothly. |
| `/vfx set <effect> {[param:value],...} [players]` | Live override of params of a **running** effect, without restarting the timeline. If the effect is not running вЂ” a new instance is started with those values and the definition's own `duration` (it ends on schedule like a normal play). Tab walks the syntax: `{` в†’ `[` в†’ param name в†’ `:` value в†’ `]` в†’ `,` (new pair) or `}`. |
| `/vfx list` | List all loaded definitions (built-ins + datapack). |

On `/vfx stop` the effect is removed instantly if `fade_ticks` is not set or is 0; otherwise вЂ” a smooth fade to neutral values.

Repeated `/vfx play` of the same effect **does not replace** the playing instance вЂ” it adds another independent one (e.g. several dents on screen at once). `/vfx stop <effect>` stops all instances; up to 64 effects play at once in total.

---

## 2. Effect types

### 2.1 Screen post-processing (shaders)

| Type | Params | Description |
|---|---|---|
| `chromatic_aberration` | `intensity` (0=off), `radius` (pixels) | Splits the RGB channels towards the screen edges |
| `color_grade` | `saturation`, `contrast`, `brightness` (neutral = 1), `tint_r/g/b` (neutral = 1) | Color grading + tint |
| `distortion` | `amount`, `radius` | Barrel (`amount > 0`) / pincushion (`amount < 0`) distortion of the whole screen |
| `dent` | `strength` (+pull in / в€’push out), `radius` (screen fractions), `center_x`, `center_y` (UV 0..1) | Local "dent" around a point. Line mode: `line_mode` (0/1) + `x0`,`y0`,`x1`,`y1` (UV 0..1) вЂ” a dent along a segment; ends can be bound to the world via `bind: screen_x`/`screen_y`. |
| `gradient_map` | `from_r/g/b`, `to_r/g/b`, `intensity`, `mode` (0/1, default 0), `pos` (0..1, default 0.5) | Maps luminance into a two-colour gradient. `mode: 0` вЂ” smooth linear gradient (`pos` shifts the transition centre; 0.5 = no shift); `mode: 1` вЂ” hard threshold (`step`): everything brighter than `pos` вЂ” colour `to`, darker вЂ” `from` (for masks/stylized shadows) |
| `posterize` | `strength` (0..1) | Posterization: reduce the number of colours on screen (255 в†’ 2 levels), clean quantization without dithering |
| `blur` | `radius` (pixels) | Two-pass adaptive Gaussian blur |
| `pixelate` | `cell_size` (screen fraction) | Pixelation |
| `hue_isolation` | `hue` (0..1), `tolerance`, `intensity` | Keeps the chosen hue, the rest goes grayscale |
| `vignette` | `intensity` (0..1), `color_r/g/b` (0..1) | Darkens/colors the screen edges |
| `screen_flash` | `alpha` (0..1), `color_r/g/b` (0..1) | Fullscreen colour overlay |
| `motion_blur` | `intensity` (0..1), `yaw_delta`, `pitch_delta` | Directional blur from camera rotation speed |
| `bloom` | `intensity` (0..1), `threshold` (0..1), `radius` (pixels) | Glow around bright screen areas |
| `film_grain` | `intensity` (0..1), `size` (grain px) | Animated film grain |
| `scanlines` | `intensity` (0..1), `line_count` (bands per 100px), `speed` (drift) | CRT bands drifting across the screen |
| `depth_of_field` | `intensity` (0..1), `focus_center` (UV Y), `focus_range` (focus half-width) | Screen tilt-shift: a sharp band, blur away from it |
| `letterbox` | `height` (0..0.5), `color_r/g/b` | Cinematic bars at the top and bottom of the screen |
| `invert` | `intensity` (0..1) | Inverts the screen colours |
| `vortex` | `strength` (radians, В± = direction), `radius`, `center_x`, `center_y` (UV) | Swirls pixels into a funnel around a point |
All screen effects also accept `screen_layer` - where the effect applies in the frame: `0` = below the first-person hand and the GUI (world only), `1` = above the hand, below the GUI (default), `2` = above everything including the GUI.
| `speed_lines` | `center_x/y` (UV, default 0.5), `count` (10..200, default 50), `length` (0..1, default 0.5), `length_rand` (0..1, default 0.7), `width` (0..1, default 0.5), `seed` (0..1000, default 0), `color_r/g/b`, `intensity` | "Speed lines" emanating from the screen borders and pointing to the centre (or a given point) вЂ” a sense of speed. `length` is the fraction of the ray to the border each line covers; `length_rand` controls how much the per-line length varies (0 = all equal, 1 = full random). Animate `seed` via `expr` (e.g. `"t * 2.0"`) to make the lines swap chaotically. |
| `fov_modifier` | `fov_delta` (degrees) | Changes the player's field of view (FOV) |

#### Gradient map: modes and colour coordinate

`gradient_map` maps pixel luminance (`luma = 0.299В·R + 0.587В·G + 0.114В·B`) into a two-colour gradient `from в†’ to`. Two params control the build:

- **`mode` (0/1, default 0):**
  - `0` (**linear**) вЂ” smooth gradient: `t = clamp(luma + (pos в€’ 0.5), 0, 1)`, then `mix(from, to, t)`. The `pos` param shifts the **transition centre**: `pos = 0.5` вЂ” no shift, `pos = 0` вЂ” dark areas faster become colour `to`, `pos = 1` вЂ” the opposite.
  - `1` (**constant / stepped**) вЂ” hard threshold: `t = step(pos, luma)`. A pixel **brighter** than `pos` в†’ colour `to`; **darker** в†’ colour `from`. No smooth transition вЂ” great for masks and stylized shadows.
- **`pos` (0..1, default 0.5)** вЂ” colour coordinate/threshold (see above). In linear вЂ” the transition centre; in constant вЂ” the luminance threshold.

**True grayscale filter:** to get real grayscale, use `mode: 0` (linear), `from = black`, `to = white`, `pos = 0.5`:
```json
{ "type": "gradient_map", "from_r": 0, "from_g": 0, "from_b": 0,
  "to_r": 1, "to_g": 1, "to_b": 1, "intensity": 1, "mode": 0, "pos": 0.5 }
```
Then `t = luma` and `mix(black, white, luma)` вЂ” exactly the luminance в†’ smooth grayscale.

**Hard black/white mask** (`mode: 1`, `from = black`, `to = white`, `pos = 0.5`): everything darker than 0.5 в†’ black, everything brighter в†’ white. "0 вЂ” black, 0.5+ вЂ” white".

### 2.2 World overlays (block model geometry)

| Type | Params | Description |
|---|---|---|
| `block_tint` | `color_r/g/b` (0..1), `alpha` (0..1), `through_blocks` (0/1, default 1) | Translucent fill of the block model's visible faces; `through_blocks: 1` вЂ” visible through other blocks, `0` вЂ” occluded by them |
| `block_outline` | `color_r/g/b` (0..1), `alpha` (0..1), `width` (0..1), `shell` (0/1, default 0), `through_blocks` (0/1, default 0) | Block outline in two modes: `shell: 0` вЂ” each model face is extruded outwards along its normal by `width/2` (the outline cannot cover the block); `shell: 1` вЂ” a classic scaled shell with back faces, clipped by the block's own depth (`width` = expansion). `through_blocks`: 1 вЂ” visible through other blocks, 0 вЂ” occluded by them |

Both types support a list of coordinates via the `positions` field (see В§3.1). If the list is not set, a single position from `params.pos_x/y/z` is used вЂ” it can be a constant, an animation or a world binding.

### 2.3 Entity effects (second model pass)

These types target living entities by UUID: the client stores the target's UUID on the render state and, in a second pass, redraws the entity model with its own render type over the original (the vanilla texture and shader are not touched).

| Type | Params | Description |
|---|---|---|
| `entity_tint` | `color_r/g/b` (0..1), `alpha` (0..1), `texture` (0/1, default 1), `through_blocks` (0/1, default 1) | Fills the entity model with the effect colour **accounting for its texture** (the texture is used as an alpha mask, so the effect follows the silhouette, not a box around the model). `texture: 1` вЂ” recolour the texture (texture Г— colour, the pattern is visible); `texture: 0` вЂ” flat colour with the texture only as a mask. `through_blocks: 1` вЂ” visible through walls, `0` вЂ” occluded by them |
| `entity_outline` | `color_r/g/b` (0..1), `alpha` (0..1), `width` (0..1, default 0.05), `through_blocks` (0/1, default 0) | Silhouette outline of the "inverted hull" type: the model is expanded by `width` around its vertical centre, only back faces remain вЂ” a thin rim sticks out. The texture is used as a mask, so the outline follows the texture contour (no flat rectangle). `through_blocks: 1` вЂ” the glow is visible through walls (full silhouette), `0` вЂ” occluded by walls |

Targets are set via `/vfx playentity <effect> <selector>`, via the Java API (see В§7) or via the `entity_selector` field in the effect definition (then `/vfx play <effect>` is enough вЂ” the server finds the targets itself). The `vfxweaver:vfx_trigger` packet carries the UUID list (up to 16). One effect can target several entities at once; several effects can hang on one entity at the same time. If the selector picks more than 16 entities, the effect applies only to the first 16 (packet cap); up to 64 effects play at once in total (`MAX_ACTIVE_EFFECTS`).

### 2.4 Misc

| Type | Params | Description |
|---|---|---|
| `camera_shake` | `amplitude_x/y/z`, `yaw`, `pitch`, `roll`, `frequency` (default 7) | Camera shake with simplex noise and a smooth envelope; `frequency` sets oscillations per second |
| `collection` | вЂ” (see В§5) | Not an effect: a scenario of several effects with delays |

---

## 3. Datapack format

Files: `data/<namespace>/vfx/<name>.json`. After edits вЂ” `/reload`. Effect id = `<namespace>:<name>`.

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
| `type` | string | вЂ” (required) | Effect type from В§2 |
| `duration` | int | 40 | Duration in ticks (20 ticks = 1 s). For `loop` вЂ” the loop period. |
| `easing` | string | `linear` | Curve for `start`в†’`end` params |
| `loop` | bool | false | Loop the animation: the timeline plays in a circle, the effect is infinite until `/vfx stop`. |
| `persistent` | bool | false | The effect is infinite (param values freeze at their final), until `/vfx stop`. |
| `fade_ticks` | int | 10 for persistent/loop, else 0 | Smooth fade-in on play and fade-out on stop. The fade drives params towards **neutral** values (brightnessв†’1, radiusв†’0, etc.; positions are not distorted). |
| `params` | object | вЂ” | Effect params (see В§3.2) |
| `effects` | array | вЂ” | Child effects for `collection` (see В§5) |
| `sound` | string | вЂ” | Id of the sound event played on the client when the effect starts |
| `sound_pos` | array `[x,y,z]` | вЂ” | World coordinates for positional sound playback (vanilla mechanic, like `/playsound ... x y z` вЂ” louder near, quieter far). If not set вЂ” the sound plays directly to the player without coordinates |
| `volume` | param (see В§3.2) | 1.0 | Sound volume (reserved param, can be a constant, animation, bind or expression) |
| `pitch` | param (see В§3.2) | 1.0 | Sound pitch (reserved param) |
| `positions` | array `[x,y,z]` | вЂ” | World coordinate list for `block_tint`/`block_outline`. If not set вЂ” `params.pos_x/y/z` is used. Not used for entity effects (targets are set by UUID). |
| `entity_selector` | string | вЂ” | Entity selector (e.g. `"@e[type=minecraft:zombie,distance=..10]"`) that the server resolves into target UUIDs on every play. Lets you trigger an entity effect with plain `/vfx play` (no `playentity`): the effect finds its own targets. For entity effects (`entity_tint`/`entity_outline`). |

### 3.2 Ways to set a param

```jsonc
"params": {
	// 1) Constant
	"radius": 4.0,

	// 2) Animation from start to end of the duration (with the definition's easing)
	"intensity": { "start": 0.8, "end": 0.0 },

	// 3) Keyframes (time вЂ” ticks from start, each segment has its own easing)
	"brightness": { "keyframes": [
		{ "time": 0,  "value": 0.8,  "easing": "ease_out_quad" },
		{ "time": 30, "value": 1.25, "easing": "ease_in_quad" },
		{ "time": 60, "value": 0.8 }
	] },

	// 4) World/camera binding (recomputed every frame)
	"center_x": { "bind": "screen_x", "pos": [8, 80, 8] },
	"center_y": { "bind": "screen_y", "pos": [8, 80, 8] },
	"strength": { "bind": "proximity", "pos": [8, 80, 8], "range": 32, "scale": 0.9, "invert": false },

	// 5) Multiplier: base (keyframes/start-end/constant/binding) Г— multiplier.
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

To play the sound in the world at coordinates (vanilla positional mechanic, like `/playsound ... x y z` вЂ” louder near, quieter far), set `sound_pos`:

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
| `screen_x` / `screen_y` | UV 0..1 (в€’scale if the point is behind the camera) | Screen position of the world point `pos: [x,y,z]` вЂ” the effect "follows" the point |
| `proximity` | 0..1 (Г—scale) | 1 near `pos`, smoothly to 0 at distance `range` (default 16). `invert: true` вЂ” the opposite (0 near, 1 far). Behind the camera = 0 (if not `invert`). |
| `look` | 0..1 (Г—scale) | 1 when the camera looks exactly in the `yaw`/`pitch` direction (degrees), smoothly to 0 at angular deviation `range` (default 90В°). `invert: true` вЂ” the opposite. |
 | `look_at` | 0..1 (Г—scale) | Same as `look`, but the target direction is derived from a world `pos: [x,y,z]` anchor instead of explicit yaw/pitch. |
 | `distance` | blocks (Г—scale) | Raw Euclidean distance from the camera to `pos: [x,y,z]` (unlike `proximity` вЂ” not 0..1, but real blocks) |
| `look_x` / `look_y` / `look_z` | в€’1..1 (Г—scale) | Components of the camera's look unit vector (for "are we looking that way" / offset along the look direction) |
| `player_x` / `player_y` / `player_z` | world coords (Г—scale) | The local player's position along the X/Y/Z axes |
| `camera_yaw_delta` | degrees/tick (Г—scale) | Camera yaw change between frames |
| `camera_pitch_delta` | degrees/tick (Г—scale) | Camera pitch change between frames |
| `health` | 0..1 (Г—scale) | The local player's health fraction (health / max). `invert: true` вЂ” grows as HP drops. |
| `hunger` | 0..1 (Г—scale) | Saturation fraction (food / 20) |
| `speed` | 0..1 (Г—scale) | Horizontal speed (blocks/s), normalized on `range` (default 5 = sprint) |
| `light_level` | 0..1 (Г—scale) | Light level at the player's position (block/sky light / 15) |
| `time_of_day` | 0..1 (Г—scale) | Fraction of the day cycle (0 = sunrise) |

Example вЂ” red vignette at low HP:

```jsonc
"intensity": { "bind": "health", "invert": true, "scale": 0.9 }
```

Example вЂ” blur while sprinting:

```jsonc
"radius": { "bind": "speed", "range": 6, "scale": 8 }
```

Options: `pos` (for screen_x/y, proximity, distance, look_at), `yaw`, `pitch` (for look), `range`, `scale` (default 1), `invert`.

Example вЂ” a dent-line stuck to two world points (a dent "cuts" the screen between them):

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
- `vfxweaver_test:dent_world` вЂ” a dent stuck to the coordinate `[8, 80, 8]`, strength drops to zero within 32 blocks;
- `vfxweaver_test:blur_look` вЂ” a blur (radius up to 10) that strengthens when looking south (yaw 0, pitch 0) and disappears past 60В° deviation.

### 3.4 Easings

`linear`, `ease_in_quad`, `ease_out_quad`, `ease_in_out_quad`, `ease_in_cubic`, `ease_out_cubic`, `ease_in_out_cubic`, `ease_in_expo`, `ease_out_expo`, `smoothstep` (case-insensitive, `-`/`_` equivalent).

Besides the built-in names you can define your own curves: a named datapack file (`data/<namespace>/vfx_curves/<name>.json` with an array of control points `points`; control points look like `[t, v]`, `t` from 0 to 1) or an inline object with a `curve` array:

```jsonc
"intensity": { "start": 0.0, "end": 1.0, "easing": { "curve": [[0, 0], [0.6, 0.9], [1, 1]] } }
```

Such a value can be used in any `easing` field вЂ” the effect definition, an individual keyframe or a collection child effect.

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

- `/vfx play` в†’ smooth fade-in over `fade_ticks` (weight 0в†’1);
- `/vfx stop` в†’ smooth fade-out, then the effect is removed;
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

`loop` = the effect runs forever + the timeline (including keyframes and `start`/`end`) plays in a circle with period `duration`. Stopping вЂ” like persistent (`fade_ticks`).

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

The same from the Java API вЂ” see `VFXAPI.sendEffect(...)` with the `List<UUID> entityUuids` argument in [docs/API.md](API.md).

---

## 5. Collections вЂ” several effects with one command

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

Child effect fields: `effect` (id, required), `delay` (ticks from collection start), `duration` (0 = definition default, в€’1 = persistent), `params` (numbers only вЂ” constant overrides), `easing`. Collection nesting вЂ” up to 4 levels. `/vfx stop <collection>` cancels not-yet-started children; already playing ones are stopped by their own `/vfx stop`.

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
VFXAPI.sendEffect(serverPlayer, effectId, Map.of(), null); // server в†’ client
VFXAPI.playEffect(effectId, 0, Map.of("radius", 8.0F), EasingType.EASE_OUT_CUBIC); // locally on the client
```

Full reference (all `VFXAPI` methods, `VFXLocalDispatcher`, the `vfxweaver:vfx_trigger` network packet format) вЂ” **[docs/API.md](API.md)**.

Effects sent via `VFXAPI.sendEffect` are remembered server-side: if the player reconnects (or a new player joins) while the effect is still running, it is re-applied automatically with its remaining duration. Persistent (`-1`) effects are always re-applied. Stopping an effect (`sendStop`) also forgets it.

---

## 8. Flashback compatibility

[Flashback](https://modrinth.com/mod/flashback) is an optional companion (a soft dependency вЂ” the mod works fine without it). When Flashback is installed, **client-local effects** (started on the client, e.g. via `VFXAPI.playEffect` or other mods calling it) are automatically written into the replay as custom Flashback actions, so they appear in the replay at the exact tick they were played. Server-triggered effects travel as `vfxweaver:vfx_trigger` packets, which Flashback captures and replays on its own.

Things to know:

- Effects played with a **negative (persistent) duration** are not recorded вЂ” without a recorded stop event they would loop forever during playback.
- The recording requires no config: start a Flashback recording, play effects, done.
- No interaction with the Flashback editor keyframes; this is replay recording/playback only.

## 9. How it renders (for debugging)

Post-processing pipeline, world overlays, effect clock, load limits and fault tolerance вЂ” **[docs/ARCHITECTURE.md](ARCHITECTURE.md)**.

---

## Changelog

Versioned feature history вЂ” **[docs/CHANGELOG.md](CHANGELOG.md)**.

Guide version: 20 вЂ” see changelog below.

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
- `/vfx playentity` accepts an optional `[players]` list вЂ” who sees the effect (default: the executing player).
- Removed the `/vfx key` command (nobody used it; `VFXAPI.sendKeyframe` and the network `KEYFRAME` action remain for mods).

### v17
- Flashback compatibility: client-local effects are recorded into Flashback replays and re-triggered during playback (soft dependency, reflection-based, no compile-time coupling).

### v16
- New effect types for entities: `entity_tint` (a solid translucent fill of the effect colour) and `entity_outline` (an "inverted hull" silhouette outline, thickness `width`). Targets are set by UUID.
- New command `/vfx playentity <effect> <targets>` вЂ” plays an effect on entities picked by a selector (up to 16 UUIDs).
- Both types support `through_blocks`: 0 вЂ” the effect hides behind walls, 1 вЂ” visible through them.

### v15
- New way to set a param вЂ” the math expression `"expr"` (variables `t`/`x`/`y`/`z`/`pi`/`e`, functions `sin`/`cos`/`abs`/`min`/`max`/`pow`/`sqrt`/`random`/`noise`). Compiled once, evaluated every frame; `random()`/`noise()` are unique per instance.
- Camera shake (`camera_shake`) is now unique per call (per-instance seed).

### v14
- The mutating `/vfx` commands (`play`, `playat`, `stop`, `set`, `key`) now require operator rights; `/vfx list` is open to all.
- Tighter client protection from a hostile/broken server: caps on network packet sizes (effect params, definition/curve sync), `vfx_sync` packet version check, cap on effect duration from the server, instance-id validation on stop.

### v13
- Datapack VFX effects and curves sync with dedicated-server clients (the `vfxweaver:vfx_sync` packet on join and after `/reload`) вЂ” custom effects now play for players on a dedicated server, like in single-player.

### v12
- `sendEffect` accepts a direct world position вЂ” no `pos_x/y/z` hack (the client immediately re-anchors spatial bindings to the point).
- Custom easing curves: files `data/<ns>/vfx_curves/<name>.json` or an inline object `{ "curve": [[t,v],...] }` in any `easing` field.
- Param multiplier: `"param": { keyframes/start-end/constant/binding + "multiply": { "bind": "proximity", ... } }` вЂ” final value = base Г— multiplier (e.g. an animated dent fading with distance from a point).
- `VFXAPI.playEffectId(...)` returns the instance id, `VFXAPI.stopEffect(long)` stops one specific instance; `sendStop(player, effectId, instanceId)` вЂ” over the network.
- Network protocol version 4: the packet carries an optional position and instance id.

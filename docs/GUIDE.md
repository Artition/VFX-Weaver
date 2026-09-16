# TOM Post Effects (vfxweaver) — Usage Guide

A client-side VFX library for Minecraft 26.2 / 26.1.x / 1.21.11 (Fabric). Screen post-processing (ping-pong FBO), camera shake, world overlays (block tint/outline), entity effects (tint/outline by UUID), keyframe animation, world/camera/player bindings, datapacks, network triggers and a public Java API.

- Guide version: 30 (see [docs/CHANGELOG.md](CHANGELOG.md) for history)
- Mod: `vfxweaver-1.1.3.jar` (one jar per Minecraft line), requires Fabric API

Files: `data/<namespace>/vfx/<name>.json` and `data/<namespace>/vfx_curves/<name>.json`. After edits — `/reload`. The effect id = `<namespace>:<name>`. On a dedicated server, definitions and curves are automatically synced to clients on player join and after `/reload`, so custom (datapack) effects work for all players, not just on the server.

The mutating commands `/vfx play`, `/vfx playat`, `/vfx playentity`, `/vfx stop`, `/vfx set` require operator rights (gamemaster level); `/vfx list` is open to everyone.

---

## Contents

1. [Commands](#1-commands)
2. [Effect types](#2-effect-types) — screen post-processing, world overlays, entity effects, misc
3. [Datapack format](#3-datapack-format) — definition fields, ways to set a param, world bindings, easings
4. [Persistent effects: on/off with animation](#4-persistent-effects-onoff-with-animation)
5. [Collections — several effects with one command](#5-collections--several-effects-with-one-command)
6. [Built-in effects](#6-built-in-effects)
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
| `/vfx validate [namespace]` | Dry-run health check of VFX definitions: prints how many are loaded and lists every broken datapack file with its parse error (optionally filtered by namespace, tab-completed). Operator-only. Useful for datapack development and server admin checks without digging through logs. |

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
- **Chaining animation segments ("from here"):** a live keyframe with a **negative time** starts its segment at the current moment, pinning whatever value the parameter has right now - so you can build variations without knowing the value. Ramp a blur up and let it hold (`/vfx play vfxweaver:blur {radius:4.0}` on a persistent definition, or a long duration), then call `VFXAPI.sendKeyframe(player, effect, "radius", -20, 0.0F, easing)` to fade it out from where it stands over 20 ticks. Because it stays one running instance and one continuous curve, nothing is applied twice. Use a **persistent** effect (or a duration long enough to cover the segment) - a finished effect is removed before the new segment can play.
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

#### `slice_shift`
The frame is cut by a straight line and the halves slide past each other along it; the exposed strips at the screen edges are filled with wrapped or mirrored copies of the world (no black gap).

| Param | Default | Description |
|---|---|---|
| `angle` | 0 | Cut-line tilt in degrees from horizontal (0 = horizontal line, 90 = vertical) |
| `offset` | 0 | Pushes the line off the screen centre along its normal, in screen fractions (-0.5..0.5) |
| `shift` | 0.05 (fades to 0) | How far each half slides along the line, in screen fractions; halves diverge by 2x `shift`, negative swaps the sides (-1..1) |
| `mirror` | 0 | Fill of the exposed strips: 0 = repeat/wrap, 1 = mirrored copy |

```
/vfx play vfxweaver:slice_shift {[angle:25],[shift:0.12]}
```

#### `noise_warp`
An animated value-noise field warps the picture in soft fluid patches; bright noise areas drag pixels the hardest.

| Param | Default | Description |
|---|---|---|
| `scale` | 8 | Noise cell detail across the screen (1..64) |
| `amplitude` | 0.03 (fades to 0) | Max pixel offset in the brightest areas, screen fractions (0..0.25) |
| `contrast` | 2 | Gathers the warp into distinct patches (>1) or flattens it (<1) |
| `coherence` | 1 | 1 = pixels flow along the noise gradient (liquid-glass), 0 = each patch pulls its own direction |
| `speed` | 0.5 | Field morph rate, cycles per second |
| `drift_x/y` | 0 / 0 | Pattern travel, screen fractions per second |

```
/vfx play vfxweaver:noise_warp {[amplitude:0.06],[scale:4],[contrast:3]}
```

#### `solarize`
Bright pixels invert, dark stay untouched.

| Param | Default | Description |
|---|---|---|
| `threshold` | 0.5 | Luma above which colours invert (0..1) |
| `softness` | 0 | Rolloff width around the threshold (0..1) |
| `intensity` | 1 (fades to 0) | Blend original to solarized (0..1) |

```
/vfx play vfxweaver:solarize {[threshold:0.4]}
```

#### `double_vision`
Two ghost copies of the frame offset left/right with a slow drift.

| Param | Default | Description |
|---|---|---|
| `offset` | 0.04 | Ghost distance from the centre, screen-width fractions (0..0.5) |
| `ghost_opacity` | 0.5 | Ghost opacity (0..1) |
| `drift` | 0 | Slow sinusoidal drift, screen fractions per second (0..0.2) |
| `intensity` | 1 (fades to 0) | Overall strength (0..1) |

```
/vfx play vfxweaver:double_vision {[offset:0.06],[ghost_opacity:0.7]}
```

#### `eyelids`
Two soft curved dark lids slide in from the top and bottom. Animate `openness` 1 -> 0 to close the eyes; the built-in is a static half-open template.

| Param | Default | Description |
|---|---|---|
| `openness` | 0.5 | 1 = wide open (lids off screen), 0 = fully closed |
| `softness` | 0.15 | Feathered lid edge width, screen-height fractions |
| `curve` | 0.35 | Lid bulge toward the centre: 0 = straight bars, 1 = heavy arc |

```
/vfx play vfxweaver:eyelids {[openness:0.2]}
```

#### `iris_wipe`
Everything outside a circle goes black - old-film iris transition.

| Param | Default | Description |
|---|---|---|
| `radius` | 0.4 -> 1.4 | Circle radius in screen-height fractions (0 = closed, 1.4 = fully open) |
| `softness` | 0.05 | Edge feather |
| `center_x/y` | 0.5 / 0.5 | Circle centre in UV (bindable to `screen_x/ screen_y`) |
| `zoom` | 1 -> 0 | Push-in inside the circle (0..1) |

```
/vfx play vfxweaver:iris_wipe {[zoom:0]}
```

#### `digital_glitch`
The frame tears into horizontal bands with RGB-split spikes, in bursts (slot-gated, not constant tearing).

| Param | Default | Description |
|---|---|---|
| `block` | 0.06 | Band height, screen-height fractions (0.01..0.5) |
| `displacement` | 0.08 | Max sideways band shift, screen-width fractions (0..0.5) |
| `rate` | 6 | Glitch quanta per second (slots) |
| `chroma` | 0.5 | RGB-split strength inside glitched bands (0..1) |
| `seed` | 0 | Pattern offset - change for a different tear layout |
| `chance` | 0.4 | Fraction of slots that burst (0..1) |
| `intensity` | 1 (fades to 0) | Overall strength (0..1) |

```
/vfx play vfxweaver:digital_glitch {[chance:1],[displacement:0.15]}
```

#### `vhs`
Worn VHS playback: wobble, a crawling noise tracking band, colour bleed and washed contrast.

| Param | Default | Description |
|---|---|---|
| `tracking` | 0.35 | Tracking band horizontal jumps (0..1) |
| `band_height` | 0.08 | Noise band height, screen-height fractions |
| `band_speed` | 0.15 | Band travel speed, screen-heights per second |
| `bleed` | 0.02 | Chroma smear to the right, screen-width fractions (0..0.1) |
| `wobble` | 0.004 | Fine constant horizontal jitter (0..0.05) |
| `intensity` | 1 (fades to 0) | Master strength (0..1) |

```
/vfx play vfxweaver:vhs {[band_speed:0.3]}
```

#### `shockwave`
A single refraction ring ripples outward from a point.

| Param | Default | Description |
|---|---|---|
| `center_x/y` | 0.5 / 0.5 | Wave origin in UV |
| `radius` | 0.4 -> 1.5 | Current ring radius, screen-height fractions (animate 0 -> 1.5) |
| `width` | 0.15 | Ring thickness |
| `amplitude` | 0.12 (fades to 0) | UV displacement at the ring crest |
| `sharpness` | 1.5 | Ring profile (1 = smooth sine ripple, 4 = hard glassy ring) |

```
/vfx play vfxweaver:shockwave {[radius:0.8]}
```

#### `afterimage`
Feedback echo: movement leaves smearing trails that linger and dissolve on a fixed decay schedule.

| Param | Default | Description |
|---|---|---|
| `decay` | 0.92 | Fraction of the previous frame surviving each tick (0..0.98) |
| `blend` | 0.6 | How strongly history mixes into the live image |
| `drift` | 0 | Per-frame zoom of the echo, positive stretches outward (negative = shrink) |
| `desat` | 0.35 | Saturation loss in the echo layer |
| `intensity` | 1 (fades to 0) | Strength of the echo over the live frame |

```
/vfx play vfxweaver:afterimage {[intensity:0.5]}
```

#### `stop_motion`
Stop-motion / papercraft: the picture updates only a few times per second while input keeps moving.

| Param | Default | Description |
|---|---|---|
| `fps` | 12 (fades to 0) | Target update rate of the held picture, updates per second (1..30; <=1 = back to full speed) |

```
/vfx play vfxweaver:stop_motion {[fps:8]}
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

Both support a list of coordinates via `positions` (see [3.1](#31-definition-fields)) or `region: [x0,y0,z0,x1,y1,z1]`. Without them a single position from `params.pos_x/y/z` is used - it can be a constant, an animation or a world binding. Positions may also be anchored to a live entity (see [3.1](#31-definition-fields)) — the effect follows it every frame; `/vfx playat` or a network position override wins over anchors, same as over static positions.

#### `light_beam`
A vertical glowing shaft of soft light descending onto each position. `top_scale` flares the top: 1 = cylinder, 2 = cone with twice the top radius. `softness` increases the number of concentric shells and fades their alpha: 0 = two hard tubes, higher = many thin, faint shells (a smooth blurred column).

| Param | Default | Description |
|---|---|---|
| `radius` | 1.5 | Beam radius in blocks (0.1..16) |
| `height` | 48 | Column height upward from the anchor (1..256) |
| `top_scale` | 1 | Top radius multiplier (0.1..8): 1 = cylinder, >1 = flared cone |
| `softness` | 0.6 | More concentric shells with lower alpha (0 = crisp, 2..4 = soft blurry column) |
| `top_fade` | 0.4 | Alpha at the top relative to the base (0..1) |
| `bottom_fade` | 0 | Alpha at the bottom relative to the base (0..1) |
| `red/green/blue` | 1 / 0.95 / 0.75 | Beam colour |
| `through_blocks` | 0 | 1 = visible through walls |
| `intensity` | 1 (fades to 0) | Beam opacity |

```
/vfx playat vfxweaver:light_beam 8 70 8 {[radius:2],[top_scale:2],[softness:3]}
```

#### `pulse_ring`
A glowing ring around each position. With `billboard:1` (default) the ring always faces the camera (perfect circle from any angle); with `billboard:0` it stays in a fixed plane rotated by `rot_x/rot_y/rot_z`.

| Param | Default | Description |
|---|---|---|
| `radius` | 0 -> 6 | Current ring radius, blocks (animate 0 -> max) |
| `thickness` | 0.5 | Ring band width, blocks |
| `billboard` | 1 | 1 = always faces the camera, 0 = fixed orientation by rot_* |
| `rot_x` | 0 | Fixed ring-plane pitch (degrees, -360..360, when billboard:0) |
| `rot_y` | 0 | Fixed ring-plane yaw (degrees, -360..360, when billboard:0) |
| `rot_z` | 0 | Fixed ring-plane roll (degrees, -360..360, when billboard:0) |
| `red/green/blue` | 1 / 0.35 / 0.1 | Ring colour |
| `through_blocks` | 0 | 1 = visible through walls |
| `intensity` | 1 (fades to 0) | Ring opacity |

```
/vfx playat vfxweaver:pulse_ring 8 70 8 {[radius:10],[billboard:0],[rot_x:60]}
```

#### `guide_line`
A glowing dashed line along a parabolic arc between two anchors.

| Param | Default | Description |
|---|---|---|
| `width` | 0.15 | Line thickness, blocks |
| `dash_length` | 0.6 | Dash length, blocks |
| `gap` | 0.6 | Gap between dashes, blocks |
| `speed` | 2 | Dash crawl speed along the line, blocks per second (negative = reverse) |
| `arc` | 1.5 | Bows the path up (+) or droops it (-) at the midpoint, blocks |
| `red/green/blue` | 0.25 / 1 / 0.45 | Line colour |
| `through_blocks` | 0 | 1 = visible through walls |
| `intensity` | 1 (fades to 0) | Line opacity |

```
/vfx playat vfxweaver:guide_line 8 70 8 {[arc:3]}
```

#### `particles`
Emits **vanilla particles** in animated shapes — no custom textures, everything is datapack-driven and works under shaderpacks. Two definition-level string fields choose the look:

| Field | Default | Description |
|---|---|---|
| `particle` | `minecraft:end_rod` | Any simple vanilla particle id (`minecraft:flame`, `minecraft:soul`, `minecraft:glow`, `minecraft:cloud`, ...). The special id `dust` builds a redstone-dust particle whose colour/size come from the animatable params below. Option-carrying types (`block`, `item`, ...) are not supported. |
| `shape` | `sphere` | `sphere` (random shell points), `ring` (flat circle on XZ), `helix` (rising spiral), `line` (between the first two `positions` slots, like `guide_line`), `cube` (random surface points), `point` (all at the anchor) |

| Param | Default | Description |
|---|---|---|
| `rate` | 40 | Particles per second (× fade weight). Animate it — e.g. keyframes for a burst. |
| `radius` | 2 | Shape size, blocks (animate for a growing shockwave ring) |
| `height` | 3 | Helix height, blocks |
| `turns` | 2 | Helix revolutions over its height |
| `spin` | 0 | Helix rotation phase, revolutions |
| `speed` | 0 | Launch velocity (blocks/s): random radial by default, towards the second position slot when `aim:1` |
| `aim` | 0 | 1 = aimed flight: every particle is launched towards the second `positions` slot (works for any shape and particle, drag-free ballistics; with entity-anchored slots the stream tracks moving targets) |
| `spread` | 0.15 | Cone spread around the aim direction (0 = perfectly aimed, 1 = wide spray) |
| `accel` | 0 | Acceleration along the aim direction, blocks/tick² — particles speed up in flight |
| `lifetime` | 0 | Particle lifetime override in ticks (0 = particle default). Match it to the flight time so particles die at the target |
| `vel_y` | 0 | Constant upward velocity (rising auras) |
| `size` | 1 | `dust` particle size (0.05..4) |
| `color_r/g/b` | 1 / 1 / 1 | `dust` colour (any RGB) |

```json
{
	"type": "particles",
	"loop": true,
	"particle": "dust",
	"shape": "helix",
	"params": { "rate": 80, "radius": 1.2, "height": 3.0, "color_r": 1.0, "color_g": 0.85, "color_b": 0.3 }
}
```

Emission is budgeted per instance (clamped to 1024 particles/s, 256 per frame) and stops automatically as the effect fades out. Anchors work like for all world overlays: `positions` may be entity-anchored, so an aura follows a player smoothly.

Aimed stream example — accelerating shot from one block to another (put both points into `positions`, or entity-anchor either end):

```json
{
	"type": "particles",
	"particle": "dust",
	"shape": "point",
	"positions": [[0, 70, 0], [20, 70, 20]],
	"params": { "rate": 60, "speed": 0.4, "accel": 0.12, "aim": 1, "spread": 0.05, "lifetime": 40,
		"color_r": 1.0, "color_g": 0.3, "color_b": 0.1 }
}
```

#### `block_chain`
A line of **real block-model links** between two anchors (like `guide_line`, but made of blocks) — the `block` definition field picks the block, links render with full vanilla textures/lighting and follow moving anchors every frame.

| Param | Default | Description |
|---|---|---|
| `spacing` | 1 | Distance between links, blocks (0.25..8); links tile the path end-to-end and stretch to span it exactly |
| `arc` | 0 | Bows the path up (+) or down/hanging (−) at the midpoint, blocks (same as `guide_line`); ignored in physics mode |
| `scale` | 1 | Link block size (0.1..4) |
| `align` | 1 | 1 = each link's Y axis is rotated to the local path direction (chain follows the curve), 0 = upright blocks |
| `physics` | 0 | 1 = verlet rope simulation: gravity sag, world collision (links catch on blocks), the local player pushes links away, `sway` wind wobble. With two anchors both ends are pinned; with one anchor the chain hangs from it (`length` blocks) |
| `length` | auto | Total chain length in blocks (physics mode). Single anchor: hanging length (default 6). Two anchors: unset = the span distance; more than the span = deeper sag; less than the span = taut (links stretch) |
| `length` | 6 | Hanging chain length in blocks (physics mode, single anchor) |
| `sway` | 0.3 | Wind wobble amplitude in physics mode (0..1) |

```json
{
	"type": "block_chain",
	"loop": true,
	"block": "minecraft:iron_chain",
	"positions": [[0, 72, 0], [12, 70, 6]],
	"params": { "spacing": 0.8, "arc": -1.5 }
}
```

Links are capped at 512 per effect; rendering happens in the vanilla submit pipeline (same path as falling blocks), so it works under shaderpacks. When the anchors are farther apart than the chain, links stretch along the path (up to 4×) to stay connected — a pulled-apart chain goes taut rather than showing gaps.

```json
{
	"type": "block_chain",
	"loop": true,
	"block": "minecraft:iron_chain",
	"positions": [{ "entity": "@s", "point": "center" }],
	"params": { "physics": 1, "length": 6, "sway": 0.4 }
}
```

Physics is a client-side visual simulation (verlet rope at a fixed tick rate) — it does not affect the server world or other entities beyond the push interaction with the local player.

The builtin `vfxweaver:particles` demo binds its position to the local player (`pos_x/y/z` with `bind: player_x/y/z`), so plain `/vfx play vfxweaver:particles` spawns the helix around the viewer; `/vfx playat` and `positions` override that as usual.

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

#### `entity_displace`
Displaces the target entity's model vertices themselves (rendered with the vanilla body material, so lighting/shadows stay normal) - the body tears/glitches, no ghost copy on top.

| Param | Default | Description |
|---|---|---|
| `amplitude` | 0.1 (fades to 0) | Max displacement in blocks (0..2) |
| `scale` | 4 | Field detail: higher = neighbours diverge more (0.5..32) |
| `seed` | 0 | Random phase; step it (`expr: "floor(t*8)*0.1"`) for 8x/s snaps, animate for smooth morphing |

```
/vfx playentity vfxweaver:entity_displace @e[type=zombie,limit=1] {[amplitude:0.2],[scale:6]}
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
| `hand` | 0.5 | First-person hand multiplier: 0 = hand stays still, 1 = full shake with the camera |

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

> **Layering note** (`screen_layer` for camera/roll effects): the `screen_layer` parameter
> (`0` under the first-person hand, `1` above the hand below the GUI, `2` above everything) applies
> only to **screen post-processing** effects in section 2.1. Camera-space effects
> (`camera_shake`, `camera_roll`, `fov_modifier`) act directly on the camera transform - the world
> shakes, and the same shake is applied to the first-person hand (it moves together with the world).
> They do not accept `screen_layer`.

#### `camera_roll`
Tilts the camera around its viewing axis by a fixed angle (dutch angle) with an optional slow wobble.

| Param | Default | Description |
|---|---|---|
| `angle` | 15 (fades to 0) | Roll in degrees; positive = clockwise lean |
| `wobble` | 0 | Sinusoidal sway of +/- this many degrees (0..45) |
| `wobble_speed` | 0.2 | Sway frequency, Hz (0..2) |

```
/vfx play vfxweaver:camera_roll {[angle:25],[wobble:5]}
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
| `positions` | array `[x,y,z]` or objects | — | World coordinate list for world overlays (`block_tint`/`block_outline`/`light_beam`/`pulse_ring`/`guide_line`/`particles`). Each entry is either a plain `[x,y,z]` array or an entity anchor `{"entity": "<selector>", "offset": [x,y,z]}` — see below. If not set — `params.pos_x/y/z` is used. Not used for entity effects (targets are set by UUID). Static entries anchor to a block (the effect uses the block's centre on X/Z); entity anchors and Java-API moves use exact sub-block coordinates. |
| `particle` | string | — | Vanilla particle id for the `particles` effect (e.g. `"minecraft:end_rod"`, `"dust"`), see the `particles` subsection in [2.2](#22-world-overlays-block-geometry). |
| `shape` | string | — | Emission shape for the `particles` effect: `sphere`/`ring`/`helix`/`line`/`cube`/`point`. |
| `block` | string | — | Block id for the `block_chain` effect (e.g. `"minecraft:iron_chain"`), see the `block_chain` subsection in [2.2](#22-world-overlays-block-geometry). |
| `entity_selector` | string | — | Entity selector (e.g. `"@e[type=minecraft:zombie,distance=..10]"`) that the server resolves into target UUIDs on every play. Lets you trigger an entity effect with plain `/vfx play` (no `playentity`): the effect finds its own targets. For entity effects (`entity_tint`/`entity_outline`). |

**Entity-anchored positions.** A `positions` entry may be an object instead of a `[x,y,z]` array: `{"entity": "<selector>", "offset": [x,y,z], "point": "center", "dir": "look", "distance": 24}`. The `offset` is optional and relative to the resolved anchor point; `point` selects the reference point on the entity — `feet` (default), `center` (bounding-box centre) or `eyes`; `dir` + `distance` optionally push the anchor along an entity direction (`look` = the tracked entity's live look direction, e.g. eyes + look × 24 = a target where the entity is looking — a laser). The server resolves each selector once per play (first match wins, `/vfx play` fails if an anchor matches nothing); the client substitutes the tracked entity's current anchor-point position every frame, so the effect follows a moving entity:

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

Slot order is preserved, so `guide_line` endpoints can mix static and anchored entries (e.g. one end on the player, the other on a block). If a tracked entity disappears (death, despawn, out of range) the effect skips rendering until it is trackable again. Works for all world overlays (`block_tint`, `block_outline`, `light_beam`, `pulse_ring`, `guide_line`); the Java API passes anchor entities as regular target UUIDs (`EffectRequest.target()`), in anchor order.

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
	//    time_of_day, player_x/y/z. Functions: sin, cos, tan, atan(y), atan(y, x) (= atan2),
	//    abs, sign, floor, ceil, round, fract, sqrt, pow, exp, log (natural), mod(a, b),
	//    min, max, clamp(x, lo, hi), lerp/mix(a, b, t), step(edge, x),
	//    smoothstep(e0, e1, x), random() (0..1), noise(x,y,z) (simplex 3D, -1..1).
	//    random()/noise() are unique per instance; argument counts are checked when the
	//    expression is compiled.
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
| `scoreboard` | raw/range, clamped 0..1 (×scale) | A scoreboard value: `{"bind": "scoreboard", "objective": "my_obj", "holder": "optional_name"}`. Default holder — the local player's own score; with `holder` — the literal named scoreholder. Normalized on `range` (default 16), `invert`/`scale` as usual; missing objective/score → 0. Works as a main value and as a `multiply` multiplier. |

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

Child effect fields: `effect` (id, required), `delay` (ticks from collection start), `duration` (0 = definition default, −1 = persistent), `params` (each value is a full parameter spec — plain numbers are constants, objects support `start`/`end`, `keyframes`, `bind`, `expr`, `multiply`, see §3.2), `easing`. Collection nesting — up to 4 levels. `/vfx stop <collection>` cancels not-yet-started children; already playing ones are stopped by their own `/vfx stop`.

---

## 6. Built-in effects

Built-ins ship as regular datapack JSON inside the mod jar (`data/vfxweaver/vfx/*.json`) — they load, sync and can be overridden by higher-priority packs exactly like custom definitions, and a broken one shows up in `/vfx list`/`/vfx validate` like any other. To tweak a built-in, copy its JSON out of the jar (`vfxweaver-1.1.0.jar → data/vfxweaver/vfx/…`) into your datapack under a new id.

Post-processing: `vfxweaver:chromatic_aberration`, `vfxweaver:color_grade`, `vfxweaver:distortion`, `vfxweaver:dent`, `vfxweaver:gradient_map`, `vfxweaver:posterize`, `vfxweaver:blur`, `vfxweaver:pixelate`, `vfxweaver:hue_isolation`, `vfxweaver:vignette`, `vfxweaver:screen_flash`, `vfxweaver:motion_blur`, `vfxweaver:bloom`, `vfxweaver:film_grain`, `vfxweaver:scanlines`, `vfxweaver:depth_of_field`, `vfxweaver:letterbox`, `vfxweaver:invert`, `vfxweaver:vortex`, `vfxweaver:speed_lines`, `vfxweaver:slice_shift`, `vfxweaver:noise_warp`, `vfxweaver:solarize`, `vfxweaver:double_vision`, `vfxweaver:eyelids`, `vfxweaver:iris_wipe`, `vfxweaver:digital_glitch`, `vfxweaver:vhs`, `vfxweaver:shockwave`, `vfxweaver:afterimage`, `vfxweaver:stop_motion`.

World overlays: `vfxweaver:block_tint`, `vfxweaver:block_outline`, `vfxweaver:light_beam`, `vfxweaver:pulse_ring`, `vfxweaver:guide_line`, `vfxweaver:particles`.

Entity effects: `vfxweaver:entity_tint`, `vfxweaver:entity_outline`, `vfxweaver:entity_displace`.

Misc: `vfxweaver:camera_shake`, `vfxweaver:camera_roll`, `vfxweaver:fov_modifier`.

All have fade animation (40 ticks, except where noted); params can be overridden by collections.

---

## 7. Java API (for other mods)

Briefly:

```java
VFXAPI.sendEffect(serverPlayer, effectId, Map.of(), null); // server → client
VFXAPI.playEffect(effectId, 0, Map.of("radius", 8.0F), EasingType.EASE_OUT_CUBIC); // locally on the client
```

Full reference (all `VFXAPI` methods, `VFXLocalDispatcher`, the `vfxweaver:vfx_trigger` network packet format) — **[docs/API.md](API.md)**.

Live-editing methods worth knowing:

```java
// Replace a running effect's parameter with a math expression (same syntax as the JSON "expr",
// per-instance seed), without restarting the timeline:
VFXAPI.sendSetParamExpr(player, effectId, "radius", "1.5 + 0.5*sin(t/10)");

// Smoothly move a running world-overlay instance to a new exact point (sub-block precision).
// Call it every tick from your own code to make the effect follow any scripted path:
VFXAPI.sendMove(player, effectId, instanceId, new Vec3(x, y, z));
```

A client mod may also **request** effects from the server with the serverbound `vfxweaver:vfx_request` packet (see API.md): without the `broadcast` flag the effect plays only for the requesting client; with `broadcast: true` it plays for every connected player — but the server grants broadcast only to operators (gamemaster level), so regular-player clients cannot spam effects at others. Note: custom named easing curves are not preserved on the request path (built-in easing names only).

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

Guide version: 27 — see changelog below.

### v30
- New Java API for client-only mods: `VFXAPI.registerDefinitions(Map)` / `VFXAPI.unregisterDefinition(id)` register effect definitions from code. They live in a local layer that survives `/reload` and a server sync (a datapack shipped by a client-side mod only loads in single player, and a server sync used to replace the whole definition set), stay private to this client, and use the same validation as datapack files.

### v29
- Live keyframes accept a **negative time** ("from here"): the value the parameter has right now is pinned at the current time and the segment runs to the new value over `|time|` ticks, so animation variations chain without the caller knowing the current value - `sendKeyframe(player, effect, "radius", -20, 0.0F, easing)` fades a held blur back out from wherever it stands.
- The live edits are now available **client-side without a packet**: `VFXAPI.setParam`, `VFXAPI.setParamExpr` and `VFXAPI.setKeyframe` mirror the network actions (`sendSetParam`, `sendSetParamExpr`, `sendKeyframe`) locally, next to the already-local `playEffect`/`playEffectId`/`moveEffect`/`stopEffect` - a pure client-side mod can drive effects end to end.
- Live edits are **recorded into Flashback replays**: `setParam`, `setParamExpr` and `setKeyframe` (local and server-driven) replay along with the original play, so an effect animated while recording keeps its animation on playback.

### v28
- Math expressions gained more functions: `floor`, `ceil`, `round`, `fract`, `sign`, `clamp(x, lo, hi)`, `lerp`/`mix(a, b, t)`, `step(edge, x)`, `smoothstep(e0, e1, x)`, `mod(a, b)`, `tan`, `atan(y)` / `atan(y, x)` (= atan2), `exp`, `log` (natural). Argument counts are now validated when the expression is compiled (a wrong count used to fail while evaluating).
- The client-local Java API can anchor effects: `VFXAPI.playEffect`/`playEffectId` take an optional world position and entity-UUID list, and `VFXAPI.moveEffect(effectId, instanceId, pos)` re-anchors a running instance - no packet involved. The position re-anchors spatial bindings (`screen_x`, `screen_y`, `proximity`, ...) like `/vfx playat`, so point effects (`dent`, `shockwave`, `vortex`) can be placed at an event and follow it.

### v27
- Build restructured for multiple Minecraft versions (Stonecutter). Adds a `1.21.11`
  build alongside `26.1.2`; effect behavior, datapack format and network protocol are unchanged.

### v26
- World overlays: entity anchors gained `point` — the reference point on the entity: `feet` (default), `center` (bounding-box centre) or `eyes` (e.g. `{"entity": "@s", "point": "center"}`), so effects can attach to the middle/head of an entity instead of its feet.
- World overlays: entity anchors gained `dir: "look"` + `distance` — the anchor is pushed along the tracked entity's live look direction (eyes + look × 24 = a laser target where the entity is looking).
- New world-overlay effect `block_chain`: a line of real textured block-model links between two anchors (datapack picks the block, `spacing`/`arc`/`scale`/`align` params), rendered through the vanilla submit pipeline (shaderpack-safe). Optional `physics: 1` — a verlet rope with gravity sag, world collision, player push, `sway` wind and animatable `length` (single-anchor hang or two-anchor slack; links stretch when pulled taut).
- New world-overlay effect `particles`: emits vanilla particles in animated shapes (`sphere`/`ring`/`helix`/`line`/`cube`/`point`) with any RGB via `dust`. No custom textures — everything from the datapack; entity anchors and fades work as usual. Builtin demo: `vfxweaver:particles` (golden dust helix).

### v25
- New command `/vfx validate [namespace]` — dry-run definition health report (loaded count + parse errors per file), operator-only.
- New world binding `scoreboard`: a param can follow a scoreboard value (`objective` + optional `holder`, normalized on `range`, works as a value or a `multiply` multiplier).
- New Java API: `sendSetParamExpr` (swap a running effect's param for a live math expression), `sendMove` (move a running world-overlay instance to an exact point — call per tick for scripted motion), and a serverbound `vfxweaver:vfx_request` packet letting client mods play effects through the server (broadcast is operator-gated).
- Built-in effects now ship as JSON resources (`data/vfxweaver/vfx/*.json` inside the mod jar) instead of code — they load, sync and can be overridden like any datapack definitions; copy a file out of the jar to tweak it.
- World-overlay geometry uses exact `Vec3` coordinates: entity anchors and API moves are no longer snapped to block centres (static `positions` entries keep the historical block-centre behaviour). Protocol version 6.

### v24
- World overlays: `positions` entries may anchor to a live entity — `{"entity": "<selector>", "offset": [x,y,z]}`. The server resolves the selector once per play (plain `/vfx play`; fails if it matches nothing), the client follows the entity every frame; the Java API passes anchors as target UUIDs in anchor order.
- Collections: child `params` values are now full parameter specs — `start`/`end`, `keyframes`, `bind`, `expr`, `multiply` (plain numbers still work as constants).

### v23
- `light_beam`: new `bottom_fade` param (fades the column toward the bottom, mirrors `top_fade`).
- Fixed `light_beam` rendering under shaderpacks: packs that declare vertex colour `flat` (Complementary) take each triangle's colour from one vertex, so the height fade showed as visible triangles. Faded shells are now split into 32 narrow slices, each quad with one uniform colour - clean stepped fade under shaders, imperceptible difference in vanilla.

### v22
- New screen effects: `slice_shift`, `noise_warp`, `solarize`, `double_vision`, `eyelids`, `iris_wipe`, `digital_glitch`, `vhs`, `shockwave`, `afterimage`, `stop_motion`.
- New world effects: `light_beam` (layered shells, cubic softness, `top_scale`), `pulse_ring` (camera-facing billboard + `rot`), `guide_line`.
- New entity effect: `entity_displace` (flat per-vertex displaced echo over the intact model).
- New misc effects: `camera_roll`.
- `afterimage` uses an island blend; `camera_shake`/`camera_roll` also move the first-person hand.
- Removed before release: `block_displace`, `god_rays`, `scan_sweep`, `hud_fade` (cut before release, will be redesigned later).

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

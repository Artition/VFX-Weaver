# Effects and the datapack format

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
| `pos_rand` | 1.0 | Per-line angular position variance: 0 = lines evenly spaced around the centre, 1 = each line may sit anywhere inside its own slice (uneven spacing, no overlap) |
| `width` | 0.5 | Line thickness |
| `seed` | 0 | Layout seed: drives each line's length **and** its position; animate via `expr` (e.g. `"t * 2.0"`) to make the layout churn |
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
| `thickness` | 0.5 | Ring band width, blocks (`0` = no band, the ring is not drawn) |
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

**Block and item mode.** Instead of a vanilla particle, the emitter can spawn **real block or item models** (full 3D model, textures, lighting and occlusion). Each particle is a **client-side display entity** — a `BlockDisplay` or `ItemDisplay` added to the client level — so vanilla interpolates its motion (smooth, no stepped submits) and its brightness, scale and tumbling orientation are the display entity's own brightness override / transformation. A model particle spawns in a random 3D orientation and, when `spin` is non-zero, tumbles about a random axis like a real falling cube; on landing, the contacted block's friction damps the tumble until it settles. Pick a single model inline, or a reusable preset:

- `"particle": "block"` with `"block": "<block state>"` — an inline block spec. The `block` field accepts a full block state string, e.g. `"minecraft:oak_stairs[facing=east]"` (default `minecraft:stone`).
- `"particle": "item"` with `"item": "<item id>"` — an inline item spec, e.g. `"item": "minecraft:skeleton_skull"` or `"minecraft:diamond_sword"`. This is how you get a **skull/head particle**: the block form of a skull has no baked block model (a block-entity renderer draws it), but the item form does. See the preset form below for a reusable item spec.
- `"particle": "<namespace>:<preset>"` — a preset from `data/<namespace>/vfx_particles/<name>.json` or registered through `VFXAPI.registerBlockParticle`. A preset is block- or item-based; both render and share the physics below. An unknown preset is treated as a vanilla particle id; with no such particle nothing is emitted and the mod logs one warning (the documented fallback).

Everything else (shape, `rate`, positions/bindings, aimed mode) works exactly as above. The model's physics and light are taken from the spec and overridden per effect by these params:

| Param | Default (inline/preset) | Description |
|---|---|---|
| `brightness` | -1 | Light override with **block-display semantics**: `-1` = use the world light at each particle, `0..15` = render that light level regardless of the surroundings (`[blockLight, skyLight]` in a preset for a split value). |
| `gravity` | 1 | Downward acceleration per tick, as a multiple of the vanilla `0.04`. `0` = weightless. |
| `friction` | 0.94 | Air drag multiplier per tick (1 = no drag). |
| `collide` | 1 | Surface friction on contact, `0..1`; `0` disables world collision entirely. |
| `bounce` | 0 | Restitution of the normal velocity on contact (`0` = no bounce, `1` = full bounce). |
| `size` | 0.25 | Model scale (1 = one full block). |
| `life` | 60 | Lifetime in ticks. |
| `spin` | 0 | Rotation angular speed per tick, in degrees (in model mode this replaces the helix-phase meaning of `spin`). `0` gives a static model. |
| `spin_random` | 1.0 | `0..1`: how random the initial orientation (and, in tumble mode, the rotation axis) is. `0` = strictly upright and identical for every particle, deterministic; `1` = fully random. |
| `spin_friction` | 1.0 | `0..1`: how much the contacted block's own friction damps the tumble on contact. `0` = the spin never decays; `1` = the full block friction. Tumble mode only. |
| `spin_roll` | 0.5 | `0..1`: how much tangential impact speed feeds the tumble on contact. `0` = no roll transfer. Tumble mode only. |

The **rotation mode** and the **rotation axis** (`spin_mode`, `spin_axis`) are preset fields (see [3.5](#35-blockitem-particle-presets-vfx_particles)) — the effect params above tune the three 0..1 values on top of whichever preset the effect names. A model with the defaults (`spin_mode: tumble`, `spin_axis: random`) reproduces the physical tumbling described above.

`brightness` behaves exactly like a block display's brightness (`net.minecraft.util.Brightness`): `-1` follows the world, anything else pins that packed light (`block << 4 | sky << 20`) on the particle's display entity. So `brightness: 15` makes the particles glow at full block+sky light even in a pitch-black room, which is how you get readable "fireflies" or embers at night.

Copy-paste — a burst of glowing stone blocks that fall, bounce and spin:

```json
{
	"type": "particles",
	"particle": "block",
	"block": "minecraft:stone",
	"shape": "sphere",
	"duration": 100,
	"params": {
		"rate": 30, "radius": 1.5, "speed": 0.25, "vel_y": 0.3, "spread": 0.6,
		"brightness": 15, "gravity": 1.0, "friction": 0.96, "collide": 0.8,
		"bounce": 0.45, "size": 0.3, "life": 80, "spin": 8
	}
}
```

Copy-paste — **skeleton-head particles flying out of the player** (item mode; the preset form of the same thing is in [3.5](#35-blockitem-particle-presets-vfx_particles)):

```json
{
	"type": "particles",
	"particle": "item",
	"item": "minecraft:skeleton_skull",
	"shape": "sphere",
	"duration": 200,
	"easing": "ease_out_cubic",
	"positions": [{ "entity": "@s", "point": "eyes" }],
	"params": {
		"rate": 40.0, "radius": 0.5, "speed": 0.35, "vel_y": 0.3, "spread": 0.9,
		"brightness": 15, "gravity": 0.9, "friction": 0.95, "collide": 1.0,
		"bounce": 0.35, "size": 0.45, "life": 100, "spin": 12
	}
}
```

Or through a preset id (define the file, then just name it from the effect):

```json
{ "type": "particles", "particle": "mymap:ember", "shape": "point", "loop": true,
  "params": { "rate": 20, "pos_x": { "bind": "player_x" }, "pos_y": { "bind": "player_y" }, "pos_z": { "bind": "player_z" } } }
```

Model particles (block or item) are capped (2048 live display entities, 512 per effect instance, 256 spawned per frame per effect) and simulated at a fixed tick step, so a runaway emitter cannot flood the frame. Each live particle owns one client-side display entity; it is removed when the particle dies, the effect stops or the world unloads. They are client-side only: nothing about them touches the server world.

#### `block_chain`
A line of **real block-model links** between two anchors (like `guide_line`, but made of blocks) — the `block` definition field picks the block, links render with full vanilla textures/lighting and follow moving anchors every frame.

| Param | Default | Description |
|---|---|---|
| `spacing` | 1 | Distance between links, blocks (0.25..8); links tile the path end-to-end and stretch to span it exactly |
| `arc` | 0 | Bows the path up (+) or down/hanging (−) at the midpoint, blocks (same as `guide_line`); ignored in physics mode |
| `scale` | 1 | Link block size (0.1..4) |
| `align` | 1 | 1 = each link's Y axis is rotated to the local path direction (chain follows the curve), 0 = upright blocks |
| `physics` | 0 | 1 = verlet rope simulation: gravity sag, swept world collision with surface friction (links catch and settle on blocks — the contacted block's own friction decides how much they grip: stone 0.6/tick, ice 0.98/tick), the local player pushes links away, `sway` wind wobble. With two anchors both ends are pinned; with one anchor the chain hangs from it (`length` blocks) |
| `length` | auto | Total chain length in blocks (physics mode). Single anchor: hanging length, default 6. Two anchors: unset (0) = the span distance; more than the span = deeper sag; less than the span = taut (links stretch) |
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

Physics is a client-side visual simulation (verlet rope at a fixed tick rate) — it does not affect the server world or other entities beyond the push interaction with the local player. Physics chains settle on terrain instead of gliding forever: each joint sweeps the path since its previous position and is pushed back out through the face it entered (a pure displacement that injects no velocity), then on contact loses its inward velocity and has its tangential velocity damped each tick by the contacted block's own friction (`Block#getFriction`: stone and air 0.6, ice 0.98) — so a rope grips stone, glides on ice, rests on surfaces without sinking, a joint that spawns inside a wall comes free, and a moving anchor drags the chain across the ground. Multi-box collision shapes are approximated by their union box.

The built-in `vfxweaver:block_chain` demo hangs a 6-link `minecraft:iron_chain` from the local player (`pos_x/y/z` bound to `player_x/y/z`, `physics: 1`), so plain `/vfx play vfxweaver:block_chain` works without a datapack — the same self-anchoring pattern the `vfxweaver:particles` demo uses.

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

Targets are set via `/vfx playentity <effect> <selector>`, via the Java API (see [7](index.md#7-java-api-for-other-mods)) or via the `entity_selector` field in the definition (then `/vfx play <effect>` is enough - the server finds the targets itself). One effect can target up to 16 entities; several effects can hang on one entity. On the first-person hand the local player's own effects are rendered too (`through_blocks` is ignored there - the hand always draws on top).

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
| `particle` | string | — | Particle for the `particles` effect: a vanilla id (e.g. `"minecraft:end_rod"`, `"dust"`), the literal `"block"` (inline block mode, uses the `block` field), the literal `"item"` (inline item mode, uses the `item` field) or a `vfx_particles` preset id. See the `particles` subsection in [2.2](#22-world-overlays-block-geometry). |
| `shape` | string | — | Emission shape for the `particles` effect: `sphere`/`ring`/`helix`/`line`/`cube`/`point`. |
| `block` | string | — | Block state for the `block_chain` effect (e.g. `"minecraft:iron_chain"`) or the inline `particles` block mode (e.g. `"minecraft:oak_stairs[facing=east]"`); see the `block_chain`/`particles` subsections in [2.2](#22-world-overlays-block-geometry). |
| `item` | string | — | Item id for the inline `particles` item mode (e.g. `"minecraft:skeleton_skull"`), used when `particle` is the literal `"item"`; see the `particles` subsection in [2.2](#22-world-overlays-block-geometry). |
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

### 3.5 Block/item-particle presets (`vfx_particles`)

Reusable model-particle definitions live in `data/<namespace>/vfx_particles/<name>.json`, id `<namespace>:<name>`. A `particles` effect names one with `"particle": "<namespace>:<name>"` (see the `particles` block and item mode in [2.2](#22-world-overlays-block-geometry)). Presets are client-local: they are **never synced to other players**, so a preset only exists where its file (or registration) does.

```json
{ "block": "minecraft:stone", "brightness": [15, 15], "gravity": 0.8, "friction": 0.94,
  "collide": 1.0, "bounce": 0.2, "size": 0.35, "life": 60, "spin": 12 }
```

A preset draws a block **or** an item — exactly one of the two is required. The item form is the reusable version of the skull example above:

```json
{ "item": "minecraft:skeleton_skull", "brightness": [15, 15], "gravity": 0.9, "friction": 0.95,
  "collide": 1.0, "bounce": 0.35, "size": 0.45, "life": 100, "spin": 12 }
```

| Field | Type | Default | Description |
|---|---|---|---|
| `block` | string | — (one of `block`/`item`) | Block state drawn by each particle, e.g. `minecraft:oak_planks` or `minecraft:oak_stairs[facing=east]` |
| `item` | string | — (one of `block`/`item`) | Item id drawn by each particle, e.g. `minecraft:skeleton_skull` or `minecraft:diamond_sword`; the alternative to `block`. An unknown item is a per-file parse error. |
| `brightness` | int or `[blockLight, skyLight]` | -1 | `-1` = world light; `0..15` = that light level on both channels; `[b, s]` = separate block/sky levels. Block-display semantics (see the explanation in the `particles` block mode). |
| `gravity` | float | 1.0 | Downward acceleration per tick (× 0.04) |
| `friction` | float | 0.94 | Air drag multiplier per tick (0..1) |
| `collide` | float | 1.0 | Surface friction on contact (0..1); `0` disables world collision |
| `bounce` | float | 0.0 | Normal-velocity restitution on contact (0..1) |
| `size` | float | 0.25 | Model scale (1 = one full block) |
| `life` | int | 60 | Lifetime in ticks |
| `spin` | float | 0.0 | Rotation angular speed per tick, in degrees |
| `spin_mode` | string | `"tumble"` | Rotation model: `tumble` (physical full-3D spin, the default), `yaw` (uniform spin about one axis) or `none` (no rotation: upright, no contact response) |
| `spin_axis` | string or `[x, y, z]` | `"random"` | Rotation axis: `"random"`, `"x"`, `"y"`, `"z"` or a vector `[x, y, z]`. For `yaw` it is the spin axis (world Y when random/unset); for `tumble` it fixes the base axis instead of drawing one per particle |
| `spin_random` | float | 1.0 | `0..1`: how random the initial orientation and tumble axis are (0 = strictly upright and identical for every particle) |
| `spin_friction` | float | 1.0 | `0..1`: how much the contacted block's friction damps the tumble on contact (0 = never decays) |
| `spin_roll` | float | 0.5 | `0..1`: how much tangential impact speed feeds the tumble on contact (0 = no roll transfer) |

A preset only supplies defaults: a `particles` effect that names it can still override any of these with the matching effect params (`spin`, `spin_random`, `spin_friction`, `spin_roll`).

Three copy-paste rotation recipes (drop each into `data/<namespace>/vfx_particles/<name>.json` and name it from a `particles` effect with `"particle": "<namespace>:<name>"`):

**1. Tumbling cubes** — the default physical model: random orientation, end-over-end tumble about a random axis, contact damping and roll:

```json
{ "block": "minecraft:oak_planks", "brightness": [15, 15], "gravity": 1.0, "friction": 0.94,
  "collide": 1.0, "bounce": 0.35, "size": 0.35, "life": 80,
  "spin": 12, "spin_mode": "tumble", "spin_axis": "random",
  "spin_random": 1.0, "spin_friction": 1.0, "spin_roll": 0.5 }
```

**2. Spinning top** — a clean uniform spin about world Y (the pre-tumble behaviour), upright start, no contact roll:

```json
{ "block": "minecraft:oak_planks", "gravity": 1.0, "friction": 0.94,
  "collide": 1.0, "bounce": 0.1, "size": 0.35, "life": 120,
  "spin": 24, "spin_mode": "yaw", "spin_axis": "y",
  "spin_random": 0.0, "spin_friction": 0.0, "spin_roll": 0.0 }
```

**3. No rotation** — a static, strictly upright model (contact still moves it, the model just never turns):

```json
{ "block": "minecraft:oak_planks", "gravity": 1.0, "friction": 0.94,
  "collide": 1.0, "bounce": 0.0, "size": 0.35, "life": 100,
  "spin_mode": "none" }
```

**Two-layer rule and the Java API.** Like effect definitions, presets have two layers: the datapack set (reloaded with `/reload`) and a code-registered local set written with `VFXAPI.registerBlockParticle(id, spec)`. The local layer survives `/reload` and is private to this client; the datapack layer wins for the same id. `VFXAPI.unregisterBlockParticle(id)` removes a local preset and `VFXAPI.blockParticle(id)` looks one up. Both layers are capped at 256 entries, and a broken file is reported by `/vfx validate` without affecting the rest. `VFXAPI.spawnBlockParticle(spec, position, velocity)` spawns a single block particle immediately on the client (no packet, no effect instance); see [docs/API.md](../API.md).

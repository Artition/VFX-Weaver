# Spec: New Effects Batch — `slice_shift`, `noise_warp`, `vertex_displace`

**Status:** Proposed (approved by the project owner; not implemented yet)
**Date:** 2026-09-05
**Author:** project owner (effects pitched via an external AI brainstorm, finalized by the owner)
**Target release:** v1.0.7+ (next feature release)

## Overview

Two new Screen (post-processing) effects, both single-pass fragment shaders over the main
framebuffer, proposed and specified by the project owner:

1. **`slice_shift`** — the frame is cut by a straight line and the halves slide past each other
   along that line. Exposed strips at the screen edges are filled with wrapped or mirrored copies
   of the world, so there is no black gap.
2. **`noise_warp`** — an animated 2D value-noise field warps the picture in soft fluid patches;
   bright areas of the noise drag pixels the hardest, and the field itself drifts and morphs over
   time.

Both follow the mod-wide conventions:

- All parameters are floats; modes are expressed as 0/1-style numeric params.
- The "main" parameter fades to neutral over the effect duration (`(fades to 0)` in the docs).
- Overriding the main parameter via command/API turns it into a constant (no auto-fade) —
  documented behaviour, must hold for these effects too.
- Both must accept `screen_layer` (0/1/2) like every other screen effect.

---

## Effect 1: `slice_shift`

### Description

A straight line slices the frame in two, and the halves slide past each other **along the line**:
one side drifts one way, the other side the opposite way — like a strike-slip fault tearing
through the picture. Pixels pushed off a screen edge re-enter from the opposite edge, so the
strips the slide opens up are filled with seamless copies of the world: no visible border, no
black gap, just quiet duplication. Animate `shift` from 0 for a slow creeping tear, or spike it
for a reality-snap when something steps through.

### Parameters

| Param | Default | Description |
|---|---|---|
| `angle` | 0 | Tilt of the cut line in degrees from horizontal: 0 = horizontal line (the halves slide left/right past each other), 90 = vertical line (the halves slide up/down), 45 = diagonal tear (0..360) |
| `offset` | 0 | Pushes the line off screen centre along its perpendicular, in screen fractions; positive = toward the upper/right normal of the line (−0.5..0.5) |
| `shift` | 0.05 (fades to 0) | How far each half slides along the line, in screen fractions; the two halves diverge by 2×`shift`, and a negative value swaps which half goes which way (−1..1) |
| `mirror` | 0 | Fill of the strips exposed at the screen edges: 0 = repeat — the content wraps around and re-enters from the opposite edge as a copy; 1 = mirrored copy, seamless with no visible duplicate jump (0..1) |

### Implementation notes

- Single fragment pass, single texture sample of the current frame.
- Aspect-corrected UV: side of the cut = `sign(dot(uv − linePoint, n))`, where
  `linePoint = vec2(0.5) + n * offset` and `n` is the line normal derived from `angle`
  (`n = vec2(cos(a + 90°), sin(a + 90°))`, line direction `d = vec2(cos a, sin a)`).
- Sample position: `uv − side · shift · d`.
- Fill of exposed strips: `fract()` wrap over both axes; when `mirror ≥ 0.5` — mirrored sample
  `abs(2 · fract(x / 2) − 1)`; final = mix of the two modes by `mirror`.
- At `shift == 0` the pass is identity — neutral state for the built-in fade, no `mix` needed.
- Uniform block: the mod's standard post uniform set (sampler + params), registered in
  `VFXShaderPrograms` under location `vfxweaver:post/slice_shift`.
- Params must be exposed in the built-in definition (`VFXDefinitionManager.registerBuiltIns`)
  with the defaults above; `shift` animated `0.05 → 0` (fade-out), `angle`/`offset`/`mirror`
  constant.

### Example

```
/vfx play vfxweaver:slice_shift {[angle:25],[shift:0.12],[offset:-0.1]}
```

---

## Effect 2: `noise_warp`

### Description

The picture warps in soft fluid patches: an invisible noise field decides where — pixels sitting
on the bright areas of the noise get dragged the hardest, each patch in its own direction, while
dark areas sit nearly still, so the frame bulges and ripples like glass melting or hot air rising.
The noise itself flows and morphs smoothly over time: blobs drift, grow and dissolve without a
single flicker. From a subtle heat shimmer over lava to full reality-melting in dream and poison
sequences.

### Parameters

| Param | Default | Description |
|---|---|---|
| `scale` | 8 | Blob size: how many noise cells fit across the screen width; higher = finer, smaller ripples (1..64) |
| `amplitude` | 0.03 (fades to 0) | Max pixel offset inside the brightest noise areas, in screen fractions (0..0.25); 0 = untouched frame |
| `contrast` | 2 | Shapes the noise field: above 1 gathers the warp into distinct patches with calm gaps between; 1 = soft, even ripples everywhere; below 1 flattens it into a uniform wobble (0.1..8) |
| `coherence` | 1 | Direction of the drag: 1 = pixels flow along the noise's own gradient — liquid-glass bulges, classic heat haze; 0 = every patch pulls in its own random direction — scattered, drunken chaos; blends between (0..1) |
| `speed` | 0.5 | How fast the field morphs, cycles per second (0..5; 0 = frozen pattern) |
| `drift_x` | 0 | Horizontal travel of the noise pattern across the screen, fractions per second (−1..1) |
| `drift_y` | 0 | Vertical travel (−1..1); ~0.15 with coherence 1 gives rising heat haze |

### Implementation notes

- Single fragment pass, single texture sample: the frame is sampled at a displaced UV.
- Noise: 2D value-noise field sampled at `aspect-corrected uv · scale + drift`, with time as the
  third coordinate (trilinear interpolation + smoothstep) — the field morphs without flicker.
- Magnitude comes from the noise value shaped by the `contrast` curve; direction is a blend by
  `coherence` between the noise gradient (2 extra taps) and a per-cell random direction.
- Sample position: `uv + dir · mag · amplitude`.
- At `amplitude == 0` the pass is identity — neutral state for the built-in fade, no `mix` needed.
- Uniform block registered in `VFXShaderPrograms` under location `vfxweaver:post/noise_warp`;
  built-in definition in `VFXDefinitionManager.registerBuiltIns` with the defaults above;
  `amplitude` animated `0.03 → 0` (fade-out), the rest constant.
- Performance note: the noise taps (3–4 samples of a cheap value-noise, not a texture) are the
  only added cost — keep the value-noise helper inline and avoid octaves unless profiling asks.

### Example

```
/vfx play vfxweaver:noise_warp {[amplitude:0.06],[scale:4],[speed:1.2],[contrast:3]}
```

---

## Registration checklist (both effects)

- [ ] `VFXEffectType`: add enum constants `SLICE_SHIFT`, `NOISE_WARP` (+ datapack type names `slice_shift`, `noise_warp`).
- [ ] `VFXShaderPrograms`: register fragment/vertex shader ids (`vfxweaver:post/slice_shift`, `vfxweaver:post/noise_warp`) + uniform blocks + program infos.
- [ ] Shader files: `assets/vfxweaver/shaders/post/slice_shift.{vsh,fsh}` and `.../noise_warp.{vsh,fsh}` (or post/ subdir per existing layout).
- [ ] `VFXDefinitionManager.registerBuiltIns`: built-in definitions with the defaults above; `shift`/`amplitude` animated to 0.
- [ ] `VFXPostProcessingManager`: extend the pass-chain switch for the new effect types.
- [ ] Built-in durations: 40 ticks, `EASE_IN_OUT_CUBIC` (screen-effect convention).
- [ ] Docs: add both effects to GUIDE §2.1 (param tables + examples above), changelog entry.
- [ ] Maven/Modrinth notes: mention in the release notes.

## Out of scope

- The other effects pitched in the same brainstorm (solarize, dither, double_vision, eyelids,
  iris_wipe, digital_glitch, vhs, shockwave, edge_detect, god_rays, frame_freeze, afterimage,
  light_beam, pulse_ring, scan_sweep, guide_line, entity_dissolve/rim/xray/glitch/holo,
  hud_fade, camera_roll, sky_tint, fog_override) — separate specs per effect group if approved.

## Open questions (decide at implementation)

1. `noise_warp` noise primitive: hand-rolled value-noise vs hash-based — pick at implementation;
   the spec pins only the observable behaviour (smooth morph, no flicker).
2. `slice_shift` sampling at `shift == 0`: render the pass or skip entirely (skip = cheaper;
   identity output either way).
3. Whether `mirror` should be continuous (0..1 blend between wrap and mirror) or thresholded —
   spec says blend, the formula above already supports it.

## Dropped by the owner

- `sky_tint`, `fog_override` - not wanted.
- `entity_glitch` - superseded by `vertex_displace` below (same vibe, works on entities AND blocks, and the motion is developer-controlled via the seed parameter).
- Not selected by the owner: `dither`, `edge_detect`, `entity_rim`, `entity_xray` (solarize was later approved).

---

## Effect 3: `entity_displace` (Entity)

### Description

All vertices of the target's model are randomly displaced, making the model look unstable and
glitchy. Targets entities by UUID. The random source is a `seed` parameter: because any
parameter can be animated, bound or driven by an expression, the developer fully controls the
motion:

- `seed` animated smoothly (`expr: "t"`) -> vertices morph fluidly.
- `seed` animated in steps (`expr: "floor(t * 8)"`, or a step keyframe) -> vertices snap
  abruptly to new positions 8 times a second.
- Constant `seed` -> a frozen distortion field.

The displaced model is drawn as a copy on top of the normal render (the vanilla body/block stays
underneath) - visually it reads as the model jittering out of place.

### Parameters

| Param | Default | Description |
|---|---|---|
| `amplitude` | 0.1 (fades to 0) | Max vertex displacement, in blocks (0..2); the built-in fade pulls the glitch back to the intact model |
| `scale` | 4 | Displacement field detail: higher = neighbouring vertices diverge more, lower = the whole model sways together (0.5..32) |
| `seed` | 0 | Random phase of the displacement field. Animate/bind it (expr, keyframes) to drive the glitch motion - stepped for snaps, smooth for morphing |

### Implementation notes

- Displacement happens in a dedicated **vertex shader** `vfxweaver/core/displace`:
  `offset = valueNoise(vertexLocalPos * scale, seed) * amplitude`, applied per vertex.
- Displacement happens in the vertex shader of a dedicated pipeline: the entity flavour shares
  the same math, only the vertex format differs (ENTITY vs POSITION_COLOR).
- Fade-out: `amplitude` animated to 0 restores the intact model - the built-in convention.
- The seed is a float, so `expr`/bindings/keyframes work with it like with any parameter.

### Examples

```
/vfx playentity vfxweaver:entity_displace @e[type=zombie,limit=1] {[amplitude:0.2],[scale:6],[seed:0]}
```

---

## Effect 4: `block_displace` (World)

### Description

Same displacement as the entity flavour, but applied to the baked model of blocks targeted by
positions/region: the block's quads are re-emitted into a custom POSITION_COLOR pipeline whose
vertex shader displaces them. Reads as the block(s) glitching apart and reassembling.

### Parameters

Same table as the entity flavour (`amplitude` 0.1, `scale` 4, `seed` 0) - identical semantics.

### Implementation notes

- World flavour: the block's baked model quads are re-emitted into a custom POSITION_COLOR
  pipeline whose vertex shader applies the displacement. Pipelines: ALWAYS / LEQUAL variants
  like the other block overlays (through_blocks applies here too).
- Shares the displacement vertex shader and noise helper with the entity flavour.
- Built-in definition: `vfxweaver:block_displace`, duration 40, amplitude animated to 0.

### Examples

```json
{ "type": "block_displace", "region": [10, 70, 10, 12, 70, 12],
  "params": { "amplitude": 0.25, "seed": { "expr": "floor(t * 8) * 0.1" } } }
```

### Examples

```
/vfx playentity vfxweaver:entity_displace @e[type=zombie,limit=1] {[amplitude:0.2],[scale:6],[seed:0]}
```
```json
{ "type": "vertex_displace", "positions": [[8, 70, 8]],
  "params": {
    "amplitude": 0.3,
    "scale": 8,
    "seed": { "expr": "floor(t * 8) * 0.1" }
  } }
```
## Effect 5: `solarize` (Screen)

Bright pixels invert, dark stay untouched - a "reality wrong" beat.

| Param | Default | Description |
|---|---|---|
| `threshold` | 0.5 | Luma above which colours invert (0..1) |
| `softness` | 0 | Rolloff width around the threshold: 0 = hard cut, 1 = full-range blend (0..1) |
| `intensity` | 1 (fades to 0) | Blend between original and solarized (0..1) |

One-pass fragment shader: `mix(color, inverted, smoothstep(band))`.

## Effect 6: `double_vision` (Screen)

Ghost copies of the frame offset left/right and layered over the frame - drunk/poison/concussion. Ghosts drift slowly over time.

| Param | Default | Description |
|---|---|---|
| `offset` | 0.04 | Ghost distance from centre, screen-width fractions (0..0.5) |
| `ghost_opacity` | 0.5 | Ghost copy opacity (0..1) |
| `drift` | 0 | Slow sinusoidal drift, screen fractions per second (0..0.2) |
| `intensity` | 1 (fades to 0) | Overall strength (0..1) |

3 samples (centre ± offset, drift by sin(t)), weighted average.

## Effect 7: `eyelids` (Screen)

Two soft curved dark lids slide in from top/bottom - organic blink or slow blackout. Animate `openness` 1 -> 0 to close the eyes.

| Param | Default | Description |
|---|---|---|
| `openness` | 0.5 (fades to 0) | 1 = wide open, 0 = fully closed (0..1; keyframe for blink curves) |
| `softness` | 0.15 | Feathered lid edge width, screen-height fractions (0..1) |
| `curve` | 0.35 | Lid bulge toward the centre: 0 = straight bars, 1 = heavy arc (0..1) |

Two parabolic dark shapes over the frame, feathered by smoothstep.

## Effect 8: `iris_wipe` (Screen)

Old-film iris transition: everything outside a circle goes black, inside stays clear. Animate `radius` 1.4 -> 0 to iris-out; bind the centre to a walking entity.

| Param | Default | Description |
|---|---|---|
| `radius` | 0.4 (fades to 1.4) | Circle radius in screen-height fractions: 1.4 = fully open, 0 = closed (0..1.5) |
| `softness` | 0.05 | Edge feather, screen-height fractions (0..0.5) |
| `center_x` | 0.5 | Circle centre X in UV (bindable to `screen_x`) |
| `center_y` | 0.5 | Circle centre Y in UV (bindable to `screen_y`) |
| `zoom` | 0 | Magnification inside the circle: 0 = none, 1 = strong push-in (0..1) |

Distance mask + smoothstep feather; inside the mask the UV lerps toward the centre for the push-in.

## Effect 9: `digital_glitch` (Screen)

The frame tears into horizontal bands that snap sideways with RGB-split spikes and bright noise rows - raw data corruption in bursts.

| Param | Default | Description |
|---|---|---|
| `block` | 0.06 | Band height, screen-height fractions (0.01..0.5) |
| `displacement` | 0.08 | Max sideways band shift, screen-width fractions (0..0.5) |
| `rate` | 6 | Average bursts per second; 0 = constant tearing (0..30) |
| `chroma` | 0.5 | RGB-split strength inside glitched bands (0..1) |
| `seed` | 0 | Random pattern offset - change for a different tear layout |
| `intensity` | 1 (fades to 0) | Overall strength (0..1) |

Per-band sideways shift by hash(floor(y/block), floor(t*rate), seed) + per-channel UV shifts in glitched bands.

## Effect 10: `vhs` (Screen)

A worn VHS tape: the frame wobbles, a noisy tracking band crawls down the screen, colours smear right, contrast washes out - found-footage / security-cam playback.

| Param | Default | Description |
|---|---|---|
| `tracking` | 0.35 | Tracking band's horizontal jumps (0..1) |
| `band_height` | 0.08 | Noise band height, screen-height fractions (0.01..0.3) |
| `band_speed` | 0.15 | Band travel speed, screen-heights per second (0..1) |
| `bleed` | 0.02 | Chroma smear to the right, screen-width fractions (0..0.1) |
| `wobble` | 0.004 | Fine constant horizontal jitter of the whole frame (0..0.05) |
| `intensity` | 1 (fades to 0) | Master strength (0..1) |

Per-row UV shifts (band + wobble), per-channel bleed shift, raised blacks + soft contrast loss.

## Effect 11: `shockwave` (Screen)

A single refraction ring ripples outward across the screen from a point: inside the ring the image bends like thick glass and snaps back behind it. Impact beat for explosions, boss slams, psychic hits.

| Param | Default | Description |
|---|---|---|
| `center_x` | 0.5 | Wave origin X in UV (0..1) |
| `center_y` | 0.5 | Wave origin Y in UV (0..1) |
| `radius` | 0.4 | Current ring radius, screen-height fractions (0..1.5; animate 0 -> 1.5) |
| `width` | 0.15 | Ring thickness, screen-height fractions (0.02..0.5) |
| `amplitude` | 0.12 (fades to 0) | UV displacement at the ring crest (0..0.3) |
| `sharpness` | 1.5 | Ring profile: 1 = smooth sine ripple, 4 = hard glassy ring (0.5..4) |

`uv += normalize(uv - center) * amplitude * profile((dist - radius) / width)` - one pass.

## Effect 12: `afterimage` (Screen, needs feedback buffer)

Every frame blends over a slowly decaying history buffer: movement leaves smearing trails that linger and dissolve like a long-exposure photo. Unlike motion_blur, trails persist for a fixed decay time regardless of camera speed. Dream sequences, poison, haunted flashbacks.

| Param | Default | Description |
|---|---|---|
| `decay` | 0.92 | Fraction of the previous frame surviving each tick (0..0.98; higher = longer trails) |
| `blend` | 0.6 | How strongly history mixes into the live image (0..1) |
| `drift` | 0 | Per-frame zoom applied to the history: positive = trails stretch outward, negative = shrink (-0.02..0.02) |
| `desat` | 0.35 | Saturation loss in the echo layer (0..1) |
| `intensity` | 1 (fades to 0) | Strength of the echo layer over the live frame (0..1) |

Feedback pass: `hist = mix(prevZoomed(drift), current, blend)`; output = `mix(current, desaturate(hist), intensity)`. Requires a persistent history target - see the feedback-buffer infra task.

## Effect 13: `stop_motion` (Screen, needs feedback buffer)

Stop-motion / papercraft: the picture updates only a few times per second while everything (input, physics) keeps moving - the world looks like cheap animation. The owner-requested replacement for `frame_freeze`.

| Param | Default | Description |
|---|---|---|
| `fps` | 12 (fades to 0) | Target update rate of the held picture, updates per second (1..30; 0 = back to full speed) |

Feedback pass: sample the history buffer at `t_held = floor(t * fps) / fps` - the picture repeats the last held frame until the next step. Same infra task as afterimage.

## Effect 14: `god_rays` (Entity/World - Ender Dragon death beams)

The owner-reworked version: not a screen filter, but **beams of light rising out of the target itself**, like the Ender Dragon death animation - the target ascends and purple beams pour out of its body. Anchored to the entity (UUID) or a pos anchor; beams rise from the body and fade upward.

| Param | Default | Description |
|---|---|---|
| `count` | 6 | Number of beams rising from the body (1..16) |
| `height` | 12 | Beam length upward from the body, in blocks (1..64) |
| `spread` | 0.6 | Where on the body beams spawn: spawn radius around the body centre, in blocks (0..4) |
| `speed` | 2 | Beam rise speed, blocks per second (0.5..8) |
| `sway` | 0.5 | Sideways sway of each beam, in blocks (0..4) |
| `red` | 0.6 | Red channel of the beam colour (0..1) |
| `green` | 0.2 | Green channel (0..1) |
| `blue` | 0.9 | Blue channel (0..1) |
| `intensity` | 1 (fades to 0) | Beam opacity (0..1) |

Implementation: additive additive-blended vertical quads rising from the entity's body (billboard toward the camera), spawn points spread over the body, opacity fades along the rise - the existing entity/world quad overlay pattern.

## Effect 15: `light_beam` (World)

A vertical column of soft light descends onto the bound position - a translucent beacon with feathered edges and optional slow sway. "Divine intervention / teleport arrival" cue.

| Param | Default | Description |
|---|---|---|
| `radius` | 1.5 | Beam radius in blocks (0.1..16) |
| `height` | 48 | Column height upward from the anchor, blocks (1..256) |
| `softness` | 0.6 | Outer falloff width as a fraction of `radius` (0..1) |
| `top_fade` | 0.4 | Alpha at the top of the column relative to the base (0..1) |
| `sway` | 0 | Sideways sway amplitude, blocks (0..8) |
| `sway_speed` | 0.4 | Sway cycles per second (0..3) |
| `red/green/blue` | 1 / 0.95 / 0.75 | Beam colour |
| `intensity` | 1 (fades to 0) | Beam opacity (0..1) |

2-4 vertical quads with cylindrical billboarding (position_color, additive); vertex-colour alpha gradients; sway = vertex offset by sin(t).

## Effect 16: `pulse_ring` (World)

A flat glowing ring expands from the bound position along the ground - a visible shockwave or AoE telegraph. Animate `radius` for the travelling wave; stack several with staggered delays for a countdown.

| Param | Default | Description |
|---|---|---|
| `radius` | 6 | Current ring radius, blocks (0..64; animate 0 -> max) |
| `thickness` | 0.5 | Ring band width in blocks (0.05..4) |
| `tilt` | 0 | Ring plane pitch: 0 = flat on the ground, 90 = vertical wall (-90..90) |
| `red/green/blue` | 1 / 0.35 / 0.1 | Ring colour |
| `intensity` | 1 (fades to 0) | Ring opacity (0..1) |

A flat annulus built from segments (position_color, additive), soft alpha toward band edges; radius/tilt recomputed per frame.

## Effect 17: `scan_sweep` (World)

A thin glowing sheet sweeps through the bound region - bottom to top, or along X/Z - a sci-fi security scan or a corruption front advancing through a base. A fading glow trail marks what has already been "infected".

| Param | Default | Description |
|---|---|---|
| `range` | 16 | Sweep length from the anchor along the axis, blocks (1..128) |
| `axis` | 1 | Sweep axis: 0 = X, 1 = Y (bottom->top), 2 = Z (0..2) |
| `progress` | 0.5 | Sheet position along the range (0..1; animate for the pass) |
| `width` | 0.4 | Glowing sheet thickness, blocks (0.05..4) |
| `trail` | 0.25 | Fading glow behind the sheet, fraction of `range` (0..1) |
| `red/green/blue` | 0.3 / 1 / 0.9 | Sheet colour |
| `intensity` | 1 (fades to 0) | Sheet and trail opacity (0..1) |

A two-sided quad sheet across the direction + 3-5 trail quads with decreasing alpha (position_color, additive); position from progress, orientation from axis.

## Effect 18: `guide_line` (World)

A glowing dashed line between two anchors - from a pressure plate to its door, or an NPC to the objective. Dashes crawl along the line to show direction; the path can arc upward like a quest marker. The clean "follow me" hint without text or particles.

| Param | Default | Description |
|---|---|---|
| `width` | 0.15 | Line thickness, blocks (0.02..1) |
| `dash_length` | 0.6 | Dash length, blocks (0.1..8) |
| `gap` | 0.6 | Gap between dashes, blocks (0..8) |
| `speed` | 2 | Dash crawl speed along the line, blocks per second (-10..10; negative = reverse) |
| `arc` | 1.5 | Bows the path up (+) or droops it (-) at the midpoint, blocks (-8..8) |
| `red/green/blue` | 0.25 / 1 / 0.45 | Line colour |
| `intensity` | 1 (fades to 0) | Line opacity (0..1) |

A chain of quad segments along a parabola between the two anchors (position_color, additive); dash phase = t * speed.

## Effect 19: `entity_holo` (Entity)

The target becomes a flickering hologram: horizontal scan bands travel across the body, the surface turns translucent and softly emissive, and the figure occasionally drops out for a frame. AR advisors, security replays, ghost-memory characters.

| Param | Default | Description |
|---|---|---|
| `band_scale` | 12 | Scan bands along the model height (1..64) |
| `band_speed` | 1 | Band travel speed, model-heights per second (-5..5; negative = downward) |
| `transparency` | 0.45 | Base opacity of the hologram body (0..1) |
| `flicker` | 0.25 | Strength of random per-frame alpha dropouts (0..1) |
| `red/green/blue` | 0.3 / 0.9 / 1 | Hologram tint |
| `intensity` | 1 (fades to 0) | Blend back to the normal solid model (0..1) |

Second pass: banded alpha fract(localY * band_scale - t * band_speed) + flicker by hash(floor(t * 30)); the base model dims proportionally to transparency.

## Effect 20: `entity_dissolve` (Entity)

The target burns away into nothing: a noisy threshold eats the mesh from the chosen direction, leaving a glowing ember band at the dissolving frontier. Animate `progress` 0 -> 1 to banish/teleport, and back down to resurrect.

| Param | Default | Description |
|---|---|---|
| `progress` | 0.5 | How much of the model is dissolved: 0 = intact, 1 = fully gone (0..1) |
| `direction` | 1 | 0 = dissolves upward from the feet, 1 = downward from the head; fractional values mix the gradients (0..1) |
| `edge_width` | 0.15 | Glowing frontier band width, fraction of model height (0..0.5) |
| `noise_scale` | 4 | Graininess of the dissolve mask; higher = finer (0.5..16) |
| `red/green/blue` | 1 / 0.55 / 0.15 | Ember colour |
| `intensity` | 1 (fades to 0) | Mask strength - the dissolve retreats as it fades (0..1) |

A custom ENTITY pass: threshold from the world-Y gradient x a noise texture (shipped in the jar), discard below the threshold, the edge band is an emissive tint; the base is muted by the same mask.

## Effect 21: `hud_fade` (Misc)

Dissolves the HUD - hotbar, hearts, hunger, XP bar, crosshair - to transparent while the player keeps full control. The standard "clean screen" switch for cutscenes.

| Param | Default | Description |
|---|---|---|
| `opacity` | 0 (fades to 1) | HUD opacity: 0 = fully hidden, 1 = normal (0..1) |
| `chat` | 1 | 1 = chat and action bar fade too, 0 = chat stays readable (0..1) |

A global alpha multiplier on the HUD layers (mixin), lerped 1 -> opacity; no shaders.

## Effect 22: `camera_roll` (Misc)

Tilts the camera around its viewing axis by a fixed angle - the dutch angle: the horizon leans sideways while everything else stays still. Instant unease or intoxication; add slow wobble for seasickness.

| Param | Default | Description |
|---|---|---|
| `angle` | 15 (fades to 0) | Roll in degrees; positive = clockwise lean (-180..180) |
| `wobble` | 0 | Slow sinusoidal sway of +/- this many degrees (0..45) |
| `wobble_speed` | 0.2 | Sway frequency in Hz (0..2) |

A rotation around the camera forward axis, applied where camera_shake is applied (right after it).

## Effect 23: `god_rays` note

`god_rays` is listed above (Effect 14) in its owner-reworked Ender-Dragon-death form; the original screen-space light-filter version is dropped.

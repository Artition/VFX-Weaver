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
- Under review (not decided): `solarize`, `dither`.

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
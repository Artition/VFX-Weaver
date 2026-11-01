# `text_overlay` — text as a first-class effect

Design doc. Status: proposed, awaiting implementation.

## Problem

VFXWeaver has 63 effect types and not one of them draws text. A mod sold as *cinematic* cannot put a
title, a caption or a credit on screen, and cannot put a word into the world. `screen_image` can place
a picture; letters are missing.

The reference the user supplied (`MusicSubtitles`) solves a different problem — syncing lyrics to
audio — but it shows the level of control expected from a text system: per-character colour, per
character animation, karaoke progress, per-character fonts, line wrapping, and 2D plus world-space
rendering. What we take from it is the level of control, not the lyrics engine: providers, acoustic
alignment and consensus merging are out of scope, because here the text comes from a datapack
definition and the clock comes from the effect timeline.

## Non-goals

- No lyrics/music integration, no external text providers, no audio alignment.
- No in-game text editor: text is authored in the definition JSON.
- **No runtime text.** `params` are `Map<String, Float>`, and the trigger payload carries the effect
  id plus numeric params only, so the string cannot be injected at play time — it comes from the
  definition. This is deliberate for v1: titles and captions are authored content. Dynamic text
  (player name, scoreboard value) is a separate design around `text.source`, not a string param.
- No per-character arrays of colour/font/animation (v1 uses per-line colour with a gradient, one
  animation for the line, and per-character timing only through `stagger`).
- No custom TTF loading from a datapack. `default` and `monospace` only.
- 26.3 port is explicitly deferred; this feature is implemented against 26.2.

## Behaviour

A character moves through three phases.

1. **Reveal.** `reveal` (0..1) is the fraction of characters currently shown. It is an ordinary
   animatable parameter, so a keyframe from 0 to 1 over 40 ticks is a typewriter, and a binding or an
   `expr` ramps it with anything else — that is the karaoke case, without a dedicated karaoke system.
2. **Exit.** `exit_at` says when the first character starts leaving; `exit_stagger` spaces the
   characters out. Two ways to leave:
   - `exit: "fade"` — opacity falls to zero in place;
   - `exit: "fall"` — the character detaches, takes `exit_speed` plus per-character jitter, is
     accelerated by `gravity`, and tumbles with `spin_x/y/z` scaled by `tumble`.
3. **Gone.** After `fade_out` seconds of the exit having finished (or after landing, when collision is
   on), the character is removed.

`collision` decides whether a falling character stops on a surface:

- **3D**: the first block below it. Off means it falls through geometry.
- **2D**: the edges of the frame. Off means it leaves the picture and fades there.

Same parameter names, same meaning; only the world the character falls in differs.

## Data model

### Definition field

A definition may carry a `text` object, alongside the existing non-numeric fields (`particle`,
`shape`, `block`, `item`, `entity_selector`) and the existing sub-specs (`screen_image`, `window`).
Parsed into a new `VFXTextSpec`, in the same shape as `VFXScreenImageSpec`:

```json
{
  "type": "text_overlay",
  "duration": 160,
  "persistent": true,
  "text": {
    "content": "Первый кадр\nсобран датапаком",
    "mode": "world",
    "font": "default",
    "align": "center",
    "anchor": "center"
  },
  "params": { "reveal": { "start": 0.0, "end": 1.0 }, "exit_at": 6.0 }
}
```

`content` is the authored string; `\n` is a hard break. `mode`, `font`, `align` and `anchor` are
structural and live in this object, not in `params` — they are not animatable. Everything in `params`
can be animated and bound like any other parameter.

### Parameters

| parameter | type | default | meaning |
|---|---|---|---|
| `x`, `y` | float | 0.5, 0.5 | UV position (flat) |
| `pos_x`, `pos_y`, `pos_z` | float | 0 | world anchor (`world` mode, uses the standard `pos_*` override) |
| `scale` | float | 1 | pixels per glyph in `flat`, blocks per glyph in `world` |
| `line_height` | float | 1.2 | line spacing as a multiple of `scale` |
| `wrap_width` | float | 0 | wrap when the line exceeds this width; 0 = only `\n` breaks |
| `alpha` | float | 1 | overall opacity multiplier |
| `color_r/g/b` | float | 1/1/1 | base colour |
| `gradient` | float | 0 | 0 = flat colour; 1 = ramp across the line to `gradient_r/g/b` |
| `gradient_r/g/b` | float | 1/0.6/0.3 | second colour of the ramp |
| `shadow` | float | 1 | drop shadow under glyphs |
| `outline` | float | 0 | outline width in pixels (`flat`) or a fraction of `scale` (`world`) |
| `outline_r/g/b` | float | 0/0/0 | outline colour |
| `reveal` | float | 1 | 0..1, fraction of characters shown |
| `per_char_anim` | int | 0 | animation while appearing: `0` none, `1` rise, `2` wave, `3` tilt |
| `stagger` | float | 0 | delay between characters for `per_char_anim`, seconds |
| `exit` | int | 0 | `0` fade, `1` fall |
| `exit_at` | float | -1 | seconds from the start when the first character leaves; -1 = the text never leaves on its own and stays until the effect is stopped |
| `exit_stagger` | float | 0 | seconds between consecutive characters leaving |
| `exit_speed` | float | 0 | initial speed along the exit direction, blocks/s (`world`) or screen heights/s (`flat`) |
| `gravity` | float | 18 | downward acceleration, blocks/s² (`world`) or screen heights/s² (`flat`) |
| `spin_x`, `spin_y`, `spin_z` | float | 0 | angular velocity while falling, degrees/s |
| `tumble` | float | 0 | 0..1, per-character multiplier on `spin_*` and initial orientation |
| `collision` | float | 1 | 1 = falling characters stop on a surface, 0 = they pass through |
| `bounce` | float | 0.15 | restitution on impact, 0..1 |
| `friction` | float | 0.4 | tangential damping on impact, 0..1 |
| `fade_out` | float | 0.5 | seconds to fade after landing / after leaving the frame |
| `rot_x`, `rot_y`, `rot_z` | float | 0 | baseline orientation of the whole block, degrees |
| `angle` | float | 0 | `flat` mode: rotation of the block around its anchor, degrees |
| `font` | string | `default` | `default` or `monospace` |

Numeric params follow the existing rules: constants, keyframes, animations, bindings, expressions,
multipliers, no arbitrary clamps beyond the ones that avoid degenerate maths (`scale > 0`,
`bounce`/`friction`/`tumble`/`reveal` clamped to 0..1). Enum-like choices are float codes rather than
strings, matching `spectral.mode` and `dent.line_mode`: every parameter stays in the single animatable
`float` map, so nothing needs a second parser path.

## Determinism

Per-character randomness — jitter of `exit_speed`, the spin multiplier, the initial orientation — is
derived from a hash of the effect instance seed and the character index. Nothing is stored between
frames beyond the floor height, which is a pure function of the world. Two consequences the design
relies on: a replay or a screenshot at time T always shows the same frame, and the physics can be
evaluated from the clock rather than integrated, which is what the existing display-entity engine
already does for its own particles.

## Implementation

### 3D (`mode: "world"`)

Vanilla `Display.TextDisplay` entities, one per character, driven by the machinery that already exists
for block/item display particles.

`VFXBlockParticleEngine` already owns: a per-particle `Display` entity added to `ClientLevel` with
`ClientLevel#addEntity`, client-local entity ids, gravity, air drag, collision and bounce, angular
velocity, transform interpolation between ticks, a hard cap on live entities, and guaranteed removal
when the effect stops or the level changes. The work is to add a *text* variant of its spec and
particle: the entity is a `TextDisplay` whose `text` is a single glyph, with `background` transparent,
`shadow`, `see_through` and opacity from the parameters, and whose transform is driven by the same
interpolation path. Characters before `exit_at` are kinematic (they sit in the laid-out line);
characters after it are handed to the simulation with their initial velocity and spin.

Layout is ours, not vanilla's: `line_width` on the display entity aligns, it does not give per-glyph
positions, and per-character physics needs positions anyway. So the block is measured once (glyph
advances from the font), broken into lines by `wrap_width`, and each character gets a rest position.

Entity budget: text characters count against the engine's existing cap; a definition whose text
exceeds the remaining budget keeps the leading characters and drops the tail (with one warning per
definition, the `VFXLog.warnOnce` pattern already used by `screen_image`).

### 2D (`mode: "flat"`)

`GuiGraphics` after the world render, so glyphs use the real font, shadow and outline. This is the one
architectural addition: the mod currently has no 2D layer over the frame — every screen effect is a
post-processing shader. The new hook draws the text effects in play order, above the shaders.

Falling in 2D is analytic, not simulated: position is a closed-form function of the time since the
character detached, including reflections off the frame rectangle when `collision` is on (a bounded
loop of at most a few reflections). Gravity is expressed in screen heights per second so the fall
looks the same at any resolution or GUI scale.

### Wiring checklist

| file | change |
|---|---|
| `VFXEffectType` | new `TEXT_OVERLAY("text_overlay")` |
| `VFXDefinition` | new optional `text` field, parsed into `VFXTextSpec` |
| `VFXTextSpec` (new) | content, mode, font, align, anchor |
| `VFXEffectSchema` | register the type and its parameters (non-post) |
| param sources | expose the new params to the editor, grouped |
| `VFXWorldOverlayRenderer` | dispatch `text_overlay` with `mode: world` |
| new `VFXTextOverlayRenderer` (client) | 2D layer and the shared layout/measurement |
| `VFXBlockParticleEngine` | text variant of spec/particle/spawn |
| docs | new page under `docs/guide/effects/`, plus a row in the index |
| demo | `vfx_demos:show_text_overlay` in `showcase/` |

## Verification

1. `python scripts/check-showcase-pack-parse.ps1` and the schema checks stay green (a new effect type
   means new schema coverage — the checks must be extended, not bypassed).
2. Build both 26.2 jars (fabric, neoforge), deploy to the 26.2 instance, run the demo definition.
3. In game: 2D text at several GUI scales; 3D text with `billboard` on and off; `exit: fall` with
   collision on (letters settle on the island) and off (they fall through); `reveal` as a keyframe.
4. Determinism: two screenshots of the same effect at the same tick match.
5. Cleanup: `/vfx stop` removes every display entity, no leak after a world change.
6. Composition check: RIFT renders a title card on top of the existing frame (regression check that
   the 2D layer does not break post-processing or the letterbox).

## Risks

| risk | mitigation |
|---|---|
| One entity per character is heavy for long credits | characters count against the existing cap, tail is dropped with one warning; a line-level fallback can be added later without changing the parameters |
| Text on the HUD layer will not be affected by `bloom`/`vhs` | accepted: titles are meant to stay crisp; a `layer: post` variant is a later addition |
| `TextDisplay` API differences across 1.21.11 / 26.x | the same version conditionals the codebase already uses (`//? if`) |
| 2D fall collisions with an odd aspect ratio | gravity and positions are expressed in screen heights, not pixels; verified at two GUI scales |

## Milestones

1. **M1 — static 3D.** Type, spec, schema, one `TextDisplay` per character laid out in the world,
   `reveal` driving opacity. Verified in game.
2. **M2 — 3D exit.** `exit: fall` through the display engine, collision toggle, spin/tumble, fade-out.
3. **M3 — 2D.** Layer hook, `GuiGraphics` rendering, analytic fall and frame collision, `angle`.
4. **M4 — finish.** Docs page, demo definition, showcase pack rebuild, RIFT title card, parse and
   schema checks extended.

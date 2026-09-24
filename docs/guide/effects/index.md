# Effects

An effect is a JSON definition at `data/<namespace>/vfx/<name>.json`; its id is
`<namespace>:<name>` and `/reload` loads it. Play one with `/vfx play <id>` (or
`/vfx playentity` for entity effects). The full definition schema is on the
[Datapack format](../datapack/format.md) page; every effect below has its own page with its
complete field list and a copy-pasteable example.

## How to read an effect page

- Every **param** is a float. Most "intensity-like" params are `0..1`, where `0` = off.
- Many built-ins animate their main param from the listed value **down to 0** over the duration, so
  the effect fades out on its own. Override the param (command param-map / API) and it becomes a
  constant unless you animate it yourself (`start`/`end`, `keyframes`, `expr`).
- A live keyframe with a **negative time** starts its segment at the current moment, pinning the
  value the param has right now - see [Animating a param](../datapack/params.md#ways-to-set-a-param).
- `screen_layer` (screen effects only): `0` = under the first-person hand and GUI, `1` = above the
  hand below the GUI (default), `2` = above everything.

## Shared fields

Every effect accepts fields that are **not** repeated in its own `Fields` table. A reader must check
these too:

**Definition fields (any effect).** `type` (required), `duration`, `easing`, `loop`, `persistent`,
`fade_ticks`, `params`, `sound`, `sound_pos`, `volume`, `pitch` — the complete list, types and
defaults is on [Datapack format](../datapack/format.md#definition-fields). These control how long the
effect runs, whether it loops or persists until stopped, how it fades, and its optional sound.

**Screen effects** additionally accept `screen_layer` (`0`/`1`/`2`, above). See
[Datapack format](../datapack/format.md).

**World overlays** (`block_tint`, `block_outline`, `light_beam`, `pulse_ring`, `guide_line`,
`particles`, `block_chain`) additionally accept:

- `positions` — an array of `[x, y, z]` entries or entity anchors
  (`{"entity": ..., "point": ..., "offset": ..., "dir": ..., "distance": ...}`), documented on
  [Datapack format](../datapack/format.md#positions-and-entity-anchors).
- `region` — a box `[x0,y0,z0,x1,y1,z1]` that expands to every block in it, an alternative to
  listing `positions`.
- `pos_x` / `pos_y` / `pos_z` — params used as a single world position when neither `positions` nor
  `region` is present; they can be constant, animated or bound.

**Entity effects** (`entity_tint`, `entity_outline`, `entity_displace`) are targeted by
`/vfx playentity <effect> <selector>` (up to 16 UUIDs) or by the `entity_selector` definition field,
which lets a plain `/vfx play` find its own targets. See
[Datapack format](../datapack/format.md#definition-fields).

**Collections** additionally accept `effects` (the child list) — see
[collection](collection.md).

## Effect groups

### Screen post-processing

Fullscreen shader passes, one per effect. They accept `screen_layer`.

- [chromatic_aberration](screen/chromatic-aberration.md) - RGB channel fringing towards the edges
- [color_grade](screen/color-grade.md) - saturation, contrast, brightness and tint
- [distortion](screen/distortion.md) - barrel/pincushion warp
- [dent](screen/dent.md) - local lens warp at a point or along a segment
- [gradient_map](screen/gradient-map.md) - luminance to a two-colour gradient
- [posterize](screen/posterize.md) - colour quantization
- [blur](screen/blur.md) - two-pass Gaussian blur
- [pixelate](screen/pixelate.md) - pixelation
- [hue_isolation](screen/hue-isolation.md) - keep one hue, grayscale the rest
- [vignette](screen/vignette.md) - edge darkening
- [screen_flash](screen/screen-flash.md) - fullscreen colour overlay
- [motion_blur](screen/motion-blur.md) - blur from camera rotation
- [bloom](screen/bloom.md) - glow around bright areas
- [film_grain](screen/film-grain.md) - animated grain
- [scanlines](screen/scanlines.md) - CRT bands
- [depth_of_field](screen/depth-of-field.md) - sharp band with blur away from it
- [letterbox](screen/letterbox.md) - cinematic bars
- [invert](screen/invert.md) - invert colours
- [vortex](screen/vortex.md) - swirl around a point
- [speed_lines](screen/speed-lines.md) - radial speed lines
- [slice_shift](screen/slice-shift.md) - slide the two halves of the frame apart
- [noise_warp](screen/noise-warp.md) - fluid value-noise warp
- [solarize](screen/solarize.md) - bright pixels invert
- [double_vision](screen/double-vision.md) - two drifting ghosts
- [eyelids](screen/eyelids.md) - soft lids closing from top and bottom
- [iris_wipe](screen/iris-wipe.md) - circular iris transition
- [digital_glitch](screen/digital-glitch.md) - banded tearing with RGB split
- [vhs](screen/vhs.md) - worn VHS playback
- [shockwave](screen/shockwave.md) - expanding refraction ring
- [afterimage](screen/afterimage.md) - feedback echo trails
- [stop_motion](screen/stop-motion.md) - hold the picture at a low frame rate
- [surface_pattern](screen/surface-pattern.md) - a world-anchored figure projected onto terrain

### World overlays

World-space geometry drawn at `positions` (or entity anchors).

- [block_tint](world/block-tint.md) - translucent fill over a block model
- [block_outline](world/block-outline.md) - outline around a block model
- [light_beam](world/light-beam.md) - vertical shaft of soft light
- [pulse_ring](world/pulse-ring.md) - glowing ring around a point
- [guide_line](world/guide-line.md) - dashed line along a parabolic arc
- [particles](world/particles.md) - vanilla particles in animated shapes
- [block_chain](world/block-chain.md) - real block-model links between anchors

### Entity effects

Targets are set by `/vfx playentity <effect> <selector>`, the Java API, or the `entity_selector`
definition field. The client redraws the entity in a second pass over the original.

- [entity_tint](entity/entity-tint.md) - texture-aware colour fill
- [entity_outline](entity/entity-outline.md) - inverted-hull silhouette
- [entity_displace](entity/entity-displace.md) - per-vertex displacement of the model

### Camera and misc

Camera-space effects act on the camera transform; they do not accept `screen_layer`.

- [camera_shake](misc/camera-shake.md) - simplex-noise shake
- [camera_roll](misc/camera-roll.md) - fixed dutch angle with optional wobble
- [fov_modifier](misc/fov-modifier.md) - field-of-view change
- [fog_modifier](misc/fog-modifier.md) - vanilla fog distance and colour

### Collections

- [collection](collection.md) - play several child effects with per-child delays

## Built-in effects

Built-ins ship as regular datapack JSON inside the mod jar (`data/vfxweaver/vfx/*.json`); they load
and sync like custom definitions and can be overridden by a higher-priority pack. To tweak one, copy
its JSON out of the jar into your own datapack under a new id.

- **Post-processing:** `chromatic_aberration`, `color_grade`, `distortion`, `dent`, `gradient_map`,
  `posterize`, `blur`, `pixelate`, `hue_isolation`, `vignette`, `screen_flash`, `motion_blur`,
  `bloom`, `film_grain`, `scanlines`, `depth_of_field`, `letterbox`, `invert`, `vortex`,
  `speed_lines`, `slice_shift`, `noise_warp`, `solarize`, `double_vision`, `eyelids`, `iris_wipe`,
  `digital_glitch`, `vhs`, `shockwave`, `afterimage`, `stop_motion`, `surface_pattern`,
  `graph_demo`, `graph_logic_demo`, `dent_field_demo`, `tint_field_demo`.
- **World overlays:** `block_tint`, `block_outline`, `light_beam`, `pulse_ring`, `guide_line`,
  `particles`, `block_chain`.
- **Entity effects:** `entity_tint`, `entity_outline`, `entity_displace`.
- **Camera:** `camera_shake`, `camera_roll`, `fov_modifier`, `fog_modifier`.

Built-in ids need their namespace (`vfxweaver:<name>`); there is no `minecraft:` fallback.

## See also

- [Commands](../commands.md) - play, stop, set params and target entities.
- [Datapack format](../datapack/format.md) - files, definition fields, positions, validation.
- [Animating a param](../datapack/params.md) - keyframes, bindings, easings.
- [Expressions (`expr`)](../datapack/expr.md) - drive a param from a formula.
- [Value graphs](../datapack/graph.md), [Per-pixel fields](../datapack/fields.md),
  [Masks](../datapack/masks.md), [Custom particles](../datapack/particles.md).

# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/).

**This page is the authoritative record of released mod versions.** The newest entry is the current
mod version (`2.0.2`, read from `mod_version` in the build). Each `##` heading names a release; where
a release shipped with a guide revision the heading shows both numbers (`v1.2.0 / Guide v32`), and a
heading titled only `Guide vN` is a guide change that shipped without a mod version bump. The two
numbers are independent: the **mod version** is what you download, the **guide revision** is how many
times this documentation has been revised. The authoritative record of guide revisions is the
[Guide changelog](guide/changelog.md). Which jar to download for your Minecraft line and loader is in
the [download table](index.md#download). Add new entries at the top, in the same PR as the behaviour
change.

## Unreleased

### Added

- **`sky_pattern` — a datapack figure or texture drawn on the sky dome.** A new screen post effect
  and the sky sibling of `surface_pattern`: the same structural `pattern` block, shared shape
  library and `pattern.texture` addressing, but the pixel's view ray is projected onto the dome
  instead of a depth-reconstructed world surface (the projection is chosen by `sky_mode` — see the
  `sky_mode` entry below). The dome anchor is authored as
  `anchor_yaw`/`anchor_pitch` (degrees) and the image can be spun about world Y with `dome_rotation`;
  `repeat: [3, 3]` draws nine dots with no texture, and a `pattern.texture` with a `sheet` plus the
  animatable `frame` param draws an animated sky. The pass is gated on the depth sky test, so it
  never touches terrain, the hand or the GUI. Additive: a new effect type only, no existing effect
  changed, no datapack field renamed, `PROTOCOL_VERSION` unchanged, and no existing shader's UBO
  layout touched. The rendering is not verified in game by the author (the owner tests visually);
  like every post effect it composes over an Iris shaderpack's sky.

- **`light_beam` can end on a chosen point.** Three optional params, `end_at_x`/`end_at_y`/`end_at_z`,
  name a world point the **far end** of the beam lands on: the anchor is derived from it
  (`end − axis × height`), so the geometry finishes exactly there — with the default axis the beam
  hangs straight down from `height` above the point, which is how a beam comes out of the sky. They
  are ordinary animatable params, so the landing point can be keyframed, driven by an expression or
  bound, and a beam from A to B is `end_at = B` with the axis and length computed between the two
  points. Unset, the beam behaves exactly as before. No shader, pipeline, UBO or wire change.

- **`light_beam` can point in any direction.** Three new params, `dir_x`/`dir_y`/`dir_z` (defaults
  `0`/`1`/`0`), replace the fixed vertical axis, so a beam can be aimed at an angle — e.g. from a
  point on the sky. They are ordinary animatable params, so they can be keyframed, driven by an
  expression or bound, including to the camera look (`{"bind": "look_x"}` etc.). With the defaults the
  beam renders exactly as before. No shader, pipeline, UBO or wire change.

- **Sky masks.** Two new mask leaves address the sky instead of the world: `sky` (the whole visible
  sky, no parameters) and a 2D shape (`circle`/`ellipse`/`rect`/`polygon`) in the new
  `space: "dome"`, which places it on an equirectangular map of the sky with the centre authored as
  `[yaw, pitch]` degrees. A dome leaf is sky-occluded by construction — a hill or a wall occupying a
  dome direction is never tinted — and both fail closed (zero coverage) when no trustworthy depth is
  available. This is what lets any existing screen effect be restricted to the sky, e.g. a green or
  glitching sky. Additive: no datapack field was renamed, the wire format and `PROTOCOL_VERSION` are
  unchanged and the coverage UBO layout did not change. The far-depth test is verified in game on all
  three lines (26.2, 26.1.2, 1.21.11). The sun, the moon and the clouds are drawn without depth, so a
  `sky`/`dome` leaf covers them too (a green sky turns the sun green); the horizon fog is baked into
  the terrain, so it is not recoloured and a strong tint can leave a seam there.

- **`volume: "aura"` on a world GLSL-plugin mask leaf.** A custom mask leaf registered with
  `VFXAPI.registerMaskShapeGlsl` can now be a real volume instead of a surface-only shape: the pixel's
  view ray is sphere-traced through the plugin's own distance field and the effect fills the volume —
  air and sky included — wherever the scene does not occlude it, with the same silhouette, edge fade
  and occlusion maths as a built-in `sphere`/`box` aura. A composed custom leaf (no raw SDF to march)
  and a screen-space plugin leaf are per-file parse errors naming the reason rather than silent
  fallbacks. An optional `vec4 vfx_shape_custom_bounds()` in the plugin source (world centre and
  radius) acts as an analytic broad phase: a ray that misses it is rejected without evaluating the SDF,
  which makes the average cost proportional to the volume's screen area instead of the whole frame. A
  plugin that declares neither function compiles and behaves exactly as before; the wire format,
  `PROTOCOL_VERSION` and the coverage UBO layout are unchanged.

### Changed

- **`sky_pattern` gains a `sky_mode` projection and no longer funnels at the zenith.** The single
  global equirectangular chart had two topological defects for whole-sky content: `u` is undefined at
  the poles (the pattern winds into a funnel at the zenith) and `u` wraps at ±180° yaw (a visible
  seam / mirror axis). `sky_mode` replaces the addressing with an atlas of local charts plus a smooth
  partition of unity: **`patch`** (default) is one gnomonic decal at the anchor (no pole, no seam,
  clean discard past the decal horizon), **`fill`** covers the whole sphere with three orthographic
  charts blended by a sharpened partition of unity (no pole convergence, no seam anywhere; the
  charts' coverage and colour are blended, never their UVs), and **`dome`** is the legacy equirect
  path kept byte-for-byte (inherent pole funnel and north seam — not for new content). `sky_mode` is
  appended last to the Config UBO, so no existing shader's layout changes; no datapack field was
  renamed and `PROTOCOL_VERSION` is unchanged. The effect is not in any release yet, so the new
  default (`patch`) breaks nothing. A full skybox **cube** remains a separate future feature.

- **A custom mask leaf in `surface` mode no longer tints the sky.** The surface path classifies only
  what the depth buffer contains, so a sky pixel contributes no coverage. Built-in shapes already
  behaved this way (their bounded distance field puts a far-plane point outside the shape); the gate is
  now explicit for custom leaves, whose SDF may be intentionally unbounded. A plugin that relied on
  painting the sky from a `surface` leaf will no longer do so.

### Fixed

- **`sky_pattern` `patch` mode now ends in a clean circular edge instead of a straight cut and a
  smear at its rim.** The gnomonic decal divides by `dot(dir, anchor)`, so as the ray approached the
  tangent plane's horizon its cell coordinate (and therefore the tiling frequency) grew without
  bound and the pattern smeared/converged into a point; the caller's `facing` gate then cut it off
  along a hard straight line, which made a large patch read as a flat plane lying over the sky. The
  patch branch now bounds the decal to its **unit disc** (`tile_scale` = `tan(half the patch's
  angular size)`, so radius 1 is the patch edge): the coverage fades to zero over `softness` (with a
  0.05 cell-unit floor) and nothing is evaluated past radius 1, so the singular rim is never
  visible; the `facing` gate remains only as a hard backstop. The demos were reassigned to match
  what each projection is for: `show_sky_pattern_texture` (a whole-sky texture) and `sky_cracks`
  are `fill`, while `show_sky_pattern` (the ring) and `nine_red_pixels` are small bounded `patch`
  decals (`tile_scale` 0.4–0.55 and 0.3). The effect is not in any release yet; no datapack field
  was renamed and `PROTOCOL_VERSION` is unchanged.

## 2.0.2 — 2026-09-23

2.0.2 lets a GLSL mask plugin shape read live per-leaf float data, so its geometry can move every
tick without recompiling the shader variant. It also raises the network parameter cap to fit a large
mask's resolved map. No datapack format or wire format changed — `PROTOCOL_VERSION` stays 6 — and an
existing plugin that ignores the new helper compiles and behaves exactly as before.

### Added

- **GLSL mask plugin shapes can read live per-leaf float data.** A custom mask leaf now carries 32
  reserved dynamic floats, `mask.p<N>.d0 … d31`, authored as a `"data": [ … ]` array on the leaf or
  set every tick with the new `VFXAPI.maskData`/`VFXAPI.sendMaskData` (or `setParam`/`sendSetParam`
  on a single slot). A plugin reads its own leaf's values through the wrapper helper
  `vfx_mask_data(vfx_shape_data_base + j)`, so a set of moving primitives far larger than the eight
  animatable params can be driven **without recompiling the shader variant** (the variant is keyed
  only by the set of plugin ids). Backward compatible: an existing plugin that ignores the helper
  compiles and behaves exactly as before. Two limits remain: a mask's custom leaves may reference
  only **one distinct GLSL plugin id** (two different plugin ids in one mask fail to compile and fall
  back to neutral coverage), and on the `1.21.11` node the missing shader-source hook leaves a
  GLSL-plugin shape inert. See [Masks](guide/datapack/masks.md) and the [Java API](API.md).

### Changed

- **The mask coverage UBO gains a `shape_data` array** (a `vec4`-packed, per-leaf slice; 18 fields,
  2336 bytes, appended after every existing field). The `check-mask-ubo.ps1` layout guard covers it.
- **The network parameter cap is raised 32 → 256** (`VFXTriggerPayload.MAX_PARAMS`,
  `VFXTimeline.MAX_OVERRIDES`, `ParamMapArgument.MAX_PARAMS`, and the Flashback replay snapshot cap)
  so a large mask's resolved parameter map (two custom leaves × 32 data slots plus the other leaf
  slots) fits. The wire format is unchanged, so `PROTOCOL_VERSION` stays 6; the cap is a read-side
  bound, so a 2.0.1 client still caps the map at 32 and cannot decode a larger play packet — a
  many-slot mask needs a 2.0.2 client.

## 2.0.1 — 2026-09-22

2.0.1 is a correctness release. It fixes the `blur` effect's animation stepping, makes client entity
selectors resolve the arguments they were silently ignoring, and brings the Flashback replay
integration in line with the replay clock. Everything here is a bug fix — no datapack format, network
protocol or Java API change — so existing worlds and packs keep working.

### Added

- **Client entity selectors now support `tag=`, `name=`, `distance=`, `limit=` and `sort=`.** A
  binding such as `@e[tag=vfx_showcase,limit=1]` used to resolve through a reader that understood
  only `type=`, so it fell through to the generic branch and matched the nearest loaded entity
  regardless of the tag — silently binding the wrong entity, or nothing usable. The client subset of
  the vanilla selector grammar now covers `@s`/`@p`/`@a`/`@r`/`@e`/bare name plus `type=`, `tag=`,
  `name=` (each optionally negated or quoted), `distance=` (a number or a `N..M`/`..M`/`N..` range),
  `limit=` and `sort=` (`nearest`/`furthest`/`random`/`arbitrary`). A selector outside that subset
  (`nbt=`, `scores=`, `gamemode=`, `x`/`y`/`z`, …) now **fails closed** and reports once through
  `VFXLog.warnOnce`, naming the argument, instead of quietly matching the wrong entity.

### Changed

- **The `blur` effect uses a fixed-tap separable Gaussian, so an animated radius no longer steps.**
  The pass derived its tap count from the radius (`int(r * 0.5 + 2)`, clamped), so as an animated
  radius crossed a threshold a whole symmetric pair of taps appeared or vanished at once and the
  normalised result jumped — a visible strength pop. Both passes now always take 12 taps per side
  with fixed weights spanning ±3σ, and only the sample spacing scales with the radius, so the
  Gaussian stays well sampled and the blur fades smoothly at every radius.

### Fixed

- **The replay-timeline integration works on 1.21.11 Fabric again.** Flashback 0.39.9 (1.21.11)
  declares `ReplayServer.jumpToTick` as a **private** field, while 0.43.x (26.2) declares it
  public. The old all-or-nothing init read it with a bare `getField`, threw
  `NoSuchFieldException: jumpToTick`, and aborted the entire compatibility layer — recording
  silently stopped and every effect kept running on the wall clock, so pausing the replay no longer
  paused the effects. The playback symbols are now resolved individually and tolerantly (public
  field first, then the declared/private one), so a visibility change degrades only the feature
  that needs it; a missing, renamed or non-public symbol is reported once through
  `VFXLog.warnOnce`, naming the symbol and the installed Flashback version, instead of no-oping
  silently. 26.2 behaviour is unchanged.
- **An effect triggered by another mod is removed when a replay is scrubbed back past its trigger,
  and an effect no longer disappears after a single frame during playback.** A backward scrub used
  to leave an instance the replay controller did not own — e.g. a mace-hit mod's effect reaching the
  manager through the network receiver — running, so a seek now stops *every* active instance and
  re-places only the effects whose recorded trigger is at or before the new replay time. That also
  fixes the regression where an effect flashed for one frame: the previous fix dropped the flagged
  instance in the per-frame update, which killed the replay's own re-delivered play the frame after
  it was created. Nothing is dropped between seeks any more; the removal happens only on the seek
  path. Both trigger paths (the client-local `VFXAPI.playEffect`/`playEffectId` dispatcher and the
  server→client network receiver) already recorded into the replay.
- **An entity tint in a Flashback replay keeps its timeline parameters and its target.** A recorded
  play carried the effect id, its trigger params and a world anchor, but not the entity UUIDs the
  server had resolved for an entity effect (`entity_tint`/`entity_outline`/`entity_displace`), so a
  replayed tint had no entity to attach to. The recorded action now carries those UUIDs (optional and
  trailing, so older recordings still decode) and the replay controller passes them into the play.
  A parameter the definition animates (keyframes, start/end, `expr`, a world binding or a graph
  input) is no longer frozen into the recording-start snapshot: the definition snapshot rebuilds the
  timeline and the value is evaluated from the effect's replay age, so the tint animates exactly as
  it did live.
- **A paused Flashback replay can still be inspected with `stop_motion` running.** The stop-motion
  hold pass kept compositing the captured pre-pause frame over the live one while the replay was
  paused (the effect's age, and therefore its quantised hold slot, is frozen), which hid every camera
  move. The hold is now bypassed while a replay is paused — the paused frame passes through so the
  camera can be moved — and the captured frame is refreshed on the first unpaused frame, so the
  stop-motion visual resumes during playback.
- **Scrubbing a paused Flashback replay back past an effect's trigger removes the effect.** While
  paused the replay server is frozen, so the polled replay time (`getPartialReplayTick`, which
  returns the pre-scrub tick) can lag behind a scrub that is still pending in `ReplayServer.jumpToTick`;
  the controller saw an unchanged time, skipped its rebuild and left the effect on screen. The replay
  clock now follows the pending seek target while paused, and any backward time move is classified as
  a rebuild, so scrubbing back drops effects whose trigger is in the future and re-places the rest.
- **A player's effects survive a re-login and keep counting while they are offline.** The
  server-side effect memory used to be wiped on disconnect (a leak fix), so every effect vanished
  when a player re-joined. It is now kept — bounded to 256 players — and re-applied on re-join at
  the age the effect would have reached, computed from its original start: time spent offline counts
  (an effect that was 30 % through comes back 30 % plus however long the player was away). A finite
  effect that would have ended during the absence is not resurrected, while looping and persistent
  effects come back at the phase they would be in instead of restarting. No wire or protocol change
  (`elapsedTicks` already carried the offset).
- **VFX effects in Flashback replays now follow the replay timeline instead of the wall clock.**
  Pausing a replay holds the effects, seeking places every recorded effect at the age it should have
  at the new replay time (an effect whose trigger is in the future, or already past its end, is not
  playing), and a recorded play or live edit only takes effect when the replay time reaches its
  recorded tick — so scrubbing back and forth shows the same frame instead of restarting the effect.
  Normal, non-replay gameplay is unchanged. Flashback is Fabric-only.
- **Looping and persistent effects are recorded into Flashback replays.** They used to be skipped:
  a persistent play (`-1`) was dropped outright and an already-running looping/persistent effect was
  left out of the recording-start snapshot, on the (now obsolete) assumption that it would loop
  forever without a stop event. With the replay-timeline controller a recorded play is placed at its
  tick and kept alive until a recorded stop — or for the whole replay when it was never stopped —
  which is exactly how it ran, so an infinite effect now reproduces like a finite one. A looping
  showcase such as `vfxweaver:graph_demo` or `vfxweaver:graph_logic_demo` replays correctly.

## 2.0.0 — 2026-09-20

2.0.0 gathers everything that landed after the previous release. Nearly all of it is additive —
existing datapacks keep working unchanged — and it is the first release to carry the six
Fabric + NeoForge jars for the 26.2 / 26.1.x / 1.21.11 lines.

### Added

- **Value graphs** — an effect definition can drive any numeric input from a small node graph
  (`graph` + `inputs`) instead of one constant. Nodes: `constant`, `time`, `random`, `noise`,
  `curve`, `math`, `mix`, `clamp`, `remap`, `bind`, `expr` and the logic nodes `compare`, `boolean`,
  `if`, `switch`; reusable `subgraphs` act as macros. Wiring is optional and lives outside `params`,
  so an older build ignores it and you can remove it without touching the rest. Reference built-ins:
  `vfxweaver:graph_demo` and `vfxweaver:graph_logic_demo`.
- **Per-pixel fields** — a field-capable input can vary *per pixel* instead of once per frame, using
  the built-in `noise`, `shape`, `gradient`, `curve`, `texture`, `depth`, `normal_facing`, `screen_uv`
  and `world_pos` functions and simple compositions. First consumers: `dent.intensity` and
  `color_grade.tint_r` (built-ins `vfxweaver:dent_field_demo`, `vfxweaver:tint_field_demo`).
  Depth/world fields need `screen_layer: 0`; elsewhere they return the neutral value.
- **Masks** — restrict where a post-processing effect applies, from a tree of screen shapes, world
  volumes (`sphere`/`box` with `volume: "surface"` or `"aura"`), block-geometry leaves and
  code-registered GLSL shapes. A leaf whose source entity cannot be resolved drops **only that
  leaf**, never the whole mask, and never expands coverage. `VFXAPI.sendMaskMove`/`maskMove` moves
  one leaf from the server or every tick.
- **`surface_pattern`** — projects a figure (or a texture) onto the terrain behind each pixel,
  anchored to a world position, with a structural `surface` block choosing which faces receive it
  (`faces`, an inclusive `min`/`max` band, `band_softness`) and an optional `pattern.texture`.
  Additive; needs `screen_layer: 0`.
- **Spark particles** — the `particles` effect can emit glowing additive sprites
  (`"particle": "spark"`, or a `vfx_particles` preset with `"kind": "spark"`) with `count`, `speed`,
  `life`, `gravity`, `bounce`, `size`, `trail`, `glow` and size/colour curves. Built-in presets and
  playable references: `vfxweaver:ember`, `vfxweaver:sparks`.
- **`/vfx stop [<player>]`** — stops every active effect of a player (default: the executor). The old
  `/vfx stop <effect> [players]` form is unchanged; a bare token is read as an effect first, so
  target a player with a selector (`@p`/`@a`).

### Changed

- **Scene depth works on every supported Minecraft line, not only 26.2.** `surface_pattern`, the
  depth/world field functions and the depth-based mask shapes used to render on 26.2 alone. They now
  render on 26.1.2 and 1.21.11 as well; 26.2 behaviour is unchanged, and where no trustworthy depth is
  available the effect still fails closed rather than sampling the wrong convention.
- **`surface_pattern` no longer trails the player by one frame** at `screen_layer: 0` — the effect
  clock and camera/player snapshots now advance before layer 0.
- **Two plays of one masked definition share a single coverage** (the first play's animated
  centre/radius/softness wins). Put the two masks in distinct definitions if they must differ.
- **Mask coverage is screen-space, computed at layer 0** against the intact scene depth. A masked
  effect running at a later layer can therefore tint the first-person hand where a masked block lies
  behind it; run the masked effect at `screen_layer: 0`, or depth-occlude the consumer, to avoid it.
- **The bundled mask demos were reworked** for a clearer A/B: `vfxweaver:mask_entity_demo` and
  `vfxweaver:mask_world_demo` use a fixed 4-block radius, and `vfxweaver:mask_pulse_demo` shows the
  growing-sphere, distance-derived look.

### Fixed

- **`VFXAPI.sendSetParamExpr` and `sendMove` over the network now do what their names say.** Both were
  misread by the client as a *play* and silently restarted the effect instead of editing or moving it;
  each action now has its own handler, so live edits work and replay correctly.
- **A `persistent: true` effect without `loop` animates and emits again.** Its `start`/`end` params
  used to freeze at their start value and particle/spark emission budgets could round down to nothing.
- **Long sessions no longer leak per-effect particle state** — particle budgets, aimed-particle lists,
  rope simulations and spark budgets are cleaned up when an effect stops.
- **Textured `surface_pattern` figures resolve on every node**, a missing sprite now shows the vanilla
  missing texture instead of drawing nothing, and the projections are crisp (NEAREST sampling, no
  mip bleed).
- **A cached texture view no longer goes stale after `/reload` or a resource-pack change.**
- **`surface_pattern` band edges and `normal_mask: 1.0` no longer flicker.**
- **Mask correctness batch** — animated `softness` now applies, right-nested compositions and
  over-cap leaves are per-file errors instead of mis-evaluating, and a block leaf's centre is
  animatable again.
- **A purely screen-space mask renders on every node and every layer**, including where scene depth
  is unavailable.
- **A malformed field `texture` id fails only that one effect**, not every post effect for the frame.
- **`surface_pattern` parse and projection fixes** — an empty `faces` list, `pattern.center_x/y/z` and
  entity-anchored `positions` are now parse errors that name the problem.
- **A repeated explicit instance id no longer stacks duplicates** — replaying a play with a non-zero
  id restarts that instance in place, so `sendStop`/`sendMove` still address it.
- **A disconnecting player's server-side effect memory is released**, so a long-lived server no longer
  accumulates per-player state for players who left.
- **An unknown `particles` `shape` stops that frame cleanly** instead of discarding the frame's
  emission budget.
- **Less per-frame work in the post chain** (fewer lookups, cached block selection, parsed texture
  ids, no per-frame allocation in the sprite probe), and a mid-frame uniform-arena growth no longer
  risks using freed memory on the following frame.

### Technical notes (for developers)

The user-visible list above is the changelog proper; these are the implementation details behind it.

- **Per-node depth convention, proven from the real client jars.** 26.2 is reversed
  (`glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` plus a near/far-swapped projection: near = 1,
  far = 0, `CompareOp.GREATER_THAN_OR_EQUAL`); 26.1.2 and 1.21.11 are standard (near = 0, far = 1).
  The flag is a per-node compile-time constant (`VFXShaderPrograms.DEPTH_REVERSED`) injected as the
  `VFX_DEPTH_REVERSED` shader define, and the single shared reconstruction in `include/camera.glsl`
  converts the raw depth, picks the sky test and the block-occlusion comparison. `depthRecipeVerified()`
  gates every depth-needing pass.
- **The effect clock** moved from the `FogRenderer.endFrame` hook (which runs after `renderLevel`) to
  before layer 0; layers 1/2 reuse the same snapshot and nothing advances twice.
- **One shared depth/world reconstruction** in `include/camera.glsl`, imported by `field.glsl` and
  `surface_pattern.fsh` (was duplicated); `normal_facing` now returns an outward, camera-facing normal.
- **Network receiver dispatch** gives `STOP`, `SET_EXPR` and `MOVE` each one reachable handler (they
  were nested inside the `STOP` branch); the wire format is unchanged.
- **Persistence is a lifecycle flag**, not an `Integer.MAX_VALUE`-tick timeline, so animated params and
  emission budgets behave normally until stopped.
- **Per-instance state** (vanilla-particle budgets, aimed-particle lists, `block_chain` rope
  simulations, spark budgets) is keyed by instance id and pruned against the live set every frame.
- **Texture resolution** has a real per-node implementation: 26.2/26.1.2 via the sprite `AtlasManager`
  (`SpriteId` keyed by `TextureAtlas.location()`), 1.21.11 via the remapped model `AtlasManager` and
  `TextureAtlas.getSprite`; every source form funnels through one `resolved(...)` factory that sets
  the `RESOLVED` flag bit, and a standalone `…/textures/…` id gets `.png`.
- **The std140 layout guard** queries the bare member name first with a qualified fallback, skips
  members the driver does not list, and logs at ERROR without throwing, so a driver that reports only
  bare names cannot drop the post layer.
- **The cached texture view is re-derived** from its descriptor on every use, because a reload closes
  and recreates the loaders' GPU view while reusing the descriptor.
- **`normal_mask: 1.0`** now tests the snapped normal (`abs(n.y) >= 0.5`) instead of the raw normal,
  and `normal_mask` is clamped to `0..1` to avoid a collapsed `smoothstep` edge.
- **Animated mask `softness`** is written into `shape_op[i].z` with the parse-time default as
  fallback, and also drives a composed custom leaf's falloff (previously hard-fixed at `0.25`).
- **Mask composition** folds left-associatively, so a right-nested `op`, a third custom leaf and a
  second block leaf are per-file parse errors rather than silent mis-evaluation.
- **The coverage prepass** binds the main target's depth view (else its colour view) as the
  `DepthSampler` placeholder when depth is untrusted, avoiding a feedback loop with the coverage
  target's own texture.
- **Parse fixes**: an empty `surface.faces` is an error, `pattern.center_x/y/z` is rejected in favour
  of `pattern.center`, an entity-anchored `positions` entry on a `surface_pattern` is rejected, and an
  invalid `pattern.texture.id`/`atlas` is a per-file error.
- **Crisp pattern textures**: NEAREST filtering, no mipmaps, each repeat spans `tile_scale` blocks,
  sprite-sheet cells are inset by half a texel, `preserve` uses the cell's real pixel aspect, and
  `frame` wraps into `0..cols*rows-1`.
- **Internal cleanup**: removed the write-only `ProgramInfo.coverage`/`VFXPass.coverage` flag (the
  coverage prepass is identified by its pipeline/`PassRole`).

## v1.2.0 / Guide v32
### Added
- **Block-model particles (`particles` block mode + `vfx_particles` presets)** — the `particles` effect can now emit real block models instead of vanilla particles, chosen inline (`"particle": "block"` with a `block` state) or by preset id (`data/<namespace>/vfx_particles/<name>.json`, or `VFXAPI.registerBlockParticle`). Each particle has block-display brightness (`-1` = world light, `[blockLight, skyLight]`), gravity, air friction, optional world collision with surface friction and bounce, size, lifetime and spin; the `particles` params override the preset's defaults. `VFXAPI.spawnBlockParticle(spec, position, velocity)` spawns a single particle immediately on the client. Presets are **client-local and never synced**, live in a two-layer registry (datapack wins over code), and render as client-side display entities so vanilla interpolates their motion.
- **Item-model particles (`particles` item mode)** — the same particle engine can now draw real **item** models: `"particle": "item"` with an `item` id (e.g. `"minecraft:skeleton_skull"`), or a `vfx_particles` preset declaring `"item"` instead of `"block"` (exactly one of the two is required; a missing or unknown entry is a per-file parse error). Items render as `ItemDisplay` entities, with the same brightness/physics params as block particles. This is what makes **skull/head particles** possible — the block form has no baked model (a block-entity renderer draws it), the item form does. `VFXBlockParticleSpec.item(ItemStack)` / `builder(ItemStack)` build the spec from code; the API methods are unchanged.
- **Configurable block/item particle rotation** — `vfx_particles` presets (and the `VFXBlockParticleSpec` builder) gained `spin_mode` (`tumble` = the physical full-3D spin, `yaw` = a uniform spin about one axis, `none` = no rotation), `spin_axis` (`random`/`x`/`y`/`z`/`[x, y, z]`), `spin_random` (`0..1`: how random the initial orientation and tumble axis are; `0` = strictly upright and identical for every particle), `spin_friction` (`0..1`: how much the contacted block's friction damps the spin; `0` = never decays) and `spin_roll` (`0..1`: how much tangential impact speed feeds the tumble; `0` = no roll transfer). The `spin`/`spin_random`/`spin_friction`/`spin_roll` effect params override a named preset; the defaults reproduce the previous tumbling behaviour exactly, so existing presets are unchanged.
- **NeoForge support for all three Minecraft lines** — a NeoForge node is built beside each Fabric node from the same source tree (`26.2-neoforge`, `26.1.2-neoforge`, `1.21.11-neoforge`), so the project produces six jars, one per (Minecraft line, loader). The build scripts are split per loader (`build.fabric.gradle` / `build.neoforge.gradle`) and a platform layer (`dev.vfxweaver.platform` / `client.platform`) hides every loader API so the render, datapack, API and command code stays loader-agnostic. The only per-line loader API split is `<26.1`: NeoForge `21.11` has no submit-geometry event and reads the collector from `LevelRenderer.submitNodeStorage`, while NeoForge `26.1.2` matches `26.2`; dependency ranges stay per line (`deps.neo_compat` for NeoForge, `deps.mc_compat` for Minecraft). **Flashback recording is Fabric-only** — Flashback has no NeoForge build, so the compatibility layer no-ops there.
- **`vfxweaver:block_chain` built-in demo** — the `block_chain` effect type now ships a built-in definition, so it appears in `/vfx` tab-completion and `/vfx play vfxweaver:block_chain` works without writing a datapack. It hangs a 6-link `minecraft:iron_chain` from the local player (`pos_x/y/z` bound to `player_x/y/z`, `physics: 1`, `length: 6`, `sway: 0.4`), the same self-anchoring pattern as the `vfxweaver:particles` demo; built-in definitions now number 44.

### Changed
- **Block/item particles tumble like real falling cubes** — `spin` (degrees/tick) is now the magnitude of a full 3D angular velocity about a random axis, with a random full-3D initial orientation, instead of a yaw around world Y. The orientation is integrated each physics tick and slerped between ticks for rendering. On contact the tumble is damped by the contacted block's own friction (the same `Block#getFriction` rule `block_chain` uses), so a cube lands and stops spinning; a little of the tangential slip becomes roll, and a particle with no slip gains none. `spin = 0` now yields a static but randomly oriented model (previously upright).

### Fixed
- **Block/item particles move smoothly and spin on a sane axis** — the model particles were submitted as geometry with their own pose interpolation, which read as jerky stepping and rotated around an odd pivot. They are now driven through client-side `BlockDisplay`/`ItemDisplay` entities, so vanilla interpolates position between ticks, `spin` drives the model's orientation, and brightness/scale map onto the display's own brightness override and transformation. One display entity per live particle, removed on death/effect stop/world unload; the physics is unchanged.
- **Physics `block_chain` ropes now settle on surfaces instead of sliding forever** — the depenetration is a pure displacement (pos and prev shift by the same correction, the player-push idiom) so the push itself injects no velocity, and on contact the inward velocity is dropped while the remaining tangential velocity is damped every contacting tick by the contacted block's own friction (`Block#getFriction`: stone/air 0.6, ice 0.98). A rope therefore grips stone, glides on ice, and a joint lifted out of the ground gains no residual horizontal velocity.
- **Physics `block_chain` ropes no longer sink through the ground** — a rope joint that moved more than half a block into terrain in one tick was being ejected through the block's far face (nearest-face resolution), so the chain tunnelled downwards; the resolver now sweeps the path from the joint's previous position, stops at the first contact and pushes the joint back out through the face it entered, dropping only the inward normal component of its velocity and keeping the tangential part. Links rest on surfaces without sinking, a joint that spawns inside a block still comes free, and a moving anchor drags the chain across the ground (instead of the joint being reverted to its previous position and freezing there). Multi-box collision shapes are approximated by their union box.
- **NeoForge world overlays render on the `26.1.2` line** — overlays now capture the camera, the render buffers and the level renderer's submit storage from the level-render stage event; the earlier `SubmitCustomGeometryEvent` path left those unset on `26.1.2`, so every buffer-based overlay threw and `block_chain` geometry was submitted in a phase that was never drawn.

## v1.1.4 / Guide v31
### Changed
- **`speed_lines` lines are no longer evenly spaced** - a new `pos_rand` param (default 1.0) jitters each line's angular position inside its own slice, so the layout is uneven instead of one line per equal sector. `seed` now drives a line's position as well as its length (animating it churns the whole layout), `pos_rand: 0` restores the old even spacing, and a line pushed against a slice boundary is measured to the nearest centre so it is not cut off. Also fixed the shader's uniform name for the line length (`line_length` -> `length`, matching the param), which used to log "Found unknown but potentially supported uniform line_length" at every startup.

### Fixed
- **Replay recording was silently disabled when Flashback was installed** - the mod registered *two* custom Flashback actions, and Flashback keys its action registry by the action class; both reflection proxies share one generated class, so the second registration threw `Action already registered`, the whole compatibility init was aborted and plays, stops and live edits never reached the recording. Everything now travels through a single action (the datapack definitions snapshot is marked by a reserved id in its payload), and recordings made by earlier builds still decode.
- **`pulse_ring` with `thickness: 0` drew a hairline instead of nothing** - the band width was clamped to a 0.05 minimum, so asking for a zero-width ring still produced a visible thin ring. `0` is now accepted and the ring is skipped entirely (the natural reading of "no band"), while every other value behaves as before.

## v1.1.3 / Guide v30
### Added
- **Code-registered effect definitions (for client-only mods)** - `VFXAPI.registerDefinitions(Map<Identifier, String>)` and `VFXAPI.unregisterDefinition(Identifier)` add definitions at runtime with exactly the datapack validation, in a separate local layer that survives `/reload` and a server sync. Before this a client-only companion mod could not own effect ids on a server at all: a mod-provided `data/<ns>/vfx/*.json` only loads on a multiplayer client in single player (Fabric never reloads `SERVER_DATA` packs there), and `VFXDefinitionManager.applySynced` replaced the whole definition set on join, so `playEffect("mymod:thing", ...)` could not resolve. Local definitions stay private to the client (never synced to other players), the datapack/server layer wins for the same id, and broken entries are logged, skipped and surfaced by `/vfx validate`.

## v1.1.2 / Guide v29
### Added
- **Client-local anchored playback** - `VFXAPI.playEffect`/`playEffectId` now accept an optional world position (`Vec3`) and an optional entity-UUID list when playing locally (no packet), so screen-space effects (`dent`, `shockwave`, `vortex`, ...) land where the event happened instead of at the definition's default spot; the position re-anchors the definition's spatial world bindings (`screen_x`, `screen_y`, `proximity`, ...), exactly like `/vfx playat`. `VFXAPI.moveEffect(effectId, instanceId, worldPos)` re-anchors a running instance locally - call it every tick to follow a moving point or entity. Positions win over the definition's entity anchors; with a null position the supplied UUIDs are zipped with the definition's entity anchors in declaration order (datapack `entity_selector`s stay server-side, so a client-side caller resolves the entities itself). Anchored local plays are recorded into Flashback replays with their anchor.
- **Client-side live control** - `VFXAPI.setParam`, `VFXAPI.setParamExpr` and `VFXAPI.setKeyframe` mirror the network actions locally (`sendSetParam`/`sendSetParamExpr`/`sendKeyframe`), so a pure client-side mod can drive effects without a server round-trip, next to the already local `playEffect`/`playEffectId`/`moveEffect`/`stopEffect`. A null or blank easing means linear for a keyframe segment.
- **Chained animation segments ("from here")** - a live keyframe with a **negative time** pins the value the parameter has right now at the current time and runs the segment to the new value over `|time|` ticks, so animation variations chain without the caller knowing the current value: ramp a blur up and let it hold, then `sendKeyframe(player, effect, "radius", -20, 0.0F, easing)` fades it back out from where it stands. The effect stays a single running instance with one continuous curve (nothing is applied twice), and the wire format is unchanged - only the meaning of a negative `time` is new.
- **More `expr` math functions** - `floor`, `ceil`, `round`, `fract`, `sign`, `clamp(x, lo, hi)`, `lerp`/`mix(a, b, t)`, `step(edge, x)`, `smoothstep(e0, e1, x)`, `mod(a, b)` (positive, like GLSL), `tan`, `atan(y)` / `atan(y, x)` (= atan2), `exp` and `log` (natural) join the existing `sin`, `cos`, `abs`, `min`, `max`, `pow`, `sqrt`, `random()` and `noise(x,y,z)`. Argument counts are now validated when the expression is compiled, with a message naming the function and the expected count - a wrong count used to fail per frame while evaluating instead.
- **Live edits are recorded into Flashback replays** - `setParam`, `setParamExpr` and `setKeyframe` (client-local and server-driven alike) now write a live-edit action into the active replay, so a replay reproduces a mod animating a running effect instead of only its initial play. The encoding reuses the trigger action with new negative sentinels (`-3` set-param, `-4` keyframe, `-5` set-expr), so recordings made by older builds still decode; only a newer recording replayed on an older build is unsupported.

## v1.1.1 / Guide v27
### Added
- **Cubic-Bézier easing curves** - standard CSS-style easing. Inline: `"easing": { "cubicBezier": [x1, y1, x2, y2] }`; named: `data/<namespace>/vfx_curves/<name>.json` with `{ "cubicBezier": [...] }`. Endpoints are fixed at (0,0)/(1,1); the y ordinates may leave 0..1 for anticipation/overshoot (e.g. ease-out-back = `[0.34, 1.56, 0.64, 1]`). Evaluated by solving the curve parameter for the given progress, so the motion is smooth instead of a piecewise-linear polyline.
- **Multi-version builds** - the project now ships from one source for Minecraft `26.1.2` and `1.21.11` (Stonecutter; per-node dependencies and Loom). Effect behaviour, the datapack format and the network protocol are identical across both.
- **Server-synchronized `scoreboard` bindings** - the server derives the tracked `(objective, holder)` pairs from the effect definitions it sends (a `null` holder resolves to the receiving player's name) and pushes value diffs once per tick; the client caches them (`VFXScoreboardCache`, the vanilla client-scoreboard mirror stays a fallback). This fixes `scoreboard` binds on objectives that are not displayed in a slot - the vanilla client only mirrors displayed objectives, so a client-side read alone returned nothing. Pairs come only from definitions the server sent (no client subscription), all collections are bounded. The network protocol version is bumped - update client and server together.

### Changed
- **Verbose logging is now off by default.** Per-request messages (a packet received, an effect started or scheduled, a replay applied) moved from `INFO` to `DEBUG`, so an integration that fires `sendSetParam`/`sendKeyframe` every tick no longer floods the game log; enable the `vfxweaver` logger at `DEBUG` to see them. Warnings that a caller could repeat every tick (unknown effect, protocol mismatch, missing permission, `playEffect` without a client, an override for an undeclared parameter) are now emitted **once per distinct key** through a bounded `VFXLog.warnOnce` instead of on every call. Startup and datapack-reload summaries (`client initialized`, `Loaded N effect definitions`, `Loaded N VFX curves`) stay at `INFO`.
- **Per-node Minecraft range and Fabric Loader floor.** The `26.1.x` jar now declares `minecraft >=26.1 <26.2` (it used to emit `~26.1.2`, so Fabric Loader refused to load it on 26.1 and 26.1.1 even though the Modrinth page listed them), and the loader requirement is per node (`>=0.18.4` for the 26.x jars, `>=0.17.3` for `1.21.11`) instead of one `>=0.19.5`.

### Fixed
- **A `null` easing no longer NPEs on the client-local play path.** `VFXClientAPI` called `EasingFunction.builtIn(null)` (and the Flashback recorder called `easing.name()`), so a caller passing the documented `null` ("use the definition default") crashed before the effect manager's own null fallback could run; both now pass a null function through, and a recorded replay marks "no easing override" with a blank name.
- **Custom easing curves silently degraded to LINEAR.** Named curves were resolved while a definition was parsed, which happens before the curve registry has loaded (reload listeners `prepare()` before `apply()`), so the reference fell back to LINEAR; inline curves also lost their control points over the network (only a name travels). Named curves now resolve lazily (cached, with a warning on a genuine miss), inline curves send a blank name so the client uses its own definition copy, and the client applies synced curves before definitions.
- **Animated `start`/`end` parameters now pass the end value through the easing.** For a curve whose value at t=1 is not 1 (a triangle/wave), the final tick snapped to the raw `end` value instead of the eased one.
- **`/vfx stop <collection>` now cancels the whole pending subtree**, including nested collections, instead of matching only a child's own definition id.
- **Player-bound world overlays** (`pos_x/y/z` with `bind: player_x/y/z`) no longer jitter at the 20 Hz tick rate and are no longer offset by +0.5 block on X/Z: dynamic (bound/expression) positions use exact sub-block coordinates and the player position is interpolated per frame.

## Guide v26
### Added
- **`camera_shake` `hand` param** - first-person hand multiplier (0..1, default 0.5): scales how strongly the camera-shake offset is re-applied to the held-item pose; at 0 the hand stays still while the world shakes, at 1 it moves together. The minimum across active shakes wins. `camera_roll` tilt is unaffected.
- **`particles` world-overlay effect** - emits vanilla particles in animated shapes with zero custom textures: definition fields `particle` (any simple vanilla id; `dust` takes animatable `color_r/g/b` + `size`) and `shape` (`sphere`/`ring`/`helix`/`line`/`cube`/`point`; `line` spans the first two `positions` slots). Animatable params: `rate` (per second, × fade weight), `radius`, `height`/`turns`/`spin` (helix), `speed` (radial launch velocity), `vel_y`. Emission is framerate-independent (budgeted per instance, clamped 1024/s and 256/frame), entity anchors and `sendMove` work as for other world overlays, rendering uses the vanilla particle path so shaderpacks stay compatible. New builtin demo: `vfxweaver:particles` (golden dust helix).
- **`particles`: aimed accelerating streams** - `aim: 1` launches every particle towards the second `positions` slot (any shape/particle; drag-free ballistics via a Fabric access widener on Particle velocity fields, cone `spread`, per-tick `accel`, `lifetime` override) — accelerating energy lines that fly into a target block or track entity-anchored slots.
- **`block_chain` world-overlay effect** - a line of real textured block-model links between two anchors (the `block` definition field picks the block; `spacing`/`arc`/`scale`/`align` params), rendered through the vanilla submit pipeline (`COLLECT_SUBMITS` + `submitMovingBlock`, shaderpack-safe). Links tile the span end-to-end and stretch when pulled taut.
- **`block_chain`: verlet rope physics** - `physics: 1` turns the chain into a client-side rope simulation at a fixed tick rate: gravity sag, world collision (links catch on blocks), the local player pushes links away, `sway` wind wobble. One anchor = hanging chain (`length` blocks); two anchors = rope pinned at both ends, `length` sets the total chain length (more than the span = deeper sag, less = taut). Joint-count changes resample the rope shape, so animated `length` never snaps. Links capped at 512; no server-side collision.
- **Entity anchors: `point`** - `{"entity": "@s", "point": "center"}` selects the reference point on the entity: `feet` (default), `center` (bounding-box centre) or `eyes`.
- **Entity anchors: `dir: "look"` + `distance`** - the anchor is pushed along the tracked entity's live look direction (eyes + look × 24 = a laser target where the entity is looking).
### Fixed
- **`particles` `cube` shape** - two face axes reused one random value, collapsing points onto face diagonals; all six faces now sample uniformly.
- **`block_chain` rendering** - submit poses are camera-relative (links previously rendered off-screen); the physics rope integrates at a fixed tick rate with a time accumulator (rope froze at >20 FPS); joint-based rendering keeps links connected at bends, and joint-count changes resample the rope shape instead of resetting it.

## Guide v25
### Added
- **`/vfx validate [namespace]` command** - dry-run definition health report: loaded count plus every broken datapack file with its parse error, optionally filtered by namespace (tab-completed). Operator-only.
- **`scoreboard` world binding** - params can follow scoreboard values: `{"bind": "scoreboard", "objective": "my_obj", "holder": "optional_name"}`; default holder is the local player's own score, normalized on `range` (default 16), `invert`/`scale` as usual; usable as a value or a `multiply` multiplier; missing objective/score evaluates to 0.
- **Java API: live expression override** - `sendSetParamExpr(player, effectId, param, exprSource)` swaps a running effect's parameter for a compiled math expression (same syntax as JSON `expr`, per-instance seed) without restarting the timeline. Protocol action `SET_EXPR`.
- **Java API: instance move** - `sendMove(player, effectId, instanceId, Vec3)` moves a running world-overlay instance to an exact point; per-tick calls produce smooth scripted motion. Protocol action `MOVE`.
- **Serverbound effect requests** - client mods can ask the server to play an effect via the new `vfxweaver:vfx_request` packet: without `broadcast` it plays only for the requester; `broadcast: true` plays for every connected player and is gated behind operator (gamemaster) permission on the server.
### Changed
- **Built-in effects are datapack JSON now** - all 42 built-in definitions moved from code to `data/vfxweaver/vfx/*.json` resources inside the mod jar: they load through the regular datapack pipeline (so a broken built-in surfaces in `/vfx list`), sync to clients like any datapack file, and can be overridden/copied by packs (jar data is the lowest-priority layer).
- **Sub-block position precision** - world-overlay geometry (`light_beam`, `pulse_ring`, `guide_line`, `block_tint`, `block_outline`) consumes exact `Vec3` coordinates: entity-anchored slots and API moves track at sub-block precision instead of snapping to the containing block; static `positions` entries keep the historical block-centre behaviour. Network protocol version bumped 5 -> 6.

## Guide v24
### Added
- **Entity-anchored `positions` for world overlays** - a `positions` entry may be `{"entity": "<selector>", "offset": [x,y,z]}` (offset relative to the entity's feet, optional): the server resolves the selector once per play (plain `/vfx play`; fails when it matches nothing), the client follows the entity every frame. Slot order is preserved, so `guide_line` endpoints can mix static and anchored entries; works for `block_tint`, `block_outline`, `light_beam`, `pulse_ring`, `guide_line`. `/vfx playat` or a network position override wins over anchors. Java API: pass anchor UUIDs via `EffectRequest.target()` in anchor order. No protocol change (reuses the `entityUuids` field).
- **Collections: full parameter specs on children** - child `params` values may be `start`/`end`, `keyframes`, `bind`, `expr`, `multiply` (merged into a derived child definition); plain numbers keep working as constants.

## Guide v23
### Added
- **`light_beam` `bottom_fade` param** - fades the column alpha toward the bottom (0..1, default 0), mirroring `top_fade`.

### Fixed
- **`light_beam` under shaderpacks** - packs that declare vertex colour `flat` (e.g. Complementary) take each triangle's colour from one vertex, so the height fade rendered as clearly visible triangles ("broken" cylinders). Faded shells are now split into 32 narrow vertical slices, each quad carrying one uniform colour: flat-colour programs show a clean stepped fade with no triangle artifacts, vanilla gets an imperceptible stepped gradient.

## v1.1.0 / Guide v22
### Added
- **`slice_shift` screen effect** - a straight line slices the frame; the halves slide along it with wrap/mirror fill (`angle`, `offset`, `shift`, `mirror`).
- **`noise_warp` screen effect** - animated value-noise field warps the picture in fluid patches (`scale`, `amplitude`, `contrast`, `coherence`, `speed`, `drift_x/y`; `time`-driven morph).
- **`solarize` screen effect** - bright pixels invert, dark stay (`threshold`, `softness`, `intensity`).
- **`double_vision` screen effect** - two ghost copies with slow drift, energy-preserving blend (`offset`, `ghost_opacity`, `drift`, `intensity`).
- **`eyelids` screen effect** - two curved dark lids with correct open-state geometry, composited over the frame (`openness`, `softness`, `curve`).
- **`iris_wipe` screen effect** - old-film iris transition (`radius`, `softness`, `center_x/y`, `zoom`).
- **`digital_glitch` screen effect** - band tearing + RGB split in **slot-gated bursts** with a `chance` parameter (not permanent tearing).
- **`vhs` screen effect** - worn tape: a real crawling tracking band, wobble, bleed, washed contrast.
- **`shockwave` screen effect** - refraction ring with full-weight composite (`center_x/y`, `radius`, `width`, `amplitude`, `sharpness`).
- **`afterimage` screen effect (feedback buffer)** - decaying history echo with desaturation and optional drift zoom; history double-buffered, cleared on resize.
- **`stop_motion` screen effect** - CPU hold-gated frame freezing at N updates/second (`fps`; `<=1` = full speed).
- **`entity_displace` entity effect** - flat per-vertex displaced echo over the intact model (`amplitude`, `scale`, `seed`, `alpha`, color, `through_blocks`).
- **`light_beam` / `pulse_ring` / `guide_line` world effects** - additive world quad effects (columns, rings, dashed parabola).
- **`camera_roll` misc effect** - dutch-angle camera tilt with optional sinusoidal wobble.

### Changed
- **`light_beam`** - layered cylindrical shells with cubic softness falloff (opaque core fading to a soft edge), optional `top_scale` for tapering beams.
- **`pulse_ring`** - camera-facing billboard mode (flat ring always perpendicular to the camera) and a `rot` param for orientation in billboard mode.
- **`afterimage`** - history echo now uses an island blend (the ghost does not overwrite the live frame's transparency).
- **`camera_shake` / `camera_roll`** - now also shake/tilt the first-person hand, not just the world camera.
- **`entity_displace`** - vertex displacement over the intact model with a quantised (snap-glitch) field.

### Removed
- **`block_displace`** - opaque per-vertex block tearing effect. Cut before release (the look was not useful enough to keep the world-space hash + block-model pipeline).
- **`god_rays`** - additive body-beam entity effect. Cut before release (superseded by `light_beam`).
- **`scan_sweep`** - sweep-sheet world effect. Cut before release (too close to `light_beam`).
- **`hud_fade`** - HUD hide effect (binary F1-style hide + `hide_hand`). Cut before release (reverted; to be redesigned later).

### Notes
- Screen effects that animate procedurally accept the auto-filled `time` parameter (effect age in ticks); film-grain-style shaders already used it.
- The `expr` parameter syntax (e.g. stepped `seed`) works in **datapack** definitions; command param-maps (`{[...]}`) accept floats only.
- A `gradlew build`-verified implementation batch; visual effects should be verified with `gradlew runClient`.
### Fixed
- **Effects replayed fresh on every world join and never expired.** The reconnect memory used the server tick counter as its clock, which resets when the server instance is recreated (every singleplayer world reload): elapsed time collapsed to zero, so all previously played effects were re-applied at full duration on each join. The reconnect memory now uses wall-clock time (1 tick = 50 ms), stable across world reloads and restarts.
- **Item frame overlay drawn twice / offset.** The overlay hook fired on every PoseStack.popPose in the vanilla submit and the frame-local pose was rebuilt from identity - the quads landed at world origin or offset by the item transforms. It is now drawn once, anchored to the frame model pose with model-space coordinates matching the panel plane (z ~ 0.97).

## v1.0.6 / Guide v21
### Docs
- **Documentation rewritten.** Every effect now has a per-parameter reference (type, default, what it actually does) and copy-pasteable command/JSON examples.

### Added
- **Item frames as entity effect targets.** `entity_tint`/`entity_outline` now also apply to item frames (non-living entities): the UUID is captured from the frame renderer and a flat tint quad / rectangular outline is drawn on the frame plane, aligned with the frame model.
- **`vertex_displace` effect (entities + blocks)** — all vertices of the target model are randomly displaced; amount, field detail and the random seed are parameters, and the seed itself can be animated/bound to drive the glitch motion (stepped snaps or smooth morphing).
- **`look_at` world binding.** Like `look`, but the target direction is derived from a world `pos: [x,y,z]` anchor instead of explicit yaw/pitch (`range` default 90, supports `invert`/`scale`).

## v1.0.5 / Guide v20
### Added
- **First-person hand effects.** `entity_tint`/`entity_outline` active on the local player now render on the first-person arm as well (the arm bypasses the normal entity submit path, so it needed its own hook). `through_blocks` is ignored there - the hand always draws on top of the world.
- **`camera_shake` `frequency` parameter** - noise oscillations per second, default 7 (previous fixed value).
- **`screen_layer` parameter for all screen effects** - where the effect applies: `0` = below the first-person hand and the GUI, `1` = above the hand below the GUI (default, previous behaviour), `2` = above everything including the GUI.
- **Datapack `region: [x0,y0,z0,x1,y1,z1]` syntax** for block effect positions.
- **`VFXAPI.EffectRequest` fluent builder** for play/send.

### Fixed
- **Persistent effects were dropped from reconnect memory instead of re-applied.** `applyTo` treated the `-1` duration of persistent effects as "expired" and deleted them when their viewer rejoined - a permanent entity tint disappeared forever after one relog. Persistent plays are now re-sent as-is on every join.
- **Parameter overrides for names not declared in the definition were silently dropped.** Setting `through_blocks` via `/vfx playentity` on the built-in entity/block effects did nothing because `createTimeline` only applied overrides for declared params. Undeclared overrides now land as constants; `through_blocks` is also declared on the four built-ins (tab-completion).
- **Outlines now always render under their target.** Entity outline with `through_blocks:1` used to cover the entity with the shell colour; it is now drawn as an opaque shell before the body pass at a lower submit order, so only the rim around the silhouette survives while staying visible through walls. Block outlines ignore `through_blocks` entirely (always occluded) for the same reason.

## v1.0.4

### Added

- **`[players]` argument in `/vfx playentity`.** The command now accepts an optional player list at the end — who sees the effect. Previously an entity effect was always sent only to the player who ran the command, so there was no way to show it to someone else (e.g. to all players in cutscene maps).

  ```
  /vfx playentity <effect> [{params}] <targets> [players]
  ```

  Examples:
  - `/vfx playentity vfxweaver:entity_outline @e[type=!player,distance=..10] @a` — everyone sees the outlines;
  - `/vfx playentity vfxweaver:entity_tint @e[tag=boss] Alice Bob` — only Alice and Bob see the tint.

  Without `[players]` behaviour is unchanged (the executing player sees it). Each viewer gets their own copy of the effect, so it also survives their reconnects independently.

### Changed

- **`duration` now ends every non-looping instance.** The one path that ignored it was `/vfx set` on a *not-running* effect: it used to start an immortal persistent instance whose animation was stretched over `Integer.MAX_VALUE` ticks — visually frozen on its first frame, never removed on its own, and replayed from scratch after every reconnect. Such instances now start with the definition's own `duration` and end on schedule like a normal play. Definitions explicitly marked `"persistent": true` keep their until-stopped semantics.
- **Protocol version 4 → 5** (`vfxweaver:vfx_trigger`). The play packet now carries a resume offset used when re-applying effects after a reconnect (see Fixed). A 1.0.4 client ignores packets from older servers and vice versa — update both sides together.

### Removed

- **The `/vfx key` command.** Nobody used it; runtime keyframing stays available to mods via `VFXAPI.sendKeyframe` and the network `KEYFRAME` action, which are unchanged.

### Fixed

- **Effects no longer restart from the first keyframe after a reconnect.** The server keeps a per-player memory of running effects; on rejoin it re-sends each still-running one with an elapsed-time offset plus any runtime keyframes, so the animation continues exactly where it left off instead of starting over. Keyframes past the nominal duration also extend the effect's lifetime correctly — previously such effects were either dropped early or replayed from the beginning.
- **Invisible effects no longer linger until the cap.** A non-looping instance whose runtime edits have all animated down to zero (or been set to `0`) is invisible but used to keep occupying one of the 64 active-effect slots until the oldest-effect eviction kicked in. It is now removed as soon as every edited parameter rests at zero. Definition-driven animations and looping effects are unaffected.

## v1.0.3
### Fixed
- Expression parser now accepts `_` in identifiers — documented variables (`player_x/y/z`, `light_level`, `time_of_day`) were declared in the `expr` switch but could never be parsed.

## v1.0.2 / Guide v17
### Added
- **Flashback compatibility** — client-local VFX effects (started via `VFXAPI.playEffect` or other mods on the client) are written into Flashback replays as custom actions and re-triggered during playback; effects already running when a recording starts are snapshotted into the replay. Flashback is a soft dependency (`suggests`, reflection-based, no compile-time coupling). Server-triggered effects already travel as `vfxweaver:vfx_trigger` packets which Flashback replays on its own.
- **Server-side effect memory (`VFXServerEffects`)** — effects sent via `VFXAPI.sendEffect` are remembered per player and re-applied on reconnect/join with their remaining duration (persistent `-1` effects always; finite ones while not expired). Pruned when expired; disabled during Flashback replay playback so replays are not doubled.

### Changed
- **Minecraft support widened to 26.1 – 26.1.2** (built against 26.1.2; the mod metadata's Minecraft range covers the whole 26.1 line, verified to compile on 26.1.2 without changes).
- **`speed_lines` reworked** — lines now emanate from the screen borders as wedges (full width at the edge, clipped by it, tapering to a point towards the centre) with sharp step edges, instead of a radial band around the centre. New `length_rand` param (0..1) controls how much the per-line length varies with the seed.

## v1.0.0 / Guide v16
### Added
- Entity effects `entity_tint` / `entity_outline` (by UUID), `/vfx playentity`.
- `through_blocks` (0/1) on both entity effect types.
### Added
- **Parameter overrides in `/vfx play`, `playat`, `playentity`.** These commands now accept an optional param-map `{[name:value],...}` (like `/vfx set`) that overrides the definition's default params at trigger time — including world coordinates (`pos_x/y/z`). This gives the command/datapack the same capability as the Java API (`sendEffect(...overrides)`).
### Added
- **New bindings and player variables.** Bindings: `distance` (raw distance from the camera to `pos` in blocks), `look_x/look_y/look_z` (components of the camera's look vector), `player_x/player_y/player_z` (the local player's position). Math expressions (`expr`) now expose player variables: `health`, `hunger`, `speed`, `light_level`, `time_of_day`, `player_x/y/z`. Datapack examples: `test_expr_health` (screen_flash, `expr: 1.0 - health`), `test_distance` (vignette via `bind: distance`).
### Added
- **Feedback for broken datapacks.** `/vfx list` now prints the list of datapack files that failed to parse on the last `/reload`, with the error text — previously they were silently skipped (log only). `VFXDefinitionManager` stores `parseErrors` (id → message).
### Added
- **`entity_selector` in the effect definition.** The `entity_selector` field (a selector string, e.g. `"@e[type=minecraft:zombie,distance=..10]"`) lets an entity effect (`entity_tint`/`entity_outline`) find its own targets: the server resolves the selector into UUIDs on every play, so `/vfx play <effect>` works without `playentity`. Datapack example — `test_zombie_outline`.
### Added
- **`gradient_map`: gradient build mode and colour coordinate.** New params `mode` (0 = linear, 1 = constant/stepped) and `pos` (0..1, colour coordinate). In linear mode `pos` shifts the transition centre (0.5 — no shift); in stepped mode it is a hard threshold: brighter than `pos` → colour `to`, darker → `from` (useful for masks/stylized shadows). The built-in `vfxweaver:gradient_map` got defaults `mode: 0`, `pos: 0.5`. True grayscale — linear with `from`=black, `to`=white, `pos`=0.5; a hard black/white mask — constant with `pos`=0.5 (0 — black, 0.5+ — white). Datapack examples: `test_grayscale`, `test_grayscale_constant`, `test_gradient_constant`.
- **`posterize`: clean colour reduction.** Removed the per-pixel dithering that produced large random colour steps at high strength ("pixelation"). The shader now does clean quantization (255 → 2 levels) without grain.
### Added
- **Entity effects respect the entity texture.** `entity_tint`/`entity_outline` now bind the entity texture (`Sampler0`) and use it as an alpha mask (like vanilla `rendertype_outline`): transparent pixels are discarded, so the effect follows the texture silhouette rather than a flat box around the model. `entity_tint` gained a `texture` param (0/1): `1` — recolour the texture (texture × colour, pattern visible), `0` — flat colour with the texture only as a mask. Render types memoized per entity texture.
### Added
- **Entity tint/outline (`entity_tint`, `entity_outline`)** — new effect types targeting entities by UUID. New subcommand `/vfx playentity <effect> <targets>` collects target UUIDs (up to 16) and sends them in `vfxweaver:vfx_trigger`; the client stores the UUID on the render state of living entities (mixin `LivingEntityRenderState`) and, in a second pass, redraws the entity model with a custom render type: `entity_tint` — a solid translucent fill of the effect colour, `entity_outline` — an inverted hull (inflated silhouette with front faces discarded), thickness via `width`. Both support `through_blocks` (0 — hidden behind walls, 1 — visible through them). Pipelines registered on the client, shaders — `assets/vfxweaver/shaders/core/entity_fx.{vsh,fsh}`.
### Fixed
- **Camera shake works again**: the per-instance seed (`instanceSeed`) shifted the noise domain by `seed * 0.0001` — for a random 64-bit seed that's ~10¹⁴, `SimplexNoise.fastFloor` overflows the int cast, and all noise samples became exactly 0 → the shake silently didn't play. The seed is now masked to 32 bits (phase range ~4.3e5, safe for the int lattice).
### Added
- **Math expressions in params** — a new way to set a param via `"expr": "sin(t * 0.1) + noise(x, y, z) * 0.2"`. Variables: `t` (ticks since start), `x`/`y`/`z` (camera coordinates), constants `pi`/`e`; functions `sin`, `cos`, `abs`, `min`, `max`, `pow`, `sqrt`, `random()` (0..1), `noise(x,y,z)` (simplex 3D, -1..1). The string compiles to an AST once (Recursive Descent Parser) when the instance is created and is evaluated every frame via `eval(t,x,y,z)` — no per-frame parsing. `random()`/`noise()` are deterministic per-instance seed, so each instance gets its own noise.
- **Unique camera shake** — `VFXActiveEffect` carries a random `instanceSeed`; `CameraShakeManager` shifts the noise domain by this seed, so every `/vfx play vfxweaver:camera_shake` gives a non-repeating shake.
- `SimplexNoise` moved to the shared (common) source (`com.tom.vfx.noise`) — now available both to math expressions and to camera shake.
- **Sound params `volume`/`pitch`** — the effect sound (the `sound` field) can now set volume and pitch via the reserved `volume`/`pitch` params, supporting all modes (constant, animation, world/camera bind, expression). Values are read once at start time (one-shot sound). Example: `"volume": { "bind": "proximity", "pos": [8,80,8], "range": 32 }` — louder near the point.
- **Positional sound `sound_pos`** — the `sound_pos: [x,y,z]` field plays the sound in the world at coordinates via the vanilla mechanism (like `/playsound ... x y z`, with distance falloff); without it the sound plays directly to the player. The position is overridable via the API (`sound_pos_x/y/z` in `sendEffect` overrides).
### Security
- Server packet sizes bounded: the param map in `vfxweaver:vfx_trigger` — max 32 entries; the definition/curve maps in `vfxweaver:vfx_sync` — 1024/256 entries (strings already capped by `ByteBufCodecs.STRING_UTF8`). Protects the client from OOM on a hostile/broken server.
- `VFXSyncPayload` got `protocolVersion` (checked on the client before applying).
- `VFXEffectManager.play` caps server-supplied duration (`MAX_DURATION_TICKS` = 1 hour); persistent/loop semantics from the definition are preserved, but an arbitrary negative/huge `durationTicks` from the server no longer creates an infinite effect.
- `VFXEffectManager.stop(effectId, instanceId)` checks that the instance belongs to the given effect — a server-supplied instance id cannot stop another instance.
- The mutating `/vfx` subcommands (`play`, `playat`, `stop`, `set`, `key`) require operator rights (gamemaster level 2); `/vfx list` stays open.
- The client log no longer prints the packet position (less noise on spam).
### Fixed
- **Datapack VFX effects and curves now sync with dedicated-server clients.** Previously `VFXDefinitionManager`/`VFXCurveManager` loaded definitions only from `PackType.SERVER_DATA`, so a dedicated-server client held only the built-in `vfxweaver:*` and ignored custom datapack effects (`Ignoring unknown VFX effect`). Added a server→client `vfxweaver:vfx_sync` packet (raw definition/curve JSON) sent to each player on join (`ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS`) and to everyone after `/reload` (`END_DATA_PACK_RELOAD`); the client merges them over the built-ins.
### Added
- Direct world-position passing in `VFXAPI.sendEffect(player, effectId, Vec3 worldPos, ...)` — the client re-anchors spatial bindings (`screen_x/y`, `proximity`) to the point without the `pos_x/y/z` hack.
- `position` and `instanceId` fields in the `vfxweaver:vfx_trigger` packet — stopping a specific effect instance via `sendStop(player, effectId, instanceId)`.
- Custom easing curves: named files `data/<ns>/vfx_curves/<name>.json` (an array of control points `points`) and inline objects `"easing": { "curve": [[t,v],...] }` — anywhere an easing is expected (effect default, keyframe, collection child).
- Multiplicative param modifier: `"strength": { "keyframes": [...], "multiply": { "bind": "proximity", ... } }` — final value = base × multiplier (e.g. an animated dent fading with distance to a point).
- Client `VFXAPI.playEffectId(...)` returns the id of the created instance; `VFXAPI.stopEffect(long instanceId)` stops one specific instance.
### Changed
- **BREAKING**: `PROTOCOL_VERSION` 2 → 4: the packet carries an optional position and instance id; the easing field is now a string name (built-in or custom curve id).
- `/vfx playat` moved to the new packet position field (backward compat with the old `pos_x/y/z` trick kept).
- `VFXTimeline` supports param multipliers alongside bindings (position rebinding reconfigures both).
- `VFXDefinitionManager`/`VFXCurveManager` store raw JSON sources for network sync; parsing moved into `reload()`.
### Fixed
- `VFXDefinitionManager.prepare()` no longer aborts loading all VFX definitions because of one malformed datapack file — `catch` widened to `IllegalArgumentException` (previously an unknown `type` or broken `positions` would hit it).
### Changed
- `getModelQuads()` (`VFXWorldOverlayRenderer`) now logs an error when collecting block geometry instead of silently swallowing it.
### Docs
- The user guide was restructured, and the project gained separate Java API, architecture and changelog documents alongside the README and contribution guide.

## v11
- `block_outline` has two modes by the boolean `shell` (default `0`): `0` — extruded walls, `1` — a classic scaled shell with back faces, clipped by the block via the depth buffer.

## v10
- The outline was reworked from a scaled shell to "walls" (each face is extruded outwards along its normal by `width/2`) — the contour physically cannot cover the block in either mode.
- `block_outline` `through_blocks` default is now `0` (occluded by other blocks), `block_tint` — `1` (see-through).

## v9
- `through_blocks` (0/1) on `block_tint`/`block_outline` — visibility through blocks or with occlusion.
- The outline no longer covers the block itself (the shell is drawn only with back faces and masked by the block's own depth).
- `/vfx playat` correctly sets the position again (command positions take priority over the definition's `positions`).
- `sound` on `collection` now plays (locally, only to targeted players).

## v8
- Added `vignette`, `screen_flash`, `motion_blur`, `fov_modifier`.
- `block_tint` restored.
- `block_outline` rewritten as a scaled model shell with multi-block support (`positions`) and depth test disabled.
- The network protocol gained an `action` field (`PLAY`/`STOP`) and a version (`protocolVersion`).
- Datapacks support `sound` and `positions`.
- Adaptive blur; `distortion` supports negative `amount` values.
- The `/vfx playat` command for quick block-effect testing by coordinates.

## v7
- `block_tint` removed; the outline rewritten onto the custom shader `vfxweaver:core/block_outline`.

## v6
- Tint fixed under Iris (custom pipeline instead of `debug_filled_box`).
- Overlay fault tolerance (try/catch per effect and on flush).
- The `look` binding (yaw/pitch/range) to bind effects to the camera rotation.

## v5
- Model geometry for tint/outline; outline with depth test (outside only); `loop`; guide created.

## v4
- `block_tint`, `block_outline`, `persistent`/`fade_ticks`, `collection`. *(historically: cube tint)*

## v3
- World bindings (`bind`), `dent`.

## v2
- Keyframes, two-pass blur, posterize, command hints.

## v1
- Base shader effects, `camera_shake`, datapacks, commands, network.

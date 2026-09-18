# Block particles (submit-engine, presets via datapack + API) — design

Status: draft for review (revised after review: renderer is the submit engine, not a vanilla
particle type; see "Decision log").

## Goal

Spawn **particles that are real block models** (full 3D model, correct render types/lighting/
occlusion — the same submit path `block_chain` already uses) with configurable **brightness**
(BlockDisplay-style light override), **physics** (gravity, friction, world collision, bounce), size,
lifetime and spin. Spawnable from the `particles` effect and from our Java API.

## Non-goals

- No vanilla particle type, no `level.addParticle(...)` / `/particle` integration (deliberate choice:
  the particle engine cannot draw a real block model with correct render types or batching).
- No dynamic world lighting (Iris/Oculus territory); "brightness" is the particle's own light value,
  exactly like `Display.Brightness` on block displays.
- No custom meshes/textures: a particle draws the block's own model.
- No new effect type: the `particles` effect gains the new ids/params.

## Design

### 1. Engine (client-side, per effect instance)

`VFXBlockParticleEngine` (client, in `dev.vfxweaver.client.render`): for each running `particles`
effect in "block" mode, a bounded list of particles:
`position`, `prevPosition` (for interpolation), `velocity`, `age`/`life`, `rotation`/`spin`, plus the
spec (block + parameters). Emission follows the existing `particles` plumbing (shape, rate, per-frame
budget, positions/bindings/aimed mode), and the engine integrates at a fixed tick step like the rope:
gravity, air friction, optional world collision (with the shared MTV depenetration), optional bounce,
lifetime, spin.

Limits reuse the existing constants (`MAX_PARTICLES_PER_FRAME`, a per-instance cap, a global cap) so a
runaway effect cannot flood the frame; the engine is dropped when its effect stops.

### 2. Rendering (submit pipeline, per line)

Submit each particle through the existing world-overlay submit hook — the same callback and the same
per-line code path `block_chain` uses today (`VFXClientRenderHooks` collector + `submitMovingBlock`
style submission), so block particles work exactly where the chain works (Fabric `>=26.1` /
`<26.1`, NeoForge `>=26.1` / `<26.1`). Pose = translate to the interpolated position, rotate by
`rotation`, scale by `size`; vertex light = `brightness >= 0 ? brightness : world light at the
particle position`.

### 3. Presets — datapack and API (our own registry, no Minecraft registry involved)

- **Datapack**: `data/<namespace>/vfx_particles/<name>.json`, full id `<namespace>:<name>`:
  ```json
  { "block": "minecraft:stone", "brightness": [15, 15], "gravity": 0.8, "friction": 0.94,
    "collide": 1.0, "bounce": 0.2, "size": 0.35, "life": 60, "spin": 12 }
  ```
  Fields: `block` (required, block state string), `brightness` (`-1` = world light, an int used as
  `LightTexture.pack(block, sky)`, or `[blockLight, skyLight]`), `gravity`, `friction` (0..1),
  `collide` (0..1, surface friction), `bounce` (0..1), `size` (scale), `life` (ticks), `spin`
  (degrees/tick).
  Loaded by a reload listener on both sides (like `vfx` definitions and `vfx_curves`), bounded
  (256 entries), per-file parse errors collected and reported like definition errors.
- **API**: `dev.vfxweaver.api.VFXBlockParticleSpec` (immutable record, defaults + `builder()`), and on
  `VFXAPI`:
  - `registerBlockParticle(Identifier id, VFXBlockParticleSpec spec)` / `unregisterBlockParticle(Identifier id)`
    — a local layer that survives `/reload` and a server sync, datapack layer wins for the same id
    (the same two-layer rule as `registerDefinitions`);
  - `blockParticle(Identifier id) -> @Nullable VFXBlockParticleSpec`;
  - `spawnBlockParticle(VFXBlockParticleSpec spec, Vec3 pos, Vec3 velocity)` — client-side spawn into
    the engine (returns an instance handle for `moveEffect`-style control if cheap; otherwise a
    one-shot spawn).
- Presets are **never synced to other players**; they are client-local (datapack presets are also
  loaded server-side, but nothing is pushed).

### 4. `particles` effect integration

- `"particle": "block"` + `"block": "minecraft:stone"` — inline spec; params override the defaults
  (`brightness`, `gravity`, `friction`, `collide`, `bounce`, `size`, `life`, `spin`).
- `"particle": "<namespace>:<preset>"` — a registered preset by id.
- Everything else (shape, rate, positions, bindings, aimed mode) is the existing `particles`
  plumbing, unchanged.

### 5. Item particles (extension)

Some blocks have no baked block model at all — their world look comes from a block-entity
renderer, so the moving-block submit path draws nothing. The canonical case is
`minecraft:skeleton_skull` (`block/skull.json` has no `elements`; `SkullBlockRenderer` draws it).
The **item** form of such a block has a real model (the inventory-style head), and dropped items /
item frames already render item models through `ItemStackRenderState`. So the same engine gains an
item mode rather than a second engine:

- `VFXBlockParticleSpec` carries either a `BlockState` or an `ItemStack` (`hasBlock()` /
  `hasItem()`, `item(ItemStack)` + `builder(ItemStack)`; exactly one populated). Physics, light,
  integration, collision, spin and bounce are shared verbatim.
- Datapack: a preset declares `"item": "<id>"` instead of `"block"` (exactly one required; unknown
  item = per-file parse error). Effect definitions add `"particle": "item"` with an `"item"` field
  for the inline mode, next to `"particle": "block"` + `"block"`.
- Rendering: `ItemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.NONE, level,
  owner, 0)` once per item (cached), then `ItemStackRenderState.submit(pose, collector,
  packedLight, OverlayTexture.NO_OVERLAY, 0)` — the same call the dropped-item and item-frame
  renderers make. The pose (camera-relative translate, spin, scale, `-0.5` centring) and the light
  (spec brightness or the world light at the particle, packed) match the block path. The submit
  signature is identical on all three lines, so no per-line guard is needed.
- Bounds: the item-model cache is bounded and cleared on level change; an empty/unbaked item model
  warns once through `VFXLog.warnOnce`, never per frame.

## Verification

- All six nodes build; no other behaviour changes.
- `javap` per line for the submission call used (the same symbols `block_chain` uses — already
  verified on all three lines) and for the block-model/pose helpers the renderer needs.
- In game (user): spawn via the effect, via a datapack preset and via an API preset; check the model
  appearance (3D, correct textures, translucent blocks), brightness override in a dark room, gravity/
  friction/lifetime/spin, world collision (particles rest on the surface, never sink), and that a few
  hundred particles keep the frame stable.

## Risks

- **Per-line submit API**: reuses the path already ported for `block_chain`, so the risk is low; still
  verify with `javap` before coding.
- Performance: block models are heavier than quads; the caps and the per-frame budget keep it sane,
  and the same block's model submission can be cached per frame if profiling shows a cost.
- Interpolation: like the rope, render from `prev→current` by the accumulator fraction, otherwise
  fast particles stutter at low tick rates.

## Decision log

- Renderer: **submit engine** (option B), chosen by the user over a vanilla particle type (A) and the
  hybrid with a carrier particle type (C): a real block model with correct render types was the
  requirement, and the submit path is already ported.
- Brightness: BlockDisplay semantics (`-1` = world light, else packed light; datapack also accepts
  `[blockLight, skyLight]`), animatable through the timeline like any other parameter.
- One shared collision helper (`VFXWorldCollision.resolve`, extracted from the `block_chain` rope) for
  both effects.

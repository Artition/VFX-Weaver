# Block particles (client-side display entities, presets via datapack + API) — design

Status: draft for review (revised after review: renderer is a client-side display entity, not a
vanilla particle type; see "Decision log").

## Goal

Spawn **particles that are real block or item models** (full 3D model, correct render types/
lighting/occlusion — rendered as a client-side `BlockDisplay`/`ItemDisplay`) with configurable
**brightness** (block-display light override), **physics** (gravity, friction, world collision,
bounce), size, lifetime and spin. Spawnable from the `particles` effect and from our Java API.

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

### 2. Rendering (client-side display entities)

**Amended 2026-09-18: option changed from the submit pipeline to client-side display entities.**
The submit version rendered each particle as geometry submitted through the same world-overlay hook
`block_chain` uses, with the engine interpolating `prev -> pos` itself. In play it read as **jerky
stepping** and the submitted pose **spun around an odd pivot**; the user asked for the item/block
display route instead, which is the same thing vanilla item frames and `/summon block_display` do.

Each live particle now owns a client-side `Display.BlockDisplay` (block spec) or
`Display.ItemDisplay` (item spec), created with the spec's block state / item stack and added to the
client level with `ClientLevel.addEntity(Entity)`. The display entity gives, for free:

- **Interpolated motion.** `ClientLevel.tickNonPassenger` snapshots the entity's old position
  (`Entity.setOldPosAndRot`) before each tick and renderers read `Entity.getPosition(partialTick)`;
  the engine sets the target each physics step (and pins the old position each frame for the
  interpolated value), so movement is smooth instead of stepped.
- **Brightness.** `Display.setBrightnessOverride(Brightness)` is the exact block-display light
  override (`-1` = world light).
- **Scale and spin.** The spec's `size` becomes the display transformation's scale and `spin` a
  yaw quaternion around the world Y axis, with the model's own upright orientation preserved. Both
  models are corner-origin (a block display's pivot is its bottom-north-west corner; an item
  display's default `ItemDisplayContext.NONE` draws the raw corner-origin item model), so the
  transformation translates by half the scaled size — rotated with the model — to keep its centre
  on the particle position.

`ClientLevel.addEntity(Entity)` is public on all three lines. The `Display` setters are public on
`1.21.11` but **private on 26.x**, so they are opened with our access widener (Fabric) / access
transformer (NeoForge); the entity-type constant differs (`EntityType` for `<26.2`, `EntityTypes`
for `>=26.2`). One display per live particle, bounded by the existing caps, removed on
death/effect stop/world unload.

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
- Rendering: an item spec spawns a `Display.ItemDisplay` with `setItemStack(stack)` (the same
  entity `/summon item_display` uses, default `ItemDisplayContext.NONE`); the display's own render
  state resolves the item model, so no per-item model cache or explicit `ItemStackRenderState.submit`
  is needed. The same scale + Y-spin + half-size centring transformation as the block path is
  applied. A block spec keeps the baked-geometry guard: a block whose model is empty
  (BER-only, e.g. the skull block) makes a `BlockDisplay` render nothing, so it still warns once
  through `VFXLog.warnOnce` and the item form remains the supported route for skulls.
- Bounds: one display entity per live particle, capped by the existing per-instance/global caps and
  removed on death, effect stop and level change; an unusable block warns once, never per frame.

## Verification

- All six nodes build; no other behaviour changes.
- `javap` per line for the client-entity symbols used: `ClientLevel.addEntity(Entity)`,
  `Entity.setOldPosAndRot` / `getPosition(partialTick)`, the `Display` transformation/brightness
  setters (public on 1.21.11, private on 26.x → AW/AT), `Display.ItemDisplay.setItemStack` /
  `Display.BlockDisplay.setBlockState`, and the `EntityType`/`EntityTypes` display constants.
- In game (user): spawn via the effect, via a datapack preset and via an API preset; check the model
  appearance (3D, correct textures, translucent blocks), brightness override in a dark room, gravity/
  friction/lifetime/spin, world collision (particles rest on the surface, never sink), and that a few
  hundred particles keep the frame stable.

## Risks

- **Per-line display API**: `ClientLevel.addEntity(Entity)` is public on all three lines, but the
  `Display` setters are private on 26.x and need the access widener/transformer; the entity-type
  constant also differs (`EntityType` <26.2, `EntityTypes` >=26.2). Verified with `javap` per line.
- Performance: one entity per particle is heavier than a submit; the existing caps (2048 global, 512
  per instance) bound it, and the display's model is resolved by vanilla.
- Position: the display is driven from the engine each frame and its old position pinned so vanilla
  renders the interpolated value; without that the tick-rate step would show.

## Decision log

- Renderer: originally the **submit engine** (option B), chosen over a vanilla particle type (A) and
  the hybrid with a carrier particle type (C) because a real block model with correct render types
  was the requirement and the submit path was already ported. **Amended 2026-09-18:** the submit
  engine produced jerky motion and a wrong rotation axis in play, so the option changed to
  **client-side display entities** (`BlockDisplay`/`ItemDisplay`) — vanilla interpolates the motion
  and owns the brightness/rotation, which was the requirement the submit path failed to meet. The
  item part of option B (option D, so to speak) is subsumed by `ItemDisplay`.
- Brightness: BlockDisplay semantics (`-1` = world light, else packed light; datapack also accepts
  `[blockLight, skyLight]`), animatable through the timeline like any other parameter.
- One shared collision helper (`VFXWorldCollision.resolve`, extracted from the `block_chain` rope) for
  both effects.

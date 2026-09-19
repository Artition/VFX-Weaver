# Moving Block Structure — Design Spec (v2)

Status: **design draft for review** (supersedes `2026-09-08-moving-block-structure-design.md`, which
was approved but never implemented).
Date: 2026-09-19. Target line: **MC 26.2** (Fabric + NeoForge). No implementation yet.

This revision changes the feature from "an effect with one AABB" into **a server-side object with
independent motion drivers and opt-in interaction flags** (Blender model), exposed through a
**handle-based Java API**, with the datapack as declarative sugar.

## 1. Decisions (locked)

1. **Server-only path.** Real blocks and real collisions require the mod on the server. There is no
   client-side fallback. A server running the mod must not break clients that do not have it (a
   client without the mod simply does not see the structure).
2. **Blender model.** A structure is kinematic by default (the timeline drives it). Drivers
   **compose**: timeline and simulation may run at the same time. Interaction is opt-in per aspect
   (`hitbox`, `collide_world`, `collide_entities`, `riding`), not a single immutable "physics level".
3. **API first, datapack as sugar.** The primary path is a handle object in the Java API; datapack
   effects are a declarative wrapper over it.
4. **Blocks are really restored.** On release the blocks are placed back at the final transform
   (rounded to the grid) with block states and block-entity NBT preserved; occupied cells drop an
   item. The world must never lose blocks.
5. **Multi-loader, multi-line.** One shared `src/`. Loader code only in `dev.vfxweaver.platform`
   and `dev.vfxweaver.client.platform`. Version differences via inline Stonecutter guards.

## 2. Object model

```
StructureHandle                     (server-side, per level)
├─ source      : captured region | explicit position list        (blocks + NBT, cap 4096 / 4 MB)
├─ drivers     : timeline | simulation | both                    (independent, composable)
├─ interaction : hitbox(none|single|per_block)
│                collideWorld, collideEntities, riding
├─ state       : transform, velocity, mass, alive
└─ release     : place | drop | vanish
```

Defaults: `timeline` driver, `hitbox = single`, all interaction flags **off**. Nothing collides or
pushes until asked for — this keeps "pure visual" uses cheap and makes the physics explicitly opt-in.

## 3. Java API

New public entry point `dev.vfxweaver.api.VFXStructureAPI` (mirrors the existing `VFXAPI` style:
static, null-tolerant, `@Nullable` returns, javadoc, `final` params).

```java
// --- creation -------------------------------------------------------------------------------
/** Captures the blocks in [min..max] (inclusive) and returns a handle, or null on failure. */
public static @Nullable StructureHandle captureStructure(
        final ServerLevel level, final BlockPos min, final BlockPos max);

/** Captures an explicit block set (may be sparse/non-cuboid). */
public static @Nullable StructureHandle captureStructure(
        final ServerLevel level, final List<BlockPos> positions);

/** Spawns a pre-built structure from another mod's own data (no world blocks consumed). */
public static @Nullable StructureHandle createStructure(
        final ServerLevel level, final Vec3 origin, final List<StructureBlock> blocks);

// --- lookup ---------------------------------------------------------------------------------
public static @Nullable StructureHandle structure(final ServerLevel level, final long id);
public static Collection<StructureHandle> structures(final ServerLevel level);

// --- motion ---------------------------------------------------------------------------------
public StructureHandle setTransform(final Vec3 pos, final float rotX, final float rotY, final float rotZ);
public StructureHandle setVelocity(final Vec3 velocity);          // simulation driver
public StructureHandle addImpulse(final Vec3 impulse);            // simulation driver
public StructureHandle setParam(final String name, final float value);   // timeline driver
public StructureHandle setKeyframe(final String name, final int time, final float value,
                                   final @Nullable EasingType easing);
public StructureHandle playDefinition(final Identifier effectId, final int durationTicks,
                                      final Map<String, Float> params);  // timeline from a datapack def

// --- interaction ----------------------------------------------------------------------------
public StructureHandle hitbox(final StructureHitbox mode);        // NONE | SINGLE | PER_BLOCK
public StructureHandle collideWorld(final boolean enabled);
public StructureHandle collideEntities(final boolean enabled);
public StructureHandle riding(final boolean enabled);
public StructureHandle physics(final Consumer<StructurePhysics> config);  // mass, gravity, drag

// --- events ---------------------------------------------------------------------------------
public StructureHandle onCollide(final Consumer<StructureCollision> listener);
public StructureHandle onRelease(final Consumer<StructureHandle> listener);

// --- lifecycle ------------------------------------------------------------------------------
/** Stops the structure; blocks are placed (or dropped/removed) per ReleaseMode. */
public void release(final StructureRelease mode);
public void release();                                            // == release(PLACE)
public boolean isValid();
```

`StructureHandle` reads: `id()`, `level()`, `blocks()` (immutable view), `bounds()`, `transform()`,
`velocity()`, `isAlive()`.

Notes:
- The handle is **server-side only** (`ServerLevel`, `ServerPlayer`) — exactly like the existing
  `VFXAPI` server half. Client-side `playEffect` stays for effects; structures are not client objects.
- `physics(Consumer<StructurePhysics>)` is a builder-ish callback so new flags do not change
  signatures (the repo's "add overloads, don't change signatures" rule).
- Everything returns the handle for chaining; failures log once and return `null`/`this`.

## 4. Datapack format (additive)

Effect files gain an optional top-level **`structure`** block. Nothing is renamed or removed, so
existing effects keep working; the field is ignored by older versions.

```json
{
  "duration": 200,
  "params": { "speed": 0.5 },
  "structure": {
    "region": {
      "min": [10, 64, 10],
      "max": [20, 74, 20]
    },
    "positions": [[10, 64, 10], [11, 64, 10]],
    "hitbox": "per_block",
    "collide_world": true,
    "collide_entities": false,
    "riding": false,
    "drivers": {
      "timeline": { "keyframes": [ { "time": 0, "x": 0, "y": 0, "z": 0, "rot_y": 0 },
                                   { "time": 100, "x": 10, "y": 5, "z": 10, "rot_y": 90 } ] },
      "physics":  { "mass": 50.0, "gravity": true, "drag": 0.02 }
    },
    "release": "place"
  }
}
```

Rules:
- An effect is a moving structure **iff** it has the `structure` block. There is no type
  discriminator in the current effect format and none is added (keeps the change purely additive).
- `region` **xor** `positions` (both set → parse error, per-file aborted as usual).
- `hitbox` / `release` / `driver` names are **strings**, which is exactly why they cannot live in
  `params` (params are numeric by design). The `structure` block is the only place for them.
- Param-driven values (`speed`, `rotation_speed`, …) stay in `params`, so the timeline can animate
  them like any other effect.
- Everything inside `structure` is validated at parse time against the same caps as the API
  (4096 positions, ~4 MB NBT); a violation aborts **that file only**.

## 5. Network protocol

Two new payloads on the existing channel set, with `VFXTriggerPayload.PROTOCOL_VERSION` bumped
(wire-breaking change — old clients must be refused cleanly).

1. `vfxweaver:structure_chunk` (S2C) — the block set, sent **chunked** (256 blocks per packet,
   ~16 packets for the 4096 cap) and **only to players tracking the carrier entity**. Fields:
   `structureId`, `chunkIndex`, `totalChunks`, then `{relPos, blockState, beNbt?}` per block.
2. `vfxweaver:structure_transform` (S2C) — the per-tick transform for interpolation: `structureId`,
   `pos (3 floats)`, `rot (3 floats)`, `flags` (bit set: alive / physics-driven). Rotation is carried
   explicitly because entities have no roll.

Entity tracking supplies the transport: the carrier is a normal tracked entity
(`clientTrackingRange`, `updateInterval(1..2)`), so vanilla already decides who sees it; the structure
payloads are addressed to the same players and sent on start-tracking.

Position/rotation **authority is the server**; the client interpolates and never sends structure
state back. There is no client→server structure packet in v1.

## 6. Collision

Verified against `minecraft-*-deobf-26.2.jar` with `javap`:

- The insertion point is **`net.minecraft.world.level.CollisionGetter`** (implemented by `Level`):
  `Iterable<VoxelShape> getBlockCollisions(Entity, AABB)`,
  `getEntityCollisions(Entity, AABB) -> List<VoxelShape>`,
  `getCollisions(Entity, AABB)`, plus `BlockCollisions`.
- A mixin appends our shapes to those iterables for the queried `AABB` — **never** `@Overwrite`
  (the first draft of this was recursive: the body called the method it replaced). A redirect/append
  that concatenates with the vanilla result is the shape of the fix.
- `net.minecraft.world.phys.shapes.VoxelShape` has **no `transform`**. A transformed hitbox is built
  by hand: take `state.getCollisionShape(level, pos).toAabbs()`, map each corner through the
  structure transform, re-normalise min/max, wrap with `Shapes.create(aabb)`.
- Rotation makes a rotated box's AABB **larger** than the box; with `per_block` hitboxes a rotated
  structure therefore has slightly fat collision on diagonals. `single` mode is exact. This is
  accepted and documented (Create has the same property).
- Cache: one `List<VoxelShape>` per structure, rebuilt **only when the transform changes**
  (per tick), not per query. Queries then do an AABB-vs-`bounds()` reject before touching the list.
- `getPreMoveCollisions(Entity, AABB, Vec3)` is **not** a moving-platform mechanism — verified: it is
  a `default` method that concatenates `getEntityCollisions(...)` with
  `getBlockCollisionsFromContext(CollisionContext.withPosition(entity, move.y), ...)`. Do not build
  "riding" on it.
- **Riding / standing on a moving structure** is a separate problem (the boat problem): it needs
  server-side carrying of the rider's delta movement and handling of the client's prediction error.
  It is explicitly **out of scope for the first step** and lands with `riding(true)`.

Client side: the same collision data must exist on the client, because the local player is predicted
client-side. The client builds its own structure cache from the chunk packets + interpolated
transform and feeds the same mixin — otherwise the player is dragged through the platform.

## 7. Entity carrier and lifecycle

- One `MovingStructureEntity extends Entity` per structure, server-side, registered through the
  loader platform layer (Fabric `Registry.register` / NeoForge `DeferredRegister` + `RegisterEvent`).
  This is the **first entity the mod registers**; the platform packages currently have no entity
  registration at all.
- Verified names for 26.2: `Entity.defineSynchedData(SynchedEntityData.Builder)` (builder signature
  — not the old no-arg form), `canBeCollidedWith(Entity)`, `refreshDimensions()`,
  `EntityDimensions.scalable(float, float)`.
- Rotation lives in `SynchedEntityData` as three floats; the client keeps prev/current and lerps by
  `partialTick`.
- Persistence: blocks + `inFlight` + `shouldRestore` in the entity's NBT, so a restart mid-flight is
  recoverable. `shouldBeSaved() → true`, `removeWhenFarAway(...) → false`.
- Ordering (unchanged from v1, and mandatory): **entity first, then clear the originals**
  (`UPDATE_CLIENTS`, bottom-up). The reverse order can lose blocks in a crash window.
- Restore: `setBlock(pos, state, UPDATE_ALL)` (neighbour updates are a feature — torches re-attach,
  light recalculates), then BE NBT. Verified: `BlockEntity.saveWithFullMetadata(HolderLookup.Provider)`
  and `loadWithComponents(ValueInput)` / `loadCustomOnly(ValueInput)` — on 26.2 these take
  `ValueInput`, **not** `(CompoundTag, RegistryAccess)`.
- Conflict cell → `Block.popResource(...)`, log once.

## 8. Client rendering

- One cached mesh per `RenderType` group (solid / cutout / translucent), built **once** when the
  block set arrives, never per frame.
- Verified names: `com.mojang.blaze3d.vertex.MeshData` (constructor takes `ByteBufferBuilder.Result`
  + `MeshData.DrawState`; has `sortQuads(...)`) and `net.minecraft.client.renderer.BufferBuilder`.
  The old `RenderedBuffer` name is gone.
- The structure must render on the **entity path** so shader packs (Iris) keep working; chunk/block
  render types are not patched by Iris in the entity context.
- Still to verify with `javap` before use: the 26.2 block-model → vertex path in an entity context
  (`BlockRenderDispatcher` / `ModelBlockRenderer` signatures), and the exact entity-path
  `RenderType`s (the `RenderType.solid()/cutout()/translucent()` names from the 1.21.x era are stale).

## 9. Phasing

Each step is a shippable increment; nothing is rewritten between steps.

1. **Foundation** — platform entity registration; `MovingStructureEntity`; capture/restore with NBT
   and the reliability rules; server timeline driver; chunked structure packet; client cache +
   interpolated render; `captureStructure` / `release` / `setParam`.
   *Check:* build all nodes; capture 16×16×16, restart the server mid-flight, restore without loss.
2. **Hitboxes** — `hitbox(single|per_block)`; `CollisionGetter` mixin on server **and** client;
   transform cache; `collideWorld`.
   *Check:* walk on a moving platform, walk through a doorway in a Г-shaped structure, no dragging.
3. **Interaction** — `collideEntities`, pushing players/mobs, `riding`.
   *Check:* the structure pushes an entity on impact and carries a rider without rubber-banding.
4. **Simulation** — rigid-body driver (`mass`, `gravity`, `drag`, impulses) composed with the
   timeline; sub-stepping for speeds where tunneling is possible.
   *Check:* a structure falls, lands, and can be pushed.

## 10. Anti-patterns

- `@Overwrite` on the collision methods (self-recursion); no `@Overwrite` where an append works.
- `StructureTemplate` for capture/restore.
- One packet for the whole structure (lag spike / client crash).
- Per-frame mesh rebuilds; any per-frame allocation in the collision path.
- Storing the block set in `SynchedEntityData`.
- Using `ResourceLocation` (it is `Identifier`), chunk/block render types for the structure mesh,
  loader types outside the `platform` packages, or `Minecraft.getInstance()` in server-side code.
- Believing `VoxelShape.transform` exists, or that `getPreMoveCollisions` carries riders.
- Clearing the originals before the carrier exists; `UPDATE_NEIGHBORS` during capture.
- Ticking block entities inside a flying structure.

## 11. Open items (verify with `javap` before coding)

1. Entity registration APIs on both loaders for 26.2 (`DeferredRegister` vs `RegisterEvent`; Fabric
   entity attributes / `FabricEntityEvents` equivalents, if any).
2. The 26.2 block-model vertex path usable in an entity render context, and the correct entity-path
   `RenderType` constants.
3. `ValueInput` / `ValueOutput` construction helpers (`TagValueInput`?) for loading a captured
   `CompoundTag` back into a freshly placed block entity.
4. Exact signature for the client's start-tracking hook on each loader.
5. `SynchedEntityData.Builder` availability for entities registered through each loader path.

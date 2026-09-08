# Moving Block Structure Effect — Design Spec

Status: **approved design, not implemented** (paused in favor of another feature on 2026-09-08).
Validated against Create's contraption system by an external senior consultation; their answer's key points are folded into this document.

## 1. Goal

A new effect type `moving_structure` (name TBD during implementation) that **really** moves blocks in the world:

- Blocks (a `positions` list or `region` from the datapack definition, cap **4096** blocks) are **captured server-side**: original positions become air, a carrier entity holds the blocks.
- The carrier moves with **smooth float coordinates and rotation**, driven by the existing timeline system (time-driven server-side: `duration`, keyframes, easing; animatable params `move_x/move_y/move_z`, `rot_x/rot_y/rot_z`).
- **Collision**: players/mobs collide with the moving structure and can stand on it. One AABB per group (like a boat); single block = perfect fit. Per-block collision is explicitly **v2**.
- **All blocks incl. block entities with NBT** (chests/shulkers with contents, signs, spawners — user decision "Все блоки с NBT").
- On stop / timeline end: blocks are **really placed** into the world at the final transform, rounded to the grid, preserving block states and BE NBT. Occupied cells → block drops as an item (Create pattern).
- The world must never lose blocks: crash/restart/unload scenarios all recover (see §6).

## 2. Architecture (validated)

Custom server entity `MovingStructureEntity extends Entity` — NOT a display entity (those have no collision and client-authoritative transforms).

```
Server play path:  read region → SPAWN ENTITY with captured data (entity-first!)
                   → clear originals setBlock(pos, air, UPDATE_CLIENTS) bottom-up
                   → mark chunks dirty
Client:            START_TRACKING → custom sync packet (structure data) → build cached mesh once
                   → EntityRenderer draws cached mesh with interpolated transform (entity RenderTypes)
Server tick():     timeline evaluates move/rot params → entity pos + SED rotation floats
Stop/end:          setBlock(pos, state, UPDATE_ALL) per block → BE NBT restore (remap x/y/z)
                   → discard entity
Crash/restart:     entity NBT persists in its chunk; onLoad: if inFlight && originals still
                   present → remove originals (entity is authoritative); if structure should
                   already be placed → immediate restore (v1); continue-timeline is v2
```

Key properties:
- **Entity RenderTypes only** (entityCutout/entityTranslucent path) — Iris patches the entity path; never chunk RenderTypes.
- **Cached mesh** (one vertex buffer per RenderType: solid/cutout/translucent) built once on sync — N draw calls, not 4096 per-frame builds (Create pattern).
- Position sync via standard entity packets (`updateInterval` 1–2, override `lerpTo` for a minimum interpolation window); **rotation via 3 floats in SynchedEntityData** (entities have no roll) with prev/current interpolation in the renderer by partialTick.
- Structure data travels in a **one-shot custom packet on START_TRACKING** (Fabric `EntityTrackingEvents` — verify exact name), never in SynchedEntityData and never via entity NBT sync.

## 3. Data model

Per block: `{relPos: BlockPos (relative to entity origin), state: BlockState, beNbt: @Nullable CompoundTag}`.

- BE NBT captured with `BlockEntity.saveWithFullMetadata(...)` (includes `id` + `x/y/z` + custom data such as `Items`).
- Persisted in entity NBT (`addAdditionalSaveData`): ListTag of blocks + `inFlight` flag + `shouldRestore` flag (+ `timelineProgress` in v2).
- Caps enforced at capture: `blocks > 4096` or estimated NBT size `> 4 MB` → refuse + log (external input, see AGENTS.md bounded-collection rule).

## 4. Capture / placement mechanics

- **Capture**: `BlockState` + `BlockEntity.saveWithFullMetadata`; removal with `setBlock(pos, air, Block.UPDATE_CLIENTS /* 2 */)` — no neighbor cascade (torches/water must not pop during capture); remove **bottom-up**; after the region is cleared, run a light check per removed position (`LightEngine.checkBlock` — verify 26.x name).
- **Placement**: `setBlock(pos, state, Block.UPDATE_ALL /* 3 */)` — neighbor updates are a **feature** ("settling": torches re-attach, light recalculates); then for BE NBT: get the created BE, **remap `x/y/z`** in a copy of the tag, `loadWithComponents(...)` (verify 26.x name vs `loadCustomOnly`), `setChanged()`.
- **Conflict** (target cell not air/replaceable at placement): `Block.popResource(level, pos, new ItemStack(state.getBlock()))` and skip — contents of a dropped container are lost (v1 accepts this; Create drops the block item too).
- **Entity-first ordering is mandatory** (crash between spawn and clearing leaves a recoverable duplicate; the opposite order loses blocks).

## 5. Entity details

- Registration: `EntityType.Builder.of(MovingStructureEntity::new, MobCategory.MISC).sized(1, 1) /* placeholder */.clientTrackingRange(10).updateInterval(2)`; recompute real dimensions after capture via `cachedDimensions = EntityDimensions.scalable(width, height)` (verify name) + `refreshDimensions()`.
- `canBeCollidedWith() → true` (players/mobs push against it, can stand on top). Riders/`positionRider` — v2.
- `shouldBeSaved() → true`, `removeWhenFarAway(_) → false` (never despawn mid-flight).
- Rotation: `ROT_X/ROT_Y/ROT_Z` as `EntityDataAccessor<Float>`; client keeps prev/current and lerps in the renderer (`Mth.lerp(partialTick, ...)`); position interpolation via `lerpTo` with a minimum of ~3 lerp steps.
- Animation source: the definition's timeline evaluated **server-side** (time-driven only — `t`, keyframes, easing, constants; client binds like camera/proximity are unavailable server-side, accepted).

## 6. Reliability scenarios

| Scenario | Outcome |
|---|---|
| Crash between entity spawn and block clearing | Duplicate (entity + blocks). On entity `onLoad`: `inFlight && originals present` → remove originals (entity authoritative). |
| Chunk with entity unloads mid-flight | Entity persists in its chunk NBT; blocks stay cleared until stop/placement. Correct. |
| Server restart mid-flight | Entity reloads from NBT; v1: immediate restore (`shouldRestore`), v2: continue timeline from saved `timelineProgress`. |
| Block place occupied during restore | Drop as item, log. |
| Block under the structure changes while flying (player builds into cleared area) | Conflict check at placement handles it (drop). |

## 7. Known risks / mitigations

| Risk | Mitigation |
|---|---|
| Block-mesh building in an entity context on 26.x (texture atlas / vertex format bindings — flagged by consultant as unverified) | Verify against mapped jar early in phase 4; fallback: `BlockRenderDispatcher.renderSingleBlock` per block (slower; acceptable ≤ ~256 blocks, unblocks everything else) |
| 4096 blocks per-frame rendering | Cached mesh (mandatory), one build, N draws |
| Neighbor cascade on capture | `UPDATE_CLIENTS` only, bottom-up removal |
| Dark holes after removal | Light engine check per removed position |
| NBT bloat (shulker inventories) | 4 MB capture cap |
| Rotation not interpolated (SED is instant) | prev/current lerp in renderer |

## 8. Implementation plan (v1)

1. **Entity skeleton** — registration, NBT save/load, no-op tick. Verify: spawns, survives restart.
2. **Capture** — `moving_structure` effect type + datapack definition (positions/region); server play path: capture → spawn → clear. Verify: blocks vanish, entity appears with data.
3. **Collision + dimensions** — AABB from captured bounds. Verify: player collides / stands on it.
4. **Client sync + render** — START_TRACKING packet, cached mesh, entity renderer with rotation lerp. Verify: blocks visible, Iris check.
5. **Movement** — server timeline drives `move_*`/`rot_*` params. Verify: smooth motion.
6. **Placement on stop/end** — restore + conflicts + `shouldRestore`. Verify: chest contents survive round-trip.
7. **Docs + deploy** — GUIDE v26 entry, CHANGELOG, API.md (new packets), in-game checklist.

**v2 backlog** (explicitly deferred): per-block collision (Create `ContraptionCollider` pattern), riding/`positionRider`, BE ticking in flight (needs fake world), continue-timeline-after-restart, palette-compressed block storage, fake world for connected textures.

## 9. Anti-patterns (never)

- `StructureTemplate` for runtime capture/restore (overhead, no ordering control — Create doesn't use it).
- Clearing blocks before the entity exists (crash window loses blocks).
- `UPDATE_NEIGHBORS` during capture (cascade pops torches/water).
- Chunk/block RenderTypes for the structure mesh (Iris won't patch them in entity context).
- Per-frame mesh rebuilds; storing structure data in SynchedEntityData; relying on default `removeWhenFarAway`.
- Placing with `UPDATE_INVISIBLE`/no neighbor updates (blocks won't "settle").
- Ticking BEs on the flying structure in v1.

## 10. 26.x API names to verify at implementation time

`EntityDimensions.scalable`, `NbtUtils.writeBlockState`, `loadWithComponents` vs `loadCustomOnly`, `BufferBuilder.RenderedBuffer` vs `MeshData`, chunk dirty method (`setUnsaved`/`markDirty`), Fabric start-tracking event name, entity-context block-mesh RenderType path (Sheets/atlas). Method: `javap` against the loom-mapped jar (same technique the scoreboard-bind agent used successfully).

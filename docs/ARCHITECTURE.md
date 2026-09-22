# Architecture

## Context and goals

vfxweaver is a client-side VFX library mod for Minecraft on **Fabric and NeoForge** (six jars, one per Minecraft line and loader): the server triggers effects over the network (or another mod — directly via `VFXAPI` on the client), the client plays and renders them. Goals: (1) declarative effects via datapack JSON without recompiling, (2) bounded render load even with many concurrent effects, (3) fault tolerance — one broken effect/datapack file must not break the rest.

The systems below are **loader-agnostic**: they touch only Minecraft/Mojang APIs, never `net.fabricmc.*` or `net.neoforged.*`. The loader is reached solely through the [platform layer](#loader-platform-layer), so the same source builds every Fabric and NeoForge jar.

## Core systems

```
                     ┌──────────────────────┐
 datapack JSON  ─────▶│ VFXDefinitionManager │  (common: main + client-as-single-player)
                     └──────────┬───────────┘
                                │ VFXDefinition (type-safe model)
                                ▼
 /vfx play, VFXAPI ──▶  VFXEffectManager (client)  ──▶ VFXActiveEffect (timeline + positions + fade)
                                │
              ┌─────────────────┼─────────────────────┬──────────────────┐
              ▼                 ▼                     ▼                  ▼
   VFXPostProcessingManager  VFXWorldOverlayRenderer  CameraShakeManager  VFXEntityEffectRenderer
   (shader post-effects)     (block_tint/outline)     (camera shake,     (entity_tint/outline,
                                                        FOV)                second model pass)
              │
              ▼
   FlashbackCompat (client, optional) ── writes replay actions into Flashback.RECORDER
   VFXServerEffects (server) ────────── re-applies remembered effects to (re)joining players
```

- **`VFXDefinitionManager`** (main) — definition registry: built-ins (`registerBuiltIns()`) + datapack (`data/<ns>/vfx/<name>.json`, reloaded via `SimplePreparableReloadListener`). Registered on both the server and the client (for single-player).
- **`VFXEffectManager`** (client, singleton) — the single source of truth about what is currently playing: the `active` list (`List<VFXActiveEffect>`) and `scheduled` (deferred collection children), a shared effect `clock` timer in ticks.
- **`VFXActiveEffect`** — one playing instance: `VFXTimeline` (animated params + world bindings) + fade-in/out weight + a list of positions (for world overlays) + a list of target UUIDs (for entity effects).
- **`VFXPostProcessingManager`** (client) — runs active post-effects through ping-pong `TextureTarget`s every frame.
- **`VFXWorldOverlayRenderer`** (client) — draws `block_tint`/`block_outline` over block geometry via `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`.
- **`VFXEntityEffectRenderer`** (client) — registers custom pipelines/render types for `entity_tint`/`entity_outline`; the actual drawing is done by the `LivingEntityRendererMixin` in a second model pass.
- **`CameraShakeManager`/`CameraMixin`** (client) — sums the noise of all active `camera_shake` effects into a position/rotation offset, applied by a mixin to `Camera`.
- **`VFXWorldBindings`** (main, but data lives on the client only) — computes `bind` params (`screen_x`, `proximity`, `look`, `distance`, `look_x/y/z`, `player_x/y/z`, `camera_yaw_delta`/`pitch_delta` and player state: `health`/`hunger`/`speed`/`light_level`/`time_of_day`) relative to the current camera frame and the player snapshot.

## Loader platform layer

The core systems above are loader-agnostic — they never import `net.fabricmc.*` or `net.neoforged.*`; the loader is reached only through the platform packages:

- **`VFXPlatform`** (main) — loader name and mod-loaded queries (`isModLoaded`, `name`).
- **`VFXNetwork`** (main) — payload registration and transport (`registerCommon`, `sendToPlayer`, `allPlayers`) plus the client-receiver dispatch table (`registerClientReceive`/`dispatchClient`).
- **`VFXLoaderEvents`** (main) — server lifecycle, tick, command registration, datapack reload and player-join wiring (including the definition/curve sync).
- **`VFXClientNetwork`** (client) — registers the client-bound receivers on the loader's client networking API and forwards them to `VFXNetwork.dispatchClient`.
- **`VFXClientRenderHooks`** (client) — client lifecycle/tick/join events and the world-overlay render-event plumbing, capturing the camera and geometry sink so `VFXWorldOverlayRenderer` stays loader-agnostic.

The entry points are guarded per loader: `VFXMod` (Fabric `ModInitializer`) and `VFXNeoForgeMod` (`@Mod`) on the common side, and `VFXClient` (`ClientModInitializer` / `@Mod(dist = Dist.CLIENT)`) on the client. **Client-only safety:** all client code stays in `src/client` and `src/main` never references it, so the dedicated server never loads a client class.

## Data flow per frame

1. `GameRendererMixin.vfxweaver$render` (injection before `FogRenderer.endFrame`) — called once per frame:
   - updates `VFXWorldBindings` from the current camera (position, yaw/pitch, view-rotation-projection matrix);
   - advances `VFXEffectManager.clock` by `deltaTicks` (`DeltaTracker.getGameTimeDeltaTicks()`, 0 on pause);
   - `VFXEffectManager.update()` — removes finished effects, fires due collection children, advances timelines;
   - `VFXPostProcessingManager.process(...)` — runs the chain of shader passes.
2. `CameraMixin` (injections in `Camera.calculateFov`/`update`) — reads the already-updated `VFXEffectManager` for the FOV delta and camera shake.
3. `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` — `VFXWorldOverlayRenderer` draws world overlays for active `block_tint`/`block_outline`.
4. In entity rendering, `LivingEntityRendererMixin` (injection right after the vanilla `submitModel` in `submit`) reads the entity's UUID from the render state (`ITomVFXEntityState`, filled in `extractRenderState`) for every living entity and, if there is an active `entity_tint`/`entity_outline` for that UUID, calls `submitNodeCollector.submitModel` again with a custom render type — a second pass over the original model in the same transform space.

## Post-processing (pipeline)

The hook is right before `FogRenderer.endFrame()` in `GameRenderer.render`, i.e. after the world and the vanilla post chain, but before the GUI. Each active post-effect expands into one or more shader passes (`VFXShaderPrograms.getPrograms(type)`, e.g. `blur` = X+Y). A copy of `mainTarget` → `pingPong[0]`, then the pass chain alternates `pingPong[0]`/`pingPong[1]`, the last pass writes back into `mainTarget`. Each pass is an ortho projection + a `SamplerInfo` UBO (in/out sizes) + an optional `Config` UBO (effect params, blended with the neutral value by the current fade weight — `VFXEffectType.neutralValue`), both via `MappableRingBuffer` (mapped and rotated every frame).

## Per-pixel fields

A field-capable input (currently `dent.intensity` and `color_grade.tint_r`) may carry a `{ "field": ... }` object that varies per pixel. The model lives in the MC-free `dev.vfxweaver.field` package (shared `src/main`, no `net.minecraft.*`/`com.mojang.*`): `VFXField` parses and validates the tree (type coercion, depth/leaf/node/texture caps, graph references on numeric parameters), and `VFXFieldProgram` flattens it once per instance into the fixed uniform program (leaf metadata, a `MAX_PARAMS`-wide parameter vector per leaf, a curve pool and a post-order instruction list). `VFXDefinition` stores the fields and `VFXTimeline` packs them, exposing `getFieldProgram`, `fieldNeedsDepth` and the per-frame graph evaluator. At runtime `VFXPostProcessingManager` writes a `FieldConfig` UBO each frame from the packed program plus a preallocated inverse view-projection (`VFXFieldEnv`), binds the main target's depth as `DepthSampler` (NEAREST) and the optional field texture as `fld_tex0`, and `assets/vfxweaver/shaders/include/field.glsl` evaluates the built-in functions and the composition program.

- **UBO field-order rule.** `field.glsl`'s `FieldConfig` declaration order, `VFXFieldProgram.write`'s emission order and `VFXShaderPrograms.FIELD_CONFIG_SIZE` are one positional std140 contract — change all three together. The four leading floats are `fld_uniform`, `fld_depth_valid`, `fld_leaf_count`, `fld_weight`; `fld_weight` reuses the padding the three-float form left before `fld_leaf_fn`, so the later offsets are unchanged.
- **Fade.** `vfx_field_intensity` blends the field against its neutral `1.0` by `fld_weight`, so a field-driven input reaches exactly the neutral value at weight 0 (`dent_field_demo` / `tint_field_demo` no longer need `fade_ticks: 0` for correctness).
- **Shared shapes.** `field.glsl` owns the 2D `circle`/`ellipse`/`rect`/`polygon` (with `fill`, `stroke_width`, `softness`, `repeat`) and the 3D `sphere`/`box` helpers; masks and `surface_pattern` consume `vfx_shape_sdf`/`vfx_shape_coverage` and never re-implement them.
- **Layer 0.** Depth/world fields reconstruct a world position from the scene depth, which is only valid at screen layer 0; elsewhere the Java side marks the depth invalid and the shader returns the neutral value (a once-per-definition warning is logged for fields that need depth). Screen-space fields work at every layer.

## World overlays

`block_tint`/`block_outline` are drawn not as a shader pass but as geometry: the block model's baked quads (`ModelManager.getBlockStateModelSet()`, fallback a full cube), transformed in a `PoseStack` relative to the camera. `block_outline` supports two modes (the `shell` param): `0` — walls (each face is extruded outwards along its normal, physically cannot cover the block), `1` — a classic scaled shell with back faces + back-face culling, clipped by the block's own depth buffer.

## Entity effects (second model pass)

`entity_tint`/`entity_outline` are also geometry, but of the entity model rather than the world: `LivingEntityRendererMixin` in `submit` calls `submitNodeCollector.submitModel` again with the same `model`/`state`/`poseStack` but a different `RenderType`. Vanilla `EntityRenderState` has no UUID field — a mixin on `LivingEntityRenderState` adds one (the `ITomVFXEntityState` interface), filled in `extractRenderState`. Both render types use `DefaultVertexFormat.ENTITY` (model vertices; the shader ignores textures/overlay/lightmap) with custom pipelines (`assets/vfxweaver/shaders/core/entity_fx.{vsh,fsh}`) over `MATRICES_FOG_LIGHT_DIR_SNIPPET` — the standard UBOs (Projection/DynamicTransforms/Fog/Globals) are bound the standard way, no separate UBOs needed.

- `entity_tint`: a fill of the model; the effect ARGB is passed as `tintedColor` to `submitModel` and becomes the vertex color. Two modes selected by the boolean `texture`: `1` — recolour the texture (texture rgb × effect color, keeps the texture alpha), `0` — flat color with the texture only as an alpha mask (like vanilla `rendertype_outline`). Depth `LEQUAL` (occluded) or `ALWAYS_PASS` (`through_blocks: 1`), `TRANSLUCENT` blending — lands in the `ModelFeatureRenderer` translucent bucket and draws after opaque entity bodies.
- `entity_outline`: an inverted hull — the model is scaled by `1 + width` around its vertical centre (`boundingBoxHeight/2`), the fragment shader discards front faces (`gl_FrontFacing`), depth `LEQUAL` leaves only the rim behind the silhouette (or `ALWAYS_PASS` for through-wall glow). Width is set by scale, not a uniform: the `submitModel` path has no way to bind a custom UBO for a per-draw value, and the pipeline API has no front-cull.

Both effects bind the entity texture as `Sampler0` and use it as an alpha mask: texels with zero alpha are discarded, so the effect follows the texture silhouette rather than a flat box around the model. Render types are memoized by the texture `Identifier` (`LivingEntityRenderer.getTextureLocation(state)`, passed from the mixin); pipelines are shared per (mode, through-blocks).

Targets are set by UUID: `/vfx playentity <effect> <selector>` collects up to 16 UUIDs and sends them in `vfxweaver:vfx_trigger` (`entityUuids`); `VFXEffectManager.getActiveEntityEffects(uuid)` finds the active effects for a specific entity. The UUID cap is `VFXTriggerPayload.MAX_ENTITY_UUIDS`.

## Flashback integration

Flashback (https://modrinth.com/mod/flashback) is a **soft dependency**: the mod works without it, and nothing in the code compiles against it — all access is reflective (`Class.forName`, `Proxy`), guarded by `VFXPlatform.isModLoaded("flashback")`. It is **Fabric-only** (Flashback has no NeoForge build), so on NeoForge the guard is false and the recording layer is skipped.

The reflection targets a version-dependent API, so each playback symbol (`Flashback.getReplayServer`, `ReplayServer.getPartialReplayTick`, `replayPaused`, `currentTick`, `jumpToTick`) is resolved on its own through a tolerant helper: a public field first, then the declared (private) one made accessible. This matters because Flashback changes symbol visibility between builds — 1.21.11's Flashback 0.39.9 has `ReplayServer.jumpToTick` **private**, 26.2's 0.43.x has it public. The required recording symbols (the action registry, the recorder, the replay writer) are resolved separately; if one is absent the integration is disabled with a stack trace. Any missing, renamed or non-public playback symbol is logged **once** through `VFXLog.warnOnce`, naming the symbol and the installed Flashback version (`VFXPlatform.modVersion`), and only degrades the feature that needs it — it never silently no-ops the whole integration (the 1.21.11 regression). `scripts/check-replay-clock.ps1` asserts the tolerant resolution and the logged failure path.

- **`FlashbackCompat`** (client) — registered as an `Action` (`vfxweaver:effect_trigger`) in Flashback's `ActionRegistry`. Client-local plays (`VFXAPI.playEffect` through `VFXClientAPI`) are written into the active replay via `Recorder.submitCustomTask` (`effectId + durationTicks + easing + params`); on playback Flashback calls the action's `handle`, which decodes the payload and re-triggers the effect on the render thread. A per-tick `END_CLIENT_TICK` hook detects a recording start (`Flashback.RECORDER` becoming non-null and ready) and snapshots the already-running effects so they appear from the first replay tick; looping/persistent effects are snapshotted too (the replay-timeline controller keeps a recorded play alive until a recorded stop, or for the whole replay when it was never stopped). Server-triggered effects *are* recorded here as well — Flashback cannot replay unknown custom payload packets, so `vfxweaver:vfx_trigger` plays/stops/edits are written into the replay through the same action.

- **`VFXServerEffects`** (server) — remembers every `VFXAPI.sendEffect` per player (`player → effectId → params/duration/easing/startMillis`). Time keeps running while a player is offline: on (re)join (`SYNC_DATA_PACK_CONTENTS`, after datapack sync) the still-active effects are re-sent with `elapsedTicks` computed from their original start, so a re-login resumes at the age the effect would have reached (offline time counts). Finite effects that ended during the absence are pruned instead of resurrected; persistent (`-1`) and looping effects always come back, the looping one at its current phase (the client wraps the elapsed time modulo the period). Bounded per player (`MAX_EFFECTS_PER_PLAYER`) and globally (`MAX_TRACKED_PLAYERS`; the global cap is what bounds the persistent effects of players who never return). Disabled while a Flashback replay is being played back (`Flashback.isInReplay()`) so effects already carried by the replay are not doubled.

## Load limits (protection against effect spam)

| Constant | Value | Where |
|---|---|---|
| `MAX_ACTIVE_EFFECTS` | 64 | `VFXEffectManager` — on overflow the oldest active is removed, with a warning in the log |
| `MAX_SCHEDULED_EFFECTS` | 128 | `VFXEffectManager` — extra collection children are dropped |
| `MAX_COLLECTION_DEPTH` | 4 | `VFXEffectManager` — deeper nested collections are ignored |
| `MAX_TRACKED_PLAYERS` | 256 | `VFXServerEffects` — distinct players with remembered effects; the least recently touched is evicted |

Any new collection/map that grows from network or datapack input must get a similar limit.

## Fault tolerance

- `VFXDefinitionManager.prepare()` — one broken datapack entry is logged and skipped, the rest load normally (see the [Changelog](CHANGELOG.md)).
- `VFXWorldOverlayRenderer.render()` — each effect's render is wrapped in try/catch with a log; an error in one effect does not block the rest or drop the frame.
- `VFXClient.handleTrigger` — a packet with a mismatched `protocolVersion` is silently ignored instead of crashing.

---
See also: [API.md](API.md) — the public Java API and network protocol, [guide/](guide/index.md) — the user guide.

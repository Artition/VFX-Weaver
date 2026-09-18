# AGENTS.md

Instructions for AI agents (Claude, Copilot, etc.) working in this repository. Read this before
touching code — most mistakes here were made by ignoring one of the rules below.

## What this project is

A client-side Fabric VFX library/API for Minecraft (screen post-processing, camera effects, world
overlays, entity effects, datapack-defined effects, a server→client trigger and a public Java API
for other mods). Java sources are split by side:

```
src/main/java/dev/vfxweaver/            shared (both sides): Java API, commands, datapack model,
                                        network payloads, expr evaluator, noise
src/client/java/dev/vfxweaver/client/   client only: renderers, post-processing, shaders, mixins,
                                        camera shake, Iris/Flashback compatibility
src/main/resources/                     fabric.mod.json, vfxweaver.mixins.json, access wideners,
                                        built-in effects (data/vfxweaver/vfx/*.json), lang
src/client/resources/                   vfxweaver.client.mixins.json, shaders
versions/<mc>/gradle.properties         per-node dependencies (see "Multi-version")
docs/                                   GUIDE.md (user guide), API.md, ARCHITECTURE.md, CHANGELOG.md
.github/workflows/build.yml             CI: builds every node; publishes the release on `v*` tags
```

The domain model (effects, timelines, datapacks, network protocol, API) is described in
`docs/GUIDE.md` and `docs/API.md`; internal design notes are in `docs/ARCHITECTURE.md`.

## Build and verify

```bash
./gradlew :26.2:build :26.1.2:build :1.21.11:build     # Fabric nodes
./gradlew :26.2-neoforge:build                         # NeoForge node (ModDevGradle)
./gradlew :<node>:build                                # one node
./gradlew :<node>:runClient                            # dev client for a node
```

**Multi-loader layout:** the loader is part of the node name — `<mc>` is Fabric, `<mc>-neoforge` is
NeoForge — and each node gets its build script from `settings.gradle`: `build.fabric.gradle`
(Loom) or `build.neoforge.gradle` (ModDevGradle). Those two tracked scripts also declare the
Stonecutter constants `fabric` / `neoforge` used by `//? if fabric { … //?} else { … //?}` guards;
`stonecutter.gradle` (which node is active) is **local-only and git-ignored**, so never put shared
configuration there. Guarded sources are materialised under
`versions/<node>/build/generated/stonecutter/…` when a guard looks wrong. First NeoForge build
decompiles Minecraft via NFRT (~2 min here, cached afterwards).

`26.2` and `26.1.2` target Java 25, `1.21.11` targets Java 21; one JDK 25+ (e.g. 26) builds all of
them via `--release` (see `build.gradle`). `error: release version 25 not supported` means Gradle
picked up the wrong JDK, not a code bug.

**Definition of done:** every node builds (`BUILD SUCCESSFUL`). Never commit a change that only
compiles for the node you happen to be looking at — the shared source multiplies by three.

There is **no test suite** (`test NO-SOURCE`). Verify in this order:

1. **Build all nodes.**
2. **Compile-time-visible behaviour** — for MC-free classes (`MathExpression`, `EasingFunction`,
   `VFXTimeline`, …) compile a throwaway `main()` against the built classes and assert:
   `javac -cp versions/<node>/build/classes/java/main -d <tmp> Check.java` then
   `java -cp "versions/<node>/build/classes/java/main;<tmp>" Check` (use JDK 25+;
   `C:\Program Files\Java\jdk-26` had one on the dev box).
3. **Mixins** — the build does **not** validate mixin targets (no refmap on 26.x: "No refMap
   loaded"), so a wrong target crashes at game start. Verify statically against the node's real jar:
   `javap -classpath <node deobf jar> <TargetClass>` and compare the exact descriptor/call site.
   Deobf jars live in `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/*-deobf/<mc>/`.
4. **Runtime** — in-game testing is the only way to confirm rendering; ask the user to test rather
   than claiming a visual change works.

## Multi-version (Stonecutter)

Supported nodes: **`26.2`, `26.1.2`, `1.21.11`**. Shared source lives in `src/`; per-node values in
`versions/<mc>/gradle.properties`:

| property | meaning |
|---|---|
| `deps.minecraft` | Minecraft version built against |
| `deps.loader` | Fabric Loader used for compile/dev |
| `deps.loader_compat` | expanded into `fabric.mod.json` `fabricloader` (the floor users may run) |
| `deps.mc_compat` | expanded into `fabric.mod.json` `minecraft` (the supported range) |
| `deps.fabric_api` | Fabric API version |
| `deps.java` | `--release` / `java` requirement |

`build.gradle` expands those into `fabric.mod.json` and `*.mixins.json`
(`${version}`, `${minecraft}`, `${mcCompat}`, `${loaderCompat}`, `${java}`, `${compatibilityLevel}`,
`${accessWidener}`) and picks per node: the Loom variant (`>=26.1` is **unobfuscated** and uses
`net.fabricmc.fabric-loom`; `<26.1` is remapped and uses `fabric-loom`), the access widener
(`vfxweaver.accesswidener` official for `>=26.1`, `vfxweaver-named.accesswidener` for `<26.1` — they
differ only in namespace, only one is shipped) and the resource excludes.

Guarding rules:

- Write a feature once in `src/`; guard only the statements that differ, in place:
  `//? if <cond { … //?} else { /* … */ //?}` (nested guards work).
- The **active node is `26.1.2`** (`stonecutter.gradle`): the on-disk text is the 26.1.2 form, the
  `1.21.11`/`26.2` branches are the commented ones. When you edit a guarded block, make sure you
  edited the right side — the active branch is what compiles now.
- Real condition boundaries in use right now: `<26.1` / `>=26.1` (the remapped 1.21.11 node versus the
  unobfuscated 26.x nodes) and `<26.2` / `>=26.2` (26.1.2 versus 26.2 API deltas). If you add a node
  whose API splits differently, introduce a boundary that matches it and keep the older ones intact.
- Never fork a whole file per version, and never add a node without adding it to
  `.github/workflows/build.yml` (the matrix is what ships release jars).

## Version-specific API deltas (the part that bites)

Differences found while porting; re-verify with `javap` before trusting these:

| 26.1.2 | 26.2 |
|---|---|
| `Builder.withSampler(String)` / `withUniform(String, UniformType)` | `withBindGroupLayout(BindGroupLayouts.X)` / composed `BindGroupLayout`s |
| `Builder.withVertexFormat(fmt, VertexFormat.Mode.QUADS)` | `withVertexBinding(0, fmt).withPrimitiveTopology(PrimitiveTopology.QUADS)` |
| `GpuBuffer.MappedView`, `encoder.mapBuffer(buf, …)` | `GpuBufferSlice.MappedView`, `buf.map(…)` |
| `new TextureTarget(label, w, h, depth)` | `new TextureTarget(label, w, h, depth, GpuFormat.RGBA8_UNORM)` |
| `new ColorTargetState(BlendFunction.X)` | `new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, WRITE_NONE)` |
| `RenderPass.draw(0, 3)` | `RenderPass.draw(vertexCount, instanceCount, firstVertex, firstInstance)` |
| `GameRenderer.getMainCamera()` / `Minecraft.getMainRenderTarget()` | `gameRenderer.mainCamera()` / `gameRenderer.mainRenderTarget()` |
| `MultiBufferSource(.BufferSource)`, `LevelRenderContext.bufferSource()` | `SubmitNodeCollector`, `LevelRenderContext.submitNodeCollector()` |
| depth test `CompareOp.LESS_THAN_OR_EQUAL` | **reversed depth** → `GREATER_THAN_OR_EQUAL` for the same occlusion |
| `ItemInHandRenderer.renderHandsWithItems(...)` | `submitHandsWithItems(...)` (same descriptor) |

`1.21.11` vs `26.x`: `WorldRenderEvents` (`<26.1`, END_MAIN/BEFORE_ENTITIES) versus
`LevelRenderEvents` (`>=26.1`, AFTER_TRANSLUCENT_TERRAIN/COLLECT_SUBMITS); the obfuscated node needs
the remapping Loom. All three supported nodes use `Identifier` (the old `ResourceLocation` name is
gone — no Stonecutter replacement is needed anymore).

### NeoForge 26.2 API (verified against `neoforge-26.2.0.84`)

Verified with `javap`/sources on the real jars — do not guess these, re-verify with the same method
when a NeoForge line changes. Loader-specific code lives **only** in `dev.vfxweaver.platform`
(and `client.platform`); everything else stays loader-agnostic.

| Concern | Verified signature / name |
|---|---|
| Mod entry | `@Mod(value = "vfxweaver", dist = Dist[])` (`net.neoforged.fml.common.Mod`); entry ctor takes `IEventBus` |
| Mod loaded | `net.neoforged.fml.ModList.get().isLoaded(String)` |
| Event subscription | `@EventBusSubscriber(value = Dist[], modid = "...")` (`net.neoforged.fml.common`) or `bus.addListener(...)` |
| Payload registration | `RegisterPayloadHandlersEvent.registrar(String version) -> PayloadRegistrar`; `playToClient/playToServer(type, codec, handler)`, `playBidirectional(...)`, `.optional()` for our server-optional model |
| Off-thread work | `IPayloadContext.enqueueWork(Runnable)` |
| Send | `PacketDistributor.sendToPlayer(ServerPlayer, payload)`, `sendToAllPlayers(payload)` |
| Server tick / lifecycle | `ServerTickEvent.Post`, `ServerStartedEvent`, `ServerStoppingEvent` (all expose `getServer()` via `ServerLifecycleEvent`) |
| Player join | `PlayerEvent.PlayerLoggedInEvent` (`getEntity()` returns the player) |
| Commands | `RegisterCommandsEvent.getDispatcher()` / `.getBuildContext()` |
| Argument type | `RegisterEvent.register(Registries.COMMAND_ARGUMENT_TYPE, Identifier, Supplier<ArgumentTypeInfo>)` (NeoForge: `ArgumentTypeInfos.registerByClass`) |
| Reload listener | `AddServerReloadListenersEvent.addListener(Identifier, PreparableReloadListener)` |
| Client tick / network | `ClientTickEvent.Post`, `ClientPlayerNetworkEvent.LoggingIn`/`LoggingOut` |
| World overlays (26.2) | `RenderLevelStageEvent.AfterTranslucentBlocks` (+ `getPoseStack()`, `getLevelRenderState()`); geometry via `SubmitCustomGeometryEvent.getSubmitNodeCollector()` |
| Access transformer | `META-INF/accesstransformer.cfg`, e.g. `public net.minecraft.client.particle.Particle xd` — the five `Particle` fields are `protected double xd/yd/zd` + `protected float gravity/friction` |

## Client hooks (where things are wired)

- `GameRendererMixin` — effect clock (`manager.advance`/`update`) plus the three post-processing
  layers (layer 0 inside `renderLevel`, layer 1 and 2 in `render`); the per-node injection points
  differ, see the guards.
- `VFXWorldOverlayRenderer.register()` — world overlays (`block_tint`, `block_outline`,
  `light_beam`, `pulse_ring`, `guide_line`, `particles`, `block_chain`) on `LevelRenderEvents`
  (`>=26.1`) or `WorldRenderEvents` (`<26.1`).
- `ItemInHandRendererMixin`, `AvatarRendererMixin`, `ItemFrameRendererMixin`,
  `LivingEntityRendererMixin` (+ the two render-state mixins) — first-person/entity frame effects.
- `CameraMixin` — FOV; `VFXPostProcessingManager` + `VFXShaderPrograms` — the pass chain (one shared
  implementation for all nodes; no per-version branch on `main`); shaders live in
  `assets/vfxweaver/shaders/post/*` (screen passes) and `core/*` (world/entity geometry). A post
  shader's parameter block is a std140 UBO: the fields in the shader's `Config` block and the names
  in `VFXShaderPrograms.registerPost(...)` must be the **same set in the same order** (the offsets
  are positional, so a mismatch silently shifts values; the uniform *name* must match too or MC logs
  "Found unknown but potentially supported uniform …" at startup). Adding a parameter therefore means
  editing the shader, the `registerPost` list and the built-in JSON together. **Never name a uniform
  after a GLSL built-in** (`length`, `mix`, `step`, `mod`, `clamp`, …): a uniform called `length`
  shadows the built-in `length()` and the shader silently fails to compile, after which MC drops
  every resource pack and the game shows a black screen. The unknown-uniform warning from a
  param/uniform name mismatch is cosmetic - UBO values are written positionally - so prefer keeping
  the shader's own name over "fixing" it by renaming.
- `FlashbackCompat` — records plays, stops **and live edits** into Flashback replays through **one**
  custom action (`vfxweaver:effect_trigger`): a real duration is a play, `-2` a stop, `-3`/`-4`/`-5`
  set-param/keyframe/set-expr (see `ACTION_*`), and the datapack definitions snapshot is marked by
  the reserved `vfxweaver:definitions` id as the first payload field. Register **exactly one**
  action: Flashback keys its registry by the action class and two reflection proxies over the same
  interface share one generated class, so a second `register` throws `Action already registered`
  and the whole init aborts (recording silently stops working). Recording is wired on both paths —
  the local API (`VFXClientAPI`) and the network receiver (`VFXClient`) — so anything the network
  can do is replayed too. When you add a live edit, record it here as well and keep old recordings
  decodable (append fields, or add a new sentinel; never reorder the existing payload).

## Logging

- Per-request/per-frame messages are `DEBUG`. `INFO` is reserved for once-per-session or
  per-reload summaries (`client initialized`, `Loaded N effect definitions`, **not** "effect
  started").
- A warning that a caller could repeat every tick must go through
  `VFXLog.warnOnce(logger, key, message, args)` (bounded key set) — never a bare `LOGGER.warn` on a
  hot path. An integration firing `sendSetParam` every tick must not be able to flood the log.

## Public API and datapack surface (do not break)

- `VFXAPI` (`docs/API.md`): server network triggers (`sendEffect`/`sendStop`/`sendSetParam`/…),
  client-local playback and live control (`playEffect`/`playEffectId`/`moveEffect`/`setParam`/
  `setParamExpr`/`setKeyframe`/`stopEffect` — everything the network does also works locally, so a
  pure client-side mod never needs a server), `registerDefinitions`/`unregisterDefinition` (see the
  definition-layer rule below), the fluent `EffectRequest`, and the `VFXLocalDispatcher` bridge the
  client registers (every new network action needs its counterpart there).
- `VFXDefinitionManager` keeps **two layers**: the datapack/server set (`definitions`, replaced by
  `apply` on `/reload` and by `applySynced` on a server sync) and the code-registered local set
  (`registerLocal`/`unregisterLocal`, written through `VFXAPI.registerDefinitions`). Never let a
  reload or a sync drop the local layer, keep `getRawDefinitions()` datapack-only (a client's local
  definitions must not be synced to other players), and keep the datapack/server layer winning for
  the same id. A new read path must consult both layers (`get`/`contains`/`getDefinitions`/
  `getParseErrors` do).
- The network protocol `vfxweaver:vfx_trigger` / `vfx_request` / `vfx_sync`:
  `VFXTriggerPayload.PROTOCOL_VERSION` must be bumped on any wire-breaking change.
- The datapack effect format `data/<namespace>/vfx/<effect>.json`: parameter specs (constant,
  `start`/`end`, `keyframes`, `bind`, `expr`, `multiply`), `positions` entries (`[x,y,z]` or
  `{"entity": "<selector>", "point": "feet|center|eyes", "dir": "none|look", "offset": […],
  "distance": n}`), `children`/collections, `sound`, `particle`/`shape`, `block`. Additive changes
  are fine; renaming or removing a field is not.
- `expr` functions live in `MathExpression`; a new function must be added in three places (name →
  id in the parser, `arity()` table, the eval `switch`) and documented in the class javadoc and
  `docs/GUIDE.md`. Argument counts are validated at compile time.

## Code style

- Indentation is tabs, not spaces.
- Method parameters and locals that are not reassigned are `final`.
- Public classes and non-trivial public methods have Javadoc (`@param`/`@return` where not obvious).
- Stateless helpers are `final class` with a private constructor (`SimplexNoise`, `VFXShaderPrograms`,
  `VFXWorldBindings`); managers are singletons with a private constructor + static `get()`
  (`VFXEffectManager`, `VFXDefinitionManager`, `VFXPostProcessingManager`).
- Every collection fed by network/datapack input is bounded by a constant
  (`MAX_ACTIVE_EFFECTS`, `MAX_SCHEDULED_EFFECTS`, `MAX_COLLECTION_DEPTH`, `VFXLog`'s key cap).
- Datapack parsing (`VFXDefinition.parse`, `VFXDefinitionManager.prepare`): catch per-file parse
  errors inside `prepare()` — one broken JSON must not take down every definition.

## What must not be broken without discussion

- The datapack effect format and the network protocol (backward compatibility; bump
  `PROTOCOL_VERSION` on a breaking change).
- The public Java API (`VFXAPI`) — other mods compile against it; add overloads, don't change
  signatures.
- The per-node `minecraft`/`fabricloader` ranges: widening a range claims support for versions we
  may not have tested; narrowing one drops users.

## Release

1. Bump `mod_version` in `gradle.properties` (+ changelog/docs, see below).
2. Commit, then tag and push: `git tag v<version> && git push origin v<version>`.
3. CI (`.github/workflows/build.yml`) builds **every node in the matrix** and publishes a GitHub
   release with the jars. If you added a Minecraft node, add it to the matrix first — otherwise its
   jar silently misses the release.
4. Modrinth is updated manually, **one version per Minecraft line** (a mixed version listing hands
   users the wrong jar): version number = `mod_version`, `game_versions` = that line only
   (`26.2` / `26.1`, `26.1.1`, `26.1.2` / `1.21.11`), file = `vfxweaver-<version>+<node>.jar`.
   Modrinth files are immutable: to change a released jar you must add the new one and delete the
   old (a version refuses to be left without files, and an upload reusing an existing file name in
   that version is rejected).

## Documentation to update with behaviour changes

- `docs/GUIDE.md` — commands/effect params/datapack fields, **and** its changelog at the bottom
  (`### vN` entries).
- `docs/API.md` — Java API / packet layout.
- `docs/CHANGELOG.md` — the release entry (keep it to what users see).
- `README.md` (Requirements table) and the Modrinth project body when the supported Minecraft /
  Fabric Loader ranges change.
- Commit and branch conventions: `CONTRIBUTING.md`.

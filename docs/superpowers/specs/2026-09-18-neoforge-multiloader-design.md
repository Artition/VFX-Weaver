# NeoForge multi-loader port — design

Status: approved for implementation planning (spike-first, stage S0 approved 2026-09-18).

## Goal

Ship the same mod (same features, same datapack format, same public `VFXAPI`, same multi-version
support) for **NeoForge** alongside **Fabric**, from one source tree, without forking files per
loader.

Target matrix after the port (6 jars):

| MC | Fabric | NeoForge |
|---|---|---|
| `26.2` | `vfxweaver-<ver>+26.2.jar` (existing) | `vfxweaver-<ver>+26.2-neoforge.jar` |
| `26.1.2` | `vfxweaver-<ver>+26.1.2.jar` (existing) | `vfxweaver-<ver>+26.1.2-neoforge.jar` |
| `1.21.11` | `vfxweaver-<ver>+1.21.11.jar` (existing) | `vfxweaver-<ver>+1.21.11-neoforge.jar` |

## Current state (what has to change)

- One Stonecutter project, central script `build.gradle` (Groovy), nodes `26.2`, `26.1.2`,
  `1.21.11` in `versions/<mc>/gradle.properties`; active node `26.1.2`.
- Source layout: `src/main` (both sides) + `src/client` (client only), split by Loom's
  `splitEnvironmentSourceSets()`.
- Loader coupling is small and localised: **9 files, 24 `net.fabricmc.*` imports** —
  `VFXMod`, `VFXAPI`, `VFXPayloads`, `VFXScoreboardSync`, `VFXServerEffects`, `VFXClient`,
  `FlashbackCompat`, `VFXWorldOverlayRenderer`, plus `FabricLoader` in two of them.
- Vanilla-targeting mixins: main config has an empty list; client config has 8 client mixins.
- Access widener: 5 `Particle` fields (`xd`, `yd`, `zd`, `friction`, `gravity`).
- Everything heavy is already loader-agnostic: render pipelines, shaders, post-processing chain,
  datapack model, timeline/expr/managers, commands, 36 built-in effects.
- CI: GitHub Actions matrix over the 3 nodes, release on `v*` tags.
- Releases: Modrinth, one version per MC line, `loaders: [fabric]`.

## Feasibility findings

- **NeoForge exists for all three lines**: `26.2.0.84`, `26.1.2.x` (≥ `26.1.2.95`),
  `21.11.45` (1.21.11).
- **Stonecutter supports multi-loader natively** (`stonecutter/template-multiloader`): nodes are
  `versions/<project>-<loader>` with a logical version used by comment conditions, per-loader build
  scripts (`build.fabric.gradle` / `build.neoforge.gradle`), centralised per-version × per-loader
  properties (`stonecutter.properties.toml`), and `//? if fabric { } //? if neoforge { }` guards
  driven by a `constants { match(loader, "fabric", "neoforge") }` declaration.
- **NeoForge has equivalents for every render hook we use** (26.2): `RenderLevelStageEvent`
  (stages `AfterSky`, `AfterOpaqueBlocks`, `AfterOpaqueFeatures`, `AfterTranslucentFeatures`,
  `AfterTranslucentBlocks`, `AfterTranslucentParticles`, `AfterWeather`, `AfterLevel`),
  `SubmitCustomGeometryEvent` (exposes `SubmitNodeCollector` + `PoseStack`, i.e. the same
  submission API our `>=26.2` overlay path already uses), `ExtractLevelRenderStateEvent`.
- NeoForge build plugin is **ModDevGradle** (`net.neoforged.moddev` 2.0.14x, requires Gradle ≥ 8.8;
  we run 9.5.1). First local build decompiles/recompiles Minecraft (slow, up to an hour); MDG skips
  it when `CI=true`, so CI stays reasonable.

## Approaches considered

- **A. Adopt the official Stonecutter multi-loader layout** (chosen). One source tree, 6 nodes,
  two build scripts, platform package with `//? if fabric/neoforge` guards. Proven upstream, keeps
  the existing "one codebase" rule and the existing per-node `gradle.properties` idea.
- **B. Two independent Gradle builds** (root stays Fabric, `neoforge/` is a second Stonecutter
  build wired to `../src`). Avoids plugin classpath risk but duplicates build config and
  Stonecutter state, and diverges from the supported path.
- **C. Real SPI modules** (`core/` + `fabric/` + `neoforge/` subprojects, no cross-loader guards).
  Cleanest boundaries, most work; still needs two Stonecutter builds for MC versions, so it is B
  with extra ceremony.

A is chosen: least new machinery, and the risky part (build tooling) is copied from a maintained
template rather than invented.

## Design

### Layout and nodes

- Keep the Fabric node directories and names unchanged (`versions/26.2`, `26.1.2`, `1.21.11`) so
  existing tasks (`:26.2:build`), docs and CI entries keep working. Add NeoForge nodes
  `versions/<mc>-neoforge` with logical version `<mc>`:

  ```groovy
  stonecutter {
      create(rootProject) {
          ['26.2', '26.1.2', '1.21.11'].each { v ->
              version(v, v).buildscript('build.fabric.gradle')
              version("${v}-neoforge", v).buildscript('build.neoforge.gradle')
          }
      }
  }
  ```

- `build.fabric.gradle` = today's `build.gradle` (Loom variant by MC version, AW, split source
  sets). `build.neoforge.gradle` = MDG 2.0.14x, `neoForge { version = deps.neo_loader; mods { ... };
  runs { ... } }`, JAVA toolchain per node (25/25/21).
- Per-version values move to the existing `versions/<mc>/gradle.properties` (Fabric fields stay)
  plus NeoForge fields (`deps.neo_loader`, `deps.mc_compat_neo`); if Stonecutter's centralised
  `stonecutter.properties.toml` proves cleaner, adopt it for the NeoForge side only.
- Jar names: `base.archivesName = 'vfxweaver'`, `version = "${mod_version}+${node}"` → Fabric jars
  unchanged, NeoForge jars `vfxweaver-<ver>+<mc>-neoforge.jar`.

### Platform layer (the only new abstraction)

New package `dev.vfxweaver.platform` in `src/main`, loader-agnostic interface + one guarded
implementation file per concern. Core code calls these; no core file imports a loader API.

- `VFXPlatform` — `isModLoaded(String)`, payload sending (`sendToPlayer`, `sendToAll`,
  `sendToTracking`/lookup-by-entity), and any other one-line loader query.
- `VFXNetwork` — payload type registration + receive dispatch (server and client).
- `VFXLoaderEvents` — lifecycle wiring: server start/stop/tick, player join, datapack/resource
  reload, command + argument-type registration, client start/tick/join/disconnect.
- `VFXClientRenderHooks` — world render events (the three post-processing layers stay in
  `GameRendererMixin`; only the world-overlay event registration moves here).

Rewrites are mechanical: replace the Fabric call in the 9 files with the platform call, and put the
loader-specific bodies inside `//? if fabric { } //? if neoforge { }` in the platform package only.

### Mapping table (verify each name against the NeoForge jar with `javap` while porting)

| Concern | Fabric (today) | NeoForge 26.x |
|---|---|---|
| Mod entry | `ModInitializer` / `ClientModInitializer` | `@Mod("vfxweaver")` ctor; client entry `@Mod(value = "vfxweaver", dist = Dist.CLIENT)` |
| Payload registration | `PayloadTypeRegistry.playS2C()/playC2S()` | `RegisterPayloadHandlersEvent` + `registrar.playToClient/playToServer` |
| Send to player / all | `ServerPlayNetworking.send`, `PlayerLookup` | `PacketDistributor.sendToPlayer/sendToAllPlayers/sendToPlayersTrackingEntity…` |
| Receive (client) | `ClientPlayNetworking.registerGlobalReceiver` | payload handler + `IPayloadContext.enqueueWork` (handler runs off the render thread) |
| Server tick | `ServerTickEvents.END_SERVER_TICK` | `ServerTickEvent.Post` |
| Server start/stop | `ServerLifecycleEvents.SERVER_STARTED/STOPPING` | `ServerStartedEvent` / `ServerStoppingEvent` |
| Player join | `ServerPlayConnectionEvents.JOIN` | `PlayerEvent.PlayerLoggedInEvent` |
| Commands | `CommandRegistrationCallback` | `RegisterCommandsEvent` |
| Argument type | `ArgumentTypeRegistry` | register the `ArgumentTypeInfo` in the vanilla command-argument registry via a NeoForge register event |
| Datapack reload | `ResourceLoader.registerReloadListener` | `AddReloadListenerEvent` |
| Client start | `ClientLifecycleEvents.CLIENT_STARTED` | client `@Mod` constructor / `FMLClientSetupEvent` |
| Client tick | `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` |
| Client join/leave | `ClientPlayConnectionEvents.JOIN/DISCONNECT` | `ClientPlayerNetworkEvent.LoggingIn/LoggingOut` |
| World overlays (26.2) | `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` | `RenderLevelStageEvent.AfterTranslucentBlocks` (+ `SubmitCustomGeometryEvent` for geometry) |
| World overlays (1.21.11) | `WorldRenderEvents.END_MAIN` + `BEFORE_ENTITIES` | `RenderLevelStageEvent` (enum-stage flavour of that NeoForge line) |
| Mod loaded | `FabricLoader.getInstance().isModLoaded` | `ModList.get().isLoaded` |
| Access widener | `*.accesswidener` (named/official) | `META-INF/accesstransformer.cfg` (same 5 `Particle` fields) |

### Resources and metadata

- `src/main/resources` stays shared. Add `META-INF/neoforge.mods.toml` next to `fabric.mod.json`;
  each loader's `processResources` expands its own file and `exclude`s the other's (as the upstream
  template does).
- Mixin configs are shared as-is (client config keeps its `"client"` list, so it stays client-only);
  `neoforge.mods.toml` references both via `[[mixins]]`.
- `vfxweaver.accesswidener` stays for Fabric; add `META-INF/accesstransformer.cfg` for NeoForge,
  each excluded from the other loader's jar.
- Dependency metadata per loader: Fabric keeps `fabricloader`/`fabric-api`; NeoForge declares
  `neoforge` + `minecraft` in Maven range syntax (`[26.2,26.3)`, `[26.1,26.2)`, `[1.21.11,1.22)`),
  optional deps `flashback`/`iris` become `type = "optional"` suggestions.
- `src/main/resources/data/**` (datapack + built-in effects) and the shaders are untouched.

### What explicitly does not change

Datapack format, network protocol (`PROTOCOL_VERSION`), public `VFXAPI` signatures, `expr`
functions, all effect behaviour, the Fabric artifacts and their names, the existing AW, the mixin
targets. The NeoForge port is additive.

## Staging (each stage ends with a build + a verification step)

- **S0 — spike, `26.2-neoforge` only (approved first step).** Stonecutter multi-loader setup +
  `build.neoforge.gradle` + `META-INF/neoforge.mods.toml` + AT + platform layer skeleton for the
  paths needed to launch, and one visible path end-to-end (post-processing layer + one screen
  effect, e.g. `speed_lines`, plus one world overlay if cheap).
  Exit: `:26.2-neoforge:build` green, mod loads in a NeoForge `26.2` instance, the effect renders,
  log shows the datapack effects loaded. This is where the build-tooling risk is retired.
- **S1 — full glue on 26.2.** Networking both directions (play/stop/set/keyframe/sync/scoreboard),
  commands + argument type, resource reload, client tick/lifecycle, Flashback compat
  (`isModLoaded` + tick), the three post layers, entity/hand/item mixin paths, world overlays.
  Exit: feature parity checklist on NeoForge 26.2 (server ↔ client trigger, datapack reload,
  replay recording, /vfx commands).
- **S2 — other lines.** Add `26.1.2-neoforge` and `1.21.11-neoforge` (guards for the NeoForge
  event/API differences of those lines), wire their `gradle.properties`, CI matrix, and verify
  in-game on each.
- **S3 — docs, CI, releases.** `AGENTS.md` (multi-loader rules), `README.md` (loader table),
  `docs/API.md` (NeoForge artifact + how a companion mod depends on it), `GUIDE.md`, CHANGELOG;
  CI matrix over 6 nodes; GitHub release; Modrinth NeoForge versions per MC line.

## Verification strategy

1. All nodes build (`BUILD SUCCESSFUL`), 6 of them after S2.
2. Static checks before running: `javap` against the NeoForge dev jar for every mixin target,
   every event class/method and every registry call (same discipline as the 26.x mixin check).
3. In-game on a NeoForge PrismLauncher instance (user-run): effects render, commands work, client
   and server paths, dedicated-server-safe startup (client classes must not load server-side).
4. No test suite exists in the repo; this does not add one (MC-bound code, verified in game).
5. Regression guard for Fabric: the existing Fabric nodes must still build and behave exactly as
   before each stage (S0/S1 must not touch Fabric-only behaviour).

## Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Loom + MDG + Stonecutter in one build conflict on the plugin classpath | Follow the upstream template's split-buildscript layout; if it still fails, fall back to approach B (two builds) for the NeoForge side only |
| R2 | Wrong/renamed NeoForge event or registry API | `javap`-verify every symbol against the NeoForge jar before writing glue; the mapping table is a starting point, not gospel |
| R3 | Client classes loaded on a dedicated server | keep the `src/client` split, gate client entry/events with `dist = Dist.CLIENT`, verify a dedicated-server start |
| R4 | Networking thread semantics (NeoForge handlers run off-thread) | route client receives through `ctx.enqueueWork`; server receives through the server thread |
| R5 | Visual differences in rendering between loaders (pipeline/state assumptions) | render paths are vanilla APIs on 26.x; verify in game per stage, per overlay and per post layer |
| R6 | First NeoForm decompile is slow locally, CI heavy | accept locally; rely on MDG's CI fast path (`CI=true`), keep the matrix `fail-fast: false` |
| R7 | Modrinth listing confusion (loader per version) | one version per MC line per loader, names `…+<mc>-neoforge`, `loaders: [neoforge]` |

## Non-goals

- No Forge (legacy), no Quilt-specific port, no Architectury/Modstitch abstraction layer unless
  approach A fails and B is insufficient.
- No feature changes, no protocol/datapack changes, no refactoring beyond the platform layer.
- No new mixins; no change to the Fabric artifacts' structure or names.

## Open questions

- NeoForge `1.21.11` line: verify `RenderLevelStageEvent`'s shape there (enum stages vs subclasses)
  before S2; it affects only `VFXClientRenderHooks`.
- Whether to adopt the template's `stonecutter.properties.toml` for all nodes (nicer for
  per-loader compat ranges) or keep `versions/<node>/gradle.properties` (less churn). Decide in S0
  after seeing how the build scripts read properties.
- `.gitignore` currently ignores `stonecutter.gradle`; the multi-loader setup adds
  `build.fabric.gradle` / `build.neoforge.gradle` that must stay tracked — check how the existing
  file is tracked and make the new files tracked explicitly.

# Multi-version restructure (Stonecutter) — Design Spec

Status: **approved design, not implemented**.
Scope of this spec: **Minecraft 26.1.2 (baseline/current) + 1.21.11**. Minecraft 1.21.1 is explicitly deferred (§12), but the structure is chosen so it fits later without rework.
Target repository: `Artition/VFX-Weaver` (local path `D:\кодики\AI_place\TOMposteffects_2\TOMvfx`), branch `multiversion`.

## 1. Goal

Keep **one source tree** that builds the same mod for several Minecraft/Fabric versions, so that:

1. a **new effect/feature is written once** and appears on every supported version — not re-implemented per version;
2. version differences live in a **small, explicit, documented place** instead of scattered `if (version)` branches;
3. porting to a new version or adding a new one is mechanical.

Non-goals: multi-loader (NeoForge/Forge). This stays Fabric-only.

## 2. Decision: Stonecutter (single source + preprocessor)

Use **Stonecutter 0.9.8** (Gradle plugin, `dev.kikugie.stonecutter`) in the official single-source model. Alternatives rejected:

| Option | Why rejected |
|---|---|
| Separate branch per version | Each change must be merged into every branch — exactly the pain we are removing. |
| `common` + per-version source sets (manual Gradle) | Every feature must be wired through all versions by hand; more boilerplate, worse for "add once". |
| Architectury / multi-loader | Solves loaders, not game versions; unnecessary complexity. |

Stonecutter features that make this work (verified against its docs):

- **Versioned subprojects** — `versions/<v>/`, one Gradle node per target; shared code in `src/`.
- **Global string replacements** — reversible, whole-file find/replace, e.g. `Identifier` ⇄ `ResourceLocation`. Removes hundreds of per-line guards.
- **Swaps / local replacements** — substitute a value/fragment per version (method calls, signatures).
- **Versioned source overrides** — Stonecutter generates the per-node processed sources under `versions/<v>/build/generated/`; dedicated override directories (`versions/<v>/src/…`) are an optional feature whose availability is confirmed in phase 0.
- **`//? if` conditions** — inline guards, from a single line up to a whole-file closed scope (the mechanism for a fully divergent implementation, §5 Tier C).
- **Per-node `gradle.properties`** — dependency/Java level per version.
- **Resource processing** (`processResources` + `expand`) — metadata, access widener and mixin `compatibilityLevel` per version.

## 3. Version matrix

| Node (`versions/<v>/`) | Minecraft | Loader | Fabric API | Java | Notes |
|---|---|---|---|---|---|
| `26.1.2` | `26.1.2` | `0.19.5` | `0.155.3+26.1.2` | 25 | baseline |
| `1.21.11` | `1.21.11` | `0.19.5` | `0.141.6+1.21.11` | 21 | same `Identifier` API (verified) |

Loom is a **single value for the whole build** (`deps.loom` in the root `gradle.properties`), not per node: Stonecutter subprojects share one Gradle plugin classpath, so two Loom versions cannot coexist in one build. Use the newest Loom (`1.17-SNAPSHOT`) — Loom is backward-compatible with older Minecraft and must support `1.21.11`. Phase 0 proves this with a trivial build of both nodes before any port code is written; if `1.17-SNAPSHOT` cannot target `1.21.11`, the fallback is to split the build (separate Gradle builds per version) — a structural change, so it is verified first.

Java *is* per node (`deps.java` → `options.release` / `sourceCompatibility`): `25` for 26.1.2, `21` for 1.21.11.

## 4. Repository layout

```
TOMvfx/
├─ settings.gradle                  # stonecutter plugin + versions(...)
├─ build.gradle                     # shared controller: deps, java, replacements, swaps, loom, processResources
├─ gradle.properties                # mod.id / mod.version / group (version-independent)
├─ versions/
│  ├─ 26.1.2/gradle.properties      # deps.minecraft / deps.loader / deps.fabric_api / deps.java
│  └─ 1.21.11/gradle.properties
├─ src/main/…                       # SHARED (written against 26.1 API)
├─ src/client/…                     # SHARED
└─ versions/<v>/build/generated/…   # Stonecutter-generated processed sources (gitignored)
```

The current `src/main` + `src/client` move to the root `src/` unchanged; `build.gradle`/`settings.gradle` are rewritten as Stonecutter controller files. Version-specific divergence is expressed **inside** the shared `src` with file-level `//? if` guards (see §5); Stonecutter writes the per-node processed copy under `versions/<v>/build/generated/`. Whether the optional versioned override directories (`versions/<v>/src/…`) are available is confirmed in phase 0; the design does not depend on them.

## 5. Seam model

Version coupling is classified into three tiers. The rule of thumb: **A** never changes, **B** is handled by build-script replacements/swaps, **C** is a whole-file version guard (not needed for 1.21.11).

### Tier A — version-independent (shared, no changes)

`effect/*` — `AnimatedValue`, `BoundParam`, `EasingFunction`, `EasingType`, `Keyframe`, `MathExpression`, `VFXEffectType`, `VFXTimeline`, `VFXWorldBindings`, `VFXActiveEffect`, `VFXCurve*`, `VFXServerEffects` (MC use is `ServerPlayer`/`Vec3` only); `noise/SimplexNoise`; `network/VFXAction`; `command/ParamMapArgument`; `client/access/IVFXWeaverEntityState`; `client/noise/VFXNoise`; **all `data/vfxweaver/vfx/*.json`**; most assets/lang.

### Tier B — renames / small signature differences (shared + build-script rules)

| File | Divergence 1.21.11 vs 26.1.2 |
|---|---|
| ~~every file with `net.minecraft.resources.Identifier`~~ | **Corrected by implementation evidence:** 1.21.11 already uses `Identifier`; no rename is needed (no replacement). |
| `network/VFXPayloads` | `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()` → **`playS2C()/playC2S()`** |
| `VFXMod`, `resource/VFXDefinitionManager`, `effect/VFXCurveManager` | `ResourceLoader.get(...).registerReloadListener(id, listener)` → **`ResourceManagerHelper.get(...).registerReloadListener(listener)`** (v0), listener implements `IdentifiableResourceReloadListener` |
| ~~permission API (`net.minecraft.server.permissions.*`)~~ | **Corrected by implementation evidence:** 1.21.11 already has the permission API; no change. |
| `network/VFXTriggerPayload` / `VFXSyncPayload` / `VFXRequestPayload` | no change (`Identifier` and codecs are identical) |
| `VFXMod` | `ResourceLoader`/`ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS` signatures (verify) |
| `client/VFXClient(API)` | `Identifier` rename; scoreboard API |
| `api/*`, `resource/VFXDefinitionManager` | `Identifier` rename |

The per-node `build.gradle` invariant means replacements and swaps are **already per node** (the controller runs once per node). Two safety rules:

- A replacement can be given an **identifier** and disabled inside a specific file (`replacements.string(cond, 'ident') { … }` + `//~ !ident`), so a file that must not be touched opts out explicitly.
- One-off critical signatures use **swaps** instead of text replacements, to avoid accidental matches. Concrete swaps for 1.21.11:
  - `VFXCommand` / `VFXPayloads`: `source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))` → `source.hasPermission(2)`.
  - `VFXCommand`: `CommandSourceStack.sendSuccess(...)` argument shape → the 1.21.11 overload.
  - Any `import net.minecraft.server.permissions.*` → removed for 1.21.11.

Two `//? if` guards (no global replacement, no swaps) handle the whole main side; no file duplication. Phase 1 verified `Identifier` and the permission API are identical on both targets.

### Tier C — divergent implementation (whole-file `//? if`)

A file whose entire body differs is wrapped in a closed file-level condition (`//? if <cond {` … `//?}`); the alternative implementation can live in a sibling file with the inverse condition, so exactly one is active per node. Not required for 1.21.11 (see §7 for what must be verified). Reserved for 1.21.1: `VFXPostProcessingManager`, `VFXShaderPrograms`, `render/*`, `mixin/*`.

## 6. Feature workflow (the "add once" guarantee)

Adding a post-processing effect (the most common case) touches only shared files:

1. `VFXEffectType` — add the enum constant + `neutralValue(param)` case.
2. `VFXShaderPrograms.register()` — add the `registerPost(TYPE, "param", …)` line (this is the parameter/UBO layout).
3. `src/client/resources/assets/vfxweaver/shaders/post/<name>.fsh` — add the shader, with the uniform block.
4. `src/main/resources/data/vfxweaver/vfx/<name>.json` — the default datapack definition.
5. (optional) lang entry.

→ the effect is available on 26.1.2 **and** 1.21.11 with no per-version code. The same holds for model/timeline/datapack/command/API features (all Tier A/B).

A genuinely new *render primitive* (e.g. a new geometry pass) is the only case that needs a versioned file — and it is isolated to one Tier-C file per divergent version.

## 7. Expected 1.21.11 divergences (verify at implementation)

1.21.11 is **post-render-rewrite** — the `RenderPipeline` system landed in 1.21.5 and 1.21.11 already has pipeline-backed `RenderType`/`RenderSetup`, `LevelRenderEvents`, submit nodes and render states. It is therefore close to 26.1.2; the *legacy* `RenderType`+immediate-GL stack belongs to 1.21.1 (deferred, §12) and must not drive this design. Verify each item against the mapped jar / compiler before assuming:

- **Corrected by implementation evidence (Phase 1):** 1.21.11 already uses `net.minecraft.resources.Identifier` and already has the `net.minecraft.server.permissions.*` API — **no rename and no permission swap**. The real main-side differences are only `PayloadTypeRegistry.playS2C()/playC2S()` and `ResourceManagerHelper` reload-listener registration.
- `RenderPipelines.POST_PROCESSING_SNIPPET`, `RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET` — presence/name.
- `RenderSetup` / `RenderType.create(name, setup)` — presence/name.
- `LevelRenderEvents` (fabric-rendering-v1) — present, event/context member names.
- `SubmitNodeCollector` / `SubmitNodeStorage` / `submitModel(...)` / render-state classes (`CameraRenderState`, `LivingEntityRenderState`, `ItemFrameRenderState`) — presence and argument lists.
- `MappableRingBuffer`, `ProjectionMatrixBuffer`, `TextureTarget`, `CommandEncoder`, `RenderPass` — presence.
- mixin target signatures: `GameRenderer.render`, `Camera.calculateFov/update`, `ItemInHandRenderer.renderHandsWithItems`, `AvatarRenderer.renderRightHand/renderLeftHand`, `ItemFrameRenderer.extractRenderState/submit`, `LivingEntityRenderer.extractRenderState/submit`.
- access widener field names (`Particle.xd/yd/zd/friction/gravity`).
- `ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS` parameter list; `CommandSourceStack.sendSuccess` signature.

Anything that cannot be expressed as a replacement/swap gets a **whole-file `//? if` guard** (Tier C) — this is the escape hatch, not the default.

### Measured 1.21.11 client-render gaps (Phase 2)

`:1.21.11:compileClientJava` produces **190 errors**. 1.21.11 *does* have `RenderPipeline`,
`MappableRingBuffer` and `RenderSetup`, but not the following symbols the 26.1 client uses:

| Missing on 1.21.11 | Used by | Nature |
|---|---|---|
| `com.mojang.blaze3d.pipeline.ColorTargetState`, `DepthStencilState`, `com.mojang.blaze3d.platform.CompareOp` | post manager, entity/frame renderers, world overlay | render-pipeline builder API differs |
| `net.minecraft.client.renderer.Projection`, `ProjectionMatrixBuffer` | `VFXPostProcessingManager` | post-projection API differs |
| `CameraRenderState`, `LevelRenderContext` (Fabric) + `cardinalLighting`, `lightEngine` | `VFXWorldOverlayRenderer`, render mixins | 1.21.11 uses `WorldRenderEvents`/`WorldRenderContext` + `Camera`, not render states |
| `BakedQuad`, `BlockStateModelPart` (expected packages) | `VFXWorldOverlayRenderer` | model geometry API package/name differs |
| `getBlockStateModelSet()`, `getViewRotationProjectionMatrix(Matrix4f)`, `getOverworldClockTime()`, `DefaultVertexFormat.ENTITY`, `ItemInHandRenderer`/`AvatarRenderer` signatures | renderers + mixins | per-symbol renames/reshapes |

Conclusion: the 1.21.11 **client render layer is a real port** (~8 files), not a near-port. The shared
core, datapack and network stay identical, so the port is confined to `client/render/**`,
`client/postprocessing/**`, `client/mixin/**` and the two shake classes' `Mth` usage.

## 8. Shaders

- `26.1.2` post shaders are pipeline shaders (`.fsh` with `layout(std140) uniform Config { … }`), compiled directly from `assets/vfxweaver/shaders/post/`.
- `1.21.11` uses the same post-pipeline system; the `.fsh` files are expected to be **shared verbatim** (guarded with `//? if` only if a declaration differs). No legacy PostChain work is in scope (§12).
- `assets/vfxweaver/shaders/core/*` and `post/*` are shared resources.

## 9. Mixins and resources per version

- Mixin classes live in shared `src/client/java`; version differences are `//? if` guarded inside them, or a whole-file guard where the target method differs structurally.
- `vfxweaver.client.mixins.json` and `vfxweaver.mixins.json`: `compatibilityLevel` is injected via `processResources` (`JAVA_21` vs `JAVA_25`) and version-specific entries added by condition if needed. Optionally use **Fletching Table** for mixin registration later — not required for v1.
- `vfxweaver.accesswidener`: Stonecutter natively processes the Access Widener format (`#` comments), so a **single file** holds both variants behind `#? if` conditions (e.g. widen the `Particle` fields only where needed). No per-version aw files and no `${aw_file}` switch.
- `fabric.mod.json`: `"minecraft"`, `"java"`, `"fabricloader"` are filled from per-node properties via `processResources expand`.

## 10. Build & CI

- Local: `./gradlew build` builds all nodes; the active node for IDE/runClient is switched with Stonecutter's `Set active project to …` task (IntelliJ plugin available).
- `runClient` is available per node for smoke testing (`/vfx play …`).
- **Replacement audit** (phase 1, and whenever a replacement changes): build the 1.21.11 node, then diff the generated sources (`versions/1.21.11/build/generated/…`) against the shared `src/`. A changed line is *legitimate* only if it is a pure `Identifier`↔`ResourceLocation` token swap or one of the declared swaps; **anything else is a stray** and must be reviewed. Automated gate: `git diff --stat` over the generated tree, review every file above a small noise threshold, plus a grep for the two expected tokens. Files that must never change opt out via a replacement identifier (`//~ !ident`).
- `.github/workflows/build.yml`: matrix over `{node, java}` (`26.1.2` → JDK 25, `1.21.11` → JDK 21); Gradle comes from the pinned wrapper (currently 9.5.1, ≥ the Loom minimum); publish jars named `vfxweaver-<version>+<mc>.jar`.
- `scripts/publish-maven.ps1` stays, extended to publish per-version artifacts.

## 11. Risks / mitigations

| Risk | Mitigation |
|---|---|
| Loom 1.17-SNAPSHOT may not target 1.21.11 | One Loom per Gradle build (shared plugin classpath). Phase 0 proves it with a trivial two-node build before any port work; fallback = split into separate Gradle builds (structural, hence verified first). |
| ~~Global `Identifier`→`ResourceLocation` replacement hitting unintended text~~ | **Not needed** — 1.21.11 uses `Identifier` (Phase 1 evidence). |
| Loom 1.17-SNAPSHOT drift | It is the status quo and already proven for 26.1.2; the Gradle wrapper is pinned (9.5.1). Pin the resolved Loom once a fixed release covers both nodes. |
| 1.21.11 render API differs more than expected | Compiler reveals it; each diff becomes either a swap or a Tier-C guard. Reduced by the phase-1 API probe (§13). |
| 1.21.11 submit-node / render-state renames ripple into mixins | Add `//? if` guards or whole-file guards; area is small (8 mixins). |
| Access widener field renames | Versioned aw file selected in `build.gradle`. |
| `net.minecraft.resources.Identifier` appears in public API signatures (`VFXAPI`) | Client-facing API only uses `Identifier` as a parameter; renaming is transparent to consumers per-version. Protocol version unchanged. |

## 12. Out of scope / future

- **Minecraft 1.21.1** — deferred. It needs the legacy pre-pipeline renderer: a re-implementation of the post-processing backend (`PostChain`/`ShaderInstance` + per-effect `shaders/core/*.json` and PostChain JSON, generated from the shared `.fsh` + the `VFXShaderPrograms` parameter table), plus `WorldRenderEvents` instead of `LevelRenderEvents` and a `Camera`/`GameRenderer` mixin rewrite, and a different entity/world-overlay strategy (no render states / submit nodes). The Stonecutter structure in this spec accommodates it as Tier-C overrides plus new `versions/1.21.1/` — no rework of §4–§6.
- Adding a third+ modern version later = one `versions/<v>/gradle.properties` + version list entry.

## 13. Phases

0. **Bootstrap + toolchain proof** — add Stonecutter (`settings.gradle`, controller `build.gradle`, per-node `gradle.properties`) with an empty 1.21.11 node; prove Loom `1.17-SNAPSHOT` can configure/resolve **both** nodes (JDK 25 for 26.1.2, JDK 21 for 1.21.11). Keep `26.1.2` building and running identically. Verify: `./gradlew build` green for 26.1.2; the 1.21.11 node resolves its dependencies (e.g. `./gradlew :1.21.11:dependencies`). If Loom cannot span both — stop and escalate (structural fallback, §11).
1. **Main side 1.21.11** — add replacements/swaps; get `src/main` (API, network, command, resource) compiling. Verify: `:1.21.11:compileJava`, then run the §10 replacement audit.
1a. **1.21.11 render API probe** — compile one small render file (e.g. `VFXShaderPrograms`) against the 1.21.11 mapped jar and `javap` the jar for the submit-node/render-state/event symbols listed in §7. Converts the unknowns into facts before porting the full client, cheaply.
2. **1.21.11 client compiles** — resolve render/mixin divergences with guards/overrides. Verify: `:1.21.11:build`.
3. **Runtime parity 1.21.11** — `runClient` smoke test: post effects, camera shake, block/entity overlays, commands, datapacks, network trigger. Verify: no log errors, effects render.
4. **CI + docs** — build workflow matrix; update `docs/GUIDE.md` changelog, `README`, `AGENTS.md` (multi-version workflow section).
5. **(Later) 1.21.1** — separate spec.

## 14. Anti-patterns (never)

- Forking a whole feature file per version when a replacement/swap/guard would do.
- `if (version)` runtime branches or reflection for things the preprocessor can resolve at build time.
- Duplicating shaders per version when the body is identical.
- Adding a new unbounded collection/map without a limit (pre-existing rule, still applies).
- Diverging the datapack format or network protocol between versions — `vfxweaver:vfx_trigger` and `data/<ns>/vfx/*.json` stay identical across versions (bump `PROTOCOL_VERSION` only for real breaking changes).

## 15. Verification checklist

- `./gradlew build` green for every node.
- Jar names carry the MC version suffix; `fabric.mod.json` inside each jar has the correct `minecraft`/`java` depends.
- `runClient` on 1.21.11: `/vfx play chromatic_aberration`, a block overlay, a camera shake, and a datapack reload produce the same visible result as 26.1.2.
- `git diff` of the Stonecutter-generated 1.21.11 sources audited for stray replacements.

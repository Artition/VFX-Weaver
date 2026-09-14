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
- **Versioned source overrides** — a file in `versions/<v>/src/...` fully replaces the common file of the same path (for genuinely divergent backends; reserved for 1.21.1).
- **`//? if` conditions** — inline guards for the few places a whole-file override is overkill.
- **Per-node `gradle.properties`** — dependency/Java level per version.
- **Resource processing** (`processResources` + `expand`) — metadata, access widener and mixin `compatibilityLevel` per version.

## 3. Version matrix

| Node (`versions/<v>/`) | Minecraft | Loader | Fabric API | Java | Notes |
|---|---|---|---|---|---|
| `26.1.2` | `26.1.2` | `0.19.5` | `0.155.3+26.1.2` | 25 | baseline; `Identifier` |
| `1.21.11` | `1.21.11` | `0.19.5` | `0.141.6+1.21.11` | 21 | `ResourceLocation` |

Loom: a single Loom version compatible with both targets is used if possible; otherwise a per-node `deps.loom` (risk §11). Verify in phase 0 before writing any port code.

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
└─ versions/<v>/src/…               # per-version overrides only (empty until needed)
```

The current `src/main` + `src/client` move to the root `src/` unchanged; `build.gradle`/`settings.gradle` are rewritten as Stonecutter controller files.

## 5. Seam model

Version coupling is classified into three tiers. The rule of thumb: **A** never changes, **B** is handled by build-script replacements/swaps, **C** is a full versioned override (not needed for 1.21.11).

### Tier A — version-independent (shared, no changes)

`effect/*` — `AnimatedValue`, `BoundParam`, `EasingFunction`, `EasingType`, `Keyframe`, `MathExpression`, `VFXEffectType`, `VFXTimeline`, `VFXWorldBindings`, `VFXActiveEffect`, `VFXCurve*`, `VFXServerEffects` (MC use is `ServerPlayer`/`Vec3` only); `noise/SimplexNoise`; `network/VFXAction`; `command/ParamMapArgument`; `client/access/IVFXWeaverEntityState`; `client/noise/VFXNoise`; **all `data/vfxweaver/vfx/*.json`**; most assets/lang.

### Tier B — renames / small signature differences (shared + build-script rules)

| File | Divergence 1.21.11 vs 26.1.2 |
|---|---|
| every file with `net.minecraft.resources.Identifier` | `Identifier` → `ResourceLocation` (global string replacement, bidirectional) |
| `command/VFXCommand` | permission check (`source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))` → `source.hasPermission(2)`); `sendSuccess` supplier/flag signature |
| `network/VFXPayloads` | same permission check on the serverbound receiver |
| `network/VFXTriggerPayload` / `VFXSyncPayload` / `VFXRequestPayload` | `Identifier` rename only (`STREAM_CODEC`, `write/readIdentifier` → `…ResourceLocation`) |
| `VFXMod` | `ResourceLoader`/`ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS` signatures (verify) |
| `client/VFXClient(API)` | `Identifier` rename; scoreboard API |
| `api/*`, `resource/VFXDefinitionManager` | `Identifier` rename |

These are handled by **global string replacements** (Identifier↔ResourceLocation) and a **small set of swaps** in `build.gradle`; no file duplication.

### Tier C — divergent render stack (full `versions/<v>/src/…` override)

Not required for 1.21.11 (see §7 for what must be verified). Reserved for 1.21.1: `VFXPostProcessingManager`, `VFXShaderPrograms`, `render/*`, `mixin/*`.

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

The 1.21.11 render stack is the post-26.1-pipeline stack and is expected to be close to 26.1.2. Verify each against the mapped jar / compiler before assuming:

- `Identifier` → `ResourceLocation` (confirmed direction).
- new permission API (`net.minecraft.server.permissions.*`) → old `hasPermission(int)`.
- `RenderPipelines.POST_PROCESSING_SNIPPET`, `RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET` — presence/name.
- `RenderSetup` / `RenderType.create(name, setup)` — presence/name.
- `LevelRenderEvents` (fabric-rendering-v1) — present, event/context member names.
- `SubmitNodeCollector` / `SubmitNodeStorage` / `submitModel(...)` / render-state classes (`CameraRenderState`, `LivingEntityRenderState`, `ItemFrameRenderState`) — presence and argument lists.
- `MappableRingBuffer`, `ProjectionMatrixBuffer`, `TextureTarget`, `CommandEncoder`, `RenderPass` — presence.
- mixin target signatures: `GameRenderer.render`, `Camera.calculateFov/update`, `ItemInHandRenderer.renderHandsWithItems`, `AvatarRenderer.renderRightHand/renderLeftHand`, `ItemFrameRenderer.extractRenderState/submit`, `LivingEntityRenderer.extractRenderState/submit`.
- access widener field names (`Particle.xd/yd/zd/friction/gravity`).
- `ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS` parameter list; `CommandSourceStack.sendSuccess` signature.

Anything that cannot be expressed as a replacement/swap gets a **versioned override file** (Tier C) — this is the escape hatch, not the default.

## 8. Shaders

- `26.1.2` post shaders are pipeline shaders (`.fsh` with `layout(std140) uniform Config { … }`), compiled directly from `assets/vfxweaver/shaders/post/`.
- `1.21.11` uses the same post-pipeline system; the `.fsh` files are expected to be **shared verbatim** (guarded with `//? if` only if a declaration differs). No legacy PostChain work is in scope (§12).
- `assets/vfxweaver/shaders/core/*` and `post/*` are shared resources.

## 9. Mixins and resources per version

- Mixin classes live in shared `src/client/java`; version differences are `//? if` guarded inside them, or a versioned override where the target method differs structurally.
- `vfxweaver.client.mixins.json` and `vfxweaver.mixins.json`: `compatibilityLevel` is injected via `processResources` (`JAVA_21` vs `JAVA_25`) and version-specific entries added by condition if needed. Optionally use **Fletching Table** for mixin registration later — not required for v1.
- `vfxweaver.accesswidener`: keep one file if the widened members are identical across versions; otherwise versioned files selected in `build.gradle` (documented Stonecutter pattern) and referenced in `fabric.mod.json` via `${aw_file}`.
- `fabric.mod.json`: `"minecraft"`, `"java"`, `"fabricloader"` are filled from per-node properties via `processResources expand`.

## 10. Build & CI

- Local: `./gradlew build` builds all nodes; the active node for IDE/runClient is switched with Stonecutter's `Set active project to …` task (IntelliJ plugin available).
- `runClient` is available per node for smoke testing (`/vfx play …`).
- `.github/workflows/build.yml`: matrix over the supported nodes; publish jars named `vfxweaver-<version>+<mc>.jar`.
- `scripts/publish-maven.ps1` stays, extended to publish per-version artifacts.

## 11. Risks / mitigations

| Risk | Mitigation |
|---|---|
| A single Loom version may not support both 1.21.11 and 26.1.2 | Verify in phase 0 with a trivial build; if needed use a per-node `deps.loom`. Blocks phase 0, not later phases. |
| Global `Identifier`→`ResourceLocation` replacement hitting unintended text (comments/strings) | Replacement is word-scoped by Stonecutter string semantics; audit `git diff` of a 1.21.11 build before trusting. Use replacement identifiers to disable it in files that must not change. |
| 1.21.11 render API differs more than expected | Compiler reveals it; each diff becomes either a swap or a Tier-C override. Budgeted; no architectural impact. |
| 1.21.11 submit-node / render-state renames ripple into mixins | Add `//? if` guards or versioned mixin overrides; area is small (8 mixins). |
| Access widener field renames | Versioned aw file selected in `build.gradle`. |
| `net.minecraft.resources.Identifier` appears in public API signatures (`VFXAPI`) | Client-facing API only uses `Identifier` as a parameter; renaming is transparent to consumers per-version. Protocol version unchanged. |

## 12. Out of scope / future

- **Minecraft 1.21.1** — deferred. It needs the legacy pre-pipeline renderer: a re-implementation of the post-processing backend (`PostChain`/`ShaderInstance` + per-effect `shaders/core/*.json` and PostChain JSON, generated from the shared `.fsh` + the `VFXShaderPrograms` parameter table), plus `WorldRenderEvents` instead of `LevelRenderEvents` and a `Camera`/`GameRenderer` mixin rewrite, and a different entity/world-overlay strategy (no render states / submit nodes). The Stonecutter structure in this spec accommodates it as Tier-C overrides plus new `versions/1.21.1/` — no rework of §4–§6.
- Adding a third+ modern version later = one `versions/<v>/gradle.properties` + version list entry.

## 13. Phases

0. **Bootstrap** — add Stonecutter (`settings.gradle`, controller `build.gradle`, per-node `gradle.properties`); keep `26.1.2` building and running identically. Verify: `./gradlew build` green for 26.1.2, jar unchanged in behavior.
1. **Version list + 1.21.11 node compiles (main side)** — add replacements/swaps; get `src/main` (API, network, command, resource) compiling for 1.21.11. Verify: `:1.21.11:compileJava`.
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

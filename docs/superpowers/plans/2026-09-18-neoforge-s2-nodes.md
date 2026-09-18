# NeoForge Port — Stage S2 (26.1.2 + 1.21.11 nodes) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `26.1.2-neoforge` and `1.21.11-neoforge` Stonecutter nodes so the project produces all six jars, each building and loading on its line, without touching the Fabric nodes' behaviour.

**Architecture:** Reuse everything from S0 — the nodes differ only in per-node properties, in the NeoForge API surface of that MC line (guarded inside the existing `platform`/`client.platform` branches), and in their dependency ranges. No new abstraction.

**Tech Stack:** Stonecutter 0.9.8, ModDevGradle 2.0.147, NeoForge `26.1.2.109` / `21.11.45` (+ bump 26.2 to `26.2.0.88`), Fabric Loom 1.17 for the Fabric nodes.

**Spec:** `docs/superpowers/specs/2026-09-18-neoforge-multiloader-design.md` (stages S2/S3)
**Prior stage:** S0 plan `docs/superpowers/plans/2026-09-18-neoforge-s0-spike.md` (all tasks complete; runtime verified on 26.2)

## Global Constraints

- All **six** nodes build green: `26.2`, `26.1.2`, `1.21.11` (Fabric) and `26.2-neoforge`, `26.1.2-neoforge`, `1.21.11-neoforge`.
- Fabric artefacts and behaviour do not change (same names, same packaged file sets).
- The active Stonecutter node stays `26.1.2`: the on-disk text is the Fabric/26.1.2 form; every guarded branch is derived from it by Stonecutter. Real condition boundaries already in use: `<26.1`/`>=26.1`, `<26.2`/`>=26.2` — reuse them inside the NeoForge branches before inventing new ones.
- Loader imports stay only in `platform` / `client.platform` plus the guarded entry points; `src/main` must not reference `src/client`.
- Every new loader symbol is verified with `javap` against that line's jar **before** use (`AGENTS.md` → "NeoForge 26.2 API" documents the method).
- Public API, datapack format, network protocol and effect behaviour are unchanged.
- NeoForge metadata ranges are Maven-style and must be **per line**: Minecraft range `[26.2,26.3)` / `[26.1,26.2)` / `[1.21.11,1.22)`; NeoForge range must be a NeoForge version range (`[26.2.0.84,)`, `[26.1.2.109,)`, `[21.11.45,)`), never the MC-style range.
- Flashback does not exist for NeoForge: `FlashbackCompat` must no-op there via `VFXPlatform.isModLoaded("flashback")` (no reflection path may trigger). Document that in the guides.
- No test suite; verification = six green builds + `javap` + in-game runs.

## File Structure

- Modify: `settings.gradle` (two new node registrations), `build.neoforge.gradle` (use the per-node NeoForge/Minecraft compat properties).
- Create: `versions/26.1.2-neoforge/gradle.properties`, `versions/1.21.11-neoforge/gradle.properties`.
- Modify per node: `versions/26.2-neoforge/gradle.properties` (bump `deps.neo_loader`, add the two compat properties).
- Modify (only where a line's API differs): `src/main/java/dev/vfxweaver/platform/VFXNetwork.java`, `VFXLoaderEvents.java`, `src/client/java/dev/vfxweaver/client/platform/VFXClientRenderHooks.java`, `VFXClientNetwork.java`, `VFXNeoForgeMod.java`, `VFXClient.java`.
- Modify: `.github/workflows/build.yml` (matrix ×6), `AGENTS.md`, `README.md`, `docs/GUIDE.md` (Flashback note), `docs/CHANGELOG.md`.

---

### Task 1: Register the two nodes and their per-line dependency ranges

- [ ] Add to `settings.gradle`:
  `versions(['26.1.2-neoforge': '26.1.2']).buildscript 'build.neoforge.gradle'` and
  `versions(['1.21.11-neoforge': '1.21.11']).buildscript 'build.neoforge.gradle'`.
- [ ] `versions/26.1.2-neoforge/gradle.properties`:
  `deps.minecraft=26.1.2`, `deps.neo_loader=26.1.2.109`, `deps.neo_compat=[26.1.2.109,)`,
  `deps.mc_compat_neo=[26.1,26.2)`, `deps.loader_compat=>=26.1.2`, `deps.java=25`.
- [ ] `versions/1.21.11-neoforge/gradle.properties`:
  `deps.minecraft=1.21.11`, `deps.neo_loader=21.11.45`, `deps.neo_compat=[21.11.45,)`,
  `deps.mc_compat_neo=[1.21.11,1.22)`, `deps.loader_compat=>=21.11`, `deps.java=21`.
- [ ] `versions/26.2-neoforge/gradle.properties`: bump `deps.neo_loader` to `26.2.0.88` (matches the
  instance the runtime was verified on), add `deps.neo_compat=[26.2.0.84,)` and
  `deps.mc_compat_neo=[26.2,26.3)`.
- [ ] `build.neoforge.gradle`: expand `neoCompat = property('deps.neo_compat')` and
  `minecraft = property('deps.mc_compat_neo')` in `expandProps` (the current file expands
  `neoCompat`/`minecraft` from the Fabric-style properties).
- [ ] Verify: `.\gradlew.bat projects --console=plain` lists all six nodes; the three Fabric nodes
  still build.

### Task 2: `javap`-verify the NeoForge API per line

- [ ] For each new line, after the first `:26.1.2-neoforge:createMinecraftArtifacts` /
  `:1.21.11-neoforge:createMinecraftArtifacts` run, verify with `javap` the classes the platform
  layer uses: `net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent`,
  `network.registration.PayloadRegistrar`, `network.PacketDistributor`,
  `network.handling.IPayloadContext`, `event.tick.ServerTickEvent`,
  `event.server.ServerStartedEvent/ServerStoppingEvent`, `event.entity.player.PlayerEvent`,
  `event.RegisterCommandsEvent`, `event.AddServerReloadListenersEvent`,
  `event.OnDatapackSyncEvent`, `client.event.ClientTickEvent`,
  `client.event.ClientPlayerNetworkEvent`, `client.event.RenderLevelStageEvent`,
  `client.event.SubmitCustomGeometryEvent` (does it exist on that line?), `registries.RegisterEvent`.
- [ ] Record the differences from the 26.2 table in `AGENTS.md` (new subsection per line, or extend
  the existing table with a "line" column).

### Task 3: Guard the per-line differences

- [ ] Compile the nodes (`:26.1.2-neoforge:build`, `:1.21.11-neoforge:build`) and fix every error
  inside the guarded NeoForge branches with nested Stonecutter guards, using only the three
  condition boundaries already in use (`<26.1`, `>=26.1`, `<26.2`, `>=26.2`) plus, if a line needs
  its own boundary, a new one documented in `AGENTS.md`.
- [ ] Expected suspects (verify, do not assume): `RenderLevelStageEvent` shape on `21.11`
  (enum-stage style vs the 26.x subclasses) and whether `SubmitCustomGeometryEvent` exists there;
  `ClientTickEvent` naming; `AddServerReloadListenersEvent` availability.
- [ ] Flashback: confirm no reflection path runs on NeoForge (`VFXPlatform.isModLoaded("flashback")`
  false → `FlashbackCompat.init()` returns before touching Flashback classes).

### Task 4: Six-node build + CI matrix

- [ ] `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain` → all green.
- [ ] Inspect each NeoForge jar: metadata + AT + both mixin configs, correct `minecraft`/`neoforge`
  ranges, no Fabric files; Fabric jars unchanged.
- [ ] `.github/workflows/build.yml`: matrix ×6 with the right Java per node (25/25/21, NeoForge
  `26.2`/`26.1.2` need 25, `1.21.11-neoforge` needs 21).

### Task 5: Runtime on both new lines (user-run)

- [ ] Create/refresh Prism instances for `26.1.2-neoforge` (`26.1.2.109`) and `1.21.11-neoforge`
  (`21.11.45`) with the built jars and Java per line.
- [ ] Check `logs/latest.log` for each: no mixin/AT errors, `client initialized`,
  `Loaded N VFX effect definitions`, `VFX Weaver server initialized on neoforge`; play one
  post-processing effect and one world overlay.

### Task 6: Docs and changelog

- [ ] `AGENTS.md`: node table (six nodes), per-line NeoForge API notes, "Flashback is Fabric-only".
- [ ] `README.md`: requirements table gains the NeoForge column/rows.
- [ ] `docs/GUIDE.md`: Flashback section notes it is Fabric-only; Modrinth/jar naming for NeoForge.
- [ ] `docs/CHANGELOG.md`: extend the `Unreleased` entry (NeoForge 26.2 / 26.1.2 / 1.21.11 nodes).

## Self-Review

- Spec coverage: spec's S2 bullet ("add the two lines, guards, CI matrix, verify in game") maps to
  Tasks 1–5; spec's S3 docs/release items are started in Task 6 because the docs must state the
  per-line facts the executor needs.
- No placeholders: every task names exact files, properties, commands and the expected result; the
  two "verify, do not assume" items are javap/compile-driven by design (the S0 precedent).
- Type consistency: platform method names are those shipped in S0 (`VFXPlatform.isModLoaded`,
  `VFXNetwork.registerCommon/registerClientReceive/dispatchClient/sendToPlayer/allPlayers`,
  `VFXLoaderEvents.initCommon/onPlayerJoin/onServerTick/onServerStarted/onServerStopping/onRegisterCommands/onReload`,
  `VFXClientRenderHooks.registerWorldOverlays/onClientTick/onClientJoin/onClientDisconnect`).

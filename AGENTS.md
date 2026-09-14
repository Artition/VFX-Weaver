# AGENTS.md

Instructions for AI agents (Claude, Copilot, etc.) working in this repository.

## What this project is

A Fabric mod for Minecraft ~26.1 (a client-side VFX library). Java sources are split by side:

```
src/main/java/dev/vfxweaver/          — shared code (server + client): API, commands, datapack effects, network
src/client/java/dev/vfxweaver/client/ — client only: rendering, post-processing, shaders, camera shake
src/main/resources/               — fabric.mod.json, mixins, lang, assets (shared)
src/client/resources/             — client mixins, shaders (assets/vfxweaver/shaders)
```

A full description of the domain model (effects, timelines, datapacks, network protocol) is in `docs/GUIDE.md`.

## Build and verify

```bash
./gradlew build          # compile + jar into build/libs/
./gradlew runClient      # test client
./gradlew runServer      # test server
```

The `26.2` and `26.1.2` nodes target Java 25 and the `1.21.11` node targets Java 21; a single JDK 25+ (e.g. 26) can build all of them via `--release` (see `build.gradle`). If the build fails with `error: release version 25 not supported` — Gradle picked up the wrong JDK, not a code bug.

After any change under `src/`, always run `./gradlew build` before committing — an agent's task is not done until the build passes.

## Multi-version (Stonecutter)

Supported nodes: `26.2`, `26.1.2` and `1.21.11`. Shared source lives in `src/`; per-node dependencies in
`versions/<mc>/gradle.properties`. Build one node with `./gradlew :<mc>:build`, all nodes with
`./gradlew build`.

Adding a feature: write it once in `src/`. Only if it touches an API that differs between targets,
guard it in place with a Stonecutter comment (`//? if <cond { ... //?}`). The **active node is
`26.1.2`**, so the on-disk source is written in 26.1.2 form and the `1.21.11`/`26.2` branches are the
ones commented out in the working tree. Never fork a whole feature per version.

## Code style

- Indentation is tabs, not spaces.
- Method parameters and local variables that are not reassigned should be marked `final` (see any class in `effect/` or `client/`).
- Public classes and non-trivial public methods should have Javadoc (description + `@param`/`@return` where not obvious from the signature).
- Stateless utility classes should be `final class` with a private constructor (see `SimplexNoise`, `VFXShaderPrograms`, `VFXWorldBindings`).
- Singletons (managers) use a private constructor + static `get()` (see `VFXEffectManager`, `VFXDefinitionManager`, `VFXPostProcessingManager`).
- Any collection that grows from external/network/datapack input must be bounded by a constant (see `MAX_ACTIVE_EFFECTS`, `MAX_SCHEDULED_EFFECTS`, `MAX_COLLECTION_DEPTH` in `VFXEffectManager`) — do not add new unbounded lists/maps without an explicit limit.
- Datapack parsing (`VFXDefinition.parse`, `VFXDefinitionManager.prepare`): any new exception thrown while parsing a single file must be caught inside `prepare()`, otherwise one broken JSON file will take down loading of all effects (we hit this before — see git log).

## What must not be broken without discussion

- The datapack JSON effect format (`data/<namespace>/vfx/<effect>.json`) and the network protocol `vfxweaver:vfx_trigger` — backward compatibility matters; the protocol version (`VFXTriggerPayload.PROTOCOL_VERSION`) must be bumped on any breaking change.
- The public Java API (`VFXAPI`) — used by other mods.

## Documentation

- User guide (commands, effect types, datapacks, Java API) — `docs/GUIDE.md`. When effect/command/API behavior changes, update its changelog at the bottom of the file (see the existing `**vN**: ...` format).
- Commit and branch conventions — `CONTRIBUTING.md`.

# NeoForge Multi-Loader Port — Stage S0 (26.2 spike) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `26.2-neoforge` Stonecutter node exist, build, load in NeoForge 26.2 and render at least one post-processing effect end-to-end, without changing any Fabric behaviour.

**Architecture:** Adopt the upstream Stonecutter multi-loader layout: the same `src/main` + `src/client` tree serves both loaders, per-loader build scripts (`build.fabric.gradle` = today's `build.gradle`, new `build.neoforge.gradle` = ModDevGradle), and all loader coupling is funnelled into a new guarded package `dev.vfxweaver.platform` (static utility classes whose method bodies are wrapped in `//? if fabric { … //?} else { … //?}`). Core files stop importing `net.fabricmc.*` entirely.

**Tech Stack:** Java 25, Gradle 9.5.1 wrapper, Stonecutter 0.9.8, Fabric Loom 1.17 (existing nodes), NeoForge ModDevGradle 2.0.147, NeoForge 26.2.0.84, Mixin, Vanilla render pipelines / `SubmitNodeCollector`.

**Spec:** `docs/superpowers/specs/2026-09-18-neoforge-multiloader-design.md`

## Global Constraints

- Supported Fabric nodes and their artefacts must stay byte-for-byte functionally identical: `:26.2:build`, `:26.1.2:build`, `:1.21.11:build` stay green at the end of **every** task.
- Active Stonecutter node is `26.1.2` (Fabric). The on-disk text is the active form: for every guard written, the Fabric branch is the live text and the NeoForge branch is the commented one.
- Java level per node comes from `deps.java`: `25` for `26.2`/`26.1.2`, `21` for `1.21.11`. NeoForge `26.2` also needs Java 25.
- Windows PowerShell: use `.\gradlew.bat` and generous timeouts (first NeoForge run decompiles Minecraft; allow 60+ minutes: `timeout 4800000`).
- Never change: datapack format, `VFXTriggerPayload.PROTOCOL_VERSION`, public `VFXAPI` signatures, `expr` functions, shader files, effect behaviour, existing Fabric mixin targets.
- Mixin targets and NeoForge event/registry names are validated **at runtime** — verify every new symbol with `javap` against the node's dev jar before using it (see Task 3).
- Logging policy: per-frame/per-request `DEBUG`; repeatable warnings via `VFXLog.warnOnce`; `INFO` only for once-per-session/reload summaries.
- Tabs for indentation, `final` for non-reassigned params/locals, Javadoc on public classes/methods.
- This repo has **no test suite** and must not gain one; verification is: node builds + `javap` symbol checks + user-run in-game checks.

## File Structure

Created:

- `build.fabric.gradle` — moved content of today's `build.gradle` (Fabric nodes only).
- `build.neoforge.gradle` — ModDevGradle config for NeoForge nodes.
- `versions/26.2-neoforge/gradle.properties` — NeoForge 26.2 node deps.
- `src/main/resources/META-INF/neoforge.mods.toml` — NeoForge metadata (expanded per node).
- `src/main/resources/META-INF/accesstransformer.cfg` — the 5 `Particle` fields.
- `src/main/java/dev/vfxweaver/platform/VFXPlatform.java` — loader queries.
- `src/main/java/dev/vfxweaver/platform/VFXNetwork.java` — payload register/send/receive.
- `src/main/java/dev/vfxweaver/platform/VFXLoaderEvents.java` — lifecycle/command/reload wiring.
- `src/client/java/dev/vfxweaver/client/platform/VFXClientRenderHooks.java` — world-overlay event wiring.
- `src/main/java/dev/vfxweaver/VFXNeoForgeMod.java` — NeoForge `@Mod` entry (whole file guarded).

Modified:

- `settings.gradle` — register the 6 nodes with per-loader build scripts.
- `stonecutter.gradle` — loader tag/constant so `//? if fabric` / `//? if neoforge` work.
- `build.gradle` — becomes the thin controller script (shared config only).
- `src/main/java/dev/vfxweaver/VFXMod.java` — drop all `net.fabricmc.*` imports, call platform classes.
- `src/main/java/dev/vfxweaver/api/VFXAPI.java` — sending via `VFXNetwork`.
- `src/main/java/dev/vfxweaver/network/VFXPayloads.java` — registration/receive via `VFXNetwork`.
- `src/main/java/dev/vfxweaver/effect/VFXScoreboardSync.java`, `VFXServerEffects.java` — Fabric direct calls → `VFXNetwork`/`VFXPlatform`.
- `src/client/java/dev/vfxweaver/client/VFXClient.java` — client entry via `VFXLoaderEvents`/`VFXNetwork`.
- `src/client/java/dev/vfxweaver/client/flashback/FlashbackCompat.java` — `VFXPlatform.isModLoaded` + `VFXLoaderEvents.onClientTick`.
- `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java` — overlay event registration via `VFXClientRenderHooks`.
- `AGENTS.md` — multi-loader section.

---

### Task 1: Stonecutter multi-loader scaffolding

**Files:**
- Modify: `settings.gradle`, `stonecutter.gradle`, `build.gradle`
- Create: `build.fabric.gradle`, `build.neoforge.gradle` (stub for now), `versions/26.2-neoforge/gradle.properties`

**Interfaces:**
- Produces: nodes `:26.2`, `:26.1.2`, `:1.21.11` (Fabric, unchanged paths) and `:26.2-neoforge` (logical version `26.2`); loader tags usable in comment conditions as `//? if fabric {` and `//? if neoforge {`.

- [ ] **Step 1: Move the current build script to the Fabric loader script**

```powershell
git mv build.gradle build.fabric.gradle
```

- [ ] **Step 2: Register both loaders per version in `settings.gradle`**

Replace the `create(getRootProject())` block with (keep `kotlinController = false`, keep `centralScript`):

```groovy
stonecutter {
	kotlinController = false
	centralScript = 'build.gradle'

	create(getRootProject()) {
		['26.2', '26.1.2', '1.21.11'].each { v ->
			version(v, v).buildscript('build.fabric.gradle')
			version("${v}-neoforge", v).buildscript('build.neoforge.gradle')
		}
	}
}
```

- [ ] **Step 3: Make `build.gradle` the thin controller script**

`build.gradle` must no longer apply Loom (the per-node scripts do that). Keep only what the controller project needs; if the file becomes empty, keep it with a single comment:

```groovy
// Controller script for the Stonecutter build. Per-node configuration lives in
// build.fabric.gradle (Fabric nodes) and build.neoforge.gradle (NeoForge nodes).
```

- [ ] **Step 4: Create the loader tags / constants in `stonecutter.gradle`**

Add the loader as a Stonecutter constant so `//? if fabric` and `//? if neoforge` resolve. Check the Groovy syntax in the wiki page `https://stonecutter.kikugie.dev/wiki/config/params` (the upstream template uses Kotlin: `constants { match(loader, "fabric", "neoforge") }`). The target result, expressed in whatever form the Groovy DSL accepts:

```groovy
stonecutter {
	parameters {
		constants {
			match('loader', 'fabric', 'neoforge')
		}
	}
}
```

- [ ] **Step 5: Create the NeoForge node properties**

`versions/26.2-neoforge/gradle.properties`:

```properties
deps.minecraft=26.2
deps.neo_loader=26.2.0.84
deps.loader_compat=>=26.2
deps.mc_compat=[26.2,26.3)
deps.java=25
```

- [ ] **Step 6: Create a build.neoforge.gradle stub that only proves the node configures**

```groovy
plugins {
	id 'java-library'
}

version = "${property('mod_version')}+${sc.current.project}"
base.archivesName = property('mod_id').toString()
```

- [ ] **Step 7: Prove the node exists and the loader guard works**

Create a temporary probe file `src/main/java/dev/vfxweaver/Probe.java`:

```java
package dev.vfxweaver;

final class Probe {
	static final String LOADER =
			//? if fabric {
			"fabric";
			//?} else {
			/*"neoforge";*/
			//?}
}
```

Run:

```powershell
.\gradlew.bat stonecutterGenerate --console=plain
```

Expected: the generated `versions/26.2-neoforge/.../Probe.java` contains `"neoforge"` (uncommented) and the generated `versions/26.2/.../Probe.java` contains `"fabric"`. Delete `Probe.java` afterwards.

- [ ] **Step 8: Confirm the node list**

Run: `.\gradlew.bat projects --console=plain`
Expected: `26.2`, `26.1.2`, `1.21.11`, `26.2-neoforge` (at minimum) are listed.

- [ ] **Step 9: Confirm Fabric still builds**

Run: `.\gradlew.bat :26.1.2:build --console=plain` (large timeout)
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```powershell
git add -A
git commit -m "build(stonecutter): multi-loader scaffolding, add 26.2-neoforge node"
```

---

### Task 2: ModDevGradle for the NeoForge node

**Files:**
- Modify: `build.neoforge.gradle`
- Modify: `settings.gradle` (pluginManagement repositories: add NeoForged + KikuGie mavens if the plugin cannot be resolved)

**Interfaces:**
- Produces: task `:26.2-neoforge:createMinecraftArtifacts`, a compiled NeoForge dev classpath, and the node's `compileJava` task — the prerequisite for Task 3's `javap` checks and for all later NeoForge code.

- [ ] **Step 1: Add the plugin repositories**

In `settings.gradle` `pluginManagement.repositories`, add:

```groovy
maven { name = 'NeoForged'; url = 'https://maven.neoforged.net/releases/' }
maven { name = 'KikuGie Releases'; url = 'https://maven.kikugie.dev/releases' }
maven { name = 'KikuGie Snapshots'; url = 'https://maven.kikugie.dev/snapshots' }
```

- [ ] **Step 2: Write the ModDevGradle build script**

`build.neoforge.gradle` (full content; mirrors the upstream `build.neoforge.gradle.kts` but supports our `src/client` split):

```groovy
plugins {
	id 'java-library'
	id 'net.neoforged.moddev' version '2.0.147'
}

version = "${property('mod_version')}+${sc.current.project}"
base.archivesName = property('mod_id').toString()

def minecraftVersion = property('deps.minecraft')
def javaVersion = (property('deps.java') as String).toInteger()

// NeoForge has no environment-splitting source sets, so the client half of the shared tree is
// compiled into its own source set (same split as Loom's splitEnvironmentSourceSets) and both
// source sets are registered on the mod below.
sourceSets {
	client {
		java.srcDir rootProject.file('src/client/java')
		resources.srcDir rootProject.file('src/client/resources')
		compileClasspath += sourceSets.main.output + sourceSets.main.compileClasspath
		runtimeClasspath += sourceSets.main.output
	}
}

neoForge {
	version = property('deps.neo_loader')
	validateAccessTransformers = true

	mods {
		"${property('mod_id')}" {
			sourceSet sourceSets.main
			sourceSet sourceSets.client
		}
	}

	runs {
		client {
			client()
			sourceSet = sourceSets.client
		}
		server {
			server()
			programArgument '--nogui'
		}
	}
}

def expandProps = [
		version   : project.version,
		minecraft : minecraftVersion,
		java      : javaVersion,
		neoCompat : property('deps.mc_compat'),
		loaderCompat: property('deps.loader_compat'),
]

tasks.matching { it.name in ['processResources', 'processClientResources'] }.configureEach {
	inputs.properties(expandProps)
	filesMatching('META-INF/neoforge.mods.toml') { expand(expandProps) }
	filesMatching('*.mixins.json') { expand(expandProps) }
	exclude 'fabric.mod.json'
	exclude 'vfxweaver.accesswidener'
	exclude 'vfxweaver-named.accesswidener'
}

tasks.withType(JavaCompile).configureEach {
	it.options.release = javaVersion
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
}

// The client source set's classes and resources must end up in the mod jar (Fabric Loom does this
// for us on the Fabric side).
jar {
	from sourceSets.client.output
}

tasks.named('createMinecraftArtifacts') {
	dependsOn 'stonecutterGenerate'
}
```

- [ ] **Step 3: Build the NeoForge artefacts (slow, one time)**

Run: `.\gradlew.bat :26.2-neoforge:createMinecraftArtifacts --console=plain` (timeout 4800000 ms)
Expected: `BUILD SUCCESSFUL` (NeoForm decompile/recompile; this may take up to an hour).

- [ ] **Step 4: Prove the toolchain compiles the shared tree**

Run: `.\gradlew.bat :26.2-neoforge:compileJava --console=plain`
Expected: compiles the *shared* sources and fails **only** on the still-present `net.fabricmc` imports (`package net.fabricmc... does not exist`). Any other error is a build-config bug to fix here.

- [ ] **Step 5: Confirm Fabric is untouched**

Run: `.\gradlew.bat :26.2:build --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "build(neoforge): ModDevGradle setup for the 26.2-neoforge node"
```

---

### Task 3: Verify the NeoForge 26.2 API surface with `javap`

**Files:**
- Modify: `AGENTS.md` (add the verified table next to the existing 26.1.2/26.2 delta table)

**Interfaces:**
- Produces: a verified name/signature list that Tasks 5–8 code against. **Do not write NeoForge glue code before this task completes.**

- [ ] **Step 1: Locate the NeoForge dev jar**

```powershell
Get-ChildItem "$env:USERPROFILE\.gradle\caches" -Recurse -Filter 'neoforge-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '26\.2' } | Select-Object -First 5 FullName
```

Also inspect the MDG-generated artefacts under `versions/26.2-neoforge/build/` (NeoForm output) if the cache search is empty.

- [ ] **Step 2: Verify the client render events exist with the documented shape**

```powershell
& 'C:\Program Files\Java\jdk-26\bin\javap' -classpath $neoforgeJar net.neoforged.neoforge.client.event.RenderLevelStageEvent
& 'C:\Program Files\Java\jdk-26\bin\javap' -classpath $neoforgeJar net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
```

Expected: `RenderLevelStageEvent` with nested stage classes (`AfterTranslucentBlocks`, …); `SubmitCustomGeometryEvent` exposing `getSubmitNodeCollector()` and `getPoseStack()` (`javap` shows the accessor names — record them verbatim).

- [ ] **Step 3: Verify registration/lifecycle/networking entry points**

Run `javap` for each and record the exact signature:

```powershell
$classes = @(
  'net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent',
  'net.neoforged.neoforge.network.registration.PayloadRegistrar',
  'net.neoforged.neoforge.network.PacketDistributor',
  'net.neoforged.neoforge.network.handling.IPayloadContext',
  'net.neoforged.neoforge.event.tick.ServerTickEvent',
  'net.neoforged.neoforge.event.server.ServerStartedEvent',
  'net.neoforged.neoforge.event.server.ServerStoppingEvent',
  'net.neoforged.neoforge.event.entity.player.PlayerEvent',
  'net.neoforged.neoforge.event.RegisterCommandsEvent',
  'net.neoforged.neoforge.event.AddReloadListenerEvent',
  'net.neoforged.neoforge.client.event.ClientTickEvent',
  'net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent',
  'net.neoforged.fml.ModList',
  'net.neoforged.bus.api.SubscribeEvent'
)
foreach ($c in $classes) { Write-Output "=== $c ==="; & 'C:\Program Files\Java\jdk-26\bin\javap' -classpath $neoforgeJar $c }
```

For `ArgumentTypeRegistry`'s NeoForge replacement, find the command-argument registry:

```powershell
& 'C:\Program Files\Java\jdk-26\bin\javap' -classpath $neoforgeJar net.minecraft.commands.synchronization.ArgumentTypeInfos
& 'C:\Program Files\Java\jdk-26\bin\javap' -classpath $neoforgeJar net.minecraft.core.registries.BuiltInRegistries | Select-String -Pattern 'ARGUMENT'
```

- [ ] **Step 4: Verify the access-transformer field names**

```powershell
& 'C:\Program Files\Java\jdk-26\bin\javap' -p -classpath $neoforgeJar net.minecraft.client.particle.Particle | Select-String -Pattern ' xd| yd| zd| friction| gravity'
```

Expected: the five fields exist with those names (26.x is unobfuscated) — if a name differs, use the `javap` output in the AT file.

- [ ] **Step 5: Record the verified table in `AGENTS.md`**

Add a "NeoForge 26.2 API (verified)" subsection under the existing multi-version table with the exact class/method names from steps 2–4 and a note that they were verified by `javap` on the node jar. Any deviation from the design doc's mapping table must be written down here.

- [ ] **Step 6: Commit**

```powershell
git add AGENTS.md
git commit -m "docs(agents): verified NeoForge 26.2 API surface for the port"
```

---

### Task 4: `VFXPlatform` + `isModLoaded` de-Fabrication

**Files:**
- Create: `src/main/java/dev/vfxweaver/platform/VFXPlatform.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXServerEffects.java`
- Modify: `src/client/java/dev/vfxweaver/client/flashback/FlashbackCompat.java`

**Interfaces:**
- Produces: `dev.vfxweaver.platform.VFXPlatform.isModLoaded(String modId) -> boolean` and `VFXPlatform.name() -> String` ("fabric"/"neoforge").

- [ ] **Step 1: Create the platform class (guarded static utility, the repo's existing idiom)**

```java
package dev.vfxweaver.platform;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;*/
//?}

/**
 * Loader queries used by the shared code. Every method body is guarded by Stonecutter so the
 * shared sources never import a loader API outside this package.
 */
public final class VFXPlatform {
	private VFXPlatform() {
	}

	/**
	 * @param modId the mod id to look for
	 * @return whether a mod with that id is loaded
	 */
	public static boolean isModLoaded(final String modId) {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded(modId);
		//?} else {
		/*return ModList.get().isLoaded(modId);*/
		//?}
	}

	/**
	 * @return the loader name, for logs and diagnostics
	 */
	public static String name() {
		//? if fabric {
		return "fabric";
		//?} else {
		/*return "neoforge";*/
		//?}
	}
}
```

- [ ] **Step 2: Replace both `FabricLoader` call sites**

`VFXServerEffects.java`: drop `import net.fabricmc.loader.api.FabricLoader;` and replace `FabricLoader.getInstance().isModLoaded(X)` with `VFXPlatform.isModLoaded(X)` (import `dev.vfxweaver.platform.VFXPlatform`).

`FlashbackCompat.java`: same replacement for its `FabricLoader` import and call.

- [ ] **Step 3: Verify no loader import remains in those files**

Run: `Select-String -Path src\main\java\dev\vfxweaver\effect\VFXServerEffects.java,src\client\java\dev\vfxweaver\client\flashback\FlashbackCompat.java -Pattern 'net\.fabricmc'`
Expected: no output.

- [ ] **Step 4: Fabric nodes still build**

Run: `.\gradlew.bat :26.1.2:build :26.2:build :1.21.11:build --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```powershell
git add -A
git commit -m "refactor(platform): add VFXPlatform and remove FabricLoader from core"
```

---

### Task 5: `VFXNetwork` — payload registration, sending, receiving

**Files:**
- Create: `src/main/java/dev/vfxweaver/platform/VFXNetwork.java`
- Modify: `src/main/java/dev/vfxweaver/network/VFXPayloads.java`, `src/main/java/dev/vfxweaver/api/VFXAPI.java`, `src/main/java/dev/vfxweaver/effect/VFXScoreboardSync.java`, `src/main/java/dev/vfxweaver/effect/VFXServerEffects.java`, `src/client/java/dev/vfxweaver/client/VFXClient.java`

**Interfaces:**
- Consumes: verified NeoForge names from Task 3.
- Produces (used by Tasks 6–8):
  - `VFXNetwork.registerCommon()` — registers S2C+C2S payload types and the server-side receive handler.
  - `VFXNetwork.registerClient()` — registers the client-side receive handler.
  - `VFXNetwork.sendToPlayer(ServerPlayer, CustomPacketPayload)`
  - `VFXNetwork.sendToAll(Iterable<ServerPlayer>, CustomPacketPayload)` (the only broadcast shape the mod uses today is `PlayerLookup.all(...)`)
  - `VFXNetwork.setOnServerReceive(...)`, `VFXNetwork.setOnClientReceive(...)`

- [ ] **Step 1: Move registration out of `VFXPayloads`**

`VFXPayloads` keeps the payload record definitions and `PROTOCOL_VERSION`. Its Fabric `PayloadTypeRegistry`/`PlayerLookup`/`ServerPlayNetworking` imports move into `VFXNetwork`; `VFXPayloads.register()` becomes `VFXNetwork.registerCommon()` (keep the old name as a one-line delegate if other classes call it, or update the callers — `VFXMod` and `VFXClient` are updated in Tasks 6–7). The Fabric branch of `registerCommon()` is today's registration code **moved verbatim** (read `VFXPayloads.java` and cut the `PayloadTypeRegistry` calls into the Fabric branch unchanged) — no rewriting of working code.

- [ ] **Step 2: Write `VFXNetwork` with guarded bodies**

Shape (the Fabric branch is today's code, moved verbatim; the NeoForge branch uses the Task 3-verified names):

```java
package dev.vfxweaver.platform;

import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
//? if fabric {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//?} else {
/*import net.neoforged.neoforge.network.PacketDistributor;*/
//?}

/** Loader-specific payload registration and transport. */
public final class VFXNetwork {
	private VFXNetwork() {
	}

	/** Registers every payload type and the server-side request handler. */
	public static void registerCommon() {
		//? if fabric {
		//? if <26.1 {
		/*PayloadTypeRegistry.playS2C().register(VFXTriggerPayload.TYPE, VFXTriggerPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(VFXSyncPayload.TYPE, VFXSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(VFXScoreboardPayload.TYPE, VFXScoreboardPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(VFXRequestPayload.TYPE, VFXRequestPayload.STREAM_CODEC);
		*///?} else {
		PayloadTypeRegistry.clientboundPlay().register(VFXTriggerPayload.TYPE, VFXTriggerPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VFXSyncPayload.TYPE, VFXSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VFXScoreboardPayload.TYPE, VFXScoreboardPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(VFXRequestPayload.TYPE, VFXRequestPayload.STREAM_CODEC);
		//?}
		ServerPlayNetworking.registerGlobalReceiver(VFXRequestPayload.TYPE, VFXPayloads::handleRequest);
		//?} else {
		/*registrar.playToClient(VFXTriggerPayload.TYPE, VFXTriggerPayload.STREAM_CODEC, (payload, ctx) -> VFXClient.handleTrigger(payload));
		registrar.playToClient(VFXSyncPayload.TYPE, VFXSyncPayload.STREAM_CODEC, (payload, ctx) -> VFXClient.handleSync(payload));
		registrar.playToClient(VFXScoreboardPayload.TYPE, VFXScoreboardPayload.STREAM_CODEC, (payload, ctx) -> VFXClient.handleScoreboard(payload));
		registrar.playToServer(VFXRequestPayload.TYPE, VFXRequestPayload.STREAM_CODEC, (payload, ctx) -> ctx.enqueueWork(() -> VFXPayloads.handleRequest(payload, ctx.player())));*/
		//?}
	}

	/** Sends one payload to one player. */
	public static void sendToPlayer(final ServerPlayer player, final CustomPacketPayload payload) {
		//? if fabric {
		ServerPlayNetworking.send(player, payload);
		//?} else {
		/*PacketDistributor.sendToPlayer(player, payload);*/
		//?}
	}

	/** Sends one payload to every player currently on the server. */
	public static void sendToAll(final Iterable<ServerPlayer> players, final CustomPacketPayload payload) {
		for (ServerPlayer player : players) {
			sendToPlayer(player, payload);
		}
	}

	/** Registers the client-side payload receiver; called from the client entry point. */
	public static void registerClient() {
		//? if fabric {
		/* ClientPlayNetworking.registerGlobalReceiver(...) per payload type, moved from VFXClient */
		//?} else {
		/*payload types are registered in registerCommon() on NeoForge; nothing to do here*/
		//?}
	}
}
```

Notes for the implementer: on Fabric the client receivers are registered by `VFXClient` today (`ClientPlayNetworking.registerGlobalReceiver`) — move those calls into `registerClient()`'s Fabric branch verbatim. On NeoForge the handlers are declared at registration time, so `VFXClient.handleTrigger/handleSync/handleScoreboard` must be reachable from the common source set: keep those methods in `VFXClient` and call them through the guarded branch, or (simpler and preferred) forward to `VFXNetwork`'s settable callbacks (`setOnClientReceive`) so `platform` never imports client classes. The broadcast path uses `PlayerLookup.all(context.server())` today, which becomes `PacketDistributor.sendToAllPlayers(payload)` inside `VFXPayloads.handleRequest` — keep the permission/validation logic there unchanged.

- [ ] **Step 3: Update the call sites**

- `VFXAPI`: `ServerPlayNetworking.send(...)` → `VFXNetwork.sendToPlayer(...)`; drop the import.
- `VFXScoreboardSync`, `VFXServerEffects`: same.
- `VFXClient`: `ClientPlayNetworking.registerGlobalReceiver(...)` → `VFXNetwork.registerClient()` + `VFXNetwork.setOnClientReceive(...)`; drop the import.

- [ ] **Step 4: Verify no loader import remains in core**

Run: `Select-String -Path (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName) -Pattern 'net\.fabricmc' | Where-Object { $_.Path -notmatch 'platform' }`
Expected: no matches outside `src/**/platform/**`.

- [ ] **Step 5: Fabric nodes still build**

Run: `.\gradlew.bat :26.1.2:build :26.2:build :1.21.11:build --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```shell
git add -A; git commit -m "refactor(platform): VFXNetwork abstracts payload registration and transport"
```

---

### Task 6: `VFXLoaderEvents` — server lifecycle, commands, reload

**Files:**
- Create: `src/main/java/dev/vfxweaver/platform/VFXLoaderEvents.java`
- Create: `src/main/java/dev/vfxweaver/VFXNeoForgeMod.java`
- Modify: `src/main/java/dev/vfxweaver/VFXMod.java`

**Interfaces:**
- Consumes: `VFXNetwork.registerCommon()`, `VFXPlatform.name()`.
- Produces: `VFXLoaderEvents.initCommon()` (called once by the loader entry point), `VFXLoaderEvents.onServerTick(MinecraftServer)`, `VFXLoaderEvents.onServerStarted(MinecraftServer)`, `VFXLoaderEvents.onServerStopping(MinecraftServer)`, `VFXLoaderEvents.onPlayerJoin(ServerPlayer)`, `VFXLoaderEvents.onRegisterCommands(CommandDispatcher<CommandSourceStack>, CommandBuildContext)`, `VFXLoaderEvents.onReload(Object reloadListener)`.

- [ ] **Step 1: Create `VFXLoaderEvents` with guarded bodies**

Same pattern as `VFXNetwork`: the Fabric branch is today's code from `VFXMod` (`ServerTickEvents.END_SERVER_TICK`, `ServerLifecycleEvents.SERVER_STARTED/STOPPING`, `ServerPlayConnectionEvents.JOIN`, `CommandRegistrationCallback`, `ArgumentTypeRegistry`, `ResourceLoader.registerReloadListener`), each moved into its own method. Write **both** branches in this task, using the Task 3-verified NeoForge names — a half-written branch would only surface in Task 8, after the Fabric nodes already passed.

- [ ] **Step 2: Make `VFXMod` loader-agnostic**

`VFXMod` keeps `implements ModInitializer` for Fabric **guarded**:

```java
//? if fabric {
public final class VFXMod implements ModInitializer {
	@Override
	public void onInitialize() {
		VFXLoaderEvents.initCommon();
	}
}
//?} else {
/*public final class VFXMod {
	public static void init() {
		VFXLoaderEvents.initCommon();
	}
}*/
//?}
```

Remove all other `net.fabricmc.*` imports from the file.

- [ ] **Step 3: Create the NeoForge entry point (whole file guarded)**

```java
//? if neoforge {
package dev.vfxweaver;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("vfxweaver")               /* exact annotation form per the MDK 26.2 template */
public final class VFXNeoForgeMod {
	public VFXNeoForgeMod(final IEventBus modBus) {
		VFXMod.init();
	}
}
//?}
```

- [ ] **Step 4: Register the NeoForge events**

In `VFXLoaderEvents`, add the NeoForge-only subscription block (guarded) using the verified event classes: `ServerTickEvent.Post`, `ServerStartedEvent`, `ServerStoppingEvent`, `PlayerEvent.PlayerLoggedInEvent`, `RegisterCommandsEvent`, `AddReloadListenerEvent` — each forwarding to the corresponding `VFXLoaderEvents` method. Follow the MDK template for whether subscriptions use `@EventBusSubscriber` or explicit `bus.addListener(...)`.

- [ ] **Step 5: Verify Fabric**

Run: `.\gradlew.bat :26.1.2:build :26.2:build :1.21.11:build --console=plain`
Expected: `BUILD SUCCESSFUL`, and `Select-String -Path src\main\java\dev\vfxweaver\VFXMod.java -Pattern 'net\.fabricmc'` returns nothing.

- [ ] **Step 6: Commit**

```shell
git add -A; git commit -m "refactor(platform): VFXLoaderEvents + NeoForge @Mod entry point"
```

---

### Task 7: `VFXClientRenderHooks` — client lifecycle, tick and world overlays

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/platform/VFXClientRenderHooks.java`
- Modify: `src/client/java/dev/vfxweaver/client/VFXClient.java`, `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java`, `src/client/java/dev/vfxweaver/client/flashback/FlashbackCompat.java`

**Interfaces:**
- Consumes: `VFXNetwork.registerClient()`, `VFXLoaderEvents`.
- Produces: `VFXClientRenderHooks.registerWorldOverlays(Runnable onRender)` wiring the loader's level-render event to our submit path; `VFXClientRenderHooks.onClientTick()`; `VFXClientRenderHooks.onClientJoin()/onClientDisconnect()`; `VFXClientRenderHooks.initClient()`.

- [ ] **Step 1: Move Fabric client wiring into the hooks class**

`VFXClient`: keep the actual client setup logic, drop `ClientModInitializer`/`ClientLifecycleEvents`/`ClientPlayConnectionEvents`/`ClientPlayNetworking` imports; call the hook methods instead. Guard the Fabric entry class (`//? if fabric`) and add the NeoForge client entry (`@Mod(value = "vfxweaver", dist = Dist.CLIENT)`) in the same guarded style as Task 6.

- [ ] **Step 2: World overlays**

`VFXWorldOverlayRenderer`: replace the static `Register` block that references `WorldRenderEvents`/`LevelRenderEvents` with a call to `VFXClientRenderHooks.registerWorldOverlays(VFXWorldOverlayRenderer::render)`; keep the render implementation itself untouched. Fabric branch = `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` (`>=26.1`) / `WorldRenderEvents` (`<26.1`); NeoForge branch = `RenderLevelStageEvent.AfterTranslucentBlocks` (26.2) with the `SubmitCustomGeometryEvent` path if the overlay geometry needs the collector — **choose the variant that matches what our `>=26.2` overlay path already does** and verify visually in Task 9.

- [ ] **Step 3: Flashback tick hook**

`FlashbackCompat`: replace `ClientTickEvents.END_CLIENT_TICK.register(...)` with a `VFXClientRenderHooks.onClientTick()` callback registration.

- [ ] **Step 4: Verify no loader import outside platform packages**

Run: `Select-String -Path (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName) -Pattern 'net\.fabricmc' | Where-Object { $_.Path -notmatch 'platform' }`
Expected: no matches.

- [ ] **Step 5: Fabric nodes still build and the mod still behaves**

Run: `.\gradlew.bat :26.1.2:build :26.2:build :1.21.11:build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```shell
git add -A; git commit -m "refactor(client): VFXClientRenderHooks abstracts client and world-render wiring"
```

---

### Task 8: NeoForge metadata, access transformer and the first NeoForge jar

**Files:**
- Create: `src/main/resources/META-INF/neoforge.mods.toml`, `src/main/resources/META-INF/accesstransformer.cfg`
- Modify: `build.fabric.gradle` (exclude the NeoForge metadata and the AT from Fabric jars)

**Interfaces:**
- Produces: `versions/26.2-neoforge/build/libs/vfxweaver-<ver>+26.2-neoforge.jar`, loadable by NeoForge 26.2.

- [ ] **Step 1: Write the access transformer**

`src/main/resources/META-INF/accesstransformer.cfg` (field names confirmed in Task 3):

```text
public net.minecraft.client.particle.Particle xd
public net.minecraft.client.particle.Particle yd
public net.minecraft.client.particle.Particle zd
public net.minecraft.client.particle.Particle friction
public net.minecraft.client.particle.Particle gravity
```

- [ ] **Step 2: Write the NeoForge metadata**

Fetch the reference shape and copy its structure exactly:

```powershell
Invoke-WebRequest 'https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.2-ModDevGradle/main/src/main/templates/META-INF/neoforge.mods.toml' -OutFile "$env:TEMP\mdk-neoforge.mods.toml"; Get-Content "$env:TEMP\mdk-neoforge.mods.toml"
```

Write `src/main/resources/META-INF/neoforge.mods.toml` with: `modLoader`, `loaderVersion = "${loaderCompat}"`, `license`, `[[mods]]` (`modId`, `version = "${version}"`, `displayName`, `description`, `logoFile = "assets/vfxweaver/icon.png"`, `authors`), required dependencies `neoforge` and `minecraft` (`versionRange = "${neoCompat}"`), optional dependencies for `flashback` and `iris`, `[[mixins]] config = "vfxweaver.mixins.json"`, `[[mixins]] config = "vfxweaver.client.mixins.json"`, and `[[accessTransformers]] file = "META-INF/accesstransformer.cfg"`.

- [ ] **Step 3: Keep the NeoForge files out of the Fabric jars**

In `build.fabric.gradle`'s `processResources`/`processClientResources` block, add:

```groovy
exclude 'META-INF/neoforge.mods.toml'
exclude 'META-INF/accesstransformer.cfg'
```

- [ ] **Step 4: Build the NeoForge jar**

Run: `.\gradlew.bat :26.2-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL`; jar at `versions/26.2-neoforge/build/libs/vfxweaver-<ver>+26.2-neoforge.jar`.

- [ ] **Step 5: Inspect the jar contents**

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::OpenRead('versions\26.2-neoforge\build\libs\vfxweaver-1.1.4+26.2-neoforge.jar').Entries | Select-Object -ExpandProperty FullName | Sort-Object
```

Expected: `META-INF/neoforge.mods.toml`, `META-INF/accesstransformer.cfg`, both mixin configs, `assets/vfxweaver/shaders/**`, `data/vfxweaver/vfx/**`, the `client`-source-set classes — and **no** `fabric.mod.json` / `*.accesswidener`.

- [ ] **Step 6: Verify the Fabric jars are unchanged**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build --console=plain`
Expected: `BUILD SUCCESSFUL`; each Fabric jar still contains `fabric.mod.json` and its AW, and no `META-INF/neoforge.mods.toml`.

- [ ] **Step 7: Commit**

```shell
git add -A; git commit -m "feat(neoforge): metadata, access transformer and 26.2-neoforge jar"
```

---

### Task 9: Runtime verification on NeoForge 26.2 (S0 exit)

**Files:**
- Modify: whatever the runtime exposes as broken (fix inside this task, one commit per fix).

**Interfaces:**
- Consumes: the jar from Task 8.
- Produces: a confirmed S0 exit (mod loads, datapack effects load, a post-processing effect renders on NeoForge 26.2).

- [ ] **Step 1: Create a NeoForge 26.2 test instance**

PrismLauncher instance (sibling of `26.2test`), NeoForge `26.2.0.84`, Java 25, with the `vfxweaver-<ver>+26.2-neoforge.jar` and the `vfxtest` datapack jar from `C:\Users\Light Flight PC\AppData\Local\Temp\opencode\vfxtestmod\`. Ask the user to launch it (or create the instance files and let Prism finish installing on first launch).

- [ ] **Step 2: Check startup logs**

Expected in `logs/latest.log`: no mixin errors, no AT errors, the client init summary line (`client initialized`), the definitions-loaded summary, and no `NoSuchMethodError`/`NoClassDefFoundError` from our classes. Client-only classes must not load on a dedicated server — start the `server` run once (`.\gradlew.bat :26.2-neoforge:runServer` cannot accept input here; instead verify statically that no `src/main` class references a `src/client` class).

- [ ] **Step 3: Play one post-processing effect**

In-game: `/vfx play @s speed_lines` (or the pack's own trigger). Expected: the effect renders identically to the Fabric build. If it does not, fix in this task (the likely culprits are the post-processing layer hooks — they are vanilla, so a difference points at `VFXClient` ordering or the overlay hook).

- [ ] **Step 4: Play one world overlay**

`/vfx play @s pulse_ring` — confirms the `RenderLevelStageEvent` wiring from Task 7 (S0 accepts this failing if the stage choice is wrong; fix the stage and re-test).

- [ ] **Step 5: Confirm Fabric parity was not broken**

Reload the Fabric `26.2test` instance with the newly built Fabric jar and repeat steps 2–4 there.

- [ ] **Step 6: Commit**

```shell
git add -A; git commit -m "fix(neoforge): S0 runtime fixes for 26.2"
```

---

### Task 10: Document the multi-loader rules

**Files:**
- Modify: `AGENTS.md`, `docs/ARCHITECTURE.md` (loader/platform section), `docs/CHANGELOG.md` (unreleased entry)

**Interfaces:**
- Produces: the rules the S1–S3 work will follow.

- [ ] **Step 1: `AGENTS.md`**

Add a "Multi-loader (Fabric + NeoForge)" section: node naming (`<mc>` = Fabric, `<mc>-neoforge`), per-loader build scripts, the rule that **all** `net.fabricmc.*` / `net.neoforged.*` imports live only in `dev.vfxweaver.platform` / `client.platform`, how to add a guarded body, how to build both loaders, and the `javap` verification requirement for new loader symbols.

- [ ] **Step 2: `docs/ARCHITECTURE.md`**

Document the platform layer: what each platform class owns, and that the core (render, datapack, API) is loader-agnostic.

- [ ] **Step 3: `docs/CHANGELOG.md`**

Add an `Unreleased` entry: "NeoForge support (26.2 spike)". Do not announce a release until S2/S3.

- [ ] **Step 4: Commit**

```shell
git add -A; git commit -m "docs: multi-loader rules and NeoForge platform layer"
```

---

## Self-Review

**Spec coverage (S0 scope):** build layout → Tasks 1–2; platform layer → Tasks 4–7; metadata/AT/mixins → Task 8; verification strategy (`javap` + in-game) → Tasks 3, 9; risks R1 (build) → Tasks 1–2; R2 (API names) → Task 3; R3 (dedicated server) → Task 9 Step 2; R4 (network threads) → Task 5 Step 2; R5 (render differences) → Task 9 Steps 3–4; R6 (slow decompile) → Task 2 Step 3; R7 (Modrinth) → deferred to S3 by design. Doc updates in the spec's S3 are started here (Task 10) because the rules are needed by S1.

**Deferred by design (S1–S3, own plans):** parity checklist completion, `26.1.2-neoforge` + `1.21.11-neoforge` nodes, CI matrix over 6 nodes, README/API/GUIDE updates, Modrinth NeoForge listings.

**Type consistency:** `VFXPlatform.isModLoaded/name`, `VFXNetwork.{registerCommon,registerClient,sendToPlayer,sendToAll,setOnServerReceive,setOnClientReceive}`, `VFXLoaderEvents.{initCommon,onServerTick,onServerStarted,onServerStopping,onPlayerJoin,onRegisterCommands,onReload}`, `VFXClientRenderHooks.{initClient,onClientTick,onClientJoin,onClientDisconnect,registerWorldOverlays}` — these names are used only by the tasks defined here and by no other part of the codebase yet. (`sendToTracking` was dropped from the design: the mod only ever broadcasts to all players, so it is not needed.)

**No placeholders:** every step names files, commands and code; the only "verify and adjust" steps are Task 3 (`javap`, by design — the symbols must be confirmed before use) and Task 9 (runtime), both with explicit expected results.

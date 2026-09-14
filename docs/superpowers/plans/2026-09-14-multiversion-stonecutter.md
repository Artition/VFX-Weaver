# Multi-version (Stonecutter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second Minecraft target (1.21.11) to VFX Weaver from one source tree using Stonecutter, without changing behavior on the existing 26.1.2 target.

**Architecture:** Stonecutter 0.9.8 turns the repo into a multi-node Gradle build: shared source stays in the root `src/`, per-node dependencies live in `versions/<mc>/gradle.properties`, and version differences are resolved at build time by global string replacements (the `Identifier`→`ResourceLocation` rename), swaps (permission API), and `//? if` guards. Each node produces its own jar.

**Tech Stack:** Gradle 9.5.1 (wrapper), Stonecutter 0.9.8, Fabric Loom 1.17-SNAPSHOT, Minecraft 26.1.2 (Java 25) + 1.21.11 (Java 21), Fabric API.

**Spec:** `docs/superpowers/specs/2026-09-14-multiversion-stonecutter-design.md`

## Global Constraints

- Targets: `26.1.2` (baseline, must stay behaviorally identical) and `1.21.11`. Do **not** add 1.21.1 in this plan.
- Per-node versions: 26.1.2 → loader `0.19.5`, fabric-api `0.155.3+26.1.2`, Java `25`; 1.21.11 → loader `0.19.5`, fabric-api `0.141.6+1.21.11`, Java `21`.
- One Loom version for the whole build: `1.17-SNAPSHOT`.
- Code style (from `AGENTS.md`): tabs, `final` on non-reassigned params/locals, Javadoc on public types/methods, bounded collections for external input.
- Datapack format `data/<ns>/vfx/*.json` and network protocol `vfxweaver:vfx_trigger` must be **identical** across versions; do not bump `PROTOCOL_VERSION` in this plan.
- Conventional Commits (`CONTRIBUTING.md`): `build`, `chore`, `ci`, `docs`, `refactor`, `feat`, `fix`. Branch: `refactor/multiversion-stonecutter` (already created and checked out).
- Work on Windows: use `.\gradlew.bat <task> --no-daemon`; on other OS `./gradlew <task>`.
- A task is not done until its verification command passes. Never claim success without pasting the command output.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle` | Stonecutter plugin + version list; root project name. |
| `build.gradle` | Shared per-node build script: deps, Java level, Loom config, replacements/swaps, resources, publishing. |
| `gradle.properties` | Version-independent properties (Loom, mod id/version/group). |
| `versions/26.1.2/gradle.properties` | Per-node deps for 26.1.2. |
| `versions/1.21.11/gradle.properties` | Per-node deps for 1.21.11. |
| `src/main/resources/fabric.mod.json` | Templated `minecraft`/`java`/`version`. |
| `src/main/resources/vfxweaver.mixins.json`, `src/client/resources/vfxweaver.client.mixins.json` | Templated `compatibilityLevel`. |
| `src/**` (existing) | Shared code; edited **only** where a `//? if` guard or swap is required. |
| `scripts/audit-replacements.ps1` | Automated stray-replacement audit. |
| `.github/workflows/build.yml` | Per-node CI matrix. |
| `docs/GUIDE.md`, `README.md`, `AGENTS.md` | Document the multi-version workflow. |

---

## Phase 0 — Stonecutter bootstrap (26.1.2 must stay green)

### Task 0.1: Confirm baseline green

**Files:** none.

- [ ] **Step 1: Build the current code before any change**

Run: `.\gradlew.bat build --no-daemon`
Expected: `BUILD SUCCESSFUL`; jars under `build/libs/`.

- [ ] **Step 2: Record the baseline jar and run dir (rollback reference)**

Run: `Get-ChildItem build\libs`
Expected: `vfxweaver-1.1.0.jar` (or `1.1.1`), plus `-sources.jar`. Note the exact name; later phases compare against it.

- [ ] **Step 3: No commit** (baseline check only).

### Task 0.2: Add per-node properties, slim the root properties

**Files:**
- Modify: `gradle.properties`
- Create: `versions/26.1.2/gradle.properties`
- Create: `versions/1.21.11/gradle.properties`

**Interfaces:**
- Produces: build-script properties `loom_version`, `mod_version`, `maven_group`, `mod_id`, and per-node `deps.minecraft`, `deps.loader`, `deps.fabric_api`, `deps.java`.

- [ ] **Step 1: Replace `gradle.properties` with the version-independent set**

```properties
# Done to increase the memory available to gradle.
org.gradle.jvmargs=-Xmx1G
org.gradle.parallel=true

# IntelliJ IDEA is not yet fully compatible with configuration cache, see:
# https://github.com/FabricMC/fabric-loom/issues/1349
org.gradle.configuration-cache=false

# Fabric Loom — ONE version for the whole Stonecutter build (shared plugin classpath).
loom_version=1.17-SNAPSHOT

# Mod properties (version-independent; per-node deps live in versions/<mc>/gradle.properties)
mod_version=1.1.0
maven_group=dev.vfxweaver
mod_id=vfxweaver
```

- [ ] **Step 2: Create `versions/26.1.2/gradle.properties`**

```properties
deps.minecraft=26.1.2
deps.loader=0.19.5
deps.fabric_api=0.155.3+26.1.2
deps.java=25
```

- [ ] **Step 3: Create `versions/1.21.11/gradle.properties`**

```properties
deps.minecraft=1.21.11
deps.loader=0.19.5
deps.fabric_api=0.141.6+1.21.11
deps.java=21
```

- [ ] **Step 4: Commit**

```bash
git add gradle.properties versions/26.1.2/gradle.properties versions/1.21.11/gradle.properties
git commit -m "build: move Minecraft/loader/fabric-api versions into per-node properties"
```

### Task 0.3: Rewrite `settings.gradle` with Stonecutter

**Files:**
- Modify: `settings.gradle`

**Interfaces:**
- Produces: Gradle nodes `:26.1.2` and `:1.21.11`; Stonecutter accessors `sc.current.version` / `sc.current.parsed`.

- [ ] **Step 1: Replace the file content**

```groovy
pluginManagement {
	repositories {
		maven {
			name = 'Fabric'
			url = 'https://maven.fabricmc.net/'
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id 'dev.kikugie.stonecutter' version '0.9.8'
}

stonecutter {
	// Use the Groovy DSL; Stonecutter defaults to a Kotlin controller.
	kotlinController = false
	centralScript = 'build.gradle'

	create getRootProject() {
		versions '26.1.2', '1.21.11'
	}
}

rootProject.name = 'vfxweaver'
```

- [ ] **Step 2: Verify Stonecutter resolves (do not expect a full build yet)**

Run: `.\gradlew.bat projects --no-daemon`
Expected: the output lists subprojects `:26.1.2` and `:1.21.11`. If Stonecutter errors on `kotlinController`/`centralScript`, open the Stonecutter setup doc linked in the spec §2 and adjust those two lines — the rest of the file is correct.

- [ ] **Step 3: Commit**

```bash
git add settings.gradle
git commit -m "build: adopt Stonecutter with 26.1.2 and 1.21.11 nodes"
```

### Task 0.4: Rewrite `build.gradle` as the shared controller

**Files:**
- Modify: `build.gradle`

**Interfaces:**
- Consumes: properties from Task 0.2, nodes from Task 0.3.
- Produces: `sc.current.version`-aware dependency resolution, Java level, Loom config, jar naming `vfxweaver-<mod_version>+<mc>.jar`.

- [ ] **Step 1: Replace the file content**

```groovy
plugins {
	id 'net.fabricmc.fabric-loom' version "${loom_version}"
	id 'maven-publish'
}

def minecraftVersion = property('deps.minecraft')
def loaderVersion = property('deps.loader')
def fabricApiVersion = property('deps.fabric_api')
def javaVersion = (property('deps.java') as String).toInteger()

version = "${property('mod_version')}+${sc.current.version}"
group = property('maven_group')
base.archivesName = property('mod_id').toString()

repositories {
	// Loom adds the Minecraft/Fabric repositories automatically.
}

loom {
	splitEnvironmentSourceSets()

	accessWidenerPath = rootProject.file("src/main/resources/vfxweaver.accesswidener")

	mods {
		"vfxweaver" {
			sourceSet sourceSets.main
			sourceSet sourceSets.client
		}
	}
}

dependencies {
	minecraft "com.mojang:minecraft:${minecraftVersion}"
	implementation "net.fabricmc:fabric-loader:${loaderVersion}"
	implementation "net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}"
}

def expandProps = [
		version          : project.version,
		minecraft        : minecraftVersion,
		java             : javaVersion,
		compatibilityLevel: "JAVA_${javaVersion}",
]

tasks.matching { it.name in ['processResources', 'processClientResources'] }.configureEach {
	inputs.properties(expandProps)
	filesMatching('fabric.mod.json') { expand(expandProps) }
	filesMatching('*.mixins.json') { expand(expandProps) }
}

tasks.withType(JavaCompile).configureEach {
	it.options.release = javaVersion
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
}

jar {
	from('LICENSE') {
		rename { "${it}_${project.name}" }
	}
}

publishing {
	publications {
		create('mavenJava', MavenPublication) {
			from components.java
		}
	}
	repositories {
		maven { url = uri(layout.buildDirectory.dir("maven")) }
	}
}
```

- [ ] **Step 2: Sync the project and confirm configuration succeeds**

Run: `.\gradlew.bat :26.1.2:tasks --no-daemon`
Expected: task list prints without errors. If the `plugins {}` version interpolation fails, replace `"${loom_version}"` with the literal `'1.17-SNAPSHOT'` and re-run.

- [ ] **Step 3: Build the 26.1.2 node**

Run: `.\gradlew.bat :26.1.2:build --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "build: convert build.gradle into the Stonecutter controller script"
```

### Task 0.5: Template metadata and mixin configs

**Files:**
- Modify: `src/main/resources/fabric.mod.json`
- Modify: `src/main/resources/vfxweaver.mixins.json`
- Modify: `src/client/resources/vfxweaver.client.mixins.json`

- [ ] **Step 1: Template `fabric.mod.json`**

Replace the `depends` block:

```json
	"depends": {
		"fabricloader": ">=0.19.5",
		"minecraft": "~${minecraft}",
		"java": ">=${java}",
		"fabric-api": "*"
	},
```

(`"version": "${version}"` is already present and stays.)

- [ ] **Step 2: Template both mixin configs**

In `src/main/resources/vfxweaver.mixins.json` and `src/client/resources/vfxweaver.client.mixins.json`, replace:

```json
	"compatibilityLevel": "JAVA_25",
```

with:

```json
	"compatibilityLevel": "${compatibilityLevel}",
```

- [ ] **Step 3: Verify the generated metadata for 26.1.2**

Run: `.\gradlew.bat :26.1.2:processResources :26.1.2:processClientResources --no-daemon`
Then: `Get-Content versions\26.1.2\build\resources\main\fabric.mod.json`
Expected: `"minecraft": "~26.1.2"`, `"java": ">=25"`, and a concrete `"version"` (no `${...}` left). Confirm the mixin config under `versions\26.1.2\build\resources\main\vfxweaver.mixins.json` has `"compatibilityLevel": "JAVA_25"`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/fabric.mod.json src/main/resources/vfxweaver.mixins.json src/client/resources/vfxweaver.client.mixins.json
git commit -m "build: template minecraft/java/compatibilityLevel per node"
```

### Task 0.6: Phase 0 gate — 26.1.2 parity

**Files:** none.

- [ ] **Step 1: Full build**

Run: `.\gradlew.bat build --no-daemon`
Expected: `BUILD SUCCESSFUL`; `versions/26.1.2/build/libs/vfxweaver-<mod_version>+26.1.2.jar` exists. The 1.21.11 node may still fail here — that is expected until Phase 1.

- [ ] **Step 2: Run the client and smoke-test**

Run: `.\gradlew.bat :26.1.2:runClient --no-daemon`
In-game: `/vfx list` shows effects; `/vfx play chromatic_aberration` renders; `/vfx play camera_shake` shakes. Exit.
Expected: no exceptions in the log related to VFX.

- [ ] **Step 3: No commit** (verification gate). If Step 1 or 2 fails, fix in the relevant task above before proceeding.

---

## Phase 1 — 1.21.11: shared/main code compiles

### Task 1.1: Global `Identifier` → `ResourceLocation` replacement

**Files:**
- Modify: `build.gradle`

**Interfaces:**
- Produces: on any node `< 26.1`, every `Identifier` token in processed sources becomes `ResourceLocation` (and `writeIdentifier`/`readIdentifier` → `…ResourceLocation`), reversibly.

- [ ] **Step 1: Add the replacement to a `stonecutter { }` block in `build.gradle`**

The `stonecutter { }` extension is configured in the **controller script** (`build.gradle`, set by `centralScript` in `settings.gradle`). Add this block at the end of `build.gradle`: 

```groovy
stonecutter {
	// 26.x renamed ResourceLocation -> Identifier (the shared source is written against 26.1).
	// For older nodes, replace the token back. Direction is the condition result.
	replacements.string(sc.current.parsed.matches('<26.1')) {
		replace 'Identifier', 'ResourceLocation'
	}
}
```

- [ ] **Step 2: Prove 26.1.2 is unaffected**

Run: `.\gradlew.bat :26.1.2:build --no-daemon`
Then: `Get-ChildItem versions\26.1.2\build\generated -Recurse -Filter *.java | Select-String -Pattern 'ResourceLocation'`
Expected: build `BUILD SUCCESSFUL`; the grep finds **zero** matches (26.1.2 keeps `Identifier`).

- [ ] **Step 3: Prove 1.21.11 sources are rewritten**

Run: `.\gradlew.bat :1.21.11:processResources --no-daemon` (this materializes generated sources without requiring full compilation)
Then: `Get-ChildItem versions\1.21.11\build\generated -Recurse -Filter *.java | Select-String -Pattern 'Identifier'`
Expected: zero `Identifier` matches (all became `ResourceLocation`). If the generated-sources path differs, locate it with `Get-ChildItem versions\1.21.11\build\generated -Recurse -Filter *.java | Select-Object -First 1` and use that root for the grep.

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "build: add Identifier->ResourceLocation replacement for pre-26.1 nodes"
```

### Task 1.2: Permission-API swap

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/java/dev/vfxweaver/command/VFXCommand.java` (permission check + imports)
- Modify: `src/main/java/dev/vfxweaver/network/VFXPayloads.java` (permission check + imports)

**Interfaces:**
- Consumes: `sc.current.parsed`.
- Produces: source line `//$ permission_check <receiver>` expands to `receiver.hasPermission(2)` on old nodes and `receiver.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))` on 26.1.

- [ ] **Step 1: Declare the swap in the `stonecutter` block of `build.gradle`**

```groovy
	swaps['permission_check'] = sc.current.parsed.matches('<26.1')
			? '$1.hasPermission(2)'
			: '$1.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))'
```

- [ ] **Step 2: Inspect the two call sites**

Run: `Select-String -Path src\main\java\dev\vfxweaver\command\VFXCommand.java, src\main\java\dev\vfxweaver\network\VFXPayloads.java -Pattern 'permissions\(\)|PermissionLevel|hasPermission' -Context 1,1`
Expected: you see the exact expressions to replace. They are the only `permissions()`/`PermissionLevel` uses in the codebase.

- [ ] **Step 3: Replace each permission expression with the swap**

For each call site, rewrite the expression so the expression itself is the swapped line, e.g. (receiver differs per site — `source` in the command, the player's command source in the payloads):

```java
		if (!(
			//$ permission_check source
			source.hasPermission(2)
		)) {
			// existing denial branch unchanged
		}
```

Keep the surrounding boolean logic and the denial branch exactly as they are; only the expression `source...hasPermission(...)` becomes the swap. For the payloads site substitute the correct receiver for `source`.

- [ ] **Step 4: Guard the permission imports for 26.1 only**

Wrap the `import net.minecraft.server.permissions.*;` lines in each file:

```java
//? if >=26.1 {
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
//?}
```

- [ ] **Step 5: Prove 26.1.2 is unchanged**

Run: `.\gradlew.bat :26.1.2:build --no-daemon`
Expected: `BUILD SUCCESSFUL`; the generated `VFXCommand.java` (under `versions\26.1.2\build\generated`) still contains `permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))`.

- [ ] **Step 6: Prove 1.21.11 got `hasPermission(2)`**

Run: `.\gradlew.bat :1.21.11:processResources --no-daemon`
Then: `Get-ChildItem versions\1.21.11\build\generated -Recurse -Filter VFXCommand.java | Select-String -Pattern 'hasPermission'`
Expected: `hasPermission(2)` and no `PermissionLevel` reference.

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/dev/vfxweaver/command/VFXCommand.java src/main/java/dev/vfxweaver/network/VFXPayloads.java
git commit -m "build: swap permission API for pre-26.1 nodes"
```

### Task 1.3: Main source compiles for 1.21.11

**Files:** possibly `build.gradle` (extra swaps/replacements) and `src/main/**` (guards).

**Known mappings to apply — add to `build.gradle` only as evidence appears:**

| 26.1.2 symbol | 1.21.11 symbol | Mechanism |
|---|---|---|
| `Identifier` | `ResourceLocation` | done (Task 1.1) |
| `source.permissions().hasPermission(...)` | `source.hasPermission(2)` | done (Task 1.2) |
| `IdentifierArgument` | `ResourceLocationArgument` | automatic via Task 1.1 replacement |
| `net.minecraft.server.permissions.*` imports | (absent) | `//? if` guard |
| `ResourceLoader.get(...).registerReloadListener(...)` | `ResourceManagerHelper.get(...).registerReloadListener(...)` **only if** the v1 `ResourceLoader` API is missing | verify first; prefer no change |
| `ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS` | parameter list may include/exclude `CloseableResourceManager` | `//? if` on the lambda parameters |
| `CommandSourceStack.sendSuccess(...)` | overload/supplier shape | swap or `//? if` |
| `Vec3.STREAM_CODEC` | present since 1.20.5 — expected unchanged | verify |

- [ ] **Step 1: Compile the main source for 1.21.11**

Run: `.\gradlew.bat :1.21.11:compileJava --no-daemon`
Expected: either `BUILD SUCCESSFUL` or a bounded list of errors.

- [ ] **Step 2: Resolve errors one at a time using the mapping table**

For each compiler error: identify the symbol, find its 1.21.11 replacement in the table (or via `javap` on the mapped jar — see Task 1.4), and apply the mechanism shown. Keep changes minimal and local. Repeat Step 1 after each fix.

- [ ] **Step 3: Verify 26.1.2 still builds after any guard/swap added**

Run: `.\gradlew.bat :26.1.2:compileJava --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit each logical fix separately**

```bash
git add <changed files>
git commit -m "build(<scope>): adapt <symbol> for 1.21.11"
```

### Task 1.4: Replacement audit script

**Files:**
- Create: `scripts/audit-replacements.ps1`

**Interfaces:**
- Consumes: generated sources under `versions/1.21.11/build/generated/`.
- Produces: a non-zero exit on stray replacements.

- [ ] **Step 1: Write the script**

```powershell
# Flags any change in the generated 1.21.11 sources that is NOT a pure
# Identifier<->ResourceLocation / permission-API rename.
# Exits 1 when a suspicious line is found.
param(
    [string]$Generated = "versions\1.21.11\build\generated",
    [string]$SharedRoot = "src"
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path $Generated)) {
    Write-Error "Generated sources not found at '$Generated' - build the 1.21.11 node first."
    exit 1
}

$generatedRoot = (Resolve-Path $Generated).Path
$bad = @()

Get-ChildItem $Generated -Recurse -Filter *.java | ForEach-Object {
    $rel = $_.FullName.Substring($generatedRoot.Length).TrimStart('\')
    if ($rel -notmatch 'java\\') { return }
    $tail = ($rel -split 'java\\', 2)[1]
    $shared = @(
        Join-Path $SharedRoot "main\java\$tail"
        Join-Path $SharedRoot "client\java\$tail"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $shared) { return }

    $diff = Compare-Object (Get-Content $shared) (Get-Content $_.FullName) |
            Where-Object { $_.SideIndicator -eq '=>' }
    foreach ($line in $diff) {
        $text = "$($line.InputObject)".Trim()
        if ($text -eq '') { continue }
        if ($text -match 'Identifier|ResourceLocation|hasPermission|permission_check') { continue }
        $bad += "$rel : $text"
    }
}

if ($bad.Count -gt 0) {
    Write-Host "Stray replacements ($($bad.Count)):" -ForegroundColor Red
    $bad | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "No stray replacements." -ForegroundColor Green
```

- [ ] **Step 2: Run the audit**

Run: `powershell -ExecutionPolicy Bypass -File scripts\audit-replacements.ps1`
Expected: `No stray replacements.` If it reports lines, inspect each — a genuine stray means a replacement matched text it should not have; fix by adding a replacement identifier and `//~ !ident` in the offending file (see spec §5).

- [ ] **Step 3: Commit**

```bash
git add scripts/audit-replacements.ps1
git commit -m "chore(build): add replacement audit script"
```

---

## Phase 1a — 1.21.11 render API probe

### Task 1.5: Probe the render symbols before porting the client

**Files:** none (investigation only).

- [ ] **Step 1: Locate the mapped 1.21.11 jar**

Run: `Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom" -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '1\.21\.11' } | Select-Object -First 20 FullName`
Expected: a mapped/named Minecraft jar path. If none, run `.\gradlew.bat :1.21.11:dependencies --no-daemon` first to force Loom to download it.

- [ ] **Step 2: Check each symbol from spec §7**

Run (substitute the jar path found above for `<JAR>`):

```powershell
$jar = "<JAR>"
$names = @(
 'com/mojang/blaze3d/pipeline/RenderPipeline',
 'com/mojang/blaze3d/pipeline/RenderPipeline$Builder',
 'net/minecraft/client/renderer/RenderPipelines',
 'net/minecraft/client/renderer/rendertype/RenderSetup',
 'net/minecraft/client/renderer/rendertype/RenderType',
 'net/minecraft/client/renderer/SubmitNodeCollector',
 'net/minecraft/client/renderer/state/level/CameraRenderState',
 'net/minecraft/client/renderer/MappableRingBuffer',
 'net/minecraft/client/renderer/ProjectionMatrixBuffer'
)
foreach ($n in $names) {
  $hit = & jar tf $jar 2>$null | Select-String -SimpleMatch "$n.class"
  "{0,-70} {1}" -f $n, ($(if ($hit) {'present'} else {'MISSING'}))
}
```

- [ ] **Step 3: Check the fields/methods the code calls**

Run (same jar):

```powershell
javap -classpath $jar net.minecraft.client.renderer.RenderPipelines 2>&1 | Select-String 'POST_PROCESSING_SNIPPET|MATRICES_FOG_LIGHT_DIR_SNIPPET'
javap -classpath $jar net.minecraft.client.renderer.rendertype.RenderSetup 2>&1 | Select-String 'builder|withTexture|createRenderSetup'
javap -classpath $jar net.minecraft.client.renderer.LevelRenderer 2>&1 | Select-String 'submitModel|submitCustomGeometry|submitMovingBlock'
```

Expected: decide, per symbol, whether the shared 26.1 code compiles unchanged on 1.21.11. Record the result as a comment in the spec file (`docs/superpowers/specs/2026-09-14-multiversion-stonecutter-design.md` §7) under the corresponding bullet.

- [ ] **Step 4: Commit the findings**

```bash
git add docs/superpowers/specs/2026-09-14-multiversion-stonecutter-design.md
git commit -m "docs: record 1.21.11 render API probe results"
```

---

## Phase 2 — 1.21.11: client code compiles

### Task 2.1: Post-processing backend

**Files:** `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java`, `.../VFXPostProcessingManager.java` (guards), and possibly `build.gradle`.

- [ ] **Step 1: Compile the client for 1.21.11**

Run: `.\gradlew.bat :1.21.11:compileClientJava --no-daemon`
Expected: errors localized to these files (if the probe found the symbols, likely none).

- [ ] **Step 2: Fix using the probe results**

For each error: if only a method/field was renamed, add a `//? if >=26.1 { … //?} else { /*…*/ //?}` guard or a swap; if the whole snippet is structurally different, wrap it in a whole-file guard (spec §5 Tier C). Do not duplicate the shader bodies.

- [ ] **Step 3: Verify both nodes**

Run: `.\gradlew.bat :26.1.2:compileClientJava :1.21.11:compileClientJava --no-daemon`
Expected: `BUILD SUCCESSFUL` for both.

- [ ] **Step 4: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/postprocessing build.gradle
git commit -m "build(client): adapt post-processing pipeline for 1.21.11"
```

### Task 2.2: World/entity/frame renderers

**Files:** `src/client/java/dev/vfxweaver/client/render/*.java`.

- [ ] **Step 1: Compile**

Run: `.\gradlew.bat :1.21.11:compileClientJava --no-daemon`
Expected: errors localized to `render/*`.

- [ ] **Step 2: Fix per symbol**

Apply guards/swaps per error, following the probe table. Keep render geometry logic identical; only API shapes change.

- [ ] **Step 3: Verify both nodes**

Run: `.\gradlew.bat :26.1.2:build :1.21.11:build --no-daemon`
Expected: `BUILD SUCCESSFUL` for both (or errors only in `mixin/*`, handled next).

- [ ] **Step 4: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/render
git commit -m "build(client): adapt world/entity renderers for 1.21.11"
```

### Task 2.3: Mixins and the access widener

**Files:** `src/client/java/dev/vfxweaver/client/mixin/*.java`, `src/main/resources/vfxweaver.accesswidener`.

- [ ] **Step 1: Compile**

Run: `.\gradlew.bat :1.21.11:build --no-daemon`
Expected: errors localized to mixin classes (descriptor strings, shadowed fields/methods).

- [ ] **Step 2: Add the access widener condition if needed**

If a widened member does not exist on 1.21.11, guard it in the single aw file:

```
#? if >=26.1
accessible field net/minecraft/client/particle/Particle xd D
#?}
```

(Repeat per member that is version-specific; Stonecutter processes the aw `#` syntax natively.)

- [ ] **Step 3: Fix mixins**

For each mixin, adjust `@Shadow`/`@Inject`/`@Redirect` signatures with `//? if` guards or a whole-file guard. Note: a wrong mixin descriptor fails at **runtime**, not compile time — Step 4 is mandatory.

- [ ] **Step 4: Verify both nodes build and the mixin configs load on 1.21.11**

Run: `.\gradlew.bat :26.1.2:build :1.21.11:build --no-daemon`
Then: `.\gradlew.bat :1.21.11:runClient --no-daemon`
Expected: build green; the client starts with no `Mixin apply failed` in the log. Exit the client.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/mixin src/main/resources/vfxweaver.accesswidener
git commit -m "build(client): adapt mixins and access widener for 1.21.11"
```

---

## Phase 3 — Runtime parity, CI, docs

### Task 3.1: 1.21.11 runtime smoke test

**Files:** none (verification). Fix in the relevant file if it fails.

- [ ] **Step 1: Run the client**

Run: `.\gradlew.bat :1.21.11:runClient --no-daemon`
Expected: client boots without exceptions.

- [ ] **Step 2: Exercise the feature matrix**

In-game, run each and confirm the visible result matches 26.1.2:
`/vfx list`; `/vfx play chromatic_aberration`; `/vfx play blur`; `/vfx play camera_shake`; `/vfx play block_outline`; `/vfx play entity_tint`; a `/reload` with a datapack effect.
Expected: effects render/shake; no errors in the log.

- [ ] **Step 3: Commit only if Step 2 required fixes.**

```bash
git add <changed files>
git commit -m "fix(<scope>): 1.21.11 runtime parity"
```

### Task 3.2: CI matrix

**Files:** `.github/workflows/build.yml`.

- [ ] **Step 1: Replace the `build` job with a matrix**

```yaml
  build:
    runs-on: ubuntu-24.04
    strategy:
      fail-fast: false
      matrix:
        include:
          - node: "26.1.2"
            java: "25"
          - node: "1.21.11"
            java: "21"
    steps:
      - name: checkout repository
        uses: actions/checkout@v6
      - name: validate gradle wrapper
        uses: gradle/actions/wrapper-validation@v6
      - name: setup jdk
        uses: actions/setup-java@v5
        with:
          java-version: ${{ matrix.java }}
          distribution: 'microsoft'
      - name: make gradle wrapper executable
        run: chmod +x ./gradlew
      - name: build ${{ matrix.node }}
        run: ./gradlew :${{ matrix.node }}:build
      - name: capture build artifacts
        uses: actions/upload-artifact@v7
        with:
          name: Artifacts-${{ matrix.node }}
          path: versions/${{ matrix.node }}/build/libs/
```

Keep the `release` job as-is but point its artifact download at both matrix outputs:

```yaml
      - name: download build artifacts
        uses: actions/download-artifact@v7
        with:
          path: build/libs
          merge-multiple: true
```

- [ ] **Step 2: Sanity-check the YAML locally**

Run: `git diff --check`
Expected: no whitespace errors. (CI itself is verified on push.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: build every Stonecutter node with its JDK"
```

### Task 3.3: Documentation

**Files:** `docs/GUIDE.md`, `README.md`, `AGENTS.md`.

- [ ] **Step 1: `AGENTS.md` — add a multi-version section**

Add under "Build and verify":

```markdown
## Multi-version (Stonecutter)

Supported nodes: `26.1.2` and `1.21.11`. Shared source lives in `src/`; per-node deps in
`versions/<mc>/gradle.properties`. Build one node with `./gradlew :<mc>:build`, all nodes
with `./gradlew build`.

Adding a feature: write it once in `src/`. Only if it touches an API that differs between
targets, guard it in place with `//? if <cond { … //?}` (or a swap declared in
`build.gradle`). Never fork a whole feature per version. After adding a version-specific
change, run `powershell -File scripts/audit-replacements.ps1`.
```

- [ ] **Step 2: `docs/GUIDE.md` — add a changelog entry**

Append to the changelog at the bottom:

```markdown
- **v27**: build restructured for multiple Minecraft versions (Stonecutter). Adds a
  `1.21.11` build alongside `26.1.2`; effect behavior, datapack format and network
  protocol are unchanged.
```

- [ ] **Step 3: `README.md` — mention supported versions**

Update the supported-version line to list `26.1.2` and `1.21.11`.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md docs/GUIDE.md README.md
git commit -m "docs: document multi-version build workflow"
```

---

## Phase 3 gate

- [ ] `.\gradlew.bat build --no-daemon` is green for both nodes.
- [ ] Jars: `versions/26.1.2/build/libs/vfxweaver-<v>+26.1.2.jar` and `versions/1.21.11/build/libs/vfxweaver-<v>+1.21.11.jar`; unpack each and confirm `fabric.mod.json` has the right `minecraft`/`java`.
- [ ] `.\gradlew.bat :1.21.11:runClient --no-daemon` smoke test passed (Task 3.1).
- [ ] `powershell -File scripts/audit-replacements.ps1` prints `No stray replacements.`

## Deferred (not in this plan)

- Minecraft 1.21.1 (legacy renderer) — separate spec, per `docs/superpowers/specs/2026-09-14-multiversion-stonecutter-design.md` §12.
- Fletching Table (automatic mixin/entrypoint registration) — optional later.

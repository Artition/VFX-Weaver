# Step 1 — Depth Access and World Reconstruction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that we can read scene depth in a screen-space pass on MC 26.2 (Fabric and NeoForge, with and without Iris) and reconstruct a world position from it, so the beam, the surface pattern and world-space masks have a foundation.

**Architecture:** A throwaway debug post pass behind a temporary effect id renders linearized depth and then reconstructed world position; nothing ships to users, and the pass is removed once the answer is recorded. The real depth plumbing is written only after the probe answers the three open questions in §10 of the spec.

**Tech Stack:** Java 25 (JDK 26 build), Gradle 9.5.1, Stonecutter 0.9.8, Fabric Loom 1.17-SNAPSHOT, ModDevGradle 2.0.147, GLSL through `RenderPipelines.POST_PROCESSING_SNIPPET`.

**Spec:** `docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md` (§5 Depth mechanism, §10 Open items).

## Global Constraints

- One shared `src/`; no `net.fabricmc.*` / `net.neoforged.*` imports outside `dev.vfxweaver.platform` and `dev.vfxweaver.client.platform`.
- Indentation is tabs; non-reassigned params/locals are `final`; public methods carry javadoc.
- No new dependencies.
- There is **no test suite**: every task is verified by (a) building all nodes and (b) an in-game check by the human partner. `javap` against the real 26.2 jar is the method for API questions.
- Build commands (from `AGENTS.md`): `.\gradlew.bat :<node>:build`; Fabric nodes are `26.2`, `26.1.2`, `1.21.11`; NeoForge nodes are the same names with `-neoforge`.
- The probe is **throwaway**: it must not change any shipped effect, format or protocol. Mark it with a `ponytail:` comment naming its removal point.
- A shader's parameter block is a std140 UBO: names in the shader's `Config` block and in `VFXShaderPrograms.registerPost(...)` must be the same set in the same order. Never name a uniform after a GLSL built-in.

---

### Task 1: Answer how depth can be bound in a 26.2 post pass

**Files:**
- Create: `docs/superpowers/specs/notes/2026-09-19-depth-findings.md` (record of the probe's answer)

**Interfaces:**
- Produces: a written answer to three questions — (1) is the main depth target bindable as a post-pass input, (2) under which name/id, (3) does the same path exist on NeoForge — used by every later task and by spec §5.

- [ ] **Step 1: Read the vanilla post-pass input types**

Run (note the single quotes — `$` in double quotes is a PowerShell variable and will silently truncate the class name):

```powershell
$cp = "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-clientonly-deobf\26.2\minecraft-clientonly-deobf-26.2.jar"
& 'C:\Program Files\Java\jdk-26\bin\javap.exe' -p -classpath $cp 'net.minecraft.client.renderer.PostPass$InputTexture'
& 'C:\Program Files\Java\jdk-26\bin\javap.exe' -p -classpath $cp 'net.minecraft.client.renderer.PostPass$TargetInput'
& 'C:\Program Files\Java\jdk-26\bin\javap.exe' -p -classpath $cp 'net.minecraft.client.renderer.PostChain$TargetBundle'
& 'C:\Program Files\Java\jdk-26\bin\javap.exe' -p -classpath $cp 'net.minecraft.client.renderer.PostChainConfig$TargetInput'
& 'C:\Program Files\Java\jdk-26\bin\javap.exe' -p -classpath $cp 'net.minecraft.client.renderer.PostPass'
```

Expected: the nested types resolve (the previous attempt printed "non-NULL" errors because `$InputTexture` was expanded as an empty variable). Record for each: what it wraps (a `RenderTarget`, a `TextureTarget`, depth), and whether it exposes a depth texture/attachment.

- [ ] **Step 2: Find how the game itself consumes depth**

Run:

```powershell
$cp = "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-clientonly-deobf\26.2\minecraft-clientonly-deobf-26.2.jar"
& 'C:\Program Files\Java\jdk-26\bin\javap.exe' -p -c -classpath $cp 'net.minecraft.client.renderer.PostChain' > "$env:TEMP\postchain.txt"
Select-String -Path "$env:TEMP\postchain.txt" -Pattern 'Depth|depth|MAIN_TARGET_ID|TargetBundle' | ForEach-Object { $_.Line.Trim() } | Select-Object -First 40
```

Expected: whether `MAIN_TARGET_ID`/`TargetBundle` carry a depth texture, and which identifiers the vanilla chain refers to. Record exact identifier names found.

- [ ] **Step 3: Check our own pass registration against those types**

Read `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` (the `registerPost` method and the `RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)` block around lines 100–170) and `VFXPostProcessingManager` (target creation/`ensureTargets`). Record: which inputs our passes currently declare (`InSampler`, `HistSampler`, `SamplerInfo` uniform) and whether a depth sampler can be added to the same layout without touching the shared pass chain.

- [ ] **Step 4: Write the findings file**

`docs/superpowers/specs/notes/2026-09-19-depth-findings.md` must contain: the three questions, the exact javap output lines that answer them, and one of the two verdicts — *depth is bindable (name: …)* or *depth is not bindable through the post-pass input API* (in which case list the fallbacks found: vanilla `PostChainConfig` depth input, `FrameGraphBuilder` external resource, or an own depth copy).

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/notes/2026-09-19-depth-findings.md
git commit -m "docs: record depth-binding findings for 26.2 post passes"
```

---

### Task 2: Throwaway debug pass that outputs linearized depth

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/post/debug_depth.fsh`
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` (register the debug pass)
- Create: `src/main/resources/data/vfxweaver/vfx/debug_depth.json` (temporary built-in effect)
- Create: `src/client/java/dev/vfxweaver/client/postprocessing/VFXDebugDepthState.java` (holds the linearization uniforms)

**Interfaces:**
- Consumes: the verdict and identifier names from Task 1.
- Produces: `VFXShaderPrograms.registerPost(VFXEffectType.DEBUG_DEPTH, "near_far")` — the pattern Task 3 extends with the inverse view-projection.

- [ ] **Step 1: Add the shader**

Create `debug_depth.fsh` following the existing post shaders' structure (copy the header/`Config` block style from `post/copy.fsh`). Body: sample the depth input declared in Task 1, linearize with `near`/`far`, write it as `vec3(depth)` into the output. Mark the file with a `ponytail: throwaway probe, delete with the debug pass`.

```glsl
// Config
layout(std140) uniform DebugDepthConfig {
	float near_plane;
	float far_plane;
};
```

- [ ] **Step 2: Register the pass**

In `VFXShaderPrograms`, add a registration next to the existing `registerPost(...)` calls, using the same builder shape already used in the file:

```java
	registerPost(VFXEffectType.DEBUG_DEPTH, "near_far");
```

and extend `VFXShaderPrograms`'s parameter table so `near_far` maps to the two floats in the UBO **in the order declared** (the offsets are positional).

- [ ] **Step 3: Add the temporary effect definition**

`src/main/resources/data/vfxweaver/vfx/debug_depth.json` — a normal effect of the debug type with a long duration and no positions, so it can be triggered with `/vfx play debug_depth`.

- [ ] **Step 4: Build every node**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six. A failure in `26.1.2`/`1.21.11` means the debug code was not guarded — the probe must compile on every line or be guarded behind `//? if >=26.2`, exactly like the rest of the post code.

- [ ] **Step 5: In-game check (Fabric 26.2)**

Install `versions/26.2/build/libs/vfxweaver-<version>+26.2.jar` into the `26.2test` instance, launch, run `/vfx play debug_depth`, and ask the human partner to report: does the screen show a depth gradient (near = dark, far = white), and does it update when moving? Expected: a smooth, correct gradient and no black screen. A black screen means the shader failed to compile (check the log for the unknown-parameter warning and for dropped resource packs).

- [ ] **Step 6: Commit**

```bash
git add src/client/resources/assets/vfxweaver/shaders/post/debug_depth.fsh src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java src/main/resources/data/vfxweaver/vfx/debug_depth.json
git commit -m "feat(debug): throwaway depth-linearization post pass"
```

---

### Task 3: Prove world-position reconstruction

**Files:**
- Modify: `src/client/resources/assets/vfxweaver/shaders/post/debug_depth.fsh` (add inverse view-projection)
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` (add the matrix uniform)
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXDebugDepthState.java` (write the matrix each frame)

**Interfaces:**
- Consumes: the depth input from Task 2.
- Produces: a verified `world_pos` reconstruction (GLSL snippets) reused verbatim by the beam and the surface pattern.

- [ ] **Step 1: Feed the inverse view-projection**

In `VFXDebugDepthState`, each frame take the camera's view-projection matrix, invert it, and upload it; add the matching `mat4 inv_view_proj` to the shader's `Config` block **after** the existing two floats (the UBO is positional).

- [ ] **Step 2: Reconstruct in the shader**

```glsl
	vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
	vec4 world = inv_view_proj * clip;
	world /= world.w;
```

Output `fract(world.xyz)` so the human partner can see the world grid move correctly with the camera instead of swimming with the screen.

- [ ] **Step 3: Build every node**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six.

- [ ] **Step 4: In-game check (Fabric 26.2)**

Ask the human partner: does `fract(world.xyz)` stay anchored to the world while turning the camera (correct) or does it follow the screen (incorrect — the matrix is wrong)? Expected: world-anchored.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/postprocessing/VFXDebugDepthState.java src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java src/client/resources/assets/vfxweaver/shaders/post/debug_depth.fsh
git commit -m "feat(debug): reconstruct world position from depth"
```

---

### Task 4: Verify NeoForge and Iris

**Files:**
- Modify: `docs/superpowers/specs/notes/2026-09-19-depth-findings.md` (results)

**Interfaces:**
- Consumes: the working debug pass from Task 3.
- Produces: the cross-loader/cross-shaderpack verdict that gates the whole screen-space family.

- [ ] **Step 1: NeoForge check**

Install `versions/26.2-neoforge/build/libs/vfxweaver-<version>+26.2-neoforge.jar` into `26.2neoforge_test`, launch, `/vfx play debug_depth`. Ask the human partner to confirm the same correct result. Expected: identical behaviour; the access transformer or a different input binding in the NeoForge path must not be needed. If it differs, record the difference verbatim in the findings file.

- [ ] **Step 2: Iris check**

Ask the human partner to launch with shaders enabled and repeat. Expected: either the depth probe still works, or a recorded, specific failure (what the log says, what the screen shows). Do not guess — record what was observed.

- [ ] **Step 3: Record results and remove the probe**

Append the NeoForge and Iris results to `docs/superpowers/specs/notes/2026-09-19-depth-findings.md`, then delete the throwaway pieces (`debug_depth.fsh`, the `DEBUG_DEPTH` registration, `debug_depth.json`, `VFXDebugDepthState`, the `DEBUG_DEPTH` effect type) — the `ponytail:` markers name them.

- [ ] **Step 4: Build every node again after removal**

Run: `.\gradlew.bat :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build --console=plain`
Expected: `BUILD SUCCESSFUL` for all six, with no debug symbols left in the jars (check with `jar tf` that `debug_depth` is absent from `assets/` and `data/`).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: depth findings for 26.2; remove the throwaway probe"
```

---

### Task 5: Update the spec with the verified depth mechanism

**Files:**
- Modify: `docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md` (§5, §10.1, §10.5)

**Interfaces:**
- Produces: a spec whose §5 states the verified mechanism (the exact input name, linearization, matrix) instead of an open question — the input for the Step 2 (uniform graph) plan.

- [ ] **Step 1: Rewrite §5 with the verified mechanism**

Replace the paragraph beginning "A shared "scene depth → world position" step ..." with the verified version: the exact input/binding used, how depth is linearized, and how the world position is reconstructed (the snippet proven in Task 3).

- [ ] **Step 2: Close the open items**

In §10, remove items 1 and 5 if answered (mark them with the observed result), or restate them precisely if they were answered negatively (including the chosen fallback).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-19-effect-graph-and-masks-design.md
git commit -m "docs: fold verified depth mechanism into the spec"
```

---

## Self-Review

**Spec coverage:** This plan implements spec §9 step 1 ("Depth + world reconstruction (prerequisite, small)") and closes the §10 items 1 and 5 that gate it. Steps 2–6 of the spec (uniform graph, masks, fields, surface pattern, sparks) are deliberately **not** in this plan: per the writing-plans scope check each is an independently shippable subsystem and gets its own plan, and step 2's plan depends on this one's recorded findings (in particular whether a post pass may read depth at all).

**Placeholder scan:** no "TBD"/"handle edge cases"; every step has a command or code. Where a name is unknown it is the explicit deliverable of Task 1 rather than an invented identifier.

**Type consistency:** `VFXEffectType.DEBUG_DEPTH` and `registerPost(VFXEffectType.DEBUG_DEPTH, "near_far")` are used identically in Tasks 2 and 4; the shader's `Config` block ordering (`near_plane`, `far_plane`, then `inv_view_proj`) matches the order the plan tells the Java side to upload.

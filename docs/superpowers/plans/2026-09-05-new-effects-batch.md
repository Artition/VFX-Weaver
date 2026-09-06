# New Effects Batch — v1.1.0 Implementation Plan (rev. 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Rev. 2:** incorporated the external senior-review pass. All blockers (eyelids, noise_warp, double_vision, vhs, feedback/stop_motion, slice_shift aspect, block_displace precision, entity_displace normals, digital_glitch gating) are fixed inline below. Affected tasks are marked **[review-fixed]**.

**Goal:** Add the v1.1.0 effect batch: screen effects (`slice_shift`, `noise_warp`, `solarize`, `double_vision`, `eyelids`, `iris_wipe`, `digital_glitch`, `vhs`, `shockwave`, `afterimage`, `stop_motion`), entity/world geometry (`entity_displace`, `block_displace`, `god_rays`, `light_beam`, `pulse_ring`, `scan_sweep`, `guide_line`) and HUD/camera misc (`hud_fade`, `camera_roll`) — each fully registered, built-in defined, documented.

**Architecture:** Screen effects follow the existing chain: fsh in `assets/vfxweaver/shaders/post/`, `registerPost(...)` in `VFXShaderPrograms.register()`, enum constant + `neutralValue()` cases in `VFXEffectType`, built-in in `VFXDefinitionManager.registerBuiltIns()`. `entity_displace`/`god_rays` are second-pass model re-emission through the existing `LivingEntityRendererMixin` loop + `VFXEntityEffectRenderer` (`submitCustomGeometry`). `block_displace` re-emits baked block-model quads (existing `VFXWorldOverlayRenderer`). No new shader mechanism needed for displace — CPU hash per vertex.

**Tech Stack:** Java 25, Fabric 0.19.3, MC 26.1.2 (official mappings), GLSL 330.

**Spec:** [docs/superpowers/specs/2026-09-05-new-effects-batch.md](../specs/2026-09-05-new-effects-batch.md) (authoritative for effect ideas and param semantics; this plan pins the exact shipped params and shader bodies).

## Global Constraints

- Params are floats; modes are numeric (0/1) — no string params.
- **`time` plumbing (unified, [review-fixed]):** any shader that animates procedurally lists `"time"` last in its `registerPost(...)` Config array. `VFXPostProcessingManager` auto-fills it from `effect.getAge()` in **ticks** (see the existing `film_grain`/`scanlines` precedent at `VFXPostProcessingManager.VFXPass.execute`). No built-in `time` param and no `neutralValue("time")` case. Convert to seconds in-shader with `float t = time / 20.0`.
- Built-in fade convention: the main param (`shift`, `amplitude`, `intensity`, `openness`, `radius`, `fps`, ...) is animated to `0` (or its neutral) in the built-in definitions (`param(name, start, end)`).
- `neutralValue()` cases: every new animated param gets a case so the fade weight blends toward neutral. Params that must not be faded (time, positions, colors, seed, chance) stay `Float.NaN`.
- **Pipeline naming convention (unified, [review-fixed]):** adopt the codebase's existing pairing — `*_VISIBLE` = `CompareOp.ALWAYS_PASS` (shows through terrain), `*_OCCLUDED` = `CompareOp.LESS_THAN_OR_EQUAL` (occluded by terrain). `through_blocks ≥ 0.5` routes to `_VISIBLE`. Do **not** swap the labels between entity and block flavours.
- **CPU hash helper (shared, [review-fixed]):** one `VFXNoise` utility class (stateless, `final class` + private ctor — see `SimplexNoise` pattern) used by both `entity_displace` and `block_displace`:
  ```java
  // dev.vfxweaver.client.render.VFXNoise (or client.noise, colocate with SimplexNoise)
  public final class VFXNoise {
      private VFXNoise() { }
      /** Quantised hash in [-1, 1]: 21 discrete steps give the "snap glitch" look. */
      public static float vhash(float x, float y, float z, float seed) {
          float h = x * 37.719F + y * 71.317F + z * 151.589F + seed * 31.7F;
          return (float) Math.floor(fract(h) * 21.0F) / 21.0F * 2.0F - 1.0F;
      }
      public static float fract(float v) { return v - (float) Math.floor(v); }
      /** Wraps a coordinate into [-4096, 4096) so world coords (±30M) keep float precision. [review-fixed] */
      public static float wrap(final float v) { return ((v % 4096.0F) + 4096.0F) % 4096.0F; }
  }
  ```
- **`expr` secrets note [review-fixed]:** `/vfx play*/... {[name:value]}` accepts **floats only** (`ParamMapArgument`). Animated/stepped `seed` must come from a **datapack** parameter (`"seed": { "expr": "floor(t * 8) * 0.1" }`), not from the command. Plan test commands below use plain floats; a datapack snippet is given where the stepped-seed look is the point.
- Docs (Task 17) must document exactly the params that ship in the built-ins — `docs ≠ /vfx play` is a bug.
- `gradlew build` green after every task (JDK 25 in `JAVA_HOME`).
- New mixins (Gui, any new) must be added to `src/client/resources/vfxweaver.client.mixins.json`.
- `VFXEffectManager.rebuildEntityEffectsIndex` currently indexes only `ENTITY_TINT`/`ENTITY_OUTLINE` — **extend it** to every type that targets entities by UUID (`ENTITY_DISPLACE`, `GOD_RAYS`), otherwise the render mixins never see them.
- `VFXEffectType.isPostProcessing()` / `isWorldOverlay()` must be updated for every new type so effects route to the right consumer (post chain vs world-overlay renderer vs entity mixin vs misc).

---

### Task 1: `hud_fade` — spike first [review-fixed]

**Why first:** the riskiest effect (grabbing the vanilla HUD alpha globally). Spike it before the batch so a dead end is found early, not after 8 effects.

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/hud/HudFadeState.java`
- Create: `src/client/java/dev/vfxweaver/client/mixin/GuiMixin.java`
- Modify: `src/client/java/dev/vfxweaver/client/mixin/GameRendererMixin.java` (drive the per-frame value if needed)
- Modify: `src/client/resources/vfxweaver.client.mixins.json`, `src/main/java/dev/vfxweaver/effect/VFXEffectType.java`, `src/main/java/dev/vfxweaver/resource/VFXDefinitionManager.java`

- [ ] **Step 1 — spike: compute + draw.** Implement `HudFadeState`:
  ```java
  public final class HudFadeState {
      private HudFadeState() { }
      /** Effective HUD opacity (1 = normal) and chat opacity, from all active hud_fade effects. */
      public static void compute(final VFXEffectManager manager, float[] outOpacity, float[] outChat) {
          float opacity = 1.0F; float chat = 1.0F;
          for (VFXActiveEffect e : manager.getActiveHudFades()) {
              float fade = (1.0F - Mth.clamp(e.getParam("opacity", 0.0F), 0.0F, 1.0F)) * e.getWeight();
              opacity *= (1.0F - fade);
              chat    *= (1.0F - fade * Mth.clamp(e.getParam("chat", 1.0F), 0.0F, 1.0F));
          }
          outOpacity[0] = opacity; outChat[0] = chat;
      }
  }
  ```
  (add `getActiveHudFades()` to `VFXEffectManager`). Add a debug mixin on `Gui.render` HEAD/TAIL using `RenderSystem.setShaderColor(1,1,1,opacity)` / restore `(1,1,1,1)`. Run `gradlew runClient` + `/vfx play vfxweaver:hud_fade`, and check **which elements actually respect the global shader color**: hotbar, hearts/hunger, XP bar, crosshair, boss bar, chat. Screenshot each.
- [ ] **Step 2 — finalise the hooking.** Based on the spike: if chat is not covered (its opacity is set later in `Gui.render`, likely), add a dedicated chat hook (inject near where chat render sets its own color/opacity; see how vanilla structures chat in 26.1). If non-HUD elements leak the color, restore at TAIL unconditionally and, if some element overrides mid-render, switch to per-method wrappers (`renderHotbar`, `renderPlayerHealth`, `renderCrosshair`, `renderExperienceBar`, ...) with `setShaderColor`/restore around each — the professor's documented fallback.
- [ ] **Step 3 — enum + built-in.** `VFXEffectType.HUD_FADE("hud_fade")`; `isPostProcessing()` excludes it; no `neutralValue` needed (params are user-driven, no fade-weight blending; weight is multiplied in `compute`). Built-in:
  ```java
  builtIn("vfxweaver", "hud_fade", VFXEffectType.HUD_FADE, 40, EasingType.EASE_IN_OUT_CUBIC,
      param("opacity", 0.0F, 1.0F),  // 0 = hidden -> 1 = normal at the end (fade back)
      param("chat", 1.0F))
  ```
- [ ] **Step 4: build + visual test + Commit** — `feat(hud): hud_fade effect (opacity, chat)`. Document the actual element coverage observed.

---

### Task 2: `slice_shift` screen effect

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/post/slice_shift.fsh`
- Modify: `VFXEffectType.java`, `VFXShaderPrograms.java`, `VFXDefinitionManager.java`

**Interfaces:** `VFXEffectType.SLICE_SHIFT`, shader `vfxweaver:post/slice_shift`, Config order `angle, offset, shift, mirror`.

- [ ] **Step 1: enum + neutral.** `SLICE_SHIFT("slice_shift")`; `neutralValue`: `"shift" -> 0.0F`, else `NaN`.
- [ ] **Step 2: register.** `registerPost(VFXEffectType.SLICE_SHIFT, "angle", "offset", "shift", "mirror")`.
- [ ] **Step 3: shader [review-fixed]** — aspect space used for the side/line math, mapped back to raw UV for sampling:
  ```glsl
  #version 330
  uniform sampler2D InSampler; in vec2 texCoord;
  layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
  layout(std140) uniform Config { float angle; float offset; float shift; float mirror; };
  out vec4 fragColor;
  void main() {
      vec2 asp = vec2(InSize.x / InSize.y, 1.0);
      vec2 ac = texCoord * asp;
      float a = radians(angle);
      vec2 n = vec2(cos(a + 1.5707963), sin(a + 1.5707963));
      vec2 lp = vec2(0.5, 0.5) * asp + n * offset;
      float side = sign(dot(ac - lp, n));
      if (side == 0.0) side = 1.0;
      vec2 shifted = (ac - side * shift * vec2(cos(a), sin(a))) / asp;
      vec2 wrapped = fract(shifted);
      vec2 mirrored = abs(2.0 * fract(shifted / 2.0) - 1.0);
      fragColor = texture(InSampler, mix(wrapped, mirrored, mirror));
  }
  ```
  Verify orientation vs `blur_x.fsh` (same `texCoord` convention). `shift == 0` is identity.
- [ ] **Step 4: built-in** — `shift` 0.05 → 0, `angle` 0, `offset` 0, `mirror` 0, duration 40, `EASE_IN_OUT_CUBIC`.
- [ ] **Step 5: build + visual test** (`gradlew build`; `/vfx play vfxweaver:slice_shift {[angle:25],[shift:0.12]}`). On 16:9, 45° must look like 45°.
- [ ] **Step 6: Commit** — `feat(post): slice_shift screen effect`.

---

### Task 3: `noise_warp` screen effect [review-fixed]

**Files:** same four as Task 2; Config order `scale, amplitude, contrast, coherence, speed, drift_x, drift_y, time`.

- [ ] **Step 1: enum + neutral.** `NOISE_WARP("noise_warp")`; `neutralValue`: `"amplitude" -> 0.0F`, else `NaN`.
- [ ] **Step 2: register.** `registerPost(VFXEffectType.NOISE_WARP, "scale", "amplitude", "contrast", "coherence", "speed", "drift_x", "drift_y", "time")` — `time` last (auto-filled, see Global Constraints).
- [ ] **Step 3: shader** — the professor's fixed version (field animated by **seconds**-based `t`, gradient + smooth per-cell directions, `contrast` actually used):
  ```glsl
  #version 330
  uniform sampler2D InSampler; in vec2 texCoord;
  layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
  layout(std140) uniform Config { float scale; float amplitude; float contrast; float coherence;
                                  float speed; float drift_x; float drift_y; float time; };
  out vec4 fragColor;
  float hash(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }
  float vnoise(vec3 p) {
      vec3 i = floor(p); vec3 f = fract(p);
      vec3 s = f * f * (3.0 - 2.0 * f);
      float a = hash(i), b = hash(i + vec3(1,0,0)), c = hash(i + vec3(0,1,0)), d = hash(i + vec3(1,1,0));
      float e = hash(i + vec3(0,0,1)), f2 = hash(i + vec3(1,0,1)), g = hash(i + vec3(0,1,1)), h = hash(i + vec3(1,1,1));
      return mix(mix(mix(a,b,s.x), mix(c,d,s.x), s.y), mix(mix(e,f2,s.x), mix(g,h,s.x), s.y), s.z);
  }
  void main() {
      float t = time / 20.0;                                   // seconds
      vec2 corr = texCoord * scale; corr.x *= InSize.x / InSize.y;
      vec3 field = vec3(corr + vec2(drift_x, drift_y) * t, t * speed);   // animated, not static
      float n = vnoise(field);
      float mag = pow(clamp(n, 0.0, 1.0), max(contrast, 0.1));          // contrast curve
      vec2 grad = vec2(vnoise(field + vec3(0.05,0,0)) - vnoise(field - vec3(0.05,0,0)),
                       vnoise(field + vec3(0,0.05,0)) - vnoise(field - vec3(0,0.05,0)));
      vec2 rndDir = normalize(vec2(vnoise(field + vec3(13.7,0,0)) - 0.5,
                                   vnoise(field + vec3(71.3,0,0)) - 0.5) + vec2(1.0e-5));
      vec2 dir = normalize(mix(rndDir, grad, clamp(coherence, 0.0, 1.0)) + vec2(1.0e-5));
      fragColor = texture(InSampler, texCoord + dir * mag * amplitude);
  }
  ```
  Perf note [review-fixed]: 7 `vnoise` calls ≈ 56 hashes/pixel is acceptable for a post pass. Do **not** add octaves; if it ever shows up in profiling, switch the gradient to forward-difference (reuse the center sample, 2 taps instead of 4).
- [ ] **Step 4: built-in** — `scale` 8, `amplitude` 0.03 → 0, `contrast` 2, `coherence` 1, `speed` 0.5, `drift_x/y` 0, duration 60.
- [ ] **Step 5: build + visual test** (`[amplitude:0.06],[scale:4],[contrast:3]` — fluid melting patches, **must visibly morph over time**, no flicker).
- [ ] **Step 6: Commit** — `feat(post): noise_warp screen effect`.

---

### Task 4: `entity_displace` — CPU vertex displacement second pass [review-fixed]

**Semantics [review-fixed]:** the vanilla body stays underneath; the displaced copy is an **echo/ghost** of the model (not a replacement). Document it honestly: "the model jitters out of place" == a displaced echo overlapping the intact body. Test criterion reads that way.

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/core/displace.fsh` (reuse `entity_fx.vsh`)
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXEntityEffectRenderer.java` (`renderDisplace` + pipelines)
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXNoise.java` (new, Global Constraints)
- Modify: `src/client/java/dev/vfxweaver/client/mixin/LivingEntityRendererMixin.java`
- Modify: `src/client/java/dev/vfxweaver/client/effect/VFXEffectManager.java` (`rebuildEntityEffectsIndex` + type filter)
- Modify: `VFXEffectType.java`, `VFXDefinitionManager.java`

- [ ] **Step 1 [review-fixed]: separate flat echo shader.** A dedicated `displace.fsh` (do not extend `entity_fx.fsh` — its `#ifdef` chain would grow unreadable and the pass needs no texture at all):
  ```glsl
  #version 330
  #moj_import <minecraft:fog.glsl>
  #moj_import <minecraft:dynamictransforms.glsl>
  in float sphericalVertexDistance;
  in float cylindricalVertexDistance;
  in vec4 vertexColor;
  in vec2 texCoord0;
  out vec4 fragColor;
  void main() {
      vec4 color = vertexColor;
      if (color.a <= 0.0) { discard; }
      fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
                            FogEnvironmentalStart, FogEnvironmentalEnd,
                            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
  }
  ```
  Reuse `core/entity_fx.vsh` for the vertex stage.
- [ ] **Step 2: pipelines.** In `VFXEntityEffectRenderer`, a `displacePipeline(depthOp, suffix)` helper mirroring `entityFxPipeline` but with fragment `core/displace`, `withShaderDefine` omitted, **no** `withSampler` (this pass is textureless — RenderTypes are static, not per-texture):
  ```java
  private static RenderPipeline displacePipeline(final CompareOp depthOp, final String suffix) {
      return RenderPipelines.register(
          RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
              .withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/entity_displace_" + suffix))
              .withVertexShader(Identifier.fromNamespaceAndPath("vfxweaver", "core/entity_fx"))
              .withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "core/displace"))
              .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
              .withDepthStencilState(new DepthStencilState(depthOp, false))
              .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
              .withCull(false)
              .build()
      );
  }
  private static final RenderType DISPLACE_VISIBLE = RenderType.create(
      "vfxweaver_entity_displace_visible",
      RenderSetup.builder(displacePipeline(CompareOp.ALWAYS_PASS, "visible")).createRenderSetup());
  private static final RenderType DISPLACE_OCCLUDED = RenderType.create(
      "vfxweaver_entity_displace_occluded",
      RenderSetup.builder(displacePipeline(CompareOp.LESS_THAN_OR_EQUAL, "occluded")).createRenderSetup());
  ```
  Naming convention per Global Constraints: `_VISIBLE` = ALWAYS_PASS (through), `_OCCLUDED` = LEQUAL.
- [ ] **Step 3: renderDisplace.** Mirror `renderOutline`'s structure without the thickness growth:
  ```java
  public static <S extends LivingEntityRenderState> void renderDisplace(
      final VFXActiveEffect effect, final S state, final PoseStack poseStack,
      final SubmitNodeCollector submitNodeCollector, final Model<? super S> model, final Identifier texture
  ) {
      float amplitude = clamp01(effect.getParam("amplitude", 0.1F)) * effect.getWeight();
      if (amplitude <= 0.0F) { return; }
      boolean through = effect.getParam("through_blocks", 0.0F) >= 0.5F;
      float scale = Math.max(effect.getParam("scale", 4.0F), 0.5F);
      float seed = effect.getParam("seed", 0.0F);
      int color = argb(effect, clamp01(effect.getParam("alpha", 1.0F)) * effect.getWeight());
      RenderType renderType = through ? DISPLACE_VISIBLE : DISPLACE_OCCLUDED;
      model.setupAnim(state);
      submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
          PoseStack stack = new PoseStack();
          stack.last().set(pose);
          model.root().visit(stack, (partPose, path, cubeIndex, cube) ->
              emitDisplacedCube(partPose, buffer, cube, amplitude, scale, seed, color, state.lightCoords));
      });
  }
  ```
- [ ] **Step 4: emitDisplacedCube [review-fixed].** Entity coords are local and small (worldX()/worldY()/worldZ() ~ ±2 · scale) — no precision wrap needed here. Per-vertex offset via 3 hashes (X/Y/Z components) so the echo looks like glitch refraction, and `scale` warps the hash inputs (larger scale → neighbours diverge more). No normal-along-vertex: it would tear shared corners inconsistently and read no better.
  ```java
  private static void emitDisplacedCube(
      final PoseStack.Pose pose, final VertexConsumer buffer, final ModelPart.Cube cube,
      final float amplitude, final float scale, final float seed, final int color, final int lightCoords
  ) {
      Vector3f pos = new Vector3f();
      Vector3f normal = new Vector3f();
      for (ModelPart.Polygon polygon : cube.polygons) {
          pose.transformNormal(polygon.normal(), normal);
          for (ModelPart.Vertex v : polygon.vertices()) {
              float x = v.worldX() * scale, y = v.worldY() * scale, z = v.worldZ() * scale;
              float ox = VFXNoise.vhash(x, y, z, seed) * amplitude;
              float oy = VFXNoise.vhash(y, z, x, seed + 3.14F) * amplitude;
              float oz = VFXNoise.vhash(z, x, y, seed + 6.28F) * amplitude;
              pose.pose().transformPosition(v.worldX() + ox, v.worldY() + oy, v.worldZ() + oz, pos);
              buffer.addVertex(pos.x(), pos.y(), pos.z(), color, v.u(), v.v(),
                  OverlayTexture.NO_OVERLAY, lightCoords, normal.x(), normal.y(), normal.z());
          }
      }
  }
  ```
- [ ] **Step 5: mixin routing.** In `LivingEntityRendererMixin.vfxweaver$applyEntityEffects` add:
  ```java
  } else if (effect.getType() == VFXEffectType.ENTITY_DISPLACE) {
      VFXEntityEffectRenderer.renderDisplace(effect, state, poseStack, submitNodeCollector, this.model, texture);
  }
  ```
  Also extend `VFXEffectManager.rebuildEntityEffectsIndex` so `ENTITY_DISPLACE` (and later `GOD_RAYS`) are indexed by UUID, and exclude `ENTITY_DISPLACE` from `isPostProcessing()`.
- [ ] **Step 6: enum + built-in.** `ENTITY_DISPLACE("entity_displace")`; `neutralValue` `NaN` for all (weight multiplies amplitude directly). Built-in duration 40:
  ```java
  param("amplitude", 0.1F, 0.0F), param("scale", 4.0F), param("seed", 0.0F),
  param("alpha", 1.0F), param("through_blocks", 0.0F),
  param("color_r", 1.0F), param("color_g", 1.0F), param("color_b", 1.0F)
  ```
- [ ] **Step 7: build + visual test.** `/vfx playentity vfxweaver:entity_displace @e[type=zombie,limit=1] {[amplitude:0.2],[scale:6]}` — the model shows a displaced echo on top of the intact body. For the stepped "snaps 8×/s" look use a datapack:
  ```json
  { "type": "entity_displace", "duration": 60,
    "params": { "amplitude": 0.2, "scale": 6, "seed": { "expr": "floor(t * 8) * 0.1" } } }
  ```
  (command params are floats only — see Global Constraints).
- [ ] **Step 8: Commit** — `feat(render): entity_displace echo effect`.

---

### Task 5: `block_displace` — CPU displaced block model quads [review-fixed]

Semantics same as Task 4: a corrupted echo overlaying the intact block. This is intentional (base not hidden); document it.

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java`
- Modify: `VFXNoise.java` (shared, already in Task 4), `VFXEffectType.java`, `VFXDefinitionManager.java`

- [ ] **Step 1: RTs [review-fixed]** — same naming convention as Task 4 (`_VISIBLE` = ALWAYS_PASS, `_OCCLUDED` = LEQUAL):
  ```java
  private static final RenderType DISPLACE_VISIBLE = RenderType.create(
      "vfxweaver_block_displace_visible",
      RenderSetup.builder(blockPipeline(CompareOp.ALWAYS_PASS, false, "displace_visible")).createRenderSetup());
  private static final RenderType DISPLACE_OCCLUDED = RenderType.create(
      "vfxweaver_block_displace_occluded",
      RenderSetup.builder(blockPipeline(CompareOp.LESS_THAN_OR_EQUAL, false, "displace_occluded")).createRenderSetup());
  ```
- [ ] **Step 2: branch in `render()`.** `BLOCK_DISPLACE` → `through ? DISPLACE_VISIBLE : DISPLACE_OCCLUDED` (same pattern as BLOCK_TINT; no outset, no extrusion). New `renderDisplaced(...)` mirrors `renderEffect` but calls `emitQuadsDisplaced`.
- [ ] **Step 3: displaced emission [review-fixed].** Hash the **world-space** vertex position (`blockPos + local`), wrapped for float precision (world coords reach ±30M):
  ```java
  private static void emitQuadsDisplaced(final VertexConsumer buffer, final PoseStack.Pose pose,
      final List<BakedQuad> quads, final int color, final BlockPos pos,
      final float amplitude, final float scale, final float seed) {
      for (BakedQuad quad : quads) {
          for (int i = 0; i < 4; i++) {
              var p = quad.position(i);
              float wx = (pos.getX() + p.x()) * scale;
              float wy = (pos.getY() + p.y()) * scale;
              float wz = (pos.getZ() + p.z()) * scale;
              float ox = VFXNoise.vhash(VFXNoise.wrap(wx), VFXNoise.wrap(wy), VFXNoise.wrap(wz), seed) * amplitude;
              float oy = VFXNoise.vhash(VFXNoise.wrap(wy), VFXNoise.wrap(wz), VFXNoise.wrap(wx), seed + 3.14F) * amplitude;
              float oz = VFXNoise.vhash(VFXNoise.wrap(wz), VFXNoise.wrap(wx), VFXNoise.wrap(wy), seed + 6.28F) * amplitude;
              buffer.addVertex(pose, p.x() + ox, p.y() + oy, p.z() + oz).setColor(color);
          }
      }
  }
  ```
  Add `emitCubeFillDisplaced` for the no-model fallback using `CUBE_FACES` with the same world-space hash. Displacement in world space per block means adjacent blocks get different patterns (no repeating 1-block grid).
- [ ] **Step 4: enum + built-in + `isWorldOverlay()`.** `BLOCK_DISPLACE("block_displace")`; add to `isWorldOverlay()`; `neutralValue` NaN. Built-in duration 40: `amplitude` 0.15 → 0, `scale` 4, `seed` 0, `alpha` 1, `through_blocks` 0, white color constants.
- [ ] **Step 5: build + visual test.** `/vfx playat vfxweaver:block_displace 8 70 8 {[amplitude:0.2]}` — block quads jitter as an echo. Stepped-seed datapack example identical in shape to Task 4's.
- [ ] **Step 6: Commit** — `feat(render): block_displace effect`.

---

### Task 6: `camera_roll` (Misc)

**Files:** Modify `src/client/java/dev/vfxweaver/client/shake/CameraShakeManager.java` (add roll computation) and `src/client/java/dev/vfxweaver/client/mixin/CameraMixin.java` (apply after shake); `VFXEffectType.java`, `VFXDefinitionManager.java`.

- [ ] **Step 1: compute roll.** Add `VFXCameraRoll.compute(manager)` (new tiny class, or fold into `CameraShakeManager`) summing over `camera_roll` effects:
  ```java
  float roll = 0.0F;
  for (VFXActiveEffect e : manager.getActiveCameraRolls()) {
      float w = e.getWeight();
      float t = e.getElapsed() / 20.0F;
      float wob = e.getParam("wobble", 0.0F) * (float) Math.sin(t * e.getParam("wobble_speed", 0.2F) * 6.2831853);
      roll += (e.getParam("angle", 0.0F) + wob) * w;
  }
  ```
  Return wrapDegrees.
- [ ] **Step 2: apply.** In `CameraMixin.vfxweaver$applyShake` (after the shake's own roll), apply the camera_roll angle as `rotationZ`, transforming `forwards/up/left` exactly like the shake roll block does. (You cannot just add into shake's `Offset.roll` — Wobble wobble should not be modulated by shake's simplex envelope.)
- [ ] **Step 3: enum + built-in.** `CAMERA_ROLL("camera_roll")`; exclude from `isPostProcessing()`; `VFXEffectManager.getActiveCameraRolls()`. Built-in duration 40: `angle` 15 → 0, `wobble` 0, `wobble_speed` 0.2.
- [ ] **Step 4: build + visual test (tilt visible, screen stays interactive) + Commit** — `feat(render): camera_roll effect`.

---

### Task 7: `solarize` screen effect

**Files:** create `post/solarize.fsh`; modify the three post files.

- [ ] **Step 1: shader.** Config `{ float threshold; float softness; float intensity; }`:
  ```glsl
  void main() {
      vec4 c = texture(InSampler, texCoord);
      float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
      float t = smoothstep(threshold - softness / 2.0 - 1.0e-4, threshold + softness / 2.0 + 1.0e-4, luma);
      fragColor = vec4(mix(c.rgb, 1.0 - c.rgb, t * intensity), c.a);
  }
  ```
- [ ] **Step 2: wiring + built-in.** `SOLARIZE("solarize")`; `registerPost(SOLARIZE, "threshold", "softness", "intensity")`; `neutralValue`: `"intensity" -> 0.0F`; built-in duration 40: `threshold` 0.5, `softness` 0, `intensity` 1 → 0.
- [ ] **Step 3: build + visual test + Commit** — `feat(post): solarize screen effect`.

---

### Task 8: `double_vision` screen effect [review-fixed]

**Files:** create `post/double_vision.fsh`; modify the three post files.

- [ ] **Step 1: shader [review-fixed]** — compile-safe (no undeclared `drift_amp`) and energy-preserving (weights sum to 1, no 1.5× brightening):
  ```glsl
  layout(std140) uniform Config { float offset; float ghost_opacity; float drift; float intensity; float time; };
  ...
  void main() {
      float g = ghost_opacity * intensity;
      float driftOff = sin((time / 20.0) * 1.2) * drift;      // seconds; slow sinusoidal drift
      vec2 base = vec2(offset + driftOff, 0.0);
      vec4 c = (texture(InSampler, texCoord)
              + texture(InSampler, texCoord + base) * g
              + texture(InSampler, texCoord - base) * g) / (1.0 + 2.0 * g);
      fragColor = vec4(c.rgb, 1.0);
  }
  ```
- [ ] **Step 2: wiring + built-in.** `DOUBLE_VISION("double_vision")`; `registerPost(DOUBLE_VISION, "offset", "ghost_opacity", "drift", "intensity", "time")`; `neutralValue`: `"intensity" -> 0.0F`; built-in duration 60: `offset` 0.04, `ghost_opacity` 0.5, `drift` 0, `intensity` 1 → 0.
- [ ] **Step 3: build + visual test (ghost copies, no brightness jump) + Commit** — `feat(post): double_vision screen effect`.

---

### Task 9: `eyelids` screen effect [review-fixed]

**Files:** create `post/eyelids.fsh`; modify the three post files.

- [ ] **Step 1: shader [review-fixed]** — fixes both bugs: (A) lids are at the screen edges when `openness=1` (overshoot via `travel`, so even edge rows are clear), and (B) it **composites** over `InSampler` instead of writing an unblended black `vec4`:
  ```glsl
  layout(std140) uniform Config { float openness; float softness; float curve; };
  ...
  void main() {
      float closure = clamp(1.0 - openness, 0.0, 1.0);
      float bulge = curve * 0.5 * (1.0 - 4.0 * pow(texCoord.x - 0.5, 2.0)) * closure;
      float travel = closure * (0.5 + 2.0 * softness);       // feathers leave the screen when open
      float lidTop = 1.0 + softness - travel + bulge;
      float lidBot = -softness + travel - bulge;
      float t = smoothstep(lidTop - softness, lidTop + softness, texCoord.y);
      float b = 1.0 - smoothstep(lidBot - softness, lidBot + softness, texCoord.y);
      float mask = clamp(t + b, 0.0, 1.0);
      fragColor = vec4(mix(texture(InSampler, texCoord).rgb, vec3(0.0), mask), 1.0);
  }
  ```
  Check: `openness=1` → `mask=0` everywhere; `openness=0` → lids meet at `0.5`; bulge scales with `closure` so the neutral state is clean.
- [ ] **Step 2: wiring + built-in.** `EYELIDS("eyelids")`; `registerPost(EYELIDS, "openness", "softness", "curve")`; `neutralValue`: `"openness" -> 1.0F`; built-in duration 120 (slow blink), params `openness` 0.5, `softness` 0.15, `curve` 0.35 as constants — owners animate `openness` via keyframes/bindings (a 40-tick built-in fade is not the intended use).
- [ ] **Step 3: build + visual test (black screen ONLY when closed; open = fully clear) + Commit** — `feat(post): eyelids screen effect`.

---

### Task 10: `iris_wipe` screen effect

**Files:** create `post/iris_wipe.fsh`; modify the three post files.

- [ ] **Step 1: shader** (mirror `dent`'s `center_x/center_y` + aspect handling):
  ```glsl
  layout(std140) uniform Config { float radius; float softness; float center_x; float center_y; float zoom; };
  ...
  void main() {
      vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
      vec2 corr = (texCoord - vec2(center_x, center_y)) * aspect;
      float dist = length(corr);
      float mask = smoothstep(radius - softness, radius + softness, dist);
      float zoomFactor = 1.0 - zoom * (1.0 - clamp(dist / max(radius, 1.0e-4), 0.0, 1.0));
      vec2 zoomed = vec2(center_x, center_y) + (texCoord - vec2(center_x, center_y)) * zoomFactor;
      vec4 inner = texture(InSampler, zoomed);
      fragColor = mix(inner, vec4(0.0, 0.0, 0.0, 1.0), mask);
  }
  ```
- [ ] **Step 2: wiring + built-in.** `IRIS_WIPE("iris_wipe")`; `registerPost(IRIS_WIPE ...)` — Config order above; `neutralValue`: `"radius" -> 1.4F` (and it is the built-in animated param); built-in duration 40: `radius` 0.4 → 1.4 (open by fading out), `softness` 0.05, `center_x/y` 0.5, `zoom` 1 → 0.
  - Note [review-fixed]: neutral `radius` should be **large** (open), not small. Fade blends toward neutral only for persistent fade-out; the built-in animation drives the swipe itself.
- [ ] **Step 3: build + visual test (iris opens/closes) + Commit** — `feat(post): iris_wipe screen effect`.

---

### Task 11: `digital_glitch` screen effect [review-fixed]

**Files:** create `post/digital_glitch.fsh`; modify the three post files. Config `{ float block; float displacement; float rate; float chroma; float seed; float chance; float intensity; float time; }`.

- [ ] **Step 1: shader [review-fixed]** — burst-gates the whole frame on time **slots** (instead of per-band probability that everlastingly tears ~17% of the screen), and restores the `chance` parameter:
  ```glsl
  float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
  void main() {
      float t = time / 20.0;                                   // seconds
      float band = floor(texCoord.y / max(block, 1.0e-3));
      float slot = floor(t * rate);
      float gate = step(1.0 - chance, hash(vec2(slot, seed)));                 // burst on/off for the whole slot
      float burst = gate * step(0.5, hash(vec2(band, slot + seed)));           // ~half the bands inside a burst
      float shift = (hash(vec2(band, slot + seed + 99.0)) - 0.5) * displacement * burst;
      vec2 uvG = texCoord + vec2(shift, 0.0);
      vec4 c;
      c.r = texture(InSampler, uvG + vec2(chroma * burst, 0.0)).r;
      c.g = texture(InSampler, uvG).g;
      c.b = texture(InSampler, uvG - vec2(chroma * burst, 0.0)).b;
      c.a = 1.0;
      fragColor = mix(texture(InSampler, texCoord), c, intensity);
  }
  ```
- [ ] **Step 2: wiring + built-in.** `DIGITAL_GLITCH("digital_glitch")`; `registerPost(DIGITAL_GLITCH, "block", "displacement", "rate", "chroma", "seed", "chance", "intensity", "time")`; `neutralValue`: `"intensity" -> 0.0F`; built-in duration 40: `block` 0.06, `displacement` 0.08, `rate` 6, `chroma` 0.5, `seed` 0, `chance` 0.4, `intensity` 1 → 0.
- [ ] **Step 3: build + visual test (bursts, not permanent tearing) + Commit** — `feat(post): digital_glitch screen effect`.

---

### Task 12: `vhs` screen effect [review-fixed]

**Files:** create `post/vhs.fsh`; modify the three post files. Config `{ float tracking; float band_height; float band_speed; float bleed; float wobble; float intensity; float time; }`.

- [ ] **Step 1: shader [review-fixed]** — the tracking band is a real **band** (time-derived centre with wrap), not a whole-screen pulse; `hash` above `main`:
  ```glsl
  float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
  void main() {
      float t = time / 20.0;                                   // seconds
      float bandCenter = fract(t * band_speed);
      float dy = abs(texCoord.y - bandCenter);
      dy = min(dy, 1.0 - dy);                                  // wraps top/bottom
      float inBand = 1.0 - smoothstep(band_height * 0.5, band_height, dy);
      float wob = (hash(vec2(floor(texCoord.y * InSize.y), floor(t * 60.0))) - 0.5) * wobble;
      float shift = (hash(vec2(floor(texCoord.y * InSize.y), floor(t * 12.0))) - 0.5) * tracking * inBand;
      vec2 uvG = texCoord + vec2(shift + wob, 0.0);
      vec4 c;
      c.r = texture(InSampler, uvG + vec2(bleed, 0.0)).r;
      c.g = texture(InSampler, uvG).g;
      c.b = texture(InSampler, uvG - vec2(bleed * 2.0, 0.0)).b;
      c.a = 1.0;
      c.rgb = (c.rgb - 0.08) / 0.92;                           // washed-out contrast
      fragColor = vec4(mix(texture(InSampler, texCoord).rgb, c.rgb, intensity), 1.0);
  }
  ```
- [ ] **Step 2: wiring + built-in.** `VHS("vhs")`; `registerPost(VHS, "tracking", "band_height", "band_speed", "bleed", "wobble", "intensity", "time")`; `neutralValue`: `"intensity" -> 0.0F`; built-in duration 60: `tracking` 0.35, `band_height` 0.08, `band_speed` 0.15, `bleed` 0.02, `wobble` 0.004, `intensity` 1 → 0.
- [ ] **Step 3: build + visual test (a band crawls; the rest is stable) + Commit** — `feat(post): vhs screen effect`.

---

### Task 13: `shockwave` screen effect [review-fixed]

**Files:** create `post/shockwave.fsh`; modify the three post files. Config `{ float center_x; float center_y; float radius; float width; float amplitude; float sharpness; }`.

- [ ] **Step 1: shader [review-fixed]** — replaced the hardcoded `0.85` composite with full weight (neutrality is handled by `amplitude → 0`, which makes the displaced sample identical to the original — at amplitude 0 the pass is identity):
  ```glsl
  void main() {
      vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
      vec2 corr = (texCoord - vec2(center_x, center_y)) * aspect;
      float dist = length(corr);
      float d = (dist - radius) / max(width, 1.0e-3);
      float profile = cos(d * 3.14159265 / max(sharpness, 1.0e-3)) * exp(-d * d);
      vec2 dir = normalize(corr + vec2(1.0e-5));
      vec4 c = texture(InSampler, texCoord + dir * amplitude * profile);
      fragColor = vec4(mix(texture(InSampler, texCoord).rgb, c.rgb, 1.0), 1.0);
  }
  ```
  Know: the `cos·exp` profile is symmetric on both sides of the ring (a bulge, not a travelling wave) — fine for a "glassy ring".
- [ ] **Step 2: wiring + built-in.** `SHOCKWAVE("shockwave")`; `registerPost(SHOCKWAVE, "center_x", "center_y", "radius", "width", "amplitude", "sharpness")`; `neutralValue`: `"amplitude" -> 0.0F`, `"radius" -> 1.5F`; built-in duration 40: `center_x/y` 0.5, `radius` 0.4 → 1.5, `width` 0.15, `amplitude` 0.12 → 0, `sharpness` 1.5.
- [ ] **Step 3: build + visual test + Commit** — `feat(post): shockwave screen effect`.

---

### Task 14: `god_rays` — entity beams [review-fixed naming]

**Decision:** keep the spec name `god_rays` and the **spec's param table** (Effect 14 of the spec — the owner-reworked Ender-Dragon-death form; screen-space god rays are dropped). The reviewer's suggested rename `entity_beams` and alt table are noted here; do **not** rename against the approved spec unless the owner says so. Docs (Task 17) describe exactly this entity-beams behaviour.

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/render/VFXBeamsRenderer.java`
- Modify: `VFXEntityEffectRenderer.java` (additive pipelines), `LivingEntityRendererMixin.java`, `VFXEffectManager.java`, `VFXEffectType.java`, `VFXDefinitionManager.java`

**Param table (spec Effect 14):** `count` 6 (1..16), `height` 12 (1..64), `spread` 0.6 (0..4), `speed` 2 (0.5..8), `sway` 0.5 (0..4), `red` 0.6 / `green` 0.2 / `blue` 0.9, `intensity` 1 → 0.

- [ ] **Step 1: additive pipelines.** In `VFXEntityEffectRenderer`, additive variants with `ColorTargetState(new BlendFunction(...))` additive (mirror `displacePipeline` but TRANSLUCENT→ADDITIVE; no texture):
  - `BEAMS_VISIBLE` (ALWAYS_PASS), `BEAMS_OCCLUDED` (LEQUAL) — reuse `DefaultVertexFormat.ENTITY` + `core/entity_fx` vertex + a small `core/beams.fsh` that outputs a flat additive `vertexColor.rgb` (no fog — light beams shouldn't fade into fog).
- [ ] **Step 2: VFXBeamsRenderer.renderEffects.** Emit `count` additive vertical quads rising from the entity body, billboarded to the camera:
  - Spawn point `i`: horizontal offset `spread` at a deterministic angle (hash of `i`), around the body centre (entity feet + `height/2`).
  - Rise: the visible beam top = `(t * speed) mod height` (spec: rise speed, blocks/sec); spawn origin drops back to the body so new beams keep rising.
  - Sway per beam: `sin(t * 1.5 + i * 2.4) * sway` horizontal offset at the tip.
  - Alpha: fades toward the tip (`1 - tipFraction`), overall `× intensity × weight`.
  - Billboard: two triangle/quads expanded perpendicular to the camera-axis → view the existing block-beam billboards in `VFXWorldOverlayRenderer` for the pose pattern.
- [ ] **Step 3: mixin + index.** `GOD_RAYS` branch in `LivingEntityRendererMixin` (render via VFXBeamsRenderer with `state` pose + camera); include `GOD_RAYS` in `rebuildEntityEffectsIndex`; exclude from `isPostProcessing()`.
- [ ] **Step 4: built-in.** duration 60, params per the table; `intensity` animated to 0; `neutralValue` NaN.
- [ ] **Step 5: build + visual test** (`/vfx playentity vfxweaver:god_rays @e[type=dragon,limit=1]` — purple beams pour out of the body) **+ Commit** — `feat(render): god_rays entity beams`.

---

### Task 15: World quad effects — `light_beam`, `pulse_ring`, `scan_sweep`, `guide_line`

**Files:** modify `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java` (four new branches + additive pipelines), `VFXEffectType.java` (`LIGHT_BEAM`, `PULSE_RING`, `SCAN_SWEEP`, `GUIDE_LINE`, all in `isWorldOverlay()`), `VFXDefinitionManager.java`.

**Param tables (spec Effects 15-18):**
- `light_beam`: `radius` 1.5, `height` 48, `softness` 0.6, `top_fade` 0.4, `sway` 0, `sway_speed` 0.4, color 1/0.95/0.75, `intensity` 1→0. Duration 60.
- `pulse_ring`: `radius` 6 (animate 0→max), `thickness` 0.5, `tilt` 0, color 1/0.35/0.1, `intensity` 1→0. Duration 60.
- `scan_sweep`: `range` 16, `axis` 1, `progress` 0.5 (animate for the pass), `width` 0.4, `trail` 0.25, color 0.3/1/0.9, `intensity` 1→0. Duration 60.
- `guide_line`: `width` 0.15, `dash_length` 0.6, `gap` 0.6, `speed` 2, `arc` 1.5, color 0.25/1/0.45, `intensity` 1→0. Duration 60.

- [ ] **Step 1: additive pipeline pair** (shared by all four): `world/beam_visible/occluded` via a `glowPipeline(depthOp, suffix)` mirroring `blockPipeline` but with `ColorTargetState(BlendFunction.ADDITIVE)`. Positions come from `effectPositions(effect)` (playat → single pos; datapack `region` → many).
- [ ] **Step 2: `light_beam`** — vertical billboarded column (2-4 quads) from the anchor up `height`; alpha gradient from base `intensity` to `top_fade` at the top; `sway` = per-vertex horizontal `sin(t * sway_speed * 6.28 + depth)`; horizontal feathered edges by `radius`/`softness` via vertex alpha.
- [ ] **Step 3: `pulse_ring`** — flat annulus built from 12-24 segments at `radius`, band `thickness`, alpha soft toward inner/outer edges; plane tilted by `tilt` degrees about the X axis (0 = flat); `radius` recomputed per frame.
- [ ] **Step 4: `scan_sweep`** — a two-sided quad sheet across `axis` at `progress * range`, width `width`; plus 3-5 trail quads behind with decreasing alpha down to `trail`; axis 0=X, 1=Y, 2=Z.
- [ ] **Step 5: `guide_line`** — parabolic arc (apex +`arc` at midpoint) between two anchors: use `effectPositions(effect)`; if `≥ 2` positions, line between `[0]` and `[1]`, else between `[0]` and `[0] + (10, 0, 0)`. Subdivide into ~32 segments, emit dashes by marching distance and cutting on `(d + t*speed) mod (dash_length+gap) < dash_length`.
- [ ] **Step 6: built-in definitions** for all four (tables above; only `intensity` and the animated param fade to 0; `neutralValue` NaN).
- [ ] **Step 7: build + visual test each (playat) + Commit** — `feat(render): world quad effects (light_beam, pulse_ring, scan_sweep, guide_line)`.

---

### Task 16: feedback buffer infrastructure + `afterimage`, `stop_motion` [review-fixed]

**The two critical hardware-grade fixes from review:**
1. A feedback pass must never read and write the same texture (GL read/draw feedback = undefined behaviour). → **Double-buffer the history** (`HistoryPrev`/`HistoryNext`); afterimage runs as two passes.
2. A single history target stores one frame — there is nothing to "sample at `floor(t*fps)/fps`". → **stop_motion uses CPU hold-gating** over a copied frame.

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java` — history double-buffer + hold target + `HistSampler` binding + special-cased pass roles
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` — `afterimage_update`, `afterimage_composite`, `stop_motion` pipelines (each with `InSampler` + `HistSampler`), plus a `usesHistory`/role field on `ProgramInfo`
- Create: `post/afterimage_update.fsh`, `post/afterimage_composite.fsh`, `post/stop_motion.fsh`
- Modify: `VFXEffectType.java`, `VFXDefinitionManager.java`

- [ ] **Step 1: targets.** `private final TextureTarget[] history = new TextureTarget[2];` and `private @Nullable TextureTarget stopMotionHold;` created/destroyed in the same resize path as `pingPong` (`ensureTargets`). On resize the targets are **recreated** (destroyed first) — previous state is discarded, which is the "clear history on resize" requirement; to avoid a garbage first blend, set a `historyDirty` flag: the first afterimage update treats the previous history as equal to the current frame (see Step 3).
- [ ] **Step 2: pipelines.** Afterimage = two `ProgramInfo`s (`afterimage_update` Config `{ decay, blend, drift }`, `afterimage_composite` Config `{ decay, blend, drift, desat, intensity }` — declare the full param set in both passes so both read the same effect Config). `stop_motion` Config `{ fps }`. Each pipeline declares `.withSampler("InSampler")` + `.withSampler("HistSampler")`. Add a `VFXShaderPrograms.ProgramInfo` field `FeedbackRole role` (`NONE`/`UPDATE`/`COMPOSITE`/`STOP_MOTION`) or reuse pass ordering.
- [ ] **Step 3: executor.**
  ```java
  // pseudo, inside process() after the copy pass
  // 1) afterimage update: read current (pingPong[0]) + HistoryPrev -> write HistoryNext
  //    historyDirty -> HistoryPrev := current (bind current as HistoryPrev) so first frame is clean
  // 2) afterimage composite: read current + HistoryNext -> write into the chain output (main/last)
  //    then swap HistoryPrev <-> HistoryNext
  // 3) stop_motion (per active effect, CPU):
  //    int slot = (int) Math.floor(effect.getAge()/20.0 * fps);       // fps<=1 -> full speed, skip
  //    if (slot != lastSlotFor(effectId)) { copyPass.execute(main -> stopMotionHold); hold=0; }
  //    else hold=1;
  //    pass: fragColor = hold<0.5 ? texture(InSampler,uv) : texture(HistSampler,uv);  // HistSampler=stopMotionHold
  ```
  Memory: `stopMotionSlots` per effect id — prune entries whose effect is gone (bounded by the active-effect cap).
- [ ] **Step 4: `afterimage` shaders.**
  ```glsl  // update: H1 = mix(prev*decay, current, blend)
  layout(std140) uniform Config { float decay; float blend; float drift; };
  void main() {
      vec2 uv = (texCoord - 0.5) * (1.0 + drift) + 0.5;        // drift = per-frame zoom of the echo
      vec4 prev = texture(HistSampler, uv) * decay;
      fragColor = mix(prev, texture(InSampler, texCoord), blend);
  }
  ```
  ```glsl  // composite: out = mix(current, desaturate(H1), intensity)
  layout(std140) uniform Config { float decay; float blend; float drift; float desat; float intensity; };
  void main() {
      vec4 hist = texture(HistSampler, texCoord);
      float luma = dot(hist.rgb, vec3(0.299, 0.587, 0.114));
      fragColor = mix(texture(InSampler, texCoord),
                      vec4(mix(hist.rgb, vec3(luma), desat), 1.0), intensity);
  }
  ```
  Neutral at `intensity=0` → composite outputs current → identity.
- [ ] **Step 5: `stop_motion.fsh`.**
  ```glsl
  layout(std140) uniform Config { float fps; };
  // hold is bound as a runtime uniform by the executor (see Step 3); expose via a small
  // reserved param like "time": add "hold" handling in VFXPass.execute that reads the
  // pre-computed hold flag instead of a user parameter.
  uniform float Hold;
  void main() { fragColor = Hold < 0.5 ? texture(InSampler, texCoord) : texture(HistSampler, texCoord); }
  ```
  `fps ≤ 1` → skip gating entirely (identity), matching the spec note "0 = back to full speed".
- [ ] **Step 6: enums + built-ins.** `AFTERIMAGE("afterimage")`, `STOP_MOTION("stop_motion")`.
  - afterimage built-in duration 60: `decay` 0.92, `blend` 0.6, `drift` 0, `desat` 0.35, `intensity` 1 → 0; `neutralValue` `"intensity" -> 0.0F`.
  - stop_motion built-in duration 60: `fps` 12 → 0 (ends back at full speed); `neutralValue` `"fps" -> 0.0F`.
  - `isPostProcessing()`: both true (they are screen passes) — but the executor must keep handling them even when they are the only active post effect (don't short-circuit the history init).
- [ ] **Step 7: build + visual test.** Afterimage: move the camera → trails linger with a fixed decay time regardless of speed; fade returns to a clean frame. Stop-motion: `[fps:8]` → world updates 8×/s, HUD/physics stay smooth. **+ Commit** — `feat(post): feedback buffer + afterimage + stop_motion`.

---

### Task 17: docs + changelog for the whole batch

**Files:** `docs/GUIDE.md` (per-parameter subsections for every new effect + the `Commands`/param-map notes), `docs/CHANGELOG.md` (unreleased Added entries).

- [ ] **Step 1: write the doc subsections from the shipped params** (pull exactly what the built-ins declare — docs must equal `/vfx play`). Include: param tables (default/normal range/description), one copy-pasteable command example per effect, the `time` (auto) note, the `expr`-needs-a-datapack note, the "echo not replacement" semantics for `entity_displace`/`block_displace`, the `god_rays` = entity-beams clarification, and `screen_layer` for every screen effect.
- [ ] **Step 2: changelog** — one `Added` entry per effect under the next version heading.
- [ ] **Step 3: Commit** — `docs: document the new effects batch`.

---

## Self-Review checklist (run after drafting/during execution)

- [ ] Spec coverage: slice_shift ✓, noise_warp ✓, entity_displace ✓, block_displace ✓, solarize ✓, double_vision ✓, eyelids ✓, iris_wipe ✓, digital_glitch ✓, vhs ✓, shockwave ✓, afterimage ✓, stop_motion ✓, god_rays ✓, light_beam ✓, pulse_ring ✓, scan_sweep ✓, guide_line ✓, hud_fade ✓, camera_roll ✓. Dropped by owner: sky_tint, fog_override, entity_glitch (superseded by displace), dither, edge_detect.
- [ ] No placeholders: every shader/Java step above contains full code or a copy-from instruction naming the exact source file.
- [ ] Naming consistent across enum, shaders, definitions, docs (`slice_shift`/`noise_warp`/`entity_displace`/... — snake_case).
- [ ] Pipeline label convention identical in entity and block flavours (`_VISIBLE`=ALWAYS_PASS, `_OCCLUDED`=LEQUAL).
- [ ] Docs Task 17 parameters exactly match built-ins.
- [ ] `VFXEffectType.isPostProcessing()`/`isWorldOverlay()` and `rebuildEntityEffectsIndex` updated for every new type.
- [ ] No effect reads and writes the same GPU texture; feedback uses the double buffer.

## Execution Handoff

Inline execution with per-task commits (`committing-after-changes` skill). Tasks 2/3 (screen pair) share files and can share a commit. Blockers were pre-cleared by the review — no open questions expected unless the runtime misbehaves (hud_fade spike is the first to run precisely because it is the unknown).
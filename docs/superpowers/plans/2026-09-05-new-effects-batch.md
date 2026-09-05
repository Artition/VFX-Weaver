# New Effects Batch (slice_shift, noise_warp, entity/block_displace) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three effects — screen `slice_shift`, screen `noise_warp`, and `entity/block_displace` (vertex glitch for entity and block models) — each fully registered, built-in defined, documented.

**Architecture:** Screen effects follow the existing chain: fsh in `assets/vfxweaver/shaders/post/`, `registerPost(VFXEffectType.X, params...)` in `VFXShaderPrograms.register()`, enum constant + `neutralValue()` cases in `VFXEffectType`, built-in definition in `VFXDefinitionManager.registerBuiltIns()`. `entity_displace` is a second pass re-emitting model cubes with CPU noise displacement via `submitCustomGeometry` into the existing `TINT_MASK`-style pipelines. `block_displace` re-emits baked block-model quads with CPU noise displacement into existing block overlay pipelines. No new shader mechanisms needed for displace (CPU hash per vertex).

**Tech Stack:** Java 25, Fabric 0.19.3, MC 26.1.2 (official mappings), GLSL 330.

**Spec:** [docs/superpowers/specs/2026-09-05-new-effects-batch.md](../specs/2026-09-05-new-effects-batch.md)

## Global Constraints

- Params are floats; modes are numeric (0/1) — no string params.
- Built-in fade convention: the main param (`shift`, `amplitude`) is animated to `0` in the built-in definitions (`param(name, start, end)`).
- `neutrValue` cases: add `neutralValue()` entries so fade weight blends toward neutral.
- Docs: follow the new per-parameter format, copy-pasteable examples.
- `gradlew build` green after every task.

---

### Task 1: `slice_shift` screen effect

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/post/slice_shift.fsh`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXEffectType.java` — enum `SLICE_SHIFT("slice_shift")` + `neutralValue()` case
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` — `registerPost(VFXEffectType.SLICE_SHIFT, "angle", "offset", "shift", "mirror")`
- Modify: `src/main/java/dev/vfxweaver/resource/VFXDefinitionManager.java` — built-in

**Interfaces:**
- Produces: `VFXEffectType.SLICE_SHIFT`, shader `vfxweaver:post/slice_shift`, Config params order `angle, offset, shift, mirror`. Task 5 docs reference these.

- [ ] **Step 1: enum + neutral.** Add `SLICE_SHIFT("slice_shift")` to the `VFXEffectType` enum; add to `neutralValue()`:
```java
case SLICE_SHIFT -> "shift".equals(parameter) ? 0.0F : Float.NaN;
```

- [ ] **Step 2: register.** In `VFXShaderPrograms.register()` after VORTEX:
```java
registerPost(VFXEffectType.SLICE_SHIFT, "angle", "offset", "shift", "mirror");
```

- [ ] **Step 3: shader.** Create `post/slice_shift.fsh` (POST_PROCESSING_SNIPPET pattern, same header as `post/blur.fsh`):
```glsl
#version 330
#moj_import <minecraft:dynamictransforms.glsl>
layout(std140) uniform Config { float angle; float offset; float shift; float mirror; };
uniform sampler2D InSampler; in vec2 uv; out vec4 fragColor;

void main() {
    vec2 corr = vec2(uv.x, uv.y * InSize.y / InSize.x);      // aspect-corrected
    float a = radians(angle);
    vec2 n = vec2(cos(a + 1.5707963), sin(a + 1.5707963));
    vec2 linePoint = vec2(0.5, 0.5) + n * offset;
    float side = sign(dot(uv - linePoint, n));
    if (side == 0.0) side = 1.0;
    vec2 shifted = uv - side * shift * vec2(cos(a), sin(a));
    vec2 wrapped = fract(shifted);
    vec2 mirrored = abs(2.0 * fract(shifted / 2.0) - 1.0);
    vec2 finalUV = mix(wrapped, mirrored, mirror);
    fragColor = texture(InSampler, finalUV);
}
```
Note: if `uv` from `core/screenquad` vsh is already 0..1 with no flip, keep as-is; verify orientation on screen (it must match `blur.fsh` sampling of InSampler).

- [ ] **Step 4: built-in definition.** In `registerBuiltIns()`:
```java
this.builtIns.put(Identifier.fromNamespaceAndPath("vfxweaver", "slice_shift"),
    builtIn("vfxweaver", "slice_shift", VFXEffectType.SLICE_SHIFT, 40, EasingType.EASE_IN_OUT_CUBIC,
        param("angle", 0.0F), param("offset", 0.0F), param("shift", 0.05F, 0.0F), param("mirror", 0.0F)));
```

- [ ] **Step 5: build + visual test** (`gradlew build`; `gradlew runClient`, then `/vfx play vfxweaver:slice_shift {[angle:25],[shift:0.12]}`).

- [ ] **Step 6: Commit** — `feat(post): slice_shift screen effect`.

### Task 2: `noise_warp` screen effect

**Files:**
- Create: `src/client/resources/assets/vfxweaver/shaders/post/noise_warp.fsh`
- Modify: same four files as Task 1 (enum `NOISE_WARP("noise_warp")`, registerPost with `scale, amplitude, contrast, coherence, speed, drift_x, drift_y`, neutral case `"amplitude" -> 0.0F`, built-in with `amplitude` 0.03 → 0)

**Interfaces:**
- Produces: `VFXEffectType.NOISE_WARP`, shader `vfxweaver:post/noise_warp`, Config order `scale, amplitude, contrast, coherence, speed, drift_x, drift_y`.

- [ ] **Step 1:** enum + `neutralValue`:
```java
case NOISE_WARP -> "amplitude".equals(parameter) ? 0.0F : Float.NaN;
```

- [ ] **Step 2:** `registerPost(VFXEffectType.NOISE_WARP, "scale", "amplitude", "contrast", "coherence", "speed", "drift_x", "drift_y");`

- [ ] **Step 3: shader** `post/noise_warp.fsh`:
```glsl
#version 330
#moj_import <minecraft:dynamictransforms.glsl>
layout(std140) uniform Config { float scale; float amplitude; float contrast; float coherence; float speed; float drift_x; float drift_y; };
uniform sampler2D InSampler; in vec2 uv; out vec4 fragColor;

float hash(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }
float vnoise(vec3 p) { vec3 i = floor(p); vec3 f = fract(p);
    vec3 s = f * f * (3.0 - 2.0 * f);
    float a = hash(i), b = hash(i + vec3(1,0,0)), c = hash(i + vec3(0,1,0)), d = hash(i + vec3(1,1,0));
    float e = hash(i + vec3(0,0,1)), f2 = hash(i + vec3(1,0,1)), g = hash(i + vec3(0,1,1)), h = hash(i + vec3(1,1,1));
    return mix(mix(mix(a,b,s.x), mix(c,d,s.x), s.y), mix(mix(e,f2,s.x), mix(g,h,s.x), s.y), s.z); }

void main() {
    vec2 corr = uv * scale; corr.x *= InSize.x / InSize.y;
    vec3 field = vec3(corr + vec2(drift_x, drift_y) * 8.0, speed * 8.0);
    float mag = pow(vnoise(field), 2.0);                        // contrast curve
    vec2 rndDir = normalize(vec2(hash(field), hash(field + 17.0)) - 0.5);
    vec2 grad = vec2(vnoise(field + vec3(0.05, 0, 0)) - vnoise(field - vec3(0.05, 0, 0)),
                     vnoise(field + vec3(0, 0.05, 0)) - vnoise(field - vec3(0, 0.05, 0)));
    vec2 dir = mix(rndDir, grad, coherence);
    fragColor = texture(InSampler, uv + dir * mag * amplitude);
}
```

- [ ] **Step 4:** built-in definition:
```java
this.builtIns.put(Identifier.fromNamespaceAndPath("vfxweaver", "noise_warp"),
    builtIn("vfxweaver", "noise_warp", VFXEffectType.NOISE_WARP, 60, EasingType.EASE_IN_OUT_CUBIC,
        param("scale", 8.0F), param("amplitude", 0.03F, 0.0F), param("contrast", 2.0F),
        param("coherence", 1.0F), param("speed", 0.5F), param("drift_x", 0.0F), param("drift_y", 0.0F)));
```

- [ ] **Step 5: build + visual test** (`[amplitude:0.06],[scale:4],[contrast:3]` should melt the image in patches).

- [ ] **Step 6: Commit** — `feat(post): noise_warp screen effect`.

### Task 3: `entity_displace` — CPU vertex displacement second pass

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXEntityEffectRenderer.java` — add `renderDisplace` + two FxType entries `ENTITY_DISPLACE_VISIBLE/OCCLUDED` (pipelines copied from the existing `TINT_MASK_VISIBLE/OCCLUDED` pattern: ENTITY format, translucent, no texture sampling in fragment — flat `vertexColor` output; verify with the existing `entity_fx.fsh` TINT_MASK branch or a tiny `displace.fsh`)
- Modify: `src/client/java/dev/vfxweaver/client/mixin/LivingEntityRendererMixin.java` — route `ENTITY_DISPLACE`... (actually route inside `renderTint`-style switch: add a `VFXEffectType.ENTITY_DISPLACE` branch in the effect loop calling `renderDisplace`)

**Interfaces:**
- Consumes: existing `submitCustomGeometry` + `arm/root().visit` emission pattern (see `renderOutline`), `argb(effect, alpha)`, effect targeting by UUID.
- Produces: static `renderDisplace(effect, state, poseStack, collector, model, texture)` called from the mixin for `VFXEffectType.ENTITY_DISPLACE`.

- [ ] **Step 1: pipelines.** Add in `VFXEntityEffectRenderer`:
```java
private static final RenderPipeline DISPLACE_VISIBLE_P = entityFxPipeline("displace_visible", CompareOp.LESS_THAN_OR_EQUAL, "DISPLACE");
private static final RenderPipeline DISPLACE_OCCLUDED_P = entityFxPipeline("displace_occluded", CompareOp.ALWAYS_PASS, "DISPLACE");
```
and FxTypes `ENTITY_DISPLACE_VISIBLE/OCCLUDED` (name prefix `vfxweaver_entity_displace_...`).

- [ ] **Step 2: CPU noise helper.**
```java
private static float vhash(float x, float y, float z, float seed) {
    float h = x * 37.719F + y * 71.317F + z * 151.589F + seed * 3.137F;
    return (float) Math.floor(fract(h) * 21.0F) / 21.0F * 2.0F - 1.0F;   // quantised -1..1
}
private static float fract(float v) { return v - (float) Math.floor(v); }
```

- [ ] **Step 3: renderDisplace.** Mirror `renderOutline` structure (through → occluded/always pair, alpha handling):
```java
float amplitude = clamp01(effect.getParam("amplitude", 0.1F)) * effect.getWeight();
if (amplitude <= 0.0F) return;
float scale = Math.max(effect.getParam("scale", 4.0F), 0.5F);
float seed = effect.getParam("seed", 0.0F);
int color = argb(effect, clamp01(effect.getParam("alpha", 1.0F)) * effect.getWeight());
RenderType renderType = (through ? ENTITY_DISPLACE_OCCLUDED : ENTITY_DISPLACE_VISIBLE).forTexture(texture);
model.setupAnim(state);
submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
    PoseStack stack = new PoseStack();
    stack.last().set(pose);
    model.root().visit(stack, (partPose, path, cubeIndex, cube) ->
        emitDisplacedCube(partPose, buffer, cube, amplitude, scale, seed, color, state.lightCoords));
});
```
`emitDisplacedCube` = copy of `emitOutlineCube` with `thickness == 0` growth and a per-vertex offset:
```java
float ox = vhash(pos0x, pos0y, pos0z, seed) * amplitude;
```
where `(pos0x/y/z)` are the untransformed per-vertex coords (each vertex gets its own hash inputs from `v.worldX()/worldY()/worldZ()` and its corner index), applied along the vertex normal direction.

- [ ] **Step 4: mixin routing.** In `LivingEntityRendererMixin.vfxweaver$applyEntityEffects` add:
```java
} else if (effect.getType() == VFXEffectType.ENTITY_DISPLACE) {
    VFXEntityEffectRenderer.renderDisplace(effect, state, poseStack, submitNodeCollector, this.model, texture);
}
```

- [ ] **Step 5:** add enum `ENTITY_DISPLACE("entity_displace")` + `VFXDefinition` import in `VFXEffectType` handling (isWorldOverlay: add ENTITY_DISPLACE if routed via world overlay path — verify which path the effect loop uses; it is driven by `getActiveEntityEffects`, no isWorldOverlay change needed unless the manager filters).

- [ ] **Step 6: built-in definition.** In `registerBuiltIns()`:
```java
this.builtIns.put(Identifier.fromNamespaceAndPath("vfxweaver", "entity_displace"),
    builtIn("vfxweaver", "entity_displace", VFXEffectType.ENTITY_DISPLACE, 40, EasingType.EASE_IN_OUT_CUBIC,
        param("amplitude", 0.1F, 0.0F), param("scale", 4.0F), param("seed", 0.0F),
        param("alpha", 1.0F), param("color_r", 1.0F), param("color_g", 1.0F), param("color_b", 1.0F)));
```

- [ ] **Step 7: build + visual test** (`/vfx playentity vfxweaver:entity_displace @e[type=zombie,limit=1] {[amplitude:0.2],[scale:6]}` — model jitters out of place; `[seed:{expr:"floor(t * 8) * 0.1"}]` — snaps 8×/s).

- [ ] **Step 8: Commit** — `feat(render): entity_displace effect`.

### Task 4: `block_displace` — CPU displaced block model quads

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java` — two new RTs (displace visible/occluded, position_color translucent, `blockPipeline(CompareOp, false, "displace_...")`), `renderDisplace` branch in the BLOCK_OUTLINE-style loop, `emitQuadsDisplaced` (copy of `emitQuads` adding per-vertex `vhash` offset along the quad normal, inputs: world-space vertex coords + seed)

**Interfaces:**
- Consumes: `effectPositions(effect)`, `getModelQuads(minecraft, pos)`, existing `blockPipeline`/`emitQuads` helpers.
- Produces: `BLOCK_DISPLACE` handling in `VFXWorldOverlayRenderer.render()`.

- [ ] **Step 1: RTs:**
```java
private static final RenderType DISPLACE_VISIBLE = RenderType.create(
    "vfxweaver_block_displace_visible",
    RenderSetup.builder(blockPipeline(CompareOp.ALWAYS_PASS, false, "displace_visible")).createRenderSetup());
private static final RenderType DISPLACE_OCCLUDED = RenderType.create(
    "vfxweaver_block_displace_occluded",
    RenderSetup.builder(blockPipeline(CompareOp.LESS_THAN_OR_EQUAL, false, "displace_occluded")).createRenderSetup());
```

- [ ] **Step 2: branch in `render()`:** `BLOCK_DISPLACE` → `through ? DISPLACE_VISIBLE : DISPLACE_OCCLUDED` (same pattern as BLOCK_TINT; no inset, no extrusion).

- [ ] **Step 3: displaced emission.** New `emitQuadsDisplaced(buffer, pose, quads, color, seed, amplitude, worldOrigin(BlockPos))`: per vertex, hash the **world-space** vertex position (block pos + local vertex coords) with the Task-3 `vhash` (make it package-visible or move to a small `VFXNoise` helper), offset along the quad normal by `hash * amplitude` (blocks), flat color.

- [ ] **Step 4: built-in definition.** `vfxweaver:block_displace`, duration 40, params `amplitude 0.15 (animated to 0)`, `scale 4.0`, `seed 0.0`, `alpha 1.0`, `color` white defaults, `through_blocks` unused (both modes exist as RTs).

- [ ] **Step 5: build + visual test** (`/vfx playat vfxweaver:block_displace 8 70 8 {[amplitude:0.2],[seed:0]}` — block quads jitter; `[seed:{expr:"floor(t * 8) * 0.1"}]` — snappy).

- [ ] **Step 6: Commit** — `feat(render): block_displace effect`.

### Task 5: `hud_fade` (Misc)

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/hud/HudFadeState.java` — static float `opacity` (1..0) + `chatOpacity`, updated from the active effect each frame via `VFXEffectManager`
- Create: `src/client/java/dev/vfxweaver/client/mixin/GuiFadeMixin.java` — targets `Gui`, injects at `render` HEAD: `RenderSystem.setShaderColor(1, 1, 1, hudOpacity)` ... or applies a global alpha via the dispatcher available in 26.1 (inspect how chat/chat opacity is already implemented by the game and mirror it)
- Modify: `src/client/java/dev/vfxweaver/client/VFXClient.java` or the effect update path — writes `opacity`/`chat` values from the active `hud_fade` effect every frame

**Interfaces:**
- Produces: `HudFadeState.getOpacity()` and `getChatOpacity()` floats consumed by the mixin.

- [ ] **Step 1:** implement `HudFadeState` + the effect→state sync (mirror the camera_shake update path).
- [ ] **Step 2:** implement the mixin; the exact injection point must be verified against `Gui.render` in the mapped sources (the alpha mechanism depends on 26.1 internals - inspect `Gui`/`GuiRenderer` and pick the hook that covers hotbar/hears/xbar/chat).
- [ ] **Step 3:** built-in `vfxweaver:hud_fade` (duration 40, `opacity 0 (animated 1 → 0)`, `chat 1`); register in `VFXEffectType` as a non-post type like `CAMERA_SHAKE`.
- [ ] **Step 4: build + visual test + Commit** — `feat(hud): hud_fade effect`.

### Task 6: `camera_roll` (Misc)

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/mixin/CameraMixin.java` (or the camera shake application point) — after the existing shake application, apply roll: rotate the view around the camera forward axis
- Modify: `src/client/java/dev/vfxweaver/client/shake/CameraShakeManager.java` or a new `VFXCameraOverlays` helper — computes roll from `camera_roll` effects (`angle * weight + sin(t * wobble_speed * 6.28) * wobble * weight`)

**Interfaces:**
- Produces: roll angle in degrees applied as `poseStack.mulPose(Axis.ZP.rotationDegrees(roll))` (or the 26.1 equivalent in the camera state) at the same hook where `camera_shake` rotates the camera.

- [ ] **Step 1:** implement the roll calculation next to the shake calculation.
- [ ] **Step 2:** apply it at the same transform point the shake uses.
- [ ] **Step 3:** built-in `vfxweaver:camera_roll` (duration 40, `angle 15 → 0`, `wobble 0`, `wobble_speed 0.2`).
- [ ] **Step 4: build + visual test (tilt visible, screen stays interactive) + Commit** — `feat(render): camera_roll effect`.

### Task 7: `god_rays` (Entity - dragon-death beams)

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/render/VFXBeamsRenderer.java` — N additive vertical quads rising from the entity body, billboarded to the camera, alpha fading along the rise; spread over the body radius; sway by sin(t)
- Modify: `src/client/java/dev/vfxweaver/client/mixin/ItemFrameRendererMixin.java` pattern -> new `EntityBeamsMixin`... (no: beams are drawn from the same `LivingEntityRendererMixin` effect loop - add a `VFXEffectType.GOD_RAYS` branch calling `VFXBeamsRenderer.render(state/camera...)`)

**Interfaces:**
- Consumes: the entity render pose (like entity_tint), `Additive` blending (new pipelines `god_rays_visible/occluded` with `BlendFunction.ADDITIVE` or `new ColorTargetState(new BlendFunction(...))`), light coords from the state.

- [ ] **Step 1:** additive pipelines (two depth variants) + a `VFXBeamsRenderer.renderEffects(entityPose, collector, state)` emitting `count` rising quads.
- [ ] **Step 2:** built-in `vfxweaver:god_rays` (duration 60, params per the spec).
- [ ] **Step 3: build + visual test + Commit** — `feat(render): god_rays beams effect`.

### Task 8: World quad effects — `light_beam`, `pulse_ring`, `scan_sweep`, `guide_line`

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java` — four new effect branches (quad generators + additive pipelines) + `VFXEffectType` additions (`LIGHT_BEAM`, `PULSE_RING`, `SCAN_SWEEP`, `GUIDE_LINE`; `isWorldOverlay()` updated)

**Interfaces:**
- Consumes: the existing world-overlay loop (positions/region, camera-relative PoseStack, `blockPipeline`-style registrations with `BlendFunction.ADDITIVE` for the glow quads).

- [ ] **Step 1:** additive pipeline pair (visible/occluded) shared by the four effects; register in `VFXShaderPrograms`-style block (or the overlay renderer's own statics, mirroring `blockPipeline`).
- [ ] **Step 2:** `light_beam` - vertical billboarded column (2 quads), alpha gradient to the top, sway offset per vertex.
- [ ] **Step 3:** `pulse_ring` - flat annulus segments (12-24 segments), alpha soft edges, tilt by pitch.
- [ ] **Step 4:** `scan_sweep` - sheet quad across the axis + 3-5 trail quads with decreasing alpha.
- [ ] **Step 5:** `guide_line` - dashed quad chain along a parabola between two anchors (the region corners), dash phase from time.
- [ ] **Step 6:** built-in definitions for all four (params per the spec).
- [ ] **Step 7: build + visual test each + Commit** — `feat(render): world quad effects (light_beam, pulse_ring, scan_sweep, guide_line)`.

### Task 9: feedback buffer infrastructure + `afterimage`, `stop_motion`

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXPostProcessingManager.java` — add a persistent history target (`TextureTarget` created/destroyed alongside the ping-pong targets, sized on resize), plus `getHistoryTarget()`
- Modify: `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` — two new passes: `afterimage.fsh` (feedback mix: `hist = mix(prev.zoomed(drift), current, blend)`, desaturation, output) and `stop_motion.fsh` (sample history at `floor(t * fps) / fps`)
- Modify: the pass executor - these two passes read AND write the history target (keep the current frame copy flow: copy main -> history-prep)

**Interfaces:**
- Produces: the history target lifecycle + two ProgramInfos (`afterimage` with params `decay/blend/drift/desat/intensity`; `stop_motion` with `fps/intensity`).

- [ ] **Step 1:** add the persistent history target lifecycle (resize-aware, freed on shutdown via `freeGpuResources`).
- [ ] **Step 2:** implement `afterimage` shader per the spec (feedback mix + desaturation + drift zoom).
- [ ] **Step 3:** implement `stop_motion` shader per the spec (time-quantized history sampling).
- [ ] **Step 4:** built-in definitions: `vfxweaver:afterimage` (duration 60, per spec), `vfxweaver:stop_motion` (duration 60, `fps 12 → 0`).
- [ ] **Step 5: build + visual test (trails/quantization visible, fade returns to normal) + Commit** — `feat(post): feedback buffer + afterimage + stop_motion`.

### Task 10: docs + changelog for the whole batch

**Files:**
- Modify: `docs/GUIDE.md` - new per-parameter subsections for every Task 1-17 effect (format: param / default / description + example), options line updates
- Modify: `docs/CHANGELOG.md` - unreleased Added entries

- [ ] **Step 1:** write the doc subsections from the shipped params (copy the tables used in the source test commands).
- [ ] **Step 2: Commit** - `docs: document the new effects batch`.

---


### Task 11: `solarize` screen effect

**Files:** Create `shaders/post/solarize.fsh`; modify `VFXEffectType` (+enum, +neutral), `VFXShaderPrograms` (+registerPost).

- [ ] **Step 1: shader.** Header as blur.fsh; Config `{ float threshold; float softness; float intensity; }`:
```glsl
void main() {
    vec4 c = texture(InSampler, uv);
    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    float t = smoothstep(threshold - softness / 2.0 - 1.0e-4, threshold + softness / 2.0 + 1.0e-4, luma);
    fragColor = vec4(mix(c.rgb, 1.0 - c.rgb, t * intensity), c.a);
}
```

- [ ] **Step 2: wiring.** `SLICE_SHIFT`-pattern: enum `SOLARIZE("solarize")`; `registerPost(VFXEffectType.SOLARIZE, "threshold", "softness", "intensity")`; `neutralValue`: `"intensity" -> 0.0F`; built-in: duration 40, params `threshold 0.5`, `softness 0`, `intensity 1 → 0`.

- [ ] **Step 3: build + visual test + Commit** — `feat(post): solarize screen effect`.

### Task 12: `double_vision` screen effect

**Files:** Create `post/double_vision.fsh`; modify the three files (pattern of Task 6).

- [ ] **Step 1: shader.** Config `{ float offset; float ghost_opacity; float drift; float intensity; }`; uniform `float time` added to Config (see film_grain `time`):
```glsl
void main() {
    float drift = sin(time * 1.2) * drift_amp;   // drift_amp = drift * 8.0 from java side? keep: pass drift already in fractions
    vec2 base = vec2(offset + drift, 0.0);
    vec4 c = texture(InSampler, uv) * (1.0 - ghost_opacity * intensity);
    c += texture(InSampler, uv + base) * ghost_opacity * intensity;
    c += texture(InSampler, uv - base) * ghost_opacity * intensity;
    fragColor = vec4(c.rgb, 1.0);
}
```
Note: keep Config params `offset`, `ghost_opacity`, `drift`, `intensity` (drift animated in shader via `time`; `time` is already passed for film_grain/scanlines - reuse that plumbing).

- [ ] **Step 2: wiring + built-in.** `registerPost(VFXEffectType.DOUBLE_VISION, "offset", "ghost_opacity", "drift", "intensity", "time")`; enum `DOUBLE_VISION("double_vision")`; neutral `"intensity" -> 0.0F`; built-in: duration 60, params `offset 0.04`, `ghost_opacity 0.5`, `drift 0.02`, `intensity 1 → 0`.

- [ ] **Step 3: build + visual test + Commit** — `feat(post): double_vision screen effect`.

### Task 13: `eyelids` screen effect

**Files:** Create `post/eyelids.fsh`; modify the three files (pattern of Task 6).

- [ ] **Step 1: shader.** Config `{ float openness; float softness; float curve; }`:
```glsl
void main() {
    float halfOpen = openness / 2.0;                       // lid travel from each edge
    float bulge = curve * 0.5 * (1.0 - 4.0 * pow(uv.x - 0.5, 2.0));
    float lidTop = halfOpen + bulge;                       // top lid edge, uv.y from bottom
    float lidBottom = 1.0 - halfOpen - bulge;
    float t = smoothstep(lidTop - softness, lidTop + softness, uv.y);
    float b = smoothstep(lidBottom - softness, lidBottom + softness, 1.0 - uv.y);
    float mask = clamp(t + b, 0.0, 1.0);
    fragColor = vec4(0.0, 0.0, 0.0, mask);
}
```

- [ ] **Step 2: wiring + built-in.** `registerPost(..., "openness", "softness", "curve")`; enum `EYELIDS("eyelids")`; neutral `"openness" -> 1.0F`; built-in: duration 40, params `openness 0.5`, `softness 0.15`, `curve 0.35` (constants - owner animates openness via keyframes/bindings).

- [ ] **Step 3: build + visual test + Commit** — `feat(post): eyelids screen effect`.

### Task 14: `iris_wipe` screen effect

**Files:** Create `post/iris_wipe.fsh`; modify the three files (pattern of Task 6).

- [ ] **Step 1: shader.** Config `{ float radius; float softness; float center_x; float center_y; float zoom; }`:
```glsl
void main() {
    vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
    vec2 corr = (uv - vec2(center_x, center_y)) * aspect;
    float dist = length(corr);
    float mask = smoothstep(radius - softness, radius + softness, dist);
    float zoomFactor = 1.0 - zoom * (1.0 - clamp(dist / max(radius, 1.0e-4), 0.0, 1.0));
    vec2 zoomed = vec2(center_x, center_y) + (uv - vec2(center_x, center_y)) * zoomFactor;
    vec4 inner = texture(InSampler, zoomed);
    fragColor = mix(inner, vec4(0.0, 0.0, 0.0, 1.0), mask);
}
```

- [ ] **Step 2: wiring + built-in.** Params `radius (animated 1.4 → 0)`, `softness 0.05`, `center_x 0.5`, `center_y 0.5`, `zoom 0`; neutral `"radius" -> 1.4F`; enum `IRIS_WIPE("iris_wipe")`.

- [ ] **Step 3: build + visual test + Commit** — `feat(post): iris_wipe screen effect`.

### Task 15: `digital_glitch` screen effect

**Files:** Create `post/digital_glitch.fsh`; modify the three files (pattern of Task 6). Config `{ float block; float displacement; float rate; float chroma; float seed; float intensity; float time; }`.

- [ ] **Step 1: shader.**
```glsl
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
void main() {
    float band = floor(uv.y / max(block, 1.0e-3));
    float burst = step(1.0 - 1.0 / max(rate, 1.0e-3), hash(vec2(band, floor(time * rate) + seed)));
    float shift = (hash(vec2(band, floor(time * rate) + seed + 99.0)) - 0.5) * displacement * burst;
    vec2 uvG = uv + vec2(shift, 0.0);
    vec4 c;
    c.r = texture(InSampler, uvG + vec2(chroma * burst, 0.0)).r;
    c.g = texture(InSampler, uvG).g;
    c.b = texture(InSampler, uvG - vec2(chroma * burst, 0.0)).b;
    c.a = 1.0;
    vec4 orig = texture(InSampler, uv);
    fragColor = mix(orig, c, intensity);
}
```

- [ ] **Step 2: wiring + built-in.** Params per Config order; neutral `"intensity" -> 0.0F`; built-in: duration 40, params `block 0.06`, `displacement 0.08`, `rate 6`, `chroma 0.5`, `seed 0`, `intensity 1 → 0`.

- [ ] **Step 3: build + visual test + Commit** — `feat(post): digital_glitch screen effect`.

### Task 16: `vhs` screen effect

**Files:** Create `post/vhs.fsh`; modify the three files (pattern of Task 6). Config `{ float tracking; float band_height; float band_speed; float bleed; float wobble; float intensity; float time; }`.

- [ ] **Step 1: shader.**
```glsl
void main() {
    float bandY = fract(uv.y - time * band_speed);
    float inBand = 1.0 - smoothstep(band_height * 0.5, band_height, abs(uv.y - bandY));
    float wob = (hash(vec2(floor(uv.y * InSize.y), floor(time * 60.0))) - 0.5) * wobble;
    float shift = (hash(vec2(floor(uv.y - time * band_speed * 8.0), floor(time * 12.0))) - 0.5) * tracking * inBand;
    vec2 uvG = uv + vec2(shift + wob, 0.0);
    vec4 c;
    c.r = texture(InSampler, uvG + vec2(bleed, 0.0)).r;
    c.g = texture(InSampler, uvG).g;
    c.b = texture(InSampler, uvG + vec2(-bleed * 2.0, 0.0)).b;
    c.a = 1.0;
    c.rgb = (c.rgb - 0.08) / 0.92;                         // washed-out contrast
    fragColor = vec4(mix(texture(InSampler, uv).rgb, c.rgb, intensity), 1.0);
}
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
```
(Place `hash` above `main`.)

- [ ] **Step 2: wiring + built-in.** Params per Config order; neutral `"intensity" -> 0.0F`; built-in: duration 60, `tracking 0.35`, `band_height 0.08`, `band_speed 0.15`, `bleed 0.02`, `wobble 0.004`, `intensity 1 → 0`.

- [ ] **Step 3: build + visual test + Commit** — `feat(post): vhs screen effect`.

### Task 17: `shockwave` screen effect

**Files:** Create `post/shockwave.fsh`; modify the three files (pattern of Task 6). Config `{ float center_x; float center_y; float radius; float width; float amplitude; float sharpness; }`.

- [ ] **Step 1: shader.**
```glsl
void main() {
    vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
    vec2 corr = (uv - vec2(center_x, center_y)) * aspect;
    float dist = length(corr);
    float d = (dist - radius) / max(width, 1.0e-3);
    float profile = cos(d * 3.14159265 / max(sharpness, 1.0e-3)) * exp(-d * d) ;
    vec2 dir = normalize(corr + vec2(1.0e-5));
    vec4 c = texture(InSampler, uv + dir * amplitude * profile);
    fragColor = vec4(mix(texture(InSampler, uv).rgb, c.rgb, 0.85), 1.0);
}
```

- [ ] **Step 2: wiring + built-in.** Params per Config order; neutral: `"amplitude" -> 0.0F`, `"radius" -> 1.5F`; built-in: duration 40, `center_x/y 0.5`, `radius 0.4 (animated 0.4 → 1.5)`, `width 0.15`, `amplitude 0.12 → 0`, `sharpness 1.5`.

- [ ] **Step 3: build + visual test + Commit** — `feat(post): shockwave screen effect`.


## Self-Review checklist (run after drafting)



- [ ] Spec coverage: slice_shift ✓, noise_warp ✓, entity_displace ✓, block_displace ✓, dropped items not implemented ✓.
- [ ] No placeholders: every shader/Java step above contains full code or a copy-from instruction that names the exact source file.
- [ ] Naming: `slice_shift`/`noise_warp`/`entity_displace`/`block_displace` consistent across enum, shaders, definitions, docs.

## Execution Handoff

Two options: subagent-driven (fresh executor per task) or inline batch. The plan is small enough for inline execution with per-task commits.

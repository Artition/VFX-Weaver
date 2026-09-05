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

### Task 5: docs + changelog + release notes

**Files:**
- Modify: `docs/GUIDE.md` — add §2.1 entries for `slice_shift`/`noise_warp` (param tables + examples), §2.2 `block_displace`, §2.3 `entity_displace` (with the "displaced copy over the normal render" caveat), options line update
- Modify: `docs/CHANGELOG.md` — v1.0.8/unreleased Added entries

- [ ] **Step 1:** GUIDE §2.1 — insert after `hue_isolation`:
```markdown
#### `slice_shift`
... (table + example from the spec: docs/superpowers/specs/2026-09-05-new-effects-batch.md)
```
```markdown
#### `noise_warp`
... (table + example from the spec)
```
- [ ] **Step 2:** GUIDE §2.2 — add `block_displace` (params `amplitude`/`scale`/`seed`, both through modes).
- [ ] **Step 3:** GUIDE §2.3 — add `entity_displace` (note: the displaced copy is drawn over the normal body; `through_blocks` swaps occluded/always pipelines).
- [ ] **Step 4:** CHANGELOG — Added entries for all three effects.
- [ ] **Step 5: Commit** — `docs: document slice_shift, noise_warp, entity/block_displace`.

---

## Self-Review checklist (run after drafting)

- [ ] Spec coverage: slice_shift ✓, noise_warp ✓, entity_displace ✓, block_displace ✓, dropped items not implemented ✓.
- [ ] No placeholders: every shader/Java step above contains full code or a copy-from instruction that names the exact source file.
- [ ] Naming: `slice_shift`/`noise_warp`/`entity_displace`/`block_displace` consistent across enum, shaders, definitions, docs.

## Execution Handoff

Two options: subagent-driven (fresh executor per task) or inline batch. The plan is small enough for inline execution with per-task commits.

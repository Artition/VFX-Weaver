# Depth-binding findings for 26.2 post passes

Probe: Task 1 of `docs/superpowers/plans/2026-09-19-depth-and-worldpos.md`.
Method: `javap` against the real 26.2 deobf jars + the vanilla resource pack in the client jar.

Jars used:

```powershell
$cp = "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-clientonly-deobf\26.2\minecraft-clientonly-deobf-26.2.jar"
$cp = "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common-deobf\26.2\minecraft-common-deobf-26.2.jar"
```

> PowerShell trap: class names containing `$` **must** be single-quoted, otherwise
> `$InputTexture` / `$TargetInput` is expanded as an empty variable and `javap` silently reads
> the outer class instead.

---

## Question 1 — Is the main scene depth target bindable as an input of a post pass on 26.2? Under what class/type and identifier name?

**Yes.** The vanilla depth-input mechanism exists and is exercised by the vanilla chain itself.

`net.minecraft.client.renderer.PostPass$TargetInput` carries a `depthBuffer` flag; its
`texture(...)` method returns `RenderTarget.getDepthTextureView()` when the flag is true:

```
public final class net.minecraft.client.renderer.PostPass$TargetInput extends java.lang.Record implements net.minecraft.client.renderer.PostPass$Input {
  private final java.lang.String samplerName;
  private final net.minecraft.resources.Identifier targetId;
  private final boolean depthBuffer;
  private final boolean bilinear;
  public net.minecraft.client.renderer.PostPass$TargetInput(java.lang.String, net.minecraft.resources.Identifier, boolean, boolean);
  public com.mojang.blaze3d.textures.GpuTextureView texture(java.util.Map<net.minecraft.resources.Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>>);
  public java.lang.String samplerName();
  public net.minecraft.resources.Identifier targetId();
  public boolean depthBuffer();
  public boolean bilinear();
}
```

`javap -c 'net.minecraft.client.renderer.PostPass$TargetInput'` on `texture(...)` (verbatim):

```
  public com.mojang.blaze3d.textures.GpuTextureView texture(java.util.Map<net.minecraft.resources.Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>>);
    Code:
         0: aload_0
         1: aload_1
         2: invokevirtual #47                 // Method getHandle:(Ljava/util/Map;)Lcom/mojang/blaze3d/resource/ResourceHandle;
         5: astore_2
         6: aload_2
         7: invokeinterface #57,  1           // InterfaceMethod com/mojang/blaze3d/resource/ResourceHandle.get:()Ljava/lang/Object;
        12: checkcast     #60                 // class com/mojang/blaze3d/pipeline/RenderTarget
        15: astore_3
        16: aload_0
        17: getfield      #17                 // Field depthBuffer:Z
        20: ifeq          30
        23: aload_3
        24: invokevirtual #62                 // Method com/mojang/blaze3d/pipeline/RenderTarget.getDepthTextureView:()Lcom/mojang/blaze3d/textures/GpuTextureView;
        27: goto          34
        30: aload_3
        31: invokevirtual #66                 // Method com/mojang/blaze3d/pipeline/RenderTarget.getColorTextureView:()Lcom/mojang/blaze3d/textures/GpuTextureView;
        34: astore        4
```

The main target identifier is a constant on `PostChain`:

```
public static final net.minecraft.resources.Identifier MAIN_TARGET_ID;
```

`javap -c 'net.minecraft.client.renderer.PostChain'`, static initializer (verbatim):

```
  static {};
    Code:
         0: ldc_w         #460                // String main
         3: invokestatic  #517                // Method net/minecraft/resources/Identifier.withDefaultNamespace:(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;
         6: putstatic     #456                // Field MAIN_TARGET_ID:Lnet/minecraft/resources/Identifier;
         9: return
```

So the identifier is `minecraft:main` (`PostChain.MAIN_TARGET_ID`).

Direct proof from the vanilla datapack — `assets/minecraft/post_effect/transparency.json` (extracted from
`minecraft-clientonly-deobf-26.2.jar`), verbatim:

```json
{
    "targets" : {
        "final": {}
    },
    "passes": [
        {
            "vertex_shader": "minecraft:core/screenquad",
            "fragment_shader": "minecraft:post/transparency",
            "inputs": [
                {
                    "sampler_name": "Main",
                    "target": "minecraft:main"
                },
                {
                    "sampler_name": "MainDepth",
                    "target": "minecraft:main",
                    "use_depth_buffer": true
                },
```

Vanilla binds the main scene depth as sampler `MainDepth` from target `minecraft:main`.

**Answer:** yes — depth is bindable. Type
`net.minecraft.client.renderer.PostPass$TargetInput(String samplerName, Identifier targetId, boolean depthBuffer, boolean bilinear)`;
identifier `minecraft:main` (`PostChain.MAIN_TARGET_ID`); any sampler name (vanilla uses
`MainDepth`). With `depthBuffer == true` the bound view is `RenderTarget.getDepthTextureView()`.

---

## Question 2 — What do `PostChain` / `TargetBundle` / `PostChainConfig$TargetInput` / `PostPass$TargetInput` / `PostPass$InputTexture` expose, and does any carry depth?

### `net.minecraft.client.renderer.PostChain$TargetBundle`

```
public interface net.minecraft.client.renderer.PostChain$TargetBundle {
  public static net.minecraft.client.renderer.PostChain$TargetBundle of(net.minecraft.resources.Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>);
  public abstract void replace(net.minecraft.resources.Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>);
  public abstract com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> get(net.minecraft.resources.Identifier);
  public default com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> getOrThrow(net.minecraft.resources.Identifier);
}
```

`TargetBundle` is a map of target-id → `ResourceHandle<RenderTarget>`. It does **not** carry depth
itself; depth lives on the `RenderTarget` it hands out. `PostChain` keys the main target into it with
the string `"main"` (`FrameGraphBuilder.importExternal("main", mainRenderTarget)` —
`invokevirtual com/mojang/blaze3d/framegraph/FrameGraphBuilder.importExternal:(Ljava/lang/String;Ljava/lang/Object;)`).

### `net.minecraft.client.renderer.PostChainConfig$TargetInput`

```
public final class net.minecraft.client.renderer.PostChainConfig$TargetInput extends java.lang.Record implements net.minecraft.client.renderer.PostChainConfig$Input {
  private final java.lang.String samplerName;
  private final net.minecraft.resources.Identifier targetId;
  private final boolean useDepthBuffer;
  private final boolean bilinear;
  public static final com.mojang.serialization.Codec<net.minecraft.client.renderer.PostChainConfig$TargetInput> CODEC;
  public net.minecraft.client.renderer.PostChainConfig$TargetInput(java.lang.String, net.minecraft.resources.Identifier, boolean, boolean);
  public java.util.Set<net.minecraft.resources.Identifier> referencedTargets();
  public java.lang.String samplerName();
  public net.minecraft.resources.Identifier targetId();
  public boolean useDepthBuffer();
  public boolean bilinear();
}
```

This is the JSON-facing record (`"use_depth_buffer"`), and the only field relevant to depth is the
`useDepthBuffer` boolean. `PostChain.addToFrame` copies that flag into the runtime
`PostPass$TargetInput`:

```
       476: invokevirtual #325                // Method net/minecraft/client/renderer/PostChainConfig$TargetInput.useDepthBuffer:()Z
```

### `net.minecraft.client.renderer.PostPass$TargetInput` and `PostPass$InputTexture`

`PostPass$TargetInput` — see Question 1; the depth flag lives here (`depthBuffer`), `texture(...)`
swaps to `getDepthTextureView()`.

```
final class net.minecraft.client.renderer.PostPass$InputTexture extends java.lang.Record {
  private final java.lang.String samplerName;
  private final com.mojang.blaze3d.textures.GpuTextureView view;
  private final com.mojang.blaze3d.textures.GpuSampler sampler;
  private net.minecraft.client.renderer.PostPass$InputTexture(java.lang.String, com.mojang.blaze3d.textures.GpuTextureView, com.mojang.blaze3d.textures.GpuSampler);
  public java.lang.String samplerName();
  public com.mojang.blaze3d.textures.GpuTextureView view();
  public com.mojang.blaze3d.textures.GpuSampler sampler();
}
```

`PostPass$InputTexture` is the resolved pair (view + sampler) that gets bound:
`view = PostPass$TargetInput.texture(...)` (i.e. the depth view when `depthBuffer == true`) and
`sampler = samplerCache.getClampToEdge(bilinear ? FilterMode.LINEAR : FilterMode.NEAREST)` (per
`PostPass.lambda$addToFrame$2`):

```
  private static net.minecraft.client.renderer.PostPass$InputTexture lambda$addToFrame$2(java.util.Map, com.mojang.blaze3d.systems.SamplerCache, net.minecraft.client.renderer.PostPass$Input);
```

So the depth **is** carried: config `useDepthBuffer` → runtime `depthBuffer` → resolved view
`RenderTarget.getDepthTextureView()` → bound as the sampler named `samplerName`. The
underlying `RenderTarget` exposes it directly:

```
public final boolean useDepth;
protected com.mojang.blaze3d.textures.GpuTexture depthTexture;
protected com.mojang.blaze3d.textures.GpuTextureView depthTextureView;
public com.mojang.blaze3d.textures.GpuTexture getDepthTexture();
public com.mojang.blaze3d.textures.GpuTextureView getDepthTextureView();
public com.mojang.blaze3d.textures.GpuTextureView getColorTextureView();
```

> Note: 26.2 uses **reversed depth** (see AGENTS.md); a linearization in the probe must account
> for `GREATER_THAN_OR_EQUAL` semantics (`depth = 1` is near).

---

## Question 3 — Can our existing pass layout accept a depth sampler/binding without changing the shared pass chain?

**Yes, on 26.2 — and it does not touch the shared chain.** Our code never uses the vanilla
`PostChain`/`PostChainConfig` path; it builds its own `RenderPipeline` from
`RenderPipelines.POST_PROCESSING_SNIPPET` and issues binds by hand in `VFXPostProcessingManager.VFXPass`.

The layout is per-pipeline, and a pipeline may add any bind-group layouts it wants:

```
public class com.mojang.blaze3d.pipeline.BindGroupLayout$Builder {
  public com.mojang.blaze3d.pipeline.BindGroupLayout$Builder withSampler(java.lang.String);
  public com.mojang.blaze3d.pipeline.BindGroupLayout$Builder withUniform(java.lang.String, com.mojang.blaze3d.shaders.UniformType);
  public com.mojang.blaze3d.pipeline.BindGroupLayout build();
}
public class com.mojang.blaze3d.pipeline.RenderPipeline$Builder {
  public com.mojang.blaze3d.pipeline.RenderPipeline$Builder withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout);
  public com.mojang.blaze3d.pipeline.RenderPipeline build();
}
```

Our own code already proves a custom sampler bind group is addable — `VFXShaderPrograms.java`:

```java
	private static final BindGroupLayout HIST_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("HistSampler")
		.build();
```

and the pipelines are built with per-pass layouts, e.g. `VFXShaderPrograms.java:190-193`:

```java
				//? if <26.2 {
				.withSampler("InSampler")
				.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
				.withUniform("Config", UniformType.UNIFORM_BUFFER)
				//?} else {
				/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
				.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
				*///?}
```

The actual bind happens in one place, `VFXPostProcessingManager.java:379-382`:

```java
				renderPass.bindTexture("InSampler", input.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				if (history != null) {
					renderPass.bindTexture("HistSampler", history.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				}
```

`RenderPass.bindTexture` accepts an arbitrary view:

```
public void bindTexture(java.lang.String, com.mojang.blaze3d.textures.GpuTextureView, com.mojang.blaze3d.textures.GpuSampler);
```

`VFXPostProcessingManager.process(...)` already receives the live main target
(`process(final VFXEffectManager effects, final RenderTarget mainTarget, final int layer)`), so the
depth view is reachable as `mainTarget.getDepthTextureView()`.

Caveat: our ping-pong/history targets are created **without** depth
(`createTarget("vfxweaver pingpong " + i, width, height, false)` — `VFXPostProcessingManager.java:257`,
and the same `false` for history and stop-motion hold). Depth must therefore be bound from the raw
`mainTarget` parameter, not from `input`/`output`. That is a one-line extra bind on the debug
pipeline, not a change to the shared chain (ping-pong ordering, UBOs, `PassRole` routing all stay).

`<26.2` path: `RenderPipeline.Builder.withSampler("DepthSampler")` (plus the existing
`withUniform` calls) and the same `renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), …)`.

---

## Verdict

**Depth is bindable.**

Two independent mechanisms exist on 26.2:

1. **Vanilla datapack path (`PostChain`/`PostChainConfig`)** — declare an input with
   `"target": "minecraft:main"` and `"use_depth_buffer": true` (sampler name arbitrary, vanilla uses
   `MainDepth`). `PostChainConfig$TargetInput(String samplerName, Identifier targetId, boolean useDepthBuffer, boolean bilinear)`
   → `PostPass$TargetInput` → `RenderTarget.getDepthTextureView()`. `minecraft:main` is
   `PostChain.MAIN_TARGET_ID = Identifier.withDefaultNamespace("main")`. This path is **not** what our
   code uses.
2. **Our own pass (`RenderPipeline` + `RenderPass`)** — add
   `BindGroupLayout.builder().withSampler("DepthSampler").build()` via
   `RenderPipeline.Builder.withBindGroupLayout(...)` (26.2) / `.withSampler("DepthSampler")` (<26.2),
   then `renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), sampler)`.
   Loader-independent (pure `com.mojang.blaze3d` / `net.minecraft.client.renderer`), so the same code
   runs on Fabric and NeoForge; the only loader-specific concern is whether the NeoForge built-in
   pack ships the vanilla `transparency` chain — irrelevant for path 2.

The main scene target carries a depth attachment; our ping-pong targets do not, so depth comes from
`mainTarget`.

## Exact names/signatures a follow-up implementation must use

- Identifier: `net.minecraft.client.renderer.PostChain.MAIN_TARGET_ID` (`Identifier.withDefaultNamespace("main")` = `minecraft:main`).
- Depth view: `com.mojang.blaze3d.pipeline.RenderTarget.getDepthTextureView() : com.mojang.blaze3d.textures.GpuTextureView`.
- Depth flag records:
  - `net.minecraft.client.renderer.PostPass$TargetInput(java.lang.String samplerName, net.minecraft.resources.Identifier targetId, boolean depthBuffer, boolean bilinear)`; accessors `depthBuffer()`.
  - `net.minecraft.client.renderer.PostChainConfig$TargetInput(java.lang.String, net.minecraft.resources.Identifier, boolean useDepthBuffer, boolean bilinear)`; accessor `useDepthBuffer()`; JSON key `use_depth_buffer`.
- Custom layout (what our `VFXShaderPrograms`/`VFXPostProcessingManager` actually needs):
  - `com.mojang.blaze3d.pipeline.BindGroupLayout$Builder.withSampler(java.lang.String) : BindGroupLayout$Builder`
  - `com.mojang.blaze3d.pipeline.RenderPipeline$Builder.withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout) : RenderPipeline$Builder`
  - `com.mojang.blaze3d.systems.RenderPass.bindTexture(java.lang.String, com.mojang.blaze3d.textures.GpuTextureView, com.mojang.blaze3d.textures.GpuSampler)`
- Sampler for a depth texture: bind with `RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)` (depth is non-filterable; `LINEAR` would be wrong).
- Shader side: declare the depth sampler under the same name passed to `bindTexture` (e.g. `DepthSampler`) and keep the `SamplerInfo` UBO (`vec2 OutSize, vec2 InSize`) unchanged.

## Contradictions with the plan's assumptions

- The plan's Task 1 Step 2 expects `MAIN_TARGET_ID`/`TargetBundle` to answer whether depth is carried.
  In fact `TargetBundle` carries no depth; the depth flag lives on `PostChainConfig$TargetInput` /
  `PostPass$TargetInput` and resolves through `RenderTarget.getDepthTextureView()`.
- The plan's Task 2 does not specify how the debug pass obtains depth. The pass registers via
  `registerPost(VFXEffectType.DEBUG_DEPTH, "near_far")`, which builds a pipeline with only
  `InSampler` + `SamplerInfo` (+`Config`). To read depth the debug pipeline needs its **own** extra
  bind group (`DepthSampler`) and `VFXPass.execute` needs to bind `mainTarget.getDepthTextureView()`
  — i.e. the debug pass cannot reuse the plain `registerPost` layout as-is; it must be registered
  with a depth-enabled layout (or `registerPost` extended for it). This is exactly what Task 3/§10
  must account for.
- No contradiction found on the identifier name or on the loader-independence.

---

## Verified in-game results (Task 4) — depth reads, but the pass MUST run at screen layer 0

All five points below were confirmed in-game by the human partner on Fabric 26.2, NeoForge 26.2
and with Iris shaders enabled.

1. **Depth is bindable.** The pass binds the main target's depth view directly:
   `renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST))`
   (`VFXPostProcessingManager.java:391`) under the extra bind group
   `DEPTH_SAMPLER_LAYOUT = BindGroupLayout.builder().withSampler("DepthSampler").build()`
   (`VFXShaderPrograms.java:66-68`). The sampler must be `NEAREST` — a depth texture is not
   filterable.
2. **Critical: the pass MUST run at screen layer 0.** At layer 1 the game has already cleared the
   single scene depth buffer — `GameRenderer.renderLevel` calls
   `clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0)` immediately before
   `renderItemInHand` — so depth there contains only the first-person hand. Observed at layer 1:
   the world was absent (black) and only the hand had depth. Our built-in probe effect therefore
   runs with `"screen_layer": 0`.
3. **Depth convention: reversed depth, `GL_ZERO_TO_ONE`.** 26.x calls
   `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` (see `GlDevice`), so the sampled buffer value is
   already NDC z (**near = 1.0, far = 0.0**) and is fed to the inverse matrix as-is — no
   `2*depth-1` remap.
4. **World position reconstruction works.** `Camera.getViewRotationProjectionMatrix(Matrix4f)`
   returns `projection * viewRotation` with **no translation**; post-multiply by
   `translate(-cameraPos)` and invert. The verified shader recipe is:
   ```glsl
   float d = texture(DepthSampler, texCoord).r;
   vec4 clip = vec4(texCoord * 2.0 - 1.0, d, 1.0);
   vec4 world = inv_view_proj * clip;
   world /= world.w;
   ```
   Camera accessor is `gameRenderer.mainCamera()` on `>=26.2`
   (`minecraft.gameRenderer.getMainCamera()` on `<26.2`).
5. **Cross-loader / shaderpack verdict:** it works on **Fabric 26.2, NeoForge 26.2, and with Iris
   shaders enabled** — no access transformer or loader-specific binding needed.

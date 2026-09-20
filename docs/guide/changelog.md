# Guide changelog


Versioned feature history — **[docs/CHANGELOG.md](../CHANGELOG.md)**.

Guide version: 46 — see changelog below.

### v46
- **Textured `surface_pattern` now resolves on 1.21.11 (and on 26.1.2/26.2 without the first-frame abort).** The texture resolver was written inside a `>=26.1` guard with a neutral fallback for the `1.21.11` node, so once the depth pass started running there the texture never resolved, `tex_flags` never reached the `RESOLVED` bit and every textured projection drew nothing (the built-in procedural figure still worked). The resolver now has a real per-node implementation — 26.2/26.1.2 through the `sprite` AtlasManager (`SpriteId` keyed by `TextureAtlas.location()`), 1.21.11 through the remapped model AtlasManager and `TextureAtlas.getSprite` — funnelling every source form (`block`/`item`/`atlas`/`standalone`) through one `resolved(...)` factory that sets the flag bit. A standalone texture is shared by both nodes. See [2.1](surface-pattern.md#surface_pattern).
- **The `surface_pattern` std140 layout guard no longer aborts the post layer.** The registration-time guard queried `glGetUniformIndices` with block-qualified names (`Config.tile_scale`); a driver that reports only the bare member name returned `-1`, which was then passed to `glGetActiveUniformsiv` (`GL_INVALID_VALUE`) and threw out of `execute`, so the first frame of a textured pattern failed with "Failed to apply VFX post-processing". It queries the bare member name first with a qualified fallback, skips members the driver does not list, and logs at ERROR without throwing, so a false positive cannot drop a frame.

### v45
- **Scene depth works on every supported line, not just 26.2.** `surface_pattern`, the depth/world field functions and the depth-needing mask families (a `world` leaf, an `aura` volume, a block leaf's `occlude`) used to be gated to 26.2 because the world reconstruction assumed 26.2's reversed depth. The per-node depth convention is now proven from the client jars and injected into one shared shader source: **26.2 is reversed** (`glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` — near = 1, far = 0) and **26.1.2 / 1.21.11 are standard** (near = 0, far = 1). The shader converts the raw sampled depth, picks the correct sky test and the correct block-occlusion comparison per node, so a depth-needing effect now renders on 26.1.2 and 1.21.11 as well. Fail-closed is unchanged where no trustworthy depth exists. See [2.1](surface-pattern.md#surface_pattern) and [3.8](masks.md#38-masks).

### v44
- **`surface.stitch` — opt-in planar (top-down) projection.** A new boolean in the structural `surface` block (default `false`). With `"stitch": true` every face samples the same top-down coordinate `p = world.xz`, so a wall pixel `(x, y, z)` shows exactly what the floor pixel at the wall base `(x, floor_y, z)` shows — the image's row at the wall line extruded vertically (a ring reaching a wall becomes two vertical stripes from its crossing points). The seam is continuous for **any anchor height** (the anchor's Y no longer matters), and `min`/`max` become a height slab that bounds how far up the wall the image stretches. A ceiling is excluded (a top-down projection cannot light a down-facing surface). Deliberate trade-off: a wall shows a 1D slice (vertically constant colour columns), not a 2D unwrapped image. Additive and off by default — without it the hard floor/wall plane switch is unchanged, so every existing definition and the built-in render exactly as before. See [2.1](surface-pattern.md#surface_pattern).
- **`normal_mask: 1.0` coverage is no longer noisy.** The `1.0` fallback compared the *raw* depth-derived normal against exactly `1.0`, which a normalized normal numerically almost never reaches (`0.9999…`), so coverage flickered at the threshold (worst at grazing angles near the floor). It now tests the **snapped** normal (`abs(n.y) >= 0.5`), which is exact for an axis-aligned block face.

### v43
- **`/vfx stop [<player>]` stops every active effect of a player.** With no argument it stops all of the executing player's effects; with a player (selector) it stops that player's effects. It reuses the existing per-effect stop payload on the server and, for the executor's own client, also clears effects played locally (single player / a client-only mod). The old `/vfx stop <effect> [players]` form is unchanged: a bare token is parsed as an `<effect>` first, so use a selector (`@p`/`@a`) to target a player. New `VFXAPI.sendStopAll(ServerPlayer)`.
- **Mask depth gate is consistent across nodes.** `depthRecipeVerified()` was left in the 26.2 Stonecutter form on disk, so the active `26.1.2` Fabric node compiled `return true` while every other node (including `26.1.2-neoforge` and `1.21.11`) compiled `false`. The active-node form is now the `false` branch, so a depth-needing mask fails closed (zero coverage) on every non-26.2 node, as documented, instead of silently using the unverified reversed-depth recipe on Fabric.
- **A screen-only mask renders on nodes without the verified depth recipe.** The coverage prepass bound the coverage target's own colour texture as the `DepthSampler` placeholder when depth was untrusted (a feedback loop, undefined on some drivers); it now binds the main target's depth view (else its colour view), so a mask that needs no depth renders identically on `26.2`, `26.1.2` and `1.21.11`.

### v42
- **`surface_pattern` now requires 26.2 (it no longer silently passes through on 26.1.2).** The pass is registered on `>=26.2` only, matching the mask coverage gate: the reversed-depth reconstruction it reads was verified on 26.2, while 26.1.2/1.21.11 use the other depth convention. On a non-26.2 node the effect type and JSON still parse, but no pass is registered and the effect **draws nothing** (previously 26.1.2 rendered a passthrough with a one-time warning). See [2.1](surface-pattern.md#surface_pattern).
- **The effect clock and camera/player snapshots are refreshed before layer 0.** They used to advance in the `FogRenderer.endFrame` hook, which runs after `renderLevel`, so a `surface_pattern` (default `screen_layer: 0`) trailed the player by a frame and time-driven animation was a frame late. Layer 0 now advances the clock and republishes the snapshots; layers 1/2 reuse them, and nothing advances twice. See [2.1](surface-pattern.md#surface_pattern).
- **`normal_mask: 1.0` is no longer undefined.** The degenerate `smoothstep(normal_mask, min(normal_mask + 0.2, 1.0), …)` edge collapse (undefined in GLSL) is guarded by clamping `normal_mask` to `0..1` and falling back to the exact hard test at `1.0`.
- **A `band_softness` wider than the band is clamped** to half the band width, so a narrow band (`min:10, max:10.5`) is solid at its centre instead of evaluating to ~0.28.
- **`distort` warps a flat surface instead of translating it.** Its phase was `dot(world, normal)`, constant on any flat axis-aligned surface; it now follows the in-plane coordinate. Definitions with a non-zero `distort` on a floor see a different (intended) result.
- **Parse fixes that stop a definition from silently doing nothing.** An empty `faces: []` is now a parse error; `pattern.center_x`/`center_y`/`center_z` are rejected (use `pattern.center`); an entity-anchored `positions` entry on a `surface_pattern` is rejected; a `pattern.texture.id`/`atlas` with invalid id syntax is a per-file parse error.
- **Per-frame work in the post chain is reduced** — one definition lookup per `surface_pattern` pass instead of three, and the texture id is parsed once at definition-parse time instead of every frame.

### v41
- **Mask correctness and performance batch.** Animated `softness`, composition nesting (left-only, a right-nested `op` is now a parse error), the custom-leaf (2) and block-leaf (1) caps, an animatable block centre, a composed custom leaf's `softness`, the depth gate (a depth-needing mask fails closed where `surface_pattern` does), cached block selection, cleared new coverage targets, live-source shader variants and one-extra-frame uniform-arena retirement. Two plays of one masked definition still share a coverage target (documented). See [3.8](masks.md#38-masks).

### v40
- **Textured `surface_pattern` figures** — the structural `pattern` block accepts an optional `texture` object: a real texture (a block-atlas sprite, an item-atlas sprite, any atlas sprite, or a standalone resource-pack texture) projected onto the same surface the figure uses. `id` is required; `source` (`block`/`item`/`atlas`/`standalone`) is inferred from the id when omitted; `channel` (`alpha` default, `luminance`/`r`/`g`/`b`), `sheet` (`[cols, rows]`, `1..16`, `cols*rows <= 256`) and `aspect` (`preserve`/`stretch`) are optional. The texture is the figure and an authored `figure` becomes its mask (a texture with no figure is not clipped). New animatable params `rotation` (overrides the structural rotation), `frame` (sprite-sheet cell) and `texture_tint` (`0..1` recolour). Atlas sprites use their stitched sub-rect and animate with the atlas; standalone textures sample `0..1`. Additive: without a `texture` the block renders exactly as before. See [2.1](surface-pattern.md#surface_pattern).
- **Texture reload fix** — a texture view cached across a resource reload used to dangle (the loaders close and recreate it); the cache now stores the descriptor and re-derives the view, for both the field `texture` function and the new pattern texture.
- **`surface_pattern` anchor is world-anchored, never the camera** — with no explicit `center` the anchor fell back to the camera, so third-person (F5) or any camera-only motion slid the projection along the ground. The anchor is now the effect instance's world position: a Java-API move, else its first declared `position` (`/vfx playat`), else the `pos_x/pos_y/pos_z` binds, else the local player for a player-anchored play. An explicit `center` still wins; camera-only movement no longer moves the pattern. See [2.1](surface-pattern.md#surface_pattern).

### v39
- **`surface_pattern` surface selection** — an optional top-level structural `surface` block selects which face orientations receive the pattern (`faces`: `up`/`down`/`north`/`south`/`east`/`west`, axis aliases `x`/`y`/`z`, groups `horizontal`/`vertical`/`all`) and an optional inclusive band (`min`/`max`) applied **along the fragment's dominant axis** (Y for up/down, X for east/west, Z for north/south). Without the block the legacy numeric `normal_mask` behaviour is unchanged, so the built-in and every existing definition keep rendering exactly as before. The projection now follows the fragment's dominant world normal, so vertical walls get an upright, un-mirrored figure instead of a sheared XZ one. See [2.1](surface-pattern.md#surface_pattern).
- **`surface_pattern` band edge is no longer hard** — a surface lying exactly on a band `min`/`max` bound flickered, because the depth-reconstructed axis coordinate jitters across the inclusive test from pixel to pixel. The optional `band_softness` (blocks, `0..4`, default `0`) fades the band edge over a small world-space distance, keeping the interior fully on and the outside fully off; `0` is the exact hard edge, so existing definitions are unchanged. See [2.1](surface-pattern.md#surface_pattern).

### v38
- **New `surface_pattern` effect** — a world-anchored shape pattern (`circle`/`ellipse`/`rect`/`polygon`, tiled by a structural `repeat` modifier) projected onto the terrain behind each pixel, so it stays fixed to world blocks. Additive: it renders on 26.1.2+ (it reads scene depth), needs `"screen_layer": 0`, and no existing definition changes behaviour. The figure strings live in a top-level `pattern` block, never in `params`. See [2.1](surface-pattern.md#surface_pattern).

### v37
- **Mask bindings now fail closed per leaf** (was per mask) — an unresolved binding (the source entity is absent or off-screen, or outside the client's tracking range, or there is no camera/player state) zeroes **only that leaf's** coverage, so an entity leaving the view no longer makes the whole effect vanish while a still-resolved world leaf knows where it is. An unresolved leaf still never expands coverage, and an `invert` mask does not turn an all-unresolved (empty) result into full screen. See [3.8](masks.md#38-masks).
- **New `VFXAPI.sendMaskMove` / `VFXAPI.maskMove`** — move one mask leaf to a world position by setting its three reserved `mask.p<N>.center_x|center_y|center_z` params (ordinary animatable effects params). Call it every tick to follow a point, and use it to drive a mask from the **server** when a client-side entity binding is not enough (an entity outside the client's tracking range). See [docs/API.md](../API.md) and [3.8](masks.md#38-masks).
- **Mask coverage at later layers is documented** — the coverage prepass runs at screen layer 0 against the intact scene depth and yields a screen-space coverage, so an effect consuming the mask at a later layer (e.g. `screen_layer: 1`) tints the first-person hand wherever a masked block lies behind it. Run the masked effect at `screen_layer: 0` or depth-occlude the consumer to avoid it. See [3.8](masks.md#38-masks).

### v36
- **Mask bindings now fail closed** — a mask that uses a world binding which cannot be resolved (the source entity is absent or off-screen, or there is no camera/player state) contributes **zero** coverage, so the effect applies nowhere, instead of falling back to the leaf's literal default — which for a bound screen `rect` is the whole screen (the reported "whole screen tint when the villager is not resolvable"). The unresolved source still reports once through `VFXLog.warnOnce`. See [3.8](masks.md#38-masks).
- **`aura` masks fill the volume at full strength** — the aura coverage is now sampled at the volume depth nearest the viewer along the view ray (the sphere's closest approach) instead of at the entry point on the boundary, so the interior reaches full coverage and the silhouette edge fades from both sides. See [3.8](masks.md#38-masks).
- **Mask demos reworked** — `vfxweaver:mask_entity_demo` and its `surface` A/B partner `vfxweaver:mask_world_demo` now use a fixed `radius` of 4 blocks, so the aura bubble stays the same size as the viewer moves. The entity-following sphere whose radius grew with the viewer's distance moved to the new `vfxweaver:mask_pulse_demo`, which also documents the `"derive": "distance"` binding form (54 built-ins).

### v35
- **World-volume masks gained an `aura` evaluation mode** — a `sphere`/`box` mask leaf now takes `"volume": "surface" | "aura"` (see [3.8](masks.md#38-masks)). `"surface"` is the default and keeps the original look (the visible surface is classified, so only geometry inside the volume is tinted); `"aura"` casts the pixel's view ray at the volume and fills the whole volume, including air and sky, wherever the scene does not occlude it, with the edge still fading over `softness`. Sky and missing depth count as "nothing occludes". The demo `vfxweaver:mask_entity_demo` (sphere + screen rect) now uses `aura`; `vfxweaver:mask_world_demo` is the same entity-following sphere in the default `surface` mode for an A/B comparison (53 built-ins).

### v34
- **Per-pixel fields** — a field-capable input (currently `dent.intensity` and `color_grade.tint_r`) can carry a `{ "field": ... }` object that is evaluated per pixel inside the shader instead of once per frame (see [3.7](fields.md#37-per-pixel-fields)): the built-in functions `constant`, `noise`, `shape`, `gradient`, `curve`, `texture`, `depth`, `depth_gradient`, `normal_facing`, `screen_uv` and `world_pos`, the shared shape set (`circle`/`ellipse`/`rect`/`polygon` with `fill: solid|stroke`, `softness` and a `repeat` tiling modifier, plus the 3D `sphere`/`box` helpers), and bounded compositions (`multiply`/`add`/`subtract`/`mix`/`min`/`max`). The block is additive — a definition without fields is bit-for-bit unchanged, and a mod that does not know fields ignores them. Depth/world fields need screen layer 0 and otherwise fall back to the neutral value. The built-ins `vfxweaver:dent_field_demo` and `vfxweaver:tint_field_demo` are the reference examples (48 built-ins).

### v33
- **Value graphs** — an effect can drive any numeric input from an optional `graph` + `inputs` block (see [3.6](graph.md#36-value-graphs)): `constant`, `time`, `random`, `noise`, `curve`, `math`, `mix`, `clamp`, `remap`, `bind` and `expr` nodes plus the logic nodes `compare`, `boolean`, `if` and `switch`, and reusable `subgraphs` (macros with `$` parameters, local prefixed ids and named outputs), all evaluated once per frame. All blocks are additive — a definition without them behaves exactly as before, and a mod that does not know graphs ignores them, because graph wiring never lives inside `params`. A broken graph fails that file only, and the built-in `vfxweaver:graph_demo` is the reference example (45 built-ins).

### v32
- **Block-model particles** — the `particles` effect can emit real block models instead of vanilla particles: `"particle": "block"` with a `block` state, or a reusable preset in `data/<namespace>/vfx_particles/<name>.json` (registerable from code with `VFXAPI.registerBlockParticle`). Each particle has block-display brightness (`-1` = world light, `[blockLight, skyLight]`), gravity, air friction, optional world collision with surface friction and bounce, size, lifetime and spin; `VFXAPI.spawnBlockParticle` spawns one immediately on the client.
- **Item-model particles** — the same engine can draw real item models: `"particle": "item"` with an `item` id (e.g. `"minecraft:skeleton_skull"`), or a `vfx_particles` preset that declares `"item"` instead of `"block"` (exactly one is required). This is how a skull/head particle works — the block form has no baked block model, the item form does; items render as `ItemDisplay` entities with the same brightness/physics params.
- **Block/item particles render as client-side display entities** — each live particle drives a `BlockDisplay`/`ItemDisplay`, so vanilla interpolates its motion (no more jerky stepping) and brightness uses the display's brightness override exactly like a real block display. The physics, presets, datapack/API surface and params are unchanged.
- **Block/item particle rotation is fully configurable** — presets and the Java spec builder gained `spin_mode` (`tumble` = the physical full-3D spin, `yaw` = a uniform spin about one axis, `none` = no rotation), `spin_axis` (`random`/`x`/`y`/`z`/`[x, y, z]`), `spin_random`, `spin_friction` and `spin_roll`; `spin` is the magnitude of a full 3D angular velocity about a random axis, integrated per tick and damped by the contacted block's friction so a cube lands and settles. The `spin`/`spin_random`/`spin_friction`/`spin_roll` effect params override a named preset; the defaults (`tumble`/`random`/`1.0`/`1.0`/`0.5`) reproduce the previous tumbling behaviour exactly.
- **`vfxweaver:block_chain` built-in demo** — the `block_chain` effect now ships a built-in definition (44 built-ins), so it appears in `/vfx` tab-completion and `/vfx play vfxweaver:block_chain` works without writing a datapack.
- **NeoForge support for all three Minecraft lines** — a NeoForge node is built beside each Fabric node from one source tree (`26.2-neoforge`, `26.1.2-neoforge`, `1.21.11-neoforge`), so the project produces six jars, one per (Minecraft line, loader). **Flashback is Fabric-only** (Flashback has no NeoForge build). All formats — effect datapacks, `vfx_particles` presets and the effect definition fields — are additive, so existing datapacks keep working unchanged.

### v31
- Replay recording works again with current Flashback versions: the compatibility layer registered two custom actions, which Flashback rejects (it keys actions by class, and both reflection proxies share one class) - that aborted the whole init, so nothing was recorded. Plays, stops, live edits and the definitions snapshot now share one action.
- `speed_lines`: new `pos_rand` param (default 1.0) - the per-line angular position is jittered inside its own slice, so the spacing is uneven instead of one line per equal sector (`0` restores the even spacing). `seed` drives the position as well as the length, so animating it churns the layout.
- `pulse_ring`: `thickness: 0` now really means zero - the ring is not drawn at all (previously it was clamped to a 0.05 minimum and showed as a hairline).

### v30
- New Java API for client-only mods: `VFXAPI.registerDefinitions(Map)` / `VFXAPI.unregisterDefinition(id)` register effect definitions from code. They live in a local layer that survives `/reload` and a server sync (a datapack shipped by a client-side mod only loads in single player, and a server sync used to replace the whole definition set), stay private to this client, and use the same validation as datapack files.

### v29
- Live keyframes accept a **negative time** ("from here"): the value the parameter has right now is pinned at the current time and the segment runs to the new value over `|time|` ticks, so animation variations chain without the caller knowing the current value - `sendKeyframe(player, effect, "radius", -20, 0.0F, easing)` fades a held blur back out from wherever it stands.
- The live edits are now available **client-side without a packet**: `VFXAPI.setParam`, `VFXAPI.setParamExpr` and `VFXAPI.setKeyframe` mirror the network actions (`sendSetParam`, `sendSetParamExpr`, `sendKeyframe`) locally, next to the already-local `playEffect`/`playEffectId`/`moveEffect`/`stopEffect` - a pure client-side mod can drive effects end to end.
- Live edits are **recorded into Flashback replays**: `setParam`, `setParamExpr` and `setKeyframe` (local and server-driven) replay along with the original play, so an effect animated while recording keeps its animation on playback.

### v28
- Math expressions gained more functions: `floor`, `ceil`, `round`, `fract`, `sign`, `clamp(x, lo, hi)`, `lerp`/`mix(a, b, t)`, `step(edge, x)`, `smoothstep(e0, e1, x)`, `mod(a, b)`, `tan`, `atan(y)` / `atan(y, x)` (= atan2), `exp`, `log` (natural). Argument counts are now validated when the expression is compiled (a wrong count used to fail while evaluating).
- The client-local Java API can anchor effects: `VFXAPI.playEffect`/`playEffectId` take an optional world position and entity-UUID list, and `VFXAPI.moveEffect(effectId, instanceId, pos)` re-anchors a running instance - no packet involved. The position re-anchors spatial bindings (`screen_x`, `screen_y`, `proximity`, ...) like `/vfx playat`, so point effects (`dent`, `shockwave`, `vortex`) can be placed at an event and follow it.

### v27
- Build restructured for multiple Minecraft versions (Stonecutter). Adds a `1.21.11`
  build alongside `26.1.2`; effect behavior, datapack format and network protocol are unchanged.

### v26
- World overlays: entity anchors gained `point` — the reference point on the entity: `feet` (default), `center` (bounding-box centre) or `eyes` (e.g. `{"entity": "@s", "point": "center"}`), so effects can attach to the middle/head of an entity instead of its feet.
- World overlays: entity anchors gained `dir: "look"` + `distance` — the anchor is pushed along the tracked entity's live look direction (eyes + look × 24 = a laser target where the entity is looking).
- New world-overlay effect `block_chain`: a line of real textured block-model links between two anchors (datapack picks the block, `spacing`/`arc`/`scale`/`align` params), rendered through the vanilla submit pipeline (shaderpack-safe). Optional `physics: 1` — a verlet rope with gravity sag, world collision, player push, `sway` wind and animatable `length` (single-anchor hang or two-anchor slack; links stretch when pulled taut).
- New world-overlay effect `particles`: emits vanilla particles in animated shapes (`sphere`/`ring`/`helix`/`line`/`cube`/`point`) with any RGB via `dust`. No custom textures — everything from the datapack; entity anchors and fades work as usual. Builtin demo: `vfxweaver:particles` (golden dust helix).

### v25
- New command `/vfx validate [namespace]` — dry-run definition health report (loaded count + parse errors per file), operator-only.
- New world binding `scoreboard`: a param can follow a scoreboard value (`objective` + optional `holder`, normalized on `range`, works as a value or a `multiply` multiplier).
- New Java API: `sendSetParamExpr` (swap a running effect's param for a live math expression), `sendMove` (move a running world-overlay instance to an exact point — call per tick for scripted motion), and a serverbound `vfxweaver:vfx_request` packet letting client mods play effects through the server (broadcast is operator-gated).
- Built-in effects now ship as JSON resources (`data/vfxweaver/vfx/*.json` inside the mod jar) instead of code — they load, sync and can be overridden like any datapack definitions; copy a file out of the jar to tweak it.
- World-overlay geometry uses exact `Vec3` coordinates: entity anchors and API moves are no longer snapped to block centres (static `positions` entries keep the historical block-centre behaviour). Protocol version 6.

### v24
- World overlays: `positions` entries may anchor to a live entity — `{"entity": "<selector>", "offset": [x,y,z]}`. The server resolves the selector once per play (plain `/vfx play`; fails if it matches nothing), the client follows the entity every frame; the Java API passes anchors as target UUIDs in anchor order.
- Collections: child `params` values are now full parameter specs — `start`/`end`, `keyframes`, `bind`, `expr`, `multiply` (plain numbers still work as constants).

### v23
- `light_beam`: new `bottom_fade` param (fades the column toward the bottom, mirrors `top_fade`).
- Fixed `light_beam` rendering under shaderpacks: packs that declare vertex colour `flat` (Complementary) take each triangle's colour from one vertex, so the height fade showed as visible triangles. Faded shells are now split into 32 narrow slices, each quad with one uniform colour - clean stepped fade under shaders, imperceptible difference in vanilla.

### v22
- New screen effects: `slice_shift`, `noise_warp`, `solarize`, `double_vision`, `eyelids`, `iris_wipe`, `digital_glitch`, `vhs`, `shockwave`, `afterimage`, `stop_motion`.
- New world effects: `light_beam` (layered shells, cubic softness, `top_scale`), `pulse_ring` (camera-facing billboard + `rot`), `guide_line`.
- New entity effect: `entity_displace` (flat per-vertex displaced echo over the intact model).
- New misc effects: `camera_roll`.
- `afterimage` uses an island blend; `camera_shake`/`camera_roll` also move the first-person hand.
- Removed before release: `block_displace`, `god_rays`, `scan_sweep`, `hud_fade` (cut before release, will be redesigned later).

### v20
- Parameter overrides whose name the effect definition does not declare (e.g. `through_blocks` on the built-in effects) now apply as constant values instead of being silently dropped; `through_blocks` is declared on the built-in entity/block tint/outline.
- Entity tint/outline are rendered on the first-person hand as well (the arm bypasses the normal entity submit path).
- Outlines always render under their target now. Entity outline with `through_blocks:1` draws an opaque shell before the body (rim around the silhouette, visible through walls); block outlines ignore `through_blocks` and never cover the block itself.
- `camera_shake` gained a `frequency` parameter (oscillations per second, default 7).
- All screen effects accept `screen_layer`: `0` = under the first-person hand and GUI, `1` = above the hand below the GUI (default), `2` = above everything including the GUI.

### v18
- Effects survive a reconnect correctly: the server remembers runtime keyframes (Java API `sendKeyframe`) and resumes playback from the position it was left at (protocol version 5).
- Non-looping effects whose runtime edits animate to zero remove themselves instead of lingering invisibly.
- `/vfx set` on a non-running effect starts a normal instance with the definition's own `duration` instead of an immortal one.
- `/vfx playentity` accepts an optional `[players]` list — who sees the effect (default: the executing player).
- Removed the `/vfx key` command (nobody used it; `VFXAPI.sendKeyframe` and the network `KEYFRAME` action remain for mods).

### v17
- Flashback compatibility: client-local effects are recorded into Flashback replays and re-triggered during playback (soft dependency, reflection-based, no compile-time coupling).

### v16
- New effect types for entities: `entity_tint` (a solid translucent fill of the effect colour) and `entity_outline` (an "inverted hull" silhouette outline, thickness `width`). Targets are set by UUID.
- New command `/vfx playentity <effect> <targets>` — plays an effect on entities picked by a selector (up to 16 UUIDs).
- Both types support `through_blocks`: 0 — the effect hides behind walls, 1 — visible through them.

### v15
- New way to set a param — the math expression `"expr"` (variables `t`/`x`/`y`/`z`/`pi`/`e`, functions `sin`/`cos`/`abs`/`min`/`max`/`pow`/`sqrt`/`random`/`noise`). Compiled once, evaluated every frame; `random()`/`noise()` are unique per instance.
- Camera shake (`camera_shake`) is now unique per call (per-instance seed).

### v14
- The mutating `/vfx` commands (`play`, `playat`, `stop`, `set`, `key`) now require operator rights; `/vfx list` is open to all.
- Tighter client protection from a hostile/broken server: caps on network packet sizes (effect params, definition/curve sync), `vfx_sync` packet version check, cap on effect duration from the server, instance-id validation on stop.

### v13
- Datapack VFX effects and curves sync with dedicated-server clients (the `vfxweaver:vfx_sync` packet on join and after `/reload`) — custom effects now play for players on a dedicated server, like in single-player.

### v12
- `sendEffect` accepts a direct world position — no `pos_x/y/z` hack (the client immediately re-anchors spatial bindings to the point).
- Custom easing curves: files `data/<ns>/vfx_curves/<name>.json` or an inline object `{ "curve": [[t,v],...] }` in any `easing` field.
- Param multiplier: `"param": { keyframes/start-end/constant/binding + "multiply": { "bind": "proximity", ... } }` — final value = base × multiplier (e.g. an animated dent fading with distance from a point).
- `VFXAPI.playEffectId(...)` returns the instance id, `VFXAPI.stopEffect(long)` stops one specific instance; `sendStop(player, effectId, instanceId)` — over the network.
- Network protocol version 4: the packet carries an optional position and instance id.

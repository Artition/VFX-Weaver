# Java API

The public API for other mods interacting with vfxweaver. Backward compatibility matters — don't break signatures without good reason (see `AGENTS.md`).

## `dev.vfxweaver.api.VFXAPI`

A stateless class (all methods static), the entry point for other mods.

### Server → client (over the network)

```java
// Resolves the datapack/built-in effectId, merges its default constant params with overrides,
// takes the duration/easing from the definition (if not given explicitly), and sends a
// VFXTriggerPayload to the player. Returns false if effectId is unknown.
boolean sendEffect(ServerPlayer player, Identifier effectId, Map<String, Float> overrides, @Nullable EasingType easing);

// Same, but with an explicit world position: the client immediately re-anchors spatial
// bindings (screen_x/screen_y/proximity) to that point and uses it for the effect's positions —
// no pos_x/pos_y/pos_z hack.
boolean sendEffect(ServerPlayer player, Identifier effectId, Vec3 worldPos, Map<String, Float> overrides, @Nullable EasingType easing);

// Same with an explicit instance id (0 = the client assigns one). Lets you later stop exactly
// this instance via sendStop(player, effectId, instanceId) instead of every instance of the effect.
// The id is a unique handle: playing it again restarts that instance in place instead of adding a
// duplicate, so sendStop/sendMove always address the instance you played.
boolean sendEffect(ServerPlayer player, Identifier effectId, long instanceId, @Nullable Vec3 worldPos, Map<String, Float> overrides, @Nullable EasingType easing);

// Explicit variant without consulting the definition registry — all packet fields are set manually.
void sendEffect(ServerPlayer player, Identifier effectId, int durationTicks, Map<String, Float> params, EasingType easing);

// Full variant: duration, instance id, world position, entity UUID targets for entity effects
// (entity_tint/entity_outline) or entity-anchored world overlays, parameter overrides and
// easing. entityUuids — up to 16 UUIDs; an empty list for all other effect types. For world
// overlays whose definition declares entity-anchored "positions", the UUIDs fill the anchor
// slots in anchor order (the client tracks those entities per frame).
void sendEffect(ServerPlayer player, Identifier effectId, int durationTicks, long instanceId, @Nullable Vec3 worldPos, List<UUID> entityUuids, Map<String, Float> overrides, @Nullable EasingType easing);

// Stops the effect on the player's client (all its instances).
void sendStop(ServerPlayer player, Identifier effectId);

// Stops one specific instance of an effect (see sendEffect with instanceId).
void sendStop(ServerPlayer player, Identifier effectId, long instanceId);

// Stops every effect the server has recorded for the player, reusing the per-effect stop payload
// (no new wire action). Effects the player's client played locally (never seen by the server) are
// not covered - use the client-local stopAllEffects() for those.
void sendStopAll(ServerPlayer player);

// Live-overrides a parameter of a running effect (without restarting the timeline).
// Ignored by the client with a warning in the log if the effect is not currently running.
void sendSetParam(ServerPlayer player, Identifier effectId, String param, float value);

// Like sendSetParam, but replaces the parameter with a compiled math expression (same syntax
// as the JSON "expr" field; compiled per instance with its seed). Applies to every running
// instance of the effect on the player's client.
void sendSetParamExpr(ServerPlayer player, Identifier effectId, String param, String exprSource);

// Moves a running world-overlay effect instance to a new exact world point (sub-block
// precision; the instance keeps its timeline and fades). Call every tick for scripted motion.
// Ignored with a warning when no instance with that id (of that effect) is running.
void sendMove(ServerPlayer player, Identifier effectId, long instanceId, Vec3 worldPos);

// Moves one mask leaf of a running effect to a world position, by setting the three reserved
// mask.p<N>.center_x/y/z params (see "Mask leaf params" below). Applies to every running
// instance of the effect; ignored with a client-side warning when it is not running. This is
// also the server-driven workaround when a client cannot resolve an entity binding (the entity
// is outside the client's tracking range).
void sendMaskMove(ServerPlayer player, Identifier effectId, int primitive, Vec3 position);

// Adds/replaces a keyframe of a parameter of a running effect.
// A negative timeTicks means "from here": the value the parameter has right now is pinned at the
// current time and the animation runs to `value` over |timeTicks| ticks - so animation segments
// chain without knowing the current value (ramp a blur up, later send -20/0 to fade it back out).
void sendKeyframe(ServerPlayer player, Identifier effectId, String param, int timeTicks, float value, EasingType easing);
```

### Client (locally, without the network)

```java
// durationTicks: 0 = definition default, negative = persistent.
// Returns false if called off-client (e.g. on a dedicated server) —
// in that case use sendEffect().
boolean playEffect(Identifier effectId, int durationTicks, Map<String, Float> params, @Nullable EasingType easing);
boolean playEffect(Identifier effectId, int durationTicks, Map<String, Float> params); // linear easing

// Like playEffect, but returns the id of the created instance (0 on error) — so you can stop
// one specific instance out of several concurrent ones via stopEffect(instanceId).
long playEffectId(Identifier effectId, int durationTicks, Map<String, Float> params, @Nullable EasingType easing);

boolean stopEffect(Identifier effectId);          // every instance of the effect
boolean stopEffect(long instanceId);              // one specific instance
boolean stopAllEffects();
```

#### Anchored local playback

A client-local play can be anchored to a world position and/or to entities, so an effect lands
where the event happened instead of at the definition's default spot (e.g. `dent`, `shockwave`,
`vortex` centred on a hit). This is the local equivalent of `/vfx playat` / a network
`sendEffect(player, id, pos, ...)` — no packet is involved.

```java
// Anchor to a world point: replaces the definition's position slots and re-anchors the spatial
// world bindings (screen_x, screen_y, proximity, ...) to that point.
boolean playEffect(Identifier effectId, int durationTicks, Vec3 position, Map<String, Float> params, @Nullable EasingType easing);
boolean playEffect(Identifier effectId, int durationTicks, Vec3 position, Map<String, Float> params); // linear easing
long playEffectId(Identifier effectId, int durationTicks, Vec3 position, Map<String, Float> params, @Nullable EasingType easing);

// Anchor to entities: when `position` is null and the definition declares entity-anchored
// positions, the UUIDs are zipped with them in declaration order and the effect follows those
// entities. Datapack `entity_selector`s resolve on the server only, so a client-side caller finds
// the entities itself and passes their UUIDs here.
boolean playEffect(Identifier effectId, int durationTicks, @Nullable Vec3 position, List<UUID> entityUuids, Map<String, Float> params, @Nullable EasingType easing);
long playEffectId(Identifier effectId, int durationTicks, @Nullable Vec3 position, List<UUID> entityUuids, Map<String, Float> params, @Nullable EasingType easing);

// Re-anchor a running instance locally (the local equivalent of the network MOVE action). Call it
// every tick to make an anchored effect follow a moving point or entity.
boolean moveEffect(Identifier effectId, long instanceId, Vec3 worldPos);
```

Notes:
- A non-null `position` wins over the definition's entity anchors (same rule as the network path).
- If a definition declares entity anchors and a local play passes no UUIDs, the play is rejected
  (the placeholder slots would otherwise render at the world origin) — logged once.
- A `null` easing keeps its meaning on this path: the definition's default easing is used.
- `moveEffect` returns `true` when the request was applied (already on the render thread) or queued;
  a queued request that references an unknown instance fails silently.
- Client-local plays with a position are recorded into Flashback replays with the same anchor, so
  a replay reproduces the effect where it originally happened.

#### Live control (client-local)

The live edits the network exposes also work locally, so a pure client-side mod never needs a
server round-trip:

```java
// Constant override of a parameter on every running instance (starts one when none is running).
boolean setParam(Identifier effectId, String name, float value);

// Swap a parameter for a live math expression (same syntax as the JSON "expr" field).
boolean setParamExpr(Identifier effectId, String name, String exprSource);

// Add/replace a keyframe. A negative time means "from here": pin the value the parameter has now
// and run the segment to `value` over |time| ticks, so animation variations chain seamlessly.
boolean setKeyframe(Identifier effectId, String name, int time, float value, @Nullable EasingType easing);
boolean setKeyframe(Identifier effectId, String name, int time, float value, String easing); // named curve

// Move one mask leaf locally (no packet) - the local counterpart of sendMaskMove; expands into
// the three mask.p<N>.center_* params (see "Mask leaf params" below).
boolean maskMove(Identifier effectId, int primitive, Vec3 position);
```

Like `moveEffect`, these return `true` when applied on the render thread or queued for it. A `null`
(or blank) easing means linear - a keyframe segment has no "definition default".

#### Mask leaf params (reserved names)

A mask's numeric leaves are registered as **ordinary animatable effect parameters**, so the whole
live-control and animation surface above works on them unchanged:

| Name | Meaning |
|---|---|
| `mask.p<N>.center_x` / `.center_y` / `.center_z` | leaf `<N>` centre (screen leaves use x/y) |
| `mask.p<N>.rotation` | leaf rotation (degrees) |
| `mask.p<N>.p<J>` | the leaf's per-shape parameter `J` (radius, half_width, … in `VFXMaskShapeKind` order) |
| `mask.p<N>.soft` | edge falloff width |
| `mask.p<N>.stroke` | stroke width (`fill: "stroke"`) |
| `mask.p<N>.field_amount` / `.field_scale` | edge-field amount / scale |

`<N>` is the leaf index in mask **declaration order** (0-based; a composition flattens depth-first).
Every live path works on them: `sendSetParam`/`setParam` (also constant, `expr`/`sendSetParamExpr`,
`sendKeyframe`/`setKeyframe`), graph `{ "from": node }` driven inputs, and datapack bindings.
`sendMaskMove`/`maskMove` are a convenience that sets a leaf's three centre components at once:

```java
// Server: follow a moving point (or drive an entity binding the client cannot resolve).
VFXAPI.sendMaskMove(player, effectId, 0, entity.position());

// Client-local, same expansion, no packet:
VFXAPI.maskMove(effectId, 0, new Vec3(x, y, z));
```

`mask.` is **reserved**: a user parameter with that prefix would be shadowed by the mask, not merged.

#### Custom mask shapes (client-local)

A mask leaf may reference a shape registered from code instead of a built-in figure. Two kinds
exist, both registered by id and both usable in a datapack mask as a `custom` leaf.

**Composed SDF** - a figure built from primitives and operations:

```java
VFXCustomShape shape = ...;                      // composed primitives + ops
VFXAPI.registerMaskShape(Identifier.fromNamespaceAndPath("mymod", "rune"), shape);
VFXAPI.unregisterMaskShape(id);
VFXCustomShape existing = VFXAPI.maskShape(id);  // null when not registered
```

The composed parts are **literal-only**: the leaf's animatable `mask.p<N>.p<J>` params are not fed
into the parts (they are fed to a GLSL plugin, below).

**GLSL plugin** - a fragment of GLSL injected into the coverage shader. The plugin defines exactly
one function, in the leaf's space (world units for a world leaf), negative inside:

```glsl
float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1);
```

`world` is the depth-reconstructed world position (zero for a screen leaf), `uv` the screen UV, and
`p0`/`p1` are the leaf's eight animatable params, so a plugin shape stays drivable from the timeline
(keyframes, `expr`, graph inputs, `setParam`). Register it with:

```java
VFXAPI.registerMaskShapeGlsl(Identifier.fromNamespaceAndPath("mymod", "pentagram"), plugin);
VFXAPI.unregisterMaskShapeGlsl(id);
```

Limits and failure behaviour, all of them deliberate:

- A mask may hold at most **2 custom leaves**; a third is a per-file parse error (it would alias
  row 0 in the packed coverage UBO).
- The coverage shader is compiled per distinct set of plugin ids, capped at **4 variants**; the
  variant re-reads the live plugin source, so re-registering a plugin (or a resource reload) does
  not leave a stale program.
- A plugin that fails to compile degrades **only the masks using it** to neutral coverage and is
  reported once through `VFXLog.warnOnce`; it never takes down the mod or another effect.
- On the `1.21.11` node there is no shader-source hook, so a GLSL-plugin shape renders nothing
  there; the composed-SDF kind works on every node.
- Plugin coverage obeys the same per-leaf fail-closed contract as every other leaf: an unresolved
  leaf contributes zero and can never be inverted into "everywhere".

`vfxweaver:ringed_glsl` is a built-in plugin (a screen ring with 8 petal-modulated lobes) shipped
so the path is testable in game; see the [guide](guide/index.md) for the datapack side.

#### Block/item-particle presets (client-local)

Model particles — the `particles` effect with `"particle": "block"`, `"particle": "item"` or a
preset id — can be defined from code, mirroring `registerDefinitions`. A spec draws a block **or**
an item (exactly one). Each live particle renders as a **client-side display entity**
(`BlockDisplay`/`ItemDisplay`), so vanilla interpolates its motion; the spec's brightness maps onto
the display's brightness override, `size` onto the transformation scale and `spin` (degrees/tick)
into a rotation driven by the spec's `spinMode`/`spinAxis` (`TUMBLE` = physical full-3D spin about a
random or fixed axis, `YAW` = uniform spin about one axis, `NONE` = no rotation) with `spinRandom`/
`spinFriction`/`spinRoll` tuning its randomness and contact response. Presets are
**client-local and never synced**; the datapack layer (`data/<ns>/vfx_particles/<name>.json`) wins
for the same id.

```java
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;

VFXBlockParticleSpec ember = VFXBlockParticleSpec.builder(Blocks.MAGMA_BLOCK.defaultBlockState())
    .brightness(15, 15)  // block-display semantics: -1 = world light, or separate block/sky levels
    .gravity(0.8F).friction(0.94F).collide(1.0F).bounce(0.2F)
    .size(0.35F).life(60).spin(12.0F)
    .spinMode(VFXBlockParticleSpec.SpinMode.TUMBLE)  // TUMBLE / YAW / NONE
    .spinAxis(new Vector3f(0.0F, 1.0F, 0.0F))        // null = random axis
    .spinRandom(1.0F).spinFriction(1.0F).spinRoll(0.5F)
    .build();

// Item form: the item's baked model is drawn (skull/tool/ingot particles). `item(ItemStack)`
// is the shorthand for the default physics; a builder is available when you need to tune them.
VFXBlockParticleSpec skull = VFXBlockParticleSpec.item(new ItemStack(Items.SKELETON_SKULL));
boolean skullBlock = skull.hasBlock();  // false
boolean skullItem = skull.hasItem();    // true

// Register/unregister a preset in the local layer; register returns false when it is full (256).
boolean registered = VFXAPI.registerBlockParticle(Identifier.fromNamespaceAndPath("mymod", "ember"), ember);
boolean removed = VFXAPI.unregisterBlockParticle(Identifier.fromNamespaceAndPath("mymod", "ember"));

// Look one up (datapack layer wins). @Nullable.
VFXBlockParticleSpec found = VFXAPI.blockParticle(Identifier.fromNamespaceAndPath("mymod", "ember"));

// Spawn a single block particle immediately on this client (no packet, no effect instance);
// no-op on a dedicated server. `velocity` is in blocks/tick; the spec supplies the physics.
VFXAPI.spawnBlockParticle(ember, new Vec3(x, y, z), new Vec3(0.0, 0.25, 0.0));
```

A `particles` effect then reaches the preset with `"particle": "mymod:ember"`; the effect's
`brightness`/`gravity`/`friction`/`collide`/`bounce`/`size`/`life`/`spin`/`spin_random`/
`spin_friction`/`spin_roll` params override its fields. `spin_mode` and `spin_axis` are spec/preset
fields only (effect params are numeric), so an effect selects yaw/none by naming a preset. The same
methods register/spawn item presets — the spec carries the model, the API surface is unchanged. See
the `particles` block and item mode in the [effects guide](guide/effects.md).

**Spark presets** use the same client-local registry: `VFXAPI.registerSpark(id, VFXSparkSpec)`,
`VFXAPI.unregisterSpark(id)`, `VFXAPI.spark(id)` and `VFXAPI.spawnSpark(spec, position, velocity)`
(the client-only `spawnSpark` is a `default` no-op on `VFXLocalDispatcher`, overridden by the mod's
client dispatcher). A spark preset is a `vfx_particles` file with `"kind": "spark"`; see the
[Spark presets](guide/particles/sparks.md) page.

### `VFXAPI.EffectRequest` (fluent builder)

```java
// Builds one request and plays it on a player (over the network) or locally on this client:
VFXAPI.EffectRequest.of()
	.duration(60)                    // ticks, 0 = definition default
	.param("radius", 2.0F)           // constant overrides (repeatable)
	.target(entityUuid)              // entity UUID targets / entity-anchored overlay slots (repeatable)
	.easing(EasingType.EASE_OUT_CUBIC) // or .easing("ease_out_cubic")
	.play(player);                   // or .play() for a client-local play
```

### `VFXLocalDispatcher`

A bridge the client entrypoint (`VFXClient`) registers via `VFXAPI.setLocalDispatcher(...)` so `playEffect`/`stopEffect`/`stopAllEffects` can run without a network packet. Other mods don't need to implement it — it's an internal part of the common↔client link of the mod. `spawnBlockParticle` is a `default` no-op on the interface (so a dispatcher compiled before block particles still links) and is overridden by the mod's client dispatcher.

## Definition registry — `VFXDefinitionManager`

```java
VFXDefinitionManager.get().get(effectId);        // VFXDefinition or null
VFXDefinitionManager.get().contains(effectId);   // whether the effect exists (built-in or datapack)
VFXDefinitionManager.get().getDefinitions();      // Map<Identifier, VFXDefinition> — snapshot of all known effects
```

Updated on every `/reload` (see `VFXDefinitionManager.prepare`/`apply`); one broken datapack entry is logged and skipped, the rest load normally.

### Registering definitions from code

A **client-only mod cannot own definitions through a datapack**: Fabric does not reload mod-provided
`SERVER_DATA` packs on a multiplayer client (so `data/<ns>/vfx/*.json` only loads in single player),
and a server running this mod replaces the whole definition set over `vfxweaver:vfx_sync`. Register
them from code instead:

```java
// Client init (or any time). Same JSON shape as a datapack file; each entry is parsed with the
// same validation - a broken entry is logged, reported by /vfx validate and skipped, and the
// layer is bounded. Returns the ids that were rejected (empty = all good).
Set<Identifier> failed = VFXAPI.registerDefinitions(Map.of(
    Identifier.fromNamespaceAndPath("mymod", "burst"), """
        { "type": "particles", "duration": 40, "shape": "sphere", "params": { "rate": 80, "radius": 1.5 } }
        """,
    Identifier.fromNamespaceAndPath("mymod", "ring"), """
        { "type": "particles", "duration": 60, "shape": "ring", "params": { "rate": 60, "radius": 2.0 } }
        """));

boolean removed = VFXAPI.unregisterDefinition(Identifier.fromNamespaceAndPath("mymod", "burst"));
```

Semantics:

- **Usable immediately** and it **survives `/reload` and a server sync** — local definitions live in a
  layer of their own; `applySynced` only replaces the datapack/server set. (A local definition was
  previously lost on joining a server, and `playEffect("mymod:thing", ...)` then failed to resolve.)
- **Private to this client**: a client's local definitions are never synced to other players
  (`getRawDefinitions()`, the payload source, stays datapack-only).
- **The datapack/server layer wins** for the same id, so a server can still override a local effect.
- Registered ids show up in `getDefinitions()`, `contains()`, `/vfx list` and (when broken) in
  `getParseErrors()` / `/vfx validate`.
- No protocol or datapack-format change; purely additive API.

## Network protocol

### Clientbound: `vfxweaver:vfx_trigger` (`VFXTriggerPayload`)

| Field | Type | Description |
|---|---|---|
| `protocolVersion` | byte | Current value — `VFXTriggerPayload.PROTOCOL_VERSION` (6). The client **silently ignores** the packet on a version mismatch (see `VFXClient.handleTrigger`). |
| `effectId` | `Identifier` | Effect id (built-in or datapack) |
| `action` | `VFXAction` (`PLAY`/`STOP`/`SET_PARAM`/`KEYFRAME`/`SET_EXPR`/`MOVE`) | `SET_PARAM`/`KEYFRAME` apply to **running** effect instances: `params` carries exactly one `name → value` entry, for `KEYFRAME` the frame time is in `durationTicks` (negative = start the segment at the current time and run over `|time|` ticks), the segment easing in `easing`; `SET_EXPR` uses `exprParam`+`exprSource`; `MOVE` uses `position` + `instanceId` |
| `durationTicks` | varint | 0 = definition default, negative = persistent (only for `PLAY`) |
| `elapsedTicks` | varint | Resume offset: how far into the timeline the effect already is (only for `PLAY`, 0 = start fresh). Used when the server re-applies effects after a reconnect. |
| `params` | `Map<String, Float>` | Constant overrides, numbers only |
| `easing` | `EasingType` (string) | |
| `exprParam` / `exprSource` | optional strings | Only for `SET_EXPR`: the parameter name and the raw expression source |

### Serverbound: `vfxweaver:vfx_request` (`VFXRequestPayload`)

Lets a client mod ask the server to play an effect through the definition registry. Fields: `protocolVersion` (must match `VFXTriggerPayload.PROTOCOL_VERSION`), `effectId`, `broadcast` (boolean), `instanceId` (long, 0 = allocate), `worldPos` (optional `Vec3`), `params` (max 32), `easing` (built-in easing name string).

- `broadcast = false`: the effect plays only on the requesting player's client.
- `broadcast = true`: the effect plays for every connected player, but the server only honours it from operators (gamemaster level) — anyone else is silently dropped (logged server-side).
- Easing: built-in easing names resolve; custom datapack curve names fall back to `LINEAR` on this path.

Bump `PROTOCOL_VERSION` on any breaking packet-format change — otherwise old clients silently ignore new packets without a single warning in the log.

---
See also: [guide/](guide/index.md) — user guide (commands, datapacks), [ARCHITECTURE.md](ARCHITECTURE.md) — how rendering works under the hood.

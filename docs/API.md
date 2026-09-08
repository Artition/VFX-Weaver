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

// Live-overrides a parameter of a running effect (without restarting the timeline).
// Ignored by the client with a warning in the log if the effect is not currently running.
void sendSetParam(ServerPlayer player, Identifier effectId, String param, float value);

// Adds/replaces a keyframe of a parameter of a running effect.
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

A bridge the client entrypoint (`VFXClient`) registers via `VFXAPI.setLocalDispatcher(...)` so `playEffect`/`stopEffect`/`stopAllEffects` can run without a network packet. Other mods don't need to implement it — it's an internal part of the common↔client link of the mod.

## Definition registry — `VFXDefinitionManager`

```java
VFXDefinitionManager.get().get(effectId);        // VFXDefinition or null
VFXDefinitionManager.get().contains(effectId);   // whether the effect exists (built-in or datapack)
VFXDefinitionManager.get().getDefinitions();      // Map<Identifier, VFXDefinition> — snapshot of all known effects
```

Updated on every `/reload` (see `VFXDefinitionManager.prepare`/`apply`); one broken datapack entry is logged and skipped, the rest load normally.

## Network protocol

The `vfxweaver:vfx_trigger` packet (`VFXTriggerPayload`), clientbound play.

| Field | Type | Description |
|---|---|---|
| `protocolVersion` | byte | Current value — `VFXTriggerPayload.PROTOCOL_VERSION`. The client **silently ignores** the packet on a version mismatch (see `VFXClient.handleTrigger`). |
| `effectId` | `Identifier` | Effect id (built-in or datapack) |
| `action` | `VFXAction` (`PLAY`/`STOP`/`SET_PARAM`/`KEYFRAME`) | `SET_PARAM`/`KEYFRAME` apply to **running** effect instances: `params` carries exactly one `name → value` entry, for `KEYFRAME` the frame time is in `durationTicks`, the segment easing in `easing` |
| `durationTicks` | varint | 0 = definition default, negative = persistent (only for `PLAY`) |
| `elapsedTicks` | varint | Resume offset: how far into the timeline the effect already is (only for `PLAY`, 0 = start fresh). Used when the server re-applies effects after a reconnect. |
| `params` | `Map<String, Float>` | Constant overrides, numbers only |
| `easing` | `EasingType` (string) | |

Bump `PROTOCOL_VERSION` on any breaking packet-format change — otherwise old clients silently ignore new packets without a single warning in the log.

---
See also: [../docs/GUIDE.md](GUIDE.md) — user guide (commands, datapacks), [ARCHITECTURE.md](ARCHITECTURE.md) — how rendering works under the hood.

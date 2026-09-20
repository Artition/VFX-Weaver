# Commands

## 1. Commands

| Command | Description |
|---|---|
| `/vfx play <effect> [{[param:value],...}] [players]` | Play an effect (default — to yourself). The optional param-map (like in `/vfx set`) overrides the definition's default params, including world coordinates (`pos_x/y/z`) — like `overrides` in the Java API. Tab autocomplete. |
| `/vfx playat <effect> <x> <y> <z> [{[...]}] [players]` | Play an effect anchored to world coordinates: the client re-anchors spatial bindings (`screen_x/y`, `proximity`) to that point and uses it for the effect's positions (for `block_tint`/`block_outline`). The optional param-map — overrides, like `play`. |
| `/vfx playentity <effect> [{[...]}] <targets> [players]` | Play an effect on selected entities (selector, e.g. `@e[type=!player,distance=..10]`). Targets are passed by UUID (up to 16) and apply to `entity_tint`/`entity_outline`. The optional param-map — overrides. The optional `[players]` — who sees the effect; default — the executing player. |
| `/vfx stop <effect> [players]` | Stop the effect (all its instances). Effects with `fade_ticks > 0` fade out smoothly. |
| `/vfx stop [<player>]` | Stop **every** active effect of a player (default — the executing player). Reuses the per-effect stop path on the server and also clears effects the executor's own client played locally. A bare token is parsed as an `<effect>` first, so target a player with a selector (`@p`/`@a`) or a name that is not a valid effect id. |
| `/vfx set <effect> {[param:value],...} [players]` | Live override of params of a **running** effect, without restarting the timeline. If the effect is not running — a new instance is started with those values and the definition's own `duration` (it ends on schedule like a normal play). Tab walks the syntax: `{` → `[` → param name → `:` value → `]` → `,` (new pair) or `}`. |
| `/vfx list` | List all loaded definitions (built-ins + datapack). |
| `/vfx validate [namespace]` | Dry-run health check of VFX definitions: prints how many are loaded and lists every broken datapack file with its parse error (optionally filtered by namespace, tab-completed). Operator-only. Useful for datapack development and server admin checks without digging through logs. |

On `/vfx stop` the effect is removed instantly if `fade_ticks` is not set or is 0; otherwise — a smooth fade to neutral values.

Repeated `/vfx play` of the same effect **does not replace** the playing instance — it adds another independent one (e.g. several dents on screen at once). `/vfx stop <effect>` stops all instances of that effect (stopping a single instance is only possible via the Java API, [7](index.md#7-java-api-for-other-mods)); up to 64 effects play at once in total.

**Param-map caveats:** the param-map overrides *parameters only* — `duration` and `fade_ticks` are definition fields and cannot be changed from a command. To get a persistent built-in effect with a smooth exit, wrap it in a datapack definition (see [4](#4-persistent-effects-onoff-with-animation)).

## 4. Persistent effects: on/off with animation

```json
{
	"type": "block_outline",
	"persistent": true,
	"fade_ticks": 15,
	"positions": [[8, 70, 8]],
	"params": { "width": 0.05, "color_r": 1.0, "color_g": 0.85, "color_b": 0.2, "alpha": 0.9 }
}
```

- `/vfx play` → smooth fade-in over `fade_ticks` (weight 0→1);
- `/vfx stop` → smooth fade-out, then the effect is removed;
- for shader effects the weight blends params with neutral values (`VFXEffectType.neutralValue`);
- for overlays the weight multiplies `alpha`.

## 4.1 Looping

```json
{
	"type": "block_outline",
	"loop": true,
	"fade_ticks": 10,
	"duration": 40,
	"positions": [[8, 70, 8]],
	"params": {
		"width": 0.05,
		"color_r": 0.2, "color_g": 0.6, "color_b": 1.0,
		"alpha": { "keyframes": [
			{ "time": 0, "value": 0.15 },
			{ "time": 20, "value": 1.0 },
			{ "time": 40, "value": 0.15 }
		] }
	}
}
```

`loop` = the effect runs forever + the timeline (including keyframes and `start`/`end`) plays in a circle with period `duration`. Stopping — like persistent (`fade_ticks`).

## 4.2 Effect on entities

```json
{
	"type": "entity_outline",
	"duration": 120,
	"fade_ticks": 10,
	"params": { "width": 0.06, "color_r": 1.0, "color_g": 0.85, "color_b": 0.2, "alpha": 1.0, "through_blocks": 0 }
}
```

For a tint add `"texture": 1` (recolour the texture) or `"texture": 0` (flat colour with the texture as a mask):

```json
{
	"type": "entity_tint",
	"duration": 120,
	"fade_ticks": 10,
	"params": { "color_r": 0.2, "color_g": 0.6, "color_b": 1.0, "alpha": 0.5, "texture": 1, "through_blocks": 0 }
}
```

Trigger on nearby mobs (the selector picks targets, UUIDs are sent to the client):

```
/vfx playentity vfxweaver_test:test_entity_outline @e[type=!player,distance=..10]
```

The same from the Java API — see `VFXAPI.sendEffect(...)` with the `List<UUID> entityUuids` argument in [docs/API.md](../API.md).

## 5. Collections — several effects with one command

```json
{
	"type": "collection",
	"effects": [
		{ "effect": "vfxweaver_test:marker_persistent", "delay": 0 },
		{ "effect": "vfxweaver_test:block_tint_demo", "delay": 10 },
		{ "effect": "vfxweaver_test:blur_grow", "delay": 20 },
		{ "effect": "vfxweaver_test:red_pulse", "delay": 40, "duration": 60 },
		{ "effect": "vfxweaver:chromatic_aberration", "delay": 55, "duration": 40, "params": { "intensity": 1.2 } }
	]
}
```

Child effect fields: `effect` (id, required), `delay` (ticks from collection start), `duration` (0 = definition default, −1 = persistent), `params` (each value is a full parameter spec — plain numbers are constants, objects support `start`/`end`, `keyframes`, `bind`, `expr`, `multiply`, see §3.2), `easing`. Collection nesting — up to 4 levels. `/vfx stop <collection>` cancels not-yet-started children; already playing ones are stopped by their own `/vfx stop`.

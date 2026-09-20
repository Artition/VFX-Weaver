# collection

`type: "collection"`

Not an effect itself: plays a list of child effects with per-child delays, so several effects start
from one command (or one `sendEffect`).

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `effects` | array | — (required) | The child effects, in play order: `{ "effect": "<id>", "delay": <ticks>, "duration": <ticks>, "params": { ... }, "easing": "<name>" }` |
| `effect` | string | — (required, per child) | Id of the child effect to play |
| `delay` | int | 0 | Ticks from the collection's start before the child begins |
| `duration` | int | 0 | `0` = the child definition's own duration; `-1` = persistent |
| `params` | object | — | Param overrides for the child. Each value is a full [param spec](../datapack/params.md#ways-to-set-a-param) - a plain number is a constant, an object supports `start`/`end`, `keyframes`, `bind`, `expr`, `multiply` |
| `easing` | string | `linear` | Easing for the child's `start`→`end` params |

Collections nest up to **4 levels**. `/vfx stop <collection>` cancels not-yet-started children;
children already playing are stopped by their own `/vfx stop <child>`.

## Example

```json
{
	"type": "collection",
	"effects": [
		{ "effect": "vfxweaver:block_tint", "delay": 0 },
		{ "effect": "vfxweaver:blur", "delay": 10 },
		{ "effect": "vfxweaver:red_pulse", "delay": 40, "duration": 60 },
		{ "effect": "vfxweaver:chromatic_aberration", "delay": 55, "duration": 40, "params": { "intensity": 1.2 } }
	]
}
```

```
/vfx play mymap:my_collection
```

# collection

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/collection.mp4" type="video/mp4"></video>

`type: "collection"`

Not an effect itself: plays a list of child effects with per-child delays, so several effects start
from one command (or one `sendEffect`).

## Fields

> Collections also accept the [shared definition fields](index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

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
		{ "effect": "vfxweaver:screen_flash", "delay": 40, "duration": 60, "params": { "color_r": 1.0, "color_g": 0.0, "color_b": 0.0, "alpha": 0.6 } },
		{ "effect": "vfxweaver:chromatic_aberration", "delay": 55, "duration": 40, "params": { "intensity": 1.2 } }
	]
}
```

```
/vfx play mymap:my_collection
```

## Code

```
/vfx play vfx_demos:show_collection
```

Its datapack definition:

```json
{
	"type": "collection",
	"duration": 200,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 20,
	"effects": [
		{
			"effect": "vfxweaver:blur",
			"delay": 0,
			"duration": 80,
			"params": {
				"radius": {
					"keyframes": [
						{
							"time": 0,
							"value": 0.0
						},
						{
							"time": 40,
							"value": 7.0
						},
						{
							"time": 80,
							"value": 0.0
						}
					]
				}
			}
		},
		{
			"effect": "vfxweaver:chromatic_aberration",
			"delay": 60,
			"duration": 80,
			"params": {
				"intensity": {
					"keyframes": [
						{
							"time": 0,
							"value": 0.0
						},
						{
							"time": 40,
							"value": 0.5
						},
						{
							"time": 80,
							"value": 0.0
						}
					]
				}
			}
		},
		{
			"effect": "vfxweaver:screen_flash",
			"delay": 120,
			"duration": 80,
			"params": {
				"alpha": {
					"keyframes": [
						{
							"time": 0,
							"value": 0.0
						},
						{
							"time": 40,
							"value": 0.16
						},
						{
							"time": 80,
							"value": 0.0
						}
					]
				},
				"color_r": 1.0,
				"color_g": 0.85,
				"color_b": 0.6
			}
		}
	]
}
```

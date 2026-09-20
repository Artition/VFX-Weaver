# invert

`type: "invert"`

Inverts the screen colours.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 1 (fades to 0) | 1 = fully inverted, 0.5 = halfway (washed out), 0 = off |

## Example

```json
{
	"type": "invert",
	"duration": 60,
	"params": { "intensity": 1 }
}
```

```
/vfx play vfxweaver:invert
```

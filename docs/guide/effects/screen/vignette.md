# vignette

`type: "vignette"`

Darkens/colours the screen edges.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.7 (fades to 0) | Edge darkening strength |
| `color_r/g/b` | float | 0 / 0 / 0 | Edge colour (black by default) |

## Example

```json
{
	"type": "vignette",
	"duration": 60,
	"params": { "intensity": 0.7, "color_r": 0, "color_g": 0, "color_b": 0 }
}
```

```
/vfx play vfxweaver:vignette {[intensity:1]}
```

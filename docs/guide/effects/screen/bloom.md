# bloom

`type: "bloom"`

Glow around bright screen areas.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.6 (fades to 0) | Glow strength |
| `threshold` | float | 0.7 | Luminance above which pixels glow (0..1) |
| `radius` | float | 3 | Glow spread in pixels |

## Example

```json
{
	"type": "bloom",
	"duration": 60,
	"params": { "intensity": 0.6, "threshold": 0.7, "radius": 3 }
}
```

```
/vfx play vfxweaver:bloom {[threshold:0.5],[intensity:1]}
```

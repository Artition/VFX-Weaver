# vortex

`type: "vortex"`

Swirls pixels into a funnel around a point.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `strength` | float | 2.5 (fades to 0) | Max swirl angle in radians; sign = direction |
| `radius` | float | 0.5 | Funnel radius as a screen fraction |
| `center_x`, `center_y` | float | 0.5, 0.5 | Funnel centre in UV |

## Example

```json
{
	"type": "vortex",
	"duration": 60,
	"params": { "strength": 2.5, "radius": 0.5, "center_x": 0.5, "center_y": 0.5 }
}
```

```
/vfx play vfxweaver:vortex {[strength:4]}
```

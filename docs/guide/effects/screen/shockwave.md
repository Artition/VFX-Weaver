# shockwave

`type: "shockwave"`

A single refraction ring ripples outward from a point.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `center_x/y` | float | 0.5 / 0.5 | Wave origin in UV |
| `radius` | float | 0.4 -> 1.5 | Current ring radius, screen-height fractions (animate 0 -> 1.5) |
| `width` | float | 0.15 | Ring thickness |
| `amplitude` | float | 0.12 (fades to 0) | UV displacement at the ring crest |
| `sharpness` | float | 1.5 | Ring profile (1 = smooth sine ripple, 4 = hard glassy ring) |

## Example

```json
{
	"type": "shockwave",
	"duration": 60,
	"params": { "center_x": 0.5, "center_y": 0.5, "radius": 0.4, "width": 0.15, "amplitude": 0.12, "sharpness": 1.5 }
}
```

```
/vfx play vfxweaver:shockwave {[radius:0.8]}
```

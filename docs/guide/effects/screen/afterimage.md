# afterimage

`type: "afterimage"`

Feedback echo: movement leaves smearing trails that linger and dissolve on a fixed decay schedule.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `decay` | float | 0.92 | Fraction of the previous frame surviving each tick (0..0.98) |
| `blend` | float | 0.6 | How strongly history mixes into the live image |
| `drift` | float | 0 | Per-frame zoom of the echo, positive stretches outward (negative = shrink) |
| `desat` | float | 0.35 | Saturation loss in the echo layer |
| `intensity` | float | 1 (fades to 0) | Strength of the echo over the live frame |

## Example

```json
{
	"type": "afterimage",
	"duration": 60,
	"params": { "decay": 0.92, "blend": 0.6, "drift": 0, "desat": 0.35, "intensity": 1 }
}
```

```
/vfx play vfxweaver:afterimage {[intensity:0.5]}
```

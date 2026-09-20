# solarize

`type: "solarize"`

Bright pixels invert, dark stay untouched.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `threshold` | float | 0.5 | Luma above which colours invert (0..1) |
| `softness` | float | 0 | Rolloff width around the threshold (0..1) |
| `intensity` | float | 1 (fades to 0) | Blend original to solarized (0..1) |

## Example

```json
{
	"type": "solarize",
	"duration": 60,
	"params": { "threshold": 0.5, "softness": 0, "intensity": 1 }
}
```

```
/vfx play vfxweaver:solarize {[threshold:0.4]}
```

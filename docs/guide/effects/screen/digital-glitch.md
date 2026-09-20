# digital_glitch

`type: "digital_glitch"`

The frame tears into horizontal bands with RGB-split spikes, in bursts (slot-gated, not constant tearing).

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `block` | float | 0.06 | Band height, screen-height fractions (0.01..0.5) |
| `displacement` | float | 0.08 | Max sideways band shift, screen-width fractions (0..0.5) |
| `rate` | float | 6 | Glitch quanta per second (slots) |
| `chroma` | float | 0.5 | RGB-split strength inside glitched bands (0..1) |
| `seed` | float | 0 | Pattern offset - change for a different tear layout |
| `chance` | float | 0.4 | Fraction of slots that burst (0..1) |
| `intensity` | float | 1 (fades to 0) | Overall strength (0..1) |

## Example

```json
{
	"type": "digital_glitch",
	"duration": 60,
	"params": { "block": 0.06, "displacement": 0.08, "rate": 6, "chroma": 0.5, "seed": 0, "chance": 0.4 }
}
```

```
/vfx play vfxweaver:digital_glitch {[chance:1],[displacement:0.15]}
```

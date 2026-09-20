# double_vision

`type: "double_vision"`

Two ghost copies of the frame offset left/right with a slow drift.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `offset` | float | 0.04 | Ghost distance from the centre, screen-width fractions (0..0.5) |
| `ghost_opacity` | float | 0.5 | Ghost opacity (0..1) |
| `drift` | float | 0 | Slow sinusoidal drift, screen fractions per second (0..0.2) |
| `intensity` | float | 1 (fades to 0) | Overall strength (0..1) |

## Example

```json
{
	"type": "double_vision",
	"duration": 60,
	"params": { "offset": 0.04, "ghost_opacity": 0.5, "drift": 0, "intensity": 1 }
}
```

```
/vfx play vfxweaver:double_vision {[offset:0.06],[ghost_opacity:0.7]}
```

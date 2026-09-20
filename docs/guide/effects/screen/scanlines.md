# scanlines

`type: "scanlines"`

CRT bands drifting across the screen.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.3 (fades to 0) | Band visibility |
| `line_count` | float | 3 | Bands per 100 screen pixels |
| `speed` | float | 0.5 | Downward drift speed |

## Example

```json
{
	"type": "scanlines",
	"duration": 60,
	"params": { "intensity": 0.3, "line_count": 3, "speed": 0.5 }
}
```

```
/vfx play vfxweaver:scanlines {[line_count:6]}
```

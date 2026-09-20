# stop_motion

`type: "stop_motion"`

Stop-motion / papercraft: the picture updates only a few times per second while input keeps moving.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `fps` | float | 12 (fades to 0) | Target update rate of the held picture, updates per second (1..30; <=1 = back to full speed) |

## Example

```json
{
	"type": "stop_motion",
	"duration": 60,
	"params": { "fps": 12 }
}
```

```
/vfx play vfxweaver:stop_motion {[fps:8]}
```

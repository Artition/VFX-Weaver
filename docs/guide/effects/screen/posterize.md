# posterize

`type: "posterize"`

Posterization: reduces the number of colours on screen, clean quantization without dithering.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `strength` | float | 0.25 (fades to 0) | 0 = off, 1 = only 2 levels per channel |

## Example

```json
{
	"type": "posterize",
	"duration": 60,
	"params": { "strength": 0.25 }
}
```

```
/vfx play vfxweaver:posterize {[strength:0.6]}
```

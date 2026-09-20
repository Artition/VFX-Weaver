# blur

`type: "blur"`

Two-pass adaptive Gaussian blur.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 4 (fades to 0) | Blur radius in pixels |

## Example

```json
{
	"type": "blur",
	"duration": 60,
	"params": { "radius": 4 }
}
```

```
/vfx play vfxweaver:blur {[radius:10]}
```

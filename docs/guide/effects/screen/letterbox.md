# letterbox

`type: "letterbox"`

Cinematic bars at the top and bottom of the screen.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `height` | float | 0.12 (fades to 0) | Bar height as a fraction of the screen half-height (max 0.5) |
| `color_r/g/b` | float | 0 / 0 / 0 | Bar colour (black by default) |

## Example

```json
{
	"type": "letterbox",
	"duration": 60,
	"params": { "height": 0.12, "color_r": 0, "color_g": 0, "color_b": 0 }
}
```

```
/vfx play vfxweaver:letterbox {[height:0.2]}
```

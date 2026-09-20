# screen_flash

`type: "screen_flash"`

Fullscreen colour overlay (flash).

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `alpha` | float | 0.8 (fades to 0) | Overlay opacity |
| `color_r/g/b` | float | 1 / 1 / 1 | Flash colour (white by default) |

## Example

```json
{
	"type": "screen_flash",
	"duration": 60,
	"params": { "alpha": 0.8, "color_r": 1, "color_g": 1, "color_b": 1 }
}
```

```
/vfx play vfxweaver:screen_flash {[color_r:1],[color_g:0],[color_b:0],[alpha:1]}
```

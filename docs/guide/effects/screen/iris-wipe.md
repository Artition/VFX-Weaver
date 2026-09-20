# iris_wipe

`type: "iris_wipe"`

Everything outside a circle goes black - old-film iris transition.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 0.4 -> 1.4 | Circle radius in screen-height fractions (0 = closed, 1.4 = fully open) |
| `softness` | float | 0.05 | Edge feather |
| `center_x/y` | float | 0.5 / 0.5 | Circle centre in UV (bindable to `screen_x/ screen_y`) |
| `zoom` | float | 1 -> 0 | Push-in inside the circle (0..1) |

## Example

```json
{
	"type": "iris_wipe",
	"duration": 60,
	"params": { "radius": 0.4, "softness": 0.05, "center_x": 0.5, "center_y": 0.5, "zoom": 1 }
}
```

```
/vfx play vfxweaver:iris_wipe {[zoom:0]}
```

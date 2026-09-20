# dent

`type: "dent"`

A local "dent" (lens warp) around a point, or along a segment in line mode.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `strength` | float | 0.6 (fades to 0) | Warp strength; positive pulls in, negative pushes out |
| `radius` | float | 0.25 | Dent size as a fraction of the screen |
| `center_x`, `center_y` | float | 0.5, 0.5 | Dent centre in UV (0..1) |
| `line_mode` | float | 0 | 1 = segment mode (below) |
| `x0`, `y0`, `x1`, `y1` | float | 0..1 UV | Segment ends for line mode; bind them to the world via `bind: screen_x` / `screen_y` |

## Example

```json
{
	"type": "dent",
	"duration": 60,
	"params": { "strength": 0.6, "radius": 0.25, "center_x": 0.5, "center_y": 0.5, "line_mode": 0, "x0": 0 }
}
```

```
/vfx play vfxweaver:dent {[strength:0.8],[radius:0.3]}
```

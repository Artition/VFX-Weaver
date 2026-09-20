# depth_of_field

`type: "depth_of_field"`

Screen tilt-shift: a sharp band, blur away from it.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.5 (fades to 0) | Blur strength outside the sharp band |
| `focus_center` | float | 0.5 | Sharp band centre, UV Y (0.5 = screen middle) |
| `focus_range` | float | 0.15 | Sharp band half-width in UV |

## Example

```json
{
	"type": "depth_of_field",
	"duration": 60,
	"params": { "intensity": 0.5, "focus_center": 0.5, "focus_range": 0.15 }
}
```

```
/vfx play vfxweaver:depth_of_field
```

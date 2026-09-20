# guide_line

`type: "guide_line"`

A glowing dashed line along a parabolic arc between two anchors.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `width` | float | 0.15 | Line thickness, blocks |
| `dash_length` | float | 0.6 | Dash length, blocks |
| `gap` | float | 0.6 | Gap between dashes, blocks |
| `speed` | float | 2 | Dash crawl speed along the line, blocks per second (negative = reverse) |
| `arc` | float | 1.5 | Bows the path up (+) or droops it (-) at the midpoint, blocks |
| `red/green/blue` | float | 0.25 / 1 / 0.45 | Line colour |
| `through_blocks` | float | 0 | 1 = visible through walls |
| `intensity` | float | 1 (fades to 0) | Line opacity |

## Example

```json
{
	"type": "guide_line",
	"duration": 60,
	"params": { "width": 0.15, "dash_length": 0.6, "gap": 0.6, "speed": 2, "arc": 1.5, "red": 0.25 }
}
```

```
/vfx playat vfxweaver:guide_line 8 70 8 {[arc:3]}
```

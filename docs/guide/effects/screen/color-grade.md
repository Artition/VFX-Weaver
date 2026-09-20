# color_grade

`type: "color_grade"`

Colour grading: saturation, contrast, brightness and a colour tint.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `saturation` | float | 0.7 (fades to 1) | 0 = grayscale, 1 = neutral, >1 = oversaturated |
| `contrast` | float | 1.05 (fades to 1) | 1 = neutral; <1 = flatter, >1 = harsher |
| `brightness` | float | 1 | 1 = neutral; 0 = black |
| `tint_r/g/b` | float | 1 / 0.9 / 1 (fade to 1) | Per-channel multiplier; 1 = neutral |

## Example

```json
{
	"type": "color_grade",
	"duration": 60,
	"params": { "saturation": 0.7, "contrast": 1.05, "brightness": 1, "tint_r": 1, "tint_g": 0.9, "tint_b": 1 }
}
```

```
/vfx play vfxweaver:color_grade
```

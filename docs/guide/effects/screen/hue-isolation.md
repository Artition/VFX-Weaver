# hue_isolation

`type: "hue_isolation"`

Keeps the chosen hue, everything else goes grayscale.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `hue` | float | 0 | Target hue on the colour wheel: 0 = red, 0.33 = green, 0.66 = blue, 0.5 = cyan/magenta boundary... (full circle 0..1) |
| `tolerance` | float | 0.2 | Hue match width: how far from `hue` (on the 0..1 wheel) a pixel may be and still keep its colour |
| `intensity` | float | 1 (fades to 0) | Strength of the grayscale conversion outside the tolerance |

## Example

```json
{
	"type": "hue_isolation",
	"duration": 60,
	"params": { "hue": 0, "tolerance": 0.2, "intensity": 1 }
}
```

```
/vfx play vfxweaver:hue_isolation {[hue:0.33],[tolerance:0.1]}
```

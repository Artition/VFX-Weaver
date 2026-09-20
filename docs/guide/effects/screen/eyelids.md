# eyelids

`type: "eyelids"`

Two soft curved dark lids slide in from the top and bottom. Animate `openness` 1 -> 0 to close the eyes; the built-in is a static half-open template.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `openness` | float | 0.5 | 1 = wide open (lids off screen), 0 = fully closed |
| `softness` | float | 0.15 | Feathered lid edge width, screen-height fractions |
| `curve` | float | 0.35 | Lid bulge toward the centre: 0 = straight bars, 1 = heavy arc |

## Example

```json
{
	"type": "eyelids",
	"duration": 60,
	"params": { "openness": 0.5, "softness": 0.15, "curve": 0.35 }
}
```

```
/vfx play vfxweaver:eyelids {[openness:0.2]}
```

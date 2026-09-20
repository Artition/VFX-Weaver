# invert

`type: "invert"`

Inverts the screen colours.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 1 (fades to 0) | 1 = fully inverted, 0.5 = halfway (washed out), 0 = off |

## Example

```json
{
	"type": "invert",
	"duration": 60,
	"params": { "intensity": 1 }
}
```

```
/vfx play vfxweaver:invert
```

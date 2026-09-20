# chromatic_aberration

`type: "chromatic_aberration"`

Splits the RGB channels towards the screen edges (RGB fringing).

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.8 (fades to 0) | Fringing strength; 0 = off |
| `radius` | float | 4 | Effect radius in pixels from the screen border |

## Example

```json
{
	"type": "chromatic_aberration",
	"duration": 60,
	"params": { "intensity": 0.8, "radius": 4 }
}
```

```
/vfx play vfxweaver:chromatic_aberration
```

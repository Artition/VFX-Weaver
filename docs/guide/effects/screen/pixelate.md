# pixelate

`type: "pixelate"`

Pixelation.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `cell_size` | float | 0.012 (fades to 0.0005) | Cell size as a fraction of the screen (0.012 ~= 23px on 1080p) |

## Example

```json
{
	"type": "pixelate",
	"duration": 60,
	"params": { "cell_size": 0.012 }
}
```

```
/vfx play vfxweaver:pixelate {[cell_size:0.03]}
```

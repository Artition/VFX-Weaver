# film_grain

`type: "film_grain"`

Animated film grain.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.08 (fades to 0) | Grain strength |
| `size` | float | 2 | Grain size in pixels |

## Example

```json
{
	"type": "film_grain",
	"duration": 60,
	"params": { "intensity": 0.08, "size": 2 }
}
```

```
/vfx play vfxweaver:film_grain
```

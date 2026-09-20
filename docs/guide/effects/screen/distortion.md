# distortion

`type: "distortion"`

Barrel (`amount > 0`) / pincushion (`amount < 0`) distortion of the whole screen.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 0.2 (fades to 0) | Distortion strength; sign picks the direction |
| `radius` | float | 0.8 | Screen fraction affected from the centre (0..1) |

## Example

```json
{
	"type": "distortion",
	"duration": 60,
	"params": { "amount": 0.2, "radius": 0.8 }
}
```

```
/vfx play vfxweaver:distortion
```

# camera_shake

`type: "camera_shake"`

Camera shake with simplex noise and a smooth fade-out envelope.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amplitude_x/y/z` | float | 0.12 / 0.12 / 0.04 | Position shake amplitude per axis (blocks) |
| `yaw` | float | 0.8 | Rotation shake amplitude (degrees) |
| `pitch` | float | 0.6 | Rotation shake amplitude (degrees) |
| `roll` | float | 0.4 | Rotation shake amplitude (degrees) |
| `frequency` | float | 7 | Noise oscillations per second |
| `hand` | float | 0.5 | First-person hand multiplier: 0 = hand stays still, 1 = full shake with the camera |

## Example

```json
{
	"type": "camera_shake",
	"duration": 60,
	"params": { "amplitude_x": 0.12, "amplitude_y": 0.12, "amplitude_z": 0.04, "yaw": 0.8, "pitch": 0.6, "roll": 0.4 }
}
```

```
/vfx play vfxweaver:camera_shake {[amplitude_y:0.3],[frequency:20]}
```

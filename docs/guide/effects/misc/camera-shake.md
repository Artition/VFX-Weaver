# camera_shake

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/camera_shake.mp4" type="video/mp4"></video>

`type: "camera_shake"`

Camera shake with simplex noise and a smooth fade-out envelope.

## Fields

> Camera effects also accept the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

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
	"params": { "amplitude_x": 0.12, "amplitude_y": 0.12, "amplitude_z": 0.04, "yaw": 0.8, "pitch": 0.6, "roll": 0.4, "frequency": 7, "hand": 0.5 }
}
```

```
/vfx play vfxweaver:camera_shake {[amplitude_y:0.3],[frequency:20]}
```

## Code

```
/vfx play vfx_demos:show_camera_shake
```

Its datapack definition:

```json
{
	"type": "camera_shake",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"amplitude_x": 0.05,
		"amplitude_y": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.02
				},
				{
					"time": 80,
					"value": 0.07
				},
				{
					"time": 160,
					"value": 0.02
				}
			]
		},
		"amplitude_z": 0.02,
		"yaw": 0.4,
		"pitch": 0.3,
		"roll": 0.2,
		"frequency": 5.0,
		"hand": 0.3
	}
}
```

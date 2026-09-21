# motion_blur

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/motion_blur.mp4" type="video/mp4"></video>

`type: "motion_blur"`

Directional blur from camera rotation speed. The built-in tracks the camera itself.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `intensity` | float | 0.35 (fades to 0) | Blur strength |
| `yaw_delta` | float | bound to camera | Yaw change between frames (deg/tick) - drives horizontal blur |
| `pitch_delta` | float | bound to camera | Pitch change between frames - drives vertical blur |

## Example

```json
{
	"type": "motion_blur",
	"duration": 60,
	"params": { "intensity": 0.35 }
}
```

```
/vfx play vfxweaver:motion_blur
```

## Code

```
/vfx play vfx_demos:show_motion_blur
```

Its datapack definition:

```json
{
	"type": "motion_blur",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 0.4
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"yaw_delta": {
			"bind": "camera_yaw_delta",
			"range": 1.0
		},
		"pitch_delta": {
			"bind": "camera_pitch_delta",
			"range": 1.0
		}
	}
}
```

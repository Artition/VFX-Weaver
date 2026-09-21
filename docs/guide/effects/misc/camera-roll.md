# camera_roll

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/camera_roll.mp4" type="video/mp4"></video>

`type: "camera_roll"`

Tilts the camera around its viewing axis by a fixed angle (dutch angle) with an optional slow wobble.

## Fields

> Camera effects also accept the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `angle` | float | 15 (fades to 0) | Roll in degrees; positive = clockwise lean |
| `wobble` | float | 0 | Sinusoidal sway of +/- this many degrees (0..45) |
| `wobble_speed` | float | 0.2 | Sway frequency, Hz (0..2) |

## Example

```json
{
	"type": "camera_roll",
	"duration": 60,
	"params": { "angle": 15, "wobble": 0, "wobble_speed": 0.2 }
}
```

```
/vfx play vfxweaver:camera_roll {[angle:25],[wobble:5]}
```

## Code

```
/vfx play vfx_demos:show_camera_roll
```

Its datapack definition:

```json
{
	"type": "camera_roll",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"angle": {
			"keyframes": [
				{
					"time": 0,
					"value": -10.0
				},
				{
					"time": 80,
					"value": 10.0
				},
				{
					"time": 160,
					"value": -10.0
				}
			]
		},
		"wobble": 0.0,
		"wobble_speed": 0.2
	}
}
```

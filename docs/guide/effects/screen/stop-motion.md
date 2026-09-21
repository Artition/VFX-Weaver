# stop_motion

<video autoplay loop muted playsinline width="100%"><source src="../../../assets/media/stop_motion.mp4" type="video/mp4"></video>

`type: "stop_motion"`

Stop-motion / papercraft: the picture updates only a few times per second while input keeps moving.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `fps` | float | 12 (fades to 0) | Target update rate of the held picture, updates per second (1..30; <=1 = back to full speed) |

## Example

```json
{
	"type": "stop_motion",
	"duration": 60,
	"params": { "fps": 12 }
}
```

```
/vfx play vfxweaver:stop_motion {[fps:8]}
```

## Code

```
/vfx play vfx_demos:show_stop_motion
```

Its datapack definition:

```json
{
	"type": "stop_motion",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"fps": {
			"keyframes": [
				{
					"time": 0,
					"value": 24.0
				},
				{
					"time": 80,
					"value": 4.0
				},
				{
					"time": 160,
					"value": 24.0
				}
			]
		}
	}
}
```

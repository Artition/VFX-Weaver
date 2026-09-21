# pulse_ring

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/pulse_ring.mp4" type="video/mp4"></video>

`type: "pulse_ring"`

A glowing ring around each position. With `billboard:1` (default) the ring always faces the camera (perfect circle from any angle); with `billboard:0` it stays in a fixed plane rotated by `rot_x/rot_y/rot_z`.

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 0 -> 6 | Current ring radius, blocks (animate 0 -> max) |
| `thickness` | float | 0.5 | Ring band width, blocks (`0` = no band, the ring is not drawn) |
| `billboard` | float | 1 | 1 = always faces the camera, 0 = fixed orientation by rot_* |
| `rot_x` | float | 0 | Fixed ring-plane pitch (degrees, -360..360, when billboard:0) |
| `rot_y` | float | 0 | Fixed ring-plane yaw (degrees, -360..360, when billboard:0) |
| `rot_z` | float | 0 | Fixed ring-plane roll (degrees, -360..360, when billboard:0) |
| `red/green/blue` | float | 1 / 0.35 / 0.1 | Ring colour |
| `through_blocks` | float | 0 | 1 = visible through walls |
| `intensity` | float | 1 (fades to 0) | Ring opacity |

## Example

```json
{
	"type": "pulse_ring",
	"duration": 60,
	"params": { "radius": 0, "thickness": 0.5, "billboard": 1, "rot_x": 0, "rot_y": 0, "rot_z": 0 }
}
```

```
/vfx playat vfxweaver:pulse_ring 8 70 8 {[radius:10],[billboard:0],[rot_x:60]}
```

## Code

```
/vfx play vfx_demos:show_pulse_ring
```

Its datapack definition:

```json
{
	"type": "pulse_ring",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"positions": [
		[
			2000,
			100.2,
			2000
		]
	],
	"params": {
		"red": 1.0,
		"green": 0.35,
		"blue": 0.1,
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 6.0
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		},
		"thickness": 0.4,
		"billboard": 1.0,
		"through_blocks": 0.0,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.2
				},
				{
					"time": 80,
					"value": 1.0
				},
				{
					"time": 160,
					"value": 0.2
				}
			]
		}
	}
}
```

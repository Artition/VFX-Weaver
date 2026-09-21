# fov_modifier

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/fov_modifier.mp4" type="video/mp4"></video>

`type: "fov_modifier"`

Changes the player's field of view.

## Fields

> Camera effects also accept the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `fov_delta` | float | 10 (fades to 0) | FOV change in degrees (positive = zoom out) |


> **Layering note** (`screen_layer` for camera/roll effects): the `screen_layer` parameter
> (`0` under the first-person hand, `1` above the hand below the GUI, `2` above everything) applies
> only to **screen post-processing** effects in section 2.1. Camera-space effects
> (`camera_shake`, `camera_roll`, `fov_modifier`) act directly on the camera transform - the world
> shakes, and the same shake is applied to the first-person hand (it moves together with the world).
> They do not accept `screen_layer`.

## Example

```json
{
	"type": "fov_modifier",
	"duration": 60,
	"params": { "fov_delta": 10 }
}
```

```
/vfx play vfxweaver:fov_modifier {[fov_delta:-20]}
```

## Code

```
/vfx play vfx_demos:show_fov_modifier
```

Its datapack definition:

```json
{
	"type": "fov_modifier",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"fov_delta": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.0
				},
				{
					"time": 80,
					"value": 10.0
				},
				{
					"time": 160,
					"value": 0.0
				}
			]
		}
	}
}
```

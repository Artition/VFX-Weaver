# motion_blur

`type: "motion_blur"`

Directional blur from camera rotation speed. The built-in tracks the camera itself.

## Fields

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

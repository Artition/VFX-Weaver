# camera_roll

`type: "camera_roll"`

Tilts the camera around its viewing axis by a fixed angle (dutch angle) with an optional slow wobble.

## Fields

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

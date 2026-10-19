# halftone

`type: "halftone"`

Print-style halftone dots whose size follows the local luminance.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `cell_size` | float | 5 | Dot cell size in pixels |
| `angle` | float | 45 | Screen angle in degrees |
| `gain` | float | 2.5 | Local-contrast gain |
| `amount` | float | 1 (fades to 0) | Dot pattern visibility |

## Example

```json
{
	"type": "halftone",
	"duration": 60,
	"params": { "cell_size": 5, "angle": 45, "gain": 2.5, "amount": 1 }
}
```

```
/vfx play vfxweaver:halftone {[cell_size:8]}
```

## Code

```
/vfx play vfxweaver:halftone
```

Its datapack definition:

```json
{
	"type": "halftone",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"cell_size": 5.0,
		"angle": 45.0,
		"gain": 2.5,
		"amount": {
			"start": 1.0,
			"end": 0.0
		}
	}
}
```

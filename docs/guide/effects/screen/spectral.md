# spectral

`type: "spectral"`

Rainbow prism look over the frame: the prism colour ramp sweeps around the screen.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amount` | float | 1 (fades to 0) | Effect strength (0..1) |
| `mode` | float | 0 | 0 = prism duotone, 1 = chromatic smear, 2 = spectral ring |
| `center_x` | float | 0.5 | The point the prism sweeps around, screen-width fractions |
| `center_y` | float | 0.5 | The point the prism sweeps around, screen-height fractions |

`mode` picks one of the three variants of the prism family:

| Mode | Look |
|---|---|
| 0 | **Prism duotone.** The frame is split into a dark and a bright half by the local mean of an 8-tap ring (radius 0.045, x divided by the aspect, `step(avg + 0.004, luma)`); the bright half is painted with the prism colour, the hottest pixels (`step(avg + 0.18, luma)`) go white and the Sobel contour (tap step 1.5 px) is drawn in near-black. |
| 1 | **Chromatic smear.** Seven taps are taken along the outward direction from `center_x`/`center_y`, spread by `0.0035 + 0.004 * distance`; every tap's Sobel edge (`smoothstep(0.05, 0.16)`) is painted with its own prism stop, so a contour fans out into a rainbow streak. |
| 2 | **Spectral ring.** The luminance picks a band (`floor(clamp((luma - mean) * 2.4 + 0.5) * 6.999)`), the distance from the centre picks a ring (`floor(distance * 9 - time * 6)`), and the two together pick one of the seven spectral stops; the highlights go white and the contour is cut out in near-black. |

The prism colour itself is a seven-stop ramp - red `(1.0, 0.07, 0.1)`, orange `(1.0, 0.42,
0.03)`, yellow `(1.0, 0.88, 0.06)`, green `(0.1, 0.95, 0.28)`, blue `(0.06, 0.48, 1.0)`,
violet `(0.28, 0.14, 1.0)` and magenta `(0.74, 0.16, 1.0)` - swept by the angle around
`center_x`/`center_y`, the distance from it (0.9 per screen height) and the clock (0.7 per
second). In mode 0 the ramp drifts continuously; in mode 2 the rings scroll inwards at 6 per
second.

`mode` is an enum and is clamped to 0..2; `center_x`/`center_y` have no upper limit.

## Example

```json
{
	"type": "spectral",
	"duration": 60,
	"params": { "amount": 1.0, "mode": 1 }
}
```

```
/vfx play vfxweaver:spectral {[mode:2]}
```

## Code

Its built-in datapack definition:

```json
{
	"type": "spectral",
	"duration": 40,
	"easing": "ease_in_out_cubic",
	"persistent": false,
	"loop": false,
	"fade_ticks": 0,
	"params": {
		"amount": { "start": 1.0, "end": 0.0 },
		"mode": 0.0,
		"center_x": 0.5,
		"center_y": 0.5
	}
}
```

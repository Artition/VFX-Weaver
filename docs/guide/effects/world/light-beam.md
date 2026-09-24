# light_beam

<video autoplay loop muted playsinline width="100%"><source src="../../../../assets/media/light_beam.mp4" type="video/mp4"></video>

`type: "light_beam"`

A glowing shaft of soft light emitted from each position along a direction (straight up by default). `top_scale` flares the top: 1 = cylinder, 2 = cone with twice the top radius. `softness` increases the number of concentric shells and fades their alpha: 0 = two hard tubes, higher = many thin, faint shells (a smooth blurred column).

## Fields

> Every world overlay also accepts **`positions`** (static or entity-anchored), **`region`** or the **`pos_x`/`pos_y`/`pos_z`** params, plus the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `radius` | float | 1.5 | Beam radius in blocks (0.1..16) |
| `height` | float | 48 | Column length along the direction, from the anchor (1..256) |
| `dir_x` / `dir_y` / `dir_z` | float | 0 / 1 / 0 | Beam direction, normalised (the default points straight up). A zero vector falls back to up |
| `end_at_x` / `end_at_y` / `end_at_z` | float | unset | A world point the **far end** of the beam must land on. When set, the anchor is derived (`end − axis × height`), so the beam finishes exactly there and `positions` is ignored |
| `top_scale` | float | 1 | Top radius multiplier (0.1..8): 1 = cylinder, >1 = flared cone |
| `softness` | float | 0.6 | More concentric shells with lower alpha (0 = crisp, 2..4 = soft blurry column) |
| `top_fade` | float | 0.4 | Alpha at the top relative to the base (0..1) |
| `bottom_fade` | float | 0 | Alpha at the bottom relative to the base (0..1) |
| `red/green/blue` | float | 1 / 0.95 / 0.75 | Beam colour |
| `through_blocks` | float | 0 | 1 = visible through walls |
| `intensity` | float | 1 (fades to 0) | Beam opacity |

## Example

The direction is an ordinary animatable param, so it can be keyframed, driven by an expression or
bound. Binding it to the camera look aims the beam wherever the viewer is looking:

```json
"params": {
	"dir_x": { "bind": "look_x" },
	"dir_y": { "bind": "look_y" },
	"dir_z": { "bind": "look_z" }
}
```

```json
{
	"type": "light_beam",
	"duration": 60,
	"params": { "radius": 1.5, "height": 48, "top_scale": 1, "softness": 0.6, "top_fade": 0.4, "bottom_fade": 0 }
}
```

**A beam that ends on a chosen point.** `end_at_x`/`end_at_y`/`end_at_z` name the world point the
far end of the beam must land on. The anchor is derived from it (`end − axis × height`), so the
geometry finishes exactly at the point. With the default axis the beam hangs straight down from
`height` blocks above it — a beam falling out of the sky onto the spot:

```json
{
	"type": "light_beam",
	"duration": 80,
	"params": {
		"dir_y": -1.0,
		"height": 60.0,
		"radius": 1.0,
		"end_at_x": 120.5,
		"end_at_y": 64.0,
		"end_at_z": -340.25
	}
}
```

They are ordinary animatable params, so the landing point can be keyframed, driven by an expression
or bound. To run a beam **from A to B**, set `end_at_*` to B and give the axis and length between the
two points (`dir_* = normalize(B − A)`, `height = |B − A|`) — `positions` is not used in that form.
Mind the sign: the beam travels *toward* `end_at`, so a beam coming down from the sky has
`dir_y = -1`.

```
/vfx playat vfxweaver:light_beam 8 70 8 {[radius:2],[top_scale:2],[softness:3]}
```

## Code

```
/vfx play vfx_demos:show_light_beam
```

Its datapack definition:

```json
{
	"type": "light_beam",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"positions": [
		[
			1996,
			100,
			1996
		]
	],
	"params": {
		"red": 1.0,
		"green": 0.95,
		"blue": 0.75,
		"radius": {
			"keyframes": [
				{
					"time": 0,
					"value": 1.0
				},
				{
					"time": 80,
					"value": 1.8
				},
				{
					"time": 160,
					"value": 1.0
				}
			]
		},
		"height": 16.0,
		"softness": 1.2,
		"top_scale": 1.5,
		"top_fade": 0.5,
		"bottom_fade": 0.0,
		"through_blocks": 0.0,
		"intensity": {
			"keyframes": [
				{
					"time": 0,
					"value": 0.25
				},
				{
					"time": 80,
					"value": 0.9
				},
				{
					"time": 160,
					"value": 0.25
				}
			]
		}
	}
}
```

# fog_modifier

`type: "fog_modifier"`

Changes the **vanilla fog** — how near or far it starts and ends, and optionally its colour. It is a
**value modifier at the source of the fog**, not a screen post pass: the mod rewrites the values
vanilla writes into the fog uniforms (`FogEnvironmentalStart`/`End`, `FogRenderDistanceStart`/`End`,
`FogSkyEnd`, `FogCloudsEnd`, `FogColor`) before the frame is drawn. Vanilla terrain, entities and the
mod's own world/entity geometry that sample the fog therefore all see the modified fog.

> **Iris:** under an Iris shaderpack this effect is a **no-op**. A pack computes its own fog and
> ignores the vanilla fog values, so there is nothing for the modifier to change. This is inherent to
> modifying vanilla fog at its source; it is not a bug.

## Fields

> Camera and value effects also accept the [shared definition fields](../index.md#shared-fields)
> (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `fog_start_scale` | float | 1.0 | Multiplier on the fog **start** distance. `1.0` = vanilla, `>1` = fog starts further (see further), `<1` = fog starts closer |
| `fog_end_scale` | float | 1.0 | Multiplier on the fog **end** distance (where the fog is fully opaque). Same meaning as above |
| `fog_r` | float | unset | Optional red channel of a fog **colour** override, `0..1` |
| `fog_g` | float | unset | Optional green channel of a fog colour override, `0..1` |
| `fog_b` | float | unset | Optional blue channel of a fog colour override, `0..1` |
| `fog_color_amount` | float | unset | How far the colour moves from vanilla: `0` = vanilla colour (not touched), `1` = the full authored colour, in between blends. Unset = `1` |

All six are ordinary animatable params: `start`/`end`, `keyframes`, `expr`, graphs, `setParam` and
`bind` all work, and `fade_ticks` fades the effect in and out by its weight. The colour params are
**optional**: a channel that is not authored (`fog_r` absent) leaves the vanilla colour for that
channel unchanged, and when none of the three is authored the vanilla colour is used bit-for-bit.

**Smooth colour transition.** The colour params are all-or-nothing at the effect's own weight — the
only thing that moved the colour was the fade. `fog_color_amount` decouples that: author a constant
colour and keyframe `fog_color_amount` from `0` to `1` to ramp smoothly from the dimension's
**vanilla** fog colour to the authored one (and back). `0` is a true no-op — the colour is not even
written — so an unauthored `fog_color_amount` keeps today's behaviour exactly.

## How effects combine

Several `fog_modifier` effects can overlap. They combine the same way no matter which order they run
in:

- **Scales are additive around neutral.** Each effect contributes `(scale − 1) × weight`, so the
  combined scale is `1 + Σ((scale − 1) × weight)`. Two effects that each pull the fog to `2×` give
  `3×`, not `4×`; an effect at `1.0` adds nothing.
- **Colour is averaged, not applied in sequence.** The authored colours are averaged weighted by each
  effect's weight (scaled by its `fog_color_amount`), then the vanilla colour is blended toward that
  average by the clamped total weight (per channel, clamped to `0..1`). Because the average is a sum,
  the result does not depend on the iteration order. An effect with `fog_color_amount` `0`
  contributes no colour at all and its colour channels are never written; an unauthored amount counts
  as `1`.
- An effect whose `weight` is `0` (faded out) contributes nothing.

The scales are applied to both the **environmental** fog range and the **render-distance** fog range,
which is what makes them visible in normal play (the shader's fog amount is the stronger of the two).

## What is and is not covered

- **Covered:** every distance-based fog on 26.2 / 26.1.2 / 1.21.11 — the atmospheric fog of the
  overworld, **the nether** (`fog_start_distance` 10 / `fog_end_distance` 96) and the End (both use
  the same `FOG_START_DISTANCE`/`FOG_END_DISTANCE` environment attributes), plus water, lava,
  powder-snow and blindness/darkness fog. They all end up in the same four `FogData` distance fields,
  which is the single point the modifier rewrites.
- **Not covered (verified absent):** there is **no density-based fog** on these versions. `FogData`
  has no density field and `EnvironmentAttributes` has no density attribute on any of the three
  nodes, and the dimension definitions express fog as start/end distances. The distance scales are
  therefore the complete control.
- **Sky and cloud fade** (`FogSkyEnd`, `FogCloudsEnd`) scale with `fog_end_scale` like the distance
  ends, so `fog_end_scale < 1` narrows the horizon band and brings the clouds in. They have no start
  value, so `fog_start_scale` never affects them.
- **The mod's own world/entity geometry:** the entity effect pipelines (`entity_tint`,
  `entity_outline`, `entity_displace`) read the same fog uniforms, so they inherit the change. The
  `block_tint`/`block_outline`/`glow`/spark overlays use the `core/position_color` shader, which does
  **not** sample fog at all, so they are unaffected either way.

## Example

```json
{
	"type": "fog_modifier",
	"duration": 80,
	"fade_ticks": 10,
	"params": {
		"fog_start_scale": { "start": 0.35, "end": 1.0 },
		"fog_end_scale": { "start": 0.5, "end": 1.0 }
	}
}
```

Pull the fog in for a claustrophobic beat (both ranges at ~40%), then let it return to vanilla. A
colour override is three extra params:

```json
{
	"type": "fog_modifier",
	"duration": 120,
	"params": { "fog_r": 0.1, "fog_g": 0.3, "fog_b": 0.25 }
}
```

Ramp that colour in smoothly by animating `fog_color_amount` while the colour stays constant — the
fog starts on the dimension's own colour and blends to the green mist over five seconds:

```json
{
	"type": "fog_modifier",
	"duration": 100,
	"params": {
		"fog_r": 0.1,
		"fog_g": 0.3,
		"fog_b": 0.25,
		"fog_color_amount": { "start": 0.0, "end": 1.0 }
	}
}
```

```
/vfx play vfxweaver:fog_modifier {[fog_start_scale:0.4,fog_end_scale:0.5]}
```

## Code

```
/vfx play vfx_demos:show_fog_modifier
```

Its datapack definition (the fog closes in, opens up past vanilla and returns, while a green tint
ramps from vanilla at the midpoint and back to vanilla at the loop ends):

```json
{
	"type": "fog_modifier",
	"duration": 240,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"params": {
		"fog_start_scale": {
			"keyframes": [
				{ "time": 0, "value": 1.0 },
				{ "time": 60, "value": 0.4 },
				{ "time": 120, "value": 1.0 },
				{ "time": 180, "value": 2.0 },
				{ "time": 240, "value": 1.0 }
			]
		},
		"fog_end_scale": {
			"keyframes": [
				{ "time": 0, "value": 1.0 },
				{ "time": 60, "value": 0.4 },
				{ "time": 120, "value": 1.0 },
				{ "time": 180, "value": 2.0 },
				{ "time": 240, "value": 1.0 }
			]
		},
		"fog_r": 0.15,
		"fog_g": 0.35,
		"fog_b": 0.3,
		"fog_color_amount": {
			"keyframes": [
				{ "time": 0, "value": 0.0 },
				{ "time": 60, "value": 0.5 },
				{ "time": 120, "value": 1.0 },
				{ "time": 180, "value": 0.5 },
				{ "time": 240, "value": 0.0 }
			]
		}
	}
}
```

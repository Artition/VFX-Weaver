# sky_pattern

`type: "sky_pattern"`

Paints a figure (or a texture) on the **sky dome**. It is the sky sibling of
[`surface_pattern`](surface-pattern.md) - the same structural `pattern` block, shape library and
texture addressing - but it only ever touches **far-depth sky pixels**, so it can never paint over
terrain, the first-person hand or the GUI. It renders on all supported lines (Fabric and NeoForge)
and **must run at `screen_layer: 0`**, the layer where the scene depth buffer is intact.

## `anchor` - follow the sun, the moon or the stars

By default the pattern sits at the world-fixed dome position you author with
`anchor_yaw`/`anchor_pitch`. The optional top-level **`anchor`** field makes it follow a vanilla sky
body instead:

| `anchor` | The pattern follows | How |
|---|---|---|
| (absent) / `"dome"` | nothing - the literal `anchor_yaw`/`anchor_pitch` (default) | today's behaviour, unchanged |
| `"sun"` | the vanilla **sun** | `anchor_yaw`/`anchor_pitch` are written from the sun's live dome direction |
| `"moon"` | the vanilla **moon** | `anchor_yaw`/`anchor_pitch` are written from the moon's live dome direction |
| `"stars"` | the rotating **star sphere** | the sampled direction is taken into the star sphere's own frame (`star_angle` is the live star angle), so the pattern is a **real** lock, not an axis approximation |

The sun/moon angles are read from the client's own sky render state and converted to the same
`[yaw, pitch]` degrees the `anchor_yaw`/`anchor_pitch` params use, **entirely on the CPU**; the sun
and moon therefore keep writing only the three existing uniforms. The `stars` anchor needs one small
extra step: vanilla draws the star sphere with the pose `Ry(-90°) · Rx(starAngle)` (verified with
`javap` in `SkyRenderer.renderSunMoonAndStars` on 26.2, 26.1.2 and 1.21.11), and the shader takes the
sampled direction into that pose's local frame with the exact inverse, so the pattern is rigidly
attached to the star material as the field rotates. This is the **only** shader path the anchor adds,
and it is gated on the `stars` anchor - `sun`/`moon`/`dome` do not touch it. `anchor` is structural (a
string, like `pattern`/`sky_mode`), so it cannot be keyframed; an unknown value is a parse error
naming the accepted ones, and `anchor` on a non-`sky_pattern` type is also a parse error.

**Why it is Iris-safe.** A shaderpack may draw the sky (and the sun/moon/stars) itself, but the
**world time** - and therefore the body angles - is still computed by the game. Because the anchor is
resolved on the CPU from that state and only writes the existing uniforms (plus the two internal
`stars` fields), the pattern tracks the same body the pack shows. Like every post effect it composes
**over** the composited frame, so it is an **overlay**.

**Fail-closed.** If the sky state cannot be read (no overworld sky - the End or a sky-less
dimension - or the render state is not ready this frame), the effect **contributes nothing** rather
than painting at a guessed spot, and warns once through the logger. It never falls back to the
literal anchor, which could be wrong.

```json
{
	"type": "sky_pattern",
	"duration": -1, "persistent": true, "loop": true,
	"anchor": "sun",
	"params": { "screen_layer": 0, "sky_mode": "patch", "tile_scale": 0.55, "opacity": 0.9,
		"color_r": 1.0, "color_g": 0.85, "color_b": 0.35 },
	"pattern": { "figure": "circle", "fill": "stroke", "radius": 0.42, "stroke_width": 0.05, "softness": 0.02 }
}
```

**Honest limits.**

- **Overlay, not replacement.** The vanilla sun/moon are still drawn underneath, so size the pattern
  to cover them: the vanilla sun is ≈33° apparent width, the moon ≈24°. To have the vanilla body
  cover *your* effect instead, you would need an effect drawn *under* the celestial draws - a
  `SkyRenderer` mixin, deliberately not implemented here (below).
- **`"stars"` positions, it does not recolour.** The vanilla star colour cannot be changed without a
  `SkyRenderer` mixin (the stars are a baked vertex buffer drawn with a brightness uniform). `"stars"`
  locks a pattern to the rotating star sphere; it does not make the vanilla stars red.
- **`"stars"` is a real lock.** Vanilla rotates the star sphere with a fixed pose
  (`Ry(-90°) · Rx(starAngle)`); the shader inverts it exactly, so a `stars` pattern stays put
  relative to the star material as the field turns. This does **not** hijack `dome_rotation`, which
  stays the world-Y author spin described below.

**Compatibility note (deliberately not implemented).** Two parts of the sky design are **not**
shipped because they would be **Iris no-ops** and would need a mixin or a frame-graph change:

- drawing an effect **under** the vanilla sun/moon/stars (the "sky-layer selector", S4b) - a
  `SkyRenderer` mixin; under a pack the vanilla body is not drawn by the game's `SkyRenderer`, so the
  injection would silently do nothing; and
- recolouring the vanilla stars (S6) - the same `SkyRenderer` mixin and the same no-op under a pack.

So a `sky_pattern` **always sits over** the sun and the moon, and the vanilla star colour cannot be
changed. That is a design decision, not a bug.

## `sky_mode` - how the sky is addressed

How a pixel's view ray is turned into a pattern coordinate is chosen by the **`sky_mode`** field.
There are two modes:

| `sky_mode` | Projection | Use it for |
|---|---|---|
| `patch` **(default)** | One **gnomonic** (tangent-plane) decal at the authored anchor - a local chart, no built-in clip | A figure or image at one spot in the sky |
| `fill` | The **whole sphere** by three orthographic charts blended with a sharpened partition of unity ("triplanar on a sphere") | Content that must cover the whole sky (cracks, a full-sky fill) |

**There is no equirectangular mode.** A single global chart of a sphere is topologically unable to
tile it cleanly: `u` is undefined at the poles, so tiling there has infinite frequency and a
whole-sky pattern winds into a **funnel at the zenith** - the worst place, because that is where
players look - and `u` has to wrap somewhere (±180° yaw), leaving a mandatory visible **seam /
mirror axis**. Those are properties of the chart, not formula bugs, and no formula fixes them. The
two modes therefore differ in *which local chart* addresses the ray; neither wraps a global chart.

- **`patch`** projects the ray onto the tangent plane at the anchor and evaluates the figure on the
  resulting cell; `tile_scale` is the cell scale on that plane. The decal is a **flat sign** and has
  **no built-in clip** - it runs out to the tangent-plane horizon, and a pixel behind the decal
  (`dot(dir, anchor) <= 0`) is discarded as a hard backstop. That horizon can read as a straight cut
  across a large patch; if you want a clean edge, restrict the effect with a
  [mask](../../datapack/masks.md). In-plane distortion is `1/cos(angle from the anchor)` (~1.41 at
  45°, ~2 at 60°), so keep `tile_scale <= 1.0` for clean decals.
- **`fill`** tiles each of the three orthographic charts and blends them with a sharpened partition
  of unity (fixed sharpness 8). Within about 40° of a chart pole - the zenith included - a single
  chart has weight > 0.9, so there is **no pole convergence and no seam anywhere**. The charts'
  **coverage and colour** are blended (never their UVs - they are incomparable frames), and the
  colour is renormalised by the blended coverage so texture texels stay saturated inside the
  crossfade ribbons. `repeat` tiling lives inside each chart.

A full skybox **cube** (six authored faces, not a tiling) is a separate future feature; `fill` is
the whole-sky tiling mode today.

The `patch` decal is a **flat sign**, not a whole-sky fill: it is one tangent plane projected onto
the dome, so it does not follow the dome's curvature and its in-plane scale grows toward the rim.
It has **no built-in clip** - add a [mask](../../datapack/masks.md) for a clean edge. Use it for a
figure or image at a spot; use `fill` for anything that must wrap the whole sky.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `screen_layer` | float | 0 | Must be `0` (at layer 1+ the depth buffer no longer covers the scene) |
| `anchor` | string | _(absent)_ | Which vanilla sky body the effect follows: `sun`, `moon`, `stars`, or `dome`/absent for the literal anchor. Structural (not animatable); an unknown value is a parse error, and it is only valid on a `sky_pattern`. See [`anchor`](#anchor-follow-the-sun-the-moon-or-the-stars) |
| `sky_mode` | string | `patch` | Projection: `patch` (gnomonic decal) or `fill` (three-chart whole sphere). Case-insensitive; an unknown value - including the removed `dome` - is a parse error naming the accepted values. Set it explicitly |
| `anchor_yaw` | float | 0 | Dome yaw of the pattern centre, in degrees (yaw `0` = south, `90` = west, `±180` = north). Animatable. In `fill` mode it is a tiling **phase shift**, not a position |
| `anchor_pitch` | float | 0 | Dome pitch of the pattern centre, in degrees (pitch `-90` = straight up, `0` = horizon, `90` = straight down). Animatable. In `fill` mode a tiling **phase shift** |
| `dome_rotation` | float | 0 | Rotates the whole image about the world **Y** axis, in degrees, before it is projected. In `patch` mode it **moves** the decal along its latitude; the in-plane `rotation` spins the figure in place. Animatable. To lock a pattern to the rotating star sphere use `anchor: "stars"`, not this |
| `tile_scale` | float | 1 | Size of one cell. In `patch` mode it is the cell scale on the tangent plane (the decal is a **flat sign** with no built-in clip, so keep it modest - `<= 1.0` for clean decals); in `fill` mode it is the tile half-size in units where 1.0 = 90° from the chart pole. Larger = bigger cell |
| `line_width` | float | — | Numeric override of the structural `pattern.stroke_width` (cell units) |
| `color_r` / `color_g` / `color_b` | float | 1 / 1 / 1 | Pattern colour |
| `opacity` | float | 1 (fades to 0) | Overall strength |
| `distort` | float | 0 | Sine warp of the pattern coordinate (`0` = off) |
| `rotation` | float | — | Numeric override of the structural `pattern.rotation`, in degrees; spins the figure and a texture together |
| `frame` | float | 0 | Sprite-sheet frame index when `pattern.texture.sheet` is set (rounded, wrapped into `0..cols*rows-1`); literal, keyframe, `expr` or graph-driven |
| `texture_tint` | float | 0 | `0` = draw the texture's own RGB; `1` = multiply it by `color_r/g/b`. `opacity` always scales coverage |

The mapping is **world-fixed**. Animating `dome_rotation` spins the sampled direction about world
**Y** before it is projected, so in `patch` mode it **carries the decal along its latitude** - around
the sky. For a true sky-lock use `anchor: "stars"`, which takes the sampled direction into the star
sphere's own frame instead of spinning about Y; the in-plane `rotation` spins the figure in place.
The anchor frame follows the authoring convention (`u`+ = increasing yaw, `v`+ = increasing pitch).

## `pattern` (structural)

The figure lives in a top-level structural `pattern` block - the same shared shape library as
[`surface_pattern`](surface-pattern.md) and [Per-pixel fields](../../datapack/fields.md). All the
figure fields are identical (`figure`, `radius`, `radius_x`/`radius_y`, `half_width`/`half_height`,
`corner_radius`, `sides`, `rotation`, `fill`, `stroke_width`, `softness`, `repeat`); see the
[`surface_pattern` pattern table](surface-pattern.md#pattern-structural) for the full list.

The one difference: a `pattern.center` **world anchor is ignored** by `sky_pattern` - the anchor is
the dome position `anchor_yaw`/`anchor_pitch`, not a point in the world.

## `pattern.texture` (structural, optional)

Replaces the procedural figure with a real texture drawn on the same dome cell. The texture **is**
the figure; an authored `figure` becomes its mask (`coverage = texture channel x shape coverage`).
Every field is the same as [`surface_pattern`](surface-pattern.md#patterntexture-structural-optional)
(`id`, `source`, `atlas`, `channel`, `sheet`, `aspect`); an atlas source (`block`/`item`/`atlas`)
samples the sprite's own sub-rect, a `standalone` source samples a resource-pack texture over
`0..1`. A `sheet` selects a cell and the numeric `frame` param animates it.

## Examples

Nine red dots in one small spot - a `patch` decal (`tile_scale` ~0.3) with a
`circle` figure and `repeat: [3, 3]` (the `repeat` tiles inside the one decal, so the dots stay
together; no texture needed):

```json
{
	"type": "sky_pattern",
	"duration": 120, "persistent": true, "fade_ticks": 20,
	"params": {
		"screen_layer": 0, "sky_mode": "patch", "anchor_pitch": -25.0,
		"tile_scale": 0.3, "opacity": { "start": 0.0, "end": 1.0 },
		"color_r": 1.0, "color_g": 0.0, "color_b": 0.0
	},
	"pattern": {
		"figure": "circle", "radius": 0.12, "fill": "solid", "softness": 0.01, "repeat": [3, 3]
	}
}
```

A texture tiled across the whole sky - `fill` mode, where `repeat` tiles inside each chart:

```json
{
	"type": "sky_pattern",
	"duration": 200, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "sky_mode": "fill", "tile_scale": 1.5, "opacity": 0.9, "color_b": 0.8,
		"rotation": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 200, "value": 360, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [6, 3],
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha" }
	}
}
```

An animated sheet (`frame` keyframes) as a decal:

```json
{
	"type": "sky_pattern",
	"duration": -1, "persistent": true, "loop": true,
	"params": {
		"screen_layer": 0, "sky_mode": "patch", "anchor_pitch": -40.0, "tile_scale": 0.8, "opacity": 0.95,
		"frame": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 120, "value": 15, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [2, 2],
		"texture": { "id": "mypack:textures/vfx/cracks", "source": "standalone", "sheet": [4, 4], "channel": "alpha" }
	}
}
```

To shoot a **beam** from a point on the sky, pair the pixels with
[`light_beam`](../world/light-beam.md) aimed at that dome direction (`dir_x`/`dir_y`/`dir_z`) - the
pixels are a layer-0 post pass, the beam is world geometry.

## Notes

- **Sky only.** The pass is gated on the depth sky test: a non-sky pixel is passed through
  untouched, so a `sky_pattern` can never tint terrain, the hand or the GUI. A pack that leaves no
  trustworthy far depth makes the gate never match - the effect no-ops (fail-closed), never a
  full-screen fill.
- **`patch` limits.** Keep `tile_scale <= 1.0` for clean decals. The decal has **no built-in clip**:
  it is a **flat sign** (one tangent plane), so it does not follow the dome's curvature and its
  in-plane scale grows toward the rim, ending at the tangent-plane horizon (a pixel behind the decal
  horizon is discarded). For a clean edge, restrict the effect with a
  [mask](../../datapack/masks.md). Use `fill` for content that must cover the whole sky.
- **`fill` limits.** The per-chart density varies by up to ~1.7x between a chart pole and a chart
  diagonal, and there are soft crossfade ribbons along the `|x|=|y|`, `|y|=|z|`, `|z|=|x|` great
  circles (roughly ±8°). For chaotic content such as cracks the ribbons read as slightly denser
  cracks, not as a cut or a seam.
- **No equirectangular mode.** A single global chart of a sphere has a pole singularity and a
  mandatory seam - topological, not fixable - so it is not offered. Use `patch` for a figure at a
  spot and `fill` for the whole sky.
- **No depth reconstruction, no normals.** Unlike `surface_pattern` there is no surface selection:
  `normal_mask`, a `surface` block and `fade_radius` do not apply. Restrict the region with a
  [mask](../../datapack/masks.md) (`space: "dome"` or the `sky` leaf) if needed.
- **Textures.** Same resolver as `surface_pattern`: a missing sprite in a valid atlas draws the
  vanilla missing texture and warns once (fail-visible); an unknown atlas or unreadable standalone
  PNG draws nothing and warns once (fail-closed). The sampler is NEAREST with no mipmaps, so a
  pixel texture stays crisp. The texture is re-resolved every frame, so `/reload` takes effect.
- **Parse errors.** The same `pattern`/`pattern.texture` validation as `surface_pattern` (unknown
  key, bad `source`/`channel`/`aspect`/`sheet`, blank or invalid `id`/`atlas`), plus an unknown
  `sky_mode` string (naming the accepted `patch`/`fill`) and an unknown `anchor` value (or `anchor`
  on a non-`sky_pattern` type).

## Code

```
/vfx play vfx_demos:show_sky_pattern
```

A soft stroked ring at a spot in the sky (`patch`), pulsing in size and brightness. It **stays at
its spot**: the animation is `tile_scale` and `opacity` only - animating `dome_rotation` would carry
the decal along its latitude, around the sky. Its datapack definition:

```json
{
	"type": "sky_pattern",
	"duration": 200, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 12,
	"params": {
		"screen_layer": 0,
		"sky_mode": "patch", "anchor_yaw": 0.0, "anchor_pitch": -30.0,
		"tile_scale": { "keyframes": [ { "time": 0, "value": 0.4 }, { "time": 100, "value": 0.55 }, { "time": 200, "value": 0.4 } ] },
		"color_r": 0.35, "color_g": 0.85, "color_b": 1.0,
		"opacity": { "keyframes": [ { "time": 0, "value": 0.4 }, { "time": 100, "value": 0.9 }, { "time": 200, "value": 0.4 } ] }
	},
	"pattern": {
		"figure": "ellipse", "fill": "stroke", "radius_x": 0.42, "radius_y": 0.42,
		"stroke_width": 0.03, "softness": 0.03, "repeat": [1, 1]
	}
}
```

## Variants

### `show_sky_pattern_texture`

```
/vfx play vfx_demos:show_sky_pattern_texture
```

A vanilla block sprite tiled across the whole sky (`fill`):

```json
{
	"type": "sky_pattern",
	"duration": 200, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 12,
	"params": {
		"screen_layer": 0, "sky_mode": "fill", "anchor_yaw": 0.0, "anchor_pitch": 0.0,
		"tile_scale": { "keyframes": [ { "time": 0, "value": 0.7 }, { "time": 100, "value": 1.0 }, { "time": 200, "value": 0.7 } ] },
		"opacity": 0.9, "distort": 0.0,
		"rotation": { "keyframes": [ { "time": 0, "value": 0.0 }, { "time": 200, "value": 360.0, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [4, 2],
		"texture": { "id": "minecraft:block/nether_portal", "source": "block", "channel": "alpha", "aspect": "preserve" }
	}
}
```

### `sky_cracks`

```
/vfx play vfx_demos:sky_cracks
```

The whole-sky case: a `fill` with an integer `repeat` and an animated sprite sheet, so the cracks
cover the entire sky with no pole funnel:

```json
{
	"type": "sky_pattern",
	"duration": 120, "easing": "linear", "persistent": true, "loop": true, "fade_ticks": 10,
	"params": {
		"screen_layer": 0, "sky_mode": "fill", "tile_scale": 1.5, "opacity": 0.95,
		"color_r": 0.9, "color_g": 0.9, "color_b": 1.0, "texture_tint": 1.0,
		"frame": { "keyframes": [ { "time": 0, "value": 0 }, { "time": 120, "value": 15, "easing": "linear" } ] }
	},
	"pattern": {
		"repeat": [6, 3],
		"texture": { "id": "minecraft:block/cracked_stone_bricks", "source": "block", "sheet": [4, 4], "channel": "luminance", "aspect": "preserve" }
	}
}
```

### `sky_anchor_sun`, `sky_anchor_moon`, `sky_anchor_stars`

```
/vfx play vfx_demos:sky_anchor_sun
/vfx play vfx_demos:sky_anchor_moon
/vfx play vfx_demos:sky_anchor_stars
```

A glowing ring that tracks the **sun** through the day (`anchor: "sun"`, a `patch`):

```json
{
	"type": "sky_pattern",
	"duration": -1, "persistent": true, "loop": true, "fade_ticks": 12,
	"anchor": "sun",
	"params": { "screen_layer": 0, "sky_mode": "patch", "tile_scale": 0.55, "opacity": 0.9,
		"color_r": 1.0, "color_g": 0.85, "color_b": 0.35 },
	"pattern": { "figure": "circle", "fill": "stroke", "radius": 0.42, "stroke_width": 0.05, "softness": 0.02 }
}
```

The same ring on the **moon** (`anchor: "moon"`), and a whole-sky texture locked to the **stars**
(`anchor: "stars"`, a `fill` whose sampled direction is taken into the star sphere's own frame):

```json
{
	"type": "sky_pattern",
	"duration": -1, "persistent": true, "loop": true, "fade_ticks": 12,
	"anchor": "stars",
	"params": { "screen_layer": 0, "sky_mode": "fill", "tile_scale": 1.6, "opacity": 0.55,
		"color_r": 0.5, "color_g": 0.7, "color_b": 1.0, "texture_tint": 1.0 },
	"pattern": { "repeat": [6, 3], "texture": { "id": "minecraft:block/cracked_stone_bricks",
		"source": "block", "sheet": [4, 4], "channel": "luminance", "aspect": "preserve" } }
}
```

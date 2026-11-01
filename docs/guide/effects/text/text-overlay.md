# text_overlay

`type: "text_overlay"`

Puts authored text on the game frame or into the world. In `world` mode every character is its own
client-side `TextDisplay` standing where you put the block; in `flat` mode the same laid-out block is
drawn onto the HUD with the game's own font. The line can be revealed character by character and then
leave - by fading in place, or by coming apart and falling.

**Not verified in game.** Nothing on this page has been looked at with eyes yet: the layout, the
orientation of the block in the world, the fall and the flat layer are all unverified. Treat the
numbers below as the code's behaviour, not as a promise about the pixels.

## The `text` block

The string and the shape of the block are **structural**, so they live in a top-level `text` object
(next to `type`, never inside `params`):

| Field | Type | Default | Meaning |
|---|---|---|---|
| `content` | string | — (required) | The line(s) to draw. `\n` is a hard break; blank content is rejected |
| `mode` | string | `flat` | `flat` draws the block on the HUD, `world` puts the characters in the world. Selects the render path, so it cannot animate |
| `font` | string | `default` | The font name, stored as given. The renderer currently always draws with the game's default font, so any other value changes nothing (custom fonts are not supported yet) |
| `align` | string | `center` | Horizontal placement of each line inside the block: `left`, `center`, `right` |
| `anchor` | string | `center` | Which point of the block sits on the anchor position: `top_left`, `top`, `top_right`, `left`, `center`, `right`, `bottom_left`, `bottom`, `bottom_right` |

**The `text` block cannot be set at play time.** `/vfx play`, the trigger payload and `setParam` carry
the effect id plus *numbers only* - every param is a float - so there is no way to send a string
later. A definition is the only place the words are authored; to change them, edit the JSON and
`/reload`. (Dynamic text such as a player name or a scoreboard value is a separate design, not this
effect.)

## Params

Everything else is an **ordinary param**, so it takes the whole [param spec](../../datapack/params.md)
surface - constant, `start`/`end`, `keyframes`, `bind`, `expr`, the `/vfx play` param-map and live
`setParam`:

| Param | Default | Animatable | Meaning |
|---|---|---|---|
| `x` | 0.5 | yes (`flat` only) | Anchor X as a fraction of the GUI width |
| `y` | 0.5 | yes (`flat` only) | Anchor Y as a fraction of the GUI height |
| `pos_x` | 0 | yes (`world` only) | Block origin, world X |
| `pos_y` | 0 | yes (`world` only) | Block origin, world Y |
| `pos_z` | 0 | yes (`world` only) | Block origin, world Z |
| `scale` | 1 | yes | Size of the whole block: `flat` multiplies the font's own pixel size, `world` scales the characters in blocks. The layout is measured at unit scale, so animating it never respawns anything |
| `line_height` | 1.2 | sampled, see below | Line spacing as a multiple of `scale` |
| `wrap_width` | 0 | sampled, see below | Soft wrap: a line breaks at a space once the next word would pass this width (in the block's own units). `0` = only `\n` breaks |
| `alpha` | 1 | **not read yet** | Would be an overall opacity multiplier; the renderer does not read it - use `reveal` and the effect's own fade |
| `color_r` | 1 | yes | Base colour, red (each channel is clamped `0..1`) |
| `color_g` | 1 | yes | Base colour, green |
| `color_b` | 1 | yes | Base colour, blue |
| `gradient` | 0 | **not read yet** | Would ramp the line from `color_*` to `gradient_*` |
| `gradient_r` | 1 | **not read yet** | Second colour of that ramp, red |
| `gradient_g` | 0.6 | **not read yet** | Second colour of that ramp, green |
| `gradient_b` | 0.3 | **not read yet** | Second colour of that ramp, blue |
| `shadow` | 1 | yes | `>= 0.5` draws the drop shadow under the glyphs, `< 0.5` draws them flat |
| `outline` | 0 | **not read yet** | Would be the outline width |
| `outline_r` | 0 | **not read yet** | Outline colour, red |
| `outline_g` | 0 | **not read yet** | Outline colour, green |
| `outline_b` | 0 | **not read yet** | Outline colour, blue |
| `reveal` | 1 | yes | Fraction of the characters that are shown, `0..1`: character `i` is opaque while `i < reveal * count`, and the one character at the boundary is caught mid-fade, so a keyframe from `0` to `1` is a typewriter |
| `per_char_anim` | 0 | **not read yet** | Would pick an animation for characters as they appear (`0` none, `1` rise, `2` wave, `3` tilt) |
| `stagger` | 0 | **not read yet** | Would be the delay between characters for `per_char_anim` |
| `exit` | 0 | yes | Float code, clamped `0..1`: `< 0.5` fades in place, `>= 0.5` falls |
| `exit_at` | -1 | yes | Seconds from the start of the effect when the **first** character starts leaving. Negative = the text never leaves on its own and stays until the effect is stopped |
| `exit_stagger` | 0 | yes | Seconds between one character leaving and the next one |
| `exit_speed` | 0 | yes | Initial downward speed of a leaving character, blocks/s, multiplied by a per-character jitter in `0.85..1.15` |
| `gravity` | 18 | yes | Downward acceleration, blocks/s². Floored at `1e-3` so it cannot divide by zero, **no upper limit** |
| `spin_x` | 0 | yes | Angular velocity of a leaving character, degrees/s; the vector `(spin_x, spin_y, spin_z)` is an axis and its length the speed |
| `spin_y` | 0 | yes | Same, Y component of the spin axis |
| `spin_z` | 0 | yes | Same, Z component of the spin axis |
| `tumble` | 0 | yes | `>= 0.5` takes the spin axis from the per-character jitter instead, so the letters tumble off different axes while keeping `|spin|` as the speed. Clamped `0..1` |
| `collision` | 1 | yes | `>= 0.5` lets a falling character stop on a surface, `< 0.5` lets it fall straight through. Clamped `0..1` |
| `bounce` | 0.15 | yes | Restitution of the single rebound after landing, `0..1`. `0` lands and stays down |
| `friction` | 0.4 | **not read yet** | Would be the tangential damping on impact; the fall is vertical only, so there is nothing to damp |
| `fade_out` | 0.5 | yes | Seconds a character takes to fade once it has left (fade exit) or once it has come to rest (fall exit). Floored at `1e-4` |
| `rot_x` | 0 | yes | Baseline orientation of the whole block, degrees |
| `rot_y` | 0 | yes | Baseline orientation of the whole block, degrees, Y |
| `rot_z` | 0 | yes | Baseline orientation of the whole block, degrees, Z |
| `angle` | 0 | yes (`flat` only) | Turns the whole block about its anchor, degrees |

Notes on the numbers:

- **`wrap_width` and `line_height` are sampled when the block is laid out** - at spawn in `world`
  mode, on the first measurement in `flat` mode, where the measured layout is then cached per effect
  instance. Animating either of them mid-flight changes nothing until the block is rebuilt; every
  other param is read again every frame.
- **No upper limits on the motion.** `gravity`, `exit_speed`, `spin_*`, `scale`, `pos_*` and the
  delays accept any value: a huge `gravity` or `exit_speed` just makes the drop instant, a huge
  `spin_*` makes the letter a blur. Only the lower guards that avoid degenerate maths are applied
  (`gravity >= 1e-3`, `fade_out >= 1e-4`), plus the `0..1` fractions listed in the table.
- **The block is laid out by the mod, not by Minecraft.** `\n` always breaks; with `wrap_width` set,
  the block also breaks at spaces. Each character gets its own rest position, which is what makes the
  per-character reveal and the per-character fall possible.
- **`world` mode is one entity per character.** A block longer than 256 characters keeps its leading
  characters and drops the tail, with one warning per definition. `flat` mode draws no entities at all.
- **Opacity is three things multiplied**: `reveal` (per character), the exit fade (per character) and
  the effect's own weight - so `duration`/`fade_ticks` fade the whole block in and out on top of
  everything else, and stopping the effect never pops the text.
- **A finished block comes back.** Once every character has faded out its entities are released; when
  the effect loops or is replayed the block is rebuilt from the definition.
- **This is not a screen effect.** There is no `screen_layer` and no `order` - nothing is composited
  over the frame; `flat` text is drawn on the HUD after the post passes. `positions` and `region` are
  not read either: `world` mode uses `pos_x`/`pos_y`/`pos_z`.
- **A `text` block that is missing renders nothing, and says so once.** The definition is rejected at
  parse time when `text` or `text.content` is absent or blank, and an effect that reaches the renderer
  without a block warns once through the log.

## The two exits

`exit` picks one of two ways for the characters to leave, and it is a float code: `0` is a **fade**,
`1` is a **fall**.

- **Fade (`exit: 0`).** Character `i` starts fading at `exit_at + i * exit_stagger` seconds and is gone
  `fade_out` seconds later. Nothing moves; only the opacity falls.
- **Fall (`exit: 1`).** The same schedule detaches the characters, but they also move: `exit_speed`
  downwards, accelerated by `gravity`, spin from `spin_x`/`spin_y`/`spin_z` (with `tumble` mixing the
  axis per character). A falling character stays opaque until it comes to rest and fades over
  `fade_out` after that; with nothing to land on it fades from the moment it detaches, so a block
  always reaches zero and its characters are always released.

`collision` is the same switch in both modes; only the surface differs:

- **3D (`world`) collision is the world.** With `collision: 1` a character stops on the top of the
  first block below its rest position, rebounds once by `bounce` and settles. The floor is looked up
  once per character when the block spawns with one block-collision query, so a moving `pos_*` does
  not re-probe it. `collision: 0` skips the query and the letters fall straight through the ground.
- **2D (`flat`) collision is the frame.** The picture *is* the world there: the edges of the game
  window are the floor and the walls, and `collision: 0` lets a character leave the picture and fade
  where it went.

That second half is the design, not the current code: **the `flat` path does not run the exit yet.**
Today `flat` mode draws the block with `reveal`, `color_*` and `shadow` only, so `exit`, `exit_at`,
`exit_stagger`, `exit_speed`, `gravity`, `spin_*`, `tumble`, `collision`, `bounce` and `fade_out` do
nothing there. Both exits are a `world`-mode feature.

## Example

```json
{
	"type": "text_overlay",
	"duration": 160,
	"persistent": true,
	"text": {
		"content": "First frame\nbuilt by a datapack",
		"mode": "world",
		"align": "center",
		"anchor": "center"
	},
	"params": {
		"pos_x": 100.5,
		"pos_y": 72.0,
		"pos_z": 200.5,
		"scale": 2.0,
		"reveal": { "start": 0.0, "end": 1.0 },
		"exit_at": 5.0,
		"exit_stagger": 0.04,
		"exit": 1.0,
		"collision": 1.0
	}
}
```

```
/vfx play vfx_demos:my_text_overlay
```

Author it in **your own** namespace (`vfx_demos:` above) - a pack that writes `vfxweaver:<name>`
would shadow a built-in rather than add one.

## Code

```
/vfx play vfx_demos:show_text_overlay
```

Its datapack definition:

```json
{
	"type": "text_overlay",
	"duration": 160,
	"easing": "ease_in_out_cubic",
	"persistent": true,
	"loop": true,
	"fade_ticks": 12,
	"text": {
		"content": "VFX WEAVER",
		"mode": "world",
		"align": "center",
		"anchor": "center"
	},
	"params": {
		"pos_x": 2000.0,
		"pos_y": 102.5,
		"pos_z": 2001.0,
		"scale": 1.5,
		"color_r": 1.0,
		"color_g": 0.92,
		"color_b": 0.62,
		"reveal": { "keyframes": [{ "time": 0, "value": 0.0 }, { "time": 40, "value": 1.0 }, { "time": 160, "value": 1.0 }] },
		"exit_at": 5.0,
		"exit_stagger": 0.04,
		"exit": 1.0,
		"spin_x": 90.0,
		"spin_z": 30.0,
		"collision": 1.0,
		"bounce": 0.2,
		"fade_out": 0.4
	}
}
```

The demo types the title in over the first two seconds, holds it, then drops the letters onto the
platform at `y 100` where they land, hop and fade, and the loop starts again.

## See also

- [Effects index](../index.md) - the shared fields and how to read an effect page.
- [Datapack format](../../datapack/format.md) - definition fields and structural blocks.
- [Animating a param](../../datapack/params.md) - keyframes, `start`/`end`, `expr`, `bind`.
- [screen_image](../screen/screen-image.md) - the other page with a structural block instead of
  animatable params.

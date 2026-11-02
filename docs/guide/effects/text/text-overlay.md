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
| `split` | string | `char` | What one **unit** is - one display entity in `world`, one animated step in both modes: `char` is one per character, `word` one per word (a space only advances the cursor, it is never a unit of its own), `block` one for the whole text, newlines included. Every animation - reveal, scramble, exit schedule, fall, spin and the flat drift - runs **per unit**, so a coarse split reveals, decodes, drops and spins whole words or the whole block instead of letters. Structural: it decides how many entities the block becomes, so it cannot animate |
| `font` | string | `default` | Only the game's own font exists. The name is lower-cased and must be `default` or `minecraft:default`; **any other value is rejected at parse time**, so the definition fails to load instead of drawing something else (a resource-pack font is not supported yet) |
| `align` | string | `center` | Horizontal placement of the **whole block**, shifted by the width of its **widest line**: `left` leaves it where it was laid out, `center` moves it left by half that width, `right` by the whole width. Lines are not aligned against each other |
| `anchor` | string | `center` | Which point of the block sits on the anchor position, read **vertically only**: `top`/`top_left`/`top_right` put the top of the block on it, `left`/`center`/`right` its middle, `bottom`/`bottom_left`/`bottom_right` its bottom |

**`align` and `anchor` are not a nine-way placement grid.** The renderer reads only the vertical part
of `anchor` (the top names shift by `0`, the middle names by `-height / 2`, the bottom names by
`-height`), so its three horizontal variants are the same placement: **`top_left` draws exactly like
`top_right`, and `left`, `center` and `right` all draw the same**. The sideways half of the name is
simply not read - where the block sits horizontally comes from `align` alone, and `align` shifts the
whole block by the width of the widest line rather than aligning each line by its own width.

**`split` changes what a step of the animation is, and what an entity is.** With the default `char`
the block decodes, wipes and falls letter by letter; `word` makes every word one piece, so its
letters appear, scramble and leave together; `block` makes the whole text one piece, so the reveal is
all-or-nothing and the block falls or fades as a single body. In `char` and `word` the layout does
not change - the characters are still measured and placed by the mod, so a line reads the same in
both settings - only the granularity of the animation does. In `world` mode a `block` unit is one
display entity holding the content with its newlines, handed `wrap_width` as the display's own line
width (vanilla's default of 200 font pixels when wrapping is off), so **vanilla breaks the lines
itself**, including an over-long word, which it splits mid-word. The unit is measured with the same
`Font.split` call at that same width, so its line count matches what the display draws, and `flat`
draws those same lines - a `block` therefore wraps identically in both modes, and its height (the
`anchor`) matches the drawn block. A unit is also pinned to its own left edge
in the display's style flags (`FLAG_ALIGN_LEFT`), so vanilla draws it from the point the mod placed it
at instead of centring it on that point and drifting it left by half its width: every `word` and
`block` unit, a one-letter word included (its narrow glyph would otherwise sit half a cell left of its
slot), and a multi-glyph unit in `char` mode. A single character in `char` mode fills its own cell and
needs no bit.

**`split` trades animation detail for entities.** `world` mode spawns **one entity per unit**, so
`word` and `block` cut the entity count of a long line instead of adding a feature: a 30-word line is
30 entities in `word` mode and one in `block`. The glyph cap does not move with it - `MAX_CHARS` (256)
still bounds the characters that are drawn at all, so in `char` and `word` a longer text keeps its
leading characters and drops the tail. A unit is spawned whole or not at all, so a `block` longer
than that is dropped entirely, and the warning names whichever of the two happened. `flat` mode
spawns nothing; there the split only decides how the block animates.

**The block is structural, and at play time only `content` can be replaced.** `mode`, `font`, `split`,
`align` and `anchor` are layout decisions that always come from the file; the words can be chosen per
call, through the optional `text "<string>"` argument described below. Every *param* is still a
number, so `setParam` and the param map never carry a string, and there is still no way to change a
line while it is running.

## Setting the text at play time

`/vfx play`, `/vfx playat` and `/vfx playentity` take an optional `text "<string>"` argument that
replaces the definition's `content` for that one call:

```
/vfx play       <effect> text "<string>" [<params>] [<targets>]
/vfx playat     <effect> <pos> text "<string>" [<params>] [<targets>]
/vfx playentity <effect> text "<string>" [<targets>] [<players>]
```

```
/vfx play vfx_demos:notice text "The gate is closed" @a
```

What it does and does not do:

- **Only `content` changes.** `mode`, `font`, `split`, `align` and `anchor` still come from the
  definition, so one definition keeps its layout and gets a different sentence per call.
- **It is per target, not broadcast.** The trigger payload is already sent per player, so two calls
  can show two players different strings - or the same call can carry one string to all of them.
- **An empty string means "not given".** A blank override is dropped rather than applied, so
  `text ""` cannot blank a definition by accident.
- **Two caps, in series.** The wire carries at most 512 characters (`MAX_TEXT_OVERRIDE`); longer input
  is cut there with one warning. The renderer's own 256-glyph cap still applies afterwards, so a
  512-character override still draws at most 256 characters.
- **A definition with no `text` block ignores the override**, with one warning per effect id: there is
  no `mode` to draw in, and inventing a default would hide an authoring mistake. The same is true,
  silently, of a non-text effect that receives one.
- **It is read once, when the instance is created.** A running line cannot tick from "3" to "2"; live
  text would need a new packet action and a pool key that tolerates a changing string.
- **One line only.** The command language has no escape for a newline, so an override is a single
  line; multi-line text stays in the definition's `content`.
- **Nothing else moves.** `reveal`, `exit`, `exit_at`, `scale`, `pos_*` and the rest are params and
  keep animating exactly as before.

### Dynamic text comes from macros, not from this mod

Once the string is an argument, the *server* chooses it - and vanilla already knows how to compose
command arguments, so a scoreboard value or a player name needs nothing from this effect:

```
# ns:notice.mcfunction
$vfx play my_ns:notice text "$(msg)" @a

# anywhere else, per player
execute store result storage ns:args kills int 1 run scoreboard players get @s kills
data modify storage ns:args msg set value "$(player) closed the gate"
function ns:notice with storage ns:args
```

That is why the effect has no `%score:kills%` template language of its own: it would be a second,
worse version of datapack macros. Two things stay out of reach, and are worth saying plainly: a
string that changes **while** the effect runs, and **client-only** data - what the player is looking
at, their FOV, their own GUI scale - which the server cannot see and therefore cannot put into an
argument.

## Params

Everything else is an **ordinary param**, so it takes the whole [param spec](../../datapack/params.md)
surface - constant, `start`/`end`, `keyframes`, `bind`, `expr`, the `/vfx play` param-map and live
`setParam`:

| Param | Default | Animatable | Meaning |
|---|---|---|---|
| `x` | 0.5 | yes (`flat` only) | Anchor X as a fraction of the GUI width |
| `y` | 0.5 | yes (`flat` only) | Anchor Y as a fraction of the GUI height |
| `pos_x` | 0 | yes (`world` only) | Block origin, world X - the absolute fallback, ignored when the effect has a slot (see [Where the block stands](#where-the-block-stands)) |
| `pos_y` | 0 | yes (`world` only) | Block origin, world Y, measured the same way |
| `pos_z` | 0 | yes (`world` only) | Block origin, world Z, measured the same way |
| `scale` | 1 | yes | Size of the whole block, and it multiplies **both** the glyph and the spacing between characters, so the ratio of a line is kept at every value. In `world` mode the block is laid out in font pixels and one font pixel is about a **twentieth of a block** (vanilla's text-display renderer scales a glyph by `0.025` before drawing it), so `scale: 1` gives letters a few tenths of a block tall and a dozen-character line roughly a block wide. `flat` multiplies the laid-out offsets by it and then, per character, pushes a pose, translates it to the glyph's centre, rotates it, calls `pose.scale(scale, scale)` and draws the glyph at 1.0 inside it - so the 2D block is a scaled pose like any other, not a larger font size. The layout is measured at unit scale, so animating `scale` never respawns anything |
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
| `reveal` | 1 | yes | Fraction of the **units** that are shown, `0..1`: unit `i` is opaque while `i < reveal * count`, and the one unit at the boundary is caught mid-fade, so a keyframe from `0` to `1` is a typewriter in `char` mode and a word-by-word (or all-at-once) wipe otherwise |
| `scramble` | 0 | yes | Intensity of the decode, clamped `0..1`: at `0` every frame is the frame it would be without it, and any value above `0` makes the characters inside the two scramble windows (see [The scramble transitions](#the-scramble-transitions)) draw a noise glyph instead of their own, at that opacity |
| `scramble_rate` | 15 | yes | Noise swaps per second. The swap number is `floor(age * scramble_rate)`, so the glyph changes at most that often; floored at `0`, **no upper limit** (a huge rate swaps on every frame) |
| `scramble_band` | 4 | yes | How many **units** **ahead of** the `reveal` frontier show noise, with the noise fading out towards the far end of the band. `0` turns the reveal half off, **no upper limit** (a huge band scrambles the whole tail of the line) |
| `scramble_time` | 0.35 | yes | Seconds **before** a unit detaches during which it shows noise instead of its own glyph, so the exit reads as a decode. Floored at `0`, **no upper limit** (a long window just scrambles longer) |
| `per_char_anim` | 0 | **not read yet** | Would pick an animation for characters as they appear (`0` none, `1` rise, `2` wave, `3` tilt) |
| `stagger` | 0 | **not read yet** | Would be the delay between characters for `per_char_anim` |
| `exit` | 0 | yes | Float code, clamped `0..1`: `< 0.5` fades in place, `>= 0.5` falls |
| `exit_at` | -1 | yes | Seconds from the start of the effect when the **first** unit starts leaving. Negative = the text never leaves on its own and stays until the effect is stopped |
| `exit_stagger` | 0 | yes | Seconds between one unit leaving and the next one |
| `exit_speed` | 0 | yes | Initial downward speed of a leaving character, multiplied by a per-character jitter in `0.85..1.15`: blocks/s in `world`, screen heights/s in `flat`, so the same value moves the block the same fraction of the frame at any GUI scale |
| `spread` | 0.6 | yes (`world` only) | Horizontal scatter of a falling character, in blocks/s: each character gets its own sideways velocity on X and on Z, picked from a hash of the instance id and its index, so the letters of one line fan out instead of dropping straight down. **No upper limit** - a large value simply scatters them further |
| `gravity` | 18 | yes | Downward acceleration: blocks/s² in `world`, screen heights/s² in `flat`. Floored at `1e-3` so it cannot divide by zero, and each `world` character multiplies it by its own factor in `0.85..1.15`, so letters do not fall in lockstep. **No upper limit** |
| `spin_x` | 0 | yes (`world` only) | Angular velocity of a leaving character, degrees/s; the vector `(spin_x, spin_y, spin_z)` is an axis and its length the speed, which is multiplied by the same per-character jitter so letters do not rotate together |
| `spin_y` | 0 | yes (`world` only) | Same, Y component of the spin axis |
| `spin_z` | 0 | yes | Same, Z component of the spin axis. Also the only spin a `flat` character can turn by: a 2D pose rotates about Z |
| `tumble` | 0 | yes | `>= 0.5` takes the spin axis from the per-character jitter instead, so the letters tumble off different axes while keeping `|spin|` as the speed; in `flat` it flips the direction of `spin_z` per character instead. Clamped `0..1` |
| `collision` | 1 | yes | `>= 0.5` lets a falling character stop on a surface, `< 0.5` lets it fall straight through. Clamped `0..1` |
| `bounce` | 0.15 | yes (`world` only) | Restitution of the single rebound after landing, `0..1`. `0` lands and stays down. `flat` has no rebound: a character that reaches the bottom edge holds there and fades |
| `friction` | 0.4 | **not read yet** | Would be the tangential damping on impact; the fall is vertical only, so there is nothing to damp |
| `fade_out` | 0.5 | yes | Seconds a character takes to fade once it has left (fade exit) or once it has come to rest (fall exit). Floored at `1e-4` |
| `rot_x` | 0 | yes | Baseline orientation of the whole block, degrees. With `rot_x`, `rot_y` and `rot_z` all at zero the block is a **billboard facing the camera**, so it is readable from any side; setting any of the three pins it in world orientation |
| `rot_y` | 0 | yes | Baseline orientation of the whole block, degrees, Y. It takes part in the billboard rule above: any non-zero value pins the block in the world |
| `rot_z` | 0 | yes (`world` only) | Baseline orientation of the whole block, degrees, Z. `flat` turns about Z with `angle` and `spin_z` instead, so this is not read there. It is part of the billboard rule in `world`: only while all three are zero does the block face the camera |
| `angle` | 0 | yes (`flat` only) | Turns the whole block about its anchor, degrees |

Notes on the numbers:

- **`wrap_width` and `line_height` are sampled when the block is laid out** - at spawn in `world`
  mode, on the first measurement in `flat` mode, where the measured layout is then cached per effect
  instance. Animating either of them mid-flight changes nothing until the block is rebuilt; every
  other param is read again every frame.
- **No upper limits on the motion.** `gravity`, `exit_speed`, `spread`, `spin_*`, `scale`, `pos_*` and the
  delays accept any value: a huge `gravity` or `exit_speed` just makes the drop instant, a huge
  `spin_*` makes the letter a blur. Only the lower guards that avoid degenerate maths are applied
  (`gravity >= 1e-3`, `fade_out >= 1e-4`), plus the `0..1` fractions listed in the table. The three
  scramble shapes follow the same rule: `scramble_rate`, `scramble_band` and `scramble_time` have only
  floors (`>= 0`) and no ceiling, and `scramble` itself is the one bounded value, an intensity read
  as `0..1`.
- **The block is laid out by the mod, not by Minecraft.** `\n` always breaks; with `wrap_width` set,
  the block also breaks at spaces. Each character gets its own rest position, which is what makes the
  per-unit reveal and the per-unit fall possible. The one exception is a `block` unit in `world` mode:
  the whole text is one display entity and vanilla lays it out at that width.
- **`world` mode is one entity per unit.** `split` decides the count: `char` is one per character,
  `word` one per word, `block` one for the whole text. A block longer than 256 characters keeps its
  leading characters and drops the tail, with one warning per definition - the cap is on glyphs, not
  on entities, so it holds in every split. `flat` mode draws no entities at all.
- **Opacity is three things multiplied**: `reveal` (per unit), the exit fade (per unit) and
  the effect's own weight - so `duration`/`fade_ticks` fade the whole block in and out on top of
  everything else, and stopping the effect never pops the text.
- **Only `world` has a finished state.** Once every unit has faded out, its entities are
  released; when the effect loops or is replayed the block is rebuilt from the definition. `flat` has
  no finished state at all: it draws from a layout cached per instance and keeps drawing - with
  nothing visible - until the effect stops being active, which is when `VFXClientRenderHooks` prunes
  that cache entry.
- **This is not a screen effect.** There is no `screen_layer` and no `order` - nothing is composited
  over the frame; `flat` text is drawn on the HUD after the post passes. `region` is not read.
- **A `text` block that is missing renders nothing, and says so once.** The definition is rejected at
  parse time when `text` or `text.content` is absent or blank, and an effect that reaches the renderer
  without a block warns once through the log.

### Where the block stands

`world` mode hangs the block from the effect's **first world slot**: a runtime move position wins,
otherwise the first `positions` entry, otherwise the first entity anchor resolved to where that entity
is right now. When a slot is present, that slot **is** the origin and `pos_x`/`pos_y`/`pos_z` are
**ignored** - a played-at position wins over the authored coordinates instead of adding to them, which
is the rule every other world effect follows - and only the **first** slot is used (one pool of
characters per slot is not implemented; extra slots are ignored). A definition with **no** slot (no
move, no `positions`, no anchor) keeps the old **absolute** coordinates, so nothing that already
exists moves. An entity anchor whose entity is untracked this frame (dead, out of range, not
spawned yet) resolves to no slot, and the block draws nothing that frame, exactly like every other
world effect.

`flat` mode does not read slots or `pos_*`: it is placed by `x`/`y` on the frame.

## The two exits

`exit` picks one of two ways for the units to leave, and it is a float code: `0` is a **fade**, `1` is
a **fall**.

- **Fade (`exit: 0`).** Unit `i` starts fading at `exit_at + i * exit_stagger` seconds and is gone
  `fade_out` seconds later. Nothing moves; only the opacity falls.
- **Fall (`exit: 1`).** The same schedule detaches the units, but they also move: `exit_speed`
  downwards plus a sideways `spread`, accelerated by `gravity`, spin from `spin_x`/`spin_y`/`spin_z`
  (with `tumble` mixing the axis per unit). Every character jitters its own speed, its own `gravity`
  in `0.85..1.15`, its own scatter direction and its own spin speed, all drawn from a hash of the
  instance id and its index, so no two letters fall the same way and the same tick always looks the
  same. A falling unit stays opaque until it comes to rest and fades over
  `fade_out` after that; with nothing to land on it fades from the moment it detaches, so a block
  always reaches zero - and in `world` mode its entities are released there (`flat` has no entities
  to release).

`collision` is the same switch in both modes; only the surface differs:

- **3D (`world`) collision is the world.** With `collision: 1` a unit stops on the top of the
  first block below its rest position, rebounds once by `bounce` and settles - **on** the surface,
  not inside it: the entity position is the bottom of the glyph, because a display draws its glyph
  upward from there, so a settled unit's position is the floor itself and needs no lift. Each unit
  probes its **own column**, on the frame it detaches, walking down from its rest Y in quarter
  blocks with the same block-collision query, so a `char` line over uneven ground stops letter by
  letter on the step under it rather than on one floor taken for the whole line, while a `word` or
  `block` unit lands as one piece. A sideways `spread` can carry a letter over a different column as
  it falls; it still lands on the floor of the column it detached over, a deliberate simplification
  that keeps the fall closed-form. Two rules come with
  that: a unit whose rest point already sits inside a collider is embedded in terrain and does not
  fall at all, and a column with no surface within 64 blocks falls free, exactly as with no floor.
  `collision: 0` skips the probe and the text falls straight through the ground.
- **2D (`flat`) collision is the frame.** The picture *is* the world there: the edges of the game
  window are the floor and the walls, and `collision: 0` lets a unit leave the picture and fade
  where it went.

That second half is the code, not only the design: **both exits run in both modes, on one timeline.**
`flat` reads the same `exit_at` + `index * exit_stagger` schedule, the same `exit` code and the same
`fade_out`; what differs is the space the motion is measured in. A `flat` fall moves the unit in
**screen heights** - `exit_speed` is screen heights per second and `gravity` screen heights per second
squared, multiplied by the GUI height - so GUI scale 2 and GUI scale 4 put the text in the same place
at the same moment. The sideways drift a leaving unit gets from the per-unit jitter is folded back
inside the frame width, so a unit bounces off the left and right edges, and one that reaches the
bottom edge stops there and fades: a `flat` landing does not rebound, so `bounce` is a `world`-only
param. `flat` spins a unit about its own centre, which a 2D pose can only do about Z, so it reads
`spin_z` alone (with `tumble` flipping its direction per unit instead of moving its axis), and the
block's own turn comes from `angle` - `rot_x`, `rot_y` and `rot_z` stay `world`-only. `exit_stagger` and
`collision` mean the same thing in both modes.

## The scramble transitions

`scramble` above `0` turns both transitions into a **decode**: instead of appearing and disappearing
as themselves, the units pass through noise. Four params, and only `scramble` is a plain switch
with an intensity - the other three shape the two windows it opens.

- **The reveal band.** Unit `i` is `i - reveal * count` units ahead of the reveal frontier.
  While it is fewer than `scramble_band` units ahead, it draws a noise glyph, and the noise fades
  out towards the far end of the band - full at the frontier, nothing at `scramble_band`. So the tail
  of the line reads as machine noise that decodes as the wipe sweeps over it, and the moment the
  frontier passes a unit it snaps back to itself. The band is computed from `reveal` alone; no
  history is kept, so seeking to the middle of the wipe lands on the frame that wipe has.
- **The exit window.** For the `scramble_time` seconds before a unit's own detach time
  (`exit_at + index * exit_stagger`), it shows noise instead of its own text, at the full `scramble`
  opacity, and then leaves by whichever exit is set - still falling or fading as itself, so the last
  thing you read before it goes is a scrap of noise rather than the text.

**The noise is a fixed pool**: `A-Z 0-9` plus `#$%&*+=-<>/?` - no lowercase, so a decode reads as
machine output rather than as text that happens to be wrong. The glyph is a hash of the instance id,
the unit index and the swap number, and the swap number is the clock read as
`floor(age * scramble_rate)`: the same tick therefore always draws the same glyph, so a replay
matches the first run and a screenshot can be compared against it frame for frame. Nothing is
accumulated - both windows and the glyph are pure functions of the clock and the params.

It works on **both transitions** (a revealed line decodes in, a leaving unit decodes out) and in
**both modes**. The `world` path rewrites a display's text only when the text it should show actually
changed, so a decode costs what a standing line costs; the `flat` path draws one noise glyph in the
unit's own box - so a swap never shifts it sideways, and a whole word or block decodes as one piece -
and costs no more than a plain frame either. A unit that is scrambled replaces **both** its text and
its opacity: the band units are visible at the noise's opacity instead of waiting at zero, which
is why the tail lights up as the sweep reaches it.

With `scramble` at `0` the effect is exactly what it was before, so a definition that does not ask for
a decode is unaffected.

```json
{
	"params": {
		"reveal": { "start": 0.0, "end": 1.0 },
		"scramble": 1.0,
		"scramble_rate": 15.0,
		"scramble_band": 4.0,
		"exit_at": 3.0,
		"exit_stagger": 0.04,
		"scramble_time": 0.4,
		"exit": 1.0
	}
}
```

## Example

```json
{
	"type": "text_overlay",
	"duration": 160,
	"persistent": true,
	"text": {
		"content": "First frame\nbuilt by a datapack",
		"mode": "world",
		"split": "char",
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
		"split": "char",
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
		"scramble": 1.0,
		"scramble_rate": 15.0,
		"scramble_band": 4.0,
		"scramble_time": 0.4,
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

The demo decodes the title in over the first two seconds - the characters ahead of the reveal frontier
show noise for `scramble_band` places - holds it, then scrambles each letter for `scramble_time`
before it detaches, drops it onto the platform at `y 100` where it lands, hops and fades, and the loop
starts again.

## See also

- [Effects index](../index.md) - the shared fields and how to read an effect page.
- [Datapack format](../../datapack/format.md) - definition fields and structural blocks.
- [Animating a param](../../datapack/params.md) - keyframes, `start`/`end`, `expr`, `bind`.
- [screen_image](../screen/screen-image.md) - the other page with a structural block instead of
  animatable params.

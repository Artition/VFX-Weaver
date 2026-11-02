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
| `brightness` | 15 | yes (`world` only) | Light the glyphs are drawn in, the block-display convention: `-1` = the **world light** at the text's own position, so the line darkens in a shadow like a block; `0..15` = an override of **both** block and sky light at that level (packed as a `Brightness` of `(level, level)`), so `15` is full-bright and `0` is nearly black. Floored at `-1` and clamped to `15` because that is the light-level range itself, not a cap on an authored value. Re-applied per frame, but written to the entities only when the level changed, so it animates |
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
| `exit_speed` | 0 | yes | Initial **upward** speed of a leaving character, multiplied by a per-character jitter in `0.85..1.15`: blocks/s in `world`, screen heights/s in `flat`, so the same value moves the block the same fraction of the frame at any GUI scale. Gravity brings the letter back; a negative value throws it down instead |
| `spread` | 0.6 | yes (`world` only) | Horizontal scatter of a falling unit, in blocks/s, picked from a hash of the instance id and its index so the letters of one line fan out instead of dropping straight down. The cheap fall is a 2D model, so it reads the **X** draw and drops the Z one (`flat` still folds both, since its drift is folded into the frame). It seeds the body's velocity when the unit detaches; the air friction `friction` bleeds it off from there. **No upper limit** - a large value simply scatters them further |
| `gravity` | 18 | yes | Downward acceleration added to every falling body on every tick: blocks/s² in `world`, screen heights/s² in `flat`. Read in the block-particle unit (a multiple of its per-tick gravity step, i.e. `400 * GRAVITY_STEP` per second squared), so the two engines agree on what `18` means. The `flat` fall applies that reading scaled by a fixed **1/8** - a screen stands in for roughly eight of the few-block drops the world number is tuned for - so the default `18` takes about **0.8 s** from the line to the bottom edge and the letter visibly speeds up on the way; `gravity` is still the only knob, so doubling it halves the duration. Floored at `1e-3` so it cannot divide by zero. **No upper limit** - a huge value just makes the drop instant |
| `spin_x` | 0 | yes (`world` only) | Angular velocity of a leaving character, degrees/s: the vector `(spin_x, spin_y, spin_z)` is an axis whose **length is the speed**, multiplied by the same per-character jitter so letters do not rotate together. The cheap body has one angle, so the axis only supplies the speed - the glyph always turns about **its own plane normal**, which keeps it readable instead of edge-on. The horizontal drift adds its own roll on top (see [The two exits](#the-two-exits)) |
| `spin_y` | 0 | yes (`world` only) | Same, Y component - it contributes to the **speed** only |
| `spin_z` | 0 | yes | Same, Z component. Also the authored seed for a `flat` character, which turns about Z only - its rate is this seed plus the roll of the sideways drift over the glyph's half-height, the same rule the `world` body uses (see [The two exits](#the-two-exits)) |
| `tumble` | 0 | yes | `>= 0.5` takes the **direction** of the roll from the per-character jitter, so the letters turn opposite ways instead of as one sheet, while `|spin|` stays the speed - in `world` and in `flat` alike. The axis is no longer per-character: the cheap body has a single angle about the glyph's own plane normal, so there is one axis for all of them. Clamped `0..1` |
| `collision` | 1 | yes | `>= 0.5` lets a falling character stop on a surface, `< 0.5` lets it fall straight through. In `world` this is the **one raycast** taken when the unit detaches: with it on, the ground is found once and the unit lands on it; with it off, there is no floor and the unit keeps falling. Clamped `0..1` |
| `bounce` | 0.15 | yes (`world` only) | Restitution of the **one** rebound the landing gets, `0..1`: the vertical velocity is reflected and scaled by it on the first touch only, so `0` lands dead, `0.15` gives a small hop, and `1` reflects it in full - which with `friction` at `1` is a body that never settles and is only ended by the release guarantees. `flat` has no rebound: its closed-form fall stops at the bottom edge, holds for `hold` and fades |
| `friction` | 0.94 | yes | Per-tick air drag **and** the share of the horizontal drift kept on a contact, `0..1`, `1` = no drag - the block-particle default is the same `0.94`. It is what makes a landing glyph stop skidding so the body can be declared resting, and set too close to `1` a drifting unit slides for ever, never settles and so never starts its fade. Clamped `0..1` because it is a fraction, not a size. `flat` does not read it: the closed-form fall has no drag |
| `hold` | 0 | yes | Seconds a unit stays **fully opaque after it comes to rest**, before its fade starts: the tick the body reports **resting** plus `hold` is when the opacity begins to fall. A duration, so **no upper limit**; floored at `0`, because a negative value would run the fade backwards. In `flat` the rest is the bottom edge instead. It is the fall exit's wait - a fade exit has no rest to hold on |
| `fade_out` | 0.5 | yes | Seconds the fade takes once it has started. **`0` means never fade**: the character stays fully opaque until the effect stops instead of vanishing at the instant it settles (or detaches). Above `0` the division is floored at `1e-4`, so a tiny non-zero value is a very fast fade and only `0` is read as "keep it" |
| `rot_x` | 0 | yes | Baseline orientation of the whole block, degrees. This is the `billboard: 0` (auto) rule: with `rot_x`, `rot_y` and `rot_z` all at zero the block is a **billboard facing the camera**, so it is readable from any side; setting any of the three pins it in world orientation |
| `rot_y` | 0 | yes | Baseline orientation of the whole block, degrees, Y. It takes part in the billboard rule above: any non-zero value pins the block in the world |
| `rot_z` | 0 | yes (`world` only) | Baseline orientation of the whole block, degrees, Z. `flat` turns about Z with `angle` and `spin_z` instead, so this is not read there. It is part of the `billboard: 0` auto rule in `world`: only while all three are zero does the block face the camera |
| `angle` | 0 | yes (`flat` only) | Turns the whole block about its anchor, degrees |
| `billboard` | 0 | sampled, see below (`world` only) | Float code for how the block follows the camera, mapped straight onto the display's billboard constraint: `0` = **auto**, `1` = **fixed**, `2` = **center**, `3` = **vertical**, `4` = **horizontal**. `0` is the rule this effect always used - see [What the block faces, and how it is lit](#what-the-block-faces-and-how-it-is-lit). Read once per display at spawn, so changing it needs a respawn |
| `two_sided` | 1 | sampled, see below (`world` only) | `>= 0.5` (the default) spawns a **second display per unit that is the mirror image of the first**: its origin is the original position plus the block rotation applied to `(unitWidth, 0, 0)` - one advance for a character, the measured width for a word, the widest line for a block - it carries **the same block rotation as the original**, and its transformation scale has a **negative X** (`-scale, scale, scale`), so its text runs from the far edge back to the original origin. That scale mirrors the glyph and flips the triangle winding, so the copy covers exactly the same rectangle and is drawn only from behind: the **back of the sign**, the letter reversed, never a readable second copy - and not through blocks, because the plane is drawn once per side. `< 0.5` spawns no twin at all, a strictly one-sided decal. Used only when the resolved `billboard` is not `center`, because a `center` billboard already faces you from every side - see [Showing the back of a pinned block](#showing-the-back-of-a-pinned-block). Read at spawn; each twin counts against the 256-display cap and is released with its unit |
| `through_blocks` | 0 | yes (`world` only) | `>= 0.5` ORs vanilla's `FLAG_SEE_THROUGH` into every unit's flags, so the **same quads** are drawn from both sides - the physical back of a letter, a mirror image rather than a readable second copy. It is **off by default** and stays as the option for text that should be seen through walls: see-through is exactly vanilla's x-ray mode, so the text is then **drawn through terrain** - a line behind a hill is visible through it. `< 0.5` (the default) draws each quad on its one side, and the back of a pinned block comes from its `two_sided` twin instead |

Notes on the numbers:

- **`wrap_width` and `line_height` are sampled when the block is laid out** - at spawn in `world`
  mode, on the first measurement in `flat` mode, where the measured layout is then cached per effect
  instance. `billboard` and `two_sided` are sampled the same way, at spawn, because a display's
  billboard constraint and the number of entities belong to the block rather than to a frame.
  Animating any of them mid-flight changes nothing until the block is rebuilt; every other param -
  `through_blocks` and `shadow` included - is read again every frame.
- **No upper limits on the motion.** `gravity`, `exit_speed`, `spread`, `spin_*`, `scale`, `pos_*` and the
  delays accept any value: a huge `gravity` or `exit_speed` just makes the drop instant, a huge
  `spin_*` makes the letter a blur, a huge `hold` just keeps the letters standing. Only the lower
  guards that avoid degenerate maths are applied (`gravity >= 1e-3`, `hold >= 0`, and the `1e-4` floor
  on the division when `fade_out` is above `0`), plus the `0..1` fractions listed in the table. The three
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
  on entities, so it holds in every split. A two-sided block (the default in a pinned mode) spends
  **two** display entities per unit, and those twins count against the 256-entity cap too. `flat`
  mode draws no entities at all.
- **Opacity is three things multiplied**: `reveal` (per unit), the exit fade (per unit) and
  the effect's own weight - so `duration`/`fade_ticks` fade the whole block in and out on top of
  everything else, and stopping the effect never pops the text.
- **Only `world` has a finished state.** Once every unit has faded out, its entities are
  released; when the effect loops or is replayed the block is rebuilt from the definition. A block
  with `fade_out: 0` never gets there and leaks nothing: its characters are released when the effect
  stops being active or the level changes, and a looping effect reuses them. `flat` has
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

### What the block faces, and how it is lit

`billboard` turns the orientation rule into a choice instead of a consequence of `rot_*`. It is the
float code of the display's own billboard constraint, and it is read **once per display**, so changing
it changes nothing until the block is rebuilt:

- **`0` (default) is auto**, and it is the rule this effect has always used: with `rot_x`, `rot_y` and
  `rot_z` all at zero the block **faces the camera**; setting **any** of the three pins it in world
  orientation. So the common case - a title you have not rotated - stays readable from every side, and
  the moment you author a rotation you get exactly that rotation with no surprise turn.
- **`1` is fixed**: the block never turns, and `rot_*` still rotates it. Use it to say "world
  orientation" with no rotation at all.
- **`2` is center**: the block always turns to face you, but `rot_*` still rotates its glyphs about
  the anchor - the whole quad follows the camera, the line inside it keeps your rotation.
- **`3` is vertical**: it turns about Y to face you but never tilts, so the line stays upright and
  reads from any horizontal direction.
- **`4` is horizontal**: it stays level in the world's plane and tilts with your pitch, the way text
  lying on the floor reads when you look down at it.

`brightness` decides the light the glyphs are drawn in, with exactly the block-particle convention:
**`-1` means the world light** at the text's own position, so the line darkens in a shadow the way a
block-display does, and **`0..15` overrides both the block and the sky light** with that level - the
light is packed as `Brightness(level, level)`, so `15` is full-bright and `0` is nearly black. The
default is `15`, because a title is usually meant to be readable in a dark room; set `-1` when the
text should belong to the place it stands in. It is re-applied every frame and written to the
entities only when the level changes, so a keyframed `brightness` pulses without touching a standing
line.

Both params are **`world` only**: `flat` text is drawn in GUI space, where the camera and the world
light do not exist.

### Showing the back of a pinned block

A text display draws its glyphs on a **single-sided** quad, so a block pinned in world orientation is
invisible from behind. The default is a **two-sided sign**: two displays per unit, the second a
mirrored copy of the first - from behind the letters read reversed, like the back of a physical sign.

- **`two_sided: 1` (the default): a mirrored copy behind every unit.** Every unit of a block whose
  resolved `billboard` is not `center` spawns a **twin** carrying the same text, flags, alignment,
  brightness and rotation. The twin is **glued**, not placed beside the original: a unit's text runs
  from its origin along the block's local **+X** by its own width - the advance for a character, the
  measured width for a word, the widest line for a block - so the twin's origin is *the original
  position plus the block rotation applied to `(unitWidth, 0, 0)`*. Its rotation is **the same block
  rotation the original gets**, and the mirror comes from its transformation scale, **`(-scale, scale,
  scale)`**: a negative X scale has a **negative determinant**, which mirrors the glyph along X and
  flips the triangle winding. The flipped winding is what makes the copy **culled from the front and
  drawn only from behind**, and the shifted origin is what makes the mirrored quad, which now runs
  backwards from where it stands, cover **exactly the same rectangle** as the original. So a viewer in
  front reads the original, and a viewer behind reads the same letters **reversed** - the back of a
  physical sign, a mirror image, never a readable second front. Nothing is drawn through terrain: each
  plane is drawn on its one side, so a block behind a hill is hidden by it, like any other world
  geometry. A single-character unit is the one case that needs no shift, and the maths says the
  special case still holds under a mirror: vanilla draws that quad centred, spanning
  `[-width/2, +width/2]` about its origin, and a mirror about that same origin maps the interval onto
  itself - the twin lands on the identical cell.
- **The pair is one object.** Both copies are driven by **exactly the same rotation each frame** -
  block rotation, then spin, then tumble - and share the exit displacement, the floor, the opacity, the
  brightness and the release, so a falling or tumbling pair never separates and neither copy outlives
  the other. They are also written by the same change detection: a standing line writes neither copy and
  a decode touches both where it touched one. The cost is honest and stated: a pinned block spends
  **two display entities per unit**, so it reaches the 256-display cap at half the units and the
  warning says `two_sided` is why. It is skipped when the resolved `billboard` is `center`, because a
  centre billboard already turns its quad to face the camera from every side and a twin would only
  double the entities for nothing.
- **`two_sided: 0`: a one-sided decal.** One display per unit, visible from the front, gone from
  behind. The cheapest state, and the one to pick for a block that is meant to have a front.
- **`through_blocks: 1` (off by default): vanilla's x-ray mode, kept as an option.** The flag is one bit
  of vanilla's display style, ORed in next to the shadow bit, so the renderer submits each unit's
  quads from **both sides** - the physical back of the same quad, a mirror image with the word running
  right to left - at any rotation, including while the block tumbles. No second entity is spawned. It
  is **not** a substitute for `two_sided`: this is vanilla's see-through mode, and that mode also draws
  the text **through terrain**, so a line standing behind a hill is still visible through it, and
  nothing in the mod separates the two. Use it for text meant to be seen through walls, with
  `two_sided: 0` when the twin is not wanted as well.

## The two exits

`exit` picks one of two ways for the units to leave, and it is a float code: `0` is a **fade**, `1` is
a **fall**.

- **Fade (`exit: 0`).** Unit `i` starts fading at `exit_at + i * exit_stagger` seconds and is gone
  `fade_out` seconds later. Nothing moves; only the opacity falls. There is no rest in a fade, so
  `hold` is not read by it.
- **Fall (`exit: 1`).** The same schedule detaches the units, and a detached unit stops being an
  offset from the clock and becomes a **body**: two offsets, two velocities, an angle and an angular
  velocity, the state a 2D character-physics pass (MusicSubtitles' `SubtitleBubble$CharPhysics`)
  keeps, lifted into the world. It is seeded with the rest pose the letter stood in, with its
  velocity (`exit_speed` plus a sideways `spread`) and its spin (`spin_x`/`spin_y`/`spin_z` as a
  length, `tumble` picking the direction per unit) and stepped **once per client tick**: gravity is
  added to the vertical velocity, `friction` multiplies **both** velocities, the offsets are
  integrated by them, and the angle advances by the roll - which is the authored spin plus
  `drift / radius` radians per block travelled, so a letter rolls over its own half-height the way
  the drift would roll it, capped so a hard throw cannot spin it absurdly. The displays are drawn at
  the interpolation between the last two ticks, so 20 Hz physics still looks smooth.

  The only thing the body knows about the world is the **Y it lands on**, and it learns it **once**,
  when the unit detaches: one downward `level.clip` raycast (32 blocks, collider blocks only - a
  plant or a torch is not a floor) from where the letter stood. When the glyph reaches that Y it
  touches down, the vertical velocity is reflected once and scaled by `bounce`, the horizontal drift
  is damped by `friction`, and the roll stops - a letter lying on the ground does not keep turning.
  The next contact, the one after that single rebound, **pins** the body: from there it gets no
  gravity and no vertical integration at all and simply holds the floor, so a landed letter lies
  still instead of being pushed a tick's worth of fall below the surface and snapped back up every
  frame. It rests **two centimetres above** the surface it hit (`0.02` blocks) rather than in it:
  landing exactly in the surface plane would put the glyph coplanar with the block's top face and
  make it shimmer with z-fighting. The body then comes to rest **on its own**: after four consecutive
  ticks under the rest thresholds its motion is zeroed and its pose frozen, and whatever angle it has
  is the final one.

  **The honest limit of that model**: the letter lands on the ground **under the spot it detached
  over** and never looks at the terrain again. It cannot tip over an edge, slide down a slope, be
  stopped by a wall, bounce off a step or tumble into a gap - a letter dropped just past the lip of
  a cliff still lands at the cliff's own height, and one that drifts over a hole lands on the column
  it started on. That is the trade for a fall that costs six floats and one raycast per unit instead
  of a world query for every corner on every tick, with up to 256 units in a block. Everything else
  still holds: the scatter, the spin, the single rebound, the air drag and the four-tick rest.

**Opacity has three stages, and only three:**

- **Opaque while it falls.** A unit in the air is drawn at full opacity, however long the drop takes.
- **`hold` seconds at full opacity once it has come to rest.** The clock for it starts at the tick the
  body reports **resting** - the letter has already stopped by then - so `hold: 2` is two seconds of
  a crisp, opaque glyph lying where it fell. `0` (the default) starts the fade on that tick.
- **Then `fade_out` seconds of fade.** Unit opacity is
  `1 - clamp01((since - rest - hold) / fade_out)`, so a larger value is a longer, softer dissolve.

A body that can never land is still released, not left hanging: with `collision` on but a fall out of
the world, `bounce: 1` or a `friction` almost at `1`, the body never rests, so the same `hold` and
`fade_out` ramp starts from the first of two moments - it fell **below the level's own minimum build
height** (plus an eight-block margin), where there is no surface left to catch it, or **ten seconds**
have passed since it detached without it coming to rest. Both are release guarantees, not gameplay
limits: a real fall, however long, is still inside the build range and lands long before either
fires. The block always reaches zero and is released; no unit stays spawned and fully opaque until
someone stops the effect.

**`exit_at` and `exit_stagger` are the *when*, and they are separate from all of that.** Unit `i`
detaches at `exit_at + i * exit_stagger`, so a stagger above `0` takes the line apart letter by letter
instead of all at once. `exit_at` is `-1` by default, which means the block never leaves on its own
and stands until the effect is stopped.

`fade_out: 0` is the fourth state, and the one for letters that must not go anywhere: it means
**never fade**, not "vanish instantly". The unit stays fully opaque until the effect stops, so a fall
with `hold` and `fade_out: 0` is a pile of letters that simply stays. Nothing leaks: a `world` block
that never reaches zero keeps its display entities and they are released when the effect stops being
active or the level changes, and a looping effect reuses them, `since` turning negative again and the
standing line coming back at full opacity. A non-zero `fade_out` still always reaches zero, and a
fully faded `world` block releases its entities right there (`flat` has no entities to release).

**A free fall never comes to rest.** With nothing to land on - `collision: 0`, or a drop into open
air - the body is never declared resting, so `hold` never starts and the fade begins the moment the
unit detaches: `fade_out` decides whether it disappears on the way down. Unless `fade_out` is `0`:
then there is no fade at all and the letter keeps falling, fully opaque, until the effect stops.

`collision` is the same switch in both modes; only the surface differs:

- **3D (`world`) collision is one raycast under the letter.** With `collision: 1` the unit's
  detach does a single downward `level.clip` against the level's **collider** blocks, 32 blocks
  deep, and that Y is the floor it lands on. It costs one raycast per unit, once, and nothing per
  tick - which is what makes a 256-unit block affordable. The price is stated in [The two
  exits](#the-two-exits): the glyph lands on the ground under the column it detached over, so it
  does not follow a slope, does not tip over an edge and cannot be stopped by a wall. A unit whose
  column has nothing within 32 blocks has **no floor**: it keeps falling, and the release guarantees
  below end it. `collision: 0` skips the raycast entirely and the text falls straight through the
  ground.
- **2D (`flat`) collision is the frame.** The picture *is* the world there: the edges of the game
  window are the floor and the walls, and `collision: 0` lets a unit leave the picture and fade
  where it went.

That second half is the code, not only the design: **both exits run in both modes, and the fade
schedule is one timeline.** `flat` reads the same `exit_at` + `index * exit_stagger` schedule, the same
`exit` code, the same `hold` and the same `fade_out`; what differs is the space the motion is measured
in. A `flat` fall moves the unit in **screen heights** - `exit_speed` is screen heights per second and
`gravity` screen heights per second squared, multiplied by the GUI height - so GUI scale 2 and GUI
scale 4 put the text in the same place at the same moment. The acceleration is the authored `gravity`
scaled by `1/8` (`SCREEN_GRAVITY_SCALE`): the world number is tuned for a few-block drop, one screen
stands in for roughly eight of those, and read unscaled it crossed the frame in about a third of a
second - no acceleration to see. So the default `18` accelerates at `2.25` screen heights per second
squared, the default fall from the line to the bottom edge takes about **0.8 s**, and the letter
visibly speeds up on the way down; `gravity` stays the only knob, so doubling it halves that time.
The HUD's y grows **downwards**, so the
offset is written positive-down: `s(t) = (gravity / 8 * t² / 2 - exit_speed * t) * GUI height`, which
means a **positive `exit_speed` still launches the letter upward first** and it falls back from there.
The impact time `t* = (v + sqrt(v² + 2 * (gravity / 8) * d)) / (gravity / 8)` - `v` the jittered
`exit_speed`, `d` the distance to the bottom edge in screen heights - solves the same quadratic, so
`s(t*)` is exactly the drop onto the bottom edge: the letter **lands there instead of jumping to
it**, flush on the frame edge (there is no surface to z-fight with, so the `world` half's landing lift
has no counterpart here). The sideways drift
a leaving unit gets from the per-unit jitter is folded back inside the frame width, so a unit bounces
off the left and right edges, and **both that drift and the rotation freeze at `t*`**: a unit that
reaches the bottom edge stands still, holds for `hold` and fades where it landed. A `flat` landing
does not rebound, so `bounce` is a `world`-only param. `flat` turns a unit about its own centre - the
only axis a 2D pose has - and turns it the way the `world` body rolls: a seed from `spin_z` (signed
per unit by `tumble`, in radians per tick) **plus the sideways speed over the glyph's own
half-height**, capped at the same `0.5` rad/tick the body uses. So a letter thrown sideways spins
faster, and neither a hard drift nor a tall frame can spin a glyph absurdly. `rot_x`, `rot_y` and
`rot_z` stay `world`-only, the block's own turn comes from `angle`, and `exit_stagger` and `collision`
mean the same thing in both modes.

**`flat` keeps the closed-form timeline, and that is deliberate.** A screen has no geometry to hit, so
a 2D unit needs no body: its fall is still solved as an offset from the clock - `gravity / 8 * t² / 2 -
exit_speed * t` in screen heights, folded against the frame - so the same tick always yields the same
frame and there is no state to accumulate.

**The one thing the `world` fall is not: a closed form of the clock.** Its state is accumulated per
tick, so a replay from the effect's start matches the first run frame for frame - the same seeds,
the same floor, the same order - while jumping into the middle of a run does not reproduce the same
arrangement, because the motion that decided where each glyph stopped never happened. That is the
trade the block particles make too, and it is stated here rather than hidden: the fade, the reveal,
the scramble and the whole `flat` path stay pure functions of the clock.

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

Tuning it without editing the file - every param can be overridden at play time. The param map is
`{[name:value],[name:value],...}`: square brackets around each pair, a comma between pairs, all of it
inside braces:

```
/vfx play vfx_demos:show_text_overlay {[scale:8],[spread:1],[billboard:1],[through_blocks:1],[fade_out:0]}
/vfx play vfx_demos:show_text_overlay {[scale:8],[hold:2],[fade_out:2]}
/vfx play vfx_demos:show_text_overlay {[scale:8],[scramble:1],[scramble_rate:8],[scramble_band:6]}
/vfx playat vfx_demos:show_text_overlay 100 66 200 text "Area 2" {[scale:12],[brightness:15]}
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

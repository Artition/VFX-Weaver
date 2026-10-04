# window_create and window_control

Two effects that open and drive **separate OS windows**: `window_create` opens a named picture
window and holds it for its own lifetime, `window_control` moves, animates and retitles that window
by name. They are not post-processing passes and not world geometry - nothing about the Minecraft
window changes unless the window `id` is the reserved `"0"`, which drives the Minecraft window itself
(see [the reserved id](#the-reserved-id-0-the-minecraft-window-itself) below).

`type: "window_create"` / `type: "window_control"`

## What you actually get

A `window_create` opens a **frameless, transparent, click-through picture window** over the desktop:

- **No frame, ever.** The window is undecorated and non-resizable: no title bar, no border, no OS
  drag or maximize. There is no field to turn a frame on - the picture is the whole feature.
- **Clicks pass through.** The window is `GLFW_MOUSE_PASSTHROUGH` at all times, so the game (and
  everything else under it) keeps receiving input. It is not a field and cannot be turned off: a
  floating full-screen window that swallowed clicks would block the desktop.
- **Focus stays with the game.** A window that took focus when it appeared would alt-tab the player
  out of Minecraft the moment an effect started; focus-on-show is disabled and the window is never
  focused.
- **Always on top**, so the picture sits over the game rather than behind it.
- **Transparent background** (the picture composites over whatever is under it) where the platform
  supports it; the fallback is an opaque black canvas, which is a no-op you cannot switch off.

## The canvas

The window is created **hidden, sized to the whole work area of the monitor the game window is on**
and shown last; **it is never moved or resized afterwards**. Everything you animate - position,
size - is the picture's rectangle *inside* that canvas, so animation costs no OS call per frame and
cannot stutter. Two consequences worth knowing:

- **The monitor is pinned at creation.** `pos_*`/`size_*` always mean fractions of the work area of
  the monitor the game window was on when the window opened. Drag the game to another monitor or
  change the taskbar, and the window keeps its original canvas (stop and replay the effect to
  re-pin it).
- **`size = 1` is "fullscreen".** There is no OS fullscreen path: filling the canvas is what
  fullscreen means here.

On **Wayland** the compositor owns window placement and sizing, so the canvas may not cover the
area you expect and absolute `pos_*` positioning is best-effort. Windows-primary is the supported
path.

## Fields

`window_create` and `window_control` take three **top-level** fields (next to `type`, never inside
`params`), because they are strings and the window's identity does not animate:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | — (required) | The window's name - the key `window_control` addresses it by. Non-blank; anything may be used (`"hud"`, `"vfx:hud"`) except the reserved `"0"`, which drives the Minecraft window |
| `texture` | string | — | Resource id of the picture (`"vfx_demos:window/pic"`), resolved from the client's resource manager, so a resource pack can supply it. A missing `.png` suffix is added. Omit it for a window with no picture (only a title). A mod may instead supply the picture from code with `VFXAPI.registerImage(id, …)` and reference that same id here (see the [API](../../../API.md#vfxapi)) |
| `titles` | array of strings | `[]` | Candidate OS titles; `title_index` picks one. At most 64, every entry a string |

Everything else is an **ordinary param**, so it takes the whole
[param spec](../../datapack/params.md) surface - constant, `start`/`end`, `keyframes`, `bind`, `expr`,
the `/vfx play` param-map and live `setParam`:

| Param | Type | Default | Meaning |
|---|---|---|---|
| `pos_x` | float | 0 | Left edge, 0..1 of the **free** work-area space (see below) |
| `pos_y` | float | 0 | Top edge, 0..1 of the free work-area space |
| `size_w` | float | 1 | Picture width, 0..1 of the work area |
| `size_h` | float | 1 | Picture height, 0..1 of the work area |
| `opacity` | float | 1 | OS window opacity, 0..1 - animate it for a fade |
| `frames` | float | 1 | How many frames the picture is split into, at least 1. Read **once, when the window opens**; `1` = a still image |
| `frame_time` | float | 0 | Ticks per frame. `0` (the default) means no stepping - the picture holds frame 0 |
| `title_index` | float | 0 | Which entry of `titles` the OS title shows |

Notes on the numbers, because the defaults are not the intuitive ones:

- **`pos_*` is relative to the free space, not to the work area.** `size_*` is applied first, then
  `pos_*` is mapped over what is left: `x = workArea.x + pos_x * max(0, workArea.w - width)`. So
  `pos_x: 1` puts the picture flush against the right edge whatever its width, and `pos_x: 0.5`
  centres it. `pos_*` and `size_*` are clamped into `0..1`, so an overshooting keyframe cannot push
  the picture off the canvas.
- **The picture keeps its own aspect ratio.** `size_w`/`size_h` are a bounding box, not a stretch:
  the picture is fitted (contained) inside that box at its own pixel aspect and centred, so a square
  icon stays square on a 16:9 monitor and nothing is ever stretched. A caller that wants a specific
  on-screen size therefore only needs one dimension to be exact; the other follows the picture (and
  the sheet cell, when `frames` splits a strip).
- **`frames` splits the image into a horizontal strip.** The sheet is read row-major with frame 0
  top-left, which for a strip means the image is split into `frames` equal columns left to right. The
  PNG must therefore be at least `frames` pixels wide; a narrower one is refused with a one-time
  warning and the window keeps its last good picture. `frames` is read at creation, so animating it
  does nothing - change it and replay the effect. Any `frame_index` the animation produces is wrapped,
  so it never draws outside the sheet.
- **`frame_time` is in ticks** (20 ticks = 1 s), so `frame_time: 4` is 5 fps and `2` is 10 fps. It is
  independent of the effect's `duration` and `easing`: it counts real elapsed ticks.
- **`title_index` is rounded and clamped.** `0.6` picks entry 1, a negative index picks the first
  title and an index past the end picks the last, so an out-of-range value never fails - keyframes can
  step through the list without a bounds check. The index is read from the `window_create` and every
  live `window_control` for that name, in effect order.

## Window names, and who owns them

- **The window lives while its `window_create` is active.** A `duration` effect closes it at expiry, a
  `persistent`/`loop` one keeps it until `/vfx stop`, a world exit closes it, and quitting closes it.
- **First creator wins.** If a second `window_create` plays for a name that is already open, it warns
  once and does nothing - the running window is never replaced under its owner. Stopping the first
  creator frees the name; replaying then opens a fresh window.
- **`window_control` needs the window.** On a name with no open window it is a no-op with a one-time
  warning.
- **Two `window_control` on one window are both applied**, in the order the effects are active, last
  writer wins - so a control can be layered on a creator's geometry without either erroring.
- **At most 8 windows at once.** A ninth is dropped with a one-time warning; existing windows are
  never evicted to make room.

## The reserved id `"0"`: the Minecraft window itself

The id **`"0"` is reserved**. A `window_create` or `window_control` with that id does **not** open a
picture window: it drives the **Minecraft game window itself** - its size and its position on the
screen. Everything else in this page still applies to every other id, unchanged.

```json
{
	"type": "window_create",
	"id": "0",
	"persistent": true,
	"params": {
		"pos_x": 0.5,
		"pos_y": 0.5,
		"size_w": { "start": 1.0, "end": 0.5 },
		"size_h": { "start": 1.0, "end": 0.5 }
	}
}
```

```
/vfx play vfx_demos:window_game_shrink
```

### What is different for id `"0"`

- **The geometry is the window's own rect, not a picture inside a canvas.** `size_w`/`size_h` are
  0..1 of the monitor work area and `pos_x`/`pos_y` are the **centre** of the window as a 0..1
  fraction of the work area - so any resize grows and shrinks **evenly about that centre** and never
  pins a corner. `size = 1` is a maximized-looking window filling the work area, `pos_x: 0.5,
  pos_y: 0.5` is dead centre, and `pos_x: 0.5, size_w: 0.5` is the middle half of the screen.
- **The work area is pinned when the `window_create` starts**, exactly like an aux canvas: it is the
  work area of the monitor the game window was on at that moment. Replay the effect to re-pin it.
- **Only an effect that declares a geometry moves the window.** If neither the `window_create` nor any
  live `window_control` for `"0"` mentions `pos_x`/`pos_y`/`size_w`/`size_h` (in the definition or
  through a live `/vfx set`), the window is left completely alone - a `"0"` effect that only renames
  or fades something is a no-op on the window.
- **Fullscreen is left first.** While the game window is in exclusive fullscreen, an id-`"0"` effect
  that declares a geometry switches it back to windowed at the requested size (through Minecraft's own
  fullscreen path, so the game's own F11 state stays correct) and then moves it.
- **The window stays an ordinary Minecraft window.** No frame is removed, it is not made click-through,
  transparent, always-on-top, resizable-disabled or undecorated: it is the normal window you play in.
  There is no picture, no OS title and no opacity for `"0"` - `texture`, `titles`, `frames`,
  `frame_time`, `title_index` and `opacity` are ignored, because the game window has no picture surface
  to draw into and its title and decorations are yours.
- **Stopping leaves it where it is.** When the driving effect stops, expires, the world is left or the
  game quits, the window is **not** moved or resized back: it stays at the size and position the effect
  left it, and you can move or resize it yourself. (A jump back to the pre-effect rect would read as a
  bug, and the mod has no idea what you had before the effect started.)
- **Never 0x0.** The size is clamped up to at least 320x240 and down into the work area, so an
  over-small or negative `size_*` still leaves a window the game can render into.
- **On Wayland** the compositor owns window placement, so the position is best-effort there too.

> The geometry is driven from the render thread after the game's own present, once per frame, and only
> the position is changed when it actually differs - so an idle id-`"0"` effect costs two integer reads
> per frame and no OS call at all.

## Two things that are quirks, not bugs

- **`fade_ticks` on the creator pops the window instead of fading it.** A stopping creator closes its
  window the moment the fade begins, so the picture disappears at once. Use an `opacity` ramp
  (keyframes or `start`/`end`) if you want it to fade out.
- **F3+T does not refresh the picture.** The image is decoded and uploaded once, when the window
  opens; a resource reload leaves the old picture on screen until the window is recreated. Recreate it
  (stop and replay the `window_create`).

## Honest limits

- **Client-side.** The window belongs to the client the effect is active on: a server-triggered
  window effect opens one window on every client playing it. `title_index`, `opacity` and the geometry
  are ordinary params and can be driven from the server like any other; `texture` and `titles` live
  in the definition, which is synced like every other definition.
- **Per-node capability.** Each Minecraft line ships its own GLFW: click-through needs GLFW 3.4,
  transparency and no-focus-on-show need 3.3. Where a node's GLFW is older the feature is simply not
  used (an opaque canvas, or a window that can take focus) - never an error, never frame damage.
- **No pass is added to the game frame.** With no window effect active, nothing here runs at all; the
  frame is byte-for-byte normal. The aux windows are presented after the game's own present, through
  their own GL contexts, with vsync-waiting disabled so they cannot slow the game down.
- No `decorated` and no `fullscreen` field: the frame cannot be enabled and fullscreen is `size = 1`.

## Example

A picture window with a 4-frame strip and three titles, opened on one monitor's work area, and a
control that slides it, retitles it and fades it:

```json
{
	"type": "window_create",
	"id": "hud",
	"texture": "vfx_demos:window/logo",
	"titles": ["VFX Weaver", "ready", "charging"],
	"persistent": true,
	"fade_ticks": 8,
	"params": {
		"pos_x": 0.5,
		"pos_y": 0.05,
		"size_w": 0.2,
		"size_h": 0.2,
		"opacity": 1.0,
		"frames": 4,
		"frame_time": 5,
		"title_index": 0
	}
}
```

```json
{
	"type": "window_control",
	"id": "hud",
	"persistent": true,
	"params": {
		"pos_x": { "start": 0.5, "end": 0.75 },
		"size_w": { "start": 0.2, "end": 0.35 },
		"opacity": { "keyframes": [
			{ "time": 0, "value": 1.0 },
			{ "time": 40, "value": 0.2 },
			{ "time": 80, "value": 1.0 }
		] },
		"title_index": { "keyframes": [
			{ "time": 0, "value": 1 },
			{ "time": 40, "value": 2 }
		] }
	}
}
```

```
/reload
/vfx play vfx_demos:window_hud
/vfx play vfx_demos:window_hud_move
/vfx play vfx_demos:window_hud {[opacity:0.5]}
```

Author both effects in **your own** namespace (`vfx_demos:` above) - a pack that writes
`vfxweaver:window_hud` would shadow a built-in rather than add one.

## Code

The window subsystem is `dev.vfxweaver.client.window` (canvas window, picture content, controller,
registry, lifecycle manager, the game-window driver for the reserved id and the per-node platform
gate). It is asserted by
`scripts/check-window-platform.ps1`, `check-window-canvas.ps1`, `check-window-content.ps1`,
`check-window-registry.ps1`, `check-window-control.ps1`, `check-window-fields.ps1`,
`check-window-lifecycle.ps1` and `check-game-window.ps1`. **Not verified in game** - the owner tests
the pixels, the passthrough, the focus behaviour and the game-window geometry.

## See also

- [Datapack format](../../datapack/format.md) - definition fields and structural blocks.
- [Animating a param](../../datapack/params.md) - keyframes, `start`/`end`, `expr`, `bind`.
- [surface_pattern](../screen/surface-pattern.md) - the same sprite-sheet addressing on the terrain.
- [Commands](../../commands.md) - `/vfx play`, `/vfx stop`, the param-map syntax.
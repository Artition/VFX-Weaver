# screen_image

`type: "screen_image"`

Draws a picture over the game frame: a resource-pack PNG (or an image a mod supplies from code)
composited inside an animatable rectangle, at a screen layer of your choice.

## Fields

> Every screen effect also accepts **`screen_layer`** and the [shared definition fields](../index.md#shared-fields) (`duration`, `easing`, `loop`, `persistent`, `fade_ticks`, `sound`).

`texture` is a **top-level** field (next to `type`, never inside `params`), because it is a string
and the picture's identity does not animate:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `texture` | string | — (required) | Resource id of the picture (`"vfx_demos:textures/vfx/screen_hero"`), resolved through the game's texture manager, so a resource pack can supply it. A missing `.png` suffix is added. A mod may instead register the pixels itself with `VFXAPI.registerImage(id, …)` and reference that same id here (see the [API](../../../API.md#vfxapi)) |

Everything else is an **ordinary param**, so it takes the whole
[param spec](../../datapack/params.md) surface - constant, `start`/`end`, `keyframes`, `bind`, `expr`,
the `/vfx play` param-map and live `setParam`:

| Param | Type | Default | Meaning |
|---|---|---|---|
| `pos_x` | float | 0 | Left edge of the picture, 0..1 of the free space left after `size_w` (see below) |
| `pos_y` | float | 0 | Top edge of the picture, same rule with `size_h` |
| `size_w` | float | 1 | Picture width, 0..1 of the smaller side of the game window. `size_w : size_h` are the **picture's own proportions** (see below) |
| `size_h` | float | 1 | Picture height, 0..1 of the smaller side of the game window |
| `opacity` | float | 1 (fades to 0) | How strongly the picture is composited over the frame, 0..1 - animate it for a fade |
| `frames` | float | 1 | How many frames the image is split into, at least 1; `1` = a still image. Ignored when the PNG has a sibling `.mcmeta` (see below) |
| `frame_time` | float | 0 | Ticks per frame for a PNG without a `.mcmeta`. `0` (the default) means no stepping - the picture holds frame 0 |
| `order` | float | 0 | Compositing order **within one `screen_layer`** (lower runs earlier). Like every screen effect's, `order` and `screen_layer` are ordinary params - see the [effects index](../index.md#how-to-read-an-effect-page) |

Notes on the numbers, because the canvas is not what it looks like:

- **The canvas is the Minecraft window, not the monitor.** Every fraction is of the **game window's
  own frame** - the same pixels you see, wherever that window happens to be on the desktop. Resize
  the game window and the picture scales with it; there is no OS window behind this, and nothing
  outside the game frame is touched.
- **`size_w : size_h` are the picture's own proportions, not the window's axes.** Both are taken in
  one common unit (the smaller side of the game window), so `size_w == size_h` is a square on any
  window and a deliberate difference stretches the picture - the window's aspect never distorts the
  shape. The picture fills that box (there is no aspect fit of the source), so `size_w:size_h` is
  exactly the on-screen width:height and you can animate a stretch by driving the two out of step.
  `size_h = 1` is as tall as the smaller window dimension; both are clamped into `0..1`.
- **`pos_*` is relative to the free space, not to the window.** `size_*` is applied first, then
  `pos_*` is mapped over what is left: `x = pos_x * max(0, windowWidth - width)`. So `pos_x: 1` puts
  the picture flush against the right edge whatever its width, and `pos_x: 0.5` centres it. `pos_*`
  and `size_*` are clamped into `0..1`, so an overshooting keyframe cannot push it off the frame.
- **`screen_layer` picks which part of the frame you draw over.** `0` is under the first-person hand
  and the GUI, `1` is above the hand and below the GUI (**default**), and `2` is above everything,
  chat and hotbar included. A picture that should never cover the HUD belongs at `0` or `1`; a
  full-screen takeover belongs at `2`.
- **A resource-pack PNG with a sibling `.mcmeta` wins over `frames`/`frame_time`.** If the `texture`
  resolves to a pack file that has a `.png.mcmeta` next to it (the vanilla animation format: optional
  `frameWidth`/`frameHeight`, a `frames` list with per-frame `time`, `defaultFrameTime`), the picture
  plays that animation - the sheet is the whole grid (multi-row is fine), each frame shows for its
  own time (milliseconds, rounded to ticks, at least one) and the loop wraps. For that picture
  `frames` and `frame_time` are ignored, so a `.mcmeta` is the only way to animate a non-uniform
  frame time. `interpolate: true` is **not** supported - frames switch without blending.
- **`frames` splits the image into a horizontal strip** (for a PNG without a `.mcmeta`): the sheet is
  read row-major with frame 0 top-left, so the image is split into `frames` equal columns left to
  right, and it must be at least `frames` pixels wide. `frame_time` is in ticks (20 ticks = 1 s), so
  `frame_time: 4` is 5 fps and `2` is 10 fps; it counts real elapsed ticks, independent of the
  effect's `duration` and `easing`.
- **A picture that does not resolve draws nothing, and says so once.** An unloadable id is warned
  about once through the log; the pass stays a passthrough, so a broken `texture` can never blank the
  frame. A resource reload re-resolves the picture, so `F3+T` is enough to pick up a new PNG.

## Example

```json
{
	"type": "screen_image",
	"texture": "vfx_demos:textures/vfx/screen_hero",
	"duration": 200,
	"params": { "pos_x": 0.3, "pos_y": 0.2, "size_w": 0.4, "size_h": 0.4, "opacity": 0.8, "screen_layer": 1 }
}
```

```
/vfx play vfx_demos:my_screen_image
```

Author it in **your own** namespace (`vfx_demos:` above) - a pack that writes `vfxweaver:<name>`
would shadow a built-in rather than add one.

## Code

```
/vfx play vfx_demos:show_screen_image
```

Its datapack definition:

```json
{
	"type": "screen_image",
	"texture": "vfx_demos:textures/vfx/screen_hero",
	"duration": 200,
	"persistent": true,
	"loop": true,
	"params": {
		"pos_x": 0.3,
		"pos_y": 0.2,
		"size_w": 0.4,
		"size_h": 0.4,
		"opacity": 0.8,
		"screen_layer": 1
	}
}
```

## See also

- [Effects index](../index.md) - the shared fields, `screen_layer`, `order`, masks and fusion.
- [Datapack format](../../datapack/format.md) - definition fields and structural blocks.
- [Animating a param](../../datapack/params.md) - keyframes, `start`/`end`, `expr`, `bind`.
- [API](../../../API.md#vfxapi) - `VFXAPI.registerImage` for an image a mod renders itself.
- [window_create and window_control](../window/custom-windows.md) - the same picture fields on a real
  OS window, off the desktop.
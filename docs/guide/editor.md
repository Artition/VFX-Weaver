# Visual node editor

VFXweaver ships a small **visual node editor**: a local web page (served by the game, on your own
machine) where you build an effect as a node graph, tune it with sliders and keyframes, watch it play
in-game live, and export the result as a normal datapack. The editor writes ordinary
`data/<namespace>/vfx/<name>.json` files - there is no editor-only format to learn and nothing extra
to install.

## Starting it

In game, run:

```
/vfxeditor
```

The first call starts the editor bridge on a free loopback port, prints the URL in chat and opens it
in your default browser:

```
VFX editor: http://127.0.0.1:<port>/?token=<token>
```

The URL carries a one-time session token; every request (the page, its assets, the API calls) must
present it, and the bridge binds `127.0.0.1` only. Keep the URL - opening it in a second tab connects
a second editor. Running `/vfxeditor` again stops the bridge ("VFX editor stopped").

If your browser does not open, copy the printed URL into it by hand.

## The window

| Panel | What it is |
|---|---|
| Top: **Top bar** | The **File** menu (New Project, Open, Recent, Save, Export, Settings), the project name (click it to open project settings: name, namespace, global defaults), the tab bar and active-tab name, the transport (play / stop / replay) and the **revision badge** (`rev N → in game`, or offline/syncing/error). |
| Left: **Effects** | One button per effect type the game declares (from the live schema). Filter with the search box; click a type to start a tab of it. |
| Centre: **Canvas** | A [LiteGraph](https://github.com/jagenjo/litegraph.js) node graph. The effect node is the graph root. |
| Right: **Inspector** | The selected param's control: constant slider, `start`/`end` animation, keyframes, a world/camera binding, or an `expr`. |
| Bottom: **Timeline** | One lane per animated param, a tick ruler, a draggable duration bar, draggable key dots, a playhead and the transport (play / stop / replay / loop). |

The tab bar is one tab per effect; a tab name is the effect id it compiles to (`<namespace>:<name>`,
or a reserved `vfxweaver:` preview id for a scratch tab). A tab may be **empty**: delete its effect
node (with `Del`) and the canvas shows a **+ Add effect** placeholder listing the effect types;
picking one fills the same tab, so a tab can be retyped without opening a new one. Double-click a
node's title bar (or the square at its left) to collapse it to the title bar; wires are kept and
the state survives tab switches and undo. An effect node with schema `group` metadata (like
`sky_pattern`) additionally groups its input sockets under Blender-style collapsible panels —
one header row per group (`Anchor`, `Dome`, ...) in inspector order, clicked anywhere on the
row to collapse/expand (wired sockets stay as stubs so wires survive); `in`/`out`/`mask` stay
outside groups. The per-group state is undoable and survives tab switches, and never reaches
the compiled definition.

## Editing and live playback

Every change goes to the running game through the local bridge:

- **Play** (▶, or the inspector's Play) compiles the whole tab and plays it in-game. A structural edit
  (switching a control, adding a keyframe, editing an `expr` or binding) recompiles and re-plays.
- **Stop** (■) stops the effect; the playhead parks at 0. **Loop** (⟳) restarts when the playhead
  reaches the tab duration.
- **Dragging a slider** (a constant param) or **a key dot** sends an incremental live update, debounced
  (~80 ms), so the running effect follows without a restart. **Pause** is not a VFXweaver operation and
  is shown disabled.
- **Undo/redo** is `Ctrl`/`Cmd`+`Z` and `Ctrl`/`Cmd`+`Shift`+`Z` (or `Ctrl`+`Y`). Undo is
  **snapshot-based**: one drag (`pointerdown`→`up`) or one discrete action is one step, up to 100
  steps. The web model is the source of truth: an undo restores the model snapshot and then
  **recompiles the whole effect** — it is never sent to the game as an incremental undo.
- The game echoes a monotonic **revision** on every reply. If the connection drops, the editor
  reconnects on its own with a growing backoff (1 s, 2 s, 4 s, …) and, when it finds the game's
  revision has diverged, re-sends the full effect so model and game agree again.

The editor clock is local and display-only (20 ticks/s); scrubbing the playhead sends a real seek.
The playhead is clamped to `[0, duration]`, and keyframes are clamped to `[0, duration]` when dragged
or added (a key outside that range is invisible to the engine); a key whose value leaves the param's
schema `min`/`max` turns red with a tooltip. **Click a lane** (not an existing key) to add a key at the
clicked tick, pinned to the lane's current value and selected — `K` adds one at the playhead instead.
The timeline's **duration bar** end handle drags the effect's duration right on the timeline.

Value-node numeric slots and inspector parameters follow the same **driven** rule: a slot with a wire
connected shows a read-only value plus its source badge (with a **go to node** affordance) and cannot
be edited there — edit the source node instead. Unwired numeric slots are edited **in place**: clicking
the field opens a text input sized and styled as the field itself (select-on-focus, Enter/blur commits,
Escape cancels), and a horizontal drag scrubs the value. The `bind` node carries a binding dropdown and
labels its output by the binding's unit (`UV 0..1`, `blocks`, `world`, `deg/tick`, …).

## Keyboard shortcuts

Press `?` in the editor to toggle this list. Shortcuts are ignored while typing in a field.

| Key | Action |
|---|---|
| `Del` / `Backspace` | delete the selected nodes (on the effect node: empty the tab) |
| `Ctrl`+`C` / `Ctrl`+`V` / `Ctrl`+`D` | copy / paste / duplicate |
| `Ctrl`+`A` | select all |
| `Ctrl`+`Z` / `Ctrl`+`Shift`+`Z` / `Ctrl`+`Y` | undo / redo |
| `Space` | play or stop the preview |
| `R` | replay from the start |
| `K` | add a keyframe at the playhead on the selected slot |
| `F` / `Home` | frame the selection / frame everything |
| `Ctrl`+`S` | save (compile + preview) |
| `Tab` / double-click | node search-add |
| `Ctrl`+`G` | group the selection on the canvas (canvas-only, not exported) |
| `[` / `]` | previous / next tab |
| middle-drag / `Space`+drag | pan the canvas |
| `Ctrl`+wheel | zoom |
| `?` | toggle the shortcut help |

## Exporting a datapack

Set the namespace in project settings (click the project name, or **File → Settings**), then
**File → Export…**. The dialog has three **targets**; every tab that has an effect becomes one
`data/<namespace>/vfx/<name>.json` file, where `<name>` is the tab's name (its part after the `:`,
if any):

| Target | You provide | Where it writes |
|---|---|---|
| **ZIP** | nothing | a `.zip` built by the game and downloaded through your browser's native save dialog, named after the namespace |
| **Folder** | the native directory picker (or a path typed by hand if the picker is unavailable) | a pack root directory created/populated in place (ready to copy or zip yourself) |
| **World** | pick one of the worlds listed from the game's `saves` folder; overwrite is confirmed | `<world>/datapacks/<namespace>/` - loadable immediately with `/reload`, no restart |

The exported pack contains a per-Minecraft-line `pack.mcmeta` and the effect JSON per tab, plus a
sidecar (`vfxweaver_editor.vfxproj.json`) holding the editor layout (root node position and timeline
zoom). The effect JSON is self-contained; the sidecar is convenience only - a pack without it still
loads and the editor lays the graph out automatically. The status line shows the path the server wrote.

## Importing a pack

**File → Open…** takes a datapack folder or zip (the exported one, or any hand-written pack); recent
paths are listed under **File → Recent**. Opening reopens one tab per effect: the effect `type` selects the node type, the tab
name becomes `<namespace>:<name>`, and the effect's params, keyframes and value graph are restored. If
the pack carries the editor sidecar the node layout is restored; otherwise nodes auto-lay-out. An
effect whose type your build does not know is kept as a tab rather than dropped, so a pack from a newer
mod still opens.

## Golden path: from empty editor to a playing datapack effect

1. In game: `/vfxeditor`. Open the printed URL.
2. Click the project name and set the namespace to `vfx_demos`.
3. Click `blur` in the **Effects** palette to create the tab (this makes the `blur` node the effect
   root), then set the **Tab** field to `my_blur`.
4. In the inspector pick `radius` → **Keyframes**; scrub the playhead and add a couple of keys (for
   example `0 → 2` and `40 → 24`) so the blur animates. A lane appears in the timeline.
5. Press **Play** (▶) - the screen blurs and the animation plays live.
6. **File → Export…**, pick **World**, choose your world from the list and confirm. The dialog shows
   the written path.
7. In game: `/reload`, then `/vfx play vfx_demos:my_blur`. The effect now plays from the exported
   datapack - not just the editor's local preview layer. (`/vfx list` shows `vfx_demos:my_blur`.)
8. **File → Open…** with `<world>/datapacks/vfx_demos` to confirm the round trip: both the tab and its
   effect return, with the node layout restored (or auto-laid-out when the sidecar is absent).

## Browser baseline

The editor is plain static JavaScript/CSS - no build step, no framework to install. It needs a current
browser; current Chrome or Firefox (and the previous release) is the supported baseline. It uses
`fetch`, `EventSource` (server-sent events) and an HTML canvas; if LiteGraph or the canvas cannot
load, the page shows a visible **"Unsupported browser"** banner instead of a blank screen. There is no
Internet Explorer / legacy-Edge support.

## Troubleshooting

- **Page is blank but the banner is absent** - the browser loaded the shell but the canvas failed;
  check the browser console for a failed `/assets/...` request (usually a stale token - reopen the URL
  `/vfxeditor` printed).
- **"Could not load /schema from the game bridge"** - the bridge is not running (run `/vfxeditor`), or
  the page was opened from a URL without the token.
- **Edits stop reaching the game** - watch the status line: a dropped connection shows
  `bridge: …` and the editor reconnects; on a revision mismatch it re-sends the whole effect.

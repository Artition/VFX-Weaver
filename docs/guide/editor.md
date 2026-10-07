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
| Left: **Effects** | One button per effect type the game declares (from the live schema). Filter with the search box; click a type to start a tab of it. |
| Centre: **Canvas** | A [LiteGraph](https://github.com/jagenjo/litegraph.js) node graph. The effect node is the graph root. |
| Right: **Inspector** | The selected param's control: constant slider, `start`/`end` animation, keyframes, a world/camera binding, or an `expr`. |
| Bottom: **Timeline** | One lane per animated param, draggable key dots, a playhead and the transport (play / stop / loop). |

The tab bar is one tab per effect; a tab name is the effect id it compiles to (`<namespace>:<name>`,
or a reserved `vfxweaver:` preview id for a scratch tab).

## Editing and live playback

Every change goes to the running game through the local bridge:

- **Play** (▶, or the inspector's Play) compiles the whole tab and plays it in-game. A structural edit
  (switching a control, adding a keyframe, editing an `expr` or binding) recompiles and re-plays.
- **Stop** (■) stops the effect; the playhead parks at 0. **Loop** (⟳) restarts when the playhead
  reaches the tab duration.
- **Dragging a slider** (a constant param) or **a key dot** sends an incremental live update, debounced
  (~80 ms), so the running effect follows without a restart. **Pause** is not a VFXweaver operation and
  is shown disabled.
- **Undo/redo** is `Ctrl`/`Cmd`+`Z` and `Ctrl`/`Cmd`+`Shift`+`Z` (or `Ctrl`+`Y`). The web model is the
  source of truth: an undo restores the model snapshot and then **recompiles the whole effect** - it is
  never sent to the game as an incremental undo.
- The game echoes a monotonic **revision** on every reply. If the connection drops, the editor
  reconnects on its own with a growing backoff (1 s, 2 s, 4 s, …) and, when it finds the game's
  revision has diverged, re-sends the full effect so model and game agree again.

The editor clock is local and display-only (20 ticks/s); scrubbing the playhead sends a real seek.

## Exporting a datapack

Exports go to one of three **targets**:

| Target | Where it writes |
|---|---|
| **world** | `<world>/datapacks/<namespace>/` - loadable immediately with `/reload`, no restart |
| **folder** | A pack root directory you choose (unzipped, ready to copy or zip yourself) |
| **zip** | A `.zip` datapack archive named after the namespace |

The exported pack contains a per-Minecraft-line `pack.mcmeta` and one `data/<namespace>/vfx/<name>.json`
per tab, plus a sidecar holding the graph layout (node positions, collapse state, timeline zoom). The
effect JSON is self-contained; the sidecar is convenience only - a pack without it still loads and the
editor lays the graph out automatically.

## Importing a pack

Importing reads a datapack folder or zip (the exported one, or any hand-written pack) and reopens one
tab per effect. The effect `type` selects the node type and the tab name becomes
`<namespace>:<name>`. If the pack carries the editor sidecar (or a `graph.meta` block) the node layout
is restored; otherwise nodes are auto-laid-out. An effect whose type your build does not know is kept
as a tab rather than dropped, so a pack from a newer mod still opens.

## Golden path: from empty editor to a playing datapack effect

1. In game: `/vfxeditor`. Open the printed URL.
2. **New project `vfx_demos`** - create an empty tab and name the pack namespace `vfx_demos`.
3. **New tab `my_blur`** - add a `blur` node as the tab's effect.
4. In the inspector pick `radius` → **Keyframes**; scrub the playhead and add a couple of keys (for
   example `0 → 2` and `40 → 24`) so the blur animates. A lane appears in the timeline.
5. Press **Play** (▶) - the screen blurs and the animation plays live.
6. **Export → to world**, namespace `vfx_demos`.
7. In game: `/reload`, then `/vfx play vfx_demos:my_blur`. The effect now plays from the exported
   datapack - not just the editor's local preview layer. (`/vfx list` shows `vfx_demos:my_blur`.)
8. **Import** the exported pack to confirm the round trip: both the tab and its effect return, with the
   node layout restored (or auto-laid-out when the sidecar is absent).

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

# Visual Node Editor — v1.1 implementation plan (redesign §15)

Implements `2026-10-06-visual-node-editor-design.md` §15 (from the consultation + in-game test).
Order follows §15.9. Tasks are large and interrelated (one view-model shift); executed sequentially,
one implementer each. Per-task reviewers are SKIPPED for context economy (ruling); one final
whole-batch review + the user's in-game test are the gate.

## P1 — App-shell (design §15.6)
- Project becomes an object: `{ name, namespace, defaults }`; top bar with project name (click →
  settings: name, namespace validated `[a-z0-9_.-]+`, global defaults), tabs, transport, revision badge.
- File menu: New Project…, Open…, Recent, Save (Ctrl+S = compile+preview), Export…, Settings.
- Export dialog with three targets:
  - **ZIP** — browser download through the bridge (native OS save dialog); a POST stages the zip and
    returns a download URL, the web navigates to it.
  - **Folder** — native picker on the client (`JFileChooser(DIRECTORIES_ONLY)`), run OFF the render
    thread; the chosen path returns by request id (SSE/polling); fallback: manual path entry.
  - **World** — list worlds from `saves` (bridge knows paths) + overwrite confirmation.
- Remove the export button from the toolbar.
- Java: add the bridge endpoints needed (zip download, native picker, world list). Keep additive; no
  new deps.

## P2 — Timing + preview + compiler invariants (design §15.3, §15.5, §15.7; fixes #4, #11, #14, #15)
- Right panel: context-sensitive sections **Layers / Node timing / Parameters**; node timing =
  `duration` (ticks or seconds @20tps), `loop`, `persistent`, `fade_ticks` (default **0**), `delay`.
- Compiler ALWAYS emits timing explicitly (incl. defaults) — no reliance on hidden datapack defaults.
- **Preview artifact ≠ authored datapack:** the bridge gets the same revision compiled with overrides
  (`loop:true`/large duration, `fade_ticks:0`) so edits don't pollute the asset; export stays authored.
- Debounced edit → auto-replay from t=0; scrub is a LOCAL curve recompute (no game request); status
  badge "rev N → in game".
- Compiler invariants pinned by tests: **I1** a slot with ≥1 key serializes ONLY to `keyframes` (fade
  never emulates animation); **I2** `fade_ticks` affects only post-life fade; **I3** keys clamped to
  `[0,duration]` with a warning. E2E: a 0→1→0 track matches the curve in game.

## P3 — Slot model (design §15.1, §15.8; fixes #9, #10, #12, #17, #18)
- Remove the per-param "type" selector. A slot has mutually exclusive states: **inline** (edit in
  place; click-drag scrub, double-click type; no popup), **track** (≥1 key → keyframes; value at
  playhead), **driven** (node connected → read-only + source badge + go-to-node).
- Diamond "animate" affordance on every slot (also value-node fields → #17).
- Right-click a slot → "Replace with expression" / "Replace with binding" = auto-create the
  `expr`/`bind` node and the link; `bind`/`expr` leave the type selector.
- `multiply` (`×`) on any slot.
- "Show as node" — a track materializes as a `curve` node and back.
- Compiler peephole-folding table §15.8; importer normalizes back to slots.

## P4 — Vector param grouping (design §15.9.6; fix #21)
- Group component params into one control: `pos_x/pos_y/pos_z` → `pos`; colour `*_r/_g/_b` → colour;
  rotation groups, etc. One bind/expr applied to all components; expandable to edit components
  individually.
- Schema gains grouping metadata (source of truth); compiler expands a group value to the underlying
  scalar params on export; importer regroups.

## P5 — Timeline curves + gantt (design §15.4; fix #13)
- Bezier curve editor: segment timing stored as `cubicBezier[x1,y1,x2,y2]` (x∈[0,1], y free); tangent
  handles; snap to named easing (ε) → serialize name else numbers; lossless round-trip.
- Value axis (auto-fit + schema min/max, out-of-range highlight), duration-bar handle, per-node gantt
  row `[delay → delay+duration]`.

## P6 — Compositions (design §15.2; fixes #1, #8, #19)
- A tab holds N effect nodes with flow ports `in`/`out`; a chain = layering order for post effects.
  Layers panel (right): order, drag-reorder, mute/solo, synced with the canvas.
- Empty tab valid (already). `collection` stays absent from the UI.
- Compiler: 1 node → today's file; N nodes → root `type:"collection"`, `effects:[...]` in chain order
  (`{effect,delay,duration,params,easing}`), NOT sorted by delay.
- **Engine check:** is post-effect application order deterministic inside a collection today? If not,
  add an additive field (`"mode":"chain"` / `"layer":N`) — additive is allowed. Verify in game.
- v2 (skip): reference nodes (instance another tab).

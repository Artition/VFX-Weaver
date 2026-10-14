# AGENTS.md — instructions for AI agents working in VFX-Weaver

## No arbitrary numeric caps

Do NOT add numeric min/max clamps on effect params unless one of these justifies it:

- **perf** — the value sizes a GPU/CPU loop, buffer, or history (e.g. shell layers, trail length, march steps);
- **correctness** — the value must avoid div-by-zero, degenerate UVs, or hash blow-up (lower bound only);
- **spec** — the value is a normalized fraction (`0..1` alpha/weight), an enum (`screen_layer 0..2`), or a full circle (`±360`).

Anything else (radius, height, speed, size, width, scale, turns, spacing, dash/gap…) must be **unbounded above**:
renderer clamps become lower-bound-only `Math.max(v, floor)`, schema entries drop the ceiling
(absent entry = unbounded), guides say "no upper limit". Geometry cost is constant here
(fixed shell/slice counts — size only scales positions), so "safety" is not a justification.
Large values may raise overdraw — note it in the guide, never hard-clamp it.

## Workflow

- Conventional Commits (`feat/fix/docs/...`), GitHub Flow, PR must build + update the guide in the same PR.
- Headless checks live in `scripts/check-*.ps1`; the visual editor has
  `node scripts/run-editor-selftest.js` and `node scripts/run-editor-grouptest.js` — keep them green.
- Docs are the contract: `docs/CHANGELOG.md` (add entries at the top, same PR as the change),
  `docs/guide/`, `docs/API.md`, `docs/ARCHITECTURE.md`. Build the site with `mkdocs build --strict`.
- Never verify visuals headless — GLSL/pixels need the game; mark such work `**Not verified in game**`.

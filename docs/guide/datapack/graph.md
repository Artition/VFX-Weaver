# Value graphs


An effect can drive any of its numeric inputs from a small value graph that the client evaluates once per frame. The block is optional and additive: a definition without `graph`/`inputs` behaves exactly as before, and a mod that does not know graphs ignores them entirely, because graph wiring lives in a separate top-level `inputs` block (or in graph edges) and never inside `params`.

```json
{
	"type": "blur",
	"duration": 400,
	"loop": true,
	"params": { "radius": 2.0 },
	"graph": {
		"version": 1,
		"nodes": [
			{ "id": "phase", "kind": "time", "inputs": { "speed": 0.25 } },
			{ "id": "pulse", "kind": "curve", "inputs": {
				"points": [
					{ "time": 0,   "value": 0.0 },
					{ "time": 50,  "value": 8.0 },
					{ "time": 100, "value": 0.0 }
				]
			} }
		],
		"edges": [ { "from": "phase", "to": "pulse", "input": "time" } ],
		"meta": { "phase": { "pos": [40, 60] }, "pulse": { "pos": [200, 60] } }
	},
	"inputs": { "radius": { "from": "pulse" } }
}
```

`graph` fields:

| Field | Type | Default | Description |
|---|---|---|---|
| `version` | int | 1 | Format version. Only `1` exists — any other value is a per-file error. |
| `nodes` | array | — (required) | 1–128 node objects. |
| `edges` | array | `[]` | 0–512 edges (node→node or node→effect input). |
| `meta` | object | — | Editor-only info (canvas positions, groups, comments). The engine ignores it; it may hold anything. |

Every node has `id` (string, unique in the graph), `kind` (one of the kinds below) and an `inputs` object whose values are either a number or `{ "from": "<node-id>" }`.

| `kind` | Inputs (default) | Result |
|---|---|---|
| `constant` | `value` (0) | The literal `value`. |
| `time` | `speed` (1), `offset` (0) | `elapsed × speed + offset`; `elapsed` is the effect's time in ticks. |
| `random` | `min` (0), `max` (1), `index` (0) | A stable per-instance random number in `min..max` (the same every frame for one `index`; animating `index` re-rolls). |
| `noise` | `x` (elapsed), `y` (0), `z` (0), `scale` (1), `octaves` (1), `gain` (0.5), `lacunarity` (2) | 3D simplex noise, roughly −1..1; `octaves` is clamped to 1–8. |
| `curve` | `points` (required), `time` (elapsed) | The curve sampled at `time`. |
| `math` | `a`, `b` (both required), top-level `op` | `a op b`; `op` is `add`, `subtract`, `multiply`, `divide`, `min`, `max`, `pow` or `mod` (divide/mod by zero → 0). |
| `mix` | `a`, `b` (both required), `factor` (0.5) | `a + (b − a) × factor`. |
| `clamp` | `value` (required), `min` (0), `max` (1) | `value` clamped into `min..max`. |
| `remap` | `value` (required), `in_min` (0), `in_max` (1), `out_min` (0), `out_max` (1) | Linear remap from the input range to the output range. |
| `bind` | a world/camera binding (§3.3) as the node's inputs, plus optional `fallback` | The same value a bound param would produce. |
| `expr` | `expr` (required string, ≤1024 chars) | The math expression from §3.2 (same `t`/`x`/`y`/`z` variables and functions), compiled per instance and evaluated every frame. |

A `curve` node's `points` are `{ "time": <ticks>, "value": <number>, "easing": "<name>" }` (up to 64, times strictly ascending; `easing` is optional and defaults to `linear`). A point's `easing` eases the segment from that point to the next — the last point's `easing` is unused — exactly like keyframes (§3.2).

Node-to-node edges: `{ "from": "<node>", "to": "<node>", "input": "<socket>" }`.

**Wiring an effect input.** Two equivalent ways:

1. the top-level `inputs` block — `"inputs": { "radius": { "from": "pulse" } }`;
2. a graph edge whose `to` is the effect input instead of a node — `{ "from": "pulse", "to": "radius" }` (no `input` field).

A numeric literal in `inputs` (`"inputs": { "radius": 6 }`) is also accepted and overrides a `params` entry of the same name. Wiring the same input twice (both ways, or twice in the edge list) is a parse error. Every effect input keeps its numeric default — an input wired but not declared in `params` gets a constant `0.0`.

**Caps and errors.** `version` must be `1`; at most 128 nodes, 512 edges and a chain depth of 32; `expr` source at most 1024 characters; at most 64 curve points; `octaves` clamped to 8. A graph that breaks any rule fails **that file only**, with an error naming the node id and input — `/vfx validate` lists it and the rest of the pack keeps loading.

**Reference example.** The built-in `vfxweaver:graph_demo` is exactly the definition above: `/vfx play vfxweaver:graph_demo` plays a blur whose radius follows a `time → curve` pulse. Built-in ids need their namespace; there is no `minecraft:` fallback.

#### Subgraphs (macros)

An effect file may declare reusable node blocks under a top-level `subgraphs` array. A graph node
of kind `subgraph` stamps one in; the loader expands it before the graph is validated, so macros
cost nothing per frame.

```json
{
  "subgraphs": [
    {
      "id": "fade_noise",
      "inputs": { "scale": 1.0, "speed": 1.0 },
      "nodes": [
        { "id": "s1", "kind": "time",  "inputs": { "speed": "$speed" } },
        { "id": "s2", "kind": "noise", "inputs": { "scale": "$scale", "octaves": 3 } },
        { "id": "s3", "kind": "curve", "inputs": { "points": [ { "time": 0, "value": 1.0 },
                                                              { "time": 100, "value": 0.0 } ] } }
      ],
      "edges": [ { "from": "s2", "to": "s3", "input": "a" } ],
      "outputs": { "out": "s3" }
    }
  ],
  "graph": {
    "nodes": [ { "id": "n1", "kind": "subgraph", "subgraph": "fade_noise",
                 "inputs": { "scale": 2.0, "speed": 0.5 } } ],
    "edges": [ { "from": "n1", "to": "radius" } ]
  }
}
```

- `inputs` on a subgraph declares parameters with defaults. A node value that is the whole string
  `"$name"` is replaced by the instance's binding (or the default). `"$name"` is only recognised as
  a whole node-input value; it is never substituted inside `expr` text or inside `points`.
- Node ids inside a subgraph are local. On expansion they are prefixed with the instance id
  (`n1.s2`), so the same macro can be used twice in one graph without collisions.
- `outputs` is a non-empty map of output name to a local node id. `outputs` order matters: the
  first entry is the default output. An edge may select one with
  `{ "from": "n1", "output": "intensity", "to": "beam.intensity" }`; with one output, or when
  `output` is omitted, the first declared output is used. An effect `inputs` entry
  `{ "from": "n1" }` always uses the first output.
- Subgraphs may nest and may not reference themselves, directly or transitively. The number of
  subgraph definitions is capped at 64 and nesting at 8; exceeding either fails that file only.
  The expanded node and edge counts must stay within the graph caps (128 / 512).
- A fault inside a macro names both ends, e.g.
  `subgraph 'fade_noise' (node 'n1') → node 's3': input 'a' is not connected and has no default`.
- A `subgraph` node without a `subgraphs` block is an error; macros are defined in the same file
  in this version.

#### Logic nodes

Four kinds produce a value from comparisons and selection. Booleans are `1.0` (true) and `0.0`
(false); any non-zero input is true.

| kind | structural field | inputs | result |
|---|---|---|---|
| `compare` | `op`: `eq`, `ne`, `lt`, `le`, `gt`, `ge` | `a`, `b` (both required) | `1.0` or `0.0` |
| `boolean` | `op`: `and`, `or`, `xor`, `not` | `a` (required); `b` required for `and`/`or`/`xor`, unused for `not` | `1.0` or `0.0` |
| `if` | — | `condition` (required), `then`, `else` (default `0`) | `then` when `condition` is non-zero, else `else` |
| `switch` | — | `index` (required), `case_0`, `case_1`, …, `default` (default `0`) | `case_<round(index)>` when present, else `default` |

```json
{ "id": "gate", "kind": "compare", "op": "gt", "inputs": { "a": { "from": "level" }, "b": 0.5 } },
{ "id": "out",  "kind": "if", "inputs": { "condition": { "from": "gate" }, "then": 1.0, "else": 0.0 } }
```

`if`, `switch`, `boolean and` and `boolean or` evaluate only the branch that can change the
result; the other side is never computed.

The built-in `vfxweaver:graph_logic_demo` combines both: a `pulse` macro (a `time`/`math`/`compare`/
`remap`/`if` chain with a `$period`/`$peak` parameter) drives the blur's `radius`, ramping it up for
the first half of each `$period` and holding it at `0` for the second.

Masks are implemented (see [Masks](masks.md)): an optional top-level `mask` block restricts where a post-processing effect applies, evaluated once per frame in a coverage prepass at screen layer 0.

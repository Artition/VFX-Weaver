/*
 * VFXweaver visual node editor — IR -> datapack JSON compiler (Task 13).
 *
 * `compileTab(tab)` serialises one `Tab` (ir.js) into the effect JSON the VFXweaver datapack
 * format and the game's `VFXDefinition.parse` expect: `type`, `duration`, `easing`, `params`,
 * an optional `graph` block for the value nodes and an optional top-level `inputs` block wiring
 * value nodes to effect params (docs/guide/datapack/graph.md, params.md).
 *
 * It is a pure function and never consults the schema — an unknown effect type is copied through
 * verbatim (the game parser is the authority on types), so a saved project always round-trips.
 *
 * Exposed as `window.VFXCompiler`; also attached to `window.VFXEditor.compiler` when present.
 */
(function () {
	"use strict";

	var root = typeof window !== "undefined" ? window
			: (typeof globalThis !== "undefined" ? globalThis : this);

	/** Coerce to a finite number, falling back to `fallback` (default 0) for a bad value. */
	function num(value, fallback) {
		var n = Number(value);
		if (isFinite(n)) {
			return n;
		}
		return fallback == null ? 0 : fallback;
	}

	/** A deep copy of plain JSON data, so an emitted definition never aliases the tab's source. */
	function clone(value) {
		return value == null ? value : JSON.parse(JSON.stringify(value));
	}

	/** Clamp a tick time into `[0, total]`; a non-finite/negative total collapses to 0 (I3). */
	function clampKeyTime(ticks, total) {
		var t = num(ticks);
		var max = num(total);
		if (max < 0) {
			max = 0;
		}
		return Math.max(0, Math.min(max, t));
	}

	/**
	 * Copy a keyframe list into the datapack shape, dropping editor-only fields. Key times are
	 * clamped to `[0, duration]` (invariant I3); each clamp is pushed onto {@code warnings} when
	 * supplied, so the shell can surface it.
	 */
	function copyKeys(list, total, warnings, name) {
		var out = [];
		if (!Array.isArray(list)) {
			return out;
		}
		for (var i = 0; i < list.length; i++) {
			var key = list[i] || {};
			var time = num(key.time);
			var clamped = clampKeyTime(time, total);
			if (clamped !== time && warnings) {
				warnings.push({ param: name, index: i, time: time, clamped: clamped });
			}
			var out_key = { time: clamped, value: num(key.value) };
			if (key.easing != null) {
				out_key.easing = key.easing;
			}
			out.push(out_key);
		}
		return out;
	}

	/**
	 * Serialise one param spec by the control state the IR carries.
	 *
	 * | control     | emitted form                                   |
	 * |------------|------------------------------------------------|
	 * | `constant` | a bare number (`{ "value": x }` with `multiply`) |
	 * | `animate`  | `{ "start": x, "end": y }`                      |
	 * | `keyframes`| `{ "keyframes": [...] }` (from `Tab.keys`)      |
	 * | `bind`     | `{ "bind": ..., <options> }`                    |
	 * | `expr`     | `{ "expr": "..." }`                             |
	 *
	 * An optional `multiply` (a bound spec) is appended to any of them. A number is passed
	 * through, and an unrecognised object keeps every non-`control` field, so an imported raw
	 * spec survives a round-trip.
	 *
	 * @param {string} name param name (looks up `Tab.keys` for the keyframes form)
	 * @param {Object|number} spec the control-state spec
	 * @param {Object} keys the tab's `keys` map
	 * @param {number} total the effect duration in ticks (keyframe clamp, I3)
	 * @param {Array} [warnings] collects a record per clamped key (I3)
	 * @returns {number|Object} the datapack param value
	 */
	function compileParam(name, spec, keys, total, warnings) {
		var multiply = spec && typeof spec === "object" ? spec.multiply : null;
		// I1: a slot with >= 1 keyframe serialises ONLY as keyframes, whatever its control marker
		// says — an animation is never emulated through fade or start/end.
		var list = keys && keys[name];
		if (Array.isArray(list) && list.length > 0) {
			var kf = { keyframes: copyKeys(list, total, warnings, name) };
			if (multiply) {
				kf.multiply = multiply;
			}
			return kf;
		}
		if (spec == null) {
			return 0;
		}
		if (typeof spec === "number") {
			return num(spec);
		}
		if (typeof spec !== "object") {
			return 0;
		}
		var control = spec.control;
		var out;
		if (control === "constant") {
			if (!spec.multiply) {
				return num(spec.value);
			}
			out = { value: num(spec.value) };
		} else if (control === "animate") {
			out = { start: num(spec.start), end: num(spec.end) };
		} else if (control === "keyframes") {
			out = { keyframes: copyKeys(spec.keyframes, total, warnings, name) };
		} else if (control === "expr") {
			out = { expr: String(spec.expr == null ? "" : spec.expr) };
		} else {
			out = {};
			Object.keys(spec).forEach(function (key) {
				if (key !== "control" && key !== "multiply") {
					out[key] = spec[key];
				}
			});
		}
		if (spec.multiply) {
			out.multiply = spec.multiply;
		}
		return out;
	}

	/**
	 * Split `Tab.valueNodes`/`Tab.edges` into the datapack `graph` and the top-level `inputs`
	 * wiring. An edge whose `to` is a value-node id is a graph edge; any other target is an
	 * effect param and becomes `inputs[to] = { from }`.
	 *
	 * @param {Object} tab the tab
	 * @returns {{nodes: Array, edges: Array, meta: Object, inputs: Object}}
	 */
	function compileGraph(tab) {
		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var ids = {};
		var graphNodes = [];
		var meta = {};
		nodes.forEach(function (node) {
			var out = { id: node.id, kind: node.kind, inputs: node.inputs || {} };
			if (node.op != null) {
				out.op = node.op;
			}
			graphNodes.push(out);
			ids[node.id] = true;
			if (Array.isArray(node.pos) && node.pos.length === 2) {
				meta[node.id] = { pos: [num(node.pos[0]), num(node.pos[1])] };
			}
		});

		var graphEdges = [];
		var inputs = {};
		(Array.isArray(tab.edges) ? tab.edges : []).forEach(function (edge) {
			if (edge == null || edge.from == null || edge.to == null) {
				return;
			}
			if (ids[edge.to]) {
				var out = { from: edge.from, to: edge.to };
				if (edge.input != null) {
					out.input = edge.input;
				}
				graphEdges.push(out);
			} else {
				inputs[edge.to] = { from: edge.from };
			}
		});
		return { nodes: graphNodes, edges: graphEdges, meta: meta, inputs: inputs };
	}

	/** Warning records from the most recent {@link compileTab} (clamped keyframes, I3). */
	var lastWarnings = [];

	/**
	 * Compile one tab into the datapack effect JSON (tab-indented, as the built-in definitions).
	 *
	 * Timing (`duration`, `loop`, `persistent`, `fade_ticks`) is ALWAYS emitted explicitly, including
	 * defaults, so a consumer never depends on the datapack's hidden defaults (design §15.3).
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @returns {string} the JSON text, ready to POST to `/compile` or write to `data/<ns>/vfx/`
	 */
	function compileTab(tab) {
		if (!tab || typeof tab !== "object") {
			throw new Error("compileTab: a tab is required");
		}
		// Start from the imported definition (when the tab came from a pack) so unknown/unsupported
		// top-level fields (sound, positions, mask, field, subgraphs, ...) round-trip unchanged. Only
		// the fields this IR owns are overwritten.
		var def = clone(tab.source) || {};
		def.type = tab.type == null ? "" : String(tab.type);
		def.duration = num(tab.duration, 40);
		def.loop = Boolean(tab.loop);
		def.persistent = Boolean(tab.persistent);
		def.fade_ticks = num(tab.fade_ticks, 0);
		def.easing = tab.easing == null ? "linear" : tab.easing;
		def.params = {};
		var warnings = [];
		var source = tab.params || {};
		Object.keys(source).forEach(function (name) {
			def.params[name] = compileParam(name, source[name], tab.keys || {}, def.duration, warnings);
		});
		lastWarnings = warnings;

		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		if (nodes.length > 0) {
			var built = compileGraph(tab);
			// Reuse the imported graph object so unknown graph-level fields are kept; the IR owns
			// nodes/edges/meta (and supplies the version a fresh graph needs).
			var graph = def.graph && typeof def.graph === "object" && !Array.isArray(def.graph) ? def.graph : {};
			if (graph.version == null) {
				graph.version = 1;
			}
			graph.nodes = built.nodes;
			graph.edges = built.edges;
			if (Object.keys(built.meta).length > 0) {
				graph.meta = built.meta;
			} else {
				delete graph.meta;
			}
			def.graph = graph;
			if (Object.keys(built.inputs).length > 0) {
				def.inputs = built.inputs;
			} else {
				delete def.inputs;
			}
		} else {
			delete def.graph;
			delete def.inputs;
		}
		return JSON.stringify(def, null, "\t");
	}

	/**
	 * Compile the PREVIEW artifact: the same IR as {@link compileTab}, but with playback overrides so
	 * editing stays pleasant (`loop:true`, `fade_ticks:0`) — the in-game preview never dies and never
	 * fades the animation out (design §15.5, fixes #4/#15). The authored compile is unchanged, so
	 * File→Export still writes the user's real timing.
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @returns {string} the preview effect JSON text
	 */
	function compileForPreview(tab) {
		var def = JSON.parse(compileTab(tab));
		def.loop = true;
		def.fade_ticks = 0;
		return JSON.stringify(def, null, "\t");
	}

	var api = {
		compileTab: compileTab,
		compileForPreview: compileForPreview,
		/** @returns {Array} the clamped-keyframe warnings recorded by the last compile (I3). */
		getWarnings: function () {
			return lastWarnings;
		}
	};
	root.VFXCompiler = api;
	if (root.VFXEditor) {
		root.VFXEditor.compiler = api;
	}
})();

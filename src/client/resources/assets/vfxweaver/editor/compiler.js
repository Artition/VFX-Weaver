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
				var easing = easingJson(key.easing);
				if (easing != null) {
					out_key.easing = easing;
				}
			}
			out.push(out_key);
		}
		return out;
	}

	/** A shallow copy of `obj` without the named keys, so a spec's editor-only fields are dropped. */
	function copyExcept(obj, omit) {
		var out = {};
		Object.keys(obj).forEach(function (key) {
			if (omit.indexOf(key) < 0) {
				out[key] = obj[key];
			}
		});
		return out;
	}

	/**
	 * Serialise an IR segment easing to the datapack shape (design §15.4): a snapped NAME stays a
	 * string, a numeric bezier `[x1,y1,x2,y2]` becomes `{ "cubicBezier": [...] }` (the form
	 * `VFXDefinition.parseEasing` accepts). Delegates to `VFXIR.easingToJson` when loaded, with an
	 * inline fallback so the compiler still works standalone.
	 *
	 * @param {string|Array<number>} easing the IR segment easing
	 * @returns {string|Object|null} the JSON value, or null for an unrecognised easing
	 */
	function easingJson(easing) {
		if (root.VFXIR && typeof root.VFXIR.easingToJson === "function") {
			return root.VFXIR.easingToJson(easing);
		}
		if (typeof easing === "string") {
			return easing;
		}
		if (Array.isArray(easing) && easing.length === 4) {
			var out = [];
			for (var i = 0; i < 4; i++) {
				var n = Number(easing[i]);
				if (!isFinite(n)) {
					return null;
				}
				out.push(n);
			}
			return { cubicBezier: out };
		}
		if (easing && typeof easing === "object" && Array.isArray(easing.cubicBezier)
				&& easing.cubicBezier.length === 4) {
			return { cubicBezier: easing.cubicBezier.map(Number) };
		}
		return null;
	}

	/** Whether `value` is a graph input reference `{ from: id }`. */
	function isRefValue(value) {
		return value != null && typeof value === "object" && value.from != null;
	}

	/**
	 * Serialise one param slot, deriving the datapack form from the IR data (design §15.1/§15.8)
	 * instead of the removed editor-only `control` discriminator:
	 *
	 * - a non-empty key list is a **track** → `{ "keyframes": [...] }` (I1; never start/end or fade);
	 * - a `bind` field is a **driven** binding → `{ "bind": ..., <options> }`;
	 * - an `expr` field is a **driven** expression → `{ "expr": "..." }`;
	 * - an imported `start`/`end` pair stays `{ "start": x, "end": y }`;
	 * - otherwise it is an **inline** constant → a bare number (`{ "value": x }` when a `multiply`
	 *   keeps it an object).
	 *
	 * An optional `multiply` (a bound spec) is layered on top of any form, and an unrecognised
	 * object keeps every non-`control` field so an imported raw spec still round-trips.
	 *
	 * @param {string} name param name (looks up `Tab.keys` for the track form)
	 * @param {Object|number} spec the slot spec
	 * @param {Object} keys the tab's `keys` map
	 * @param {number} total the effect duration in ticks (keyframe clamp, I3)
	 * @param {Array} [warnings] collects a record per clamped key (I3)
	 * @returns {number|Object} the datapack param value
	 */
	function compileParam(name, spec, keys, total, warnings) {
		var multiply = spec && typeof spec === "object" ? spec.multiply : null;
		// I1: a slot with >= 1 keyframe serialises ONLY as keyframes, whatever else the spec holds.
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
		var out;
		if (spec.bind != null) {
			out = copyExcept(spec, ["control", "multiply", "value", "expr"]);
		} else if (spec.expr != null) {
			out = { expr: String(spec.expr) };
		} else if (spec.start != null || spec.end != null) {
			out = { start: num(spec.start), end: num(spec.end) };
		} else if (spec.value !== undefined) {
			if (!multiply) {
				return num(spec.value);
			}
			out = { value: num(spec.value) };
		} else {
			out = copyExcept(spec, ["control", "multiply"]);
		}
		if (Object.keys(out).length === 0) {
			return multiply ? { value: 0, multiply: multiply } : 0;
		}
		if (multiply) {
			out.multiply = multiply;
		}
		return out;
	}

	/**
	 * Peephole-fold a param's driver (design §15.8): a `constant`, `bind` or `expr` value node that
	 * is a leaf (not driven itself) feeding exactly one effect param, and not shared, collapses into
	 * a direct `value`/`bind`/`expr` param so the exported graph stays minimal. A bind node carrying
	 * a `fallback` cannot fold (the param-bind form has no fallback), so it stays a graph node.
	 *
	 * @param {Object} tab the tab
	 * @returns {{specs: Object, skip: Object<string, boolean>}} folded param specs by name and the
	 *          node ids to omit from the emitted graph
	 */
	function foldDrivers(tab) {
		var specs = {};
		var skip = {};
		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var edges = Array.isArray(tab.edges) ? tab.edges : [];
		var outEdges = {};
		var incoming = {};
		edges.forEach(function (edge) {
			if (edge == null || edge.from == null || edge.to == null) {
				return;
			}
			if (!outEdges[edge.from]) {
				outEdges[edge.from] = [];
			}
			outEdges[edge.from].push(edge);
			incoming[edge.to] = (incoming[edge.to] || 0) + 1;
		});
		nodes.forEach(function (node) {
			if (node.kind !== "bind" && node.kind !== "expr" && node.kind !== "constant") {
				return;
			}
			if (incoming[node.id]) {
				return;
			}
			var outs = outEdges[node.id] || [];
			if (outs.length !== 1 || outs[0].input != null) {
				return;
			}
			var param = outs[0].to;
			var drivers = edges.filter(function (candidate) {
				return candidate.to === param && candidate.input == null;
			});
			if (drivers.length !== 1) {
				return;
			}
			var inputs = node.inputs || {};
			var spec;
			if (node.kind === "expr") {
				spec = { expr: String(inputs.expr == null ? "" : inputs.expr) };
			} else if (node.kind === "constant") {
				spec = { value: num(inputs.value) };
			} else {
				if (inputs.fallback != null) {
					return;
				}
				spec = copyExcept(inputs, []);
				if (spec.bind == null) {
					return;
				}
			}
			specs[param] = spec;
			skip[node.id] = true;
		});
		return { specs: specs, skip: skip };
	}

	/**
	 * Split `Tab.valueNodes`/`Tab.edges` into the datapack `graph` and the top-level `inputs`
	 * wiring. An edge whose `to` is a value-node id is a graph edge; any other target is an
	 * effect param and becomes `inputs[to] = { from }`.
	 *
	 * @param {Object} tab the tab
	 * @param {Object<string, boolean>} [skip] node ids already folded into a param (design §15.8)
	 * @returns {{nodes: Array, edges: Array, meta: Object, inputs: Object}}
	 */
	function compileGraph(tab, skip) {
		skip = skip || {};
		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var ids = {};
		var graphNodes = [];
		var meta = {};
		nodes.forEach(function (node) {
			if (skip[node.id]) {
				return;
			}
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
			if (skip[edge.from] || skip[edge.to]) {
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
		// Fold bind/expr leaf drivers into their param before serialising (design §15.8), so a
		// driven slot exports as a direct `bind`/`expr` and its node is left out of the graph.
		var folded = foldDrivers(tab);
		var source = tab.params || {};
		Object.keys(source).forEach(function (name) {
			var spec = folded.specs[name] != null ? folded.specs[name] : source[name];
			def.params[name] = compileParam(name, spec, tab.keys || {}, def.duration, warnings);
		});
		Object.keys(folded.specs).forEach(function (name) {
			if (!Object.prototype.hasOwnProperty.call(def.params, name)) {
				def.params[name] = compileParam(name, folded.specs[name], tab.keys || {}, def.duration, warnings);
			}
		});
		lastWarnings = warnings;

		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var activeNodes = nodes.filter(function (node) {
			return !folded.skip[node.id];
		});
		if (activeNodes.length > 0) {
			var built = compileGraph(tab, folded.skip);
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

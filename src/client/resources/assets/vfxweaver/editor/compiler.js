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

	/**
	 * Copy a curve node's `points` into the datapack shape (design §15.4): clamp each time to
	 * `[0,duration]`, enforce strictly-ascending times (a clamped/duplicate point is dropped), cap
	 * the list at 64 (the engine's `VFXGraph.MAX_CURVE_POINTS`), and convert each IR easing to the
	 * JSON form (a name stays a string, `[x1,y1,x2,y2]` becomes `{cubicBezier:[...]}`). Warnings are
	 * pushed for a clamp/drop when `warnings` is supplied.
	 *
	 * @param {Array<Object>} points IR curve points (`{time,value,easing}`)
	 * @param {number} total the effect duration in ticks
	 * @param {Array} [warnings] collects a record per clamp/dropped point
	 * @param {string} [name] the node id for the warning record
	 * @returns {Array<Object>} the datapack curve points (<=64, strictly ascending)
	 */
	function curvePointsJson(points, total, warnings, name) {
		var out = [];
		if (!Array.isArray(points)) {
			return out;
		}
		var max = num(total);
		if (max < 0) {
			max = 0;
		}
		var lastTime = -Infinity;
		for (var i = 0; i < points.length; i++) {
			if (out.length >= 64) {
				if (warnings) {
					warnings.push({ param: name, index: i, dropped: true, reason: "cap-64" });
				}
				break;
			}
			var point = points[i] || {};
			var raw = num(point.time);
			var time = clampKeyTime(raw, max);
			if (time !== raw && warnings) {
				warnings.push({ param: name, index: i, time: raw, clamped: time });
			}
			if (out.length && time <= lastTime) {
				if (warnings) {
					warnings.push({ param: name, index: i, dropped: true, reason: "not-ascending" });
				}
				continue;
			}
			var entry = { time: time, value: num(point.value) };
			if (point.easing != null) {
				var easing = easingJson(point.easing);
				if (easing != null) {
					entry.easing = easing;
				}
			}
			out.push(entry);
			lastTime = time;
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

	/** Whether a value-node kind belongs to the field tree (never in the value graph). */
	function isFieldKind(kind) {
		if (root.VFXIR && typeof root.VFXIR.isFieldKind === "function") {
			return root.VFXIR.isFieldKind(kind);
		}
		return kind === "field" || kind === "field_op";
	}

	/** Whether a name is a reserved mask slot (`mask.p<N>.*`), which never lands in `params`/`inputs`. */
	function isMaskParam(name) {
		return typeof name === "string" && name.indexOf("mask.") === 0;
	}

	/**
	 * Whether an edge belongs to the effect being compiled (design §15.2, P6). A param wire in a
	 * chain carries `effect` = the owning node id; a single-effect tab (or a legacy file) leaves it
	 * absent. A node->node edge (`input != null`) is shared by every effect and always in scope.
	 */
	function edgeInScope(edge, effectId, multi) {
		if (edge == null) {
			return false;
		}
		if (edge.input != null) {
			return true;
		}
		if (effectId == null) {
			return true;
		}
		if (edge.effect === effectId) {
			return true;
		}
		// A legacy/untagged param wire only belongs to the single (non-chain) effect.
		return !multi && edge.effect == null;
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
	function foldDrivers(tab, effectId, multi) {
		var specs = {};
		var skip = {};
		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var edges = (Array.isArray(tab.edges) ? tab.edges : []).filter(function (edge) {
			return edgeInScope(edge, effectId, multi);
		});
		var outEdges = {};
		var incoming = {};
		(Array.isArray(tab.edges) ? tab.edges : []).forEach(function (edge) {
			if (edge == null || edge.from == null || edge.to == null) {
				return;
			}
			incoming[edge.to] = (incoming[edge.to] || 0) + 1;
		});
		edges.forEach(function (edge) {
			if (!outEdges[edge.from]) {
				outEdges[edge.from] = [];
			}
			outEdges[edge.from].push(edge);
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
			if (isMaskParam(param)) {
				// A node driving a mask slot is not folded into a param: the mask compiler writes the
				// `{ "from": node }` reference into the mask leaf instead (design §13.1).
				return;
			}
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
	 * The source value-node id wired into a mask slot of the effect being compiled, or null. Mask
	 * wires are param wires (`input == null`, `to` = a `mask.p<N>.*` name) and are scoped like any
	 * other param wire.
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the reserved slot name
	 * @param {string|null} effectId the owning effect id
	 * @param {boolean} multi whether the tab is a chain
	 * @returns {string|null} the source node id, or null when the slot is an inline constant
	 */
	function maskEdgeSource(tab, name, effectId, multi) {
		var edges = tab && Array.isArray(tab.edges) ? tab.edges : [];
		for (var i = 0; i < edges.length; i++) {
			var edge = edges[i];
			if (edge && edge.input == null && edge.to === name && edge.from != null
					&& edgeInScope(edge, effectId, multi)) {
				return edge.from;
			}
		}
		return null;
	}

	/**
	 * Serialise the effect's authored mask (design §13.1). The IR holds a datapack-shaped mask; a
	 * wired slot (`mask.p<N>.*`) is written into the matching leaf field as `{ "from": node }`, and
	 * `mask.` edges never land in `params`/`inputs`. The leaf order is the canvas operand wiring
	 * (`effect.maskWires`, operand -> leaf index): an unwired operand fails the whole mask — the
	 * shell reports `Mask operand N is not wired` and the effect compiles without it. Without
	 * wiring (an old project) the authored order stands, so migration emits byte-identical JSON.
	 * An effect with no mask authored keeps whatever the cloned source definition carried, so an
	 * imported mask round-trips; an explicit `null` removes it.
	 *
	 * @param {Object} tab the owning tab
	 * @param {Object|null} effect the effect entry (carries `mask`, `maskWires`)
	 * @param {string|null} effectId the effect id param wires are tagged with
	 * @param {boolean} multi whether the tab is a chain
	 * @param {Object} def the definition being built (mutated)
	 */
	function compileMask(tab, effect, effectId, multi, def) {
		var mask = effect ? effect.mask : undefined;
		if (mask === undefined) {
			return;
		}
		if (mask === null) {
			delete def.mask;
			return;
		}
		var built = clone(mask);
		var vfxir = root.VFXIR;
		if (vfxir && typeof vfxir.maskSlots === "function") {
			var leaves = typeof vfxir.maskLeaves === "function" ? vfxir.maskLeaves(built) : [];
			var centerDefaults = typeof vfxir.maskCenterDefaults === "function"
					? vfxir.maskCenterDefaults : function () { return [0, 0, 0]; };
			vfxir.maskSlots(built).forEach(function (slot) {
				var from = maskEdgeSource(tab, slot.name, effectId, multi);
				if (from == null) {
					return;
				}
				var leaf = leaves[slot.leafIndex];
				if (!leaf) {
					return;
				}
				if (slot.kind === "center") {
					if (!Array.isArray(leaf.center)) {
						leaf.center = centerDefaults(slot.space).slice();
					}
					leaf.center[slot.fieldIndex] = { from: from };
				} else {
					leaf[slot.field] = { from: from };
				}
			});
		}
		built = applyMaskWireOrder(built, effect ? effect.maskWires : undefined);
		if (built == null) {
			delete def.mask;
			return;
		}
		def.mask = built;
	}

	/**
	 * Reorder a finished mask to the canvas operand wiring, or fail it. `wires` maps each
	 * operand port to its leaf index (or null when unwired): any null skips the whole mask
	 * (returns null), an absent/mismatched mapping keeps the authored order untouched so old
	 * projects emit byte-identical JSON, and an identity mapping skips the rebuild so key
	 * order never churns.
	 *
	 * @param {Object} built the datapack mask with `{from}` refs applied
	 * @param {Array|null|undefined} wires the operand -> leaf index mapping
	 * @returns {Object|null} the ordered mask, null when an operand is not wired
	 */
	function applyMaskWireOrder(built, wires) {
		if (wires === undefined || wires === null) {
			return built;
		}
		var vfxir = root.VFXIR;
		if (!vfxir || typeof vfxir.maskLeaves !== "function"
				|| typeof vfxir.maskNodeState !== "function"
				|| typeof vfxir.maskFromNodeState !== "function") {
			return built;
		}
		var leaves = vfxir.maskLeaves(built);
		if (!Array.isArray(wires) || wires.length !== leaves.length) {
			return built;
		}
		var identity = true;
		var seen = {};
		for (var i = 0; i < wires.length; i++) {
			if (wires[i] == null || wires[i] < 0 || wires[i] >= leaves.length) {
				return null;
			}
			if (seen[wires[i]]) {
				return built;
			}
			seen[wires[i]] = true;
			if (wires[i] !== i) {
				identity = false;
			}
		}
		if (identity || leaves.length < 2) {
			return built;
		}
		var chain = vfxir.maskNodeState(built);
		var ordered = wires.map(function (li) { return chain.leaves[li]; });
		return vfxir.maskFromNodeState({ invert: chain.invert, space: chain.space,
			leaves: ordered, ops: chain.ops });
	}

	/**
	 * Serialise the tab's authored subgraph macros (design §13.5) as the top-level `subgraphs`
	 * block. A tab without macros emits no block, so existing graphs compile byte-identical. Caps
	 * come from the IR (`subgraphWarnings`: 64 macros, depth 8) and are collected into `warnings`,
	 * never refused; past 64 only the first 64 are emitted. A tab whose `subgraphs` is not an
	 * array (a pre-macro snapshot) leaves the cloned source untouched, so an AUTHOR/export
	 * round-trip never drops an imported block.
	 *
	 * @param {Object} tab the owning tab
	 * @param {Object} def the definition being built (mutated)
	 * @param {Array} warnings collects cap records
	 */
	function compileSubgraphs(tab, def, warnings) {
		if (!Array.isArray(tab.subgraphs)) {
			return;
		}
		if (root.VFXIR && typeof root.VFXIR.subgraphWarnings === "function") {
			root.VFXIR.subgraphWarnings(tab).forEach(function (record) {
				warnings.push(record);
			});
		}
		if (!tab.subgraphs.length) {
			delete def.subgraphs;
			return;
		}
		def.subgraphs = clone(tab.subgraphs.slice(0, 64));
	}

	/** A non-blank string (an optional top-level id/selector). */
	function isNonEmptyString(value) {
		return typeof value === "string" && value.trim() !== "";
	}

	/**
	 * Serialise the effect's authored structural fields (design §13.4): `positions`, `region`,
	 * `entity_selector`, `sound` and `sound_pos`, plus the tail slice — the `particle`/`shape`/
	 * `block`/`item` strings, the `pattern`/`surface` blocks, the `anchor` string, the window spec
	 * fields (`id`/`texture`/`titles`) and the screen-image `texture`. Each is emitted only when
	 * the IR carries it (`!== undefined`), so an imported definition's fields survive untouched
	 * through the source clone when the editor did not touch them; an explicit empty/null value
	 * removes the field. The shapes match {@code VFXDefinition.parse} exactly (the parser is the
	 * oracle): `positions` a non-empty list, `region` six ints, `entity_selector`/`sound`/
	 * `particle`/`shape`/`block`/`item`/`anchor` a string, `sound_pos` three, `pattern`/`surface`
	 * objects, `titles` a string list.
	 *
	 * @param {Object|null} effect the effect entry (carries the structural fields), or null
	 * @param {Object} def the definition being built (mutated)
	 */
	function compileStructural(effect, def) {
		if (!effect) {
			return;
		}
		if (effect.positions !== undefined) {
			if (Array.isArray(effect.positions) && effect.positions.length) {
				def.positions = effect.positions;
			} else {
				delete def.positions;
			}
		}
		if (effect.region !== undefined) {
			if (Array.isArray(effect.region) && effect.region.length === 6) {
				def.region = effect.region;
			} else {
				delete def.region;
			}
		}
		if (effect.entitySelector !== undefined) {
			if (isNonEmptyString(effect.entitySelector)) {
				def.entity_selector = effect.entitySelector;
			} else {
				delete def.entity_selector;
			}
		}
		if (effect.sound !== undefined) {
			if (isNonEmptyString(effect.sound)) {
				def.sound = effect.sound;
			} else {
				delete def.sound;
			}
		}
		if (effect.soundPos !== undefined) {
			if (Array.isArray(effect.soundPos) && effect.soundPos.length === 3) {
				def.sound_pos = effect.soundPos;
			} else {
				delete def.sound_pos;
			}
		}
		["particle", "shape", "block", "item", "anchor"].forEach(function (name) {
			if (effect[name] !== undefined) {
				if (isNonEmptyString(effect[name])) {
					def[name] = effect[name];
				} else {
					delete def[name];
				}
			}
		});
		["pattern", "surface"].forEach(function (name) {
			if (effect[name] !== undefined) {
				if (effect[name] != null && typeof effect[name] === "object"
						&& Object.keys(effect[name]).length) {
					def[name] = effect[name];
				} else {
					delete def[name];
				}
			}
		});
		if (effect.windowId !== undefined) {
			if (isNonEmptyString(effect.windowId)) {
				def.id = effect.windowId;
			} else {
				delete def.id;
			}
		}
		if (effect.windowTitles !== undefined) {
			if (Array.isArray(effect.windowTitles) && effect.windowTitles.length) {
				def.titles = effect.windowTitles;
			} else {
				delete def.titles;
			}
		}
		// The top-level `texture` serves the window spec and the screen-image spec (only one
		// sub-editor is ever visible — flag-gated per type), so one IR field emits it.
		if (effect.texture !== undefined) {
			if (isNonEmptyString(effect.texture)) {
				def.texture = effect.texture;
			} else {
				delete def.texture;
			}
		}
	}

	/**
	 * Split `Tab.valueNodes`/`Tab.edges` into the datapack `graph` and the top-level `inputs`
	 * wiring. An edge whose `to` is a value-node id is a graph edge; any other target is an
	 * effect param and becomes `inputs[to] = { from }`.
	 *
	 * @param {Object} tab the tab
	 * @param {Object<string, boolean>} [skip] node ids already folded into a param (design §15.8)
	 * @param {string|null} [effectId] the owning chain node (param wires are tagged with it)
	 * @param {boolean} [multi] whether the tab is a chain
	 * @param {number} [total] the effect duration in ticks (curve clamp, `[0,duration]`)
	 * @param {Array} [warnings] collects curve clamp/drop records
	 * @returns {{nodes: Array, edges: Array, meta: Object, inputs: Object}}
	 */
	function compileGraph(tab, skip, effectId, multi, total, warnings) {
		skip = skip || {};
		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var ids = {};
		var kinds = {};
		var graphNodes = [];
		var meta = {};
		nodes.forEach(function (node) {
			kinds[node.id] = node.kind;
			if (isFieldKind(node.kind)) {
				// Field-tree nodes are a separate kind set (design §13.2): the field compiler
				// owns them, so they never land in the value `graph` block.
				return;
			}
			if (skip[node.id]) {
				return;
			}
			var inputs = node.inputs || {};
			if (node.kind === "curve" && Array.isArray(inputs.points)) {
				// A curve node's points compile like a timeline track: clamp `[0,duration]`,
				// strictly-ascending, <=64 points, easing as a name or `{cubicBezier}` (design §15.4).
				var built = {};
				Object.keys(inputs).forEach(function (key) {
					built[key] = inputs[key];
				});
				built.points = curvePointsJson(inputs.points, total, warnings, node.id);
				inputs = built;
			}
			var out = { id: node.id, kind: node.kind, inputs: inputs };
			if (node.op != null) {
				out.op = node.op;
			}
			if (node.kind === "subgraph" && node.subgraph != null) {
				// A macro instance carries its macro id beside the bindings; the engine
				// (`VFXSubgraphExpander`) reads `subgraph` + `inputs`, never `op`.
				out.subgraph = String(node.subgraph);
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
			if (!edgeInScope(edge, effectId, multi)) {
				return;
			}
			if (skip[edge.from] || skip[edge.to]) {
				return;
			}
			if (isFieldKind(kinds[edge.from]) || isFieldKind(kinds[edge.to])) {
				// A wire touching a field node belongs to the field tree (design §13.2),
				// never to the value graph or its `inputs` block.
				return;
			}
			if (ids[edge.to]) {
				var out = { from: edge.from, to: edge.to };
				if (edge.input != null) {
					out.input = edge.input;
				}
				if (edge.output != null) {
					// A wire off a non-default macro output names it (design §13.5); the
					// expander rewrites `from` to the output's node, `VFXGraph` ignores the key.
					out.output = edge.output;
				}
				graphEdges.push(out);
			} else if (!isMaskParam(edge.to)) {
				if (edge.output != null && kinds[edge.from] === "subgraph") {
					// A param wire off a non-default macro output cannot ride the `inputs`
					// block (the engine aliases it to the first output only), so it stays a
					// graph edge: `VFXGraph` reads it as an effect-input ref by its `to`.
					graphEdges.push({ from: edge.from, to: edge.to, output: edge.output });
				} else {
					inputs[edge.to] = { from: edge.from };
				}
			}
		});
		return { nodes: graphNodes, edges: graphEdges, meta: meta, inputs: inputs };
	}

	/** Warning records from the most recent {@link compileTab} (clamped keyframes, I3). */
	var lastWarnings = [];

	/** Lowercase a display name into the `[a-z0-9_.-]` a datapack file name allows. */
	function safeName(value) {
		return String(value == null ? "" : value).trim().toLowerCase()
				.replace(/[^a-z0-9_.-]+/g, "_").replace(/^_+|_+$/g, "");
	}

	/** The effect-name part of a tab name (`<namespace>:<name>` -> `<name>`). */
	function effectName(tab) {
		var name = tab && tab.name ? String(tab.name) : "untitled";
		var colon = name.indexOf(":");
		return colon > 0 && colon < name.length - 1 ? name.substring(colon + 1) : name;
	}

	/** The project namespace when the shell is up, else a safe default. */
	function namespaceOf(options) {
		if (options && options.namespace) {
			return String(options.namespace);
		}
		var project = root.VFXEditor && root.VFXEditor.project;
		var ns = project && project.namespace ? String(project.namespace) : "";
		return safeName(ns) || "vfx_project";
	}

	/** The file base name for a tab's chain (`options.baseName`, else the sanitised tab name). */
	function baseNameOf(tab, options) {
		if (options && options.baseName) {
			return safeName(options.baseName);
		}
		return safeName(effectName(tab)) || "effect";
	}

	/** A child effect's effective duration: its own when set, else the root/tab duration. */
	function childDurationTicks(tab, effect) {
		var own = num(effect && effect.duration, 0);
		return own > 0 ? own : num(tab.duration, 40);
	}

	/**
	 * The tab's effects in application order with mute/solo applied (design §15.2, P6): when any node
	 * is soloed only the soloed nodes play, and muted nodes never do. Order is preserved.
	 *
	 * @param {Object} tab the tab
	 * @returns {Array<Object>} the active chain (possibly empty)
	 */
	function activeChain(tab) {
		var all = (tab.effects || []).filter(function (effect) {
			return effect && effect.type;
		});
		var soloed = all.filter(function (effect) {
			return effect.solo === true;
		});
		var base = soloed.length ? soloed : all;
		return base.filter(function (effect) {
			return effect.mute !== true;
		});
	}

	/**
	 * Compile one effect's params (design §15.1/§15.8), folding leaf drivers into the param spec and
	 * carrying a slot's `multiply` onto the folded (driven) form. Shared by {@link compileSingle}
	 * (which also emits the graph) and by a chain child imported from an existing definition.
	 *
	 * @param {Object} tab the owning tab
	 * @param {Object|null} effect the effect entry, or null for a legacy/single tab
	 * @param {string|null} effectId the effect id param wires are tagged with (null = legacy)
	 * @param {boolean} multi whether the tab is a chain
	 * @param {number} duration the effect duration in ticks (keyframe clamp, I3)
	 * @returns {{value: Object, warnings: Array, folded: Object}} the params object plus the fold data
	 */
	function compileParamMap(tab, effect, effectId, multi, duration) {
		var warnings = [];
		var folded = foldDrivers(tab, effectId, multi);
		var source = effect ? effect.params : tab.params;
		source = source || {};
		var keys = effect ? effect.keys : tab.keys;
		keys = keys || {};
		var params = {};
		Object.keys(source).forEach(function (name) {
			// The `mask.` prefix is reserved (design §13.1): a user param with it is shadowed, never
			// emitted as a normal effect param.
			if (isMaskParam(name)) {
				return;
			}
			var raw = source[name];
			var spec = folded.specs[name] != null ? folded.specs[name] : raw;
			// §15.8: `multiply` layers on top of ANY form, including a driven slot whose form was
			// folded to `{bind|expr|value}` — carry the slot's multiplier onto the folded spec.
			var multiply = raw && typeof raw === "object" ? raw.multiply : null;
			if (multiply && spec && typeof spec === "object" && spec.multiply == null) {
				spec = copyExcept(spec, []);
				spec.multiply = multiply;
			}
			params[name] = compileParam(name, spec, keys, duration, warnings);
		});
		Object.keys(folded.specs).forEach(function (name) {
			if (isMaskParam(name)) {
				return;
			}
			if (!Object.prototype.hasOwnProperty.call(params, name)) {
				params[name] = compileParam(name, folded.specs[name], keys, duration, warnings);
			}
		});
		return { value: params, warnings: warnings, folded: folded };
	}

	/**
	 * Serialise the effect's authored per-pixel fields (design §13.2). Each entry of the IR map
	 * `effect.fields` (`param -> {field}|{op}`) is written into `def.inputs[param]`; a value-graph
	 * wire to the same param loses (one `inputs` entry only) with a warning. Caps come from the
	 * schema and violations warn, never refuse. A depth/world field off screen layer 0 warns
	 * once per param (the engine falls back to neutral there).
	 *
	 * <p>An effect that never authored fields keeps the hand-written field blocks its imported
	 * source carried (clone pass-through): the compiler used to drop every `inputs` field entry,
	 * so without this an AUTHOR/export round-trip lost them. An explicit `null` entry (or
	 * `fields: null`) removes the carried block.
	 *
	 * @param {Object} tab the owning tab
	 * @param {Object|null} effect the effect entry (carries `fields`), or null for a legacy tab
	 * @param {Object} def the definition being built (mutated)
	 * @param {Object|null} sourceDef the imported definition to round-trip, or null
	 * @param {Array} warnings collects field records (cap/layer/over-wire)
	 */
	function compileFields(tab, effect, def, sourceDef, warnings) {
		var vfxir = root.VFXIR;
		var authored = effect && effect.fields && typeof effect.fields === "object" ? effect.fields : undefined;
		if (authored === undefined && !(sourceDef && sourceDef.inputs)) {
			return;
		}
		var srcInputs = sourceDef && sourceDef.inputs && typeof sourceDef.inputs === "object"
				&& !Array.isArray(sourceDef.inputs) ? sourceDef.inputs : {};
		var merged = {};
		Object.keys(srcInputs).forEach(function (name) {
			var entry = srcInputs[name];
			if (entry && typeof entry === "object" && (entry.field != null || entry.op != null)) {
				merged[name] = clone(entry);
			}
		});
		if (authored) {
			Object.keys(authored).forEach(function (name) {
				if (authored[name] == null) {
					delete merged[name];
				} else {
					merged[name] = clone(authored[name]);
				}
			});
		} else if (effect && effect.fields === null) {
			Object.keys(merged).forEach(function (name) {
				delete merged[name];
			});
		}
		var names = Object.keys(merged);
		if (!names.length) {
			return;
		}
		if (!def.inputs || typeof def.inputs !== "object") {
			def.inputs = {};
		}
		var layer = screenLayerOf(effect);
		names.forEach(function (name) {
			if (def.inputs[name] && def.inputs[name].from != null) {
				warnings.push({ param: name, reason: "field-over-wire", from: def.inputs[name].from });
			}
			def.inputs[name] = merged[name];
			if (vfxir && typeof vfxir.fieldCapWarnings === "function") {
				vfxir.fieldCapWarnings(name, merged[name], layer).forEach(function (record) {
					warnings.push(record);
				});
			}
		});
	}

	/**
	 * Compile one effect (a child of a chain, or the tab's only effect) into a datapack definition.
	 *
	 * @param {Object} tab the owning tab (timing + value graph live here)
	 * @param {Object|null} effect the effect entry, or null for a legacy/single tab
	 * @param {string|null} effectId the effect id param wires are tagged with (null = legacy)
	 * @param {Object|null} sourceDef the imported definition to round-trip, or null
	 * @returns {string} the definition JSON text
	 */
	function compileSingle(tab, effect, effectId, sourceDef) {
		var multi = Boolean(tab.effects && tab.effects.length > 1);
		var def = clone(sourceDef) || {};
		var type = effect ? effect.type : tab.type;
		def.type = type == null ? "" : String(type);
		def.duration = effect ? childDurationTicks(tab, effect) : num(tab.duration, 40);
		// Timing is per node (design §15.3, fix H4): a chain child owns its loop/persistent/fade_ticks;
		// a single-effect tab (or the root) owns the tab-level values, so single-effect behaviour is
		// unchanged. The collection root is emitted by `compileCollection` and keeps the tab values.
		var perNode = multi && effect != null;
		def.loop = perNode ? Boolean(effect.loop) : Boolean(tab.loop);
		def.persistent = perNode ? Boolean(effect.persistent) : Boolean(tab.persistent);
		def.fade_ticks = perNode ? num(effect.fade_ticks, 0) : num(tab.fade_ticks, 0);
		def.easing = (effect && effect.easing != null ? effect.easing : tab.easing);
		if (def.easing == null) {
			def.easing = "linear";
		}
		var built = compileParamMap(tab, effect, effectId, multi, def.duration);
		def.params = built.value;
		var warnings = built.warnings;
		var folded = built.folded;
		lastWarnings = warnings;

		var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
		var activeNodes = nodes.filter(function (node) {
			return !folded.skip[node.id];
		});
		if (activeNodes.length > 0) {
			var graphBuilt = compileGraph(tab, folded.skip, effectId, multi, def.duration, warnings);
			var graph = def.graph && typeof def.graph === "object" && !Array.isArray(def.graph) ? def.graph : {};
			if (graph.version == null) {
				graph.version = 1;
			}
			graph.nodes = graphBuilt.nodes;
			graph.edges = graphBuilt.edges;
			if (Object.keys(graphBuilt.meta).length > 0) {
				graph.meta = graphBuilt.meta;
			} else {
				delete graph.meta;
			}
			def.graph = graph;
			if (Object.keys(graphBuilt.inputs).length > 0) {
				def.inputs = graphBuilt.inputs;
			} else {
				delete def.inputs;
			}
		} else {
			delete def.graph;
			delete def.inputs;
		}
		// Optional mask (design §13.1): authored on the IR, or carried by the cloned imported source.
		compileMask(tab, effect, effectId, multi, def);
		// Optional subgraph macros (design §13.5): authored on the IR, or carried by the clone.
		compileSubgraphs(tab, def, warnings);
		// Optional structural fields (design §13.4): positions/region, entity_selector, sound/sound_pos.
		compileStructural(effect, def);
		// Optional per-pixel fields (design §13.2): authored on the IR, or carried by the clone.
		compileFields(tab, effect, def, sourceDef, warnings);
		return JSON.stringify(def, null, "\t");
	}

	/**
	 * Compile a chain into a root `type:"collection"` (design §15.2, P6): the children are emitted in
	 * chain order, never sorted by delay, each one referencing a *definition id* (`<ns>:<base>__<n>`).
	 * A per-child `order` param pins the post-processing compositing order to the chain order even
	 * when the children start at different delays (the engine sorts a screen layer by `order`).
	 *
	 * @param {Object} tab the tab
	 * @param {Array<Object>} chain the active chain (mute/solo applied)
	 * @param {Object} [options] `{ namespace, baseName }` overrides
	 * @returns {string} the collection root JSON text
	 */
	function compileCollection(tab, chain, options) {
		// A chain root is ONLY the collection's own timing + `effects` (design §15.2): a `tab.source`
		// may be a previously-imported single effect, so cloning it would leak its `sound`/`mask`/
		// `positions`/`particle`/... onto the root (and make the collection play the old sound).
		var def = {
			type: "collection",
			duration: num(tab.duration, 40),
			loop: Boolean(tab.loop),
			persistent: Boolean(tab.persistent),
			fade_ticks: num(tab.fade_ticks, 0),
			easing: tab.easing == null ? "linear" : tab.easing
		};
		var multi = true;
		var namespace = namespaceOf(options);
		var base = baseNameOf(tab, options);
		var effects = [];
		var refTabs = tabsOf(options);
		chain.forEach(function (effect, index) {
			if (isReferenceEffect(effect)) {
				// A reference instances another tab: point at its exported id, never a local
				// `__n` def. A cyclic or dangling reference compiles to nothing.
				var resolved = refTabs
						? resolveExportReference(refTabs, tab, effect, options) : null;
				if (resolved == null) {
					return;
				}
				var refDuration = childDurationTicks(tab, effect);
				var refEntry = {
					effect: resolved,
					delay: num(effect.delay, 0),
					duration: refDuration
				};
				if (effect.easing != null) {
					refEntry.easing = effect.easing;
				}
				var refParams = compileParamMap(tab, effect, effect.id, multi, refDuration).value;
				refParams.order = index;
				refEntry.params = refParams;
				effects.push(refEntry);
				return;
			}
			// A child imported from an existing definition id keeps that id and is NOT re-emitted as a
			// new local `__n` def (its params are carried as overrides); a locally-authored child gets
			// `<ns>:<base>__<index>` plus its own `<base>__<index>.json` file (compileTabFiles).
			var external = effect.external === true && effect.effectId != null;
			var duration = childDurationTicks(tab, effect);
			var entry = {
				effect: external ? String(effect.effectId) : namespace + ":" + base + "__" + index,
				delay: num(effect.delay, 0),
				duration: duration
			};
			if (effect.easing != null) {
				entry.easing = effect.easing;
			}
			var params = {};
			if (external) {
				params = compileParamMap(tab, effect, effect.id, multi, duration).value;
			}
			params.order = index;
			entry.params = params;
			effects.push(entry);
		});
		def.effects = effects;
		return JSON.stringify(def, null, "\t");
	}

	/**
	 * Compile one tab into the datapack effect JSON (tab-indented, as the built-in definitions).
	 *
	 * Timing (`duration`, `loop`, `persistent`, `fade_ticks`) is ALWAYS emitted explicitly, including
	 * defaults, so a consumer never depends on the datapack's hidden defaults (design §15.3).
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @param {Object} [options] `{ namespace, baseName }` for the child reference ids of a chain
	 * @returns {string} the JSON text, ready to POST to `/compile` or write to `data/<ns>/vfx/`
	 */
	function compileTab(tab, options) {
		if (!tab || typeof tab !== "object") {
			throw new Error("compileTab: a tab is required");
		}
		var nodes = (tab.effects || []).filter(function (effect) {
			return effect && effect.type;
		});
		if (nodes.length >= 2) {
			return compileCollection(tab, activeChain(tab), options);
		}
		var effect = nodes.length === 1 ? nodes[0] : null;
		if (effect && effect.mute === true) {
			// A single muted/disabled node still compiles (to a silent collection) rather than falling
			// back to the flat form, so a muted chain never plays.
			return compileCollection(tab, [], options);
		}
		return compileSingle(tab, effect, effect ? effect.id : null,
				effect ? (effect.source || tab.source) : tab.source);
	}

	/**
	 * Compile a tab into the set of files an export writes: `<base>.json` (a flat definition for
	 * one effect, a `collection` root for a chain) plus one `<base>__<n>.json` child definition per
	 * chain node (design §15.2, P6). The root references each child by `<namespace>:<base>__<n>`.
	 *
	 * @param {Object} tab the tab
	 * @param {Object} [options] `{ namespace, baseName }` overrides
	 * @returns {Object<string, string>} file base name -> JSON text
	 */
	function compileTabFiles(tab, options) {
		var nodes = (tab.effects || []).filter(function (effect) {
			return effect && effect.type;
		});
		var base = baseNameOf(tab, options);
		var out = {};
		out[base] = compileTab(tab, options);
		if (nodes.length < 2) {
			return out;
		}
		activeChain(tab).forEach(function (effect, index) {
			// A reference instances another tab (its id is already in the root): no local file.
			// A child imported from an existing definition id is referenced as-is and already ships
			// elsewhere, so no local child file is written for it (design §15.2).
			if (isReferenceEffect(effect)) {
				return;
			}
			if (effect.external === true && effect.effectId != null) {
				return;
			}
			out[base + "__" + index] = compileSingle(tab, effect, effect.id, effect.source);
		});
		return out;
	}

	/**
	 * Compile the PREVIEW artifact: the same IR as {@link compileTab}, but with playback overrides so
	 * editing stays pleasant and the animation is always visible (`loop:true`, `persistent:true`,
	 * `fade_ticks:0`) — the in-game preview never dies and never fades out (design §15.5, fixes
	 * #4/#15, H1). The authored `duration` is kept deliberately: the engine wraps the loop over the
	 * *timeline* duration, so a large sentinel duration made the authored keyframes play once and
	 * then hold their end value (usually 0) for the rest of the sentinel — reading as the preview
	 * decaying to nothing. Looping over the authored duration replays the real animation at the real
	 * timing forever, and `persistent` makes the keep-alive explicit even if a consumer ignores
	 * `loop` (see `VFXActiveEffect.isFinished`). The authored compile is unchanged, so File→Export
	 * still writes the user's real timing.
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @returns {string} the preview effect JSON text
	 */
	function compileForPreview(tab) {
		var def = JSON.parse(compileTab(tab));
		def.loop = true;
		def.persistent = true;
		def.fade_ticks = 0;
		return JSON.stringify(def, null, "\t");
	}

	/* ------------------------------------------------------------------ */
	/* Preview v2 definition set (design §16.1/§16.3)                      */
	/* ------------------------------------------------------------------ */

	/**
	 * The private preview namespace (design §16.1). A datapack definition cannot shadow an id under
	 * this namespace, so the compiled preview is what the engine resolves for a preview play.
	 */
	var PREVIEW_NS = "vfxweaver:editor_preview";

	/**
	 * Effect types that are NOT fullscreen post-processing passes, mirroring
	 * {@code VFXEffectType.isPostProcessing()}. This is only a fallback for when the schema is not
	 * loaded yet: the schema now carries a `post` flag per effect type (the additive
	 * {@code VFXEffectDescriptor.post()}), which is the authority. It decides the `isPost` meta flag
	 * and whether a child contributes to the screen-layer ordering.
	 */
	var NON_POST_TYPES = {
		camera_shake: true, camera_roll: true, block_outline: true, block_tint: true,
		light_beam: true, pulse_ring: true, guide_line: true, particles: true, block_chain: true,
		entity_tint: true, entity_outline: true, entity_displace: true, fov_modifier: true,
		fog_modifier: true, window_create: true, window_control: true, collection: true
	};

	/**
	 * Whether an effect type renders a fullscreen post pass. Reads the authoritative `post` flag from
	 * the loaded schema (`VFXEditor.schema[type].post`, additive — unknown/missing reads as false via
	 * the static fallback), else falls back to {@link #NON_POST_TYPES}.
	 */
	function isPostType(type) {
		if (type == null || type === "") {
			return false;
		}
		var schema = root.VFXEditor && root.VFXEditor.schema;
		var descriptor = schema ? schema[String(type)] : null;
		if (descriptor && typeof descriptor.post === "boolean") {
			return descriptor.post;
		}
		return !NON_POST_TYPES[String(type)];
	}

	/** A node uuid generator, used only when the IR helper is absent. */
	function generateUuid() {
		return "n" + Date.now().toString(36) + Math.floor(Math.random() * 0x7fffffff).toString(36);
	}

	/**
	 * The stable per-node uuid (design §16.1): generated once and remembered on the effect entry (and
	 * therefore persisted by the sidecar/snapshot), so every preview re-compile addresses the child
	 * by the same key. Delegates to `VFXIR.nodeUuid` when the IR is loaded.
	 *
	 * @param {Object} effect an IR effect entry
	 * @returns {string} the stable uuid
	 */
	function nodeUuid(effect) {
		if (!effect) {
			return "node";
		}
		if (root.VFXIR && typeof root.VFXIR.nodeUuid === "function") {
			return root.VFXIR.nodeUuid(effect);
		}
		if (!effect.uuid) {
			effect.uuid = generateUuid();
		}
		return effect.uuid;
	}

	/**
	 * The validated tab slug (`[a-z0-9_.-]+`) used as the middle id segment; falls back to `tab` so a
	 * weird tab name can never produce an illegal Identifier.
	 *
	 * @param {Object} tab the tab
	 * @returns {string} the slug
	 */
	function slugOf(tab) {
		var slug = safeName(effectName(tab));
		return /^[a-z0-9_.-]+$/.test(slug) ? slug : "tab";
	}

	/** The preview child definition id for a node uuid. */
	function previewDefId(slug, uuid) {
		return PREVIEW_NS + "/" + slug + "/" + uuid;
	}

	/** How deep a reference chain may nest in the Exact preview (the architecture limit;
	 * read live off the IR — the single source of truth — with the same value as fallback). */
	var MAX_PREVIEW_DEPTH = root.VFXIR && typeof root.VFXIR.MAX_REFERENCE_DEPTH === "number"
			? root.VFXIR.MAX_REFERENCE_DEPTH : 4;

	/** Whether an effect entry is a reference node (delegates to the IR when loaded). */
	function isReferenceEffect(effect) {
		if (root.VFXIR && typeof root.VFXIR.isReferenceEffect === "function") {
			return root.VFXIR.isReferenceEffect(effect);
		}
		return Boolean(effect) && (effect.type === "reference" || effect.reference != null);
	}

	/** The target tab name of a reference node, or null (delegates to the IR when loaded). */
	function referenceTarget(effect) {
		if (root.VFXIR && typeof root.VFXIR.referenceTarget === "function") {
			return root.VFXIR.referenceTarget(effect);
		}
		var target = effect ? effect.reference : null;
		return typeof target === "string" && target.trim() !== "" ? target : null;
	}

	/**
	 * Every project tab a reference may resolve against: `options.tabs` when the caller passes
	 * it (the selftest does), else the live project. Null when neither is available.
	 */
	function tabsOf(options) {
		if (options && Array.isArray(options.tabs)) {
			return options.tabs;
		}
		var project = root.VFXEditor && root.VFXEditor.project;
		return project && Array.isArray(project.tabs) ? project.tabs : null;
	}

	/** Find a tab by reference target (delegates to the IR when loaded). */
	function findTabByName(tabs, name) {
		if (root.VFXIR && typeof root.VFXIR.findTabByName === "function") {
			return root.VFXIR.findTabByName(tabs, name);
		}
		if (!Array.isArray(tabs) || name == null) {
			return null;
		}
		var want = String(name);
		for (var i = 0; i < tabs.length; i++) {
			if (tabs[i] && tabs[i].name === want) {
				return tabs[i];
			}
		}
		return null;
	}

	/**
	 * Whether resolving `targetName` from `sourceName` loops back to the source: follow every
	 * reference edge out of the target, so a self- or circular reference compiles to nothing.
	 */
	function refIsCyclic(tabs, sourceName, targetName) {
		if (!Array.isArray(tabs) || targetName == null) {
			return false;
		}
		var source = sourceName == null ? null : findTabByName(tabs, sourceName);
		var origin = source ? String(source.name)
				: (sourceName == null ? null : String(sourceName));
		// A null source still reports a target-side self-loop: watch for the target
		// reaching itself (the first pop is the target, so only a revisit counts).
		var targetTab = findTabByName(tabs, targetName);
		var targetCanonical = targetTab ? String(targetTab.name) : String(targetName);
		var entered = false;
		var seen = {};
		var frontier = [targetName];
		var guard = tabs.length + 1;
		while (frontier.length && guard-- > 0) {
			var name = frontier.pop();
			var tab = findTabByName(tabs, name);
			var canonical = tab ? String(tab.name) : String(name);
			if (origin != null ? canonical === origin : entered && canonical === targetCanonical) {
				return true;
			}
			entered = true;
			if (seen[canonical]) {
				continue;
			}
			seen[canonical] = true;
			((tab && tab.effects) || []).forEach(function (effect) {
				var next = referenceTarget(effect);
				if (next) {
					frontier.push(next);
				}
			});
		}
		return false;
	}

	/**
	 * The exported id a reference resolves to: the target's external id when its first concrete
	 * node is itself imported, else `<namespace>:<target base>` (the file the target tab
	 * exports). Null when the target is missing or the edge is cyclic (compiles to nothing).
	 */
	function resolveExportReference(tabs, sourceTab, effect, options) {
		var target = referenceTarget(effect);
		if (!target) {
			return null;
		}
		if (refIsCyclic(tabs, sourceTab ? sourceTab.name : null, target)) {
			return null;
		}
		var tab = findTabByName(tabs, target);
		if (!tab) {
			return null;
		}
		var first = ((tab.effects) || []).filter(function (candidate) {
			return candidate && candidate.type && !isReferenceEffect(candidate);
		})[0];
		if (first && first.external === true && first.effectId != null) {
			return String(first.effectId);
		}
		return namespaceOf(options) + ":" + baseNameOf(tab);
	}

	/**
	 * Resolve one Exact-preview reference to the leaf it plays: follow reference nodes down to
	 * the first concrete effect (delays accumulate), at most {@link MAX_PREVIEW_DEPTH} hops.
	 *
	 * @returns {{tab:Object,effect:Object,delay:number}|{external:string}|{problem:string}}
	 */
	function resolvePreviewReference(tabs, sourceTab, effect) {
		var label = effectName(sourceTab);
		var target = referenceTarget(effect);
		if (!target) {
			return { problem: "Reference node in tab '" + label + "' has no target. Pick a tab." };
		}
		if (refIsCyclic(tabs, sourceTab ? sourceTab.name : null, target)) {
			return { problem: "Tab '" + label + "' references itself through '" + target
					+ "'. Remove the reference node." };
		}
		var delay = num(effect.delay, 0);
		var tab = findTabByName(tabs, target);
		var depth = 0;
		while (tab) {
			var next = ((tab.effects) || []).filter(function (candidate) {
				return candidate && candidate.type;
			})[0];
			if (!next) {
				return { problem: "Tab '" + effectName(tab)
						+ "' is empty. Add an effect or retarget the reference." };
			}
			if (!isReferenceEffect(next)) {
				if (next.external === true && next.effectId != null) {
					return { external: String(next.effectId) };
				}
				return { tab: tab, effect: next, delay: delay + num(next.delay, 0) };
			}
			depth++;
			if (depth > MAX_PREVIEW_DEPTH) {
				return { problem: "Tab '" + label + "' nests deeper than " + MAX_PREVIEW_DEPTH
						+ ". Flatten to " + MAX_PREVIEW_DEPTH + " or fewer levels." };
			}
			delay += num(next.delay, 0);
			var following = referenceTarget(next);
			tab = following ? findTabByName(tabs, following) : null;
			if (!tab) {
				return { problem: "Reference target '" + following
						+ "' is missing. Fix the reference in tab '" + label + "'." };
			}
		}
		return { problem: "Reference target '" + target + "' is missing. Fix the reference in tab '"
				+ label + "'." };
	}

	/**
	 * One Exact child definition for a concrete effect (the per-node timing overrides of
	 * {@link compilePreviewSet}, shared by the plain and the reference-aware path).
	 */
	function exactChildDef(tab, effect, slug) {
		var uuid = nodeUuid(effect);
		var duration = childDurationTicks(tab, effect);
		var exact = JSON.parse(compileSingle(tab, effect, effect.id, effect.source));
		exact.duration = duration;
		exact.fade_ticks = 0;
		exact.loop = false;
		exact.persistent = true;
		if (exact.params && Object.prototype.hasOwnProperty.call(exact.params, "order")) {
			delete exact.params.order;
		}
		return { id: previewDefId(slug, uuid), json: JSON.stringify(exact), uuid: uuid,
			duration: duration, isPost: isPostType(effect.type) };
	}

	/**
	 * Compile the whole preview definition SET for a tab (design §16.1/§16.3): one Exact definition
	 * per effect node, addressed by the private id `vfxweaver:editor_preview/<tabSlug>/<nodeUuid>`.
	 * Per-node timing is baked into every child.
	 *
	 * <p>Every child forces {@code fade_ticks:0, loop:false, persistent:true,
	 * duration=<node duration>} and never carries an {@code order} param (it is pushed as a play-param
	 * by the controller, invariant I1/I2).
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @param {Object} [options] optional `{ context: {anchor,pos,targets}, empty: boolean }`; `empty`
	 *        forces an empty child set (the tombstone sync that clears the preview)
	 * @returns {{tab:string,slug:string,definitions:Object<string,string>,meta:Object}}
	 */
	function compilePreviewSet(tab, options) {
		if (!tab || typeof tab !== "object") {
			throw new Error("compilePreviewSet: a tab is required");
		}
		var slug = slugOf(tab);
		var nodes = options && options.empty ? [] : (tab.effects || []).filter(function (effect) {
			return effect && effect.type;
		});
		var definitions = {};
		var children = [];
		var problems = [];
		var allWarnings = [];
		var tabs = tabsOf(options);
		var refAware = tabs && nodes.some(function (effect) {
			return isReferenceEffect(effect);
		});
		if (!refAware) {
			nodes.forEach(function (effect) {
				var built = exactChildDef(tab, effect, slug);
				// `compileSingle` overwrites `lastWarnings` per node; aggregate them so
				// `getWarnings()` reflects the whole set, not just the last child.
				allWarnings = allWarnings.concat(lastWarnings);
				definitions[built.id] = built.json;

				children.push({
					key: built.uuid,
					def: built.id,
					delay: num(effect.delay, 0),
					duration: built.duration,
					isPost: built.isPost
				});
			});
		} else {
			nodes.forEach(function (effect) {
				if (!isReferenceEffect(effect)) {
					var plain = exactChildDef(tab, effect, slug);
					allWarnings = allWarnings.concat(lastWarnings);
					definitions[plain.id] = plain.json;
					children.push({
						key: plain.uuid,
						def: plain.id,
						delay: num(effect.delay, 0),
						duration: plain.duration,
						isPost: plain.isPost
					});
					return;
				}
				// A reference plays the leaf it resolves to (never itself); the child key
				// stays the reference node's uuid, so reordering keeps the same ids.
				var key = nodeUuid(effect);
				var resolved = resolvePreviewReference(tabs, tab, effect);
				if (resolved.problem) {
					problems.push({
						key: "ref:" + key,
						severity: "error",
						message: resolved.problem,
						context: "[" + effectName(tab) + "]",
						slug: slug,
						uuid: key
					});
					return;
				}
				if (resolved.external) {
					var installed = options && Array.isArray(options.installedIds)
							? options.installedIds : null;
					if (installed && installed.indexOf(resolved.external) < 0) {
						problems.push({
							key: "ref:dangling:" + resolved.external,
							severity: "error",
							message: "Effect '" + resolved.external
									+ "' is not installed. Install the pack or fix the reference.",
							context: "[" + effectName(tab) + "]"
						});
						return;
					}
					children.push({
						key: key,
						def: resolved.external,
						delay: num(effect.delay, 0),
						duration: childDurationTicks(tab, effect),
						// The target type is unknown without its definition; order only
						// matters for post children, so report it as non-post.
						isPost: false
					});
					return;
				}
				var leaf = exactChildDef(resolved.tab, resolved.effect, slugOf(resolved.tab));
				allWarnings = allWarnings.concat(lastWarnings);
				definitions[leaf.id] = leaf.json;
				children.push({
					key: key,
					def: leaf.id,
					delay: resolved.delay,
					duration: leaf.duration,
					isPost: leaf.isPost
				});
			});
		}
		var meta = {
			tabDuration: num(tab.duration, 40),
			children: children,
			context: options && options.context ? options.context : {}
		};
		lastWarnings = allWarnings;
		return { tab: effectName(tab), slug: slug, definitions: definitions, meta: meta,
			problems: problems };
	}

	/**
	 * Compile the AUTHORED preview set (Authored phase 1): the collection ROOT with its authored
	 * timings (duration/loop/fade_ticks/delays as compiled by {@link compileTab}), plus one
	 * authored child definition per chain node. This reuses {@link compileTab} — never the forced
	 * per-child overrides of {@link compilePreviewSet} — so authors verify the real lifecycle
	 * before export. Child references in the root are rewritten to the private preview ids.
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @param {Object} [options] optional `{ context: {anchor,pos,targets}, empty: boolean }`; `empty`
	 *        forces an empty definition set (the tombstone sync that clears the preview)
	 * @returns {{tab:string,slug:string,root:string|null,definitions:Object<string,string>,meta:Object}}
	 */
	function compileAuthoredSet(tab, options) {
		if (!tab || typeof tab !== "object") {
			throw new Error("compileAuthoredSet: a tab is required");
		}
		var slug = slugOf(tab);
		var rootId = previewDefId(slug, "root");
		var nodes = options && options.empty ? [] : (tab.effects || []).filter(function (effect) {
			return effect && effect.type;
		});
		var definitions = {};
		var problems = [];
		var allWarnings = [];
		var tabs = tabsOf(options);
		var refAware = tabs && nodes.some(function (effect) {
			return isReferenceEffect(effect);
		});
		if (nodes.length >= 2 && !refAware) {
			var root = JSON.parse(compileTab(tab));
			// Key-based, like the ref-aware path below: a reorder keeps the same ids on
			// both sides (positional `authored-<index>` ids churned on every reorder).
			var chain = activeChain(tab);
			var keyOf = {};
			chain.forEach(function (effect, index) {
				if (effect.external === true && effect.effectId != null) {
					return;
				}
				keyOf[namespaceOf() + ":" + baseNameOf(tab) + "__" + index] =
						previewDefId(slug, "authored-" + nodeUuid(effect));
			});
			(root.effects || []).forEach(function (entry) {
				if (entry && keyOf[entry.effect]) {
					entry.effect = keyOf[entry.effect];
				}
			});
			chain.forEach(function (effect) {
				// A child imported from an existing definition id is referenced as-is (as in
				// `compileCollection`); a locally-authored child is emitted under its preview id.
				if (effect.external === true && effect.effectId != null) {
					return;
				}
				var child = JSON.parse(compileSingle(tab, effect, effect.id, effect.source));
				allWarnings = allWarnings.concat(lastWarnings);
				definitions[previewDefId(slug, "authored-" + nodeUuid(effect))] =
						JSON.stringify(child);
			});
			definitions[rootId] = JSON.stringify(root);
		} else if (nodes.length >= 2) {
			// Reference-aware chains resolve through `compileTab` (which already points each
			// reference at its exported id and drops the unresolvable ones), then the local
			// children are re-addressed by node key — never by position — so a reorder keeps
			// the same ids on both sides.
			var refRoot = JSON.parse(compileTab(tab, options));
			var chain = activeChain(tab);
			var keyOf = {};
			chain.forEach(function (effect, index) {
				if (isReferenceEffect(effect) || effect.external === true) {
					return;
				}
				keyOf[namespaceOf(options) + ":" + baseNameOf(tab, options) + "__" + index] =
						previewDefId(slug, "authored-" + nodeUuid(effect));
			});
			(refRoot.effects || []).forEach(function (entry) {
				if (entry && keyOf[entry.effect]) {
					entry.effect = keyOf[entry.effect];
				}
			});
			chain.forEach(function (effect) {
				if (isReferenceEffect(effect)) {
					if (resolveExportReference(tabs, tab, effect, options) == null) {
						var target = referenceTarget(effect);
						var reason = !target ? "has no target. Pick a tab."
								: refIsCyclic(tabs, tab.name, target)
										? "references itself through '" + target
												+ "'. Remove the reference node."
										: "target '" + target + "' is missing. Fix the reference.";
						problems.push({
							key: "ref:" + nodeUuid(effect),
							severity: "error",
							message: "Reference node in tab '" + effectName(tab) + "' " + reason,
							context: "[" + effectName(tab) + "]",
							slug: slug,
							uuid: nodeUuid(effect)
						});
					}
					return;
				}
				if (effect.external === true) {
					return;
				}
				var local = JSON.parse(compileSingle(tab, effect, effect.id, effect.source));
				allWarnings = allWarnings.concat(lastWarnings);
				definitions[previewDefId(slug, "authored-" + nodeUuid(effect))] =
						JSON.stringify(local);
			});
			var installedIds = options && Array.isArray(options.installedIds)
					? options.installedIds : null;
			if (installedIds) {
				(refRoot.effects || []).forEach(function (entry) {
					var id = entry && entry.effect ? String(entry.effect) : null;
					if (id && id.indexOf(PREVIEW_NS + "/") !== 0
							&& installedIds.indexOf(id) < 0) {
						problems.push({
							key: "ref:dangling:" + id,
							severity: "error",
							message: "Effect '" + id
									+ "' is not installed. Install the pack or fix the reference.",
							context: "[" + effectName(tab) + "]"
						});
					}
				});
			}
			definitions[rootId] = JSON.stringify(refRoot);
		} else if (nodes.length === 1) {
			// A flat single (or a muted single's silent collection): no child references to rewrite.
			definitions[rootId] = compileTab(tab);
			allWarnings = allWarnings.concat(lastWarnings);
		}
		var meta = {
			tabDuration: num(tab.duration, 40),
			loop: Boolean(tab.loop),
			root: nodes.length ? rootId : null,
			children: [],
			context: options && options.context ? options.context : {}
		};
		lastWarnings = allWarnings;
		return { tab: effectName(tab), slug: slug, root: nodes.length ? rootId : null,
			definitions: definitions, meta: meta, problems: problems };
	}

	/**
	 * The view state for a tab (design §16.2): the child keys in chain order (the `order` array),
	 * plus the muted and soloed keys. Pure, so the headless selftest pins the order-array contract.
	 *
	 * @param {Object} tab the tab
	 * @returns {{order:Array<string>, muted:Array<string>, solo:Array<string>}}
	 */
	function previewViewstate(tab) {
		var order = [];
		var muted = [];
		var solo = [];
		(tab && tab.effects ? tab.effects : []).forEach(function (effect) {
			if (!effect || !effect.type) {
				return;
			}
			var uuid = nodeUuid(effect);
			order.push(uuid);
			if (effect.mute === true) {
				muted.push(uuid);
			}
			if (effect.solo === true) {
				solo.push(uuid);
			}
		});
		return { order: order, muted: muted, solo: solo };
	}

	/** The screen layer a post effect runs at (default 1; surface_pattern defaults to 0). */
	function screenLayerOf(effect) {
		var raw = effect && effect.params ? effect.params.screen_layer : null;
		var value = raw && typeof raw === "object" ? num(raw.value, NaN) : num(raw, NaN);
		if (isFinite(value)) {
			return Math.max(0, Math.min(2, Math.round(value)));
		}
		return effect && effect.type === "surface_pattern" ? 0 : 1;
	}

	/* ------------------------------------------------------------------ */
	/* Preview v2 UX helpers (design §16.9 P1) — pure, pinned by the selftest */
	/* ------------------------------------------------------------------ */

	/**
	 * The authored live source of a slot, or null when the slot's value is a pure timeline constant
	 * (design §16.9 P1). Reflects the AUTHORED slot kind only — no runtime probe: a non-empty track
	 * is a timeline constant (null), an inline `bind`/`expr` spec or a wired `bind`/`expr` value node
	 * is "bind"/"expr". A constant/curve driver is not live.
	 *
	 * @param {Object|number} spec the inline slot spec
	 * @param {boolean} hasTrack whether `keys[name]` holds a non-empty track
	 * @param {string|null} driverKind the wired value node's `kind` (or null)
	 * @returns {string|null} "bind", "expr" or null
	 */
	function liveKind(spec, hasTrack, driverKind) {
		if (hasTrack) {
			return null;
		}
		if (spec && typeof spec === "object") {
			if (spec.bind != null) {
				return "bind";
			}
			if (spec.expr != null) {
				return "expr";
			}
		}
		return driverKind === "bind" || driverKind === "expr" ? driverKind : null;
	}

	/**
	 * The `{slug, uuid}` a preview definition id addresses, or null for an unrelated id
	 * (design §16.1). Used to turn a rejected id back into the node it came from.
	 *
	 * @param {string} defId an id like `vfxweaver:editor_preview/<slug>/<uuid>`
	 * @returns {{slug: string, uuid: string}|null} the parsed reference
	 */
	function nodeRefFromDefId(defId) {
		if (defId == null) {
			return null;
		}
		var prefix = PREVIEW_NS + "/";
		var id = String(defId);
		if (id.indexOf(prefix) !== 0) {
			return null;
		}
		var rest = id.substring(prefix.length).split("/");
		if (rest.length === 2 && rest[0] && rest[1]) {
			return { slug: rest[0], uuid: rest[1] };
		}
		return null;
	}

	/**
	 * Decide what an SSE (re)connect should do with the preview set (design §16.9 P2/P3), pure so the
	 * headless selftest pins it. A reconnect must never recompile the graph on the browser main
	 * thread when nothing changed since the last successful sync: the last built set is simply
	 * re-sent (or skipped when there is none). A dirty graph is recompiled.
	 *
	 * @param {Object} state `{dirty: boolean, hasSet: boolean}`
	 * @returns {string} `"resync"` (recompile), `"resend"` (re-send the cached set) or `"skip"`
	 */
	function previewReconnectPlan(state) {
		var dirty = !state || state.dirty !== false;
		if (dirty) {
			return "resync";
		}
		return state && state.hasSet ? "resend" : "skip";
	}

	/**
	 * A tiny keyed problem store for the Preview problems panel (design §16.9 P1). Pure and DOM-free
	 * so the headless selftest pins the add/clear semantics. Entries are keyed: a repeated report
	 * replaces its entry, `retain(prefix, keys)` drops the entries under a prefix that a later
	 * successful sync no longer reports, and `clearPrefix` drops a whole family.
	 *
	 * @returns {Object} the store: `add`, `remove`, `retain`, `clearPrefix`, `list`, `count`
	 */
	function createProblemStore() {
		var entries = [];
		var byKey = {};

		function reindex() {
			byKey = {};
			entries = entries.filter(function (entry) {
				return entry && entry.key != null;
			});
			entries.forEach(function (entry) {
				byKey[entry.key] = entry;
			});
		}

		return {
			/** Add or replace one entry (keyed; an entry without a key is ignored). */
			add: function (entry) {
				if (!entry || entry.key == null) {
					return;
				}
				if (byKey[entry.key]) {
					var at = entries.indexOf(byKey[entry.key]);
					if (at >= 0) {
						entries[at] = entry;
					}
					byKey[entry.key] = entry;
				} else {
					entries.push(entry);
					byKey[entry.key] = entry;
				}
			},
			/** Drop the entry with `key` (no-op when absent). */
			remove: function (key) {
				if (byKey[key]) {
					var at = entries.indexOf(byKey[key]);
					if (at >= 0) {
						entries.splice(at, 1);
					}
					delete byKey[key];
				}
			},
			/**
			 * Keep only the `keys` (array of full keys) under `prefix`; drop every other prefixed
			 * entry. This is "clear an entry when a later successful sync no longer reports it".
			 */
			retain: function (prefix, keys) {
				var keep = {};
				(keys || []).forEach(function (key) {
					keep[key] = true;
				});
				entries = entries.filter(function (entry) {
					return String(entry.key).indexOf(prefix) !== 0 || keep[entry.key];
				});
				reindex();
			},
			/** Drop every entry whose key starts with `prefix`. */
			clearPrefix: function (prefix) {
				entries = entries.filter(function (entry) {
					return String(entry.key).indexOf(prefix) !== 0;
				});
				reindex();
			},
			/** The live entries, in insert order. */
			list: function () {
				return entries.slice();
			},
			/** The number of live entries. */
			count: function () {
				return entries.length;
			}
		};
	}

	/**
	 * The revision/status badge text+class for a preview state (design §16.9 P1), pure so the
	 * selftest pins it. `ended` clamps the shown tick to the duration (never past the end); the
	 * replay affordance is part of the ended text. `paused` (the live badge watchdog: the game is
	 * paused/unfocused so no `preview/state` frames arrive) wins over `playing` but not over an
	 * explicit `ended`/error/offline.
	 *
	 * @param {Object} state `{revision, playing, ended, tick, syncing, offline, error, paused, mode}`
	 * @param {number} total the tab duration in ticks
	 * @returns {{text: string, cls: string}} the badge text and modifier class
	 */
	function previewStatus(state, total) {
		var pv = state || {};
		var rev = pv.revision != null ? Number(pv.revision) : 0;
		if (!isFinite(rev)) {
			rev = 0;
		}
		var span = num(total, 0);
		// The preview mode suffix (Authored phase 1): `Exact`/`Authored` when the caller tracks it,
		// else "" so every existing badge string is unchanged.
		var modeSuffix = pv.mode === "authored" ? " · Authored"
				: (pv.mode === "exact" ? " · Exact" : "");
		if (pv.offline) {
			return { text: "offline", cls: "offline" };
		}
		if (pv.error) {
			return { text: "rev " + rev + " — error" + modeSuffix, cls: "error" };
		}
		if (pv.ended) {
			var tick = Math.min(Math.max(0, Math.round(num(pv.tick, 0))), Math.round(span));
			return { text: "ended @ " + tick + "/" + Math.round(span) + " — ▶ replay" + modeSuffix,
				cls: "ended" };
		}
		if (pv.paused && pv.playing) {
			return { text: "rev " + rev + " — paused — waiting for game…" + modeSuffix, cls: "paused" };
		}
		if (pv.playing) {
			return { text: "rev " + rev + " → live ●" + modeSuffix, cls: "live" };
		}
		if (pv.syncing) {
			return { text: "rev " + rev + " — syncing…" + modeSuffix, cls: "syncing" };
		}
		return { text: "rev " + rev + " → in game" + modeSuffix, cls: "" };
	}

	/**
	 * Reconcile the problems store against one sync's `rejected` list (design §16.9 P2): keep only
	 * the rejected ids this sync still reports, add/replace one entry per id (wired to its node), and
	 * — on a successful sync with no rejects — drop the defId-less `error:`/`bridge:` families. Pure
	 * (operates on the `createProblemStore` API or the shell's wrapper), so the selftest pins it.
	 *
	 * @param {Object} store a store with `retain(prefix,keys)` and `add(entry)` (the problems API)
	 * @param {Array<string>} rejected the sync's rejected definition ids
	 * @param {string} [label] an optional tab label for the entry context
	 */
	function reconcileRejected(store, rejected, label) {
		if (!store || !Array.isArray(rejected) || typeof store.retain !== "function") {
			return;
		}
		var keys = rejected.map(function (id) {
			return "reject:" + id;
		});
		store.retain("reject:", keys);
		if (!rejected.length) {
			// A successful sync no longer reports a defId-less preview error (or a bridge error).
			store.retain("error:", []);
			store.retain("bridge:", []);
		}
		rejected.forEach(function (id) {
			var ref = nodeRefFromDefId(id);
			var where = label ? (label + "/" + (ref ? ref.uuid : ""))
					: (ref ? ref.slug + "/" + ref.uuid : id);
			store.add({
				key: "reject:" + id,
				severity: "error",
				message: "rejected definition: " + id,
				context: "[" + where + "]",
				slug: ref ? ref.slug : null,
				uuid: ref ? ref.uuid : null
			});
		});
	}

	var api = {
		compileTab: compileTab,
		compileTabFiles: compileTabFiles,
		compileForPreview: compileForPreview,
		compilePreviewSet: compilePreviewSet,
		compileAuthoredSet: compileAuthoredSet,
		previewViewstate: previewViewstate,
		previewDefId: previewDefId,
		slugOf: slugOf,
		nodeUuid: nodeUuid,
		isPostType: isPostType,
		isReferenceEffect: isReferenceEffect,
		referenceTarget: referenceTarget,
		findTabByName: findTabByName,
		refIsCyclic: refIsCyclic,
		resolveExportReference: resolveExportReference,
		resolvePreviewReference: resolvePreviewReference,
		MAX_PREVIEW_DEPTH: MAX_PREVIEW_DEPTH,
		screenLayerOf: screenLayerOf,
		liveKind: liveKind,
		nodeRefFromDefId: nodeRefFromDefId,
		createProblemStore: createProblemStore,
		reconcileRejected: reconcileRejected,
		previewStatus: previewStatus,
		previewReconnectPlan: previewReconnectPlan,
		PREVIEW_NS: PREVIEW_NS,
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

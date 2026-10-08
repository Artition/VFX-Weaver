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
	function compileGraph(tab, skip, effectId, multi) {
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
			if (!edgeInScope(edge, effectId, multi)) {
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
			if (!Object.prototype.hasOwnProperty.call(params, name)) {
				params[name] = compileParam(name, folded.specs[name], keys, duration, warnings);
			}
		});
		return { value: params, warnings: warnings, folded: folded };
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
			var graphBuilt = compileGraph(tab, folded.skip, effectId, multi);
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
		chain.forEach(function (effect, index) {
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
			// A child imported from an existing definition id is referenced as-is and already ships
			// elsewhere, so no local child file is written for it (design §15.2).
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
	 * {@code VFXEffectType.isPostProcessing()}. The schema payload carries only `params` (there is no
	 * `post` flag), so this static classification is the web's only source until the schema grows one;
	 * it decides the `isPost` meta flag and whether a child contributes to the screen-layer ordering.
	 */
	var NON_POST_TYPES = {
		camera_shake: true, camera_roll: true, block_outline: true, block_tint: true,
		light_beam: true, pulse_ring: true, guide_line: true, particles: true, block_chain: true,
		entity_tint: true, entity_outline: true, entity_displace: true, fov_modifier: true,
		fog_modifier: true, window_create: true, window_control: true, collection: true
	};

	/** Whether an effect type renders a fullscreen post pass (see {@link #NON_POST_TYPES}). */
	function isPostType(type) {
		return type != null && type !== "" && !NON_POST_TYPES[String(type)];
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

	/** The exact-mode child definition id for a node uuid. */
	function previewDefId(slug, uuid) {
		return PREVIEW_NS + "/" + slug + "/" + uuid;
	}

	/** The authored-mode child definition id for a node uuid. */
	function previewAuthoredDefId(slug, uuid) {
		return PREVIEW_NS + "/" + slug + "/a/" + uuid;
	}

	/** The authored collection root id for a tab slug. */
	function previewRootId(slug) {
		return PREVIEW_NS + "/" + slug + "/root";
	}

	/**
	 * Compile the whole preview definition SET for a tab (design §16.1/§16.3): one definition per
	 * effect node, addressed by the private id `vfxweaver:editor_preview/<tabSlug>/<nodeUuid>`, plus
	 * the authored collection root. Per-node timing is baked into every child.
	 *
	 * <p><b>Exact</b> children force {@code fade_ticks:0, loop:false, persistent:true,
	 * duration=<node duration>} and never carry an {@code order} param (it is pushed as a play-param
	 * by the controller, invariant I1/I2). <b>Authored</b> children keep their own
	 * loop/persistent/fade (real lifecycle, including fade) and are referenced by the root.
	 *
	 * @param {Object} tab a `VFXIR.Tab`
	 * @param {Object} [options] optional `{ context: {anchor,pos,targets} }`
	 * @returns {{tab:string,slug:string,definitions:Object<string,string>,root:string,meta:Object}}
	 */
	function compilePreviewSet(tab, options) {
		if (!tab || typeof tab !== "object") {
			throw new Error("compilePreviewSet: a tab is required");
		}
		var slug = slugOf(tab);
		var nodes = (tab.effects || []).filter(function (effect) {
			return effect && effect.type;
		});
		var definitions = {};
		var children = [];
		var effects = [];
		nodes.forEach(function (effect, index) {
			var uuid = nodeUuid(effect);
			var exactId = previewDefId(slug, uuid);
			var authoredId = previewAuthoredDefId(slug, uuid);
			var duration = childDurationTicks(tab, effect);
			var isPost = isPostType(effect.type);

			var exact = JSON.parse(compileSingle(tab, effect, effect.id, effect.source));
			exact.duration = duration;
			exact.fade_ticks = 0;
			exact.loop = false;
			exact.persistent = true;
			if (exact.params && Object.prototype.hasOwnProperty.call(exact.params, "order")) {
				delete exact.params.order;
			}
			definitions[exactId] = JSON.stringify(exact);

			// Authored child: `compileSingle` already carries the node/tab loop/persistent/fade.
			definitions[authoredId] = JSON.stringify(
					JSON.parse(compileSingle(tab, effect, effect.id, effect.source)));

			children.push({
				key: uuid,
				def: exactId,
				delay: num(effect.delay, 0),
				duration: duration,
				isPost: isPost
			});

			var entry = {
				effect: authoredId,
				delay: num(effect.delay, 0),
				duration: duration
			};
			if (effect.easing != null) {
				entry.easing = effect.easing;
			}
			if (isPost) {
				entry.params = { order: index };
			}
			effects.push(entry);
		});
		var rootId = previewRootId(slug);
		definitions[rootId] = JSON.stringify({
			type: "collection",
			duration: num(tab.duration, 40),
			loop: Boolean(tab.loop),
			persistent: Boolean(tab.persistent),
			fade_ticks: num(tab.fade_ticks, 0),
			easing: tab.easing == null ? "linear" : tab.easing,
			effects: effects
		});
		var meta = {
			tabDuration: num(tab.duration, 40),
			children: children,
			context: options && options.context ? options.context : {}
		};
		return { tab: effectName(tab), slug: slug, definitions: definitions, root: rootId, meta: meta };
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

	var api = {
		compileTab: compileTab,
		compileTabFiles: compileTabFiles,
		compileForPreview: compileForPreview,
		compilePreviewSet: compilePreviewSet,
		previewViewstate: previewViewstate,
		previewDefId: previewDefId,
		previewRootId: previewRootId,
		slugOf: slugOf,
		nodeUuid: nodeUuid,
		isPostType: isPostType,
		screenLayerOf: screenLayerOf,
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

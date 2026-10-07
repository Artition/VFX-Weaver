/*
 * VFXweaver visual node editor — project IR (Task 13).
 *
 * The browser model is the single source of truth for the editor (design §6.2). It is kept
 * deliberately small: `Project` holds `Tab`s, a `Tab` holds the effect node (`type`, timing,
 * `params`) plus the value graph (`valueNodes`/`edges`) and the timeline keyframes (`keys`).
 * `compiler.js` turns one `Tab` into the VFXweaver datapack JSON.
 *
 * Exposed as `window.VFXIR`. When the app shell is present it is also attached to
 * `window.VFXEditor.ir` so later tasks can reach it through the shell namespace.
 */
(function () {
	"use strict";

	var root = typeof window !== "undefined" ? window
			: (typeof globalThis !== "undefined" ? globalThis : this);

	/**
	 * The whole editor project. `revision` is the monotonic counter echoed to the game bridge
	 * (design §7); `tabs` is one entry per edited effect. `name`/`namespace`/`defaults` make the
	 * project an object (design §15.6): the namespace is the datapack namespace every effect is
	 * exported under, and `defaults` carries the global timing defaults new tabs inherit.
	 *
	 * @constructor
	 */
	function Project() {
		this.revision = 0;
		this.name = "Untitled project";
		this.namespace = "vfx_project";
		/** @type {Object<string, *>} global defaults (e.g. duration, easing) new tabs inherit */
		this.defaults = {};
		this.tabs = [];
		this.active = -1;
	}

	/**
	 * @param {Tab} tab the tab to append
	 * @returns {Tab} the appended tab
	 */
	Project.prototype.addTab = function (tab) {
		this.tabs.push(tab);
		return tab;
	};

	/**
	 * One effect being edited.
	 *
	 * @param {string} [name] display name (usually `<namespace>:<effect>`)
	 * @param {string|null} [type] schema effect type, copied verbatim by the compiler (never validated there)
	 * @constructor
	 */
	function Tab(name, type) {
		this.name = name || "untitled";
		this.type = type || null;
		this.duration = 40;
		this.easing = "linear";
		/** @type {boolean} preview/playback loop (design §15.3) */
		this.loop = false;
		/** @type {boolean} keep the effect alive after its duration (design §15.3) */
		this.persistent = false;
		/** @type {number} post-life fade in ticks; 0 disables it (design §15.3, I2) */
		this.fade_ticks = 0;
		/** @type {number} reserved start delay for P6 chains (design §15.3); stored, not yet applied */
		this.delay = 0;
		/** @type {Object<string, Object|number>} param name -> control-state spec */
		this.params = {};
		/** @type {Array<Object>} value nodes: `{ id, kind, inputs, op? }` (+ editor `pos`) */
		this.valueNodes = [];
		/** @type {Array<Object>} wires: node->node `{ from, to, input }` or node->param `{ from, to }` */
		this.edges = [];
		/** @type {Object<string, Array<Object>>} param name -> keyframes `{ time, value, easing? }` */
		this.keys = {};
	}

	/**
	 * @param {Object} node the value node to append
	 * @returns {Object} the appended node
	 */
	Tab.prototype.addValueNode = function (node) {
		this.valueNodes.push(node);
		return node;
	};

	/**
	 * Add a wire. `input` is omitted for a wire into an effect param (the compiler routes those
	 * to the top-level `inputs` block); it is required for a wire into another value node.
	 *
	 * @param {string} from source value-node id
	 * @param {string} to target value-node id or effect param name
	 * @param {string} [input] target socket, for a node->node wire
	 * @returns {Object} the added edge
	 */
	Tab.prototype.connect = function (from, to, input) {
		var edge = { from: from, to: to };
		if (input != null) {
			edge.input = input;
		}
		this.edges.push(edge);
		return edge;
	};

	/**
	 * Append a keyframe to a param's timeline track and return it.
	 *
	 * @param {string} name param name
	 * @param {number} time tick offset from the effect start (may be negative for "from now")
	 * @param {number} value key value
	 * @param {string} [easing] per-segment easing name
	 * @returns {Object} the added keyframe
	 */
	Tab.prototype.addKey = function (name, time, value, easing) {
		if (!this.keys[name]) {
			this.keys[name] = [];
		}
		var key = { time: time, value: value };
		if (easing != null) {
			key.easing = easing;
		}
		this.keys[name].push(key);
		return key;
	};

	/**
	 * Builders for the per-param control state the inspector writes and the compiler serialises.
	 * Every spec carries a `control` discriminator; the optional `multiply` field (a bound spec)
	 * is accepted on any of them.
	 */
	var ParamSpec = {
		/**
		 * @param {number} value the literal value
		 * @returns {Object} a constant spec
		 */
		constant: function (value) {
			return { control: "constant", value: value };
		},

		/**
		 * @param {number} start value at t=0
		 * @param {number} end value at the effect duration
		 * @returns {Object} a start/end animation spec
		 */
		animate: function (start, end) {
			return { control: "animate", start: start, end: end };
		},

		/**
		 * Keyframes live on `Tab.keys[name]` (the timeline reads them); the spec only marks the
		 * control as keyframed.
		 *
		 * @returns {Object} a keyframes spec
		 */
		keyframes: function () {
			return { control: "keyframes" };
		},

		/**
		 * @param {string} kind binding name, e.g. `proximity`
		 * @param {Object} [options] extra binding fields (`pos`, `range`, `scale`, `invert`, ...)
		 * @returns {Object} a bound spec
		 */
		bind: function (kind, options) {
			var spec = { control: "bind", bind: kind };
			if (options) {
				Object.keys(options).forEach(function (key) {
					spec[key] = options[key];
				});
			}
			return spec;
		},

		/**
		 * @param {string} source the expression source
		 * @returns {Object} an expression spec
		 */
		expr: function (source) {
			return { control: "expr", expr: source };
		}
	};

	/**
	 * Snapshot-based undo/redo (design §15.7). The IR is serialisable, so a transaction just pushes a
	 * whole-project snapshot; undo/redo move a cursor over the snapshot list. This replaces the old
	 * command stack: a transaction is one `pointerdown→up` drag or one discrete action, and the list
	 * is capped at {@code limit} states (oldest dropped). After a move the shell recompiles the
	 * affected tab with a full `/compile`, never an incremental game undo (design §6.2).
	 *
	 * @param {number} [limit] maximum number of retained states (default 100)
	 * @constructor
	 */
	function SnapshotHistory(limit) {
		this.limit = limit && limit > 0 ? limit : 100;
		this.states = [];
		this.index = -1;
	}

	/**
	 * Start from a fresh snapshot (clears any existing history).
	 *
	 * @param {*} state the initial serialisable state
	 * @returns {number} the new index (0)
	 */
	SnapshotHistory.prototype.reset = function (state) {
		this.states = [state];
		this.index = 0;
		return this.index;
	};

	/**
	 * Commit a new state, dropping the redo branch and trimming the oldest past the cap.
	 *
	 * @param {*} state the serialisable state after the action
	 * @returns {number} the new index
	 */
	SnapshotHistory.prototype.record = function (state) {
		this.states.length = this.index + 1;
		this.states.push(state);
		if (this.states.length > this.limit) {
			this.states.shift();
		}
		this.index = this.states.length - 1;
		return this.index;
	};

	/**
	 * @returns {boolean} whether a previous state exists
	 */
	SnapshotHistory.prototype.canUndo = function () {
		return this.index > 0;
	};

	/**
	 * @returns {boolean} whether a redo state exists
	 */
	SnapshotHistory.prototype.canRedo = function () {
		return this.index >= 0 && this.index < this.states.length - 1;
	};

	/**
	 * Move the cursor one state back.
	 *
	 * @returns {*|null} the state to restore, or null when there is nothing to undo
	 */
	SnapshotHistory.prototype.undo = function () {
		if (!this.canUndo()) {
			return null;
		}
		this.index--;
		return this.states[this.index];
	};

	/**
	 * Move the cursor one state forward.
	 *
	 * @returns {*|null} the state to restore, or null when there is nothing to redo
	 */
	SnapshotHistory.prototype.redo = function () {
		if (!this.canRedo()) {
			return null;
		}
		this.index++;
		return this.states[this.index];
	};

	/** Whether `value` is a real, finite number (not a numeric string, not NaN/Infinity). */
	function isFiniteNumber(value) {
		return typeof value === "number" && isFinite(value);
	}

	/**
	 * Parse a strict decimal number (optional sign, fraction, exponent) from user text.
	 *
	 * @param {*} text the text to parse
	 * @returns {number|null} the number, or null when the text is not one (never evaluates code)
	 */
	function parseNumber(text) {
		var trimmed = String(text == null ? "" : text).trim();
		if (!/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(trimmed)) {
			return null;
		}
		var n = Number(trimmed);
		return isFinite(n) ? n : null;
	}

	/**
	 * Clamp a tick time into `[0, total]`. A non-finite `ticks` becomes 0 and a non-finite or
	 * negative `total` collapses the range to 0, so an absent duration can never yield a negative
	 * time and a key can never land beyond the lane.
	 *
	 * @param {number} ticks the candidate time in ticks
	 * @param {number} total the effect duration in ticks
	 * @returns {number} the clamped time
	 */
	function clampTime(ticks, total) {
		var t = Number(ticks);
		if (!isFinite(t)) {
			t = 0;
		}
		var max = Number(total);
		if (!isFinite(max) || max < 0) {
			max = 0;
		}
		return Math.max(0, Math.min(max, t));
	}

	/**
	 * Normalise one imported datapack param back to the slot model (design §15.8): a `{keyframes}`
	 * object becomes a track (keys + a spec that only carries a possible `multiply`), a `bind`/`expr`
	 * object becomes a driven slot (a driver-node description the shell materializes + such a spec),
	 * a `{start,end}` pair becomes a two-key track at 0/duration, and a bare number or `{value}`
	 * stays an inline constant. Compiling the result folds the driver back to the same JSON.
	 *
	 * @param {Object|number} raw the parsed `def.params[name]` value
	 * @param {number} duration the effect duration in ticks (seeds a start/end track)
	 * @returns {{state: string, spec: Object, keys: Array|null, driver: Object|null}} the slot
	 */
	function normalizeParam(raw, duration) {
		var total = isFiniteNumber(Number(duration)) && Number(duration) > 0 ? Number(duration) : 40;
		if (raw == null) {
			return { state: "inline", spec: { value: 0 }, keys: null, driver: null };
		}
		if (typeof raw === "number") {
			return { state: "inline", spec: { value: raw }, keys: null, driver: null };
		}
		if (typeof raw !== "object") {
			return { state: "inline", spec: { value: 0 }, keys: null, driver: null };
		}
		var spec = {};
		if (raw.multiply) {
			spec.multiply = raw.multiply;
		}
		if (Array.isArray(raw.keyframes)) {
			return {
				state: "track",
				spec: spec,
				keys: raw.keyframes.map(function (key) {
					var out = { time: Number(key.time) || 0, value: Number(key.value) || 0 };
					if (key.easing != null) {
						out.easing = key.easing;
					}
					return out;
				}),
				driver: null
			};
		}
		if (raw.bind != null) {
			var bindInputs = {};
			Object.keys(raw).forEach(function (key) {
				if (key !== "multiply") {
					bindInputs[key] = raw[key];
				}
			});
			return { state: "driven", spec: spec, keys: null,
				driver: { kind: "bind", inputs: bindInputs } };
		}
		if (raw.expr != null) {
			return { state: "driven", spec: spec, keys: null,
				driver: { kind: "expr", inputs: { expr: String(raw.expr) } } };
		}
		if (raw.start != null || raw.end != null) {
			return {
				state: "track",
				spec: spec,
				keys: [{ time: 0, value: Number(raw.start) || 0 },
					{ time: total, value: Number(raw.end) || 0 }],
				driver: null
			};
		}
		if (raw.value !== undefined) {
			spec.value = raw.value;
		}
		return { state: "inline", spec: spec, keys: null, driver: null };
	}

	/**
	 * Graph-space point -> canvas-pixel point, mirroring LiteGraph's
	 * `DragAndScale.convertOffsetToCanvas` (`(pos + offset) * scale`). Extracted so the inline-edit
	 * placement is pinned by the headless selftest without a live canvas.
	 *
	 * @param {Array<number>} pos graph-space `[x, y]`
	 * @param {Array<number>} offset canvas pan offset `[x, y]`
	 * @param {number} scale canvas zoom
	 * @returns {Array<number>} canvas-pixel `[x, y]` relative to the canvas top-left
	 */
	function graphToCanvas(pos, offset, scale) {
		var p = pos || [0, 0];
		var o = offset || [0, 0];
		var s = isFiniteNumber(Number(scale)) && Number(scale) > 0 ? Number(scale) : 1;
		return [(Number(p[0]) + Number(o[0])) * s, (Number(p[1]) + Number(o[1])) * s];
	}

	/** A valid datapack namespace: non-empty and only `[a-z0-9_.-]` (design §15.6). */
	var NAMESPACE_RE = /^[a-z0-9_.-]+$/;

	/**
	 * @param {*} value the candidate namespace
	 * @returns {boolean} true when it matches `[a-z0-9_.-]+`
	 */
	function validNamespace(value) {
		return typeof value === "string" && NAMESPACE_RE.test(value);
	}

	/**
	 * Body for `POST /export/zip` (the staged, browser-download path). `sidecar` is included only
	 * when present, matching the bridge's optional-field contract.
	 *
	 * @param {string} namespace the datapack namespace
	 * @param {Object} effects effect name -> compiled JSON text
	 * @param {*} [sidecar] the editor project document, or null
	 * @returns {Object} the request body
	 */
	function exportZipBody(namespace, effects, sidecar) {
		var body = { namespace: namespace, effects: effects || {} };
		if (sidecar != null) {
			body.sidecar = sidecar;
		}
		return body;
	}

	/**
	 * Body for `POST /export` to a folder or world target.
	 *
	 * @param {string} namespace the datapack namespace
	 * @param {string} destination `"folder"` or `"world"`
	 * @param {string} path the pack root (folder) or world save directory (world)
	 * @param {Object} effects effect name -> compiled JSON text
	 * @param {*} [sidecar] the editor project document, or null
	 * @returns {Object} the request body
	 */
	function exportTargetBody(namespace, destination, path, effects, sidecar) {
		var body = {
			namespace: namespace,
			destination: destination,
			path: path,
			effects: effects || {}
		};
		if (sidecar != null) {
			body.sidecar = sidecar;
		}
		return body;
	}

	/**
	 * Split a schema param list into the rows the inspector renders (design §15.9.6/P4, fix #21):
	 * descriptors sharing a non-empty `vectorGroup` collapse into one entry keyed by the group,
	 * with `components` ordered by `vectorIndex`; a descriptor without a group is its own singleton
	 * entry. Order follows the schema, by the first component of each group seen.
	 *
	 * @param {Array<Object>} params the `VFXParamDescriptor[]` of one effect type
	 * @returns {Array<{group: (string|null), name: string, components: Array<Object>}>} the rows
	 */
	function paramGroups(params) {
		var out = [];
		var byGroup = {};
		(Array.isArray(params) ? params : []).forEach(function (param) {
			if (!param || !param.name) {
				return;
			}
			var group = param.vectorGroup;
			if (!group) {
				out.push({ group: null, name: param.name, components: [param] });
				return;
			}
			if (!byGroup[group]) {
				byGroup[group] = { group: group, name: group, components: [] };
				out.push(byGroup[group]);
			}
			byGroup[group].components.push(param);
		});
		out.forEach(function (entry) {
			if (entry.group) {
				entry.components.sort(function (a, b) {
					var ai = Number(a.vectorIndex);
					var bi = Number(b.vectorIndex);
					return (isFinite(ai) ? ai : 0) - (isFinite(bi) ? bi : 0);
				});
			}
		});
		return out;
	}

	/**
	 * Broadcast one spec to every component of a group (design §15.9.6/P4): the group-level edit a
	 * collapsed row performs, expanded to the underlying scalar params. Each component gets its own
	 * deep copy, so a later per-component edit changes only that name and wins at compile time.
	 *
	 * @param {Object} tab the tab whose `params` are written
	 * @param {Array<Object>} descriptors the group's component descriptors
	 * @param {Object} spec the group-level spec (e.g. `{ value: 1 }`)
	 * @returns {Array<string>} the component names written
	 */
	function applyGroupSpec(tab, descriptors, spec) {
		var names = [];
		if (!tab) {
			return names;
		}
		if (!tab.params) {
			tab.params = {};
		}
		(Array.isArray(descriptors) ? descriptors : []).forEach(function (param) {
			if (!param || !param.name) {
				return;
			}
			tab.params[param.name] = spec == null ? spec : JSON.parse(JSON.stringify(spec));
			names.push(param.name);
		});
		return names;
	}

	/**
	 * The spec every component of a group shares, or null when they differ (design §15.9.6/P4).
	 * Drives the collapsed row's uniform/mixed display and the importer's collapse: mixed or
	 * overridden components render expanded instead. A component that is a track or driven by a
	 * wire is never "uniform" (there is no shared inline literal), so such a group stays expanded.
	 *
	 * @param {Object} tab the tab
	 * @param {Array<Object>} descriptors the group's component descriptors
	 * @returns {Object|null} the shared spec, or null when mixed (or when there is no component)
	 */
	function uniformGroupSpec(tab, descriptors) {
		var list = Array.isArray(descriptors) ? descriptors : [];
		if (list.length === 0) {
			return null;
		}
		var first = null;
		for (var i = 0; i < list.length; i++) {
			var name = list[i].name;
			var keys = tab && tab.keys ? tab.keys[name] : null;
			if (Array.isArray(keys) && keys.length > 0) {
				return null;
			}
			if (hasDriver(tab, name)) {
				return null;
			}
			var spec = componentSpec(tab, list[i]);
			if (i === 0) {
				first = spec;
			} else if (JSON.stringify(spec) !== JSON.stringify(first)) {
				return null;
			}
		}
		return first;
	}

	/** Whether a value node is wired into a param. */
	function hasDriver(tab, name) {
		var edges = tab && Array.isArray(tab.edges) ? tab.edges : [];
		for (var i = 0; i < edges.length; i++) {
			var edge = edges[i];
			if (edge && edge.to === name && edge.input == null && edge.from != null) {
				return true;
			}
		}
		return false;
	}

	/** The inline spec of one param: the stored object, a `{ value }` wrapper for a literal, or its default. */
	function componentSpec(tab, param) {
		var raw = tab && tab.params ? tab.params[param.name] : undefined;
		if (raw != null && typeof raw === "object") {
			return raw;
		}
		return { value: raw == null ? param.defaultValue : raw };
	}

	var api = {
		Project: Project,
		Tab: Tab,
		ParamSpec: ParamSpec,
		SnapshotHistory: SnapshotHistory,
		isFiniteNumber: isFiniteNumber,
		parseNumber: parseNumber,
		clampTime: clampTime,
		normalizeParam: normalizeParam,
		graphToCanvas: graphToCanvas,
		validNamespace: validNamespace,
		exportZipBody: exportZipBody,
		exportTargetBody: exportTargetBody,
		paramGroups: paramGroups,
		applyGroupSpec: applyGroupSpec,
		uniformGroupSpec: uniformGroupSpec
	};

	root.VFXIR = api;
	if (root.VFXEditor) {
		root.VFXEditor.ir = api;
	}
})();

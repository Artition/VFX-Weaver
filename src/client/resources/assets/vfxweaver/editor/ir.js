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

	var api = {
		Project: Project,
		Tab: Tab,
		ParamSpec: ParamSpec,
		SnapshotHistory: SnapshotHistory,
		isFiniteNumber: isFiniteNumber,
		parseNumber: parseNumber,
		clampTime: clampTime,
		graphToCanvas: graphToCanvas,
		validNamespace: validNamespace,
		exportZipBody: exportZipBody,
		exportTargetBody: exportTargetBody
	};

	root.VFXIR = api;
	if (root.VFXEditor) {
		root.VFXEditor.ir = api;
	}
})();

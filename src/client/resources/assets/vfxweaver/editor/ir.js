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
	 * (design §7); `tabs` is one entry per edited effect.
	 *
	 * @constructor
	 */
	function Project() {
		this.revision = 0;
		this.tabs = [];
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

	var api = {
		Project: Project,
		Tab: Tab,
		ParamSpec: ParamSpec,
		SnapshotHistory: SnapshotHistory
	};

	root.VFXIR = api;
	if (root.VFXEditor) {
		root.VFXEditor.ir = api;
	}
})();

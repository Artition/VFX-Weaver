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
	 * Undo/redo stack. A command is `{ do(), undo() }` (either side optional). Undo applies the
	 * model snapshot held by the command; Task 16 then pushes the whole model with a full
	 * `/compile`, never an incremental game undo (design §6.2).
	 *
	 * @constructor
	 */
	function CommandStack() {
		this._undo = [];
		this._redo = [];
	}

	/**
	 * Run a command and make it undoable (clears the redo branch).
	 *
	 * @param {{do: function, undo: function}} command the command
	 * @returns {Object} the command
	 */
	CommandStack.prototype["do"] = function (command) {
		if (!command) {
			return command;
		}
		if (typeof command["do"] === "function") {
			command["do"]();
		}
		this._undo.push(command);
		this._redo.length = 0;
		return command;
	};

	/**
	 * Undo the most recent command.
	 *
	 * @returns {Object|null} the command that was undone, or null when the stack is empty
	 */
	CommandStack.prototype.undo = function () {
		var command = this._undo.pop();
		if (!command) {
			return null;
		}
		if (typeof command.undo === "function") {
			command.undo();
		}
		this._redo.push(command);
		return command;
	};

	/**
	 * Redo the most recently undone command.
	 *
	 * @returns {Object|null} the command that was redone, or null when there is nothing to redo
	 */
	CommandStack.prototype.redo = function () {
		var command = this._redo.pop();
		if (!command) {
			return null;
		}
		if (typeof command["do"] === "function") {
			command["do"]();
		}
		this._undo.push(command);
		return command;
	};

	/**
	 * @returns {boolean} whether there is a command to undo
	 */
	CommandStack.prototype.canUndo = function () {
		return this._undo.length > 0;
	};

	/**
	 * @returns {boolean} whether there is a command to redo
	 */
	CommandStack.prototype.canRedo = function () {
		return this._redo.length > 0;
	};

	var api = {
		Project: Project,
		Tab: Tab,
		ParamSpec: ParamSpec,
		CommandStack: CommandStack
	};

	root.VFXIR = api;
	if (root.VFXEditor) {
		root.VFXEditor.ir = api;
	}
})();

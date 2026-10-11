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
		this.duration = 40;
		this.easing = "linear";
		/** @type {boolean} preview/playback loop (design §15.3) */
		this.loop = false;
		/** @type {boolean} keep the effect alive after its duration (design §15.3) */
		this.persistent = false;
		/** @type {number} post-life fade in ticks; 0 disables it (design §15.3, I2) */
		this.fade_ticks = 0;
		/** @type {number} reserved start delay for the collection root (design §15.3) */
		this.delay = 0;
		/**
		 * @type {Array<Object>} the tab's effect nodes in chain (layering) order (design §15.2, P6).
		 * Each entry: `{ id, type, params, keys, delay, duration, easing, mute, solo, source, pos? }`.
		 */
		this.effects = [];
		/** @type {number} index into {@link Tab#effects} of the effect the inspector/timeline edits */
		this.activeEffect = -1;
		/** @type {number} monotonic counter for stable effect ids */
		this.__effectSeq = 0;
		/** scratch slot for an empty (effect-less) tab, so `tab.params` never throws */
		this.__params = {};
		this.__keys = {};
		this.__type = type == null ? null : type;
		/** @type {Array<Object>} value nodes: `{ id, kind, inputs, op? }` (+ editor `pos`) */
		this.valueNodes = [];
		/** @type {Array<Object>} wires: node->node `{ from, to, input }` or node->param `{ from, to, effect? }` */
		this.edges = [];
		if (type != null) {
			this.addEffect(type);
		}
	}

	/**
	 * The effect entry the inspector/timeline is editing, or null for an empty tab.
	 *
	 * @returns {Object|null} `effects[activeEffect]`, or null when the tab has no effect
	 */
	Tab.prototype.effect = function () {
		return this.effects && this.activeEffect >= 0 && this.activeEffect < this.effects.length
				? this.effects[this.activeEffect] : null;
	};

	/**
	 * Append a new effect node to the chain (design §15.2, P6) and return it.
	 *
	 * @param {string} [type] the schema effect type
	 * @param {string} [id] a stable id; a fresh `fx_<n>` is allocated when omitted
	 * @returns {Object} the added effect entry
	 */
	Tab.prototype.addEffect = function (type, id) {
		if (!this.effects) {
			this.effects = [];
		}
		var seq = (this.__effectSeq || 0) + 1;
		this.__effectSeq = seq;
		var effect = {
			id: id || ("fx_" + seq),
			type: type == null ? null : type,
			params: {},
			keys: {},
			delay: 0,
			duration: 0,
			easing: null,
			// Per-node timing (design §15.3, fix H4): a chain node owns its own loop/persistent/
			// fade_ticks, so the Node timing panel edits only the selected effect, not the whole
			// collection. For a single-effect tab these are ignored (the root tab owns them).
			loop: false,
			persistent: false,
			fade_ticks: 0,
			mute: false,
			solo: false,
			source: null
		};
		this.effects.push(effect);
		if (this.activeEffect < 0) {
			this.activeEffect = this.effects.length - 1;
		}
		return effect;
	};

	/**
	 * Remove an effect entry by id.
	 *
	 * @param {string} id the effect id
	 * @returns {boolean} true when an entry was removed
	 */
	Tab.prototype.removeEffect = function (id) {
		if (!this.effects) {
			return false;
		}
		for (var i = 0; i < this.effects.length; i++) {
			if (this.effects[i].id === id) {
				this.effects.splice(i, 1);
				if (this.activeEffect >= this.effects.length) {
					this.activeEffect = this.effects.length - 1;
				}
				return true;
			}
		}
		return false;
	};

	/** The effect entry with `id`, or null. */
	function effectById(tab, id) {
		if (!tab || !tab.effects) {
			return null;
		}
		for (var i = 0; i < tab.effects.length; i++) {
			if (tab.effects[i].id === id) {
				return tab.effects[i];
			}
		}
		return null;
	}

	// The single-edit fields (`type`/`params`/`keys`) proxy to the active effect, so every existing
	// inspector/timeline/compiler code path keeps working unchanged for a chain of one, and edits
	// the selected node for a longer chain. An effect-less tab writes the scratch slots.
	Object.defineProperty(Tab.prototype, "type", {
		configurable: true,
		get: function () {
			var effect = this.effect();
			return effect ? effect.type : (this.__type == null ? null : this.__type);
		},
		set: function (value) {
			var effect = this.effect();
			if (effect) {
				effect.type = value;
			}
			this.__type = value;
		}
	});
	Object.defineProperty(Tab.prototype, "params", {
		configurable: true,
		get: function () {
			var effect = this.effect();
			return effect ? effect.params : this.__params;
		},
		set: function (value) {
			var effect = this.effect();
			if (effect) {
				effect.params = value;
			} else {
				this.__params = value;
			}
		}
	});
	Object.defineProperty(Tab.prototype, "keys", {
		configurable: true,
		get: function () {
			var effect = this.effect();
			return effect ? effect.keys : this.__keys;
		},
		set: function (value) {
			var effect = this.effect();
			if (effect) {
				effect.keys = value;
			} else {
				this.__keys = value;
			}
		}
	});

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
		} else if (this.effects && this.effects.length > 1) {
			// A param wire in a chain belongs to one effect node (P6); tag it so the compiler routes
			// it into that child definition. A single-effect tab keeps the bare `{from,to}` shape.
			var active = this.effect();
			if (active) {
				edge.effect = active.id;
			}
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
		// Keep the track time-sorted at every write: the timeline readout, the curve view and the
		// engine all assume ascending time, and an append-only key can be inserted left of the rest.
		this.keys[name].sort(function (a, b) {
			return (Number(a.time) || 0) - (Number(b.time) || 0);
		});
		return key;
	};

	/**
	 * The shared key writer behind the timeline/inspector edits (the animate diamond and the lane
	 * click): upsert a key at a whole tick — replace the value when a key already sits on that tick,
	 * else append — and keep the track time-sorted. Also seeds the param map so a key-only slot still
	 * compiles. App-derived defaults (a schema `defaultValue`) are resolved by the caller.
	 *
	 * @param {string} name param name
	 * @param {number} time tick offset from the effect start
	 * @param {number} value key value
	 * @returns {Object} the written keyframe
	 */
	Tab.prototype.setKey = function (name, time, value) {
		if (!this.keys) {
			this.keys = {};
		}
		if (!this.params) {
			this.params = {};
		}
		var t = Math.round(Number(time) || 0);
		var list = this.keys[name];
		if (!Array.isArray(list)) {
			list = this.keys[name] = [];
		}
		// A key-only param must still exist in `params`, or the compiler (which walks the param map)
		// drops the whole slot from the export. Seed it once, keeping any existing spec.
		if (this.params[name] == null) {
			this.params[name] = { value: value };
		} else if (typeof this.params[name] !== "object") {
			this.params[name] = { value: this.params[name] };
		}
		for (var i = 0; i < list.length; i++) {
			if (Math.round(Number(list[i].time) || 0) === t) {
				list[i].value = value;
				return list[i];
			}
		}
		return this.addKey(name, t, value);
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
	 * Canonical cubic-Bézier control points `[x1,y1,x2,y2]` per built-in easing name (design §15.4).
	 * The editor draws every segment as a Bézier; a snapped segment stores the NAME, and these are
	 * the control points that name is drawn/handled at and recognised back from — so name ↔ bezier
	 * round-trips losslessly. The values approximate the engine's named curves (they are the CSS
	 * cubic-bezier equivalents) so a snapped preview stays close; an exact bezier is stored as the
	 * four numbers instead. x1,x2 ∈ [0,1]; y1,y2 are free (overshoot allowed).
	 */
	var EASING_BEZIER = {
		linear: [0, 0, 1, 1],
		ease_in_quad: [0.11, 0, 0.5, 0],
		ease_out_quad: [0.5, 1, 0.89, 1],
		ease_in_out_quad: [0.45, 0, 0.55, 1],
		ease_in_cubic: [0.32, 0, 0.67, 0],
		ease_out_cubic: [0.33, 1, 0.68, 1],
		ease_in_out_cubic: [0.65, 0, 0.35, 1],
		ease_in_expo: [0.7, 0, 0.84, 0],
		ease_out_expo: [0.16, 1, 0.3, 1],
		smoothstep: [1 / 3, 0, 2 / 3, 1]
	};

	/** Per-component snap tolerance for recognising a named easing when a handle is released. */
	var SNAP_EPSILON = 0.04;

	/** One axis component of a cubic Bézier at parameter `t` (endpoints fixed at 0 and 1). */
	function bezierComponent(t, a1, a2) {
		var mt = 1 - t;
		return 3 * mt * mt * t * a1 + 3 * mt * t * t * a2 + t * t * t;
	}

	/**
	 * Solve for the curve parameter `t` whose x equals `x`, by bisection on `[0,1]` — the same
	 * scheme as the engine's `EasingFunction.solveBezierT`, so the drawn curve matches the game.
	 */
	function solveBezierT(x1, x2, x) {
		if (x <= 0) {
			return 0;
		}
		if (x >= 1) {
			return 1;
		}
		var lo = 0;
		var hi = 1;
		var t = 0.5;
		for (var i = 0; i < 30; i++) {
			t = (lo + hi) / 2;
			if (bezierComponent(t, x1, x2) < x) {
				lo = t;
			} else {
				hi = t;
			}
		}
		return t;
	}

	/**
	 * Sample a cubic-Bézier easing `[x1,y1,x2,y2]` at a normalized time `u` (design §15.4): solve
	 * the x-behaviour for `t`, then evaluate y. `y` may leave `[0,1]` for an overshoot bezier.
	 *
	 * @param {Array<number>} bezier `[x1,y1,x2,y2]`
	 * @param {number} u normalized time in `[0,1]`
	 * @returns {number} the eased value at `u` (0 at u=0, 1 at u=1)
	 */
	function bezierY(bezier, u) {
		var b = Array.isArray(bezier) ? bezier : [0, 0, 1, 1];
		var x = Number(u);
		if (!isFinite(x)) {
			x = 0;
		}
		if (x <= 0) {
			return 0;
		}
		if (x >= 1) {
			return 1;
		}
		var x1 = Number(b[0]);
		var y1 = Number(b[1]);
		var x2 = Number(b[2]);
		var y2 = Number(b[3]);
		return bezierComponent(solveBezierT(x1, x2, x), y1, y2);
	}

	/**
	 * The unit-space y change of a curve segment's tangent handle for a vertical drag, so the handle
	 * tracks the pointer. The handle is drawn at `v0 + (v1 - v0) * bez`, and the value axis maps a
	 * pixel to `axisSpan / innerHeight` values, so the change in `bez` is the pixel delta divided by
	 * the segment's value span (`v1 - v0`). A zero value span has no vertical geometry, so the delta
	 * is zero (guard `v1 === v0`). Linear in both the axis range and the pixel delta; the caller
	 * subtracts the result (dragging up raises the drawn value).
	 *
	 * @param {number} pixelDelta the vertical drag in pixels (screen y grows downward)
	 * @param {number} innerHeight the curve plot's inner pixel height
	 * @param {number} valueSpan `v1 - v0` of the segment being edited
	 * @param {number} axisSpan `axis.hi - axis.lo` of the value axis
	 * @returns {number} the change to apply to the Bézier control point's y component
	 */
	function bezierHandleDeltaY(pixelDelta, innerHeight, valueSpan, axisSpan) {
		var span = Number(valueSpan);
		if (!isFinite(span) || span === 0) {
			return 0;
		}
		var pixels = Number(pixelDelta);
		var inner = Number(innerHeight);
		var axis = Number(axisSpan);
		if (!isFinite(pixels) || !isFinite(inner) || !isFinite(axis)) {
			return 0;
		}
		return pixels / (inner || 1) * (axis / span);
	}

	/**
	 * Round a number to a fixed number of decimal places (for a compact readout). Pure, so the
	 * headless selftest pins it; a non-finite input and an absurd precision are clamped.
	 *
	 * @param {*} value the value to round
	 * @param {number} [decimals] decimal places (default 2; clamped to `[0,10]`)
	 * @returns {number} the rounded value, or 0 for a non-finite input
	 */
	function roundTo(value, decimals) {
		var n = Number(value);
		if (!isFinite(n)) {
			return 0;
		}
		var places = isFiniteNumber(Number(decimals)) ? Math.max(0, Math.min(10, Math.round(Number(decimals)))) : 2;
		var factor = Math.pow(10, places);
		return Math.round(n * factor) / factor;
	}

	/**
	 * Clamp a dragged curve tangent handle into the visible curve viewport (design §15.4). The x
	 * control point lives in the segment's unit box (`[0,1]`); the y control point is free but is
	 * clamped so the handle's drawn value stays inside the value axis the view shows (the segment
	 * box plus the axis headroom), which keeps every handle on-screen and grabbable. A zero-length
	 * segment (or zero value span) has no vertical scale, so only x is clamped. Pure, so the
	 * headless selftest pins it.
	 *
	 * @param {Array<number>} bezier the segment easing `[x1,y1,x2,y2]`
	 * @param {number} which 0 for the first control point, 1 for the second
	 * @param {number} value0 the segment's start value
	 * @param {number} value1 the segment's end value
	 * @param {number} axisLo the bottom of the visible value axis
	 * @param {number} axisHi the top of the visible value axis
	 * @returns {Array<number>} a clamped copy of the bezier
	 */
	function clampBezierHandle(bezier, which, value0, value1, axisLo, axisHi) {
		var b = Array.isArray(bezier) ? bezier.slice() : [0, 0, 1, 1];
		var xi = (Number(which) === 1 ? 1 : 0) * 2;
		var x = Number(b[xi]);
		b[xi] = isFinite(x) ? Math.max(0, Math.min(1, x)) : 0;
		var span = Number(value1) - Number(value0);
		var lo = Math.min(Number(axisLo), Number(axisHi));
		var hi = Math.max(Number(axisLo), Number(axisHi));
		if (isFinite(span) && span !== 0 && isFinite(lo) && isFinite(hi)) {
			var value = Number(value0) + span * Number(b[xi + 1]);
			if (isFinite(value)) {
				value = Math.max(lo, Math.min(hi, value));
				b[xi + 1] = (value - Number(value0)) / span;
			}
		}
		return b;
	}

	/**
	 * Normalise a datapack easing reference to the IR segment model: a name string stays a string,
	 * `{ "cubicBezier": [x1,y1,x2,y2] }` becomes the bare four-number array (design §15.4/§15.8).
	 *
	 * @param {*} raw the easing field (string, `{cubicBezier}`, array, or null)
	 * @returns {string|Array<number>|null} the normalized easing, or null when unrecognised
	 */
	function normalizeEasing(raw) {
		if (raw == null) {
			return null;
		}
		if (typeof raw === "string") {
			return raw;
		}
		var arr = raw;
		if (raw && typeof raw === "object" && !Array.isArray(raw)) {
			arr = raw.cubicBezier;
		}
		if (Array.isArray(arr) && arr.length === 4) {
			var out = [];
			for (var i = 0; i < 4; i++) {
				var n = Number(arr[i]);
				if (!isFinite(n)) {
					return null;
				}
				out.push(n);
			}
			return out;
		}
		return null;
	}

	/**
	 * The Bézier the editor draws a segment with: a numeric easing is itself, a known name maps to
	 * its canonical control points, anything else/absent is linear.
	 *
	 * @param {string|Array<number>} easing the IR segment easing
	 * @returns {Array<number>} a fresh `[x1,y1,x2,y2]`
	 */
	function easingToBezier(easing) {
		var norm = normalizeEasing(easing);
		if (Array.isArray(norm)) {
			return norm.slice();
		}
		if (typeof norm === "string" && EASING_BEZIER[norm]) {
			return EASING_BEZIER[norm].slice();
		}
		return [0, 0, 1, 1];
	}

	/**
	 * Recognise a Bézier as a named easing within a per-component tolerance (design §15.4): the
	 * name whose canonical control points every component matches. Lossless because the canonical
	 * points are what {@link easingToBezier} returns for that name.
	 *
	 * @param {Array<number>} bezier the `[x1,y1,x2,y2]` to test
	 * @param {number} [eps] per-component tolerance (default {@link SNAP_EPSILON})
	 * @returns {string|null} the matching name, or null when it is not a named easing
	 */
	function snapEasing(bezier, eps) {
		var b = normalizeEasing(bezier);
		if (!Array.isArray(b)) {
			return null;
		}
		var tol = isFiniteNumber(Number(eps)) ? Number(eps) : SNAP_EPSILON;
		var names = Object.keys(EASING_BEZIER);
		for (var i = 0; i < names.length; i++) {
			var c = EASING_BEZIER[names[i]];
			if (Math.abs(b[0] - c[0]) <= tol && Math.abs(b[1] - c[1]) <= tol
					&& Math.abs(b[2] - c[2]) <= tol && Math.abs(b[3] - c[3]) <= tol) {
				return names[i];
			}
		}
		return null;
	}

	/**
	 * The datapack JSON form of an IR segment easing (design §15.4): a name stays a string, a
	 * numeric bezier becomes `{ "cubicBezier": [x1,y1,x2,y2] }`. Compiler-facing.
	 *
	 * @param {string|Array<number>} easing the IR segment easing
	 * @returns {string|Object|null} the JSON value, or null for an unrecognised easing
	 */
	function easingToJson(easing) {
		var norm = normalizeEasing(easing);
		if (Array.isArray(norm)) {
			return { cubicBezier: norm.slice() };
		}
		if (typeof norm === "string") {
			return norm;
		}
		return null;
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
					var easing = normalizeEasing(key.easing);
					if (easing != null) {
						out.easing = easing;
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
	 * Build the absolute path of a datapack inside a world, mirroring the bridge's
	 * `<root>/<world>/datapacks/<pack>` layout (used by the Open picker when a listing entry
	 * carries only a name). Separators are normalized to `/`; the bridge accepts either.
	 *
	 * @param {string} root the saves directory from `GET /worlds`
	 * @param {string} world the world save-folder name
	 * @param {string} pack the datapack folder or zip name under the world's `datapacks/`
	 * @returns {string} the pack path, or "" when any part is missing
	 */
	function datapackPath(root, world, pack) {
		if (!root || !world || !pack) {
			return "";
		}
		var base = String(root).replace(/[\\/]+$/, "");
		return base + "/" + world + "/datapacks/" + pack;
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

	/** Whether a value node is wired into a param (of the active effect in a chain). */
	function hasDriver(tab, name) {
		var edges = tab && Array.isArray(tab.edges) ? tab.edges : [];
		var active = tab && tab.effects && tab.effects.length > 1 ? tab.effect() : null;
		var activeId = active ? active.id : null;
		for (var i = 0; i < edges.length; i++) {
			var edge = edges[i];
			if (!edge || edge.to !== name || edge.input != null || edge.from == null) {
				continue;
			}
			if (activeId != null && edge.effect != null && edge.effect !== activeId) {
				continue;
			}
			return true;
		}
		return false;
	}

	/**
	 * Re-hydrate a `collection` root into a chain of effect entries (design §15.2, P6). Each child is
	 * resolved to its own definition through `resolve` (an effect id -> parsed definition map), so the
	 * child's `type`, params and unknown top-level fields are carried. Each entry's own `params`
	 * overrides the resolved child's (the engine merges entry params on top), and its `duration`/
	 * `delay`/`easing` are read directly. When a child cannot be resolved (an external or built-in
	 * id), the id's path names the type and the entry's own `params` overrides are used, so a
	 * hand-written collection still imports; `external: true` and `effectId` let the compiler keep the
	 * original reference on export instead of renaming it to a local `__n` def.
	 *
	 * @param {Object} def the parsed collection definition (`type:"collection"`, `effects:[...]`)
	 * @param {Function} [resolve] `id -> child definition` lookup
	 * @returns {Array<Object>|null} the chain entries in array order, or null when `def` is not a collection
	 */
	function chainFromDefinition(def, resolve) {
		if (!def || typeof def !== "object" || def.type !== "collection"
				|| !Array.isArray(def.effects)) {
			return null;
		}
		var out = [];
		for (var i = 0; i < def.effects.length; i++) {
			var entry = def.effects[i];
			if (!entry || entry.effect == null) {
				continue;
			}
			var id = String(entry.effect);
			var child = typeof resolve === "function" ? resolve(id) : null;
			var type = child && child.type != null ? String(child.type) : null;
			if (type == null) {
				var colon = id.indexOf(":");
				type = colon >= 0 ? id.substring(colon + 1) : id;
			}
			var params = {};
			if (child && child.params && typeof child.params === "object") {
				Object.keys(child.params).forEach(function (name) {
					params[name] = child.params[name];
				});
			}
			if (entry.params && typeof entry.params === "object") {
				Object.keys(entry.params).forEach(function (name) {
					params[name] = entry.params[name];
				});
			}
			out.push({
				type: type,
				delay: isFiniteNumber(Number(entry.delay)) ? Number(entry.delay) : 0,
				duration: entry.duration != null && isFiniteNumber(Number(entry.duration))
						? Number(entry.duration) : 0,
				easing: entry.easing == null ? null : entry.easing,
				// Per-node timing comes from the resolved child definition, not the collection entry
				// (the engine's child spec carries only effect/delay/duration/params/easing).
				loop: child ? child.loop === true : false,
				persistent: child ? child.persistent === true : false,
				fade_ticks: child && isFiniteNumber(Number(child.fade_ticks)) ? Number(child.fade_ticks) : 0,
				params: params,
				source: child || null,
				effectId: id,
				external: child == null
			});
		}
		return out.length ? out : null;
	}

	/** The inline spec of one param: the stored object, a `{ value }` wrapper for a literal, or its default. */
	function componentSpec(tab, param) {
		var raw = tab && tab.params ? tab.params[param.name] : undefined;
		if (raw != null && typeof raw === "object") {
			return raw;
		}
		return { value: raw == null ? param.defaultValue : raw };
	}

	/* ------------------------------------------------------------------ */
	/* Bind schema (single source of truth: BoundParam.Kind/SourceKind)     */
	/* ------------------------------------------------------------------ */

	/** A safe default bind kind (needs no `pos`, no `objective`) for a fresh spec. */
	var DEFAULT_BIND = "health";

	/** The bind spec fields the datapack parser reads besides `bind`/`derive`. */
	var BIND_FIELD_KEYS = ["pos", "objective", "holder", "range", "invert", "scale",
		"yaw", "pitch", "selector", "uuid", "point", "offset"];

	/** The scalar fields the bind form renders as optional rows (per kind `fields`). */
	var BIND_SCALAR_KEYS = ["range", "invert", "scale", "yaw", "pitch", "holder"];

	/** The loaded schema's `bind` section (`{kinds:[...],sources:[...]}`), or null when unavailable. */
	function bindSection() {
		var schema = root.VFXEditor && root.VFXEditor.schema;
		var bind = schema && schema.bind;
		return bind && Array.isArray(bind.kinds) && bind.kinds.length ? bind : null;
	}

	/** The engine's bind kind ids (`BoundParam.Kind.values()`), from the schema. */
	function bindKinds() {
		var section = bindSection();
		return section ? section.kinds.map(function (kind) { return String(kind.id); }) : [];
	}

	/** The schema descriptor for one bind kind (`{id,needsPos,requiresObjective,fields}`), or null. */
	function bindKind(id) {
		var section = bindSection();
		if (!section) {
			return null;
		}
		for (var i = 0; i < section.kinds.length; i++) {
			if (section.kinds[i].id === id) {
				return section.kinds[i];
			}
		}
		return null;
	}

	/** Whether the kind needs a world `pos` anchor (`BoundParam.Kind.needsPos()`). */
	function bindNeedsPos(id) {
		var kind = bindKind(id);
		return Boolean(kind && kind.needsPos);
	}

	/** Whether the kind needs a non-blank `objective` (`BoundParam.Kind.requiresObjective()`). */
	function bindRequiresObjective(id) {
		var kind = bindKind(id);
		return Boolean(kind && kind.requiresObjective);
	}

	/** The optional field ids the kind reads (`Kind.optionalFields()`), e.g. `["range","invert","scale"]`. */
	function bindKindFields(id) {
		var kind = bindKind(id);
		return kind && Array.isArray(kind.fields) ? kind.fields.slice() : [];
	}

	/** The bind source ids (`BoundParam.SourceKind.values()`), e.g. `["camera","player",...]`. */
	function bindSources() {
		var section = bindSection();
		return section && Array.isArray(section.sources) ? section.sources.slice() : [];
	}

	/** The source id of a bind spec (`""` when it binds a kind directly, not a source). */
	function bindSpecSource(spec) {
		spec = spec || {};
		return bindSources().indexOf(spec.bind) >= 0 ? spec.bind : "";
	}

	/** The kind id of a bind spec (its `derive` when sourced, else `bind`; the default when unset). */
	function bindSpecKind(spec) {
		spec = spec || {};
		if (bindSpecSource(spec)) {
			return spec.derive == null ? "point" : String(spec.derive);
		}
		return spec.bind == null ? DEFAULT_BIND : String(spec.bind);
	}

	/**
	 * Prune a bind spec to the fields its kind reads: drop `range`/`invert`/`scale`/`yaw`/`pitch`/
	 * `holder` the kind does not use, and `objective` for a non-scoreboard kind.
	 */
	function pruneBindFields(spec, kind) {
		var allowed = bindKindFields(kind);
		BIND_SCALAR_KEYS.forEach(function (name) {
			if (allowed.indexOf(name) < 0) {
				delete spec[name];
			}
		});
		if (!bindRequiresObjective(kind)) {
			delete spec.objective;
		}
		return spec;
	}

	/**
	 * Fill the fields a bind spec's kind/source require so the emitted JSON parses: `pos` for a
	 * needsPos kind with no source (or a point/block source), and a non-blank `objective` for
	 * scoreboard. `fallbackPos` seeds a missing `pos` (the shell passes the preview context position
	 * so a fresh `screen_x`/`screen_y` lands on-screen instead of at the world origin).
	 *
	 * @param {Object} spec the bind spec (mutated)
	 * @param {Array<number>} [fallbackPos] the `pos` to seed when one is required and absent
	 * @returns {Object} the spec
	 */
	function applyBindDefaults(spec, fallbackPos) {
		var kind = bindSpecKind(spec);
		var source = bindSpecSource(spec);
		var needsPos = bindNeedsPos(kind) && (!source || source === "point" || source === "block");
		if (needsPos) {
			if (!Array.isArray(spec.pos)) {
				spec.pos = Array.isArray(fallbackPos) && fallbackPos.length === 3
						? [Number(fallbackPos[0]) || 0, Number(fallbackPos[1]) || 0, Number(fallbackPos[2]) || 0]
						: [0, 0, 0];
			}
		} else {
			delete spec.pos;
		}
		if (bindRequiresObjective(kind)) {
			if (spec.objective == null || spec.objective === "") {
				spec.objective = "objective";
			}
		}
		return spec;
	}

	/**
	 * Switch a bind spec to a kind, keeping any active source (`bind` stays the source, `derive`
	 * becomes the kind).
	 */
	function setBindKind(spec, kind) {
		spec = spec || {};
		var source = bindSpecSource(spec);
		if (source) {
			spec.bind = source;
			spec.derive = kind;
		} else {
			spec.bind = kind;
			delete spec.derive;
		}
		pruneBindFields(spec, kind);
		return applyBindDefaults(spec);
	}

	/**
	 * Switch a bind spec's source (`""` = none). A sourced spec emits `{bind:<source>, derive:<kind>}`;
	 * removing a source drops the source-only fields (`selector`/`uuid`/`point`/`offset`).
	 */
	function setBindSource(spec, source) {
		spec = spec || {};
		var kind = bindSpecKind(spec);
		if (source) {
			spec.bind = source;
			spec.derive = kind;
		} else {
			spec.bind = kind;
			delete spec.derive;
			delete spec.selector;
			delete spec.uuid;
			delete spec.point;
			delete spec.offset;
		}
		pruneBindFields(spec, kind);
		return applyBindDefaults(spec);
	}

	/**
	 * Build the datapack bind object for a kind/source/fields triple (design §15.1) — the pure emit
	 * the headless selftest pins. No source: `{bind:<kind>, pos?, objective?, ...scalars}`. A source:
	 * `{bind:<source>, derive:<kind>, [source fields], ...scalars}`.
	 *
	 * @param {string} kind the bind kind id
	 * @param {string} source the source id, or ""/null for none
	 * @param {Object} fields the field values
	 * @returns {Object} the bind spec
	 */
	function buildBindSpec(kind, source, fields) {
		fields = fields || {};
		var spec = { bind: source || kind };
		if (source) {
			spec.derive = kind;
			if (source === "entity") {
				if (fields.selector) {
					spec.selector = fields.selector;
				} else if (fields.uuid) {
					spec.uuid = fields.uuid;
				}
				if (fields.point && fields.point !== "center") {
					spec.point = fields.point;
				}
				if (Array.isArray(fields.offset)) {
					spec.offset = fields.offset.slice();
				}
			} else if (source === "point" || source === "block") {
				spec.pos = Array.isArray(fields.pos) ? fields.pos.slice() : [0, 0, 0];
			}
		} else if (bindNeedsPos(kind)) {
			spec.pos = Array.isArray(fields.pos) ? fields.pos.slice() : [0, 0, 0];
		}
		if (bindRequiresObjective(kind) && fields.objective != null && fields.objective !== "") {
			spec.objective = fields.objective;
		}
		var allowed = bindKindFields(kind);
		allowed.forEach(function (name) {
			if (name === "objective") {
				return;
			}
			if (fields[name] != null && fields[name] !== "") {
				spec[name] = fields[name];
			}
		});
		return spec;
	}

	/* ------------------------------------------------------------------ */
	/* Mask model (design §13.1)                                            */
	/* ------------------------------------------------------------------ */

	/**
	 * The composition operators, mirroring {@code VFXMaskOp}. Order is the datapack order; the
	 * right operand of an op may NOT itself be a composition (the parser rejects it — the shader
	 * folds left-associatively).
	 */
	var MASK_OPS = ["union", "intersection", "difference"];

	/**
	 * The mask leaf shapes the editor understands, mirroring {@code VFXMaskShapeKind} and the
	 * parser's per-kind parameter names/defaults. A shape with {@code deferred: true} is recognised
	 * (so an imported mask round-trips and an enum shape shows up) but the editor does not author
	 * it yet. Unknown shape ids (a registered custom id) are treated as deferred too.
	 */
	var MASK_SHAPES = {
		circle: { space: "screen", params: [["radius", 0.5]] },
		ellipse: { space: "screen", params: [["radius_x", 0.5], ["radius_y", 0.3]] },
		rect: { space: "screen", params: [["half_width", 0.5], ["half_height", 0.5], ["corner_radius", 0.0]] },
		polygon: { space: "screen", params: [["radius", 0.5], ["sides", 6.0]], deferred: true, note: "polygon" },
		sphere: { space: "world", params: [["radius", 8.0]], worldOnly: true },
		box: { space: "world", params: [["half_width", 4.0], ["half_height", 4.0], ["half_depth", 4.0]], worldOnly: true },
		sky: { space: "dome", params: [] },
		depth: { space: "screen", params: [["distance", 32.0]] },
		block: { space: "world", params: [["radius", 16.0]], deferred: true, note: "block geometry" }
	};

	var MASK_CENTER_SCREEN = [0.5, 0.5];
	var MASK_CENTER_WORLD = [0.0, 0.0, 0.0];
	var MASK_CENTER_DOME = [0.0, 0.0];

	/** The shape definition for an id, or null (a custom/unknown id). */
	function maskShapeDef(shape) {
		return Object.prototype.hasOwnProperty.call(MASK_SHAPES, String(shape)) ? MASK_SHAPES[String(shape)] : null;
	}

	/**
	 * The editor's shape table, preferring the schema's additive `structural.maskShapeFields`
	 * (design §13.1: the UI does not hardcode the shapes or their fields). Each entry mirrors the
	 * built-in {@code MASK_SHAPES} shape: {@code {space, worldOnly, deferred, note, params}} with
	 * {@code params} an ordered {@code [[name, default], ...]}. Unknown ids fall back to the
	 * built-in table, so a schema that predates this section still works.
	 *
	 * @param {Object} [descriptor] the effect's schema descriptor
	 * @returns {Object<string, Object>} the shape table
	 */
	function maskShapeTable(descriptor) {
		var fields = descriptor && descriptor.structural ? descriptor.structural.maskShapeFields : null;
		if (!Array.isArray(fields) || !fields.length) {
			return MASK_SHAPES;
		}
		var table = {};
		Object.keys(MASK_SHAPES).forEach(function (id) {
			var def = MASK_SHAPES[id];
			table[id] = { space: def.space, params: def.params, worldOnly: def.worldOnly,
				deferred: def.deferred, note: def.note };
		});
		fields.forEach(function (spec) {
			if (!spec || spec.id == null) {
				return;
			}
			var id = String(spec.id);
			var params = Array.isArray(spec.params) ? spec.params.map(function (p) {
				return [p[0], p[1]];
			}) : [];
			table[id] = { space: spec.space, params: params, worldOnly: spec.worldOnly === true,
				deferred: spec.deferred === true, note: (MASK_SHAPES[id] || {}).note };
		});
		return table;
	}

	/** The shape definition for an id in a table (default: the built-in table), or null. */
	function maskShapeDefIn(table, shape) {
		var source = table || MASK_SHAPES;
		return Object.prototype.hasOwnProperty.call(source, String(shape)) ? source[String(shape)] : null;
	}

	/** Whether the effect descriptor offers a mask at all (post-effects only). */
	function maskAffordance(descriptor) {
		return Boolean(descriptor && descriptor.structural && descriptor.structural.supportsMask === true);
	}

	/** The shape ids the schema lists for an effect, or every editor-known id as a fallback. */
	function maskShapeList(descriptor) {
		var list = descriptor && descriptor.structural ? descriptor.structural.maskShapes : null;
		return Array.isArray(list) && list.length ? list.slice() : Object.keys(MASK_SHAPES);
	}

	/* ---- Non-post structural options (design §13.4) ------------------- */

	/**
	 * The schema's structural section, or null (a descriptor without one offers no structural
	 * options). Every affordance below is a plain flag on this section, so the UI adds a sub-editor
	 * for a new structural option without a per-effect-type branch (design §13.4 forward-compat).
	 *
	 * @param {Object} [descriptor] the effect's schema descriptor
	 * @returns {Object|null} the `structural` object
	 */
	function structuralSection(descriptor) {
		return descriptor && descriptor.structural ? descriptor.structural : null;
	}

	/** Whether the effect reads a top-level `positions` list (design §13.4). */
	function positionsAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsPositions === true);
	}

	/** Whether the effect reads a top-level `region` that expands into positions (design §13.4). */
	function regionAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsRegion === true);
	}

	/** Whether the effect reads a top-level `entity_selector` (design §13.4). */
	function entitySelectorAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsEntitySelector === true);
	}

	/** Whether the effect reads a top-level `sound` (+ `sound_pos`, `volume`/`pitch`; design §13.4). */
	function soundAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsSound === true);
	}

	/** The parser's positions entry shape (mirrors VFXStructuralDescriptor.positionFields). */
	var POSITIONS_FIELD_FALLBACK = { arrayArity: 3, objectFields: ["entity", "point", "dir", "offset", "distance"],
		pointValues: ["feet", "center", "eyes"], dirValues: ["none", "look"], offsetArity: 3 };
	var REGION_FIELD_FALLBACK = { arity: 6 };
	var ENTITY_SELECTOR_FIELD_FALLBACK = { field: "entity_selector" };
	var SOUND_FIELD_FALLBACK = { fields: ["sound", "sound_pos", "volume", "pitch"], posArity: 3 };

	/** The schema's positions field spec, or the parser-mirroring fallback when absent. */
	function positionsFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.positionsFields ? s.positionsFields : POSITIONS_FIELD_FALLBACK;
	}

	/** The schema's region field spec, or the fallback. */
	function regionFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.regionFields ? s.regionFields : REGION_FIELD_FALLBACK;
	}

	/** The schema's entity-selector field spec, or the fallback. */
	function entitySelectorFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.entitySelectorFields ? s.entitySelectorFields : ENTITY_SELECTOR_FIELD_FALLBACK;
	}

	/** The schema's sound field spec, or the fallback. */
	function soundFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.soundFields ? s.soundFields : SOUND_FIELD_FALLBACK;
	}

	/* ---- Tail slice (design §13.4): particle ids, pattern/surface, anchor, window, image --- */

	/** Whether the effect reads the optional `particle`/`shape`/`block`/`item` strings. */
	function particleIdsAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsParticleIds === true);
	}

	/** Whether the effect reads a top-level `pattern` block (surface/sky pattern). */
	function patternAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsPattern === true);
	}

	/** Whether the effect reads a top-level `surface` block (surface pattern). */
	function surfaceAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsSurface === true);
	}

	/** Whether the effect reads a top-level `anchor` string (sky pattern only). */
	function anchorAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsAnchor === true);
	}

	/** Whether the effect reads the window spec fields (window_create/control). */
	function windowAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsWindow === true);
	}

	/** Whether the effect reads the screen-image texture (screen_image). */
	function screenImageAffordance(descriptor) {
		return Boolean(structuralSection(descriptor) && structuralSection(descriptor).supportsScreenImage === true);
	}

	/** The parser's particle-id field shape (mirrors VFXStructuralDescriptor.particleFields). */
	var PARTICLE_FIELD_FALLBACK = { fields: ["particle", "shape", "block", "item"] };
	var PATTERN_FIELD_FALLBACK = { figureValues: ["circle", "ellipse", "rect", "polygon"],
		fillValues: ["solid", "stroke"], shapeParams: [], repeatMin: 1, repeatMax: 64,
		centerArity: 3, textureKeys: ["id", "source", "atlas", "channel", "sheet", "aspect"],
		textureSources: ["block", "item", "atlas", "standalone"],
		textureChannels: ["r", "g", "b", "alpha", "luminance"],
		textureAspects: ["preserve", "stretch"], sheetDim: 16, sheetFrames: 256 };
	var SURFACE_FIELD_FALLBACK = { fields: ["faces", "min", "max", "band_softness", "stitch"],
		faceTokens: ["up", "down", "north", "south", "east", "west", "x", "y", "z",
			"horizontal", "vertical", "all"], maxTokens: 8, bandSoftnessMax: 4 };
	var ANCHOR_FIELD_FALLBACK = { field: "anchor", values: ["dome", "sun", "moon", "stars"] };
	var WINDOW_FIELD_FALLBACK = { fields: ["id", "texture", "titles"], maxTitles: 64 };
	var SCREEN_IMAGE_FIELD_FALLBACK = { field: "texture" };

	/** The schema's particle-id field spec, or the parser-mirroring fallback when absent. */
	function particleFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.particleFields ? s.particleFields : PARTICLE_FIELD_FALLBACK;
	}

	/** The schema's pattern field spec, or the fallback. */
	function patternFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.patternFields ? s.patternFields : PATTERN_FIELD_FALLBACK;
	}

	/** The schema's surface field spec, or the fallback. */
	function surfaceFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.surfaceFields ? s.surfaceFields : SURFACE_FIELD_FALLBACK;
	}

	/** The schema's anchor field spec, or the fallback. */
	function anchorFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.anchorFields ? s.anchorFields : ANCHOR_FIELD_FALLBACK;
	}

	/** The schema's window field spec, or the fallback. */
	function windowFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.windowFields ? s.windowFields : WINDOW_FIELD_FALLBACK;
	}

	/** The schema's screen-image field spec, or the fallback. */
	function screenImageFieldSpec(descriptor) {
		var s = structuralSection(descriptor);
		return s && s.screenImageFields ? s.screenImageFields : SCREEN_IMAGE_FIELD_FALLBACK;
	}

	/** Whether a node is a composition (has an `op`). */
	function isMaskComposition(node) {
		return Boolean(node && typeof node === "object" && node.op != null);
	}

	/** The top-level mask space, or null when unset. */
	function maskTopSpace(mask) {
		return mask && typeof mask.space === "string" ? mask.space : null;
	}

	/** The leaves of a mask in depth-first (left-to-right) order — the parser's slot order. */
	function maskLeaves(mask) {
		var out = [];
		function walk(node) {
			if (!node || typeof node !== "object") {
				return;
			}
			if (node.op != null) {
				walk(node.a);
				walk(node.b);
				return;
			}
			// A single-leaf top-level mask may be wrapped as `{ "a": { shape ... } }` (the parser
			// unwraps it); follow that so the leaves match.
			if (node.shape == null && node.a && typeof node.a === "object") {
				walk(node.a);
				return;
			}
			out.push(node);
		}
		walk(mask);
		return out;
	}

	/** The operator list in flatten (left-fold) order, parallel to {@link maskLeaves}. */
	function maskOps(mask) {
		var ops = [];
		(function walk(node) {
			if (!node || typeof node !== "object" || node.op == null) {
				return;
			}
			walk(node.a);
			ops.push(node.op);
			walk(node.b);
		})(mask);
		return ops;
	}

	/** The space a leaf is classified in, resolved like the parser (leaf > shape > top). */
	function maskLeafSpace(leaf, topSpace, table) {
		if (leaf && typeof leaf.space === "string") {
			return leaf.space;
		}
		var def = maskShapeDefIn(table, leaf && leaf.shape);
		if (def && def.space) {
			return def.space;
		}
		return topSpace || "screen";
	}

	/** The default centre for a space (screen is centre-of-view 0.5/0.5). */
	function maskCenterDefaults(space) {
		if (space === "world") {
			return MASK_CENTER_WORLD.slice();
		}
		if (space === "dome") {
			return MASK_CENTER_DOME.slice();
		}
		return MASK_CENTER_SCREEN.slice();
	}

	/** The centre component count for a space (world needs z). */
	function maskCenterArity(space) {
		return space === "world" ? 3 : 2;
	}

	/** The parser's default softness for a space. */
	function maskSoftnessDefault(space) {
		return space === "screen" ? 0.01 : 0.25;
	}

	/** Whether the editor authors this shape yet (false: polygon/block/custom). */
	function maskShapeDeferred(shape, table) {
		var def = maskShapeDefIn(table, shape);
		if (def) {
			return def.deferred === true;
		}
		// An unknown id is a registered custom shape: recognised, deferred.
		return String(shape) !== "sky";
	}

	/**
	 * The reserved `mask.p<N>.*` slots of a mask, in parser order: for every leaf (depth-first,
	 * N = its index) the centre axes, rotation, the per-kind numeric params, softness and stroke.
	 * Each slot names the leaf field it maps to, so the compiler can replace an inline value with a
	 * `{ "from": node }` wire without re-deriving the shape table.
	 *
	 * @param {Object} mask a datapack-shaped mask object
	 * @returns {Array<Object>} `{name, leafIndex, kind, field, fieldIndex?, paramName?, shape, space, deferred, defaultValue}`
	 */
	function maskSlots(mask, table) {
		var topSpace = maskTopSpace(mask);
		var leaves = maskLeaves(mask);
		var slots = [];
		leaves.forEach(function (leaf, i) {
			var space = maskLeafSpace(leaf, topSpace, table);
			var def = maskShapeDefIn(table, leaf.shape);
			var deferred = maskShapeDeferred(leaf.shape, table);
			var axes = space === "world" ? ["x", "y", "z"] : ["x", "y"];
			var centerDefaults = maskCenterDefaults(space);
			var center = Array.isArray(leaf.center) ? leaf.center : null;
			axes.forEach(function (axis, j) {
				var value = center && isFiniteNumber(Number(center[j])) ? Number(center[j]) : centerDefaults[j];
				slots.push({ name: "mask.p" + i + ".center_" + axis, leafIndex: i, kind: "center",
					field: "center", fieldIndex: j, defaultValue: value, shape: leaf.shape,
					space: space, deferred: deferred });
			});
			slots.push({ name: "mask.p" + i + ".rotation", leafIndex: i, kind: "rotation",
				field: "rotation",
				defaultValue: leaf.rotation != null && isFiniteNumber(Number(leaf.rotation)) ? Number(leaf.rotation) : 0,
				shape: leaf.shape, space: space, deferred: deferred });
			var params = def ? def.params : null;
			if (params) {
				params.forEach(function (param, j) {
					var raw = leaf[param[0]];
					slots.push({ name: "mask.p" + i + ".p" + j, leafIndex: i, kind: "param",
						field: param[0], paramName: param[0],
						defaultValue: raw != null && isFiniteNumber(Number(raw)) ? Number(raw) : param[1],
						shape: leaf.shape, space: space, deferred: deferred });
				});
			}
			slots.push({ name: "mask.p" + i + ".soft", leafIndex: i, kind: "soft", field: "softness",
				defaultValue: leaf.softness != null && isFiniteNumber(Number(leaf.softness)) ? Number(leaf.softness) : maskSoftnessDefault(space),
				shape: leaf.shape, space: space, deferred: deferred });
			var stroke = leaf.fill === "stroke";
			slots.push({ name: "mask.p" + i + ".stroke", leafIndex: i, kind: "stroke", field: "stroke_width",
				defaultValue: leaf.stroke_width != null && isFiniteNumber(Number(leaf.stroke_width)) ? Number(leaf.stroke_width)
						: (stroke ? 0.05 : 0),
				shape: leaf.shape, space: space, deferred: deferred });
		});
		return slots;
	}

	/** The reserved `mask.` parameter names of a mask, as a `{name: true}` set (for exclusion). */
	function maskParamNames(mask) {
		var out = {};
		maskSlots(mask).forEach(function (slot) { out[slot.name] = true; });
		return out;
	}

	/** A synthetic param descriptor for a mask slot, so the inspector can render/drive it. */
	function maskParamDescriptor(slot) {
		return {
			name: slot.name,
			kind: "FLOAT",
			defaultValue: slot.defaultValue,
			min: -Infinity,
			max: Infinity,
			step: 0,
			group: "Mask",
			animatable: !slot.deferred,
			bindable: !slot.deferred,
			fieldCapable: false,
			enumValues: [],
			neutral: null,
			vectorGroup: null,
			vectorIndex: 0
		};
	}

	/** A fresh leaf for a shape id: default centre plus the per-kind params. */
	function makeMaskLeaf(shape, table) {
		var id = String(shape || "circle");
		var def = maskShapeDefIn(table, id);
		var space = def && def.space ? def.space : "screen";
		var leaf = { shape: id, center: maskCenterDefaults(space).slice(0, maskCenterArity(space)) };
		if (def) {
			def.params.forEach(function (param) { leaf[param[0]] = param[1]; });
		}
		return leaf;
	}

	/**
	 * A left-nested datapack mask from a chain of leaves + operators. One leaf emits the leaf
	 * directly (with `invert`/`space` beside it); two or more nest `{op, a, b}` with the left
	 * operand the running composition, so the flattened slot order is leaves[0..n]. `invert`/`space`
	 * land on the outermost object (the parser only allows them at the top level).
	 *
	 * @param {Array<Object>} leaves the leaf objects
	 * @param {Array<string>} ops one operator per join, length leaves.length-1
	 * @param {boolean} [invert] the top-level invert flag
	 * @param {string} [space] the top-level space override
	 * @returns {Object|null} the datapack mask object
	 */
	function maskFromChain(leaves, ops, invert, space) {
		var list = (leaves || []).filter(function (leaf) { return leaf && typeof leaf === "object"; });
		if (!list.length) {
			return null;
		}
		var acc = JSON.parse(JSON.stringify(list[0]));
		for (var i = 0; i < list.length - 1; i++) {
			acc = { op: (ops && ops[i]) || "union", a: acc, b: JSON.parse(JSON.stringify(list[i + 1])) };
		}
		if (invert) {
			acc.invert = true;
		}
		if (space) {
			acc.space = space;
		}
		return acc;
	}

	/**
	 * Flatten a datapack mask into the editor chain view `{invert, space, leaves, ops}`.
	 *
	 * @param {Object} mask the datapack mask
	 * @returns {{invert: boolean, space: (string|null), leaves: Array<Object>, ops: Array<string>}}
	 */
	function maskChain(mask) {
		return {
			invert: Boolean(mask && mask.invert),
			space: maskTopSpace(mask),
			leaves: maskLeaves(mask).map(function (leaf) { return JSON.parse(JSON.stringify(leaf)); }),
			ops: maskOps(mask)
		};
	}

	/**
	 * Whether a composition nests on the right, which the parser rejects (nest compositions on the
	 * left). Pure, so the editor can refuse before emitting an unparseable mask.
	 */
	function maskRightCompositionInvalid(mask) {
		var bad = false;
		(function walk(node) {
			if (!node || typeof node !== "object" || node.op == null) {
				return;
			}
			if (isMaskComposition(node.b)) {
				bad = true;
			}
			walk(node.a);
			walk(node.b);
		})(mask);
		return bad;
	}

	/* ---- Mask node model (design §13.1) ------------------------------- */

	/**
	 * The editable node state of a mask: the left-nested a/b composition tree flattened to an
	 * ordered leaf list plus one operator per join, beside the top-level `invert`/`space`. This is
	 * the source of truth the canvas `Mask`/`MaskLeaf` nodes read and write; {@link maskFromNodeState}
	 * projects it back to the parser's datapack object. Because the joins are left-nested by
	 * construction, the right operand of every composition is a leaf, matching the parser.
	 *
	 * @param {Object} mask a datapack-shaped mask object
	 * @returns {Object} `{invert, space, leaves, ops}`
	 */
	function maskNodeState(mask) {
		return maskChain(mask);
	}

	/**
	 * Project a node state back to the parser's datapack mask object. The inverse of
	 * {@link maskNodeState}; one leaf emits directly, two or more nest on the left only.
	 *
	 * @param {Object} state `{invert, space, leaves, ops}`
	 * @returns {Object|null} the datapack mask, or null for an empty tree
	 */
	function maskFromNodeState(state) {
		if (!state) {
			return null;
		}
		return maskFromChain(state.leaves, state.ops, state.invert, state.space);
	}

	/**
	 * Resolve one numeric leaf slot onto its datapack leaf field (pure). A centre slot writes the
	 * matching `center[j]` axis, seeding the default centre first; every other slot writes its named
	 * field. Used by both the canvas MaskLeaf nodes and the inspector so they cannot diverge.
	 *
	 * @param {Object} leaf the datapack leaf object (mutated)
	 * @param {Object} slot a `maskSlots` entry of kind `center`/`param`/`soft`/`stroke`/`rotation`
	 * @param {number} value the numeric value
	 */
	function maskWriteSlot(leaf, slot, value) {
		if (!leaf || !slot) {
			return;
		}
		if (slot.kind === "center") {
			if (!Array.isArray(leaf.center)) {
				leaf.center = maskCenterDefaults(slot.space).slice(0, maskCenterArity(slot.space));
			}
			leaf.center[slot.fieldIndex] = value;
		} else {
			leaf[slot.field] = value;
		}
	}

	/**
	 * The `maskSlots` of a mask grouped by depth-first leaf index, so a MaskLeaf node can list only
	 * its own fields.
	 *
	 * @param {Object} mask a datapack-shaped mask
	 * @param {Object} [table] the shape table (schema-driven when given)
	 * @returns {Array<Array<Object>>} `[ [slot...], [slot...] ]`, one list per leaf
	 */
	function maskSlotsByLeaf(mask, table) {
		var byLeaf = [];
		maskSlots(mask, table).forEach(function (slot) {
			if (!byLeaf[slot.leafIndex]) {
				byLeaf[slot.leafIndex] = [];
			}
			byLeaf[slot.leafIndex].push(slot);
		});
		return byLeaf;
	}

	/* ------------------------------------------------------------------ */
	/* Per-pixel field model (design §13.2)                               */
	/* ------------------------------------------------------------------ */

	/**
	 * The parser's field tree caps, mirroring `VFXField` (used only when the schema's additive
	 * `field.caps` section is absent, e.g. an old bridge or the standalone selftest).
	 */
	var FIELD_CAP_FALLBACK = { maxDepth: 3, maxLeaves: 4, maxProgram: 8, maxCurvePoints: 8,
		maxTextureLeaves: 1, maxParams: 16 };

	/** The schema's additive `field` section (`{fns, ops, caps}`), or null. */
	function fieldSection() {
		var schema = root.VFXEditor && root.VFXEditor.schema;
		var field = schema && schema.field;
		return field && typeof field === "object" ? field : null;
	}

	/**
	 * The field function table by id, from the schema (never hardcoded: a new `VFXFieldFn`
	 * appears without an editor change). Each entry mirrors the Java inventory:
	 * `{id, outputType, spatial, hasChannel, needsDepth, hasCurve, hasTexture, hasShape, params}`.
	 *
	 * @returns {Object<string, Object>} id -> spec
	 */
	function fieldFnTable() {
		var table = {};
		var section = fieldSection();
		((section && section.fns) || []).forEach(function (spec) {
			if (spec && spec.id != null) {
				table[String(spec.id)] = spec;
			}
		});
		return table;
	}

	/** The field function ids in schema order, or [] when the schema has no field section. */
	function fieldFnIds() {
		var section = fieldSection();
		return section && Array.isArray(section.fns)
				? section.fns.map(function (spec) { return String(spec.id); }) : [];
	}

	/** The field operator specs (`[{id, arity}]`) in schema order, or []. */
	function fieldOpList() {
		var section = fieldSection();
		return section && Array.isArray(section.ops) ? section.ops.slice() : [];
	}

	/**
	 * @param {string} id the operator id
	 * @returns {number} its arity (2, or 3 for mix), or 2 when unknown
	 */
	function fieldOpArity(id) {
		var ops = fieldOpList();
		for (var i = 0; i < ops.length; i++) {
			if (String(ops[i].id) === String(id)) {
				return Number(ops[i].arity) === 3 ? 3 : 2;
			}
		}
		return 2;
	}

	/** The schema's field caps, or the parser-mirroring fallback when absent. */
	function fieldCaps() {
		var section = fieldSection();
		return section && section.caps ? section.caps : FIELD_CAP_FALLBACK;
	}

	/** Whether a value-node kind belongs to the field tree (never emitted into the value graph). */
	function isFieldKind(kind) {
		return kind === "field" || kind === "field_op";
	}

	/**
	 * Whether a param offers the field affordance: its descriptor carries `fieldCapable`
	 * (design §13.2 — the gate; only `dent:intensity` and `color_grade:tint_r` today).
	 *
	 * @param {Object} [descriptor] the effect's schema descriptor
	 * @param {string} name the param name
	 * @returns {boolean} true when a field node may be attached to the socket
	 */
	function fieldAffordance(descriptor, name) {
		var params = descriptor && Array.isArray(descriptor.params) ? descriptor.params : [];
		for (var i = 0; i < params.length; i++) {
			if (params[i] && params[i].name === name) {
				return params[i].fieldCapable === true;
			}
		}
		return false;
	}

	/**
	 * A fresh authored leaf for a function id: `{field, ...defaults}` with an explicit
	 * `space` for a spatial function. The default space is `screen` (the parser would default
	 * a missing space to `world`, which needs depth and warns off layer 0 — the editor is
	 * explicit instead).
	 *
	 * @param {string} fnId the function id
	 * @param {Object} [table] the function table (schema-driven when given)
	 * @returns {Object} the datapack-shaped leaf
	 */
	function makeFieldLeaf(fnId, table) {
		var spec = (table || fieldFnTable())[String(fnId)] || null;
		var leaf = { field: String(fnId) };
		if (spec && Array.isArray(spec.params)) {
			spec.params.forEach(function (param) {
				leaf[param[0]] = param[1];
			});
		}
		if (spec && spec.spatial) {
			leaf.space = "screen";
		}
		if (spec && spec.hasChannel) {
			leaf.channel = "luminance";
		}
		if (spec && spec.hasTexture) {
			leaf.texture = "minecraft:textures/block/stone.png";
		}
		if (spec && spec.hasShape) {
			leaf.primitive = "circle";
			leaf.fill = "solid";
		}
		if (spec && spec.hasCurve) {
			leaf.points = [{ time: 0, value: 0 }, { time: 1, value: 1 }];
		}
		return leaf;
	}

	/**
	 * The size of a field tree, mirroring the parser's cap accounting (`VFXField` adorning
	 * leaves/depth/nodes/texture leaves; curve points are the per-leaf max, capped per leaf).
	 *
	 * @param {Object} field a datapack-shaped field object
	 * @param {Object} [table] the function table
	 * @returns {{leaves: number, depth: number, nodes: number, textureLeaves: number, curvePoints: number}}
	 */
	function fieldStats(field, table) {
		var spec = table || fieldFnTable();
		function walk(node) {
			if (!node || typeof node !== "object") {
				return { leaves: 0, depth: 0, nodes: 0, textureLeaves: 0, curvePoints: 0 };
			}
			if (node.op != null) {
				var acc = { leaves: 0, depth: 0, nodes: 1, textureLeaves: 0, curvePoints: 0 };
				[node.a, node.b, node.factor].forEach(function (child) {
					if (child == null) {
						return;
					}
					var sub = walk(child);
					acc.leaves += sub.leaves;
					acc.depth = Math.max(acc.depth, sub.depth);
					acc.nodes += sub.nodes;
					acc.textureLeaves += sub.textureLeaves;
					acc.curvePoints = Math.max(acc.curvePoints, sub.curvePoints);
				});
				acc.depth += 1;
				return acc;
			}
			if (node.field == null) {
				return { leaves: 0, depth: 0, nodes: 0, textureLeaves: 0, curvePoints: 0 };
			}
			var fn = spec[String(node.field)] || null;
			return {
				leaves: 1,
				depth: 1,
				nodes: 1,
				textureLeaves: fn && fn.hasTexture ? 1 : 0,
				curvePoints: Array.isArray(node.points) ? node.points.length : 0
			};
		}
		return walk(field);
	}

	/**
	 * Whether any part of the tree reads the scene depth: a `needsDepth` function, or a
	 * spatial function evaluated in `world` space (mirrors `VFXField`'s `needsDepth`; an absent
	 * space defaults to `world` for a spatial function, exactly like the parser).
	 *
	 * @param {Object} field a datapack-shaped field object
	 * @param {Object} [table] the function table
	 * @returns {boolean} true when the field needs depth
	 */
	function fieldNeedsDepth(field, table) {
		var spec = table || fieldFnTable();
		var found = false;
		(function walk(node) {
			if (!node || typeof node !== "object" || found) {
				return;
			}
			if (node.op != null) {
				walk(node.a);
				walk(node.b);
				walk(node.factor);
				return;
			}
			if (node.field == null) {
				return;
			}
			var fn = spec[String(node.field)] || null;
			if (!fn) {
				return;
			}
			if (fn.needsDepth) {
				found = true;
				return;
			}
			if (fn.spatial && String(node.space || "world") === "world") {
				found = true;
			}
		})(field);
		return found;
	}

	/**
	 * The cap/layer warnings for one authored field (design §13.2): a record per violated cap
	 * plus at most one depth/layer-0 record (a depth/world field is only valid on screen
	 * layer 0, else neutral — warned once per definition by the caller aggregating per param).
	 *
	 * @param {string} param the input name (for the record)
	 * @param {Object} field a datapack-shaped field object
	 * @param {number} screenLayer the effect's screen layer
	 * @param {Object} [caps] the cap map
	 * @param {Object} [table] the function table
	 * @returns {Array<Object>} `{param, reason, ...}` records (empty when clean)
	 */
	function fieldCapWarnings(param, field, screenLayer, caps, table) {
		var limits = caps || fieldCaps();
		var stats = fieldStats(field, table);
		var out = [];
		function cap(reason, value, limit) {
			if (isFiniteNumber(limit) && value > limit) {
				out.push({ param: param, reason: reason, value: value, limit: limit });
			}
		}
		cap("cap-depth", stats.depth, Number(limits.maxDepth));
		cap("cap-leaves", stats.leaves, Number(limits.maxLeaves));
		cap("cap-program", stats.nodes, Number(limits.maxProgram));
		cap("cap-texture", stats.textureLeaves, Number(limits.maxTextureLeaves));
		cap("cap-curve", stats.curvePoints, Number(limits.maxCurvePoints));
		if (fieldNeedsDepth(field, table) && Number(screenLayer) !== 0) {
			out.push({ param: param, reason: "layer-0", screenLayer: screenLayer });
		}
		return out;
	}

	/* ------------------------------------------------------------------ */
	/* Pack import index (lazy open)                                       */
	/* ------------------------------------------------------------------ */

	/**
	 * Definitions auto-materialised as tabs when a pack is imported. A pack above this opens as a
	 * lazy, searchable index instead, so opening is O(N) and independent of the effect count.
	 */
	var MAX_EAGER_TABS = 8;

	/** Cap on the pack-list rows rendered at once (search narrows the rest into view). */
	var PACK_LIST_MAX = 200;

	/** Parse an imported effect entry (object or JSON string) into a definition object, or null. */
	function parseEffectEntry(raw) {
		if (raw && typeof raw === "object") {
			return raw;
		}
		if (typeof raw === "string") {
			try {
				var parsed = JSON.parse(raw);
				return parsed && typeof parsed === "object" ? parsed : null;
			} catch (e) {
				return null;
			}
		}
		return null;
	}

	/** The local (post-colon) effect name from a possibly qualified `namespace:name` id. */
	function packLocalName(id) {
		var text = String(id);
		var colon = text.indexOf(":");
		return colon >= 0 ? text.substring(colon + 1) : text;
	}

	/** A cheap byte-ish size hint of a raw entry for the pack list (no graph is built for it). */
	function packSizeHint(raw) {
		if (typeof raw === "string") {
			return raw.length;
		}
		try {
			return JSON.stringify(raw).length;
		} catch (e) {
			return 0;
		}
	}

	/**
	 * Build the lightweight import index for a pack: every file parsed once (O(N)), collection
	 * children marked `consumed` (they become chain nodes, not their own entry), and a sorted list
	 * of `{ name, def, type, size }`. No tab, graph or snapshot is created here.
	 *
	 * @param {string} namespace the pack namespace
	 * @param {Object|Array} effects name -> raw effect JSON (object or array), or null
	 * @returns {{namespace: string, defs: Object, resolve: Function, entries: Array, count: number}}
	 */
	function buildPackIndex(namespace, effects) {
		var pairs = [];
		if (Array.isArray(effects)) {
			effects.forEach(function (value, index) {
				pairs.push([value && value.name ? value.name : String(index), value]);
			});
		} else if (effects && typeof effects === "object") {
			Object.keys(effects).forEach(function (key) {
				pairs.push([key, effects[key]]);
			});
		}
		var defs = {};
		var raw = {};
		pairs.forEach(function (pair) {
			defs[pair[0]] = parseEffectEntry(pair[1]);
			raw[pair[0]] = pair[1];
		});
		var resolve = function (id) {
			return defs[packLocalName(id)] || null;
		};
		var consumed = {};
		Object.keys(defs).forEach(function (name) {
			var def = defs[name];
			if (def && def.type === "collection" && Array.isArray(def.effects)) {
				def.effects.forEach(function (child) {
					if (child && child.effect != null && defs[packLocalName(child.effect)]) {
						consumed[packLocalName(child.effect)] = true;
					}
				});
			}
		});
		var entries = [];
		pairs.forEach(function (pair) {
			if (consumed[pair[0]]) {
				return;
			}
			var def = defs[pair[0]];
			entries.push({
				name: pair[0],
				def: def,
				type: def && def.type != null ? String(def.type) : null,
				size: packSizeHint(raw[pair[0]])
			});
		});
		entries.sort(function (a, b) {
			return a.name < b.name ? -1 : (a.name > b.name ? 1 : 0);
		});
		return { namespace: namespace ? String(namespace) : "", defs: defs, resolve: resolve,
			entries: entries, count: entries.length };
	}

	/** True when a pack is large enough that opening it eagerly would be the old O(N^2) fan-out. */
	function packIsLazy(index) {
		return Boolean(index) && index.entries.length > MAX_EAGER_TABS;
	}

	/**
	 * Materialise an import index: create one tab per entry, hydrating and building each graph,
	 * inside a single suspended-history window and with one trailing commit. A pack above
	 * {@link MAX_EAGER_TABS} is NOT opened here unless `options.openAll` is set (the shell shows the
	 * lazy list instead). O(N) work and a bounded (one) commit — never the per-tab snapshot fan-out.
	 *
	 * @param {Object} index a {@link buildPackIndex} result
	 * @param {Object} hooks `createTab(label, entry)`, `hydrate(tab, def, resolve)`,
	 *   `materialize(tab)`, `select(tab)`, `commit()`, optional `suspend()`/`resume()`
	 * @param {Object} [options] `{ openAll: true }` forces materialisation
	 * @returns {{created: number, indexed: number, lazy: boolean, tabs: Array}}
	 */
	function openPackPlan(index, hooks, options) {
		if (!index) {
			return { created: 0, indexed: 0, lazy: false, tabs: [] };
		}
		var openAll = Boolean(options && options.openAll);
		if (!openAll && packIsLazy(index)) {
			return { created: 0, indexed: index.entries.length, lazy: true, tabs: [] };
		}
		hooks = hooks || {};
		var tabs = [];
		var suspended = false;
		if (typeof hooks.suspend === "function") {
			hooks.suspend();
			suspended = true;
		}
		try {
			index.entries.forEach(function (entry) {
				if (typeof hooks.createTab !== "function") {
					return;
				}
				var label = index.namespace ? index.namespace + ":" + entry.name : entry.name;
				var tab = hooks.createTab(label, entry);
				if (!tab) {
					return;
				}
				if (typeof hooks.hydrate === "function") {
					hooks.hydrate(tab, entry.def, index.resolve);
				}
				if (typeof hooks.materialize === "function") {
					hooks.materialize(tab);
				}
				tabs.push(tab);
			});
		} finally {
			if (suspended && typeof hooks.resume === "function") {
				hooks.resume();
			}
		}
		if (tabs.length && typeof hooks.select === "function") {
			hooks.select(tabs[tabs.length - 1]);
		}
		if (typeof hooks.commit === "function") {
			hooks.commit();
		}
		return { created: tabs.length, indexed: index.entries.length, lazy: false, tabs: tabs };
	}

	var api = {
		Project: Project,
		Tab: Tab,
		MAX_EAGER_TABS: MAX_EAGER_TABS,
		PACK_LIST_MAX: PACK_LIST_MAX,
		buildPackIndex: buildPackIndex,
		packIsLazy: packIsLazy,
		openPackPlan: openPackPlan,
		ParamSpec: ParamSpec,
		SnapshotHistory: SnapshotHistory,
		isFiniteNumber: isFiniteNumber,
		parseNumber: parseNumber,
		clampTime: clampTime,
		EASING_BEZIER: EASING_BEZIER,
		SNAP_EPSILON: SNAP_EPSILON,
		bezierY: bezierY,
		bezierHandleDeltaY: bezierHandleDeltaY,
		roundTo: roundTo,
		clampBezierHandle: clampBezierHandle,
		normalizeEasing: normalizeEasing,
		easingToBezier: easingToBezier,
		snapEasing: snapEasing,
		easingToJson: easingToJson,
		normalizeParam: normalizeParam,
		DEFAULT_BIND: DEFAULT_BIND,
		BIND_FIELD_KEYS: BIND_FIELD_KEYS,
		BIND_SCALAR_KEYS: BIND_SCALAR_KEYS,
		bindKinds: bindKinds,
		bindKind: bindKind,
		bindNeedsPos: bindNeedsPos,
		bindRequiresObjective: bindRequiresObjective,
		bindKindFields: bindKindFields,
		bindSources: bindSources,
		bindSpecKind: bindSpecKind,
		bindSpecSource: bindSpecSource,
		applyBindDefaults: applyBindDefaults,
		setBindKind: setBindKind,
		setBindSource: setBindSource,
		buildBindSpec: buildBindSpec,
		chainFromDefinition: chainFromDefinition,
		effectById: effectById,
		graphToCanvas: graphToCanvas,
		validNamespace: validNamespace,
		exportZipBody: exportZipBody,
		exportTargetBody: exportTargetBody,
		datapackPath: datapackPath,
		paramGroups: paramGroups,
		applyGroupSpec: applyGroupSpec,
		uniformGroupSpec: uniformGroupSpec,
		MASK_OPS: MASK_OPS,
		MASK_SHAPES: MASK_SHAPES,
		maskShapeDef: maskShapeDef,
		maskShapeDefIn: maskShapeDefIn,
		maskShapeTable: maskShapeTable,
		maskAffordance: maskAffordance,
		maskShapeList: maskShapeList,
		structuralSection: structuralSection,
		positionsAffordance: positionsAffordance,
		regionAffordance: regionAffordance,
		entitySelectorAffordance: entitySelectorAffordance,
		soundAffordance: soundAffordance,
		positionsFieldSpec: positionsFieldSpec,
		regionFieldSpec: regionFieldSpec,
		entitySelectorFieldSpec: entitySelectorFieldSpec,
		soundFieldSpec: soundFieldSpec,
		particleIdsAffordance: particleIdsAffordance,
		patternAffordance: patternAffordance,
		surfaceAffordance: surfaceAffordance,
		anchorAffordance: anchorAffordance,
		windowAffordance: windowAffordance,
		screenImageAffordance: screenImageAffordance,
		particleFieldSpec: particleFieldSpec,
		patternFieldSpec: patternFieldSpec,
		surfaceFieldSpec: surfaceFieldSpec,
		anchorFieldSpec: anchorFieldSpec,
		windowFieldSpec: windowFieldSpec,
		screenImageFieldSpec: screenImageFieldSpec,
		isMaskComposition: isMaskComposition,
		maskLeaves: maskLeaves,
		maskOps: maskOps,
		maskLeafSpace: maskLeafSpace,
		maskCenterDefaults: maskCenterDefaults,
		maskCenterArity: maskCenterArity,
		maskSoftnessDefault: maskSoftnessDefault,
		maskShapeDeferred: maskShapeDeferred,
		maskSlots: maskSlots,
		maskSlotsByLeaf: maskSlotsByLeaf,
		maskParamNames: maskParamNames,
		maskParamDescriptor: maskParamDescriptor,
		makeMaskLeaf: makeMaskLeaf,
		maskFromChain: maskFromChain,
		maskChain: maskChain,
		maskRightCompositionInvalid: maskRightCompositionInvalid,
		maskNodeState: maskNodeState,
		maskFromNodeState: maskFromNodeState,
		maskWriteSlot: maskWriteSlot,
		FIELD_CAP_FALLBACK: FIELD_CAP_FALLBACK,
		fieldSection: fieldSection,
		fieldFnTable: fieldFnTable,
		fieldFnIds: fieldFnIds,
		fieldOpList: fieldOpList,
		fieldOpArity: fieldOpArity,
		fieldCaps: fieldCaps,
		isFieldKind: isFieldKind,
		fieldAffordance: fieldAffordance,
		makeFieldLeaf: makeFieldLeaf,
		fieldStats: fieldStats,
		fieldNeedsDepth: fieldNeedsDepth,
		fieldCapWarnings: fieldCapWarnings
	};

	root.VFXIR = api;
	if (root.VFXEditor) {
		root.VFXEditor.ir = api;
	}
})();

/*
 * VFXweaver visual node editor - property inspector (Task 14).
 *
 * Given the selected tab (`ir.js` `Tab`) and one `VFXParamDescriptor` from GET /schema, this
 * module renders the value control for the param's `kind` plus the form toggles (constant /
 * animate / keyframes / bind / expr, with an optional `multiply`) that write the matching
 * `ParamSpec` into the IR. It is driven purely by the descriptor - there is no per-effect
 * branch anywhere.
 *
 * `render(tab, param, onEdit)` returns a detached element the shell places in its panel; it
 * mutates `tab.params[name]` (and `tab.keys[name]` for the keyframes form) and then calls
 * `onEdit(name, spec, previous)` so the shell can route the edit (undo stack, live sync).
 *
 * Exposed as `window.VFXInspector`; also attached to `window.VFXEditor.inspector` when the
 * shell is present.
 */
(function () {
	"use strict";

	var root = typeof window !== "undefined" ? window
			: (typeof globalThis !== "undefined" ? globalThis : this);

	/** The world/camera bindings offered by the Bind and Multiply forms (docs/guide/datapack/params.md). */
	var BINDINGS = [
		"screen_x", "screen_y", "proximity", "look", "look_at", "distance",
		"look_x", "look_y", "look_z", "player_x", "player_y", "player_z",
		"camera_yaw_delta", "camera_pitch_delta", "health", "hunger", "speed",
		"light_level", "time_of_day", "scoreboard"
	];

	/** Bindings whose parser (`BoundParam.Kind.needsPos()`) requires a world `pos` anchor. */
	var NEEDS_POS = ["screen_x", "screen_y", "proximity", "distance", "look_at"];
	/** A non-positional, non-scoreboard default, so a fresh Bind/Multiply spec is always parseable. */
	var DEFAULT_BIND = "health";
	/** The format's fallback duration when a tab has none (matches `VFXIR.Tab`/the compiler default). */
	var DEFAULT_DURATION = 40;

	function isFiniteNumber(value) {
		return typeof value === "number" && isFinite(value);
	}

	function num(value, fallback) {
		var n = parseFloat(value);
		return isFinite(n) ? n : (fallback == null ? 0 : fallback);
	}

	/** A value rounded to 2 decimals for the live readout (fix H5); pure through `VFXIR.roundTo`. */
	function round2(value) {
		return root.VFXIR && typeof root.VFXIR.roundTo === "function"
				? root.VFXIR.roundTo(value, 2) : Math.round(num(value) * 100) / 100;
	}

	/** A shallow copy of a plain object, so control mutations never alias the caller's spec. */
	function clone(spec) {
		var out = {};
		Object.keys(spec).forEach(function (key) {
			out[key] = spec[key];
		});
		return out;
	}

	/** Create an element; `class`/`text` are special, a `on<event>` function is a listener. */
	function el(tag, attrs, kids) {
		var node = document.createElement(tag);
		if (attrs) {
			Object.keys(attrs).forEach(function (key) {
				var value = attrs[key];
				if (value == null) {
					return;
				}
				if (key === "class") {
					node.className = String(value);
				} else if (key === "text") {
					node.textContent = String(value);
				} else if (key.indexOf("on") === 0 && typeof value === "function") {
					node.addEventListener(key.slice(2), value);
				} else {
					node.setAttribute(key, String(value));
				}
			});
		}
		if (kids) {
			kids.forEach(function (kid) {
				if (kid) {
					node.appendChild(kid);
				}
			});
		}
		return node;
	}

	/**
	 * The slot state of a param (design §15.1), from the IR data alone: `driven` when a value node
	 * is wired in, `track` when it has >= 1 key, `inline` otherwise. The removed `control`
	 * discriminator plays no part.
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the param name
	 * @param {string|null} driven the wired source node id, when the shell supplied one
	 * @returns {string} `"driven"`, `"track"` or `"inline"`
	 */
	function slotState(tab, name, driven) {
		if (driven != null) {
			return "driven";
		}
		var keys = tab && tab.keys ? tab.keys[name] : null;
		return Array.isArray(keys) && keys.length > 0 ? "track" : "inline";
	}

	/** Eased interpolation of a key list at `time`, clamped to the first/last key. */
	function valueAtTime(list, time, fallback) {
		if (!list || !list.length) {
			return fallback;
		}
		if (time <= num(list[0].time)) {
			return list[0].value;
		}
		var last = list[list.length - 1];
		if (time >= num(last.time)) {
			return last.value;
		}
		for (var i = 1; i < list.length; i++) {
			var a = list[i - 1];
			var b = list[i];
			if (time <= num(b.time)) {
				var span = num(b.time) - num(a.time);
				if (span <= 0) {
					return b.value;
				}
				var u = (time - num(a.time)) / span;
				// Match the drawn curve: apply the segment's easing (a name or a cubic bezier).
				var eased = u;
				if (root.VFXIR && typeof root.VFXIR.easingToBezier === "function"
						&& typeof root.VFXIR.bezierY === "function") {
					eased = root.VFXIR.bezierY(root.VFXIR.easingToBezier(a.easing), u);
				}
				return num(a.value) + (num(b.value) - num(a.value)) * eased;
			}
		}
		return last.value;
	}

	/**
	 * The shared finite `min`/`max`/`step` of a vector group, or null when any component is
	 * unbounded or the bounds differ. Drives the collapsed group's slider (item 6).
	 *
	 * @param {Array<Object>} components the group's component descriptors
	 * @returns {Object|null} a synthetic descriptor `{ kind, min, max, step }`, or null
	 */
	function sharedBounds(components) {
		var min;
		var max;
		var step;
		for (var i = 0; i < components.length; i++) {
			var param = components[i];
			if (!isFiniteNumber(param.min) || !isFiniteNumber(param.max)) {
				return null;
			}
			if (i === 0) {
				min = param.min;
				max = param.max;
				step = param.step;
			} else if (param.min !== min || param.max !== max) {
				return null;
			}
		}
		return components.length ? { kind: "FLOAT", min: min, max: max, step: step } : null;
	}

	/* ------------------------------------------------------------------ */
	/* Controls by kind                                                    */
	/* ------------------------------------------------------------------ */

	/**
	 * Render a control for one value, dispatching on the descriptor's `kind`.
	 *
	 * @param {Object} param the param descriptor
	 * @param {*} value the current value
	 * @param {function} onValue called with the new value on every edit
	 * @returns {HTMLElement} the control
	 */
	function scalarFor(param, value, onValue) {
		var kind = param.kind || "FLOAT";
		if (kind === "BOOL") {
			var check = el("input", { type: "checkbox", class: "vfx-check" });
			check.checked = Boolean(value);
			check.addEventListener("change", function () {
				onValue(check.checked ? 1 : 0);
			});
			return check;
		}
		if (kind === "ENUM") {
			var select = el("select", { class: "vfx-select" });
			(param.enumValues || []).forEach(function (option) {
				select.appendChild(el("option", { value: option, text: option }));
			});
			if (value != null) {
				select.value = String(value);
			}
			select.addEventListener("change", function () {
				onValue(select.value);
			});
			return select;
		}
		if (kind === "COLOR_RGB" || kind === "POSITION") {
			return vectorFor(value, onValue);
		}
		return numberFor(param, value, onValue);
	}

	/** A number input, paired with a range slider when the descriptor is bounded. */
	function numberFor(param, value, onValue, disabled) {
		var wrap = el("div", { class: "vfx-scalar" });
		var step = isFiniteNumber(param.step) && param.step > 0 ? param.step
				: (param.kind === "INT" ? 1 : 0.001);
		var input = el("input", { type: "number", class: "vfx-num", value: String(num(value)) });
		if (isFiniteNumber(param.min)) {
			input.min = param.min;
		}
		if (isFiniteNumber(param.max)) {
			input.max = param.max;
		}
		input.step = step;
		if (disabled) {
			input.disabled = true;
		}
		if (isFiniteNumber(param.min) && isFiniteNumber(param.max)) {
			var range = el("input", {
				type: "range",
				class: "vfx-range",
				min: param.min,
				max: param.max,
				step: step,
				value: String(num(value))
			});
			if (disabled) {
				range.disabled = true;
			}
			range.addEventListener("input", function () {
				input.value = range.value;
				onValue(num(range.value, num(value)));
			});
			input.addEventListener("input", function () {
				var v = num(input.value, num(value));
				range.value = String(v);
				onValue(v);
			});
			wrap.appendChild(range);
			wrap.appendChild(input);
		} else {
			input.addEventListener("input", function () {
				onValue(num(input.value, num(value)));
			});
			wrap.appendChild(input);
		}
		return wrap;
	}

	/** Three number fields for a COLOR_RGB / POSITION vector; the value is a 3-array. */
	function vectorFor(value, onValue) {
		var wrap = el("div", { class: "vfx-vec" });
		var current = Array.isArray(value) ? value : [0, 0, 0];
		[0, 1, 2].forEach(function (index) {
			var field = el("input", {
				type: "number",
				class: "vfx-num",
				value: String(num(current[index]))
			});
			field.addEventListener("input", function () {
				var out = [num(current[0]), num(current[1]), num(current[2])];
				out[index] = num(field.value);
				onValue(out);
			});
			wrap.appendChild(field);
		});
		return wrap;
	}

	/** An optional number row: an empty field removes the key from the spec. */
	function optionalRow(label, target, key, onCommit) {
		var input = el("input", {
			type: "number",
			class: "vfx-num",
			value: target[key] == null ? "" : String(target[key])
		});
		input.placeholder = "-";
		input.addEventListener("input", function () {
			if (input.value === "") {
				delete target[key];
			} else {
				target[key] = num(input.value);
			}
			onCommit();
		});
		return el("label", { class: "vfx-field" }, [el("span", { text: label }), input]);
	}

	/** An optional boolean row (invert): checked sets the key, unchecked removes it. */
	function optionalCheck(label, target, key, onCommit) {
		var check = el("input", { type: "checkbox", class: "vfx-check" });
		check.checked = target[key] === true;
		check.addEventListener("change", function () {
			if (check.checked) {
				target[key] = true;
			} else {
				delete target[key];
			}
			onCommit();
		});
		return el("label", { class: "vfx-field" }, [el("span", { text: label }), check]);
	}

	/** A text field row; the value is written verbatim. */
	function textRow(label, target, key, onCommit) {
		var input = el("input", {
			type: "text",
			class: "vfx-text",
			value: target[key] == null ? "" : String(target[key])
		});
		input.addEventListener("input", function () {
			target[key] = input.value;
			onCommit();
		});
		return el("label", { class: "vfx-field" }, [el("span", { text: label }), input]);
	}

	/**
	 * Fill in the fields a binding kind requires so the emitted spec parses:
	 * position-based kinds get a `pos`, `scoreboard` a non-blank `objective`.
	 */
	function applyBindDefaults(target) {
		if (NEEDS_POS.indexOf(target.bind) >= 0 && !Array.isArray(target.pos)) {
			target.pos = [0, 0, 0];
		}
		if (target.bind === "scoreboard" && (target.objective == null || target.objective === "")) {
			target.objective = "objective";
		}
	}

	/**
	 * The binding fields shared by the Bind form and the Multiply form. Mutates {@code target}
	 * (an object carrying `bind` plus optional `pos`/`range`/`scale`/`invert`/`objective`/`holder`)
	 * and commits. An imported binding name not in {@link BINDINGS} is preserved as its own option.
	 *
	 * @param {Object} target the bind spec being edited
	 * @param {function} onCommit persists the change
	 * @param {function} [refresh] re-renders the form (needed when fields appear/disappear)
	 */
	function bindForm(target, onCommit, refresh) {
		var box = el("div", { class: "vfx-bind" });
		var select = el("select", { class: "vfx-select" });
		var names = BINDINGS.slice();
		if (target.bind && names.indexOf(target.bind) < 0) {
			names.push(target.bind);
		}
		names.forEach(function (bind) {
			select.appendChild(el("option", { value: bind, text: bind }));
		});
		if (!target.bind) {
			target.bind = DEFAULT_BIND;
		}
		select.value = target.bind;
		select.addEventListener("change", function () {
			target.bind = select.value;
			applyBindDefaults(target);
			onCommit();
			if (refresh) {
				refresh();
			}
		});
		box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "bind" }), select]));

		var needsPos = NEEDS_POS.indexOf(target.bind) >= 0;
		if (needsPos && !Array.isArray(target.pos)) {
			target.pos = [0, 0, 0];
		}
		var posBox = el("div", { class: "vfx-pos" });
		if (needsPos) {
			posBox.appendChild(el("span", { class: "vfx-pos-label", text: "pos (required)" }));
			posBox.appendChild(vectorFor(target.pos, function (v) {
				target.pos = v;
				onCommit();
			}));
		} else {
			var posToggle = el("input", { type: "checkbox", class: "vfx-check" });
			posToggle.checked = Array.isArray(target.pos);
			posBox.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "pos" }), posToggle]));
			if (Array.isArray(target.pos)) {
				posBox.appendChild(vectorFor(target.pos, function (v) {
					target.pos = v;
					onCommit();
				}));
			}
			posToggle.addEventListener("change", function () {
				if (posToggle.checked) {
					target.pos = [0, 0, 0];
				} else {
					delete target.pos;
				}
				onCommit();
				if (refresh) {
					refresh();
				}
			});
		}
		box.appendChild(posBox);

		if (target.bind === "scoreboard") {
			applyBindDefaults(target);
			box.appendChild(textRow("objective", target, "objective", onCommit));
			box.appendChild(textRow("holder", target, "holder", onCommit));
		}

		box.appendChild(optionalRow("range", target, "range", onCommit));
		box.appendChild(optionalRow("scale", target, "scale", onCommit));
		box.appendChild(optionalCheck("invert", target, "invert", onCommit));
		return box;
	}

	/* ------------------------------------------------------------------ */
	/* Form bodies                                                         */
	/* ------------------------------------------------------------------ */

	/**
	 * Render a param's inspector.
	 *
	 * @param {Object} tab the selected `VFXIR.Tab` (its `params`/`keys` are mutated)
	 * @param {Object} param the `VFXParamDescriptor` to edit
	 * @param {function} [onEdit] called `(name, spec, previous)` after every edit
	 * @param {string|null} [driven] the source value-node id wiring this param, for a read-only view
	 *        (design §15.1, fix 5); null/absent keeps the normal editable form
	 * @returns {HTMLElement} the inspector element
	 */
	function render(tab, param, onEdit, driven) {
		if (!param || !param.name) {
			throw new Error("inspector.render: a param descriptor is required");
		}
		if (!tab.params) {
			tab.params = {};
		}
		if (!tab.keys) {
			tab.keys = {};
		}

		var name = param.name;
		var slot = root.VFXEditor && root.VFXEditor.slot ? root.VFXEditor.slot : null;

		/** The key list backing this slot (empty when inline). */
		function keys() {
			return tab.keys[name] || [];
		}

		/** A live view of the inline spec (`{ value, multiply? }`), seeded from the raw param value. */
		function rawSpec() {
			var raw = tab.params[name];
			if (raw != null && typeof raw === "object") {
				return raw;
			}
			return { value: raw == null ? param.defaultValue : raw };
		}

		/** The playhead tick (0 when the timeline module is absent). */
		function playhead() {
			return root.VFXTimeline && typeof root.VFXTimeline.playhead === "function"
					? num(root.VFXTimeline.playhead()) : 0;
		}

		/** The value shown/edited: the value at the playhead on a track, else the inline value. */
		function currentValue() {
			if (slotState(tab, name, driven) === "track") {
				return valueAtTime(keys(), playhead(),
						param.defaultValue == null ? 0 : param.defaultValue);
			}
			var spec = rawSpec();
			return spec.value != null ? spec.value : param.defaultValue;
		}

		/** Write an inline constant (keeping any `multiply`) and notify the shell. */
		function setInline(value) {
			var spec = rawSpec();
			var previous = tab.params[name];
			var next = { value: value };
			if (spec.multiply) {
				next.multiply = spec.multiply;
			}
			tab.params[name] = next;
			if (typeof onEdit === "function") {
				onEdit(name, next, previous);
			}
		}

		/** Edit the slot: on a track this auto-keys at the playhead, inline writes the constant. */
		function editValue(value) {
			var state = slotState(tab, name, driven);
			if (state === "track" && slot && typeof slot.addKey === "function") {
				// Auto-key without re-rendering the panel, so the field keeps its caret.
				slot.addKey(tab, name, value);
				if (typeof onEdit === "function") {
					onEdit(name, rawSpec(), rawSpec());
				}
			} else if (state === "inline") {
				setInline(value);
			}
		}

		/**
		 * The diamond: append a key at the playhead (design §15.1). It never clears the track, so
		 * every press adds/updates one key and keys accumulate across playhead positions. A track is
		 * cleared from the slot context menu ("Clear track") or by deleting its last key.
		 */
		function animateToggle() {
			if (!slot || slotState(tab, name, driven) === "driven") {
				return;
			}
			slot.animate(tab, name, currentValue());
		}

		function inlineBody() {
			var box = el("div", { class: "vfx-slot vfx-slot-inline" });
			var spec = rawSpec();
			box.appendChild(el("label", { class: "vfx-field" }, [
				el("span", { text: "value" }),
				scalarFor(param, spec.value != null ? spec.value : param.defaultValue, editValue)
			]));
			return box;
		}

		function trackBody() {
			var box = el("div", { class: "vfx-slot vfx-slot-track" });
			// The live value lives IN the field itself (fix H5): rounded to 2 decimals, refreshed at
			// the playhead by `refreshLiveValues`, which skips the field while it is being typed into.
			// A separate readout element used to sit to the right and overflow the panel.
			var field = el("label", { class: "vfx-field vfx-live-field" });
			field.appendChild(el("span", { text: "value @ playhead" }));
			field.appendChild(scalarFor(param, round2(currentValue()), editValue));
			field.__vfxCompute = function () { return round2(currentValue()); };
			box.appendChild(field);
			var list = keys();
			var info = el("div", { class: "vfx-track-info" }, [
				el("span", { class: "vfx-track-count",
					text: list.length + (list.length === 1 ? " key" : " keys") })
			]);
			if (slot && typeof slot.showAsCurve === "function") {
				var materialize = el("button", { type: "button", class: "vfx-slot-btn",
					text: "Show as curve node",
					title: "Materialize this track as a curve value node wired into the slot" });
				materialize.addEventListener("click", function () {
					slot.showAsCurve(tab, name);
				});
				info.appendChild(materialize);
			}
			box.appendChild(info);
			return box;
		}

		/** The read-only view shown when a wire drives this param (design §15.1). */
		function drivenBody() {
			var box = el("div", { class: "vfx-body vfx-driven" });
			box.appendChild(el("div", { class: "vfx-field" }, [
				el("span", { text: "value" }),
				el("span", { class: "vfx-driven-value", text: String(num(currentValue())) })
			]));
			box.appendChild(el("span", { class: "vfx-driven-badge", text: "driven by " + driven }));
			var actions = el("div", { class: "vfx-slot-actions" });
			if (root.VFXEditor && typeof root.VFXEditor.focusNode === "function") {
				var go = el("button", { type: "button", class: "vfx-driven-go", text: "go to node" });
				go.addEventListener("click", function () {
					root.VFXEditor.focusNode(driven);
				});
				actions.appendChild(go);
			}
			if (slot && typeof slot.removeDriver === "function") {
				var remove = el("button", { type: "button", class: "vfx-slot-btn", text: "remove driver" });
				remove.addEventListener("click", function () {
					slot.removeDriver(tab, name);
				});
				actions.appendChild(remove);
			}
			box.appendChild(actions);
			return box;
		}

		function multiplySection() {
			var box = el("div", { class: "vfx-multiply" });
			var current = rawSpec().multiply || null;
			var check = el("input", { type: "checkbox", class: "vfx-check" });
			check.checked = Boolean(current);
			check.addEventListener("change", function () {
				if (slot && typeof slot.setMultiply === "function") {
					slot.setMultiply(tab, name, check.checked);
					return;
				}
				var spec = rawSpec();
				if (check.checked) {
					spec.multiply = { bind: DEFAULT_BIND };
				} else {
					delete spec.multiply;
				}
				tab.params[name] = spec;
				if (typeof onEdit === "function") {
					onEdit(name, spec, spec);
				}
				refresh();
			});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "× multiply" }), check]));
			if (current) {
				// The bind form mutates `current` in place; commit without re-rendering so the
				// focused field keeps its caret.
				box.appendChild(bindForm(current, function () {
					if (typeof onEdit === "function") {
						onEdit(name, rawSpec(), rawSpec());
					}
				}, refresh));
			}
			return box;
		}

		function bodyFor() {
			var box = el("div", { class: "vfx-body" });
			var state = slotState(tab, name, driven);
			if (state === "driven") {
				box.appendChild(drivenBody());
			} else if (state === "track") {
				box.appendChild(trackBody());
			} else {
				box.appendChild(inlineBody());
			}
			box.appendChild(multiplySection());
			return box;
		}

		var header = el("div", { class: "vfx-inspect-head" }, [
			el("span", { class: "vfx-inspect-name", text: name }),
			el("span", { class: "vfx-inspect-kind", text: param.kind || "FLOAT" })
		]);
		if (param.neutral == null) {
			var note = el("span", { class: "vfx-inspect-note", text: "not affected by fade" });
			note.title = "This parameter has no neutral value, so the effect fade weight does not blend it.";
			header.appendChild(note);
		}
		var stateName = slotState(tab, name, driven);
		header.appendChild(el("span", { class: "vfx-state-badge " + stateName, text: stateName }));
		if (rawSpec().multiply) {
			header.appendChild(el("span", { class: "vfx-multiply-badge", text: "×" }));
		}
		if (stateName !== "driven" && param.animatable !== false && slot) {
			var diamond = el("button", {
				type: "button",
				class: "vfx-diamond" + (stateName === "track" ? " active" : ""),
				text: stateName === "track" ? "◆" : "◇",
				title: stateName === "track"
						? "Add a key at the playhead"
						: "Animate: add a key at the playhead"
			});
			diamond.addEventListener("click", function (event) {
				event.preventDefault();
				animateToggle();
			});
			header.appendChild(diamond);
		}

		var content = el("div", { class: "vfx-inspect-content" });
		var rootEl = el("div", { class: "vfx-inspector" }, [header, content]);
		// Inline editing: every field selects its content on focus (design §15.1).
		rootEl.addEventListener("focusin", function (event) {
			var target = event.target;
			if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")
					&& typeof target.select === "function") {
				try {
					target.select();
				} catch (e) {
					// number inputs in some engines reject select(); harmless
				}
			}
		});
		// Right-click a slot (design §15.1): replace with an expr/bind node, remove the driver,
		// key/clear the track, or toggle the multiplier. A right-click inside a text field is left to
		// the browser (the slot menu would hijack copy/paste in an expr textarea or number input).
		rootEl.addEventListener("contextmenu", function (event) {
			if (!slot || !root.VFXEditor || typeof root.VFXEditor.showMenu !== "function") {
				return;
			}
			var target = event.target;
			if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA"
					|| target.tagName === "SELECT")) {
				return;
			}
			event.preventDefault();
			openSlotMenu(event);
		});

		/** Build the slot context menu and hand it to the shell's floating menu. */
		function openSlotMenu(event) {
			var state = slotState(tab, name, driven);
			var items = [];
			if (state !== "driven") {
				items.push({ label: "Animate (key at playhead)",
					action: function () { slot.animate(tab, name, currentValue()); } });
			}
			if (param.bindable !== false) {
				items.push({ label: "Replace with expression", enabled: state !== "driven",
					action: function () { slot.replaceWithExpr(tab, name); } });
				items.push({ label: "Replace with binding", enabled: state !== "driven",
					action: function () { slot.replaceWithBind(tab, name); } });
			}
			if (state === "driven") {
				items.push({ label: "Remove driver",
					action: function () { slot.removeDriver(tab, name); } });
			}
			if (state === "track") {
				items.push({ label: "Clear track", action: function () { slot.clearTrack(tab, name); } });
				items.push({ label: "Show as curve node",
					action: function () { slot.showAsCurve(tab, name); } });
			}
			items.push({
				label: rawSpec().multiply ? "Remove multiplier" : "Add multiplier",
				action: function () { slot.setMultiply(tab, name, !rawSpec().multiply); }
			});
			root.VFXEditor.showMenu(items, event);
		}

		function refresh() {
			content.textContent = "";
			content.appendChild(bodyFor());
		}

		refresh();
		return rootEl;
	}

	/* ------------------------------------------------------------------ */
	/* Vector groups (design §15.9.6 P4, fix #21)                          */
	/* ------------------------------------------------------------------ */

	/** Group rows the user has explicitly disclosed, keyed by group name; survives re-renders. */
	var expandedGroups = {};

	/** The source value node driving a param (its `driven` id), or null when it is free. */
	function drivenSource(tab, name) {
		var edges = tab && Array.isArray(tab.edges) ? tab.edges : [];
		var active = tab && tab.effects && tab.effects.length > 1 ? tab.effect() : null;
		var activeId = active ? active.id : null;
		for (var i = 0; i < edges.length; i++) {
			var edge = edges[i];
			if (!edge || edge.to !== name || edge.input != null || edge.from == null) {
				continue;
			}
			// A chain param wire belongs to one effect node; don't show a slot driven by another
			// node's wire (mirrors app.js `edgeOwnedByActive`).
			if (activeId != null && edge.effect != null && edge.effect !== activeId) {
				continue;
			}
			return edge.from;
		}
		return null;
	}

	/**
	 * Render a vector group (design §15.9.6/P4, fix #21) as ONE collapsed control: the components
	 * share a single value, and editing it broadcasts the same inline spec to every component. The
	 * header context menu applies a bind/expr/multiply to all components at once. A disclosure
	 * toggle reveals the per-component rows, each the normal P3 slot inspector (inline / track /
	 * driven + diamond + right-click replace + multiply), so a per-component override stays
	 * reachable. A group whose components differ opens expanded (the importer's mixed rule).
	 *
	 * @param {Object} tab the selected `VFXIR.Tab`
	 * @param {{group: string, components: Array<Object>}} entry a group row from `VFXIR.paramGroups`
	 * @param {function} [onEdit] called `(name, spec, previous)` after every edit
	 * @returns {HTMLElement} the group inspector element
	 */
	function renderGroup(tab, entry, onEdit) {
		var components = entry.components.slice();
		var names = components.map(function (param) { return param.name; });
		var groupName = entry.group || names[0];
		var first = components[0];
		var slot = root.VFXEditor && root.VFXEditor.slot ? root.VFXEditor.slot : null;

		/** The spec every component shares, or null when the group is mixed. */
		function uniformSpec() {
			return root.VFXIR && typeof root.VFXIR.uniformGroupSpec === "function"
					? root.VFXIR.uniformGroupSpec(tab, components) : null;
		}

		/** The collapsed state: driven when any component is wired, else track, else inline. */
		function groupState() {
			var state = "inline";
			components.forEach(function (param) {
				var one = slotState(tab, param.name, drivenSource(tab, param.name));
				if (one === "driven") {
					state = "driven";
				} else if (one === "track" && state === "inline") {
					state = "track";
				}
			});
			return state;
		}

		/** Broadcast one inline constant to every component (auto-keying a track), keeping multiply. */
		function broadcast(value) {
			names.forEach(function (name) {
				if (!tab.params) {
					tab.params = {};
				}
				var raw = tab.params[name];
				var next = { value: value };
				if (raw && typeof raw === "object" && raw.multiply) {
					next.multiply = raw.multiply;
				}
				if (tab.keys && tab.keys[name] && tab.keys[name].length && slot
						&& typeof slot.addKey === "function") {
					slot.addKey(tab, name, value);
				} else {
					tab.params[name] = next;
				}
				if (typeof onEdit === "function") {
					onEdit(name, next, raw);
				}
			});
		}

		/** Apply one slot operation to every component (design §15.9.6/P4). */
		function applyAll(operation) {
			if (!slot) {
				return;
			}
			names.forEach(function (name) {
				operation(name);
			});
		}

		var expanded = Object.prototype.hasOwnProperty.call(expandedGroups, groupName)
				? expandedGroups[groupName] : !uniformSpec();

		var toggle = el("button", { type: "button", class: "vfx-group-toggle",
			text: expanded ? "\u25BE" : "\u25B8", title: "Show the component rows" });
		var header = el("div", { class: "vfx-inspect-head vfx-group-head" }, [
			toggle,
			el("span", { class: "vfx-inspect-name", text: groupName }),
			el("span", { class: "vfx-inspect-kind", text: first.kind || "FLOAT" }),
			el("span", { class: "vfx-group-count", text: names.length + " components" }),
			el("span", { class: "vfx-state-badge " + groupState(), text: groupState() })
		]);

		var content = el("div", { class: "vfx-inspect-content" });

		function refresh() {
			content.textContent = "";
			var body = el("div", { class: "vfx-body" });
			var spec = uniformSpec();
			var value = spec && spec.value !== undefined ? spec.value : 0;
			var bounds = sharedBounds(components);
			var control;
			if (bounds) {
				// All components share one finite range: reuse the scalar slider+field (item 6).
				control = numberFor(bounds, value, function (v) {
					broadcast(v);
				}, groupState() === "driven");
			} else {
				control = el("input", { type: "number", class: "vfx-num vfx-group-value" });
				control.placeholder = "mixed";
				if (spec && spec.value !== undefined) {
					control.value = String(num(spec.value));
				}
				// A driven group has no shared literal to edit, so the collapsed value input is inert.
				control.disabled = groupState() === "driven";
				control.addEventListener("input", function () {
					broadcast(num(control.value, 0));
				});
			}
			body.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "value" }), control]));

			var anyMultiply = names.some(function (name) {
				return slot && typeof slot.multiply === "function" && slot.multiply(tab, name);
			});
			var check = el("input", { type: "checkbox", class: "vfx-check" });
			check.checked = Boolean(anyMultiply);
			check.addEventListener("change", function () {
				applyAll(function (name) { slot.setMultiply(tab, name, check.checked); });
			});
			body.appendChild(el("div", { class: "vfx-multiply" }, [
				el("label", { class: "vfx-field" }, [el("span", { text: "\u00D7 multiply" }), check])
			]));
			content.appendChild(body);

			if (expanded) {
				components.forEach(function (param) {
					content.appendChild(render(tab, param, onEdit, drivenSource(tab, param.name)));
				});
			}
		}

		toggle.addEventListener("click", function (event) {
			event.preventDefault();
			expanded = !expanded;
			expandedGroups[groupName] = expanded;
			toggle.textContent = expanded ? "\u25BE" : "\u25B8";
			refresh();
		});

		var rootEl = el("div", { class: "vfx-inspector vfx-group" }, [header, content]);
		rootEl.addEventListener("focusin", function (event) {
			var target = event.target;
			if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")
					&& typeof target.select === "function") {
				try {
					target.select();
				} catch (e) {
					// number inputs in some engines reject select(); harmless
				}
			}
		});
		// Right-click the group header applies a driver/multiplier to every component at once.
		header.addEventListener("contextmenu", function (event) {
			if (!slot || !root.VFXEditor || typeof root.VFXEditor.showMenu !== "function") {
				return;
			}
			event.preventDefault();
			var anyMultiply = names.some(function (name) {
				return typeof slot.multiply === "function" && slot.multiply(tab, name);
			});
			root.VFXEditor.showMenu([
				{ label: "Replace with expression",
					action: function () { applyAll(function (name) { slot.replaceWithExpr(tab, name); }); } },
				{ label: "Replace with binding",
					action: function () { applyAll(function (name) { slot.replaceWithBind(tab, name); }); } },
				{ label: anyMultiply ? "Remove multiplier" : "Add multiplier",
					action: function () {
						applyAll(function (name) { slot.setMultiply(tab, name, !anyMultiply); });
					} }
			], event);
		});

		refresh();
		return rootEl;
	}

	/** Game ticks per second, for the duration field's ticks<->seconds toggle (20 tps, §15.3). */
	var TICKS_PER_SECOND = 20;
	/** Preferred duration unit across renders/tabs: "ticks" or "seconds". */
	var durationUnit = "ticks";

	/** The duration as shown in the field: ticks, or seconds at 20 tps. */
	function displayDuration(ticks) {
		var t = num(ticks, 0);
		if (durationUnit === "seconds") {
			return String(Math.round((t / TICKS_PER_SECOND) * 1000) / 1000);
		}
		return String(t);
	}

	/** A labelled checkbox row bound to a tab boolean field. */
	function timingCheck(label, tab, key, onCommit) {
		var check = el("input", { type: "checkbox", class: "vfx-check" });
		check.checked = Boolean(tab[key]);
		check.addEventListener("change", function () {
			tab[key] = check.checked;
			onCommit(key, check.checked);
		});
		return el("label", { class: "vfx-field" }, [el("span", { text: label }), check]);
	}

	/** A labelled whole-number row bound to a tab numeric field. */
	function timingNumber(label, tab, key, min, title, onCommit) {
		var input = el("input", { type: "number", class: "vfx-num", value: String(num(tab[key])) });
		input.min = String(min);
		input.step = "1";
		if (title) {
			input.title = title;
		}
		input.addEventListener("input", function () {
			var value = Math.max(min, Math.round(num(input.value, min)));
			input.value = String(value);
			tab[key] = value;
			onCommit(key, value);
		});
		return el("label", { class: "vfx-field" }, [el("span", { text: label }), input]);
	}

	/**
	 * Render the "Node timing" section (design §15.3, fix H4): `duration` (ticks or seconds at
	 * 20 tps), `loop`, `persistent`, `fade_ticks` (default 0) and `delay`. The values are read from
	 * and written to {@code target}: the selected chain node when the caller passes one, else the
	 * root `tab` (a single-effect tab, or the collection root). An edit calls `onEdit(name, value)`
	 * so the shell can snapshot-undo it and re-play the preview.
	 *
	 * @param {Object} tab the active `VFXIR.Tab` (for an empty tab, and the render guard)
	 * @param {function} [onEdit] called `(name, value)` after every edit
	 * @param {Object} [target] the object whose timing fields are edited (defaults to `tab`)
	 * @returns {HTMLElement} the section
	 */
	function renderTiming(tab, onEdit, target) {
		var box = el("div", { class: "vfx-timing" });
		box.appendChild(el("h2", { text: "Node timing" }));
		if (!tab) {
			return box;
		}
		// Per-node timing (fix H4): edit the selected effect entry when the caller supplies one,
		// otherwise the root/tab. This is what makes a chain node's duration a per-node edit instead
		// of the whole collection's duration.
		var node = target || tab;
		function commit(name, value) {
			if (typeof onEdit === "function") {
				onEdit(name, value);
			}
		}

		var durationRow = el("label", { class: "vfx-field" });
		durationRow.appendChild(el("span", { text: "duration" }));
		var wrap = el("div", { class: "vfx-timing-duration" });
		var durationInput = el("input", {
			type: "number",
			class: "vfx-num",
			value: displayDuration(node.duration),
			title: "Effect length (min 1 tick)"
		});
		durationInput.min = "1";
		durationInput.step = durationUnit === "seconds" ? "0.05" : "1";
		durationInput.addEventListener("input", function () {
			var raw = num(durationInput.value, 1);
			var ticks = durationUnit === "seconds" ? Math.round(raw * TICKS_PER_SECOND) : Math.round(raw);
			node.duration = Math.max(1, ticks);
			commit("duration", node.duration);
		});
		var unitSelect = el("select", { class: "vfx-select vfx-timing-unit", title: "Duration unit" });
		unitSelect.appendChild(el("option", { value: "ticks", text: "ticks" }));
		unitSelect.appendChild(el("option", { value: "seconds", text: "s" }));
		unitSelect.value = durationUnit;
		unitSelect.addEventListener("change", function () {
			durationUnit = unitSelect.value;
			durationInput.value = displayDuration(node.duration);
			durationInput.step = durationUnit === "seconds" ? "0.05" : "1";
		});
		wrap.appendChild(durationInput);
		wrap.appendChild(unitSelect);
		durationRow.appendChild(wrap);
		box.appendChild(durationRow);

		box.appendChild(timingCheck("loop", node, "loop", commit));
		box.appendChild(timingCheck("persistent", node, "persistent", commit));
		box.appendChild(timingNumber("fade_ticks", node, "fade_ticks", 0,
				"Post-life fade in ticks (0 disables it)", commit));
		box.appendChild(timingNumber("delay", node, "delay", 0,
				"Start delay in ticks (for a chain node)", commit));
		return box;
	}

	/**
	 * Recompute the live value of every animated field (`.vfx-live-field`) at the current playhead
	 * and write it into the field's own inputs (fix H5). Pure IR math, no game request; the timeline
	 * calls it on every scrub/play frame so the number tracks the playhead. An input being typed
	 * into (or dragged) keeps its own value, so an exact entry is never overwritten mid-edit.
	 */
	function refreshLiveValues() {
		if (typeof document === "undefined" || !document.querySelectorAll) {
			return;
		}
		var nodes = document.querySelectorAll(".vfx-live-field");
		for (var i = 0; i < nodes.length; i++) {
			var node = nodes[i];
			if (typeof node.__vfxCompute !== "function") {
				continue;
			}
			var shown = String(node.__vfxCompute());
			var inputs = node.querySelectorAll('input[type="number"], input[type="range"]');
			for (var j = 0; j < inputs.length; j++) {
				var input = inputs[j];
				if (document.activeElement === input) {
					continue;
				}
				if (input.value !== shown) {
					input.value = shown;
				}
			}
		}
	}

	var api = {
		render: render,
		renderGroup: renderGroup,
		renderTiming: renderTiming,
		refreshLiveValues: refreshLiveValues,
		slotState: slotState,
		BINDINGS: BINDINGS,
		NEEDS_POS: NEEDS_POS,
		DEFAULT_BIND: DEFAULT_BIND
	};
	root.VFXInspector = api;
	if (root.VFXEditor) {
		root.VFXEditor.inspector = api;
		// If /schema already resolved before this module loaded, fill the panel now instead of
		// waiting for a user action (the shell's renderInspector no-ops while this is absent).
		if (typeof root.VFXEditor.renderInspector === "function") {
			root.VFXEditor.renderInspector();
		}
	}
})();

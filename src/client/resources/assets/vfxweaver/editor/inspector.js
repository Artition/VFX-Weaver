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

	/*
	 * The bind kinds/sources/needsPos are read from the loaded schema (`VFXEditor.schema.bind`,
	 * derived from `BoundParam.Kind`/`SourceKind`) via `VFXIR` — one source of truth, no local copies.
	 */

	/** A non-positional, non-scoreboard default, so a fresh Bind/Multiply spec is always parseable. */
	function DEFAULT_BIND() {
		return root.VFXIR && root.VFXIR.DEFAULT_BIND ? root.VFXIR.DEFAULT_BIND : "health";
	}

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

	/** The bind kind ids the engine accepts, from the schema (single source of truth). */
	function bindNames() {
		return (root.VFXIR && typeof root.VFXIR.bindKinds === "function" && root.VFXIR.bindKinds()) || [];
	}

	/** The bind source ids (`camera`/`player`/`entity`/`point`/`block`), from the schema. */
	function bindSourceNames() {
		return (root.VFXIR && typeof root.VFXIR.bindSources === "function" && root.VFXIR.bindSources()) || [];
	}

	/** The optional field ids a kind reads (`Kind.optionalFields()`), from the schema. */
	function bindFieldsFor(kind) {
		return (root.VFXIR && typeof root.VFXIR.bindKindFields === "function" && root.VFXIR.bindKindFields(kind)) || [];
	}

	/** The kind id of a bind spec (its `derive` when sourced, else `bind`). */
	function bindKindOf(target) {
		return root.VFXIR && typeof root.VFXIR.bindSpecKind === "function"
				? root.VFXIR.bindSpecKind(target) : (target.bind || "health");
	}

	/** The active source id of a bind spec (`""` when it binds a kind directly). */
	function bindSourceOf(target) {
		return root.VFXIR && typeof root.VFXIR.bindSpecSource === "function"
				? root.VFXIR.bindSpecSource(target) : "";
	}

	/** A `<select>` of `values`, current `selected` first appended when not in the list. */
	function selectFor(values, selected, onPick) {
		var select = el("select", { class: "vfx-select" });
		var list = values.slice();
		if (selected && list.indexOf(selected) < 0) {
			list = [selected].concat(list);
		}
		list.forEach(function (value) {
			select.appendChild(el("option", { value: value, text: value === "" ? "(literal pos)" : value }));
		});
		select.value = selected;
		select.addEventListener("change", function () {
			onPick(select.value);
		});
		return select;
	}

	/** An optional 3-vector row (checkbox + `vectorFor`); unchecked removes the key. */
	function optionalVector(label, target, key, onCommit) {
		var wrap = el("div", { class: "vfx-pos" });
		var toggle = el("input", { type: "checkbox", class: "vfx-check" });
		toggle.checked = Array.isArray(target[key]);
		wrap.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: label }), toggle]));
		if (Array.isArray(target[key])) {
			wrap.appendChild(vectorFor(target[key], function (v) {
				target[key] = v;
				onCommit();
			}));
		}
		toggle.addEventListener("change", function () {
			if (toggle.checked) {
				target[key] = [0, 0, 0];
			} else {
				delete target[key];
			}
			onCommit();
		});
		return wrap;
	}

	/**
	 * The binding fields shared by the Bind form and the Multiply form. Mutates {@code target}
	 * (an object carrying `bind` plus optional `pos`/`range`/`scale`/`invert`/`objective`/`holder`)
	 * and commits. The kind/source options and the visible field set come from the schema
	 * (`VFXEditor.schema.bind`), matching the engine's `BoundParam` parser — the canvas bind node
	 * uses the same rules, so the two never drift.
	 *
	 * @param {Object} target the bind spec being edited
	 * @param {function} onCommit persists the change
	 * @param {function} [refresh] re-renders the form (needed when fields appear/disappear)
	 */
	function bindForm(target, onCommit, refresh) {
		var ir = root.VFXIR || {};
		var box = el("div", { class: "vfx-bind" });
		var kind = bindKindOf(target);
		var commitRefresh = function () {
			onCommit();
			if (refresh) {
				refresh();
			}
		};

		box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "bind" }),
			selectFor(bindNames(), kind, function (value) {
				if (ir.setBindKind) {
					ir.setBindKind(target, value);
				} else {
					target.bind = value;
				}
				commitRefresh();
			})]));

		box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "source" }),
			selectFor([""].concat(bindSourceNames()), bindSourceOf(target), function (value) {
				if (ir.setBindSource) {
					ir.setBindSource(target, value);
				} else {
					target.bind = value || kind;
				}
				commitRefresh();
			})]));

		kind = bindKindOf(target);
		var source = bindSourceOf(target);
		var needsPos = (ir.bindNeedsPos ? ir.bindNeedsPos(kind) : false)
				&& (!source || source === "point" || source === "block");
		if (needsPos) {
			if (!Array.isArray(target.pos) && ir.applyBindDefaults) {
				ir.applyBindDefaults(target);
			}
			if (!Array.isArray(target.pos)) {
				target.pos = [0, 0, 0];
			}
			var posBox = el("div", { class: "vfx-pos" });
			posBox.appendChild(el("span", { class: "vfx-pos-label",
				text: source ? "pos (source anchor)" : "pos (required)" }));
			posBox.appendChild(vectorFor(target.pos, function (v) {
				target.pos = v;
				onCommit();
			}));
			box.appendChild(posBox);
		}

		if (source === "entity") {
			box.appendChild(textRow("selector", target, "selector", onCommit));
			box.appendChild(textRow("uuid", target, "uuid", onCommit));
			var pointSelect = selectFor(["feet", "center", "eyes"],
				target.point == null ? "center" : String(target.point), function (value) {
					if (value === "center") {
						delete target.point;
					} else {
						target.point = value;
					}
					onCommit();
				});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "point" }), pointSelect]));
			box.appendChild(optionalVector("offset", target, "offset", onCommit));
		}

		var fields = bindFieldsFor(kind);
		if (ir.bindRequiresObjective && ir.bindRequiresObjective(kind)) {
			if (ir.applyBindDefaults) {
				ir.applyBindDefaults(target);
			}
			box.appendChild(textRow("objective", target, "objective", onCommit));
		}
		["range", "scale", "invert", "yaw", "pitch"].forEach(function (name) {
			if (fields.indexOf(name) < 0) {
				return;
			}
			if (name === "invert") {
				box.appendChild(optionalCheck("invert", target, "invert", onCommit));
			} else {
				box.appendChild(optionalRow(name, target, name, onCommit));
			}
		});
		if (fields.indexOf("holder") >= 0) {
			box.appendChild(textRow("holder", target, "holder", onCommit));
		}
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
	 * @param {string|null} [live] the authored live source kind ("bind"/"expr"), so a live-input badge
	 *        is shown next to the control (design §16.9 P1); null keeps it hidden
	 * @returns {HTMLElement} the inspector element
	 */
	function render(tab, param, onEdit, driven, live) {
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
					spec.multiply = { bind: DEFAULT_BIND() };
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
		if (live === "bind" || live === "expr") {
			header.appendChild(el("span", {
				class: "vfx-live-badge", text: "● live",
				title: "value comes from a live " + live + " source (reads game state)"
			}));
		}
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

	/* ------------------------------------------------------------------ */
	/* Schema-driven parameter groups (sky_pattern and kin)              */
	/* ------------------------------------------------------------------ */

	/** Collapsed parameter-group sections, keyed by `type + "/" + group`; survives re-renders. */
	var paramGroupCollapsed = {};

	/** The display group of a param descriptor (`""` when ungrouped). */
	function paramGroupOf(param) {
		return param && typeof param.group === "string" && param.group ? param.group : "";
	}

	/**
	 * Partition a schema param list into the collapsible sections the inspector renders: one
	 * section per non-empty `group`, in first-seen (schema) order, holding that group's params
	 * in schema order; params without a group share one trailing default section. Pure: no DOM,
	 * no per-type branch — grouping metadata lives in Java (`ParamGroups`).
	 *
	 * @param {Array<Object>} params the `VFXParamDescriptor[]` of one effect type
	 * @returns {Array<{group: string, label: string, params: Array<Object>}>} the sections
	 */
	function paramSections(params) {
		var out = [];
		var byGroup = {};
		(Array.isArray(params) ? params : []).forEach(function (param) {
			if (!param || !param.name) {
				return;
			}
			var group = paramGroupOf(param);
			if (!Object.prototype.hasOwnProperty.call(byGroup, group)) {
				byGroup[group] = { group: group, label: group || "Parameters", params: [] };
				out.push(byGroup[group]);
			}
			byGroup[group].params.push(param);
		});
		return out;
	}

	/**
	 * Render one section's rows, collapsing vector triples through `VFXIR.paramGroups` exactly
	 * as the flat inspector does (a vector triple shares one param group by construction, so a
	 * section never splits one).
	 */
	function sectionRows(tab, list, onEdit, ctx) {
		var box = el("div", { class: "vfx-param-group-body" });
		var rows;
		if (root.VFXIR && typeof root.VFXIR.paramGroups === "function") {
			rows = root.VFXIR.paramGroups(list);
		} else {
			rows = list.map(function (param) {
				return { group: null, name: param.name, components: [param] };
			});
		}
		rows.forEach(function (row) {
			if (row.group) {
				box.appendChild(renderGroup(tab, row, onEdit));
				return;
			}
			var param = row.components[0];
			var driven = ctx && typeof ctx.drivenFor === "function"
					? ctx.drivenFor(param.name) : drivenSource(tab, param.name);
			var live = ctx && typeof ctx.liveFor === "function" ? ctx.liveFor(param.name) : null;
			box.appendChild(render(tab, param, onEdit, driven, live));
		});
		return box;
	}

	/**
	 * Render every param of an effect descriptor in collapsible schema-driven group sections
	 * (one section per `group`, plus a default section for ungrouped params). An effect type
	 * without group metadata renders its rows flat, exactly as the single-param inspector does.
	 * Canvas sockets are untouched (they stay flat) and groups are presentation-only: nothing
	 * here reaches the compiler.
	 *
	 * @param {Object} tab the selected `VFXIR.Tab`
	 * @param {Object} descriptor the effect's schema descriptor
	 * @param {function} [onEdit] called `(name, spec, previous)` after every edit
	 * @param {Object} [ctx] optional `{ drivenFor(name), liveFor(name) }` overrides
	 * @returns {HTMLElement} the grouped inspector element
	 */
	function renderParamGroups(tab, descriptor, onEdit, ctx) {
		var params = descriptor && Array.isArray(descriptor.params) ? descriptor.params : [];
		var sections = paramSections(params);
		var wrap = el("div", { class: "vfx-param-groups" });
		if (sections.length === 1 && !sections[0].group) {
			wrap.appendChild(sectionRows(tab, sections[0].params, onEdit, ctx));
			return wrap;
		}
		var typeKey = tab && tab.type ? tab.type : "";
		sections.forEach(function (section) {
			var key = typeKey + "/" + section.group;
			var collapsed = Boolean(paramGroupCollapsed[key]);
			var toggle = el("button", { type: "button", class: "vfx-param-group-toggle",
				text: collapsed ? "\u25B8" : "\u25BE", title: "Show the params in this group" });
			var header = el("div", { class: "vfx-param-group-head" }, [
				toggle,
				el("span", { class: "vfx-inspect-name", text: section.label }),
				el("span", { class: "vfx-param-group-count",
					text: section.params.length + (section.params.length === 1 ? " param" : " params") })
			]);
			var body = el("div", { class: "vfx-param-group-content" });
			function refresh() {
				body.textContent = "";
				if (!collapsed) {
					body.appendChild(sectionRows(tab, section.params, onEdit, ctx));
				}
			}
			toggle.addEventListener("click", function (event) {
				event.preventDefault();
				collapsed = !collapsed;
				paramGroupCollapsed[key] = collapsed;
				toggle.textContent = collapsed ? "\u25B8" : "\u25BE";
				refresh();
			});
			refresh();
			wrap.appendChild(el("div", { class: "vfx-param-group" }, [header, body]));
		});
		return wrap;
	}

	/* ------------------------------------------------------------------ */
	/* Non-post structural sub-editors (design §13.4)                      */
	/* ------------------------------------------------------------------ */

	/**
	 * Render the schema-driven non-post structural sub-editors (design §13.4): `positions`
	 * (ordered entries, each `[x,y,z]` or an entity-anchored object), `region` (six ints),
	 * `entity_selector` and `sound` (+ optional `sound_pos` and the `volume`/`pitch` params), plus
	 * the tail slice — the `particle`/`shape`/`block`/`item` strings, the `pattern`/`surface`
	 * blocks, the `anchor` string, the window spec (`id`/shared `texture`/`titles`) and the
	 * screen-image `texture`. Each section appears only when the descriptor's `structural`
	 * section advertises the matching flag, so this function holds no per-effect-type branch
	 * (forward-compat). The emitted JSON matches the datapack parser exactly (the parser is
	 * the oracle).
	 *
	 * @param {Object} tab the active `VFXIR.Tab` (its active effect entry is edited)
	 * @param {Object} descriptor the effect's schema descriptor
	 * @param {function(boolean)} onChange called on any edit; `true` when the panel must rebuild
	 *        (add/remove/toggle), `false` for an in-place value edit
	 * @returns {HTMLElement|null} the section, or null when the effect offers no structural option
	 */
	function renderStructural(tab, descriptor, onChange) {
		var vfxir = root.VFXIR;
		if (!vfxir) {
			return null;
		}
		var effect = tab && typeof tab.effect === "function" ? tab.effect() : null;
		if (!effect) {
			return null;
		}
		var showPositions = vfxir.positionsAffordance(descriptor);
		var showRegion = vfxir.regionAffordance(descriptor);
		var showEntity = vfxir.entitySelectorAffordance(descriptor);
		var showSound = vfxir.soundAffordance(descriptor);
		var showParticles = vfxir.particleIdsAffordance(descriptor);
		var showPattern = vfxir.patternAffordance(descriptor);
		var showSurface = vfxir.surfaceAffordance(descriptor);
		var showAnchor = vfxir.anchorAffordance(descriptor);
		var showWindow = vfxir.windowAffordance(descriptor);
		var showImage = vfxir.screenImageAffordance(descriptor);
		if (!showPositions && !showRegion && !showEntity && !showSound && !showParticles
				&& !showPattern && !showSurface && !showAnchor && !showWindow && !showImage) {
			return null;
		}

		function commit(structural) {
			if (typeof onChange === "function") {
				onChange(Boolean(structural));
			}
		}

		function section(title) {
			var box = el("div", { class: "vfx-struct-section" });
			box.appendChild(el("h2", { text: title }));
			return box;
		}

		/** A text row writing a string IR field verbatim. */
		function textRow(label, target, key, placeholder) {
			var input = el("input", { type: "text", class: "vfx-text",
				value: target[key] == null ? "" : String(target[key]) });
			if (placeholder) {
				input.placeholder = placeholder;
			}
			input.addEventListener("input", function () {
				target[key] = input.value;
				commit(false);
			});
			return el("label", { class: "vfx-field" }, [el("span", { text: label }), input]);
		}

		/** A select row writing one of `values` into an object key (with a fallback shown first). */
		function selectRow(label, target, key, values, fallback) {
			return el("label", { class: "vfx-field" }, [el("span", { text: label }),
				selectFor(values, target[key] == null ? fallback : String(target[key]), function (value) {
					target[key] = value;
					commit(false);
				})]);
		}

		/** The positions sub-editor (design §13.4): an ordered list of `[x,y,z]`/entity entries. */
		function positionsSection(descriptor2) {
			var spec = vfxir.positionsFieldSpec(descriptor2);
			var box = section("Positions");
			var rows = el("div", { class: "vfx-struct-rows" });

			function list() {
				if (!Array.isArray(effect.positions)) {
					effect.positions = [];
				}
				return effect.positions;
			}

			function entryRow(index) {
				var entries = list();
				var entry = entries[index];
				var isObject = entry != null && typeof entry === "object" && !Array.isArray(entry);
				var row = el("div", { class: "vfx-struct-entry" });
				row.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "kind" }),
					selectFor(["point", "entity"], isObject ? "entity" : "point", function (value) {
						if (value === "entity") {
							list()[index] = { entity: "@e" };
						} else {
							list()[index] = [0, 0, 0];
						}
						commit(true);
					})]));
				if (!isObject) {
					if (!Array.isArray(entry)) {
						entry = [0, 0, 0];
						entries[index] = entry;
					}
					row.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "pos" }),
						vectorFor(entry, function (value) {
							list()[index] = value;
							commit(false);
						})]));
				} else {
					row.appendChild(textRow("entity", entry, "entity", "@e"));
					row.appendChild(selectRow("point", entry, "point",
							spec.pointValues || ["feet", "center", "eyes"], "feet"));
					row.appendChild(selectRow("dir", entry, "dir",
							spec.dirValues || ["none", "look"], "none"));
					row.appendChild(optionalVector("offset", entry, "offset", function () { commit(false); }));
					row.appendChild(optionalRow("distance", entry, "distance", function () { commit(false); }));
				}
				var remove = el("button", { type: "button", class: "vfx-layer-btn", text: "\u00D7",
					title: "Remove position" });
				remove.addEventListener("click", function () {
					list().splice(index, 1);
					commit(true);
				});
				row.appendChild(remove);
				return row;
			}

			function rebuild() {
				rows.textContent = "";
				list().forEach(function (_entry, index) {
					rows.appendChild(entryRow(index));
				});
			}

			var add = el("button", { type: "button", class: "vfx-type", text: "+ Position" });
			add.addEventListener("click", function () {
				list().push([0, 0, 0]);
				commit(true);
			});
			box.appendChild(rows);
			box.appendChild(add);
			rebuild();
			return box;
		}

		/** The region sub-editor (design §13.4): six ints expanding into block positions. */
		function regionSection(descriptor2) {
			var arity = (vfxir.regionFieldSpec(descriptor2).arity) || 6;
			var box = section("Region");
			var enabled = Array.isArray(effect.region) && effect.region.length === arity;
			var check = el("input", { type: "checkbox", class: "vfx-check" });
			check.checked = enabled;
			var fields = el("div", { class: "vfx-vec vfx-struct-region" });
			check.addEventListener("change", function () {
				// Disable writes an empty list (not `undefined`), so the compiler drops an imported
				// region instead of letting the pass-through clone re-emit it.
				effect.region = check.checked ? new Array(arity).fill(0) : [];
				commit(true);
			});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "enabled" }), check]));
			if (enabled) {
				effect.region.forEach(function (value, index) {
					var input = el("input", { type: "number", class: "vfx-num", value: String(num(value)) });
					input.addEventListener("input", function () {
						effect.region[index] = num(input.value);
						commit(false);
					});
					fields.appendChild(input);
				});
			}
			box.appendChild(fields);
			return box;
		}

		/** The entity-selector sub-editor (design §13.4). */
		function entitySection() {
			var box = section("Entity selector");
			box.appendChild(textRow("entity_selector", effect, "entitySelector",
					"@e[type=minecraft:zombie,distance=..10]"));
			return box;
		}

		/** The sound sub-editor (design §13.4): id, optional `sound_pos`, `volume`/`pitch` params. */
		function soundSection() {
			var box = section("Sound");
			box.appendChild(textRow("sound", effect, "sound", "minecraft:entity.generic.explode"));
			box.appendChild(optionalVector("sound_pos", effect, "soundPos", function () { commit(false); }));
			["volume", "pitch"].forEach(function (name) {
				var current = soundParam(name, 1);
				var input = el("input", { type: "number", class: "vfx-num", step: "0.01",
					value: String(current) });
				input.addEventListener("input", function () {
					setSoundParam(name, num(input.value, 1));
					commit(false);
				});
				box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: name }), input]));
			});
			return box;
		}

		/** The current `volume`/`pitch` value (a `{value}` spec, a bare number, or the fallback). */
		function soundParam(name, fallback) {
			var raw = effect.params ? effect.params[name] : undefined;
			if (raw != null && typeof raw === "object") {
				return isFiniteNumber(raw.value) ? raw.value : fallback;
			}
			return isFiniteNumber(raw) ? raw : fallback;
		}

		/** Write a `volume`/`pitch` constant into the effect's params (keeping any multiply). */
		function setSoundParam(name, value) {
			if (!effect.params) {
				effect.params = {};
			}
			var raw = effect.params[name];
			var next = { value: value };
			if (raw && typeof raw === "object" && raw.multiply) {
				next.multiply = raw.multiply;
			}
			effect.params[name] = next;
		}

		/** The particle-id strings (design §13.4): four optional top-level id strings. */
		function particleSection(spec) {
			var box = section("Particle ids");
			(spec.fields || ["particle", "shape", "block", "item"]).forEach(function (name) {
				box.appendChild(textRow(name, effect, name,
						name === "particle" ? "minecraft:flame" : ""));
			});
			return box;
		}

		/** The pattern object, created on first edit so an untouched effect emits nothing. */
		function patternObj() {
			if (!effect.pattern || typeof effect.pattern !== "object") {
				effect.pattern = {};
			}
			return effect.pattern;
		}

		/** A number row writing into the pattern object (default shown as the placeholder). */
		function patternNumRow(target, name, fallback) {
			var input = el("input", { type: "number", class: "vfx-num",
				value: target[name] == null ? "" : String(target[name]) });
			if (fallback != null) {
				input.placeholder = String(fallback);
			}
			input.addEventListener("input", function () {
				if (input.value === "") {
					delete target[name];
				} else {
					target[name] = num(input.value);
				}
				commit(false);
			});
			return el("label", { class: "vfx-field" }, [el("span", { text: name }), input]);
		}

		/** The pattern sub-editor (design §13.4): figure/fill, shape numbers, center, texture. */
		function patternSection(spec) {
			var box = section("Pattern");
			var target = patternObj();
			box.appendChild(selectRow("figure", target, "figure",
					spec.figureValues || ["circle"], "circle"));
			box.appendChild(selectRow("fill", target, "fill",
					spec.fillValues || ["solid"], "solid"));
			(target.shapeParams || spec.shapeParams || []).forEach(function (pair) {
				box.appendChild(patternNumRow(target, pair[0], pair[1]));
			});
			box.appendChild(optionalVector("center", target, "center", function () { commit(false); }));
			var tex = target.texture;
			var toggle = el("input", { type: "checkbox", class: "vfx-check" });
			toggle.checked = tex != null && typeof tex === "object";
			toggle.addEventListener("change", function () {
				if (toggle.checked) {
					target.texture = { id: "minecraft:block/stone" };
				} else {
					delete target.texture;
				}
				commit(true);
			});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "texture" }), toggle]));
			if (toggle.checked) {
				var t = target.texture;
				box.appendChild(textRow("id", t, "id", "minecraft:block/stone"));
				var sourceSelect = el("select", { class: "vfx-select" });
				[""].concat(spec.textureSources || []).forEach(function (value) {
					sourceSelect.appendChild(el("option", { value: value,
						text: value === "" ? "(infer from id)" : value }));
				});
				sourceSelect.value = t.source == null ? "" : String(t.source);
				sourceSelect.addEventListener("change", function () {
					if (sourceSelect.value === "") {
						delete t.source;
					} else {
						t.source = sourceSelect.value;
					}
					commit(false);
				});
				box.appendChild(el("label", { class: "vfx-field" },
						[el("span", { text: "source" }), sourceSelect]));
				box.appendChild(textRow("atlas", t, "atlas", ""));
				box.appendChild(selectRow("channel", t, "channel",
						spec.textureChannels || ["alpha"], "alpha"));
				["sheet_cols", "sheet_rows"].forEach(function (name, index) {
					var sheet = Array.isArray(t.sheet) ? t.sheet : [1, 1];
					var input = el("input", { type: "number", class: "vfx-num",
						min: 1, max: spec.sheetDim || 16, value: String(sheet[index]) });
					input.addEventListener("input", function () {
						var next = Array.isArray(t.sheet) ? t.sheet.slice() : [1, 1];
						next[index] = Math.round(num(input.value, 1));
						t.sheet = next;
						commit(false);
					});
					box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: name }), input]));
				});
				box.appendChild(selectRow("aspect", t, "aspect",
						spec.textureAspects || ["preserve"], "preserve"));
			}
			return box;
		}

		/** The surface sub-editor (design §13.4): faces, band, soft edge, stitch. */
		function surfaceSection(spec) {
			var box = section("Surface");
			var target = effect.surface;
			if (!target || typeof target !== "object") {
				target = effect.surface = {};
			}
			var faces = el("div", { class: "vfx-struct-rows" });
			(spec.faceTokens || []).forEach(function (token) {
				var list = Array.isArray(target.faces) ? target.faces : [];
				var check = el("input", { type: "checkbox", class: "vfx-check" });
				check.checked = list.indexOf(token) >= 0;
				check.addEventListener("change", function () {
					var next = Array.isArray(target.faces) ? target.faces.slice() : [];
					if (check.checked) {
						if (next.indexOf(token) < 0) {
							next.push(token);
						}
					} else {
						next = next.filter(function (t) { return t !== token; });
					}
					// An empty list is a parse error; omit the key (floor-only) instead.
					if (!next.length) {
						delete target.faces;
					} else {
						target.faces = next;
					}
					commit(false);
				});
				faces.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: token }), check]));
			});
			box.appendChild(faces);
			["min", "max", "band_softness"].forEach(function (name) {
				box.appendChild(patternNumRow(target, name, name === "band_softness" ? 0 : null));
			});
			var stitch = el("input", { type: "checkbox", class: "vfx-check" });
			stitch.checked = target.stitch === true;
			stitch.addEventListener("change", function () {
				if (stitch.checked) {
					target.stitch = true;
				} else {
					delete target.stitch;
				}
				commit(false);
			});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "stitch" }), stitch]));
			return box;
		}

		/** The anchor sub-editor (design §13.4): the sky_pattern-only celestial anchor. */
		function anchorSection(spec) {
			var box = section("Anchor");
			box.appendChild(selectRow("anchor", effect, "anchor", spec.values || ["dome"], "dome"));
			return box;
		}

		/** The window sub-editor (design §13.4): required id, shared texture, titles list. */
		function windowSection(spec) {
			var box = section("Window");
			box.appendChild(textRow("id", effect, "windowId", "overlay"));
			box.appendChild(textRow("texture", effect, "texture", "minecraft:textures/gui/icon.png"));
			var rows = el("div", { class: "vfx-struct-rows" });
			function list() {
				if (!Array.isArray(effect.windowTitles)) {
					effect.windowTitles = [];
				}
				return effect.windowTitles;
			}
			function rebuild() {
				rows.textContent = "";
				list().forEach(function (title, index) {
					var input = el("input", { type: "text", class: "vfx-text", value: String(title) });
					input.addEventListener("input", function () {
						list()[index] = input.value;
						commit(false);
					});
					var remove = el("button", { type: "button", class: "vfx-layer-btn", text: "\u00D7",
						title: "Remove title" });
					remove.addEventListener("click", function () {
						list().splice(index, 1);
						commit(true);
					});
					rows.appendChild(el("div", { class: "vfx-struct-entry" }, [input, remove]));
				});
			}
			var add = el("button", { type: "button", class: "vfx-type", text: "+ Title" });
			add.addEventListener("click", function () {
				if (list().length < (spec.maxTitles || 64)) {
					list().push("");
					commit(true);
				}
			});
			box.appendChild(rows);
			box.appendChild(add);
			rebuild();
			return box;
		}

		/** The screen-image sub-editor (design §13.4): the required picture texture. */
		function screenImageSection() {
			var box = section("Screen image");
			box.appendChild(textRow("texture", effect, "texture", "minecraft:textures/gui/icon.png"));
			return box;
		}

		var wrap = el("div", { class: "vfx-struct" });
		if (showPositions) {
			wrap.appendChild(positionsSection(descriptor));
		}
		if (showRegion) {
			wrap.appendChild(regionSection(descriptor));
		}
		if (showEntity) {
			wrap.appendChild(entitySection());
		}
		if (showSound) {
			wrap.appendChild(soundSection());
		}
		if (showParticles) {
			wrap.appendChild(particleSection(vfxir.particleFieldSpec(descriptor)));
		}
		if (showPattern) {
			wrap.appendChild(patternSection(vfxir.patternFieldSpec(descriptor)));
		}
		if (showSurface) {
			wrap.appendChild(surfaceSection(vfxir.surfaceFieldSpec(descriptor)));
		}
		if (showAnchor) {
			wrap.appendChild(anchorSection(vfxir.anchorFieldSpec(descriptor)));
		}
		if (showWindow) {
			wrap.appendChild(windowSection(vfxir.windowFieldSpec(descriptor)));
		}
		if (showImage) {
			wrap.appendChild(screenImageSection());
		}
		return wrap;
	}

	/* ------------------------------------------------------------------ */
	/* Mask sub-editor (design §13.1)                                      */
	/* ------------------------------------------------------------------ */

	/** The display label of a mask slot (its field name), or the slot name for a centre axis. */
	function maskSlotLabel(slot) {
		if (slot.kind === "center") {
			return "center_" + ["x", "y", "z"][slot.fieldIndex];
		}
		if (slot.kind === "param") {
			return slot.paramName;
		}
		if (slot.kind === "soft") {
			return "softness";
		}
		if (slot.kind === "stroke") {
			return "stroke_width";
		}
		return slot.kind;
	}

	/**
	 * Render the Mask sub-editor for a post effect (design §13.1). Non-post effects get nothing —
	 * {@link root.VFXIR.maskAffordance} gates it on the schema's `structural.supportsMask`. The
	 * editor authors the common shapes only: `polygon`/`block` and a custom id render as a read-only
	 * "not yet" row so an imported mask still round-trips; the parser rejects a composition as the
	 * right operand, and the chain builder only ever nests on the left.
	 *
	 * <p>The section navigates: the canvas Mask/MaskLeaf nodes are the one editing surface (leaf
	 * numbers live there, one field at a time a socket XOR a widget per design §15.1), so this
	 * panel lists the leaves with "go to leaf" jumps, the per-join op combos and leaf add/remove.
	 *
	 * @param {Object} tab the active tab
	 * @param {Object} descriptor the effect's schema descriptor
	 * @param {function(boolean)} onMaskChange called on any edit; `true` when the tree changed shape
	 *        (add/remove/retype) and the panel must rebuild, `false` for an in-place value edit
	 * @returns {HTMLElement|null} the section, or null when the effect offers no mask
	 */
	function renderMask(tab, descriptor, onMaskChange) {
		var vfxir = root.VFXIR;
		if (!vfxir || typeof vfxir.maskAffordance !== "function" || !vfxir.maskAffordance(descriptor)) {
			return null;
		}
		var effect = tab && typeof tab.effect === "function" ? tab.effect() : null;
		if (!effect) {
			return null;
		}
		var wrap = el("div", { class: "vfx-mask" });
		wrap.appendChild(el("h2", { text: "Mask" }));

		if (effect.mask == null) {
			wrap.appendChild(el("p", { class: "vfx-mask-note",
				text: "No mask — the post effect applies to the whole screen." }));
			var add = el("button", { type: "button", class: "vfx-type", text: "+ Add mask" });
			add.addEventListener("click", function () {
				effect.mask = vfxir.makeMaskLeaf("circle");
				if (typeof onMaskChange === "function") {
					onMaskChange(true);
				}
			});
			wrap.appendChild(add);
			return wrap;
		}

		var chain = vfxir.maskChain(effect.mask);
		var leaves = chain.leaves;
		var ops = chain.ops;
		// The per-shape field metadata comes from the schema's additive structural section (design
		// §13.1), not a hardcoded list, so a new VFXMaskShapeKind appears without an editor change.
		var table = typeof vfxir.maskShapeTable === "function" ? vfxir.maskShapeTable(descriptor) : null;

		function rebuild() {
			var next = vfxir.maskFromChain(leaves, ops, chain.invert, chain.space);
			effect.mask = next;
			return next;
		}

		function changed(structural) {
			rebuild();
			if (typeof onMaskChange === "function") {
				onMaskChange(Boolean(structural));
			}
		}

		/** Centre the canvas on a MaskLeaf node, when the editor exposes it. */
		function goToLeaf(leafIndex) {
			if (root.VFXEditor && typeof root.VFXEditor.focusMaskLeaf === "function") {
				root.VFXEditor.focusMaskLeaf(effect.id, leafIndex);
			}
		}

		/** One leaf navigation row: its shape, a jump to its canvas node, and removal. */
		function leafRow(leafIndex) {
			var leaf = leaves[leafIndex];
			var deferred = vfxir.maskShapeDeferred(leaf.shape, table);
			var row = el("div", { class: "vfx-mask-leaf" + (deferred ? " deferred" : "") });
			var head = el("div", { class: "vfx-mask-leaf-head" }, [
				el("span", { class: "vfx-mask-leaf-title",
					text: "leaf " + leafIndex + " · " + String(leaf.shape) })
			]);
			var go = el("button", { type: "button", class: "vfx-layer-btn",
				text: "go to leaf", title: "Centre the canvas on this leaf's node" });
			go.addEventListener("click", function () {
				goToLeaf(leafIndex);
			});
			head.appendChild(go);
			var remove = el("button", { type: "button", class: "vfx-layer-btn", text: "\u00D7", title: "Remove leaf" });
			remove.addEventListener("click", function () {
				leaves.splice(leafIndex, 1);
				if (ops.length > Math.max(0, leaves.length - 1)) {
					ops.length = Math.max(0, leaves.length - 1);
				}
				if (!leaves.length) {
					effect.mask = null;
				}
				changed(true);
			});
			head.appendChild(remove);
			row.appendChild(head);

			if (deferred) {
				row.appendChild(el("p", { class: "vfx-mask-note",
					text: "‘" + leaf.shape + "’ is not authored in the editor yet; it is kept verbatim." }));
			}
			return row;
		}

		// Tree: leaf 0, then one op + leaf per join (the chain is always left-nested).
		leaves.forEach(function (_leaf, index) {
			wrap.appendChild(leafRow(index));
			if (index < leaves.length - 1) {
				var aligned = ops[index] || "union";
				var opRow = el("div", { class: "vfx-mask-op" }, [
					el("span", { text: "op" }),
					selectFor(vfxir.MASK_OPS, aligned, function (value) {
						ops[index] = value;
						changed(true);
					})
				]);
				wrap.appendChild(opRow);
			}
		});

		var actions = el("div", { class: "vfx-mask-actions" });
		var addLeaf = el("button", { type: "button", class: "vfx-type", text: "+ Leaf" });
		addLeaf.addEventListener("click", function () {
			leaves.push(vfxir.makeMaskLeaf("circle"));
			while (ops.length < leaves.length - 1) {
				ops.push("union");
			}
			changed(true);
		});
		actions.appendChild(addLeaf);
		var clear = el("button", { type: "button", class: "vfx-type", text: "Remove mask" });
		clear.addEventListener("click", function () {
			effect.mask = null;
			changed(true);
		});
		actions.appendChild(clear);
		wrap.appendChild(actions);

		wrap.appendChild(el("p", { class: "vfx-mask-note",
			text: "Leaf numbers live on the canvas MaskLeaf nodes (mask.p<N>.*); wire a value node into a leaf field, or right-click it, to drive one." }));
		return wrap;
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

	/**
	 * Format the active-tab diagnostic gathered by the shell (`VFXEditor.diag`) as a short
	 * multi-line string for pasting from devtools. Pure: `info` is plain data, no DOM read here.
	 *
	 * @param {Object} info `{name, type, effects, edges:[{from,to,input,output,effect}],
	 * canvasNodes, canvasLinks, valueNodes, fieldKeys, instanceIds, hidden, ports, sections, schemaGroups,
	 * anomalies:[string]}`
	 * @returns {string} the multi-line report
	 */
	function formatDiag(info) {
		var data = info && typeof info === "object" ? info : {};
		var lines = [];
		lines.push("tab: " + data.name + " (type: " + data.type
				+ ", effects: " + num(data.effects, 0) + ")");
		var edges = Array.isArray(data.edges) ? data.edges : [];
		lines.push("ir edges: " + edges.length);
		edges.forEach(function (edge) {
			var text = "  " + edge.from + " -> " + edge.to;
			if (edge.input != null) {
				text += " [input=" + edge.input + "]";
			}
			if (edge.output != null) {
				text += " [output=" + edge.output + "]";
			}
			if (edge.effect != null) {
				text += " [effect=" + edge.effect + "]";
			}
			lines.push(text);
		});
		lines.push("canvas: " + num(data.canvasNodes, 0) + " nodes, "
				+ num(data.canvasLinks, 0) + " links; valueNodes: " + num(data.valueNodes, 0)
				+ "; instances: [" + (Array.isArray(data.instanceIds) ? data.instanceIds : []).join(", ") + "]");
		var fields = Array.isArray(data.fieldKeys) ? data.fieldKeys : [];
		lines.push("fields: " + (fields.length ? fields.join(", ") : "(none)"));
		var ports = data.ports && typeof data.ports === "object" ? data.ports : {};
		lines.push("ports: " + ["flow", "number", "mask", "field",
				"macro", "bind", "expr", "time"].map(function (port) {
			return port + "=" + (ports[port] || 0);
		}).join(" "));
		var hidden = Array.isArray(data.hidden) ? data.hidden : [];
		if (hidden.length) {
			lines.push("hidden sockets: " + hidden.map(function (entry) {
				return entry.id + " +" + num(entry.hidden, 0) + " hidden"
						+ (num(entry.animated, 0) > 0 ? " (" + num(entry.animated, 0)
						+ " animated)" : "");
			}).join(", "));
		}
		lines.push("inspector sections: " + num(data.sections, 0)
				+ " (schema groups: " + num(data.schemaGroups, 0) + ")");
		var anomalies = Array.isArray(data.anomalies) ? data.anomalies : [];
		anomalies.forEach(function (text) {
			lines.push("ANOMALY: " + text);
		});
		if (!anomalies.length) {
			lines.push("OK: no anomalies");
		}
		return lines.join("\n");
	}

	var api = {
		render: render,
		renderGroup: renderGroup,
		paramSections: paramSections,
		renderParamGroups: renderParamGroups,
		renderMask: renderMask,
		renderStructural: renderStructural,
		renderTiming: renderTiming,
		refreshLiveValues: refreshLiveValues,
		slotState: slotState,
		formatDiag: formatDiag,
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

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

	/** The base form toggles, in display order. */
	var FORMS = ["constant", "animate", "keyframes", "bind", "expr"];
	var FORM_LABELS = {
		constant: "Constant",
		animate: "Animate",
		keyframes: "Keyframes",
		bind: "Bind",
		expr: "Expr"
	};

	function isFiniteNumber(value) {
		return typeof value === "number" && isFinite(value);
	}

	function num(value, fallback) {
		var n = parseFloat(value);
		return isFinite(n) ? n : (fallback == null ? 0 : fallback);
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
	 * The control a raw spec uses. `control` wins; an imported datapack spec without a `control`
	 * is inferred from its fields, so a round-tripped pack lands on the right form.
	 *
	 * @param {Object|number} spec the raw `Tab.params[name]` value
	 * @returns {string} one of {@link FORMS}
	 */
	function inferControl(spec) {
		if (spec == null || typeof spec === "number") {
			return "constant";
		}
		if (typeof spec !== "object") {
			return "constant";
		}
		if (spec.control) {
			return spec.control;
		}
		if (spec.expr != null) {
			return "expr";
		}
		if (spec.bind != null) {
			return "bind";
		}
		if (spec.keyframes != null) {
			return "keyframes";
		}
		if (spec.start != null || spec.end != null) {
			return "animate";
		}
		return "constant";
	}

	/** A mutable spec copy for the editor, seeded from the tab's current value. */
	function readSpec(tab, param) {
		var raw = tab && tab.params ? tab.params[param.name] : undefined;
		var spec = (raw != null && typeof raw === "object")
				? clone(raw)
				: { value: raw == null ? param.defaultValue : raw };
		spec.control = inferControl(raw);
		return spec;
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
	function numberFor(param, value, onValue) {
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
		if (isFiniteNumber(param.min) && isFiniteNumber(param.max)) {
			var range = el("input", {
				type: "range",
				class: "vfx-range",
				min: param.min,
				max: param.max,
				step: step,
				value: String(num(value))
			});
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

	/**
	 * The binding fields shared by the Bind form and the Multiply form. Mutates {@code target}
	 * (an object carrying `bind` plus optional `pos`/`range`/`scale`/`invert`) and commits.
	 */
	function bindForm(target, onCommit) {
		var box = el("div", { class: "vfx-bind" });
		var select = el("select", { class: "vfx-select" });
		BINDINGS.forEach(function (bind) {
			select.appendChild(el("option", { value: bind, text: bind }));
		});
		select.value = target.bind || "proximity";
		select.addEventListener("change", function () {
			target.bind = select.value;
			onCommit();
		});
		box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "bind" }), select]));

		var posBox = el("div", { class: "vfx-pos" });
		var posToggle = el("input", { type: "checkbox", class: "vfx-check" });
		posToggle.checked = Array.isArray(target.pos);
		var posRow = el("label", { class: "vfx-field" }, [el("span", { text: "pos" }), posToggle]);
		posBox.appendChild(posRow);
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
		});
		box.appendChild(posBox);

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
	 * @returns {HTMLElement} the inspector element
	 */
	function render(tab, param, onEdit) {
		if (!param || !param.name) {
			throw new Error("inspector.render: a param descriptor is required");
		}
		if (!tab.params) {
			tab.params = {};
		}
		if (!tab.keys) {
			tab.keys = {};
		}

		var spec = readSpec(tab, param);
		var lastRaw = tab.params[param.name];

		/** Persist the current spec to the IR and notify the shell. */
		function commit() {
			var previous = lastRaw;
			if (spec.control === "keyframes") {
				if (!tab.keys[param.name]) {
					tab.keys[param.name] = seedKeys();
				}
				tab.params[param.name] = { control: "keyframes" };
			} else {
				tab.params[param.name] = clone(spec);
			}
			lastRaw = tab.params[param.name];
			if (typeof onEdit === "function") {
				onEdit(param.name, clone(spec), previous);
			}
		}

		/** A seed keyframe list for the keyframes form: one key at t=0 with the current value. */
		function seedKeys() {
			var value = spec.value != null ? spec.value : (spec.start != null ? spec.start : param.defaultValue);
			return [{ time: 0, value: value }];
		}

		/** The scalar value carried by the current spec (for seeding another form). */
		function currentValue() {
			if (spec.value != null) {
				return spec.value;
			}
			if (spec.start != null) {
				return spec.start;
			}
			if (spec.control === "keyframes") {
				var keys = tab.keys[param.name];
				if (keys && keys.length) {
					return keys[0].value;
				}
			}
			return param.defaultValue;
		}

		/** Switch the active form, preserving `multiply`, and re-render. */
		function switchTo(control) {
			var multiply = spec.multiply;
			var value = currentValue();
			if (control === "constant") {
				spec = { control: "constant", value: value };
			} else if (control === "animate") {
				spec = { control: "animate", start: value, end: value };
			} else if (control === "keyframes") {
				spec = { control: "keyframes" };
			} else if (control === "bind") {
				spec = { control: "bind", bind: "proximity" };
			} else if (control === "expr") {
				spec = { control: "expr", expr: "" };
			}
			if (multiply) {
				spec.multiply = multiply;
			}
			commit();
			refresh();
		}

		function formTabs() {
			var row = el("div", { class: "vfx-forms" });
			FORMS.forEach(function (control) {
				var button = el("button", {
					type: "button",
					class: "vfx-form" + (control === spec.control ? " active" : ""),
					text: FORM_LABELS[control]
				});
				button.addEventListener("click", function () {
					if (control !== spec.control) {
						switchTo(control);
					}
				});
				row.appendChild(button);
			});
			return row;
		}

		function keyframeList() {
			var list = tab.keys[param.name] || (tab.keys[param.name] = seedKeys());
			var box = el("div", { class: "vfx-keys" });
			list.forEach(function (key, index) {
				var time = el("input", { type: "number", class: "vfx-num vfx-key-time", value: String(num(key.time)) });
				time.title = "time (ticks from start)";
				time.addEventListener("input", function () {
					key.time = num(time.value);
					commit();
				});
				var value = scalarFor(param, key.value, function (v) {
					key.value = v;
					commit();
				});
				var remove = el("button", { type: "button", class: "vfx-key-remove", text: "x", title: "Remove keyframe" });
				remove.addEventListener("click", function () {
					list.splice(index, 1);
					commit();
					refresh();
				});
				box.appendChild(el("div", { class: "vfx-key" }, [time, value, remove]));
			});
			var add = el("button", { type: "button", class: "vfx-add-key", text: "+ keyframe" });
			add.addEventListener("click", function () {
				var last = list.length ? list[list.length - 1] : { time: 0, value: num(param.defaultValue) };
				list.push({ time: num(last.time) + 10, value: num(last.value) });
				commit();
				refresh();
			});
			box.appendChild(add);
			return box;
		}

		function multiplySection() {
			var box = el("div", { class: "vfx-multiply" });
			var check = el("input", { type: "checkbox", class: "vfx-check" });
			check.checked = Boolean(spec.multiply);
			check.addEventListener("change", function () {
				if (check.checked) {
					spec.multiply = { bind: "proximity" };
				} else {
					delete spec.multiply;
				}
				commit();
				refresh();
			});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "x multiply" }), check]));
			if (spec.multiply) {
				box.appendChild(bindForm(spec.multiply, commit));
			}
			return box;
		}

		function bodyFor() {
			var box = el("div", { class: "vfx-body" });
			if (spec.control === "constant") {
				box.appendChild(el("label", { class: "vfx-field" }, [
					el("span", { text: "value" }),
					scalarFor(param, spec.value != null ? spec.value : param.defaultValue, function (v) {
						spec.value = v;
						commit();
					})
				]));
			} else if (spec.control === "animate") {
				box.appendChild(el("label", { class: "vfx-field" }, [
					el("span", { text: "start" }),
					scalarFor(param, spec.start != null ? spec.start : param.defaultValue, function (v) {
						spec.start = v;
						commit();
					})
				]));
				box.appendChild(el("label", { class: "vfx-field" }, [
					el("span", { text: "end" }),
					scalarFor(param, spec.end != null ? spec.end : param.defaultValue, function (v) {
						spec.end = v;
						commit();
					})
				]));
			} else if (spec.control === "keyframes") {
				box.appendChild(keyframeList());
			} else if (spec.control === "bind") {
				box.appendChild(bindForm(spec, commit));
			} else if (spec.control === "expr") {
				var area = el("textarea", { class: "vfx-expr", rows: "4" });
				area.value = spec.expr == null ? "" : String(spec.expr);
				area.addEventListener("input", function () {
					spec.expr = area.value;
					commit();
				});
				box.appendChild(el("label", { class: "vfx-field vfx-field-block" }, [
					el("span", { text: "expr" }),
					area
				]));
			}
			box.appendChild(multiplySection());
			return box;
		}

		var header = el("div", { class: "vfx-inspect-head" }, [
			el("span", { class: "vfx-inspect-name", text: param.name }),
			el("span", { class: "vfx-inspect-kind", text: param.kind || "FLOAT" })
		]);
		if (param.neutral == null) {
			var note = el("span", { class: "vfx-inspect-note", text: "not affected by fade" });
			note.title = "This parameter has no neutral value, so the effect fade weight does not blend it.";
			header.appendChild(note);
		}

		var content = el("div", { class: "vfx-inspect-content" });
		var rootEl = el("div", { class: "vfx-inspector" }, [header, content]);

		function refresh() {
			content.textContent = "";
			content.appendChild(formTabs());
			content.appendChild(bodyFor());
		}

		refresh();
		return rootEl;
	}

	var api = { render: render, inferControl: inferControl, BINDINGS: BINDINGS };
	root.VFXInspector = api;
	if (root.VFXEditor) {
		root.VFXEditor.inspector = api;
	}
})();

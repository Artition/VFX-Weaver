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

		var spec = readSpec(tab, param);
		var lastRaw = tab.params[param.name];

		/** Persist the current spec to the IR and notify the shell. */
		function commit() {
			var previous = lastRaw;
			if (spec.control === "keyframes") {
				if (!tab.keys[param.name]) {
					tab.keys[param.name] = seedKeys();
				}
				// Carry any bound `multiply` on the keyframed param, or a re-export would drop it.
				var keySpec = { control: "keyframes" };
				if (spec.multiply) {
					keySpec.multiply = spec.multiply;
				}
				tab.params[param.name] = keySpec;
			} else {
				tab.params[param.name] = clone(spec);
			}
			lastRaw = tab.params[param.name];
			if (typeof onEdit === "function") {
				onEdit(param.name, clone(spec), previous);
			}
		}

		/**
		 * A seed keyframe list for the keyframes form. The game's `AnimatedValue` requires at least
		 * two keys, so seed `t=0` and `t=duration` around the current value (duration falls back to
		 * the format's default when the tab has none).
		 */
		function seedKeys() {
			var value = spec.value != null ? spec.value : (spec.start != null ? spec.start : param.defaultValue);
			var duration = isFiniteNumber(tab.duration) && tab.duration > 0 ? tab.duration : DEFAULT_DURATION;
			return [{ time: 0, value: value }, { time: duration, value: value }];
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
				spec = { control: "bind", bind: DEFAULT_BIND };
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
				// A read-once param (e.g. `frames`) can be neither animated nor bound; Constant stays.
				if ((control === "animate" || control === "keyframes") && param.animatable === false) {
					return;
				}
				if (control === "bind" && param.bindable === false) {
					return;
				}
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
				// The game's AnimatedValue requires at least two keys, so the last two may never be dropped.
				if (list.length <= 2) {
					remove.disabled = true;
					remove.title = "A keyframed param needs at least two keys";
				}
				remove.addEventListener("click", function () {
					if (list.length <= 2) {
						return;
					}
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
					spec.multiply = { bind: DEFAULT_BIND };
				} else {
					delete spec.multiply;
				}
				commit();
				refresh();
			});
			box.appendChild(el("label", { class: "vfx-field" }, [el("span", { text: "x multiply" }), check]));
			if (spec.multiply) {
				box.appendChild(bindForm(spec.multiply, commit, refresh));
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
				box.appendChild(bindForm(spec, commit, refresh));
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
		// Inline editing: every field selects its content on focus (design §15.1, fix 5).
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

		/** The read-only view shown when a wire drives this param (fix 5). */
		function drivenBody() {
			var box = el("div", { class: "vfx-body vfx-driven" });
			var badge = el("span", { class: "vfx-driven-badge", text: "driven by " + driven });
			box.appendChild(el("div", { class: "vfx-field" }, [
				el("span", { text: "value" }),
				el("span", { class: "vfx-driven-value", text: String(num(currentValue())) })
			]));
			box.appendChild(badge);
			if (root.VFXEditor && typeof root.VFXEditor.focusNode === "function") {
				var go = el("button", { type: "button", class: "vfx-driven-go", text: "go to node" });
				go.addEventListener("click", function () {
					root.VFXEditor.focusNode(driven);
				});
				box.appendChild(go);
			}
			return box;
		}

		function refresh() {
			content.textContent = "";
			if (driven) {
				content.appendChild(drivenBody());
				return;
			}
			content.appendChild(formTabs());
			content.appendChild(bodyFor());
		}

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
	 * Render the "Node timing" section for one tab (design §15.3): `duration` (ticks or seconds at
	 * 20 tps), `loop`, `persistent`, `fade_ticks` (default 0) and `delay` (reserved for P6 chains).
	 * Every value lives on the tab; an edit calls `onEdit(name, value)` so the shell can snapshot-undo
	 * it and re-play the preview.
	 *
	 * @param {Object} tab the active `VFXIR.Tab`
	 * @param {function} [onEdit] called `(name, value)` after every edit
	 * @returns {HTMLElement} the section
	 */
	function renderTiming(tab, onEdit) {
		var box = el("div", { class: "vfx-timing" });
		box.appendChild(el("h2", { text: "Node timing" }));
		if (!tab) {
			return box;
		}
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
			value: displayDuration(tab.duration),
			title: "Effect length (min 1 tick)"
		});
		durationInput.min = "1";
		durationInput.step = durationUnit === "seconds" ? "0.05" : "1";
		durationInput.addEventListener("input", function () {
			var raw = num(durationInput.value, 1);
			var ticks = durationUnit === "seconds" ? Math.round(raw * TICKS_PER_SECOND) : Math.round(raw);
			tab.duration = Math.max(1, ticks);
			commit("duration", tab.duration);
		});
		var unitSelect = el("select", { class: "vfx-select vfx-timing-unit", title: "Duration unit" });
		unitSelect.appendChild(el("option", { value: "ticks", text: "ticks" }));
		unitSelect.appendChild(el("option", { value: "seconds", text: "s" }));
		unitSelect.value = durationUnit;
		unitSelect.addEventListener("change", function () {
			durationUnit = unitSelect.value;
			durationInput.value = displayDuration(tab.duration);
			durationInput.step = durationUnit === "seconds" ? "0.05" : "1";
		});
		wrap.appendChild(durationInput);
		wrap.appendChild(unitSelect);
		durationRow.appendChild(wrap);
		box.appendChild(durationRow);

		box.appendChild(timingCheck("loop", tab, "loop", commit));
		box.appendChild(timingCheck("persistent", tab, "persistent", commit));
		box.appendChild(timingNumber("fade_ticks", tab, "fade_ticks", 0,
				"Post-life fade in ticks (0 disables it)", commit));
		box.appendChild(timingNumber("delay", tab, "delay", 0,
				"Start delay in ticks (reserved for chains)", commit));
		return box;
	}

	var api = {
		render: render,
		renderTiming: renderTiming,
		inferControl: inferControl,
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

/*
 * VFXweaver visual node editor — reusable Bézier curve editor (design §15.4).
 *
 * A data-driven component: `curveView(container, keys, opts)` renders a track's keys through each
 * segment's cubic-bezier with draggable tangent handles, draggable keys (time snaps to ticks),
 * a value axis and the header operations. It owns its selection; every mutation edits `keys` in
 * place and calls `opts.onChange(keys)` (live) / `opts.onCommit(keys)` (one drag = one commit).
 *
 * It is the single curve editor used by BOTH the timeline (`timeline.js` wraps it for a track) and
 * the graph curve node (hosted in a modal by `app.js`). All curve maths comes from `window.VFXIR`
 * (`easingToBezier`, `bezierY`, `bezierHandleDeltaY`, `clampBezierHandle`, `snapEasing`,
 * `normalizeEasing`), so the drawn curve matches the game's `EasingFunction`.
 *
 * `keys` are in the IR easing form: `[{time,value,easing}]` where `easing` is a name string or a
 * bare `[x1,y1,x2,y2]`. The datapack conversion (`{cubicBezier}`) happens at the compiler boundary.
 *
 * Exposed as `window.VFXCurveView`.
 */
(function () {
	"use strict";

	var root = typeof window !== "undefined" ? window
			: (typeof globalThis !== "undefined" ? globalThis : this);

	var SVG_NS = "http://www.w3.org/2000/svg";
	/** Curve-view geometry, shared with `.vfx-tl-curve*` in editor.css. */
	var HEIGHT = 240;
	var PAD_TOP = 18;
	var PAD_BOTTOM = 24;
	var SAMPLES = 48;
	/** The left gutter every time-based row starts after (must match `.vfx-tl-label`). */
	var LABEL_COLUMN = 120;
	var DEFAULT_TOTAL = 40;

	function num(value, fallback) {
		var n = Number(value);
		if (isFinite(n)) {
			return n;
		}
		return fallback == null ? 0 : fallback;
	}

	function isFiniteNumber(value) {
		return typeof value === "number" && isFinite(value);
	}

	/** A number for an axis label: integers plain, fractions to 3 decimals. */
	function formatValue(value) {
		var n = Number(value);
		if (!isFinite(n)) {
			return "";
		}
		if (Math.abs(n - Math.round(n)) < 1e-6) {
			return String(Math.round(n));
		}
		return String(Math.round(n * 1000) / 1000);
	}

	/** The value axis with 8% headroom for overshoot. */
	function curveAxis(range) {
		var lo = num(range && range.lo, 0);
		var hi = num(range && range.hi, 1);
		var span = (hi - lo) || 1;
		return { lo: lo - span * 0.08, hi: hi + span * 0.08 };
	}

	function round3(value) {
		return Math.round(num(value) * 1000) / 1000;
	}

	function clampTime(ticks, total) {
		var t = num(ticks);
		return Math.max(0, Math.min(num(total, DEFAULT_TOTAL), t));
	}

	/** A DOM element with attributes (`el`) / an SVG element in the SVG namespace (`svgEl`). */
	function el(tag, attrs, kids) {
		var node = document.createElement(tag);
		if (attrs) {
			Object.keys(attrs).forEach(function (key) {
				if (key === "text") {
					node.textContent = attrs[key];
				} else if (attrs[key] != null) {
					node.setAttribute(key, String(attrs[key]));
				}
			});
		}
		(kids || []).forEach(function (kid) {
			node.appendChild(kid);
		});
		return node;
	}

	function svgEl(tag, attrs) {
		var node = document.createElementNS(SVG_NS, tag);
		if (attrs) {
			Object.keys(attrs).forEach(function (key) {
				if (attrs[key] != null) {
					node.setAttribute(key, String(attrs[key]));
				}
			});
		}
		return node;
	}

	function svgTitle(text) {
		var node = svgEl("title", {});
		node.textContent = String(text);
		return node;
	}

	/** Whether an event target is a text field where the browser's own key handling must win. */
	function isEditable(target) {
		if (!target || target.nodeType !== 1) {
			return false;
		}
		var tag = target.tagName ? target.tagName.toLowerCase() : "";
		return tag === "input" || tag === "textarea" || target.isContentEditable === true;
	}

	/* ------------------------------------------------------------------ */
	/* VFXIR delegates (with inline fallbacks for a broken asset chain)    */
	/* ------------------------------------------------------------------ */

	function easingBezier(easing) {
		return root.VFXIR && typeof root.VFXIR.easingToBezier === "function"
				? root.VFXIR.easingToBezier(easing) : [0, 0, 1, 1];
	}

	function sampleBezier(bezier, u) {
		return root.VFXIR && typeof root.VFXIR.bezierY === "function"
				? root.VFXIR.bezierY(bezier, u) : u;
	}

	function snapEasingName(bezier) {
		return root.VFXIR && typeof root.VFXIR.snapEasing === "function"
				? root.VFXIR.snapEasing(bezier) : null;
	}

	function clampHandle(bezier, which, v0, v1, axisLo, axisHi) {
		return root.VFXIR && typeof root.VFXIR.clampBezierHandle === "function"
				? root.VFXIR.clampBezierHandle(bezier, which, v0, v1, axisLo, axisHi) : bezier;
	}

	function handleDeltaY(pixelDelta, innerHeight, valueSpan, axisSpan) {
		return root.VFXIR && typeof root.VFXIR.bezierHandleDeltaY === "function"
				? root.VFXIR.bezierHandleDeltaY(pixelDelta, innerHeight, valueSpan, axisSpan)
				: pixelDelta / (innerHeight || 1) * (axisSpan / valueSpan || 0);
	}

	/** The interpolated value of a key list at a tick, through the segment's own easing. */
	function interpolatedValue(keys, ticks) {
		var sorted = sortedKeys(keys);
		if (sorted.length === 0) {
			return 0;
		}
		if (ticks <= num(sorted[0].time)) {
			return num(sorted[0].value);
		}
		var last = sorted[sorted.length - 1];
		if (ticks >= num(last.time)) {
			return num(last.value);
		}
		for (var i = 0; i < sorted.length - 1; i++) {
			var a = sorted[i];
			var b = sorted[i + 1];
			if (ticks <= num(b.time)) {
				var span = num(b.time) - num(a.time);
				var u = span > 0 ? (ticks - num(a.time)) / span : 0;
				var eased = sampleBezier(easingBezier(a.easing), u);
				return round3(num(a.value) + (num(b.value) - num(a.value)) * eased);
			}
		}
		return num(last.value);
	}

	/** A time-sorted copy of a key list (the original objects are shared). */
	function sortedKeys(keys) {
		return (keys || []).slice().sort(function (a, b) {
			return (num(a.time)) - (num(b.time));
		});
	}

	/**
	 * Render an editable Bézier curve for `keys` into `container`. The component owns its selection
	 * and re-render; the host may pass `opts.onChange` (live drag) and `opts.onCommit` (drag end).
	 *
	 * @param {HTMLElement} container the host element (cleared on every render)
	 * @param {Array<Object>} keys the track's `{time,value,easing}` list, mutated in place
	 * @param {Object} [opts] see the option table in the header; all optional
	 * @returns {{refresh: Function, keys: Array<Object>}} the component handle
	 */
	function curveView(container, keys, opts) {
		opts = opts || {};
		if (!Array.isArray(keys)) {
			keys = [];
		}
		var total = num(opts.total, DEFAULT_TOTAL) > 0 ? num(opts.total, DEFAULT_TOTAL) : DEFAULT_TOTAL;
		var range = opts.range || { lo: 0, hi: 1 };
		var axis = opts.axis || curveAxis(range);
		var innerH = HEIGHT - PAD_TOP - PAD_BOTTOM;
		var pxPerTick = isFiniteNumber(Number(opts.pxPerTick)) && Number(opts.pxPerTick) > 0
				? Number(opts.pxPerTick) : null;
		if (pxPerTick == null) {
			var width = num(opts.width, LABEL_COLUMN + total * 8 + 20);
			pxPerTick = Math.max(0.25, (width - LABEL_COLUMN - 20) / (total || 1));
		}
		var plotWidth = LABEL_COLUMN + Math.max(1, total * pxPerTick + 20);
		/** The selected key object, or null. */
		var selected = opts.selectedKey || null;

		function X(t) {
			return LABEL_COLUMN + Math.max(0, num(t)) * pxPerTick;
		}

		function Y(v) {
			var f = (num(v) - axis.lo) / ((axis.hi - axis.lo) || 1);
			return PAD_TOP + (1 - Math.max(0, Math.min(1, f))) * innerH;
		}

		function changed() {
			render();
			if (typeof opts.onChange === "function") {
				opts.onChange(keys);
			}
		}

		function select(key) {
			selected = key || null;
			if (typeof opts.onSelect === "function") {
				opts.onSelect(selected);
			}
		}

		function commit(reason, key) {
			if (typeof opts.onCommit === "function") {
				opts.onCommit(keys, { reason: reason || "edit", key: key || null });
			}
		}

		function message(text) {
			if (typeof opts.onMessage === "function") {
				opts.onMessage(text);
			}
		}

		/** Drag a key: x snaps to a tick (clamped to `[0,total]`), y maps back through the axis. */
		function beginKeyDrag(key, dot) {
			return function (event) {
				event.preventDefault();
				event.stopPropagation();
				select(key);
				var startX = event.clientX;
				var startY = event.clientY;
				var t0 = num(key.time);
				var v0 = num(key.value);
				var moved = false;

				function move(ev) {
					moved = true;
					key.time = Math.max(0, Math.min(total, Math.round(t0 + (ev.clientX - startX) / pxPerTick)));
					key.value = round3(v0 - (ev.clientY - startY) / (innerH || 1) * (axis.hi - axis.lo));
					changed();
				}

				function up() {
					document.removeEventListener("mousemove", move);
					document.removeEventListener("mouseup", up);
					if (moved) {
						commit("key", key);
					}
				}

				document.addEventListener("mousemove", move);
				document.addEventListener("mouseup", up);
			};
		}

		/** Drag one tangent handle in the segment's local unit box; snap to a named easing on release. */
		function beginHandleDrag(key, which, bez) {
			return function (event) {
				event.preventDefault();
				event.stopPropagation();
				var list = sortedKeys(keys);
				var next = list[list.indexOf(key) + 1];
				if (!next) {
					return;
				}
				var startX = event.clientX;
				var startY = event.clientY;
				var orig = bez.slice();
				var b = bez.slice();
				var segW = (num(next.time) - num(key.time)) * pxPerTick;
				var v0 = num(key.value);
				var v1 = num(next.value);
				var valueSpan = v1 - v0;
				var moved = false;

				function move(ev) {
					moved = true;
					var xi = which * 2;
					var candidate = orig.slice();
					if (segW !== 0) {
						candidate[xi] = orig[xi] + (ev.clientX - startX) / segW;
					}
					if (valueSpan !== 0) {
						candidate[xi + 1] = orig[xi + 1]
								- handleDeltaY(ev.clientY - startY, innerH, valueSpan, axis.hi - axis.lo);
					}
					b = clampHandle(candidate, which, v0, v1, axis.lo, axis.hi);
					key.easing = [b[0], b[1], b[2], b[3]];
					changed();
				}

				function up() {
					document.removeEventListener("mousemove", move);
					document.removeEventListener("mouseup", up);
					if (!moved) {
						return;
					}
					var snapped = snapEasingName(b);
					key.easing = snapped ? snapped : [round3(b[0]), round3(b[1]), round3(b[2]), round3(b[3])];
					commit("handle", key);
					message("easing: " + (snapped ? snapped : "custom bezier"));
				}

				document.addEventListener("mousemove", move);
				document.addEventListener("mouseup", up);
			};
		}

		function headerButton(label, title, onClick) {
			var node = el("button", { type: "button", class: "vfx-tl-btn vfx-tl-curve-btn",
				text: label, title: title });
			node.addEventListener("click", onClick);
			return node;
		}

		/** Delete the selected key (delegated to the host when it can handle track bookkeeping). */
		function deleteSelected() {
			if (!selected) {
				message("select a key first");
				return;
			}
			if (typeof opts.onDeleteSelected === "function") {
				opts.onDeleteSelected(selected);
				return;
			}
			if (keys.length <= 1) {
				// A graph curve node needs at least one point (VFXGraph: "points must be non-empty").
				message("a curve needs at least one point");
				return;
			}
			var index = keys.indexOf(selected);
			if (index >= 0) {
				keys.splice(index, 1);
			}
			select(null);
			changed();
			commit("delete", null);
		}

		function applyEasingToAll() {
			if (typeof opts.onApplyEasingToAll === "function") {
				opts.onApplyEasingToAll(selected);
				return;
			}
			if (!selected || selected.easing == null) {
				message("select a key with an easing first");
				return;
			}
			var easing = Array.isArray(selected.easing) ? selected.easing.slice() : selected.easing;
			keys.forEach(function (key) {
				key.easing = Array.isArray(easing) ? easing.slice() : easing;
			});
			changed();
			commit("apply", null);
		}

		function render() {
			container.textContent = "";
			var wrap = el("div", { class: "vfx-tl-curve" });
			var head = el("div", { class: "vfx-tl-curve-head" });
			head.appendChild(el("span", { class: "vfx-tl-curve-title",
				text: opts.title == null ? "Curve" : String(opts.title) }));
			head.appendChild(headerButton("apply all", "Copy the selected key's easing to every segment",
				applyEasingToAll));
			head.appendChild(headerButton("del key", "Delete the selected key (Delete)", deleteSelected));
			if (typeof opts.onClose === "function") {
				head.appendChild(headerButton("✕", "Close the curve editor", opts.onClose));
			}
			wrap.appendChild(head);

			var svg = svgEl("svg", { class: "vfx-tl-curve-svg", width: plotWidth, height: HEIGHT });
			var list = sortedKeys(keys);

			for (var g = 0; g <= 4; g++) {
				var value = num(range.lo) + (g / 4) * (num(range.hi) - num(range.lo));
				var gy = Y(value);
				svg.appendChild(svgEl("line", { class: "vfx-tl-grid", x1: 0, y1: gy, x2: plotWidth, y2: gy }));
				var label = svgEl("text", { class: "vfx-tl-axis-label", x: 4, y: gy - 2 });
				label.textContent = formatValue(value);
				svg.appendChild(label);
			}

			for (var i = 0; i < list.length - 1; i++) {
				var a = list[i];
				var b = list[i + 1];
				var t0 = num(a.time);
				var t1 = num(b.time);
				var v0 = num(a.value);
				var v1 = num(b.value);
				var bez = easingBezier(a.easing);
				var pts = [];
				for (var s = 0; s <= SAMPLES; s++) {
					var u = s / SAMPLES;
					pts.push(X(t0 + (t1 - t0) * u) + "," + Y(v0 + (v1 - v0) * sampleBezier(bez, u)));
				}
				svg.appendChild(svgEl("polyline", { class: "vfx-tl-curve-line", points: pts.join(" ") }));

				if (t1 > t0) {
					var h1x = X(t0 + (t1 - t0) * bez[0]);
					var h1y = Y(v0 + (v1 - v0) * bez[1]);
					var h2x = X(t0 + (t1 - t0) * bez[2]);
					var h2y = Y(v0 + (v1 - v0) * bez[3]);
					svg.appendChild(svgEl("line", { class: "vfx-tl-handle-line",
						x1: X(t0), y1: Y(v0), x2: h1x, y2: h1y }));
					svg.appendChild(svgEl("line", { class: "vfx-tl-handle-line",
						x1: X(t1), y1: Y(v1), x2: h2x, y2: h2y }));
					[0, 1].forEach(function (which) {
						var hx = which === 0 ? h1x : h2x;
						var hy = which === 0 ? h1y : h2y;
						var handle = svgEl("circle", { class: "vfx-tl-handle", cx: hx, cy: hy, r: 4 });
						handle.appendChild(svgTitle(which === 0
								? "Easing handle 1 (x1,y1) — drag; release near a named easing to snap"
								: "Easing handle 2 (x2,y2)"));
						handle.addEventListener("mousedown", beginHandleDrag(a, which, bez));
						svg.appendChild(handle);
					});
				}
			}

			list.forEach(function (key) {
				var dot = svgEl("circle", { class: "vfx-tl-curve-key",
					cx: X(key.time), cy: Y(key.value), r: 5 });
				if (selected === key) {
					dot.classList.add("active");
				}
				var descriptor = opts.descriptor;
				var outOfRange = descriptor && ((isFiniteNumber(descriptor.min) && num(key.value) < descriptor.min)
						|| (isFiniteNumber(descriptor.max) && num(key.value) > descriptor.max));
				dot.appendChild(svgTitle((opts.name ? opts.name + " " : "")
						+ "t=" + Math.round(num(key.time)) + " v=" + key.value
						+ (key.easing ? " (" + easingLabel(key.easing) + ")" : "")));
				if (outOfRange) {
					dot.classList.add("vfx-tl-out");
				}
				dot.addEventListener("mousedown", beginKeyDrag(key, dot));
				if (typeof opts.onKeyMenu === "function") {
					dot.addEventListener("contextmenu", function (event) {
						select(key);
						opts.onKeyMenu(key, event, dot);
					});
				}
				svg.appendChild(dot);
			});

			if (typeof opts.onAddKey === "function") {
				svg.addEventListener("dblclick", function (event) {
					var target = event.target;
					if (target && target.classList && (target.classList.contains("vfx-tl-curve-key")
							|| target.classList.contains("vfx-tl-handle"))) {
						return;
					}
					var rect = svg.getBoundingClientRect();
					var local = event.clientX - rect.left;
					var time = Math.round(clampTime((local - LABEL_COLUMN) / pxPerTick, total));
					opts.onAddKey(time);
				});
			}

			wrap.appendChild(svg);
			container.appendChild(wrap);
		}

		/** The display label of an IR segment easing. */
		function easingLabel(easing) {
			if (typeof easing === "string") {
				return easing;
			}
			if (Array.isArray(easing)) {
				var snapped = snapEasingName(easing);
				return snapped ? snapped : "cubic-bezier(" + easing.join(", ") + ")";
			}
			return "linear";
		}

		render();
		return {
			refresh: render,
			keys: keys,
			selected: function () {
				return selected;
			},
			deleteSelected: deleteSelected,
			interpolatedValue: function (ticks) {
				return interpolatedValue(keys, ticks);
			}
		};
	}

	/** Delete/Backspace deletes the selected key (installed by the component, not the host). */
	function installKeyHandler(component) {
		function onKey(event) {
			if (isEditable(event.target)) {
				return;
			}
			if (event.key === "Delete" || event.key === "Backspace") {
				if (component.selected()) {
					event.preventDefault();
					component.deleteSelected();
				}
			}
		}
		document.addEventListener("keydown", onKey);
		return function () {
			document.removeEventListener("keydown", onKey);
		};
	}

	var api = {
		curveView: curveView,
		installKeyHandler: installKeyHandler,
		interpolatedValue: interpolatedValue,
		HEIGHT: HEIGHT
	};
	root.VFXCurveView = api;
	if (root.VFXEditor) {
		root.VFXEditor.curveView = api;
	}
})();

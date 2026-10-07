/*
 * VFXweaver visual node editor — timeline + live transport (Task 15).
 *
 * One lane per animated param (a param that has `Tab.keys[name]` or whose spec is
 * `control === "keyframes"`), draggable key dots `{time,value,easing}`, a playhead advanced from a
 * local `requestAnimationFrame` clock and the transport controls. Everything the editor does to the
 * running game goes through the bridge (design §7, Task 8 routes):
 *
 *   play            -> POST /compile      { effectId, json, transport:"play",    revision }
 *   stop            -> POST /transport    { effectId, action:"stop" }
 *   seek (scrub)    -> POST /transport    { effectId, action:"seek", elapsedTicks }
 *   slider (const)  -> POST /set_param    { effectId, name, value, revision }
 *   key drag        -> POST /set_keyframe { effectId, name, time, value, revision }
 *   structural edit -> POST /compile      { effectId, json, transport:"play",    revision }
 *
 * `play` goes through `/compile` (a local play must register the definition, ruling R11); `stop` and
 * `seek` go through `/transport`; `pause` has no VFXweaver equivalent and is rendered **disabled**
 * with an explanatory tooltip. The playhead advances locally while playing — only scrubbing seeks —
 * and a loop re-seeks to 0 when it passes the tab duration.
 *
 * Every response carries the session `revision`; it is stored on `VFXEditor.project.revision` and
 * sent with the next `set_param`/`set_keyframe`. A `stale_revision` (or a hot edit the game refused)
 * surfaces in the status line and is healed with a full `/compile`.
 *
 * `bridge.js` (Task 16) consolidates the fetch helper, SSE reconnect and undo reconcile; this module
 * keeps its own `post()` and reaches the token through `VFXEditor.url`, as the brief allows.
 *
 * Exposed as `window.VFXTimeline`; also attached to `window.VFXEditor.timeline` when the shell is up.
 */
(function () {
	"use strict";

	var root = typeof window !== "undefined" ? window
			: (typeof globalThis !== "undefined" ? globalThis : this);

	/** Game ticks per second (Minecraft default); the playhead clock divides wall time by this. */
	var TICKS_PER_SECOND = 20;
	var MS_PER_TICK = 1000 / TICKS_PER_SECOND;
	/** Drag/debounce window for hot edits, per design §7 ("debounce 50-100 ms"). */
	var DEBOUNCE_MS = 80;
	/** Zoom bounds, px per tick. */
	var ZOOM_MIN = 0.25;
	var ZOOM_MAX = 40;
	var DEFAULT_PX_PER_TICK = 8;
	/** A lane's pixel height and the key dot diameter; value maps into the lane's inner box. */
	var LANE_HEIGHT = 30;
	var KEY_SIZE = 10;
	var LANE_PAD = 3;
	var DEFAULT_DURATION = 40;
	/** Per-key easing names, matching `EasingType` (docs/guide/datapack). */
	var EASINGS = [
		"linear", "ease_in_quad", "ease_out_quad", "ease_in_out_quad",
		"ease_in_cubic", "ease_out_cubic", "ease_in_out_cubic",
		"ease_in_expo", "ease_out_expo", "smoothstep"
	];

	/* ------------------------------------------------------------------ */
	/* Model helpers                                                       */
	/* ------------------------------------------------------------------ */

	/** Lower-case a pack name segment into a legal Identifier part. */
	function sanitize(part) {
		return String(part).toLowerCase().replace(/[^a-z0-9_.\/-]+/g, "_");
	}

	/**
	 * The raw effect id (`<namespace>:<name>`) for a tab. A tab opened from a pack already carries
	 * the qualified name; a fresh tab borrows the reserved editor preview namespace.
	 *
	 * @param {Object} tab the tab
	 * @returns {string} a non-blank `<namespace>:<name>`
	 */
	function effectId(tab) {
		var name = tab && tab.name ? String(tab.name) : "untitled";
		var colon = name.indexOf(":");
		if (colon > 0 && colon < name.length - 1) {
			return sanitize(name.substring(0, colon)) + ":" + sanitize(name.substring(colon + 1));
		}
		return "vfxweaver:" + sanitize(name);
	}

	/** The tab duration in ticks (the format's 40-tick default when absent/invalid). */
	function duration(tab) {
		var d = tab ? Number(tab.duration) : NaN;
		return isFinite(d) && d > 0 ? d : DEFAULT_DURATION;
	}

	function isFiniteNumber(value) {
		return typeof value === "number" && isFinite(value);
	}

	/** The current bridge revision, mirrored from the last response (0 before the first compile). */
	function currentRevision() {
		var project = root.VFXEditor && root.VFXEditor.project;
		var revision = project ? Number(project.revision) : 0;
		return isFinite(revision) ? revision : 0;
	}

	/** Store the revision carried back by the bridge. */
	function setRevision(revision) {
		var project = root.VFXEditor && root.VFXEditor.project;
		if (project && typeof revision === "number") {
			project.revision = revision;
		}
	}

	/** Surface a message in the shell's status line (no-op when the shell is absent). */
	function surface(message) {
		var editor = root.VFXEditor;
		if (editor && typeof editor.status === "function") {
			editor.status(message);
		}
	}

	/* ------------------------------------------------------------------ */
	/* Request bodies (pure — pinned by the headless harness)              */
	/* ------------------------------------------------------------------ */

	/**
	 * {@code POST /compile} body. `play` both registers and plays (R11); structural edits re-play.
	 *
	 * @param {Object} tab the tab to compile
	 * @param {string} transport "play" or "stop"
	 * @returns {Object} the request body
	 */
	function compileBody(tab, transport) {
		var compiler = root.VFXCompiler;
		var json = compiler && typeof compiler.compileTab === "function" ? compiler.compileTab(tab) : "{}";
		return {
			effectId: effectId(tab),
			json: json,
			transport: transport === "stop" ? "stop" : "play",
			revision: currentRevision()
		};
	}

	/**
	 * {@code POST /transport} body. `elapsedTicks` is only sent for a seek.
	 *
	 * @param {Object} tab the tab's effect
	 * @param {string} action "stop" or "seek"
	 * @param {number} [elapsedTicks] target tick for a seek
	 * @returns {Object} the request body
	 */
	function transportBody(tab, action, elapsedTicks) {
		var body = { effectId: effectId(tab), action: action };
		if (action === "seek") {
			body.elapsedTicks = Math.max(0, Math.round(Number(elapsedTicks) || 0));
		}
		return body;
	}

	/**
	 * {@code POST /set_param} body.
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the param name
	 * @param {number} value the new value
	 * @returns {Object} the request body
	 */
	function setParamBody(tab, name, value) {
		return { effectId: effectId(tab), name: name, value: Number(value), revision: currentRevision() };
	}

	/**
	 * {@code POST /set_keyframe} body.
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the param name
	 * @param {number} time the key time in ticks
	 * @param {number} value the key value
	 * @returns {Object} the request body
	 */
	function setKeyframeBody(tab, name, time, value) {
		return {
			effectId: effectId(tab),
			name: name,
			time: Math.round(Number(time) || 0),
			value: Number(value),
			revision: currentRevision()
		};
	}

	/* ------------------------------------------------------------------ */
	/* Bridge transport                                                    */
	/* ------------------------------------------------------------------ */

	/**
	 * Token-bearing POST with a JSON body, resolving to the parsed response object.
	 *
	 * @param {string} path the bridge path, e.g. `/compile`
	 * @param {Object} body the JSON body
	 * @returns {Promise<Object>} the parsed response
	 */
	function post(path, body) {
		var editor = root.VFXEditor;
		var url = editor && typeof editor.url === "function" ? editor.url(path) : path;
		return root.fetch(url, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body)
		}).then(function (response) {
			return response.json();
		}).catch(function (error) {
			// A dropped connection or a non-JSON body must be visible, not an unhandled rejection.
			surface("bridge: " + (error && error.message ? error.message : error));
			return null;
		});
	}

	/**
	 * Record the echoed revision and surface a failure. When `recover` is set, a stale revision or a
	 * refused hot edit triggers a full re-compile so the game catches up with the model.
	 *
	 * @param {Object} result the bridge response
	 * @param {Object} tab the tab the request was for
	 * @param {boolean} [recover] whether a failure may re-compile (false while already compiling)
	 * @returns {Object} the response
	 */
	function handleResult(result, tab, recover) {
		if (!result || typeof result !== "object") {
			return result;
		}
		if (typeof result.revision === "number") {
			setRevision(result.revision);
		}
		if (result.ok === false) {
			surface("bridge: " + (result.error || "error")
					+ (result.detail ? " — " + result.detail : ""));
			if (recover !== false
					&& (result.error === "stale_revision" || result.error === "game_rejected"
							|| result.error === "unknown_effect")) {
				compile(tab, "play");
			}
		}
		return result;
	}

	/* ------------------------------------------------------------------ */
	/* Debounce                                                            */
	/* ------------------------------------------------------------------ */

	var timers = {};

	/** Coalesce repeated calls under one key into a single trailing call. */
	function debounce(key, fn) {
		if (timers[key]) {
			clearTimeout(timers[key]);
		}
		timers[key] = setTimeout(function () {
			delete timers[key];
			fn();
		}, DEBOUNCE_MS);
	}

	/* ------------------------------------------------------------------ */
	/* Editor actions                                                      */
	/* ------------------------------------------------------------------ */

	/**
	 * POST a full `/compile` (and play). Called by the play button and by every structural edit.
	 *
	 * @param {Object} tab the tab to compile
	 * @param {string} [transport] "play" (default) or "stop"
	 * @returns {Promise<Object|null>} the response
	 */
	function compile(tab, transport) {
		if (!tab || !tab.type) {
			surface("choose an effect type before playing");
			return Promise.resolve(null);
		}
		return post("/compile", compileBody(tab, transport)).then(function (result) {
			return handleResult(result, tab, false);
		});
	}

	/** Play: compile with `transport:"play"` (registers + plays), then run the local clock. */
	function play(tab) {
		return compile(tab, "play").then(function (result) {
			// Success carries a revision; `ok` is absent on some bridge replies, so test `!== false`.
			if (result && result.ok !== false) {
				startClock(tab, 0);
			}
			return result;
		});
	}

	/** Stop: halt the local clock, reset the playhead, then `POST /transport { action:"stop" }`. */
	function stop(tab) {
		stopClock();
		setPlayhead(0);
		if (!tab || !tab.type) {
			return Promise.resolve(null);
		}
		return post("/transport", transportBody(tab, "stop")).then(function (result) {
			return handleResult(result, tab, false);
		});
	}

	/** Seek: move the local playhead immediately and send a debounced `/transport seek`. */
	function seek(tab, ticks) {
		var target = Math.max(0, Number(ticks) || 0);
		if (!tab) {
			return;
		}
		if (clock.playing) {
			clock.baseElapsed = target;
			clock.startMs = now();
		}
		setPlayhead(target);
		debounce("seek:" + effectId(tab), function () {
			post("/transport", transportBody(tab, "seek", target)).then(function (result) {
				return handleResult(result, tab, false);
			});
		});
	}

	/** Hot param edit (a constant slider): debounced `POST /set_param`. */
	function setParam(tab, name, value) {
		if (!tab || !name || !isFiniteNumber(Number(value))) {
			return;
		}
		debounce("param:" + effectId(tab) + ":" + name, function () {
			post("/set_param", setParamBody(tab, name, value)).then(function (result) {
				return handleResult(result, tab, true);
			});
		});
	}

	/** Hot keyframe edit (a key drag): debounced `POST /set_keyframe`. */
	function setKeyframe(tab, name, key) {
		if (!tab || !name || !key) {
			return;
		}
		debounce("key:" + effectId(tab) + ":" + name, function () {
			// Build the body (and read `revision`) at send time, so a compile landing inside the
			// debounce window cannot make this keyframe stale.
			post("/set_keyframe", setKeyframeBody(tab, name, key.time, key.value)).then(function (result) {
				return handleResult(result, tab, true);
			});
		});
	}

	/** Structural edit: debounced full `/compile` (revision++ + play). */
	function structural(tab) {
		if (!tab) {
			return;
		}
		debounce("compile:" + effectId(tab), function () {
			compile(tab, "play");
		});
	}

	/**
	 * Route an inspector edit to the right bridge call. A constant value on a constant param is a hot
	 * `set_param`; a form switch, an array value, or any other control is structural (`/compile`).
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the param name
	 * @param {Object|number} spec the edited spec
	 * @param {Object|number} previous the spec before the edit
	 */
	function onEdit(tab, name, spec, previous) {
		if (!tab) {
			return;
		}
		var previousControl = previous && typeof previous === "object" && previous.control
				? previous.control : "constant";
		var scalar = spec && !Array.isArray(spec.value) && isFiniteNumber(Number(spec.value))
				&& (spec.control === "constant" || (spec.control == null && spec.value !== undefined));
		if (scalar && previousControl === "constant") {
			setParam(tab, name, spec.value);
		} else {
			structural(tab);
		}
	}

	/* ------------------------------------------------------------------ */
	/* Playhead clock                                                      */
	/* ------------------------------------------------------------------ */

	var clock = { playing: false, raf: 0, startMs: 0, baseElapsed: 0, tab: null, loop: false };
	/** The DOM of the last {@link render}: `{ tab, scroller, content, head, readout, pxPerTick, ranges }`. */
	var ui = null;
	/** The key shown in the easing control: `{ name, key }` or null. */
	var selected = null;

	function now() {
		return root.performance && typeof root.performance.now === "function"
				? root.performance.now() : Date.now();
	}

	/** The playhead's elapsed ticks: the live clock while playing, else the parked position. */
	function elapsedNow() {
		if (!clock.playing) {
			return clock.baseElapsed;
		}
		return clock.baseElapsed + (now() - clock.startMs) / MS_PER_TICK;
	}

	function startClock(tab, fromTicks) {
		clock.tab = tab;
		clock.playing = true;
		clock.baseElapsed = Number(fromTicks) || 0;
		clock.startMs = now();
		schedule();
	}

	function stopClock() {
		clock.playing = false;
		if (clock.raf) {
			if (root.cancelAnimationFrame) {
				root.cancelAnimationFrame(clock.raf);
			} else {
				clearTimeout(clock.raf);
			}
			clock.raf = 0;
		}
	}

	function schedule() {
		if (clock.raf) {
			return;
		}
		var step = function () {
			clock.raf = 0;
			if (!clock.playing) {
				return;
			}
			var elapsed = elapsedNow();
			var total = duration(clock.tab);
			if (elapsed >= total) {
				if (clock.loop) {
					clock.baseElapsed = 0;
					clock.startMs = now();
					elapsed = 0;
					if (clock.tab) {
						seek(clock.tab, 0);
					}
				} else {
					clock.playing = false;
					clock.baseElapsed = total;
					elapsed = total;
				}
			}
			setPlayhead(elapsed);
			schedule();
		};
		var raf = root.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };
		clock.raf = raf(step);
	}

	function setPlayhead(ticks) {
		clock.baseElapsed = clock.playing ? clock.baseElapsed : Math.max(0, Number(ticks) || 0);
		if (!ui) {
			return;
		}
		var x = Math.max(0, Number(ticks) || 0) * ui.pxPerTick;
		if (ui.head) {
			ui.head.style.left = x + "px";
		}
		if (ui.readout) {
			ui.readout.textContent = Math.round(Number(ticks) || 0) + " / " + Math.round(duration(ui.tab)) + " t";
		}
	}

	/* ------------------------------------------------------------------ */
	/* DOM helpers                                                         */
	/* ------------------------------------------------------------------ */

	/** Create an element; `class`/`text` are special, an `on<event>` function is a listener. */
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

	/** The params that deserve a lane: a non-empty `Tab.keys[name]`, or a keyframes control. */
	function animatedParams(tab) {
		var names = [];
		var keys = tab && tab.keys ? tab.keys : {};
		Object.keys(keys).forEach(function (name) {
			if (keys[name] && keys[name].length) {
				names.push(name);
			}
		});
		var params = tab && tab.params ? tab.params : {};
		Object.keys(params).forEach(function (name) {
			var spec = params[name];
			if (spec && spec.control === "keyframes" && names.indexOf(name) < 0) {
				names.push(name);
			}
		});
		return names;
	}

	/** The schema descriptor for a param, for its min/max value range (may be null). */
	function paramDescriptor(tab, name) {
		var schema = root.VFXEditor && root.VFXEditor.schema;
		var descriptor = schema && tab && tab.type ? schema[tab.type] : null;
		var params = descriptor && descriptor.params ? descriptor.params : [];
		for (var i = 0; i < params.length; i++) {
			if (params[i].name === name) {
				return params[i];
			}
		}
		return null;
	}

	/** The value range a lane maps vertically: the descriptor bounds when finite, else the keys. */
	function valueRange(tab, name, keys) {
		var descriptor = paramDescriptor(tab, name);
		var lo;
		var hi;
		if (descriptor && isFiniteNumber(descriptor.min) && isFiniteNumber(descriptor.max)
				&& descriptor.max > descriptor.min) {
			lo = descriptor.min;
			hi = descriptor.max;
		} else {
			lo = Infinity;
			hi = -Infinity;
			(keys || []).forEach(function (key) {
				var value = Number(key.value);
				if (isFinite(value)) {
					lo = Math.min(lo, value);
					hi = Math.max(hi, value);
				}
			});
			if (!isFinite(lo) || !isFinite(hi) || lo === hi) {
				lo = isFinite(lo) ? lo - 1 : 0;
				hi = isFinite(hi) ? hi + 1 : 1;
			}
		}
		return { lo: lo, hi: hi };
	}

	var INNER = LANE_HEIGHT - KEY_SIZE - 2 * LANE_PAD;

	function valueToY(value, range) {
		var span = range.hi - range.lo || 1;
		var t = (Number(value) - range.lo) / span;
		t = Math.max(0, Math.min(1, t));
		return LANE_PAD + (1 - t) * INNER;
	}

	function yToValue(y, range) {
		var t = 1 - (y - LANE_PAD) / (INNER || 1);
		t = Math.max(0, Math.min(1, t));
		return range.lo + t * (range.hi - range.lo);
	}

	function positionKey(dot, name, key) {
		if (!ui) {
			return;
		}
		dot.style.left = Math.max(0, Number(key.time) || 0) * ui.pxPerTick + "px";
		dot.style.top = valueToY(key.value, ui.ranges[name]) + "px";
	}

	/* ------------------------------------------------------------------ */
	/* Interaction                                                         */
	/* ------------------------------------------------------------------ */

	/** Select a key for the easing control and outline it. */
	function selectKey(name, key, dot) {
		if (selected && selected.dot) {
			selected.dot.classList.remove("active");
		}
		selected = { name: name, key: key, dot: dot };
		if (dot) {
			dot.classList.add("active");
		}
		if (ui && ui.easing) {
			ui.easing.value = key.easing || "linear";
			ui.easingRow.hidden = false;
			ui.easingLabel.textContent = name + " @" + Math.round(Number(key.time) || 0);
		}
	}

	/** Drag a key dot: x -> time (ticks), y -> value within the lane; commits on mouseup. */
	function beginDrag(tab, name, key, dot) {
		return function (event) {
			event.preventDefault();
			event.stopPropagation();
			selectKey(name, key, dot);
			var startX = event.clientX;
			var startY = event.clientY;
			var t0 = Number(key.time) || 0;
			var v0 = Number(key.value) || 0;
			var range = ui.ranges[name];
			var moved = false;

			function move(ev) {
				moved = true;
				var dTicks = (ev.clientX - startX) / ui.pxPerTick;
				var dValue = -((ev.clientY - startY) / (INNER || 1)) * (range.hi - range.lo);
				key.time = Math.max(0, Math.round(t0 + dTicks));
				key.value = round3(v0 + dValue);
				positionKey(dot, name, key);
				if (ui && ui.easingRow && !ui.easingRow.hidden && ui.easingLabel) {
					ui.easingLabel.textContent = name + " @" + key.time;
				}
			}

			function up() {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
				if (moved) {
					setKeyframe(tab, name, key);
				}
			}

			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
		};
	}

	function round3(value) {
		return Math.round(value * 1000) / 1000;
	}

	/** Map a viewport x to a tick inside the (horizontally scrolled) track content. */
	function tickAt(clientX) {
		if (!ui || !ui.scroller) {
			return 0;
		}
		var rect = ui.scroller.getBoundingClientRect();
		var x = clientX - rect.left + ui.scroller.scrollLeft;
		return Math.max(0, x / ui.pxPerTick);
	}

	/** Scrub the playhead by dragging the track background (never a key dot). */
	function beginScrub(tab) {
		return function (event) {
			event.preventDefault();
			function move(ev) {
				seek(tab, tickAt(ev.clientX));
			}
			function up() {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
			}
			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
			seek(tab, tickAt(event.clientX));
		};
	}

	/* ------------------------------------------------------------------ */
	/* Render                                                              */
	/* ------------------------------------------------------------------ */

	/** A small transport button; `disabled` renders it inert with a tooltip. */
	function button(label, title, disabled, onClick) {
		var node = el("button", { type: "button", class: "vfx-tl-btn", text: label, title: title });
		node.disabled = Boolean(disabled);
		if (!disabled) {
			node.addEventListener("click", onClick);
		}
		return node;
	}

	function transportBar(tab) {
		var bar = el("div", { class: "vfx-tl-transport" });
		bar.appendChild(button("▶", "Play (compile + play)", false, function () { play(tab); }));
		// R11: the bridge accepts /transport pause but there is no game pause - disable, don't fake.
		bar.appendChild(button("⏸", "Pause is not supported by VFXweaver — use Stop", true, null));
		bar.appendChild(button("■", "Stop", false, function () { stop(tab); }));

		var loop = button("⟳", "Loop", false, function () {
			clock.loop = !clock.loop;
			loop.classList.toggle("active", clock.loop);
		});
		loop.classList.toggle("active", clock.loop);
		bar.appendChild(loop);

		var readout = el("span", { class: "vfx-tl-readout", text: "0 / " + Math.round(duration(tab)) + " t" });
		bar.appendChild(readout);

		var easingRow = el("div", { class: "vfx-tl-easing" });
		var easingLabel = el("span", { class: "vfx-tl-easing-label", text: "" });
		var easing = el("select", { class: "vfx-select" });
		EASINGS.forEach(function (name) {
			easing.appendChild(el("option", { value: name, text: name }));
		});
		easing.addEventListener("change", function () {
			if (!selected || !selected.key) {
				return;
			}
			selected.key.easing = easing.value;
			structural(tab);
		});
		easingRow.appendChild(easingLabel);
		easingRow.appendChild(easing);
		easingRow.hidden = !(selected && selected.key);
		if (selected && selected.key) {
			easing.value = selected.key.easing || "linear";
			easingLabel.textContent = selected.name + " @" + Math.round(Number(selected.key.time) || 0);
		}

		bar.appendChild(easingRow);
		return { bar: bar, readout: readout, easing: easing, easingRow: easingRow, easingLabel: easingLabel };
	}

	function ruler(tab, pxPerTick) {
		var bar = el("div", { class: "vfx-tl-ruler" });
		var total = duration(tab);
		var step = 1;
		var steps = [1, 2, 5, 10, 20, 50, 100, 200, 500];
		for (var i = 0; i < steps.length; i++) {
			if (steps[i] * pxPerTick >= 50) {
				step = steps[i];
				break;
			}
			step = steps[i];
		}
		for (var tick = 0; tick <= total; tick += step) {
			var mark = el("span", { class: "vfx-tl-tick", text: String(tick) });
			mark.style.left = tick * pxPerTick + "px";
			bar.appendChild(mark);
		}
		return bar;
	}

	/**
	 * Build the timeline panel for one tab. Returns a detached element the shell mounts; if the same
	 * clock is still running for this tab, the playhead element is re-bound and keeps advancing.
	 *
	 * @param {Object|null} tab the active `VFXIR.Tab` (may be null)
	 * @returns {HTMLElement} the panel
	 */
	function render(tab) {
		if (clock.tab && clock.tab !== tab) {
			stopClock();
			clock.baseElapsed = 0;
		}
		var panel = el("div", { class: "vfx-tl" });
		if (!tab) {
			panel.appendChild(el("p", { class: "vfx-tl-empty", text: "No active tab" }));
			ui = null;
			return panel;
		}
		if (!tab.timeline || typeof tab.timeline !== "object") {
			tab.timeline = {};
		}
		var pxPerTick = isFiniteNumber(Number(tab.timeline.pxPerTick)) && tab.timeline.pxPerTick > 0
				? Number(tab.timeline.pxPerTick) : DEFAULT_PX_PER_TICK;
		pxPerTick = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, pxPerTick));
		tab.timeline.pxPerTick = pxPerTick;

		var bar = transportBar(tab);
		panel.appendChild(bar.bar);

		var total = duration(tab);
		var names = animatedParams(tab);
		var ranges = {};
		names.forEach(function (name) {
			ranges[name] = valueRange(tab, name, tab.keys[name] || []);
		});

		var scroller = el("div", { class: "vfx-tl-scroll" });
		var content = el("div", { class: "vfx-tl-content" });
		content.style.width = Math.max(1, total * pxPerTick + 20) + "px";
		scroller.appendChild(content);

		if (names.length === 0) {
			content.appendChild(el("p", { class: "vfx-tl-hint", text: "No animated parameters — add keyframes in the inspector" }));
		}

		content.appendChild(ruler(tab, pxPerTick));
		names.forEach(function (name) {
			var lane = el("div", { class: "vfx-tl-lane" });
			lane.style.height = LANE_HEIGHT + "px";
			lane.appendChild(el("span", { class: "vfx-tl-label", text: name }));
			(tab.keys[name] || []).forEach(function (key) {
				var dot = el("button", {
					type: "button",
					class: "vfx-tl-key",
					title: name + " t=" + Math.round(Number(key.time) || 0) + " v=" + key.value
							+ (key.easing ? " (" + key.easing + ")" : "")
				});
				dot.addEventListener("mousedown", beginDrag(tab, name, key, dot));
				lane.appendChild(dot);
				positionKeyLater(dot, name, key, pxPerTick, ranges);
			});
			content.appendChild(lane);
		});

		var head = el("div", { class: "vfx-tl-playhead" });
		content.appendChild(head);
		head.style.left = elapsedNow() * pxPerTick + "px";

		// Scrub on the background: a mousedown that was not consumed by a key dot.
		scroller.addEventListener("mousedown", beginScrub(tab));
		// Ctrl/Cmd + wheel zooms; otherwise the native horizontal scrollbar handles scrolling.
		scroller.addEventListener("wheel", function (event) {
			if (!event.ctrlKey && !event.metaKey) {
				return;
			}
			event.preventDefault();
			var factor = event.deltaY < 0 ? 1.15 : 1 / 1.15;
			tab.timeline.pxPerTick = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, pxPerTick * factor));
			renderInto(panel.parentNode, tab);
		}, { passive: false });

		panel.appendChild(scroller);

		ui = {
			tab: tab,
			scroller: scroller,
			content: content,
			head: head,
			pxPerTick: pxPerTick,
			ranges: ranges,
			readout: bar.readout,
			easing: bar.easing,
			easingRow: bar.easingRow,
			easingLabel: bar.easingLabel
		};
		if (selected && selected.key) {
			// Re-highlight the selected key if its lane survived a re-render.
			var dots = content.querySelectorAll(".vfx-tl-key.active");
			if (dots.length === 0 && selected.dot && content.contains(selected.dot)) {
				selected.dot.classList.add("active");
			}
		}
		return panel;
	}

	/** Position a dot after its lane has been given a pxPerTick (render sets `ui` last). */
	function positionKeyLater(dot, name, key, pxPerTick, ranges) {
		dot.style.left = Math.max(0, Number(key.time) || 0) * pxPerTick + "px";
		dot.style.top = valueToY(key.value, ranges[name]) + "px";
	}

	/** Re-render the panel in place (used by a zoom, which changes every key's position). */
	function renderInto(container, tab) {
		if (!container) {
			return;
		}
		container.textContent = "";
		container.appendChild(render(tab));
	}

	var api = {
		render: render,
		onEdit: onEdit,
		compile: compile,
		play: play,
		stop: stop,
		seek: seek,
		setParam: setParam,
		setKeyframe: setKeyframe,
		effectId: effectId,
		bodies: {
			compile: compileBody,
			transport: transportBody,
			setParam: setParamBody,
			setKeyframe: setKeyframeBody
		},
		EASINGS: EASINGS
	};
	root.VFXTimeline = api;
	if (root.VFXEditor) {
		root.VFXEditor.timeline = api;
	}
})();

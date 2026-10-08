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
 * `bridge.js` (Task 16) owns the transport: this module builds the request bodies and hands them to
 * `VFXBridge.post` (SSE reconnect, revision reconcile and undo resync live there).
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
	/**
	 * The fixed left gutter (px) every time-based row starts after: the ruler, the duration bar, the
	 * key lanes, the curve plot and the gantt track all share this left offset, so ticks align with
	 * keys and gantt bars at any effect count (fix 5). Lane/effect names live inside it and never
	 * consume axis width. Keep in sync with `.vfx-tl-label` / `.vfx-tl-gantt-name` in editor.css.
	 */
	var LABEL_COLUMN = 120;
	/** Curve-view geometry (design §15.4, fix #13): a taller plot with padding for axis labels. */
	var CURVE_HEIGHT = 240;
	var CURVE_PAD_TOP = 18;
	var CURVE_PAD_BOTTOM = 24;
	/** Polyline resolution per segment when sampling the eased curve. */
	var CURVE_SAMPLES = 48;
	/** SVG namespace for the curve view (created with `createElementNS`, not `el`). */
	var SVG_NS = "http://www.w3.org/2000/svg";
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

	/**
	 * The reserved effect id the PREVIEW plays under (fix H1). It must NOT be the authored id: a tab
	 * opened from a datapack keeps the pack id, and the game's definition registry lets the
	 * datapack/server layer win over a local registration, so a preview registered under the
	 * authored id plays the *datapack* definition (its own `loop`/`fade_ticks`/timing) instead of
	 * the compiled preview — which is exactly why an edited effect still faded/died in preview. A
	 * private id makes the local preview the definition the engine resolves. Export still uses the
	 * authored namespace/name.
	 *
	 * @returns {string} the reserved preview effect id
	 */
	function previewId() {
		return "vfxweaver:editor_preview";
	}

	/** The tab duration in ticks (the format's 40-tick default when absent/invalid). */
	function duration(tab) {
		var d = tab ? Number(tab.duration) : NaN;
		return isFinite(d) && d > 0 ? d : DEFAULT_DURATION;
	}

	function isFiniteNumber(value) {
		return typeof value === "number" && isFinite(value);
	}

	/**
	 * Whether an event target is a text field (an `input`/`textarea`/`contenteditable`), where the
	 * browser's own key handling must win. Mirrors `app.js isEditable`; the curve key listener is a
	 * bubble listener the global capture hotkey does not shield (review G3).
	 */
	function isEditable(target) {
		if (!target || target.nodeType !== 1) {
			return false;
		}
		var tag = target.tagName ? target.tagName.toLowerCase() : "";
		return tag === "input" || tag === "textarea" || target.isContentEditable === true;
	}

	/**
	 * Clamp a tick time into `[0, duration(tab)]`. Delegates to the IR helper when it is loaded
	 * (so the same rule is pinned by the selftest), with an inline fallback for a broken asset chain.
	 *
	 * @param {Object} tab the tab (its `duration` bounds the range)
	 * @param {number} ticks the candidate time in ticks
	 * @returns {number} the clamped time
	 */
	function clampToDuration(tab, ticks) {
		var total = duration(tab);
		if (root.VFXIR && typeof root.VFXIR.clampTime === "function") {
			return root.VFXIR.clampTime(ticks, total);
		}
		var t = Number(ticks);
		if (!isFinite(t)) {
			t = 0;
		}
		return Math.max(0, Math.min(total, t));
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

	/** Record one undo snapshot through the shell (no-op when the shell is absent). */
	function commit(tab) {
		var editor = root.VFXEditor;
		if (editor && typeof editor.commit === "function") {
			editor.commit();
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
		// Preview artifact ≠ authored output (design §15.5): the play/auto-replay path sends the
		// loop:true + fade_ticks:0 override so an edit never fades or dies mid-inspection. Export
		// (app.js `gatherEffects`) keeps the authored `compileTab`.
		var json = compiler && typeof compiler.compileForPreview === "function"
				? compiler.compileForPreview(tab)
				: (compiler && typeof compiler.compileTab === "function" ? compiler.compileTab(tab) : "{}");
		return {
			effectId: previewId(),
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
		var body = { effectId: previewId(), action: action };
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
		return { effectId: previewId(), name: name, value: Number(value), revision: currentRevision() };
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
			effectId: previewId(),
			name: name,
			time: Math.round(Number(time) || 0),
			value: Number(value),
			revision: currentRevision()
		};
	}

	/* ------------------------------------------------------------------ */
	/* Preview v2 session (design §16)                                     */
	/* ------------------------------------------------------------------ */

	/**
	 * The preview session state. `revision` is WEB-owned: the controller stores whatever `sync`
	 * sends and gates `viewstate`/`param`/`transport` on it, so the browser is the single writer of
	 * the revision counter. `mode` is `exact` (default) or `authored`.
	 */
	var preview = {
		revision: 0,
		tab: null,
		mode: "exact",
		playing: false,
		ended: false,
		tick: 0,
		duration: DEFAULT_DURATION,
		syncing: false,
		context: { anchor: "player", pos: null, targets: [] }
	};

	/** Bump and return the next preview revision (sent with every preview action). */
	function nextRevision() {
		preview.revision += 1;
		return preview.revision;
	}

	/** The tab the preview is driving (the synced one, else the shell's active tab). */
	function previewTab() {
		if (preview.tab) {
			return preview.tab;
		}
		var project = root.VFXEditor && root.VFXEditor.project;
		return project && project.active >= 0 ? project.tabs[project.active] : null;
	}

	/** Surface a preview failure in the status line and the console (never silently). */
	function previewError(message) {
		surface("preview: " + message);
		if (root.console && root.console.error) {
			root.console.error("[vfx preview] " + message);
		}
	}

	/** Guards the one-shot stale-revision recovery so a bad expected revision cannot loop. */
	var staleGuard = false;

	/** Route a preview response: stale → re-sync, rejected → error entry, else mark applied. */
	function handlePreviewResult(result, tab) {
		if (!result || typeof result !== "object") {
			return result;
		}
		if (result.ok === false) {
			if (result.error === "stale_revision") {
				if (staleGuard) {
					previewError("stale revision after re-sync");
					return result;
				}
				staleGuard = true;
				// Jump to the revision the game expects (a page reload resets our counter).
				var match = /expected revision (\d+)/.exec(result.detail || "");
				if (match) {
					preview.revision = Number(match[1]);
				}
				return previewResync(tab);
			}
			previewError(result.error + (result.detail ? " — " + result.detail : ""));
			return result;
		}
		staleGuard = false;
		preview.syncing = false;
		if (Array.isArray(result.rejected) && result.rejected.length) {
			previewError("rejected " + result.rejected.length + " definition(s): "
					+ result.rejected.join(", "));
		}
		return result;
	}

	/** POST the whole tab definition set (revision++); the controller diffs and re-projects it. */
	function previewSyncNow(tab) {
		var target = tab || previewTab();
		if (!target || !target.effects || !target.effects.length || !root.VFXCompiler
				|| typeof root.VFXCompiler.compilePreviewSet !== "function") {
			return Promise.resolve(null);
		}
		preview.tab = target;
		var set;
		try {
			set = root.VFXCompiler.compilePreviewSet(target, { context: preview.context });
		} catch (e) {
			previewError("compile failed: " + (e && e.message ? e.message : e));
			return Promise.resolve(null);
		}
		preview.syncing = true;
		return post("/preview/sync", {
			revision: nextRevision(),
			tab: target.name,
			definitions: set.definitions,
			root: set.root,
			meta: set.meta
		}).then(function (result) {
			return handlePreviewResult(result, target);
		});
	}

	/** Debounced (~250 ms) full-set sync, fired after every structural edit. */
	function previewSync(tab) {
		if (!tab) {
			return;
		}
		debounceAfter("preview:" + effectId(tab), 250, function () {
			previewSyncNow(tab);
		});
	}

	/** Immediate re-sync (revision++): the 409/stale recovery and the reconnect reassert. */
	function previewResync(tab) {
		return previewSyncNow(tab || previewTab());
	}

	/** One transport action, revision-gated; a stale reply triggers one re-sync + retry. */
	function previewTransport(action, extra) {
		var body = { revision: preview.revision, action: action };
		if (extra) {
			Object.keys(extra).forEach(function (key) {
				body[key] = extra[key];
			});
		}
		return post("/preview/transport", body).then(function (result) {
			if (result && result.ok === false && result.error === "stale_revision") {
				return previewResync(previewTab()).then(function () {
					body.revision = preview.revision;
					return post("/preview/transport", body);
				});
			}
			return handlePreviewResult(result, previewTab());
		});
	}

	/** Send the view state (order/mute/solo) — instance ops only, no recompilation. */
	function previewViewstate(tab) {
		var target = tab || previewTab();
		if (!target || !root.VFXCompiler || typeof root.VFXCompiler.previewViewstate !== "function") {
			return Promise.resolve(null);
		}
		var vs = root.VFXCompiler.previewViewstate(target);
		var body = { revision: preview.revision, order: vs.order, muted: vs.muted, solo: vs.solo };
		return post("/preview/viewstate", body).then(function (result) {
			if (result && result.ok === false && result.error === "stale_revision") {
				return previewResync(target).then(function () {
					body.revision = preview.revision;
					return post("/preview/viewstate", body);
				});
			}
			return handlePreviewResult(result, target);
		});
	}

	/** Fast-path param send state (leading + trailing throttle at ~30 Hz). */
	var paramLastMs = 0;
	var paramTimer = 0;
	var paramPending = null;

	/** POST one param override on the child's live instance (fast path). */
	function sendParam(key, name, value) {
		return post("/preview/param", {
			revision: preview.revision,
			key: key,
			param: name,
			value: Number(value)
		}).then(function (result) {
			if (result && result.ok === false && result.error === "stale_revision") {
				return previewResync(previewTab());
			}
			return result;
		});
	}

	/**
	 * Fast-path param edit (~30 Hz while dragging): sends the override now when the throttle window
	 * elapsed, else remembers the latest value for a trailing send. The caller re-syncs on release.
	 *
	 * @param {Object} tab the tab (unused but keeps the call sites uniform)
	 * @param {string} key the child key (the node uuid)
	 * @param {string} name the param name
	 * @param {number} value the new value
	 */
	function previewParam(tab, key, name, value) {
		if (!key || !name || !isFiniteNumber(Number(value))) {
			return;
		}
		if (now() - paramLastMs >= 33) {
			paramLastMs = now();
			sendParam(key, name, value);
			return;
		}
		paramPending = { key: key, name: name, value: value };
		if (!paramTimer) {
			paramTimer = setTimeout(function () {
				paramTimer = 0;
				var pending = paramPending;
				paramPending = null;
				if (pending) {
					paramLastMs = now();
					sendParam(pending.key, pending.name, pending.value);
				}
			}, 33);
		}
	}

	/** Switch the preview mode (exact|authored) and push it to the controller. */
	function setPreviewMode(mode) {
		preview.mode = mode === "authored" ? "authored" : "exact";
		return previewTransport("setMode", { mode: preview.mode });
	}

	/** The preview session, exposed for the shell (layers, mode toggle, status badge). */
	var previewApi = {
		sync: previewSync,
		resync: previewResync,
		transport: previewTransport,
		viewstate: previewViewstate,
		param: previewParam,
		setMode: setPreviewMode,
		mode: function () {
			return preview.mode;
		},
		state: function () {
			return preview;
		},
		setContext: function (context) {
			preview.context = context || preview.context;
		}
	};

	/* ------------------------------------------------------------------ */
	/* Bridge transport                                                    */
	/* ------------------------------------------------------------------ */

	/**
	 * Token-bearing POST with a JSON body, resolving to the parsed response object. Task 16 moved the
	 * transport into `bridge.js`; this is a thin delegate, so every mutation still goes through one
	 * place (revision tracking, reconnect, error surfacing).
	 *
	 * @param {string} path the bridge path, e.g. `/compile`
	 * @param {Object} body the JSON body
	 * @returns {Promise<Object|null>} the parsed response, or null when there is no bridge
	 */
	function post(path, body) {
		var bridge = root.VFXBridge;
		return bridge && typeof bridge.post === "function" ? bridge.post(path, body) : Promise.resolve(null);
	}

	/* ------------------------------------------------------------------ */
	/* Debounce                                                            */
	/* ------------------------------------------------------------------ */

	var timers = {};

	/** Coalesce repeated calls under one key into a single trailing call. */
	function debounce(key, fn) {
		debounceAfter(key, DEBOUNCE_MS, fn);
	}

	/** Like {@link debounce} with an explicit delay in ms. */
	function debounceAfter(key, ms, fn) {
		if (timers[key]) {
			clearTimeout(timers[key]);
		}
		timers[key] = setTimeout(function () {
			delete timers[key];
			fn();
		}, ms);
	}

	/* ------------------------------------------------------------------ */
	/* Editor actions                                                      */
	/* ------------------------------------------------------------------ */

	/** Recompile the whole set now (the structural entry point; kept as `compile` for callers). */
	function compile(tab) {
		return previewSyncNow(tab);
	}

	/** Play: sync the set, then transport play; run the local clock as an SSE fallback. */
	function play(tab) {
		if (!tab || !tab.type) {
			surface("choose an effect type before playing");
			return Promise.resolve(null);
		}
		preview.tab = tab;
		return previewSyncNow(tab).then(function () {
			return previewTransport("play");
		}).then(function (result) {
			if (!result || result.ok !== false) {
				clock.tab = tab;
				startClock(tab, 0);
			}
			return result;
		});
	}

	/** Stop: transport stop, halt the local clock, reset the playhead. */
	function stop(tab) {
		stopClock();
		setPlayhead(0);
		if (!tab || !tab.type) {
			return Promise.resolve(null);
		}
		return previewTransport("stop");
	}

	/** Pause: transport pause, hold the playhead where it is (design §16.4). */
	function pause(tab) {
		stopClock();
		if (!tab || !tab.type) {
			return Promise.resolve(null);
		}
		return previewTransport("pause");
	}

	/** Seek: move the local playhead immediately and send a debounced transport seek. */
	function seek(tab, ticks) {
		var target = clampToDuration(tab, Number(ticks) || 0);
		if (!tab) {
			return;
		}
		if (clock.playing) {
			clock.baseElapsed = target;
			clock.startMs = now();
		}
		setPlayhead(target);
		debounce("seek:" + effectId(tab), function () {
			previewTransport("seek", { tick: target });
		});
	}

	/**
	 * Scrub: move the local playhead only. The pause-while-dragging / restore-after gesture lives in
	 * {@link beginScrub}; the actual game seek is a debounced transport call.
	 */
	function scrub(tab, ticks) {
		if (!tab) {
			return;
		}
		var target = clampToDuration(tab, Number(ticks) || 0);
		if (clock.playing) {
			clock.baseElapsed = target;
			clock.startMs = now();
		}
		setPlayhead(target);
	}

	/**
	 * Hot param edit (a constant slider): fast-path `/preview/param` at ~30 Hz, then a debounced
	 * full-set sync when the edit settles (design §16.6). The local readout stays instant.
	 */
	function setParam(tab, name, value) {
		if (!tab || !name || !isFiniteNumber(Number(value))) {
			return;
		}
		var effect = tab.effect ? tab.effect() : (tab.effects && tab.effects[0]);
		if (effect && root.VFXCompiler && typeof root.VFXCompiler.nodeUuid === "function") {
			previewParam(tab, root.VFXCompiler.nodeUuid(effect), name, value);
		}
		previewSync(tab);
	}

	/** Key edit: the definition changes, so a full-set sync (no fast path for keys). */
	function setKeyframe(tab, name, key) {
		if (!tab || !name || !key) {
			return;
		}
		previewSync(tab);
	}

	/** Structural edit: debounced full-set sync. */
	function structural(tab) {
		previewSync(tab);
	}

	/**
	 * After an edit settles (~250 ms) re-sync the set. The controller re-projects at the current
	 * tick, so the playhead is preserved (no auto-replay to 0); a scrub does not come here.
	 *
	 * @param {Object} tab the edited tab
	 */
	function scheduleReplay(tab) {
		previewSync(tab);
	}

	/**
	 * Route an inspector edit to the right preview call. A constant value on a constant param is a
	 * fast `/preview/param`; a form switch, an array value, or any other control is a full sync.
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the param name
	 * @param {Object|number} spec the edited spec
	 * @param {Object|number} previous the spec before the edit
	 */
	function onEdit(tab, name, spec) {
		if (!tab) {
			return;
		}
		var keys = tab.keys && tab.keys[name];
		var hasKeys = Array.isArray(keys) && keys.length > 0;
		var driven = false;
		(tab.edges || []).forEach(function (edge) {
			if (edge && edge.to === name && edge.input == null) {
				driven = true;
			}
		});
		var scalar = spec && typeof spec === "object" && spec.value !== undefined
				&& !Array.isArray(spec.value) && isFiniteNumber(Number(spec.value));
		if (scalar && !hasKeys && !driven) {
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
	/**
	 * The open curve view (design §15.4, fix #13): `{ tab, name }` while a track is expanded to its
	 * curve editor, else null. `curvePanel` caches the mounted `.vfx-tl` so a drag can re-render it
	 * without threading the DOM through every handler; `curveKeyHandler` is the live Delete/Esc key
	 * listener, removed and re-added on every render.
	 */
	var curveOpen = null;
	var curvePanel = null;
	var curveKeyHandler = null;
	/** A single-click key add deferred so a following double-click (open curve) can cancel it. */
	var pendingLaneAdd = null;

	/** The Bézier the curve editor draws a segment with (delegates to the IR's canonical table). */
	function easingBezier(easing) {
		return root.VFXIR && typeof root.VFXIR.easingToBezier === "function"
				? root.VFXIR.easingToBezier(easing) : [0, 0, 1, 1];
	}

	/** Sample a cubic-Bézier easing at normalized time `u` (delegates to the IR). */
	function sampleBezier(bezier, u) {
		return root.VFXIR && typeof root.VFXIR.bezierY === "function"
				? root.VFXIR.bezierY(bezier, u) : u;
	}

	/** Recognise a Bézier as a named easing within epsilon, else null (delegates to the IR). */
	function snapEasingName(bezier) {
		return root.VFXIR && typeof root.VFXIR.snapEasing === "function"
				? root.VFXIR.snapEasing(bezier) : null;
	}

	/** A deep-enough copy of an easing (array clone / string) for broadcast edits. */
	function cloneEasing(easing) {
		return Array.isArray(easing) ? easing.slice() : easing;
	}

	/** The keys of a track, sorted by time (the curve view is always time-ordered). */
	function sortedKeys(tab, name) {
		var list = (tab.keys && tab.keys[name]) || [];
		return list.slice().sort(function (a, b) {
			return (Number(a.time) || 0) - (Number(b.time) || 0);
		});
	}

	/** Create an SVG element with attributes (mirrors {@link el}, but in the SVG namespace). */
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

	/** Re-render the whole panel in place (used after a curve edit/drag). */
	function refreshCurve(tab) {
		if (curvePanel && curvePanel.parentNode) {
			renderInto(curvePanel.parentNode, tab);
		} else if (root.VFXEditor && typeof root.VFXEditor.renderTimeline === "function") {
			root.VFXEditor.renderTimeline();
		}
	}

	/** The interpolated value of a track at a tick (the curve's own easing), for a new key. */
	function interpolatedValue(tab, name, ticks) {
		var keys = sortedKeys(tab, name);
		if (keys.length === 0) {
			return laneValue(tab, name);
		}
		if (ticks <= Number(keys[0].time)) {
			return Number(keys[0].value);
		}
		var last = keys[keys.length - 1];
		if (ticks >= Number(last.time)) {
			return Number(last.value);
		}
		for (var i = 0; i < keys.length - 1; i++) {
			var a = keys[i];
			var b = keys[i + 1];
			if (ticks <= Number(b.time)) {
				var span = Number(b.time) - Number(a.time);
				var u = span > 0 ? (ticks - Number(a.time)) / span : 0;
				var eased = sampleBezier(easingBezier(a.easing), u);
				return round3(Number(a.value) + (Number(b.value) - Number(a.value)) * eased);
			}
		}
		return Number(last.value);
	}

	/** Remove the selected key from the open curve view, commit, and re-render (fix #13). */
	function deleteSelectedCurveKey(tab, name) {
		if (!selected || !selected.key) {
			surface("select a key first");
			return;
		}
		var list = (tab.keys && tab.keys[name]) || [];
		var index = list.indexOf(selected.key);
		if (index < 0) {
			return;
		}
		list.splice(index, 1);
		selected = null;
		if (list.length === 0) {
			delete tab.keys[name];
			curveOpen = null;
		}
		commit(tab);
		structural(tab);
		refreshCurve(tab);
	}

	/** Copy the selected key's easing onto every segment of the track (design §15.4). */
	function applyEasingToAll(tab, name) {
		if (!selected || !selected.key || selected.key.easing == null) {
			surface("select a key with an easing first");
			return;
		}
		var easing = cloneEasing(selected.key.easing);
		(tab.keys[name] || []).forEach(function (key) {
			key.easing = cloneEasing(easing);
		});
		commit(tab);
		structural(tab);
		refreshCurve(tab);
	}

	/** Whether a timeline key dot is currently selected (the Delete/Backspace target). */
	function hasSelectedKey() {
		return Boolean(selected && selected.key);
	}

	/** Re-render the timeline (and the inspector, whose slot state may have changed). */
	function refreshAfterKeyEdit() {
		refreshCurve(tabFromSelection());
		if (root.VFXEditor && typeof root.VFXEditor.renderInspector === "function") {
			root.VFXEditor.renderInspector();
		}
	}

	/** The tab a selected key belongs to (the clock's tab, else the rendered one). */
	function tabFromSelection() {
		return clock.tab || (ui && ui.tab) || (curveOpen && curveOpen.tab) || null;
	}

	/**
	 * Delete the selected timeline key. Removing a track's last key returns the slot to an inline
	 * constant (design §15.1). Routes through snapshot undo + a structural recompile (item 2).
	 *
	 * @returns {boolean} true when a key was removed
	 */
	function deleteSelectedKey() {
		if (!selected || !selected.key) {
			return false;
		}
		var tab = tabFromSelection();
		var name = selected.name;
		var key = selected.key;
		if (!tab || !tab.keys || !Array.isArray(tab.keys[name])) {
			return false;
		}
		var list = tab.keys[name];
		var index = list.indexOf(key);
		if (index < 0) {
			return false;
		}
		var lastValue = Number(key.value) || 0;
		list.splice(index, 1);
		selected = null;
		if (list.length === 0) {
			returnToInline(tab, name, lastValue);
		}
		commit(tab);
		structural(tab);
		refreshAfterKeyEdit();
		return true;
	}

	/** Turn a now-empty track back into an inline constant, keeping any `multiply`. */
	function returnToInline(tab, name, value) {
		delete tab.keys[name];
		var raw = tab.params ? tab.params[name] : null;
		var multiply = raw != null && typeof raw === "object" ? raw.multiply : null;
		var next = { value: value };
		if (multiply) {
			next.multiply = multiply;
		}
		tab.params[name] = next;
		if (curveOpen && curveOpen.name === name) {
			curveOpen = null;
		}
	}

	/** Remove a param's whole track, keeping its last value as the inline constant (item 2). */
	function removeTrack(tab, name) {
		if (!tab || !tab.keys || !Array.isArray(tab.keys[name]) || tab.keys[name].length === 0) {
			return;
		}
		var list = tab.keys[name];
		var lastValue = Number(list[list.length - 1].value) || 0;
		selected = null;
		returnToInline(tab, name, lastValue);
		commit(tab);
		structural(tab);
		refreshAfterKeyEdit();
	}

	/** Right-click a key: a small menu to delete it, edit its easing, or drop the whole track. */
	function openKeyMenu(tab, name, key, dot, event) {
		if (!root.VFXEditor || typeof root.VFXEditor.showMenu !== "function") {
			return;
		}
		event.preventDefault();
		event.stopPropagation();
		selectKey(name, key, dot);
		root.VFXEditor.showMenu([
			{ label: "Delete key", action: function () {
				selectKey(name, key, dot);
				deleteSelectedKey();
			} },
			{ label: "Edit easing", action: function () {
				selectKey(name, key, dot);
				if (ui && ui.easing) {
					ui.easing.focus();
				}
			} },
			{ label: "Remove whole track", action: function () { removeTrack(tab, name); } }
		], event);
	}

	function now() {
		return root.performance && typeof root.performance.now === "function"
				? root.performance.now() : Date.now();
	}

	/**
	 * The playhead's elapsed ticks: the live clock while playing, else the parked position. Always
	 * clamped to `[0, duration]` so a frame that runs past the end (or a seek) can never report a
	 * time outside the lane, which is what makes a `K` key land on the visible playhead.
	 */
	function elapsedNow() {
		var raw = clock.playing
				? clock.baseElapsed + (now() - clock.startMs) / MS_PER_TICK
				: clock.baseElapsed;
		return clampToDuration(clock.tab, raw);
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
		var t = clampToDuration(clock.tab, ticks);
		clock.baseElapsed = clock.playing ? clock.baseElapsed : t;
		// Keep the inspector's live numeric readout in step with the playhead (item 3): pure IR math,
		// no game request.
		if (root.VFXInspector && typeof root.VFXInspector.refreshLiveValues === "function") {
			root.VFXInspector.refreshLiveValues();
		}
		if (!ui) {
			return;
		}
		var x = LABEL_COLUMN + t * ui.pxPerTick;
		if (ui.head) {
			ui.head.style.left = x + "px";
		}
		if (ui.readout) {
			ui.readout.textContent = Math.round(t) + " / " + Math.round(duration(ui.tab)) + " t";
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

	/** The params that deserve a lane: a non-empty `Tab.keys[name]` (a track). */
	function animatedParams(tab) {
		var names = [];
		var keys = tab && tab.keys ? tab.keys : {};
		Object.keys(keys).forEach(function (name) {
			if (keys[name] && keys[name].length) {
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

	/** The value range a lane maps vertically: auto-fit to the keys, widened to include the finite
	 *  schema min/max (design §15.4) so an out-of-schema key stays visible (and is red-flagged). */
	function valueRange(tab, name, keys) {
		var descriptor = paramDescriptor(tab, name);
		var lo = Infinity;
		var hi = -Infinity;
		(keys || []).forEach(function (key) {
			var value = Number(key.value);
			if (isFinite(value)) {
				lo = Math.min(lo, value);
				hi = Math.max(hi, value);
			}
		});
		if (descriptor) {
			if (isFiniteNumber(descriptor.min)) {
				lo = Math.min(lo, descriptor.min);
			}
			if (isFiniteNumber(descriptor.max)) {
				hi = Math.max(hi, descriptor.max);
			}
		}
		if (!isFinite(lo) || !isFinite(hi) || lo === hi) {
			lo = isFinite(lo) ? lo - 1 : 0;
			hi = isFinite(hi) ? hi + 1 : 1;
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
		dot.style.left = LABEL_COLUMN + Math.max(0, Number(key.time) || 0) * ui.pxPerTick + "px";
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

	/** Drag a key dot: x -> time (ticks, clamped to [0, duration]), y -> value; commits on mouseup. */
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
			var total = duration(tab);
			var descriptor = paramDescriptor(tab, name);
			var moved = false;

			function move(ev) {
				moved = true;
				var dTicks = (ev.clientX - startX) / ui.pxPerTick;
				var dValue = -((ev.clientY - startY) / (INNER || 1)) * (range.hi - range.lo);
				var rawTime = Math.round(t0 + dTicks);
				// Keys outside [0, duration] are invisible to the engine (design §15.4, I3): clamp.
				var clamped = Math.max(0, Math.min(total, rawTime));
				dot.classList.toggle("vfx-tl-clamped", rawTime !== clamped);
				key.time = clamped;
				key.value = round3(v0 + dValue);
				positionKey(dot, name, key);
				markOutOfRange(dot, descriptor, key.value);
				if (ui && ui.easingRow && !ui.easingRow.hidden && ui.easingLabel) {
					ui.easingLabel.textContent = name + " @" + key.time;
				}
			}

			function up() {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
				if (moved) {
					// A drag can move a key across its neighbours; re-sort before the hot edit so the
					// stored track stays the time-ordered one the readout/curve assume (review G2).
					sortTrack(tab, name);
					setKeyframe(tab, name, key);
					commit(tab);
				}
			}

			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
		};
	}

	/** Red-flag a value outside the descriptor's min/max with an explanatory tooltip (§15.4). */
	function markOutOfRange(dot, descriptor, value) {
		if (dot.__baseTitle == null) {
			dot.__baseTitle = dot.title;
		}
		var out = descriptorOutOfRange(descriptor, value);
		dot.classList.toggle("vfx-tl-out", out);
		if (out) {
			dot.title = "value " + value + " is outside the schema range ["
					+ descriptor.min + ", " + descriptor.max + "]";
		} else {
			dot.title = dot.__baseTitle || "";
		}
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
		// The label gutter is not part of the time axis, so remove it before dividing.
		var x = clientX - rect.left + ui.scroller.scrollLeft - LABEL_COLUMN;
		return Math.max(0, x / ui.pxPerTick);
	}

	/** Whether a viewport x lands in the fixed label gutter, left of the time axis (minor). */
	function inLabelGutter(clientX) {
		if (!ui || !ui.scroller) {
			return false;
		}
		var rect = ui.scroller.getBoundingClientRect();
		return clientX - rect.left + ui.scroller.scrollLeft < LABEL_COLUMN;
	}

	/**
	 * Scrub the playhead by dragging the track background (never a key dot or a lane). The preview
	 * is paused for the drag (so the game holds its frame) and restored on release (design §16.6).
	 */
	function beginScrub(tab) {
		return function (event) {
			// A mousedown on a lane is the add-key gesture, not a scrub (fix 4).
			if (event.target && event.target.closest && event.target.closest(".vfx-tl-lane")) {
				return;
			}
			event.preventDefault();
			var wasPlaying = clock.playing;
			if (wasPlaying) {
				previewTransport("pause");
				stopClock();
			}
			function move(ev) {
				scrub(tab, tickAt(ev.clientX));
			}
			function up(ev) {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
				var target = clampToDuration(tab, tickAt(ev.clientX));
				previewTransport("seek", { tick: target });
				if (wasPlaying) {
					play(tab);
				}
			}
			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
			scrub(tab, tickAt(event.clientX));
		};
	}

	/** The current value to pin for a new key: the lane's last key, else the schema default. */
	function laneValue(tab, name) {
		var keys = (tab.keys && tab.keys[name]) || [];
		var last = keys.length ? keys[keys.length - 1] : null;
		if (last && isFiniteNumber(Number(last.value))) {
			return Number(last.value);
		}
		var descriptor = paramDescriptor(tab, name);
		return descriptor && isFiniteNumber(descriptor.defaultValue) ? descriptor.defaultValue : 0;
	}

	/** Append a key, select it, re-render the panel and recompile. */
	function addKeyToLane(tab, name, time, value, panel) {
		// A double-click on a lane (open the curve) fires two clicks, so a tick that already has a
		// key selects the existing one instead of stacking a duplicate on the same tick.
		var existing = tab.keys && tab.keys[name] ? tab.keys[name] : [];
		for (var i = 0; i < existing.length; i++) {
			if (Math.round(Number(existing[i].time) || 0) === Math.round(Number(time) || 0)) {
				selectKey(name, existing[i], null);
				if (panel && panel.parentNode) {
					renderInto(panel.parentNode, tab);
				}
				return existing[i];
			}
		}
		var key = tab.setKey ? tab.setKey(name, time, value) : tab.addKey(name, time, value);
		selectKey(name, key, null);
		commit(tab);
		if (panel && panel.parentNode) {
			renderInto(panel.parentNode, tab);
		} else if (root.VFXEditor && typeof root.VFXEditor.renderTimeline === "function") {
			root.VFXEditor.renderTimeline();
		}
		structural(tab);
		return key;
	}

	/**
	 * Click a lane (not an existing key) to add a key at the clicked tick, pinned to the lane's
	 * current value and selected. A drag is left to the background scrub, so only a click (<3 px of
	 * movement between down and up) adds a key. The add is deferred briefly so the double-click that
	 * opens the curve editor cancels it instead of also dropping a stray key.
	 */
	function beginLaneAdd(tab, name, panel) {
		return function (event) {
			if (event.target && event.target.classList
					&& event.target.classList.contains("vfx-tl-key")) {
				return;
			}
			// The param label sits in the fixed gutter and is `pointer-events:none`, so a click on it
			// lands on the lane and would otherwise add a key at tick 0 — ignore the gutter (minor).
			if (inLabelGutter(event.clientX)) {
				return;
			}
			// The second click of a double-click must not schedule its own add.
			if (event.detail > 1) {
				return;
			}
			// A lane mousedown is its own gesture: keep it from also reaching the scroller's scrub
			// handler, so a lane click adds one key and never scrubs/plays (fix 4).
			event.preventDefault();
			event.stopPropagation();
			var startX = event.clientX;
			var startY = event.clientY;
			function up(ev) {
				document.removeEventListener("mouseup", up);
				if (Math.abs(ev.clientX - startX) > 3 || Math.abs(ev.clientY - startY) > 3) {
					return;
				}
				var time = Math.round(clampToDuration(tab, tickAt(ev.clientX)));
				var value = laneValue(tab, name);
				cancelPendingLaneAdd();
				pendingLaneAdd = {
					timer: setTimeout(function () {
						pendingLaneAdd = null;
						addKeyToLane(tab, name, time, value, panel);
					}, 220)
				};
			}
			document.addEventListener("mouseup", up);
		};
	}

	/** Drop a deferred lane key add (a double-click beat the single click to the event). */
	function cancelPendingLaneAdd() {
		if (pendingLaneAdd) {
			clearTimeout(pendingLaneAdd.timer);
			pendingLaneAdd = null;
		}
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
		bar.appendChild(button("▶", "Play (Space)", false, function () { play(tab); }));
		// R11: the bridge accepts /transport pause but there is no game pause - disable, don't fake.
		bar.appendChild(button("⏸", "Pause is not supported by VFXweaver — use Stop", true, null));
		bar.appendChild(button("■", "Stop (Space)", false, function () { stop(tab); }));
		bar.appendChild(button("⟲", "Replay from the start (R)", false, function () { play(tab); }));

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
		// A numeric (hand-edited) bezier has no named option; a hidden-in-disabled `custom` entry
		// lets the select show it without clobbering it on open (design §15.4).
		var custom = el("option", { value: "__custom__", text: "custom" });
		custom.disabled = true;
		easing.appendChild(custom);
		easing.addEventListener("change", function () {
			if (!selected || !selected.key || easing.value === "__custom__") {
				return;
			}
			selected.key.easing = easing.value;
			commit(tab);
			structural(tab);
			if (curveOpen && curveOpen.tab === tab) {
				refreshCurve(tab);
			}
		});
		easingRow.appendChild(easingLabel);
		easingRow.appendChild(easing);
		easingRow.hidden = !(selected && selected.key);
		if (selected && selected.key) {
			easing.value = typeof selected.key.easing === "string"
					? selected.key.easing : "__custom__";
			easingLabel.textContent = selected.name + " @" + Math.round(Number(selected.key.time) || 0);
		}

		bar.appendChild(easingRow);
		return { bar: bar, readout: readout, easing: easing, easingRow: easingRow, easingLabel: easingLabel };
	}

	/**
	 * The tick ruler: a labelled major tick every {@code step} ticks (adaptive to the zoom) plus
	 * lighter minor ticks in between (design §15.4, fix 4).
	 */
	function ruler(tab, pxPerTick) {
		var bar = el("div", { class: "vfx-tl-ruler" });
		var total = duration(tab);
		// Labelled major ticks never crowd below ~5 ticks (design §15.4); minor ticks subdivide.
		var steps = [5, 10, 20, 50, 100, 200, 500];
		var step = steps[steps.length - 1];
		for (var i = 0; i < steps.length; i++) {
			if (steps[i] * pxPerTick >= 60) {
				step = steps[i];
				break;
			}
		}
		var minor = step / 5;
		if (minor * pxPerTick < 6) {
			minor = 0;
		}
		if (minor > 0) {
			for (var t = 0; t <= total; t += minor) {
				var minorMark = el("span", { class: "vfx-tl-tick-minor" });
				minorMark.style.left = LABEL_COLUMN + t * pxPerTick + "px";
				bar.appendChild(minorMark);
			}
		}
		for (var tick = 0; tick <= total; tick += step) {
			var mark = el("span", { class: "vfx-tl-tick", text: String(tick) });
			mark.style.left = LABEL_COLUMN + tick * pxPerTick + "px";
			bar.appendChild(mark);
		}
		return bar;
	}

	/**
	 * The duration bar: spans 0..duration with a draggable end handle that edits `tab.duration`
	 * (design §15.4, fix 4), snapping to whole ticks and clamped to at least one tick.
	 */
	function durationBar(tab, pxPerTick) {
		var bar = el("div", { class: "vfx-tl-duration" });
		var total = duration(tab);
		var fill = el("div", { class: "vfx-tl-duration-fill" });
		fill.style.left = LABEL_COLUMN + "px";
		fill.style.width = total * pxPerTick + "px";
		var handle = el("div", { class: "vfx-tl-duration-handle" });
		handle.style.left = LABEL_COLUMN + total * pxPerTick + "px";
		handle.title = "Drag to change the effect duration (currently " + Math.round(total) + " ticks)";
		handle.addEventListener("mousedown", function (event) {
			event.preventDefault();
			event.stopPropagation();
			function move(ev) {
				var ticks = Math.max(1, Math.round(tickAt(ev.clientX)));
				tab.duration = ticks;
				handle.style.left = LABEL_COLUMN + ticks * pxPerTick + "px";
				fill.style.width = ticks * pxPerTick + "px";
				if (ui && ui.content) {
					ui.content.style.width = LABEL_COLUMN + Math.max(1, ticks * pxPerTick + 20) + "px";
				}
				setPlayhead(elapsedNow());
			}
			function up() {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
				commit(tab);
				structural(tab);
				var panel = bar.closest ? bar.closest(".vfx-tl") : null;
				if (panel && panel.parentNode) {
					renderInto(panel.parentNode, tab);
				}
			}
			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
		});
		bar.appendChild(fill);
		bar.appendChild(handle);
		return bar;
	}

	/* ------------------------------------------------------------------ */
	/* Curve editor (design §15.4, fix #13)                               */
	/* ------------------------------------------------------------------ */

	/** Open a track's curve editor and re-render. */
	function openCurve(tab, name, panel) {
		curveOpen = { tab: tab, name: name };
		curvePanel = panel;
		renderInto(panel.parentNode, tab);
	}

	/** The display label of an IR segment easing: a name, a snapped bezier's name, else numbers. */
	function easingTitle(easing) {
		if (typeof easing === "string") {
			return easing;
		}
		if (Array.isArray(easing)) {
			var snapped = snapEasingName(easing);
			return snapped ? snapped : "cubic-bezier(" + easing.join(", ") + ")";
		}
		return "linear";
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

	/** Whether a value is outside the schema descriptor's finite min/max (design §15.4). */
	function descriptorOutOfRange(descriptor, value) {
		return Boolean(descriptor && ((isFiniteNumber(descriptor.min) && value < descriptor.min)
				|| (isFiniteNumber(descriptor.max) && value > descriptor.max)));
	}

	/** The axis a curve view maps values into: the auto-fit range plus 8% headroom for overshoot. */
	function curveAxis(range) {
		var span = (range.hi - range.lo) || 1;
		return { lo: range.lo - span * 0.08, hi: range.hi + span * 0.08 };
	}

	/** An SVG `<title>` tooltip. */
	function svgTitle(text) {
		var node = svgEl("title", {});
		node.textContent = String(text);
		return node;
	}

	/** A small header button for the curve view. */
	function curveButton(label, title, onClick) {
		var node = el("button", { type: "button", class: "vfx-tl-btn vfx-tl-curve-btn", text: label, title: title });
		node.addEventListener("click", onClick);
		return node;
	}

	/** Keep a track's key list time-sorted (the curve view and the engine both assume it). */
	function sortTrack(tab, name) {
		if (tab.keys && tab.keys[name]) {
			tab.keys[name].sort(function (a, b) {
				return (Number(a.time) || 0) - (Number(b.time) || 0);
			});
		}
	}

	/** A lane's header: the param name plus a button that opens the curve view. */
	function laneLabel(tab, name, panel) {
		var label = el("span", { class: "vfx-tl-label" });
		label.appendChild(el("span", { text: name }));
		var open = el("button", { type: "button", class: "vfx-tl-curve-open", text: "curve",
			title: "Open the curve editor" });
		open.addEventListener("mousedown", function (event) {
			event.stopPropagation();
		});
		open.addEventListener("click", function (event) {
			event.preventDefault();
			event.stopPropagation();
			openCurve(tab, name, panel);
		});
		label.appendChild(open);
		return label;
	}

	/** One tangent handle of a segment's local unit box; drag edits x (clamped [0,1]) and y (free). */
	function curveHandle(which, key, bez, hx, hy, tab, name, range, pxPerTick, panel) {
		var h = svgEl("circle", { class: "vfx-tl-handle", cx: hx, cy: hy, r: 4 });
		h.appendChild(svgTitle(which === 0
				? "Easing handle 1 (x1,y1) — drag; release near a named easing to snap"
				: "Easing handle 2 (x2,y2)"));
		h.addEventListener("mousedown", beginHandleDrag(tab, name, key, bez, which, range, pxPerTick, panel));
		return h;
	}

	/** Drag a curve key: x -> tick (clamped), y -> value; one commit per drag (fix #13). */
	function beginCurveKeyDrag(tab, name, key, dot, range, pxPerTick, panel) {
		return function (event) {
			event.preventDefault();
			event.stopPropagation();
			selectKey(name, key, dot);
			var startX = event.clientX;
			var startY = event.clientY;
			var t0 = Number(key.time) || 0;
			var v0 = Number(key.value) || 0;
			var total = duration(tab);
			var axis = curveAxis(range);
			var innerH = CURVE_HEIGHT - CURVE_PAD_TOP - CURVE_PAD_BOTTOM;
			var moved = false;

			function move(ev) {
				moved = true;
				var rawTime = Math.round(t0 + (ev.clientX - startX) / pxPerTick);
				key.time = Math.max(0, Math.min(total, rawTime));
				key.value = round3(v0 - (ev.clientY - startY) / (innerH || 1) * (axis.hi - axis.lo));
				refreshCurve(tab);
			}

			function up() {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
				if (!moved) {
					return;
				}
				sortTrack(tab, name);
				setKeyframe(tab, name, key);
				commit(tab);
				structural(tab);
				refreshCurve(tab);
			}

			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
		};
	}

	/** Drag a segment's tangent handle in its local unit box; snap to a named easing on release. */
	function beginHandleDrag(tab, name, key, bez, which, range, pxPerTick, panel) {
		return function (event) {
			event.preventDefault();
			event.stopPropagation();
			var startX = event.clientX;
			var startY = event.clientY;
			var orig = bez.slice();
			var b = bez.slice();
			var list = sortedKeys(tab, name);
			var next = list[list.indexOf(key) + 1];
			if (!next) {
				return;
			}
			var segW = ((Number(next.time) || 0) - (Number(key.time) || 0)) * pxPerTick;
			var innerH = CURVE_HEIGHT - CURVE_PAD_TOP - CURVE_PAD_BOTTOM;
			var axis = curveAxis(range);
			// The handle is drawn at `v0 + (v1 - v0) * bez`, so a vertical drag must be scaled by the
			// value span (guarded against v1 === v0) as well as the axis range — see
			// `VFXIR.bezierHandleDeltaY` (design §15.4).
			var valueSpan = (Number(next.value) || 0) - (Number(key.value) || 0);
			var v0 = Number(key.value) || 0;
			var v1 = Number(next.value) || 0;
			var moved = false;

			function move(ev) {
				moved = true;
				var xi = which * 2;
				// Always recompute from the drag's starting bezier with the *cumulative* pointer
				// delta, never off the already-moved `b`: adding the cumulative delta to a mutated
				// value double-counts each frame and launches the handle off-screen (fix H2).
				var candidate = orig.slice();
				if (segW !== 0) {
					candidate[xi] = orig[xi] + (ev.clientX - startX) / segW;
				}
				if (valueSpan !== 0) {
					var dy = ev.clientY - startY;
					var delta = root.VFXIR && typeof root.VFXIR.bezierHandleDeltaY === "function"
							? root.VFXIR.bezierHandleDeltaY(dy, innerH, valueSpan, axis.hi - axis.lo)
							: dy / (innerH || 1) * ((axis.hi - axis.lo) / valueSpan);
					candidate[xi + 1] = orig[xi + 1] - delta;
				}
				// Keep the handle inside the visible curve viewport (x in the segment box, y within
				// the value axis) so it stays on-screen and grabbable (fix H2).
				b = root.VFXIR && typeof root.VFXIR.clampBezierHandle === "function"
						? root.VFXIR.clampBezierHandle(candidate, which, v0, v1, axis.lo, axis.hi)
						: candidate;
				key.easing = [b[0], b[1], b[2], b[3]];
				refreshCurve(tab);
			}

			function up() {
				document.removeEventListener("mousemove", move);
				document.removeEventListener("mouseup", up);
				if (!moved) {
					return;
				}
				var snapped = snapEasingName(b);
				key.easing = snapped
						? snapped : [round3(b[0]), round3(b[1]), round3(b[2]), round3(b[3])];
				commit(tab);
				structural(tab);
				refreshCurve(tab);
				surface("easing: " + (snapped ? snapped : "custom bezier"));
			}

			document.addEventListener("mousemove", move);
			document.addEventListener("mouseup", up);
		};
	}

	/**
	 * Build the full curve view for one track (design §15.4, fix #13): the slot's keys, the eased
	 * polyline sampled through each segment's cubic-bezier, draggable tangent handles, draggable
	 * keys (x snaps to ticks), value-axis gridlines/labels, and the header operations.
	 *
	 * @param {Object} tab the tab
	 * @param {string} name the animated param
	 * @param {number} pxPerTick horizontal scale (shared with the timeline)
	 * @param {Object} range `{lo,hi}` from {@link valueRange}
	 * @param {HTMLElement} panel the mounted `.vfx-tl` (for re-renders)
	 * @returns {HTMLElement} the curve view element
	 */
	function curveEditor(tab, name, pxPerTick, range, panel) {
		var wrap = el("div", { class: "vfx-tl-curve" });
		var total = duration(tab);
		var keys = sortedKeys(tab, name);
		var axis = curveAxis(range);
		var innerH = CURVE_HEIGHT - CURVE_PAD_TOP - CURVE_PAD_BOTTOM;
		var width = LABEL_COLUMN + Math.max(1, total * pxPerTick + 20);

		function X(t) {
			return LABEL_COLUMN + Math.max(0, Number(t) || 0) * pxPerTick;
		}
		function Y(v) {
			var f = (Number(v) - axis.lo) / ((axis.hi - axis.lo) || 1);
			return CURVE_PAD_TOP + (1 - Math.max(0, Math.min(1, f))) * innerH;
		}

		var head = el("div", { class: "vfx-tl-curve-head" });
		head.appendChild(el("span", { class: "vfx-tl-curve-title", text: "Curve · " + name }));
		head.appendChild(curveButton("apply to all", "Copy the selected key's easing to every segment",
			function () { applyEasingToAll(tab, name); }));
		head.appendChild(curveButton("del key", "Delete the selected key (Delete)",
			function () { deleteSelectedCurveKey(tab, name); }));
		head.appendChild(curveButton("✕", "Close the curve editor", function () {
			curveOpen = null;
			curvePanel = null;
			renderInto(panel.parentNode, tab);
		}));
		wrap.appendChild(head);

		var svg = svgEl("svg", { class: "vfx-tl-curve-svg", width: width, height: CURVE_HEIGHT });

		for (var g = 0; g <= 4; g++) {
			var frac = g / 4;
			var value = range.lo + frac * (range.hi - range.lo);
			var gy = Y(value);
			svg.appendChild(svgEl("line", { class: "vfx-tl-grid", x1: 0, y1: gy, x2: width, y2: gy }));
			var label = svgEl("text", { class: "vfx-tl-axis-label", x: 4, y: gy - 2 });
			label.textContent = formatValue(value);
			svg.appendChild(label);
		}

		for (var i = 0; i < keys.length - 1; i++) {
			var a = keys[i];
			var b = keys[i + 1];
			var t0 = Number(a.time) || 0;
			var t1 = Number(b.time) || 0;
			var v0 = Number(a.value) || 0;
			var v1 = Number(b.value) || 0;
			var bez = easingBezier(a.easing);
			var pts = [];
			for (var s = 0; s <= CURVE_SAMPLES; s++) {
				var u = s / CURVE_SAMPLES;
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
				svg.appendChild(curveHandle(0, a, bez, h1x, h1y, tab, name, range, pxPerTick, panel));
				svg.appendChild(curveHandle(1, a, bez, h2x, h2y, tab, name, range, pxPerTick, panel));
			}
		}

		keys.forEach(function (key) {
			var dot = svgEl("circle", { class: "vfx-tl-curve-key",
				cx: X(key.time), cy: Y(key.value), r: 5 });
			if (selected && selected.key === key) {
				dot.classList.add("active");
			}
			var descriptor = paramDescriptor(tab, name);
			dot.appendChild(svgTitle(descriptorOutOfRange(descriptor, key.value)
					? "value " + key.value + " is outside the schema range ["
							+ descriptor.min + ", " + descriptor.max + "]"
					: name + " t=" + Math.round(Number(key.time) || 0) + " v=" + key.value
							+ " (" + easingTitle(key.easing) + ")"));
			if (descriptorOutOfRange(descriptor, key.value)) {
				dot.classList.add("vfx-tl-out");
			}
			dot.addEventListener("mousedown", beginCurveKeyDrag(tab, name, key, dot, range, pxPerTick, panel));
			dot.addEventListener("contextmenu", function (event) {
				openKeyMenu(tab, name, key, dot, event);
			});
			svg.appendChild(dot);
		});

		svg.addEventListener("dblclick", function (event) {
			var target = event.target;
			if (target && target.classList && (target.classList.contains("vfx-tl-curve-key")
					|| target.classList.contains("vfx-tl-handle"))) {
				return;
			}
			var t = Math.round(clampToDuration(tab, tickAt(event.clientX)));
			addKeyToLane(tab, name, t, interpolatedValue(tab, name, t), panel);
		});

		wrap.appendChild(svg);

		// Delete removes the selected key; Escape closes the view (design §15.4).
		curveKeyHandler = function (event) {
			// Never steal Delete/Backspace/Escape from a text field: the global hotkey is capture and
			// skips editable targets, but this bubble listener would otherwise delete a key (review G3).
			if (isEditable(event.target)) {
				return;
			}
			if (event.key === "Delete" || event.key === "Backspace") {
				if (selected && selected.key) {
					event.preventDefault();
					deleteSelectedCurveKey(tab, name);
				}
			} else if (event.key === "Escape") {
				curveOpen = null;
				curvePanel = null;
				renderInto(panel.parentNode, tab);
			}
		};
		if (typeof document !== "undefined") {
			document.addEventListener("keydown", curveKeyHandler);
		}
		return wrap;
	}

	/**
	 * Build the timeline panel for one tab. Returns a detached element the shell mounts; if the same
	 * clock is still running for this tab, the playhead element is re-bound and keeps advancing.
	 *
	 * @param {Object|null} tab the active `VFXIR.Tab` (may be null)
	 * @returns {HTMLElement} the panel
	 */
	/**
	 * One Gantt row per chain node (design §15.4, deferred from P5 to P6): a bar from
	 * `delay` to `delay + duration` (or the tab duration when the node inherits it), so the
	 * application order and any overlap are visible. Shown only for a real chain (>= 2 nodes).
	 */
	function gantt(tab, pxPerTick) {
		var wrap = el("div", { class: "vfx-tl-gantt" });
		var total = duration(tab);
		(tab.effects || []).forEach(function (effect, index) {
			var row = el("div", { class: "vfx-tl-gantt-row" });
			var name = el("span", { class: "vfx-tl-gantt-name",
				text: (index + 1) + ". " + (effect.type || "—") });
			var track = el("div", { class: "vfx-tl-gantt-track" });
			var delay = Math.max(0, Number(effect.delay) || 0);
			var span = Number(effect.duration) > 0 ? Number(effect.duration) : total;
			var bar = el("div", { class: "vfx-tl-gantt-bar" });
			bar.style.left = (delay * pxPerTick) + "px";
			bar.style.width = Math.max(2, span * pxPerTick) + "px";
			bar.title = (effect.type || "") + " — delay " + delay + "t, duration " + span + "t";
			if (effect.mute) {
				bar.classList.add("muted");
			}
			if (index === tab.activeEffect) {
				bar.classList.add("active");
			}
			track.appendChild(bar);
			row.appendChild(name);
			row.appendChild(track);
			wrap.appendChild(row);
		});
		return wrap;
	}

	function render(tab) {
		if (clock.tab && clock.tab !== tab) {
			stopClock();
			clock.baseElapsed = 0;
		}
		// Keep the clock's tab current so the playhead clamps to *this* tab's duration.
		clock.tab = tab;
		// Drop the previous curve view's key listener; a curve view re-adds its own below.
		if (curveKeyHandler && typeof document !== "undefined") {
			document.removeEventListener("keydown", curveKeyHandler);
			curveKeyHandler = null;
		}
		var panel = el("div", { class: "vfx-tl" });
		// A selected key belongs to one tab: after a tab switch (or an undo) it is stale, and a stale
		// selection would make the Delete hotkey consume the press without deleting (review G1).
		if (selected && selected.key
				&& !(tab && tab.keys && tab.keys[selected.name]
						&& tab.keys[selected.name].indexOf(selected.key) >= 0)) {
			selected = null;
		}
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
		content.style.width = LABEL_COLUMN + Math.max(1, total * pxPerTick + 20) + "px";
		scroller.appendChild(content);

		if (names.length === 0) {
			content.appendChild(el("p", { class: "vfx-tl-hint", text: "No animated parameters — add keyframes in the inspector" }));
		}

		content.appendChild(ruler(tab, pxPerTick));
		content.appendChild(durationBar(tab, pxPerTick));
		if (tab.effects && tab.effects.length >= 2) {
			content.appendChild(gantt(tab, pxPerTick));
		}
		// Curve view (design §15.4, fix #13) replaces the lane list while a track is expanded.
		var curveActive = curveOpen && curveOpen.tab === tab
				&& tab.keys && tab.keys[curveOpen.name] && tab.keys[curveOpen.name].length > 0;
		if (curveOpen && curveOpen.tab === tab && !curveActive) {
			curveOpen = null;
		}
		if (curveActive) {
			curvePanel = panel;
			content.appendChild(curveEditor(tab, curveOpen.name, pxPerTick, ranges[curveOpen.name], panel));
		} else {
			curvePanel = null;
			names.forEach(function (name) {
				var lane = el("div", { class: "vfx-tl-lane" });
				lane.style.height = LANE_HEIGHT + "px";
				lane.addEventListener("mousedown", beginLaneAdd(tab, name, panel));
				lane.appendChild(laneLabel(tab, name, panel));
				lane.addEventListener("dblclick", function (event) {
					event.preventDefault();
					event.stopPropagation();
					cancelPendingLaneAdd();
					openCurve(tab, name, panel);
				});
				(tab.keys[name] || []).forEach(function (key) {
					var dot = el("button", {
						type: "button",
						class: "vfx-tl-key",
						title: name + " t=" + Math.round(Number(key.time) || 0) + " v=" + key.value
								+ (key.easing ? " (" + easingTitle(key.easing) + ")" : "")
					});
					if (selected && selected.key === key) {
						dot.classList.add("active");
					}
					dot.addEventListener("mousedown", beginDrag(tab, name, key, dot));
					dot.addEventListener("contextmenu", function (event) {
						openKeyMenu(tab, name, key, dot, event);
					});
					lane.appendChild(dot);
					positionKeyLater(dot, name, key, pxPerTick, ranges);
					markOutOfRange(dot, paramDescriptor(tab, name), key.value);
					if (Number(key.time) < 0 || Number(key.time) > total) {
						dot.classList.add("vfx-tl-clamped");
					}
				});
				content.appendChild(lane);
			});
		}

		var head = el("div", { class: "vfx-tl-playhead" });
		content.appendChild(head);
		head.style.left = LABEL_COLUMN + elapsedNow() * pxPerTick + "px";

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
		dot.style.left = LABEL_COLUMN + Math.max(0, Number(key.time) || 0) * pxPerTick + "px";
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

	/* ------------------------------------------------------------------ */
	/* Game-clock follow (SSE)                                             */
	/* ------------------------------------------------------------------ */

	/**
	 * Follow the game's preview clock (design §16.6): each `preview/state` frame moves the playhead
	 * to the game tick while playing, so the browser playhead and the rendered effect stay in step.
	 * The local rAF clock keeps the motion smooth between frames; the frame re-anchors it.
	 *
	 * @param {Object} data the `preview/state` payload
	 */
	function onPreviewState(data) {
		if (!data || typeof data !== "object") {
			return;
		}
		if (typeof data.mode === "string") {
			preview.mode = data.mode;
		}
		if (typeof data.playing === "boolean") {
			preview.playing = data.playing;
		}
		if (typeof data.ended === "boolean") {
			preview.ended = data.ended;
		}
		preview.tick = Number(data.tick) || 0;
		var tab = previewTab();
		if (tab) {
			clock.tab = tab;
		}
		if (data.playing) {
			clock.playing = true;
			clock.baseElapsed = preview.tick;
			clock.startMs = now();
			setPlayhead(preview.tick);
			schedule();
		} else {
			if (clock.playing) {
				stopClock();
			}
			setPlayhead(preview.tick);
		}
	}

	/** Surface a preview error frame from the game (never silent). */
	function onPreviewError(data) {
		previewError(data && data.message ? data.message : "error");
	}

	if (root.VFXBridge && typeof root.VFXBridge.on === "function") {
		root.VFXBridge.on("preview/state", onPreviewState);
		root.VFXBridge.on("preview/error", onPreviewError);
		root.VFXBridge.on("open", function () {
			// A (re)connect reasserts the model so the game's session matches the browser's.
			previewResync(previewTab());
		});
	}

	var api = {
		render: render,
		onEdit: onEdit,
		compile: compile,
		play: play,
		pause: pause,
		stop: stop,
		seek: seek,
		scrub: scrub,
		setParam: setParam,
		setKeyframe: setKeyframe,
		structural: structural,
		scheduleReplay: scheduleReplay,
		effectId: effectId,
		hasSelectedKey: hasSelectedKey,
		deleteSelectedKey: deleteSelectedKey,
		removeTrack: removeTrack,
		isPlaying: function () {
			return clock.playing;
		},
		playhead: elapsedNow,
		duration: duration,
		clampTime: clampToDuration,
		preview: previewApi,
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

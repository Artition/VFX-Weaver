/*
 * VFXweaver visual node editor — bridge client (Task 16).
 *
 * The one place the editor talks to the game. `timeline.js`/`app.js` build request bodies and hand
 * them here; nothing else opens a socket. Three public entry points (design §7, plan Task 16):
 *
 *   connect(token)        open GET /events (SSE) with exponential-backoff reconnect
 *   post(path, body)      token-bearing JSON POST -> parsed response (or null)
 *   on(event, handler)    subscribe to a bridge event
 *
 * Events: `open` (SSE (re)connected), `disconnect` (dropped, `{delay}` until the retry),
 * `revision`/`error` (server frames, `data` = the uniform `{ok,revision,error,detail}` body),
 * `reconcile` (`{local,game}` when the reconnect probe found a diverged revision), `transport-error`
 * (a POST could not be delivered). A handler throwing never breaks the others.
 *
 * Revision ownership: the last-seen revision lives on the IR `Project` (`VFXEditor.project.revision`,
 * `ir.js`), the single source of truth. Every response stores its `revision` there, so the game's
 * value and the model's value can be compared (`reconcile`).
 *
 * Reconnect: `EventSource` reconnects on its own with a fixed delay; to run the design's 1s/2s/4s…
 * backoff we close the source on error and reopen on our own timer. Every (re)connect probes the
 * game's current revision (`POST /transport {action:"play"}` is a state-free ack that echoes it) and,
 * when it differs from ours, reasserts the model with a **full** `/compile` (never an increment).
 *
 * Same rule for undo: the command stack applies the model snapshot (`ir.js`), then `app.js` calls
 * `VFXTimeline.structural`, i.e. a full `/compile` — an undo is never sent as an incremental edit.
 *
 * Exposed as `window.VFXBridge`; the shell surfaces it as `VFXEditor.bridge`.
 */
(function () {
	"use strict";

	var root = typeof window !== "undefined" ? window
			: (typeof globalThis !== "undefined" ? globalThis : this);

	/** Reconnect backoff: first retry and the cap, doubling between (1s, 2s, 4s …). */
	var MIN_BACKOFF_MS = 1000;
	var MAX_BACKOFF_MS = 30000;
	/**
	 * Effect id for the state-free revision probe. `/transport {action:"play"}` only echoes the
	 * current revision, so a reserved id that never plays anything is exactly right.
	 */
	var PROBE_EFFECT_ID = "vfxweaver:editor_preview";

	/** The bridge token (from `?token=`/`window.VFX_TOKEN`), set by `connect`. */
	var token = "";
	/** The live SSE connection, or null while disconnected/reconnecting. */
	var source = null;
	/** The pending reconnect timer handle (0 when none). */
	var reconnectTimer = 0;
	/** Set by `disconnect`; suppresses reconnects until the next `connect`. */
	var stopped = false;
	/** Current retry delay in ms; reset to {@link MIN_BACKOFF_MS} on a successful open. */
	var backoff = MIN_BACKOFF_MS;
	/** event name -> handlers. */
	var handlers = {};

	/** Append the bridge token to a same-origin path (shared by POST and the SSE URL). */
	function url(path) {
		return token ? path + (path.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(token) : path;
	}

	/** The last-seen revision, read from the IR project (single source of truth). */
	function revision() {
		var project = root.VFXEditor && root.VFXEditor.project;
		var value = project ? Number(project.revision) : 0;
		return isFinite(value) ? value : 0;
	}

	/**
	 * Store the revision the game echoed, on the IR project, and announce a change so the shell's
	 * revision badge can follow every POST response (not only SSE frames).
	 */
	function setRevision(value) {
		var project = root.VFXEditor && root.VFXEditor.project;
		if (!project || typeof value !== "number") {
			return;
		}
		var changed = project.revision !== value;
		project.revision = value;
		if (changed) {
			emit("revision", { revision: value });
		}
	}

	/** Call every handler for an event; one failing handler never blocks the rest. */
	function emit(event, data) {
		var list = handlers[event];
		if (!list) {
			return;
		}
		for (var i = 0; i < list.length; i++) {
			try {
				list[i](data);
			} catch (error) {
				console.error("VFXBridge handler failed for " + event, error);
			}
		}
	}

	/**
	 * Subscribe to a bridge event.
	 *
	 * @param {string} event the event name (`open`, `disconnect`, `revision`, `error`, `reconcile`,
	 *        `transport-error`)
	 * @param {Function} handler called with the event data
	 */
	function on(event, handler) {
		if (typeof event !== "string" || typeof handler !== "function") {
			return;
		}
		if (!handlers[event]) {
			handlers[event] = [];
		}
		handlers[event].push(handler);
	}

	/**
	 * Token-bearing POST with a JSON body, resolving to the parsed response object.
	 *
	 * <p>Every response carries the session `revision`; it is stored here before the caller sees the
	 * result, so the next edit uses the fresh value. A dropped connection or a non-JSON body emits
	 * `transport-error` and resolves to `null` (callers already tolerate `null`).
	 *
	 * @param {string} path the bridge path, e.g. `/compile`
	 * @param {Object} body the JSON body
	 * @returns {Promise<Object|null>} the parsed response, or null when it could not be read
	 */
	function post(path, body) {
		if (typeof root.fetch !== "function") {
			emit("transport-error", "this browser has no fetch()");
			return Promise.resolve(null);
		}
		var request;
		try {
			request = root.fetch(url(path), {
				method: "POST",
				headers: { "Content-Type": "application/json" },
				body: JSON.stringify(body == null ? {} : body)
			});
		} catch (error) {
			emit("transport-error", error && error.message ? error.message : String(error));
			return Promise.resolve(null);
		}
		return request.then(function (response) {
			return response.json();
		}).then(function (result) {
			if (result && typeof result.revision === "number") {
				setRevision(result.revision);
			}
			return result;
		}).catch(function (error) {
			emit("transport-error", error && error.message ? error.message : String(error));
			return null;
		});
	}

	/** Parse one SSE `data:` frame and dispatch its `event`/`data`. */
	function handleFrame(raw) {
		var frame;
		try {
			frame = JSON.parse(raw);
		} catch (error) {
			return;
		}
		if (!frame || typeof frame !== "object") {
			return;
		}
		if (frame.data && typeof frame.data.revision === "number") {
			setRevision(frame.data.revision);
		}
		emit(frame.event || "message", frame.data);
	}

	/** Close any live source and schedule the next open with the current backoff. */
	function scheduleReconnect() {
		if (source) {
			try {
				source.close();
			} catch (error) {
				// already closed; nothing to do
			}
			source = null;
		}
		if (stopped || reconnectTimer) {
			return;
		}
		emit("disconnect", { delay: backoff });
		reconnectTimer = root.setTimeout(function () {
			reconnectTimer = 0;
			open();
		}, backoff);
		backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
	}

	/** Open (or reopen) the SSE stream. */
	function open() {
		if (stopped) {
			return;
		}
		if (typeof root.EventSource !== "function") {
			emit("transport-error", "this browser has no EventSource (SSE) support");
			return;
		}
		try {
			source = new root.EventSource(url("/events"));
		} catch (error) {
			scheduleReconnect();
			return;
		}
		source.onopen = function () {
			backoff = MIN_BACKOFF_MS;
			emit("open", {});
			reconcile();
		};
		source.onmessage = function (event) {
			handleFrame(event.data);
		};
		source.onerror = function () {
			// Take over from the browser's fixed-delay retry so the backoff is ours.
			scheduleReconnect();
		};
	}

	/** The active IR tab, when the shell is up. */
	function activeTab() {
		var project = root.VFXEditor && root.VFXEditor.project;
		return project && project.active >= 0 ? project.tabs[project.active] : null;
	}

	/**
	 * Reassert the whole model with a full `/compile` — the only resync path (design §7): after a
	 * diverged reconnect, and after an undo. Never an incremental edit.
	 *
	 * @returns {Promise<Object|null>} the compile response
	 */
	function fullCompile() {
		var tab = activeTab();
		var bodies = root.VFXTimeline && root.VFXTimeline.bodies;
		if (!tab || !tab.type || !bodies || typeof bodies.compile !== "function") {
			return Promise.resolve(null);
		}
		return post("/compile", bodies.compile(tab, "play")).then(function (result) {
			emit("recompiled", result);
			return result;
		});
	}

	/**
	 * Compare our last-seen revision with the game's and recompile on divergence. Runs on every SSE
	 * (re)connect: a lost response, a game restart or another writer can leave the two apart, and the
	 * model (the source of truth) must win.
	 *
	 * <p><b>The probe is read-only (ruling R11 / Task 8).</b> {@code /transport} with
	 * {@code action:"play"} is deliberately a no-op ack on the server: it neither touches a live
	 * effect nor bumps the revision, it only echoes the session's current {@code revision} (see
	 * {@code EditorServer.handleTransport} - play/pause return a fresh {@code EditorResult} with the
	 * unchanged revision). So it is safe to use purely to read the game's revision. We capture
	 * {@code local} *before* the probe, because {@link post} stores the echoed revision as a side
	 * effect; if the two differ the recompile path below reasserts the model.
	 */
	function reconcile() {
		var local = revision();
		post("/transport", { effectId: PROBE_EFFECT_ID, action: "play" }).then(function (result) {
			if (!result || typeof result.revision !== "number" || result.revision === local) {
				return;
			}
			emit("reconcile", { local: local, game: result.revision });
			fullCompile();
		});
	}

	/**
	 * Start the bridge: store the token and open the SSE stream.
	 *
	 * @param {string} [bridgeToken] the session token (kept when omitted)
	 */
	function connect(bridgeToken) {
		if (typeof bridgeToken === "string" && bridgeToken) {
			token = bridgeToken;
		}
		stopped = false;
		if (source || reconnectTimer) {
			return;
		}
		backoff = MIN_BACKOFF_MS;
		open();
	}

	/** Stop the bridge and cancel any pending reconnect. */
	function disconnect() {
		stopped = true;
		if (reconnectTimer) {
			root.clearTimeout(reconnectTimer);
			reconnectTimer = 0;
		}
		if (source) {
			try {
				source.close();
			} catch (error) {
				// already closed
			}
			source = null;
		}
	}

	var api = {
		connect: connect,
		disconnect: disconnect,
		post: post,
		on: on,
		url: url,
		revision: revision,
		setRevision: setRevision,
		fullCompile: fullCompile
	};

	root.VFXBridge = api;
})();

/*
 * VFXweaver visual node editor — app shell.
 *
 * This task (12) is the shell only: a token-aware bridge client, a LiteGraph
 * canvas, a tab bar (one tab = one effect) and a palette built from GET /schema.
 * The project IR, compiler, timeline and live bridge arrive in Tasks 13-16 and
 * extend `window.VFXEditor` from here.
 */
(function () {
	"use strict";

	var token = "";
	try {
		var found = /[?&]token=([^&]+)/.exec(window.location.search);
		token = found ? decodeURIComponent(found[1]) : "";
	} catch (e) {
		token = "";
	}
	if (!token && typeof window.VFX_TOKEN === "string") {
		token = window.VFX_TOKEN;
	}

	/** Append the bridge token to a same-origin URL (static assets are token-gated too). */
	function url(path) {
		return token ? path + (path.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(token) : path;
	}

	/**
	 * Token-bearing URL for a static asset. {@code EditorServer} serves assets only
	 * under {@code /assets/}; future assets (e.g. a selftest page) must use this.
	 */
	function assetUrl(path) {
		return url("/assets/" + String(path).replace(/^\//, ""));
	}

	var dom = {
		banner: document.getElementById("vfx-banner"),
		status: document.getElementById("vfx-status"),
		tabs: document.getElementById("vfx-tabs"),
		newTab: document.getElementById("vfx-new-tab"),
		types: document.getElementById("vfx-types"),
		valueTypes: document.getElementById("vfx-value-types"),
		filter: document.getElementById("vfx-filter"),
		canvas: document.getElementById("vfx-canvas"),
		wrap: document.getElementById("vfx-canvas-wrap"),
		inspector: document.getElementById("vfx-inspector"),
		timeline: document.getElementById("vfx-timeline"),
		file: document.getElementById("vfx-file"),
		projectBtn: document.getElementById("vfx-project"),
		projectName: document.getElementById("vfx-project-name"),
		projectNs: document.getElementById("vfx-project-ns"),
		tabName: document.getElementById("vfx-tab-name"),
		play: document.getElementById("vfx-play"),
		stop: document.getElementById("vfx-stop"),
		replay: document.getElementById("vfx-replay"),
		revbadge: document.getElementById("vfx-revbadge")
	};

	function showBanner(message) {
		dom.banner.textContent = message;
		dom.banner.hidden = false;
	}

	function status(message) {
		dom.status.textContent = message || "";
	}

	/**
	 * The project model (design §15.6): an object with the datapack `namespace`, a display `name`,
	 * `defaults` new tabs inherit, and the tabs themselves. `revision` is the bridge counter echoed
	 * on every reply (`bridge.js`).
	 *
	 * @type {{revision: number, name: string, namespace: string, defaults: Object, tabs: Array, active: number}}
	 */
	var project = {
		revision: 0,
		name: "Untitled project",
		namespace: "vfx_project",
		defaults: {},
		tabs: [],
		active: -1
	};

	/** The parsed GET /schema payload: `{ "<type>": { params: [...] } }`. */
	var schema = {};

	/** Group -> colour, so a node's sockets are tinted by their `group`. */
	var groupColors = {};
	var groupPalette = ["#4f9dff", "#ffb454", "#6dd36d", "#ff6b9d", "#b48cff", "#4fd6d6", "#ff8a5c"];

	function groupColor(group) {
		var key = group || "";
		if (!Object.prototype.hasOwnProperty.call(groupColors, key)) {
			groupColors[key] = groupPalette[Object.keys(groupColors).length % groupPalette.length];
		}
		return groupColors[key];
	}

	/** A LiteGraph canvas bound to the active tab's graph. */
	var scene = null;

	function activeTab() {
		return project.active >= 0 ? project.tabs[project.active] : null;
	}

	/* ------------------------------------------------------------------ */
	/* Schema -> node types                                                */
	/* ------------------------------------------------------------------ */

	/** The socket type/colour hint for a param descriptor. */
	function socketType(param) {
		return param && param.kind ? String(param.kind).toLowerCase() : "number";
	}

	/**
	 * Build a LiteGraph node constructor for one effect type: one input socket per
	 * declared param, its colour keyed to the param's `group` (a lightweight visual
	 * grouping). Value widgets and the inspector are Tasks 13-16.
	 */
	var FLOW_COLOR = "#8fb3ff";

	/**
	 * Build a LiteGraph node constructor for one effect type: a flow `in`/`out` pair (design §15.2,
	 * P6 — connect `out -> in` to chain) plus one input socket per declared param, its colour keyed
	 * to the param's `group`. The chain order is the layering order for post effects.
	 */
	function makeEffectNode(typeName, descriptor) {
		var params = (descriptor && descriptor.params) || [];

		function EffectNode() {
			var inSlot = this.addInput("in", "flow");
			if (inSlot) {
				inSlot.color_on = FLOW_COLOR;
				inSlot.color_off = FLOW_COLOR;
			}
			for (var i = 0; i < params.length; i++) {
				var param = params[i];
				var slot = this.addInput(param.name, socketType(param));
				if (slot) {
					slot.color_on = groupColor(param.group);
					slot.color_off = slot.color_on;
				}
			}
			var outSlot = this.addOutput("out", "flow");
			if (outSlot) {
				outSlot.color_on = FLOW_COLOR;
				outSlot.color_off = FLOW_COLOR;
			}
			this.properties = { effectType: typeName };
		}

		EffectNode.title = typeName;
		EffectNode.desc = "VFXweaver effect: " + typeName;
		return EffectNode;
	}

	/** Whether a canvas node is an effect node (vs a value node / group). */
	function isEffectNode(node) {
		return !!(node && node.properties && node.properties.effectType);
	}

	/** The effect nodes on a tab's canvas, in insertion order. */
	function effectNodes(tab) {
		var out = [];
		if (tab && tab.graph && tab.graph._nodes) {
			tab.graph._nodes.forEach(function (node) {
				if (isEffectNode(node)) {
					out.push(node);
				}
			});
		}
		return out;
	}

	/** The canvas node of an effect entry, or null. */
	function nodeForEffect(tab, id) {
		var found = null;
		effectNodes(tab).forEach(function (node) {
			if (node.properties.effectId === id) {
				found = node;
			}
		});
		return found;
	}

	/** The index of an effect id in `tab.effects`, or -1. */
	function indexOfEffect(tab, id) {
		for (var i = 0; i < (tab.effects || []).length; i++) {
			if (tab.effects[i].id === id) {
				return i;
			}
		}
		return -1;
	}

	/** The output slot index named `name` on `node`, or -1. */
	function outputIndex(node, name) {
		if (!node || !node.outputs) {
			return -1;
		}
		for (var i = 0; i < node.outputs.length; i++) {
			if (node.outputs[i].name === name) {
				return i;
			}
		}
		return -1;
	}

	/** Create a canvas node for one effect type (not yet added to a graph). */
	function createEffectNode(type) {
		var registered = "vfx/effect/" + type;
		if (!LiteGraph.registered_node_types[registered]) {
			return null;
		}
		return LiteGraph.createNode(registered);
	}

	/** Register one `vfx/effect/<name>` node type per schema effect type. */
	function registerNodeTypes() {
		var names = paletteTypes();
		for (var i = 0; i < names.length; i++) {
			var typeName = names[i];
			var registered = "vfx/effect/" + typeName;
			if (LiteGraph.registered_node_types[registered]) {
				continue;
			}
			LiteGraph.registerNodeType(registered, makeEffectNode(typeName, schema[typeName]));
		}
		registerValueNodeTypes();
	}

	/** Register one `vfx/value/<kind>` node type per datapack graph kind (schema-independent). */
	function registerValueNodeTypes() {
		Object.keys(VALUE_KINDS).forEach(function (kind) {
			var registered = "vfx/value/" + kind;
			if (!LiteGraph.registered_node_types[registered]) {
				LiteGraph.registerNodeType(registered, makeValueNode(kind));
			}
		});
	}

	/**
	 * The effect types the v1 palette offers, sorted. `COLLECTION` plays an `effects[]` list and has no
	 * editable canvas yet (design §13.3), so it is not a v1 type and must not present as a working one
	 * (a collection compile produces no play instance and would be reported as `game_rejected`).
	 */
	function paletteTypes() {
		return Object.keys(schema).filter(function (name) {
			return name !== "COLLECTION";
		}).sort();
	}

	/* ------------------------------------------------------------------ */
	/* Tabs                                                                */
	/* ------------------------------------------------------------------ */

	/** A started graph, so every tab (and the empty fallback) is set up the same way. */
	function newGraph() {
		var graph = new LiteGraph.LGraph();
		graph.start();
		return graph;
	}

	function newTab(name, type) {
		ensureHistoryBaseline();
		var graph = newGraph();
		// The IR `Tab` (Task 13) carries params/keys/valueNodes/edges; fall back to an
		// equivalent plain object when ir.js has not loaded (e.g. a broken asset chain).
		var tab = window.VFXIR
				? new window.VFXIR.Tab(name, null)
				: { name: name || "untitled", effects: [], activeEffect: -1, duration: 40,
					easing: "linear", params: {}, valueNodes: [], edges: [], keys: {} };
		if (project.defaults) {
			if (isFiniteNumber(project.defaults.duration)) {
				tab.duration = Number(project.defaults.duration);
			}
			if (typeof project.defaults.easing === "string" && project.defaults.easing) {
				tab.easing = project.defaults.easing;
			}
		}
		tab.graph = graph;
		tab.root = null;
		tab.__valueSeq = 0;
		wireGraphHooks(tab);
		project.tabs.push(tab);
		project.active = project.tabs.length - 1;
		if (type) {
			attachEffect(tab, type);
		}
		selectTab(project.active);
		commitHistory();
		return tab;
	}

	/**
	 * Append an effect of {@code type} to {@code tab}'s chain (design §15.2, P6): adds the model
	 * entry and its canvas node (with the flow in/out ports), then rewires the flow chain and makes
	 * it the active effect.
	 *
	 * @param {Object} tab the tab
	 * @param {string} type the schema effect type
	 * @returns {Object|null} the created canvas node
	 */
	function attachEffect(tab, type) {
		var node = createEffectNode(type);
		if (!node) {
			status("unknown effect type: " + type);
			return null;
		}
		var count = effectNodes(tab).length;
		var effect = tab.addEffect(type);
		node.pos = [80 + count * 260, 80];
		node.ignore_remove = true;
		node.__tab = tab;
		node.properties.effectId = effect.id;
		tab.graph.add(node);
		tab.activeEffect = indexOfEffect(tab, effect.id);
		tab.root = node;
		rewireChain(tab);
		return node;
	}

	/**
	 * Remove every effect node from a tab, leaving an empty (but valid) tab that can be re-typed
	 * (design §15.2, fix 1). Its params/keys go with it; the value graph is kept.
	 *
	 * @param {Object} tab the tab to empty
	 */
	function detachEffect(tab) {
		if (!tab) {
			return;
		}
		if (tab.graph) {
			effectNodes(tab).forEach(function (node) {
				// The effect nodes carry `ignore_remove` so LiteGraph's own delete never drops them;
				// lift it here for the explicit "empty this tab" path only.
				node.ignore_remove = false;
				tab.graph.remove(node);
			});
		}
		if (tab.effects) {
			tab.effects = [];
		}
		tab.activeEffect = -1;
		tab.root = null;
		tab.params = {};
		tab.keys = {};
	}

	/**
	 * The chain order implied by the canvas flow links: start at the effect node with no incoming
	 * `in` link and follow `out -> in`. Returns null when the flow graph is not a single full path
	 * (a fork, a cycle, a disconnected node), so the model order is kept instead.
	 *
	 * @param {Object} tab the tab
	 * @returns {Array<string>|null} effect ids in order, or null
	 */
	function orderEffectsFromCanvas(tab) {
		if (!tab || !tab.graph) {
			return null;
		}
		var nodes = effectNodes(tab);
		if (nodes.length < 2) {
			return null;
		}
		var next = {};
		var incoming = {};
		var byId = {};
		nodes.forEach(function (node) {
			byId[node.id] = node;
		});
		Object.keys(tab.graph.links).forEach(function (linkId) {
			var link = tab.graph.links[linkId];
			var origin = byId[link.origin_id];
			var target = byId[link.target_id];
			if (!origin || !target) {
				return;
			}
			var slot = target.inputs ? target.inputs[link.target_slot] : null;
			if (slot && slot.name === "in") {
				next[origin.properties.effectId] = target.properties.effectId;
				incoming[target.properties.effectId] = true;
			}
		});
		var head = null;
		nodes.forEach(function (node) {
			if (head == null && !incoming[node.properties.effectId]) {
				head = node.properties.effectId;
			}
		});
		if (head == null) {
			return null;
		}
		var order = [];
		var seen = {};
		var cursor = head;
		while (cursor != null && !seen[cursor]) {
			seen[cursor] = true;
			order.push(cursor);
			cursor = next[cursor] != null ? next[cursor] : null;
		}
		return order.length === nodes.length ? order : null;
	}

	/** Rewrite the flow links so they connect the effect nodes in `tab.effects` order. */
	function rewireChain(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		Object.keys(tab.graph.links).forEach(function (linkId) {
			var link = tab.graph.links[linkId];
			var target = tab.graph.getNodeById(link.target_id);
			if (target && isEffectNode(target)) {
				var slot = target.inputs ? target.inputs[link.target_slot] : null;
				if (slot && slot.name === "in") {
					tab.graph.removeLink(linkId);
				}
			}
		});
		var chain = [];
		(tab.effects || []).forEach(function (effect) {
			var node = nodeForEffect(tab, effect.id);
			if (node) {
				chain.push(node);
			}
		});
		for (var i = 0; i + 1 < chain.length; i++) {
			var out = outputIndex(chain[i], "out");
			var into = inputIndex(chain[i + 1], "in");
			if (out >= 0 && into >= 0) {
				chain[i].connect(out, chain[i + 1], into);
			}
		}
	}

	/** Make {@code effect} the effect the inspector/timeline edit and highlight its node. */
	function selectEffect(tab, effect) {
		if (!tab || !effect) {
			return;
		}
		tab.activeEffect = indexOfEffect(tab, effect.id);
		tab.root = nodeForEffect(tab, effect.id);
		inspectorName = null;
		renderInspector();
		renderTimeline();
		renderTabs();
	}

	function selectTab(index) {
		if (index < 0 || index >= project.tabs.length) {
			return;
		}
		project.active = index;
		var tab = project.tabs[index];
		if (scene) {
			scene.setGraph(tab.graph);
			scene.setDirty(true, true);
		}
		materializeGraph(tab);
		if (dom.tabName) {
			dom.tabName.value = tab.name;
		}
		renderTabs();
		status(tab.type ? tab.name : tab.name + " — choose an effect type");
		renderInspector();
		renderTimeline();
		showEmptyPlaceholder();
	}

	function closeTab(index) {
		if (index < 0 || index >= project.tabs.length) {
			return;
		}
		var wasActive = index === project.active;
		project.tabs.splice(index, 1);
		if (project.tabs.length === 0) {
			project.active = -1;
			if (scene) {
				scene.setGraph(newGraph());
			}
			if (dom.tabName) {
				dom.tabName.value = "";
			}
			renderTabs();
			status("Choose an effect type to start");
			renderInspector();
			renderTimeline();
			showEmptyPlaceholder();
			commitHistory();
			return;
		}
		if (wasActive) {
			// Select the neighbour now at this index (the next tab, or the new last).
			selectTab(Math.min(index, project.tabs.length - 1));
			commitHistory();
			return;
		}
		// Closing another tab must not move the selection.
		if (index < project.active) {
			project.active--;
		}
		if (project.active >= 0) {
			selectTab(project.active);
		} else {
			renderTabs();
			renderTimeline();
		}
		commitHistory();
	}

	function renderTabs() {
		dom.tabs.textContent = "";
		project.tabs.forEach(function (tab, index) {
			// A <div> (not a <button>) so the close <button> is not nested interactive content.
			var tabEl = document.createElement("div");
			tabEl.className = "vfx-tab" + (index === project.active ? " active" : "");
			tabEl.setAttribute("role", "tab");
			tabEl.tabIndex = 0;
			tabEl.addEventListener("click", function () {
				selectTab(index);
			});

			var label = document.createElement("span");
			label.textContent = tab.name + (tab.type ? "" : " •");
			tabEl.appendChild(label);

			var close = document.createElement("button");
			close.type = "button";
			close.className = "vfx-close";
			close.textContent = "×";
			close.title = "Close tab";
			close.addEventListener("click", function (event) {
				event.stopPropagation();
				closeTab(index);
			});
			tabEl.appendChild(close);

			dom.tabs.appendChild(tabEl);
		});
	}

	/* ------------------------------------------------------------------ */
	/* Palette                                                             */
	/* ------------------------------------------------------------------ */

	function buildPalette() {
		dom.types.textContent = "";
		paletteTypes().forEach(function (typeName) {
			var item = document.createElement("button");
			item.type = "button";
			item.className = "vfx-type";
			item.textContent = typeName;
			item.dataset.type = typeName;
			item.addEventListener("click", function () {
				chooseType(typeName);
			});
			dom.types.appendChild(item);
		});
	}

	/**
	 * Add an effect of that type to the active tab's chain (design §15.2, P6), creating a tab when
	 * there is none. The first add also names the tab after the type.
	 */
	function chooseType(typeName) {
		var tab = activeTab();
		if (!tab) {
			newTab(typeName, typeName);
			return;
		}
		ensureHistoryBaseline();
		attachEffect(tab, typeName);
		if (!tab.name || tab.name === "untitled") {
			tab.name = typeName;
			if (dom.tabName) {
				dom.tabName.value = tab.name;
			}
		}
		renderTabs();
		status(tab.name);
		renderInspector();
		renderTimeline();
		showEmptyPlaceholder();
		commitHistory();
		requestStructural(tab);
	}

	/**
	 * The canvas placeholder shown while a tab has no effect (design §15.2, fix 1): a list of the
	 * palette's effect types; clicking one fills the empty tab. Hidden whenever the active tab has an
	 * effect (or there is no tab at all).
	 */
	function showEmptyPlaceholder() {
		var wrap = dom.wrap;
		if (!wrap) {
			return;
		}
		var node = document.getElementById("vfx-empty");
		var tab = activeTab();
		var empty = !tab || !(tab.effects && tab.effects.length);
		if (!node) {
			if (!empty) {
				return;
			}
			node = document.createElement("div");
			node.id = "vfx-empty";
			node.className = "vfx-empty";
			wrap.appendChild(node);
		}
		node.hidden = !empty;
		if (!empty) {
			return;
		}
		node.textContent = "";
		var heading = document.createElement("p");
		heading.className = "vfx-empty-head";
		heading.textContent = "+ Add effect";
		node.appendChild(heading);
		var list = document.createElement("div");
		list.className = "vfx-empty-list";
		paletteTypes().forEach(function (typeName) {
			var button = document.createElement("button");
			button.type = "button";
			button.className = "vfx-type";
			button.textContent = typeName;
			button.addEventListener("click", function () {
				chooseType(typeName);
			});
			list.appendChild(button);
		});
		node.appendChild(list);
	}

	function filterPalette(needle) {
		var query = (needle || "").toLowerCase();
		var items = document.querySelectorAll("#vfx-types .vfx-type, #vfx-value-types .vfx-type");
		for (var i = 0; i < items.length; i++) {
			var label = items[i].dataset.type || items[i].dataset.value || "";
			var match = label.toLowerCase().indexOf(query) >= 0;
			items[i].style.display = match ? "" : "none";
		}
	}

	/* ------------------------------------------------------------------ */
	/* Value nodes (graph kinds)                                            */
	/* ------------------------------------------------------------------ */

	/**
	 * One entry per datapack value-graph kind (docs/guide/datapack/graph.md), fixed by the format
	 * like the compiler and therefore schema-independent. `sockets` are the numeric inputs that get
	 * both a socket and a literal widget; `defaults` seed those widgets; `required` are the inputs
	 * the game parser needs present even when untouched. `subgraph` is deliberately absent: the
	 * compiler does not copy its structural `subgraph`/`output` fields, so the UI defers it.
	 */
	var VALUE_KINDS = {
		constant: { sockets: ["value"], defaults: { value: 0 }, required: ["value"] },
		time: { sockets: ["speed", "offset"], defaults: { speed: 1, offset: 0 } },
		random: { sockets: ["min", "max", "index"], defaults: { min: 0, max: 1, index: 0 } },
		noise: { sockets: ["x", "y", "z", "scale", "octaves", "gain", "lacunarity"],
			defaults: { x: 0, y: 0, z: 0, scale: 1, octaves: 1, gain: 0.5, lacunarity: 2 } },
		curve: { sockets: ["time"], defaults: {}, curve: true },
		math: { sockets: ["a", "b"], defaults: { a: 0, b: 0 }, required: ["a", "b"],
			op: ["add", "subtract", "multiply", "divide", "min", "max", "pow", "mod"], defaultOp: "add" },
		mix: { sockets: ["a", "b", "factor"], defaults: { a: 0, b: 0, factor: 0.5 }, required: ["a", "b"] },
		clamp: { sockets: ["value", "min", "max"], defaults: { value: 0, min: 0, max: 1 }, required: ["value"] },
		remap: { sockets: ["value", "in_min", "in_max", "out_min", "out_max"],
			defaults: { value: 0, in_min: 0, in_max: 1, out_min: 0, out_max: 1 }, required: ["value"] },
		bind: { sockets: ["fallback"], defaults: { fallback: 0 }, required: [], bind: true },
		expr: { sockets: [], defaults: {}, required: [], expr: true },
		compare: { sockets: ["a", "b"], defaults: { a: 0, b: 0 }, required: ["a", "b"],
			op: ["eq", "ne", "lt", "le", "gt", "ge"], defaultOp: "lt" },
		boolean: { sockets: ["a", "b"], defaults: { a: 0, b: 0 }, required: ["a"],
			op: ["and", "or", "xor", "not"], defaultOp: "and" },
		"if": { sockets: ["condition", "then", "else"], defaults: { condition: 0, "then": 0, "else": 0 }, required: ["condition"] },
		"switch": { sockets: ["index", "default"], defaults: { index: 0, "default": 0 }, required: ["index"], switch: true }
	};

	/** True while the canvas is being rebuilt from the IR, so the change hooks stay quiet. */
	var applyingGraph = false;

	function valueNodeTypes() {
		return Object.keys(VALUE_KINDS).sort();
	}

	/** The world/camera bindings and pos-needing set the inspector already publishes. */
	function bindingNames() {
		return (window.VFXInspector && window.VFXInspector.BINDINGS) || ["health"];
	}

	function needsPosBindings() {
		return (window.VFXInspector && window.VFXInspector.NEEDS_POS) || [];
	}

	/**
	 * The unit / range a world binding yields (design §15.1, fix 6): used to type and label a `bind`
	 * value node's output. Order of the checks does not matter; each key is exact.
	 */
	var BINDING_UNITS = {
		screen_x: "UV 0..1", screen_y: "UV 0..1",
		proximity: "0..1", health: "0..1", hunger: "0..1", look: "0..1", look_at: "0..1",
		distance: "blocks",
		look_x: "-1..1", look_y: "-1..1", look_z: "-1..1",
		player_x: "world", player_y: "world", player_z: "world",
		camera_yaw_delta: "deg/tick", camera_pitch_delta: "deg/tick",
		time_of_day: "0..1", light_level: "0..1", speed: "0..1",
		scoreboard: "raw"
	};

	/** The unit label for a binding name (falls back to the raw binding name). */
	function bindingUnit(bind) {
		return BINDING_UNITS[bind] || String(bind == null ? "" : bind);
	}

	/** The label a bind node's single output carries for the chosen binding. */
	function bindOutputLabel(bind) {
		return String(bind == null ? "await" : bind) + " · " + bindingUnit(bind);
	}

	function isRef(value) {
		return value != null && typeof value === "object" && value.from != null;
	}

	/** Whether `value` is a real, finite number (delegates to the IR helper when available). */
	function isFiniteNumber(value) {
		return window.VFXIR && typeof window.VFXIR.isFiniteNumber === "function"
				? window.VFXIR.isFiniteNumber(value)
				: (typeof value === "number" && isFinite(value));
	}

	/** Parse a strict decimal number from user text, or null (delegates to the IR helper). */
	function parseNumber(text) {
		if (window.VFXIR && typeof window.VFXIR.parseNumber === "function") {
			return window.VFXIR.parseNumber(text);
		}
		var trimmed = String(text == null ? "" : text).trim();
		if (!/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(trimmed)) {
			return null;
		}
		var n = Number(trimmed);
		return isFinite(n) ? n : null;
	}

	/** A per-node numeric input: a socket that also carries a literal widget value. */
	function addNumericInput(node, name, value) {
		node.addInput(name, "number");
		node.properties.inputs[name] = value;
		var widget = node.addWidget("number", name, value, function (v) {
			node.properties.inputs[name] = v;
			node.properties.explicit[name] = true;
			scheduleGraphEdit(node.__tab);
		});
		node.__widgets[name] = widget;
	}

	/** Render a curve's points as editable lines: `time value [easing]`, one per line. */
	function pointsText(points) {
		if (!Array.isArray(points)) {
			return "";
		}
		return points.map(function (point) {
			var line = num(point.time) + " " + num(point.value);
			// A numeric bezier easing is a single JSON token (no spaces), so it stays one field.
			if (point.easing) {
				line += " " + (typeof point.easing === "object"
						? JSON.stringify(point.easing) : point.easing);
			}
			return line;
		}).join("\n");
	}

	/** Parse the curve lines back into points, or null when malformed / not strictly ascending. */
	function parsePoints(text) {
		var lines = String(text == null ? "" : text).split("\n");
		var out = [];
		for (var i = 0; i < lines.length; i++) {
			var line = lines[i].trim();
			if (!line) {
				continue;
			}
			var parts = line.split(/\s+/);
			var time = Number(parts[0]);
			var value = Number(parts[1]);
			if (!isFinite(time) || !isFinite(value)) {
				return null;
			}
			if (out.length && time <= out[out.length - 1].time) {
				return null;
			}
			var point = { time: time, value: value };
			if (parts[2]) {
				// A `{...}` token is the inline-bezier form; a bare word is a named easing.
				if (parts[2].charAt(0) === "{") {
					try {
						point.easing = JSON.parse(parts[2]);
					} catch (e) {
						return null;
					}
				} else {
					point.easing = parts[2];
				}
			}
			out.push(point);
		}
		return out.length ? out : null;
	}

	/** The bind node's bound-param spec (`bind` plus optional `pos`/`objective`). */
	function bindSpec(node) {
		if (!node.properties.bind) {
			node.properties.bind = { bind: bindingNames()[0] };
		}
		return node.properties.bind;
	}

	/** Fill the fields a binding kind requires, mirroring the inspector's bind form. */
	function applyBindDefaults(node) {
		var spec = bindSpec(node);
		if (needsPosBindings().indexOf(spec.bind) >= 0) {
			if (!Array.isArray(spec.pos)) {
				spec.pos = [0, 0, 0];
			}
		} else {
			delete spec.pos;
		}
		if (spec.bind === "scoreboard") {
			if (!spec.objective) {
				spec.objective = "objective";
			}
		} else {
			delete spec.objective;
		}
	}

	/** Keep the bind node's widgets showing its spec after an import / undo. */
	function refreshBindWidgets(node) {
		var spec = bindSpec(node);
		if (node.__widgets.bind) {
			node.__widgets.bind.value = spec.bind;
		}
		["x", "y", "z"].forEach(function (axis, index) {
			var widget = node.__widgets["pos." + axis];
			if (widget) {
				widget.value = Array.isArray(spec.pos) ? num(spec.pos[index]) : 0;
			}
		});
		if (node.__widgets.objective) {
			node.__widgets.objective.value = spec.objective == null ? "" : String(spec.objective);
		}
		refreshBindOutput(node);
	}

	/** Show the chosen binding on the node and label its output by the binding's unit (fix 6). */
	function refreshBindOutput(node) {
		if (!node || !node.__kindSpec || node.__kindSpec.bind !== true) {
			return;
		}
		var bind = bindSpec(node).bind;
		if (node.outputs && node.outputs[0]) {
			node.outputs[0].label = bindOutputLabel(bind);
			node.outputs[0].name = bindOutputLabel(bind);
		}
		node.title = "bind · " + bind;
	}

	function initCurve(node) {
		node.properties.points = [{ time: 0, value: 0 }, { time: 100, value: 1 }];
		var widget = node.addWidget("text", "points", pointsText(node.properties.points), function (v) {
			var points = parsePoints(v);
			if (points) {
				node.properties.points = points;
				scheduleGraphEdit(node.__tab);
			}
		}, { multiline: true });
		node.__widgets.points = widget;
		node.__addPoint = function () {
			var points = (node.properties.points || []).slice();
			var last = points[points.length - 1] || { time: 0, value: 0 };
			points.push({ time: num(last.time) + 10, value: num(last.value) });
			node.properties.points = points;
			widget.value = pointsText(points);
			scheduleGraphEdit(node.__tab);
		};
		node.addWidget("button", "+ point", null, function () {
			node.__addPoint();
		});
	}

	function initExpr(node) {
		node.properties.expr = "t";
		var widget = node.addWidget("text", "expr", "t", function (v) {
			node.properties.expr = v == null || v === "" ? "0" : String(v);
			scheduleGraphEdit(node.__tab);
		}, { multiline: true });
		node.__widgets.expr = widget;
	}

	function initBind(node) {
		var names = bindingNames();
		node.properties.bind = { bind: names[0] };
		var combo = node.addWidget("combo", "bind", names[0], function (v) {
			bindSpec(node).bind = v;
			applyBindDefaults(node);
			refreshBindWidgets(node);
			scheduleGraphEdit(node.__tab);
		}, { values: names });
		node.__widgets.bind = combo;
		["x", "y", "z"].forEach(function (axis, index) {
			node.__widgets["pos." + axis] = node.addWidget("number", "pos." + axis, 0, function (v) {
				var value = bindSpec(node);
				var pos = Array.isArray(value.pos) ? value.pos.slice() : [0, 0, 0];
				pos[index] = v;
				value.pos = pos;
				scheduleGraphEdit(node.__tab);
			});
		});
		node.__widgets.objective = node.addWidget("text", "objective", "", function (v) {
			bindSpec(node).objective = v;
			scheduleGraphEdit(node.__tab);
		});
		applyBindDefaults(node);
		refreshBindWidgets(node);
	}

	/** Add one `case_N` socket+widget (used for the switch kind). */
	function addSwitchCase(node, name, value) {
		addNumericInput(node, name, num(value));
		node.properties.explicit[name] = true;
	}

	function initSwitch(node) {
		addSwitchCase(node, "case_0", 0);
		node.addWidget("button", "+ case", null, function () {
			var next = 0;
			node.inputs.forEach(function (slot) {
				if (/^case_\d+$/.test(slot.name)) {
					next = Math.max(next, parseInt(slot.name.substring(5), 10) + 1);
				}
			});
			addSwitchCase(node, "case_" + next, 0);
			scheduleGraphEdit(node.__tab);
		});
	}

	/**
	 * Build a LiteGraph constructor for one value-graph kind. Every numeric input is both a socket
	 * (wire a node output in) and a widget (a literal); structural fields (`op`, `points`, `expr`,
	 * `bind`) get their own editor and never use a socket.
	 */
	function makeValueNode(kind) {
		var spec = VALUE_KINDS[kind];

		function ValueNode() {
			this.properties = { kind: kind, inputs: {}, explicit: {} };
			this.__kindSpec = spec;
			this.__widgets = {};
			var self = this;
			(spec.sockets || []).forEach(function (name) {
				addNumericInput(self, name, spec.defaults[name] == null ? 0 : spec.defaults[name]);
			});
			if (spec.op) {
				this.properties.op = spec.defaultOp;
				this.__widgets.op = this.addWidget("combo", "op", spec.defaultOp, function (v) {
					self.properties.op = v;
					scheduleGraphEdit(self.__tab);
				}, { values: spec.op });
			}
			if (spec.curve) {
				initCurve(this);
			}
			if (spec.expr) {
				initExpr(this);
			}
			if (spec.bind) {
				initBind(this);
			}
			if (spec.switch) {
				initSwitch(this);
			}
			this.addOutput("out", "*");
			if (spec.bind) {
				refreshBindOutput(this);
			}
			this.onDrawForeground = drawSlotAffordances;
			this.onMouseDown = slotFieldClick;
		}

		ValueNode.title = kind;
		ValueNode.desc = "VFXweaver value node: " + kind;
		return ValueNode;
	}

	/** A deep JSON snapshot of the tab's value graph and chain shape (for a debounced change check). */
	function snapshotIR(tab) {
		return {
			valueNodes: snapshot(tab && tab.valueNodes ? tab.valueNodes : []),
			edges: snapshot(tab && tab.edges ? tab.edges : []),
			effects: snapshot(tab && tab.effects ? tab.effects : [])
		};
	}

	/** The first input slot index named {@code name} on {@code node}, or -1. */
	function inputIndex(node, name) {
		if (!node || !node.inputs) {
			return -1;
		}
		for (var i = 0; i < node.inputs.length; i++) {
			if (node.inputs[i].name === name) {
				return i;
			}
		}
		return -1;
	}

	/** A unique value-node id (`<kind>_<n>`) for {@code tab}, avoiding imported ids. */
	function uniqueNodeId(tab, kind) {
		var used = {};
		(tab.valueNodes || []).forEach(function (node) {
			used[node.id] = true;
		});
		// Also reserve ids already on the canvas but not yet flushed into the IR.
		if (tab.graph && tab.graph._nodes) {
			tab.graph._nodes.forEach(function (node) {
				if (node.properties && node.properties.irId) {
					used[node.properties.irId] = true;
				}
			});
		}
		var seq = tab.__valueSeq || 0;
		var id;
		do {
			seq++;
			id = kind + "_" + seq;
		} while (used[id]);
		tab.__valueSeq = seq;
		return id;
	}

	/** The input names of {@code node} currently fed by a wire. */
	function wiredInputs(node) {
		var wired = {};
		var graph = node.graph;
		if (!graph || !graph.links) {
			return wired;
		}
		Object.keys(graph.links).forEach(function (linkId) {
			var link = graph.links[linkId];
			if (link.target_id === node.id) {
				var slot = node.inputs ? node.inputs[link.target_slot] : null;
				if (slot) {
					wired[slot.name] = true;
				}
			}
		});
		return wired;
	}

	/** input name -> source value-node id, for a node's wired inputs. */
	function wiredSources(node) {
		var sources = {};
		var graph = node.graph;
		if (!graph || !graph.links) {
			return sources;
		}
		Object.keys(graph.links).forEach(function (linkId) {
			var link = graph.links[linkId];
			if (link.target_id !== node.id) {
				return;
			}
			var slot = node.inputs ? node.inputs[link.target_slot] : null;
			var origin = graph.getNodeById(link.origin_id);
			if (slot && origin && origin.properties) {
				sources[slot.name] = origin.properties.irId;
			}
		});
		return sources;
	}

	/**
	 * Apply the driven→read-only rule to a value node's numeric slots (design §15.1, fix 5): a
	 * wired input hides its editable widget (drawn as a read-only value + source badge instead),
	 * an unwired one restores it. The widget is kept in place (only its type is swapped) so the
	 * node's vertical layout does not jump.
	 *
	 * @param {Object} node a canvas value node
	 */
	function applySlotState(node) {
		if (!node || !node.__kindSpec || !node.__widgets) {
			return;
		}
		var sources = wiredSources(node);
		if (!node.__driven) {
			node.__driven = {};
		}
		(node.__kindSpec.sockets || []).forEach(function (name) {
			var widget = node.__widgets[name];
			if (!widget) {
				return;
			}
			if (sources[name]) {
				if (widget.type !== "__driven") {
					widget.__savedType = widget.type;
					widget.type = "__driven";
				}
				node.__driven[name] = sources[name];
			} else if (widget.type === "__driven") {
				widget.type = widget.__savedType || "number";
				delete node.__driven[name];
			}
		});
	}

	/** Re-apply the driven rule across every value node on a tab. */
	function syncAllSlots(tab) {
		if (!tab || !tab.graph || applyingGraph) {
			return;
		}
		tab.graph._nodes.forEach(function (node) {
			if (node !== tab.root) {
				applySlotState(node);
			}
		});
	}

	/** Draw a small diamond (the slot "animate" affordance) at a widget's right edge. */
	function drawDiamond(ctx, cx, cy, color) {
		var r = 4;
		ctx.beginPath();
		ctx.moveTo(cx, cy - r);
		ctx.lineTo(cx + r, cy);
		ctx.lineTo(cx, cy + r);
		ctx.lineTo(cx - r, cy);
		ctx.closePath();
		ctx.strokeStyle = color;
		ctx.stroke();
	}

	/**
	 * Draw each value-node numeric slot (design §15.1): a driven slot shows a read-only value + its
	 * source node, every slot carries a diamond that animates it (a `curve` node wired in).
	 */
	function drawSlotAffordances(ctx, canvas) {
		var node = this;
		if (!node.__kindSpec || !node.__widgets) {
			return;
		}
		var driven = node.__driven || {};
		var scale = canvas && canvas.ds ? canvas.ds.scale : 1;
		ctx.save();
		ctx.font = (11 / scale) + "px sans-serif";
		ctx.textAlign = "left";
		(node.__kindSpec.sockets || []).forEach(function (name) {
			var widget = node.__widgets[name];
			if (!widget || widget.last_y == null) {
				return;
			}
			var y = widget.last_y;
			var height = LiteGraph.NODE_WIDGET_HEIGHT;
			if (driven[name]) {
				var value = node.properties && node.properties.inputs ? node.properties.inputs[name] : "";
				ctx.fillStyle = "#2b3550";
				ctx.strokeStyle = "#4f9dff";
				ctx.fillRect(15, y, node.size[0] - 30, height);
				ctx.strokeRect(15, y, node.size[0] - 30, height);
				ctx.fillStyle = "#cfe0ff";
				ctx.fillText(num(value) + "  <- " + name + " (" + driven[name] + ")", 20, y + height * 0.7);
			}
			drawDiamond(ctx, node.size[0] - 9, y + height / 2, driven[name] ? "#cfe0ff" : "#7f8aa3");
		});
		ctx.restore();
	}

	/** Click a value-node slot: the diamond animates it (or focuses its driver); the badge focuses. */
	function slotFieldClick(event, pos, canvas) {
		var node = this;
		if (!node.__kindSpec || !node.__widgets) {
			return false;
		}
		var driven = node.__driven || {};
		var hit = null;
		(node.__kindSpec.sockets || []).forEach(function (name) {
			var widget = node.__widgets[name];
			if (!widget || widget.last_y == null) {
				return;
			}
			var y = widget.last_y;
			var height = LiteGraph.NODE_WIDGET_HEIGHT;
			if (pos[1] < y || pos[1] > y + height) {
				return;
			}
			if (pos[0] >= node.size[0] - 18 && pos[0] <= node.size[0]) {
				hit = { name: name, diamond: true };
			} else if (driven[name] && pos[0] >= 15 && pos[0] <= node.size[0] - 15) {
				hit = { name: name, diamond: false };
			}
		});
		if (!hit) {
			return false;
		}
		if (hit.diamond && !driven[hit.name]) {
			animateField(node, hit.name);
		} else {
			focusNode(driven[hit.name]);
		}
		return true;
	}

	/** The slot name under a graph event, or null (for the field context menu). */
	function fieldAtEvent(node, event) {
		if (event.canvasX == null || event.canvasY == null) {
			return null;
		}
		var local = [event.canvasX - node.pos[0], event.canvasY - node.pos[1]];
		var hit = null;
		(node.__kindSpec.sockets || []).forEach(function (name) {
			var widget = node.__widgets[name];
			if (!widget || widget.last_y == null) {
				return;
			}
			if (local[0] >= 0 && local[0] <= node.size[0]
					&& local[1] >= widget.last_y
					&& local[1] <= widget.last_y + LiteGraph.NODE_WIDGET_HEIGHT) {
				hit = name;
			}
		});
		return hit;
	}

	/** Centre the canvas on a value node by IR id (the driven badge's "go to node"). */
	function focusNode(irId) {
		var tab = activeTab();
		if (!tab || !tab.graph || !scene) {
			return false;
		}
		var target = null;
		tab.graph._nodes.forEach(function (node) {
			if (node.properties && node.properties.irId === irId) {
				target = node;
			}
		});
		if (!target) {
			return false;
		}
		scene.selectNodes([target]);
		scene.centerOnNode(target);
		return true;
	}

	/**
	 * Read one canvas value node into the IR node shape. Wired inputs are omitted (the compiler
	 * routes them through `edges`); a numeric input is emitted only when the user set it explicitly
	 * or the game parser requires it present, so a datapack default (e.g. noise `x` = elapsed) is
	 * never overridden by an untouched widget.
	 */
	function nodeToIR(node) {
		var spec = node.__kindSpec || {};
		var wired = wiredInputs(node);
		var inputs = {};
		if (spec.curve && Array.isArray(node.properties.points)) {
			inputs.points = snapshot(node.properties.points);
		}
		if (spec.expr) {
			inputs.expr = String(node.properties.expr == null || node.properties.expr === "" ? "0" : node.properties.expr);
		}
		if (spec.bind && node.properties.bind) {
			Object.keys(node.properties.bind).forEach(function (key) {
				inputs[key] = snapshot(node.properties.bind[key]);
			});
		}
		(spec.sockets || []).forEach(function (name) {
			if (wired[name]) {
				return;
			}
			var required = (spec.required || []).indexOf(name) >= 0;
			if (node.properties.explicit[name] || required) {
				inputs[name] = num(node.properties.inputs[name]);
			}
		});
		// Dynamic switch cases live outside `sockets`.
		(node.inputs || []).forEach(function (slot) {
			if (/^case_\d+$/.test(slot.name) && !wired[slot.name] && node.properties.explicit[slot.name]) {
				inputs[slot.name] = num(node.properties.inputs[slot.name]);
			}
		});
		var ir = { id: node.properties.irId, kind: node.properties.kind, inputs: inputs };
		if (node.properties.op) {
			ir.op = node.properties.op;
		}
		if (Array.isArray(node.pos) && node.pos.length === 2) {
			ir.pos = [Math.round(node.pos[0]), Math.round(node.pos[1])];
		}
		return ir;
	}

	/**
	 * Sync the tab's effect entries with the effect nodes on the canvas (design §15.2, P6): add an
	 * entry for a node without one, drop an entry whose node is gone, and mirror each node's
	 * position onto its entry. Keeps `tab.root` on the active effect.
	 */
	function reconcileEffects(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		if (!tab.effects) {
			tab.effects = [];
		}
		var nodes = effectNodes(tab);
		// An inactive tab is materialized lazily (only the active tab's graph holds nodes), so an
		// empty canvas with a non-empty model must not be read as "every effect was deleted".
		if (nodes.length === 0 && tab.effects.length > 0) {
			return;
		}
		var present = {};
		nodes.forEach(function (node) {
			node.__tab = tab;
			node.ignore_remove = true;
			if (!node.properties.effectId) {
				node.properties.effectId = "fx_" + ((tab.__effectSeq = (tab.__effectSeq || 0) + 1));
			}
			present[node.properties.effectId] = node;
		});
		var byId = {};
		tab.effects.forEach(function (effect) {
			byId[effect.id] = effect;
		});
		for (var i = tab.effects.length - 1; i >= 0; i--) {
			if (!present[tab.effects[i].id]) {
				tab.effects.splice(i, 1);
			}
		}
		nodes.forEach(function (node) {
			if (!byId[node.properties.effectId]) {
				var effect = tab.addEffect(node.properties.effectType || node.title, node.properties.effectId);
				byId[effect.id] = effect;
			}
			var entry = byId[node.properties.effectId];
			entry.type = node.properties.effectType || entry.type;
			entry.pos = [Math.round(num(node.pos[0])), Math.round(num(node.pos[1]))];
		});
		if (tab.activeEffect >= tab.effects.length) {
			tab.activeEffect = tab.effects.length - 1;
		}
		if (tab.activeEffect < 0 && tab.effects.length) {
			tab.activeEffect = 0;
		}
		var active = tab.effect();
		tab.root = active ? present[active.id] || null : null;
	}

	/** Reorder `tab.effects` to the given id order, keeping any entry the order omitted. */
	function reorderEffects(tab, order) {
		var byId = {};
		(tab.effects || []).forEach(function (effect) {
			byId[effect.id] = effect;
		});
		var next = [];
		order.forEach(function (id) {
			if (byId[id]) {
				next.push(byId[id]);
				delete byId[id];
			}
		});
		(tab.effects || []).forEach(function (effect) {
			if (byId[effect.id]) {
				next.push(effect);
			}
		});
		tab.effects = next;
	}

	/** Rebuild `tab.effects`/`tab.valueNodes`/`tab.edges` from the canvas. */
	function refreshFromCanvas(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		reconcileEffects(tab);
		var order = orderEffectsFromCanvas(tab);
		if (order) {
			reorderEffects(tab, order);
		}
		reconcileEffects(tab);
		var irByGraphId = {};
		var nodes = [];
		tab.graph._nodes.forEach(function (node) {
			if (isEffectNode(node) || !node.properties || !node.properties.kind || !node.properties.irId) {
				return;
			}
			irByGraphId[node.id] = node.properties.irId;
			nodes.push(nodeToIR(node));
		});
		var multi = Boolean(tab.effects && tab.effects.length > 1);
		var edges = [];
		Object.keys(tab.graph.links).forEach(function (linkId) {
			var link = tab.graph.links[linkId];
			var from = irByGraphId[link.origin_id];
			if (!from) {
				return;
			}
			var target = tab.graph.getNodeById(link.target_id);
			if (!target) {
				return;
			}
			if (isEffectNode(target)) {
				var param = target.inputs ? target.inputs[link.target_slot] : null;
				if (param && param.name !== "in") {
					var edge = { from: from, to: param.name };
					if (multi) {
						edge.effect = target.properties.effectId;
					}
					edges.push(edge);
				}
			} else if (target.properties && target.properties.kind && irByGraphId[target.id]) {
				var socket = target.inputs ? target.inputs[link.target_slot] : null;
				if (socket) {
					edges.push({ from: from, to: irByGraphId[target.id], input: socket.name });
				}
			}
		});
		tab.valueNodes = nodes;
		tab.edges = edges;
	}

	/** Apply one IR node's values onto a freshly created canvas node. */
	function applyIR(node, ir) {
		var spec = node.__kindSpec || {};
		var values = ir.inputs || {};
		node.__pendingRefs = [];
		if (spec.curve && Array.isArray(values.points)) {
			node.properties.points = snapshot(values.points);
			if (node.__widgets.points) {
				node.__widgets.points.value = pointsText(node.properties.points);
			}
		}
		if (spec.expr && values.expr != null) {
			node.properties.expr = String(values.expr);
			if (node.__widgets.expr) {
				node.__widgets.expr.value = node.properties.expr;
			}
		}
		if (spec.bind) {
			node.properties.bind = {};
			if (values.bind != null) {
				node.properties.bind.bind = String(values.bind);
			}
			if (Array.isArray(values.pos)) {
				node.properties.bind.pos = values.pos.slice();
			}
			if (values.objective != null) {
				node.properties.bind.objective = String(values.objective);
			}
			applyBindDefaults(node);
			refreshBindWidgets(node);
		}
		(spec.sockets || []).forEach(function (name) {
			var value = values[name];
			if (isRef(value)) {
				node.__pendingRefs.push({ from: value.from, input: name });
				return;
			}
			if (typeof value === "number") {
				node.properties.inputs[name] = value;
				node.properties.explicit[name] = true;
				if (node.__widgets[name]) {
					node.__widgets[name].value = value;
				}
			}
		});
		Object.keys(values).forEach(function (name) {
			if (!/^case_\d+$/.test(name)) {
				return;
			}
			if (isRef(values[name])) {
				node.__pendingRefs.push({ from: values[name].from, input: name });
				return;
			}
			if (inputIndex(node, name) < 0) {
				addSwitchCase(node, name, values[name]);
			} else if (typeof values[name] === "number") {
				node.properties.inputs[name] = values[name];
				node.properties.explicit[name] = true;
				if (node.__widgets[name]) {
					node.__widgets[name].value = values[name];
				}
			}
		});
		if (ir.op && node.__widgets.op) {
			node.properties.op = ir.op;
			node.__widgets.op.value = ir.op;
		}
	}

	/**
	 * Rebuild a tab's canvas value graph from its IR (after an import, a tab switch or an undo/redo).
	 * The effect root node and its position are preserved; only value nodes and their wires are redone.
	 */
	function materializeGraph(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		var effectNodeById = {};
		applyingGraph = true;
		try {
			tab.graph._nodes.slice().forEach(function (node) {
				if (isEffectNode(node) || (node.properties && node.properties.kind)) {
					if (isEffectNode(node)) {
						node.ignore_remove = false;
					}
					tab.graph.remove(node);
				}
			});
			var count = 0;
			(tab.effects || []).forEach(function (effect) {
				var node = createEffectNode(effect.type);
				if (!node) {
					return;
				}
				node.__tab = tab;
				node.properties.effectId = effect.id;
				node.ignore_remove = true;
				node.pos = Array.isArray(effect.pos) && effect.pos.length === 2
						? [num(effect.pos[0]), num(effect.pos[1])]
						: [80 + count * 260, 80];
				count++;
				tab.graph.add(node);
				effectNodeById[effect.id] = node;
			});
			var chain = [];
			(tab.effects || []).forEach(function (effect) {
				if (effectNodeById[effect.id]) {
					chain.push(effectNodeById[effect.id]);
				}
			});
			for (var c = 0; c + 1 < chain.length; c++) {
				var outSlot = outputIndex(chain[c], "out");
				var intoSlot = inputIndex(chain[c + 1], "in");
				if (outSlot >= 0 && intoSlot >= 0) {
					chain[c].connect(outSlot, chain[c + 1], intoSlot);
				}
			}
			var byId = {};
			(tab.valueNodes || []).forEach(function (ir) {
				var registered = "vfx/value/" + ir.kind;
				if (!LiteGraph.registered_node_types[registered]) {
					return;
				}
				var node = LiteGraph.createNode(registered);
				if (!node) {
					return;
				}
				node.__tab = tab;
				node.properties.irId = ir.id;
				applyIR(node, ir);
				if (Array.isArray(ir.pos) && ir.pos.length === 2) {
					node.pos = [num(ir.pos[0]), num(ir.pos[1])];
				}
				tab.graph.add(node);
				byId[ir.id] = node;
			});
			var multi = Boolean(tab.effects && tab.effects.length > 1);
			(tab.edges || []).forEach(function (edge) {
				var from = byId[edge.from];
				if (!from) {
					return;
				}
				var target;
				var slot = -1;
				if (edge.input != null) {
					target = byId[edge.to];
					slot = target ? inputIndex(target, edge.input) : -1;
				} else {
					target = multi && edge.effect != null
							? effectNodeById[edge.effect]
							: (tab.effects && tab.effects.length ? effectNodeById[tab.effects[0].id] : null);
					slot = target ? inputIndex(target, edge.to) : -1;
				}
				if (target && slot >= 0) {
					from.connect(0, target, slot);
				}
			});
			// Imported datapacks may wire node->node as `inputs: { name: { from } }`; materialize those.
			Object.keys(byId).forEach(function (id) {
				var node = byId[id];
				(node.__pendingRefs || []).forEach(function (ref) {
					var source = byId[ref.from];
					var slot = inputIndex(node, ref.input);
					if (source && slot >= 0) {
						source.connect(0, node, slot);
					}
				});
			});
		} finally {
			applyingGraph = false;
		}
		var active = tab.effect();
		tab.root = active ? effectNodeById[active.id] || null : (effectNodes(tab)[0] || null);
		syncAllSlots(tab);
		if (scene) {
			scene.setDirty(true, true);
		}
	}

	/** Debounced graph edits, so one drag/delete is one undo step. */
	var graphEdits = {};
	var graphSeq = 0;

	function scheduleGraphEdit(tab) {
		if (!tab || applyingGraph) {
			return;
		}
		if (!tab.__graphKey) {
			tab.__graphKey = "g" + (++graphSeq);
		}
		var pending = graphEdits[tab.__graphKey];
		if (!pending) {
			pending = graphEdits[tab.__graphKey] = { tab: tab, before: snapshotIR(tab) };
		}
		clearTimeout(pending.timer);
		pending.timer = setTimeout(function () {
			flushGraphEdit(tab.__graphKey);
		}, 200);
	}

	function flushGraphEdit(key) {
		var pending = graphEdits[key];
		if (!pending) {
			return;
		}
		delete graphEdits[key];
		var tab = pending.tab;
		refreshFromCanvas(tab);
		var after = snapshotIR(tab);
		if (JSON.stringify(after) === JSON.stringify(pending.before)) {
			return;
		}
		commitHistory();
		if (window.VFXTimeline && typeof window.VFXTimeline.structural === "function") {
			window.VFXTimeline.structural(tab);
		}
	}

	/** Add a value node of {@code kind} to {@code tab} at a default canvas position. */
	function addValueNode(tab, kind) {
		if (!tab) {
			status("choose an effect type before adding value nodes");
			return null;
		}
		var registered = "vfx/value/" + kind;
		if (!LiteGraph.registered_node_types[registered]) {
			return null;
		}
		var node = LiteGraph.createNode(registered);
		node.__tab = tab;
		node.properties.irId = uniqueNodeId(tab, kind);
		var count = 0;
		tab.graph._nodes.forEach(function (existing) {
			if (existing !== tab.root && existing.properties && existing.properties.kind) {
				count++;
			}
		});
		node.pos = [120 + (count % 6) * 30, 120 + (count % 6) * 30];
		tab.graph.add(node);
		scheduleGraphEdit(tab);
		if (scene) {
			scene.setDirty(true, true);
		}
		return node;
	}

	/** Register the canvas hooks that turn a user edit into a debounced IR update. */
	function wireGraphHooks(tab) {
		// LiteGraph fires `connectionChange` on the LGraph for both connect and disconnect
		// (litegraph.js `LGraph.prototype.connectionChange`); there is no `onNodeConnectionChange`.
		tab.graph.onConnectionChange = function () {
			syncAllSlots(tab);
			scheduleGraphEdit(tab);
		};
		tab.graph.onNodeRemoved = function () {
			scheduleGraphEdit(tab);
		};
	}

	function buildValuePalette() {
		if (!dom.valueTypes) {
			return;
		}
		dom.valueTypes.textContent = "";
		valueNodeTypes().forEach(function (kind) {
			var item = document.createElement("button");
			item.type = "button";
			item.className = "vfx-type";
			item.textContent = kind;
			item.dataset.value = kind;
			item.addEventListener("click", function () {
				addValueNode(activeTab(), kind);
			});
			dom.valueTypes.appendChild(item);
		});
	}



	/* ------------------------------------------------------------------ */
	/* Inspector                                                           */
	/* ------------------------------------------------------------------ */

	/** The param shown in the inspector, by name; reset when it is not on the active tab. */
	var inspectorName = null;

	/** The snapshot undo/redo stack (ir.js `SnapshotHistory`); created lazily once ir.js has loaded. */
	var stack = null;
	/** Maximum retained snapshots; one drag or one discrete action is one snapshot (design §15.7). */
	var HISTORY_LIMIT = 100;

	/** The serialisable snapshot history, or null until ir.js has loaded. */
	function history() {
		if (!stack && window.VFXIR && window.VFXIR.SnapshotHistory) {
			stack = new window.VFXIR.SnapshotHistory(HISTORY_LIMIT);
		}
		return stack;
	}

	/** One tab's model, minus the LiteGraph canvas (which is rebuilt from this on restore). */
	function snapshotTab(tab) {
		reconcileEffects(tab);
		return {
			name: tab.name,
			type: tab.type,
			effects: snapshot(tab.effects || []),
			activeEffect: tab.activeEffect,
			effectSeq: tab.__effectSeq || 0,
			duration: tab.duration,
			easing: tab.easing,
			loop: tab.loop,
			persistent: tab.persistent,
			fade_ticks: tab.fade_ticks,
			delay: tab.delay,
			params: snapshot(tab.params || {}),
			keys: snapshot(tab.keys || {}),
			valueNodes: snapshot(tab.valueNodes || []),
			edges: snapshot(tab.edges || []),
			source: snapshot(tab.source),
			timeline: snapshot(tab.timeline),
			root: tab.root && Array.isArray(tab.root.pos)
					? [num(tab.root.pos[0]), num(tab.root.pos[1])] : null,
			valueSeq: tab.__valueSeq || 0
		};
	}

	/** The whole project as a serialisable snapshot (project object fields included, so undoable). */
	function snapshotProject() {
		return {
			active: project.active,
			name: project.name,
			namespace: project.namespace,
			defaults: snapshot(project.defaults || {}),
			tabs: project.tabs.map(snapshotTab)
		};
	}

	/** Make sure a baseline exists before the first mutation, so the first action is undoable. */
	function ensureHistoryBaseline() {
		var h = history();
		if (h && h.states.length === 0) {
			h.reset(snapshotProject());
		}
		return h;
	}

	/** Capture the current project as the baseline (called once the shell is up). */
	function resetHistory() {
		var h = history();
		if (h) {
			h.reset(snapshotProject());
		}
	}

	/** Record the current project state as one undo step (skips an unchanged state). */
	function commitHistory() {
		var h = ensureHistoryBaseline();
		if (!h) {
			return;
		}
		var next = snapshotProject();
		if (h.index >= 0 && JSON.stringify(h.states[h.index]) === JSON.stringify(next)) {
			return;
		}
		h.record(next);
	}

	/** Debounced {@link commitHistory}, so a slider drag commits as one transaction. */
	var commitTimer = 0;
	function scheduleCommitHistory() {
		clearTimeout(commitTimer);
		commitTimer = setTimeout(function () {
			commitTimer = 0;
			commitHistory();
		}, 250);
	}

	/** Rebuild every tab (and its canvas graph) from a project snapshot, then re-render the shell. */
	function restoreProject(snap) {
		if (!snap || !Array.isArray(snap.tabs)) {
			return;
		}
		project.name = snap.name == null ? project.name : String(snap.name);
		project.namespace = snap.namespace == null ? project.namespace : String(snap.namespace);
		project.defaults = snapshot(snap.defaults) || {};
		setProjectName();
		// Drop any pending debounced edit/commit so a stale timer cannot re-commit after the restore.
		clearTimeout(commitTimer);
		commitTimer = 0;
		Object.keys(graphEdits).forEach(function (key) {
			clearTimeout(graphEdits[key].timer);
			delete graphEdits[key];
		});
		project.tabs = snap.tabs.map(function (state) {
			var tab = window.VFXIR ? new window.VFXIR.Tab(state.name, null)
					: { name: state.name, effects: [], activeEffect: -1, duration: 40, easing: "linear",
						params: {}, valueNodes: [], edges: [], keys: {} };
			tab.duration = num(state.duration, 40);
			tab.easing = state.easing == null ? "linear" : String(state.easing);
			tab.loop = state.loop === true;
			tab.persistent = state.persistent === true;
			tab.fade_ticks = num(state.fade_ticks, 0);
			tab.delay = num(state.delay, 0);
			tab.valueNodes = snapshot(state.valueNodes) || [];
			tab.edges = snapshot(state.edges) || [];
			tab.source = snapshot(state.source);
			tab.timeline = snapshot(state.timeline) || {};
			tab.__valueSeq = state.valueSeq || 0;
			if (Array.isArray(state.effects) && state.effects.length) {
				tab.effects = [];
				tab.activeEffect = -1;
				state.effects.forEach(function (saved) {
					var entry = tab.addEffect(saved.type, saved.id);
					entry.params = snapshot(saved.params) || {};
					entry.keys = snapshot(saved.keys) || {};
					entry.delay = num(saved.delay, 0);
					entry.duration = num(saved.duration, 0);
					entry.easing = saved.easing == null ? null : saved.easing;
					entry.mute = saved.mute === true;
					entry.solo = saved.solo === true;
					entry.source = snapshot(saved.source);
					entry.effectId = saved.effectId == null ? null : String(saved.effectId);
					entry.external = saved.external === true;
					if (Array.isArray(saved.pos)) {
						entry.pos = [num(saved.pos[0]), num(saved.pos[1])];
					}
				});
				tab.activeEffect = Number(state.activeEffect);
				if (!(tab.activeEffect >= 0 && tab.activeEffect < tab.effects.length)) {
					tab.activeEffect = tab.effects.length ? 0 : -1;
				}
				tab.__effectSeq = num(state.effectSeq, tab.effects.length);
			} else if (state.type) {
				var restored = tab.addEffect(state.type);
				restored.params = snapshot(state.params) || {};
				restored.keys = snapshot(state.keys) || {};
				if (Array.isArray(state.root)) {
					restored.pos = [num(state.root[0]), num(state.root[1])];
				}
			} else {
				tab.params = snapshot(state.params) || {};
				tab.keys = snapshot(state.keys) || {};
			}
			tab.graph = newGraph();
			tab.root = null;
			wireGraphHooks(tab);
			return tab;
		});
		var index = Number(snap.active);
		project.active = index >= 0 && index < project.tabs.length ? index
				: (project.tabs.length ? 0 : -1);
		if (scene) {
			scene.setGraph(project.active >= 0 ? project.tabs[project.active].graph : newGraph());
		}
		if (project.active >= 0) {
			materializeGraph(project.tabs[project.active]);
		}
		renderTabs();
		if (dom.tabName) {
			dom.tabName.value = project.active >= 0 ? project.tabs[project.active].name : "";
		}
		renderInspector();
		renderTimeline();
		showEmptyPlaceholder();
	}

	/** Apply a snapshot and recompile the restored tab through the full `/compile` bridge path. */
	function applyHistory(snap) {
		restoreProject(snap);
		var tab = activeTab();
		if (tab && tab.type && window.VFXBridge && typeof window.VFXBridge.fullCompile === "function") {
			status("history → recompile " + tab.name);
			window.VFXBridge.fullCompile();
		}
	}

	/** Undo one transaction. */
	function undoHistory() {
		var h = history();
		var snap = h ? h.undo() : null;
		if (snap) {
			applyHistory(snap);
		}
		return snap;
	}

	/** Redo one transaction. */
	function redoHistory() {
		var h = history();
		var snap = h ? h.redo() : null;
		if (snap) {
			applyHistory(snap);
		}
		return snap;
	}

	function inspectorMessage(text) {
		var p = document.createElement("p");
		p.className = "vfx-inspect-empty";
		p.textContent = text;
		return p;
	}

	/** A deep JSON snapshot of a spec, so an undo command holds a value, not a live reference. */
	function snapshot(value) {
		return value == null ? null : JSON.parse(JSON.stringify(value));
	}

	/**
	 * The inspector edit hook: the inspector has already mutated `tab.params[name]`; record a
	 * snapshot (debounced so a slider drag is one step), surface the form in the status line and hand
	 * the edit to Task 15's live sync (a constant slider -> `set_param`, anything structural ->
	 * `/compile`), then refresh the timeline so a new/edited key lane appears.
	 */
	function onInspectorEdit(name, spec, previous) {
		var tab = activeTab();
		if (!tab) {
			return;
		}
		status("param " + name + " → " + slot.state(tab, name));
		scheduleCommitHistory();
		if (window.VFXTimeline && typeof window.VFXTimeline.onEdit === "function") {
			window.VFXTimeline.onEdit(tab, name, spec, previous);
		}
		renderTimeline();
	}

	/**
	 * The timing-panel edit hook (design §15.3/§15.5): the panel already mutated a tab timing field;
	 * snapshot it for undo and auto-replay the preview (a duration change also re-renders the lane
	 * bounds). Never re-renders the inspector, so a focused field keeps its caret.
	 */
	function onTimingEdit(name, value) {
		var tab = activeTab();
		if (!tab) {
			return;
		}
		status("timing " + name + " → " + value);
		scheduleCommitHistory();
		renderTimeline();
		if (tab.type && window.VFXTimeline && typeof window.VFXTimeline.scheduleReplay === "function") {
			window.VFXTimeline.scheduleReplay(tab);
		}
	}

	/** Mount the timeline panel (Task 15) for the active tab. */
	function renderTimeline() {
		if (!dom.timeline) {
			return;
		}
		dom.timeline.textContent = "";
		if (window.VFXTimeline && typeof window.VFXTimeline.render === "function") {
			dom.timeline.appendChild(window.VFXTimeline.render(activeTab()));
		}
	}

	/** Rebuild the inspector panel for the active tab: a param list plus the selected control. */
	/** The source value-node id wiring an effect param, or null when the param is free (fix 5). */
	function drivenParamSource(tab, name) {
		if (!tab || !tab.edges) {
			return null;
		}
		for (var i = 0; i < tab.edges.length; i++) {
			var edge = tab.edges[i];
			if (edge && edge.to === name && edge.input == null && edge.from != null
					&& edgeOwnedByActive(tab, edge)) {
				return edge.from;
			}
		}
		return null;
	}

	/**
	 * The schema param list collapsed into inspector rows (design §15.9.6/P4): each vector group
	 * (see {@code VFXIR.paramGroups}) is one row, every other param is its own. Falls back to the
	 * flat list when ir.js has not loaded.
	 *
	 * @param {Array<Object>} params the `VFXParamDescriptor[]`
	 * @returns {Array<Object>} the rows `{ group, name, components }`
	 */
	function groupedParamRows(params) {
		if (window.VFXIR && typeof window.VFXIR.paramGroups === "function") {
			return window.VFXIR.paramGroups(params);
		}
		return params.map(function (param) {
			return { group: null, name: param.name, components: [param] };
		});
	}

	/** A chooser label: the name, or for a group `name = value` / `name = mixed` (design §15.9.6/P4). */
	function rowLabel(tab, row) {
		if (!row.group) {
			return row.name;
		}
		if (!window.VFXIR || typeof window.VFXIR.uniformGroupSpec !== "function") {
			return row.name;
		}
		var spec = window.VFXIR.uniformGroupSpec(tab, row.components);
		if (!spec) {
			return row.name + " = mixed";
		}
		return row.name + " = " + (spec.value != null ? spec.value : "—");
	}

	/**
	 * The real scalar param names behind an inspector selection (design §15.9.6/P4): a group
	 * selection expands to its components, a single param is itself. Empty when the selection is stale.
	 *
	 * @param {Object} tab the active tab
	 * @param {string} name the selected row name (group or param)
	 * @returns {Array<string>} the scalar param names
	 */
	function selectedParamNames(tab, name) {
		var descriptor = tab && tab.type ? schema[tab.type] : null;
		var rows = groupedParamRows((descriptor && descriptor.params) || []);
		for (var i = 0; i < rows.length; i++) {
			if (rows[i].name === name
					|| rows[i].components.some(function (component) { return component.name === name; })) {
				return rows[i].components.map(function (component) { return component.name; });
			}
		}
		return [];
	}

	/** The layer row being dragged in the Layers panel, or null. */
	var dragLayerIndex = null;
	/** A ▲/▼ reorder button for the Layers panel. */
	function layerMoveButton(tab, index, delta, label) {
		var button = document.createElement("button");
		button.type = "button";
		button.className = "vfx-layer-btn";
		button.textContent = label;
		button.title = delta < 0 ? "Move up (earlier layer)" : "Move down (later layer)";
		button.disabled = index + delta < 0 || index + delta >= tab.effects.length;
		button.addEventListener("click", function (event) {
			event.stopPropagation();
			moveLayer(tab, index, index + delta);
		});
		return button;
	}

	/** Reorder the chain, dragging or via ▲/▼; the reorder rewires the canvas flow links (P6). */
	function moveLayer(tab, from, to) {
		if (!tab || !tab.effects || from == null || !isFinite(Number(from))) {
			return;
		}
		from = Number(from);
		if (from < 0 || from >= tab.effects.length || from === to) {
			return;
		}
		var active = tab.effect();
		var activeId = active ? active.id : null;
		var moved = tab.effects.splice(from, 1)[0];
		var target = Math.max(0, Math.min(tab.effects.length, to));
		tab.effects.splice(target, 0, moved);
		if (activeId) {
			tab.activeEffect = indexOfEffect(tab, activeId);
		}
		rewireChain(tab);
		renderInspector();
		renderTimeline();
		commitHistory();
		requestStructural(tab);
	}

	/**
	 * The Layers panel (design §15.2, P6): the tab's effects in application order, drag-to-reorder,
	 * mute/solo, per-node delay/duration, all synchronized with the canvas chain.
	 *
	 * @param {Object} tab the active tab
	 * @returns {HTMLElement} the panel
	 */
	function renderLayers(tab) {
		var wrap = document.createElement("div");
		wrap.className = "vfx-layers";
		var heading = document.createElement("h2");
		heading.textContent = "Layers";
		wrap.appendChild(heading);
		if (!tab || !tab.effects || !tab.effects.length) {
			wrap.appendChild(inspectorMessage("No effects — add one from the palette"));
			return wrap;
		}
		var list = document.createElement("div");
		list.className = "vfx-layer-list";
		tab.effects.forEach(function (effect, index) {
			var row = document.createElement("div");
			row.className = "vfx-layer" + (index === tab.activeEffect ? " active" : "")
					+ (effect.mute ? " muted" : "") + (effect.solo ? " solo" : "");
			row.draggable = true;
			row.dataset.index = String(index);
			row.dataset.id = effect.id;
			row.title = "Drag to reorder (application order)";

			var mute = document.createElement("button");
			mute.type = "button";
			mute.className = "vfx-layer-btn";
			mute.textContent = "M";
			mute.title = "Mute (disable this effect)";
			mute.addEventListener("click", function (event) {
				event.stopPropagation();
				effect.mute = !effect.mute;
				commitHistory();
				renderInspector();
				renderTimeline();
				requestStructural(tab);
			});

			var solo = document.createElement("button");
			solo.type = "button";
			solo.className = "vfx-layer-btn";
			solo.textContent = "S";
			solo.title = "Solo (play only the soloed effects)";
			solo.addEventListener("click", function (event) {
				event.stopPropagation();
				effect.solo = !effect.solo;
				commitHistory();
				renderInspector();
				renderTimeline();
				requestStructural(tab);
			});

			var label = document.createElement("span");
			label.className = "vfx-layer-name";
			label.textContent = (index + 1) + ". " + (effect.type || "—");
			label.title = effect.type || "";

			var delay = document.createElement("input");
			delay.type = "number";
			delay.className = "vfx-layer-num";
			delay.value = String(num(effect.delay, 0));
			delay.title = "Delay (ticks) before this effect starts";
			delay.addEventListener("click", function (event) { event.stopPropagation(); });
			delay.addEventListener("change", function () {
				effect.delay = num(delay.value, 0);
				commitHistory();
				renderTimeline();
				requestStructural(tab);
			});

			var duration = document.createElement("input");
			duration.type = "number";
			duration.className = "vfx-layer-num";
			duration.value = String(num(effect.duration, 0));
			duration.title = "Duration (ticks); 0 inherits the tab duration";
			duration.addEventListener("click", function (event) { event.stopPropagation(); });
			duration.addEventListener("change", function () {
				effect.duration = num(duration.value, 0);
				commitHistory();
				renderTimeline();
				requestStructural(tab);
			});

			row.appendChild(mute);
			row.appendChild(solo);
			row.appendChild(label);
			row.appendChild(delay);
			row.appendChild(duration);
			row.appendChild(layerMoveButton(tab, index, -1, "▲"));
			row.appendChild(layerMoveButton(tab, index, 1, "▼"));

			row.addEventListener("click", function () {
				if (tab.activeEffect !== index) {
					selectEffect(tab, effect);
					var node = nodeForEffect(tab, effect.id);
					if (scene && node) {
						scene.selectNodes([node]);
					}
				}
			});
			row.addEventListener("dragstart", function (event) {
				dragLayerIndex = index;
				if (event.dataTransfer) {
					event.dataTransfer.effectAllowed = "move";
					event.dataTransfer.setData("text/plain", String(index));
				}
			});
			row.addEventListener("dragover", function (event) {
				event.preventDefault();
				if (event.dataTransfer) {
					event.dataTransfer.dropEffect = "move";
				}
				row.classList.add("dragover");
			});
			row.addEventListener("dragleave", function () {
				row.classList.remove("dragover");
			});
			row.addEventListener("drop", function (event) {
				event.preventDefault();
				row.classList.remove("dragover");
				var from = dragLayerIndex;
				if (from == null && event.dataTransfer) {
					from = Number(event.dataTransfer.getData("text/plain"));
				}
				dragLayerIndex = null;
				moveLayer(tab, from, index);
			});
			list.appendChild(row);
		});
		list.addEventListener("dragend", function () {
			dragLayerIndex = null;
		});
		wrap.appendChild(list);
		return wrap;
	}

	/**
	 * Rebuild the inspector panel for the active tab: the Layers list, node timing and the selected
	 * parameter. A param driven by a wire is rendered read-only with its source badge (design §15.1).
	 */
	function renderInspector() {
		if (!dom.inspector) {
			return;
		}
		dom.inspector.textContent = "";
		if (!window.VFXInspector) {
			return;
		}
		var tab = activeTab();
		var descriptor = tab && tab.type ? schema[tab.type] : null;
		if (!tab) {
			dom.inspector.appendChild(inspectorMessage("No active tab"));
			return;
		}
		// Layers panel (design §15.2, P6): the chain order, before the per-node sections.
		dom.inspector.appendChild(renderLayers(tab));
		// Node timing section (design §15.3): duration/loop/persistent/fade_ticks/delay on the tab.
		if (typeof window.VFXInspector.renderTiming === "function") {
			dom.inspector.appendChild(window.VFXInspector.renderTiming(tab, onTimingEdit));
		}
		if (!descriptor || !descriptor.params || descriptor.params.length === 0) {
			dom.inspector.appendChild(inspectorMessage(
					tab.type ? "No editable parameters" : "Choose an effect type"));
			return;
		}
		var params = descriptor.params;
		// Collapse vector groups into one row (design §15.9.6/P4, fix #21): color_r/_g/_b -> `color`.
		var rows = groupedParamRows(params);
		var current = null;
		for (var i = 0; i < rows.length; i++) {
			if (rows[i].name === inspectorName
					|| rows[i].components.some(function (component) { return component.name === inspectorName; })) {
				current = rows[i];
				break;
			}
		}
		if (!current) {
			current = rows[0];
			inspectorName = current.name;
		}
		var heading = document.createElement("h2");
		heading.textContent = "Inspector";
		dom.inspector.appendChild(heading);

		var chooser = document.createElement("div");
		chooser.className = "vfx-params";
		rows.forEach(function (row) {
			var source = row.group
					? (row.components.some(function (component) { return drivenParamSource(tab, component.name); })
							? "group" : null)
					: drivenParamSource(tab, row.components[0].name);
			var item = document.createElement("button");
			item.type = "button";
			item.className = "vfx-param" + (row.name === inspectorName ? " active" : "")
					+ (source ? " driven" : "");
			item.textContent = rowLabel(tab, row) + (source ? " ←" : "");
			item.title = source ? ("driven by " + source) : "";
			item.addEventListener("click", function () {
				inspectorName = row.name;
				renderInspector();
			});
			chooser.appendChild(item);
		});
		dom.inspector.appendChild(chooser);
		if (current.group && typeof window.VFXInspector.renderGroup === "function") {
			dom.inspector.appendChild(window.VFXInspector.renderGroup(tab, current, onInspectorEdit));
		} else {
			var param = current.components[0];
			dom.inspector.appendChild(window.VFXInspector.render(tab, param, onInspectorEdit,
					drivenParamSource(tab, param.name)));
		}
	}

	/* ------------------------------------------------------------------ */
	/* Slot model (design §15.1/§15.8)                                     */
	/* ------------------------------------------------------------------ */

	/** Whether a param wire belongs to the effect the inspector is editing (an untagged legacy wire
	 *  belongs to a single-effect tab). */
	function edgeOwnedByActive(tab, edge) {
		if (!tab || !tab.effects || tab.effects.length <= 1) {
			return true;
		}
		var active = tab.effect();
		return active ? edge.effect === active.id : edge.effect == null;
	}

	/** Find the edge wiring a value node into an effect param of the active effect, or null. */
	function driverEdge(tab, name) {
		if (!tab || !Array.isArray(tab.edges)) {
			return null;
		}
		for (var i = 0; i < tab.edges.length; i++) {
			var edge = tab.edges[i];
			if (edge && edge.to === name && edge.input == null && edge.from != null
					&& edgeOwnedByActive(tab, edge)) {
				return edge;
			}
		}
		return null;
	}

	/** The schema-descriptor default for a param (0 when unknown). */
	function paramDefault(tab, name) {
		var descriptor = tab && tab.type ? schema[tab.type] : null;
		var params = (descriptor && descriptor.params) || [];
		for (var i = 0; i < params.length; i++) {
			if (params[i].name === name) {
				return isFiniteNumber(params[i].defaultValue) ? params[i].defaultValue : 0;
			}
		}
		return 0;
	}

	/** The active tab's playhead tick, clamped to the tab duration. */
	function playheadTime(tab) {
		var raw = window.VFXTimeline && typeof window.VFXTimeline.playhead === "function"
				? Number(window.VFXTimeline.playhead()) : 0;
		if (!isFiniteNumber(raw)) {
			raw = 0;
		}
		return window.VFXTimeline && typeof window.VFXTimeline.clampTime === "function"
				? window.VFXTimeline.clampTime(tab, raw) : Math.max(0, raw);
	}

	/** The slot's value with no playhead lookup: the last key, else the inline literal, else default. */
	function slotValue(tab, name) {
		var keys = (tab.keys && tab.keys[name]) || [];
		if (keys.length) {
			return keys[keys.length - 1].value;
		}
		var raw = tab.params ? tab.params[name] : null;
		if (raw != null && typeof raw === "object") {
			return raw.value != null ? raw.value : paramDefault(tab, name);
		}
		return raw != null ? raw : paramDefault(tab, name);
	}

	/** Add (or update) one key at `time` on a slot — the auto-key writer the diamond/K share. */
	function addKeyToSlot(tab, name, time, value) {
		if (!tab) {
			return null;
		}
		if (!tab.keys) {
			tab.keys = {};
		}
		var t = Math.round(num(time));
		var list = tab.keys[name];
		if (!Array.isArray(list)) {
			list = tab.keys[name] = [];
		}
		var v = value != null ? value : paramDefault(tab, name);
		for (var i = 0; i < list.length; i++) {
			if (Math.round(num(list[i].time)) === t) {
				list[i].value = v;
				return list[i];
			}
		}
		return tab.addKey(name, t, v);
	}

	/** Rebuild the canvas model, snapshot undo, refresh the panels and recompile. */
	function afterSlotChange(tab) {
		refreshFromCanvas(tab);
		commitHistory();
		renderInspector();
		renderTimeline();
		if (tab && tab.type) {
			requestStructural(tab);
		}
	}

	/** Create a driver value node of `kind` (expr/bind/curve) and apply its inputs. */
	function createDriverNode(tab, kind, inputs) {
		var node = addValueNode(tab, kind);
		if (!node) {
			return null;
		}
		applyIR(node, { inputs: inputs || {} });
		return node;
	}

	/** Wire a value node's output into an effect param; false when the param has no root slot. */
	function wireDriverToParam(tab, node, name) {
		var slotIndex = tab && tab.root ? inputIndex(tab.root, name) : -1;
		if (!node || slotIndex < 0) {
			return false;
		}
		node.connect(0, tab.root, slotIndex);
		return true;
	}

	/** Remove every wire feeding one effect param. */
	function disconnectParamDriver(tab, name) {
		if (!tab || !tab.graph || !tab.root) {
			return;
		}
		var slotIndex = inputIndex(tab.root, name);
		if (slotIndex < 0) {
			return;
		}
		Object.keys(tab.graph.links).forEach(function (linkId) {
			var link = tab.graph.links[linkId];
			if (link && link.target_id === tab.root.id && link.target_slot === slotIndex) {
				tab.graph.removeLink(linkId);
			}
		});
	}

	/** Remove every wire feeding one value-node input. */
	function disconnectField(tab, node, slotIndex) {
		if (!tab || !tab.graph || slotIndex < 0) {
			return;
		}
		Object.keys(tab.graph.links).forEach(function (linkId) {
			var link = tab.graph.links[linkId];
			if (link && link.target_id === node.id && link.target_slot === slotIndex) {
				tab.graph.removeLink(linkId);
			}
		});
	}

	/** Diamond: create a plain key track on an effect param (value at the playhead). */
	function slotAnimate(tab, name, value) {
		var key = slot.addKey(tab, name, value);
		if (key) {
			afterSlotChange(tab);
		}
		return key;
	}

	/** Auto-key one value at the playhead without the full panel refresh (for focused edits). */
	function slotAddKey(tab, name, value) {
		if (!tab || driverEdge(tab, name)) {
			return null;
		}
		return addKeyToSlot(tab, name, playheadTime(tab), value != null ? value : slotValue(tab, name));
	}

	/** Clear a param's track, keeping its last value as the inline constant. */
	function slotClearTrack(tab, name) {
		if (!tab) {
			return;
		}
		var list = (tab.keys && tab.keys[name]) || [];
		var raw = tab.params ? tab.params[name] : null;
		var multiply = raw && typeof raw === "object" ? raw.multiply : null;
		var last = list.length ? list[list.length - 1].value : paramDefault(tab, name);
		if (tab.keys) {
			delete tab.keys[name];
		}
		var next = { value: last };
		if (multiply) {
			next.multiply = multiply;
		}
		tab.params[name] = next;
		afterSlotChange(tab);
	}

	/** Replace a param's driver (or its inline value/track) with a fresh driver node. */
	function slotReplaceDriver(tab, name, kind, inputs) {
		if (!tab || !tab.type) {
			return;
		}
		disconnectParamDriver(tab, name);
		if (tab.keys) {
			delete tab.keys[name];
		}
		var node = createDriverNode(tab, kind, inputs);
		wireDriverToParam(tab, node, name);
		syncAllSlots(tab);
		afterSlotChange(tab);
	}

	/** Drop a param's driver, leaving it editable inline. */
	function slotRemoveDriver(tab, name) {
		disconnectParamDriver(tab, name);
		syncAllSlots(tab);
		afterSlotChange(tab);
	}

	/** Add or remove a `multiply` binding on any slot. */
	function slotSetMultiply(tab, name, on) {
		if (!tab) {
			return;
		}
		var raw = tab.params ? tab.params[name] : null;
		var spec = raw != null && typeof raw === "object"
				? raw : { value: raw == null ? paramDefault(tab, name) : raw };
		if (on) {
			if (!spec.multiply) {
				spec.multiply = {
					bind: (window.VFXInspector && window.VFXInspector.DEFAULT_BIND) || "health"
				};
			}
		} else {
			delete spec.multiply;
		}
		tab.params[name] = spec;
		afterSlotChange(tab);
	}

	/** Materialize a param track as a `curve` value node wired into the slot. */
	function slotShowAsCurve(tab, name) {
		var list = (tab.keys && tab.keys[name]) || [];
		if (!tab || !tab.type || list.length === 0) {
			return;
		}
		var points = list.map(function (key) {
			var point = { time: num(key.time), value: num(key.value) };
			if (key.easing) {
				// Curve nodes carry the datapack form (name string or `{cubicBezier:[...]}`).
				point.easing = window.VFXIR && typeof window.VFXIR.easingToJson === "function"
						? window.VFXIR.easingToJson(key.easing) : key.easing;
			}
			return point;
		});
		disconnectParamDriver(tab, name);
		var node = createDriverNode(tab, "curve", { points: points });
		wireDriverToParam(tab, node, name);
		if (tab.keys) {
			delete tab.keys[name];
		}
		syncAllSlots(tab);
		afterSlotChange(tab);
	}

	/** Animate a value-node field: a `curve` node (two flat points) wired into the field. */
	function animateField(node, inputName) {
		var tab = node.__tab;
		if (!tab || !tab.root) {
			return;
		}
		var slotIndex = inputIndex(node, inputName);
		if (slotIndex < 0) {
			return;
		}
		disconnectField(tab, node, slotIndex);
		var current = num(node.properties && node.properties.inputs ? node.properties.inputs[inputName] : 0);
		var total = window.VFXTimeline && typeof window.VFXTimeline.duration === "function"
				? window.VFXTimeline.duration(tab) : num(tab.duration, 40);
		var curve = addValueNode(tab, "curve");
		if (!curve) {
			return;
		}
		curve.properties.points = [{ time: 0, value: current }, { time: total, value: current }];
		if (curve.__widgets.points) {
			curve.__widgets.points.value = pointsText(curve.properties.points);
		}
		curve.connect(0, node, slotIndex);
		syncAllSlots(tab);
		afterSlotChange(tab);
	}

	/** Replace a value-node field's driver with a fresh expr/bind node. */
	function replaceFieldDriver(node, inputName, kind, inputs) {
		var tab = node.__tab;
		if (!tab || !tab.root) {
			return;
		}
		var slotIndex = inputIndex(node, inputName);
		if (slotIndex < 0) {
			return;
		}
		disconnectField(tab, node, slotIndex);
		var created = addValueNode(tab, kind);
		if (!created) {
			return;
		}
		applyIR(created, { inputs: inputs || {} });
		created.connect(0, node, slotIndex);
		syncAllSlots(tab);
		afterSlotChange(tab);
	}

	/** The floating slot context menu, shared by the inspector and the canvas fields. */
	var menuCloser = null;

	function closeMenu() {
		var node = document.querySelector(".vfx-slot-menu");
		if (node && node.parentNode) {
			node.parentNode.removeChild(node);
		}
		if (menuCloser) {
			document.removeEventListener("mousedown", menuCloser, true);
			menuCloser = null;
		}
	}

	/**
	 * Show a small anchored menu of `{ label, enabled?, action }` entries at the event position.
	 *
	 * @param {Array<Object>} items the menu entries
	 * @param {MouseEvent} event the opening event (its clientX/clientY anchor the menu)
	 */
	function showMenu(items, event) {
		closeMenu();
		var menu = document.createElement("div");
		menu.className = "vfx-slot-menu";
		items.forEach(function (entry) {
			var button = document.createElement("button");
			button.type = "button";
			button.className = "vfx-slot-menu-item";
			button.textContent = entry.label;
			if (entry.enabled === false) {
				button.disabled = true;
			}
			button.addEventListener("click", function (e) {
				e.preventDefault();
				e.stopPropagation();
				closeMenu();
				if (typeof entry.action === "function") {
					entry.action();
				}
			});
			menu.appendChild(button);
		});
		document.body.appendChild(menu);
		var rect = menu.getBoundingClientRect();
		menu.style.left = Math.max(4, Math.min(event.clientX, window.innerWidth - rect.width - 4)) + "px";
		menu.style.top = Math.max(4, Math.min(event.clientY, window.innerHeight - rect.height - 4)) + "px";
		setTimeout(function () {
			menuCloser = function (e) {
				if (!menu.contains(e.target)) {
					closeMenu();
				}
			};
			document.addEventListener("mousedown", menuCloser, true);
		}, 0);
	}

	/** The right-click menu for a value-node field (design §15.1). */
	function openFieldMenu(node, inputName, event) {
		var items = [
			{ label: "Animate (curve node)", action: function () { animateField(node, inputName); } },
			{ label: "Replace with expression",
				action: function () { replaceFieldDriver(node, inputName, "expr", { expr: "t" }); } },
			{ label: "Replace with binding",
				action: function () {
					replaceFieldDriver(node, inputName, "bind",
							{ bind: (window.VFXInspector && window.VFXInspector.DEFAULT_BIND) || "health" });
				} }
		];
		if (wiredSources(node)[inputName]) {
			items.push({ label: "Disconnect driver", action: function () {
				var tab = node.__tab;
				var slotIndex = inputIndex(node, inputName);
				disconnectField(tab, node, slotIndex);
				syncAllSlots(tab);
				afterSlotChange(tab);
			} });
		}
		showMenu(items, event);
	}

	/** The slot API the inspector and canvas field menus drive (all mutations are undoable). */
	var slot = {
		state: function (tab, name) {
			if (!tab) {
				return "inline";
			}
			if (driverEdge(tab, name)) {
				return "driven";
			}
			return tab.keys && tab.keys[name] && tab.keys[name].length > 0 ? "track" : "inline";
		},
		driverId: function (tab, name) {
			var edge = driverEdge(tab, name);
			return edge ? edge.from : null;
		},
		multiply: function (tab, name) {
			var raw = tab && tab.params ? tab.params[name] : null;
			return raw != null && typeof raw === "object" && raw.multiply ? raw.multiply : null;
		},
		animate: slotAnimate,
		addKey: slotAddKey,
		clearTrack: slotClearTrack,
		replaceWithExpr: function (tab, name) {
			slotReplaceDriver(tab, name, "expr", { expr: "t" });
		},
		replaceWithBind: function (tab, name) {
			slotReplaceDriver(tab, name, "bind",
					{ bind: (window.VFXInspector && window.VFXInspector.DEFAULT_BIND) || "health" });
		},
		removeDriver: slotRemoveDriver,
		setMultiply: slotSetMultiply,
		showAsCurve: slotShowAsCurve
	};

	/* ------------------------------------------------------------------ */
	/* Public API (Tasks 13-16 extend this object)                         */
	/* ------------------------------------------------------------------ */

	/* ------------------------------------------------------------------ */
	/* Pack import / export                                                */
	/* ------------------------------------------------------------------ */

	/** Lowercase a namespace/effect name into the `[a-z0-9_.-]` the server's export accepts. */
	function safeName(value) {
		return String(value == null ? "" : value).trim().toLowerCase()
				.replace(/[^a-z0-9_.-]+/g, "_").replace(/^_+|_+$/g, "");
	}

	/** The effect-name part of a tab name (`<namespace>:<name>` -> `<name>`). */
	function effectName(tab) {
		var name = tab && tab.name ? String(tab.name) : "untitled";
		var colon = name.indexOf(":");
		return colon > 0 && colon < name.length - 1 ? name.substring(colon + 1) : name;
	}

	/** Parse an imported effect entry (object or JSON string) into a definition object, or null. */
	function parseEffect(raw) {
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

	/** Coerce to a finite number, falling back to `fallback` (default 0). */
	function num(value, fallback) {
		var n = Number(value);
		return isFinite(n) ? n : (fallback == null ? 0 : fallback);
	}

	/**
	 * Restore a tab's model from a datapack definition on import: timing, params (raw specs the
	 * inspector infers a control from), keyframes into `Tab.keys`, and the value graph. Layout is
	 * not here — it comes from the sidecar (see {@link applySidecar}).
	 *
	 * @param {Object} tab a freshly created tab
	 * @param {Object|null} def the parsed effect JSON, or null
	 */
	/**
	 * Normalise one effect's raw `params` back to the slot model (design §15.8): keyframes -> a
	 * track, a bind/expr form -> a driver node + wire, start/end -> a two-key track, value/number ->
	 * an inline constant. Drivers are collected for the caller to materialize after the graph.
	 */
	function hydrateEffectParams(tab, effect, paramsRaw, duration, drivers) {
		var params = paramsRaw && typeof paramsRaw === "object" ? paramsRaw : {};
		Object.keys(params).forEach(function (name) {
			var norm = window.VFXIR && typeof window.VFXIR.normalizeParam === "function"
					? window.VFXIR.normalizeParam(params[name], duration) : null;
			if (!norm) {
				effect.params[name] = params[name];
				return;
			}
			if (norm.keys) {
				effect.keys[name] = norm.keys;
			}
			if (norm.driver) {
				drivers.push({ effect: effect.id, name: name, kind: norm.driver.kind,
					inputs: norm.driver.inputs });
			}
			if (norm.spec && Object.keys(norm.spec).length) {
				effect.params[name] = snapshot(norm.spec);
			}
		});
	}

	/**
	 * Merge one definition's value graph (`graph` + `inputs`) into the tab, tagging its param wires
	 * with the owning effect so a chain's children keep their drivers (design §15.2, P6). Imported
	 * node ids that collide across children are renamed.
	 */
	function hydrateEffectGraph(tab, effect, childDef, used) {
		var graph = childDef && childDef.graph && typeof childDef.graph === "object" ? childDef.graph : null;
		var rename = {};
		if (graph && Array.isArray(graph.nodes)) {
			graph.nodes.forEach(function (node) {
				var base = String(node.id);
				var mapped = base;
				var suffix = 1;
				while (used[mapped]) {
					mapped = base + "_" + (suffix++);
				}
				used[mapped] = true;
				rename[base] = mapped;
				var out = { id: mapped, kind: node.kind, inputs: snapshot(node.inputs || {}) };
				if (node.op != null) {
					out.op = node.op;
				}
				var meta = graph.meta && graph.meta[node.id];
				if (meta && Array.isArray(meta.pos) && meta.pos.length === 2) {
					out.pos = [num(meta.pos[0]), num(meta.pos[1])];
				}
				tab.valueNodes.push(out);
			});
		}
		if (graph && Array.isArray(graph.edges)) {
			graph.edges.forEach(function (edge) {
				var from = rename[String(edge.from)];
				var to = rename[String(edge.to)];
				if (from == null || to == null) {
					return;
				}
				var out = { from: from, to: to };
				if (edge.input != null) {
					out.input = edge.input;
				}
				tab.edges.push(out);
			});
		}
		if (childDef && childDef.inputs && typeof childDef.inputs === "object") {
			Object.keys(childDef.inputs).forEach(function (param) {
				var wire = childDef.inputs[param];
				if (wire && wire.from != null && rename[String(wire.from)]) {
					tab.edges.push({ from: rename[String(wire.from)], to: param, effect: effect.id });
				}
			});
		}
	}

	/**
	 * Restore a tab's model from a datapack definition on import: timing, one or more effect nodes
	 * (a `collection` root becomes a chain, design §15.2/P6), params, keyframes and the value graph.
	 * Layout is not here — it comes from the sidecar (see {@link applySidecar}).
	 *
	 * @param {Object} tab a freshly created tab
	 * @param {Object|null} def the parsed effect JSON, or null
	 * @param {Function} [resolve] `effect id -> child definition` for a collection root
	 */
	function hydrateTab(tab, def, resolve) {
		if (!tab || !def || typeof def !== "object") {
			return;
		}
		// Keep the full parsed definition, so `compileTab` can re-emit it and let every top-level field
		// the IR does not model (loop, persistent, fade_ticks, sound, positions, mask, field, subgraphs,
		// ...) round-trip unchanged instead of being silently dropped on re-export.
		tab.source = snapshot(def);
		if (def.duration != null) {
			tab.duration = num(def.duration, tab.duration);
		}
		if (def.easing != null) {
			tab.easing = String(def.easing);
		}
		tab.loop = def.loop === true;
		tab.persistent = def.persistent === true;
		if (def.fade_ticks != null) {
			tab.fade_ticks = num(def.fade_ticks, 0);
		}
		if (def.delay != null) {
			tab.delay = num(def.delay, 0);
		}
		var drivers = [];
		var used = {};
		var chain = window.VFXIR && typeof window.VFXIR.chainFromDefinition === "function"
				? window.VFXIR.chainFromDefinition(def, resolve) : null;
		if (chain) {
			tab.effects = [];
			tab.activeEffect = -1;
			chain.forEach(function (entry) {
				var effect = tab.addEffect(entry.type);
				effect.delay = num(entry.delay, 0);
				effect.duration = num(entry.duration, 0);
				effect.easing = entry.easing == null ? null : entry.easing;
				effect.source = entry.source ? snapshot(entry.source) : null;
				// Keep the imported reference id so export can re-emit an external/built-in child as
				// itself instead of renaming it to a local `__n` def (design §15.2 round-trip).
				effect.effectId = entry.effectId == null ? null : String(entry.effectId);
				effect.external = entry.external === true;
				hydrateEffectParams(tab, effect, entry.params, num(entry.duration, tab.duration), drivers);
				if (entry.source) {
					hydrateEffectGraph(tab, effect, entry.source, used);
				}
			});
			if (tab.effects.length) {
				tab.activeEffect = 0;
			}
		} else {
			var effect = tab.effects && tab.effects.length ? tab.effects[0] : tab.addEffect(def.type);
			effect.type = def.type == null ? effect.type : String(def.type);
			hydrateEffectParams(tab, effect, def.params, num(def.duration, tab.duration), drivers);
			hydrateEffectGraph(tab, effect, def, used);
		}
		// Materialize the bind/expr drivers as value nodes once the imported nodes exist, so a
		// normalized driver id never collides with an imported one.
		drivers.forEach(function (entry) {
			var id = uniqueNodeId(tab, entry.kind);
			used[id] = true;
			tab.valueNodes.push({ id: id, kind: entry.kind, inputs: snapshot(entry.inputs) });
			var edge = { from: id, to: entry.name };
			if (tab.effects && tab.effects.length > 1) {
				edge.effect = entry.effect;
			}
			tab.edges.push(edge);
		});
	}

	/**
	 * Every tab as `{ fileBaseName: compiledJsonString }` for `/export` (design §15.2, P6): a
	 * single-effect tab is one flat file; a chain is a `collection` root plus one child definition
	 * file per node, referenced by `<namespace>:<base>__<n>`.
	 */
	function gatherEffects() {
		var effects = {};
		var namespace = validNamespace(project.namespace) ? project.namespace : "vfx_project";
		project.tabs.forEach(function (tab) {
			if (!tab.effects || !tab.effects.length) {
				return;
			}
			var base = safeName(effectName(tab));
			if (!base) {
				return;
			}
			if (window.VFXCompiler && typeof window.VFXCompiler.compileTabFiles === "function") {
				var files = window.VFXCompiler.compileTabFiles(tab, { namespace: namespace, baseName: base });
				Object.keys(files).forEach(function (name) {
					effects[name] = files[name];
				});
			} else {
				effects[base] = window.VFXCompiler ? window.VFXCompiler.compileTab(tab) : "{}";
			}
		});
		return effects;
	}

	/** The editor project document: tab layout (root position + timeline zoom) for a round-trip. */
	function buildSidecar() {
		return {
			version: 1,
			tabs: project.tabs.filter(function (tab) {
				return tab.effects && tab.effects.length;
			}).map(function (tab) {
				var entry = { name: safeName(effectName(tab)) };
				if (tab.root && Array.isArray(tab.root.pos)) {
					entry.root = [num(tab.root.pos[0]), num(tab.root.pos[1])];
				}
				if (tab.timeline && isFinite(Number(tab.timeline.pxPerTick))) {
					entry.pxPerTick = Number(tab.timeline.pxPerTick);
				}
				entry.effects = (tab.effects || []).map(function (effect) {
					var node = nodeForEffect(tab, effect.id);
					var pos = node && Array.isArray(node.pos) ? [num(node.pos[0]), num(node.pos[1])]
							: (Array.isArray(effect.pos) ? [num(effect.pos[0]), num(effect.pos[1])] : null);
					var out = {
						id: effect.id,
						type: effect.type,
						pos: pos,
						mute: effect.mute === true,
						solo: effect.solo === true,
						delay: num(effect.delay, 0),
						duration: num(effect.duration, 0)
					};
					// Persist an imported external reference so a saved project keeps the id on export.
					if (effect.effectId != null) {
						out.effectId = String(effect.effectId);
					}
					if (effect.external === true) {
						out.external = true;
					}
					return out;
				});
				return entry;
			})
		};
	}

	/**
	 * Apply an imported sidecar to the just-created tabs, by effect name. A missing or corrupt
	 * sidecar is ignored, so an import without layout still works (nodes keep their default spot).
	 *
	 * @param {Object|string|null} sidecar the `/import` sidecar
	 * @param {Array} tabs the tabs the import created
	 */
	function applySidecar(sidecar, tabs) {
		var doc = sidecar;
		if (typeof sidecar === "string") {
			try {
				doc = JSON.parse(sidecar);
			} catch (e) {
				return;
			}
		}
		if (!doc || !Array.isArray(doc.tabs)) {
			return;
		}
		var byName = {};
		doc.tabs.forEach(function (entry) {
			if (entry && entry.name) {
				byName[String(entry.name)] = entry;
			}
		});
		tabs.forEach(function (tab) {
			var entry = byName[safeName(effectName(tab))];
			if (!entry) {
				return;
			}
			if (Array.isArray(entry.root) && tab.root) {
				tab.root.pos = [num(entry.root[0]), num(entry.root[1])];
			}
			if (Array.isArray(entry.effects) && tab.effects) {
				entry.effects.forEach(function (saved, index) {
					var effect = saved && saved.id && window.VFXIR && typeof window.VFXIR.effectById === "function"
							? window.VFXIR.effectById(tab, saved.id) : null;
					if (!effect) {
						effect = tab.effects[index];
					}
					if (!effect) {
						return;
					}
					if (Array.isArray(saved.pos)) {
						effect.pos = [num(saved.pos[0]), num(saved.pos[1])];
					}
					if (saved.delay != null) {
						effect.delay = num(saved.delay, effect.delay);
					}
					if (saved.duration != null) {
						effect.duration = num(saved.duration, effect.duration);
					}
					effect.mute = saved.mute === true;
					effect.solo = saved.solo === true;
					if (saved.effectId != null) {
						effect.effectId = String(saved.effectId);
					}
					if (saved.external === true) {
						effect.external = true;
					}
				});
				materializeGraph(tab);
			}
			if (isFinite(Number(entry.pxPerTick))) {
				if (!tab.timeline) {
					tab.timeline = {};
				}
				tab.timeline.pxPerTick = Number(entry.pxPerTick);
			}
		});
	}

	/* ------------------------------------------------------------------ */
	/* Project / settings (design §15.6)                                   */
	/* ------------------------------------------------------------------ */

	/** A local namespace check with an ir.js fallback, so the shell works before ir.js loads. */
	function validNamespace(value) {
		return window.VFXIR && typeof window.VFXIR.validNamespace === "function"
				? window.VFXIR.validNamespace(value)
				: /^[a-z0-9_.-]+$/.test(String(value == null ? "" : value));
	}

	/** Project name + namespace in the top bar. */
	function setProjectName() {
		if (dom.projectName) {
			dom.projectName.textContent = project.name || "Untitled project";
		}
		if (dom.projectNs) {
			dom.projectNs.textContent = project.namespace || "";
		}
	}

	/** Reset to a fresh project: new name/namespace, no tabs. */
	function newProject(name, namespace) {
		project.name = name || "Untitled project";
		project.namespace = namespace || "vfx_project";
		project.defaults = project.defaults || {};
		project.tabs = [];
		project.active = -1;
		if (scene) {
			scene.setGraph(newGraph());
		}
		if (dom.tabName) {
			dom.tabName.value = "";
		}
		setProjectName();
		renderTabs();
		renderInspector();
		renderTimeline();
		showEmptyPlaceholder();
		commitHistory();
		status("new project: " + project.name + " (" + project.namespace + ")");
	}

	/* ------------------------------------------------------------------ */
	/* Small dialog/menu widgets                                           */
	/* ------------------------------------------------------------------ */

	/** Open a modal; `build(body, close)` fills the body. Returns the handle. */
	function openModal(title, build) {
		var overlay = document.createElement("div");
		overlay.className = "vfx-modal";
		var box = document.createElement("div");
		box.className = "vfx-modal-box";
		var head = document.createElement("div");
		head.className = "vfx-modal-head";
		var heading = document.createElement("span");
		heading.textContent = title;
		var closeBtn = document.createElement("button");
		closeBtn.type = "button";
		closeBtn.className = "vfx-modal-close";
		closeBtn.textContent = "×";
		closeBtn.title = "Close";
		head.appendChild(heading);
		head.appendChild(closeBtn);
		var body = document.createElement("div");
		body.className = "vfx-modal-body";
		box.appendChild(head);
		box.appendChild(body);
		overlay.appendChild(box);
		function close() {
			document.removeEventListener("keydown", onKey, true);
			if (overlay.parentNode) {
				overlay.parentNode.removeChild(overlay);
			}
		}
		function onKey(event) {
			if (event.key === "Escape") {
				event.stopPropagation();
				close();
			}
		}
		closeBtn.addEventListener("click", close);
		overlay.addEventListener("mousedown", function (event) {
			if (event.target === overlay) {
				close();
			}
		});
		document.addEventListener("keydown", onKey, true);
		document.body.appendChild(overlay);
		if (build) {
			build(body, close);
		}
		return { body: body, close: close, overlay: overlay };
	}

	/** A labelled text input row inside a dialog; returns the input element. */
	function dialogInput(parent, label, value, placeholder) {
		var row = document.createElement("label");
		row.className = "vfx-dialog-field";
		var span = document.createElement("span");
		span.textContent = label;
		var input = document.createElement("input");
		input.type = "text";
		input.value = value == null ? "" : value;
		if (placeholder) {
			input.placeholder = placeholder;
		}
		row.appendChild(span);
		row.appendChild(input);
		parent.appendChild(row);
		return input;
	}

	/** A right-aligned action row inside a dialog. */
	function dialogActions(parent) {
		var row = document.createElement("div");
		row.className = "vfx-dialog-actions";
		parent.appendChild(row);
		return row;
	}

	/** Append a button to an actions row. */
	function dialogButton(parent, label, primary, action) {
		var button = document.createElement("button");
		button.type = "button";
		button.className = "vfx-dialog-btn" + (primary ? " primary" : "");
		button.textContent = label;
		button.addEventListener("click", action);
		parent.appendChild(button);
		return button;
	}

	/** A message line that can be shown/hidden in a dialog. */
	function dialogMessage(parent) {
		var p = document.createElement("p");
		p.className = "vfx-dialog-msg";
		p.hidden = true;
		parent.appendChild(p);
		return {
			show: function (text, isError) {
				p.textContent = text;
				p.className = "vfx-dialog-msg" + (isError ? " error" : "");
				p.hidden = false;
			},
			hide: function () {
				p.hidden = true;
			}
		};
	}

	/* ------------------------------------------------------------------ */
	/* File menu                                                           */
	/* ------------------------------------------------------------------ */

	var RECENT_KEY = "vfxweaver.recent";
	var RECENT_MAX = 8;

	/** Recent import paths from localStorage (bounded, never throws). */
	function recentPaths() {
		try {
			var raw = window.localStorage.getItem(RECENT_KEY);
			var list = raw ? JSON.parse(raw) : [];
			return Array.isArray(list) ? list.filter(function (p) { return typeof p === "string"; }) : [];
		} catch (e) {
			return [];
		}
	}

	/** Remember a path as most-recent. */
	function pushRecent(path) {
		if (!path) {
			return;
		}
		try {
			var list = recentPaths().filter(function (p) { return p !== path; });
			list.unshift(path);
			window.localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_MAX)));
		} catch (e) {
			// localStorage may be unavailable; recents are a convenience only
		}
	}

	/** The last path segment, for a compact menu label. */
	function shortPath(path) {
		var parts = String(path).split(/[\\/]/);
		return parts[parts.length - 1] || String(path);
	}

	function closeFileMenu() {
		if (window.__vfxFileMenu && window.__vfxFileMenu.parentNode) {
			window.__vfxFileMenu.parentNode.removeChild(window.__vfxFileMenu);
		}
		window.__vfxFileMenu = null;
		if (dom.file) {
			dom.file.setAttribute("aria-expanded", "false");
		}
	}

	/** Toggle the File dropdown, anchored under the File button. */
	function openFileMenu() {
		if (window.__vfxFileMenu && window.__vfxFileMenu.parentNode) {
			closeFileMenu();
			return;
		}
		var menu = document.createElement("div");
		menu.className = "vfx-menu";
		window.__vfxFileMenu = menu;

		function close() {
			if (menu.parentNode) {
				menu.parentNode.removeChild(menu);
			}
			window.__vfxFileMenu = null;
			dom.file.setAttribute("aria-expanded", "false");
			document.removeEventListener("mousedown", onDoc, true);
		}
		function onDoc(event) {
			if (!menu.contains(event.target) && event.target !== dom.file) {
				close();
			}
		}
		function item(label, hint, action) {
			var button = document.createElement("button");
			button.type = "button";
			button.className = "vfx-menu-item";
			button.textContent = label;
			if (hint) {
				var span = document.createElement("span");
				span.className = "vfx-menu-hint";
				span.textContent = hint;
				button.appendChild(span);
			}
			button.addEventListener("click", function () {
				close();
				action();
			});
			menu.appendChild(button);
		}
		function sep() {
			var line = document.createElement("div");
			line.className = "vfx-menu-sep";
			menu.appendChild(line);
		}

		item("New Project…", "", openNewProjectDialog);
		item("Open…", "", openOpenDialog);
		var recents = recentPaths();
		if (recents.length) {
			var head = document.createElement("div");
			head.className = "vfx-menu-head";
			head.textContent = "Recent";
			menu.appendChild(head);
			recents.forEach(function (path) {
				item(shortPath(path), "", function () {
					importPack(path);
				});
			});
		}
		sep();
		item("Save (preview)", "Ctrl+S", saveProject);
		item("Export…", "", openExportDialog);
		sep();
		item("Settings…", "", openSettingsDialog);

		var rect = dom.file.getBoundingClientRect();
		menu.style.left = rect.left + "px";
		menu.style.top = rect.bottom + "px";
		document.body.appendChild(menu);
		dom.file.setAttribute("aria-expanded", "true");
		// Attach after this click finishes, or the same click closes the menu immediately.
		setTimeout(function () {
			document.addEventListener("mousedown", onDoc, true);
		}, 0);
	}

	/* ------------------------------------------------------------------ */
	/* File menu dialogs                                                   */
	/* ------------------------------------------------------------------ */

	function openNewProjectDialog() {
		openModal("New project", function (body, close) {
			var nameInput = dialogInput(body, "Name", project.name, "My effects");
			var nsInput = dialogInput(body, "Namespace", project.namespace, "my_effects");
			var msg = dialogMessage(body);
			var actions = dialogActions(body);
			dialogButton(actions, "Cancel", false, close);
			dialogButton(actions, "Create", true, function () {
				var ns = nsInput.value.trim();
				if (!validNamespace(ns)) {
					msg.show("namespace must match [a-z0-9_.-]+", true);
					return;
				}
				newProject(nameInput.value.trim(), ns);
				close();
			});
		});
	}

	function openSettingsDialog() {
		openModal("Project settings", function (body, close) {
			var nameInput = dialogInput(body, "Name", project.name, "My effects");
			var nsInput = dialogInput(body, "Namespace", project.namespace, "my_effects");
			var durInput = dialogInput(body, "Default duration (ticks)",
					project.defaults && project.defaults.duration != null ? project.defaults.duration : 40, "40");
			var easeInput = dialogInput(body, "Default easing",
					project.defaults && project.defaults.easing ? project.defaults.easing : "linear", "linear");
			var msg = dialogMessage(body);
			var actions = dialogActions(body);
			dialogButton(actions, "Cancel", false, close);
			dialogButton(actions, "Apply", true, function () {
				var ns = nsInput.value.trim();
				if (!validNamespace(ns)) {
					msg.show("namespace must match [a-z0-9_.-]+", true);
					return;
				}
				project.name = nameInput.value.trim() || "Untitled project";
				project.namespace = ns;
				project.defaults = project.defaults || {};
				var duration = parseNumber(durInput.value);
				if (duration != null) {
					project.defaults.duration = duration;
				}
				var easing = easeInput.value.trim();
				if (easing) {
					project.defaults.easing = easing;
				}
				setProjectName();
				commitHistory();
				status("project: " + project.name + " (" + project.namespace + ")");
				close();
			});
		});
	}

	function openOpenDialog() {
		openModal("Open datapack", function (body, close) {
			var pathInput = dialogInput(body, "Pack folder or zip", "", "C:\\...\\vfx_demos");
			var msg = dialogMessage(body);
			var actions = dialogActions(body);
			dialogButton(actions, "Cancel", false, close);
			dialogButton(actions, "Open", true, function () {
				var path = pathInput.value.trim();
				if (!path) {
					msg.show("enter a pack folder or zip path", true);
					return;
				}
				close();
				importPack(path);
			});
		});
	}

	/* ------------------------------------------------------------------ */
	/* Export dialog (design §15.6)                                        */
	/* ------------------------------------------------------------------ */

	/** Every effect tab + the sidecar, or null when there is nothing to export. */
	function exportPayload() {
		var effects = gatherEffects();
		if (Object.keys(effects).length === 0) {
			return null;
		}
		return { namespace: project.namespace, effects: effects, sidecar: buildSidecar() };
	}

	/** Open the single export dialog with its three targets. */
	function openExportDialog() {
		openModal("Export " + (project.namespace || "project"), function (body, close) {
			var tabs = document.createElement("div");
			tabs.className = "vfx-export-tabs";
			body.appendChild(tabs);

			var pane = document.createElement("div");
			pane.className = "vfx-export-pane";
			body.appendChild(pane);

			var msg = dialogMessage(body);

			function target(buttonLabel, view) {
				var button = document.createElement("button");
				button.type = "button";
				button.className = "vfx-export-tab";
				button.textContent = buttonLabel;
				button.addEventListener("click", function () {
					Array.prototype.forEach.call(tabs.children, function (child) {
						child.classList.toggle("active", child === button);
					});
					pane.textContent = "";
					msg.hide();
					view();
				});
				tabs.appendChild(button);
				return button;
			}

			function exportToTarget(destination, path) {
				var payload = exportPayload();
				if (!payload) {
					msg.show("no effect tabs to export", true);
					return;
				}
				msg.show("exporting to " + destination + "…", false);
				window.VFXBridge.post("/export", window.VFXIR.exportTargetBody(
						payload.namespace, destination, path, payload.effects, payload.sidecar)).then(function (result) {
					if (!result || result.ok === false) {
						msg.show("export failed: " + ((result && (result.detail || result.error)) || "no response"), true);
						return;
					}
					pushRecent(result.path || path);
					msg.show("exported → " + (result.path || path), false);
				});
			}

			// ZIP: stage on the bridge, then navigate to the download URL (native OS save dialog).
			function viewZip() {
				var intro = document.createElement("p");
				intro.textContent = "Build a .zip and download it via your browser's save dialog.";
				pane.appendChild(intro);
				var actions = dialogActions(pane);
				dialogButton(actions, "Download .zip", true, function () {
					var payload = exportPayload();
					if (!payload) {
						msg.show("no effect tabs to export", true);
						return;
					}
					msg.show("building zip…", false);
					window.VFXBridge.post("/export/zip", window.VFXIR.exportZipBody(
							payload.namespace, payload.effects, payload.sidecar)).then(function (result) {
						if (!result || result.ok === false || !result.id) {
							msg.show("zip failed: " + ((result && (result.detail || result.error)) || "no response"), true);
							return;
						}
						msg.show("zip ready (" + result.size + " bytes) — starting download…", false);
						window.location.href = window.VFXBridge.url(
								"/export/download?id=" + encodeURIComponent(result.id));
					});
				});
			}

			// Folder: native directory picker (bridge), with a manual path fallback.
			function viewFolder() {
				var pathInput = dialogInput(pane, "Folder", "", "C:\\...\\export");
				var actions = dialogActions(pane);
				dialogButton(actions, "Choose…", false, function () {
					msg.show("waiting for the folder picker…", false);
					window.VFXBridge.post("/pick-folder", {}).then(function (result) {
						if (result && result.ok && result.path) {
							pathInput.value = result.path;
							msg.show("folder: " + result.path, false);
						} else {
							msg.show("native picker unavailable ("
									+ ((result && result.error) || "no response")
									+ ") — enter a path manually", true);
							pathInput.focus();
						}
					});
				});
				dialogButton(actions, "Export", true, function () {
					var path = pathInput.value.trim();
					if (!path) {
						msg.show("choose or enter a folder", true);
						return;
					}
					exportToTarget("folder", path);
				});
			}

			// World: pick a save from the bridge list, confirm overwrite, export there.
			function viewWorld() {
				var row = document.createElement("label");
				row.className = "vfx-dialog-field";
				var span = document.createElement("span");
				span.textContent = "World";
				var select = document.createElement("select");
				row.appendChild(span);
				row.appendChild(select);
				pane.appendChild(row);
				var rootPath = "";
				select.disabled = true;
				var loading = document.createElement("option");
				loading.textContent = "loading…";
				loading.value = "";
				select.appendChild(loading);
				window.fetch(window.VFXBridge.url("/worlds")).then(function (response) {
					return response.json();
				}).then(function (data) {
					select.textContent = "";
					select.disabled = false;
					rootPath = data && data.root ? String(data.root) : "";
					var worlds = data && Array.isArray(data.worlds) ? data.worlds : [];
					if (!worlds.length) {
						var none = document.createElement("option");
						none.textContent = "(no worlds found)";
						none.value = "";
						select.appendChild(none);
						return;
					}
					worlds.forEach(function (name) {
						var opt = document.createElement("option");
						opt.value = name;
						opt.textContent = name;
						select.appendChild(opt);
					});
				}).catch(function () {
					select.textContent = "";
					var failed = document.createElement("option");
					failed.textContent = "(could not list worlds)";
					failed.value = "";
					select.appendChild(failed);
				});
				var actions = dialogActions(pane);
				dialogButton(actions, "Export", true, function () {
					var world = select.value;
					if (!world || !rootPath) {
						msg.show("pick a world first", true);
						return;
					}
					var dir = rootPath.replace(/[\\/]+$/, "") + "/" + world;
					if (!window.confirm("Export into world \"" + world + "\"? Existing files for this "
							+ "namespace will be overwritten.")) {
						return;
					}
					exportToTarget("world", dir);
				});
			}

			var zipButton = target("ZIP", viewZip);
			target("Folder", viewFolder);
			target("World", viewWorld);
			zipButton.classList.add("active");
			viewZip();
		});
	}

	/* ------------------------------------------------------------------ */
	/* Export / import transport                                           */
	/* ------------------------------------------------------------------ */

	/** Backward-compatible entry point: opens the unified export dialog. */
	function exportPack() {
		if (!window.VFXBridge) {
			status("export: the bridge is not available");
			return;
		}
		openExportDialog();
	}

	/** Import a datapack folder or zip: rebuild one tab per effect and restore the sidecar layout. */
	function importPack(path) {
		var bridge = window.VFXBridge;
		if (!bridge) {
			status("import: the bridge is not available");
			return;
		}
		if (!path) {
			openOpenDialog();
			return;
		}
		status("importing " + shortPath(path) + "…");
		bridge.post("/import", { path: path }).then(function (result) {
			if (!result || result.ok === false) {
				status("import failed: " + ((result && (result.detail || result.error)) || "no response"));
				return;
			}
			var namespace = result.namespace || "";
			var before = project.tabs.length;
			var created = openPack(namespace, result.effects || {});
			applySidecar(result.sidecar, project.tabs.slice(before));
			pushRecent(path);
			status("imported " + created + " effect" + (created === 1 ? "" : "s")
					+ (namespace ? " from " + namespace : ""));
		});
	}

	/**
	 * Open a pack: one tab per effect. {@code effects} is the `/import` shape
	 * (`{ "<name>": "<effect JSON string>" }`); the effect's `type` selects the
	 * node type, the tab is named `<namespace>:<name>`, and the effect's params,
	 * keys and value graph are restored onto the tab.
	 *
	 * @param {string} namespace the pack namespace
	 * @param {Object|Array} effects name -> raw effect JSON (object or string)
	 * @returns {number} the number of tabs created
	 */
	function openPack(namespace, effects) {
		var created = 0;
		var entries = [];
		if (Array.isArray(effects)) {
			effects.forEach(function (value, index) {
				entries.push([value && value.name ? value.name : String(index), value]);
			});
		} else if (effects && typeof effects === "object") {
			Object.keys(effects).forEach(function (key) {
				entries.push([key, effects[key]]);
			});
		}
		// The parsed definitions by file name, so a collection can resolve its child files.
		var defs = {};
		entries.forEach(function (entry) {
			defs[entry[0]] = parseEffect(entry[1]);
		});
		var localName = function (id) {
			var text = String(id);
			var colon = text.indexOf(":");
			return colon >= 0 ? text.substring(colon + 1) : text;
		};
		var resolve = function (id) {
			return defs[localName(id)] || null;
		};
		// Child definitions consumed by a collection become chain nodes, not their own tabs.
		var consumed = {};
		Object.keys(defs).forEach(function (name) {
			var def = defs[name];
			if (def && def.type === "collection" && Array.isArray(def.effects)) {
				def.effects.forEach(function (child) {
					if (child && child.effect != null && defs[localName(child.effect)]) {
						consumed[localName(child.effect)] = true;
					}
				});
			}
		});
		entries.forEach(function (entry) {
			if (consumed[entry[0]]) {
				return;
			}
			var def = defs[entry[0]];
			var tab = newTab(namespace ? namespace + ":" + entry[0] : entry[0], null);
			hydrateTab(tab, def, resolve);
			materializeGraph(tab);
			created++;
		});
		return created;
	}

	var VFXEditor = {
		project: project,
		openPack: openPack,
		newTab: newTab,
		exportPack: exportPack,
		importPack: importPack,
		openExportDialog: openExportDialog,
		openFileMenu: openFileMenu,
		openSettingsDialog: openSettingsDialog,
		openNewProjectDialog: openNewProjectDialog,
		newProject: newProject,
		setProjectName: setProjectName,
		validNamespace: validNamespace,
		renderInspector: renderInspector,
		renderTimeline: renderTimeline,
		status: status,
		schema: schema,
		token: token,
		url: url,
		assetUrl: assetUrl,
		valueKinds: VALUE_KINDS,
		addValueNode: addValueNode,
		materializeGraph: materializeGraph,
		commit: commitHistory,
		focusNode: focusNode,
		slot: slot,
		showMenu: showMenu,
		undo: undoHistory,
		redo: redoHistory
	};
	Object.defineProperty(VFXEditor, "graph", {
		enumerable: true,
		get: function () {
			var tab = activeTab();
			return tab ? tab.graph : null;
		}
	});
	Object.defineProperty(VFXEditor, "stack", {
		enumerable: true,
		get: function () {
			return history();
		}
	});
	Object.defineProperty(VFXEditor, "bridge", {
		enumerable: true,
		get: function () {
			return window.VFXBridge || null;
		}
	});
	window.VFXEditor = VFXEditor;

	/* ------------------------------------------------------------------ */
	/* Boot                                                                */
	/* ------------------------------------------------------------------ */

	function resizeCanvas() {
		var width = dom.wrap.clientWidth;
		var height = dom.wrap.clientHeight;
		if (width > 0 && height > 0) {
			dom.canvas.width = width;
			dom.canvas.height = height;
		}
	}

	/** The revision-status badge in the top bar: "rev N → in game", offline, syncing or error. */
	function updateRevBadge(state) {
		if (!dom.revbadge) {
			return;
		}
		var rev = Number(project.revision) || 0;
		if (state === "offline") {
			dom.revbadge.textContent = "offline";
			dom.revbadge.className = "vfx-badge offline";
		} else if (state === "error") {
			dom.revbadge.textContent = "rev " + rev + " — error";
			dom.revbadge.className = "vfx-badge error";
		} else if (state === "syncing") {
			dom.revbadge.textContent = "rev " + rev + " — syncing…";
			dom.revbadge.className = "vfx-badge syncing";
		} else {
			dom.revbadge.textContent = "rev " + rev + " → in game";
			dom.revbadge.className = "vfx-badge";
		}
	}

	/** Start the bridge (SSE + reconnect) and surface its health in the status line + badge. */
	function wireBridge() {
		var bridge = window.VFXBridge;
		if (!bridge) {
			return;
		}
		bridge.on("transport-error", function (message) {
			status("bridge: " + message);
			updateRevBadge("offline");
		});
		bridge.on("error", function (data) {
			status("bridge: " + ((data && (data.detail || data.error)) || "error"));
			updateRevBadge("error");
		});
		bridge.on("revision", function () {
			updateRevBadge();
		});
		bridge.on("reconcile", function (info) {
			status("re-syncing with the game (revision " + info.game + ")");
			updateRevBadge("syncing");
		});
		bridge.on("open", function () {
			updateRevBadge();
		});
		bridge.on("disconnect", function () {
			updateRevBadge("offline");
		});
		bridge.connect(token);
	}

	/** Whether a key event targets a text field, where the browser's own undo must win. */
	function isEditable(target) {
		if (!target || target.nodeType !== 1) {
			return false;
		}
		var tag = target.tagName ? target.tagName.toLowerCase() : "";
		return tag === "input" || tag === "textarea" || target.isContentEditable === true;
	}

	/* ------------------------------------------------------------------ */
	/* Hotkeys (design §15.6/b)                                            */
	/* ------------------------------------------------------------------ */

	/** The selected value nodes on the active tab (the effect root is never selected for ops). */
	function selectedNodes() {
		var out = [];
		if (!scene || !scene.selected_nodes) {
			return out;
		}
		Object.keys(scene.selected_nodes).forEach(function (id) {
			out.push(scene.selected_nodes[id]);
		});
		return out;
	}

	/** Delete the selected nodes; deleting the effect root empties the tab (fix 1). */
	function deleteSelection() {
		var tab = activeTab();
		if (!tab) {
			return;
		}
		var changed = false;
		selectedNodes().forEach(function (node) {
			if (isEffectNode(node)) {
				// Delete one chain node (design §15.2, P6); the chain and its flow links are rewired.
				tab.removeEffect(node.properties.effectId);
				node.ignore_remove = false;
				tab.graph.remove(node);
				changed = true;
			} else if (node.properties && node.properties.kind) {
				tab.graph.remove(node);
				changed = true;
			}
		});
		if (changed) {
			refreshFromCanvas(tab);
			rewireChain(tab);
			renderInspector();
			renderTimeline();
			showEmptyPlaceholder();
			commitHistory();
			if (tab.effects && tab.effects.length) {
				requestStructural(tab);
			}
		}
	}

	/** The module-level value-node clipboard (`{ nodes, edges }` of IR fragments). */
	var clipboard = null;

	/** Copy the selected value nodes (with the wires wholly inside the selection). */
	function copySelection() {
		var tab = activeTab();
		var nodes = selectedNodes().filter(function (node) {
			return tab && !isEffectNode(node) && node.properties && node.properties.kind;
		});
		if (!nodes.length) {
			return;
		}
		var index = {};
		nodes.forEach(function (node, i) {
			index[node.id] = i;
		});
		var edges = [];
		Object.keys(tab.graph.links).forEach(function (linkId) {
			var link = tab.graph.links[linkId];
			var target = tab.graph.getNodeById(link.target_id);
			if (index[link.origin_id] != null && target && index[target.id] != null) {
				var slot = target.inputs ? target.inputs[link.target_slot] : null;
				if (slot) {
					edges.push({ from: index[link.origin_id], to: index[target.id], input: slot.name });
				}
			}
		});
		clipboard = {
			nodes: nodes.map(function (node) {
				return nodeToIR(node);
			}),
			edges: edges
		};
		status("copied " + clipboard.nodes.length + " node" + (clipboard.nodes.length === 1 ? "" : "s"));
	}

	/** Paste the clipboard into the active tab as new nodes (fresh ids, offset positions). */
	function pasteClipboard() {
		var tab = activeTab();
		if (!tab || !clipboard || !clipboard.nodes.length) {
			return;
		}
		applyingGraph = true;
		var created = [];
		try {
			clipboard.nodes.forEach(function (ir) {
				var kind = ir.kind;
				var registered = "vfx/value/" + kind;
				if (!LiteGraph.registered_node_types[registered]) {
					created.push(null);
					return;
				}
				var node = LiteGraph.createNode(registered);
				node.__tab = tab;
				node.properties.irId = uniqueNodeId(tab, kind);
				var copy = snapshot(ir);
				copy.id = node.properties.irId;
				copy.pos = [num(ir.pos ? ir.pos[0] : 0) + 30, num(ir.pos ? ir.pos[1] : 0) + 30];
				applyIR(node, copy);
				node.pos = copy.pos.slice();
				tab.graph.add(node);
				created.push(node);
			});
		} finally {
			applyingGraph = false;
		}
		clipboard.edges.forEach(function (edge) {
			var from = created[edge.from];
			var to = created[edge.to];
			var slot = to ? inputIndex(to, edge.input) : -1;
			if (from && to && slot >= 0) {
				from.connect(0, to, slot);
			}
		});
		syncAllSlots(tab);
		var fresh = created.filter(Boolean);
		if (scene) {
			scene.selectNodes(fresh);
		}
		refreshFromCanvas(tab);
		commitHistory();
		if (tab.type) {
			requestStructural(tab);
		}
	}

	/** Duplicate the selection in place (copy + paste as one action). */
	function duplicateSelection() {
		copySelection();
		pasteClipboard();
	}

	/** Select every node on the active graph. */
	function selectAllNodes() {
		if (scene) {
			scene.selectNodes();
		}
	}

	/** Frame the given nodes (or all when none): set the pan offset and zoom to fit. */
	function frameNodes(nodes) {
		if (!scene || !nodes || !nodes.length) {
			return;
		}
		var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
		nodes.forEach(function (node) {
			var size = node.size || [120, 40];
			minX = Math.min(minX, node.pos[0]);
			minY = Math.min(minY, node.pos[1] - LiteGraph.NODE_TITLE_HEIGHT);
			maxX = Math.max(maxX, node.pos[0] + size[0]);
			maxY = Math.max(maxY, node.pos[1] + size[1]);
		});
		var pad = 60;
		var width = Math.max(1, maxX - minX);
		var height = Math.max(1, maxY - minY);
		var scale = Math.min(scene.canvas.width / (width + pad * 2),
				scene.canvas.height / (height + pad * 2));
		scene.ds.scale = Math.max(0.2, Math.min(2, scale));
		scene.ds.offset[0] = scene.canvas.width / (2 * scene.ds.scale) - (minX + maxX) / 2;
		scene.ds.offset[1] = scene.canvas.height / (2 * scene.ds.scale) - (minY + maxY) / 2;
		scene.setDirty(true, true);
	}

	/** Frame the selected nodes, else everything on the active graph. */
	function frameSelected() {
		var tab = activeTab();
		var selected = selectedNodes();
		frameNodes(selected.length ? selected : (tab && tab.graph ? tab.graph._nodes : []));
	}

	/** Frame every node on the active graph. */
	function frameAll() {
		var tab = activeTab();
		if (tab && tab.graph) {
			frameNodes(tab.graph._nodes);
		}
	}

	/**
	 * Group the selected nodes on the canvas (design §15.6 Ctrl+G). Groups are a canvas-only
	 * convenience: they are not part of the effect IR, so they are not exported and not undoable.
	 */
	function groupSelection() {
		var nodes = selectedNodes();
		if (!scene || !nodes.length) {
			return;
		}
		var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
		nodes.forEach(function (node) {
			minX = Math.min(minX, node.pos[0]);
			minY = Math.min(minY, node.pos[1] - LiteGraph.NODE_TITLE_HEIGHT);
			maxX = Math.max(maxX, node.pos[0] + node.size[0]);
			maxY = Math.max(maxY, node.pos[1] + node.size[1]);
		});
		var group = new LiteGraph.LGraphGroup();
		group.pos = [minX - 12, minY - 24];
		group.size = [maxX - minX + 24, maxY - minY + 36];
		group.title = "group";
		scene.graph.add(group);
		scene.setDirty(true, true);
	}

	/** Open LiteGraph's node search box at the canvas centre (Tab). */
	function openNodeSearch() {
		if (!scene || typeof scene.showSearchBox !== "function") {
			return;
		}
		var rect = dom.canvas.getBoundingClientRect();
		scene.showSearchBox({
			clientX: rect.left + rect.width / 2,
			clientY: rect.top + rect.height / 2,
			preventDefault: function () {},
			stopPropagation: function () {}
		});
	}

	/** Toggle the keyboard-shortcut help overlay (?). */
	function toggleHelp() {
		var node = document.getElementById("vfx-hotkeys");
		if (!node) {
			node = document.createElement("div");
			node.id = "vfx-hotkeys";
			node.className = "vfx-hotkeys";
			var rows = [				["Del / Backspace", "delete selected nodes (empties the tab on the effect node)"],
				["Ctrl+C / Ctrl+V / Ctrl+D", "copy / paste / duplicate"],
				["Ctrl+A", "select all"],
				["Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y", "undo / redo"],
				["Space", "play or stop the preview"],
				["R", "replay from the start"],
				["K", "add a keyframe at the playhead on the selected slot"],
				["F / Home", "frame selected / frame all"],
				["Ctrl+S", "save (compile + preview)"],
				["Tab / double-click", "node search-add"],
				["Ctrl+G", "group selection (canvas only)"],
				["[ / ]", "previous / next tab"],
				["MMB / Space+drag", "pan the canvas"],
				["Ctrl+wheel", "zoom"],
				["?", "toggle this help"]
			];
			var list = document.createElement("ul");
			rows.forEach(function (row) {
				var li = document.createElement("li");
				var key = document.createElement("b");
				key.textContent = row[0];
				li.appendChild(key);
				li.appendChild(document.createTextNode(" — " + row[1]));
				list.appendChild(li);
			});
			var title = document.createElement("h2");
			title.textContent = "Keyboard shortcuts";
			node.appendChild(title);
			node.appendChild(list);
			document.body.appendChild(node);
			node.hidden = false;
			return;
		}
		node.hidden = !node.hidden;
	}

	/** Space: play the active tab, or stop it when already playing. */
	function transportToggle() {
		var tab = activeTab();
		if (!tab || !tab.type || !window.VFXTimeline) {
			return;
		}
		if (typeof window.VFXTimeline.isPlaying === "function" && window.VFXTimeline.isPlaying()) {
			window.VFXTimeline.stop(tab);
		} else {
			window.VFXTimeline.play(tab);
		}
	}

	/** Add a keyframe at the playhead on the inspector's selected slot (K) — the diamond's K twin. */
	function addKeyAtPlayhead() {
		var tab = activeTab();
		if (!tab || !tab.type || !inspectorName) {
			return;
		}
		// A selected vector group keys every component (design §15.9.6/P4), never a group pseudo-name.
		selectedParamNames(tab, inspectorName).forEach(function (name) {
			slotAnimate(tab, name, null);
		});
		commitHistory();
		renderTimeline();
	}

	/**
	 * Ask for a structural recompile (revision++ + replay). Prefers the timeline's debounced
	 * `structural`; falls back to the bridge's full `/compile` so a hotkey never throws when the
	 * timeline module is absent.
	 *
	 * @param {Object} tab the tab that changed
	 */
	function requestStructural(tab) {
		if (window.VFXTimeline && typeof window.VFXTimeline.structural === "function") {
			window.VFXTimeline.structural(tab);
		} else if (window.VFXBridge && typeof window.VFXBridge.fullCompile === "function") {
			window.VFXBridge.fullCompile();
		}
	}

	/** Switch to the previous/next tab, wrapping ([/]). */
	function switchTab(delta) {
		if (project.tabs.length < 2) {
			return;
		}
		var next = (project.active + delta + project.tabs.length) % project.tabs.length;
		selectTab(next);
	}

	/** Save: compile and play the active tab (Ctrl+S). */
	function saveProject() {
		var tab = activeTab();
		if (tab && tab.type && window.VFXTimeline) {
			window.VFXTimeline.play(tab);
			status("saved / previewing " + tab.name);
		}
	}

	/**
	 * Wire the editor keyboard shortcuts. Runs in the capture phase so it can pre-empt LiteGraph's
	 * own canvas handlers for the keys we own; text fields always keep the browser's defaults.
	 */
	function wireHotkeys() {
		var spaceDown = false;
		var spaceDragged = false;

		// A Space+drag pan counts as a drag only when the primary mouse button is actually held; a
		// tap with a pixel of pointer drift must still toggle the transport.
		document.addEventListener("mousemove", function (event) {
			if (spaceDown && (event.buttons & 1)) {
				spaceDragged = true;
			}
		});

		document.addEventListener("keydown", function (event) {
			if (isEditable(event.target)) {
				return;
			}
			var key = String(event.key || "");
			var lower = key.toLowerCase();
			var mod = event.ctrlKey || event.metaKey;
			var handled = true;

			if (mod && lower === "z" && !event.shiftKey) {
				undoHistory();
			} else if (mod && (lower === "y" || (lower === "z" && event.shiftKey))) {
				redoHistory();
			} else if (mod && lower === "c") {
				copySelection();
			} else if (mod && lower === "v") {
				pasteClipboard();
			} else if (mod && lower === "d") {
				duplicateSelection();
			} else if (mod && lower === "a") {
				selectAllNodes();
			} else if (mod && lower === "s") {
				saveProject();
			} else if (mod && lower === "g") {
				groupSelection();
			} else if (key === "Delete" || key === "Backspace") {
				deleteSelection();
			} else if (key === "Tab") {
				openNodeSearch();
			} else if (key === "[") {
				switchTab(-1);
			} else if (key === "]") {
				switchTab(1);
			} else if (key === "?" || (event.shiftKey && key === "/")) {
				toggleHelp();
			} else if (lower === "r" && !mod) {
				var tab = activeTab();
				if (tab && tab.type && window.VFXTimeline) {
					window.VFXTimeline.play(tab);
				}
			} else if (lower === "k" && !mod) {
				addKeyAtPlayhead();
			} else if (lower === "f" && !mod) {
				frameSelected();
			} else if (key === "Home") {
				frameAll();
			} else if (key === " " && !mod) {
				// Let keydown reach LiteGraph for Space+drag pan; toggle on a tap-release.
				if (!spaceDown) {
					spaceDown = true;
					spaceDragged = false;
				}
				handled = false;
			} else {
				handled = false;
			}

			if (handled) {
				event.preventDefault();
				event.stopPropagation();
			}
		}, true);

		document.addEventListener("keyup", function (event) {
			if (event.key !== " " || isEditable(event.target)) {
				return;
			}
			var wasDragged = spaceDragged;
			spaceDown = false;
			if (!wasDragged) {
				transportToggle();
			}
		}, true);
	}

	/**
	 * Replace LiteGraph's floating value dialog with an inline, select-on-focus editor positioned over
	 * the widget (design §15.1, fix 5). Only the number widget's prompt is intercepted; every other
	 * prompt (node titles, string widgets) keeps the stock behaviour.
	 */
	function wireInlineWidgetEdit() {
		if (!window.LGraphCanvas || LGraphCanvas.prototype.__vfxInlineEdit) {
			return;
		}
		LGraphCanvas.prototype.__vfxInlineEdit = true;
		var original = LGraphCanvas.prototype.prompt;
		LGraphCanvas.prototype.prompt = function (title, value, callback, event, multiline) {
			var pair = this.node_widget;
			if (!multiline && event && pair && pair[1] && pair[1].type === "number") {
				inlineEditNumber(this, pair[0], pair[1], value, callback, event);
				return;
			}
			return original.apply(this, arguments);
		};
	}

	/**
	 * The DOM input shown *inside* the canvas wrapper, sized and styled to sit exactly over the
	 * number widget it edits (so typing reads as editing the field, not a popup). Positioned with
	 * LiteGraph's own `convertOffsetToCanvas`, which yields canvas-pixel coordinates relative to the
	 * canvas top-left — the same origin as the `position:relative` wrapper the canvas fills. Commits
	 * on Enter/blur, cancels on Escape.
	 */
	function inlineEditNumber(canvas, node, widget, value, callback, event) {
		var scale = canvas.ds && isFiniteNumber(Number(canvas.ds.scale)) && canvas.ds.scale > 0
				? Number(canvas.ds.scale) : 1;
		var offset = canvas.ds && canvas.ds.offset ? canvas.ds.offset : [0, 0];
		var host = (canvas.canvas && canvas.canvas.parentNode) || document.body;
		var widgetWidth = widget.width || node.size[0];
		var lastY = widget.last_y != null ? Number(widget.last_y) : 0;
		var graphPos = [node.pos[0], node.pos[1] + lastY];
		var topLeft;
		if (typeof canvas.convertOffsetToCanvas === "function") {
			topLeft = canvas.convertOffsetToCanvas(graphPos);
		} else if (window.VFXIR && typeof window.VFXIR.graphToCanvas === "function") {
			topLeft = window.VFXIR.graphToCanvas(graphPos, offset, scale);
		} else {
			topLeft = [(graphPos[0] + offset[0]) * scale, (graphPos[1] + offset[1]) * scale];
		}
		// LiteGraph draws the widget box from x = 15 to width - 15, at height NODE_WIDGET_HEIGHT.
		var margin = 15;
		var rowHeight = (window.LiteGraph && LiteGraph.NODE_WIDGET_HEIGHT
				? LiteGraph.NODE_WIDGET_HEIGHT : 20) * scale;

		var input = document.createElement("input");
		input.type = "text";
		input.className = "vfx-inline-edit";
		input.value = value;
		input.title = widget.name;
		input.style.left = (topLeft[0] + margin * scale) + "px";
		input.style.top = topLeft[1] + "px";
		input.style.width = Math.max(30, (widgetWidth - margin * 2) * scale) + "px";
		input.style.height = rowHeight + "px";
		input.style.fontSize = Math.max(8, 14 * scale) + "px";
		host.appendChild(input);
		input.focus();
		input.select();

		function done(commit) {
			input.removeEventListener("keydown", onKey);
			input.removeEventListener("blur", done);
			if (commit) {
				// A strict decimal number only; no eval of the field's text (the stock LiteGraph
				// prompt evaluated it, which is an injection sink).
				var n = parseNumber(input.value);
				if (callback && n != null) {
					callback(n);
				}
			}
			if (input.parentNode) {
				input.parentNode.removeChild(input);
			}
			canvas.canvas.focus();
			canvas.setDirty(true, true);
		}

		function onKey(e) {
			if (e.keyCode === 13) {
				e.preventDefault();
				done(true);
			} else if (e.keyCode === 27) {
				e.preventDefault();
				done(false);
			}
		}

		input.addEventListener("keydown", onKey);
		input.addEventListener("blur", function () {
			done(true);
		});
	}

	function boot() {
		if (typeof window.LiteGraph === "undefined"
				|| typeof window.LGraph === "undefined"
				|| typeof window.LGraphCanvas === "undefined"
				|| typeof window.fetch === "undefined"
				|| !window.HTMLCanvasElement
				|| !dom.canvas.getContext) {
			showBanner("Unsupported browser: the LiteGraph canvas failed to load. "
					+ "Use a current Chrome or Firefox and reload the page.");
			return;
		}

		resizeCanvas();
		scene = new LiteGraph.LGraphCanvas(dom.canvas, new LiteGraph.LGraph());
		// Intercept the node right-click: a value-node field shows the slot menu (replace with
		// expr/bind, animate, disconnect), anything else keeps LiteGraph's own menu.
		var originalContextMenu = scene.processContextMenu;
		scene.processContextMenu = function (node, event) {
			var tab = activeTab();
			if (node && tab && !isEffectNode(node) && node.__kindSpec && node.__widgets) {
				var input = fieldAtEvent(node, event);
				if (input) {
					openFieldMenu(node, input, event);
					return;
				}
			}
			if (typeof originalContextMenu === "function") {
				originalContextMenu.call(scene, node, event);
			}
		};
		window.addEventListener("resize", resizeCanvas);
		wireInlineWidgetEdit();
		wireHotkeys();
		wireBridge();
		resetHistory();

		// Selecting an effect node makes it the active effect (its params show in the inspector).
		scene.onNodeSelected = function (node) {
			var tab = activeTab();
			if (!tab || !isEffectNode(node)) {
				return;
			}
			var effect = window.VFXIR && typeof window.VFXIR.effectById === "function"
					? window.VFXIR.effectById(tab, node.properties.effectId) : null;
			if (!effect) {
				return;
			}
			tab.activeEffect = indexOfEffect(tab, effect.id);
			tab.root = node;
			var descriptor = schema[tab.type];
			if (descriptor && descriptor.params && descriptor.params.length) {
				inspectorName = descriptor.params[0].name;
			}
			renderInspector();
			renderTimeline();
			renderTabs();
		};
		// A dragged node's new position is persisted to the IR (and is undoable like any graph edit).
		scene.onNodeMoved = function (node) {
			if (!applyingGraph && node && node.__tab) {
				scheduleGraphEdit(node.__tab);
			}
		};

		// Value node types are fixed by the format, so they are offered even before /schema resolves.
		registerValueNodeTypes();
		buildValuePalette();

		dom.newTab.addEventListener("click", function () {
			newTab("untitled", null);
		});
		dom.filter.addEventListener("input", function () {
			filterPalette(dom.filter.value);
		});
		dom.tabName.addEventListener("change", function () {
			var tab = activeTab();
			var value = dom.tabName.value.trim();
			if (tab && value) {
				tab.name = value;
				renderTabs();
				status(tab.name);
				commitHistory();
			}
		});
		if (dom.file) {
			dom.file.addEventListener("click", openFileMenu);
		}
		if (dom.projectBtn) {
			dom.projectBtn.addEventListener("click", openSettingsDialog);
		}
		if (dom.play) {
			dom.play.addEventListener("click", transportToggle);
		}
		if (dom.stop) {
			dom.stop.addEventListener("click", function () {
				var tab = activeTab();
				if (tab && window.VFXTimeline) {
					window.VFXTimeline.stop(tab);
				}
			});
		}
		if (dom.replay) {
			dom.replay.addEventListener("click", function () {
				var tab = activeTab();
				if (tab && tab.type && window.VFXTimeline) {
					window.VFXTimeline.play(tab);
				}
			});
		}
		setProjectName();
		updateRevBadge();

		status("Loading schema…");
		window.fetch(url("/schema"))
				.then(function (response) {
					if (!response.ok) {
						throw new Error("GET /schema -> " + response.status);
					}
					return response.json();
				})
				.then(function (payload) {
					schema = payload && typeof payload === "object" ? payload : {};
					VFXEditor.schema = schema;
					registerNodeTypes();
					buildPalette();
					status("Loaded " + Object.keys(schema).length + " effect types");
					renderInspector();
					renderTimeline();
					showEmptyPlaceholder();
				})
				.catch(function (error) {
					showBanner("Could not load /schema from the game bridge: " + error.message);
				});
	}

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", boot);
	} else {
		boot();
	}
})();

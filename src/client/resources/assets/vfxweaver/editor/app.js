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
		project: document.getElementById("vfx-project"),
		tabName: document.getElementById("vfx-tab-name"),
		exportDest: document.getElementById("vfx-export-dest"),
		exportPath: document.getElementById("vfx-export-path"),
		exportBtn: document.getElementById("vfx-export"),
		importPath: document.getElementById("vfx-import-path"),
		importBtn: document.getElementById("vfx-import")
	};

	function showBanner(message) {
		dom.banner.textContent = message;
		dom.banner.hidden = false;
	}

	function status(message) {
		dom.status.textContent = message || "";
	}

	/**
	 * The project model. Minimal for the shell; Task 13 replaces the tab objects
	 * with the real IR (`ir.js`).
	 *
	 * @type {{revision: number, tabs: Array, active: number}}
	 */
	var project = { revision: 0, tabs: [], active: -1 };

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
	function makeEffectNode(typeName, descriptor) {
		var params = (descriptor && descriptor.params) || [];

		function EffectNode() {
			for (var i = 0; i < params.length; i++) {
				var param = params[i];
				var slot = this.addInput(param.name, socketType(param));
				if (slot) {
					slot.color_on = groupColor(param.group);
					slot.color_off = slot.color_on;
				}
			}
			this.properties = { effectType: typeName };
		}

		EffectNode.title = typeName;
		EffectNode.desc = "VFXweaver effect: " + typeName;
		return EffectNode;
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
		var graph = newGraph();
		// The IR `Tab` (Task 13) carries params/keys/valueNodes/edges; fall back to an
		// equivalent plain object when ir.js has not loaded (e.g. a broken asset chain).
		var tab = window.VFXIR
				? new window.VFXIR.Tab(name, type)
				: { name: name || "untitled", type: null, duration: 40, easing: "linear",
					params: {}, valueNodes: [], edges: [], keys: {} };
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
		return tab;
	}

	/** Put the effect node of {@code type} on {@code tab}'s graph as its root. */
	function attachEffect(tab, type) {
		var registered = "vfx/effect/" + type;
		if (!LiteGraph.registered_node_types[registered]) {
			status("unknown effect type: " + type);
			return null;
		}
		var node = LiteGraph.createNode(registered);
		node.pos = [80, 80];
		node.ignore_remove = true;
		node.__tab = tab;
		tab.graph.add(node);
		tab.type = type;
		tab.root = node;
		return node;
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
			return;
		}
		if (wasActive) {
			// Select the neighbour now at this index (the next tab, or the new last).
			selectTab(Math.min(index, project.tabs.length - 1));
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

	/** Fill the active empty tab, else open a new tab of that effect type. */
	function chooseType(typeName) {
		var tab = activeTab();
		if (tab && !tab.type) {
			attachEffect(tab, typeName);
			tab.name = typeName;
			if (dom.tabName) {
				dom.tabName.value = tab.name;
			}
			renderTabs();
			status(tab.name);
			renderInspector();
			renderTimeline();
			return;
		}
		newTab(typeName, typeName);
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

	function isRef(value) {
		return value != null && typeof value === "object" && value.from != null;
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
			return point.easing ? line + " " + point.easing : line;
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
				point.easing = parts[2];
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
		}

		ValueNode.title = kind;
		ValueNode.desc = "VFXweaver value node: " + kind;
		return ValueNode;
	}

	/** A deep JSON snapshot of the tab's value graph. */
	function snapshotIR(tab) {
		return {
			valueNodes: snapshot(tab && tab.valueNodes ? tab.valueNodes : []),
			edges: snapshot(tab && tab.edges ? tab.edges : [])
		};
	}

	/** Restore a graph snapshot into the IR (used by the undo/redo command). */
	function restoreIR(tab, snap) {
		tab.valueNodes = snapshot(snap && snap.valueNodes ? snap.valueNodes : []);
		tab.edges = snapshot(snap && snap.edges ? snap.edges : []);
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

	/** Rebuild `tab.valueNodes`/`tab.edges` from the canvas. */
	function refreshFromCanvas(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		var irByGraphId = {};
		var nodes = [];
		tab.graph._nodes.forEach(function (node) {
			if (node === tab.root || !node.properties || !node.properties.kind || !node.properties.irId) {
				return;
			}
			irByGraphId[node.id] = node.properties.irId;
			nodes.push(nodeToIR(node));
		});
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
			if (target === tab.root) {
				var param = target.inputs ? target.inputs[link.target_slot] : null;
				if (param) {
					edges.push({ from: from, to: param.name });
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
		applyingGraph = true;
		try {
			tab.graph._nodes.slice().forEach(function (node) {
				if (node !== tab.root && node.properties && node.properties.kind) {
					tab.graph.remove(node);
				}
			});
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
				} else if (tab.root) {
					target = tab.root;
					slot = inputIndex(tab.root, edge.to);
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
		var commands = commandStack();
		if (commands) {
			commands["do"]({
				tab: tab,
				graph: true,
				"do": function () {
					restoreIR(tab, after);
				},
				undo: function () {
					restoreIR(tab, pending.before);
				}
			});
		}
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
		tab.graph.onNodeConnectionChange = function () {
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

	/** The undo/redo stack (ir.js `CommandStack`); created lazily once ir.js has loaded. */
	var stack = null;

	function commandStack() {
		if (!stack && window.VFXIR) {
			stack = new window.VFXIR.CommandStack();
			wireHistory(stack);
		}
		return stack;
	}

	/**
	 * A full re-sync after the model moved under an undo/redo (Task 16, design §6.2): the command has
	 * already applied the model snapshot, so we push the whole definition with a full `/compile` —
	 * never an incremental game undo. The command carries the tab it edited, so the recompile targets
	 * that tab even when it is not the active one; the inspector/timeline are only rebuilt for the
	 * active tab (they render whichever tab is selected).
	 *
	 * @param {Object} [command] the undo/redo command that just applied, carrying `tab`
	 */
	function afterHistory(command) {
		var tab = (command && command.tab) || activeTab();
		if (command && command.graph && tab && tab.graph) {
			materializeGraph(tab);
			if (scene && tab === activeTab()) {
				scene.setDirty(true, true);
			}
		}
		if (tab === activeTab()) {
			renderInspector();
			renderTimeline();
		}
		if (tab && tab.type && window.VFXTimeline && typeof window.VFXTimeline.structural === "function") {
			status("history → recompile " + tab.name);
			window.VFXTimeline.structural(tab);
		}
	}

	/**
	 * Wrap a {@link VFXIR.CommandStack} so applying a snapshot via undo/redo also recompiles. The IR
	 * stays the source of truth; this only observes its history transitions.
	 *
	 * @param {Object} commands the command stack
	 */
	function wireHistory(commands) {
		if (!commands || commands.__vfxWired) {
			return;
		}
		commands.__vfxWired = true;
		var undo = commands.undo;
		var redo = commands.redo;
		commands.undo = function () {
			var applied = undo.call(this);
			if (applied) {
				afterHistory(applied);
			}
			return applied;
		};
		commands.redo = function () {
			var applied = redo.call(this);
			if (applied) {
				afterHistory(applied);
			}
			return applied;
		};
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
	 * The inspector edit hook: the inspector has already mutated `tab.params[name]`; record an
	 * undoable command around the new value, surface the form in the status line and hand the edit to
	 * Task 15's live sync (a constant slider -> `set_param`, anything structural -> `/compile`), then
	 * refresh the timeline so a new/edited key lane appears.
	 */
	function onInspectorEdit(name, spec, previous) {
		var tab = activeTab();
		if (!tab) {
			return;
		}
		status("param " + name + " → " + (spec && spec.control ? spec.control : "constant"));
		var next = snapshot(spec);
		var before = snapshot(previous);
		var commands = commandStack();
		if (commands) {
			commands["do"]({
				tab: tab,
				"do": function () {
					if (next == null) {
						delete tab.params[name];
					} else {
						tab.params[name] = next;
					}
				},
				undo: function () {
					if (before == null) {
						delete tab.params[name];
					} else {
						tab.params[name] = before;
					}
				}
			});
		}
		if (window.VFXTimeline && typeof window.VFXTimeline.onEdit === "function") {
			window.VFXTimeline.onEdit(tab, name, spec, previous);
		}
		renderTimeline();
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
		if (!descriptor || !descriptor.params || descriptor.params.length === 0) {
			dom.inspector.appendChild(inspectorMessage(
					!tab ? "No active tab" : (tab.type ? "No editable parameters" : "Choose an effect type")));
			return;
		}
		var params = descriptor.params;
		var current = null;
		for (var i = 0; i < params.length; i++) {
			if (params[i].name === inspectorName) {
				current = params[i];
				break;
			}
		}
		if (!current) {
			current = params[0];
			inspectorName = current.name;
		}
		var heading = document.createElement("h2");
		heading.textContent = "Inspector";
		dom.inspector.appendChild(heading);

		var chooser = document.createElement("div");
		chooser.className = "vfx-params";
		params.forEach(function (param) {
			var item = document.createElement("button");
			item.type = "button";
			item.className = "vfx-param" + (param.name === inspectorName ? " active" : "");
			item.textContent = param.name;
			item.addEventListener("click", function () {
				inspectorName = param.name;
				renderInspector();
			});
			chooser.appendChild(item);
		});
		dom.inspector.appendChild(chooser);
		dom.inspector.appendChild(window.VFXInspector.render(tab, current, onInspectorEdit));
	}

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
	function hydrateTab(tab, def) {
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
		var params = def.params && typeof def.params === "object" ? def.params : {};
		Object.keys(params).forEach(function (name) {
			var raw = params[name];
			if (raw && typeof raw === "object" && Array.isArray(raw.keyframes)) {
				tab.keys[name] = raw.keyframes.map(function (key) {
					var out = { time: num(key.time), value: num(key.value) };
					if (key.easing != null) {
						out.easing = key.easing;
					}
					return out;
				});
				// Keep any `multiply` bound spec on the keyframed param, or a round-trip would drop it.
				tab.params[name] = { control: "keyframes" };
				if (raw.multiply) {
					tab.params[name].multiply = raw.multiply;
				}
			} else {
				tab.params[name] = raw;
			}
		});
		var graph = def.graph && typeof def.graph === "object" ? def.graph : null;
		if (graph && Array.isArray(graph.nodes)) {
			tab.valueNodes = graph.nodes.map(function (node) {
				var out = { id: node.id, kind: node.kind, inputs: node.inputs || {} };
				if (node.op != null) {
					out.op = node.op;
				}
				var meta = graph.meta && graph.meta[node.id];
				if (meta && Array.isArray(meta.pos) && meta.pos.length === 2) {
					out.pos = [num(meta.pos[0]), num(meta.pos[1])];
				}
				return out;
			});
		}
		if (graph && Array.isArray(graph.edges)) {
			tab.edges = graph.edges.map(function (edge) {
				var out = { from: edge.from, to: edge.to };
				if (edge.input != null) {
					out.input = edge.input;
				}
				return out;
			});
		}
		if (def.inputs && typeof def.inputs === "object") {
			Object.keys(def.inputs).forEach(function (param) {
				var wire = def.inputs[param];
				if (wire && wire.from != null) {
					tab.edges.push({ from: wire.from, to: param });
				}
			});
		}
	}

	/** Every tab with an effect type, as `{ name: compiledJsonString }` for `/export`. */
	function gatherEffects() {
		var effects = {};
		project.tabs.forEach(function (tab) {
			if (!tab.type) {
				return;
			}
			var name = safeName(effectName(tab));
			if (name) {
				effects[name] = window.VFXCompiler ? window.VFXCompiler.compileTab(tab) : "{}";
			}
		});
		return effects;
	}

	/** The editor project document: tab layout (root position + timeline zoom) for a round-trip. */
	function buildSidecar() {
		return {
			version: 1,
			tabs: project.tabs.filter(function (tab) {
				return tab.type;
			}).map(function (tab) {
				var entry = { name: safeName(effectName(tab)) };
				if (tab.root && Array.isArray(tab.root.pos)) {
					entry.root = [num(tab.root.pos[0]), num(tab.root.pos[1])];
				}
				if (tab.timeline && isFinite(Number(tab.timeline.pxPerTick))) {
					entry.pxPerTick = Number(tab.timeline.pxPerTick);
				}
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
			if (isFinite(Number(entry.pxPerTick))) {
				if (!tab.timeline) {
					tab.timeline = {};
				}
				tab.timeline.pxPerTick = Number(entry.pxPerTick);
			}
		});
	}

	/** Export every effect tab as a datapack to the chosen destination (world / folder / zip). */
	function exportPack() {
		var bridge = window.VFXBridge;
		if (!bridge) {
			status("export: the bridge is not available");
			return;
		}
		var namespace = safeName(dom.project.value);
		if (!namespace) {
			status("export: enter a project namespace");
			return;
		}
		var destination = dom.exportDest.value;
		var path = dom.exportPath.value.trim();
		if (!path) {
			status("export: enter a destination path");
			return;
		}
		var effects = gatherEffects();
		if (Object.keys(effects).length === 0) {
			status("export: no effect tabs to export");
			return;
		}
		status("exporting " + namespace + "…");
		bridge.post("/export", {
			destination: destination,
			path: path,
			namespace: namespace,
			effects: effects,
			sidecar: buildSidecar()
		}).then(function (result) {
			if (!result || result.ok === false) {
				status("export failed: " + ((result && (result.detail || result.error)) || "no response"));
				return;
			}
			status("exported " + namespace + " → " + (result.path || path));
		});
	}

	/** Import a datapack folder or zip: rebuild one tab per effect and restore the sidecar layout. */
	function importPack() {
		var bridge = window.VFXBridge;
		if (!bridge) {
			status("import: the bridge is not available");
			return;
		}
		var path = dom.importPath.value.trim();
		if (!path) {
			status("import: enter a pack folder or zip");
			return;
		}
		status("importing…");
		bridge.post("/import", { path: path }).then(function (result) {
			if (!result || result.ok === false) {
				status("import failed: " + ((result && (result.detail || result.error)) || "no response"));
				return;
			}
			var namespace = result.namespace || "";
			var before = project.tabs.length;
			var created = openPack(namespace, result.effects || {});
			applySidecar(result.sidecar, project.tabs.slice(before));
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
		entries.forEach(function (entry) {
			var def = parseEffect(entry[1]);
			var tab = newTab(namespace ? namespace + ":" + entry[0] : entry[0], def && def.type ? def.type : null);
			hydrateTab(tab, def);
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
		undo: function () {
			var commands = commandStack();
			return commands ? commands.undo() : null;
		},
		redo: function () {
			var commands = commandStack();
			return commands ? commands.redo() : null;
		}
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
			return commandStack();
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

	/** Start the bridge (SSE + reconnect) and surface its health in the status line. */
	function wireBridge() {
		var bridge = window.VFXBridge;
		if (!bridge) {
			return;
		}
		bridge.on("transport-error", function (message) {
			status("bridge: " + message);
		});
		bridge.on("error", function (data) {
			status("bridge: " + ((data && (data.detail || data.error)) || "error"));
		});
		bridge.on("reconcile", function (info) {
			status("re-syncing with the game (revision " + info.game + ")");
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

	/** Ctrl/Cmd+Z undoes, Ctrl/Cmd+Shift+Z or Ctrl/Cmd+Y redoes; both recompile (Task 16). */
	function wireHistoryKeys() {
		document.addEventListener("keydown", function (event) {
			if (!(event.ctrlKey || event.metaKey) || isEditable(event.target)) {
				return;
			}
			var key = String(event.key || "").toLowerCase();
			if (key === "z" && !event.shiftKey) {
				event.preventDefault();
				VFXEditor.undo();
			} else if (key === "y" || (key === "z" && event.shiftKey)) {
				event.preventDefault();
				VFXEditor.redo();
			}
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
		window.addEventListener("resize", resizeCanvas);
		wireHistoryKeys();
		wireBridge();

		// Selecting an effect node shows its params in the inspector (param name defaults to the first).
		scene.onNodeSelected = function (node) {
			var tab = activeTab();
			if (node && node.properties && node.properties.effectType && tab && node === tab.root) {
				var descriptor = schema[tab.type];
				if (descriptor && descriptor.params && descriptor.params.length) {
					inspectorName = descriptor.params[0].name;
				}
				renderInspector();
			}
		};
		// A dragged node's new position is persisted to the IR (and is undoable like any graph edit).
		scene.onNodeMoved = function (node) {
			if (!applyingGraph && node && node.__tab && node !== node.__tab.root) {
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
			}
		});
		dom.exportBtn.addEventListener("click", exportPack);
		dom.importBtn.addEventListener("click", importPack);

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

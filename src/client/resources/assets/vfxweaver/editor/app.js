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
		var names = Object.keys(schema).sort();
		for (var i = 0; i < names.length; i++) {
			var typeName = names[i];
			var registered = "vfx/effect/" + typeName;
			if (LiteGraph.registered_node_types[registered]) {
				continue;
			}
			LiteGraph.registerNodeType(registered, makeEffectNode(typeName, schema[typeName]));
		}
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
		Object.keys(schema).sort().forEach(function (typeName) {
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
		var items = dom.types.querySelectorAll(".vfx-type");
		for (var i = 0; i < items.length; i++) {
			var match = items[i].dataset.type.toLowerCase().indexOf(query) >= 0;
			items[i].style.display = match ? "" : "none";
		}
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
	 * never an incremental game undo.
	 */
	function afterHistory() {
		renderInspector();
		renderTimeline();
		var tab = activeTab();
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
				afterHistory();
			}
			return applied;
		};
		commands.redo = function () {
			var applied = redo.call(this);
			if (applied) {
				afterHistory();
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
				tab.params[name] = { control: "keyframes" };
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
				var entry = { name: effectName(tab) };
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
			var entry = byName[effectName(tab)];
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

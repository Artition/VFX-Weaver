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
		wrap: document.getElementById("vfx-canvas-wrap")
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
		var tab = { name: name || "untitled", type: null, graph: graph, root: null };
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
		renderTabs();
		status(tab.type ? tab.name : tab.name + " — choose an effect type");
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
			renderTabs();
			status("Choose an effect type to start");
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
			renderTabs();
			status(tab.name);
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
	/* Public API (Tasks 13-16 extend this object)                         */
	/* ------------------------------------------------------------------ */

	/**
	 * Open a pack: one tab per effect. {@code effects} is the `/import` shape
	 * (`{ "<name>": "<effect JSON string>" }`); the effect's `type` selects the
	 * node type, and the tab is named `<namespace>:<name>`.
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
			var type = null;
			var raw = entry[1];
			if (raw && typeof raw === "object") {
				type = raw.type || null;
			} else if (typeof raw === "string") {
				try {
					type = JSON.parse(raw).type || null;
				} catch (e) {
					type = null;
				}
			}
			newTab(namespace ? namespace + ":" + entry[0] : entry[0], type);
			created++;
		});
		return created;
	}

	var VFXEditor = {
		project: project,
		openPack: openPack,
		newTab: newTab,
		schema: schema,
		token: token,
		url: url,
		assetUrl: assetUrl
	};
	Object.defineProperty(VFXEditor, "graph", {
		enumerable: true,
		get: function () {
			var tab = activeTab();
			return tab ? tab.graph : null;
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

		dom.newTab.addEventListener("click", function () {
			newTab("untitled", null);
		});
		dom.filter.addEventListener("input", function () {
			filterPalette(dom.filter.value);
		});

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

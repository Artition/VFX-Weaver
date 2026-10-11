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
		pack: document.getElementById("vfx-pack"),
		types: document.getElementById("vfx-types"),
		valueTypes: document.getElementById("vfx-value-types"),
		filter: document.getElementById("vfx-filter"),
		canvas: document.getElementById("vfx-canvas"),
		wrap: document.getElementById("vfx-canvas-wrap"),
		main: document.getElementById("vfx-main"),
		inspector: document.getElementById("vfx-inspector"),
		timeline: document.getElementById("vfx-timeline"),
		splitInspector: document.getElementById("vfx-split-inspector"),
		splitTimeline: document.getElementById("vfx-split-timeline"),
		file: document.getElementById("vfx-file"),
		projectBtn: document.getElementById("vfx-project"),
		projectName: document.getElementById("vfx-project-name"),
		projectNs: document.getElementById("vfx-project-ns"),
		tabName: document.getElementById("vfx-tab-name"),
		play: document.getElementById("vfx-play"),
		stop: document.getElementById("vfx-stop"),
		replay: document.getElementById("vfx-replay"),
		revbadge: document.getElementById("vfx-revbadge"),
		problems: document.getElementById("vfx-problems"),
		problemsList: document.getElementById("vfx-problems-list"),
		problemsToggle: document.getElementById("vfx-problems-toggle"),
		problemsCount: document.getElementById("vfx-problems-count")
	};

	function showBanner(message) {
		dom.banner.textContent = message;
		dom.banner.hidden = false;
	}

	function status(message) {
		dom.status.textContent = message || "";
	}

	/* ------------------------------------------------------------------ */
	/* Preview problems panel (design §16.9 P1)                            */
	/* ------------------------------------------------------------------ */

	/**
	 * The persistent problems store (rejected definitions, preview errors, parse errors, compile
	 * warnings). Created lazily because app.js executes before compiler.js in the asset chain; by the
	 * time an entry is added (a sync reply or an SSE frame) the compiler is loaded.
	 */
	var problemStore = null;
	function problems() {
		if (!problemStore && window.VFXCompiler
				&& typeof window.VFXCompiler.createProblemStore === "function") {
			problemStore = window.VFXCompiler.createProblemStore();
		}
		return problemStore;
	}

	/** Add/replace one problem entry and repaint (never silently drops a preview problem). */
	function reportProblem(entry) {
		var store = problems();
		if (!store) {
			return;
		}
		store.add(entry);
		if (entry && entry.severity === "error") {
			setProblemsCollapsed(false);
		}
		renderProblems();
	}

	/** Keep only the entries under `prefix` whose keys are in `keys`; drop the rest. */
	function retainProblems(prefix, keys) {
		var store = problems();
		if (store) {
			store.retain(prefix, keys);
			renderProblems();
		}
	}

	/** Drop every entry whose key starts with `prefix`. */
	function clearProblems(prefix) {
		var store = problems();
		if (store) {
			store.clearPrefix(prefix);
			renderProblems();
		}
	}

	/** Toggle the panel's collapsed state and sync the header's aria-expanded. */
	function setProblemsCollapsed(collapsed) {
		if (!dom.problems) {
			return;
		}
		dom.problems.className = "vfx-problems" + (collapsed ? " collapsed" : "");
		if (dom.problemsToggle) {
			dom.problemsToggle.setAttribute("aria-expanded", collapsed ? "false" : "true");
		}
	}

	/** Select the tab/node a problem entry points at (no-op when the entry has no node). */
	function selectProblemNode(entry) {
		if (!entry || !entry.slug || !entry.uuid || !window.VFXCompiler) {
			return;
		}
		for (var t = 0; t < project.tabs.length; t++) {
			var tab = project.tabs[t];
			if (window.VFXCompiler.slugOf(tab) !== entry.slug) {
				continue;
			}
			for (var e = 0; e < (tab.effects || []).length; e++) {
				if (window.VFXCompiler.nodeUuid(tab.effects[e]) === entry.uuid) {
					selectTab(t);
					selectEffect(tab, tab.effects[e]);
					var node = nodeForEffect(tab, tab.effects[e].id);
					if (scene && node) {
						scene.selectNodes([node]);
					}
					return;
				}
			}
		}
	}

	/** Rebuild the problems list and its count badge from the store. */
	function renderProblems() {
		if (!dom.problemsList) {
			return;
		}
		var store = problems();
		var entries = store ? store.list() : [];
		if (dom.problemsCount) {
			dom.problemsCount.textContent = String(entries.length);
			dom.problemsCount.className = "vfx-problems-count" + (entries.length ? " has" : "");
		}
		if (dom.problems) {
			dom.problems.classList.toggle("empty", entries.length === 0);
		}
		dom.problemsList.textContent = "";
		entries.forEach(function (entry) {
			var row = document.createElement("div");
			row.className = "vfx-problem " + (entry.severity === "warning" ? "warning" : "error");
			row.setAttribute("role", "listitem");
			var severity = document.createElement("span");
			severity.className = "vfx-problem-sev";
			severity.textContent = entry.severity === "warning" ? "warn" : "error";
			var message = document.createElement("span");
			message.className = "vfx-problem-msg";
			message.textContent = entry.message || "";
			row.appendChild(severity);
			row.appendChild(message);
			if (entry.context) {
				var context = document.createElement("span");
				context.className = "vfx-problem-ctx";
				context.textContent = entry.context;
				row.appendChild(context);
			}
			if (entry.slug && entry.uuid) {
				row.classList.add("clickable");
				row.title = "Click to select the node";
				row.addEventListener("click", function () {
					selectProblemNode(entry);
				});
			}
			dom.problemsList.appendChild(row);
		});
	}

	/** Wire the panel's header toggle. */
	function wireProblems() {
		if (dom.problemsToggle) {
			dom.problemsToggle.addEventListener("click", function () {
				var collapsed = dom.problems && dom.problems.classList.contains("collapsed");
				setProblemsCollapsed(!collapsed);
			});
		}
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
			// A post effect offers a Mask attachment (design §13.1): the schema's structural flag
			// gates it, so a world/camera effect never shows a mask port. The port is an input
			// the Mask root feeds (purely visual: the tree resolves via effectId).
			if (descriptor && descriptor.structural && descriptor.structural.supportsMask === true) {
				var maskSlot = this.addInput("mask", "*");
				if (maskSlot) {
					maskSlot.color_on = groupColor("Mask");
					maskSlot.color_off = maskSlot.color_on;
				}
			}
			this.properties = { effectType: typeName, fieldCapable: params.filter(function (param) {
				return param && param.fieldCapable === true;
			}).map(function (param) {
				return param.name;
			}) };
			this.onDblClick = onNodeTitleDblClick;
			// Blender-style socket groups (see rebuildEffectInputs): per-`group` collapsible
			// header rows over the input sockets. Wired here so every construction path
			// (palette, import, undo) gets them; the header rows are laid out just below.
			this.__groupCollapsed = {};
			// Hide-unused starts OFF so the fresh layout is complete: the edge pass and
			// attachEffect resolve the real default afterwards (a hidden socket has no
			// index, so hiding before the wires land would drop them).
			this.__hideUnused = false;
			this.__hiddenInfo = { hidden: 0, animated: 0 };
			this.onMouseDown = onEffectNodeMouseDown;
			this.onMouseMove = onEffectNodeMouseMove;
			this.onDrawBackground = drawEffectGroupBands;
			this.onDrawForeground = drawEffectFooter;
			rebuildEffectInputs(this, null);
		}

		EffectNode.title = typeName;
		EffectNode.desc = "VFXweaver effect: " + typeName;
		return EffectNode;
	}

		/* ------------------------------------------------------------------ */
	/* Effect-node socket groups (Blender-style collapsible panels)        */
	/* ------------------------------------------------------------------ */

	/**
	 * Mechanism (checked against the vendored litegraph.js): it offers node-level collapse
	 * only — there are no hidden slots or slot sections. So a group collapse is a remove/add
	 * of input sockets: unwired members leave `node.inputs`, wired members stay as stubs (a
	 * wire is never dropped and stays visible), and the header row shows the hidden count.
	 * Links never die in the surgery: `rebuildEffectInputs` reuses the slot objects, then
	 * rewrites `target_slot` on the surviving graph links by socket name. The IR edge merge
	 * (`mergePreservedEdges`) additionally preserves any wire whose socket is hidden, and the
	 * edge pass redraws it on expand — the same thinking as the link-restore path.
	 */
	/** The input-slot name prefix of a group header row (never a real param). */
	var GROUP_HEADER_PREFIX = "__group:";

	/** Whether an input slot is a group header row rather than a param socket. */
	function isGroupHeaderSlot(slot) {
		return !!(slot && typeof slot.name === "string"
				&& slot.name.indexOf(GROUP_HEADER_PREFIX) === 0);
	}

	/** The header-slot name of `group`. */
	function groupHeaderName(group) {
		return GROUP_HEADER_PREFIX + group;
	}

	/** The input-slot name of the per-node eye row (show-all / hide-unused toggle). */
	var EYE_SLOT = "__eye";

	/** The eye glyph (surrogate pair, like the header triangles below). */
	var EYE_GLYPH = "👁";

	/** The eye-row band colour (fixed, so the group palette order never shifts). */
	var EYE_COLOR = "#8fb3ff";

	/** Whether an input slot is the eye row rather than a param socket. */
	function isEyeSlot(slot) {
		return !!(slot && slot.name === EYE_SLOT);
	}

	/**
	 * Param sockets above this count hide when unused by default: twelve rows are ~300px
	 * of node, about what fits beside the inspector without scrolling; below that every
	 * socket stays visible so small nodes never surprise. The eye toggle overrides per node;
	 * value/field/mask/macro nodes are exempt (few, structural sockets — stated limit).
	 */
	var HIDE_UNUSED_THRESHOLD = 12;

	/** Extra node-body height for the hidden-counter footer line. */
	var EYE_FOOTER_H = 16;

	/** While true a link drag is revealing every socket (see the boot-time canvas wrap). */
	var linkDragReveal = false;

	/** The default hide-unused state of a descriptor (big nodes hide, small nodes show). */
	function defaultHideUnused(descriptor) {
		var params = (descriptor && Array.isArray(descriptor.params)) ? descriptor.params : [];
		return params.length > HIDE_UNUSED_THRESHOLD;
	}

	/** The effect entry owning a canvas node, or null. */
	function effectEntryOf(tab, node) {
		if (!tab || !node || !node.properties) {
			return null;
		}
		var id = node.properties.effectId;
		for (var i = 0; i < (tab.effects || []).length; i++) {
			if (tab.effects[i].id === id) {
				return tab.effects[i];
			}
		}
		return null;
	}

	/** The hide-unused state of an effect node: the persisted eye toggle, else the default. */
	function resolveHideUnused(tab, node) {
		var entry = effectEntryOf(tab, node);
		if (entry && entry.hideUnused === true) {
			return true;
		}
		if (entry && entry.hideUnused === false) {
			return false;
		}
		return defaultHideUnused(effectNodeDescriptor(node));
	}

	/** Param names pinned visible on an effect entry (`shownSockets`, additive IR). */
	function pinnedSockets(tab, node) {
		var entry = effectEntryOf(tab, node);
		return (entry && entry.shownSockets && typeof entry.shownSockets === "object")
				? entry.shownSockets : {};
	}

	/** Whether a param of an effect carries keyframe tracks (effect-level or legacy tab-level). */
	function paramAnimated(tab, effectId, name) {
		var entry = effectEntryOf(tab, { properties: { effectId: effectId } });
		var keys = (entry && entry.keys && entry.keys[name]) || (tab && tab.keys && tab.keys[name]);
		return Array.isArray(keys) && keys.length > 0;
	}

	/**
	 * Count an effect node's currently-hidden params (descriptor params absent from the
	 * inputs) and how many of those are animated, for the eye label, the footer and diag.
	 *
	 * @param {Object} node the effect canvas node (inputs already laid out)
	 * @returns {{hidden: number, animated: number}} the counts
	 */
	function effectHiddenInfo(node) {
		var info = { hidden: 0, animated: 0 };
		var descriptor = effectNodeDescriptor(node);
		var params = (descriptor && Array.isArray(descriptor.params)) ? descriptor.params : [];
		if (!node || !node.inputs) {
			return info;
		}
		var present = {};
		node.inputs.forEach(function (slot) {
			present[slot.name] = true;
		});
		var tab = (node && node.__tab) || null;
		var effectId = node.properties ? node.properties.effectId : null;
		params.forEach(function (param) {
			if (!param || !param.name || present[param.name]) {
				return;
			}
			info.hidden++;
			if (tab && paramAnimated(tab, effectId, param.name)) {
				info.animated++;
			}
		});
		return info;
	}

	/**
	 * Whether a param socket is laid out: wired and pinned sockets always are (a wire is
	 * never dropped and stays visible); a collapsed group keeps its stubs only; otherwise
	 * hide-unused decides (suppressed while a link drag reveals).
	 */
	function effectMemberVisible(node, group, isWired, isPinned) {
		if (isWired || isPinned) {
			return true;
		}
		if (group && groupCollapsedMap(node)[group]) {
			return false;
		}
		if (linkDragReveal) {
			return true;
		}
		return node.__hideUnused !== true;
	}

	/** The group name a header-slot name addresses. */
	function groupOfHeader(name) {
		return String(name).substring(GROUP_HEADER_PREFIX.length);
	}

	/**
	 * Partition a descriptor's params into inspector sections. Reuses
	 * `window.VFXInspector.paramSections` (same order/labels as the inspector); the local
	 * fallback is the same algorithm (first-seen group order, ungrouped params share one
	 * default section) for the boot window before inspector.js loads.
	 *
	 * @param {Object} [descriptor] the effect's schema descriptor
	 * @returns {Array<{group: string, label: string, params: Array<Object>}>} the sections
	 */
	function effectGroupSections(descriptor) {
		var params = (descriptor && Array.isArray(descriptor.params)) ? descriptor.params : [];
		if (window.VFXInspector && typeof window.VFXInspector.paramSections === "function") {
			try {
				return window.VFXInspector.paramSections(params);
			} catch (e) {
				// fall through to the local partition
			}
		}
		var out = [];
		var byGroup = {};
		params.forEach(function (param) {
			if (!param || !param.name) {
				return;
			}
			var group = (param && typeof param.group === "string" && param.group) ? param.group : "";
			if (!Object.prototype.hasOwnProperty.call(byGroup, group)) {
				byGroup[group] = { group: group, label: group || "Parameters", params: [] };
				out.push(byGroup[group]);
			}
			byGroup[group].params.push(param);
		});
		return out;
	}

	/** The schema descriptor of an effect canvas node, or null for an unknown type. */
	function effectNodeDescriptor(node) {
		var type = node && node.properties ? node.properties.effectType : null;
		return (type && schema[type]) || null;
	}

	/** The node's collapsed-group map (`group -> true`), always an object. */
	function groupCollapsedMap(node) {
		if (!node.__groupCollapsed || typeof node.__groupCollapsed !== "object") {
			node.__groupCollapsed = {};
		}
		return node.__groupCollapsed;
	}

	/**
	 * Lay out an effect node's inputs as `[in] + sections + [mask] + extras`: every named
	 * section gets a header row, then its members (all when expanded, only the wired stubs
	 * when collapsed); the ungrouped default section stays headerless and always expanded.
	 * `in`/`out`/`mask` and unknown extras (`mask.*` reserved sockets) stay outside groups
	 * and always visible. Only ever called on effect nodes; value/mask/field/macro nodes
	 * keep their flat sockets (few sockets, structural layouts).
	 *
	 * @param {Object} node the effect canvas node
	 * @param {Object|null} [wiredNames] extra socket names to treat as wired (the IR edges of
	 * a materialise pass, whose canvas links do not exist yet); the toggle path passes null
	 * and reads the live links instead
	 */
	function rebuildEffectInputs(node, wiredNames) {
		if (!node || !isEffectNode(node) || !node.inputs) {
			return;
		}
		var descriptor = effectNodeDescriptor(node);
		var sections = effectGroupSections(descriptor);
		var collapsed = groupCollapsedMap(node);
		var pinned = pinnedSockets((node && node.__tab) || null, node);
		var paramsByName = {};
		((descriptor && descriptor.params) || []).forEach(function (param) {
			if (param && param.name) {
				paramsByName[param.name] = param;
			}
		});
		// Snapshot the live wires by socket name before the surgery (removeInput is bypassed
		// on purpose: disconnectInput would drop the wire; the array is rebuilt instead).
		var pool = {};
		node.inputs.forEach(function (slot) {
			pool[slot.name] = slot;
		});
		var graph = node.graph;
		var linksByName = {};
		if (graph && graph.links) {
			Object.keys(graph.links).forEach(function (id) {
				var link = graph.links[id];
				if (link && link.target_id === node.id) {
					var slot = node.inputs[link.target_slot];
					if (slot) {
						linksByName[slot.name] = id;
					}
				}
			});
		}
		function wired(name) {
			return Object.prototype.hasOwnProperty.call(linksByName, name)
					|| (!!wiredNames && wiredNames[name] === true);
		}
		var ordered = [EYE_SLOT, "in"];
		sections.forEach(function (section) {
			if (section.group) {
				ordered.push(groupHeaderName(section.group));
				section.params.forEach(function (param) {
					if (effectMemberVisible(node, section.group, wired(param.name),
							pinned[param.name] === true)) {
						ordered.push(param.name);
					}
				});
			} else {
				section.params.forEach(function (param) {
					if (effectMemberVisible(node, "", wired(param.name),
							pinned[param.name] === true)) {
						ordered.push(param.name);
					}
				});
			}
		});
		// The mask port stays outside groups (post effects only); unknown extras (`mask.*`
		// reserved sockets appended by syncMaskSockets) keep their trailing order.
		if (pool.mask) {
			ordered.push("mask");
		}
		node.inputs.forEach(function (slot) {
			if (ordered.indexOf(slot.name) < 0 && !isGroupHeaderSlot(slot)
					&& !paramsByName[slot.name]) {
				ordered.push(slot.name);
			}
		});
		var next = [];
		ordered.forEach(function (name) {
			var slot = pool[name];
			if (!slot) {
				if (name === "in" || name === "mask") {
					return;
				}
				if (name === EYE_SLOT) {
					slot = { name: name, type: "eye", link: null, label: "" };
					slot.color_on = EYE_COLOR;
					slot.color_off = slot.color_on;
				} else if (name.indexOf(GROUP_HEADER_PREFIX) === 0) {
					var group = groupOfHeader(name);
					slot = { name: name, type: "group", link: null, label: group };
					slot.color_on = groupColor(group);
					slot.color_off = slot.color_on;
				} else if (paramsByName[name]) {
					var param = paramsByName[name];
					slot = { name: name, type: socketType(param), link: null };
					slot.color_on = groupColor(param.group);
					slot.color_off = slot.color_on;
				} else {
					return;
				}
			}
			next.push(slot);
		});
		node.inputs = next;
		// The hidden counts are read off the laid-out inputs (descriptor params absent from
		// them, group-collapsed and hide-unused alike); the eye label and footer share them.
		var hiddenInfo = effectHiddenInfo(node);
		node.__hiddenInfo = hiddenInfo;
		// Refresh the header labels (arrow + hidden count), the eye label and the surviving
		// links, whose target_slot shifted with the array.
		node.inputs.forEach(function (slot) {
			if (isEyeSlot(slot)) {
				slot.label = node.__hideUnused === true
						? EYE_GLYPH + " +" + hiddenInfo.hidden + " hidden · show all"
						: EYE_GLYPH + " showing all · hide unused";
				return;
			}
			if (!isGroupHeaderSlot(slot)) {
				return;
			}
			var group = groupOfHeader(slot.name);
			var hidden = 0;
			sections.forEach(function (section) {
				if (section.group !== group) {
					return;
				}
				section.params.forEach(function (param) {
					if (inputIndex(node, param.name) < 0) {
						hidden++;
					}
				});
			});
			slot.label = (collapsed[group] ? "\u25B8 " : "\u25BE ") + group
					+ (hidden > 0 ? " (" + hidden + ")" : "");
		});
		if (graph && graph.links) {
			Object.keys(linksByName).forEach(function (name) {
				var link = graph.links[linksByName[name]];
				var at = inputIndex(node, name);
				if (link && at >= 0) {
					link.target_slot = at;
				}
			});
			node.inputs.forEach(function (slot) {
				slot.link = null;
			});
			Object.keys(graph.links).forEach(function (id) {
				var link = graph.links[id];
				if (link && link.target_id === node.id && node.inputs[link.target_slot]) {
					node.inputs[link.target_slot].link = id;
				}
			});
			graph._version++;
		}
		node.setSize(node.computeSize());
		if (!(node.flags && node.flags.collapsed) && node.__hiddenInfo.hidden > 0) {
			node.size[1] += EYE_FOOTER_H;
		}
		node.setDirtyCanvas(true, true);
	}

	/**
	 * The group whose header row holds the node-local `pos` (the whole header row is the hit
	 * area, never the glyph alone), or null. Header rows are dedicated slots, so a socket dot
	 * never shares the hit area and wire drags keep working.
	 *
	 * @param {Object} node the effect canvas node
	 * @param {Array<number>} pos the node-local position
	 * @returns {string|null} the group name, or null
	 */
	function effectGroupHeaderAt(node, pos) {
		if (!node || !node.inputs || !pos || pos[1] < 0) {
			return null;
		}
		if (node.flags && node.flags.collapsed) {
			return null;
		}
		if (typeof LiteGraph === "undefined" || !LiteGraph.NODE_SLOT_HEIGHT) {
			return null;
		}
		for (var i = 0; i < node.inputs.length; i++) {
			if (!isGroupHeaderSlot(node.inputs[i])) {
				continue;
			}
			var top = i * LiteGraph.NODE_SLOT_HEIGHT;
			if (pos[1] >= top && pos[1] < top + LiteGraph.NODE_SLOT_HEIGHT
					&& pos[0] >= 0 && pos[0] <= node.size[0]) {
				return groupOfHeader(node.inputs[i].name);
			}
		}
		return null;
	}

	/**
	 * Toggle one socket group. Wired members stay as stubs, so no wire drops; the flip is
	 * persisted on the IR entry (`collapsedGroups`, additive — the compiler never reads it)
	 * and committed through the debounced graph edit, so it is undoable and survives tab
	 * switches exactly like the whole-node collapse.
	 *
	 * @param {Object} node the effect canvas node
	 * @param {string} group the group to flip
	 */
	function toggleEffectGroup(node, group) {
		if (!node || group == null) {
			return;
		}
		var map = groupCollapsedMap(node);
		if (map[group]) {
			delete map[group];
		} else {
			map[group] = true;
		}
		rebuildEffectInputs(node, null);
		var tab = node.__tab;
		if (tab) {
			for (var i = 0; i < (tab.effects || []).length; i++) {
				if (tab.effects[i].id === node.properties.effectId) {
					if (Object.keys(map).length) {
						tab.effects[i].collapsedGroups = snapshot(map);
					} else {
						delete tab.effects[i].collapsedGroups;
					}
					break;
				}
			}
			scheduleGraphEdit(tab);
		}
		node.setDirtyCanvas(true, true);
	}

	/**
	 * Param names of one effect that carry an IR edge or a live field tree: the materialise
	 * pass treats them as wired, so a collapsed group keeps them as stubs and the edge pass
	 * redraws their wires. Mirrors the owner rule of the edge pass.
	 *
	 * @param {Object} tab the tab
	 * @param {string} effectId the owning effect id
	 * @returns {Object} wired param names (`name -> true`)
	 */
	function effectWiredParams(tab, effectId) {
		var wired = {};
		(tab.edges || []).forEach(function (edge) {
			if (!edge || edge.input != null || edge.from == null || edge.to == null) {
				return;
			}
			if (typeof edge.to !== "string" || edge.to.indexOf("mask.") === 0) {
				return;
			}
			if (edgeEffectId(tab, edge) === effectId) {
				wired[edge.to] = true;
			}
		});
		(tab.effects || []).forEach(function (effect) {
			if (effect.id !== effectId || !effect.fields || typeof effect.fields !== "object") {
				return;
			}
			Object.keys(effect.fields).forEach(function (name) {
				if (effect.fields[name] != null) {
					wired[name] = true;
				}
			});
		});
		return wired;
	}

	/** Click the eye row or a group header row (consumed: the node must not drag). */
	function onEffectNodeMouseDown(event, pos, canvas) {
		var node = this;
		if (effectEyeAt(node, pos)) {
			toggleEye(node);
			return true;
		}
		var group = effectGroupHeaderAt(node, pos);
		if (group == null) {
			return false;
		}
		toggleEffectGroup(node, group);
		return true;
	}

	/**
	 * Whether the node-local `pos` is on the eye row (the whole row is the hit area, like
	 * the group headers — never a glyph alone; a socket dot never shares the row, so wire
	 * drags keep working).
	 *
	 * @param {Object} node the effect canvas node
	 * @param {Array<number>} pos the node-local position
	 * @returns {boolean} true when the eye row was hit
	 */
	function effectEyeAt(node, pos) {
		if (!node || !node.inputs || !pos || pos[1] < 0) {
			return false;
		}
		if (node.flags && node.flags.collapsed) {
			return false;
		}
		if (typeof LiteGraph === "undefined" || !LiteGraph.NODE_SLOT_HEIGHT) {
			return false;
		}
		for (var i = 0; i < node.inputs.length; i++) {
			if (!isEyeSlot(node.inputs[i])) {
				continue;
			}
			var top = i * LiteGraph.NODE_SLOT_HEIGHT;
			return pos[1] >= top && pos[1] < top + LiteGraph.NODE_SLOT_HEIGHT
					&& pos[0] >= 0 && pos[0] <= node.size[0];
		}
		return false;
	}

	/**
	 * Flip one node's eye (show-all / hide-unused). `scheduleGraphEdit` runs first so the
	 * pre-flip IR is the undo baseline (a flip is one undo step); the flip persists on the
	 * IR entry (`hideUnused`, additive — the compiler never reads it) and survives tab
	 * switches exactly like a group flip.
	 *
	 * @param {Object} node the effect canvas node
	 */
	function toggleEye(node) {
		if (!node) {
			return;
		}
		var tab = (node && node.__tab) || null;
		if (tab) {
			scheduleGraphEdit(tab);
		}
		node.__hideUnused = !resolveHideUnused(tab, node);
		rebuildEffectInputs(node, null);
		if (tab) {
			var entry = effectEntryOf(tab, node);
			if (entry) {
				entry.hideUnused = node.__hideUnused;
			}
		}
		node.setDirtyCanvas(true, true);
	}

	/**
	 * Reveal every hidden socket while a link drags (Blender highlights its drop targets):
	 * the vendored litegraph.js offers no link-drag event (only the `connecting_*` canvas
	 * state set in `processMouseDown` and cleared in `processMouseUp`), so the boot wrap
	 * shows all on drag-start and restores on drop/cancel. Compatibility filtering is
	 * skipped — effect sockets are all numeric wildcards, so every hidden socket is a valid
	 * target. ESC-cancel without a mouseup keeps the reveal until the next click.
	 */
	function beginLinkReveal() {
		if (linkDragReveal) {
			return;
		}
		var tab = activeTab();
		if (!tab || !tab.graph) {
			return;
		}
		linkDragReveal = true;
		(tab.graph._nodes || []).forEach(function (node) {
			if (isEffectNode(node)) {
				rebuildEffectInputs(node, null);
			}
		});
	}

	/** Restore the hide-unused layout after a link drop/cancel (no-op when not revealing). */
	function endLinkReveal() {
		if (!linkDragReveal) {
			return;
		}
		linkDragReveal = false;
		var tab = activeTab();
		if (!tab || !tab.graph) {
			return;
		}
		(tab.graph._nodes || []).forEach(function (node) {
			if (isEffectNode(node)) {
				rebuildEffectInputs(node, null);
			}
		});
	}

	/**
	 * Track header hover for the pointer cursor. Only the transition repaints; the cursor
	 * itself is applied in {@link drawEffectGroupBands}, because the canvas overwrites it
	 * after this handler on every mousemove.
	 */
	function onEffectNodeMouseMove(event, pos, canvas) {
		var node = this;
		var over = effectGroupHeaderAt(node, pos) != null || effectEyeAt(node, pos);
		if (over !== !!node.__groupHover) {
			node.__groupHover = over;
			node.setDirtyCanvas(true, false);
		}
	}

	/**
	 * Tint each header row in the group's colour (drawn behind the slot dots/labels) and
	 * apply the pointer cursor. Background, not foreground, so the slot rendering stays
	 * legible on top of the band.
	 */
	function drawEffectGroupBands(ctx, graphcanvas, canvasEl) {
		var node = this;
		if (!node || !node.inputs || (node.flags && node.flags.collapsed)) {
			return;
		}
		if (typeof LiteGraph === "undefined" || !LiteGraph.NODE_SLOT_HEIGHT) {
			return;
		}
		var rowH = LiteGraph.NODE_SLOT_HEIGHT;
		ctx.save();
		ctx.textAlign = "left";
		for (var i = 0; i < node.inputs.length; i++) {
			if (isEyeSlot(node.inputs[i])) {
				ctx.globalAlpha = node.__groupHover ? 0.30 : 0.20;
				ctx.fillStyle = EYE_COLOR;
				ctx.fillRect(0, i * rowH, node.size[0], rowH);
				continue;
			}
			if (!isGroupHeaderSlot(node.inputs[i])) {
				continue;
			}
			ctx.globalAlpha = node.__groupHover ? 0.24 : 0.13;
			ctx.fillStyle = groupColor(groupOfHeader(node.inputs[i].name));
			ctx.fillRect(0, i * rowH, node.size[0], rowH);
		}
		ctx.restore();
		// The canvas sets its own cursor on every mousemove, so only ever add/remove ours.
		try {
			var el = canvasEl || (graphcanvas && graphcanvas.canvas);
			if (el && el.style) {
				if (node.__groupHover) {
					el.style.cursor = "pointer";
				} else if (el.style.cursor === "pointer") {
					el.style.cursor = "";
				}
			}
		} catch (e) {
			// cursor only: never break the draw
		}
	}

	/**
	 * The hidden-counter footer (foreground, so it reads above the body): `+N hidden` plus
	 * `· K animated` when hidden params carry tracks — an animated param never vanishes
	 * silently. Nothing is drawn when nothing hides; the value stays editable in the
	 * inspector and the compiler reads the IR, not the canvas.
	 */
	function drawEffectFooter(ctx, graphcanvas) {
		var node = this;
		if (!node || (node.flags && node.flags.collapsed)) {
			return;
		}
		var info = node.__hiddenInfo || { hidden: 0, animated: 0 };
		if (!info.hidden) {
			return;
		}
		var scale = graphcanvas && graphcanvas.ds ? graphcanvas.ds.scale : 1;
		ctx.save();
		ctx.font = (11 / scale) + "px sans-serif";
		ctx.textAlign = "left";
		ctx.fillStyle = info.animated > 0 ? "#ffd98a" : "#7f8aa3";
		ctx.fillText("+" + info.hidden + " hidden"
				+ (info.animated > 0 ? " · " + info.animated + " animated" : ""),
				8, node.size[1] - 4);
		ctx.restore();
	}

	/** Whether a canvas node is an effect node (vs a value node / group). */
	function isEffectNode(node) {
		return !!(node && node.properties && node.properties.effectType);
	}	/** The effect nodes on a tab's canvas, in insertion order. */
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
		registerMaskNodeTypes();
	}

	/**
	 * A `Mask` canvas node (design §13.1): the root of an effect's mask tree. It owns the `invert`
	 * and `space` fields, one `op` combo per composition join, and the single `mask` attachment
	 * output the owning effect node's `mask` input links from. The leaves are port-less operands:
	 * their order (titles, leafIndex) and the root's op rows are the tree the compiler consumes.
	 */
	function makeMaskNode() {
		function MaskNode() {
			this.properties = { maskRole: "mask", effectId: null };
			this.addOutput("mask", "*");
			this.size = [200, 90];
			this.onDblClick = onNodeTitleDblClick;
		}
		MaskNode.title = "Mask";
		MaskNode.desc = "VFXweaver mask root: op tree over the child MaskLeaf nodes";
		return MaskNode;
	}

	/**
	 * A `MaskLeaf` canvas node (design §13.1): one shape of a mask tree. It exposes the leaf's
	 * reserved `mask.p&lt;N&gt;.*` numeric fields as widgets, turning one field at a time into a
	 * driven input socket while a wire feeds it (design §15.1: socket XOR widget). Leaves carry
	 * no structural ports: the tree order lives in the leaf titles and leafIndex, never in wires.
	 */
	function makeMaskLeafNode() {
		function MaskLeafNode() {
			// `inputs`/`explicit` mirror the value-node convention: buildMaskLeafNode writes the
			// reserved-slot defaults here, and without them the first mask import throws
			// "Cannot set properties of undefined (setting 'mask.p<N>.*')".
			this.properties = { maskRole: "leaf", effectId: null, leafIndex: 0, inputs: {}, explicit: {} };
			this.size = [190, 80];
			this.onDblClick = onNodeTitleDblClick;
		}
		MaskLeafNode.title = "MaskLeaf";
		MaskLeafNode.desc = "VFXweaver mask leaf: a shape and its mask.p<N>.* fields";
		return MaskLeafNode;
	}

	/** Register the `vfx/mask` and `vfx/mask_leaf` structural node types. */
	function registerMaskNodeTypes() {
		if (!LiteGraph.registered_node_types["vfx/mask"]) {
			LiteGraph.registerNodeType("vfx/mask", makeMaskNode());
		}
		if (!LiteGraph.registered_node_types["vfx/mask_leaf"]) {
			LiteGraph.registerNodeType("vfx/mask_leaf", makeMaskLeafNode());
		}
		registerFieldNodeTypes();
	}

	/* ------------------------------------------------------------------ */
	/* Field nodes (design §13.2)                                          */
	/* ------------------------------------------------------------------ */

	/**
	 * Field nodes are a separate kind set from the value graph: a `vfx/field` leaf (one
	 * `VFXFieldFn`, literals only in slice 1) and a `vfx/field_op` composition. They link only
	 * into a field-capable effect socket (the `fieldAffordance` gate); the compiler walks them
	 * into `inputs.<param>` (`{field}|{op}`), never into the value `graph` block.
	 */
	var FIELD_LINK = "field";

	/** Whether a canvas node belongs to the field tree (never to the value graph). */
	function isFieldNode(node) {
		return !!(node && node.properties && node.properties.fieldRole);
	}

	/** The schema's field function table, or {} (the UI never hardcodes the ids). */
	function fieldTable() {
		var vfxir = ir();
		return vfxir && typeof vfxir.fieldFnTable === "function" ? vfxir.fieldFnTable() : {};
	}

	/** The schema's field function ids in order, or []. */
	function fieldFnIds() {
		var vfxir = ir();
		return vfxir && typeof vfxir.fieldFnIds === "function" ? vfxir.fieldFnIds() : [];
	}

	/** The schema's field operator specs (`[{id, arity}]`), or []. */
	function fieldOpList() {
		var vfxir = ir();
		return vfxir && typeof vfxir.fieldOpList === "function" ? vfxir.fieldOpList() : [];
	}

	/**
	 * Whether the descriptor gates a field on `name` (the fieldCapable affordance gate).
	 *
	 * @param {Object} [descriptor] the effect's schema descriptor
	 * @param {string} name the param/socket name
	 * @returns {boolean} true when a field node may be attached to the socket
	 */
	function fieldGated(descriptor, name) {
		var vfxir = ir();
		return !!vfxir && typeof vfxir.fieldAffordance === "function"
				&& vfxir.fieldAffordance(descriptor, name);
	}

	/** A `vfx/field` leaf node: one field function with literal widgets (slice 1 has no input sockets). */
	function makeFieldLeafNode() {
		function FieldLeafNode() {
			this.properties = { fieldRole: "leaf", fn: "", inputs: {}, effectId: null };
			this.size = [210, 110];
			var self = this;
			this.addWidget("combo", "fn", "", function (v) {
				self.properties.fn = v;
				rebuildFieldLeaf(self);
				scheduleGraphEdit(self.__tab);
			}, { values: fieldFnIds() });
			// A wildcard output: the root links into a fieldCapable effect socket (typed by the
			// param, e.g. `number`), and LiteGraph refuses a `field`->`number` wire — the field
			// tree itself is still read from the link's origin (see syncFieldTrees), so the type
			// carries no semantics.
			this.addOutput("field", "*");
			this.onDblClick = onNodeTitleDblClick;
		}
		FieldLeafNode.title = "Field";
		FieldLeafNode.desc = "VFXweaver per-pixel field leaf (links to a field-capable socket)";
		return FieldLeafNode;
	}

	/** A `vfx/field_op` composition node: `a <op> b` (plus `factor` for the ternary mix). */
	function makeFieldOpNode() {
		function FieldOpNode() {
			this.properties = { fieldRole: "op", op: "multiply", effectId: null };
			this.size = [170, 90];
			this.addInput("a", FIELD_LINK);
			this.addInput("b", FIELD_LINK);
			var self = this;
			this.__widgets = {};
			this.__widgets.op = this.addWidget("combo", "op", "multiply", function (v) {
				self.properties.op = v;
				syncFieldOpInputs(self);
				scheduleGraphEdit(self.__tab);
			}, { values: fieldOpList().map(function (spec) { return String(spec.id); }) });
			// Wildcard output, like the leaf: the op root links into a fieldCapable effect
			// socket, whose type is the param's (see the leaf constructor).
			this.addOutput("field", "*");
			this.onDblClick = onNodeTitleDblClick;
		}
		FieldOpNode.title = "FieldOp";
		FieldOpNode.desc = "VFXweaver field composition (links to a field-capable socket)";
		return FieldOpNode;
	}

	/** Register the `vfx/field` and `vfx/field_op` node types (constructors are schema-free). */
	function registerFieldNodeTypes() {
		if (!LiteGraph.registered_node_types["vfx/field"]) {
			LiteGraph.registerNodeType("vfx/field", makeFieldLeafNode());
		}
		if (!LiteGraph.registered_node_types["vfx/field_op"]) {
			LiteGraph.registerNodeType("vfx/field_op", makeFieldOpNode());
		}
	}

	/** Keep the op node's `factor` input present exactly for the ternary mix. */
	function syncFieldOpInputs(node) {
		var vfxir = ir();
		var arity = vfxir && typeof vfxir.fieldOpArity === "function"
				? vfxir.fieldOpArity(node.properties.op) : 2;
		var at = inputIndex(node, "factor");
		if (arity === 3 && at < 0) {
			node.addInput("factor", FIELD_LINK);
		} else if (arity !== 3 && at >= 0 && typeof node.removeInput === "function") {
			node.removeInput(at);
		}
		if (node.__widgets && node.__widgets.op) {
			node.__widgets.op.options = { values: fieldOpList().map(function (spec) {
				return String(spec.id);
			}) };
		}
	}

	/**
	 * Rebuild a leaf node's widgets from the schema's function inventory: one number widget per
	 * numeric param plus combos/text for space/channel/texture/primitive/fill/points. A param
	 * holding a `{from}` graph reference (hand-written) is preserved opaquely — slice 1 offers
	 * no visual wire into a leaf param.
	 *
	 * @param {Object} node the canvas leaf node
	 * @param {Object} [values] initial values (`{name: value}`), seeding `properties.inputs`
	 */
	function rebuildFieldLeaf(node, values) {
		var table = fieldTable();
		var spec = table[node.properties.fn] || null;
		if (node.widgets && node.widgets.length > 1) {
			node.widgets.length = 1;
		}
		if (node.widgets && node.widgets[0]) {
			node.widgets[0].options = { values: fieldFnIds() };
			node.widgets[0].value = node.properties.fn;
		}
		var inputs = node.properties.inputs || (node.properties.inputs = {});
		if (values) {
			Object.keys(values).forEach(function (key) {
				inputs[key] = values[key];
			});
		}
		if (!spec) {
			return;
		}
		(spec.params || []).forEach(function (pair) {
			var name = pair[0];
			var current = inputs[name];
			if (current && typeof current === "object") {
				return;
			}
			if (typeof current !== "number") {
				current = inputs[name] = num(pair[1], 0);
			}
			node.addWidget("number", name, current, function (v) {
				inputs[name] = v;
				scheduleGraphEdit(node.__tab);
			});
		});
		function combo(name, options, fallback) {
			var current = inputs[name];
			if (typeof current !== "string" || options.indexOf(current) < 0) {
				current = inputs[name] = fallback;
			}
			node.addWidget("combo", name, current, function (v) {
				inputs[name] = v;
				scheduleGraphEdit(node.__tab);
			}, { values: options });
		}
		if (spec.spatial) {
			combo("space", ["screen", "world"], "screen");
		}
		if (spec.hasChannel) {
			combo("channel", ["r", "g", "b", "a", "luminance"], "luminance");
		}
		if (spec.hasTexture && typeof inputs.texture !== "string") {
			inputs.texture = "minecraft:textures/block/stone.png";
		}
		if (spec.hasTexture) {
			node.addWidget("text", "texture", String(inputs.texture || ""), function (v) {
				inputs.texture = String(v);
				scheduleGraphEdit(node.__tab);
			});
		}
		if (spec.hasShape) {
			combo("primitive", ["circle", "ellipse", "rect", "polygon"], "circle");
			combo("fill", ["solid", "stroke"], "solid");
		}
		if (spec.hasCurve) {
			var text = Array.isArray(inputs.points) ? inputs.points.map(function (point) {
				return num(point.time) + " " + num(point.value);
			}).join("\n") : "0 0\n1 1";
			node.addWidget("text", "points", text, function (v) {
				var out = [];
				String(v == null ? "" : v).split("\n").forEach(function (line) {
					var parts = line.trim().split(/\s+/);
					if (parts.length >= 2) {
						var time = parseNumber(parts[0]);
						var value = parseNumber(parts[1]);
						if (time != null && value != null) {
							out.push({ time: time, value: value });
						}
					}
				});
				inputs.points = out;
				scheduleGraphEdit(node.__tab);
			});
		}
	}

	/** The input slot index named `name` on a field node, or -1 (inputIndex covers any node). */

	/**
	 * Read one field tree from the canvas, starting at a field node id. A leaf emits
	 * `{field, ...literals}` (`{from}` refs preserved verbatim); an op emits `{op, a, b, factor?}`.
	 * An incomplete tree (missing child, unknown fn) is transient canvas state and reads as null.
	 *
	 * @param {Object} tab the tab
	 * @param {number} canvasId the LiteGraph node id
	 * @param {Object} seen guards against a link cycle
	 * @returns {Object|null} the datapack-shaped field object
	 */
	function fieldTreeFromNode(tab, canvasId, seen) {
		var node = tab.graph.getNodeById(canvasId);
		if (!node || !isFieldNode(node) || seen[canvasId]) {
			return null;
		}
		seen[canvasId] = true;
		if (node.properties.fieldRole === "leaf") {
			var fn = String(node.properties.fn || "");
			if (!fn || !fieldTable()[fn]) {
				return null;
			}
			var leaf = { field: fn };
			var inputs = node.properties.inputs || {};
			Object.keys(inputs).forEach(function (key) {
				leaf[key] = snapshot(inputs[key]);
			});
			return leaf;
		}
		var op = String(node.properties.op || "multiply");
		var out = { op: op };
		var complete = true;
		["a", "b", "factor"].forEach(function (name) {
			var at = inputIndex(node, name);
			if (at < 0) {
				if (name !== "factor") {
					complete = false;
				}
				return;
			}
			var child = fieldChildFromSlot(tab, node, at, seen);
			if (child) {
				out[name] = child;
			} else if (name !== "factor") {
				complete = false;
			}
		});
		return complete && out.a && out.b ? out : null;
	}

	/** The field tree wired into a field node's input slot, or null. */
	function fieldChildFromSlot(tab, node, slot, seen) {
		var links = (tab.graph && tab.graph.links) || {};
		var found = null;
		Object.keys(links).forEach(function (id) {
			var link = links[id];
			if (link.target_id === node.id && link.target_slot === slot) {
				found = link.origin_id;
			}
		});
		return found == null ? null : fieldTreeFromNode(tab, found, seen);
	}

	/**
	 * Rebuild every effect's `fields` map (`param -> field object`) from the canvas field links.
	 * Only a field node wired into a fieldCapable socket counts (the gate, enforced again here so
	 * a hand-placed wire into an ordinary socket can never emit a field). Params that lose their
	 * node are recorded as `null`, so an imported carried block is explicitly removed instead of
	 * silently resurrected by the compiler's clone pass-through.
	 *
	 * @param {Object} tab the tab
	 */
	function syncFieldTrees(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		var links = tab.graph.links || {};
		var fieldOwner = {};
		(tab.graph._nodes || []).forEach(function (node) {
			if (isFieldNode(node) && node.properties && node.properties.effectId != null) {
				fieldOwner[node.properties.effectId] = true;
			}
		});
		tab.effects.forEach(function (effect) {
			var enode = nodeForEffect(tab, effect.id);
			if (!enode) {
				return;
			}
			var descriptor = schema[effect.type];
			var next = {};
			Object.keys(links).forEach(function (id) {
				var link = links[id];
				if (link.target_id !== enode.id) {
					return;
				}
				var origin = tab.graph.getNodeById(link.origin_id);
				if (!origin || !isFieldNode(origin)) {
					return;
				}
				var sock = enode.inputs ? enode.inputs[link.target_slot] : null;
				if (!sock || sock.name === "in" || sock.name === "mask" || sock.name === EYE_SLOT) {
					return;
				}
				if (!fieldGated(descriptor, sock.name)) {
					return;
				}
				var tree = fieldTreeFromNode(tab, link.origin_id, {});
				if (tree) {
					next[sock.name] = tree;
				}
			});
			if (Object.keys(next).length || effect.fields !== undefined) {
				var prev = effect.fields && typeof effect.fields === "object" ? effect.fields : {};
				Object.keys(prev).forEach(function (key) {
					if (!(key in next)) {
						next[key] = null;
					}
				});
				// A field entry whose tree node never materialised is a view bug, never a delete:
				// restore it. A node on canvas with its wire removed stays an explicit disconnect.
				if (!fieldOwner[effect.id]) {
					Object.keys(prev).forEach(function (key) {
						if (prev[key] != null && next[key] == null) {
							next[key] = snapshot(prev[key]);
						}
					});
				}
				effect.fields = next;
			}
		});
	}

	/** The hand-written field blocks an imported definition carries (`param -> object`). */
	function carriedFields(effect, tab) {
		var src = (effect && effect.source) || (tab && tab.source) || null;
		var inputs = src && src.inputs && typeof src.inputs === "object" ? src.inputs : {};
		var out = null;
		Object.keys(inputs).forEach(function (name) {
			var entry = inputs[name];
			if (entry && typeof entry === "object" && (entry.field != null || entry.op != null)) {
				(out || (out = {}))[name] = snapshot(entry);
			}
		});
		return out;
	}

	/**
	 * Materialise the canvas field nodes for every effect's authored (or carried) field tree:
	 * one node per leaf/op, wired into the owning effect's fieldCapable socket. Canvas-only view
	 * of `effect.fields` (like the mask nodes); positions fan out left of the effect node.
	 *
	 * @param {Object} tab the tab
	 * @param {Object} effectNodeById effect id -> canvas node
	 */
	function materializeFieldNodes(tab, effectNodeById) {
		(tab.effects || []).forEach(function (effect) {
			var view = effect.fields === undefined ? carriedFields(effect, tab) : effect.fields;
			if (!view) {
				return;
			}
			var enode = effectNodeById[effect.id];
			if (!enode) {
				return;
			}
			var descriptor = schema[effect.type];
			var base = enode.pos ? [num(enode.pos[0]) - 300, num(enode.pos[1])] : [80, 80];
			Object.keys(view).forEach(function (param, pi) {
				var tree = view[param];
				if (!tree || typeof tree !== "object") {
					return;
				}
				var slot = inputIndex(enode, param);
				if (slot < 0 || !fieldGated(descriptor, param)) {
					return;
				}
				var root = materializeFieldTree(tab, tree, [base[0], base[1] + pi * 170],
						effect.id);
				if (root) {
					root.connect(0, enode, slot);
				}
			});
		});
	}

	/**
	 * Materialise one field tree as canvas nodes, children fanning further left.
	 *
	 * @param {Object} tab the tab
	 * @param {Object} tree the datapack-shaped field object
	 * @param {Array<number>} pos the canvas position
	 * @param {string} effectId the owning effect id
	 * @returns {Object|null} the root canvas node
	 */
	function materializeFieldTree(tab, tree, pos, effectId) {
		if (!tree || typeof tree !== "object") {
			return null;
		}
		var node = null;
		if (tree.field != null) {
			if (!fieldTable()[String(tree.field)]) {
				return null;
			}
			node = LiteGraph.createNode("vfx/field");
			if (!node) {
				return null;
			}
			node.properties.fn = String(tree.field);
			var values = {};
			Object.keys(tree).forEach(function (key) {
				if (key !== "field") {
					values[key] = snapshot(tree[key]);
				}
			});
			rebuildFieldLeaf(node, values);
		} else if (tree.op != null) {
			node = LiteGraph.createNode("vfx/field_op");
			if (!node) {
				return null;
			}
			node.properties.op = String(tree.op);
			syncFieldOpInputs(node);
		} else {
			return null;
		}
		node.__tab = tab;
		node.properties.effectId = effectId;
		node.pos = pos;
		tab.graph.add(node);
		if (node.properties.fieldRole === "op") {
			["a", "b", "factor"].forEach(function (name, i) {
				if (tree[name] == null) {
					return;
				}
				var child = materializeFieldTree(tab, tree[name], [pos[0] - 260, pos[1] + i * 120],
						effectId);
				var at = inputIndex(node, name);
				if (child && at >= 0) {
					child.connect(0, node, at);
				}
			});
		}
		return node;
	}

	/**
	 * Drop field nodes whose owning effect is gone (deleting an effect node must not leave its
	 * field tree on the canvas). Guarded so the removal is not read as a user edit.
	 *
	 * @param {Object} tab the tab
	 */
	function pruneFieldNodes(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		var ids = {};
		(tab.effects || []).forEach(function (effect) { ids[effect.id] = true; });
		var prev = applyingGraph;
		applyingGraph = true;
		try {
			tab.graph._nodes.slice().forEach(function (node) {
				if (isFieldNode(node) && !ids[node.properties.effectId]) {
					node.ignore_remove = false;
					tab.graph.remove(node);
				}
			});
		} finally {
			applyingGraph = prev;
		}
	}

	/**
	 * The effect types the v1 palette offers, sorted. `COLLECTION` plays an `effects[]` list and has no
	 * editable canvas yet (design §13.3), so it is not a v1 type and must not present as a working one
	 * (a collection compile produces no play instance and would be reported as `game_rejected`).
	 */
	function paletteTypes() {
		return Object.keys(schema).filter(function (name) {
			// COLLECTION is a serialisation artifact; the `bind` section (kinds/sources) is not an
			// effect either — it has no `params` array. Only real descriptors become palette entries.
			return name !== "COLLECTION" && schema[name] && Array.isArray(schema[name].params);
		}).sort();
	}

	/* ------------------------------------------------------------------ */
	/* Tabs                                                                */
	/* ------------------------------------------------------------------ */

	/** A started graph, so every tab (and the empty fallback) is set up the same way. */
	function newGraph() {
		var graph = new LiteGraph.LGraph();
		// Draw connections on the FRONT canvas, after the nodes, so a wire is never hidden behind a
		// node body (LGraphCanvas.drawFrontCanvas honours `config.links_ontop`). Set per graph, since
		// the editor swaps the whole graph on every tab switch.
		graph.config.links_ontop = true;
		graph.start();
		return graph;
	}

	function newTab(name, type) {
		ensureHistoryBaseline();
		var tab = createTab(name, type);
		selectTab(project.active);
		commitHistory();
		return tab;
	}

	/**
	 * Build a tab (model + canvas graph + hooks) and append it, WITHOUT selecting it or recording
	 * history — the caller decides when to select and commit. {@link newTab} selects+commits per
	 * tab; the bulk pack import selects+commits once, so opening a pack stays O(N).
	 *
	 * @param {string} name the tab name
	 * @param {string} type an optional schema effect type to attach
	 * @returns {Object} the created tab
	 */
	function createTab(name, type) {
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
		// A fresh node has no wires yet, so the size default applies immediately: big
		// effects open hiding their unused sockets, small ones fully shown.
		node.__hideUnused = defaultHideUnused(schema[type]);
		rebuildEffectInputs(node, null);
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
			// The mask node tree hangs off the effect nodes; drop it with them.
			tab.graph._nodes.slice().forEach(function (node) {
				if (isMaskNode(node)) {
					dropMaskNode(node);
				}
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
		// A drag schedules its IR capture 200ms out; flush the outgoing tab now, so a quick
		// tab switch cannot rebuild it from a still-stale position. The pending timer then
		// no-ops, so one drag stays one undo step.
		var outgoing = activeTab();
		if (outgoing && outgoing !== project.tabs[index] && outgoing.__graphKey) {
			flushGraphEdit(outgoing.__graphKey);
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
		// Re-project the preview onto the newly active tab (design §16.9 P1): an empty tab clears it.
		if (window.VFXTimeline && window.VFXTimeline.preview
				&& typeof window.VFXTimeline.preview.sync === "function") {
			window.VFXTimeline.preview.sync(tab);
		}
	}

	function closeTab(index) {
		if (index < 0 || index >= project.tabs.length) {
			return;
		}
		var wasActive = index === project.active;
		var closing = project.tabs[index];
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
			// No tab left: clear the preview so the closed tab's definitions unregister and nothing plays.
			if (window.VFXTimeline && window.VFXTimeline.preview
					&& typeof window.VFXTimeline.preview.clear === "function") {
				window.VFXTimeline.preview.clear(closing);
			}
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

	/**
	 * Max tab elements kept in the bar. Above this renderTabs shows a window around the active tab
	 * (with head/tail "more" markers) instead of every tab, so the DOM stays bounded at N=1000+.
	 */
	var TAB_WINDOW = 40;

	function renderTabs() {
		dom.tabs.textContent = "";
		var total = project.tabs.length;
		if (!total) {
			return;
		}
		var start = 0;
		var end = total;
		if (total > TAB_WINDOW) {
			var half = Math.floor(TAB_WINDOW / 2);
			start = Math.max(0, Math.min(project.active - half, total - TAB_WINDOW));
			end = Math.min(total, start + TAB_WINDOW);
		}
		if (start > 0) {
			dom.tabs.appendChild(tabMore("\u25C0 " + start + " more"));
		}
		for (var index = start; index < end; index++) {
			dom.tabs.appendChild(tabElement(project.tabs[index], index));
		}
		if (end < total) {
			dom.tabs.appendChild(tabMore((total - end) + " more \u25B6"));
		}
	}

	/** A non-interactive "N more" marker in the tab bar (the hidden head/tail of a big tab set). */
	function tabMore(text) {
		var el = document.createElement("span");
		el.className = "vfx-tab-more";
		el.textContent = text;
		el.title = "Open an effect from the pack list (Effects button) to bring its tab into view";
		return el;
	}

	/** One tab element; `index` is the real tab index (closures capture it per render). */
	function tabElement(tab, index) {
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
		return tabEl;
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
	 * the game parser needs present even when untouched. `subgraph` is a macro *instance*: its
	 * sockets come from the macro's params/outputs (design §13.5), so it carries no static sockets.
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
		"switch": { sockets: ["index", "default"], defaults: { index: 0, "default": 0 }, required: ["index"], switch: true },
		subgraph: { sockets: [], defaults: {}, required: [], subgraph: true }
	};

	/** True while the canvas is being rebuilt from the IR, so the change hooks stay quiet. */
	var applyingGraph = false;

	function valueNodeTypes() {
		return Object.keys(VALUE_KINDS).sort();
	}

	/** The coerce helper for the shared `VFXIR` bind model (loaded after this file, used at call time). */
	function ir() {
		return window.VFXIR || null;
	}

	/** The bind kind ids, from the schema via `VFXIR` (single source of truth). */
	function bindingNames() {
		var vfxir = ir();
		var names = vfxir && typeof vfxir.bindKinds === "function" ? vfxir.bindKinds() : [];
		return names.length ? names : ["health"];
	}

	/**
	 * The unit / range a world binding yields (design §15.1, fix 6): used to type and label a `bind`
	 * value node's output. Cosmetic labels only — the kind list itself comes from the schema.
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
				// A `{...}`/`[...]` token is the inline-bezier form; a bare word is a named easing.
				if (parts[2].charAt(0) === "{" || parts[2].charAt(0) === "[") {
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

	/**
	 * A curve node's points as the IR easing form (design §15.4): each point's easing is normalised
	 * to a name string or `[x1,y1,x2,y2]` via `VFXIR.normalizeEasing`, so the node↔IR boundary never
	 * carries `{cubicBezier}` (the compiler converts it at the datapack boundary).
	 */
	function curvePointsIR(points) {
		var vfxir = ir();
		var normalize = vfxir && typeof vfxir.normalizeEasing === "function" ? vfxir.normalizeEasing : null;
		return (points || []).map(function (point) {
			var out = { time: num(point.time), value: num(point.value) };
			if (point.easing != null) {
				var easing = normalize ? normalize(point.easing) : point.easing;
				if (easing != null) {
					out.easing = snapshot(easing);
				}
			}
			return out;
		});
	}

	/** The bind node's bound-param spec (`bind`/`derive` plus the kind's fields). */
	function bindSpec(node) {
		if (!node.properties.bind || typeof node.properties.bind !== "object") {
			node.properties.bind = { bind: bindingNames()[0] || "health" };
		}
		return node.properties.bind;
	}

	/**
	 * The preview context world position (`meta.context.pos`) or null. The shell passes it as the
	 * default `pos` for a fresh `screen_x`/`screen_y` (world origin sits behind the camera, so a
	 * literal `[0,0,0]` puts the effect off-screen); null keeps a literal 0 with an on-node hint.
	 */
	function bindContextPos() {
		var timeline = window.VFXTimeline;
		if (!timeline || !timeline.preview || typeof timeline.preview.state !== "function") {
			return null;
		}
		var context = timeline.preview.state().context;
		return context && Array.isArray(context.pos) && context.pos.length === 3 ? context.pos : null;
	}

	/** A 3-array of finite numbers, or null when `value` is not one. */
	function asVec3(value) {
		if (!Array.isArray(value) || value.length !== 3) {
			return null;
		}
		return [num(value[0]), num(value[1]), num(value[2])];
	}

	/** Remove the bind node's dynamic widgets (the `fallback` socket widget is never touched). */
	function removeBindWidgets(node) {
		var doomed = node.__bindWidgets || [];
		node.__bindWidgets = [];
		doomed.forEach(function (widget) {
			var index = node.widgets ? node.widgets.indexOf(widget) : -1;
			if (index >= 0) {
				node.widgets.splice(index, 1);
			}
		});
		node.setSize(node.computeSize());
	}

	/** Add one dynamic bind widget, tracking it so a rebuild can remove it. */
	function addBindWidget(node, widget) {
		if (!node.__bindWidgets) {
			node.__bindWidgets = [];
		}
		node.__bindWidgets.push(widget);
		return widget;
	}

	/**
	 * Rebuild a bind node's widgets from its spec, mirroring the engine (`BoundParam`) per kind
	 * (design §15.1, bind-explore): a needsPos kind shows `pos` (required) + `scale` + any
	 * `range`/`invert`; `scoreboard` shows `objective`/`holder`/`range`/`invert`/`scale` and hides
	 * `pos`; `look` shows `yaw`/`pitch`/`range`/`invert`/`scale`. A source selector rewrites the
	 * spec to the source form `{"bind":<source>,"derive":<kind>}`; `player`/`camera` need no `pos`,
	 * `point`/`block` need a source `pos`, `entity` a selector/uuid (+ point/offset).
	 */
	function rebuildBindWidgets(node) {
		var vfxir = ir();
		var spec = bindSpec(node);
		removeBindWidgets(node);

		var kindConf = vfxir && typeof vfxir.bindSpecKind === "function" ? vfxir : null;
		var kind = kindConf ? kindConf.bindSpecKind(spec) : spec.bind;

		addBindWidget(node, node.addWidget("combo", "bind", kind, function (value) {
			if (kindConf) {
				kindConf.setBindKind(spec, value);
			} else {
				spec.bind = value;
			}
			rebuildBindWidgets(node);
			scheduleGraphEdit(node.__tab);
		}, { values: bindingNames() }));

		var sources = vfxir && typeof vfxir.bindSources === "function" ? vfxir.bindSources() : [];
		if (sources.length) {
			var current = vfxir.bindSpecSource(spec);
			addBindWidget(node, node.addWidget("combo", "source", current || "none",
				function (value) {
					vfxir.setBindSource(spec, value === "none" ? "" : value);
					rebuildBindWidgets(node);
					scheduleGraphEdit(node.__tab);
				}, { values: ["none"].concat(sources) }));
		}

		kind = vfxir && vfxir.bindSpecKind ? vfxir.bindSpecKind(spec) : spec.bind;
		var source = vfxir && vfxir.bindSpecSource ? vfxir.bindSpecSource(spec) : "";
		var needsPos = Boolean(vfxir && vfxir.bindNeedsPos && vfxir.bindNeedsPos(kind))
				&& (!source || source === "point" || source === "block");
		if (needsPos) {
			if (!asVec3(spec.pos) && vfxir && vfxir.applyBindDefaults) {
				vfxir.applyBindDefaults(spec, bindContextPos());
			}
			if (!asVec3(spec.pos)) {
				spec.pos = [0, 0, 0];
			}
			["x", "y", "z"].forEach(function (axis, index) {
				addBindWidget(node, node.addWidget("number", "pos." + axis, num(spec.pos[index]),
					function (value) {
						var pos = asVec3(spec.pos) || [0, 0, 0];
						pos[index] = value;
						spec.pos = pos;
						scheduleGraphEdit(node.__tab);
					}));
			});
		}

		if (source === "entity") {
			addBindWidget(node, node.addWidget("text", "selector",
				spec.selector == null ? "" : String(spec.selector), function (value) {
					if (value === "") {
						delete spec.selector;
					} else {
						spec.selector = value;
					}
					scheduleGraphEdit(node.__tab);
				}));
			addBindWidget(node, node.addWidget("text", "uuid",
				spec.uuid == null ? "" : String(spec.uuid), function (value) {
					if (value === "") {
						delete spec.uuid;
					} else {
						spec.uuid = value;
					}
					scheduleGraphEdit(node.__tab);
				}));
			addBindWidget(node, node.addWidget("combo", "point",
				spec.point == null ? "center" : String(spec.point), function (value) {
					if (value === "center") {
						delete spec.point;
					} else {
						spec.point = value;
					}
					scheduleGraphEdit(node.__tab);
				}, { values: ["feet", "center", "eyes"] }));
			["x", "y", "z"].forEach(function (axis, index) {
				var offset = asVec3(spec.offset) || [0, 0, 0];
				addBindWidget(node, node.addWidget("number", "offset." + axis, num(offset[index]),
					function (value) {
						var next = asVec3(spec.offset) || [0, 0, 0];
						next[index] = value;
						spec.offset = next;
						scheduleGraphEdit(node.__tab);
					}));
			});
		}

		if (vfxir && vfxir.bindRequiresObjective && vfxir.bindRequiresObjective(kind)) {
			if (vfxir.applyBindDefaults) {
				vfxir.applyBindDefaults(spec, bindContextPos());
			}
			addBindWidget(node, node.addWidget("text", "objective",
				spec.objective == null ? "" : String(spec.objective), function (value) {
					spec.objective = value;
					scheduleGraphEdit(node.__tab);
				}));
		}

		var fields = vfxir && vfxir.bindKindFields ? vfxir.bindKindFields(kind) : [];
		fields.forEach(function (name) {
			if (name === "invert") {
				addBindWidget(node, node.addWidget("toggle", "invert", spec.invert === true ? 1 : 0,
					function (value) {
						if (value) {
							spec.invert = true;
						} else {
							delete spec.invert;
						}
						scheduleGraphEdit(node.__tab);
					}));
			} else if (name === "holder") {
				addBindWidget(node, node.addWidget("text", "holder",
					spec.holder == null ? "" : String(spec.holder), function (value) {
						if (value === "") {
							delete spec.holder;
						} else {
							spec.holder = value;
						}
						scheduleGraphEdit(node.__tab);
					}));
			} else {
				addBindWidget(node, node.addWidget("number", name,
					spec[name] == null ? 0 : num(spec[name]), function (value) {
						spec[name] = value;
						scheduleGraphEdit(node.__tab);
					}));
			}
		});

		// A literal needsPos anchor at the world origin with no context sits behind the camera: hint.
		if (needsPos && !source && !bindContextPos()
				&& (!asVec3(spec.pos) || (num(spec.pos[0]) === 0 && num(spec.pos[1]) === 0 && num(spec.pos[2]) === 0))) {
			addBindWidget(node, node.addWidget("button", "⚠ pos=origin (add a source)", null,
				function () {}, {}));
		}
		refreshBindOutput(node);
	}

	/** Show the chosen binding on the node and label its output by the binding's unit (fix 6). */
	function refreshBindOutput(node) {
		if (!node || !node.__kindSpec || node.__kindSpec.bind !== true) {
			return;
		}
		var vfxir = ir();
		var spec = bindSpec(node);
		var kind = vfxir && vfxir.bindSpecKind ? vfxir.bindSpecKind(spec) : spec.bind;
		var source = vfxir && vfxir.bindSpecSource ? vfxir.bindSpecSource(spec) : "";
		var label = String(kind == null ? "await" : kind)
				+ (source ? " ← " + source : "") + " · " + bindingUnit(kind);
		if (node.outputs && node.outputs[0]) {
			node.outputs[0].label = label;
			node.outputs[0].name = label;
		}
		node.title = "bind · " + kind + (source ? " ← " + source : "");
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
		node.__syncPoints = function () {
			if (node.__widgets.points) {
				node.__widgets.points.value = pointsText(node.properties.points);
			}
		};
		node.__addPoint = function () {
			var points = (node.properties.points || []).slice();
			var last = points[points.length - 1] || { time: 0, value: 0 };
			points.push({ time: num(last.time) + 10, value: num(last.value) });
			node.properties.points = points;
			node.__syncPoints();
			scheduleGraphEdit(node.__tab);
		};
		node.addWidget("button", "+ point", null, function () {
			node.__addPoint();
		});
		// A visual Bézier editor hosted in a modal (litegraph has no DOM widget): edits
		// `node.properties.points` through the shared curve component; the text widget stays a
		// secondary/fallback editor (design §15.4).
		node.addWidget("button", "Edit curve…", null, function () {
			openNodeCurveEditor(node);
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
		var vfxir = ir();
		var names = bindingNames();
		// Prefer the scalar default (`health`: needs no `pos`) over the schema's first kind
		// (`screen_x`), so a fresh bind node is immediately parseable without a world anchor.
		var defaultKind = vfxir && vfxir.DEFAULT_BIND && names.indexOf(vfxir.DEFAULT_BIND) >= 0
				? vfxir.DEFAULT_BIND : (names[0] || "health");
		node.properties.bind = {};
		if (vfxir && vfxir.setBindKind) {
			vfxir.setBindKind(node.properties.bind, defaultKind);
		} else {
			node.properties.bind.bind = defaultKind;
		}
		node.__bindWidgets = [];
		rebuildBindWidgets(node);
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
			this.onDblClick = onNodeTitleDblClick;
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
			subgraphs: snapshot(tab && tab.subgraphs ? tab.subgraphs : []),
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

	/** Re-apply the driven rule across every value node (and MaskLeaf) on a tab. */
	function syncAllSlots(tab) {
		if (!tab || !tab.graph || applyingGraph) {
			return;
		}
		tab.graph._nodes.forEach(function (node) {
			if (isMaskLeafNode(node)) {
				syncMaskLeafSockets(node);
			} else if (isEffectNode(node)) {
				// A connect/disconnect changes which sockets count as used: re-hide the
				// newly unused, re-show the newly wired. Skipped while a link drag
				// reveals (the drop's own mouseup restores) — a wire is never dropped.
				if (!linkDragReveal) {
					rebuildEffectInputs(node, null);
				}
			} else if (node !== tab.root) {
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
	 * Centre the canvas on a MaskLeaf node (the inspector MASK section's "go to leaf").
	 *
	 * @param {string} effectId the owning effect id
	 * @param {number} leafIndex the depth-first leaf index
	 * @returns {boolean} whether a leaf node was focused
	 */
	function focusMaskLeaf(effectId, leafIndex) {
		var tab = activeTab();
		if (!tab || !tab.graph || !scene) {
			return false;
		}
		var target = null;
		maskNodesForEffect(tab, effectId).forEach(function (node) {
			if (isMaskLeafNode(node) && node.properties.leafIndex === Number(leafIndex)) {
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
	 * Toggle a node's collapsed state from a title-bar double-click (the whole title bar is
	 * the hit area; LiteGraph's own collapse square keeps working for single clicks). Links
	 * survive collapsing (LiteGraph keeps the sockets), and the state persists into the IR
	 * through the normal debounced edit, so it survives tab switches and undo.
	 *
	 * @param {Object} event the canvas event (unused)
	 * @param {Array<number>} pos the node-local position (the title bar is above y 0)
	 */
	function onNodeTitleDblClick(event, pos) {
		var node = this;
		if (!node || typeof node.collapse !== "function" || !node.graph
				|| !pos || pos[1] >= 0) {
			return;
		}
		node.collapse();
		if (node.__tab) {
			scheduleGraphEdit(node.__tab);
		}
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
			inputs.points = curvePointsIR(node.properties.points);
		}
		if (spec.expr) {
			inputs.expr = String(node.properties.expr == null || node.properties.expr === "" ? "0" : node.properties.expr);
		}
		if (spec.bind && node.properties.bind) {
			// Emit the clean datapack bind object (source form / required fields / pruned scalars) via
			// the shared IR builder, so the canvas node and the inspector produce identical JSON.
			var vfxir = ir();
			var built = vfxir && vfxir.buildBindSpec
					? vfxir.buildBindSpec(vfxir.bindSpecKind(node.properties.bind),
							vfxir.bindSpecSource(node.properties.bind), node.properties.bind)
					: node.properties.bind;
			Object.keys(built).forEach(function (key) {
				inputs[key] = snapshot(built[key]);
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
		if (spec.subgraph) {
			// A macro instance carries its macro id; the sockets came from the macro, so the
			// generic socket loop above already emitted the edited literals.
			if (node.properties.subgraph != null) {
				ir.subgraph = node.properties.subgraph;
			}
			Object.keys(node.__verbatim || {}).forEach(function (name) {
				if (!node.properties.explicit[name]) {
					ir.inputs[name] = snapshot(node.__verbatim[name]);
				}
			});
		}
		if (Array.isArray(node.pos) && node.pos.length === 2) {
			ir.pos = [Math.round(node.pos[0]), Math.round(node.pos[1])];
		}
		if (node.flags && node.flags.collapsed === true) {
			ir.collapsed = true;
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
			if (node.flags && node.flags.collapsed === true) {
				entry.collapsed = true;
			} else {
				delete entry.collapsed;
			}
			var groupMap = node.__groupCollapsed;
			if (groupMap && typeof groupMap === "object" && Object.keys(groupMap).length) {
				entry.collapsedGroups = snapshot(groupMap);
			} else {
				delete entry.collapsedGroups;
			}
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

	/**
	 * Drop mask nodes whose owning effect is gone (design §13.1): deleting an effect node must not
	 * leave its Mask/MaskLeaf nodes on the canvas. Guarded so the removal is not read as a user edit.
	 *
	 * @param {Object} tab the tab
	 */
	function pruneMaskNodes(tab) {
		if (!tab || !tab.graph || !window.VFXIR) {
			return;
		}
		var ids = {};
		(tab.effects || []).forEach(function (effect) { ids[effect.id] = true; });
		var prev = applyingGraph;
		applyingGraph = true;
		try {
			tab.graph._nodes.slice().forEach(function (node) {
				if (isMaskNode(node) && !ids[node.properties.effectId]) {
					dropMaskNode(node);
				}
			});
		} finally {
			applyingGraph = prev;
		}
	}

	/** The canonical key of one IR edge (via `VFXIR.edgeKey`; JSON only without the IR layer). */
	function edgeKeyOf(edge) {
		var vfxir = ir();
		return vfxir && typeof vfxir.edgeKey === "function" ? vfxir.edgeKey(edge) : JSON.stringify(edge);
	}

	/**
	 * Walk the canvas links into IR edges (the canvas half of refreshFromCanvas): effect-param
	 * wires, mask-slot wires and node-to-node wires become edges; a wire into a macro instance
	 * becomes an instance `{from}` binding (a fold, applied by the caller); flow/mask-attach links
	 * are classified for the diagnostic. Read-only: folds are returned, never applied here.
	 *
	 * @param {Object} tab the tab
	 * @param {Object} irByGraphId canvas node id -> IR value-node id
	 * @returns {{edges: Array, folds: Array, keys: Object, wires: Array}} the edges, the instance
	 * folds, the drawn edge keys and the per-link classification
	 */
	function collectCanvasEdges(tab, irByGraphId) {
		/** The macro output name an origin slot addresses, or null for the default (first) output. */
		function originOutput(origin, slot) {
			if (!origin || !origin.properties || origin.properties.kind !== "subgraph") {
				return null;
			}
			var names = origin.__outputs || [];
			slot = Number(slot) || 0;
			return slot > 0 && slot < names.length ? names[slot] : null;
		}
		var graphLinks = (tab.graph && tab.graph.links) || {};
		var edges = [];
		var folds = [];
		var keys = {};
		var wires = [];
		function emit(edge) {
			edges.push(edge);
			var key = edgeKeyOf(edge);
			keys[key] = true;
			wires.push({ kind: "edge", key: key });
		}
		Object.keys(graphLinks).forEach(function (linkId) {
			var link = graphLinks[linkId];
			var from = irByGraphId[link.origin_id];
			if (!from) {
				wires.push({ kind: "skip" });
				return;
			}
			var target = tab.graph.getNodeById(link.target_id);
			if (!target) {
				wires.push({ kind: "skip" });
				return;
			}
			var origin = tab.graph.getNodeById(link.origin_id);
			var output = originOutput(origin, link.origin_slot);
			if (isEffectNode(target)) {
				var param = target.inputs ? target.inputs[link.target_slot] : null;
				// The `mask` attachment port is a cosmetic Mask-root wire (it resolves via
				// effectId), never a param edge — same skip as the field-tree reader below.
				if (param && param.name !== "in" && param.name !== "mask"
						&& !isGroupHeaderSlot(param) && !isEyeSlot(param)) {
					var edge = { from: from, to: param.name, effect: target.properties.effectId };
					if (output != null) {
						edge.output = output;
					}
					emit(edge);
				} else {
					wires.push({ kind: (isGroupHeaderSlot(param) || isEyeSlot(param)) ? "skip" : (param && param.name === "mask" ? "mask" : "flow") });
				}
			} else if (isMaskLeafNode(target)) {
				// A value node wired into a MaskLeaf's `mask.p<N>.*` socket is a param wire for the
				// owning effect (design §13.1); the compiler consumes it. The cosmetic `mask` attach
				// port is not a slot, so it is ignored.
				var maskSocket = target.inputs ? target.inputs[link.target_slot] : null;
				if (maskSocket && maskSocket.name.indexOf("mask.") === 0) {
					var maskEdge = { from: from, to: maskSocket.name };
					if (target.properties.effectId) {
						maskEdge.effect = target.properties.effectId;
					}
					emit(maskEdge);
				} else {
					wires.push({ kind: "skip" });
				}
			} else if (target.properties && target.properties.kind && irByGraphId[target.id]) {
				var socket = target.inputs ? target.inputs[link.target_slot] : null;
				if (socket) {
					if (target.properties.kind === "subgraph") {
						// The engine forbids edges into an instance: a wire into a macro
						// input socket becomes an instance `{from}` binding instead of an edge.
						var fold = { id: irByGraphId[target.id], input: socket.name, from: from };
						folds.push(fold);
						wires.push({ kind: "binding", id: fold.id, input: fold.input, from: from });
					} else {
						var wire = { from: from, to: irByGraphId[target.id], input: socket.name };
						if (output != null) {
							wire.output = output;
						}
						emit(wire);
					}
				} else {
					wires.push({ kind: "skip" });
				}
			} else if (isFieldNode(origin)) {
				wires.push({ kind: "field" });
			} else {
				wires.push({ kind: "skip" });
			}
		});
		return { edges: edges, folds: folds, keys: keys, wires: wires };
	}

	/**
	 * The effect id an edge belongs to (the multi-effect disambiguator, else the first effect),
	 * mirroring the materialise pass so preserved edges resolve to the same owner.
	 */
	function edgeEffectId(tab, edge) {
		var multi = Boolean(tab.effects && tab.effects.length > 1);
		if (multi && edge && edge.effect != null) {
			return edge.effect;
		}
		return tab.effects && tab.effects.length ? tab.effects[0].id : null;
	}

	/**
	 * Whether an old edge with no canvas link still resolves (a view bug, worth preserving): its
	 * source node must still exist and its target must still exist (a param on the owning effect,
	 * a node input, or a mask slot). A wire into a macro instance is folded into the instance's
	 * `{from}` binding instead (the engine forbids edges there) and reports false.
	 *
	 * @param {Object} tab the tab
	 * @param {Object} irById rebuilt IR value-node id -> node
	 * @param {Object} byIrId IR value-node id -> canvas node
	 * @param {Object} edge the old edge
	 * @param {string|null} effectId the owning effect id
	 * @returns {boolean} true when the edge is preserved as an edge
	 */
	function keepPreservedEdge(tab, irById, byIrId, edge, effectId) {
		if (!edge || edge.from == null || edge.to == null || !irById[edge.from]) {
			return false;
		}
		if (edge.input != null) {
			var target = byIrId[edge.to];
			if (!target || inputIndex(target, edge.input) < 0) {
				return false;
			}
			if (target.properties && target.properties.kind === "subgraph") {
				irById[edge.to].inputs[edge.input] = { from: edge.from };
				return false;
			}
			return true;
		}
		if (typeof edge.to === "string" && edge.to.indexOf("mask.") === 0) {
			if (!effectId || indexOfEffect(tab, effectId) < 0) {
				return false;
			}
			var enode = nodeForEffect(tab, effectId);
			if (enode && inputIndex(enode, edge.to) >= 0) {
				return true;
			}
			var found = false;
			(tab.graph._nodes || []).forEach(function (node) {
				if (isMaskLeafNode(node) && node.properties.effectId === effectId
						&& inputIndex(node, edge.to) >= 0) {
					found = true;
				}
			});
			return found;
		}
		if (!effectId || indexOfEffect(tab, effectId) < 0) {
			return false;
		}
		var effect = null;
		(tab.effects || []).forEach(function (entry) {
			if (entry.id === effectId) {
				effect = entry;
			}
		});
		var descriptor = effect ? schema[effect.type] : null;
		if (descriptor && Array.isArray(descriptor.params)) {
			for (var i = 0; i < descriptor.params.length; i++) {
				if (descriptor.params[i] && descriptor.params[i].name === edge.to) {
					return true;
				}
			}
		}
		var owned = nodeForEffect(tab, effectId);
		return !!owned && inputIndex(owned, edge.to) >= 0;
	}

	/**
	 * Merge the canvas-rebuilt edges with the tab's existing ones (a missing canvas link is a
	 * view bug, never a delete) and record the drawn edge keys as the next explicit-disconnect
	 * witness. Without the IR layer this falls back to replacing.
	 *
	 * @param {Object} tab the tab (its `edges` and `__linkSig` are written)
	 * @param {Array<Object>} canvasEdges the edges rebuilt from the canvas links
	 */
	function mergePreservedEdges(tab, canvasEdges) {
		var vfxir = ir();
		if (vfxir && typeof vfxir.mergeEdges === "function") {
			var byIrId = {};
			(tab.graph._nodes || []).forEach(function (node) {
				if (node && node.properties && node.properties.irId != null
						&& node.properties.kind && !isEffectNode(node)) {
					byIrId[node.properties.irId] = node;
				}
			});
			var irById = {};
			(tab.valueNodes || []).forEach(function (entry) {
				if (entry && entry.id != null) {
					irById[entry.id] = entry;
				}
			});
			tab.edges = vfxir.mergeEdges(tab.edges, canvasEdges, tab.__linkSig || {},
					function (edge) {
						return edgeEffectId(tab, edge);
					},
					function (edge, effectId) {
						return keepPreservedEdge(tab, irById, byIrId, edge, effectId);
					});
		} else {
			tab.edges = canvasEdges;
		}
		var keys = {};
		canvasEdges.forEach(function (edge) {
			keys[edgeKeyOf(edge)] = true;
		});
		tab.__linkSig = keys;
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
		pruneMaskNodes(tab);
		pruneFieldNodes(tab);
		var irByGraphId = {};
		var nodes = [];
		tab.graph._nodes.forEach(function (node) {
			if (isEffectNode(node) || !node.properties || !node.properties.kind || !node.properties.irId) {
				return;
			}
			irByGraphId[node.id] = node.properties.irId;
			nodes.push(nodeToIR(node));
		});
		var irById = {};
		nodes.forEach(function (entry) {
			irById[entry.id] = entry;
		});
		var collected = collectCanvasEdges(tab, irByGraphId);
		collected.folds.forEach(function (fold) {
			if (irById[fold.id]) {
				irById[fold.id].inputs[fold.input] = { from: fold.from };
			}
		});
		tab.valueNodes = nodes;
		mergePreservedEdges(tab, collected.edges);
		syncFieldTrees(tab);
	}

	/** Apply one IR node's values onto a freshly created canvas node. */
	/**
	 * The output slot index of a macro output name on a canvas instance node, or -1. Sockets are
	 * built in macro order (first = default = slot 0), so the index is the compile/expand address.
	 */
	function outputSlotIndex(node, name) {
		var names = node && node.__outputs ? node.__outputs : null;
		if (!names || !names.length) {
			return name == null ? 0 : -1;
		}
		if (name == null) {
			return 0;
		}
		for (var i = 0; i < names.length; i++) {
			if (names[i] === name) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Apply an instance IR onto a subgraph canvas node (design §13.5, flat first slice): input
	 * sockets are the macro's params (each a socket + a literal widget), outputs are the macro's
	 * outputs in order. A `{from}` binding becomes a pending ref (wired at materialise); a
	 * non-number literal is kept verbatim (widgets only edit numbers). An unknown macro falls back
	 * to the instance's own inputs with one output, so an imported definition still materialises.
	 * Nested-macro editing UI does not exist: the instance is opaque, its body lives in `subgraphs`.
	 */
	function applySubgraphIR(node, ir) {
		var tab = node.__tab;
		var macro = null;
		if (tab && ir && ir.subgraph != null && window.VFXIR
				&& typeof window.VFXIR.subgraphById === "function") {
			macro = window.VFXIR.subgraphById(tab, ir.subgraph);
		}
		var params = macro && macro.inputs ? Object.keys(macro.inputs) : Object.keys(ir.inputs || {});
		var outputs = macro && macro.outputs ? Object.keys(macro.outputs) : ["out"];
		if (!outputs.length) {
			outputs = ["out"];
		}
		node.properties.subgraph = ir.subgraph == null ? null : String(ir.subgraph);
		node.properties.inputs = {};
		node.properties.explicit = {};
		node.__kindSpec = { subgraph: true, sockets: params.slice() };
		node.__outputs = outputs.slice();
		node.__widgets = {};
		node.__verbatim = {};
		node.__pendingRefs = [];
		node.__driven = {};
		node.inputs = [];
		node.outputs = [];
		(node.widgets || []).slice().forEach(function (widget) {
			var at = node.widgets.indexOf(widget);
			if (at >= 0) {
				node.widgets.splice(at, 1);
			}
		});
		node.title = "macro" + (node.properties.subgraph ? " · " + node.properties.subgraph : "");
		var values = ir.inputs || {};
		var self = node;
		params.forEach(function (name) {
			self.addInput(name, "number");
			var value = values[name];
			if (isRef(value)) {
				var ref = { from: value.from, input: name };
				if (value.output != null) {
					ref.output = value.output;
				}
				self.__pendingRefs.push(ref);
				self.properties.inputs[name] = 0;
				return;
			}
			if (typeof value === "number") {
				self.properties.inputs[name] = value;
				self.properties.explicit[name] = true;
			} else if (value !== undefined) {
				self.__verbatim[name] = snapshot(value);
				self.properties.inputs[name] = 0;
			} else {
				self.properties.inputs[name] = 0;
			}
			var widget = self.addWidget("number", name, num(self.properties.inputs[name]), function (v) {
				self.properties.inputs[name] = v;
				self.properties.explicit[name] = true;
				delete self.__verbatim[name];
				scheduleGraphEdit(self.__tab);
			});
			self.__widgets[name] = widget;
		});
		outputs.forEach(function (name) {
			// Wildcard like every other value/field output: effect sockets are typed by
			// param kind ("float", ...) and LiteGraph refuses a "number"->"float" wire,
			// which left macro->effect edges undrawable (the compiler reads IR, so the
			// type carries no semantics on canvas).
			self.addOutput(name, "*");
		});
		node.setSize(node.computeSize());
	}

	function applyIR(node, ir) {
		if (ir && ir.collapsed === true && node && node.flags) {
			// Restored as a bare flag: the node is not on a graph yet and collapse()
			// needs one. Rendering and the link reader only look at the flag, so the
			// edge pass below is unaffected.
			node.flags.collapsed = true;
		}
		var spec = node.__kindSpec || {};
		if (spec.subgraph) {
			applySubgraphIR(node, ir);
			return;
		}
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
			// The whole spec (kind/source/derive + fields) is copied verbatim; `fallback` is a socket
			// and is applied below with the rest of the numeric inputs.
			var bind = {};
			Object.keys(values).forEach(function (name) {
				if (name !== "fallback") {
					bind[name] = snapshot(values[name]);
				}
			});
			node.properties.bind = bind;
			rebuildBindWidgets(node);
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
	 * Deterministic layout for value nodes that carry no stored position (imported with no
	 * meta.pos and no sidecar): topological columns (driver nodes left), rows spaced by node
	 * height plus a gap, below the effect row. Nodes with a stored/sidecar/user position are
	 * never moved. Mask/field structural nodes are not laid out here — they are auto-placed
	 * beside their effect node on every rebuild and their moves are never persisted. Same IR
	 * always yields the same layout; dependency-free.
	 *
	 * @param {Object} tab the tab
	 * @param {Object} byId IR id -> canvas node
	 */
	var LAYOUT_COL_W = 300;
	var LAYOUT_ROW_GAP = 40;
	var LAYOUT_LEFT = 80;
	function layoutAutoNodes(tab, byId) {
		var ids = Object.keys(byId).filter(function (id) {
			return byId[id].__autoPos === true;
		});
		if (!ids.length) {
			return;
		}
		var deps = {};
		ids.forEach(function (id) {
			deps[id] = [];
		});
		function addDep(id, from) {
			if (from != null && byId[from] && deps[id].indexOf(from) < 0) {
				deps[id].push(from);
			}
		}
		(tab.edges || []).forEach(function (edge) {
			if (edge.input != null && byId[edge.to]) {
				addDep(edge.to, edge.from);
			}
		});
		ids.forEach(function (id) {
			(byId[id].__pendingRefs || []).forEach(function (ref) {
				addDep(id, ref.from);
			});
		});
		var depth = {};
		function level(id, seen) {
			if (Object.prototype.hasOwnProperty.call(depth, id)) {
				return depth[id];
			}
			if (seen[id] || !deps[id]) {
				return 0;
			}
			seen[id] = true;
			var top = 0;
			deps[id].forEach(function (from) {
				var below = level(from, seen) + 1;
				if (below > top) {
					top = below;
				}
			});
			delete seen[id];
			depth[id] = top;
			return top;
		}
		ids.forEach(function (id) {
			level(id, {});
		});
		var columns = {};
		ids.forEach(function (id) {
			var col = depth[id] || 0;
			if (!columns[col]) {
				columns[col] = [];
			}
			columns[col].push(id);
		});
		// Start below the effect row, so value wires never start out from under an effect node.
		var top = 280;
		effectNodes(tab).forEach(function (enode) {
			var bottom = num(enode.pos[1]) + (enode.size ? num(enode.size[1]) : 120) + 80;
			if (bottom > top) {
				top = bottom;
			}
		});
		Object.keys(columns).map(Number).sort(function (a, b) {
			return a - b;
		}).forEach(function (col) {
			var x = LAYOUT_LEFT + col * LAYOUT_COL_W;
			var y = top;
			columns[col].sort().forEach(function (id) {
				var node = byId[id];
				if (node && typeof node.computeSize === "function") {
					try {
						node.setSize(node.computeSize());
					} catch (e) {
						// Keep the constructor size; the row gap still separates neighbours.
					}
				}
				var height = node && node.size ? num(node.size[1]) : 120;
				node.pos = [x, Math.round(y)];
				y += height + LAYOUT_ROW_GAP;
			});
		});
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
				if (isEffectNode(node) || isMaskNode(node) || isFieldNode(node)
						|| (node.properties && node.properties.kind)) {
					if (isEffectNode(node) || isMaskNode(node)) {
						node.ignore_remove = false;
					}
					tab.graph.remove(node);
				}
			});
			// The whole canvas is rebuilt, so the mask node cache is stale: force a re-materialise.
			tab.__maskSig = {};
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
				if (effect.collapsed === true && typeof node.collapse === "function") {
					node.collapse();
				}
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
			// Reserved mask sockets must exist before the edge pass so a mask wire can resolve.
			syncMaskSockets(tab);
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
				} else {
					// No stored pos (no meta.pos/sidecar): flagged for the layout pass below,
					// which never moves a node that has one.
					node.__autoPos = true;
				}
				tab.graph.add(node);
				byId[ir.id] = node;
			});
			var multi = Boolean(tab.effects && tab.effects.length > 1);
			var drawnKeys = {};
			(tab.edges || []).forEach(function (edge) {
				var from = byId[edge.from];
				if (!from) {
					return;
				}
				// A wire off a named macro output connects from that output's slot (design §13.5).
				var fromSlot = 0;
				if (edge.output != null) {
					fromSlot = outputSlotIndex(from, edge.output);
					if (fromSlot < 0) {
						return;
					}
				}
				var target;
				var slot = -1;
				if (edge.input != null) {
					target = byId[edge.to];
					slot = target ? inputIndex(target, edge.input) : -1;
				} else if (typeof edge.to === "string" && edge.to.indexOf("mask.") === 0) {
					// A mask-slot wire targets the MaskLeaf that owns the slot (design §13.1); the
					// effect node also carries the socket for back-compat when no leaf node exists.
					var maskEffect = multi && edge.effect != null ? edge.effect
							: (tab.effects && tab.effects.length ? tab.effects[0].id : null);
					target = (maskEffect ? maskSlotNode(tab, maskEffect, edge.to) : null)
							|| (maskEffect ? effectNodeById[maskEffect] : null);
					slot = target ? inputIndex(target, edge.to) : -1;
					if (slot < 0 && target && isMaskLeafNode(target)) {
						// A leaf whose field is unwired shows no socket (§15.1); re-add it for
						// the imported wire (the end-of-materialise slot sync keeps it: wired).
						slot = ensureMaskLeafInput(target, edge.to);
					}
				} else {
					target = multi && edge.effect != null
							? effectNodeById[edge.effect]
							: (tab.effects && tab.effects.length ? effectNodeById[tab.effects[0].id] : null);
					slot = target ? inputIndex(target, edge.to) : -1;
				}
				if (target && slot >= 0) {
					from.connect(fromSlot, target, slot);
					drawnKeys[edgeKeyOf(edge)] = true;
				}
			});
			// Imported datapacks may wire node->node as `inputs: { name: { from } }`; materialize those.
			Object.keys(byId).forEach(function (id) {
				var node = byId[id];
				(node.__pendingRefs || []).forEach(function (ref) {
					var source = byId[ref.from];
					var slot = inputIndex(node, ref.input);
					var outSlot = 0;
					if (ref.output != null) {
						outSlot = outputSlotIndex(source, ref.output);
						if (outSlot < 0) {
							return;
						}
					}
					if (source && slot >= 0) {
						source.connect(outSlot, node, slot);
					}
				});
			});
			layoutAutoNodes(tab, byId);
			materializeFieldNodes(tab, effectNodeById);
			// Restore the persisted socket-group collapse after the edge + field passes, so
			// wired members already exist as stubs and their wires redraw (see the helper).
			// The eye state resolves the same way: entry toggle, else the size default.
			(tab.effects || []).forEach(function (effect) {
				var enode = effectNodeById[effect.id];
				if (!enode) {
					return;
				}
				enode.__groupCollapsed = (effect.collapsedGroups
						&& typeof effect.collapsedGroups === "object")
						? snapshot(effect.collapsedGroups) : {};
				enode.__hideUnused = resolveHideUnused(tab, enode);
				rebuildEffectInputs(enode, effectWiredParams(tab, effect.id));
			});
			// The explicit-disconnect witness for the next refresh: only wires actually drawn.
			tab.__linkSig = drawnKeys;
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
		if (kind === "subgraph") {
			// Instances are born from Group (they need a macro); see groupSelectionIntoMacro.
			status("select value nodes, then group them into a macro");
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
			if (kind === "subgraph") {
				// Instances are born from Group (they need a macro), never from the palette.
				return;
			}
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
		// Structural Mask node (design §13.1): offered beside the value kinds; a no-op with a status
		// message on an effect whose schema does not support a mask (non-post types).
		var maskItem = document.createElement("button");
		maskItem.type = "button";
		maskItem.className = "vfx-type";
		maskItem.textContent = "mask";
		maskItem.dataset.value = "mask";
		maskItem.title = "Add a Mask node with MaskLeaf children (post effects only)";
		maskItem.addEventListener("click", function () {
			addMaskNode(activeTab());
		});
		dom.valueTypes.appendChild(maskItem);
		// Subgraph macros (design §13.5, flat first slice): group the selected value nodes into a
		// `subgraphs` entry + one instance node, or expand selected instances back. There is no
		// nested-macro editing UI: an instance is opaque, its body lives in `tab.subgraphs`.
		var groupItem = document.createElement("button");
		groupItem.type = "button";
		groupItem.className = "vfx-type";
		groupItem.textContent = "group → macro";
		groupItem.dataset.value = "group-macro";
		groupItem.title = "Group the selected value nodes into a subgraph macro";
		groupItem.addEventListener("click", function () {
			groupSelectionIntoMacro();
		});
		dom.valueTypes.appendChild(groupItem);
		var ungroupItem = document.createElement("button");
		ungroupItem.type = "button";
		ungroupItem.className = "vfx-type";
		ungroupItem.textContent = "ungroup macro";
		ungroupItem.dataset.value = "ungroup-macro";
		ungroupItem.title = "Expand the selected macro instances back into plain nodes";
		ungroupItem.addEventListener("click", function () {
			ungroupSelectedMacros();
		});
		dom.valueTypes.appendChild(ungroupItem);
	}



	/* ------------------------------------------------------------------ */
	/* Inspector                                                           */
	/* ------------------------------------------------------------------ */

	/** The param shown in the inspector, by name; reset when it is not on the active tab. */
	var inspectorName = null;

	/** Collapsed inspector chip-group sections, keyed by `type + "/" + group`; survives re-renders. */
	var paramGroupCollapsed = {};

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
			subgraphs: snapshot(tab.subgraphs || []),
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

	/**
	 * Record the current project state as one undo step (skips an unchanged state). A bulk import
	 * raises {@link historySuspend} so the per-tab commits collapse into the one trailing commit.
	 */
	function commitHistory() {
		if (historySuspend > 0) {
			return;
		}
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

	/** >0 while a bulk import is materialising tabs; suppresses intermediate history commits. */
	var historySuspend = 0;
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
			tab.subgraphs = snapshot(state.subgraphs) || [];
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
					entry.loop = saved.loop === true;
					entry.persistent = saved.persistent === true;
					entry.fade_ticks = num(saved.fade_ticks, 0);
					entry.mute = saved.mute === true;
					entry.solo = saved.solo === true;
					entry.source = snapshot(saved.source);
					if (saved.mask !== undefined) {
						entry.mask = snapshot(saved.mask);
					}
					if (saved.fields !== undefined) {
						entry.fields = snapshot(saved.fields);
					}
					entry.effectId = saved.effectId == null ? null : String(saved.effectId);
					entry.external = saved.external === true;
					if (saved.uuid != null) {
						entry.uuid = String(saved.uuid);
					}
					if (saved.collapsedGroups !== undefined) {
						entry.collapsedGroups = snapshot(saved.collapsedGroups);
					}
					if (saved.hideUnused !== undefined) {
						entry.hideUnused = saved.hideUnused === true;
					}
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

	/** The display label of a reserved mask slot (its datapack field) for a leaf widget. */
	function maskSlotLabel(slot) {
		if (slot.kind === "center") {
			return "center_" + ["x", "y", "z"][slot.fieldIndex];
		}
		if (slot.kind === "param") {
			return slot.paramName;
		}
		if (slot.kind === "soft") {
			return "softness";
		}
		if (slot.kind === "stroke") {
			return "stroke_width";
		}
		return slot.kind;
	}

	/** The schema descriptor of an effect entry, or null when the schema has not resolved. */
	function effectDescriptor(effect) {
		return effect && effect.type && schema ? schema[effect.type] : null;
	}

	/** Whether a canvas node is a Mask root or MaskLeaf (design §13.1). */
	function isMaskNode(node) {
		return !!(node && node.properties && node.properties.maskRole);
	}

	/** Whether a canvas node is a MaskLeaf. */
	function isMaskLeafNode(node) {
		return !!(node && node.properties && node.properties.maskRole === "leaf");
	}

	/** The mask nodes (root + leaves) of one effect on a tab's canvas. */
	function maskNodesForEffect(tab, effectId) {
		var out = [];
		if (tab && tab.graph && tab.graph._nodes) {
			tab.graph._nodes.forEach(function (node) {
				if (isMaskNode(node) && node.properties.effectId === effectId) {
					out.push(node);
				}
			});
		}
		return out;
	}

	/** The MaskLeaf node owning a reserved slot of an effect, or null. */
	function maskSlotNode(tab, effectId, slotName) {
		var found = null;
		maskNodesForEffect(tab, effectId).forEach(function (node) {
			if (!isMaskLeafNode(node)) {
				return;
			}
			// An unwired field shows no socket (§15.1) but is still owned: match the field
			// list too, so an imported wire re-adds the socket instead of falling back.
			if (inputIndex(node, slotName) >= 0
					|| (node.__maskSlots && node.__maskSlots.indexOf(slotName) >= 0)) {
				found = node;
			}
		});
		return found;
	}

	/**
	 * Add the reserved input socket `name` to a MaskLeaf node when a wire is about to land on
	 * it (design §15.1: the socket exists only while driven). No-op when the socket is there
	 * or the node owns no such field.
	 *
	 * @param {Object} node a canvas MaskLeaf node
	 * @param {string} name the reserved `mask.p<N>.*` slot
	 * @returns {number} the input slot index, or -1
	 */
	function ensureMaskLeafInput(node, name) {
		var at = inputIndex(node, name);
		if (at >= 0) {
			return at;
		}
		if (!isMaskLeafNode(node) || !node.__maskSlots
				|| node.__maskSlots.indexOf(name) < 0
				|| typeof node.addInput !== "function") {
			return -1;
		}
		var added = node.addInput(name, "number");
		if (added) {
			added.color_on = groupColor("Mask");
			added.color_off = added.color_on;
		}
		return inputIndex(node, name);
	}

	/**
	 * Apply the driven→read-only rule to a MaskLeaf node (design §15.1): a wired field shows
	 * its input socket and a read-only value badge, an unwired one shows only the number
	 * widget. The widget swap reuses {@link applySlotState}; the sockets are added/removed so
	 * the leaf never shows both at once.
	 *
	 * @param {Object} node a canvas MaskLeaf node
	 */
	function syncMaskLeafSockets(node) {
		if (!isMaskLeafNode(node) || !node.__maskSlots) {
			return;
		}
		applySlotState(node);
		var wired = wiredInputs(node);
		node.__maskSlots.slice().forEach(function (name) {
			var at = inputIndex(node, name);
			if (wired[name]) {
				ensureMaskLeafInput(node, name);
			} else if (at >= 0 && typeof node.removeInput === "function") {
				node.removeInput(at);
			}
		});
	}

	/** A structural fingerprint of a mask tree; the canvas nodes rebuild only when it changes. */
	function maskSignature(mask) {
		var vfxir = window.VFXIR;
		if (!mask || typeof mask !== "object" || !vfxir) {
			return "none";
		}
		var chain = vfxir.maskNodeState(mask);
		return JSON.stringify({
			invert: chain.invert, space: chain.space, ops: chain.ops,
			leaves: chain.leaves.map(function (leaf) { return leaf.shape; })
		});
	}

	/** Detach and remove a mask node from its graph (lifting `ignore_remove` for the explicit path). */
	function dropMaskNode(node) {
		if (node && node.graph) {
			node.ignore_remove = false;
			node.graph.remove(node);
		}
	}

	/** Build the Mask root node for an effect's node state, beside the effect node. */
	function buildMaskNode(tab, effect, chain, descriptor) {
		var node = LiteGraph.createNode("vfx/mask");
		node.__tab = tab;
		node.properties.effectId = effect.id;
		node.ignore_remove = true;
		node.__chain = chain;
		var edit = function () {
			effect.mask = window.VFXIR.maskFromNodeState(chain);
			// The node already shows the new field; record the fingerprint so the sync below sees no
			// structural change and does not rebuild (and remove) the node whose widget just fired.
			if (tab.__maskSig) {
				tab.__maskSig[effect.id] = maskSignature(effect.mask);
			}
			onMaskChange(tab, false);
		};
		node.addWidget("toggle", "invert", chain.invert === true, function (v) {
			chain.invert = v;
			edit();
		});
		node.addWidget("combo", "space", chain.space || "auto", function (v) {
			chain.space = v === "auto" ? null : v;
			edit();
		}, { values: ["auto", "screen", "world", "dome"] });
		for (var i = 0; i + 1 < chain.leaves.length; i++) {
			(function (index) {
				node.addWidget("combo", "op " + index, chain.ops[index] || "union", function (v) {
					chain.ops[index] = v;
					edit();
				}, { values: window.VFXIR.MASK_OPS });
			})(i);
		}
		// Add another MaskLeaf to the composition (design §13.1). The joins stay left-nested, so the
		// right operand of every composition is a leaf, matching the parser.
		node.addWidget("button", "+ leaf", null, function () {
			var table = window.VFXIR.maskShapeTable(descriptor);
			chain.leaves.push(window.VFXIR.makeMaskLeaf("circle", table));
			while (chain.ops.length < chain.leaves.length - 1) {
				chain.ops.push("union");
			}
			effect.mask = window.VFXIR.maskFromNodeState(chain);
			onMaskChange(tab, true);
		});
		var effectNode = nodeForEffect(tab, effect.id);
		node.pos = effectNode ? [effectNode.pos[0] + 240, effectNode.pos[1] - 20] : [140, 220];
		return node;
	}

	/**
	 * Build a MaskLeaf node for one leaf: the shape combo, the reserved `mask.p&lt;N&gt;.*` input
	 * sockets (one numeric field each) and their edit widgets. The sockets are the wiring point an
	 * ordinary value node connects into; a wire is recorded as a normal param edge and the compiler
	 * consumes it without a schema round-trip.
	 */
	function buildMaskLeafNode(tab, effect, chain, index, leaf, slots, descriptor, table, basePos) {
		var vfxir = window.VFXIR;
		var node = LiteGraph.createNode("vfx/mask_leaf");
		node.__tab = tab;
		node.properties.effectId = effect.id;
		node.properties.leafIndex = index;
		node.ignore_remove = true;
		node.__chain = chain;
		node.__leafIndex = index;
		node.__kindSpec = { sockets: [] };
		node.__widgets = {};
		node.__maskSlots = [];
		var deferred = vfxir.maskShapeDeferred(leaf.shape, table);
		node.title = "leaf " + index + " · " + leaf.shape + (deferred ? " (not authored)" : "");
		node.addWidget("combo", "shape", String(leaf.shape), function (v) {
			chain.leaves[index] = vfxir.makeMaskLeaf(v, table);
			effect.mask = vfxir.maskFromNodeState(chain);
			onMaskChange(tab, true);
		}, { values: vfxir.maskShapeList(descriptor) });
		// Remove this leaf; the remaining leaves keep their order and the joins are re-trimmed.
		node.addWidget("button", "\u00D7 remove leaf", null, function () {
			chain.leaves.splice(index, 1);
			if (chain.ops.length > Math.max(0, chain.leaves.length - 1)) {
				chain.ops.length = Math.max(0, chain.leaves.length - 1);
			}
			effect.mask = chain.leaves.length ? vfxir.maskFromNodeState(chain) : null;
			onMaskChange(tab, true);
		});
		slots.forEach(function (slot) {
			if (slot.deferred) {
				return;
			}
			// Guard stale/foreign nodes whose constructor predates the inputs convention.
			if (!node.properties.inputs) {
				node.properties.inputs = {};
			}
			if (!node.properties.explicit) {
				node.properties.explicit = {};
			}
			node.__maskSlots.push(slot.name);
			node.__kindSpec.sockets.push(slot.name);
			var input = node.addInput(slot.name, "number");
			if (input) {
				input.color_on = groupColor("Mask");
				input.color_off = input.color_on;
			}
			node.properties.inputs[slot.name] = slot.defaultValue;
			node.__widgets[slot.name] = node.addWidget("number", maskSlotLabel(slot),
					slot.defaultValue, function (v) {
				var target = chain.leaves[index];
				if (target) {
					vfxir.maskWriteSlot(target, slot, v);
					node.properties.inputs[slot.name] = v;
					node.properties.explicit[slot.name] = true;
					effect.mask = vfxir.maskFromNodeState(chain);
					onMaskChange(tab, false);
				}
			});
		});
		node.onDrawForeground = drawSlotAffordances;
		node.onMouseDown = slotFieldClick;
		// A horizontal chain: the leaves read a -> b in join order to the right of the Mask
		// root that owns the op combos, instead of a vertical stack under it.
		node.pos = [basePos[0] + 250 + index * 260, basePos[1] + 30];
		return node;
	}

	/** Whether a link from `from` into a specific input slot of `target` already exists. */
	function hasLink(from, target, slotIndex) {
		if (!from || !from.graph || !from.graph.links) {
			return false;
		}
		var links = from.graph.links;
		return Object.keys(links).some(function (id) {
			var link = links[id];
			return link && link.origin_id === from.id && link.target_id === target.id
					&& link.target_slot === slotIndex;
		});
	}

	/**
	 * Reconnect the `mask.p<N>.*` wires of `tab.edges` to the current MaskLeaf sockets (a rebuild
	 * replaces the leaf nodes, dropping their links). The value-node `from` is resolved by IR id.
	 *
	 * @param {Object} tab the tab
	 */
	function connectMaskEdges(tab) {
		if (!tab || !tab.graph) {
			return;
		}
		var byIr = {};
		tab.graph._nodes.forEach(function (node) {
			if (node.properties && node.properties.irId) {
				byIr[node.properties.irId] = node;
			}
		});
		(tab.edges || []).forEach(function (edge) {
			if (edge.input != null || typeof edge.to !== "string" || edge.to.indexOf("mask.") !== 0) {
				return;
			}
			var effectId = edge.effect != null ? edge.effect
					: (tab.effects && tab.effects.length ? tab.effects[0].id : null);
			if (!effectId) {
				return;
			}
			var target = maskSlotNode(tab, effectId, edge.to);
			var from = byIr[edge.from];
			if (!from || !target) {
				return;
			}
			// A leaf whose field is unwired shows no socket (§15.1); re-add it for the wire.
			var slot = ensureMaskLeafInput(target, edge.to);
			if (slot >= 0 && !hasLink(from, target, slot)) {
				from.connect(0, target, slot);
			}
		});
	}

	/**
	 * Rebuild the canvas Mask/MaskLeaf nodes for every effect whose mask changed shape (design
	 * §13.1). The nodes are a view/projection of `effect.mask`: the mask object stays the datapack
	 * serialisation the compiler emits, while the node widgets write it back through {@link
	 * VFXIR.maskFromNodeState}. Idempotent by a structural signature, so a value-only edit does not
	 * rebuild (and lose focus/wires). Removal/creation is guarded so it is not read as a user edit.
	 */
	function syncMaskNodes(tab) {
		if (!tab || !tab.graph || !window.VFXIR || typeof window.VFXIR.maskNodeState !== "function") {
			return;
		}
		var prev = applyingGraph;
		applyingGraph = true;
		try {
			(tab.effects || []).forEach(function (effect) {
				var descriptor = effectDescriptor(effect);
				var offered = window.VFXIR.maskAffordance(descriptor);
				var has = effect.mask != null && typeof effect.mask === "object";
				var sig = offered && has ? maskSignature(effect.mask) : "none";
				if (!tab.__maskSig) {
					tab.__maskSig = {};
				}
				if (tab.__maskSig[effect.id] === sig) {
					return;
				}
				// Remember the leaf positions, so a structural rebuild keeps a leaf the user
				// dragged where it was; a new leaf still lands deterministically in the chain.
				var keptPos = {};
				maskNodesForEffect(tab, effect.id).forEach(function (node) {
					if (isMaskLeafNode(node) && Array.isArray(node.pos)) {
						keptPos[node.properties.leafIndex] = [num(node.pos[0]), num(node.pos[1])];
					}
				});
				maskNodesForEffect(tab, effect.id).forEach(dropMaskNode);
				tab.__maskSig[effect.id] = sig;
				if (sig === "none") {
					return;
				}
				var chain = window.VFXIR.maskNodeState(effect.mask);
				var table = window.VFXIR.maskShapeTable(descriptor);
				var maskNode = buildMaskNode(tab, effect, chain, descriptor);
				tab.graph.add(maskNode);
				var slotsByLeaf = window.VFXIR.maskSlotsByLeaf(effect.mask, table);
				chain.leaves.forEach(function (leaf, i) {
					var leafNode = buildMaskLeafNode(tab, effect, chain, i, leaf,
							slotsByLeaf[i] || [], descriptor, table, maskNode.pos);
					if (keptPos[i]) {
						leafNode.pos = keptPos[i];
					}
					tab.graph.add(leafNode);
				});
				// Attach the tree to the effect node with one cosmetic wire (the compiler
				// reads the `mask.` edges; mask nodes carry no IR id, so the wire is never an edge).
				var effectNode = nodeForEffect(tab, effect.id);
				if (effectNode) {
					var port = outputIndex(maskNode, "mask");
					var into = inputIndex(effectNode, "mask");
					if (port >= 0 && into >= 0) {
						maskNode.connect(port, effectNode, into);
					}
				}
			});
			connectMaskEdges(tab);
			// A fresh leaf shows widgets only (§15.1); sockets appear as wires land (above).
			(tab.effects || []).forEach(function (effect) {
				maskNodesForEffect(tab, effect.id).forEach(function (node) {
					if (isMaskLeafNode(node)) {
						syncMaskLeafSockets(node);
					}
				});
			});
		} finally {
			applyingGraph = prev;
		}
	}

	/**
	 * Add a Mask node tree to the active effect (design §13.1): a `circle` leaf when none exists,
	 * gated on the schema's `structural.supportsMask` so a non-post effect offers none.
	 *
	 * @param {Object} tab the active tab
	 * @returns {boolean} whether a mask node was added
	 */
	function addMaskNode(tab) {
		if (!tab || !tab.effect) {
			return false;
		}
		var effect = tab.effect();
		var descriptor = effectDescriptor(effect);
		if (!window.VFXIR || !window.VFXIR.maskAffordance(descriptor)) {
			status("this effect has no mask");
			return false;
		}
		if (effect.mask == null) {
			effect.mask = window.VFXIR.makeMaskLeaf("circle", window.VFXIR.maskShapeTable(descriptor));
		}
		tab.__maskSig = tab.__maskSig || {};
		tab.__maskSig[effect.id] = null;
		syncMaskNodes(tab);
		onMaskChange(tab, true);
		return true;
	}

	/**
	 * Keep the effect node's reserved `mask.p<N>.*` input sockets for the leafless case only
	 * (design §13.1): when MaskLeaf nodes exist they own the fields, so the effect node shows
	 * just its single `mask` attachment port; an effect whose mask has no leaf nodes keeps the
	 * sockets as the import fallback. Also syncs the Mask/MaskLeaf canvas nodes.
	 *
	 * @param {Object} tab the tab
	 */
	function syncMaskSockets(tab) {
		if (!tab || !tab.graph || !window.VFXIR || typeof window.VFXIR.maskSlots !== "function") {
			return;
		}
		syncMaskNodes(tab);
		(tab.effects || []).forEach(function (effect) {
			var node = nodeForEffect(tab, effect.id);
			if (!node) {
				return;
			}
			var hasLeaves = maskNodesForEffect(tab, effect.id).some(function (n) {
				return isMaskLeafNode(n);
			});
			var wanted = {};
			if (!hasLeaves && effect.mask && typeof effect.mask === "object") {
				window.VFXIR.maskSlots(effect.mask).forEach(function (slot) {
					if (slot.deferred) {
						return;
					}
					wanted[slot.name] = true;
					if (inputIndex(node, slot.name) < 0 && typeof node.addInput === "function") {
						var added = node.addInput(slot.name, "number");
						if (added) {
							added.color_on = groupColor("Mask");
							added.color_off = added.color_on;
						}
					}
				});
			}
			if (node.inputs && typeof node.removeInput === "function") {
				for (var i = node.inputs.length - 1; i >= 0; i--) {
					var name = node.inputs[i].name;
					if (typeof name === "string" && name.indexOf("mask.") === 0 && !wanted[name]) {
						node.removeInput(i);
					}
				}
			}
		});
	}

	/**
	 * The mask sub-editor edit hook (design §13.1): the panel already mutated the effect's mask;
	 * snapshot for undo, refresh the reserved sockets and recompile. A structural edit (shape/op/
	 * leaf add/remove) rebuilds the panel; a value edit leaves it in place so the field keeps focus.
	 *
	 * @param {Object} tab the active tab
	 * @param {boolean} structural whether the mask tree changed shape
	 */
	function onMaskChange(tab, structural) {
		if (!tab) {
			return;
		}
		scheduleCommitHistory();
		syncMaskSockets(tab);
		if (tab.type) {
			requestStructural(tab);
		}
		if (structural) {
			renderInspector();
			renderTimeline();
		}
	}

	/**
	 * The structural sub-editor edit hook (design §13.4): the panel already mutated the active
	 * effect's `positions`/`region`/`entitySelector`/`sound`/`soundPos` (or the `volume`/`pitch`
	 * params); snapshot for undo and recompile. A structural edit (add/remove/toggle) rebuilds the
	 * panel; a value edit leaves it in place so the focused field keeps its caret.
	 *
	 * @param {Object} tab the active tab
	 * @param {boolean} structural whether the panel must rebuild
	 */
	function onStructuralChange(tab, structural) {
		if (!tab) {
			return;
		}
		scheduleCommitHistory();
		if (tab.type) {
			requestStructural(tab);
		}
		if (structural) {
			renderInspector();
			renderTimeline();
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
	 * The authored live source of a param (design §16.9 P1): "bind"/"expr" when the active effect's
	 * slot is an inline bind/expr or is wired to a bind/expr value node, else null (a track or a
	 * constant/curve is not live). Pure-authority: reads the authored IR only, never the running game.
	 *
	 * @param {Object} tab the active tab
	 * @param {string} name the param name
	 * @returns {string|null} "bind", "expr" or null
	 */
	function liveKindFor(tab, name) {
		if (!tab || !window.VFXCompiler || typeof window.VFXCompiler.liveKind !== "function") {
			return null;
		}
		var keys = tab.keys && tab.keys[name];
		var hasTrack = Array.isArray(keys) && keys.length > 0;
		var active = tab.effects && tab.effects.length > 1 ? tab.effect() : null;
		var params = (active ? active.params : tab.params) || {};
		var driverKind = null;
		var source = drivenParamSource(tab, name);
		if (source) {
			var nodes = Array.isArray(tab.valueNodes) ? tab.valueNodes : [];
			for (var i = 0; i < nodes.length; i++) {
				if (nodes[i] && nodes[i].id === source) {
					driverKind = nodes[i].kind;
					break;
				}
			}
		}
		return window.VFXCompiler.liveKind(params[name], hasTrack, driverKind);
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

	/** Whether an effect renders a fullscreen post pass (via the compiler's classification). */
	function effectIsPost(effect) {
		return window.VFXCompiler && typeof window.VFXCompiler.isPostType === "function"
				? window.VFXCompiler.isPostType(effect && effect.type) : true;
	}

	/** The screen layer an effect runs at (0..2). */
	function effectScreenLayer(effect) {
		return window.VFXCompiler && typeof window.VFXCompiler.screenLayerOf === "function"
				? window.VFXCompiler.screenLayerOf(effect) : 1;
	}

	/** Push the view state (order/mute/solo) to the preview — instance ops only, no recompilation. */
	function refreshViewstate(tab) {
		if (window.VFXTimeline && window.VFXTimeline.preview
				&& typeof window.VFXTimeline.preview.viewstate === "function") {
			window.VFXTimeline.preview.viewstate(tab);
		}
	}

	/** Reorder the chain, dragging or via ▲/▼; the reorder is a viewstate change (§16.2). */
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
		refreshViewstate(tab);
	}

	/** A group header row in the Layers panel (screen layer / world). */
	function layerGroupHeader(text) {
		var header = document.createElement("div");
		header.className = "vfx-layer-group";
		header.textContent = text;
		return header;
	}

	/** A warning row in the Layers panel (mixed layers). */
	function layerWarning(text) {
		var warning = document.createElement("div");
		warning.className = "vfx-layer-warn";
		warning.textContent = text;
		return warning;
	}

	/** Build one draggable layer row for an effect (mute/solo/delay/duration/reorder). */
	function renderLayerRow(tab, effect, index) {
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
			refreshViewstate(tab);
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
			refreshViewstate(tab);
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
		return row;
	}

	/**
	 * The Layers panel (design §15.2, §16.2): the tab's effects in application order, grouped by
	 * `screen_layer` (0/1/2) so the `order` axis is legible, with a warning when a chain mixes
	 * layers. Drag-to-reorder, mute/solo (instance ops, no recompilation) and per-node delay/duration.
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
		// A pseudo-row for the collection root (fix H4): selecting it makes the Node timing panel
		// edit the collection's own timing. Shown only for a chain; for a single effect the node IS
		// the root, so a separate row would be redundant.
		if (tab.effects.length >= 2) {
			var rootRow = document.createElement("div");
			rootRow.className = "vfx-layer vfx-layer-root" + (tab.activeEffect < 0 ? " active" : "");
			rootRow.textContent = "▣ " + (tab.name || "collection") + " · collection";
			rootRow.title = "Select the collection root to edit its overall timing";
			rootRow.addEventListener("click", function () {
				tab.activeEffect = -1;
				inspectorName = null;
				renderInspector();
				renderTimeline();
				renderTabs();
			});
			list.appendChild(rootRow);
		}
		// Group by screen layer (§16.2): `order` sorts only inside a layer, so a chain that mixes
		// layers is called out rather than silently composited in chain order.
		var groups = { "0": [], "1": [], "2": [], other: [] };
		tab.effects.forEach(function (effect, index) {
			if (effectIsPost(effect)) {
				groups[String(effectScreenLayer(effect))].push(index);
			} else {
				groups.other.push(index);
			}
		});
		var postLayers = ["0", "1", "2"].filter(function (layer) {
			return groups[layer].length > 0;
		});
		if (postLayers.length > 1) {
			list.appendChild(layerWarning("Chain mixes screen layers (" + postLayers.join(", ")
					+ "): `order` applies only inside a layer."));
		}
		if (groups.other.length > 0 && postLayers.length > 0) {
			list.appendChild(layerWarning("Chain mixes post passes and world/camera effects; "
					+ "they do not share a screen layer."));
		}
		postLayers.forEach(function (layer) {
			list.appendChild(layerGroupHeader("Screen layer " + layer));
			groups[layer].forEach(function (index) {
				list.appendChild(renderLayerRow(tab, tab.effects[index], index));
			});
		});
		if (groups.other.length > 0) {
			list.appendChild(layerGroupHeader("World / camera"));
			groups.other.forEach(function (index) {
				list.appendChild(renderLayerRow(tab, tab.effects[index], index));
			});
		}
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
		if (!tab) {
			dom.inspector.appendChild(inspectorMessage("No active tab"));
			return;
		}
		// Layers panel (design §15.2, P6): the chain order, before the per-node sections.
		dom.inspector.appendChild(renderLayers(tab));
		// Node timing section (design §15.3, fix H4): a chain node's timing is per node; only a
		// single-effect tab or the selected collection root edits the tab/root timing.
		var timingTarget = tab.effects && tab.effects.length >= 2 && tab.effect() ? tab.effect() : tab;
		if (typeof window.VFXInspector.renderTiming === "function") {
			dom.inspector.appendChild(window.VFXInspector.renderTiming(tab, onTimingEdit, timingTarget));
		}
		// A selected collection root has no params of its own; the effect nodes hold them.
		if (tab.effects && tab.effects.length >= 2 && !tab.effect()) {
			dom.inspector.appendChild(inspectorMessage(
					"Collection root selected — select an effect node to edit its parameters"));
			return;
		}
		var descriptor = tab.type ? schema[tab.type] : null;
		var supportsMask = Boolean(descriptor && descriptor.structural
				&& descriptor.structural.supportsMask === true);
		// A schema-advertised structural option (design §13.4) is editable even for an effect with no
		// params — the palette gate must not hide the sub-editor.
		var structural = descriptor && descriptor.structural ? descriptor.structural : null;
		var supportsStructural = Boolean(structural && (structural.supportsPositions === true
				|| structural.supportsRegion === true || structural.supportsEntitySelector === true
				|| structural.supportsSound === true));
		var params = descriptor && Array.isArray(descriptor.params) ? descriptor.params : [];
		if (!params.length && !supportsMask && !supportsStructural) {
			dom.inspector.appendChild(inspectorMessage(
					tab.type ? "No editable parameters" : "Choose an effect type"));
			return;
		}
		if (params.length) {
		// Collapse vector groups into one row (design §15.9.6/P4, fix #21): color_r/_g/_b -> `color`.
		// The rows are then partitioned into the schema's parameter groups (ParamGroups): one
		// collapsible chip section per group, through the inspector's grouping API — no per-type
		// branch, groups come from the schema. An effect without group metadata renders one flat
		// section, exactly as before.
		var sections = typeof window.VFXInspector.paramSections === "function"
				? window.VFXInspector.paramSections(params)
				: [{ group: "", label: "", params: params }];
		var sectionRows = sections.map(function (section) {
			return { group: section.group, label: section.label, rows: groupedParamRows(section.params) };
		});
		var allRows = [];
		sectionRows.forEach(function (entry) {
			allRows = allRows.concat(entry.rows);
		});
		var current = null;
		for (var i = 0; i < allRows.length; i++) {
			if (allRows[i].name === inspectorName
					|| allRows[i].components.some(function (component) { return component.name === inspectorName; })) {
				current = allRows[i];
				break;
			}
		}
		if (!current) {
			current = allRows[0];
			inspectorName = current.name;
		}
		var heading = document.createElement("h2");
		heading.textContent = "Inspector";
		dom.inspector.appendChild(heading);

		/** One param chip: the active/driven/live badges, selecting re-renders the detail. */
		var makeChip = function (row) {
			var source = row.group
					? (row.components.some(function (component) { return drivenParamSource(tab, component.name); })
							? "group" : null)
					: drivenParamSource(tab, row.components[0].name);
			var live = row.components.some(function (component) {
				return liveKindFor(tab, component.name);
			});
			var item = document.createElement("button");
			item.type = "button";
			item.className = "vfx-param" + (row.name === inspectorName ? " active" : "")
					+ (source ? " driven" : "");
			item.textContent = rowLabel(tab, row) + (source ? " ←" : "") + (live ? " ●" : "");
			item.title = source ? ("driven by " + source) : (live ? "live source" : "");
			item.addEventListener("click", function () {
				inspectorName = row.name;
				renderInspector();
			});
			return item;
		};
		if (sections.length === 1 && !sections[0].group) {
			var chooser = document.createElement("div");
			chooser.className = "vfx-params";
			allRows.forEach(function (row) {
				chooser.appendChild(makeChip(row));
			});
			dom.inspector.appendChild(chooser);
		} else {
			var typeKey = tab && tab.type ? tab.type : "";
			sectionRows.forEach(function (entry) {
				var key = typeKey + "/" + entry.group;
				var collapsed = Boolean(paramGroupCollapsed[key]);
				var block = document.createElement("div");
				block.className = "vfx-param-group";
				var head = document.createElement("div");
				head.className = "vfx-param-group-head";
				var toggle = document.createElement("button");
				toggle.type = "button";
				toggle.className = "vfx-param-group-toggle";
				toggle.textContent = collapsed ? "▸" : "▾";
				toggle.title = "Show the params in this group";
				var name = document.createElement("span");
				name.className = "vfx-inspect-name";
				name.textContent = entry.label;
				var paramCount = 0;
				entry.rows.forEach(function (row) {
					paramCount += row.components.length;
				});
				var count = document.createElement("span");
				count.className = "vfx-param-group-count";
				count.textContent = paramCount + (paramCount === 1 ? " param" : " params");
				head.appendChild(toggle);
				head.appendChild(name);
				head.appendChild(count);
				var body = document.createElement("div");
				body.className = "vfx-param-group-content";
				var refreshSection = function () {
					body.textContent = "";
					if (!collapsed) {
						var groupChooser = document.createElement("div");
						groupChooser.className = "vfx-params";
						entry.rows.forEach(function (row) {
							groupChooser.appendChild(makeChip(row));
						});
						body.appendChild(groupChooser);
					}
				};
				// The whole header flips, not just the 16px glyph: a toggle-only target is a
				// guaranteed missed click. One listener on the header (the button bubbles to it).
				head.addEventListener("click", function (event) {
					event.preventDefault();
					collapsed = !collapsed;
					paramGroupCollapsed[key] = collapsed;
					toggle.textContent = collapsed ? "▸" : "▾";
					refreshSection();
				});
				refreshSection();
				block.appendChild(head);
				block.appendChild(body);
				dom.inspector.appendChild(block);
			});
		}
		if (current.group && typeof window.VFXInspector.renderGroup === "function") {
			dom.inspector.appendChild(window.VFXInspector.renderGroup(tab, current, onInspectorEdit));
		} else {
			var param = current.components[0];
			dom.inspector.appendChild(window.VFXInspector.render(tab, param, onInspectorEdit,
					drivenParamSource(tab, param.name), liveKindFor(tab, param.name)));
		}
		}
		// Mask sub-editor (design §13.1): post effects only, gated by the schema's structural flag.
		if (supportsMask && typeof window.VFXInspector.renderMask === "function") {
			var maskSection = window.VFXInspector.renderMask(tab, descriptor, function (structural) {
				onMaskChange(tab, structural);
			});
			if (maskSection) {
				dom.inspector.appendChild(maskSection);
			}
		}
		// Non-post structural sub-editors (design §13.4): positions/region, entity selector, sound —
		// each shown only when the schema advertises it (no per-type branch).
		if (typeof window.VFXInspector.renderStructural === "function") {
			var structSection = window.VFXInspector.renderStructural(tab, descriptor, function (structural) {
				onStructuralChange(tab, structural);
			});
			if (structSection) {
				dom.inspector.appendChild(structSection);
			}
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
		// The upsert + sort + param-seeding lives on the IR (`Tab.setKey`), so the diamond, the lane
		// click and the selftest all exercise one writer.
		if (!tab.setKey) {
			return null;
		}
		var v = value != null ? value : paramDefault(tab, name);
		return tab.setKey(name, time, v);
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
		// A MaskLeaf field shows no socket until driven (§15.1); add it for the new wire.
		var slotIndex = ensureMaskLeafInput(node, inputName);
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
		// A MaskLeaf field shows no socket until driven (§15.1); add it for the new wire.
		var slotIndex = ensureMaskLeafInput(node, inputName);
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

	/** The input socket name under a graph event on an effect node, or null. */
	function effectInputAtEvent(node, event) {
		if (event.canvasX == null || event.canvasY == null || !node.inputs) {
			return null;
		}
		for (var i = 0; i < node.inputs.length; i++) {
			var pos = null;
			try {
				pos = node.getConnectionPos(true, i);
			} catch (e) {
				pos = null;
			}
			if (!pos) {
				continue;
			}
			var dx = event.canvasX - pos[0];
			var dy = event.canvasY - pos[1];
			if (dx * dx + dy * dy < 169) {
				return node.inputs[i].name;
			}
		}
		return null;
	}

	/**
	 * The right-click menu for a field-capable effect socket (design §13.2): the ONLY place a
	 * field node is offered (the palette never lists field kinds). Leaf entries come from the
	 * schema inventory, never hardcoded; "Clear" drops the authored/carried block (explicit null).
	 */
	function openEffectFieldMenu(node, param, event) {
		var tab = node.__tab || activeTab();
		var effect = tab && window.VFXIR && typeof window.VFXIR.effectById === "function"
				? window.VFXIR.effectById(tab, node.properties.effectId) : null;
		if (!tab || !effect) {
			return;
		}
		var items = [];
		fieldFnIds().forEach(function (fnId) {
			items.push({ label: "Field node: " + fnId, action: function () {
				addFieldNode(tab, node, effect, param, { field: fnId });
			} });
		});
		fieldOpList().forEach(function (spec) {
			items.push({ label: "Combine: " + spec.id, action: function () {
				addFieldNode(tab, node, effect, param, { op: String(spec.id) });
			} });
		});
		if (!items.length) {
			items.push({ label: "No field functions in schema", enabled: false, action: function () {} });
		}
		var current = effect.fields && effect.fields[param];
		if (current !== undefined && current !== null) {
			items.push({ label: "Clear field", action: function () {
				ensureHistoryBaseline();
				if (!effect.fields) {
					effect.fields = {};
				}
				effect.fields[param] = null;
				commitHistory();
				materializeGraph(tab);
				afterSlotChange(tab);
			} });
		}
		showMenu(items, event);
	}

	/**
	 * Add a field node wired into an effect's fieldCapable socket (the gated creation path).
	 * A leaf starts from the schema defaults; an op node adopts the socket's current tree as
	 * its `a` child when one is linked, so "Combine" wraps instead of replacing.
	 */
	function addFieldNode(tab, enode, effect, param, seed) {
		ensureHistoryBaseline();
		var node = null;
		if (seed && seed.op != null) {
			node = LiteGraph.createNode("vfx/field_op");
			if (node) {
				node.properties.op = String(seed.op);
				syncFieldOpInputs(node);
			}
		} else {
			node = LiteGraph.createNode("vfx/field");
			if (node) {
				var table = fieldTable();
				var fnId = seed && seed.field ? String(seed.field) : fieldFnIds()[0];
				if (!fnId || !table[fnId]) {
					status("no field functions in schema");
					return null;
				}
				node.properties.fn = fnId;
				rebuildFieldLeaf(node, window.VFXIR && typeof window.VFXIR.makeFieldLeaf === "function"
						? window.VFXIR.makeFieldLeaf(fnId, table) : { field: fnId });
			}
		}
		if (!node) {
			return null;
		}
		node.__tab = tab;
		node.properties.effectId = effect.id;
		node.pos = [num(enode.pos[0]) - 300, num(enode.pos[1]) + 60];
		tab.graph.add(node);
		var slot = inputIndex(enode, param);
		if (seed && seed.op != null && slot >= 0) {
			// Adopt the current tree (if any) as the `a` child, then wire the op to the socket.
			var adopted = fieldRootLink(tab, enode, slot);
			if (adopted) {
				var at = inputIndex(node, "a");
				if (at >= 0) {
					adopted.connect(0, node, at);
				}
			}
		}
		if (slot >= 0) {
			node.connect(0, enode, slot);
		}
		refreshFromCanvas(tab);
		commitHistory();
		if (scene) {
			scene.setDirty(true, true);
		}
		return node;
	}

	/** The field node currently linked into an effect socket, or null. */
	function fieldRootLink(tab, enode, slot) {
		var links = (tab.graph && tab.graph.links) || {};
		var found = null;
		Object.keys(links).forEach(function (id) {
			var link = links[id];
			if (link.target_id === enode.id && link.target_slot === slot) {
				var origin = tab.graph.getNodeById(link.origin_id);
				if (origin && isFieldNode(origin)) {
					found = origin;
				}
			}
		});
		return found;
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
	 * Whether `output` names the default (first) output of the macro instance `fromId`
	 * (design §13.5): instance outputs are built in macro order with the first as slot 0,
	 * which the canvas treats as the nameless default. An unknown macro keeps its output
	 * (its wire stays undrawn and preserved, never silently re-aliased).
	 *
	 * @param {Object} tab the tab (its `valueNodes` carry the instance's macro id)
	 * @param {string} fromId the mapped IR id of the wire's source node
	 * @param {*} output the edge's output name
	 * @returns {boolean} true when the output is the macro's first
	 */
	function isDefaultMacroOutput(tab, fromId, output) {
		if (output == null || !tab || !window.VFXIR
				|| typeof window.VFXIR.subgraphById !== "function") {
			return false;
		}
		var macroId = null;
		(tab.valueNodes || []).forEach(function (entry) {
			if (entry && entry.id === fromId && entry.subgraph != null) {
				macroId = entry.subgraph;
			}
		});
		if (macroId == null) {
			return false;
		}
		var macro = window.VFXIR.subgraphById(tab, macroId);
		var names = macro && macro.outputs && typeof macro.outputs === "object"
				? Object.keys(macro.outputs) : [];
		return names.length > 0 && String(names[0]) === String(output);
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
				if (node.kind === "subgraph" && node.subgraph != null) {
					// A macro instance (design §13.5): the macro id rides beside the bindings.
					out.subgraph = String(node.subgraph);
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
				if (from == null) {
					return;
				}
				var to = rename[String(edge.to)];
				var out;
				if (to == null) {
					// An effect-param wire (e.g. off a macro output): kept as a param wire,
					// tagged like the `inputs`-block wires below so a chain routes it.
					out = { from: from, to: String(edge.to), effect: effect.id };
				} else {
					out = { from: from, to: to };
					if (edge.input != null) {
						out.input = edge.input;
					}
				}
				if (edge.output != null) {
					out.output = edge.output;
					if (isDefaultMacroOutput(tab, from, edge.output)) {
						// The canvas addresses slot 0 as the default output and reads it back
						// without a name, so keep the default form: the engine aliases an
						// `inputs` `{from}` to the first output, and the wire round-trips.
						delete out.output;
					}
				}
				tab.edges.push(out);
			});
		}
		if (childDef && childDef.inputs && typeof childDef.inputs === "object") {
			Object.keys(childDef.inputs).forEach(function (param) {
				var wire = childDef.inputs[param];
				if (wire && typeof wire === "object" && (wire.field != null || wire.op != null)) {
					// A hand-written per-pixel field block (design §13.2): carried into the IR so
					// the field nodes show it and the compiler re-emits it verbatim.
					if (!effect.fields) {
						effect.fields = {};
					}
					effect.fields[param] = snapshot(wire);
					return;
				}
				if (wire && wire.from != null && rename[String(wire.from)]) {
					tab.edges.push({ from: rename[String(wire.from)], to: param, effect: effect.id });
				}
			});
		}
	}

	/**
	 * Carry the top-level structural fields a definition may carry (design §13.4) into an effect IR
	 * entry, so the positions/region/entity-selector/sound sub-editors show them and the compiler
	 * re-emits them identically. A field the source does not carry stays `undefined` (not an empty
	 * list), which keeps the compiler's pass-through clone in charge for imported definitions.
	 *
	 * @param {Object} effect the effect entry (mutated)
	 * @param {Object|null} src the imported definition (or a resolved chain child)
	 */
	function hydrateStructural(effect, src) {
		if (!effect) {
			return;
		}
		effect.positions = src && src.positions !== undefined ? snapshot(src.positions) : undefined;
		effect.region = src && src.region !== undefined ? snapshot(src.region) : undefined;
		effect.entitySelector = src && src.entity_selector != null ? String(src.entity_selector) : undefined;
		effect.sound = src && src.sound != null ? String(src.sound) : undefined;
		effect.soundPos = src && src.sound_pos !== undefined ? snapshot(src.sound_pos) : undefined;
		// Tail slice (design §13.4): the same carry, so the new sub-editors show imported values.
		effect.particle = src && src.particle != null ? String(src.particle) : undefined;
		effect.shape = src && src.shape != null ? String(src.shape) : undefined;
		effect.block = src && src.block != null ? String(src.block) : undefined;
		effect.item = src && src.item != null ? String(src.item) : undefined;
		effect.pattern = src && src.pattern !== undefined ? snapshot(src.pattern) : undefined;
		effect.surface = src && src.surface !== undefined ? snapshot(src.surface) : undefined;
		effect.anchor = src && src.anchor != null ? String(src.anchor) : undefined;
		effect.windowId = src && src.id != null ? String(src.id) : undefined;
		effect.windowTitles = src && src.titles !== undefined ? snapshot(src.titles) : undefined;
		// The top-level `texture` is shared by the window spec and the screen-image spec (one is
		// visible at a time — the sub-editors are flag-gated per type), so one IR field serves both.
		effect.texture = src && src.texture != null ? String(src.texture) : undefined;
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
		// Imported macros (design §13.5) live on the tab beside the value graph; the compiler
		// re-emits them verbatim when untouched.
		tab.subgraphs = Array.isArray(def.subgraphs) ? snapshot(def.subgraphs) : [];
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
				effect.loop = entry.loop === true;
				effect.persistent = entry.persistent === true;
				effect.fade_ticks = num(entry.fade_ticks, 0);
				effect.source = entry.source ? snapshot(entry.source) : null;
				// Carry an imported mask into the editable IR (design §13.1), so it can be viewed and
				// edited; `compileMask` re-emits it identically when untouched.
				effect.mask = entry.source && entry.source.mask ? snapshot(entry.source.mask) : undefined;
				// Carry the imported structural fields (design §13.4) into the IR the same way, so the
				// positions/entity-selector/sound sub-editors show them and export round-trips.
				hydrateStructural(effect, entry.source);
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
			effect.mask = def.mask ? snapshot(def.mask) : undefined;
			hydrateStructural(effect, def);
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
						uuid: effect.uuid == null ? null : String(effect.uuid),
						pos: pos,
						mute: effect.mute === true,
						solo: effect.solo === true,
						delay: num(effect.delay, 0),
						duration: num(effect.duration, 0),
						loop: effect.loop === true,
						persistent: effect.persistent === true,
						fade_ticks: num(effect.fade_ticks, 0)
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
					if (saved.loop != null) {
						effect.loop = saved.loop === true;
					}
					if (saved.persistent != null) {
						effect.persistent = saved.persistent === true;
					}
					if (saved.fade_ticks != null) {
						effect.fade_ticks = num(saved.fade_ticks, effect.fade_ticks);
					}
					effect.mute = saved.mute === true;
					effect.solo = saved.solo === true;
					if (saved.uuid != null) {
						effect.uuid = String(saved.uuid);
					}
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
		pack = null;
		updatePackButton();
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

	/**
	 * Open a modal; `build(body, close)` fills the body. An optional `{ className, onClose }` widens/
	 * stylises the overlay and runs a cleanup when it closes.
	 */
	function openModal(title, build, options) {
		var overlay = document.createElement("div");
		overlay.className = "vfx-modal" + (options && options.className ? " " + options.className : "");
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
			if (options && typeof options.onClose === "function") {
				options.onClose();
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

	/** A labelled field row inside a dialog; returns the row so a caller can append any control. */
	function dialogField(parent, label) {
		var row = document.createElement("label");
		row.className = "vfx-dialog-field";
		var span = document.createElement("span");
		span.textContent = label;
		row.appendChild(span);
		parent.appendChild(row);
		return row;
	}

	/** The value-axis `{lo,hi}` that fits a curve node's points (with a little headroom). */
	function curveRangeFor(points) {
		var lo = 0;
		var hi = 1;
		var seen = false;
		(points || []).forEach(function (point) {
			var value = num(point.value);
			if (!seen) {
				lo = value;
				hi = value;
				seen = true;
			} else {
				lo = Math.min(lo, value);
				hi = Math.max(hi, value);
			}
		});
		if (!seen) {
			return { lo: 0, hi: 1 };
		}
		if (hi - lo < 1e-9) {
			return { lo: lo - 1, hi: hi + 1 };
		}
		var pad = (hi - lo) * 0.1;
		return { lo: lo - pad, hi: hi + pad };
	}

	/**
	 * Open the visual Bézier editor for a curve value node (design §15.4) in a modal, hosted by the
	 * shared `VFXCurveView` component. Edits `node.properties.points` in place; the node's text
	 * widget mirrors it, and a committed drag records undo history + a debounced graph edit.
	 *
	 * @param {Object} node the curve value node
	 */
	function openNodeCurveEditor(node) {
		if (!node || !window.VFXCurveView || typeof window.VFXCurveView.curveView !== "function") {
			status("curve editor unavailable");
			return;
		}
		var tab = node.__tab;
		var total = num(tab && tab.duration, 40) || 40;
		var removeKeys = null;
		openModal("Curve — " + (node.properties.irId || "curve"), function (body) {
			var host = document.createElement("div");
			host.className = "vfx-curve-modal";
			body.appendChild(host);
			var component = window.VFXCurveView.curveView(host, node.properties.points, {
				total: total,
				range: curveRangeFor(node.properties.points),
				width: 860,
				title: "Curve",
				onChange: function () {
					if (node.__syncPoints) {
						node.__syncPoints();
					}
					scheduleGraphEdit(tab);
				},
				onCommit: function () {
					if (node.__syncPoints) {
						node.__syncPoints();
					}
					commitHistory();
					scheduleGraphEdit(tab);
				}
			});
			if (typeof window.VFXCurveView.installKeyHandler === "function") {
				removeKeys = window.VFXCurveView.installKeyHandler(component);
			}
		}, {
			className: "vfx-modal-wide",
			onClose: function () {
				if (removeKeys) {
					removeKeys();
					removeKeys = null;
				}
			}
		});
	}

	/** A labelled text input row inside a dialog; returns the input element. */
	function dialogInput(parent, label, value, placeholder) {
		var row = dialogField(parent, label);
		var input = document.createElement("input");
		input.type = "text";
		input.value = value == null ? "" : value;
		if (placeholder) {
			input.placeholder = placeholder;
		}
		row.appendChild(input);
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
			var worldRow = dialogField(body, "World");
			var worldSelect = document.createElement("select");
			worldRow.appendChild(worldSelect);
			var packRow = dialogField(body, "Datapack");
			var packSelect = document.createElement("select");
			packRow.appendChild(packSelect);
			var pathInput = dialogInput(body, "Or a path", "", "C:\\...\\vfx_demos");
			var msg = dialogMessage(body);
			var actions = dialogActions(body);

			var root = "";
			var current = "";
			var packs = [];

			function option(select, value, label) {
				var opt = document.createElement("option");
				opt.value = value;
				opt.textContent = label;
				select.appendChild(opt);
				return opt;
			}

			// Load one world's datapacks; a world with none, or a failed listing, leaves the select
			// with a single explanatory entry so Open falls back to the manual path.
			function loadPacks(world) {
				packs = [];
				packSelect.textContent = "";
				if (!world) {
					packSelect.disabled = true;
					option(packSelect, "", "(pick a world)");
					return;
				}
				packSelect.disabled = true;
				option(packSelect, "", "loading…");
				window.fetch(window.VFXBridge.url("/worlds/datapacks?world=" + encodeURIComponent(world)))
						.then(function (response) {
							return response.json();
						}).then(function (data) {
							packs = data && Array.isArray(data.datapacks) ? data.datapacks : [];
							packSelect.textContent = "";
							packSelect.disabled = false;
							if (!packs.length) {
								option(packSelect, "", "(no datapacks in this world)");
								return;
							}
							packs.forEach(function (pack) {
								option(packSelect, pack.name,
										pack.name + (pack.kind === "zip" ? "  (zip)" : ""));
							});
						}).catch(function () {
							packSelect.textContent = "";
							packSelect.disabled = false;
							option(packSelect, "", "(could not list datapacks)");
						});
			}

			worldSelect.disabled = true;
			option(worldSelect, "", "loading…");
			window.fetch(window.VFXBridge.url("/worlds")).then(function (response) {
				return response.json();
			}).then(function (data) {
				worldSelect.textContent = "";
				worldSelect.disabled = false;
				root = data && data.root ? String(data.root) : "";
				current = data && data.current != null ? String(data.current) : "";
				var worlds = data && Array.isArray(data.worlds) ? data.worlds : [];
				if (!worlds.length) {
					option(worldSelect, "", "(no worlds found)");
					loadPacks("");
					return;
				}
				worlds.forEach(function (name) {
					var opt = option(worldSelect, name,
							name === current ? name + "  (current world)" : name);
					if (name === current) {
						opt.selected = true;
					}
				});
				if (current) {
					worldSelect.value = current;
				}
				loadPacks(worldSelect.value);
			}).catch(function () {
				worldSelect.textContent = "";
				worldSelect.disabled = false;
				option(worldSelect, "", "(could not list worlds)");
				loadPacks("");
			});

			worldSelect.addEventListener("change", function () {
				loadPacks(worldSelect.value);
			});

			dialogButton(actions, "Cancel", false, close);
			dialogButton(actions, "Open", true, function () {
				var manual = pathInput.value.trim();
				var world = worldSelect.value;
				var name = packSelect.value;
				var entry = null;
				packs.forEach(function (pack) {
					if (pack && pack.name === name) {
						entry = pack;
					}
				});
				var path = manual
						|| (entry && entry.path ? String(entry.path) : "")
						|| (world && name ? window.VFXIR.datapackPath(root, world, name) : "");
				if (!path) {
					msg.show("pick a world and datapack, or enter a path", true);
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
				var note = document.createElement("p");
				note.className = "vfx-dialog-note";
				pane.appendChild(note);
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
					var current = data && data.current != null ? String(data.current) : "";
					note.textContent = current ? "Currently playing: " + current
							: "Not in a single-player world — pick any world below.";
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
						opt.textContent = name === current ? name + "  (current world)" : name;
						if (name === current) {
							opt.selected = true;
							opt.className = "vfx-world-current";
						}
						select.appendChild(opt);
					});
					if (current) {
						select.value = current;
					}
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

	/**
	 * Import a datapack folder or zip. A small pack opens as tabs; a large one opens as a lazy,
	 * searchable index (see {@link openPack}), so opening stays O(N) and independent of size.
	 */
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
		var tPost0 = importNow();
		var settled = false;
		var timer = setTimeout(function () {
			// The POST (or the synchronous open below) is still stuck: say so instead of leaving
			// "importing…" forever. A late response still processes normally when it arrives.
			if (!settled) {
				status("importing " + shortPath(path) + "… (still waiting after 15s)");
			}
		}, 15000);
		bridge.post("/import", { path: path }).then(function (result) {
			var tPost1 = importNow();
			if (!result || result.ok === false) {
				settled = true;
				clearTimeout(timer);
				status("import failed: " + ((result && (result.detail || result.error)) || "no response"));
				return;
			}
			var namespace = result.namespace || "";
			var created;
			try {
				created = openPack(namespace, result.effects || {}, result.sidecar);
			} catch (error) {
				settled = true;
				clearTimeout(timer);
				status("import failed: " + (error && error.message ? error.message : String(error)));
				return;
			}
			var tDone = importNow();
			settled = true;
			clearTimeout(timer);
			pushRecent(path);
			// Import-timing probe: where the wall time went (server read vs browser build), so a
			// slow import reports its hot stage instead of just hanging on "importing…".
			var probe = " (post " + Math.round(tPost1 - tPost0) + "ms"
				+ ", build " + Math.round(tDone - tPost1) + "ms"
				+ (pack && pack.timings
					? " [index " + Math.round(pack.timings.index) + "ms"
						+ ", material " + Math.round(pack.timings.material) + "ms]" : "")
				+ (result.readMs != null ? ", server " + Math.round(result.readMs) + "ms" : "") + ")";
			if (created) {
				status("imported " + created + " effect" + (created === 1 ? "" : "s")
						+ (namespace ? " from " + namespace : "") + probe);
			} else {
				status("opened " + (pack ? pack.count : 0) + " effects"
						+ (namespace ? " from " + namespace : "") + " — choose one to edit" + probe);
			}
		}).catch(function (error) {
			// Any synchronous throw the try/catch above missed must still surface: "importing…"
			// must never stick forever with an empty project and no explanation.
			settled = true;
			clearTimeout(timer);
			status("import failed: " + (error && error.message ? error.message : String(error)));
		});
	}

	/** Wall-clock ms for the import probe (`performance.now` when available). */
	function importNow() {
		if (typeof performance !== "undefined" && typeof performance.now === "function") {
			return performance.now();
		}
		return Date.now();
	}

	/** The current import index (or null) and its sidecar, driving the lazy pack list UI. */
	var pack = null;

	/** The tab-builder hooks {@link window.VFXIR.openPackPlan} drives; one shared object. */
	function packHooks() {
		return {
			suspend: function () {
				historySuspend++;
			},
			resume: function () {
				if (historySuspend > 0) {
					historySuspend--;
				}
			},
			createTab: function (label) {
				return createTab(label, null);
			},
			hydrate: function (tab, def, resolve) {
				hydrateTab(tab, def, resolve);
			},
			materialize: function (tab) {
				materializeGraph(tab);
			},
			select: function (tab) {
				selectTab(project.tabs.indexOf(tab));
			},
			commit: function () {
				commitHistory();
			}
		};
	}

	/**
	 * Open a pack. {@code effects} is the `/import` shape (`{ "<name>": "<effect JSON string>" }`).
	 * The pack is indexed once (O(N), no tabs); a pack at or below {@link VFXIR.MAX_EAGER_TABS} is
	 * materialised immediately (one commit), a bigger one stays lazy and shows the searchable list,
	 * where opening an effect builds exactly one graph. {@code sidecar} layout is applied per tab.
	 *
	 * @param {string} namespace the pack namespace
	 * @param {Object|Array} effects name -> raw effect JSON (object or string)
	 * @param {*} [sidecar] the saved editor layout, or null
	 * @returns {number} the number of tabs created (0 for a lazy open)
	 */
	function openPack(namespace, effects, sidecar) {
		if (!window.VFXIR || typeof window.VFXIR.buildPackIndex !== "function") {
			status("import failed: the IR module is not loaded");
			return 0;
		}
		var tIndex0 = importNow();
		pack = window.VFXIR.buildPackIndex(namespace, effects);
		var tIndex1 = importNow();
		pack.sidecar = sidecar;
		ensureHistoryBaseline();
		var tMaterial0 = importNow();
		var plan = window.VFXIR.openPackPlan(pack, packHooks(), {});
		pack.timings = { index: tIndex1 - tIndex0, material: importNow() - tMaterial0 };
		if (plan.created && pack.sidecar) {
			applySidecar(pack.sidecar, plan.tabs);
		}
		updatePackButton();
		if (plan.lazy) {
			openPackList();
		}
		return plan.created;
	}

	/** Find the index of an already-open tab by name, or -1. */
	function findTabByName(name) {
		for (var i = 0; i < project.tabs.length; i++) {
			if (project.tabs[i].name === name) {
				return i;
			}
		}
		return -1;
	}

	/** Open one pack definition as a tab (or select its existing tab); builds exactly one graph. */
	function openDefinition(entry) {
		if (!pack || !entry) {
			return;
		}
		var label = pack.namespace ? pack.namespace + ":" + entry.name : entry.name;
		var existing = findTabByName(label);
		if (existing >= 0) {
			selectTab(existing);
			return;
		}
		ensureHistoryBaseline();
		var one = { namespace: pack.namespace, resolve: pack.resolve, entries: [entry] };
		var plan = window.VFXIR.openPackPlan(one, packHooks(), {});
		if (plan.created && pack.sidecar) {
			applySidecar(pack.sidecar, plan.tabs);
		}
	}

	/** Open every pack definition as a tab — the O(N) batch path (one commit, tab bar windowed). */
	function openAllDefinitions() {
		if (!pack) {
			return 0;
		}
		ensureHistoryBaseline();
		var plan = window.VFXIR.openPackPlan(pack, packHooks(), { openAll: true });
		if (plan.created && pack.sidecar) {
			applySidecar(pack.sidecar, plan.tabs);
		}
		return plan.created;
	}

	/** Show/hide and label the top-bar "Effects (N)" pack-list button. */
	function updatePackButton() {
		var button = document.getElementById("vfx-pack");
		if (!button) {
			return;
		}
		if (pack && pack.count) {
			button.hidden = false;
			button.textContent = "Effects (" + pack.count + ")";
			button.title = "Open an effect from " + (pack.namespace || "the pack");
		} else {
			button.hidden = true;
		}
	}

	/** The searchable pack list: one row per definition, clicking opens it as a single tab. */
	function openPackList() {
		if (!pack || !pack.count) {
			return;
		}
		openModal("Effects — " + (pack.namespace || "pack"), function (body, close) {
			var search = document.createElement("input");
			search.type = "search";
			search.className = "vfx-pack-search";
			search.placeholder = "Search effects…";
			search.setAttribute("aria-label", "Search effects");
			body.appendChild(search);
			var list = document.createElement("div");
			list.className = "vfx-pack-list";
			body.appendChild(list);
			var note = dialogMessage(body);
			var actions = dialogActions(body);
			dialogButton(actions, "Open all (" + pack.count + ")", false, function () {
				var opened = openAllDefinitions();
				status("opened " + opened + " effects");
				close();
			});
			dialogButton(actions, "Close", false, close);

			function render(filterText) {
				list.textContent = "";
				var needle = String(filterText || "").toLowerCase();
				var shown = 0;
				var matched = 0;
				pack.entries.forEach(function (entry) {
					var label = pack.namespace ? pack.namespace + ":" + entry.name : entry.name;
					if (needle && label.toLowerCase().indexOf(needle) < 0) {
						return;
					}
					matched++;
					if (shown >= window.VFXIR.PACK_LIST_MAX) {
						return;
					}
					shown++;
					var row = document.createElement("button");
					row.type = "button";
					row.className = "vfx-pack-row";
					var nameSpan = document.createElement("span");
					nameSpan.className = "vfx-pack-name";
					nameSpan.textContent = entry.name;
					var typeSpan = document.createElement("span");
					typeSpan.className = "vfx-pack-type";
					var open = findTabByName(label) >= 0;
					typeSpan.textContent = (entry.type || "?") + (open ? " • open" : "");
					if (open) {
						row.className += " open";
					}
					row.appendChild(nameSpan);
					row.appendChild(typeSpan);
					row.addEventListener("click", function () {
						openDefinition(entry);
						close();
					});
					list.appendChild(row);
				});
				if (!matched) {
					note.show("no effect matches “" + filterText + "”", true);
				} else if (matched > shown) {
					note.show("showing " + shown + " of " + matched + " — refine the search");
				} else {
					note.show(matched + " effect" + (matched === 1 ? "" : "s"));
				}
			}

			search.addEventListener("input", function () {
				render(search.value);
			});
			render("");
			search.focus();
		}, { className: "vfx-modal-wide" });
	}

	/**
	 * A read-only diagnostic of the active tab for pasting from devtools (no DOM/state mutation):
	 * tab name/type, IR edge list, canvas/value-node counts, field keys, instance ids, inspector
	 * section vs schema group counts, plus explicit ANOMALY lines. Formatting is pure
	 * (`VFXInspector.formatDiag`).
	 *
	 * @returns {string} the multi-line report
	 */
	function diag() {
		var tab = activeTab();
		if (!tab) {
			return "VFXEditor.diag: no active tab";
		}
		var anomalies = [];
		var nodes = (tab.graph && tab.graph._nodes) ? tab.graph._nodes : [];
		var links = (tab.graph && tab.graph.links) ? tab.graph.links : {};
		var irByGraphId = {};
		var canvasIrIds = {};
		nodes.forEach(function (node) {
			if (node && node.properties && node.properties.irId != null) {
				irByGraphId[node.id] = node.properties.irId;
				canvasIrIds[node.properties.irId] = true;
			}
		});
		var collected = collectCanvasEdges(tab, irByGraphId);
		var drawnKeys = {};
		var bindings = [];
		collected.wires.forEach(function (wire) {
			if (wire.kind === "edge") {
				drawnKeys[wire.key] = true;
			} else if (wire.kind === "binding") {
				bindings.push(wire);
			}
		});
		var irKeys = {};
		(tab.edges || []).forEach(function (edge) {
			irKeys[edgeKeyOf(edge)] = true;
		});
		// Link-comparison anomalies are only stable after the debounced flush ran; while one is
		// pending the IR is simply the pre-edit snapshot.
		var settled = !tab.__graphKey || !graphEdits[tab.__graphKey];
		if (settled) {
			(tab.edges || []).forEach(function (edge) {
				if (!drawnKeys[edgeKeyOf(edge)]) {
					anomalies.push("IR edge " + edge.from + " -> " + edge.to + " has no canvas link");
				}
			});
			collected.wires.forEach(function (wire) {
				if (wire.kind === "edge" && !irKeys[wire.key]) {
					anomalies.push("canvas wire " + wire.key + " has no IR edge");
				}
			});
			bindings.forEach(function (wire) {
				var entry = null;
				(tab.valueNodes || []).forEach(function (node) {
					if (node && node.id === wire.id) {
						entry = node;
					}
				});
				var bound = entry && entry.inputs ? entry.inputs[wire.input] : null;
				if (!bound || bound.from !== wire.from) {
					anomalies.push("canvas wire " + wire.from + " -> " + wire.id + "." + wire.input
							+ " has no IR edge");
				}
			});
		}
		var instanceIds = [];
		(tab.valueNodes || []).forEach(function (entry) {
			if (entry && entry.kind === "subgraph") {
				instanceIds.push(entry.id);
			}
		});
		(tab.edges || []).forEach(function (edge) {
			if (edge && instanceIds.indexOf(edge.from) >= 0 && !canvasIrIds[edge.from]) {
				anomalies.push("instance edge from " + edge.from + " is not on canvas");
			}
		});
		var fieldOwners = {};
		nodes.forEach(function (node) {
			if (isFieldNode(node) && node.properties && node.properties.effectId != null) {
				fieldOwners[node.properties.effectId] = true;
			}
		});
		(tab.effects || []).forEach(function (effect) {
			var fields = effect && effect.fields;
			var live = fields && typeof fields === "object" ? Object.keys(fields).filter(function (key) {
				return fields[key] != null;
			}) : [];
			if (live.length && !fieldOwners[effect.id]) {
				anomalies.push("effect " + effect.id + " fields [" + live.join(", ")
						+ "] have no field node");
			}
		});
		var descriptor = tab.type && schema[tab.type] ? schema[tab.type] : null;
		var params = descriptor && Array.isArray(descriptor.params) ? descriptor.params : [];
		var groups = {};
		params.forEach(function (param) {
			if (param && param.group) {
				groups[param.group] = true;
			}
		});
		var schemaGroups = Object.keys(groups).length;
		var rendered = 0;
		if (dom.inspector && typeof dom.inspector.querySelectorAll === "function") {
			rendered = dom.inspector.querySelectorAll(".vfx-param-group").length;
			if (!rendered && dom.inspector.querySelector(".vfx-params")) {
				rendered = 1;
			}
		}
		if (schemaGroups > 1 && rendered <= 1) {
			anomalies.push("params have " + schemaGroups + " schema groups but "
					+ rendered + " rendered section");
		}
		var current = ((typeof tab.effect === "function" && tab.effect()) || (tab.effects || [])[0]) || null;
		var currentFields = current && current.fields && typeof current.fields === "object"
				? current.fields : {};
		var fieldKeys = Object.keys(currentFields).map(function (key) {
			return currentFields[key] == null ? key + " (removed)" : key;
		});
		var hidden = [];
		effectNodes(tab).forEach(function (node) {
			var info = node.__hiddenInfo || effectHiddenInfo(node);
			if (info.hidden > 0) {
				hidden.push({ id: node.properties.effectId, hidden: info.hidden,
						animated: info.animated });
			}
		});
		var info = {
			name: tab.name,
			type: tab.type,
			effects: (tab.effects || []).length,
			edges: snapshot(tab.edges || []),
			canvasNodes: nodes.length,
			canvasLinks: Object.keys(links).length,
			valueNodes: (tab.valueNodes || []).length,
			fieldKeys: fieldKeys,
			instanceIds: instanceIds,
			hidden: hidden,
			sections: rendered,
			schemaGroups: schemaGroups,
			anomalies: anomalies
		};
		if (window.VFXInspector && typeof window.VFXInspector.formatDiag === "function") {
			return window.VFXInspector.formatDiag(info);
		}
		return "tab: " + info.name + " (type: " + info.type + ")\n" + anomalies.join("\n");
	}

	var VFXEditor = {
		project: project,
		openPack: openPack,
		openDefinition: openDefinition,
		openPackList: openPackList,
		openAllDefinitions: openAllDefinitions,
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
		focusMaskLeaf: focusMaskLeaf,
		slot: slot,
		liveKind: liveKindFor,
		problems: {
			/** Add/replace one problem entry (see {@link reportProblem}). */
			add: reportProblem,
			/** Keep only `keys` under `prefix`, dropping entries a later sync no longer reports. */
			retain: retainProblems,
			/** Drop every entry whose key starts with `prefix`. */
			clear: clearProblems,
			/** The current entries. */
			list: function () {
				var store = problems();
				return store ? store.list() : [];
			},
			/** The current entry count. */
			count: function () {
				var store = problems();
				return store ? store.count() : 0;
			}
		},
		showMenu: showMenu,
		undo: undoHistory,
		redo: redoHistory,
		diag: diag,
		// Headless test seam for the socket groups (scripts/run-editor-grouptest.js):
		// the constructor, partition, layout, toggle, hit test and wired-set helpers.
		groups: {
			make: makeEffectNode,
			sections: effectGroupSections,
			rebuild: rebuildEffectInputs,
			toggle: toggleEffectGroup,
			headerAt: effectGroupHeaderAt,
			isHeader: isGroupHeaderSlot,
			wiredParams: effectWiredParams
		},
		// Headless test seam for hide-unused (same runner): the size default, the eye
		// toggle + hit test, and the hidden/animated counts behind the footer and diag.
		hide: {
			threshold: HIDE_UNUSED_THRESHOLD,
			defaultHide: defaultHideUnused,
			resolve: resolveHideUnused,
			toggleEye: toggleEye,
			eyeAt: effectEyeAt,
			isEye: isEyeSlot,
			hiddenInfo: effectHiddenInfo
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
			return history();
		}
	});
	Object.defineProperty(VFXEditor, "pack", {
		enumerable: true,
		get: function () {
			return pack;
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

	/**
	 * Size the LiteGraph canvas to its wrapper. Prefers `LGraphCanvas.resize()`, which sizes the
	 * foreground and background canvases and marks them dirty — setting the canvas size alone clears
	 * the bitmap without scheduling a repaint, which is what left the graph blank after a window
	 * change. Falls back to a raw size before the canvas exists.
	 */
	function resizeCanvas() {
		if (scene && typeof scene.resize === "function") {
			scene.resize();
			return;
		}
		var width = dom.wrap.clientWidth;
		var height = dom.wrap.clientHeight;
		if (width > 0 && height > 0) {
			dom.canvas.width = width;
			dom.canvas.height = height;
		}
	}

	/** Debounced {@link resizeCanvas} for the window `resize` event (a live drag fires many events). */
	var resizeTimer = 0;
	function scheduleResize() {
		if (resizeTimer) {
			window.clearTimeout(resizeTimer);
		}
		resizeTimer = window.setTimeout(function () {
			resizeTimer = 0;
			resizeCanvas();
		}, 100);
	}

	/* ------------------------------------------------------------------ */
	/* Resizable panels (item 4)                                           */
	/* ------------------------------------------------------------------ */

	/** localStorage key for the persisted panel sizes / collapse flags. */
	var LAYOUT_KEY = "vfxweaver.editor.layout";
	/** Sane bounds (px); the max is re-clamped to the viewport at drag time. */
	var LAYOUT_BOUNDS = {
		inspector: { min: 160, max: 720 },
		timeline: { min: 80, max: 620 }
	};

	/** The panel layout; the widths/heights stay the *expanded* values even while collapsed. */
	var layout = { inspectorW: 280, timelineH: 176, inspectorCollapsed: false, timelineCollapsed: false };

	/** Clamp a panel size into its bounds. */
	function clampLayout(value, which) {
		var bounds = LAYOUT_BOUNDS[which];
		return Math.max(bounds.min, Math.min(bounds.max, Math.round(value)));
	}

	/** Push `layout` onto the CSS custom properties + collapse classes, then resize the canvas. */
	function applyLayout() {
		var rootStyle = document.documentElement.style;
		rootStyle.setProperty("--vfx-inspector-w",
				(layout.inspectorCollapsed ? 0 : layout.inspectorW) + "px");
		rootStyle.setProperty("--vfx-timeline-h",
				(layout.timelineCollapsed ? 0 : layout.timelineH) + "px");
		if (dom.main) {
			dom.main.classList.toggle("vfx-inspector-collapsed", layout.inspectorCollapsed);
		}
		resizeCanvas();
	}

	/** Persist the current layout (never throws: localStorage may be unavailable). */
	function saveLayout() {
		try {
			window.localStorage.setItem(LAYOUT_KEY, JSON.stringify(layout));
		} catch (e) {
			// private mode / disabled storage: the layout is a convenience only
		}
	}

	/** Restore the persisted layout, clamped; a corrupt entry falls back to the defaults. */
	function loadLayout() {
		try {
			var saved = JSON.parse(window.localStorage.getItem(LAYOUT_KEY));
			if (!saved || typeof saved !== "object") {
				return;
			}
			if (isFiniteNumber(Number(saved.inspectorW))) {
				layout.inspectorW = clampLayout(Number(saved.inspectorW), "inspector");
			}
			if (isFiniteNumber(Number(saved.timelineH))) {
				layout.timelineH = clampLayout(Number(saved.timelineH), "timeline");
			}
			layout.inspectorCollapsed = saved.inspectorCollapsed === true;
			layout.timelineCollapsed = saved.timelineCollapsed === true;
		} catch (e) {
			// ignore a corrupt/absent entry
		}
	}

	/** A drag on a splitter: pointer capture on the handle, size from the pointer delta. */
	function beginSplitDrag(event, which) {
		if (!event || event.button !== 0) {
			return;
		}
		event.preventDefault();
		var startX = event.clientX;
		var startY = event.clientY;
		var startW = layout.inspectorW;
		var startH = layout.timelineH;
		var splitter = event.currentTarget;
		splitter.classList.add("active");
		if (splitter.setPointerCapture) {
			splitter.setPointerCapture(event.pointerId);
		}
		function maxInspector() {
			// leave the palette (240) and a usable canvas (160) in the viewport
			return dom.main ? Math.max(LAYOUT_BOUNDS.inspector.min, dom.main.clientWidth - 400)
					: LAYOUT_BOUNDS.inspector.max;
		}
		function onMove(moveEvent) {
			if (which === "inspector") {
				layout.inspectorW = Math.max(LAYOUT_BOUNDS.inspector.min,
						Math.min(maxInspector(), Math.round(startW - (moveEvent.clientX - startX))));
				layout.inspectorCollapsed = false;
			} else {
				layout.timelineH = Math.max(LAYOUT_BOUNDS.timeline.min,
						Math.min(LAYOUT_BOUNDS.timeline.max, Math.round(startH - (moveEvent.clientY - startY))));
				layout.timelineCollapsed = false;
			}
			applyLayout();
		}
		function onUp() {
			splitter.classList.remove("active");
			if (splitter.releasePointerCapture) {
				try {
					splitter.releasePointerCapture(event.pointerId);
				} catch (e) {
					// the pointer may already be released
				}
			}
			document.removeEventListener("pointermove", onMove);
			document.removeEventListener("pointerup", onUp);
			saveLayout();
		}
		document.addEventListener("pointermove", onMove);
		document.addEventListener("pointerup", onUp);
	}

	/** Collapse/expand a panel (double-click on its splitter); the expanded size is remembered. */
	function toggleSplit(which) {
		if (which === "inspector") {
			layout.inspectorCollapsed = !layout.inspectorCollapsed;
		} else {
			layout.timelineCollapsed = !layout.timelineCollapsed;
		}
		applyLayout();
		saveLayout();
	}

	/** Wire both splitters: drag to resize, double-click to collapse, arrow keys to nudge. */
	function wireLayout() {
		loadLayout();
		applyLayout();
		if (dom.splitInspector) {
			dom.splitInspector.addEventListener("pointerdown", function (event) {
				beginSplitDrag(event, "inspector");
			});
			dom.splitInspector.addEventListener("dblclick", function () {
				toggleSplit("inspector");
			});
			dom.splitInspector.addEventListener("keydown", function (event) {
				if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
					event.preventDefault();
					layout.inspectorW = clampLayout(layout.inspectorW
							+ (event.key === "ArrowLeft" ? 16 : -16), "inspector");
					layout.inspectorCollapsed = false;
					applyLayout();
					saveLayout();
				}
			});
		}
		if (dom.splitTimeline) {
			dom.splitTimeline.addEventListener("pointerdown", function (event) {
				beginSplitDrag(event, "timeline");
			});
			dom.splitTimeline.addEventListener("dblclick", function () {
				toggleSplit("timeline");
			});
			dom.splitTimeline.addEventListener("keydown", function (event) {
				if (event.key === "ArrowUp" || event.key === "ArrowDown") {
					event.preventDefault();
					layout.timelineH = clampLayout(layout.timelineH
							+ (event.key === "ArrowUp" ? 16 : -16), "timeline");
					layout.timelineCollapsed = false;
					applyLayout();
					saveLayout();
				}
			});
		}
	}

	/**
	 * The revision/status badge in the top bar. When a preview session exists it shows the live
	 * preview state (`rev N → live ●`, `ended @ X/Y`, syncing), else the legacy `/compile` badge.
	 * The text/class is computed by the pure {@code VFXCompiler.previewStatus} (design §16.9 P1).
	 */
	function updateRevBadge(state) {
		if (!dom.revbadge) {
			return;
		}
		var pv = window.VFXTimeline && window.VFXTimeline.preview
				? window.VFXTimeline.preview.state() : null;
		var total = window.VFXTimeline && typeof window.VFXTimeline.duration === "function"
				? window.VFXTimeline.duration(activeTab()) : (pv ? pv.duration : 0);
		var status = {
			revision: pv ? pv.revision : (Number(project.revision) || 0),
			playing: pv ? pv.playing : false,
			ended: pv ? pv.ended : false,
			tick: pv ? pv.tick : 0,
			syncing: pv ? pv.syncing : state === "syncing",
			paused: previewPaused,
			offline: state === "offline",
			error: state === "error"
		};
		if (window.VFXCompiler && typeof window.VFXCompiler.previewStatus === "function") {
			var shown = window.VFXCompiler.previewStatus(status, total);
			dom.revbadge.textContent = shown.text;
			dom.revbadge.className = "vfx-badge" + (shown.cls ? " " + shown.cls : "");
		}
	}

	/**
	 * Preview live-badge watchdog (design §16.9 P1): `preview/state` frames stop while the game is
	 * paused/unfocused or the client thread is busy. When the browser believes the preview is
	 * playing but no frame has arrived for a while, the badge says so ("paused / ожидание игры")
	 * instead of looking frozen; the next frame clears it. No values are fabricated.
	 */
	var PREVIEW_STATE_TIMEOUT_MS = 1500;
	var previewStateAt = 0;
	var previewPaused = false;

	/** Note a fresh `preview/state` frame: the game is talking again. */
	function notePreviewState() {
		previewStateAt = Date.now();
		previewPaused = false;
	}

	/** Flag a stalled live preview (frames stopped while the browser thinks it is playing). */
	function checkPreviewState() {
		var pv = window.VFXTimeline && window.VFXTimeline.preview
				? window.VFXTimeline.preview.state() : null;
		var paused = !!(pv && pv.playing) && previewStateAt > 0
				&& (Date.now() - previewStateAt) > PREVIEW_STATE_TIMEOUT_MS;
		if (paused !== previewPaused) {
			previewPaused = paused;
			updateRevBadge();
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
			var message = (data && (data.detail || data.error)) || "error";
			status("bridge: " + message);
			reportProblem({
				key: "bridge:" + message,
				severity: "error",
				message: "bridge: " + message
			});
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
		bridge.on("preview/state", function (data) {
			notePreviewState();
			updateRevBadge();
			if (data && data.ended) {
				status("preview ended — press ▶ to replay");
			}
		});
		bridge.on("preview/error", function (data) {
			var defId = data && data.defId ? String(data.defId) : null;
			var ref = defId && window.VFXCompiler
					? window.VFXCompiler.nodeRefFromDefId(defId) : null;
			reportProblem({
				key: defId ? "reject:" + defId : "error:" + ((data && data.message) || "error"),
				severity: "error",
				message: "preview: " + ((data && data.message) || "error"),
				context: defId ? "[" + (ref ? ref.slug + "/" + ref.uuid : defId) + "]" : null,
				slug: ref ? ref.slug : null,
				uuid: ref ? ref.uuid : null
			});
			updateRevBadge("error");
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
			// Always re-sync, including the empty chain: an emptied tab must clear the preview
			// (design §16.9 P1), not leave the last effect playing.
			requestStructural(tab);
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

	/** Whether a canvas node is a groupable value node (not an effect/mask/field node). */
	function isGroupableNode(node) {
		return !!(node && node.properties && node.properties.kind && node.properties.irId
				&& !isEffectNode(node) && !isMaskNode(node) && !isFieldNode(node));
	}

	/**
	 * Group the selected value nodes into a subgraph macro (design §13.5): one `subgraphs`
	 * entry plus one instance node with named inputs/outputs (`$name`, first output = default).
	 */
	function groupSelectionIntoMacro() {
		var tab = activeTab();
		if (!tab) {
			return;
		}
		refreshFromCanvas(tab);
		var ids = selectedNodes().filter(isGroupableNode).map(function (node) {
			return node.properties.irId;
		});
		if (!ids.length) {
			status("select value nodes to group into a macro");
			return;
		}
		if (!window.VFXIR || typeof window.VFXIR.groupSelection !== "function") {
			return;
		}
		var res = window.VFXIR.groupSelection(tab, ids, null);
		if (!res || res.error) {
			status("cannot group into a macro: " + (res && res.error ? res.error : "error"));
			return;
		}
		materializeGraph(tab);
		afterSlotChange(tab);
		status("macro " + res.macroId + " (" + ids.length + " nodes)");
	}

	/** Expand the selected macro instances back into plain nodes (design §13.5 ungroup). */
	function ungroupSelectedMacros() {
		var tab = activeTab();
		if (!tab) {
			return;
		}
		refreshFromCanvas(tab);
		var ids = selectedNodes().filter(function (node) {
			return isGroupableNode(node) && node.properties.kind === "subgraph";
		}).map(function (node) {
			return node.properties.irId;
		});
		if (!ids.length) {
			status("select a macro instance to expand it back");
			return;
		}
		if (!window.VFXIR || typeof window.VFXIR.ungroupMacro !== "function") {
			return;
		}
		var expanded = 0;
		ids.forEach(function (id) {
			var res = window.VFXIR.ungroupMacro(tab, id);
			if (res && !res.error) {
				expanded++;
			}
		});
		if (!expanded) {
			status("cannot expand the selected macro");
			return;
		}
		materializeGraph(tab);
		afterSlotChange(tab);
		status("expanded " + expanded + " macro" + (expanded === 1 ? "" : "s"));
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
				["macro buttons", "group selection into a subgraph macro / expand it back (§13.5)"],
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

	/** Space: play the active tab, pause it when playing, or replay when the preview ended. */
	function transportToggle() {
		var tab = activeTab();
		if (!tab || !tab.type || !window.VFXTimeline) {
			return;
		}
		var pv = window.VFXTimeline.preview;
		if (pv && pv.state().ended && typeof window.VFXTimeline.replay === "function") {
			window.VFXTimeline.replay(tab);
			return;
		}
		if (typeof window.VFXTimeline.isPlaying === "function" && window.VFXTimeline.isPlaying()) {
			if (typeof window.VFXTimeline.pause === "function") {
				window.VFXTimeline.pause(tab);
			} else {
				window.VFXTimeline.stop(tab);
			}
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
				// A selected timeline key wins while it is still on the active tab; a stale key (or
				// none) falls through to the canvas node deletion (review G1).
				var deletedKey = window.VFXTimeline
						&& typeof window.VFXTimeline.deleteSelectedKey === "function"
						&& window.VFXTimeline.deleteSelectedKey();
				if (!deletedKey) {
					deleteSelection();
				}
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
		scene.graph.config.links_ontop = true;
		// Hovering a connection highlights it (white + slightly thicker) so a dense chain is readable.
		// LiteGraph tracks the hovered link in `over_link_center` but only coloured it in the tooltip;
		// wrap `renderLink` once (guarded) rather than editing the vendored library.
		var canvasProto = LiteGraph.LGraphCanvas.prototype;
		if (!canvasProto.__vfxHoverLink) {
			canvasProto.__vfxHoverLink = true;
			var originalRenderLink = canvasProto.renderLink;
			canvasProto.renderLink = function (ctx, a, b, link, skipBorder, flow, color, startDir, endDir, sublines) {
				var hovered = link != null && link === this.over_link_center;
				if (hovered) {
					this.connections_width += 2;
				}
				try {
					return originalRenderLink.call(this, ctx, a, b, link, skipBorder, flow,
							hovered ? "#FFFFFF" : color, startDir, endDir, sublines);
				} finally {
					if (hovered) {
						this.connections_width -= 2;
					}
				}
			};
		}
		// Show-all while a link drags, restore on drop/cancel (see beginLinkReveal): the
		// vendor exposes no drag event, so wrap the pair that owns `connecting_*` — once,
		// guarded, never editing litegraph.js itself.
		if (!canvasProto.__vfxLinkReveal) {
			canvasProto.__vfxLinkReveal = true;
			var originalMouseDown = canvasProto.processMouseDown;
			var originalMouseUp = canvasProto.processMouseUp;
			canvasProto.processMouseDown = function (event) {
				var result = originalMouseDown ? originalMouseDown.call(this, event) : undefined;
				try {
					if (this.connecting_node || this.connecting_output || this.connecting_input) {
						beginLinkReveal();
					}
				} catch (e) {
					// reveal only: never break the drag
				}
				return result;
			};
			canvasProto.processMouseUp = function (event) {
				var result = originalMouseUp ? originalMouseUp.call(this, event) : undefined;
				try {
					endLinkReveal();
				} catch (e) {
					// restore only: never break the drop
				}
				return result;
			};
		}
		// Intercept the node right-click: a value-node field shows the slot menu (replace with
		// expr/bind, animate, disconnect), a field-capable effect socket shows the field menu
		// (design §13.2 — the only place a field node is offered), anything else keeps LiteGraph's.
		var originalContextMenu = scene.processContextMenu;
		scene.processContextMenu = function (node, event) {
			var tab = activeTab();
			if (node && tab && isEffectNode(node)) {
				var socket = effectInputAtEvent(node, event);
				if (socket && socket !== "in" && fieldGated(schema[node.properties.effectType], socket)) {
					openEffectFieldMenu(node, socket, event);
					return;
				}
			}
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
		window.addEventListener("resize", scheduleResize);
		wireLayout();
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
		if (dom.pack) {
			dom.pack.addEventListener("click", openPackList);
		}
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
		// Ended badge is the replay affordance (design §16.9 P1): click seeks to 0 and plays.
		if (dom.revbadge) {
			dom.revbadge.addEventListener("click", function () {
				var pv = window.VFXTimeline && window.VFXTimeline.preview;
				if (!pv || !pv.state().ended) {
					return;
				}
				var tab = activeTab();
				if (tab && tab.type && typeof window.VFXTimeline.replay === "function") {
					window.VFXTimeline.replay(tab);
				}
			});
		}
		// Watchdog: surface a stalled live preview (game paused/unfocused) in the badge.
		window.setInterval(checkPreviewState, 500);
		wireProblems();
		renderProblems();
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
					status("Loaded " + paletteTypes().length + " effect types");
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

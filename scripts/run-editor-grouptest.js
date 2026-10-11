/*
 * Headless test for effect-node socket groups (app.js).
 *
 * app.js is DOM-bound at load (getElementById) but boots on DOMContentLoaded, so with small
 * window/document/LiteGraph stubs it loads headlessly without booting, and the VFXEditor.groups
 * seam (make/sections/rebuild/toggle/headerAt/isHeader/wiredParams) drives the real canvas
 * logic: group partition, header layout, collapse/expand with wire preservation, and the IR
 * persist field. The VFXEditor.hide seam (threshold/defaultHide/resolve/toggleEye/eyeAt/isEye/
 * hiddenInfo) drives the hide-unused half: size defaults, the eye row, animated counts and the
 * IR persist field. ir.js + compiler.js load too (as in run-editor-selftest.js), so the last test
 * proves the persist fields never reach the emitted definition. No dependencies.
 *
 *   node scripts/run-editor-grouptest.js
 */
"use strict";

var fs = require("fs");
var path = require("path");
var vm = require("vm");

var editorDir = path.join(__dirname, "..", "src", "client", "resources", "assets",
		"vfxweaver", "editor");

function elementStub() {
	return {
		textContent: "",
		value: "",
		hidden: false,
		className: "",
		title: "",
		style: {},
		dataset: {},
		classList: {
			add: function () {},
			remove: function () {},
			toggle: function () {},
			contains: function () { return false; }
		},
		setAttribute: function () {},
		getAttribute: function () { return null; },
		appendChild: function () {},
		addEventListener: function () {},
		removeEventListener: function () {},
		querySelector: function () { return null; },
		querySelectorAll: function () { return []; },
		clientWidth: 800,
		clientHeight: 600,
		width: 800,
		height: 600
	};
}

// app.js reads these at load; boot never runs (readyState stays "loading").
global.window = global;
global.location = { search: "" };
global.document = {
	readyState: "loading",
	getElementById: function () { return elementStub(); },
	createElement: function () { return elementStub(); },
	addEventListener: function () {},
	documentElement: { style: { setProperty: function () {} } }
};
global.LiteGraph = {
	NODE_SLOT_HEIGHT: 20,
	NODE_WIDGET_HEIGHT: 20,
	registered_node_types: {},
	registerNodeType: function () {},
	createNode: function () { return null; }
};
var realSetTimeout = setTimeout;
global.setTimeout = function () { return 0; };
global.clearTimeout = function () {};

["ir.js", "compiler.js", "app.js"].forEach(function (file) {
	var source = fs.readFileSync(path.join(editorDir, file), "utf8");
	vm.runInThisContext(source, { filename: file });
});
global.setTimeout = realSetTimeout;

var groups = global.VFXEditor.groups;
var VFXIR = global.VFXIR;
var compileTab = global.VFXCompiler && global.VFXCompiler.compileTab;
if (!groups || !VFXIR || typeof compileTab !== "function") {
	throw new Error("VFXEditor.groups/VFXIR/VFXCompiler not loaded");
}

/* Minimal canvas node stand-in (the constructor only needs addInput/addOutput/size). */
function FakeNode() {
	this.inputs = [];
	this.outputs = [];
	this.properties = {};
	this.flags = {};
	this.size = [240, 100];
	this.pos = [0, 0];
	this.widgets = [];
	this.graph = null;
}
FakeNode.prototype.addInput = function (name, type, extra) {
	var slot = { name: name, type: type, link: null };
	if (extra) {
		Object.keys(extra).forEach(function (key) { slot[key] = extra[key]; });
	}
	this.inputs.push(slot);
	this.setSize(this.computeSize());
	return slot;
};
FakeNode.prototype.addOutput = function (name, type) {
	var slot = { name: name, type: type, links: [] };
	this.outputs.push(slot);
	return slot;
};
FakeNode.prototype.computeSize = function () {
	return [240, 30 + this.inputs.length * 20];
};
FakeNode.prototype.setSize = function (size) { this.size = size; };
FakeNode.prototype.setDirtyCanvas = function () {};

function fakeGraph() {
	return { links: {}, _version: 0, getNodeById: function () { return null; } };
}

var descriptor = {
	params: [
		{ name: "anchor_yaw", kind: "float", group: "Anchor" },
		{ name: "anchor_pitch", kind: "float", group: "Anchor" },
		{ name: "dome_radius", kind: "float", group: "Dome" },
		{ name: "tile_scale", kind: "float", group: "Tile" },
		{ name: "color_r", kind: "float", group: "Color" },
		{ name: "intensity", kind: "float", group: "" }
	],
	structural: { supportsMask: true }
};
global.VFXEditor.schema.sky_pattern = descriptor;

function makeNode(id) {
	var Ctor = groups.make("sky_pattern", descriptor);
	var node = Object.create(FakeNode.prototype);
	FakeNode.call(node);
	Ctor.call(node);
	node.id = id == null ? 5 : id;
	node.graph = fakeGraph();
	return node;
}

function inputNames(node) {
	return node.inputs.map(function (slot) { return slot.name; });
}

function fakeTab(node) {
	var tab = { effects: [{ id: "fx_1" }], valueNodes: [], edges: [], subgraphs: [] };
	node.__tab = tab;
	node.properties.effectId = "fx_1";
	return tab;
}

var failures = 0;
function check(condition, message) {
	if (condition) {
		console.log("PASS  " + message);
	} else {
		failures++;
		console.log("FAIL  " + message);
	}
}

(function () {
	var sections = groups.sections(descriptor);
	check(sections.length === 5, "five sections (four groups + default)");
	check(sections[0].group === "Anchor" && sections[0].label === "Anchor"
			&& sections[0].params.length === 2, "Anchor section first, two members");
	check(sections[4].group === "" && sections[4].label === "Parameters"
			&& sections[4].params[0].name === "intensity",
			"ungrouped params share one trailing default section");
})();

(function () {
	var node = makeNode();
	check(JSON.stringify(inputNames(node)) === JSON.stringify(["__eye", "in", "__group:Anchor",
			"anchor_yaw", "anchor_pitch", "__group:Dome", "dome_radius", "__group:Tile",
			"tile_scale", "__group:Color", "color_r", "intensity", "mask"]),
			"fresh node lays out the eye row, then headers + members, mask last");
	check(node.outputs.length === 1 && node.outputs[0].name === "out", "flow out port kept");
	check(node.inputs[2].label === "\u25BE Anchor", "expanded header label");
	check(groups.isHeader(node.inputs[2]) && !groups.isHeader(node.inputs[1])
			&& !groups.isHeader(node.inputs[3]), "only header rows are headers");
	check(global.VFXEditor.hide.isEye(node.inputs[0]) && !global.VFXEditor.hide.isEye(node.inputs[1]),
			"only the first row is the eye row");
})();

(function () {
	var node = makeNode();
	var tab = fakeTab(node);
	groups.toggle(node, "Anchor");
	check(JSON.stringify(inputNames(node)) === JSON.stringify(["__eye", "in", "__group:Anchor",
			"__group:Dome", "dome_radius", "__group:Tile", "tile_scale", "__group:Color",
			"color_r", "intensity", "mask"]), "collapse hides only the unwired members");
	check(node.inputs[2].label === "\u25B8 Anchor (2)", "collapsed header shows hidden count");
	check(tab.effects[0].collapsedGroups
			&& tab.effects[0].collapsedGroups.Anchor === true, "collapse persists in IR");
	groups.toggle(node, "Anchor");
	check(inputNames(node).indexOf("anchor_yaw") >= 0, "expand restores members");
	check(!("collapsedGroups" in tab.effects[0]), "expand-all clears the IR field");
})();

(function () {
	var node = makeNode();
	fakeTab(node);
	var at = inputNames(node).indexOf("anchor_pitch");
	node.inputs[at].link = "10";
	node.graph.links["10"] = { origin_id: 7, origin_slot: 0, target_id: node.id,
		target_slot: at };
	groups.toggle(node, "Anchor");
	var names = inputNames(node);
	check(names.indexOf("anchor_pitch") >= 0, "a wired member stays as a stub");
	check(node.graph.links["10"].target_slot === names.indexOf("anchor_pitch"),
			"the surviving wire is repointed, never dropped");
	groups.toggle(node, "Anchor");
	check(node.graph.links["10"] && node.inputs[inputNames(node).indexOf("anchor_pitch")].link === "10",
			"expand keeps the wire on the same link");
})();

(function () {
	var node = makeNode();
	node.__groupCollapsed = { Dome: true };
	groups.rebuild(node, { dome_radius: true });
	check(inputNames(node).indexOf("dome_radius") >= 0, "materialise keeps IR-wired stubs");
	groups.rebuild(node, {});
	check(inputNames(node).indexOf("dome_radius") < 0, "unwired members hide on rebuild");
})();

(function () {
	var node = makeNode();
	var row = inputNames(node).indexOf("__group:Dome");
	check(groups.headerAt(node, [2, row * 20 + 3]) === "Dome", "header hit at the left edge");
	check(groups.headerAt(node, [node.size[0] - 2, row * 20 + 17]) === "Dome",
			"header hit at the right edge (whole row, not the glyph)");
	check(groups.headerAt(node, [120, (row + 1) * 20 + 3]) === null, "socket rows miss");
	check(groups.headerAt(node, [120, -4]) === null, "title bar misses");
})();

(function () {
	check(typeof groups.rowAction === "function", "row-action decision exposed");
	var node = makeNode();
	var row = inputNames(node).indexOf("__group:Dome");
	check(groups.rowAction(node, [10, row * 20 + 14]) === "Dome",
			"row action toggles at the socket dot (the wire-drag zone)");
	check(groups.rowAction(node, [2, 3]) === "eye", "row action hits the eye at the left edge");
	check(groups.rowAction(node, [120, (row + 1) * 20 + 3]) === null,
			"row action misses socket rows");
})();

(function () {
	function build(withGroups) {
		var tab = new VFXIR.Tab("sky", "sky_pattern");
		tab.effect().anchor = "moon";
		tab.effect().pattern = { figure: "rect" };
		tab.effect().surface = { faces: ["up"], min: 60, max: 96 };
		tab.effect().mask = { op: "union", a: { shape: "circle" }, b: { shape: "rect" } };
		tab.valueNodes = [{ id: "v", kind: "constant", inputs: { value: 2 } }];
		tab.edges = [{ from: "v", to: "mask.p0.p0" }];
		if (withGroups) {
			tab.effect().collapsedGroups = { Anchor: true, Color: true };
			tab.effect().hideUnused = true;
			tab.effect().shownSockets = { anchor_yaw: true };
			tab.valueNodes[0].collapsed = true;
		}
		return tab;
	}
	var plain = compileTab(build(false));
	check(compileTab(build(true)) === plain, "collapsedGroups+hideUnused never reach the emit");
	check(plain.indexOf("collapsedGroups") < 0 && plain.indexOf("hideUnused") < 0
			&& plain.indexOf("shownSockets") < 0, "no view state leaks into the datapack");
})();

/* Hide-unused (the VFXEditor.hide seam): size defaults, eye row, animated counts. */

var bigDescriptor = { params: [], structural: { supportsMask: true } };
["a1", "a2", "a3", "a4", "a5", "a6", "a7"].forEach(function (name) {
	bigDescriptor.params.push({ name: name, kind: "float", group: "Alpha" });
});
["b1", "b2", "b3", "b4", "b5"].forEach(function (name) {
	bigDescriptor.params.push({ name: name, kind: "float", group: "Beta" });
});
bigDescriptor.params.push({ name: "tail1", kind: "float", group: "" });
bigDescriptor.params.push({ name: "tail2", kind: "float", group: "" });
global.VFXEditor.schema.big_effect = bigDescriptor;

function makeBigNode(id) {
	var Ctor = groups.make("big_effect", bigDescriptor);
	var node = Object.create(FakeNode.prototype);
	FakeNode.call(node);
	Ctor.call(node);
	node.id = id == null ? 9 : id;
	node.graph = fakeGraph();
	return node;
}

(function () {
	var hide = global.VFXEditor.hide;
	check(!!hide, "hide seam exposed");
	check(hide.threshold === 12, "hide threshold is 12 inputs");
	check(hide.defaultHide(descriptor) === false, "six params default to show-all");
	check(hide.defaultHide(bigDescriptor) === true, "fourteen params default to hide-unused");
	check(hide.defaultHide(null) === false, "unknown type defaults to show-all");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	fakeTab(node);
	node.__hideUnused = hide.resolve(node.__tab, node);
	groups.rebuild(node, null);
	check(JSON.stringify(inputNames(node)) === JSON.stringify(["__eye", "in",
			"__group:Alpha", "__group:Beta", "mask"]),
			"a big fresh node shows only the eye row, in, headers and mask");
	var info = hide.hiddenInfo(node);
	check(info.hidden === 14 && info.animated === 0, "fourteen hidden, none animated");
	check(node.inputs[0].label.indexOf("+14 hidden") >= 0, "eye label counts the hidden");
	check(node.size[1] === 30 + 5 * 20, "no footer line: the eye row carries the only counter");
	var small = makeNode();
	check(hide.hiddenInfo(small).hidden === 0, "a small node hides nothing");
	check(small.size[1] === 30 + small.inputs.length * 20, "no footer when nothing hides");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	fakeTab(node);
	node.__hideUnused = false;
	groups.rebuild(node, null);
	var at = inputNames(node).indexOf("a3");
	node.inputs[at].link = "11";
	node.graph.links["11"] = { origin_id: 7, origin_slot: 0, target_id: node.id,
		target_slot: at };
	node.__hideUnused = true;
	groups.rebuild(node, null);
	var names = inputNames(node);
	check(names.indexOf("a3") >= 0 && names.indexOf("a1") < 0,
			"a wired socket stays visible while the rest hide");
	check(node.graph.links["11"].target_slot === names.indexOf("a3"),
			"the surviving wire is repointed, never dropped");
	check(hide.hiddenInfo(node).hidden === 13, "the wired socket leaves the hidden count");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	var tab = fakeTab(node);
	tab.effects[0].keys = { b2: [{ time: 0, value: 1 }] };
	tab.effects[0].shownSockets = { b1: true };
	node.__hideUnused = true;
	groups.rebuild(node, null);
	var names = inputNames(node);
	check(names.indexOf("b1") >= 0, "a pinned socket stays visible");
	var info = hide.hiddenInfo(node);
	check(info.hidden === 13 && info.animated === 1, "hidden animated params are counted");
	check(node.inputs[0].label.indexOf("1 animated") >= 0,
			"the eye row carries the animated count");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	var tab = fakeTab(node);
	check(hide.resolve(tab, node) === true, "resolve falls back to the size default");
	hide.toggleEye(node);
	check(tab.effects[0].hideUnused === false, "eye persists show-all in IR");
	check(inputNames(node).indexOf("a1") >= 0, "eye shows every socket");
	hide.toggleEye(node);
	check(tab.effects[0].hideUnused === true, "eye persists hide-unused in IR");
	check(inputNames(node).indexOf("a1") < 0, "eye hides the unused again");
	check(hide.resolve(tab, node) === true, "resolve honours the persisted toggle");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeNode();
	fakeTab(node);
	check(hide.eyeAt(node, [2, 3]) === true, "eye hit on the first row");
	check(hide.eyeAt(node, [node.size[0] - 2, 17]) === true, "eye hit at the right edge");
	check(hide.eyeAt(node, [120, 23]) === false, "second row misses the eye");
	check(hide.eyeAt(node, [120, -4]) === false, "title bar misses the eye");
})();

/* Typed ports (the VFXEditor.ports seam): one table, metadata-derived paint. */

(function () {
	var ports = global.VFXEditor.ports;
	check(!!ports, "ports seam exposed");
	check(JSON.stringify(Object.keys(ports.types).sort()) === JSON.stringify(["bind", "expr",
			"field", "flow", "macro", "mask", "number", "time"]), "eight fixed port types");
	Object.keys(ports.types).forEach(function (key) {
		var entry = ports.types[key];
		check(typeof entry.color === "string" && /^#[0-9a-f]{6}$/.test(entry.color),
				"port " + key + " has a hex color");
		check(typeof entry.shape === "string" && /^[A-Z_]*$/.test(entry.shape),
				"port " + key + " names exactly one shape");
		check(entry.shape.indexOf("BOX") < 0 && entry.shape.indexOf("DIAMOND") < 0,
				"port " + key + " is never a rectangle or a diamond");
	});
	check(ports.types.flow.shape === ""
			&& ports.types.field.shape === "ARROW_SHAPE"
			&& ports.types.mask.shape === "GRID_SHAPE",
			"chain is a circle, field a triangle, mask the dotted square");
	check(ports.types.number.shape === "" && ports.types.macro.shape === ""
			&& ports.types.bind.shape === "" && ports.types.expr.shape === ""
			&& ports.types.time.shape === "", "numeric drivers stay circles");
	check(ports.flow === "flow" && ports.mask === "mask" && ports.field === "field"
			&& ports.wildcard === "*", "wire constants exposed");
})();

(function () {
	var ports = global.VFXEditor.ports;
	["FLOAT", "INT", "BOOL", "ENUM", "COLOR_RGB", "POSITION", "float", undefined, null]
			.forEach(function (kind) {
		check(ports.wireForParam({ kind: kind }) === "number",
				"kind " + String(kind) + " wires number");
	});
	check(ports.wireForParam({ kind: "FLOAT", fieldCapable: true }) === "number,field",
			"a fieldCapable param also accepts field");
	check(ports.wireForParam({ kind: "FLOAT", fieldCapable: false }) === "number",
			"a gated-off param stays number");
})();

(function () {
	var ports = global.VFXEditor.ports;
	var kinds = global.VFXEditor.valueKinds;
	var seen = {};
	Object.keys(kinds).forEach(function (kind) {
		var port = ports.visualForKind(kind, kinds[kind]);
		check(typeof port === "string" && ports.types[port], "kind " + kind + " maps to one port");
		seen[port] = true;
	});
	check(seen.bind && seen.expr && seen.time && seen.macro && seen.number,
			"every driver family is painted");
	check(ports.visualForKind("bind", kinds.bind) === "bind", "bind paints bind");
	check(ports.visualForKind("expr", kinds.expr) === "expr", "expr paints expr");
	check(ports.visualForKind("time", kinds.time) === "time", "time paints time");
	check(ports.visualForKind("curve", kinds.curve) === "time", "curve paints time");
	check(ports.visualForKind("subgraph", kinds.subgraph) === "macro", "instance paints macro");
	check(ports.visualForKind("constant", kinds.constant) === "number", "constant paints number");
	check(ports.visualForKind("math", kinds.math) === "number", "math paints number");
})();

(function () {
	var ports = global.VFXEditor.ports;
	var slot = { name: "x", type: "number", link: null };
	ports.paint(slot, "field");
	check(slot.color_on === ports.types.field.color
			&& slot.color_off === ports.types.field.color && slot.__port === "field",
			"paint stamps color + port");
	ports.paint(slot, "bogus");
	check(slot.__port === "number" && slot.color_on === ports.types.number.color,
			"paint falls back to number");
	check(ports.shape("flow") === 0, "no LiteGraph shapes headlessly (guarded)");
	check(ports.format({ flow: 2, number: 3 })
			=== "flow=2 number=3 mask=0 field=0 macro=0 bind=0 expr=0 time=0",
			"formatPorts renders table order with zeros");
})();

(function () {
	var ports = global.VFXEditor.ports;
	var node = makeNode();
	var byName = {};
	node.inputs.forEach(function (slot) {
		byName[slot.name] = slot;
	});
	check(byName["in"].type === ports.flow && byName["in"].__port === "flow",
			"chain in is a flow port");
	check(byName.anchor_yaw.type === "number" && byName.anchor_yaw.__port === "number",
			"params wire number");
	check(byName.mask.type === ports.mask && byName.mask.__port === "mask",
			"mask attachment is a mask port");
	check(node.outputs[0].type === ports.flow && node.outputs[0].__port === "flow",
			"chain out is a flow port");
	check(byName.__eye.__port === undefined
			&& byName["__group:Anchor"].__port === undefined,
			"eye + headers are not ports");
})();

(function () {
	var desc = { params: [{ name: "intensity", kind: "FLOAT", group: "" },
			{ name: "radius", kind: "FLOAT", group: "", fieldCapable: true }] };
	global.VFXEditor.schema.gated_fx = desc;
	var Ctor = groups.make("gated_fx", desc);
	var node = Object.create(FakeNode.prototype);
	FakeNode.call(node);
	Ctor.call(node);
	node.id = 77;
	node.graph = fakeGraph();
	function slot(name) {
		for (var i = 0; i < node.inputs.length; i++) {
			if (node.inputs[i].name === name) {
				return node.inputs[i];
			}
		}
		return null;
	}
	check(slot("intensity").type === "number", "plain param wires number");
	check(slot("radius").type === "number,field", "gated param wires number,field");
	check(slot("radius").__port === "number", "gated param still paints number");
})();

/* The vendored drag/drop gate, executed for real (extracted from litegraph.js). */
(function () {
	var source = fs.readFileSync(path.join(editorDir, "litegraph.js"), "utf8");
	var anchor = "isValidConnection: function(type_a, type_b) {";
	var begin = source.indexOf(anchor);
	check(begin >= 0, "isValidConnection found in the bundle");
	var cleaned = source.substring(begin).replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\/\/[^\n]*/g,
			function (match) {
		return new Array(match.length + 1).join(" ");
	});
	var depth = 0;
	var end = -1;
	for (var i = 0; i < cleaned.length; i++) {
		if (cleaned[i] === "{") {
			depth++;
		} else if (cleaned[i] === "}") {
			depth--;
			if (depth === 0) {
				end = i + 1;
				break;
			}
		}
	}
	check(end > 0, "isValidConnection body extracted");
	var factory = new Function("return (function(type_a, type_b) "
			+ source.substring(begin + anchor.length - 1, begin + end) + ");");
	var gate = { isValidConnection: factory() };
	global.__vfxGate = gate;
	function valid(a, b) {
		return gate.isValidConnection(a, b) === true;
	}
	check(valid("*", "flow"), "wildcard lands on flow");
	check(valid("*", "number"), "wildcard lands on number");
	check(valid("*", "number,field"), "wildcard lands on a gated param");
	check(valid("*", "field"), "wildcard lands on field");
	check(valid("*", "mask"), "wildcard lands on mask");
	check(valid("*", "*"), "wildcard lands on wildcard (reroute chain)");
	check(valid("number", "*"), "number lands on a reroute input");
	check(valid("flow", "flow"), "chain flow connects");
	check(valid("mask", "mask"), "mask attach connects");
	check(valid("field", "field"), "field tree links");
	check(valid("field", "number,field"), "field root lands on a gated param");
	check(!valid("flow", "number"), "flow never lands on a param");
	check(!valid("flow", "mask"), "flow never lands on mask");
	check(!valid("field", "number"), "field never lands on an ungated param");
	check(!valid("mask", "number"), "mask never lands on a param");
	check(!valid("mask", "flow"), "mask never lands on the chain");
})();

(function () {
	function build(withView) {
		var tab = new VFXIR.Tab("pulse", "sky_pattern");
		tab.effect().anchor = "moon";
		tab.subgraphs = [{ id: "pulse", inputs: { speed: 1 }, nodes: [{ id: "n",
				kind: "constant", inputs: { value: 1 } }], edges: [], outputs: { radius: "m" } }];
		tab.valueNodes = [{ id: "i", kind: "subgraph", subgraph: "pulse", inputs: { speed: 2 } }];
		tab.edges = [{ from: "i", to: "anchor_yaw", output: "radius" }];
		tab.effect().fields = { tile_scale: { field: "constant", value: 0.5 } };
		if (withView) {
			tab.effect().collapsedGroups = { Anchor: true };
			tab.effect().hideUnused = true;
			tab.effect().shownSockets = { anchor_yaw: true };
		}
		return tab;
	}
	var plain = compileTab(build(false));
	check(compileTab(build(true)) === plain, "macro + field view state never reaches the emit");
})();

/* Reroute dots (the VFXEditor.reroute seam): purely visual wire points. */

var reroute = global.VFXEditor.reroute;

FakeNode.prototype.disconnectInput = function (slot) {
	var sock = this.inputs[slot];
	if (sock && sock.link != null && this.graph) {
		this.graph.removeLink(sock.link);
	}
};
FakeNode.prototype.connect = function (slot, target, targetSlot) {
	if (!this.graph || !this.outputs[slot] || !target || !target.inputs
			|| targetSlot == null || targetSlot >= target.inputs.length) {
		return null;
	}
	// The vendored gate, for real (stashed above): a refused half fails the whole
	// insert/heal, exactly like LGraphNode.connect in litegraph.js.
	var gate = global.__vfxGate;
	if (gate && gate.isValidConnection(this.outputs[slot].type,
			target.inputs[targetSlot].type) !== true) {
		return null;
	}
	if (target.inputs[targetSlot].link != null) {
		target.disconnectInput(targetSlot);
	}
	return this.graph.addLink(this.id, slot, target.id, targetSlot);
};
FakeNode.prototype.getConnectionPos = function (isInput, slot) {
	if (isInput) {
		return [this.pos[0], this.pos[1] + 20 + slot * 20];
	}
	return [this.pos[0] + 240, this.pos[1] + 20 + slot * 20];
};

function fakeLinkGraph() {
	var graph = fakeGraph();
	graph._nodes = [];
	graph._lastLink = 0;
	graph.add = function (node) {
		node.graph = graph;
		graph._nodes.push(node);
	};
	graph.remove = function (node) {
		Object.keys(graph.links).forEach(function (id) {
			var link = graph.links[id];
			if (link.origin_id === node.id || link.target_id === node.id) {
				graph.removeLink(id);
			}
		});
		graph._nodes = graph._nodes.filter(function (n) { return n !== node; });
	};
	graph.removeLink = function (id) {
		var link = graph.links[id];
		if (!link) {
			return;
		}
		delete graph.links[id];
		var target = graph.getNodeById(link.target_id);
		if (target && target.inputs[link.target_slot]
				&& target.inputs[link.target_slot].link === id) {
			target.inputs[link.target_slot].link = null;
		}
		var origin = graph.getNodeById(link.origin_id);
		if (origin && origin.outputs[link.origin_slot]) {
			var at = origin.outputs[link.origin_slot].links.indexOf(id);
			if (at >= 0) {
				origin.outputs[link.origin_slot].links.splice(at, 1);
			}
		}
	};
	graph.addLink = function (originId, originSlot, targetId, targetSlot) {
		var id = "L" + (++graph._lastLink);
		graph.links[id] = { origin_id: originId, origin_slot: originSlot,
			target_id: targetId, target_slot: targetSlot };
		graph.getNodeById(targetId).inputs[targetSlot].link = id;
		graph.getNodeById(originId).outputs[originSlot].links.push(id);
		return graph.links[id];
	};
	graph.getNodeById = function (id) {
		for (var i = 0; i < graph._nodes.length; i++) {
			if (graph._nodes[i].id === id) {
				return graph._nodes[i];
			}
		}
		return null;
	};
	return graph;
}

function fakeValueNode(id, irId, kind, inputNames, pos) {
	var node = Object.create(FakeNode.prototype);
	FakeNode.call(node);
	node.id = id;
	node.properties = { kind: kind, irId: irId };
	inputNames.forEach(function (name) {
		node.addInput(name, "number");
	});
	node.addOutput("out", "*");
	node.pos = pos || [0, 0];
	return node;
}

function fakeEffectNode(id, effectId, paramNames) {
	var node = Object.create(FakeNode.prototype);
	FakeNode.call(node);
	node.id = id;
	node.properties = { effectType: "sky_pattern", effectId: effectId };
	paramNames.forEach(function (name) {
		node.addInput(name, "number");
	});
	node.addOutput("out", "flow");
	node.pos = [400, 0];
	return node;
}

function fakeReroute(id, pos) {
	var Ctor = reroute.make();
	var node = Object.create(FakeNode.prototype);
	FakeNode.call(node);
	Ctor.call(node);
	node.id = id;
	node.pos = pos || [200, 100];
	return node;
}

function fakeEditTab(graph) {
	return { graph: graph, valueNodes: [], edges: [], subgraphs: [], effects: [] };
}

var nextDotId = 900;
global.LiteGraph.createNode = function (type) {
	if (type !== "vfx/reroute") {
		return null;
	}
	return fakeReroute(nextDotId++);
};
global.LiteGraph.registered_node_types["vfx/reroute"] = true;

(function () {
	check(!!reroute, "reroute seam exposed");
	var dot = fakeReroute(50);
	check(reroute.isReroute(dot), "predicate matches the dot");
	check(!reroute.isReroute(fakeValueNode(51, "v", "constant", ["value"])),
			"predicate misses value nodes");
	check(dot.inputs.length === 1 && dot.inputs[0].type === "*"
			&& dot.outputs.length === 1 && dot.outputs[0].type === "*",
			"one wildcard in + one wildcard out");
	check(!dot.properties.kind && !dot.properties.irId,
			"no kind/irId (kind readers ignore dots)");
})();

(function () {
	function wiredTab() {
		var graph = fakeLinkGraph();
		var a = fakeValueNode(1, "a", "constant", ["value"], [0, 0]);
		var b = fakeValueNode(2, "b", "math", ["a", "b"], [400, 0]);
		var fx = fakeEffectNode(3, "fx_1", ["radius"]);
		graph.add(a);
		graph.add(b);
		graph.add(fx);
		return { graph: graph, a: a, b: b, fx: fx };
	}
	var dotted = wiredTab();
	var r1 = fakeReroute(4, [200, 50]);
	var r2 = fakeReroute(5, [300, 50]);
	dotted.graph.add(r1);
	dotted.graph.add(r2);
	dotted.graph.addLink(1, 0, 4, 0);
	dotted.graph.addLink(4, 0, 5, 0);
	dotted.graph.addLink(5, 0, 2, 0);
	dotted.graph.addLink(2, 0, 3, 0);
	var irByGraphId = { 1: "a", 2: "b" };
	var collected = reroute.collect({ graph: dotted.graph }, irByGraphId);
	check(collected.edges.length === 2, "two logical edges through two dots");
	check(collected.wires.filter(function (wire) { return wire.kind === "skip"; }).length === 2,
			"dot-internal links classify as skip");
	var direct = wiredTab();
	direct.graph.addLink(1, 0, 2, 0);
	direct.graph.addLink(2, 0, 3, 0);
	var plain = reroute.collect({ graph: direct.graph }, irByGraphId);
	check(JSON.stringify(collected.edges) === JSON.stringify(plain.edges),
			"dots are transparent to edge collection");
})();

(function () {
	var graph = fakeLinkGraph();
	var a = fakeValueNode(11, "x", "constant", ["value"], [0, 0]);
	var b = fakeValueNode(12, "y", "math", ["a", "b"], [400, 0]);
	graph.add(a);
	graph.add(b);
	graph.addLink(11, 0, 12, 0);
	var tab = fakeEditTab(graph);
	var before = reroute.collect(tab, { 11: "x", 12: "y" });
	var dot = reroute.insert(tab, "L1", [200, 60]);
	check(!!dot && graph._nodes.length === 3, "insert drops a dot on the wire");
	var after = reroute.collect(tab, { 11: "x", 12: "y" });
	check(JSON.stringify(after.edges) === JSON.stringify(before.edges),
			"insert keeps the logical edge");
	reroute.heal(tab, dot);
	graph.remove(dot);
	var healed = reroute.collect(tab, { 11: "x", 12: "y" });
	check(JSON.stringify(healed.edges) === JSON.stringify(before.edges)
			&& graph._nodes.length === 2, "delete heals the wire");
})();

(function () {
	var graph = fakeLinkGraph();
	var a = fakeValueNode(21, "x", "constant", ["value"], [0, 0]);
	var b = fakeValueNode(22, "y", "math", ["a", "b"], [400, 0]);
	var r1 = fakeReroute(23, [180, 47]);
	var r2 = fakeReroute(24, [280, 47]);
	[a, b, r1, r2].forEach(function (n) { graph.add(n); });
	graph.addLink(21, 0, 23, 0);
	graph.addLink(23, 0, 24, 0);
	graph.addLink(24, 0, 22, 0);
	var tab = { graph: graph, effects: [{ id: "fx_1" }],
		edges: [{ from: "x", to: "y", input: "a" }] };
	reroute.record(tab);
	check(tab.__reroutes && tab.__reroutes.length === 2,
			"two dots recorded, got " + (tab.__reroutes ? tab.__reroutes.length : 0));
	check(tab.__reroutes[0].x === 180 && tab.__reroutes[0].y === 47,
			"positions persist rounded");
	// Rebuild like materializeGraph: fresh nodes, one direct wire, then restore.
	var graph2 = fakeLinkGraph();
	var a2 = fakeValueNode(31, "x", "constant", ["value"], [0, 0]);
	var b2 = fakeValueNode(32, "y", "math", ["a", "b"], [400, 0]);
	graph2.add(a2);
	graph2.add(b2);
	graph2.addLink(31, 0, 32, 0);
	var tab2 = { graph: graph2, effects: [{ id: "fx_1" }],
		edges: [{ from: "x", to: "y", input: "a" }], __reroutes: tab.__reroutes };
	reroute.restore(tab2, { x: a2, y: b2 }, {});
	var dots2 = graph2._nodes.filter(reroute.isReroute);
	check(dots2.length === 2, "rebuild keeps both dots, got " + dots2.length);
	var back = reroute.collect(tab2, { 31: "x", 32: "y" });
	check(JSON.stringify(back.edges) === JSON.stringify(tab2.edges),
			"restored chain reads the same edge");
})();

(function () {
	function build() {
		var tab = new VFXIR.Tab("dots", "sky_pattern");
		tab.valueNodes = [
			{ id: "x", kind: "constant", inputs: { value: 2 } },
			{ id: "y", kind: "math", op: "add", inputs: { a: 0, b: 0 } }
		];
		tab.edges = [{ from: "x", to: "y", input: "a" }];
		return tab;
	}
	var key = VFXIR.edgeKey({ from: "x", to: "y", input: "a" });
	var bare = compileTab(build());
	var dotted = build();
	dotted.__reroutes = [{ x: 180, y: 47, edge: key }, { x: 280, y: 47, edge: key }];
	check(compileTab(dotted) === bare, "a wire through 2 reroutes emits identical JSON");
	check(bare.indexOf("reroute") < 0 && bare.indexOf("__reroutes") < 0,
			"no dot state leaks into the emit");
})();

console.log("");
console.log(failures === 0 ? "all group tests passed" : failures + " FAILURES");
process.exit(failures ? 1 : 0);

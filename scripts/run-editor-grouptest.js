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
	check(node.size[1] === 30 + 5 * 20 + 16, "footer reserves one line while hiding");
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

console.log("");
console.log(failures === 0 ? "all group tests passed" : failures + " FAILURES");
process.exit(failures ? 1 : 0);

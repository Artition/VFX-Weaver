/*
 * Headless test for effect-node socket groups (app.js).
 *
 * app.js is DOM-bound at load (getElementById) but boots on DOMContentLoaded, so with small
 * window/document/LiteGraph stubs it loads headlessly without booting, and the VFXEditor.groups
 * seam (make/sections/rebuild/toggle/headerAt/isHeader/wiredParams) drives the real canvas
 * logic: group partition, header layout, collapse/expand with wire preservation, and the IR
 * persist field. The VFXEditor.hide seam (resolve/reveal/hiddenInfo) drives the
 * hide-unconnected half: the always-false resolve (a carried IR hideUnused is ignored
 * for layout), the link-drag reveal pair and the hidden/animated counts behind diag. ir.js + compiler.js load too (as in run-editor-selftest.js), so the last test
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
	check(JSON.stringify(inputNames(node)) === JSON.stringify(["in", "__group:Anchor",
			"__group:Dome", "__group:Tile", "__group:Color", "intensity", "mask"]),
			"fresh node hides the unconnected (groups start collapsed, ungrouped stays)");
	check(node.outputs.length === 1 && node.outputs[0].name === "out", "flow out port kept");
	check(node.inputs[1].label === "\u25B8 Anchor (2)", "collapsed header shows hidden count");
	check(groups.isHeader(node.inputs[1]) && !groups.isHeader(node.inputs[0])
			&& !groups.isHeader(node.inputs[5]), "only header rows are headers");
	check(inputNames(node).indexOf("__eye") < 0, "no eye row on a fresh node");
})();

(function () {
	var node = makeNode();
	var tab = fakeTab(node);
	check(inputNames(node).indexOf("anchor_yaw") < 0, "unwired members hide by default");
	groups.toggle(node, "Anchor");
	check(JSON.stringify(inputNames(node)) === JSON.stringify(["in", "__group:Anchor",
			"anchor_yaw", "anchor_pitch", "__group:Dome",
			"__group:Tile", "__group:Color", "intensity", "mask"]),
			"expand reveals the group's members");
	check(node.inputs[1].label === "\u25BE Anchor", "expanded header drops the count");
	check(tab.effects[0].collapsedGroups
			&& tab.effects[0].collapsedGroups.Dome === true
			&& tab.effects[0].collapsedGroups.Tile === true
			&& tab.effects[0].collapsedGroups.Color === true
			&& !tab.effects[0].collapsedGroups.Anchor, "expand persists the rest in IR");
	groups.toggle(node, "Anchor");
	check(inputNames(node).indexOf("anchor_yaw") < 0, "collapse hides them again");
	check(!("collapsedGroups" in tab.effects[0]) || tab.effects[0].collapsedGroups.Anchor === true,
			"collapse-all restores the default IR field");
})();

(function () {
	var node = makeNode();
	fakeTab(node);
	groups.rebuild(node, { anchor_pitch: true });
	var names = inputNames(node);
	check(names.indexOf("anchor_pitch") >= 0, "an IR-wired member stays as a stub");
	check(names.indexOf("anchor_yaw") < 0, "its unwired sibling stays hidden");
	var at = names.indexOf("anchor_pitch");
	node.inputs[at].link = "10";
	node.graph.links["10"] = { origin_id: 7, origin_slot: 0, target_id: node.id,
		target_slot: at };
	groups.toggle(node, "Anchor");
	check(inputNames(node).indexOf("anchor_pitch") >= 0, "expand keeps the wired stub");
	check(node.graph.links["10"].target_slot === inputNames(node).indexOf("anchor_pitch"),
			"the surviving wire is repointed, never dropped");
	groups.toggle(node, "Anchor");
	check(node.graph.links["10"] && node.inputs[inputNames(node).indexOf("anchor_pitch")].link === "10",
			"collapse keeps the wire on the same link");
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
	check(groups.headerAt(node, [120, inputNames(node).indexOf("intensity") * 20 + 3]) === null,
			"ungrouped rows miss");
	check(groups.headerAt(node, [120, -4]) === null, "title bar misses");
})();

(function () {
	var node = makeNode();
	var row = inputNames(node).indexOf("__group:Dome");
	check(groups.headerAt(node, [10, row * 20 + 14]) === "Dome",
			"header hit at the socket dot (the wire-drag zone)");
	check(groups.headerAt(node, [2, row * 20 + 3]) === "Dome",
			"header hit at the left edge (whole row, not the glyph)");
	check(groups.headerAt(node, [120, inputNames(node).indexOf("intensity") * 20 + 3]) === null,
			"header misses ungrouped rows");
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

/* Hide-unconnected (the VFXEditor.hide seam): the always-false resolve, diag counts. */

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
	check(hide.resolve(null, null) === false, "resolve is always false (hide-unused ignored)");
	check(hide.toggleEye === undefined && hide.eyeAt === undefined && hide.isEye === undefined,
			"no eye API left on the seam");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	fakeTab(node);
	node.__hideUnused = hide.resolve(node.__tab, node);
	groups.rebuild(node, null);
	check(JSON.stringify(inputNames(node)) === JSON.stringify(["in",
			"__group:Alpha", "__group:Beta", "tail1", "tail2", "mask"]),
			"a big fresh node hides its unconnected (headers + ungrouped + mask)");
	var info = hide.hiddenInfo(node);
	check(info.hidden === 12 && info.animated === 0, "twelve hidden, none animated");
	check(node.size[1] === 30 + node.inputs.length * 20, "no footer line, no counter row");
})();

(function () {
	var node = makeBigNode();
	fakeTab(node);
	groups.rebuild(node, { a3: true });
	var names = inputNames(node);
	check(names.indexOf("a3") >= 0 && names.indexOf("a1") < 0,
			"only the wired stub shows, its siblings stay hidden");
	node.inputs[names.indexOf("a3")].link = "11";
	node.graph.links["11"] = { origin_id: 7, origin_slot: 0, target_id: node.id,
		target_slot: names.indexOf("a3") };
	groups.rebuild(node, null);
	names = inputNames(node);
	check(names.indexOf("a3") >= 0 && names.indexOf("a1") < 0,
			"a live wire keeps its stub across rebuilds");
	check(node.graph.links["11"].target_slot === names.indexOf("a3"),
			"the surviving wire is repointed, never dropped");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	var tab = fakeTab(node);
	tab.effects[0].keys = { b2: [{ time: 0, value: 1 }] };
	var info = hide.hiddenInfo(node);
	check(info.hidden === 12 && info.animated === 1, "hidden animated params are counted");
	groups.toggle(node, "Beta");
	check(inputNames(node).indexOf("b2") >= 0, "expanding a group reveals its members");
	check(hide.hiddenInfo(node).hidden === 7, "only the still-collapsed group counts");
	groups.toggle(node, "Beta");
	check(hide.hiddenInfo(node).hidden === 12, "collapse restores the hidden count");
})();

(function () {
	var hide = global.VFXEditor.hide;
	var node = makeBigNode();
	var tab = fakeTab(node);
	tab.effects[0].hideUnused = true;
	check(hide.resolve(tab, node) === false, "a carried hideUnused is ignored for layout");
	node.__hideUnused = hide.resolve(tab, node);
	groups.rebuild(node, null);
	check(inputNames(node).indexOf("a1") < 0, "grouped sockets hide by default, old or new");
	check(inputNames(node).indexOf("tail1") >= 0, "ungrouped params stay visible");
	check(tab.effects[0].hideUnused === true, "the carried IR field survives untouched");
})();

/* Link-drag reveal (the VFXEditor.hide reveal pair): collapsed groups open for the drag. */

(function () {
	var hide = global.VFXEditor.hide;
	check(typeof hide.beginReveal === "function" && typeof hide.endReveal === "function",
			"reveal pair exposed");
	var project = global.VFXEditor.project;
	var savedTabs = project.tabs;
	var savedActive = project.active;
	var node = makeBigNode();
	var graph = fakeLinkGraph();
	graph.add(node);
	node.__tab = null;
	project.tabs = [{ graph: graph, effects: [], valueNodes: [], edges: [], subgraphs: [] }];
	project.active = 0;
	try {
		check(inputNames(node).indexOf("a1") < 0, "collapsed before the drag");
		hide.beginReveal();
		check(inputNames(node).indexOf("a1") >= 0
				&& inputNames(node).indexOf("b5") >= 0, "every socket reveals for the drag");
		hide.endReveal();
		check(inputNames(node).indexOf("a1") < 0
				&& inputNames(node).indexOf("tail1") >= 0, "the drop restores the hidden layout");
	} finally {
		project.tabs = savedTabs;
		project.active = savedActive;
	}
})();

/* Canvas viewer/solo (the VFXEditor.solo seam): the title-bar badge over panel state. */

(function () {
	var solo = global.VFXEditor.solo;
	check(!!solo, "solo seam exposed");
	function soloNode(tab) {
		return { properties: { effectType: "big_effect", effectId: "fx_1" },
			size: [240, 100], flags: {}, __tab: tab, setDirtyCanvas: function () {} };
	}
	var tab = { effects: [{ id: "fx_1", solo: false }] };
	var node = soloNode(tab);
	check(solo.at(node, [236 - 14, -15]) === true, "badge hit at the title-bar right");
	check(solo.at(node, [10, -15]) === false, "title-bar left misses");
	check(solo.at(node, [236 - 14, 30]) === false, "body misses");
	node.flags.collapsed = true;
	check(solo.at(node, [236 - 14, -15]) === false, "collapsed nodes miss");
	node.flags.collapsed = false;
	try {
		solo.toggle(node);
	} catch (e) {
		// the flip precedes the shell refresh; headless DOM may not follow
	}
	check(tab.effects[0].solo === true, "one click solos the effect");
	try {
		solo.toggle(node);
	} catch (e2) {
		// same as above
	}
	check(tab.effects[0].solo === false, "a second click unsolos it");
})();

/* Named canvas frames (the VFXEditor.frames seam): record/restore/rename, never the emit. */

(function () {
	var frames = global.VFXEditor.frames;
	check(!!frames, "frames seam exposed");
	function frameTab(groups) {
		return { graph: { _groups: groups || [] } };
	}
	var tab = frameTab([{ title: "blast", _bounding: [10, 20, 300, 200] }]);
	frames.record(tab);
	check(JSON.stringify(tab.__frameGroups) === JSON.stringify(
			[{ title: "blast", x: 10, y: 20, w: 300, h: 200 }]),
			"record snapshots title + box rounded");
	check(frames.titleAt(tab, [15, 25]) === 0, "title-strip hit");
	check(frames.titleAt(tab, [15, 60]) === -1, "frame body misses");
	check(frames.titleAt(tab, [500, 25]) === -1, "outside misses");
	check(frames.titleAt(frameTab([]), [15, 25]) === -1, "no frames miss");
})();

(function () {
	var frames = global.VFXEditor.frames;
	var tab = { graph: { _groups: [{ title: "blast", pos: [0, 0], size: [140, 80] }] } };
	check(frames.rename(tab, 3, "boom") === false, "rename out of range fails");
	check(frames.rename(tab, 0, "   ") === false, "an empty title keeps the old one");
	check(tab.graph._groups[0].title === "blast", "a refused rename changes nothing");
	try {
		check(frames.rename(tab, 0, "  shockwave  ") === true, "rename trims + applies");
	} catch (e) {
		// the title precedes the history commit; headless DOM may not follow
	}
	check(tab.graph._groups[0].title === "shockwave", "the live group retitles");
	check(tab.__frameGroups && tab.__frameGroups[0].title === "shockwave",
			"the record follows the rename");
})();

(function () {
	var frames = global.VFXEditor.frames;
	global.LiteGraph.LGraphGroup = function () {
		this.title = "Group";
		this.pos = [0, 0];
		this.size = [140, 80];
	};
	var tab = { graph: { _groups: [],
		add: function (node) { node.graph = this; this._groups.push(node); },
		remove: function (node) {
			this._groups = this._groups.filter(function (n) { return n !== node; });
		} } };
	tab.__frameGroups = [{ title: "halo", x: 40, y: 50, w: 200, h: 120 }];
	frames.restore(tab);
	check(tab.graph._groups.length === 1, "restore rebuilds the frames");
	check(tab.graph._groups[0].title === "halo", "the title survives the round trip");
	check(tab.graph._groups[0].pos[0] === 40 && tab.graph._groups[0].size[0] === 200,
			"the box survives the round trip");
	frames.restore(tab);
	check(tab.graph._groups.length === 1, "restore is idempotent");
	delete global.LiteGraph.LGraphGroup;
})();

(function () {
	var frames = global.VFXEditor.frames;
	function build(withFrames) {
		var tab = new VFXIR.Tab("sky", "sky_pattern");
		if (withFrames) {
			tab.__frameGroups = [{ title: "halo", x: 1, y: 2, w: 3, h: 4 }];
		}
		return tab;
	}
	var plain = compileTab(build(false));
	check(compileTab(build(true)) === plain, "frame view state never reaches the emit");
	check(plain.indexOf("__frameGroups") < 0 && plain.indexOf("halo") < 0,
			"no frame state leaks into the datapack");
	var moved = global.VFXEditor.exportModel.migrateSidecar({ version: 2,
		tabs: [{ name: "sky", frameGroups: [{ title: "halo", x: 1, y: 2, w: 3, h: 4 }] }] });
	check(moved && moved.tabs[0].frameGroups[0].title === "halo",
			"the sidecar carries frames through untouched");
})();

(function () {
	var project = global.VFXEditor.project;
	var savedTabs = project.tabs;
	var savedNs = project.namespace;
	project.tabs = [];
	project.namespace = "demo";
	try {
		var lonely = new VFXIR.Tab("demo:lonely", null);
		lonely.__frameGroups = [{ title: "halo", x: 1, y: 2, w: 3, h: 4 }];
		project.tabs.push(lonely);
		var sidecar = global.VFXEditor.exportModel.buildSidecar();
		check(sidecar.tabs.length === 1 && sidecar.tabs[0].name === "lonely",
				"an effect-less tab keeps its sidecar entry for its frames");
		check(JSON.stringify(sidecar.tabs[0].frameGroups)
				=== JSON.stringify(lonely.__frameGroups),
				"its frames ride the sidecar without effects");
	} finally {
		project.tabs = savedTabs;
		project.namespace = savedNs;
	}
})();

(function () {
	var project = global.VFXEditor.project;
	var savedTabs = project.tabs;
	var savedNs = project.namespace;
	project.tabs = [];
	project.namespace = "demo";
	try {
		var tab = new VFXIR.Tab("sky", "sky_pattern");
		tab.effect().collapsedGroups = { Anchor: true };
		tab.effect().mute = true;
		project.tabs.push(tab);
		var sidecar = global.VFXEditor.exportModel.buildSidecar();
		check(sidecar.tabs.length === 1 && sidecar.tabs[0].effects.length === 1
				&& sidecar.tabs[0].effects[0].collapsedGroups
				&& sidecar.tabs[0].effects[0].collapsedGroups.Anchor === true
				&& sidecar.tabs[0].effects[0].mute === true,
				"collapsedGroups rides the sidecar beside mute/solo");
		check(global.VFXCompiler.compileTab(tab).indexOf("collapsedGroups") < 0,
				"collapsedGroups never reaches the emit");
		var fresh = new VFXIR.Tab("sky", "sky_pattern");
		global.VFXEditor.exportModel.applySidecar(sidecar, [fresh]);
		check(fresh.effect().collapsedGroups
				&& fresh.effect().collapsedGroups.Anchor === true,
				"collapsedGroups round-trips through the sidecar");
	} finally {
		project.tabs = savedTabs;
		project.namespace = savedNs;
	}
})();

(function () {
	var afterRemove = global.VFXEditor.maskWire.afterRemove;
	check(JSON.stringify(afterRemove([2, 3], 0, 2)) === JSON.stringify([1, 2]),
			"a removed leaf with no operand still shifts higher wires down");
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
			&& ports.types.mask.shape === "GRID_SHAPE"
			&& ports.types.bind.glyph === "B"
			&& ports.types.expr.glyph === "E",
			"chain is a circle, field a triangle, mask the dotted square,"
					+ " bind/expr carry in-dot glyphs");
	check(ports.types.number.shape === "" && ports.types.macro.shape === ""
			&& ports.types.time.shape === "", "number, macro and time stay circles");
	check(ports.types.bind.shape === "" && ports.types.expr.shape === ""
			&& ports.types.bind.glyph === "B" && ports.types.expr.glyph === "E",
			"bind and expr stay circles with their own in-dot glyphs");
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
	ports.paint(slot, "bind");
	check(slot.__glyph === "B" && slot.color_on === ports.types.bind.color,
			"paint stamps the in-dot glyph");
	ports.paint(slot, "expr");
	check(slot.__glyph === "E", "paint stamps the expr glyph");
	ports.paint(slot, "number");
	check(slot.__glyph == null, "paint clears the glyph off plain ports");
	check(ports.shape("flow") === 0, "no LiteGraph shapes headlessly (guarded)");
	check(ports.format({ flow: 2, number: 3 })
			=== "flow=2 number=3 mask=0 field=0 macro=0 bind=0 expr=0 time=0",
			"formatPorts renders table order with zeros");
})();

(function () {
	var ports = global.VFXEditor.ports;
	var node = makeNode();
	["Anchor", "Dome", "Tile", "Color"].forEach(function (group) {
		groups.toggle(node, group);
	});
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
	check(byName["__group:Anchor"].__port === undefined,
			"headers are not ports");
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
	check(slot("radius").__port === "field"
			&& slot("radius").color_on === global.VFXEditor.ports.types.field.color,
			"gated param paints field");
	check(slot("intensity").__port === "number", "plain param still paints number");
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

/* Mask operand wires (the VFXEditor.maskWire seam): leaves feed operands, only. */

(function () {
	var maskWire = global.VFXEditor.maskWire;
	check(!!maskWire, "maskWire seam exposed");
	function maskNode(id, role, effectId, inputs, outputs) {
		return { id: id, properties: { maskRole: role, effectId: effectId },
			inputs: inputs, outputs: outputs, pos: [0, 0] };
	}
	function inSlot(name) {
		return { name: name, type: "mask", link: null };
	}
	function outSlot(name) {
		return { name: name, type: "mask", links: [] };
	}
	function build() {
		var graph = fakeLinkGraph();
		var root = maskNode(31, "mask", "fx_1",
				[inSlot("operand 0"), inSlot("operand 1")], [outSlot("mask")]);
		var leafA = maskNode(32, "leaf", "fx_1",
				[{ name: "mask.p0.p0", type: "number", link: null }], [outSlot("mask")]);
		leafA.properties.leafIndex = 0;
		var leafB = maskNode(33, "leaf", "fx_1", [], [outSlot("mask")]);
		leafB.properties.leafIndex = 1;
		var effect = { id: 34, properties: { effectType: "blur", effectId: "fx_1" },
			inputs: [{ name: "in", type: "flow", link: null },
				{ name: "mask", type: "mask", link: null }],
			outputs: [], pos: [0, 0] };
		var value = fakeValueNode(35, "v", "constant", ["value"]);
		[root, leafA, leafB, effect, value].forEach(function (node) { graph.add(node); });
		return { graph: graph, root: root, leafA: leafA, leafB: leafB,
			effect: effect, value: value };
	}
	function rule(graph, origin, originSlot, target, targetSlot) {
		return maskWire.rule(graph, origin, originSlot, target, targetSlot);
	}
	var setup = build();
	check(rule(setup.graph, setup.leafA, 0, setup.root, 0)
			&& rule(setup.graph, setup.leafA, 0, setup.root, 0).ok === true,
			"a leaf feeds its own operand");
	var direct = rule(setup.graph, setup.leafA, 0, setup.effect, 1);
	check(!!direct && direct.ok === false && /Mask root/.test(direct.hint),
			"a leaf never lands on the effect mask input");
	var occupied = build();
	occupied.root.inputs[1].link = "L9";
	var fanIn = rule(occupied.graph, occupied.leafB, 0, occupied.root, 1);
	check(!!fanIn && fanIn.ok === false && /already has a wire/.test(fanIn.hint),
			"a second link into an operand is refused");
	var fanned = build();
	fanned.leafA.outputs[0].links = ["L1"];
	var fanOut = rule(fanned.graph, fanned.leafA, 0, fanned.root, 0);
	check(!!fanOut && fanOut.ok === false && /one wire per leaf/.test(fanOut.hint),
			"a leaf feeding two operands is refused");
	var foreign = rule(setup.graph, setup.value, 0, setup.root, 0);
	check(!!foreign && foreign.ok === false, "a value node never lands on an operand");
	check(rule(setup.graph, setup.root, 0, setup.effect, 1) === null,
			"the root attach wire is not a maskWire verdict");
	check(rule(setup.graph, setup.value, 0, setup.leafA, 0) === null,
			"value-node field wires bypass the operand rule");
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

/* Export model v2 (the VFXEditor.exportModel seam): per-tab namespaces, grouped files,
 * the payload shape with verbatim curve/particle blocks, and the sidecar migration. */

var exportModel = global.VFXEditor.exportModel;

(function () {
	check(!!exportModel, "exportModel seam exposed");
	check(exportModel.sidecarVersion === 2, "sidecar version is 2");
	check(exportModel.tabNamespace({ name: "other_ns:blur" }) === "other_ns",
			"a valid tab prefix wins over the project namespace");
	check(exportModel.tabNamespace({ name: "blur" }) === exportModel.defaultNamespace(),
			"a bare tab name falls back to the project namespace");
	check(exportModel.tabNamespace({ name: "Bad Name:x" }) === exportModel.defaultNamespace(),
			"an invalid tab prefix falls back to the project namespace");
	check(exportModel.tabNamespace(null) === exportModel.defaultNamespace(),
			"a missing tab falls back to the project namespace");
})();

(function () {
	var project = global.VFXEditor.project;
	var savedTabs = project.tabs;
	var savedNs = project.namespace;
	var savedCurves = project.curves;
	var savedParticles = project.particles;
	project.tabs = [];
	project.namespace = "demo";
	project.curves = {};
	project.particles = {};
	try {
		project.tabs.push(new VFXIR.Tab("demo:one", "blur"));
		project.tabs.push(new VFXIR.Tab("extra:two", "blur"));
		var grouped = exportModel.group();
		check(JSON.stringify(grouped.order) === JSON.stringify(["demo", "extra"]),
				"tabs group under their own namespaces in first-use order");
		check(grouped.namespaces.demo && grouped.namespaces.demo.one != null
				&& grouped.namespaces.extra && grouped.namespaces.extra.two != null,
				"each namespace holds its own files");
		var payload = exportModel.payload();
		check(payload.namespace === "demo", "the project namespace stays primary");
		check(!!payload.namespaces && payload.namespaces.extra.effects.two != null,
				"a multi-namespace project sends the namespaces map");
		check(payload.effects.one != null, "legacy top-level effects carry the primary ns");
		check(exportModel.summary(payload).indexOf("demo (1 effects)") >= 0
				&& exportModel.summary(payload).indexOf("extra (1 effects)") >= 0,
				"the dialog summary lists every namespace");

		project.tabs.pop();
		var single = exportModel.payload();
		check(!("namespaces" in single), "a single namespace sends no namespaces map");
		check(single.effects.one != null, "single-namespace effects stay top-level");
		check(exportModel.summary(single).indexOf("demo (1 effects)") >= 0,
				"the single-namespace summary reads as before");

		exportModel.rememberPresets("demo", { soft: "{\"points\":[]}" },
				{ puff: "{\"kind\":\"spark\"}" });
		exportModel.rememberPresets("demo", { junk: 42 }, null);
		var withPresets = exportModel.payload();
		check(withPresets.curves.soft === "{\"points\":[]}"
				&& withPresets.particles.puff === "{\"kind\":\"spark\"}",
				"verbatim curve/particle blocks ride the payload");
		check(!("junk" in withPresets.curves), "non-string preset blocks are skipped");
		check(!("namespaces" in withPresets), "presets alone do not force multi-namespace");
	} finally {
		project.tabs = savedTabs;
		project.namespace = savedNs;
		project.curves = savedCurves;
		project.particles = savedParticles;
	}
})();

(function () {
	var migrate = exportModel.migrateSidecar;
	check(migrate(null) === null, "a missing sidecar migrates to null");
	check(migrate("nope{") === null, "a corrupt sidecar migrates to null");
	check(migrate(42) === null, "a non-object sidecar migrates to null");
	var v1 = { tabs: [{ name: "fx", effects: [] }] };
	var moved = migrate(v1);
	check(moved && moved.version === 2 && moved.tabs === v1.tabs,
			"v1 (no version) migrates to v2 keeping its tabs");
	check(!("namespace" in moved), "v1 without a namespace gains none");
	var named = migrate({ version: 1, namespace: "my_vfx", tabs: [] });
	check(named && named.version === 2 && named.namespace === "my_vfx",
			"v1 with a namespace keeps it at v2");
	var future = migrate({ version: 99, namespace: "my_vfx", tabs: [{ name: "fx" }],
		crystal: "ball" });
	check(future && future.version === 2 && future.tabs.length === 1
			&& !("crystal" in future),
			"a newer sidecar applies its known fields and drops the rest");
	check(exportModel.buildSidecar().version === exportModel.sidecarVersion,
			"the emitted sidecar carries the current version");
})();

console.log("");
console.log(failures === 0 ? "all group tests passed" : failures + " FAILURES");
process.exit(failures ? 1 : 0);

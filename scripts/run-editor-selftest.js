/*
 * Headless runner for the editor's in-page selftest.
 *
 * Extracts the `window.__vfxSelftest` function from `selftest.html` (between the
 * `SELFTEST:START`/`SELFTEST:END` markers), loads ir.js + compiler.js + inspector.js into this
 * Node process the same way the browser page does, runs every test and exits
 * non-zero on the first failure. No dependencies.
 *
 *   node scripts/run-editor-selftest.js
 */
"use strict";

var fs = require("fs");
var path = require("path");
var vm = require("vm");

var editorDir = path.join(__dirname, "..", "src", "client", "resources", "assets",
		"vfxweaver", "editor");

// ir.js/compiler.js/inspector.js pick `window` first and fall back to globalThis; aliasing
// window to globalThis makes them write VFXIR/VFXCompiler/VFXInspector exactly where the
// selftest reads them. inspector.js is load-safe headlessly (it only touches `document`
// inside render calls, which the DOM-free selftest never makes).
global.window = global;

["ir.js", "compiler.js", "inspector.js"].forEach(function (file) {
	var source = fs.readFileSync(path.join(editorDir, file), "utf8");
	vm.runInThisContext(source, { filename: file });
});

var html = fs.readFileSync(path.join(editorDir, "selftest.html"), "utf8");
var start = html.indexOf("SELFTEST:START");
var end = html.indexOf("SELFTEST:END");
if (start < 0 || end < 0) {
	throw new Error("selftest.html is missing the SELFTEST:START/END markers");
}
var block = html.substring(start, end);
block = block.substring(block.indexOf("*/") + 2);
block = block.substring(0, block.lastIndexOf("/*"));

vm.runInThisContext(block, { filename: "selftest-extracted.js" });

var results = global.window.__vfxSelftest();
var failed = 0;
results.forEach(function (result) {
	if (result.ok) {
		console.log("PASS  " + result.name);
	} else {
		failed++;
		console.log("FAIL  " + result.name + "  — " + result.error);
	}
});

/* P3 export-v2 wire-contract pins (design §13.7). ir.js bodies are unchanged; the bridge
 * accepts three additive blocks beside them (`curves` / `particles` for the primary namespace,
 * `namespaces` for a multi-namespace project). These pins lock the documented shapes and the
 * name rule both sides enforce, without needing the Java bridge. */
(function () {
	var pins = 0;
	function pin(condition, name) {
		pins++;
		if (condition) {
			console.log("PASS  " + name);
		} else {
			failed++;
			console.log("FAIL  " + name);
		}
	}

	// The legacy bodies stay exactly as the bridge always accepted them.
	var zip = global.VFXIR.exportZipBody("vfx_demos", { spark: "{}" }, { version: 2 });
	pin(JSON.stringify(Object.keys(zip).sort()) === JSON.stringify(["effects", "namespace", "sidecar"]),
			"(p3a) exportZipBody legacy keys unchanged");
	pin(!("curves" in zip) && !("particles" in zip) && !("namespaces" in zip),
			"(p3b) no curve/particle/namespace keys unless the editor attaches them");

	// The additive blocks mirror the effects map: name -> verbatim JSON text, and one entry
	// per namespace holding the three maps. A non-empty `namespaces` map is authoritative.
	var multi = global.VFXIR.exportZipBody("demo", { one: "{}" }, null);
	multi.curves = { soft: "{\"points\":[]}" };
	multi.particles = { puff: "{\"kind\":\"spark\"}" };
	multi.namespaces = {
		demo: { effects: { one: "{}" }, curves: { soft: "{\"points\":[]}" }, particles: {} },
		extra: { effects: { two: "{}" }, curves: {}, particles: {} }
	};
	pin(multi.namespaces.demo.effects.one === "{}"
			&& multi.namespaces.extra.effects.two === "{}",
			"(p3c) namespaces carry each namespace's own effects");
	pin(multi.namespaces.demo.curves.soft === "{\"points\":[]}"
			&& multi.curves.soft === "{\"points\":[]}",
			"(p3d) curve blocks ride verbatim beside effects");
	pin(Object.keys(multi.namespaces).length === 2, "(p3e) one entry per namespace");

	// The trust-boundary name rule both sides enforce: [a-z0-9_.-]+, never '.'/'..'/empty.
	// (EditorPack.safeName rejects '.' and '..' explicitly on top of the pattern.)
	function safeNameOk(name) {
		return typeof name === "string" && name !== "" && name !== "." && name !== ".."
				&& /^[a-z0-9_.-]+$/.test(name);
	}
	["vfx_demos", "my-pack.v2", "a", "0_.-"].forEach(function (name) {
		pin(safeNameOk(name), "(p3f) accepted name: " + name);
	});
	["", ".", "..", "../x", "a/b", "a\\b", "UPPER", "with space", "C:\\pack"].forEach(function (name) {
		pin(!safeNameOk(name), "(p3g) rejected name: " + JSON.stringify(name));
	});

	// The /import answer for a multi-namespace pack: legacy primary-namespace fields plus the
	// full per-namespace map, omitted for a single-namespace pack.
	var singleImport = { namespace: "demo", effects: { one: "{}" }, curves: {}, particles: {} };
	pin(!("namespaces" in singleImport), "(p3h) single-namespace import needs no namespaces map");
	console.log("");
	console.log((results.length + pins - failed) + "/" + (results.length + pins) + " passed");
})();
process.exit(failed ? 1 : 0);

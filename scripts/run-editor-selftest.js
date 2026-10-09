/*
 * Headless runner for the editor's in-page selftest.
 *
 * Extracts the `window.__vfxSelftest` function from `selftest.html` (between the
 * `SELFTEST:START`/`SELFTEST:END` markers), loads ir.js + compiler.js into this
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

// ir.js/compiler.js pick `window` first and fall back to globalThis; aliasing window to
// globalThis makes them write VFXIR/VFXCompiler exactly where the selftest reads them.
global.window = global;

["ir.js", "compiler.js"].forEach(function (file) {
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
console.log("");
console.log((results.length - failed) + "/" + results.length + " passed");
process.exit(failed ? 1 : 0);

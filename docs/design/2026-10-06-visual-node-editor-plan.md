# Visual Node Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-mod visual node editor to VFXweaver: a local web UI that compiles node graphs into VFXweaver datapack JSON and live-plays them in the game client.

**Architecture:** A JDK-only HTTP/SSE bridge inside the client serves a vendored LiteGraph web UI from the jar and exposes the game's effect registry through a declarative `VFXEffectSchema`. The web model is the single source of truth; structural edits push a full compiled definition (`/compile`), hot edits push increments (`/set_param`, `/set_keyframe`), all gated by a monotonic `revision`. Export writes a real datapack (world or zip) with a per-node `pack.mcmeta`.

**Tech Stack:** Java 21/25 (JDK `com.sun.net.httpserver`, Gson already present), Minecraft 26.2 / 26.1.2 / 1.21.11 × Fabric/NeoForge via Stonecutter, vendored LiteGraph.js (vanilla, MIT), SSE + POST, PowerShell check scripts.

**Spec:** `docs/design/2026-10-06-visual-node-editor-design.md`

## Global Constraints

- **No new Java dependencies.** Only the JDK (incl. `com.sun.net.httpserver`) and libraries already in the build (Gson etc.).
- **`src/main` never references `src/client`.** Client editor wiring lives in `src/client`; anything only client code may touch the MC/loader types.
- **Never reorder or edit the name lists in `VFXShaderPrograms.registerPost(...)`** — they are the std140 UBO contract. The schema is a parallel source, validated against them.
- **Datapack format stays backward compatible**: additive only, no renamed/removed fields. Editor metadata never goes into the effect JSON except the sanctioned `graph.meta`.
- **No runtime Minecraft-version detection** for export; the `pack.mcmeta` format is a compile-time Stonecutter constant per node.
- **The network protocol is untouched in v1** (`VFXTriggerPayload.PROTOCOL_VERSION` unchanged).
- **Definition of done:** all six nodes build (`:26.2`, `:26.1.2`, `:1.21.11`, and each `-neoforge`). Visual results are confirmed only by running the game.
- **Reproduce first:** before using any loader/MC/JDK API signature below, confirm it with `javap` against the node's real jar (the build does not validate these).
- **Editor static assets** live in `src/client/resources/assets/vfxweaver/editor/`.
- **Commits:** one per task, message style `editor: <what>` (matches the repo's `area: description`).

## Review Focus

Inputs/conditions no task's tests naturally exercise, most likely to bite a user:

1. **Import a pack whose effect type is absent from the schema** (older/newer pack) — editor must show the effect read-only, never crash or drop the tab.
2. **Missing or corrupt sidecar / `graph.meta`** — import must degrade to auto-layout, never fail the import.
3. **Concurrent/out-of-order requests** (a stale increment arriving after a newer compile) — revision gating must ignore the stale edit, not corrupt game state.
4. **Token/origin failure** — every non-static request without the right token is rejected before any state change; no schema/state leaks on 401.
5. **Malformed JSON or empty graph** reaching `/compile` — a structured error is returned and the previously-playing effect is left intact.

---

## File Structure

**Shared (`src/main/java/dev/vfxweaver/editor/`)**
- `VFXParamKind.java` — enum of parameter kinds.
- `VFXParamDescriptor.java` — record: one parameter's schema.
- `VFXEffectDescriptor.java` — record: one effect type's ordered params + structural options.
- `VFXEffectSchema.java` — registry `Map<VFXEffectType, VFXEffectDescriptor>` + JSON writer.
- `VFXPackFormat.java` — per-node compile-time constant + `pack.mcmeta` builder.

**Client (`src/client/java/dev/vfxweaver/client/editor/`)**
- `EditorGame.java` — MC-free interface the bridge drives (apply/play/stop/setParam/setKeyframe/seek/revision).
- `EditorSession.java` — revision state, request gating, JSON validation, error payloads.
- `EditorAssets.java` — classpath static file lookup + content types.
- `EditorSse.java` — SSE client hub (register/remove/broadcast, idle timeout).
- `EditorServer.java` — `HttpServer` lifecycle, token, routing, rate limit.
- `EditorPack.java` — read/write packs (world dir / folder / zip) and sidecar.
- `MinecraftEditorGame.java` — MC-touching impl of `EditorGame` (VFXAPI / effect manager / definition manager).
- (modify) `src/client/java/dev/vfxweaver/client/platform/VFXClientCommands.java` — add `/vfxeditor`.
- (modify) `src/client/java/dev/vfxweaver/client/VFXClient.java` — create/start the editor on demand.

**Client resources (`src/client/resources/assets/vfxweaver/editor/`)**
- `index.html`, `editor.css`, `app.js`
- `litegraph.js`, `litegraph.css` (vendored)
- `ir.js` — web project model + command stack (undo/redo).
- `compiler.js` — IR → VFX datapack JSON.
- `bridge.js` — SSE + POST, revision, reconnect backoff.
- `timeline.js` — keyframe tracks + transport.
- `inspector.js` — schema-driven controls (slider/enum/color/bind/expr).
- `selftest.html` — dev-only compiler/round-trip checks.

**Scripts / CI**
- `scripts/check-vfx-schema.ps1`
- (modify) `.github/workflows/build.yml`

**Docs**
- (modify) `docs/guide/index.md` + a new `docs/guide/editor.md`, `docs/CHANGELOG.md`.

---

### Task 1: Schema records + registry + JSON export

**Files:**
- Create: `src/main/java/dev/vfxweaver/editor/VFXParamKind.java`
- Create: `src/main/java/dev/vfxweaver/editor/VFXParamDescriptor.java`
- Create: `src/main/java/dev/vfxweaver/editor/VFXEffectDescriptor.java`
- Create: `src/main/java/dev/vfxweaver/editor/VFXEffectSchema.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckSchema.java`

**Interfaces:**
- Produces:
  - `enum VFXParamKind { FLOAT, INT, BOOL, ENUM, COLOR_RGB, POSITION }`
  - `record VFXParamDescriptor(String name, VFXParamKind kind, float defaultValue, float min, float max, float step, String group, boolean animatable, boolean bindable, boolean fieldCapable, List<String> enumValues, Float neutral)`
  - `record VFXEffectDescriptor(VFXEffectType type, List<VFXParamDescriptor> params)`
  - `VFXEffectSchema.get(VFXEffectType) -> @Nullable VFXEffectDescriptor`
  - `VFXEffectSchema.register(VFXEffectDescriptor)` (rejects duplicate type)
  - `VFXEffectSchema.toJson() -> String` (Gson; `{ "<type name>": { "params": [ ... ] } }`)

- [ ] **Step 1: Write the failing test** (`CheckSchema.java`)

```java
import dev.vfxweaver.editor.*;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.List;

public class CheckSchema {
	public static void main(String[] args) {
		VFXEffectSchema.register(new VFXEffectDescriptor(VFXEffectType.BLUR, List.of(
			new VFXParamDescriptor("radius", VFXParamKind.FLOAT, 0.0F, 0.0F, 64.0F, 0.5F, "main", true, true, false, List.of(), 0.0F))));
		String json = VFXEffectSchema.toJson();
		assert json.contains("\"blur\"") : json;
		assert json.contains("\"radius\"") : json;
		assert json.contains("\"FLOAT\"") : json;
		boolean dup = false;
		try { VFXEffectSchema.register(new VFXEffectDescriptor(VFXEffectType.BLUR, List.of())); }
		catch (IllegalStateException e) { dup = true; }
		assert dup : "duplicate type must be rejected";
		System.out.println("OK");
	}
}
```

- [ ] **Step 2: Run it and verify it fails**

Run:
```
javac -cp "versions/26.1.2/build/classes/java/main" -d "$env:TEMP\opencode" "$env:TEMP\opencode\CheckSchema.java"
```
Expected: FAIL — `cannot find symbol: class VFXEffectSchema`.

- [ ] **Step 3: Implement the four classes** in `dev.vfxweaver.editor` (public, javadoc on public API; tabs for indentation). `toJson` emits the enum's `getName()` (shader-style lowercase) as key and `kind.name()` as the kind string.

- [ ] **Step 4: Run the test and verify it passes**

Run:
```
javac -cp "versions/26.1.2/build/classes/java/main" -d "$env:TEMP\opencode" "$env:TEMP\opencode\CheckSchema.java"
java -ea -cp "versions/26.1.2/build/classes/java/main;$env:TEMP\opencode" CheckSchema
```
Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/vfxweaver/editor
git commit -m "editor: add effect schema records and registry"
```

---

### Task 2: Post-effect descriptors

**Files:**
- Modify: `src/main/java/dev/vfxweaver/editor/VFXEffectSchema.java`
- Create: `src/main/java/dev/vfxweaver/editor/PostParamSources.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckPostSchema.java`

**Interfaces:**
- Consumes: Task 1 records.
- Produces: `VFXEffectSchema.registerBuiltIns()` — declares a descriptor for every `isPostProcessing()` type (excluding `COLLECTION`), with param names **in the same order and set** as `VFXShaderPrograms.registerPost*` for that type. `defaultValue` is the parameter's neutral `Float` when finite, else `0`; `neutral` from `VFXEffectType.neutralValue(name)` (or `null` when `NaN`); `fieldCapable` from `VFXEffectType.fieldCapableInputs()`. The built-in JSON files are **not** read at runtime.

- [ ] **Step 1: Write the failing test** (`CheckPostSchema.java`)

```java
import dev.vfxweaver.editor.*;
import dev.vfxweaver.effect.VFXEffectType;

public class CheckPostSchema {
	public static void main(String[] args) {
		VFXEffectSchema.registerBuiltIns();
		for (VFXEffectType t : VFXEffectType.values()) {
			if (!t.isPostProcessing() || t == VFXEffectType.COLLECTION) continue;
			VFXEffectDescriptor d = VFXEffectSchema.get(t);
			assert d != null : "missing descriptor: " + t;
			assert !d.params().isEmpty() || t == VFXEffectType.SCREEN_IMAGE : "empty params: " + t;
		}
		VFXEffectDescriptor blur = VFXEffectSchema.get(VFXEffectType.BLUR);
		assert blur.params().get(0).name().equals("radius") : "blur first param";
		assert blur.params().get(0).min() == 0.0F && blur.params().get(0).max() > 8.0F : "radius range";
		System.out.println("OK");
	}
}
```

- [ ] **Step 2: Run it and verify it fails**

Run: `javac -cp "versions/26.1.2/build/classes/java/main" -d "$env:TEMP\opencode" "$env:TEMP\opencode\CheckPostSchema.java"`
Expected: FAIL — `cannot find symbol: method registerBuiltIns()`.

- [ ] **Step 3: Implement `PostParamSources` + `registerBuiltIns`**

`PostParamSources` holds one `String[]` per post type, copied **verbatim and in order** from the `registerPost*` call sites in `src/client/java/dev/vfxweaver/client/postprocessing/VFXShaderPrograms.java` (lines 241–320). Add a one-line comment on each array: `// order = VFXShaderPrograms.registerPost(<TYPE>); do not reorder`. Declare each descriptor by streaming that array and looking up default/neutral/field from `VFXDefinition` (built-ins are registered by Task 3's schema only — for defaults, read the shipped JSON under `src/main/resources/data/vfxweaver/vfx/<name>.json` at build time is not allowed; instead use `0` as default and rely on `neutral`). Ranges: only pin a `min`/`max` where the spec/docs fix one (e.g. `radius` 0..64); otherwise `Float.NEGATIVE_INFINITY`/`POSITIVE_INFINITY` and `step = 0`.

- [ ] **Step 4: Run the test and verify it passes**

Run:
```
javac -cp "versions/26.1.2/build/classes/java/main" -d "$env:TEMP\opencode" "$env:TEMP\opencode\CheckPostSchema.java"
java -ea -cp "versions/26.1.2/build/classes/java/main;$env:TEMP\opencode" CheckPostSchema
```
Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/vfxweaver/editor
git commit -m "editor: declare post-effect parameter schema"
```

---

### Task 3: Non-post descriptors + full coverage

**Files:**
- Modify: `src/main/java/dev/vfxweaver/editor/VFXEffectSchema.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckFullSchema.java`

**Interfaces:**
- Produces: `registerBuiltIns()` also declares every non-post type (`CAMERA_SHAKE`, `CAMERA_ROLL`, `BLOCK_OUTLINE`, `BLOCK_TINT`, `LIGHT_BEAM`, `PULSE_RING`, `GUIDE_LINE`, `PARTICLES`, `BLOCK_CHAIN`, `ENTITY_TINT`, `ENTITY_OUTLINE`, `ENTITY_DISPLACE`, `FOV_MODIFIER`, `FOG_MODIFIER`, `WINDOW_CREATE`, `WINDOW_CONTROL`, `COLLECTION`, `SCREEN_IMAGE`). Non-post param names come from the new `docs/guide/effects/**` pages (one page per type) — read them and list names; do not invent. `COLLECTION` declares no params (uses `effects[]`).

- [ ] **Step 1: Write the failing test** (`CheckFullSchema.java`)

```java
import dev.vfxweaver.editor.*;
import dev.vfxweaver.effect.VFXEffectType;

public class CheckFullSchema {
	public static void main(String[] args) {
		VFXEffectSchema.registerBuiltIns();
		assert VFXEffectSchema.get(VFXEffectType.COLLECTION) != null : "collection";
		assert VFXEffectSchema.get(VFXEffectType.BLOCK_OUTLINE) != null : "block_outline";
		for (VFXEffectType t : VFXEffectType.values()) assert VFXEffectSchema.get(t) != null : "missing " + t;
		assert VFXEffectSchema.get(VFXEffectType.BLOCK_OUTLINE).params().stream().anyMatch(p -> p.name().equals("width")) : "outline width";
		System.out.println("OK");
	}
}
```

- [ ] **Step 2: Run it and verify it fails** — same command as Task 2 with `CheckFullSchema.java`. Expected: FAIL on the first missing type.

- [ ] **Step 3: Implement the non-post descriptors** in `registerBuiltIns` (append after the post loop). For each type, add the param descriptors with `name`/`kind`/range taken from its guide page. Mark `animatable = true` for numeric params (all params are animatable unless the guide says read-once, e.g. `COLLECTION` children).

- [ ] **Step 4: Run and verify it passes.** Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/vfxweaver/editor
git commit -m "editor: declare non-post effect schema, full type coverage"
```

---

### Task 4: Schema check script + CI

**Files:**
- Create: `scripts/check-vfx-schema.ps1`
- Modify: `.github/workflows/build.yml`
- Modify: `src/main/java/dev/vfxweaver/editor/VFXEffectSchema.java` (only if the check finds drift)

**Interfaces:**
- Consumes: `PostParamSources` (Task 2), `VFXEffectSchema` (Tasks 2–3).
- Produces: exit 0 iff, for every post type, the set of names in `PostParamSources.java` equals the set extracted from `VFXShaderPrograms.java`; and every `VFXEffectType` name appears in `VFXEffectSchema.java`.

- [ ] **Step 1: Write the check script** `scripts/check-vfx-schema.ps1` (following `scripts/check-depth-convention.ps1` style: no external deps, `throw` on failure, print a summary). It parses `VFXShaderPrograms.java` for `registerPost|registerFieldPost|registerMultiPass|registerDepthPost` call blocks and `PostParamSources.java` for the same type keys, and diffs the name sets; then scans `VFXEffectType.java` enum constants vs `VFXEffectSchema.java` registrations.

- [ ] **Step 2: Run it and verify it passes on the current tree**

Run: `powershell -ExecutionPolicy Bypass -File scripts/check-vfx-schema.ps1`
Expected: `schema check OK (N types)` and exit 0.

- [ ] **Step 3: Prove it fails on drift** — temporarily add a bogus `"zzz"` name to a `PostParamSources` array, run, confirm non-zero exit and an error naming that type/parameter; then revert.

- [ ] **Step 4: Add the check to CI** as a step in `.github/workflows/build.yml` before the Gradle build.

- [ ] **Step 5: Commit**

```bash
git add scripts/check-vfx-schema.ps1 .github/workflows/build.yml src/main/java/dev/vfxweaver/editor
git commit -m "editor: add schema drift check and wire it into CI"
```

---

### Task 5: EditorSession — revision gating and JSON validation

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/editor/EditorGame.java`
- Create: `src/client/java/dev/vfxweaver/client/editor/EditorSession.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckSession.java`

**Interfaces:**
- Produces:
  - `interface EditorGame { long applyAndPlay(String effectId, String json, String transport); boolean stop(String effectId); boolean setParam(String effectId, String name, float value); boolean setKeyframe(String effectId, String name, int time, float value); boolean seek(String effectId, int elapsedTicks); }`
  - `record EditorResult(boolean ok, long revision, @Nullable String error, @Nullable String detail)`
  - `EditorSession(EditorGame game)`
  - `EditorResult compile(String json, @Nullable Long revision)`
  - `EditorResult setParam(String effectId, String name, float value, long revision)`
  - `EditorResult setKeyframe(String effectId, String name, int time, float value, long revision)`
  - `long revision()`

- [ ] **Step 1: Write the failing test** (`CheckSession.java`, MC-free)

```java
import dev.vfxweaver.client.editor.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CheckSession {
	static class Fake implements EditorGame {
		final AtomicInteger applied = new AtomicInteger();
		public long applyAndPlay(String id, String json, String transport) { applied.incrementAndGet(); return 1L; }
		public boolean stop(String id) { return true; }
		public boolean setParam(String id, String n, float v) { return true; }
		public boolean setKeyframe(String id, String n, int t, float v) { return true; }
		public boolean seek(String id, int e) { return true; }
	}
	public static void main(String[] args) {
		Fake fake = new Fake();
		EditorSession s = new EditorSession(fake);
		assert s.compile("{ \"type\": \"blur\" }", null).ok() : "valid compile";
		long r1 = s.revision();
		assert r1 == 1L : "revision advanced";
		EditorResult stale = s.setParam("ns:fx", "radius", 2.0F, r1 - 1);
		assert !stale.ok() && "stale_revision".equals(stale.error()) : "stale rejected";
		assert s.setParam("ns:fx", "radius", 2.0F, r1).ok() : "current revision accepted";
		EditorResult bad = s.compile("{ not json", null);
		assert !bad.ok() && bad.error() != null : "bad json rejected";
		assert fake.applied.get() == 1 : "bad compile must not apply";
		System.out.println("OK");
	}
}
```

- [ ] **Step 2: Run it and verify it fails**

Run:
```
javac -cp "versions/26.1.2/build/classes/java/client;versions/26.1.2/build/classes/java/main" -d "$env:TEMP\opencode" "$env:TEMP\opencode\CheckSession.java"
```
Expected: FAIL — `cannot find symbol: class EditorSession`. (If `javac` needs MC to resolve the client dir, add the node's deobf MC jar — locate it under `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/*-deobf/<mc>/`.)

- [ ] **Step 3: Implement `EditorGame` and `EditorSession`**

`compile` validates the JSON with Gson (`JsonParser.parseString`) and returns `ok=false, error="invalid_json"` on failure **without** calling the game. On success it bumps `revision`, calls `game.applyAndPlay(...)`, and returns the new revision. `setParam`/`setKeyframe` compare the supplied `revision` to the current one; a mismatch returns `error="stale_revision"` without touching the game; a match forwards to the game. All string ids are the raw effect id (`<ns>:<name>`).

- [ ] **Step 4: Run and verify it passes.** Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/editor src/main/java/dev/vfxweaver/editor
git commit -m "editor: add session with revision gating and json validation"
```

---

### Task 6: EditorServer lifecycle, token, static assets, rate limit

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/editor/EditorAssets.java`
- Create: `src/client/java/dev/vfxweaver/client/editor/EditorServer.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckServer.java`

**Interfaces:**
- Consumes: `EditorSession` (Task 5).
- Produces:
  - `EditorAssets.resolve(String path) -> @Nullable byte[]` (classpath `assets/vfxweaver/editor/...`)
  - `EditorServer.start(EditorSession, int rateLimitPerSec) -> EditorServer`
  - `EditorServer.port() -> int`, `EditorServer.url() -> String` (`http://127.0.0.1:<port>/?token=<token>`), `EditorServer.token() -> String`, `EditorServer.stop()`
  - Requests without the token → `401`; paths under `/assets` are served only with the token too; non-`GET`/`POST` → `405`; over rate limit → `429`.

- [ ] **Step 1: Write the failing test** (`CheckServer.java`) — start the server on port 0, `HttpClient` GET `/` with the correct token (expect 200 + `text/html`), GET with a wrong token (expect 401), and hit a `POST` path past the rate limit (expect 429).

- [ ] **Step 2: Run it and verify it fails** — same classpath as Task 5. Expected: FAIL — `cannot find symbol: class EditorServer`.

- [ ] **Step 3: Implement** `EditorAssets` (classpath read, content-type by extension) and `EditorServer` (`HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)`, `SecureRandom` 128-bit URL-safe token, `setExecutor(Executors.newFixedThreadPool(4))`, token check via `?token=` or `Authorization: Bearer`, `Origin`/`Host` check for `POST`, a simple fixed-window rate limiter). Static handler serves from `EditorAssets`; unknown routes → 404. No loader/MC imports.

- [ ] **Step 4: Run and verify it passes.** Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/editor
git commit -m "editor: add local editor server with token, assets and rate limit"
```

---

### Task 7: SSE hub + `/events` + `/schema`

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/editor/EditorSse.java`
- Modify: `src/client/java/dev/vfxweaver/client/editor/EditorServer.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckSse.java`

**Interfaces:**
- Produces:
  - `EditorSse.add(OutputStream out) -> int` (returns a handle), `EditorSse.remove(int)`, `EditorSse.broadcast(String event, String json)`
  - `GET /events` (SSE, `text/event-stream`, emits `data: {"event":..., "data":...}\n\n`, idle timeout 30 s)
  - `GET /schema` → `VFXEffectSchema.toJson()` with `application/json`

- [ ] **Step 1: Write the failing test** (`CheckSse.java`) — connect to `/events` with `HttpClient` streaming, trigger `EditorSse.broadcast("hello","{}")`, assert a `data: {"event":"hello"` line arrives within 2 s; then GET `/schema` and assert the body contains `"blur"` (after `registerBuiltIns`).

- [ ] **Step 2: Run it and verify it fails.** Expected: FAIL.

- [ ] **Step 3: Implement** the SSE hub with a `CopyOnWriteArrayList` of clients, a daemon idle-reaper, and thread-safe writes; wire `/events` and `/schema` in `EditorServer`. On broadcast, remove any client whose write throws (client disconnect must not kill the hub).

- [ ] **Step 4: Run and verify it passes.** Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/editor
git commit -m "editor: add SSE hub, /events and /schema endpoints"
```

---

### Task 8: POST routes — `/compile`, `/transport`, `/set_param`, `/set_keyframe`

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/editor/EditorServer.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckRoutes.java`

**Interfaces:**
- Consumes: `EditorSession` (Task 5).
- Produces (all JSON, all token-gated):
  - `POST /compile` body `{ "effectId": "<ns>:<name>", "json": "<definition json as string>", "transport": "play"|"stop", "revision": <long?>" }` → `{ "ok": bool, "revision": long, "error"?: string, "detail"?: string }`. On success broadcasts `revision` over SSE.
  - `POST /transport` `{ "effectId": "<ns>:<name>", "action": "play"|"pause"|"stop"|"seek", "elapsedTicks": int? }`
  - `POST /set_param` `{ "effectId", "name", "value", "revision" }`
  - `POST /set_keyframe` `{ "effectId", "name", "time", "value", "revision" }`
  - Every response echoes the current `revision`; all failures use `{ "ok": false, "error": "<code>", "detail": "..." }` with codes `invalid_json`, `stale_revision`, `unknown_effect`, `bad_request`, `game_rejected`.

- [ ] **Step 1: Write the failing test** (`CheckRoutes.java` with a `Fake` `EditorGame`): POST `/compile` valid → `ok=true`, revision 1; POST `/set_param` with `revision=0` → `stale_revision`; POST `/set_param` with the current revision → `ok=true`; POST malformed JSON to `/compile` → `invalid_json` and game not called.

- [ ] **Step 2: Run it and verify it fails.** Expected: FAIL.

- [ ] **Step 3: Implement** the routes: parse bodies with Gson, delegate to `EditorSession`, broadcast `revision`/`error` events, never throw out of a handler (catch → `bad_request`).

- [ ] **Step 4: Run and verify it passes.** Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/editor
git commit -m "editor: add compile/transport/set_param/set_keyframe routes"
```

---

### Task 9: Elapsed-aware play API + `MinecraftEditorGame` + `/vfxeditor`

**Files:**
- Modify: `src/main/java/dev/vfxweaver/api/VFXLocalDispatcher.java` (add a `default` method)
- Modify: `src/main/java/dev/vfxweaver/api/VFXAPI.java` (static wrapper)
- Modify: `src/client/java/dev/vfxweaver/client/VFXClientAPI.java` (override)
- Create: `src/client/java/dev/vfxweaver/client/editor/MinecraftEditorGame.java`
- Modify: `src/client/java/dev/vfxweaver/client/platform/VFXClientCommands.java`
- Modify: `src/client/java/dev/vfxweaver/client/VFXClient.java`

**Interfaces:**
- Produces:
  - `VFXLocalDispatcher.playEffectAt(Identifier effectId, int durationTicks, int elapsedTicks, Map<String,Float> params, @Nullable EasingType easing) -> long` (default returns `0` so older dispatchers still link)
  - `VFXAPI.playEffectAt(...) -> boolean`
  - `VFXAPI.seekEffect(Identifier effectId, int elapsedTicks) -> boolean` (re-plays at a tick offset; implemented via `VFXEffectManager` already-existing elapsed overload `play(Identifier, int durationTicks, int elapsedTicks, long instanceId, Vec3, List<UUID>, Map<String,Float>, EasingFunction)`)
  - `MinecraftEditorGame implements EditorGame` — `applyAndPlay` calls `VFXDefinitionManager.get().registerLocal(id -> json)`, then plays on the render thread; `stop`/`setParam` forward to the existing locator; `seek` uses `VFXAPI.seekEffect`.
  - `/vfxeditor` client command starts/stops the server and opens the local URL in the desktop browser.

- [ ] **Step 1: Verify the existing overload with `javap`.** Run `javap -p -classpath "versions/26.1.2/build/classes/java/client" dev.vfxweaver.client.effect.VFXEffectManager` and confirm the `play(Identifier, int, int, long, Vec3, List, Map, EasingFunction)` overload signature before wiring `seek`.
- [ ] **Step 2: Add `playEffectAt` (default) + `VFXAPI.playEffectAt` + `VFXAPI.seekEffect` + `VFXClientAPI` overrides.** `playEffectAt` uses `schedulePlay(...)` with the elapsed value; extend `schedulePlay` with an `int elapsedTicks` parameter and call the manager's elapsed overload.
- [ ] **Step 3: Implement `MinecraftEditorGame`** and register it: instantiate the `EditorServer` lazily on first `/vfxeditor` from `VFXClient`, passing `new EditorSession(new MinecraftEditorGame())`.
- [ ] **Step 4: Build the active node** — `./gradlew :26.1.2:build`. Expected `BUILD SUCCESSFUL`.
- [ ] **Step 5: Manual check** — `./gradlew :26.1.2:runClient`, run `/vfxeditor`, open the printed URL, confirm the page loads and `/schema` returns data in the browser.
- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/vfxweaver/api src/client/java/dev/vfxweaver/client
git commit -m "editor: wire game bridge, elapsed-aware play, /vfxeditor command"
```

---

### Task 10: Pack format constant + export (world / folder / zip) + sidecar

**Files:**
- Create: `src/main/java/dev/vfxweaver/editor/VFXPackFormat.java`
- Create: `src/client/java/dev/vfxweaver/client/editor/EditorPack.java`
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckPack.java`

**Interfaces:**
- Produces:
  - `VFXPackFormat.mcmeta() -> String` — per-node compile-time `pack.mcmeta` (26.2: `{"pack":{"min_format":[107,1],"max_format":107,"description":"..."}}`; 1.21.11/26.1.2 format confirmed in Step 1).
  - `EditorPack.write(Path packDir, String namespace, Map<String,String> effectsJson, @Nullable String sidecarJson)` — creates `pack.mcmeta`, `data/<ns>/vfx/*.json`, optional `vfxweaver_editor.vfxproj.json`.
  - `EditorPack.writeZip(Path zip, ...)` — same layout inside the zip.
  - `EditorPack.worldPackDir(Path worldSaveDir, String namespace) -> Path`

- [ ] **Step 1: Confirm the pack formats.** Read the vanilla `pack.mcmeta` inside each node's client jar (the `versions/<node>` deps / the decompiled client) and record the exact `min_format`/`max_format` for 26.2, 26.1.2 and 1.21.11. Update the spec's open question. Do not guess.
- [ ] **Step 2: Write the failing test** (`CheckPack.java`) — call `EditorPack.write(tempDir, "vfx_demos", Map.of("ring", "{ \"type\": \"blur\" }"), "{\"version\":1}")`; assert `pack.mcmeta`, `data/vfx_demos/vfx/ring.json`, and the sidecar exist and the JSON parses; call `writeZip` and assert the entries exist.
- [ ] **Step 3: Run it and verify it fails.** Expected: FAIL — `cannot find symbol: class EditorPack`.
- [ ] **Step 4: Implement** `VFXPackFormat` and `EditorPack` (plain `java.nio` + `java.util.zip`, no new deps). `VFXPackFormat` uses a Stonecutter-guarded constant (`//? if >=26.2 { ... } else { ... }`) so each node writes its own format.
- [ ] **Step 5: Run and verify it passes.** Expected: `OK`.
- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/vfxweaver/editor/VFXPackFormat.java src/client/java/dev/vfxweaver/client/editor/EditorPack.java
git commit -m "editor: add per-node pack format and datapack export"
```

---

### Task 11: Import + round-trip

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/editor/EditorPack.java`
- Modify: `src/client/java/dev/vfxweaver/client/editor/EditorServer.java` (`POST /import`, `POST /export`)
- Test: `C:\Users\LIGHTF~1\AppData\Local\Temp\opencode\CheckImport.java`

**Interfaces:**
- Produces:
  - `EditorPack.read(Path packDirOrZip) -> EditorPack.ImportedPack(String namespace, Map<String,String> effectsJson, @Nullable String sidecarJson)`
  - `POST /import` body `{ "path": "..." }` → `{ "ok": true, "namespace": "...", "effects": { "<name>": "<json>" }, "sidecar": <json|null> }`
  - `POST /export` body `{ "destination": "world"|"folder"|"zip", "path": "...", "namespace": "...", "effects": {...}, "sidecar": <json|null> }` → `{ "ok": true, "path": "..." }`

- [ ] **Step 1: Write the failing test** (`CheckImport.java`) — write a pack with Task 10, import it, assert namespace/effects/sidecar match; then import a pack with **no sidecar** and assert it still succeeds (`sidecar == null`); then import a directory containing a corrupt `vfx/*.json` and assert that effect is returned as-is (raw string), not dropped, and the call still succeeds.
- [ ] **Step 2: Run it and verify it fails.** Expected: FAIL.
- [ ] **Step 3: Implement** `read` (walk `data/<ns>/vfx/*.json`, take namespace from the first path segment; zip via `ZipInputStream`) and the two routes. Import never parses/validates effect JSON (the web model does) — it returns raw strings.
- [ ] **Step 4: Run and verify it passes.** Expected: `OK`.
- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/editor src/main/java/dev/vfxweaver/editor
git commit -m "editor: add datapack import and export routes"
```

---

### Task 12: Vendor LiteGraph + app shell (tabs, canvas, schema palette)

**Files:**
- Create: `src/client/resources/assets/vfxweaver/editor/litegraph.js`, `litegraph.css` (vendored, pinned version noted in a header comment)
- Create: `src/client/resources/assets/vfxweaver/editor/index.html`, `editor.css`, `app.js`
- Test: manual (browser)

**Interfaces:**
- Consumes: `GET /` (Task 6), `GET /schema` (Task 7), `POST /import` (Task 11).
- Produces: `app.js` exposes `window.VFXEditor = { project, graph, openPack(namespace, effects), newTab(name, type) }`; a tab bar (one tab per effect), a LiteGraph canvas, and a palette built from `/schema`.

- [ ] **Step 1: Vendor LiteGraph** into `src/client/resources/assets/vfxweaver/editor/` and record its version + license header.
- [ ] **Step 2: Build the shell** — `index.html` loads `litegraph.js`, `editor.css`, `app.js`; `app.js` fetches `/schema`, registers one LiteGraph node type per effect type (`vfx/effect/<name>`) with an input socket per declared param, and renders a tab bar. Add a load-time guard: if `window.LiteGraph` is undefined, show a visible "unsupported browser" banner instead of a blank page (Review Focus baseline).
- [ ] **Step 3: Manual check** — `./gradlew :26.1.2:runClient`, `/vfxeditor`, open the URL: canvas renders, palette lists every schema type, creating a tab with e.g. `blur` shows a node with a `radius` socket. Capture a screenshot.
- [ ] **Step 4: Commit**

```bash
git add src/client/resources/assets/vfxweaver/editor
git commit -m "editor: vendor litegraph and add app shell with schema palette"
```

---

### Task 13: IR + compiler (effect node + value nodes) + selftest

**Files:**
- Create: `src/client/resources/assets/vfxweaver/editor/ir.js`
- Create: `src/client/resources/assets/vfxweaver/editor/compiler.js`
- Create: `src/client/resources/assets/vfxweaver/editor/selftest.html`
- Test: `selftest.html` fixtures; authoritative validation is the Java parser

**Interfaces:**
- Produces:
  - `ir.js`: `class Project { revision, tabs }`, `class Tab { name, type, params, valueNodes, edges, keys }`, plus `CommandStack` (do/undo/redo).
  - `compiler.js`: `compileTab(tab) -> string` producing the VFX datapack JSON for one effect (`type`, `duration`, `easing`, `params`, `graph`, `inputs`).

- [ ] **Step 1: Write the failing selftest** (`selftest.html`) with fixtures: (a) a `blur` with a constant `radius` → JSON with `"type":"blur"` and `params.radius`; (b) a `blur` whose radius is driven by a `time`→`curve` value graph → JSON contains `graph.nodes`, `graph.edges`, and `inputs.radius.from`; (c) an empty graph with only defaults; (d) **a tab whose type is absent from the schema** (Review Focus #1) → `compileTab` still emits `type` + its params and does not throw. Assert exact substrings; print PASS/FAIL per fixture.
- [ ] **Step 2: Run selftest** — serve it in the editor (`/assets/selftest.html`) and confirm FAIL (functions missing).
- [ ] **Step 3: Implement `ir.js` and `compiler.js`** — the IR keeps `{id, kind, inputs}` value nodes; `compileTab` copies them into `graph.nodes`, emits `graph.edges` for node→node wires and `inputs` for node→effect-param wires, and writes constant/`start`-`end`/`keyframes`/`bind`/`expr`/`multiply` param specs by the param's control state.
- [ ] **Step 4: Run selftest, expect all PASS.** 
- [ ] **Step 5: Cross-check against Java** — pipe each fixture's JSON into the game's `/compile` (or paste into a throwaway `VFXDefinition.parse` main) and confirm it parses. This is the authoritative validation (spec §9).
- [ ] **Step 6: Commit**

```bash
git add src/client/resources/assets/vfxweaver/editor
git commit -m "editor: add project IR and vfx json compiler with selftest"
```

---

### Task 14: Inspector (controls, bind, expr, keyframes)

**Files:**
- Create: `src/client/resources/assets/vfxweaver/editor/inspector.js`
- Modify: `src/client/resources/assets/vfxweaver/editor/app.js`

**Interfaces:**
- Consumes: `/schema` `VFXParamDescriptor`, `ir.js`.
- Produces: `inspector.render(tab, paramDescriptor, onEdit)` — a control per `kind` (`FLOAT`/`INT` slider+number, `BOOL` checkbox, `ENUM` select over `enumValues`, `COLOR_RGB` three fields, `POSITION` three numbers), plus form toggles for constant / start-end / keyframes / bind / expr / multiply that write the correct `ParamSpec` shape.

- [ ] **Step 1: Implement the controls** driven purely by the descriptor (no per-effect branches). Mark controls whose `neutral` is null so the user knows fade won't affect them.
- [ ] **Step 2: Implement the form toggles** writing `{ value }`, `{ start, end }`, `{ keyframes }`, `{ bind }`, `{ expr }`, and `{ ..., multiply }`.
- [ ] **Step 3: Manual check** — in-game: pick `blur`, switch `radius` to `expr`, type `4`, see the effect update live; switch to `keyframes` and confirm the timeline track appears (Task 15).
- [ ] **Step 4: Commit**

```bash
git add src/client/resources/assets/vfxweaver/editor
git commit -m "editor: schema-driven inspector with bind/expr/keyframe forms"
```

---

### Task 15: Timeline + transport + incremental sync

**Files:**
- Create: `src/client/resources/assets/vfxweaver/editor/timeline.js`
- Modify: `src/client/resources/assets/vfxweaver/editor/app.js`

**Interfaces:**
- Consumes: `POST /transport`, `POST /set_keyframe`, `POST /set_param`.
- Produces: `timeline.render(tab)` — one track per animated param, keys `{time,value,easing}` draggable, a playhead, and transport buttons (play/pause/stop/loop/seek). Dragging a slider emits `set_param` (debounced 80 ms); dragging a key emits `set_keyframe`; structural changes call `/compile`.

- [ ] **Step 1: Implement the timeline panel** and transport; the playhead position advances locally from a `requestAnimationFrame` clock while playing and seeks via `POST /transport {action:"seek", elapsedTicks}` on scrub.
- [ ] **Step 2: Implement incremental sync** — slider drag → debounced `set_param` with the current revision; key drag → `set_keyframe`; any structural edit → `/compile`.
- [ ] **Step 3: Manual check** — play a `blur`, scrub the playhead and confirm the in-game effect follows; drag the radius key and confirm it animates; stop returns to neutral.
- [ ] **Step 4: Commit**

```bash
git add src/client/resources/assets/vfxweaver/editor
git commit -m "editor: add timeline, transport and incremental live sync"
```

---

### Task 16: Bridge JS (reconnect, undo semantics), golden path, docs

**Files:**
- Create: `src/client/resources/assets/vfxweaver/editor/bridge.js`
- Modify: `src/client/resources/assets/vfxweaver/editor/app.js`
- Create: `docs/guide/editor.md`
- Modify: `docs/guide/index.md`, `docs/CHANGELOG.md`

**Interfaces:**
- Produces: `bridge.js` — `connect(token)`, `post(path, body)`, `on(event, handler)`; SSE auto-reconnect with exponential backoff (1s/2s/4s…), reconcile by comparing the last-seen revision and sending a full `/compile` when it diverges; **undo** applies the model snapshot then sends a full `/compile`, never an incremental undo.

- [ ] **Step 1: Implement `bridge.js`** with reconnect + revision reconcile; route all editor mutations through it. Wire `CommandStack.undo()` to recompile.
- [ ] **Step 2: Manual end-to-end golden path** — `/vfxeditor` → new project `vfx_demos` → new tab `my_blur` → add `blur` node, set `radius` keyframes → play → export "to world" → `/reload` → `/vfx play vfx_demos:my_blur` → effect plays from the datapack (not just the local layer). Capture a screenshot.
- [ ] **Step 3: Verify import** — reload the editor, import the exported pack, confirm both tabs/effects return with node layout restored (or auto-laid-out when the sidecar is absent).
- [ ] **Step 4: Write `docs/guide/editor.md`** (how to start, the golden path, export targets, browser baseline) and link it from `docs/guide/index.md`; add a CHANGELOG entry.
- [ ] **Step 5: Build all six nodes** — `./gradlew :26.2:build :26.1.2:build :1.21.11:build :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build`; run `scripts/check-vfx-schema.ps1`. Expected: all `BUILD SUCCESSFUL`, schema check OK.
- [ ] **Step 6: Commit**

```bash
git add src/client/resources/assets/vfxweaver/editor docs
git commit -m "editor: add bridge/reconnect/undo, golden path and docs"
```

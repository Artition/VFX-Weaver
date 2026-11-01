# `text_overlay` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one effect type, `text_overlay`, that draws text either as a layer over the frame or as
characters standing in the world, with per-character reveal and a configurable exit (fade or fall).

**Architecture:** 3D text is a `Display.TextDisplay` per character, spawned client-side and driven by
the display-entity engine that already serves `block`/`item` display particles
(`VFXBlockParticleEngine`). 2D text is drawn through `GuiGraphics` from a new hook in
`VFXClientRenderHooks`; its fall is analytic, not simulated. Both modes share one layout class that
measures the string, wraps it and assigns each character a rest position.

**Tech Stack:** Java 25, Minecraft 26.2 (Fabric + NeoForge jars), Stonecutter multi-version
conditionals, Gradle, PowerShell guard scripts under `scripts/`.

**Spec:** `docs/design/2026-11-01-text-overlay-design.md`

## Global Constraints

- Target **26.2 only**. The 26.3 port is explicitly out of scope; do not add `//? if >=26.3` branches
  and do not re-create the `versions/26.3*` nodes.
- **There is no unit-test framework in this repository** (`src/test` does not exist, there is no
  `test` task). The test cycle here is: a `scripts/check-*.ps1` guard that pins the wiring as literal
  text, `.\gradlew.bat :26.2:build`, and in-game verification. Do not add JUnit; do not claim a test
  passed when only a build ran.
- Effect parameters are `Map<String, Float>`: every animatable value is a float. String and enum-like
  choices that are structural (`mode`, `font`, `align`, `anchor`) live in the definition's `text`
  object; enum-like switches that stay animatable (`exit`, `per_char_anim`) are float codes, matching
  `spectral.mode` and `dent.line_mode`.
- New public API surface is additive only: no existing effect type, param name, UBO layout or
  `PROTOCOL_VERSION` changes.
- Every commit message follows Conventional Commits with a body explaining *why*, in plain prose.

---

### Task 1: The type, the spec and the schema

**Files:**
- Modify: `src/main/java/dev/vfxweaver/effect/VFXEffectType.java`
- Create: `src/main/java/dev/vfxweaver/effect/VFXTextSpec.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXDefinition.java`
- Modify: `src/main/java/dev/vfxweaver/effect/VFXEffectSchema.java`
- Create: `scripts/check-text-overlay.ps1`

**Interfaces:**
- Produces: `VFXEffectType.TEXT_OVERLAY` (`"text_overlay"`)
- Produces: `VFXTextSpec.parse(JsonObject) -> VFXTextSpec`, with accessors
  `content()`, `mode()`, `font()`, `align()`, `anchor()`
- Produces: `VFXDefinition.getText() -> @Nullable VFXTextSpec`

- [ ] **Step 1: Read the current enum and the schema registration so the new entries match their shape**

```powershell
cd 'D:\кодики\AI_place\VFX-Weaver-visual-editor'
Select-String -Path src\main\java\dev\vfxweaver\effect\VFXEffectType.java -Pattern 'SCREEN_IMAGE|COLLECTION'
Select-String -Path src\main\java\dev\vfxweaver\effect\VFXEffectSchema.java -Pattern 'registerNonPost\(VFXEffectType.PULSE_RING' -Context 0,7
```

- [ ] **Step 2: Write the guard first, so it fails for the right reason**

Create `scripts/check-text-overlay.ps1`:

```powershell
# Dev-only guard for the text_overlay effect.
#
# Pins the wiring as literal text, because none of it is exercised by a build:
#   * VFXEffectType declares TEXT_OVERLAY("text_overlay"), so the datapack `type` resolves;
#   * VFXDefinition carries the optional `text` block through parse, through withParams and out
#     through a getter - the same three places screenImage goes through, because a definition that
#     loses its spec when params are merged renders nothing;
#   * the schema registers the type with exactly the params the renderer reads, and marks the
#     enum-like ones (exit, per_char_anim) with their bounds so the editor cannot send nonsense;
#   * the renderer file exists and is wired from the world overlay dispatch and from the client
#     render hooks, so the type is not silently unhandled.
$ErrorActionPreference = "Stop"
$repoRoot  = Split-Path -Parent $PSScriptRoot
$typePath  = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$specPath  = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXTextSpec.java"
$defPath   = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXDefinition.java"
$schemaPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectSchema.java"

$failures = New-Object System.Collections.Generic.List[string]
function Require-Pattern([string]$path, [string]$pattern, [string]$why) {
    if (-not (Test-Path $path)) { $script:failures.Add("missing file: $path"); return }
    $text = Get-Content -Raw -Path $path
    if ($text -notmatch $pattern) { $script:failures.Add("$([IO.Path]::GetFileName($path)): $why") }
}

Require-Pattern $typePath 'TEXT_OVERLAY\("text_overlay"\)' "TEXT_OVERLAY is not declared"
Require-Pattern $specPath 'class VFXTextSpec'                "VFXTextSpec is missing"
Require-Pattern $specPath '"mode"'                          "spec does not parse 'mode'"
Require-Pattern $specPath '"font"'                          "spec does not parse 'font'"
Require-Pattern $defPath  'VFXTextSpec'                     "VFXDefinition does not carry the text spec"
Require-Pattern $defPath  'getText\('                       "VFXDefinition has no getText()"
Require-Pattern $schemaPath 'TEXT_OVERLAY'                  "schema does not register TEXT_OVERLAY"
foreach ($p in 'reveal', 'exit', 'exit_at', 'exit_stagger', 'gravity', 'bounce', 'friction', 'collision', 'tumble', 'spin_x') {
    Require-Pattern $schemaPath ([regex]::Escape('"' + $p + '"')) "schema is missing param '$p'"
}

if ($failures.Count -gt 0) { $failures | ForEach-Object { "FAIL  $_" }; exit 1 }
"text_overlay guard OK"
```

- [ ] **Step 3: Run it and watch it fail**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-text-overlay.ps1`
Expected: several `FAIL` lines, exit code 1.

- [ ] **Step 4: Add the enum entry**

In `VFXEffectType.java`, next to `SCREEN_IMAGE`:

```java
TEXT_OVERLAY("text_overlay"),
```

- [ ] **Step 5: Create `VFXTextSpec`**

```java
package dev.vfxweaver.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The structural half of a {@code text_overlay} effect: what the text says and how it is shaped as a
 * block of characters. The animatable numbers (position, scale, reveal, exit) stay in ordinary
 * {@code params}. Kept structural on purpose - {@code mode} selects a different render path, and a
 * render path cannot be interpolated between two values.
 */
public final class VFXTextSpec {
	/** Where the characters live. */
	public enum Mode { FLAT, WORLD }

	/** Horizontal placement of a line inside the block. */
	public enum Align { LEFT, CENTER, RIGHT }

	/** Which point of the block sits on the anchor position. */
	public enum Anchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

	private final String content;
	private final Mode mode;
	private final String font;
	private final Align align;
	private final Anchor anchor;

	private VFXTextSpec(final String content, final Mode mode, final String font,
			final Align align, final Anchor anchor) {
		this.content = content;
		this.mode = mode;
		this.font = font;
		this.align = align;
		this.anchor = anchor;
	}

	/**
	 * Parses the top-level {@code text} object of a {@code text_overlay} definition.
	 *
	 * @param json the effect object
	 * @return the parsed spec, never {@code null}
	 * @throws IllegalArgumentException when {@code text} is missing or {@code content} is blank
	 */
	public static VFXTextSpec parse(final JsonObject json) {
		if (json == null) {
			throw new IllegalArgumentException("text_overlay: expected an object");
		}
		final JsonElement block = json.get("text");
		if (block == null || block.isJsonNull() || !block.isJsonObject()) {
			throw new IllegalArgumentException("text_overlay: 'text' must be an object");
		}
		final JsonObject obj = block.getAsJsonObject();
		final JsonElement contentElement = obj.get("content");
		if (contentElement == null || contentElement.isJsonNull()
				|| !contentElement.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("text_overlay: 'text.content' is required and must be a string");
		}
		final String content = contentElement.getAsString();
		if (content.isBlank()) {
			throw new IllegalArgumentException("text_overlay: 'text.content' must not be blank");
		}
		final String mode = str(obj, "mode", "flat");
		final String font = str(obj, "font", "default");
		final String align = str(obj, "align", "center");
		final String anchor = str(obj, "anchor", "center");
		return new VFXTextSpec(content, parseMode(mode), font, parseAlign(align), parseAnchor(anchor));
	}

	private static String str(final JsonObject obj, final String key, final String fallback) {
		final JsonElement element = obj.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static Mode parseMode(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "flat" -> Mode.FLAT;
			case "world" -> Mode.WORLD;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.mode '" + raw + "'");
		};
	}

	private static Align parseAlign(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "left" -> Align.LEFT;
			case "center" -> Align.CENTER;
			case "right" -> Align.RIGHT;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.align '" + raw + "'");
		};
	}

	private static Anchor parseAnchor(final String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "top_left" -> Anchor.TOP_LEFT;
			case "top" -> Anchor.TOP;
			case "top_right" -> Anchor.TOP_RIGHT;
			case "left" -> Anchor.LEFT;
			case "center" -> Anchor.CENTER;
			case "right" -> Anchor.RIGHT;
			case "bottom_left" -> Anchor.BOTTOM_LEFT;
			case "bottom" -> Anchor.BOTTOM;
			case "bottom_right" -> Anchor.BOTTOM_RIGHT;
			default -> throw new IllegalArgumentException("text_overlay: unknown text.anchor '" + raw + "'");
		};
	}

	public String content() { return this.content; }
	public Mode mode() { return this.mode; }
	public String font() { return this.font; }
	public Align align() { return this.align; }
	public Anchor anchor() { return this.anchor; }
}
```

- [ ] **Step 6: Carry the spec through `VFXDefinition`**

Add the field next to `private final @Nullable VFXScreenImageSpec screenImage;`:

```java
	private final @Nullable VFXTextSpec text;
```

Add the constructor parameter after `screenImage`, assign it (`this.text = text;`), and parse it next
to the existing `screenImage` parse (around line 377):

```java
		VFXTextSpec text = null;
		if (VFXEffectType.TEXT_OVERLAY == type) {
			text = VFXTextSpec.parse(json);
		}
```

Pass `text` into both `new VFXDefinition(...)` calls (the main parse around line 389 and the
`withParams` copy around line 652), and add the getter next to `getScreenImage()`:

```java
	/** The authored text block, or {@code null} for every other effect type. */
	public @Nullable VFXTextSpec getText() {
		return this.text;
	}
```

- [ ] **Step 7: Register the schema**

In `VFXEffectSchema.registerNonPost()`, next to the `PULSE_RING` registration:

```java
		registerNonPost(VFXEffectType.TEXT_OVERLAY,
			new String[] {"x", "y", "pos_x", "pos_y", "pos_z", "scale", "line_height", "wrap_width",
				"alpha", "color_r", "color_g", "color_b", "gradient", "gradient_r", "gradient_g", "gradient_b",
				"shadow", "outline", "outline_r", "outline_g", "outline_b", "reveal",
				"per_char_anim", "stagger", "exit", "exit_at", "exit_stagger", "exit_speed",
				"gravity", "spin_x", "spin_y", "spin_z", "tumble", "collision", "bounce", "friction",
				"fade_out", "rot_x", "rot_y", "rot_z", "angle"},
			Map.of(
				"reveal", new float[] {0.0F, 1.0F},
				"collision", new float[] {0.0F, 1.0F},
				"tumble", new float[] {0.0F, 1.0F},
				"bounce", new float[] {0.0F, 1.0F},
				"friction", new float[] {0.0F, 1.0F},
				"per_char_anim", new float[] {0.0F, 3.0F},
				"exit", new float[] {0.0F, 1.0F}));
```

- [ ] **Step 8: Run the guard and the build**

Run:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-text-overlay.ps1
.\gradlew.bat :26.2:build --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-vfx-schema.ps1
```
Expected: `text_overlay guard OK`, `BUILD SUCCESSFUL`, schema guard OK.

- [ ] **Step 9: Commit**

```bash
git add scripts/check-text-overlay.ps1 src/main/java/dev/vfxweaver/effect
git commit -m "feat(effect): text_overlay type, spec and schema" -m "..."
```

---

### Task 2: Layout — measure, wrap, place

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/render/VFXTextLayout.java`

**Interfaces:**
- Consumes: `VFXTextSpec` (Task 1)
- Produces: `VFXTextLayout.measure(Font font, String content, float scale, float lineHeight, float wrapWidth) -> VFXTextLayout`
- Produces: `VFXTextLayout.glyphs() -> List<Glyph>` where `Glyph` is
  `record Glyph(char ch, int index, float x, float y, float advance)` in *layout units* (1 unit = one
  glyph at `scale = 1`); the caller multiplies by `scale`.
- Produces: `VFXTextLayout.width()`, `height()`

- [ ] **Step 1: Write the class**

```java
package dev.vfxweaver.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Measures a text block once and hands back one entry per visible character with its rest position.
 *
 * <p>Minecraft's own wrapping lives in {@code Font.split} and returns {@code FormattedCharSequence},
 * which discards the per-character positions we need for per-character physics. So the block is laid
 * out here instead: hard breaks on {@code \n}, soft breaks on spaces when {@code wrapWidth > 0}, one
 * {@link Glyph} per character in reading order.
 *
 * <p>Positions are in layout units - {@code scale = 1} pixels - so the same layout serves a 2D
 * overlay at any GUI scale and a 3D block at any size in the world.
 */
public final class VFXTextLayout {

	/** One visible character and where it rests before anything animates. */
	public record Glyph(char ch, int index, float x, float y, float advance) {
	}

	private final List<Glyph> glyphs;
	private final float width;
	private final float height;

	private VFXTextLayout(final List<Glyph> glyphs, final float width, final float height) {
		this.glyphs = List.copyOf(glyphs);
		this.width = width;
		this.height = height;
	}

	public static VFXTextLayout measure(final Font font, final String content,
			final float scale, final float lineHeight, final float wrapWidth) {
		final List<Glyph> glyphs = new ArrayList<>(content.length());
		final List<String> lines = wrap(font, content, wrapWidth, scale);
		final float lineStep = scale * lineHeight;
		float index = 0.0F;
		float widest = 0.0F;
		for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
			final String line = lines.get(lineNo);
			final float lineWidth = font.width(line) * scale / 8.0F;
			widest = Math.max(widest, lineWidth);
			float cursor = 0.0F;
			for (int i = 0; i < line.length(); i++) {
				final char ch = line.charAt(i);
				final int advance = font.width(String.valueOf(ch));
				glyphs.add(new Glyph(ch, (int) index++, cursor, lineNo * lineStep, advance * scale / 8.0F));
				cursor += advance * scale / 8.0F;
			}
		}
		final float totalHeight = Math.max(0.0F, (lines.size() - 1) * lineStep + scale);
		return new VFXTextLayout(glyphs, widest, totalHeight);
	}

	/**
	 * Breaks the content into lines: {@code \n} always breaks, spaces break when the next word would
	 * pass {@code wrapWidth} (0 disables soft wrapping).
	 */
	private static List<String> wrap(final Font font, final String content,
			final float wrapWidth, final float scale) {
		final List<String> lines = new ArrayList<>();
		for (final String hardLine : content.split("\n", -1)) {
			if (wrapWidth <= 0.0F) {
				lines.add(hardLine);
				continue;
			}
			final StringBuilder current = new StringBuilder();
			for (final String word : hardLine.split(" ", -1)) {
				final String candidate = current.isEmpty() ? word : current + " " + word;
				final float candidateWidth = font.width(candidate) * scale / 8.0F;
				if (!current.isEmpty() && candidateWidth > wrapWidth) {
					lines.add(current.toString());
					current.setLength(0);
					current.append(word);
				} else {
					current.setLength(0);
					current.append(candidate);
				}
			}
			lines.add(current.toString());
		}
		return lines;
	}

	public List<Glyph> glyphs() { return this.glyphs; }
	public float width() { return this.width; }
	public float height() { return this.height; }
}
```

- [ ] **Step 2: Build it**

Run: `.\gradlew.bat :26.2:build --console=plain`
Expected: `BUILD SUCCESSFUL`. Layout has no guard of its own on purpose: it is pure geometry and the
truth is the frame — Task 3's in-game check at two GUI scales covers wrapping, alignment and anchor.

- [ ] **Step 3: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/render/VFXTextLayout.java
git commit -m "feat(text): layout characters once, with hard and soft wrapping" -m "..."
```

---

### Task 3: 3D text standing in the world (M1)

**Files:**
- Create: `src/client/java/dev/vfxweaver/client/render/VFXTextOverlayRenderer.java`
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXWorldOverlayRenderer.java` (dispatch at
  the `if/else if` chain around line 1115)

**Interfaces:**
- Consumes: `VFXTextSpec`, `VFXTextLayout`
- Produces: `VFXTextOverlayRenderer.renderWorld(VFXActiveEffect effect, ClientLevel level, PoseStack pose)` —
  called from the overlay dispatch for `TEXT_OVERLAY`
- Produces (Task 4 extends it): an internal per-instance pool keyed by effect instance id

- [ ] **Step 0: Confirm the `TextDisplay` setter names on this exact version**

```powershell
$jar = (Get-ChildItem -Recurse -Filter '*26.2.jar' -Path .gradle\loom-cache |
    Where-Object { $_.Name -match 'clientOnly' } | Select-Object -First 1).FullName
& 'C:\Program Files\Java\jdk-25.0.2\bin\javap.exe' -p -cp $jar 'net.minecraft.world.entity.Display$TextDisplay'
```

Write down the setters that exist (text, line width, background, opacity, shadow, see-through,
alignment) and use those names below. Do not guess a setter that is not in that output.

- [ ] **Step 1: Spawn one display per character**

In `VFXTextOverlayRenderer`, model on `VFXBlockParticleEngine` (same file family, same lifecycle):

```java
	/** Characters of one live text effect, in layout order. */
	private record LiveText(long instanceId, List<Display.TextDisplay> entities, VFXTextLayout layout) {
	}

	private static final Map<Long, LiveText> LIVE = new HashMap<>();
	private static final int MAX_CHARS = 256;

	/** Builds (or rebuilds) the display entities of a world-mode text effect. */
	private static LiveText ensure(final VFXActiveEffect effect, final ClientLevel level) {
		final LiveText existing = LIVE.get(effect.getInstanceId());
		if (existing != null) {
			return existing;
		}
		final VFXTextSpec spec = effect.getDefinition().getText();
		final Font font = Minecraft.getInstance().font;
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = VFXTextLayout.measure(font, spec.content(), scale,
			effect.getParam("line_height", 1.2F), effect.getParam("wrap_width", 0.0F));
		final List<Display.TextDisplay> entities = new ArrayList<>(layout.glyphs().size());
		for (final VFXTextLayout.Glyph glyph : layout.glyphs()) {
			if (entities.size() >= MAX_CHARS) {
				VFXLog.warnOnce(LOGGER, "text_overlay:cap:" + effect.getId(),
					"text_overlay '{}': more than {} characters, the tail is dropped",
					effect.getId(), MAX_CHARS);
				break;
			}
			final Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
			display.setText(Component.literal(String.valueOf(glyph.ch())));
			// setters confirmed in Step 0: transparent background, shadow from param, opacity from reveal
			display.setPos(0.0, 0.0, 0.0);
			level.addEntity(display);
			entities.add(display);
		}
		final LiveText live = new LiveText(effect.getInstanceId(), entities, layout);
		LIVE.put(effect.getInstanceId(), live);
		return live;
	}
```

- [ ] **Step 2: Place and orient the block from the parameters**

```java
	/** Positions every character of the block: layout units scaled, rotated by rot_*, anchored. */
	private static void placeBlock(final VFXActiveEffect effect, final LiveText live) {
		final Vec3 origin = new Vec3(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F),
			effect.getParam("pos_z", 0.0F));
		final float scale = effect.getParam("scale", 1.0F);
		final float alignShift = switch (effect.getDefinition().getText().align()) {
			case LEFT -> 0.0F;
			case CENTER -> -live.layout().width() / 2.0F;
			case RIGHT -> -live.layout().width();
		};
		final float anchorShiftY = switch (effect.getDefinition().getText().anchor()) {
			case TOP_LEFT, TOP, TOP_RIGHT -> 0.0F;
			case LEFT, CENTER, RIGHT -> -live.layout().height() / 2.0F;
			case BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT -> -live.layout().height();
		};
		final Quaternionf rotation = new Quaternionf()
			.rotateX((float) Math.toRadians(effect.getParam("rot_x", 0.0F)))
			.rotateY((float) Math.toRadians(effect.getParam("rot_y", 0.0F)))
			.rotateZ((float) Math.toRadians(effect.getParam("rot_z", 0.0F)));
		for (int i = 0; i < live.entities().size(); i++) {
			final VFXTextLayout.Glyph glyph = live.layout().glyphs().get(i);
			final Vector3f local = new Vector3f(
				(glyph.x() + alignShift) * scale,
				(glyph.y() + anchorShiftY) * scale,
				0.0F).rotate(rotation);
			final Display.TextDisplay display = live.entities().get(i);
			display.setPos(origin.x + local.x, origin.y + local.y, origin.z + local.z);
			display.setTransformation(new Transformation(local, new Quaternionf(rotation), new Vector3f(scale, scale, scale), new Quaternionf()));
		}
	}
```

- [ ] **Step 3: Wire the dispatch**

In `VFXWorldOverlayRenderer`, in the `if/else if` chain around line 1115, next to `GUIDE_LINE`:

```java
		} else if (effect.getType() == VFXEffectType.TEXT_OVERLAY) {
			VFXTextOverlayRenderer.renderWorld(effect, level, pose);
```

- [ ] **Step 4: Build, deploy, look at it**

```powershell
.\gradlew.bat :26.2:build --console=plain
Copy-Item versions\26.2\build\libs\vfxweaver-*.jar "<26.2 instance>\minecraft\mods\" -Force
```

In game: `/vfx play vfx_demos:show_text_overlay` (create the demo in Task 6 first if it does not
exist), or a hand-written definition. Expected: the characters stand in the world at the anchor,
readable, correct line breaks, no flicker at 60 fps.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/render
git commit -m "feat(text): world-space characters as client-side text displays" -m "..."
```

---

### Task 4: Exit — fade and fall in 3D (M2)

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXTextOverlayRenderer.java`
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXBlockParticleEngine.java` (expose the
  integration step if it is private; keep the change additive)

**Interfaces:**
- Consumes: `LiveText` from Task 3
- Produces: per-character exit state, computed from the clock, not accumulated:
  `exitOffset(int index, VFXActiveEffect effect) -> Vector3f` and `exitAlpha(int index, VFXActiveEffect effect) -> float`

- [ ] **Step 1: Compute the detachment time per character**

```java
	/** Seconds since the effect started, from the effect clock. */
	private static float age(final VFXActiveEffect effect) {
		return effect.getElapsed() / 20.0F;
	}

	/** When character {@code index} starts leaving; -1 when the text never leaves on its own. */
	private static float detachTime(final VFXActiveEffect effect, final int index) {
		final float at = effect.getParam("exit_at", -1.0F);
		if (at < 0.0F) {
			return -1.0F;
		}
		return at + index * effect.getParam("exit_stagger", 0.0F);
	}
```

- [ ] **Step 2: Analytic fall with an optional floor**

```java
	/**
	 * Where the character is {@code since} seconds after it detached. Closed form, so the same tick
	 * always yields the same frame - a replay and a screenshot agree.
	 */
	private static Vector3f fallOffset(final VFXActiveEffect effect, final int index,
			final float since, final float groundY) {
		final float gravity = effect.getParam("gravity", 18.0F);
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		final float y = speed * since - 0.5F * gravity * since * since;
		if (effect.getParam("collision", 1.0F) < 0.5F) {
			return new Vector3f(0.0F, y, 0.0F);
		}
		final float floor = groundY;
		if (y <= floor) {
			final float impact = Math.max(0.0F, since - (float) Math.sqrt(Math.max(0.0F, 2.0F * -floor / gravity)));
			final float bounce = effect.getParam("bounce", 0.15F);
			return new Vector3f(0.0F, Math.max(floor, floor + bounce * impact), 0.0F);
		}
		return new Vector3f(0.0F, y, 0.0F);
	}

	/** Deterministic per-character jitter in [0.85, 1.15], from the instance seed and the index. */
	private static float jitter(final VFXActiveEffect effect, final int index) {
		final long h = effect.getInstanceId() * 0x9E3779B97F4A7C15L ^ (index * 0xBF58476D1CE4E5B9L);
		final int bits = (int) (h ^ (h >>> 31));
		return 0.85F + 0.30F * ((bits & 0xFFFF) / 65535.0F);
	}
```

`groundY` is the height of the first block below the character's rest position, looked up once per
character and cached in `LiveText`; if there is no block below, use `Float.NEGATIVE_INFINITY` so the
character falls free.

- [ ] **Step 3: Apply position, spin and opacity per frame**

In the per-frame update, for each character: if `detachTime < 0` or `age < detachTime`, keep the rest
pose and opacity 1; otherwise apply `fallOffset`, rotate by `spin_* * since * tumble`, and set the
display's text opacity from `1 - clamp((since - fallDuration) / fade_out)`.

- [ ] **Step 4: Mirror the behaviour for `exit: 0` (fade)**

`exit = 0` skips the offset entirely and drives only the opacity, using the same timeline, so the two
exits differ in one branch and nothing else.

- [ ] **Step 5: Remove entities when the effect stops**

Extend the removal path that `VFXBlockParticleEngine` already has (it removes its displays when an
effect stops or the level changes) to the text pool. Verify with `/vfx stop`.

- [ ] **Step 6: In-game verification**

`exit_at 2`, `exit_stagger 0.04`, `collision 1`: the line should crumble onto the island and settle.
Then `collision 0`: the same letters should fall through the ground and fade. Both under `persistent`.

- [ ] **Step 7: Commit**

```bash
git add src/client/java/dev/vfxweaver/client/render
git commit -m "feat(text): letters fall out of the line and settle on the ground" -m "..."
```

---

### Task 5: 2D layer (M3)

**Files:**
- Modify: `src/client/java/dev/vfxweaver/client/render/VFXTextOverlayRenderer.java`
- Modify: `src/client/java/dev/vfxweaver/client/platform/VFXClientRenderHooks.java`

**Interfaces:**
- Produces: `VFXTextOverlayRenderer.renderFlat(VFXActiveEffect effect, GuiGraphics graphics, int width, int height)`
  called once per frame per flat text effect

- [ ] **Step 1: Find the right HUD hook for 26.2 Fabric API**

```powershell
Select-String -Path src\client\java\dev\vfxweaver\client\platform\VFXClientRenderHooks.java -Pattern 'WorldRenderEvents|Hud|END_MAIN' -Context 2,4
Get-ChildItem -Recurse -Filter '*Hud*.java' -Path .gradle\loom-cache -ErrorAction SilentlyContinue | Select-Object -First 5
```

Use the hook the file already imports if one fits; otherwise add the Fabric API entry point that
v0.160.0+26.2 provides for HUD drawing. Record which one was used in the commit body.

- [ ] **Step 2: Draw the block**

```java
	public static void renderFlat(final VFXActiveEffect effect, final GuiGraphics graphics,
			final int screenWidth, final int screenHeight) {
		final VFXTextSpec spec = effect.getDefinition().getText();
		final Font font = Minecraft.getInstance().font;
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = VFXTextLayout.measure(font, spec.content(), scale,
			effect.getParam("line_height", 1.2F), effect.getParam("wrap_width", 0.0F));
		final float anchorX = effect.getParam("x", 0.5F) * screenWidth;
		final float anchorY = effect.getParam("y", 0.5F) * screenHeight;
		final float alignShift = switch (spec.align()) {
			case LEFT -> 0.0F;
			case CENTER -> -layout.width() / 2.0F;
			case RIGHT -> -layout.width();
		};
		// ... per glyph: position, rotation by `angle`, colour, gradient, shadow, outline, then draw
	}
```

- [ ] **Step 3: Analytic fall with frame collision**

Same shape as Task 4's `fallOffset`, but in screen space with `gravity` in screen heights per second
and reflections off `[0, screenWidth] x [0, screenHeight]` when `collision` is on. Reflection is a
closed-form fold, not a loop over frames:

```java
	/** Folds a coordinate back inside [0, size] as if it bounced, losing `bounce` energy per impact. */
	private static float reflect(final float value, final float size) {
		final float span = Math.max(size, 1.0F);
		final float folded = Math.abs(value % (2.0F * span));
		return folded > span ? 2.0F * span - folded : folded;
	}
```

- [ ] **Step 4: Verify at two GUI scales**

Set GUI scale 2 and 4; the text must keep its proportions (everything is in screen fractions or
heights, never in raw pixels), and a fall with `collision 1` must settle on the bottom edge.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/vfxweaver/client
git commit -m "feat(text): 2D text layer with a closed-form fall" -m "..."
```

---

### Task 6: Docs, demo, and the frame that proves it (M4)

**Files:**
- Create: `docs/guide/effects/text/text-overlay.md`
- Modify: the effects index page that lists types
- Modify: `showcase/` — new `show_text_overlay` definition + the cheat sheet
- Modify: `compositions/rift/generate.py` + regenerate the composition

- [ ] **Step 1: Write the effect page**

Follow the shape of `docs/guide/effects/screen/screen-image.md`: a table of every parameter with
default and meaning, one JSON example, one `/vfx play` example, and a section on the two exit modes
that states plainly that 2D collision is the frame and 3D collision is the world.

- [ ] **Step 2: Add the demo definition**

`showcase/.../vfx/show_text_overlay.json` — a looping demo that reveals a line, holds, then falls;
same recording-friendly shape as the other `show_*` definitions.

- [ ] **Step 3: Rebuild and re-verify the showcase pack**

```powershell
python scripts/build-showcase-pack.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-showcase-pack-parse.ps1
```

- [ ] **Step 4: Put a title card on RIFT**

In `compositions/rift/generate.py`, add a `text_overlay` child to the scene's `order` list: two lines
in `world` mode above the island, `exit_at` after the hold, `exit: fall`, `collision: 1` so the
letters settle on the deepslate. Regenerate and reinstall into the 26.2 world, then screenshot.

- [ ] **Step 5: Full check sweep**

```powershell
python scripts/check-composition.py compositions/rift
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-text-overlay.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-vfx-schema.ps1
.\gradlew.bat :26.2:build :26.2-neoforge:build --console=plain
```

- [ ] **Step 6: Commit**

```bash
git add docs showcase compositions
git commit -m "docs(text): page, demo and a title card on the composition" -m "..."
```

---

## Self-Review

**Spec coverage:** the type, spec and schema (Task 1), the shared layout (Task 2), world mode (Task 3),
both exit modes and the collision toggle in 3D (Task 4), flat mode with frame collision and `angle`
(Task 5), docs, demo and the RIFT frame (Task 6). Determinism is Task 4's `jitter` and the closed-form
`fallOffset`; the entity cap is Task 3's `MAX_CHARS`; cleanup is Task 4 step 5.

**Not covered by any task, on purpose:** per-character colour/font arrays, custom TTF loading,
in-game editor — the spec lists them as non-goals for v1.

**Placeholder scan:** no `TBD`/`TODO`. Two steps deliberately instruct the executor to confirm names
against the toolchain before writing code (Task 3 step 0, Task 5 step 1) — that is a required
discovery, not a placeholder, and both say exactly what to run.

**Type consistency:** `VFXTextLayout.Glyph(ch, index, x, y, advance)` is produced in Task 2 and
consumed only with those accessors in Tasks 3 and 5; `getText()` returns `VFXTextSpec` in Task 1 and is
called with that type everywhere after; `LiveText` is introduced in Task 3 and extended, never
renamed, in Task 4.

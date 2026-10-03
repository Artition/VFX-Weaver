# Dev-only guard for the window_create / window_control datapack surface (Task 6).
#
# The two aux-window effects are an effect-type registration plus a structural parse, exactly like
# surface_pattern/color_grade: the type is registered in VFXEffectType (and is NOT a post pass, so
# the post chain never sees it), a small structural spec carries the strings (VFXWindowSpec: the
# window name `id`, the optional `texture` resource id and the `titles` list) and the animatable
# numbers live in ordinary `params`. The strings stay in the definition and never travel on the
# wire: VFXTriggerPayload (PROTOCOL_VERSION 6) gains no field, and VFXEffectType.fromString already
# resolves the new shader names without any wire change.
#
# Static phase pins:
#   * VFXEffectType declares WINDOW_CREATE("window_create") and WINDOW_CONTROL("window_control") and
#     excludes both from isPostProcessing();
#   * VFXDefinition parses the window block only for the two window types and exposes getWindow();
#   * VFXWindowSpec exists, parses id/texture/titles, caps the titles list and clamps an animated
#     title_index into the list (held at the nearest valid title);
#   * VFXTriggerPayload is untouched (still PROTOCOL_VERSION 6, no window/title field).
# Runnable phase parses window_create and window_control definitions and asserts the exact field set,
# the type, the structural values and title_index clamping, and that a missing `id` is a per-file
# parse error. GLSL is never compiled and there is no GPU here, so nothing here claims a pixel works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-fields.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$effectTypePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$definitionPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXDefinition.java"
$specPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXWindowSpec.java"
$payloadPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\network\VFXTriggerPayload.java"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($path in @($effectTypePath, $definitionPath, $payloadPath)) {
	if (-not (Test-Path -LiteralPath $path)) {
		Write-Error "window fields check: missing $path"
		exit 1
	}
}
$effectType = [System.IO.File]::ReadAllText($effectTypePath)
$definition = [System.IO.File]::ReadAllText($definitionPath)
$payload = [System.IO.File]::ReadAllText($payloadPath)

function Get-Body([string]$text, [string]$signature) {
	$start = $text.IndexOf($signature)
	if ($start -lt 0) { return $null }
	$open = $text.IndexOf('{', $start)
	if ($open -lt 0) { return $null }
	$depth = 0
	for ($i = $open; $i -lt $text.Length; $i++) {
		if ($text[$i] -eq '{') { $depth++ }
		elseif ($text[$i] -eq '}') { $depth--; if ($depth -eq 0) { return $text.Substring($open + 1, $i - $open - 1) } }
	}
	return $null
}

# 1) the effect types are registered, the same way every other effect is.
if ($effectType -notmatch 'WINDOW_CREATE\("window_create"\)') {
	$problems.Add('VFXEffectType does not declare WINDOW_CREATE("window_create")')
}
if ($effectType -notmatch 'WINDOW_CONTROL\("window_control"\)') {
	$problems.Add('VFXEffectType does not declare WINDOW_CONTROL("window_control")')
}
# A window effect is neither a post pass nor world geometry: getActivePostEffects() must not pick
# it up, or the post chain would try to render it.
$isPost = Get-Body $effectType 'public boolean isPostProcessing() {'
if ($isPost -eq $null) {
	$problems.Add("VFXEffectType has no readable isPostProcessing()")
} else {
	foreach ($excluded in @('this != WINDOW_CREATE', 'this != WINDOW_CONTROL')) {
		if (-not $isPost.Contains($excluded)) {
			$problems.Add("isPostProcessing() does not exclude windows ($excluded) - a window effect is not a post pass")
		}
	}
}

# 2) the datapack parse accepts the window block only for the two window types and exposes it.
if ($definition -notmatch 'type == VFXEffectType\.WINDOW_CREATE \|\| type == VFXEffectType\.WINDOW_CONTROL') {
	$problems.Add("VFXDefinition.parse does not gate the window block on WINDOW_CREATE/WINDOW_CONTROL")
}
if ($definition -notmatch 'VFXWindowSpec\.parse\(json\)') {
	$problems.Add("VFXDefinition.parse does not resolve the window block through VFXWindowSpec.parse(json)")
}
if ($definition -notmatch 'getWindow\(\)') {
	$problems.Add("VFXDefinition has no getWindow() accessor")
}

# 3) the structural spec: parse, bounded titles, clamped title_index.
if (-not (Test-Path -LiteralPath $specPath)) {
	$problems.Add("src\main\java\dev\vfxweaver\effect\VFXWindowSpec.java does not exist")
} else {
	$spec = [System.IO.File]::ReadAllText($specPath)
	if ($spec -match 'net\.fabricmc|net\.neoforged') {
		$problems.Add("VFXWindowSpec imports a loader package - the datapack model stays loader-agnostic")
	}
	if ($spec -notmatch 'public static VFXWindowSpec parse\(final JsonObject json\)') {
		$problems.Add("VFXWindowSpec has no 'public static VFXWindowSpec parse(JsonObject)'")
	}
	if ($spec -notmatch 'MAX_TITLES') {
		$problems.Add("VFXWindowSpec does not bound the titles list with a MAX_TITLES cap")
	}
	if ($spec -notmatch 'public @Nullable String titleAt\(final float index\)') {
		$problems.Add("VFXWindowSpec has no 'public @Nullable String titleAt(float index)'")
	}
	if ($spec -notmatch 'Math\.max\(0, Math\.min\(this\.titles\.size\(\) - 1, rounded\)\)') {
		$problems.Add("VFXWindowSpec.titleAt does not clamp the index into the titles bounds")
	}
	foreach ($field in @('id', 'texture', 'titles')) {
		if ($spec -notmatch ('"' + $field + '"')) {
			$problems.Add("VFXWindowSpec does not parse the '$field' field")
		}
	}
}

# 4) the wire is untouched: no new field, protocol version still 6.
if ($payload -notmatch 'PROTOCOL_VERSION = 6') {
	$problems.Add("VFXTriggerPayload.PROTOCOL_VERSION is no longer 6 - the window surface must not touch the wire")
}
foreach ($forbidden in @('window_create', 'window_control', 'windowId', 'titles')) {
	if ($payload.Contains($forbidden)) {
		$problems.Add("VFXTriggerPayload carries '$forbidden' - strings do not travel on the wire in v1")
	}
}

Write-Host "Window fields check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window fields check failed ($($problems.Count) problem(s))."
	exit 1
}

# 5) runnable: parse window_create / window_control and assert the field set + clamping.
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) {
		$javac = Join-Path $candidate.FullName "bin\javac.exe"
		$java = Join-Path $candidate.FullName "bin\java.exe"
	}
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "window fields check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXWindowSpec.class"))) {
	Write-Error "window fields check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "window fields check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-window-fields-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "WindowFieldsCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXWindowSpec;
import net.minecraft.resources.Identifier;

/** Standalone assertions for the window_create / window_control datapack surface (Task 6). */
public final class WindowFieldsCheck {
	public static void main(final String[] args) {
		final String createJson = "{\"type\":\"window_create\",\"id\":\"hud\",\"texture\":\"vfx_demos:window/pic\","
			+ "\"titles\":[\"One\",\"Two\",\"Three\"],\"params\":{\"pos_x\":0.1,\"pos_y\":0.2,\"size_w\":0.5,"
			+ "\"size_h\":0.4,\"opacity\":0.9,\"frames\":4,\"frame_time\":0.5,\"title_index\":1}}";
		final VFXDefinition create = parse("vfx_demos", "check_window_create", createJson);
		require(create.getType() == VFXEffectType.WINDOW_CREATE, "the create type is not WINDOW_CREATE");
		require(!create.getType().isPostProcessing(), "WINDOW_CREATE is treated as a post pass");
		final VFXWindowSpec spec = create.getWindow();
		require(spec != null, "window_create has no window spec");
		require("hud".equals(spec.id()), "the window id is not 'hud'");
		require(spec.texture() != null && spec.texture().equals(Identifier.fromNamespaceAndPath("vfx_demos", "window/pic")),
			"the window texture id did not parse");
		require(spec.titles().size() == 3, "the titles list is not [One, Two, Three]");
		for (String param : new String[]{"pos_x", "pos_y", "size_w", "size_h", "opacity", "frames", "frame_time", "title_index"}) {
			require(create.getParams().containsKey(param), "the create field set is missing '" + param + "'");
		}
		// title_index clamps (held at the nearest valid title), including the rounding.
		require("Two".equals(spec.titleAt(1.0F)), "title_index 1 is not 'Two'");
		require("One".equals(spec.titleAt(-5.0F)), "a negative title_index did not clamp to the first title");
		require("Three".equals(spec.titleAt(99.0F)), "an over-range title_index did not clamp to the last title");
		require("Two".equals(spec.titleAt(1.4F)), "title_index 1.4 did not round to 'Two'");
		require("Three".equals(spec.titleAt(1.6F)), "title_index 1.6 did not round to 'Three'");

		final VFXDefinition control = parse("vfx_demos", "check_window_control",
			"{\"type\":\"window_control\",\"id\":\"hud\",\"params\":{\"opacity\":0.5}}");
		require(control.getType() == VFXEffectType.WINDOW_CONTROL, "the control type is not WINDOW_CONTROL");
		require(!control.getType().isPostProcessing(), "WINDOW_CONTROL is treated as a post pass");
		require(control.getWindow() != null && "hud".equals(control.getWindow().id()),
			"window_control did not carry its target id");
		require(control.getWindow().titles().isEmpty(), "window_control invented a titles list");
		require(control.getWindow().titleAt(0.0F) == null, "titleAt on an empty titles list is not null");

		// A non-window definition ignores the window keys entirely (additive) and has no spec.
		final VFXDefinition grade = parse("vfx_demos", "check_not_window", "{\"type\":\"color_grade\"}");
		require(grade.getWindow() == null, "a non-window type carries a window spec");

		// `id` is required for a window type: a missing/blank name is a per-file parse error.
		requireThrows("{\"type\":\"window_create\"}", "missing");
		requireThrows("{\"type\":\"window_create\",\"id\":\"  \"}", "blank");
		requireThrows("{\"type\":\"window_control\",\"id\":\"hud\",\"titles\":\"no\"}", "non-array titles");
		requireThrows("{\"type\":\"window_create\",\"id\":\"hud\",\"titles\":[1]}", "non-string title");

		System.out.println("window fields check OK: both types parse; id/texture/titles carried; title_index clamps");
	}

	private static void requireThrows(final String json, final String what) {
		try {
			parse("vfx_demos", "check_bad", json);
			throw new AssertionError("expected a parse error for " + what);
		} catch (final IllegalArgumentException expected) {
			// the per-file parse error isolation depends on this being an IllegalArgumentException
		}
	}

	private static VFXDefinition parse(final String namespace, final String name, final String json) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath(namespace, name),
			JsonParser.parseString(json).getAsJsonObject());
	}

	private static void require(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" WindowFieldsCheck
	if ($LASTEXITCODE -ne 0) { throw "WindowFieldsCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Window fields check OK."
exit 0

# Dev-only guard: the editor effect schema must match the renderer's post registration.
#
# The visual node editor reads its per-effect parameter contract from the schema package
# (`dev.vfxweaver.editor`). Two parallel sources encode it:
#   - `PostParamSources.PARAMS` (main source set): the verbatim Config names per effect type.
#   - `VFXShaderPrograms.register*` (client source set): the registration the config UBO is
#     actually sized and written from.
# If the two drift, the editor shows parameters the shader has no offset for (or misses ones it
# does), so this check fails the build. It also asserts every `VFXEffectType` enum constant is
# covered by a schema registration.
#
# The client registration is the source of truth; this script is read-only (AGENTS.md: never
# edit/reorder `VFXShaderPrograms.registerPost(...)`). It needs no compiled classes: a plain
# source scan, runnable before the Gradle build.
#
# Scope: Java-side parity only. This checks the `PostParamSources.PARAMS` name sets against the
# `VFXShaderPrograms.register*` Config name lists, plus `VFXEffectType` enum coverage. It does
# NOT parse the GLSL shaders and does NOT verify each shader's `Config` block names or their
# std140 order - that ~40-block contract stays with the shader sources themselves.
#
# Parsing notes (all shapes are parsed; assumptions documented in place):
#   - Comments are stripped before call extraction, because the registration call sites carry
#     `//` comments (some with parentheses) and block comments. No string literal in these files
#     contains `//`, so the line-comment strip is safe.
#   - `registerPost(TYPE, "a", "b")`          -> the string literals are the params.
#     `registerPost(TYPE, new String[]{...}, true, Set.of(...), n)` -> only the array, never the
#     `Set.of` pixel-param names.
#   - `registerFieldPost(TYPE, new String[]{...}, "fieldInput")` -> array names plus the trailing
#     fieldInput (a real logical input appended after the Config names, Task 2).
#   - `registerMultiPass(TYPE, List.of(shaders), List.of(new String[]{...}, ...), ...)` -> the
#     union of the per-pass arrays; the shader-name `List.of` is ignored.
#   - `registerDepthPost(TYPE, ...names...)` and `registerDepthPost(TYPE, true, ...names...)` ->
#     all string literals (the boolean is not a param).
#   - `registerFeedbackEffects()` -> `feedbackPipeline` calls grouped by shader prefix: `afterimage_*`
#     union -> AFTERIMAGE, `stop_motion` -> STOP_MOTION.
#   - `registerScreenImagePost()` -> SCREEN_IMAGE with an empty param set (Config written positionally).
#   - Coverage: enum constants are read from `VFXEffectType.java`; a constant is covered when it is
#     named in `VFXEffectSchema.java` OR is a key in `PostParamSources.java` (post types are only
#     referenced through the latter).
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-vfx-schema.ps1
# Exits 1 (after listing every problem) on drift; 0 when the schema matches the renderer.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$sourcesPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\editor\PostParamSources.java"
$schemaPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\editor\VFXEffectSchema.java"
$typePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"

$problems = New-Object System.Collections.Generic.List[string]

# Reads a source file with block and line comments removed (see the parsing notes above).
function Read-NoComments([string]$path) {
	$text = [System.IO.File]::ReadAllText($path)
	$text = [regex]::Replace($text, '(?s)/\*.*?\*/', '')
	$text = [regex]::Replace($text, '(?m)//.*$', '')
	return $text
}

# All double-quoted string literals in a text fragment, in order.
function Get-Strings([string]$text) {
	$out = New-Object System.Collections.Generic.List[string]
	foreach ($m in [regex]::Matches($text, '"([^"]*)"')) { $out.Add($m.Groups[1].Value) }
	return $out
}

# Every `new String[]{ ... }` array body in a text fragment (used for the array-shaped calls).
function Get-Arrays([string]$text) {
	$out = New-Object System.Collections.Generic.List[string]
	foreach ($m in [regex]::Matches($text, '(?s)new String\[\]\{(.*?)\}')) { $out.Add($m.Groups[1].Value) }
	return $out
}

# The balanced-paren argument text of every `name(...)` occurrence that is a call (prefix is not a
# word char), so method *definitions* (`void registerPost(`) are included too and filtered later.
function Get-Calls([string]$text, [string]$name) {
	$calls = New-Object System.Collections.Generic.List[string]
	$needle = $name + '('
	$idx = 0
	while (($idx = $text.IndexOf($needle, $idx, [System.StringComparison]::Ordinal)) -ge 0) {
		$before = if ($idx -gt 0) { $text[$idx - 1] } else { ' ' }
		$start = $idx + $needle.Length
		if ([char]::IsLetterOrDigit($before) -or $before -eq '_') { $idx = $start; continue }
		$depth = 1
		$i = $start
		while ($i -lt $text.Length -and $depth -gt 0) {
			$c = $text[$i]
			if ($c -eq '(') { $depth++ } elseif ($c -eq ')') { $depth-- }
			$i++
		}
		$calls.Add($text.Substring($start, $i - $start - 1))
		$idx = $i
	}
	return $calls
}

# --- 1. the renderer's name set per post type ----------------------------------------------------
$programs = Read-NoComments $programsPath
$registered = [ordered]@{}

function Add-Params([string]$type, [object]$names) {
	if (-not $registered.Contains($type)) { $registered[$type] = New-Object System.Collections.Generic.List[string] }
	foreach ($n in @($names)) { if (-not $registered[$type].Contains($n)) { $registered[$type].Add($n) } }
}

# Each registration function is parsed by the shape it uses (see the parsing notes).
foreach ($call in @(Get-Calls $programs 'registerPost')) {
	$m = [regex]::Match($call, '^\s*VFXEffectType\.(\w+)')
	if (-not $m.Success) { continue }
	if ($call.Contains('new String[]')) {
		$names = New-Object System.Collections.Generic.List[string]
		foreach ($arr in @(Get-Arrays $call)) { foreach ($s in @(Get-Strings $arr)) { $names.Add($s) } }
		Add-Params $m.Groups[1].Value $names
	} else {
		Add-Params $m.Groups[1].Value (Get-Strings $call)
	}
}
foreach ($call in @(Get-Calls $programs 'registerFieldPost')) {
	$m = [regex]::Match($call, '^\s*VFXEffectType\.(\w+)')
	if (-not $m.Success) { continue }
	$names = New-Object System.Collections.Generic.List[string]
	foreach ($arr in @(Get-Arrays $call)) { foreach ($s in @(Get-Strings $arr)) { $names.Add($s) } }
	# the trailing fieldInput string (the only literal outside an array) is appended by Task 2.
	foreach ($s in @(Get-Strings $call)) { if (-not $names.Contains($s)) { $names.Add($s) } }
	Add-Params $m.Groups[1].Value $names
}
foreach ($call in @(Get-Calls $programs 'registerMultiPass')) {
	$m = [regex]::Match($call, '^\s*VFXEffectType\.(\w+)')
	if (-not $m.Success) { continue }
	$names = New-Object System.Collections.Generic.List[string]
	foreach ($arr in @(Get-Arrays $call)) { foreach ($s in @(Get-Strings $arr)) { $names.Add($s) } }
	Add-Params $m.Groups[1].Value $names
}
foreach ($call in @(Get-Calls $programs 'registerDepthPost')) {
	$m = [regex]::Match($call, '^\s*VFXEffectType\.(\w+)')
	if (-not $m.Success) { continue }
	Add-Params $m.Groups[1].Value (Get-Strings $call)
}
# registerFeedbackEffects: group the feedbackPipeline(...) calls by shader prefix.
foreach ($call in @(Get-Calls $programs 'feedbackPipeline')) {
	$lits = @(Get-Strings $call)
	if ($lits.Count -lt 1) { continue }
	$shader = $lits[0]
	$names = New-Object System.Collections.Generic.List[string]
	for ($i = 1; $i -lt $lits.Count; $i++) { $names.Add($lits[$i]) }
	if ($shader.StartsWith('afterimage')) { Add-Params 'AFTERIMAGE' $names }
	elseif ($shader.StartsWith('datamosh')) { Add-Params 'DATAMOSH' $names }
	elseif ($shader.StartsWith('stop_motion')) { Add-Params 'STOP_MOTION' $names }
}
# registerScreenImagePost: SCREEN_IMAGE, empty Config names (written positionally).
if ($programs -match 'PROGRAMS\.put\(VFXEffectType\.SCREEN_IMAGE') { Add-Params 'SCREEN_IMAGE' @() }

# --- 2. the schema's name set per post type ------------------------------------------------------
$sources = Read-NoComments $sourcesPath
$schemaText = Read-NoComments $schemaPath
$schema = [ordered]@{}
foreach ($m in [regex]::Matches($sources, '(?s)params\.put\(VFXEffectType\.(\w+),\s*new String\[\]\{(.*?)\}\)')) {
	$schema[$m.Groups[1].Value] = @(Get-Strings $m.Groups[2].Value)
}

# --- 3. diff the two per-type name sets ----------------------------------------------------------
$allTypes = @($registered.Keys + $schema.Keys | Sort-Object -Unique)
foreach ($type in $allTypes) {
	$inShader = $registered.Contains($type)
	$inSchema = $schema.Contains($type)
	if (-not $inShader) { $problems.Add("$type is in PostParamSources but not registered in VFXShaderPrograms"); continue }
	if (-not $inSchema) { $problems.Add("$type is registered in VFXShaderPrograms but missing from PostParamSources"); continue }
	$shaderNames = @($registered[$type] | Sort-Object)
	$schemaNames = @($schema[$type] | Sort-Object)
	$missing = @($shaderNames | Where-Object { $schemaNames -notcontains $_ })
	$extra = @($schemaNames | Where-Object { $shaderNames -notcontains $_ })
	if ($missing.Count -gt 0) { $problems.Add("${type}: PostParamSources is missing [$($missing -join ', ')]") }
	if ($extra.Count -gt 0) { $problems.Add("${type}: PostParamSources has extra [$($extra -join ', ')]") }
}

# --- 4. every VFXEffectType enum constant is covered by the schema --------------------------------
$typeText = Read-NoComments $typePath
$enumConstants = @()
# Broad on purpose: any enum-body constant line, with or without a constructor argument, so a
# constant can never hide from the coverage check (the body constants are the only one-tab,
# uppercase-initial lines in VFXEffectType.java).
foreach ($m in [regex]::Matches($typeText, '(?m)^\t([A-Z][A-Z0-9_]*)\b')) { $enumConstants += $m.Groups[1].Value }
# Names the schema file itself registers (the non-post types), plus the post types it pulls in
# through PostParamSources.PARAMS. A PostParamSources key counts as covered only when
# VFXEffectSchema really consumes that map (registerBuiltIns iterates it), so a stray or broken
# entry in PostParamSources cannot be counted as a registration.
$schemaRefs = New-Object System.Collections.Generic.HashSet[string]
foreach ($m in [regex]::Matches($schemaText, 'VFXEffectType\.(\w+)')) { [void]$schemaRefs.Add($m.Groups[1].Value) }
$consumesSources = $schemaText -match 'PostParamSources\.PARAMS'
$postKeys = New-Object System.Collections.Generic.HashSet[string]
foreach ($k in $schema.Keys) { [void]$postKeys.Add($k) }
foreach ($constant in $enumConstants) {
	$covered = $schemaRefs.Contains($constant) -or ($consumesSources -and $postKeys.Contains($constant))
	if (-not $covered) { $problems.Add("VFXEffectType.$constant is not registered in the editor schema") }
}

Write-Host "Schema check"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "schema check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "schema check OK ($($allTypes.Count) types, $($enumConstants.Count) enum constants)"
exit 0

# Dev-only guard for the chain-resolution conversion passes (chain-resolution, Task 2).
#
# The contract (spec §4): the two conversion shaders sample at explicit texel centres so the result
# does not depend on the filter mode the cache binds. Down is the exact 2x2 box average
# (`floor(p - 0.5) + 0.5`, four fetches), Up is manual bilinear clamped to the last texel centre
# (`floor(p)`, four fetches, `min(` against `(InSize - 0.5) / InSize`). Neither uses textureLod,
# textureGrad, fwidth or textureSize: implicit LOD 0 only. Both carry a single pad-float Config, and
# the Java registry exposes both as `post/downsample` / `post/upsample`.
#
# This checks the shader text's shape and the Java registration only - Gradle does not compile GLSL
# and there is no GPU here, so nothing here claims the shaders compile.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-chainres-conversions.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$shaderDir = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post"
$downPath = Join-Path $shaderDir "downsample.fsh"
$upPath = Join-Path $shaderDir "upsample.fsh"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"

$problems = New-Object System.Collections.Generic.List[string]

function Get-Text([string]$path) {
	if (Test-Path -LiteralPath $path) { return [System.IO.File]::ReadAllText($path) }
	$problems.Add("missing file: $path")
	return ""
}

function Count-Text([string]$text, [string]$needle) {
	$count = 0
	$index = 0
	while (($index = $text.IndexOf($needle, $index)) -ge 0) { $count++; $index += $needle.Length }
	return $count
}

$down = Get-Text $downPath
$up = Get-Text $upPath
$programs = [System.IO.File]::ReadAllText($programsPath)

# Both shaders declare the single pad-float Config block (whitespace-tolerant).
$configPattern = 'layout\(std140\)\s+uniform\s+Config\s*\{\s*float\s+vfxPad\s*;\s*\}'

# A) downsample: the exact 2x2 box average, at explicit texel centres, four fetches.
if ($down -ne "") {
	if ($down -notmatch 'floor\(p - 0\.5\) \+ 0\.5') {
		$problems.Add("downsample.fsh lacks the exact box base 'floor(p - 0.5) + 0.5'")
	}
	if ($down -notmatch 'uniform sampler2D InSampler') {
		$problems.Add("downsample.fsh does not declare 'uniform sampler2D InSampler'")
	}
	$downFetches = Count-Text $down 'texture(InSampler'
	if ($downFetches -ne 4) {
		$problems.Add("downsample.fsh has $downFetches texture(InSampler fetches, want exactly 4")
	}
	if ($down -notmatch $configPattern) {
		$problems.Add("downsample.fsh lacks 'layout(std140) uniform Config { float vfxPad; }'")
	}
}

# B) upsample: manual bilinear at explicit texel centres, clamped to the last texel centre.
if ($up -ne "") {
	if ($up -notmatch 'floor\(p\)') {
		$problems.Add("upsample.fsh lacks the bilinear base 'floor(p)'")
	}
	if ($up -notmatch 'min\(' -or $up -notmatch '\(InSize - 0\.5\) / InSize') {
		$problems.Add("upsample.fsh lacks the edge clamp 'min(' against '(InSize - 0.5) / InSize'")
	}
	$upFetches = Count-Text $up 'texture(InSampler'
	if ($upFetches -ne 4) {
		$problems.Add("upsample.fsh has $upFetches texture(InSampler fetches, want exactly 4")
	}
	if ($up -notmatch $configPattern) {
		$problems.Add("upsample.fsh lacks 'layout(std140) uniform Config { float vfxPad; }'")
	}
}

# C) neither conversion uses an explicit LOD/gradient/derivative or a texture-size query.
foreach ($entry in @(@('downsample.fsh', $down), @('upsample.fsh', $up))) {
	foreach ($banned in @('textureLod', 'textureGrad', 'fwidth', 'textureSize')) {
		if ($entry[1] -match $banned) {
			$problems.Add("$($entry[0]) uses $banned, which the conversion passes must not")
		}
	}
}

# D) the Java registry exposes both programs and both shader locations.
foreach ($required in @('downsampleProgram()', 'upsampleProgram()', 'post/downsample', 'post/upsample')) {
	if ($programs -notmatch [regex]::Escape($required)) {
		$problems.Add("VFXShaderPrograms does not register '$required'")
	}
}

Write-Host "Chain-resolution conversions check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "chain-resolution conversions check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  downsample is the exact 2x2 box average, upsample the clamped manual bilinear; both are four-fetch, implicit-LOD-0, single-pad-Config; downsampleProgram()/upsampleProgram() register post/downsample and post/upsample"
Write-Host "Chain-resolution conversions check OK."
exit 0

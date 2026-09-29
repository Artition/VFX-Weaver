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

# E) the manager actually executes a half-resolution run (chain-resolution, Task 5).
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$manager = Get-Text $managerPath

function Find-Index([string]$text, [string]$needle) {
	if ($text -eq "") { return -1 }
	return $text.IndexOf($needle)
}

if ($manager -ne "") {
	# A) the chain-resolution switch gates everything.
	if ($manager -notmatch 'VFXChainResolution\.half\(\)') {
		$problems.Add("manager: does not consult VFXChainResolution.half()")
	}
	# B) the half pair is created lazily, only while the switch is on.
	if ($manager -notmatch [regex]::Escape('if (this.halfPingPong == null && VFXChainResolution.half()')) {
		$problems.Add("manager: no lazy half pair creation guarded by 'this.halfPingPong == null && VFXChainResolution.half()'")
	}
	if ($manager -notmatch 'Math\.max\(1, width >> 1\)' -or $manager -notmatch 'Math\.max\(1, height >> 1\)') {
		$problems.Add("manager: half pair is not sized 'Math.max(1, width >> 1)' / 'Math.max(1, height >> 1)'")
	}
	# C) the pair is destroyed on resize and when the scale returns to 1.0 (or nothing scalable).
	$destroyCount = Count-Text $manager 'this.destroyHalfTargets();'
	if ($destroyCount -lt 2) {
		$problems.Add("manager: destroyHalfTargets() is called $destroyCount time(s); expected the resize path and the scale-1.0/no-scalable path")
	}
	if ($manager -notmatch [regex]::Escape('!VFXChainResolution.half() || !anyScalable')) {
		$problems.Add("manager: the half pair is not destroyed when '!VFXChainResolution.half() || !anyScalable'")
	}
	# D) a run head's captureBefore copy runs from the full-resolution read before the downsample.
	$captureIndex = Find-Index $manager 'chain.get(entry).captureBefore()'
	$downIndex = Find-Index $manager 'this.pass(downInfo).execute'
	if ($captureIndex -lt 0) {
		$problems.Add("manager: no run-head 'chain.get(entry).captureBefore()' copy")
	} elseif ($downIndex -ge 0 -and $captureIndex -gt $downIndex) {
		$problems.Add("manager: the run head's captureBefore copy is not placed before the downsample")
	}
	# E) an execution failure poisons the run's composition for the session.
	if ($manager -notmatch 'poisonedScaledRuns\.add\(runKey\)' -or $manager -notmatch 'poisonedScaledRuns\.contains\(runKey\)') {
		$problems.Add("manager: execution failures do not poison the run composition (poisonedScaledRuns add/contains)")
	}
	if ($manager -notmatch 'post:scaled:') {
		$problems.Add("manager: a poisoned scaled run is not reported with a 'post:scaled:' warnOnce")
	}
	if ($manager -notmatch 'scaledRunKey') {
		$problems.Add("manager: no composition-based scaled-run key")
	}
	# F) pixel-unit parameters are divided by the run scale, only for the names the program lists.
	if ($manager -notmatch 'this\.pixelParams\.contains\(param\) \? raw / resScale : raw') {
		$problems.Add("manager: no 'this.pixelParams.contains(param) ? raw / resScale : raw' division")
	}
}

Write-Host "Chain-resolution conversions check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "chain-resolution conversions check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  downsample is the exact 2x2 box average, upsample the clamped manual bilinear; both are four-fetch, implicit-LOD-0, single-pad-Config; downsampleProgram()/upsampleProgram() register post/downsample and post/upsample"
Write-Host "  the manager creates the half pair only under VFXChainResolution.half(), destroys it on resize and at scale 1.0, captures the run head before the downsample, poisons a failed run by composition, and divides only the program's pixel parameters by resScale"
Write-Host "Chain-resolution conversions check OK."
exit 0

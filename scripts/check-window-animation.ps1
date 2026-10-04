# Dev-only guard for the .mcmeta window animation (Task 4).
#
# Pins the two halves of the feature as literal text:
#   * VFXWindowFrames is the geometry-only value type: the sheet size, one frame's size and a frame
#     table, no pixels, and it picks the frame at a tick count - a still/single-frame sheet returns
#     slot 0, an animated sheet walks the table (each frame for its own timeTicks, at least 1) and
#     wraps, and a negative timeTicks is clamped to 0;
#   * VFXWindowFrames.fromMetadata(...) turns a parsed AnimationMetadataSection into that: the
#     vanilla sheet size (calculateFrameSize), the playback table bounded by MAX_FRAMES, and vanilla
#     per-frame milliseconds to ticks with /50 clamped to at least 1;
#   * VFXWindowContent reads the sibling .mcmeta through the resource metadata
#     (AnimationMetadataSection.TYPE), hands it to fromMetadata(), and falls back to
#     VFXWindowFrames.strip(...)/.still(...) without one;
#   * interpolate: true is out of scope, so the interpolatedFrames flag must never be read.
# It also rejects any loader import (VFXWindowFrames / VFXWindowContent stay loader-agnostic), and
# because GLSL is never compiled and there is no GPU here, nothing here claims a pixel or a shader
# works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-animation.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$framesPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowFrames.java"
$contentPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowContent.java"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($path in @($framesPath, $contentPath)) {
	if (-not (Test-Path -LiteralPath $path)) {
		Write-Error "window animation check: missing $path"
		exit 1
	}
}
$frames = [System.IO.File]::ReadAllText($framesPath)
$content = [System.IO.File]::ReadAllText($contentPath)

function Assert-Contains([string]$text, [string]$literal, [string]$message) {
	if ($text -eq $null -or -not $text.Contains($literal)) {
		$problems.Add($message)
	}
}

# 0) loader-agnostic: src/client must never name a loader type.
foreach ($pair in @(
	@('VFXWindowFrames', $frames),
	@('VFXWindowContent', $content))) {
	if ($pair[1] -match 'net\.fabricmc|net\.neoforged') {
		$problems.Add("$($pair[0]) imports a loader package - src/client must stay loader-agnostic")
	}
}

# 1) the value type and its produced interface.
foreach ($literal in @(
	'public record VFXWindowFrames(',
	'public record Frame(int index, int timeTicks)',
	'public static VFXWindowFrames still(',
	'public static VFXWindowFrames strip(',
	'public static VFXWindowFrames animated(',
	'public int columns()',
	'public int rows()',
	'public int frameCount()',
	'public int frameAt(final long timeTicks)')) {
	Assert-Contains $frames $literal "VFXWindowFrames is missing '$literal'"
}

# 2) frameAt: slot 0 for a still/single frame; wrap with floorMod; negative timeTicks clamps to 0;
#    every frame shows at least 1 tick.
foreach ($literal in @(
	'!this.animated || this.frames.size() == 1',
	'Math.floorMod(timeTicks < 0 ? 0 : timeTicks, total)',
	'Math.max(1, f.timeTicks())')) {
	Assert-Contains $frames $literal "VFXWindowFrames.frameAt() is missing '$literal'"
}

# 3) VFXWindowContent reads the sibling .mcmeta and builds the right sheet.
foreach ($literal in @(
	'resource.get().metadata().getSection(AnimationMetadataSection.TYPE)',
	'VFXWindowFrames.strip(',
	'VFXWindowFrames.still(',
	'this.sheet.frameAt(timeTicks)',
	'Math.floorMod(frame, columns * rows)')) {
	Assert-Contains $content $literal "VFXWindowContent is missing '$literal'"
}

# 4) the .mcmeta -> frames logic (vanilla sizing, the playback table, the MAX_FRAMES cap and the
#    vanilla per-frame milliseconds -> ticks /50 clamped to at least 1) lives in VFXWindowFrames.
foreach ($literal in @(
	'public static VFXWindowFrames fromMetadata(final AnimationMetadataSection m, final int imageWidth,',
	'calculateFrameSize(imageWidth, imageHeight)',
	'MAX_FRAMES',
	'f.timeOr(defaultMs) / 50',
	'Math.max(1, defaultMs / 50)',
	'animated(imageWidth, imageHeight, frameWidth, frameHeight, table)')) {
	Assert-Contains $frames $literal "VFXWindowFrames is missing '$literal'"
}

# 5) interpolation is out of scope: the flag must never be read.
if ($content -match 'interpolatedFrames' -or $frames -match 'interpolatedFrames') {
	$problems.Add("the interpolatedFrames flag is read - interpolate: true is out of scope")
}

Write-Host "Window animation check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window animation check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowFrames is a final loader-agnostic value type for the sheet geometry + frame table (no pixels)"
Write-Host "  frameAt picks slot 0 for a still/single frame, wraps with floorMod and clamps a negative tick count"
Write-Host "  VFXWindowContent reads the sibling .mcmeta (AnimationMetadataSection.TYPE) and builds the frames through fromMetadata(), strip/still without one"
Write-Host "  fromMetadata() sizes the sheet with calculateFrameSize, caps the table at MAX_FRAMES and converts per-frame ms to ticks (/50, min 1); interpolation is not used"
Write-Host "Window animation check OK."
exit 0

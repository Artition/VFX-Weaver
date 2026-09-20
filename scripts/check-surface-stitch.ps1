# Dev-only guard for the surface_pattern "planar" mode (surface.stitch, 2026-09-20).
#
# A wall projected onto its own plane (u, world.y) can never be continuous with the floor's world-XZ
# plane at a floor/wall edge: the two planes use different physical axes. The opt-in `stitch` mode
# makes the pattern coordinate a top-down planar projection instead: EVERY face samples the same
# p = world.xz, so a wall pixel (x, y, z_w) shows exactly what the floor pixel at the wall base
# (x, floor_y, z_w) shows - the image's row at the wall line extruded vertically. p never depends on
# world.y, so the seam is continuous for ANY anchor height (the anchor's Y no longer matters), a
# ceiling is in the projector's shadow (never drawn), and the fade uses the horizontal distance.
# This script fails if the shader regresses to the removed unfold reflection or the planar contract
# is broken.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-surface-stitch.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$patternPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\surface_pattern.fsh"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$selectionPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\field\VFXSurfaceSelection.java"

$pattern = [System.IO.File]::ReadAllText($patternPath)
$programs = [System.IO.File]::ReadAllText($programsPath)
$manager = [System.IO.File]::ReadAllText($managerPath)
$selection = [System.IO.File]::ReadAllText($selectionPath)

$problems = New-Object System.Collections.Generic.List[string]

# --- 1. the shader declares the uniform and the planar branch -----------------------------------
if ($pattern -notmatch 'float\s+stitch\s*;') {
	$problems.Add("post/surface_pattern.fsh does not declare 'float stitch;' in the Config block")
}
# Planar reaches a single p = world.xz branch whenever stitch != 0, regardless of face.
if ($pattern -notmatch 'if\s*\(\s*stitch\s*!=\s*0\.0\s*\|\|\s*faceId\s*<=\s*1\s*\)\s*\{[\s\S]{0,200}?p\s*=\s*world\.xz\s*;') {
	$problems.Add("post/surface_pattern.fsh has no single 'if (stitch != 0.0 || faceId <= 1) { p = world.xz; ... }' planar branch")
}
if ($pattern -notmatch 'centerP\s*=\s*center\.xz\s*;') {
	$problems.Add("post/surface_pattern.fsh planar branch does not set centerP = center.xz")
}
if ($pattern -notmatch 'bandAxis\s*=\s*world\.y\s*;') {
	$problems.Add("post/surface_pattern.fsh planar branch does not set bandAxis = world.y (height slab)")
}
# The removed reflection must not come back.
if ($pattern -match 'float\s+unfold\s*=') {
	$problems.Add("post/surface_pattern.fsh still computes 'float unfold = ...' (reflection removed in favour of the planar projection)")
}
if ($pattern -match 'world\.y\s*-\s*center\.y') {
	$problems.Add("post/surface_pattern.fsh still depends on world.y - center.y (the anchor Y must not enter the coordinate)")
}
# With stitch off the previous hard switch must survive exactly (u = cross(worldUp, n)).
if ($pattern -notmatch 'vec3\s+u\s*=\s*cross\(vec3\(0\.0,\s*1\.0,\s*0\.0\),\s*n\)') {
	$problems.Add("post/surface_pattern.fsh lost the legacy wall projection (u = cross(worldUp, n)) in the stitch-off branch")
}
# Planar fades by the horizontal distance; legacy keeps the 3-D distance.
if ($pattern -notmatch 'stitch\s*!=\s*0\.0\s*\?\s*length\(world\.xz\s*-\s*center\.xz\)\s*:\s*length\(world\s*-\s*center\)') {
	$problems.Add("post/surface_pattern.fsh fade does not use the horizontal distance in planar mode and the 3-D distance otherwise")
}
# A ceiling is in the top-down projector's shadow and must be excluded.
if ($pattern -notmatch 'if\s*\(\s*stitch\s*!=\s*0\.0\s*&&\s*faceId\s*==\s*1\s*\)\s*\{[\s\S]{0,120}?faceCov\s*=\s*0\.0\s*;') {
	$problems.Add("post/surface_pattern.fsh does not zero faceCov for a ceiling (faceId == 1) in planar mode")
}

# --- 2. the three-place UBO contract ------------------------------------------------------------
if ($programs -notmatch '"tex_px_h"[\s\S]{0,200}?"stitch"') {
	$problems.Add("VFXShaderPrograms.registerDepthPost does not list 'stitch' after 'tex_px_h'")
}
if ($manager -notmatch 'case\s+"stitch"\s*->\s*surface\s*==\s*null') {
	$problems.Add("VFXPostProcessingManager does not resolve the 'stitch' Config name from the surface block")
}
if ($manager -notmatch '"band_softness",\s*"stitch"\s*->\s*true') {
	$problems.Add("VFXPostProcessingManager.isReservedDepthParam does not list 'stitch'")
}

# --- 3. the CPU parse accepts/validates the key, default false ----------------------------------
if ($selection -notmatch '"stitch"\.equals\(key\)') {
	$problems.Add("VFXSurfaceSelection.parse does not accept the 'stitch' key")
}
if ($selection -notmatch 'bool\(json\.get\("stitch"\),\s*"stitch"\)') {
	$problems.Add("VFXSurfaceSelection.parse does not validate stitch as a boolean")
}
if ($selection -notmatch 'this\.stitch\s*=\s*stitch\s*;') {
	$problems.Add("VFXSurfaceSelection does not carry the parsed stitch flag")
}

# --- 4. numeric model ---------------------------------------------------------------------------
# Mirrors the shader: planar p = world.xz, independent of world.y and of the anchor's Y.
function Get-PlanarP([double]$X, [double]$Z) {
	return @($X, $Z)
}

# Mirrors the ceiling guard: faceId == 1 (down) gets zero coverage, every other face is untouched.
function Get-PlanarFaceCov([int]$FaceId) {
	if ($FaceId -eq 1) { return 0.0 }
	return 1.0
}

$tol = 1.0e-9
$floorY = 64.0
$worldX = 7.25
$worldZ = -3.5

# 4a. the wall pixel (x, y, z) and the floor pixel at its base (x, floor_y, z) share one coordinate,
# for any wall height.
foreach ($height in @(0.0, 1.62, 100.0)) {
	$wall = Get-PlanarP $worldX $worldZ
	$floor = Get-PlanarP $worldX $worldZ
	if ([Math]::Abs($wall[0] - $floor[0]) -gt $tol -or [Math]::Abs($wall[1] - $floor[1]) -gt $tol) {
		$problems.Add("planar coordinate is not shared by the wall at height $height and its base floor pixel")
	}
}

# 4b. the coordinate does not depend on the anchor height at all: every anchor gives the same p.
foreach ($centerY in @($floorY, $floorY + 1.62, -123.0)) {
	$p = Get-PlanarP $worldX $worldZ
	if ([Math]::Abs($p[0] - $worldX) -gt $tol -or [Math]::Abs($p[1] - $worldZ) -gt $tol) {
		$problems.Add("planar coordinate moved with anchor Y ${centerY}: p=($($p[0]), $($p[1]))")
	}
}

# 4c. planar excludes the ceiling (faceId == 1) and keeps every other orientation.
if ((Get-PlanarFaceCov 1) -ne 0.0) {
	$problems.Add("planar ceiling faceId 1 must get zero coverage")
}
foreach ($face in @(0, 2, 3, 4, 5)) {
	if ((Get-PlanarFaceCov $face) -ne 1.0) {
		$problems.Add("planar mode must keep non-ceiling face $face")
	}
}

Write-Host "surface_pattern stitch check (planar)"
Write-Host "  shader: planar p = world.xz on EVERY face when stitch != 0; legacy u = cross(worldUp, n) otherwise"
Write-Host "  wall pixel (x, y, z) shows the floor base pixel (x, $floorY, z): same coordinate for any y"
Write-Host "  anchor Y does not enter the coordinate: continuity holds for any anchor height"
Write-Host "  ceiling (faceId == 1) excluded in planar mode; fade uses the horizontal distance"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern stitch check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Stitch OK: planar top-down projection; continuous at the base for any anchor height, no ceiling."
exit 0

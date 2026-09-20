# Dev-only guard for the surface_pattern "floor-anchored unfolding" mode (surface.stitch, 2026-09-20).
#
# A wall projected onto its own plane (u, world.y) can never be continuous with the floor's world-XZ
# plane at a floor/wall edge: the two planes use different physical axes. The opt-in `stitch` mode
# unfolds the wall into the floor plane instead: unfold = world.y - center.y and
#   north (-Z): p = (x, z + unfold)   south (+Z): p = (x, z - unfold)
#   west  (-X): p = (x + unfold, z)   east  (+X): p = (x - unfold, z)
# with centerP = center.xz in every case. At a wall base with the anchor at floor level the unfolded
# coordinate equals the floor coordinate at the same world XZ (continuity); the unfold always points
# away from the viewer, into the wall, beyond the visible floor (so the figure is not double-painted
# on the visible floor). This script fails if the shader formula regresses or the numeric model no
# longer holds.
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

# --- 1. the shader declares the uniform and the unfold branch, with the four formulas ------------
if ($pattern -notmatch 'float\s+stitch\s*;') {
	$problems.Add("post/surface_pattern.fsh does not declare 'float stitch;' in the Config block")
}
if ($pattern -notmatch 'float\s+unfold\s*=\s*world\.y\s*-\s*center\.y\s*;') {
	$problems.Add("post/surface_pattern.fsh does not compute 'float unfold = world.y - center.y'")
}
if ($pattern -notmatch 'stitch\s*!=\s*0\.0') {
	$problems.Add("post/surface_pattern.fsh does not branch on 'stitch != 0.0'")
}
foreach ($formula in @(
		'vec2\(\s*world\.x\s*,\s*world\.z\s*\+\s*unfold\s*\)',
		'vec2\(\s*world\.x\s*,\s*world\.z\s*-\s*unfold\s*\)',
		'vec2\(\s*world\.x\s*\+\s*unfold\s*,\s*world\.z\s*\)',
		'vec2\(\s*world\.x\s*-\s*unfold\s*,\s*world\.z\s*\)')) {
	if ($pattern -notmatch $formula) {
		$problems.Add("post/surface_pattern.fsh is missing the unfold formula $formula")
	}
}
# With stitch off the previous hard switch must survive exactly (u = cross(worldUp, n)).
if ($pattern -notmatch 'vec3\s+u\s*=\s*cross\(vec3\(0\.0,\s*1\.0,\s*0\.0\),\s*n\)') {
	$problems.Add("post/surface_pattern.fsh lost the legacy wall projection (u = cross(worldUp, n)) in the stitch-off branch")
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
# Mirrors the shader branch: returns the unfolded in-plane coordinate for a wall fragment.
function Get-UnfoldP([int]$FaceId, [double]$X, [double]$Y, [double]$Z, [double]$Cx, [double]$Cy) {
	$u = $Y - $Cy
	switch ($FaceId) {
		2 { return @($X, ($Z + $u)) }   # north (-Z)
		3 { return @($X, ($Z - $u)) }   # south (+Z)
		4 { return @(($X + $u), $Z) }   # west (-X)
		5 { return @(($X - $u), $Z) }   # east (+X)
		default { throw "Get-UnfoldP: not a wall face id: $FaceId" }
	}
}

$tol = 1.0e-9
$floorY = 64.0
$worldX = 7.25
$worldZ = -3.5
# Anchor at floor level: center.y == floor_y.
$centerY = $floorY

# 4a. continuity at the wall base: unfolded coordinate == floor coordinate (x, z) at the same XZ.
foreach ($face in @(2, 3, 4, 5)) {
	$p = Get-UnfoldP $face $worldX $floorY $worldZ 0.0 $centerY
	if ([Math]::Abs($p[0] - $worldX) -gt $tol -or [Math]::Abs($p[1] - $worldZ) -gt $tol) {
		$problems.Add("stitch continuity broken on face $face at the base: p=($($p[0]), $($p[1])) != floor ($worldX, $worldZ)")
	}
}

# 4b. the unfold direction points away from the viewer for all four wall signs (h above the base).
$h = 2.0
# Each entry: face, viewer side, the axis the unfold moves, and the sign of that movement.
foreach ($case in @(
		@{ Face = 2; Name = "north (-Z)"; Axis = 1; Sign = +1 },  # away = +Z
		@{ Face = 3; Name = "south (+Z)"; Axis = 1; Sign = -1 },  # away = -Z
		@{ Face = 4; Name = "west (-X)";  Axis = 0; Sign = +1 },  # away = +X
		@{ Face = 5; Name = "east (+X)";  Axis = 0; Sign = -1 })) { # away = -X
	$p = Get-UnfoldP $case.Face $worldX ($floorY + $h) $worldZ 0.0 $centerY
	$delta = $p[$case.Axis] - $worldZ
	if ($case.Axis -eq 0) { $delta = $p[0] - $worldX }
	if ([Math]::Abs($delta - $case.Sign * $h) -gt $tol) {
		$problems.Add("stitch unfold on $($case.Name) does not move $($case.Sign*$h) along axis $($case.Axis): delta=$delta")
	}
}

# 4c. documented limitation: an anchor above the floor shifts the wall by floor_y - center.y.
$eyeY = $floorY + 1.62
$shifted = Get-UnfoldP 2 $worldX $floorY $worldZ 0.0 $eyeY
$shift = ($worldZ + ($floorY - $eyeY)) - $worldZ
if ([Math]::Abs($shift - ($floorY - $eyeY)) -gt $tol -or [Math]::Abs($shift) -lt 1.0) {
	$problems.Add("stitch limitation model wrong: an eye-level anchor must shift the wall by floor_y - center.y = $($floorY - $eyeY), got $shift")
}

Write-Host "surface_pattern stitch check"
Write-Host "  shader: unfold = world.y - center.y; north/south keep X and offset Z, west/east keep Z and offset X"
Write-Host "  base continuity (anchor at floor $floorY): all four walls == floor coordinate at the same XZ"
Write-Host "  unfold direction: north +Z, south -Z, west +X, east -X (away from the viewer)"
Write-Host "  eye-anchor ($eyeY) seam shift: $($floorY - $eyeY) blocks (documented limitation)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern stitch check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Stitch OK: the wall unfolds into the floor plane, continuous at the base with a floor-level anchor."
exit 0

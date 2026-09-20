# Dev-only guard for the surface_pattern band edge (2026-09-20).
#
# A surface lying exactly on a band min/max bound used to shimmer: the depth-reconstructed axis
# coordinate (camera.glsl `world.xyz / world.w`) is not bit-exact, so the hard inclusive test
# `bandAxis >= band_min && bandAxis <= band_max` flipped 0/1 from pixel to pixel. The band edge
# must fade over `band_softness` (world blocks, default 0 = the exact hard test), guarded for the
# ±1e30 "unbounded" sentinel where the two smoothstep edges collapse to one value.
#
# Usage: powershell -File scripts/check-band-edge.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the recipe is intact.
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

# 1. The shader declares the soft band edge and fades both bounds over it.
if ($pattern -notmatch 'float\s+band_softness\s*;') {
	$problems.Add("post/surface_pattern.fsh does not declare 'float band_softness;' in the Config block")
}
foreach ($needle in @(
		'smoothstep\s*\(\s*band_min\s*-\s*band_softness\s*,\s*band_min\s*\+\s*band_softness',
		'smoothstep\s*\(\s*band_max\s*-\s*band_softness\s*,\s*band_max\s*\+\s*band_softness')) {
	if ($pattern -notmatch $needle) {
		$problems.Add("post/surface_pattern.fsh is missing the soft band edge $needle")
	}
}

# 2. The ±1e30 sentinel must be guarded, or the smoothstep edges collapse (undefined result).
if ($pattern -notmatch 'band_min\s*>\s*-1\.0e29') {
	$problems.Add("post/surface_pattern.fsh does not guard the unbounded lower sentinel (band_min > -1.0e29)")
}
if ($pattern -notmatch 'band_max\s*<\s*1\.0e29') {
	$problems.Add("post/surface_pattern.fsh does not guard the unbounded upper sentinel (band_max < 1.0e29)")
}

# 3. The hard inclusive test must survive as the band_softness == 0 fallback.
if ($pattern -notmatch 'bandAxis\s*>=\s*band_min\s*&&\s*bandAxis\s*<=\s*band_max') {
	$problems.Add("post/surface_pattern.fsh lost the hard inclusive fallback test")
}
if ($pattern -notmatch 'band_softness\s*>\s*0\.0') {
	$problems.Add("post/surface_pattern.fsh does not branch on band_softness > 0.0")
}

# 4. The three-place UBO contract: the name must be in registerDepthPost after band_max, and the
#    manager must resolve/whitelist it.
if ($programs -notmatch '"band_max"\s*,\s*"band_softness"') {
	$problems.Add("VFXShaderPrograms.registerDepthPost does not list 'band_softness' after 'band_max'")
}
if ($manager -notmatch 'case\s+"band_softness"') {
	$problems.Add("VFXPostProcessingManager does not resolve the 'band_softness' Config name")
}
if ($manager -notmatch '"band_softness"\s*->\s*true') {
	$problems.Add("VFXPostProcessingManager.isReservedDepthParam does not list 'band_softness'")
}

# 5. The CPU parse validates the key and bounds it.
if ($selection -notmatch '"band_softness"\.equals\(key\)') {
	$problems.Add("VFXSurfaceSelection.parse does not accept the 'band_softness' key")
}
if ($selection -notmatch 'MAX_BAND_SOFTNESS') {
	$problems.Add("VFXSurfaceSelection does not bound band_softness with MAX_BAND_SOFTNESS")
}

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern band-edge check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "surface_pattern band edge OK: soft fade over band_softness, sentinel-guarded, hard fallback at 0."
exit 0

# Dev-only guard for the single-tile coverage mask of the textured surface_pattern (2026-09-20).
#
# A texture-only pattern has no figure to bound it: with repeat <= 1 on both axes vfx_shape_cell
# skips its fract and returns an unbounded coordinate, and vfx_texture_sheet_uv clamps it, so the
# cell's edge texel smears across the whole surface (the owner's "huge stretched texture"). The
# consumer must bound its own tile: vfx_pattern_tile_coverage fades out over `softness` outside the
# [0,1] tile from the RAW coordinate, and uv is clamped before the preserve aspect / half-texel
# inset / sample. Both halves are required: the clamp alone leaves the edge texel at full alpha, the
# mask alone lets the smeared edge sit inside the fade band. The mask must reach the final coverage.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-pattern-tile-coverage.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$patternPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\surface_pattern.fsh"
$shapePath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\shape.glsl"

$pattern = [System.IO.File]::ReadAllText($patternPath)
$shape = [System.IO.File]::ReadAllText($shapePath)

$problems = New-Object System.Collections.Generic.List[string]

# --- structural: the mask exists and multiplies the texture coverage ----------------------------
if ($pattern -notmatch 'float\s+vfx_pattern_tile_coverage\s*\(\s*vec2\s+uv,\s*float\s+softness\s*\)') {
	$problems.Add("surface_pattern.fsh has no vfx_pattern_tile_coverage(vec2, float) helper")
}
if ($pattern -notmatch 'vec2\s+outside\s*=\s*max\(abs\(uv - vec2\(0\.5\)\) - vec2\(0\.5\), vec2\(0\.0\)\)') {
	$problems.Add("vfx_pattern_tile_coverage does not measure distance outside the [0,1] tile")
}
if ($pattern -notmatch '1\.0 - clamp\(max\(outside\.x, outside\.y\) / max\(softness, 1\.0e-4\), 0\.0, 1\.0\)') {
	$problems.Add("vfx_pattern_tile_coverage does not fade out over softness")
}
# the single-tile branch computes the mask from the raw uv and then clamps it
if ($pattern -notmatch 'if \(repeat_x <= 1\.0 && repeat_y <= 1\.0\) \{\s*tileCov\s*=\s*vfx_pattern_tile_coverage\(uv, softness\);\s*uv = clamp\(uv, 0\.0, 1\.0\);') {
	$problems.Add("the texture branch does not compute tileCov from the raw uv and clamp a single tile together")
}
# the mask multiplies the channel coverage before it becomes body coverage
if ($pattern -notmatch 'texCoverage = clamp\(vfx_texture_channel\(texel, int\(tex_channel \+ 0\.5\)\), 0\.0, 1\.0\);\s*texCoverage \*= tileCov;') {
	$problems.Add("the texture branch does not multiply texCoverage by tileCov after the channel read")
}
# order: tile coverage + clamp must precede the preserve aspect step (the tile is the whole cell)
$iMask = $pattern.IndexOf('tileCov = vfx_pattern_tile_coverage(')
$iClamp = $pattern.IndexOf('uv = clamp(uv, 0.0, 1.0);')
$iAspect = $pattern.IndexOf('vfx_texture_aspect(uv,')
if ($iMask -lt 0 -or $iClamp -lt 0 -or $iAspect -lt 0 -or -not ($iMask -lt $iClamp -and $iClamp -lt $iAspect)) {
	$problems.Add("order is wrong: tile coverage + clamp must come before the preserve aspect step")
}
# the shared shape include keeps its unbounded-coordinate contract (the fix is consumer-owned)
if ($shape -notmatch 'UNBOUNDED') {
	$problems.Add("shape.glsl does not document the unbounded repeat <= 1 coordinate")
}
if ($shape -notmatch 'if \(repeat\.x > 1\.0 \|\| repeat\.y > 1\.0\) \{\s*local = fract\(local \* repeat\) - 0\.5;') {
	$problems.Add("vfx_shape_cell's body changed (the fix must stay consumer-owned)")
}

# --- numeric: mirror the helper and assert the mask shape ---------------------------------------
function Tile-Coverage([double]$u, [double]$v, [double]$softness) {
	$ox = [Math]::Max([Math]::Abs($u - 0.5) - 0.5, 0.0)
	$oy = [Math]::Max([Math]::Abs($v - 0.5) - 0.5, 0.0)
	return 1.0 - [Math]::Min([Math]::Max([Math]::Max($ox, $oy) / [Math]::Max($softness, 1.0e-4), 0.0), 1.0)
}

$tol = 1.0e-9
# inside the tile -> full coverage; far outside -> zero; a point `softness` outside -> ~0
foreach ($inside in @(@(0.0, 0.0), @(0.5, 0.5), @(1.0, 1.0))) {
	if ([Math]::Abs((Tile-Coverage $inside[0] $inside[1] 0.1) - 1.0) -gt $tol) {
		$problems.Add("tile coverage at ($($inside[0]),$($inside[1])) is not 1 inside the tile")
	}
}
foreach ($outside in @(@(-5.0, 0.5), @(2.0, 2.0), @(0.5, 100.0))) {
	if ([Math]::Abs((Tile-Coverage $outside[0] $outside[1] 0.1)) -gt $tol) {
		$problems.Add("tile coverage at ($($outside[0]),$($outside[1])) is not 0 far outside the tile")
	}
}
if ([Math]::Abs((Tile-Coverage 1.1 0.5 0.1) - 0.0) -gt $tol) {
	$problems.Add("tile coverage does not fall to 0 at one softness outside the tile")
}
if ([Math]::Abs((Tile-Coverage 1.05 0.5 0.1) - 0.5) -gt $tol) {
	$problems.Add("tile coverage is not linear across softness at half a softness outside the tile")
}

# the clamp drops the smeared coordinate back into the sampled range
foreach ($far in @(@(-30.0, 12.0), @(4.0, -9.0))) {
	$cu = [Math]::Min([Math]::Max($far[0], 0.0), 1.0)
	$cv = [Math]::Min([Math]::Max($far[1], 0.0), 1.0)
	if ($cu -lt 0.0 -or $cu -gt 1.0 -or $cv -lt 0.0 -or $cv -gt 1.0) {
		$problems.Add("clamp did not keep ($($far[0]),$($far[1])) in [0,1]")
	}
}

Write-Host "Pattern single-tile coverage check"
Write-Host "  mask: 1 inside [0,1], 0 at one softness outside, clamp keeps the sample in range"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "pattern single-tile coverage check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Single-tile coverage OK: raw tile mask, clamp before aspect, mask multiplies coverage."
exit 0

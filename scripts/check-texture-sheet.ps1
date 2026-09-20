# Dev-only guard for the sprite-sheet cell UV maths (2026-09-20).
#
# The shared include assets/vfxweaver/shaders/include/texture.glsl maps a 0..1 cell coordinate into
# one cell of a `sheet: [cols, rows]` grid. Two properties matter and both are easy to break:
#   * the frame is ROW-MAJOR (frame 0 = top-left, frame 1 = the cell to its right) and WRAPPED into
#     [0, cols*rows) — a runaway graph `frame` must wrap, not index out of range;
#   * each cell is INSET by half a texel (0.5 / sprite pixel size) so a filtered sample of a cell
#     edge cannot bleed into the neighbouring cell (the owner's cross-shaped artefact).
# The function below mirrors texture.glsl's vfx_texture_sheet_uv token for token; the structural
# assertions fail if the GLSL changes shape (so the mirror cannot silently drift). Verified: for
# every frame of a 4x4 sheet on a 64x64 texture, the cell rect is exact and sits exactly half a
# texel inside its cell on all four edges.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-texture-sheet.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$texturePath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\texture.glsl"
$patternPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\surface_pattern.fsh"
$fieldPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\field.glsl"

$texture = [System.IO.File]::ReadAllText($texturePath)
$pattern = [System.IO.File]::ReadAllText($patternPath)
$field = [System.IO.File]::ReadAllText($fieldPath)

$problems = New-Object System.Collections.Generic.List[string]

# --- structural: the include must keep the row-major + wrap + inset shape ----------------------
if ($texture -notmatch 'mod\(floor\(frame \+ 0\.5\), cells\)') {
	$problems.Add("texture.glsl does not round+wrap the frame with mod(floor(frame + 0.5), cells)")
}
if ($texture -notmatch 'mod\(idx, c\.x\), floor\(idx / c\.x\)') {
	$problems.Add("texture.glsl no longer computes the cell row-major as (mod(idx, cols), floor(idx / cols))")
}
if ($texture -notmatch 'min\(halfTexel \* c, vec2\(0\.49\)\)') {
	$problems.Add("texture.glsl lost the half-texel inset (min(halfTexel * c, vec2(0.49)))")
}
if ($texture -notmatch 'vec4\s+vfx_texture_sample\s*\(\s*sampler2D\s+tex,\s*vec2\s+uv,\s*vec4\s+rect,\s*vec2\s+sheet,\s*float\s+frame,\s*vec2\s+halfTexel\s*\)') {
	$problems.Add("vfx_texture_sample does not take the halfTexel inset argument")
}
if ($field -notmatch 'vfx_texture_sample\(fld_tex0,[\s\S]{0,200}?vec2\(0\.0\)\)') {
	$problems.Add("field.glsl no longer passes vec2(0.0) (the field texture must keep its legacy no-inset sample)")
}
if ($pattern -notmatch 'vec2 halfTexel = vec2\(0\.5\) / max\(vec2\(tex_px_w, tex_px_h\), vec2\(1\.0\)\)') {
	$problems.Add("surface_pattern.fsh does not derive halfTexel from tex_px_w/tex_px_h")
}
if ($pattern -notmatch 'vfx_texture_aspect\(uv, tex_aspect \* \(tex_rows / max\(tex_cols, 1\.0\)\)\)') {
	$problems.Add("surface_pattern.fsh does not use the *cell* aspect (tex_aspect * rows / cols) under preserve")
}

# --- numeric: mirror the GLSL and assert exact, half-texel-inset cell rects --------------------
function Mod-Positive([double]$x, [double]$y) { return (($x % $y) + $y) % $y }

function Sheet-Uv([double]$u, [double]$v, [double[]]$sheet, [double]$frame, [double[]]$halfTexel) {
	$cx = [Math]::Max($sheet[0], 1.0)
	$cy = [Math]::Max($sheet[1], 1.0)
	$cells = $cx * $cy
	$insetX = [Math]::Min($halfTexel[0] * $cx, 0.49)
	$insetY = [Math]::Min($halfTexel[1] * $cy, 0.49)
	$fx = [Math]::Min([Math]::Max($u, 0.0), 1.0) * (1.0 - 2.0 * $insetX) + $insetX
	$fy = [Math]::Min([Math]::Max($v, 0.0), 1.0) * (1.0 - 2.0 * $insetY) + $insetY
	if ($cells -le 1.0) { return @($fx, $fy) }
	$idx = Mod-Positive ([Math]::Floor($frame + 0.5)) $cells
	$cellX = Mod-Positive $idx $cx
	$cellY = [Math]::Floor($idx / $cx)
	return @((($fx + $cellX) / $cx), (($fy + $cellY) / $cy))
}

$sheet = @(4.0, 4.0)
$px = @(64.0, 64.0)
$halfTexel = @((0.5 / ($px[0])), (0.5 / ($px[1])))
$tol = 1.0e-9
$frames = [int]($sheet[0] * $sheet[1])
for ($f = 0; $f -lt $frames; $f++) {
	$cellX = $f % [int]$sheet[0]
	$cellY = [Math]::Floor($f / $sheet[0])
	$lo = Sheet-Uv 0.0 0.0 $sheet $f $halfTexel
	$hi = Sheet-Uv 1.0 1.0 $sheet $f $halfTexel
	# exact cell bounds, then inset by half a texel on every edge
	$exactU0 = $cellX / ($sheet[0])
	$exactV0 = $cellY / ($sheet[1])
	$exactU1 = ($cellX + 1) / ($sheet[0])
	$exactV1 = ($cellY + 1) / ($sheet[1])
	if ([Math]::Abs($lo[0] - ($exactU0 + $halfTexel[0])) -gt $tol) { $problems.Add("frame ${f}: u0 $($lo[0]) is not the cell's left edge + half a texel") }
	if ([Math]::Abs($lo[1] - ($exactV0 + $halfTexel[1])) -gt $tol) { $problems.Add("frame ${f}: v0 $($lo[1]) is not the cell's top edge + half a texel") }
	if ([Math]::Abs($hi[0] - ($exactU1 - $halfTexel[0])) -gt $tol) { $problems.Add("frame ${f}: u1 $($hi[0]) is not the cell's right edge - half a texel") }
	if ([Math]::Abs($hi[1] - ($exactV1 - $halfTexel[1])) -gt $tol) { $problems.Add("frame ${f}: v1 $($hi[1]) is not the cell's bottom edge - half a texel") }
}

# frame wrap: exact multiples stay, out-of-range (incl. negative) wraps
foreach ($case in @(@(-1.0, 15.0), @(16.0, 0.0), @(17.0, 1.0))) {
	$wrapped = Sheet-Uv 0.0 0.0 $sheet $case[0] $halfTexel
	$expected = Sheet-Uv 0.0 0.0 $sheet $case[1] $halfTexel
	if ([Math]::Abs($wrapped[0] - $expected[0]) -gt $tol -or [Math]::Abs($wrapped[1] - $expected[1]) -gt $tol) {
		$problems.Add("frame $($case[0]) does not wrap to frame $($case[1])")
	}
}

# a non-square cell must be inset on both axes independently
$tall = @(2.0, 8.0)
$halfTall = @((0.5 / 32.0), (0.5 / 256.0))
$lo2 = Sheet-Uv 0.0 0.0 $tall 0.0 $halfTall
$hi2 = Sheet-Uv 1.0 1.0 $tall 0.0 $halfTall
if ([Math]::Abs($lo2[0] - (0.0 + $halfTall[0])) -gt $tol -or [Math]::Abs($hi2[0] - (0.5 - $halfTall[0])) -gt $tol) {
	$problems.Add("a 2x8 sheet cell is not inset independently on the u axis")
}
if ([Math]::Abs($lo2[1] - (0.0 + $halfTall[1])) -gt $tol -or [Math]::Abs($hi2[1] - (0.125 - $halfTall[1])) -gt $tol) {
	$problems.Add("a 2x8 sheet cell is not inset independently on the v axis")
}

Write-Host "Sprite-sheet cell UV check"
Write-Host "  4x4 sheet on a 64x64 texture: $frames frames exact + half-texel inset; wrap and non-square cells checked"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "sprite-sheet cell check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Sheet cell UVs OK: row-major, wrapped, exact, and inset by half a texel on every edge."
exit 0

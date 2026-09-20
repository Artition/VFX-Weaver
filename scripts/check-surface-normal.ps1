# Dev-only guard for the surface_pattern normal fix (2026-09-20).
#
# The normal used to come from screen-space derivatives (dFdx/dFdy) of the depth-reconstructed
# world position. Those are unstable at grazing angles and, at a face edge or silhouette, mix two
# surfaces (or the sky's far-plane position), so the floor pattern leaked onto neighbouring faces,
# walls went noisy and the wall basis/band flipped. The normal must instead come from neighbouring
# depth taps and be snapped to the nearest world axis before face/plane/band selection.
#
# Usage: powershell -File scripts/check-surface-normal.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the recipe is intact.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$shaderRoot = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders"
$patternPath = Join-Path $shaderRoot "post\surface_pattern.fsh"
$cameraPath = Join-Path $shaderRoot "include\camera.glsl"
$fieldPath = Join-Path $shaderRoot "include\field.glsl"

$pattern = [System.IO.File]::ReadAllText($patternPath)
$camera = [System.IO.File]::ReadAllText($cameraPath)
$field = [System.IO.File]::ReadAllText($fieldPath)

$problems = New-Object System.Collections.Generic.List[string]

# 1. surface_pattern must not derive its normal from screen-space derivatives.
if ($pattern -match 'dFdx\s*\(|dFdy\s*\(') {
	$problems.Add("post/surface_pattern.fsh still calls dFdx/dFdy; the normal must come from depth taps")
}

# 2. surface_pattern must use the shared robust normal and the axis snap / face id.
foreach ($needle in @('vfx_depth_normal\s*\(', 'vfx_snap_normal\s*\(', 'vfx_face_id\s*\(')) {
	if ($pattern -notmatch $needle) {
		$problems.Add("post/surface_pattern.fsh is missing $needle")
	}
}

# 3. The snapped normal (not the raw one) must drive the face id, projection plane and band.
if ($pattern -notmatch 'faceId\s*=\s*vfx_face_id\s*\(\s*n\s*\)') {
	$problems.Add("post/surface_pattern.fsh does not take faceId from the snapped normal n")
}

# 4. camera.glsl is the single owner: it declares the robust recipe and contains no derivative.
foreach ($needle in @('vec3\s+vfx_surface_step\s*\(', 'vec3\s+vfx_depth_normal\s*\(',
		'vec3\s+vfx_snap_normal\s*\(', 'int\s+vfx_face_id\s*\(')) {
	if ($camera -notmatch $needle) {
		$problems.Add("include/camera.glsl does not declare $needle")
	}
}
if ($camera -match 'dFdx\s*\(|dFdy\s*\(') {
	$problems.Add("include/camera.glsl still uses dFdx/dFdy")
}

# 5. field.glsl's normal-facing leaf uses the same robust recipe, never derivatives.
if ($field -match 'dFdx\s*\(|dFdy\s*\(') {
	$problems.Add("include/field.glsl still uses dFdx/dFdy")
}
if ($field -notmatch 'vfx_depth_normal\s*\(') {
	$problems.Add("include/field.glsl normal-facing leaf does not use vfx_depth_normal")
}

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern normal check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "surface_pattern normal OK: depth-tap normal + axis snap, no screen-space derivatives in the pattern/field normals."
exit 0

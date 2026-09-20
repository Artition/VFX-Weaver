# Dev-only guard for the per-node scene-depth convention (2026-09-20).
#
# The depth buffer is window depth in [0,1] on every node, but the NDC z it maps to differs:
#   26.2   reversed  (glClipControl GL_ZERO_TO_ONE + near/far-swapped projection, near = 1, far = 0)
#   26.1.2 standard  (no clip control, standard projection, near = 0, far = 1)
#   1.21.11 standard (Matrix4f.perspective + the default LEQUAL depth test)
# The convention is a per-node compile-time constant (VFXShaderPrograms.DEPTH_REVERSED) injected as
# the VFX_DEPTH_REVERSED shader define; one shader source (include/camera.glsl) converts raw depth
# per node. This check asserts, runnably, that each node's built class carries the right flag, and
# statically that the shader consumers use the flag instead of a hard-coded convention.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-depth-convention.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$shaderRoot = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders"
$cameraPath = Join-Path $shaderRoot "include\camera.glsl"
$patternPath = Join-Path $shaderRoot "post\surface_pattern.fsh"
$coveragePath = Join-Path $shaderRoot "post\mask_coverage.fsh"
$geometryPath = Join-Path $shaderRoot "post\mask_block_geometry.fsh"

$programs = [System.IO.File]::ReadAllText($programsPath)
$camera = [System.IO.File]::ReadAllText($cameraPath)
$pattern = [System.IO.File]::ReadAllText($patternPath)
$coverage = [System.IO.File]::ReadAllText($coveragePath)
$geometry = [System.IO.File]::ReadAllText($geometryPath)

$problems = New-Object System.Collections.Generic.List[string]

# --- 1. runnable per-node flag: javap -constants on each node's built class ----------------------
$jdkHome = $env:JAVA_HOME
$javap = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javap.exe"))) { Join-Path $jdkHome "bin\javap.exe" } else { $null }
if (-not $javap) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javap = Join-Path $candidate.FullName "bin\javap.exe" }
}
if (-not $javap) {
	$cmd = Get-Command javap.exe -ErrorAction SilentlyContinue
	if ($cmd) { $javap = $cmd.Source }
}
if (-not $javap) {
	$problems.Add("depth convention check: no javap found (set JAVA_HOME)")
} else {
	# node -> expected DEPTH_REVERSED value (proven from the real client jars).
	$expected = [ordered]@{ "26.2" = $true; "26.1.2" = $false; "1.21.11" = $false }
	foreach ($node in $expected.Keys) {
		$classFile = Join-Path $repoRoot "versions\$node\build\classes\java\client\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.class"
		if (-not (Test-Path $classFile)) {
			$problems.Add("depth convention check: build :$node first (missing $classFile)")
			continue
		}
		$output = & $javap -p -constants $classFile 2>&1 | Out-String
		$m = [regex]::Match($output, 'DEPTH_REVERSED\s*=\s*(true|false)')
		if (-not $m.Success) {
			$problems.Add("${node}: DEPTH_REVERSED is not a compile-time constant in the built class")
			continue
		}
		$actual = $m.Groups[1].Value -eq 'true'
		$want = $expected[$node]
		if ($actual -ne $want) {
			$problems.Add("${node}: DEPTH_REVERSED = $actual, expected $want (reversed only on 26.2)")
		} else {
			Write-Host "  $node : DEPTH_REVERSED = $actual"
		}
	}
}

# --- 2. the shared conversion lives in camera.glsl and both branches are correct ------------------
if ($camera -notmatch '(?s)#ifndef VFX_DEPTH_REVERSED\s*\r?\n#define VFX_DEPTH_REVERSED 0') {
	$problems.Add("include/camera.glsl does not default VFX_DEPTH_REVERSED to 0 when a pipeline omits it")
}
if ($camera -notmatch '#if VFX_DEPTH_REVERSED') {
	$problems.Add("include/camera.glsl does not branch on VFX_DEPTH_REVERSED")
}
if ($camera -notmatch '#define VFX_DEPTH_TO_NDC\(rawDepth\) \(rawDepth\)') {
	$problems.Add("include/camera.glsl reversed branch does not feed raw depth as NDC z")
}
if ($camera -notmatch '#define VFX_DEPTH_TO_NDC\(rawDepth\) \(\(rawDepth\) \* 2\.0 - 1\.0\)') {
	$problems.Add("include/camera.glsl standard branch does not map raw depth to NDC z (*2-1)")
}
if ($camera -notmatch '#define VFX_DEPTH_IS_SKY\(rawDepth\) \(\(rawDepth\) <= 1\.0e-6\)') {
	$problems.Add("include/camera.glsl reversed sky test is not raw <= 1e-6")
}
if ($camera -notmatch '#define VFX_DEPTH_IS_SKY\(rawDepth\) \(\(rawDepth\) >= 1\.0 - 1\.0e-6\)') {
	$problems.Add("include/camera.glsl standard sky test is not raw >= 1-1e-6")
}
if ($camera -notmatch '#define VFX_DEPTH_NEAR_RAW 1\.0' -or $camera -notmatch '#define VFX_DEPTH_NEAR_RAW 0\.0') {
	$problems.Add("include/camera.glsl does not define VFX_DEPTH_NEAR_RAW per convention")
}

# --- 3. consumers use the flag, not a hard-coded convention ---------------------------------------
if ($pattern -notmatch 'VFX_DEPTH_IS_SKY\(sceneDepth\)') {
	$problems.Add("post/surface_pattern.fsh does not test the sky with VFX_DEPTH_IS_SKY")
}
if ($pattern -match 'sceneDepth <= 1\.0e-6') {
	$problems.Add("post/surface_pattern.fsh still hard-codes the reversed sky test (sceneDepth <= 1e-6)")
}
if ($pattern -notmatch 'VFX_DEPTH_NEAR_RAW') {
	$problems.Add("post/surface_pattern.fsh does not use VFX_DEPTH_NEAR_RAW for its eye point")
}
if ($coverage -notmatch 'VFX_DEPTH_IS_SKY\(depthRaw\)') {
	$problems.Add("post/mask_coverage.fsh does not test the sky with VFX_DEPTH_IS_SKY")
}
if ($coverage -notmatch 'vfx_world_from_depth\(texCoord, depthRaw, invViewProj\)') {
	$problems.Add("post/mask_coverage.fsh does not use the shared vfx_world_from_depth recipe")
}
if ($coverage -match 'vec4 clip = vec4\(texCoord \* 2\.0 - 1\.0, depthRaw, 1\.0\)') {
	$problems.Add("post/mask_coverage.fsh still duplicates the world reconstruction inline")
}
if ($geometry -notmatch '#if VFX_DEPTH_REVERSED') {
	$problems.Add("post/mask_block_geometry.fsh does not branch the occlusion test on VFX_DEPTH_REVERSED")
}
if ($geometry -notmatch 'gl_FragCoord\.z < sceneDepth - 1\.0e-4') {
	$problems.Add("post/mask_block_geometry.fsh is missing the reversed occlusion comparison")
}
if ($geometry -notmatch 'gl_FragCoord\.z > sceneDepth \+ 1\.0e-4') {
	$problems.Add("post/mask_block_geometry.fsh is missing the standard occlusion comparison")
}

# --- 4. every depth-reading pipeline injects the define ------------------------------------------
$defineCount = ([regex]::Matches($programs, 'withShaderDefine\("VFX_DEPTH_REVERSED"')).Count
if ($defineCount -lt 4) {
	$problems.Add("VFXShaderPrograms injects VFX_DEPTH_REVERSED into $defineCount pipeline(s); expected >= 4 (surface_pattern, field posts, coverage, block geometry)")
}
foreach ($needle in @('registerDepthPost', 'registerFieldPost', 'buildCoveragePipeline', 'blockGeometryPipeline')) {
	if ($programs -notmatch [regex]::Escape($needle)) {
		$problems.Add("VFXShaderPrograms is missing $needle")
	}
}
if ($programs -notmatch '(?s)registerDepthPost\(VFXEffectType\.SURFACE_PATTERN') {
	$problems.Add("VFXShaderPrograms does not register surface_pattern on every node")
}

Write-Host "Depth convention check"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "depth convention check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Depth convention OK: 26.2 reversed, 26.1.2/1.21.11 standard; one shader converts raw depth via VFX_DEPTH_REVERSED."
exit 0

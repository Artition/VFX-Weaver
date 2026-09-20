# Dev-only guard for the mask coverage prepass across nodes (2026-09-20).
#
# A screen-only mask needs no scene depth, so the coverage prepass must be registered and run on
# every node (including the `<26.1` / 1.21.11 line). This checks the static contracts that make
# that true and catches the active-node Stonecutter trap:
#   * VFXShaderPrograms registers the coverage and mask-apply pipelines with NO node guard;
#   * VFXPostProcessingManager runs the prepass at layer 0 for every masked effect;
#   * the depth gate is scoped to `mask.needsDepth()`, so a screen mask is never cleared;
#   * the coverage pass never binds its own output target as DepthSampler (a feedback loop that
#     is undefined on some drivers and broke screen masks where depthRecipeVerified() is false);
#   * depthRecipeVerified() is written in the active-node form (>=26.2 commented, else active),
#     so the active 26.1.2 node returns false like every other node.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-mask-prepass.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"

$programs = [System.IO.File]::ReadAllText($programsPath)
$manager = [System.IO.File]::ReadAllText($managerPath)

$problems = New-Object System.Collections.Generic.List[string]

# 1) Coverage prepass pipeline registration must be unguarded (present on every node).
if ($programs -notmatch 'coveragePipeline = RenderPipelines\.register\(buildCoveragePipeline\(') {
	$problems.Add("VFXShaderPrograms does not register the coverage prepass pipeline")
}
if ($programs -notmatch 'maskPipeline = RenderPipelines\.register\(') {
	$problems.Add("VFXShaderPrograms does not register the mask-apply pipeline")
}
# A guard immediately before the coverage/mask registration would drop them on a node.
if ($programs -match '(?s)coverageShader[\s\S]{0,200}?//\? if (?!>=26\.2)') {
	$problems.Add("the coverage pipeline registration sits behind a node guard")
}

# 2) The prepass must run at layer 0 for every masked effect.
if ($manager -notmatch 'if \(layer == 0 && !maskEffects\.isEmpty\(\)\)') {
	$problems.Add("VFXPostProcessingManager has no layer-0 mask-effects branch")
}
if ($manager -notmatch 'runCoveragePrepass\(encoder, samplerCache, mainTarget') {
	$problems.Add("VFXPostProcessingManager does not invoke the coverage prepass")
}

# 3) The depth gate must only fail a mask that actually needs depth.
if ($manager -notmatch 'mask\.needsDepth\(\) && !depthReady') {
	$problems.Add("the coverage prepass depth gate is not scoped to mask.needsDepth()")
}

# 4) The DepthSampler placeholder must never be the coverage target being written.
if ($manager -match 'bindTexture\("DepthSampler",\s*depthReady') {
	$problems.Add("executeCoverage still binds the old depthReady/coverage-target placeholder")
}
if ($manager -notmatch 'bindTexture\("DepthSampler", depthBind') {
	$problems.Add("executeCoverage does not bind the safe depth placeholder")
}
if ($manager -notmatch 'mainTarget\.getDepthTextureView\(\) != null') {
	$problems.Add("executeCoverage does not prefer the main target's depth view for DepthSampler")
}

# 5) depthRecipeVerified() must be in the active-node form so 26.1.2 returns false.
if ($manager -notmatch '(?s)depthRecipeVerified\(\)[\s\S]{0,400}?//\? if >=26\.2 \{\s*/\*return true;\s*\*///\?\} else \{\s*return false;') {
	$problems.Add("depthRecipeVerified() is not in the active-node form (>=26.2 commented, else active)")
}

Write-Host "Mask coverage prepass check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "mask prepass check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  prepass registered on every node, layer-0 scoped, depth gate needsDepth-scoped, no feedback bind"
Write-Host "Mask coverage prepass check OK."
exit 0

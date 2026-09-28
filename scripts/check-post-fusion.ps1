# Dev-only guard for the fusion class on the post program registry (post-chain fusion, Task 2).
#
# The contract: VFXFusionClass exists with exactly BARRIER/POINT/UV_REMAP; ProgramInfo carries the
# fusion policy as its last two components (BARRIER/1 = never fuse); every convenience constructor
# routes those defaults, so an unannotated registration can never fuse; and nothing can flip a
# program's class from a system property (the kill switch in the policy is the only switch).
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-post-fusion.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusionClass.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	$problems.Add("VFXFusionClass.java is missing")
}
$programs = [System.IO.File]::ReadAllText($programsPath)
$enum = if (Test-Path -LiteralPath $classPath) { [System.IO.File]::ReadAllText($classPath) } else { "" }

# 1) the enum: exactly the three constants, in order, and no further one.
$enumBody = [regex]::Match($enum, '(?s)enum VFXFusionClass\s*\{(.*)\}\s*$').Groups[1].Value
$constants = @([regex]::Matches($enumBody, '(?m)^\s*([A-Z_]{3,})\s*,?\s*(//.*)?$') | ForEach-Object { $_.Groups[1].Value })
$expected = @('BARRIER', 'POINT', 'UV_REMAP')
if (($constants -join ',') -ne ($expected -join ',')) {
	$problems.Add("VFXFusionClass constants are '$($constants -join ',')', expected '$($expected -join ',')'")
}

# 2) the registry: the fusion policy is the record's last two components.
if ($programs -notmatch 'VFXFusionClass fusionClass, int prefixEvals\)\s*\{') {
	$problems.Add("ProgramInfo does not end with the fusion components (VFXFusionClass fusionClass, int prefixEvals)")
}
if ($programs -notmatch 'public record ProgramInfo\(RenderPipeline pipeline,') {
	$problems.Add("ProgramInfo no longer starts with RenderPipeline pipeline - the positional registry contract changed")
}

# 3) every convenience constructor routes BARRIER/1 (the canonical one takes them as parameters).
$delegations = [regex]::Matches($programs, '(?m)^\s*this\((.*)\);\s*$')
if ($delegations.Count -lt 3) {
	$problems.Add("expected at least three delegating constructors, found $($delegations.Count)")
}
foreach ($d in $delegations) {
	if ($d.Groups[1].Value -notmatch 'VFXFusionClass\.BARRIER,\s*1$') {
		$problems.Add("a convenience constructor does not default to BARRIER/1: this($($d.Groups[1].Value))")
	}
}

# 4) no property can change a program's class (the policy's own kill switch lives in VFXFusionPolicy).
foreach ($text in @($programs, $enum)) {
	if ($text -match 'System\.getProperty\([^\)]*fusion') {
		$problems.Add("a fusion class is being read from a system property - the default must stay BARRIER")
	}
}

Write-Host "Post fusion class check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "post fusion class check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  BARRIER/POINT/UV_REMAP, the policy as the record's last two components, every convenience constructor defaulting to BARRIER/1, no property override"
Write-Host "Post fusion class check OK."
exit 0

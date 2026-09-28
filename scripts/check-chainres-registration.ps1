# Dev-only guard for the chain-resolution policy on the post program registry (chain-resolution, Task 1).
#
# The contract: ProgramInfo carries the resolution policy as its last three components
# (scalable/pixelParams/taps, after the fusion pair); a convenience constructor defaults them to
# false/Set.of()/0, so existing registrations stay non-scalable; and a scalable program may never be
# fusible (spec §3) - a violation is forced to a barrier, so the two mechanisms cannot disagree.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-chainres-registration.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"

$problems = New-Object System.Collections.Generic.List[string]
$programs = [System.IO.File]::ReadAllText($programsPath)

# the record ends with the three new components, in order, after the fusion pair
if ($programs -notmatch 'VFXFusionClass fusionClass, int prefixEvals, boolean scalable, Set<String> pixelParams, int taps\)\s*\{') {
	$problems.Add("ProgramInfo does not end with (scalable, pixelParams, taps)")
}
# a convenience constructor defaults them, so existing registrations stay non-scalable
if ($programs -notmatch 'this\([^;]*VFXFusionClass\.BARRIER, 1, false, Set\.of\(\), 0\);') {
	$problems.Add("no convenience constructor defaults the resolution policy")
}
# a scalable program may never be fusible (spec 3)
if ($programs -notmatch 'scalable && .*fusionClass != VFXFusionClass\.BARRIER') {
	$problems.Add("the scalable/fusible invariant is not enforced")
}

Write-Host "Chain-resolution registration check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "chain-resolution registration check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  the resolution policy is the record's last three components, a convenience constructor defaults it, and a scalable program is forced to a barrier"
Write-Host "Chain-resolution registration check OK."
exit 0

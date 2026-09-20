# Dev-only guard for the surface_pattern world anchor source (2026-09-20).
#
# The pattern anchor (center_x/y/z in the Config UBO) must be the effect instance's world
# position, never the camera: a camera-only change (F5 / third person) must not slide the
# projection over the ground. Precedence: an authored shape `center` wins, else the effect's
# runtime move (`sendMove`/`moveEffect`), else its first declared `positions` slot (`/vfx playat`),
# else authored `pos_x/pos_y/pos_z` binds, else the local player's position for a player-anchored
# play. The camera snapshot is removed from the resolver.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-surface-anchor.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$bindingsPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXWorldBindings.java"

$manager = [System.IO.File]::ReadAllText($managerPath)
$bindings = [System.IO.File]::ReadAllText($bindingsPath)

$problems = New-Object System.Collections.Generic.List[string]

# Isolate the resolveAnchor method body (from its signature to the hasPositionBind helper).
$anchorStart = $manager.IndexOf("private void resolveAnchor(")
$anchorEnd = $manager.IndexOf("private static boolean hasPositionBind(")
if ($anchorStart -lt 0 -or $anchorEnd -lt 0 -or $anchorEnd -le $anchorStart) {
	$problems.Add("resolveAnchor / hasPositionBind not found in VFXPostProcessingManager.java")
	$anchor = ""
} else {
	$anchor = $manager.Substring($anchorStart, $anchorEnd - $anchorStart)
}

# 1) the camera fallback is gone (the defect: F5 slid the pattern)
if ($anchor -match "VFXFieldEnv\.camera") {
	$problems.Add("resolveAnchor still reads VFXFieldEnv.cameraX/Y/Z (the F5 anchor bug)")
}
# 2) the effect world position sources are all consulted, in precedence order
$iCenter = $anchor.IndexOf("shape.center()")
$iMove = $anchor.IndexOf("getMovePosition()")
$iPos = $anchor.IndexOf("getPositions()")
$iBind = $anchor.IndexOf("hasPositionBind(definition)")
$iPlayer = $anchor.IndexOf("VFXWorldBindings.playerState()")
foreach ($pair in @(
	@("shape.center()", $iCenter), @("getMovePosition()", $iMove), @("getPositions()", $iPos),
	@("hasPositionBind(definition)", $iBind), @("VFXWorldBindings.playerState()", $iPlayer)
)) {
	if ($pair[1] -lt 0) { $problems.Add("resolveAnchor does not consult $($pair[0])") }
}
if ($iCenter -ge 0 -and $iMove -ge 0 -and $iPos -ge 0 -and $iBind -ge 0 -and $iPlayer -ge 0 -and
	-not ($iCenter -lt $iMove -and $iMove -lt $iPos -and $iPos -lt $iBind -and $iBind -lt $iPlayer)) {
	$problems.Add("anchor precedence is wrong: center -> move -> positions -> pos binds -> player")
}
# 3) the entity-anchor placeholder guard stays (a ZERO placeholder must not anchor at 0.5)
if ($anchor -notmatch "getAnchors\(\)\.isEmpty\(\)") {
	$problems.Add("resolveAnchor does not skip the ZERO placeholder of an entity-anchored slot")
}
# 4) the player fallback reads the shared interpolated player state
if ($bindings -notmatch "public static @Nullable PlayerState playerState\(\)") {
	$problems.Add("VFXWorldBindings.playerState() is missing")
}
if ($bindings -notmatch "record PlayerState\(float health, float hunger, float speed, float light, float timeOfDay, float px, float py, float pz\)") {
	$problems.Add("PlayerState no longer carries the player world position (px/py/pz)")
}
# 5) hasPositionBind is definition-driven (pos_x/y/z only count when authored); the caller resolves
#    the definition once per pass and passes it in, instead of re-looking it up here.
if ($manager -notmatch "private static boolean hasPositionBind\(final @Nullable VFXDefinition definition\)") {
	$problems.Add("hasPositionBind(definition) is missing")
}
if ($manager -notmatch 'definition\.getParams\(\)\.containsKey\("pos_x"\)') {
	$problems.Add("hasPositionBind does not check the authored pos_x parameter")
}

Write-Host "surface_pattern anchor source check"
Write-Host "  anchor: center -> move -> positions -> pos binds -> player; camera is never consulted"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern anchor source check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Anchor OK: the pattern is anchored to the effect's world position, not the camera."
exit 0

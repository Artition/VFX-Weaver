# Dev-only guard for the surface_pattern world anchor source (2026-09-20).
#
# The pattern anchor (center_x/y/z in the Config UBO) must be the effect instance's world
# position, never the camera: a camera-only change (F5 / third person) must not slide the
# projection over the ground. Precedence: an authored shape `center` wins, else the effect's
# runtime move (`sendMove`/`moveEffect`), else its first declared `positions` slot (`/vfx playat`),
# else authored `pos_x/pos_y/pos_z` binds, else the local player's EYE position for a
# player-anchored play. The camera snapshot is removed from the resolver.
#
# The player fallback is the EYE, not the feet (regression 2026-09-20): the anchor's Y is the
# wall projection's centre (`centerP.y = center.y`), so an anchor at the feet puts the figure on
# the floor line and half of it below the wall, inside the ground - the "wall mode stopped
# working" report. Floors are unaffected (they ignore the anchor Y: `centerP = center.xz`), which
# is why only walls regressed when 762c836 moved the anchor from the camera (the eye) to the
# player's feet. The eye height is a player property (sneak/swim change it), not the camera, so
# the pattern still never slides when only the camera moves.
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
# 6) the player fallback anchors at the player's EYE, not the feet; otherwise a wall projection
#    is centred on the floor line and the figure's lower half is buried in the ground.
if ($anchor -notmatch 'player\.py\(\) \+ eyeHeight') {
	$problems.Add("the player fallback anchors at the feet (player.py()), not the eye: the wall figure is buried in the floor")
}
if ($anchor -notmatch 'getEyeHeight\(\)') {
	$problems.Add("the player fallback does not read the player's eye height")
}

# 7) numeric proof of the wall burial, for a built-in-sized figure (half-extent 0.5 cell at
#    tile_scale 2 = 1 block) on a wall rising from the player's feet (world Y = 0 at the feet):
#    a feet anchor spans [-1, +1] (half the figure below the wall), an eye anchor (1.62) spans
#    [0.62, 2.62] (all of it on the wall). The check fails if the visible fraction regresses.
$figureHalf = 1.0                 # 0.5 cell * tile_scale 2.0
[double]$eyeHeight = 1.62         # standing eye height
$feetFraction = ($figureHalf - 0.0) / (2.0 * $figureHalf)       # of [-1, 1], only [0, 1] is above the floor
$eyeFraction = [Math]::Min(1.0, ($eyeHeight + $figureHalf) / (2.0 * $figureHalf))
if ($feetFraction -ge 1.0) {
	$problems.Add("the feet-anchor geometry check is wrong: a feet anchor hides part of the figure on a wall")
}
if ([Math]::Abs($eyeFraction - 1.0) -gt 1.0e-9) {
	$problems.Add("an eye-anchored figure must be fully on the wall above the feet (visible fraction ~1)")
}

Write-Host "surface_pattern anchor source check"
Write-Host "  anchor: center -> move -> positions -> pos binds -> player EYE; camera is never consulted"
Write-Host "  wall figure visible above the feet: feet anchor $([Math]::Round($feetFraction, 3)), eye anchor $([Math]::Round($eyeFraction, 3))"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern anchor source check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Anchor OK: the pattern is anchored to the effect's world position, not the camera."
exit 0

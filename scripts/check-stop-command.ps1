# Dev-only guard for the `/vfx stop [<player>]` form (2026-09-20).
#
# Checks the static contracts of the "stop every effect for a player" command:
#   * the stop literal has a no-argument executor (stop all of the executor's effects);
#   * the old `/vfx stop <effect> [players]` branch is preserved;
#   * the new `/vfx stop <player>` branch uses EntityArgument.player();
#   * the effect branch is registered BEFORE the player branch, so a bare namespaced id still
#     resolves as an effect (a selector `@p`/`@a` is not a valid Identifier and falls through);
#   * VFXAPI.sendStopAll and VFXServerEffects.activeEffects exist, and the local counterpart
#     VFXLocalDispatcher.stopAllEffects is still declared.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-stop-command.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$commandPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\command\VFXCommand.java"
$apiPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\api\VFXAPI.java"
$serverEffectsPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXServerEffects.java"
$dispatcherPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\api\VFXLocalDispatcher.java"

$command = [System.IO.File]::ReadAllText($commandPath)
$api = [System.IO.File]::ReadAllText($apiPath)
$serverEffects = [System.IO.File]::ReadAllText($serverEffectsPath)
$dispatcher = [System.IO.File]::ReadAllText($dispatcherPath)

$problems = New-Object System.Collections.Generic.List[string]

# The stop literal block only, so the checks below cannot match a different subcommand.
$stopStart = $command.IndexOf('Commands.literal("stop")')
$stopEnd = $command.IndexOf('Commands.literal("set")')
if ($stopStart -lt 0 -or $stopEnd -le $stopStart) {
	$problems.Add("could not locate the /vfx stop command block")
} else {
	$stop = $command.Substring($stopStart, $stopEnd - $stopStart)
	if ($stop -notmatch '\.executes\(context2 -> stopAll\(') {
		$problems.Add("the stop literal has no no-argument executor (stop all of the executor's effects)")
	}
	if ($stop -notmatch 'Commands\.argument\("effect", IdentifierArgument\.id\(\)\)') {
		$problems.Add("the /vfx stop <effect> branch is missing")
	}
	if ($stop -notmatch 'Commands\.argument\("targets", EntityArgument\.players\(\)\)') {
		$problems.Add("the /vfx stop <effect> [players] branch lost its targets argument")
	}
	if ($stop -notmatch 'Commands\.argument\("player", EntityArgument\.player\(\)\)') {
		$problems.Add("the /vfx stop <player> branch is missing EntityArgument.player()")
	}
	$effectIndex = $stop.IndexOf('Commands.argument("effect"')
	$playerIndex = $stop.IndexOf('Commands.argument("player"')
	if ($effectIndex -lt 0 -or $playerIndex -lt 0 -or $effectIndex -gt $playerIndex) {
		$problems.Add("the effect branch must be registered before the player branch (id-before-player disambiguation)")
	}
}

if ($api -notmatch 'public static void sendStopAll\(final ServerPlayer player\)') {
	$problems.Add("VFXAPI.sendStopAll(ServerPlayer) is missing")
}
if ($serverEffects -notmatch 'public Set<Identifier> activeEffects\(final ServerPlayer player\)') {
	$problems.Add("VFXServerEffects.activeEffects(ServerPlayer) is missing")
}
if ($dispatcher -notmatch 'void stopAllEffects\(\);') {
	$problems.Add("VFXLocalDispatcher.stopAllEffects() is missing (the local counterpart)")
}

Write-Host "Stop command check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "stop command check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  /vfx stop [<player>] no-arg + effect + player branches, id-before-player, API primitive present"
Write-Host "Stop command check OK."
exit 0

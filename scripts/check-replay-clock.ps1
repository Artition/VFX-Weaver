# Dev-only guard for the Flashback replay-driven effect clock (2026-09-22).
#
# The effects used to run on the wall clock: pausing a replay did not pause them and a seek did
# not reposition them. This check asserts the contracts that fix that, plus a compiled standalone
# check of the MC-free replay-timeline math:
#   * ReplayClockCheck - VFXReplayClock.phaseAt/ageAt place an effect before its trigger, in the
#                        middle (correct age), after it ends, and keep looping/persistent alive.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-replay-clock.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client"
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$flashback = Read-Source (Join-Path $client "flashback\FlashbackCompat.java")
$controller = Read-Source (Join-Path $client "flashback\VFXReplayController.java")
$manager = Read-Source (Join-Path $client "effect\VFXEffectManager.java")
$replayClock = Read-Source (Join-Path $main "effect\VFXReplayClock.java")
$timeline = Read-Source (Join-Path $main "effect\VFXTimeline.java")
$mixin = Read-Source (Join-Path $client "mixin\GameRendererMixin.java")
$hooks = Read-Source (Join-Path $client "platform\VFXClientRenderHooks.java")
$graphDemo = Read-Source (Join-Path $repoRoot "src\main\resources\data\vfxweaver\vfx\graph_demo.json")
$graphLogicDemo = Read-Source (Join-Path $repoRoot "src\main\resources\data\vfxweaver\vfx\graph_logic_demo.json")

$problems = New-Object System.Collections.Generic.List[string]

# 1) Flashback exposes the playback state we drive the clock from.
if ($flashback -notmatch 'public static boolean isReplayActive\(\)') {
	$problems.Add("FlashbackCompat: isReplayActive() is missing")
}
if ($flashback -notmatch 'getPartialReplayTick') {
	$problems.Add("FlashbackCompat: the replay time does not come from getPartialReplayTick")
}
if ($flashback -notmatch 'getDeclaredField\("currentTick"\)') {
	$problems.Add("FlashbackCompat: the recorded action tick does not come from ReplayServer.currentTick")
}
if ($flashback -notmatch 'currentTickField\.getInt\(server\)') {
	$problems.Add("FlashbackCompat: currentActionTick does not read the currentTick field")
}
if ($flashback -notmatch 'replayPausedField\.getBoolean\(server\)') {
	$problems.Add("FlashbackCompat: isReplayPaused does not read the replayPaused field")
}
# Still exactly one action registration (a second one throws and disables recording).
$registerCalls = ([regex]::Matches($flashback, 'registryClass\.getMethod\("register"')).Count
if ($registerCalls -ne 1) {
	$problems.Add("FlashbackCompat: expected exactly one action registration, found $registerCalls")
}

# 2) The effect manager can be driven by an absolute replay time and can place replay effects.
if ($manager -notmatch 'public void setClock\(final float now\)') {
	$problems.Add("VFXEffectManager: setClock is missing")
}
if ($manager -notmatch 'public long playReplay\(') {
	$problems.Add("VFXEffectManager: playReplay is missing")
}
if ($manager -notmatch 'public boolean replayEffectActive\(') {
	$problems.Add("VFXEffectManager: replayEffectActive is missing")
}
if ($manager -notmatch 'public void removeInstance\(final long instanceId\)') {
	$problems.Add("VFXEffectManager: removeInstance(long) is missing")
}
if ($manager -notmatch 'public boolean applyParam\(') {
	$problems.Add("VFXEffectManager: applyParam (no-start set-param) is missing")
}
if ($manager -notmatch 'Float\.isNaN\(startTime\) \? this\.clock : startTime') {
	$problems.Add("VFXEffectManager: play does not honour the explicit start time")
}
if ($manager -notmatch 'playSound && definition != null && definition\.getSound\(\) != null') {
	$problems.Add("VFXEffectManager: a replay rebuild can still re-trigger the effect sound")
}

# 3) The replay controller records each action at its tick and reconciles on apply.
foreach ($needle in @('onPlay(', 'onStop(', 'onSetParam(', 'onKeyframe(', 'onSetExpr(', 'public void apply(final float replayTick', 'SEEK_THRESHOLD_TICKS')) {
	if ($controller -notmatch [regex]::Escape($needle)) {
		$problems.Add("VFXReplayController: missing '$needle'")
	}
}
if ($controller -notmatch 'manager\.replayEffectActive\(') {
	$problems.Add("VFXReplayController: does not gate effect creation on replayEffectActive")
}
if ($controller -notmatch 'manager\.removeInstance\(play\.instanceId\)') {
	$problems.Add("VFXReplayController: does not drop an effect whose trigger is now in the future")
}
if ($manager -notmatch 'public void clearScheduled\(\)') {
	$problems.Add("VFXEffectManager: clearScheduled is missing")
}
if ($controller -notmatch 'clearScheduled\(\)') {
	$problems.Add("VFXReplayController: a seek rebuild does not clear pending collection children")
}

# 4) The frame clock is the replay time while a replay is open, and the wall clock otherwise.
if ($mixin -notmatch 'FlashbackCompat\.isReplayActive\(\)') {
	$problems.Add("GameRendererMixin: the effect clock is not switched on replay state")
}
if ($mixin -notmatch 'VFXEffectManager\.get\(\)\.setClock\(replayTick\)') {
	$problems.Add("GameRendererMixin: the replay time is not set as the effect clock")
}
if ($mixin -notmatch 'VFXReplayController\.get\(\)\.apply\(replayTick') {
	$problems.Add("GameRendererMixin: the replay controller is not applied per frame")
}
if ($mixin -notmatch 'VFXEffectManager\.get\(\)\.advance\(deltaTicks\)') {
	$problems.Add("GameRendererMixin: the non-replay wall-clock path was removed")
}
if ($hooks -notmatch 'FlashbackCompat\.tickReplayState\(\)') {
	$problems.Add("VFXClientRenderHooks: replay effects are not cleared once no replay is open")
}

# 5) The pure replay math lives in a Minecraft-free class.
if ($replayClock -notmatch 'public static Phase phaseAt\(' -or $replayClock -notmatch 'public static float ageAt\(') {
	$problems.Add("VFXReplayClock: phaseAt/ageAt are missing")
}
if ($replayClock -match 'net\.minecraft') {
	$problems.Add("VFXReplayClock: must stay Minecraft-free for the standalone check")
}

# 6) Looping/persistent effects are recorded, not skipped: the snapshot and the live-play path
#    must both carry them, and the snapshot payload must stay decodable (MAX_PARAMS cap).
if ($flashback -match 'effect\.isLooping\(\)|effect\.isPersistent\(\)') {
	$problems.Add("FlashbackCompat: the active-effects snapshot still skips looping/persistent effects")
}
if ($flashback -match 'if \(!enabled \|\| durationTicks < 0\)') {
	$problems.Add("FlashbackCompat: recordPlay still drops negative-duration (persistent) plays")
}
if ($flashback -notmatch 'durationTicks < 0 \? -1 : durationTicks') {
	$problems.Add("FlashbackCompat: a persistent play is not normalised to the -1 sentinel")
}
if ($flashback -notmatch 'params\.size\(\) >= MAX_PARAMS') {
	$problems.Add("FlashbackCompat: the snapshot params are not capped at MAX_PARAMS")
}
foreach ($pair in @(@('graph_demo', $graphDemo), @('graph_logic_demo', $graphLogicDemo))) {
	if ($pair[1] -notmatch '"loop"\s*:\s*true' -or $pair[1] -notmatch '"persistent"\s*:\s*true') {
		$problems.Add("$($pair[0]).json: not loop+persistent, so it is no longer a snapshot candidate")
	}
}

# 7) The definitions snapshot carries the raw JSON (graph/inputs/meta/subgraphs verbatim) and
#    re-applies it through the same parse path, so graph wiring and subgraph macros survive.
if ($flashback -notmatch 'getRawDefinitions\(\)') {
	$problems.Add("FlashbackCompat: the definitions snapshot does not write the raw definition JSON")
}
if ($flashback -notmatch 'VFXDefinitionManager\.get\(\)\.applySynced\(definitions\)') {
	$problems.Add("FlashbackCompat: the definitions snapshot is not re-applied through applySynced")
}
# Precedence: a runtime override (set-param/keyframe) wins over a graph input, but a graph input
# wins over a plain definition value - the same order live and on playback.
if ($timeline -notmatch '(?s)AnimatedValue override = this\.overrides\.get\(name\);.*?final String graphNode = this\.graphInputs\.get\(name\);') {
	$problems.Add("VFXTimeline.getValue: runtime overrides no longer win over graph inputs")
}

Write-Host "Flashback replay clock check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "replay clock check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable check: compile and run the standalone replay-math check -------------------------
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$programFiles = $env:ProgramFiles
	$candidate = Get-ChildItem (Join-Path $programFiles "Java") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) {
		$javac = Join-Path $candidate.FullName "bin\javac.exe"
		$java = Join-Path $candidate.FullName "bin\java.exe"
	}
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "replay clock check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXReplayClock.class"))) {
	Write-Error "replay clock check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}

$checkDir = Join-Path $env:TEMP "vfxweaver-replay-clock-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "ReplayClockCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'))"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import dev.vfxweaver.effect.VFXReplayClock;
import dev.vfxweaver.effect.VFXReplayClock.Phase;

/** The replay-driven age function must place an effect before, during and after its trigger. */
public final class ReplayClockCheck {
	public static void main(final String[] args) {
		final float trigger = 150.0F;
		final float duration = 40.0F;
		require(VFXReplayClock.phaseAt(100.0, trigger, duration, false, false) == Phase.BEFORE, "before the trigger");
		require(VFXReplayClock.phaseAt(150.0, trigger, duration, false, false) == Phase.ACTIVE, "at the trigger");
		require(VFXReplayClock.phaseAt(170.0, trigger, duration, false, false) == Phase.ACTIVE, "mid-effect");
		require(VFXReplayClock.phaseAt(190.0, trigger, duration, false, false) == Phase.AFTER, "after the end");
		require(VFXReplayClock.phaseAt(1000.0, trigger, duration, true, false) == Phase.ACTIVE, "looping never ends");
		require(VFXReplayClock.phaseAt(1000.0, trigger, duration, false, true) == Phase.ACTIVE, "persistent never ends");
		require(VFXReplayClock.phaseAt(1_000_000.0, trigger, duration, true, false) == Phase.ACTIVE, "looping is still active a million ticks later");
		require(VFXReplayClock.phaseAt(1_000_000.0, trigger, duration, false, true) == Phase.ACTIVE, "persistent is still active a million ticks later");
		require(VFXReplayClock.ageAt(170.0, trigger, duration, false) == 20.0F, "age mid-effect");
		require(VFXReplayClock.ageAt(190.0, trigger, duration, false) == 40.0F, "age at the end");
		require(VFXReplayClock.ageAt(100.0, trigger, duration, false) == 0.0F, "age clamps before the trigger");
		require(VFXReplayClock.ageAt(195.0, trigger, duration, true) == 5.0F, "looping age wraps");
		System.out.println("replay clock check OK: BEFORE/ACTIVE/AFTER, ages 0/20/40/5 and looping/persistent at 1e6 ticks all place correctly");
	}

	private static void require(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" ReplayClockCheck
	if ($LASTEXITCODE -ne 0) { throw "ReplayClockCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Flashback replay clock check OK."
exit 0

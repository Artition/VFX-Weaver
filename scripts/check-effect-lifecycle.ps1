# Dev-only guard for the effect lifecycle / network-dispatch / particle-state batch (2026-09-20).
#
# Static assertions over the exact contracts this batch fixed, plus two compiled standalone checks:
#   * FallbackCheck  - VFXGraphEvaluator.evaluateIndex(..., fallback) must return the fallback for a
#                      fallback-less bind node (it delegated to eval(index) -> fallback 0 before).
#   * LifecycleCheck - VFXEffectManager.resolveTimelineDuration + VFXActiveEffect.isFinished:
#                      "never ends" is the lifecycle flag, not an Integer.MAX_VALUE timeline.
#   * ServerEffectsCheck - VFXServerEffects: a stored effect resumes at the age it had at disconnect
#                      (offline time is not counted), expired finites are not resurrected and the
#                      offline-memory bound prunes old entries.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-effect-lifecycle.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client"
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$vfxClient = Read-Source (Join-Path $client "VFXClient.java")
$manager = Read-Source (Join-Path $client "effect\VFXEffectManager.java")
$activeEffect = Read-Source (Join-Path $main "effect\VFXActiveEffect.java")
$graphEval = Read-Source (Join-Path $main "graph\VFXGraphEvaluator.java")
$overlay = Read-Source (Join-Path $client "render\VFXWorldOverlayRenderer.java")
$spark = Read-Source (Join-Path $client "render\VFXSparkEngine.java")
$block = Read-Source (Join-Path $client "render\VFXBlockParticleEngine.java")
$serverEffects = Read-Source (Join-Path $main "effect\VFXServerEffects.java")
$loaderEvents = Read-Source (Join-Path $main "platform\VFXLoaderEvents.java")
$post = Read-Source (Join-Path $client "postprocessing\VFXPostProcessingManager.java")
$flashback = Read-Source (Join-Path $client "flashback\FlashbackCompat.java")

$problems = New-Object System.Collections.Generic.List[string]

# 1) One reachable handler per network action: SET_EXPR and MOVE must be top-level branches, never
#    nested inside the STOP branch (where they were unreachable and fell through to a play).
if ($vfxClient -notmatch '(?s)action\(\) == VFXAction\.STOP\) \{.*?recordStop.*?\} else if \(payload\.action\(\) == VFXAction\.SET_EXPR\)') {
	$problems.Add("VFXClient: the SET_EXPR handler is not a top-level branch after STOP (unreachable/mis-routed)")
}
if ($vfxClient -notmatch '\} else if \(payload\.action\(\) == VFXAction\.MOVE\) \{') {
	$problems.Add("VFXClient: the MOVE handler is not a top-level branch (unreachable/mis-routed)")
}
if ($vfxClient -match '(?s)action\(\) == VFXAction\.STOP\) \{(?<stopBody>.*?)\} else if') {
	if ($Matches['stopBody'] -match 'action\(\) ==') {
		$problems.Add("VFXClient: a SET_EXPR check is still nested inside the STOP branch")
	}
}

# 2) Persistent duration is finite; the lifecycle flag keeps the instance alive.
if ($manager -notmatch 'static int resolveTimelineDuration\(') {
	$problems.Add("VFXEffectManager: resolveTimelineDuration is missing")
}
if ($manager -match ':\s*Integer\.MAX_VALUE\)') {
	$problems.Add("VFXEffectManager: a persistent duration is still Integer.MAX_VALUE (freezes animation / zeroes emission)")
}
if ($manager -notmatch 'boolean persistent = persistentFromServer \|\| \(definition != null && \(definition\.isPersistent\(\) \|\| loop\)\)') {
	$problems.Add("VFXEffectManager: the persistent flag does not combine the definition flags and a server duration")
}
if ($manager -notmatch 'loop, persistent, positions, entityUuids, anchors') {
	$problems.Add("VFXEffectManager: the persistent flag is not passed to VFXActiveEffect")
}
if ($activeEffect -notmatch 'private final boolean persistent;') {
	$problems.Add("VFXActiveEffect: no persistent field")
}
if ($activeEffect -notmatch '(?s)if \(this\.persistent\) \{.*?return false;.*?\}\s*return this\.timeline\.isFinished\(\);') {
	$problems.Add("VFXActiveEffect.isFinished: a persistent instance still finishes via the timeline")
}
if ($activeEffect -notmatch 'public boolean isPersistent\(\)') {
	$problems.Add("VFXActiveEffect: isPersistent() getter is missing")
}
# The active-effects snapshot must no longer skip persistent/looping effects: the replay
# controller keeps such a play alive until a recorded stop, so an infinite effect reproduces.
if ($flashback -match 'effect\.isPersistent\(\)|effect\.isLooping\(\)') {
	$problems.Add("FlashbackCompat: the active-effects snapshot still skips persistent/looping effects")
}

# 3) The evaluator routes the fallback through the in-range path.
if ($graphEval -notmatch 'index < 0 \|\| index >= this\.cache\.length \? fallback : eval\(index, fallback\);') {
	$problems.Add("VFXGraphEvaluator.evaluateIndex: the fallback is not routed through eval(index, fallback)")
}

# 4) Per-instance renderer state is pruned against the live instance set.
if ($overlay -notmatch 'private static void pruneInstanceState\(final Set<Long> liveInstances\)') {
	$problems.Add("VFXWorldOverlayRenderer: pruneInstanceState is missing")
}
foreach ($map in @('PARTICLE_BUDGETS', 'AIMED_PARTICLES', 'CHAIN_SIMS')) {
	if ($overlay -notmatch "$map\.keySet\(\)\.retainAll\(liveInstances\);") {
		$problems.Add("VFXWorldOverlayRenderer: $map is not pruned against the live instances")
	}
}
if ($overlay -notmatch 'pruneInstanceState\(liveInstances\);') {
	$problems.Add("VFXWorldOverlayRenderer: pruneInstanceState is never called")
}

# 5) Unknown shape exits the emission loop, not the whole method.
$shapeNeedle = 'Unknown shape: stop emitting this frame'
foreach ($pair in @(@('VFXWorldOverlayRenderer', $overlay), @('VFXSparkEngine', $spark), @('VFXBlockParticleEngine', $block))) {
	if ($pair[1] -notmatch [regex]::Escape($shapeNeedle)) {
		$problems.Add("$($pair[0]): the unknown-shape path is not a loop break")
	}
}

# 6) A disconnecting player's effects are kept (age frozen), not wiped; the store stays bounded.
if ($serverEffects -notmatch 'public void onPlayerDisconnect\(final ServerPlayer player\)') {
	$problems.Add("VFXServerEffects: onPlayerDisconnect(ServerPlayer) is missing")
}
if ($loaderEvents -notmatch 'VFXServerEffects\.get\(\)\.onPlayerDisconnect\(player\);') {
	$problems.Add("VFXLoaderEvents.onPlayerDisconnect: VFXServerEffects.onPlayerDisconnect is not called")
}
if ($serverEffects -match 'public void remove\(final ServerPlayer player\)') {
	$problems.Add("VFXServerEffects: the wipe-on-disconnect remove(ServerPlayer) is still present")
}
if ($serverEffects -notmatch 'public void onServerStopping\(\)') {
	$problems.Add("VFXServerEffects: onServerStopping() (singleplayer world-reload freeze) is missing")
}
if ($loaderEvents -notmatch 'VFXServerEffects\.get\(\)\.onServerStopping\(\);') {
	$problems.Add("VFXLoaderEvents.onServerStopping: VFXServerEffects.onServerStopping is not called")
}
if ($serverEffects -notmatch 'MAX_TRACKED_PLAYERS = 256') {
	$problems.Add("VFXServerEffects: the global player cap MAX_TRACKED_PLAYERS is missing")
}
if ($serverEffects -notmatch 'MAX_OFFLINE_MILLIS = 24L \* 60L \* 60L \* 1000L') {
	$problems.Add("VFXServerEffects: the offline expiry MAX_OFFLINE_MILLIS is missing")
}
if ($serverEffects -notmatch 'this\.byPlayer\.size\(\) >= MAX_TRACKED_PLAYERS') {
	$problems.Add("VFXServerEffects: the global cap is not enforced on a new player")
}
if ($serverEffects -notmatch 'isOfflineExpired\(entry\.getValue\(\)\.disconnectedAtMillis, now\)') {
	$problems.Add("VFXServerEffects: offline entries are not pruned by age")
}
# The age must be frozen at disconnect, not advanced by the offline time: applyTo resolves its
# clock through resumeClock, and a persistent effect is no longer resumed from elapsed 0.
if ($serverEffects -notmatch 'resumeClock\(state\.disconnectedAtMillis, System\.currentTimeMillis\(\)\)') {
	$problems.Add("VFXServerEffects.applyTo: the resume clock is not frozen at disconnect")
}
if ($serverEffects -match 'int elapsed = persistent \? 0 :') {
	$problems.Add("VFXServerEffects.applyTo: a persistent effect still resumes from elapsed 0 (phase restarts)")
}
if ($serverEffects -notmatch 'active\.neverExpires\(\)') {
	$problems.Add("VFXServerEffects: looping/persistent effects are not tracked as never-expiring")
}

# 7) A malformed field texture id degrades only its own pass.
if ($post -notmatch '(?s)resolveTexture\(final String id\).*?Identifier\.tryParse\(id\)') {
	$problems.Add("VFXPostProcessingManager.resolveTexture: the texture id is not tryParse-validated")
}
if ($post -match '(?s)resolveTexture\(final String id\).*?Identifier\.parse\(id\)') {
	$problems.Add("VFXPostProcessingManager.resolveTexture: a raw Identifier.parse(id) can still throw into the layer-wide catch")
}

# 8) An explicit instance id restarts its slot instead of stacking a duplicate.
if ($manager -notmatch 'removeIf\(existing -> existing\.getInstanceId\(\) == instanceId\)') {
	$problems.Add("VFXEffectManager.play: an explicit instance id does not replace the existing slot")
}

# 10) The dead engine clear methods are gone (state is pruned every frame).
foreach ($pair in @(@('VFXSparkEngine', $spark), @('VFXBlockParticleEngine', $block))) {
	if ($pair[1] -match 'public static void clear\(final long instanceKey\)') {
		$problems.Add("$($pair[0]): the unreferenced clear(long) method is still present")
	}
}

# 11) No duplicate import was left behind.
$nullableImports = ([regex]::Matches($activeEffect, 'import org\.jspecify\.annotations\.Nullable;')).Count
if ($nullableImports -ne 1) {
	$problems.Add("VFXActiveEffect: expected exactly one jspecify Nullable import, found $nullableImports")
}

Write-Host "Effect lifecycle / dispatch check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "effect lifecycle check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable checks: compile and run the two standalone Java checks -------------------------
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
	Write-Error "effect lifecycle check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
$clientClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\client"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\graph\VFXGraphEvaluator.class"))) {
	Write-Error "effect lifecycle check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "effect lifecycle check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
# Gradle module jars, minus the mod's own published jars and Flashback (stale shadows of our classes).
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-lifecycle-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$fallbackSrc = Join-Path $checkDir "FallbackCheck.java"
$lifecycleSrc = Join-Path $checkDir "LifecycleCheck.java"
$serverEffectsSrc = Join-Path $checkDir "ServerEffectsCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;versions/26.1.2/build/classes/java/client;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$fallbackJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;

/** A fallback-less BIND node must resolve to the caller's fallback on both evaluate paths. */
public final class FallbackCheck {
	public static void main(final String[] args) {
		final JsonObject graphJson = JsonParser.parseString(
			"{\"version\":1,\"nodes\":[{\"id\":\"b\",\"kind\":\"bind\",\"inputs\":{\"bind\":\"proximity\",\"pos\":[0,0,0],\"range\":8}}]}"
		).getAsJsonObject();
		final VFXGraph graph = VFXGraph.parse("check", graphJson);
		final VFXGraphEvaluator evaluator = new VFXGraphEvaluator(graph, 0L);
		evaluator.beginFrame(0.0F);
		final Integer index = graph.indexOf("b");
		if (index == null) {
			throw new AssertionError("bind node not indexed");
		}
		final float byIndex = evaluator.evaluateIndex(index, 42.0F);
		if (byIndex != 42.0F) {
			throw new AssertionError("evaluateIndex ignored the fallback: got " + byIndex + ", want 42.0");
		}
		evaluator.beginFrame(0.0F);
		final float byId = evaluator.evaluate("b", 42.0F);
		final float memo = evaluator.evaluateIndex(index, 7.0F);
		if (byId != 42.0F || memo != 42.0F) {
			throw new AssertionError("fallback/memo mismatch: byId=" + byId + ", memo=" + memo);
		}
		System.out.println("fallback check OK: evaluateIndex -> " + byIndex + ", evaluate -> " + byId + ", memo -> " + memo);
	}
}
'@
$lifecycleJava = @'
package dev.vfxweaver.client.effect;

import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXTimeline;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** "Never ends" must be the lifecycle flag, not a 2^31-tick timeline. */
public final class LifecycleCheck {
	public static void main(final String[] args) {
		require(VFXEffectManager.resolveTimelineDuration(true, 0, 40) == 40, "loop period is the definition duration");
		require(VFXEffectManager.resolveTimelineDuration(true, 200, 40) == 40, "loop ignores a payload duration");
		require(VFXEffectManager.resolveTimelineDuration(false, 0, 40) == 40, "persistent non-loop uses the definition duration");
		require(VFXEffectManager.resolveTimelineDuration(false, 0, 90) == 90, "definition default is honoured");
		require(VFXEffectManager.resolveTimelineDuration(false, 200, 40) == 200, "a positive payload duration wins");
		final Identifier id = Identifier.fromNamespaceAndPath("vfxweaver", "check");
		final VFXActiveEffect persistent = new VFXActiveEffect(id, VFXEffectType.PARTICLES, 1L, 0L, 0.0F, new VFXTimeline(40.0F, Map.of()), 10, false, true, List.of(), List.of(), List.of(), null, null, null, null);
		persistent.update(10_000.0F);
		require(!persistent.isFinished(), "a persistent non-loop instance must not finish at/after its timeline duration");
		final VFXActiveEffect finite = new VFXActiveEffect(id, VFXEffectType.PARTICLES, 2L, 0L, 0.0F, new VFXTimeline(40.0F, Map.of()), 0, false, false, List.of(), List.of(), List.of(), null, null, null, null);
		finite.update(40.0F);
		require(finite.isFinished(), "a finite instance finishes at its timeline duration");
		System.out.println("lifecycle check OK: durations resolve to finite values and persistent never finishes");
	}

	private static void require(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
'@
$serverEffectsJava = @'
package dev.vfxweaver.effect;

/** A stored effect must resume at the age it had when the player left; offline time never advances it. */
public final class ServerEffectsCheck {
	public static void main(final String[] args) {
		final long start = 1_000L;
		final long disconnect = start + 30_000L; // 30 s in = 600 ticks
		require(VFXServerEffects.elapsedTicksAt(start, disconnect) == 600, "age at disconnect");
		// The disconnect instant is the resume clock: the age is frozen, not advanced by the away time.
		final long away = disconnect + 6L * 60L * 60L * 1000L; // six hours later
		final long resume = VFXServerEffects.resumeClock(disconnect, away);
		require(resume == disconnect, "offline time is not counted");
		require(VFXServerEffects.elapsedTicksAt(start, resume) == 600, "resumed age equals age at disconnect");
		require(VFXServerEffects.resumeClock(0L, away) == away, "the online clock still advances");
		// Finite effects: remaining is what was left, and an already-finished one does not come back.
		require(VFXServerEffects.remainingTicksFor(1200, 600, 0) == 600, "remaining = duration - frozen age");
		require(VFXServerEffects.remainingTicksFor(1200, 1200, 0) == -1, "an expired finite effect is not resurrected");
		require(VFXServerEffects.remainingTicksFor(-1, 600, 0) == -1, "a persistent effect has no remaining");
		require(VFXServerEffects.remainingTicksFor(1200, 600, 2000) == 1400, "a late keyframe extends the lifetime");
		// The global offline bound expires old entries and keeps fresh ones.
		require(!VFXServerEffects.isOfflineExpired(1_000L, 1_000L + VFXServerEffects.MAX_OFFLINE_MILLIS), "an offline entry inside the window is kept");
		require(VFXServerEffects.isOfflineExpired(1_000L, 1_000L + VFXServerEffects.MAX_OFFLINE_MILLIS + 1L), "an offline entry past the window is pruned");
		require(!VFXServerEffects.isOfflineExpired(0L, Long.MAX_VALUE), "an online player is never offline-expired");
		System.out.println("server effects check OK: resume age frozen at 600 ticks, remaining/expiry/bound hold");
	}

	private static void require(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
'@
[System.IO.File]::WriteAllText($fallbackSrc, $fallbackJava, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($lifecycleSrc, $lifecycleJava, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($serverEffectsSrc, $serverEffectsJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $fallbackSrc $lifecycleSrc $serverEffectsSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" FallbackCheck
	if ($LASTEXITCODE -ne 0) { throw "FallbackCheck failed" }
	& $java "@$cpFile" dev.vfxweaver.client.effect.LifecycleCheck
	if ($LASTEXITCODE -ne 0) { throw "LifecycleCheck failed" }
	& $java "@$cpFile" dev.vfxweaver.effect.ServerEffectsCheck
	if ($LASTEXITCODE -ne 0) { throw "ServerEffectsCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Effect lifecycle / dispatch check OK."
exit 0

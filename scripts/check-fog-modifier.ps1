# Dev-only guard for the fog_modifier effect (2026-09-24, corrected 2026-09-25).
#
# fog_modifier mirrors fov_modifier: it is a value modifier at the SOURCE of the vanilla fog, not a
# post pass. The vanilla fog's six distances + colour reach the shaders through the Fog UBO written
# by the private FogRenderer.updateBuffer(ByteBuffer, int, Vector4f, float x6). How the modifier
# reaches that write differs per node:
#   26.x     : setupFog assembles a FogData, then the public updateBuffer(FogData) serialises it ->
#              the mixin @ModifyVariable's that entry (arg 0) and returns a NEW FogData with every
#              start/end scaled (including skyEnd/cloudEnd) and the colour replaced.
#   1.21.11  : there is no public FogData entry - setupFog builds a local FogData and calls the
#              private write inline - so the mixin @ModifyArgs' the private call's six float slots.
# This check asserts:
#   * the effect type exists with its neutral values and is excluded from isPostProcessing();
#   * the manager walks the active effects and reads the six params (incl. fog_color_amount);
#   * fog_color_amount scales the colour weight (NaN = 1.0), so amount 0 leaves the colour
#     untouched with hasColor false and amount 0.5 is midway - the distance scales are unaffected;
#   * the mixin is registered, the 26.x hook is @ModifyVariable on updateBuffer(FogData) returning a
#     new instance (no in-place mutation) and the 1.21.11 hook is @ModifyArgs on the private write;
#   * every scaled end including skyEnd/cloudEnd is present, and the degenerate-range guard exists;
#   * `javap -s` on each node's REAL jar proves the target entries/fields exist (the build does not
#     validate mixin targets, so a stale target fails here, not at game start);
#   * the MC-free combination helper (dev.vfxweaver.util.VFXFogModifier) behaves runnably.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-fog-modifier.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$typePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$helperPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\util\VFXFogModifier.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\effect\VFXEffectManager.java"
$mixinPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\mixin\FogRendererMixin.java"
$mixinsJsonPath = Join-Path $repoRoot "src\client\resources\vfxweaver.client.mixins.json"

$type = if (Test-Path $typePath) { [System.IO.File]::ReadAllText($typePath) } else { "" }
$helper = if (Test-Path $helperPath) { [System.IO.File]::ReadAllText($helperPath) } else { "" }
$manager = if (Test-Path $managerPath) { [System.IO.File]::ReadAllText($managerPath) } else { "" }
$mixin = if (Test-Path $mixinPath) { [System.IO.File]::ReadAllText($mixinPath) } else { "" }
$mixinsJson = if (Test-Path $mixinsJsonPath) { [System.IO.File]::ReadAllText($mixinsJsonPath) } else { "" }

$problems = New-Object System.Collections.Generic.List[string]

# The real shared descriptor: the private UBO writer on every node.
$targetDescriptor = 'updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V'
# The enclosing method that holds that call, per node.
$enclosing26 = 'updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V'
$enclosing12111 = 'setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lorg/joml/Vector4f;'

# --- 1. effect type: enum entry, neutral values, isPostProcessing exclusion ------------------------
if ($type -notmatch 'FOG_MODIFIER\("fog_modifier"\)') {
	$problems.Add('VFXEffectType has no FOG_MODIFIER("fog_modifier") entry')
}
if ($type -notmatch '(?s)case FOG_MODIFIER ->.*?fog_start_scale.*?1\.0F') {
	$problems.Add("VFXEffectType.neutralValue has no FOG_MODIFIER case with fog_start_scale = 1.0F")
}
if ($type -notmatch '(?s)case FOG_MODIFIER ->.*?fog_end_scale.*?1\.0F') {
	$problems.Add("VFXEffectType.neutralValue FOG_MODIFIER case does not set fog_end_scale = 1.0F")
}
if ($type -notmatch '(?s)case FOG_MODIFIER ->.*?fog_color_amount.*?Float\.NaN') {
	$problems.Add("VFXEffectType.neutralValue FOG_MODIFIER case does not set fog_color_amount = NaN (unauthored)")
}
if ($type -notmatch 'this != FOV_MODIFIER && this != FOG_MODIFIER') {
	$problems.Add("VFXEffectType.isPostProcessing does not exclude FOG_MODIFIER")
}

# --- 2. manager: accumulates the active fog_modifier contributions ---------------------------------
if ($manager -notmatch 'getActiveFogContributions') {
	$problems.Add("VFXEffectManager has no getActiveFogContributions() accumulation")
}
if ($manager -notmatch 'VFXEffectType\.FOG_MODIFIER') {
	$problems.Add("VFXEffectManager does not filter for VFXEffectType.FOG_MODIFIER")
}
foreach ($param in @('fog_start_scale', 'fog_end_scale', 'fog_r', 'fog_g', 'fog_b', 'fog_color_amount')) {
	if ($manager -notmatch [regex]::Escape("getParam(`"$param`"")) {
		$problems.Add("VFXEffectManager does not read the '$param' param")
	}
}

# --- 3. mixin registered + the per-node hooks + the corrected semantics ----------------------------
if ($mixinsJson -notmatch '"FogRendererMixin"') {
	$problems.Add("vfxweaver.client.mixins.json does not register FogRendererMixin")
}
# Split the one guarded file into the 1.21.11 (<26.1) branch and the 26.x (else) branch. The file
# guards the imports too (an earlier if/else), so take the LAST match: the method-level guard.
$legacyMatches = [regex]::Matches($mixin, '(?s)//\? if <26\.1 \{(.*?)//\?\} else \{')
$modernMatches = [regex]::Matches($mixin, '(?s)//\?\} else \{(.*?)//\?\}')
$legacyBranch = if ($legacyMatches.Count -gt 0) { $legacyMatches[$legacyMatches.Count - 1].Groups[1].Value } else { '' }
$modernBranch = if ($modernMatches.Count -gt 0) { $modernMatches[$modernMatches.Count - 1].Groups[1].Value } else { '' }
if (-not $legacyBranch) { $problems.Add("FogRendererMixin has no '//? if <26.1' guarded branch (1.21.11)") }
if (-not $modernBranch) { $problems.Add("FogRendererMixin has no '//?} else {' guarded branch (26.x)") }

# 26.x: @ModifyVariable at HEAD of the public updateBuffer(FogData) entry, returning a NEW FogData.
if ($modernBranch -notmatch '@ModifyVariable') {
	$problems.Add("26.x fog hook is not @ModifyVariable (must modify the FogData entry, not the private transport)")
}
if ($modernBranch -notmatch [regex]::Escape($enclosing26)) {
	$problems.Add("26.x fog hook does not target the public '$enclosing26' entry")
}
if ($modernBranch -match '@ModifyArgs') {
	$problems.Add("26.x fog hook still uses @ModifyArgs on the private transport")
}
if ($modernBranch -notmatch 'new FogData\(\)') {
	$problems.Add("26.x fog hook does not construct a new FogData (must not mutate the incoming instance)")
}
if ($modernBranch -match 'data\.[A-Za-z]+\s*=') {
	$problems.Add("26.x fog hook mutates the incoming FogData in place")
}
foreach ($band in @('environmentalStart', 'environmentalEnd', 'renderDistanceStart', 'renderDistanceEnd', 'skyEnd', 'cloudEnd')) {
	if ($modernBranch -notmatch [regex]::Escape($band)) {
		$problems.Add("26.x fog hook does not scale '$band' (all four distances + skyEnd + cloudEnd)")
	}
}

# 1.21.11: @ModifyArgs on the private transport call inside setupFog, corrected semantics.
if ($legacyBranch -notmatch '@ModifyArgs') {
	$problems.Add("1.21.11 fog hook is not @ModifyArgs on the private transport")
}
if ($legacyBranch -notmatch [regex]::Escape($targetDescriptor)) {
	$problems.Add("1.21.11 fog hook does not target the real private '$targetDescriptor'")
}
if ($legacyBranch -notmatch [regex]::Escape($enclosing12111)) {
	$problems.Add("1.21.11 fog hook does not wrap '$enclosing12111'")
}
if ($legacyBranch -notmatch 'args\.set\(7' -or $legacyBranch -notmatch 'args\.set\(8') {
	$problems.Add("1.21.11 fog hook does not scale the private slots 7/8 (skyEnd/cloudEnd)")
}

# The degenerate-range guard must exist (shared, runnable) - never mutate the incoming FogData.
if ($mixin -notmatch 'pullBelow') {
	$problems.Add("fog hook has no degenerate-range guard (pullBelow: start >= end -> just below end)")
}
if ($helper -notmatch 'pullBelow' -or $helper -notmatch 'nextDown') {
	$problems.Add("VFXFogModifier has no runnable pullBelow degenerate-range guard")
}

# --- 4. javap -s: each node's REAL jar carries the target and the enclosing method -----------------
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
	$problems.Add("fog_modifier check: no javap found (set JAVA_HOME)")
} else {
	$mcCache = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft"
	# 26.x is unobfuscated (deobf jar); 1.21.11 is remapped and verified against the named jar.
	$named12111 = Get-ChildItem (Join-Path $mcCache "minecraft-clientonly") -Recurse -Filter "minecraft-clientonly-1.21.11-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
	$targets = [ordered]@{
		"26.2"    = @{ Jar = (Get-ChildItem (Join-Path $mcCache "minecraft-clientonly-deobf\26.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '\.backup$' } | Select-Object -First 1); Kind = 'Modern' }
		"26.1.2"  = @{ Jar = (Get-ChildItem (Join-Path $mcCache "minecraft-clientonly-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '\.backup$' } | Select-Object -First 1); Kind = 'Modern' }
		"1.21.11" = @{ Jar = $named12111; Kind = 'Legacy' }
	}
	foreach ($node in $targets.Keys) {
		$t = $targets[$node]
		if (-not $t.Jar) {
			$problems.Add("${node}: real minecraft jar not found (cannot verify the fog_modifier mixin target)")
			continue
		}
		# javap wraps long descriptor lines, so strip all whitespace and match the descriptor alone.
		$out = ((& $javap -classpath $t.Jar.FullName -s -p net.minecraft.client.renderer.fog.FogRenderer 2>&1 | Out-String) -replace '\s', '')
		if ($t.Kind -eq 'Modern') {
			# 26.x: the hook is @ModifyVariable on the public updateBuffer(FogData) entry.
			$entryOnly = $enclosing26.Substring($enclosing26.IndexOf('('))
			if ($out -notmatch [regex]::Escape($entryOnly)) {
				$problems.Add("${node}: FogRenderer has no public '$enclosing26' (mixin entry is stale)")
			} else {
				Write-Host "  $node : FogRenderer carries $enclosing26"
			}
			# ...and the FogData instance fields the new record is rebuilt from.
			$data = ((& $javap -classpath $t.Jar.FullName -s -p net.minecraft.client.renderer.fog.FogData 2>&1 | Out-String) -replace '\s', '')
			foreach ($field in @('environmentalStart', 'renderDistanceStart', 'environmentalEnd', 'renderDistanceEnd', 'skyEnd', 'cloudEnd', 'color')) {
				if ($data -notmatch [regex]::Escape($field)) {
					$problems.Add("${node}: FogData has no '$field' field")
				}
			}
			Write-Host "  $node : FogData carries environmentalStart/renderDistanceStart/environmentalEnd/renderDistanceEnd/skyEnd/cloudEnd/color"
		} else {
			# 1.21.11: setupFog builds the FogData and calls the private write inline.
			$targetOnly = $targetDescriptor.Substring($targetDescriptor.IndexOf('('))
			if ($out -notmatch [regex]::Escape($targetOnly)) {
				$problems.Add("${node}: FogRenderer has no private '$targetDescriptor' (mixin target is stale)")
			} else {
				Write-Host "  $node : FogRenderer carries $targetDescriptor"
			}
			$enclosingOnly = $enclosing12111.Substring($enclosing12111.IndexOf('('))
			if ($out -notmatch [regex]::Escape($enclosingOnly)) {
				$problems.Add("${node}: FogRenderer has no '$enclosing12111' (mixin caller is stale)")
			} else {
				Write-Host "  $node : FogRenderer carries $enclosing12111"
			}
		}
	}
}

# --- 5. runnable combination helper ----------------------------------------------------------------
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) {
	$problems.Add("fog_modifier check: no JDK found (set JAVA_HOME)")
}

$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\util\VFXFogModifier.class"))) {
	$problems.Add("fog_modifier check: build :26.1.2 first (missing $mainClasses\dev\vfxweaver\util\VFXFogModifier.class)")
}

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "fog_modifier check failed ($($problems.Count) problem(s))."
	exit 1
}

$checkDir = Join-Path $env:TEMP "vfxweaver-fog-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "FogModifierCheck.java"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir -replace '\\', '/')"

$checkJava = @'
import dev.vfxweaver.util.VFXFogModifier;
import java.util.List;

/** Standalone assertions for the MC-free fog_modifier combination maths. */
public final class FogModifierCheck {
	private static final float NaN = Float.NaN;

	private static void expectEq(final String what, final float actual, final float expected) {
		if (Math.abs(actual - expected) > 1.0e-6F) {
			throw new AssertionError(what + ": " + actual + " != " + expected);
		}
	}

	private static void expect(final String what, final boolean ok) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}

	public static void main(final String[] args) {
		// no effects: neutral scales, vanilla colour untouched.
		VFXFogModifier.Result none = VFXFogModifier.combine(List.of(), 0.1F, 0.2F, 0.3F);
		expect("no contributions => inactive", !none.active());
		expectEq("none start", none.startScale(), 1.0F);
		expectEq("none end", none.endScale(), 1.0F);
		expect("none has no colour", !none.hasColor());
		expectEq("none r", none.r(), 0.1F);
		expectEq("none g", none.g(), 0.2F);
		expectEq("none b", none.b(), 0.3F);

		// one effect: its scale, unauthored colour leaves vanilla unchanged.
		VFXFogModifier.Result one = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(0.5F, 1.5F, NaN, NaN, NaN, NaN, 1.0F)), 0.1F, 0.2F, 0.3F);
		expect("one active", one.active());
		expectEq("one start", one.startScale(), 0.5F);
		expectEq("one end", one.endScale(), 1.5F);
		expect("one no colour", !one.hasColor());
		expectEq("one r unchanged", one.r(), 0.1F);

		// two effects: additive, weight-scaled.
		VFXFogModifier.Result two = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(2.0F, 3.0F, NaN, NaN, NaN, NaN, 1.0F),
				new VFXFogModifier.Contribution(3.0F, 0.5F, NaN, NaN, NaN, NaN, 1.0F)), 0.0F, 0.0F, 0.0F);
		expectEq("additive start", two.startScale(), 1.0F + 1.0F + 2.0F);
		expectEq("additive end", two.endScale(), 1.0F + 2.0F + (-0.5F));

		VFXFogModifier.Result weighted = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(3.0F, 3.0F, NaN, NaN, NaN, NaN, 0.5F)), 0.0F, 0.0F, 0.0F);
		expectEq("weight start", weighted.startScale(), 1.0F + (3.0F - 1.0F) * 0.5F);
		expectEq("weight end", weighted.endScale(), 1.0F + (3.0F - 1.0F) * 0.5F);

		// colour: weighted average blended from vanilla, order-independent, clamped.
		VFXFogModifier.Contribution red = new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, NaN, 1.0F);
		VFXFogModifier.Contribution green = new VFXFogModifier.Contribution(1.0F, 1.0F, 0.0F, 1.0F, 0.0F, NaN, 1.0F);
		VFXFogModifier.Result ab = VFXFogModifier.combine(List.of(red, green), 0.5F, 0.5F, 0.5F);
		VFXFogModifier.Result ba = VFXFogModifier.combine(List.of(green, red), 0.5F, 0.5F, 0.5F);
		expect("colour active", ab.hasColor());
		expectEq("colour r", ab.r(), 0.5F);
		expectEq("colour g", ab.g(), 0.5F);
		expectEq("colour b", ab.b(), 0.0F);
		expectEq("colour order r", ba.r(), ab.r());
		expectEq("colour order g", ba.g(), ab.g());
		expectEq("colour order b", ba.b(), ab.b());

		// clamped: an authored component past 1 lands on 1, a negative one on 0.
		VFXFogModifier.Result clamped = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(1.0F, 1.0F, 2.0F, -1.0F, 0.5F, NaN, 1.0F)), 0.2F, 0.2F, 0.2F);
		expectEq("clamp r", clamped.r(), 1.0F);
		expectEq("clamp g", clamped.g(), 0.0F);
		expectEq("clamp b", clamped.b(), 0.5F);

		// fog_color_amount scales the COLOUR only: 1 (or NaN) = full target, 0.5 = midway,
		// 0 = vanilla and hasColor false; the distance scales are untouched.
		VFXFogModifier.Result full = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F)), 0.0F, 0.0F, 0.0F);
		expect("amount 1 has colour", full.hasColor());
		expectEq("amount 1 r", full.r(), 1.0F);
		expectEq("amount 1 start neutral", full.startScale(), 1.0F);
		expectEq("amount 1 end neutral", full.endScale(), 1.0F);

		VFXFogModifier.Result halfAmount = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.5F, 1.0F)), 0.0F, 0.0F, 0.0F);
		expect("amount 0.5 has colour", halfAmount.hasColor());
		expectEq("amount 0.5 r midway", halfAmount.r(), 0.5F);

		VFXFogModifier.Result zeroAmount = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F)), 0.4F, 0.4F, 0.4F);
		expect("amount 0 no colour", !zeroAmount.hasColor());
		expectEq("amount 0 r untouched", zeroAmount.r(), 0.4F);

		// an unauthored amount (NaN) with an authored colour behaves as 1.0 (today's full colour).
		VFXFogModifier.Result unsetAmount = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, NaN, 1.0F)), 0.0F, 0.0F, 0.0F);
		expect("unauthored amount has colour", unsetAmount.hasColor());
		expectEq("unauthored amount r full", unsetAmount.r(), 1.0F);

		// two effects with different amounts combine order-independently (sums, not sequence).
		VFXFogModifier.Contribution redFull = new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, NaN, NaN, 1.0F, 1.0F);
		VFXFogModifier.Contribution greenHalf = new VFXFogModifier.Contribution(1.0F, 1.0F, NaN, 1.0F, NaN, 0.5F, 1.0F);
		VFXFogModifier.Result rg = VFXFogModifier.combine(List.of(redFull, greenHalf), 0.0F, 0.0F, 0.0F);
		VFXFogModifier.Result gr = VFXFogModifier.combine(List.of(greenHalf, redFull), 0.0F, 0.0F, 0.0F);
		expectEq("amount order r", gr.r(), rg.r());
		expectEq("amount order g", gr.g(), rg.g());
		expectEq("amount order b", gr.b(), rg.b());
		expectEq("amount mixed r full", rg.r(), 1.0F);
		expectEq("amount mixed g half", rg.g(), 0.5F);
		expect("amount result finite", Float.isFinite(rg.r()) && Float.isFinite(rg.g()) && Float.isFinite(rg.b()));

		// fading out (weight 0) => scales exactly neutral, colour off.
		VFXFogModifier.Result faded = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(0.5F, 0.5F, 1.0F, 0.0F, 0.0F, NaN, 0.0F)), 0.4F, 0.5F, 0.6F);
		expectEq("faded start", faded.startScale(), 1.0F);
		expectEq("faded end", faded.endScale(), 1.0F);
		expect("faded no colour", !faded.hasColor());
		expectEq("faded r unchanged", faded.r(), 0.4F);

		// degenerate-range guard: an inverted scaled pair is pulled just below the end.
		expectEq("pullBelow healthy", VFXFogModifier.pullBelow(1.0F, 3.0F), 1.0F);
		expectEq("pullBelow inverted", VFXFogModifier.pullBelow(5.0F, 3.0F), Math.nextDown(3.0F));
		expectEq("pullBelow equal", VFXFogModifier.pullBelow(3.0F, 3.0F), Math.nextDown(3.0F));

		System.out.println("fog_modifier combination checks OK");
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac -cp $cp -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java -cp $cp FogModifierCheck
	if ($LASTEXITCODE -ne 0) { throw "fog_modifier combination check failed" }
} finally {
	Pop-Location
}

Write-Host "fog_modifier OK: type + neutral values (incl. fog_color_amount = NaN) + isPostProcessing exclusion, manager accumulation, per-node hooks (26.x @ModifyVariable on FogData returning a new instance incl. skyEnd/cloudEnd + degenerate guard; 1.21.11 @ModifyArgs slots), real-jar targets (javap -s), colour-amount combination maths (amount 0/0.5/1, NaN = 1.0, order-independent)."
exit 0

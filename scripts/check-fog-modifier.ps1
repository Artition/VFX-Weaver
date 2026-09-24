# Dev-only guard for the fog_modifier effect (2026-09-24).
#
# fog_modifier mirrors fov_modifier: it is a value modifier at the SOURCE of the vanilla fog, not a
# post pass. The one write point that all three nodes share is the private
# FogRenderer.updateBuffer(ByteBuffer, int, Vector4f, float x6) that serialises the fog UBO the
# shaders (and our own entity pipelines) read. The mixin modifies the arguments of that call:
#   26.x     : the call sits in FogRenderer.updateBuffer(FogData)
#   1.21.11  : setupFog returns the colour and writes the UBO inline, so the call sits in setupFog
# This check asserts:
#   * the effect type exists with its neutral values and is excluded from isPostProcessing();
#   * the manager walks the active effects and reads the five params;
#   * the mixin is registered and names the REAL descriptor on each node;
#   * `javap -s` on each node's REAL jar proves the target and the enclosing method exist (a stale
#     target fails here, not at game start - the build does not validate mixin targets);
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
foreach ($param in @('fog_start_scale', 'fog_end_scale', 'fog_r', 'fog_g', 'fog_b')) {
	if ($manager -notmatch [regex]::Escape("getParam(`"$param`"")) {
		$problems.Add("VFXEffectManager does not read the '$param' param")
	}
}

# --- 3. mixin registered + source declares the per-node targets ------------------------------------
if ($mixinsJson -notmatch '"FogRendererMixin"') {
	$problems.Add("vfxweaver.client.mixins.json does not register FogRendererMixin")
}
if ($mixin -notmatch [regex]::Escape($targetDescriptor)) {
	$problems.Add("FogRendererMixin does not target the real '$targetDescriptor' descriptor")
}
if ($mixin -notmatch [regex]::Escape($enclosing26)) {
	$problems.Add("FogRendererMixin does not wrap the 26.x '$enclosing26' caller")
}
if ($mixin -notmatch [regex]::Escape($enclosing12111)) {
	$problems.Add("FogRendererMixin does not wrap the 1.21.11 '$enclosing12111' caller")
}
if ($mixin -notmatch '@ModifyArgs') {
	$problems.Add("FogRendererMixin does not use @ModifyArgs on the fog UBO write")
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
		"26.2"    = @{ Jar = (Get-ChildItem (Join-Path $mcCache "minecraft-clientonly-deobf\26.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '\.backup$' } | Select-Object -First 1); Enclosing = $enclosing26 }
		"26.1.2"  = @{ Jar = (Get-ChildItem (Join-Path $mcCache "minecraft-clientonly-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '\.backup$' } | Select-Object -First 1); Enclosing = $enclosing26 }
		"1.21.11" = @{ Jar = $named12111; Enclosing = $enclosing12111 }
	}
	foreach ($node in $targets.Keys) {
		$t = $targets[$node]
		if (-not $t.Jar) {
			$problems.Add("${node}: real minecraft jar not found (cannot verify the fog_modifier mixin target)")
			continue
		}
		# javap wraps long descriptor lines, so strip all whitespace and match the descriptor alone.
		$out = ((& $javap -classpath $t.Jar.FullName -s -p net.minecraft.client.renderer.fog.FogRenderer 2>&1 | Out-String) -replace '\s', '')
		$targetOnly = $targetDescriptor.Substring($targetDescriptor.IndexOf('('))
		if ($out -notmatch [regex]::Escape($targetOnly)) {
			$problems.Add("${node}: FogRenderer has no private '$targetDescriptor' (mixin target is stale)")
		} else {
			Write-Host "  $node : FogRenderer carries $targetDescriptor"
		}
		$enclosingOnly = $t.Enclosing.Substring($t.Enclosing.IndexOf('('))
		if ($out -notmatch [regex]::Escape($enclosingOnly)) {
			$problems.Add("${node}: FogRenderer has no '$($t.Enclosing)' (mixin caller is stale)")
		} else {
			Write-Host "  $node : FogRenderer carries $($t.Enclosing)"
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
			List.of(new VFXFogModifier.Contribution(0.5F, 1.5F, NaN, NaN, NaN, 1.0F)), 0.1F, 0.2F, 0.3F);
		expect("one active", one.active());
		expectEq("one start", one.startScale(), 0.5F);
		expectEq("one end", one.endScale(), 1.5F);
		expect("one no colour", !one.hasColor());
		expectEq("one r unchanged", one.r(), 0.1F);

		// two effects: additive, weight-scaled.
		VFXFogModifier.Result two = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(2.0F, 3.0F, NaN, NaN, NaN, 1.0F),
				new VFXFogModifier.Contribution(3.0F, 0.5F, NaN, NaN, NaN, 1.0F)), 0.0F, 0.0F, 0.0F);
		expectEq("additive start", two.startScale(), 1.0F + 1.0F + 2.0F);
		expectEq("additive end", two.endScale(), 1.0F + 2.0F + (-0.5F));

		VFXFogModifier.Result weighted = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(3.0F, 3.0F, NaN, NaN, NaN, 0.5F)), 0.0F, 0.0F, 0.0F);
		expectEq("weight start", weighted.startScale(), 1.0F + (3.0F - 1.0F) * 0.5F);
		expectEq("weight end", weighted.endScale(), 1.0F + (3.0F - 1.0F) * 0.5F);

		// colour: weighted average blended from vanilla, order-independent, clamped.
		VFXFogModifier.Contribution red = new VFXFogModifier.Contribution(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
		VFXFogModifier.Contribution green = new VFXFogModifier.Contribution(1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F);
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
			List.of(new VFXFogModifier.Contribution(1.0F, 1.0F, 2.0F, -1.0F, 0.5F, 1.0F)), 0.2F, 0.2F, 0.2F);
		expectEq("clamp r", clamped.r(), 1.0F);
		expectEq("clamp g", clamped.g(), 0.0F);
		expectEq("clamp b", clamped.b(), 0.5F);

		// fading out (weight 0) => scales exactly neutral, colour off.
		VFXFogModifier.Result faded = VFXFogModifier.combine(
			List.of(new VFXFogModifier.Contribution(0.5F, 0.5F, 1.0F, 0.0F, 0.0F, 0.0F)), 0.4F, 0.5F, 0.6F);
		expectEq("faded start", faded.startScale(), 1.0F);
		expectEq("faded end", faded.endScale(), 1.0F);
		expect("faded no colour", !faded.hasColor());
		expectEq("faded r unchanged", faded.r(), 0.4F);

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

Write-Host "fog_modifier OK: type + neutral values + isPostProcessing exclusion, manager accumulation, per-node mixin targets (javap -s), helper maths."
exit 0

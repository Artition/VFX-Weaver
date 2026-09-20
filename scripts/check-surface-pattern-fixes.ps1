# Dev-only guard for the 2026-09-20 surface_pattern fixes batch.
#
# Covers:
#   * the depth gate is consistent: the pass is registered on >=26.2 only (the reversed-depth recipe
#     is verified on 26.2), matching depthRecipeVerified() and the mask gate; the stale "verified on
#     26.1.2" comment and the guide claim are gone;
#   * layer 0 refreshes the effect clock and the camera/player snapshots (the layer-0 hook calls
#     vfxweaver$updateFrame; the FogRenderer hook does not advance/update, so the clock cannot
#     double-advance);
#   * normal_mask == 1.0 cannot reach the degenerate smoothstep(edge0 == edge1, ...);
#   * distort's phase is an in-plane coordinate, not dot(world, normal);
#   * the parse fixes: an empty faces array, pattern.center_x/y/z, an entity-anchored position on a
#     surface_pattern and an invalid texture id are per-file errors, and band_softness is clamped to
#     half the band width.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-surface-pattern-fixes.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$mixinPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\mixin\GameRendererMixin.java"
$patternPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\surface_pattern.fsh"
$selectionPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\field\VFXSurfaceSelection.java"
$shapePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\field\VFXShape.java"
$texturePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\field\VFXTexture.java"
$definitionPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXDefinition.java"
$guidePath = Join-Path $repoRoot "docs\GUIDE.md"

$programs = [System.IO.File]::ReadAllText($programsPath)
$manager = [System.IO.File]::ReadAllText($managerPath)
$mixin = [System.IO.File]::ReadAllText($mixinPath)
$pattern = [System.IO.File]::ReadAllText($patternPath)
$selection = [System.IO.File]::ReadAllText($selectionPath)
$shape = [System.IO.File]::ReadAllText($shapePath)
$texture = [System.IO.File]::ReadAllText($texturePath)
$definition = [System.IO.File]::ReadAllText($definitionPath)
$guide = [System.IO.File]::ReadAllText($guidePath)

$problems = New-Object System.Collections.Generic.List[string]

# --- 1. depth gate: >=26.2 registration, no 26.1.2 "verified" claim -------------------------------
if ($programs -notmatch '//\? if >=26\.2 \{\r?\n\t\t/\*registerDepthPost\(VFXEffectType\.SURFACE_PATTERN') {
	$problems.Add("registerDepthPost(SURFACE_PATTERN) is not guarded by '//? if >=26.2' (the depth recipe is 26.2-only)")
}
if ($programs -match 'verified on 26\.1\.2') {
	$problems.Add("VFXShaderPrograms still claims the depth recipe was verified on 26.1.2")
}
if ($manager -notmatch '(?s)depthRecipeVerified\(\)[\s\S]{0,400}?//\? if >=26\.2 \{\s*/\*return true;\s*\*///\?\} else \{\s*return false;') {
	$problems.Add("depthRecipeVerified() is not in the active-node form (the >=26.2 branch must be commented so 26.1.2 returns false; the uncommented on-disk form is what the active node compiles)")
}
if ($guide -match 'renders on Minecraft 26\.1\.2\+ only') {
	$problems.Add("docs/GUIDE.md still promises surface_pattern renders on 26.1.2+")
}
if ($guide -notmatch 'draws nothing') {
	$problems.Add("docs/GUIDE.md does not state that surface_pattern draws nothing off 26.2")
}

# --- 2. clock/order: layer 0 updates the frame; the FogRenderer hook does not ---------------------
if ($mixin -notmatch 'vfxweaver\$renderLayer0\([\s\S]{0,400}?vfxweaver\$updateFrame\(minecraft, deltaTracker\)') {
	$problems.Add('GameRendererMixin layer-0 hook does not refresh the frame (vfxweaver$updateFrame)')
}
$frameStart = $mixin.IndexOf('private void vfxweaver$updateFrame(')
$frameBody = if ($frameStart -ge 0) { $mixin.Substring($frameStart) } else { "" }
if (-not ($frameBody -match 'VFXEffectManager\.get\(\)\.advance\(deltaTicks\);' -and $frameBody -match 'VFXEffectManager\.get\(\)\.update\(\);')) {
	$problems.Add('vfxweaver$updateFrame does not advance and update the effect clock')
}
# The FogRenderer.endFrame hook (vfxweaver$render) must not advance/update again.
$renderStart = $mixin.IndexOf('private void vfxweaver$render(')
$renderEnd = $mixin.IndexOf('private void vfxweaver$renderLayer0(')
$renderBody = if ($renderStart -ge 0 -and $renderEnd -gt $renderStart) { $mixin.Substring($renderStart, $renderEnd - $renderStart) } else { "" }
if ($renderBody -match '\.advance\(|\.update\(\)|updatePlayerState\(') {
	$problems.Add("the FogRenderer hook still advances/updates the clock (double-advance)")
}

# --- 3. normal_mask == 1.0 cannot reach a degenerate smoothstep -----------------------------------
if ($pattern -notmatch 'float nm = clamp\(normal_mask, 0\.0, 1\.0\);') {
	$problems.Add("surface_pattern.fsh does not clamp normal_mask to [0,1]")
}
if ($pattern -notmatch 'nmUpper > nm \? smoothstep\(nm, nmUpper, abs\(nRaw\.y\)\)') {
	$problems.Add("surface_pattern.fsh does not guard the collapsed smoothstep edge (nmUpper > nm)")
}

# --- 4. distort phase is in-plane ------------------------------------------------------------------
if ($pattern -match 'float phase = dot\(world, n\);') {
	$problems.Add("surface_pattern.fsh still derives distort's phase from dot(world, n) (constant on a flat surface)")
}
if ($pattern -notmatch 'float phase = p\.x \+ p\.y;') {
	$problems.Add("surface_pattern.fsh does not drive distort's phase from the in-plane coordinate")
}

# --- 5. parse fixes --------------------------------------------------------------------------------
if ($selection -notmatch "array\.isEmpty\(\)") {
	$problems.Add("VFXSurfaceSelection.parse does not reject an empty 'faces' array")
}
if ($selection -notmatch 'Math\.min\(bandSoftness, \(max - min\) \* 0\.5F\)') {
	$problems.Add("VFXSurfaceSelection.parse does not clamp band_softness to half the band width")
}
if ($shape -notmatch '"center_x"\.equals\(key\)') {
	$problems.Add("VFXShape.parse does not reject pattern.center_x/y/z")
}
if ($texture -notmatch 'Identifier\.tryParse\(id\)') {
	$problems.Add("VFXTexture.parse does not validate the texture 'id' syntax")
}
if ($definition -notmatch 'type == VFXEffectType\.SURFACE_PATTERN && !entityAnchors\.isEmpty\(\)') {
	$problems.Add("VFXDefinition.parse does not reject an entity-anchored position on a surface_pattern")
}

# --- runnable parse assertions ---------------------------------------------------------------------
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) {
	$problems.Add("surface_pattern fixes check: no JDK found (set JAVA_HOME)")
}

$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\field\VFXSurfaceSelection.class"))) {
	$problems.Add("surface_pattern fixes check: build :26.1.2 first (missing $mainClasses)")
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	$problems.Add("surface_pattern fixes check: minecraft merged-deobf 26.1.2 jar not found")
}

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "surface_pattern fixes check failed ($($problems.Count) problem(s))."
	exit 1
}

$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'
$checkDir = Join-Path $env:TEMP "vfxweaver-surface-fixes-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "SurfaceParseCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.field.VFXShape;
import dev.vfxweaver.field.VFXSurfaceSelection;
import dev.vfxweaver.field.VFXTexture;
import net.minecraft.resources.Identifier;

/** Standalone assertions for the surface_pattern parse fixes. */
public final class SurfaceParseCheck {
	private static void expectThrow(final String what, final Runnable action) {
		try {
			action.run();
		} catch (final RuntimeException e) {
			System.out.println("OK " + what + " rejected: " + e.getMessage());
			return;
		}
		throw new AssertionError("expected " + what + " to be rejected");
	}

	private static void expectEq(final String what, final float actual, final float expected) {
		if (Math.abs(actual - expected) > 1.0e-6F) {
			throw new AssertionError(what + ": " + actual + " != " + expected);
		}
		System.out.println("OK " + what + " = " + actual);
	}

	public static void main(final String[] args) {
		expectThrow("faces:[]", () -> VFXSurfaceSelection.parse(JsonParser.parseString("{\"faces\":[]}").getAsJsonObject()));
		expectEq("band_softness clamp (10..10.5, softness 4)",
			VFXSurfaceSelection.parse(JsonParser.parseString("{\"faces\":[\"up\"],\"min\":10,\"max\":10.5,\"band_softness\":4}").getAsJsonObject()).bandSoftness(), 0.25F);
		expectEq("band_softness under half unchanged",
			VFXSurfaceSelection.parse(JsonParser.parseString("{\"faces\":[\"up\"],\"min\":10,\"max\":12,\"band_softness\":0.5}").getAsJsonObject()).bandSoftness(), 0.5F);
		expectEq("band_softness default",
			VFXSurfaceSelection.parse(JsonParser.parseString("{\"faces\":[\"up\"]}").getAsJsonObject()).bandSoftness(), 0.0F);
		expectThrow("pattern.center_x", () -> VFXShape.parse(JsonParser.parseString("{\"center_x\":0.5}").getAsJsonObject()));
		expectThrow("bad pattern.texture id", () -> VFXTexture.parse(JsonParser.parseString("{\"id\":\"Not A Valid Id\"}").getAsJsonObject()));
		expectThrow("entity anchor on surface_pattern", () -> VFXDefinition.parse(Identifier.fromNamespaceAndPath("vfxweaver", "check"),
			JsonParser.parseString("{\"type\":\"surface_pattern\",\"positions\":[{\"entity\":\"@e\",\"offset\":[0,0,0]}]}").getAsJsonObject()));
		VFXShape.parse(JsonParser.parseString("{\"center\":[1,2,3],\"figure\":\"circle\"}").getAsJsonObject());
		System.out.println("OK pattern center array still accepted");
		System.out.println("surface parse checks OK");
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" SurfaceParseCheck
	if ($LASTEXITCODE -ne 0) { throw "surface parse check failed" }
} finally {
	Pop-Location
}

Write-Host "Surface_pattern fixes OK: depth gate 26.2-only, layer-0 clock, normal_mask guard, in-plane distort, parse traps closed, band softness clamped."
exit 0

# Dev-only guard for the mask-pipeline batch (2026-09-20). Static assertions over the exact
# contracts this batch fixed, plus a compiled standalone check:
#   * MaskParserCheck - the parser must reject a right-nested composition, a third custom leaf, a
#                       second block leaf and an invalid top-level space, and must keep the
#                       parse-time softness default on the .soft slot.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-mask-pipeline.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$parser = Read-Source (Join-Path $main "mask\VFXMaskParser.java")
$mask = Read-Source (Join-Path $main "mask\VFXMask.java")
$shapeKind = Read-Source (Join-Path $main "mask\VFXMaskShapeKind.java")
$customShape = Read-Source (Join-Path $main "mask\VFXCustomShape.java")
$registry = Read-Source (Join-Path $main "mask\VFXShapeRegistry.java")
$uniforms = Read-Source (Join-Path $client "postprocessing\VFXMaskUniforms.java")
$geometry = Read-Source (Join-Path $client "postprocessing\VFXMaskBlockGeometry.java")
$variants = Read-Source (Join-Path $client "postprocessing\VFXMaskShaderVariants.java")
$post = Read-Source (Join-Path $client "postprocessing\VFXPostProcessingManager.java")
$coverage = Read-Source (Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh")

$problems = New-Object System.Collections.Generic.List[string]

# 1) Animated softness: the writer must read the .soft slot, not the parse-time default.
if ($uniforms -notmatch 'slotValue\(mask, effect, primitive\.softnessSlot\(\), primitive\.softnessDefault\(\)\)') {
	$problems.Add("VFXMaskUniforms: rows[i][0].z does not read the animated softness slot")
}

# 2) Composition nesting: a right operand that is itself a composition must be rejected.
if ($parser -notmatch 'if \(!right\.ops\(\)\.isEmpty\(\)\)') {
	$problems.Add("VFXMaskParser: a right-nested composition is not rejected")
}

# 3) Caps: the custom-leaf and block-leaf counts must be enforced at parse.
if ($parser -notmatch 'customLeaves > VFXCustomShape\.MAX_CUSTOM_LEAVES') {
	$problems.Add("VFXMaskParser: the custom-leaf cap is not enforced")
}
if ($parser -notmatch 'blockLeaves > 1') {
	$problems.Add("VFXMaskParser: a second block leaf is not rejected")
}

# 3b) Shader guard: a bad custom row must not alias row 0.
if ($coverage -notmatch 'row < 0 \|\| row >= MASK_MAX_CUSTOM_LEAVES') {
	$problems.Add("mask_coverage.fsh: the custom-row range guard is missing")
}

# 5) Composed leaf falloff: the leaf softness must replace the hard-coded 0.25.
if ($coverage -match 'clamp\(0\.5 - d / 0\.25') {
	$problems.Add("mask_coverage.fsh: vfx_composed_leaf still hard-codes the 0.25 falloff")
}
if ($coverage -notmatch 'vfx_composed_leaf\(row, world, texCoord, so\.z\)') {
	$problems.Add("mask_coverage.fsh: vfx_composed_leaf does not receive the leaf softness")
}

# 4) Block centre: the scan centre must resolve the animated centre slots, not just the literal.
if ($geometry -notmatch 'blockCenter\(final VFXMask mask, final VFXMaskPrimitive primitive, final VFXActiveEffect effect, final VFXMaskBlockSelection selection\)') {
	$problems.Add("VFXMaskBlockGeometry.blockCenter: the mask/effect slot resolution is missing")
}
if ($geometry -notmatch 'VFXMaskUniforms\.slotValue\(mask, effect, slots\[0\], defaults\[0\]\)') {
	$problems.Add("VFXMaskBlockGeometry.blockCenter: the centre slots are not resolved through slotValue")
}

# 7) The block selection must be cached and its model quads resolved once.
if ($geometry -notmatch 'private static @Nullable SelectionCache selectionCache') {
	$problems.Add("VFXMaskBlockGeometry: the selection cache is missing")
}
if ($geometry -match 'hasBlockModelGeometry\(state\)') {
	$problems.Add("VFXMaskBlockGeometry.select: the double model resolve (hasBlockModelGeometry) is still present")
}

# 8) clearGeometry must not pretend to fail.
if ($geometry -match 'private static boolean clearGeometry\(') {
	$problems.Add("VFXMaskBlockGeometry.clearGeometry: still boolean (the caller's failure branch checks nothing)")
}

# 9) Variant re-validation: reseed must rebuild from the live base; the dead error accessor is gone.
if ($variants -notmatch 'injectedSource\(key\)') {
	$problems.Add("VFXMaskShaderVariants: reseed/compileVariant do not rebuild the injected source")
}
if ($variants -match 'public static @Nullable String error\(final String key\)') {
	$problems.Add("VFXMaskShaderVariants.error: the dead accessor is still present")
}
if ($registry -notmatch 'public void setChangeListener\(' -or $registry -notmatch 'notifyChanged\(\);') {
	$problems.Add("VFXShapeRegistry: the registration change listener is missing (stale variants on re-registration)")
}

# 6) Depth gate: the coverage prepass must fail closed when depth is not trusted; this matches the
#    surface_pattern gate and disables the depth-tested block pass.
if ($post -notmatch 'mask\.needsDepth\(\) && !depthReady') {
	$problems.Add("VFXPostProcessingManager.runCoveragePrepass: the coverage prepass is not depth-gated")
}
if ($post -notmatch 'VFXMaskBlockGeometry\.render\(encoder, geometry, mainTarget, entryMask, entry\.getValue\(\), depthReady\)') {
	$problems.Add("VFXPostProcessingManager: the block geometry pass is not depth-gated")
}

# 11) A freshly created coverage target must be cleared (an effect starting after layer 0).
if ($post -notmatch '(?s)mask coverage.*?clearTarget\(created\)') {
	$problems.Add("VFXPostProcessingManager.ensureMaskTargets: a new coverage target is not cleared")
}

# 14) Arena retirement: retired rings must survive one extra endFrame.
if ($post -notmatch '(?s)for \(final MappableRingBuffer old : this\.retiring\) \{\s*old\.close\(\);\s*\}\s*this\.retiring\.clear\(\);\s*this\.retiring\.addAll\(this\.retired\);') {
	$problems.Add("VFXPostProcessingManager.UniformArena.endFrame: retired rings are not kept one extra frame")
}

# 16) Dead code removed.
if ($mask -match 'public float softness\(\)') {
	$problems.Add("VFXMask.softness(): the unreferenced method is still present")
}
if ($shapeKind -match 'integerParameter') {
	$problems.Add("VFXMaskShapeKind.integerParameter(): the unreferenced method is still present")
}

# 10) The composed custom shape is literal-only (javadoc corrected, leaf params not threaded).
if ($customShape -notmatch 'a composed leaf''s parts are literal-only') {
	$problems.Add("VFXCustomShape: the javadoc still claims the leaf params drive the composed parts")
}

Write-Host "Mask pipeline check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "mask pipeline check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable check: compile and run MaskParserCheck ----------------------------------------
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
	Write-Error "mask pipeline check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\mask\VFXMaskParser.class"))) {
	Write-Error "mask pipeline check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "mask pipeline check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-mask-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "MaskParserCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.mask.VFXMask;

/** The mask parser's batch contracts: composition nesting, family caps, space validation, softness. */
public final class MaskParserCheck {
	public static void main(final String[] args) {
		// A right-nested composition cannot be flattened into the shader's left fold -> reject.
		rejected("right-nested composition", "{\"op\":\"union\",\"a\":{\"shape\":\"circle\",\"center\":[0.5,0.5]},\"b\":{\"op\":\"intersection\",\"a\":{\"shape\":\"circle\",\"center\":[0.5,0.5]},\"b\":{\"shape\":\"rect\",\"center\":[0.5,0.5]}}}");
		// Left-nesting is the supported form.
		final VFXMask leftNested = parse("{\"op\":\"intersection\",\"a\":{\"op\":\"union\",\"a\":{\"shape\":\"circle\",\"center\":[0.5,0.5]},\"b\":{\"shape\":\"rect\",\"center\":[0.5,0.5]}},\"b\":{\"shape\":\"polygon\",\"center\":[0.5,0.5]}}");
		require(leftNested.primitives().size() == 3, "left-nested mask leaves = " + leftNested.primitives().size() + ", want 3");
		require(leftNested.ops().size() == 2, "left-nested mask ops = " + leftNested.ops().size() + ", want 2");
		// Third custom leaf exceeds MAX_CUSTOM_LEAVES.
		rejected("third custom leaf", "{\"op\":\"union\",\"a\":{\"op\":\"union\",\"a\":{\"shape\":\"vfxweaver:ringed_volume\",\"center\":[0,64,0]},\"b\":{\"shape\":\"vfxweaver:ringed_volume\",\"center\":[0,64,0]}},\"b\":{\"shape\":\"vfxweaver:ringed_volume\",\"center\":[0,64,0]}}");
		// Second block leaf shares one scratch.
		rejected("second block leaf", "{\"op\":\"union\",\"a\":{\"shape\":\"block\",\"blocks\":[\"minecraft:stone\"],\"center\":[0,0,0],\"radius\":4},\"b\":{\"shape\":\"block\",\"blocks\":[\"minecraft:dirt\"],\"center\":[0,0,0],\"radius\":4}}");
		// A present-but-invalid top-level space must throw, not default.
		rejected("invalid top-level space", "{\"space\":\"wrold\",\"shape\":\"circle\",\"center\":[0.5,0.5]}");
		// The parse-time softness default must land on the .soft slot.
		final VFXMask explicit = parse("{\"shape\":\"circle\",\"center\":[0.5,0.5],\"softness\":0.5}");
		require(explicit.slots().get("mask.p0.soft").defaultValue() == 0.5F, "explicit softness slot default = " + explicit.slots().get("mask.p0.soft").defaultValue());
		final VFXMask implicit = parse("{\"shape\":\"circle\",\"center\":[0.5,0.5]}");
		require(implicit.slots().get("mask.p0.soft").defaultValue() == 0.01F, "screen default softness = " + implicit.slots().get("mask.p0.soft").defaultValue());
		final VFXMask world = parse("{\"shape\":\"sphere\",\"center\":[0,64,0]}");
		require(world.slots().get("mask.p0.soft").defaultValue() == 0.25F, "world default softness = " + world.slots().get("mask.p0.soft").defaultValue());
		System.out.println("mask parser check OK: right-nested/third-custom/second-block/bad-space rejected; left-nesting and softness defaults hold");
	}

	private static VFXMask parse(final String json) {
		return VFXMask.parse("check", JsonParser.parseString(json).getAsJsonObject());
	}

	private static void rejected(final String what, final String json) {
		try {
			parse(json);
			throw new AssertionError(what + " was accepted; it must throw");
		} catch (final IllegalArgumentException expected) {
			// expected
		}
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
	& $java "@$cpFile" MaskParserCheck
	if ($LASTEXITCODE -ne 0) { throw "MaskParserCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Mask pipeline check OK."
exit 0

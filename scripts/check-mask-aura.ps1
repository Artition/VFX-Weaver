$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client"
$coverage = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$parser = Read-Source (Join-Path $main "mask\VFXMaskParser.java")
$variants = Read-Source (Join-Path $client "postprocessing\VFXMaskShaderVariants.java")
$shader = Read-Source $coverage
$problems = New-Object System.Collections.Generic.List[string]

$kind7Start = $shader.IndexOf('} else if (kind == 7) {')
$kind7End = $shader.IndexOf('} else if ((kind == 4 || kind == 5)', [Math]::Max(0, $kind7Start))
if ($kind7Start -lt 0 -or $kind7End -le $kind7Start) {
	$problems.Add("mask_coverage.fsh: the kind == 7 branch is missing")
	$kind7 = ""
} else {
	$kind7 = $shader.Substring($kind7Start, $kind7End - $kind7Start)
}
if (-not $kind7.Contains('if (shape_volume[i].x <= 0.5 && isSky)')) {
	$problems.Add("mask_coverage.fsh: the custom surface path lacks the sky gate")
}
if (-not $kind7.Contains('shape_volume[i].x > 0.5') -or -not $kind7.Contains('vfx_aura_cover(')) {
	$problems.Add("mask_coverage.fsh: a kind == 7 plugin leaf cannot reach shared aura coverage")
}
if ($shader -notmatch 'vfx_aura_cover\(float d, float tEnter, float tExit, float chordEps, float softness, float occWidth, float sceneDist\)') {
	$problems.Add("mask_coverage.fsh: the shared aura coverage helper is missing")
}
$constants = @(
	@('VFX_PLUGIN_AURA_MAX_RANGE', '1024.0'),
	@('VFX_PLUGIN_AURA_ENTRY_STEPS', '40'),
	@('VFX_PLUGIN_AURA_EXIT_STEPS', '16'),
	@('VFX_PLUGIN_AURA_REFINE_STEPS', '4'),
	@('VFX_PLUGIN_AURA_MIN_STEP', '0.5'),
	@('VFX_PLUGIN_AURA_MAX_STEP', '64.0')
)
foreach ($constant in $constants) {
	if ($shader -notmatch ('#define\s+' + [regex]::Escape($constant[0]) + '\s+' + [regex]::Escape($constant[1]))) {
		$problems.Add("mask_coverage.fsh: march constant $($constant[0]) = $($constant[1]) is missing")
	}
}
$injectEnd = $shader.IndexOf('// <<< vfx_mask_custom_inject:end')
$boundsWrapper = $shader.IndexOf('vec4 vfx_custom_bounds()')
if ($injectEnd -lt 0 -or $boundsWrapper -lt 0) {
	$problems.Add("mask_coverage.fsh: vfx_custom_bounds or the injection end marker is missing")
} elseif ($boundsWrapper -lt $injectEnd) {
	$problems.Add("mask_coverage.fsh: vfx_custom_bounds must be outside the injection region")
}
if ($boundsWrapper -ge 0) {
	$wrapperEnd = $shader.IndexOf('}', $boundsWrapper)
	if ($wrapperEnd -gt 0) {
		$wrapper = $shader.Substring($boundsWrapper, $wrapperEnd - $boundsWrapper)
		if (-not $wrapper.Contains('#ifdef VFX_CUSTOM_HAS_BOUNDS') -or -not $wrapper.Contains('return vfx_shape_custom_bounds();')) {
			$problems.Add("mask_coverage.fsh: vfx_custom_bounds does not conditionally return the plugin bound")
		}
	}
}
if ($variants -notmatch 'Pattern\.compile\(.*vfx_shape_custom_bounds') {
	$problems.Add("VFXMaskShaderVariants: the bounds signature pattern is missing")
}
if ($variants -notmatch 'VFX_CUSTOM_HAS_BOUNDS 1"\s*:\s*""') {
	$problems.Add("VFXMaskShaderVariants: VFX_CUSTOM_HAS_BOUNDS is not emitted conditionally")
}
$hardError = @'
throw new IllegalArgumentException("mask: 'volume': 'aura' is not supported on a composed custom leaf ('" + shapeName + "' has no raw SDF to march); use a single-primitive custom shape.");
'@
$hardError = $hardError.Trim()
if (-not $parser.Contains($hardError)) {
	$problems.Add("VFXMaskParser: the composed-plugin aura hard error is missing")
}

Write-Host "Mask aura check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "mask aura check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) {
		$javac = Join-Path $candidate.FullName "bin\javac.exe"
		$java = Join-Path $candidate.FullName "bin\java.exe"
	}
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "mask aura check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\mask\VFXMaskParser.class"))) {
	Write-Error "mask aura check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "mask aura check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-mask-aura-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "MaskAuraCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonParser;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskVolumeMode;
import dev.vfxweaver.mask.VFXShapeRegistry;

/** The parser contracts for world GLSL-plugin aura masks. */
public final class MaskAuraCheck {
	private static final String PLUGIN_ID = "check:aura_plugin";

	public static void main(final String[] args) {
		final VFXShapeRegistry registry = VFXShapeRegistry.get();
		require(registry.registerGlsl(PLUGIN_ID, () ->
			"float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) { return 1.0; }\n"), "plugin registration failed");
		try {
			final VFXMask worldAura = parseMask("{\"shape\":\"" + PLUGIN_ID + "\",\"space\":\"world\",\"center\":[0.0,64.0,0.0],\"volume\":\"aura\"}");
			require(worldAura.primitives().get(0).volumeMode() == VFXMaskVolumeMode.AURA, "world plugin aura mode is not AURA");

			final String composedError = "mask: 'volume': 'aura' is not supported on a composed custom leaf ('vfxweaver:ringed_volume' has no raw SDF to march); use a single-primitive custom shape.";
			rejected(composedError, "{\"shape\":\"vfxweaver:ringed_volume\",\"space\":\"world\",\"center\":[0.0,64.0,0.0],\"volume\":\"aura\"}");
			rejected(null, "{\"shape\":\"" + PLUGIN_ID + "\",\"space\":\"screen\",\"center\":[0.5,0.5],\"volume\":\"aura\"}");
			rejected(null, "{\"shape\":\"rect\",\"space\":\"screen\",\"center\":[0.5,0.5],\"volume\":\"aura\"}");

			final VFXMask surface = parseMask("{\"shape\":\"" + PLUGIN_ID + "\",\"space\":\"world\",\"center\":[0.0,64.0,0.0]}");
			require(surface.primitives().get(0).volumeMode() == VFXMaskVolumeMode.SURFACE, "plugin leaf without volume is not SURFACE");

			System.out.println("mask aura check OK: world plugin aura accepted; composed/screen/rect rejected; absent volume remains surface");
		} finally {
			registry.unregister(PLUGIN_ID);
		}
	}

	private static VFXMask parseMask(final String json) {
		return VFXMask.parse("check", JsonParser.parseString(json).getAsJsonObject());
	}

	private static void rejected(final String expected, final String json) {
		try {
			parseMask(json);
			throw new AssertionError("mask was accepted; it must throw: " + json);
		} catch (final IllegalArgumentException expectedFailure) {
			if (expected != null && !expected.equals(expectedFailure.getMessage())) {
				throw new AssertionError("message = " + expectedFailure.getMessage() + ", want " + expected, expectedFailure);
			}
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
	& $java "@$cpFile" MaskAuraCheck
	if ($LASTEXITCODE -ne 0) { throw "MaskAuraCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Mask aura check OK."
exit 0

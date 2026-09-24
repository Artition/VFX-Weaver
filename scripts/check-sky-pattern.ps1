# Dev-only guard for the sky_pattern effect type (stage S3, spec 2026-09-22).
#
# sky_pattern is surface_pattern's sibling: the same `pattern` block (VFXShape / shape.glsl /
# texture.glsl / VFXTexture), the same shape and texture addressing, but the projection target is
# the sky dome instead of a depth-reconstructed world surface. It is registered as a depth post so
# the per-node VFX_DEPTH_REVERSED define is injected, and the whole pass is gated on
# VFX_DEPTH_IS_SKY so it can only ever paint far-depth sky pixels (never geometry, the hand or the
# GUI). This check asserts, statically, that the effect type, the registration, the shader gate and
# the Config order all agree, and runnably that a sky_pattern definition parses with its pattern,
# texture and dome params.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-sky-pattern.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$effectTypePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$shaderPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\sky_pattern.fsh"
$domePath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\dome.glsl"
$builtinPath = Join-Path $repoRoot "src\main\resources\data\vfxweaver\vfx\sky_pattern.json"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$effectType = Read-Source $effectTypePath
$programs = Read-Source $programsPath
$manager = Read-Source $managerPath
$dome = Read-Source $domePath
$problems = New-Object System.Collections.Generic.List[string]

# --- 1. the effect type exists with its neutral values and is a post pass ------------------------
if ($effectType -notmatch 'SKY_PATTERN\("sky_pattern"\)') {
	$problems.Add('VFXEffectType does not declare SKY_PATTERN("sky_pattern")')
}
$neutral = 'case SKY_PATTERN -> "opacity".equals(parameter) || "frame".equals(parameter) || "texture_tint".equals(parameter) ? 0.0F : Float.NaN;'
if (-not $effectType.Contains($neutral)) {
	$problems.Add("VFXEffectType.neutralValue does not give SKY_PATTERN the surface_pattern texture-surface neutrals (opacity/frame/texture_tint -> 0)")
}
if ($effectType -match 'this != SKY_PATTERN') {
	$problems.Add("SKY_PATTERN is excluded from isPostProcessing(), but it renders a fullscreen post pass")
}

# --- 2. registered as a depth post (per-node depth define, cam_pos prefix) -----------------------
if ($programs -notmatch 'registerDepthPost\(VFXEffectType\.SKY_PATTERN,\s*true,') {
	$problems.Add("VFXShaderPrograms does not register sky_pattern through the camPos depth-post path")
}
if ($programs -notmatch 'withShaderDefine\("VFX_DEPTH_REVERSED", depthReversedDefine\(\)\)') {
	$problems.Add("the depth-post builder does not inject the per-node VFX_DEPTH_REVERSED define")
}
if ($programs -notmatch 'static int depthConfigSize\(final int nameCount, final boolean hasCamPos\)') {
	$problems.Add("VFXShaderPrograms has no depthConfigSize(nameCount, hasCamPos) overload for the vec4 cam_pos prefix")
}
if ($programs -notmatch 'depthConfigSize\(params\.length, true\)') {
	$problems.Add("the camPos depth post does not size its Config with the cam_pos prefix")
}
if ($programs -notmatch 'boolean depthConfig, boolean depthHasCamPos') {
	$problems.Add("ProgramInfo does not carry the depthHasCamPos flag the writer needs")
}

# --- 3. the shader: dome projection, sky gate, Config order --------------------------------------
if (-not (Test-Path -LiteralPath $shaderPath)) {
	$problems.Add("post/sky_pattern.fsh does not exist")
	$shader = ""
} else {
	$shader = Read-Source $shaderPath
}
if ($dome -notmatch 'vec3\s+vfx_view_dir\s*\(' -or $dome -notmatch 'vec2\s+vfx_dome_uv\s*\(') {
	$problems.Add("include/dome.glsl does not expose the shared vfx_view_dir / vfx_dome_uv projection")
}
if ($shader -ne "") {
	if ($shader -notmatch '#moj_import\s*<vfxweaver:dome\.glsl>') {
		$problems.Add("post/sky_pattern.fsh does not import <vfxweaver:dome.glsl>")
	}
	if ($shader -notmatch 'vfx_view_dir\s*\(texCoord, inv_view_proj, cam_pos\.xyz\)') {
		$problems.Add("post/sky_pattern.fsh does not reconstruct the view ray with vfx_view_dir(texCoord, inv_view_proj, cam_pos.xyz)")
	}
	if ($shader -notmatch 'vfx_dome_uv\s*\(') {
		$problems.Add("post/sky_pattern.fsh does not map the view ray with vfx_dome_uv")
	}
	if ($shader -notmatch 'dome_rotation') {
		$problems.Add("post/sky_pattern.fsh has no dome_rotation handling")
	}
	if ($shader -notmatch 'VFX_DEPTH_IS_SKY\s*\(sceneDepth\)') {
		$problems.Add("post/sky_pattern.fsh does not gate on VFX_DEPTH_IS_SKY(sceneDepth)")
	}
	# the gate must be a passthrough of the untouched base, not a zero-coverage paint
	if ($shader -notmatch 'if\s*\(!VFX_DEPTH_IS_SKY\(sceneDepth\)\)\s*\{\s*fragColor = base;\s*return;\s*\}') {
		$problems.Add("post/sky_pattern.fsh does not pass non-sky pixels through untouched")
	}
	# the sky test must not be hard-coded per convention
	if ($shader -match 'sceneDepth <= 1\.0e-6' -or $shader -match 'sceneDepth >= 1\.0 - 1\.0e-6') {
		$problems.Add("post/sky_pattern.fsh hard-codes a depth convention instead of using VFX_DEPTH_IS_SKY")
	}
}

# --- 3b. Config block vs registerDepthPost: same set, same order ---------------------------------
$shaderNames = New-Object System.Collections.Generic.List[string]
$hasMat4 = $false
$hasCamPos = $false
if ($shader -ne "") {
	$config = [regex]::Match($shader, 'layout\(std140\)\s+uniform\s+Config\s*\{(?<body>.*?)\}', 'Singleline')
	if (-not $config.Success) {
		$problems.Add("post/sky_pattern.fsh has no std140 Config block")
	} else {
		foreach ($line in ($config.Groups['body'].Value -split "\r?\n")) {
			$code = ($line -replace '//.*$', '').Trim()
			if ($code -eq '' -or $code -eq ';') { continue }
			if ($code -match '^mat4\s+inv_view_proj\s*;$') { $hasMat4 = $true; continue }
			if ($code -match '^vec4\s+cam_pos\s*;$') { $hasCamPos = $true; continue }
			if ($code -match '^float\s+(\w+)\s*;$') { $shaderNames.Add($Matches[1]); continue }
			$problems.Add("post/sky_pattern.fsh Config has an unrecognised declaration: $code")
		}
	}
	if (-not $hasMat4) { $problems.Add("post/sky_pattern.fsh Config block lacks the leading 'mat4 inv_view_proj;'") }
	if (-not $hasCamPos) { $problems.Add("post/sky_pattern.fsh Config block lacks the 'vec4 cam_pos;' the view ray starts from") }
}
$call = [regex]::Match($programs, 'registerDepthPost\(VFXEffectType\.SKY_PATTERN,\s*true\s*,(?<body>.*?)\);', 'Singleline')
if (-not $call.Success) {
	$problems.Add("no registerDepthPost(SKY_PATTERN, true, ...) call to compare the Config order against")
	$javaNames = New-Object System.Collections.Generic.List[string]
} else {
	$javaNames = New-Object System.Collections.Generic.List[string]
	$body = $call.Groups['body'].Value -replace '//[^\r\n]*', ''
	foreach ($m in [regex]::Matches($body, '"([^"]+)"')) { $javaNames.Add($m.Groups[1].Value) }
}
if ($shaderNames.Count -ne $javaNames.Count) {
	$problems.Add("Config float count: shader $($shaderNames.Count) vs registerDepthPost $($javaNames.Count)")
}
# The sky_pattern Config is the figure + texture surface: 38 floats after mat4 + vec4 cam_pos.
# Bump this when a real field is appended to both sides.
$expectedFloatCount = 38
if ($shaderNames.Count -ne $expectedFloatCount) {
	$problems.Add("Config float count: shader $($shaderNames.Count) vs expected $expectedFloatCount")
}
$count = [Math]::Min($shaderNames.Count, $javaNames.Count)
for ($i = 0; $i -lt $count; $i++) {
	if ($shaderNames[$i] -ne $javaNames[$i]) {
		$problems.Add("Config slot ${i}: shader '$($shaderNames[$i])' vs registerDepthPost '$($javaNames[$i])'")
	}
}
# the texture tail must be present and the surface-only concepts absent
foreach ($name in @('shape_present', 'tex_u0', 'tex_v0', 'tex_u1', 'tex_v1', 'tex_aspect', 'tex_cols', 'tex_rows', 'tex_frame', 'tex_flags', 'tex_channel', 'texture_tint', 'tex_px_w', 'tex_px_h', 'anchor_yaw', 'anchor_pitch', 'dome_rotation')) {
	if ($shaderNames -notcontains $name) { $problems.Add("post/sky_pattern.fsh Config is missing '$name'") }
}
foreach ($name in @('normal_mask', 'face_mask', 'band_min', 'band_max', 'band_softness', 'stitch', 'center_x', 'center_y', 'center_z')) {
	if ($shaderNames -contains $name) { $problems.Add("post/sky_pattern.fsh Config carries the surface-only field '$name'") }
}

# --- 4. the writer: cam_pos after inv_view_proj, camPos-aware layout guard, resolver cases -------
if ($manager -notmatch 'if \(this\.depthHasCamPos\)\s*\{\s*builder\.putVec4\(VFXFieldEnv\.cameraX\(\), VFXFieldEnv\.cameraY\(\), VFXFieldEnv\.cameraZ\(\), 0\.0F\);\s*\}') {
	$problems.Add("VFXPostProcessingManager does not write vec4 cam_pos after inv_view_proj for a camPos depth post")
}
if ($manager -notmatch 'this\.depthHasCamPos = info\.depthHasCamPos\(\);') {
	$problems.Add("VFXPass does not carry the ProgramInfo depthHasCamPos flag")
}
if ($manager -notmatch 'final int prefixBytes = hasCamPos \? 80 : 64;') {
	$problems.Add("the std140 layout guard does not offset by the vec4 cam_pos prefix for a camPos pass")
}
$resolver = [regex]::Match($manager, 'resolveDepthValue\([\s\S]*?final float raw = switch \(param\) \{(?<body>.*?)\};', 'Singleline')
$switchBody = if ($resolver.Success) { $resolver.Groups['body'].Value } else { "" }
if ($switchBody -eq "") {
	$problems.Add("VFXPostProcessingManager has no resolveDepthValue switch to enumerate")
}
foreach ($name in @('anchor_yaw', 'anchor_pitch', 'dome_rotation')) {
	if ($switchBody -notmatch ('case\s+"' + [regex]::Escape($name) + '"')) {
		$problems.Add("resolveDepthValue has no case for the sky_pattern param '$name'")
	}
}

# --- 5. the shipped built-in exists -----------------------------------------------------------------
if (-not (Test-Path -LiteralPath $builtinPath)) {
	$problems.Add("data/vfxweaver/vfx/sky_pattern.json (the stable built-in) does not exist")
}

Write-Host "sky_pattern check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "sky_pattern check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK: type, depth-post registration, dome gate, Config order, writer cam_pos"

# --- 6. runnable: a sky_pattern definition parses with its pattern / texture / dome params --------
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
	Write-Error "sky_pattern check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXDefinition.class"))) {
	Write-Error "sky_pattern check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "sky_pattern check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-sky-pattern-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "SkyPatternCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.field.VFXTexture;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;

/** Standalone assertions for the sky_pattern effect type (stage S3). */
public final class SkyPatternCheck {
	public static void main(final String[] args) throws Exception {
		final VFXDefinition builtin = parse("vfxweaver", "sky_pattern", Files.readString(Path.of(args[0])));
		require(builtin.getType() == VFXEffectType.SKY_PATTERN, "the built-in's type is not SKY_PATTERN");
		require(builtin.getType().isPostProcessing(), "SKY_PATTERN is not a post-processing type");
		require(builtin.getPattern() != null, "the built-in has no pattern block");
		require(builtin.getPattern().texture() == null, "the built-in unexpectedly references a texture");

		final String json = "{\"type\":\"sky_pattern\",\"params\":{\"screen_layer\":0,\"anchor_yaw\":12.0,"
			+ "\"anchor_pitch\":-30.0,\"dome_rotation\":15.0,\"tile_scale\":0.5,\"opacity\":0.9,\"frame\":3},"
			+ "\"pattern\":{\"figure\":\"circle\",\"radius\":0.5,\"texture\":{\"id\":\"minecraft:block/stone\","
			+ "\"source\":\"block\",\"channel\":\"alpha\",\"sheet\":[4,4],\"aspect\":\"preserve\"}}}";
		final VFXDefinition textured = parse("vfx_demos", "check_sky_pattern", json);
		require(textured.getType() == VFXEffectType.SKY_PATTERN, "the textured type is not SKY_PATTERN");
		require(textured.getPattern() != null && textured.getPattern().texture() != null, "the textured pattern lost its texture");
		final VFXTexture texture = textured.getPattern().texture();
		require(texture.source() == VFXTexture.Source.BLOCK, "the texture source is not BLOCK");
		require(texture.sheetCols() == 4 && texture.sheetRows() == 4, "the sheet grid is not 4x4");
		require(textured.getParams().containsKey("anchor_yaw"), "the anchor_yaw param is missing");
		require(textured.getParams().containsKey("anchor_pitch"), "the anchor_pitch param is missing");
		require(textured.getParams().containsKey("dome_rotation"), "the dome_rotation param is missing");
		require(textured.getParams().containsKey("frame"), "the animatable frame param is missing");

		System.out.println("sky_pattern check OK: built-in and textured sheet parse; type/pattern/params asserted");
	}

	private static VFXDefinition parse(final String namespace, final String name, final String json) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath(namespace, name),
			JsonParser.parseString(json).getAsJsonObject());
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
	& $java "@$cpFile" SkyPatternCheck $builtinPath
	if ($LASTEXITCODE -ne 0) { throw "SkyPatternCheck failed" }
} finally {
	Pop-Location
}
Write-Host "sky_pattern check OK."
exit 0

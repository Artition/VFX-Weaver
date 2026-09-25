# Dev-only guard for the built-in WORLD GLSL plugin (vfxweaver:blobs_glsl).
#
# The shipped GLSL plugins were screen-space figures (vfxweaver:ringed_glsl), so a `space: "world"`
# leaf could not express a volume: `volume: "aura"` marched a 2D screen SDF and produced nonsense,
# and a surface leaf tinted the wrong thing. `vfxweaver:blobs_glsl` is the reference WORLD plugin:
# a union of up to 8 world-space spheres whose centres/radii come from the leaf's dynamic data
# (mask.p<N>.d<J>), with the leaf's p0..p7 as global controls, plus the optional
# vfx_shape_custom_bounds() broad phase so the aura march is cheap.
#
# Static assertions over the exact contract, plus a compiled standalone check:
#   * MaskWorldPluginCheck - the plugin is registered as a WORLD GLSL plugin, its source defines a
#     negative-inside SDF that reads vfx_mask_data and declares a vfx_shape_custom_bounds that also
#     reads the data (and the leaf params); a world aura/surface leaf using it parses, needs depth,
#     and the three demo effects parse and reference it.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-mask-world-plugin.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"
$coverage = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$registry = Read-Source (Join-Path $main "mask\VFXShapeRegistry.java")
$contract = Read-Source (Join-Path $main "mask\VFXMaskShapeGlsl.java")
$shader = Read-Source $coverage
$problems = New-Object System.Collections.Generic.List[string]

# 1) registration: a WORLD GLSL plugin, beside the screen reference (ringed_glsl must stay).
if ($registry -notmatch 'this\.plugins\.put\("vfxweaver:blobs_glsl"') {
	$problems.Add("VFXShapeRegistry: vfxweaver:blobs_glsl is not registered")
}
if ($registry -notmatch 'new VFXCustomShape\("vfxweaver:blobs_glsl",\s*VFXMaskSpace\.WORLD,\s*VFXCustomShape\.Family\.GLSL_PLUGIN') {
	$problems.Add("VFXShapeRegistry: vfxweaver:blobs_glsl is not a WORLD GLSL_PLUGIN shape")
}
if ($registry -notmatch 'this\.plugins\.put\("vfxweaver:ringed_glsl"') {
	$problems.Add("VFXShapeRegistry: the screen reference vfxweaver:ringed_glsl was removed")
}

# 2) host: the current leaf's p0/p1 must reach the plugin's bounds (declared OUTSIDE the injection
#    markers so an existing plugin that ignores them is byte-for-byte unchanged).
if ($shader -notmatch 'vec4 vfx_shape_params0 = vec4\(0\.0\);') {
	$problems.Add("mask_coverage.fsh: the vfx_shape_params0 global is missing")
}
if ($shader -notmatch 'vec4 vfx_shape_params1 = vec4\(0\.0\);') {
	$problems.Add("mask_coverage.fsh: the vfx_shape_params1 global is missing")
}
if ($shader -notmatch 'vfx_shape_params0 = shape_params0\[i\];' -or $shader -notmatch 'vfx_shape_params1 = shape_params1\[i\];') {
	$problems.Add("mask_coverage.fsh: the plugin branch does not set the leaf params globals")
}
$begin = $shader.IndexOf('// >>> vfx_mask_custom_inject:begin')
$p0 = $shader.IndexOf('vec4 vfx_shape_params0 = vec4(0.0);')
$p1 = $shader.IndexOf('vec4 vfx_shape_params1 = vec4(0.0);')
if ($begin -lt 0 -or $p0 -lt 0 -or $p1 -lt 0 -or -not ($p0 -lt $begin -and $p1 -lt $begin)) {
	$problems.Add("mask_coverage.fsh: the params globals must be declared outside the injection markers (backward compatibility)")
}

# 3) the contract docs the bounds function (the reference for a world plugin).
if ($contract -notmatch 'vfx_shape_custom_bounds') {
	$problems.Add("VFXMaskShapeGlsl: the optional vfx_shape_custom_bounds contract is not documented")
}

Write-Host "Mask world-plugin check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "mask world-plugin check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable check: compile and run MaskWorldPluginCheck -------------------------------------
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
	Write-Error "mask world-plugin check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\mask\VFXShapeRegistry.class"))) {
	Write-Error "mask world-plugin check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "mask world-plugin check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-mask-world-plugin-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "MaskWorldPluginCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.mask.VFXCustomShape;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskShapeGlsl;
import dev.vfxweaver.mask.VFXMaskSpace;
import dev.vfxweaver.mask.VFXMaskVolumeMode;
import dev.vfxweaver.mask.VFXShapeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;

/** The built-in WORLD GLSL plugin (vfxweaver:blobs_glsl) and its demo contract. */
public final class MaskWorldPluginCheck {
	private static final String ID = "vfxweaver:blobs_glsl";

	public static void main(final String[] args) throws Exception {
		final VFXShapeRegistry registry = VFXShapeRegistry.get();
		final VFXCustomShape shape = registry.get(ID);
		require(shape != null, ID + " is not registered");
		require(shape.family() == VFXCustomShape.Family.GLSL_PLUGIN, ID + " is not a GLSL plugin");
		require(shape.space() == VFXMaskSpace.WORLD, ID + " is not a WORLD plugin (got " + shape.space() + ")");
		final VFXMaskShapeGlsl plugin = registry.plugin(ID);
		require(plugin != null, ID + " has no registered GLSL source");
		final String glsl = plugin.glsl();
		require(glsl.contains("float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1)"),
			"the SDF does not declare the exact vfx_shape_custom signature");
		require(glsl.contains("vfx_mask_data("), "the SDF does not read the per-leaf dynamic data");
		final int bounds = glsl.indexOf("vfx_shape_custom_bounds");
		require(bounds >= 0, "the plugin has no optional vfx_shape_custom_bounds broad phase");
		final String boundsBody = glsl.substring(bounds);
		require(boundsBody.contains("vfx_mask_data("), "vfx_shape_custom_bounds does not read the data");
		require(boundsBody.contains("vfx_shape_params0") && boundsBody.contains("vfx_shape_params1"),
			"vfx_shape_custom_bounds does not read the leaf params globals (the global controls change the extent)");

		final VFXMask aura = parseMask("{\"shape\":\"" + ID + "\",\"space\":\"world\",\"center\":[0.0,64.0,0.0],"
			+ "\"volume\":\"aura\",\"data\":[0.0,64.0,0.0,4.0]}");
		require(aura.primitives().get(0).volumeMode() == VFXMaskVolumeMode.AURA, "a world aura plugin leaf is not AURA");
		require(ID.equals(aura.primitives().get(0).customShape()), "the aura leaf lost its custom shape id");
		require(aura.needsDepth(), "a world plugin mask must need scene depth");

		final VFXMask surface = parseMask("{\"shape\":\"" + ID + "\",\"space\":\"world\",\"center\":[0.0,64.0,0.0],"
			+ "\"data\":[0.0,64.0,0.0,4.0]}");
		require(surface.primitives().get(0).volumeMode() == VFXMaskVolumeMode.SURFACE, "a world plugin without volume is not SURFACE");

		// The three demo effects must use the world plugin (the clips the owner re-records).
		final Path demos = Path.of(args[0]);
		checkDemo(demos, "show_mask_custom_glsl_aura_demo", VFXMaskVolumeMode.AURA);
		checkDemo(demos, "show_mask_custom_glsl_surface_demo", VFXMaskVolumeMode.SURFACE);
		checkDemo(demos, "show_mask_custom_data_demo", VFXMaskVolumeMode.SURFACE);

		System.out.println("mask world-plugin check OK: " + ID + " is a WORLD plugin reading vfx_mask_data with a data-bounds broad phase; aura/surface parse; the three demos use it");
	}

	private static void checkDemo(final Path demos, final String name, final VFXMaskVolumeMode want) throws Exception {
		final VFXDefinition definition = VFXDefinition.parse(Identifier.fromNamespaceAndPath("vfx_demos", name),
			JsonParser.parseString(Files.readString(demos.resolve(name + ".json"))).getAsJsonObject());
		require(definition.getMask() != null, name + " has no mask");
		final VFXMask mask = definition.getMask();
		require(mask.primitives().get(0).customShape() != null && mask.primitives().get(0).customShape().equals(ID),
			name + " does not use " + ID + " (got " + mask.primitives().get(0).customShape() + ")");
		require(mask.primitives().get(0).volumeMode() == want, name + " volume mode is not " + want);
	}

	private static VFXMask parseMask(final String json) {
		return VFXMask.parse("check", JsonParser.parseString(json).getAsJsonObject());
	}

	private static void require(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

# The demo pack the runnable phase parses: the demos live in a datapack, not in the repo (AGENTS.md),
# so locate the vfx_demos pack under the Prism instances.
$instancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
$demoDir = Join-Path $instancesRoot "26.2fabric\minecraft\saves\New World\datapacks\vfx_demos\data\vfx_demos\vfx"
if (-not (Test-Path (Join-Path $demoDir "show_mask_custom_glsl_aura_demo.json"))) {
	$candidate = Get-ChildItem -Path $instancesRoot -Recurse -File -Filter "show_mask_custom_glsl_aura_demo.json" -ErrorAction SilentlyContinue |
		Where-Object { $_.DirectoryName -match '\\vfx_demos\\data\\vfx_demos\\vfx$' } |
		Select-Object -First 1 -ExpandProperty DirectoryName
	$demoDir = $candidate
}
if (-not $demoDir) {
	Write-Error "mask world-plugin check: no vfx_demos pack with show_mask_custom_glsl_aura_demo.json found under $instancesRoot."
	exit 1
}

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" MaskWorldPluginCheck $demoDir
	if ($LASTEXITCODE -ne 0) { throw "MaskWorldPluginCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Mask world-plugin check OK."
exit 0

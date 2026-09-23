# Dev-only guard for per-leaf mask dynamic float data (GLSL plugin shapes). Static assertions over
# the exact contract this feature adds, plus a compiled standalone check:
#   * MaskDataCheck - `mask.p<N>.d<J>` slots parse (defaults, { "from": node } wiring, >K rejected),
#                     VFXDefinition registers them as params + graph inputs, and the reserved-name
#                     helper is stable.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-mask-data.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client"
$coverage = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$slots = Read-Source (Join-Path $main "mask\VFXMaskSlots.java")
$parser = Read-Source (Join-Path $main "mask\VFXMaskParser.java")
$contract = Read-Source (Join-Path $main "mask\VFXMaskShapeGlsl.java")
$api = Read-Source (Join-Path $main "api\VFXAPI.java")
$uniforms = Read-Source (Join-Path $client "postprocessing\VFXMaskUniforms.java")
$variants = Read-Source (Join-Path $client "postprocessing\VFXMaskShaderVariants.java")
$shader = Read-Source $coverage

$problems = New-Object System.Collections.Generic.List[string]

# 1) Reserved slots: K = 32 floats packed into 8 vec4s; the name helper.
if ($slots -notmatch 'MAX_LEAF_DATA = 32') {
	$problems.Add("VFXMaskSlots: MAX_LEAF_DATA is not 32")
}
if ($slots -notmatch 'MAX_LEAF_DATA_VEC4 = MAX_LEAF_DATA / 4') {
	$problems.Add("VFXMaskSlots: MAX_LEAF_DATA_VEC4 is not derived from MAX_LEAF_DATA")
}
if ($slots -notmatch 'return "mask\.p" \+ i \+ "\.d" \+ j;') {
	$problems.Add("VFXMaskSlots.data: the reserved mask.p<N>.d<J> name is not produced")
}

# 2) Parser: the custom leaf reads a `data` array and registers every d<J> slot via number(...).
if ($parser -notmatch 'json\.get\("data"\)') {
	$problems.Add("VFXMaskParser: a custom leaf does not read the 'data' array")
}
if ($parser -notmatch 'number\(slots, VFXMaskSlots\.data\(i, j\), value, 0\.0F\)') {
	$problems.Add("VFXMaskParser: the d<J> slots are not registered through number(...)")
}
if ($parser -notmatch 'customData\.size\(\) > VFXMaskSlots\.MAX_LEAF_DATA') {
	$problems.Add("VFXMaskParser: a data array larger than MAX_LEAF_DATA is not rejected")
}

# 3) UBO: appended vec4-packed array (never reorder), written per primitive.
if ($uniforms -notmatch 'new ConfigField\("shape_data", "vec4", VFXMask\.MAX_PRIMITIVES \* VFXMaskSlots\.MAX_LEAF_DATA_VEC4\)') {
	$problems.Add("VFXMaskUniforms: the shape_data Config field is missing or mis-sized")
}
if ($uniforms -notmatch 'slotValue\(mask, effect, VFXMaskSlots\.data\(i, base \+ 3\), 0\.0F\)') {
	$problems.Add("VFXMaskUniforms: the per-primitive shape_data slice is not written")
}
if ($uniforms -notmatch 'slotResolved\(mask, VFXMaskSlots\.data\(primitiveIndex, j\)\)') {
	$problems.Add("VFXMaskUniforms: a bound data slot is not part of the fail-closed leaf check")
}

# 4) Shader: the define, the Config array, the helper + base global OUTSIDE the injection markers
#    (so an existing plugin that ignores them compiles and behaves identically), and the base set
#    per leaf just before the plugin call.
if ($shader -notmatch '#define MASK_MAX_LEAF_DATA_VEC4 8') {
	$problems.Add("mask_coverage.fsh: MASK_MAX_LEAF_DATA_VEC4 is not defined as 8")
}
if ($shader -notmatch 'vec4 shape_data\[MASK_MAX_PRIMITIVES \* MASK_MAX_LEAF_DATA_VEC4\];') {
	$problems.Add("mask_coverage.fsh: the shape_data Config array is missing")
}
if ($shader -notmatch 'float vfx_mask_data\(int index\)') {
	$problems.Add("mask_coverage.fsh: the vfx_mask_data helper is missing")
}
if ($shader -notmatch 'int vfx_shape_data_base = 0;') {
	$problems.Add("mask_coverage.fsh: the vfx_shape_data_base global is missing")
}
if ($shader -notmatch 'vfx_shape_data_base = i \* \(MASK_MAX_LEAF_DATA_VEC4 \* 4\);') {
	$problems.Add("mask_coverage.fsh: the plugin branch does not point the base at the leaf's slice")
}
$begin = $shader.IndexOf('// >>> vfx_mask_custom_inject:begin')
$helper = $shader.IndexOf('float vfx_mask_data(int index)')
$baseGlobal = $shader.IndexOf('int vfx_shape_data_base = 0;')
$stub = $shader.IndexOf('float vfx_shape_custom(vec3 world, vec2 uv, vec4 p0, vec4 p1) {')
if ($begin -lt 0 -or $helper -lt 0 -or $baseGlobal -lt 0 -or $stub -lt 0) {
	$problems.Add("mask_coverage.fsh: an injection marker / helper / stub is missing")
} elseif (-not ($helper -lt $begin -and $baseGlobal -lt $begin -and $stub -gt $begin)) {
	$problems.Add("mask_coverage.fsh: the data globals must be declared outside the injection markers (backward compatibility)")
}

# 5) The variant is keyed by the plugin-id set only; a param edit never reaches it. The injected
#    plugin source is the registered glsl() verbatim (no transformation), so a plugin that never
#    calls vfx_mask_data compiles exactly as before.
if ($variants -notmatch 'String\.join\(",", new TreeSet<>\(pluginIds\)\)') {
	$problems.Add("VFXMaskShaderVariants: the variant key is no longer the sorted plugin-id set")
}
if ($variants -notmatch 'plugin\.append\("\\n// mask custom shape ''"\)\.append\(id\)\.append\("''\\n"\)\.append\(shape\.glsl\(\)\)') {
	$problems.Add("VFXMaskShaderVariants: the plugin source is no longer injected verbatim")
}

# 6) The plugin contract documents the helper and the API exposes the convenience writers.
if ($contract -notmatch 'vfx_mask_data\(vfx_shape_data_base \+ 0\)') {
	$problems.Add("VFXMaskShapeGlsl: the dynamic-data contract is not documented")
}
if ($api -notmatch 'public static void sendMaskData\(' -or $api -notmatch 'public static boolean maskData\(') {
	$problems.Add("VFXAPI: sendMaskData/maskData are missing")
}

Write-Host "Mask dynamic-data check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "mask dynamic-data check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable check: compile and run MaskDataCheck -------------------------------------------
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
	Write-Error "mask dynamic-data check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\mask\VFXMaskParser.class"))) {
	Write-Error "mask dynamic-data check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "mask dynamic-data check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-mask-data-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "MaskDataCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskSlots;
import net.minecraft.resources.Identifier;

/** The per-leaf mask dynamic-data contracts: reserved slots, parse, definition wiring. */
public final class MaskDataCheck {
	public static void main(final String[] args) {
		require(VFXMaskSlots.MAX_LEAF_DATA == 32, "MAX_LEAF_DATA = " + VFXMaskSlots.MAX_LEAF_DATA + ", want 32");
		require(VFXMaskSlots.MAX_LEAF_DATA_VEC4 == 8, "MAX_LEAF_DATA_VEC4 = " + VFXMaskSlots.MAX_LEAF_DATA_VEC4 + ", want 8");
		require("mask.p0.d0".equals(VFXMaskSlots.data(0, 0)), "data(0,0) = " + VFXMaskSlots.data(0, 0));
		require("mask.p1.d31".equals(VFXMaskSlots.data(1, 31)), "data(1,31) = " + VFXMaskSlots.data(1, 31));

		// Defaults + authored values land on the reserved slots (0 for the unset tail).
		final VFXMask mask = parseMask("{\"shape\":\"vfxweaver:ringed_glsl\",\"space\":\"screen\",\"center\":[0.5,0.5],\"data\":[1.0,2.0,3.0]}");
		require(mask.slots().get("mask.p0.d0").defaultValue() == 1.0F, "d0 default = " + mask.slots().get("mask.p0.d0").defaultValue());
		require(mask.slots().get("mask.p0.d2").defaultValue() == 3.0F, "d2 default = " + mask.slots().get("mask.p0.d2").defaultValue());
		require(mask.slots().get("mask.p0.d31").defaultValue() == 0.0F, "d31 default = " + mask.slots().get("mask.p0.d31").defaultValue());

		// { "from": node } wires a data slot as a graph input.
		final VFXMask wired = parseMask("{\"shape\":\"vfxweaver:ringed_glsl\",\"space\":\"screen\",\"center\":[0.5,0.5],\"data\":[0.0,0.0,0.0,0.0,0.0,{\"from\":\"phase\"}]}");
		require("phase".equals(wired.slots().get("mask.p0.d5").graphNode()), "d5 graphNode = " + wired.slots().get("mask.p0.d5").graphNode());

		// More than K values is a parse error.
		final StringBuilder big = new StringBuilder();
		for (int j = 0; j < VFXMaskSlots.MAX_LEAF_DATA + 1; j++) {
			if (j > 0) {
				big.append(',');
			}
			big.append('0');
		}
		rejected("too many data values", "{\"shape\":\"vfxweaver:ringed_glsl\",\"space\":\"screen\",\"center\":[0.5,0.5],\"data\":[" + big + "]}");

		// VFXDefinition registers every data slot as a param and wires the authored graph node.
		final VFXDefinition definition = VFXDefinition.parse(Identifier.fromNamespaceAndPath("check", "data_demo"), JsonParser.parseString(
			"{\"type\":\"blur\",\"duration\":100,"
				+ "\"graph\":{\"version\":1,\"nodes\":[{\"id\":\"phase\",\"kind\":\"time\",\"inputs\":{\"speed\":0.25}}],\"edges\":[]},"
				+ "\"mask\":{\"shape\":\"vfxweaver:ringed_glsl\",\"space\":\"screen\",\"center\":[0.5,0.5],\"data\":[0.5,{\"from\":\"phase\"}]}}"
		).getAsJsonObject());
		require(definition.getParams().containsKey("mask.p0.d0"), "definition params lack mask.p0.d0");
		require(definition.getParams().containsKey("mask.p0.d31"), "definition params lack mask.p0.d31");
		require("phase".equals(definition.getGraphInputs().get("mask.p0.d1")), "definition graph input d1 = " + definition.getGraphInputs().get("mask.p0.d1"));

		System.out.println("mask dynamic-data check OK: d0..d31 parse, wire as graph inputs and register as params; >K rejected");
	}

	private static VFXMask parseMask(final String json) {
		return VFXMask.parse("check", JsonParser.parseString(json).getAsJsonObject());
	}

	private static void rejected(final String what, final String json) {
		try {
			parseMask(json);
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
	& $java "@$cpFile" MaskDataCheck
	if ($LASTEXITCODE -ne 0) { throw "MaskDataCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Mask dynamic-data check OK."
exit 0

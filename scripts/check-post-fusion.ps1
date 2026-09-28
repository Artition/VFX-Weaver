# Dev-only guard for the fusion class on the post program registry (post-chain fusion, Task 2) and
# for the shape of the generated fused source (Task 5).
#
# The class contract: VFXFusionClass exists with exactly BARRIER/POINT/UV_REMAP; ProgramInfo carries
# the fusion policy as its last two components (BARRIER/1 = never fuse); every convenience
# constructor routes those defaults, so an unannotated registration can never fuse; and nothing can
# flip a program's class from a system property (the kill switch in the policy is the only switch).
#
# The source contract (spec §9.1): the generator emits fx0/fx0q/fx1, reads the run input once
# (through vfxIn, so no fx* body names InSampler), declares each s<k>_<Name> exactly once, applies
# vfxQ8 and merges the field Config members. This checks the shape only - Gradle does not compile
# GLSL, so nothing here claims the generated program compiles.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-post-fusion.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusionClass.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	$problems.Add("VFXFusionClass.java is missing")
}
$programs = [System.IO.File]::ReadAllText($programsPath)
$enum = if (Test-Path -LiteralPath $classPath) { [System.IO.File]::ReadAllText($classPath) } else { "" }

# 1) the enum: exactly the three constants, in order, and no further one.
$enumBody = [regex]::Match($enum, '(?s)enum VFXFusionClass\s*\{(.*)\}\s*$').Groups[1].Value
$constants = @([regex]::Matches($enumBody, '(?m)^\s*([A-Z_]{3,})\s*,?\s*(//.*)?$') | ForEach-Object { $_.Groups[1].Value })
$expected = @('BARRIER', 'POINT', 'UV_REMAP')
if (($constants -join ',') -ne ($expected -join ',')) {
	$problems.Add("VFXFusionClass constants are '$($constants -join ',')', expected '$($expected -join ',')'")
}

# 2) the registry: the fusion policy is the record's last two components.
if ($programs -notmatch 'VFXFusionClass fusionClass, int prefixEvals\)\s*\{') {
	$problems.Add("ProgramInfo does not end with the fusion components (VFXFusionClass fusionClass, int prefixEvals)")
}
if ($programs -notmatch 'public record ProgramInfo\(RenderPipeline pipeline,') {
	$problems.Add("ProgramInfo no longer starts with RenderPipeline pipeline - the positional registry contract changed")
}

# 3) every convenience constructor routes BARRIER/1 (the canonical one takes them as parameters).
$delegations = [regex]::Matches($programs, '(?m)^\s*this\((.*)\);\s*$')
if ($delegations.Count -lt 3) {
	$problems.Add("expected at least three delegating constructors, found $($delegations.Count)")
}
foreach ($d in $delegations) {
	if ($d.Groups[1].Value -notmatch 'VFXFusionClass\.BARRIER,\s*1$') {
		$problems.Add("a convenience constructor does not default to BARRIER/1: this($($d.Groups[1].Value))")
	}
}

# 4) no property can change a program's class (the policy's own kill switch lives in VFXFusionPolicy).
foreach ($text in @($programs, $enum)) {
	if ($text -match 'System\.getProperty\([^\)]*fusion') {
		$problems.Add("a fusion class is being read from a system property - the default must stay BARRIER")
	}
}

# 5) the annotation list: each fusable program's class/prefixEvals, and the barriers.
foreach ($entry in @(
	@('COLOR_GRADE', 'POINT', 1), @('SCREEN_FLASH', 'POINT', 1), @('VIGNETTE', 'POINT', 1),
	@('INVERT', 'POINT', 1), @('POSTERIZE', 'POINT', 1), @('SCANLINES', 'POINT', 1), @('FILM_GRAIN', 'POINT', 1),
	@('DISTORTION', 'UV_REMAP', 2), @('VORTEX', 'UV_REMAP', 2), @('PIXELATE', 'UV_REMAP', 2),
	@('NOISE_WARP', 'UV_REMAP', 2), @('SLICE_SHIFT', 'UV_REMAP', 2))) {
	if ($programs -notmatch "annotate\(VFXEffectType\.$($entry[0]), VFXFusionClass\.$($entry[1]), $($entry[2])\);") {
		$problems.Add("annotation missing or wrong: expected $($entry[0]) $($entry[1])/$($entry[2])")
	}
}
foreach ($barrier in @('BLUR', 'BLOOM', 'VHS', 'MOTION_BLUR', 'AFTERIMAGE', 'STOP_MOTION', 'DOUBLE_VISION', 'SHOCKWAVE', 'DENT')) {
	if ($programs -match "annotate\(VFXEffectType\.$barrier,") {
		$problems.Add("$barrier must stay BARRIER (multi-tap or non-fusible) but is annotated")
	}
}
if ($programs -notmatch 'maskProgram = new ProgramInfo\(maskPipeline, new String\[0\], 0, PassRole\.NORMAL, false, null, true, false, false, VFXFusionClass\.POINT, 1\);') {
	$problems.Add("mask_apply (the coverage consumer) is not annotated POINT/1")
}

Write-Host "Post fusion class check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "post fusion class check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  BARRIER/POINT/UV_REMAP, the policy as the record's last two components, every convenience constructor defaulting to BARRIER/1, no property override; the 12 fusable programs annotated and the multi-tap/feedback ones left BARRIER"
Write-Host "Post fusion class check OK."

# --- golden source (Task 5): the shape of the generated fused program ------------------------------
# This checks the *shape* of the generated GLSL, not that it compiles: Gradle does not compile GLSL
# and there is no GPU here. The four spec §9.1 assertions are made on the generator's output.
$generatorPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusedShaderGenerator.java"
if (-not (Test-Path -LiteralPath $generatorPath)) {
	Write-Error "post fusion source check: VFXFusedShaderGenerator.java is missing (Task 5 not implemented)."
	exit 1
}
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) { Write-Error "post fusion source check: no JDK found (set JAVA_HOME); static contracts passed."; exit 1 }
$clientClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\client"
if (-not (Test-Path (Join-Path $clientClasses "dev\vfxweaver\client\postprocessing\VFXFusedShaderGenerator.class"))) {
	Write-Error "post fusion source check: build :26.1.2 first (missing $clientClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) { Write-Error "post fusion source check: minecraft merged-deobf 26.1.2 jar not found."; exit 1 }
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$sourceCheckDir = Join-Path $env:TEMP "vfxweaver-fusion-source-check"
New-Item -ItemType Directory -Force -Path $sourceCheckDir | Out-Null
$sourceSrc = Join-Path $sourceCheckDir "FusionSourceCheck.java"
$sourceCpFile = Join-Path $sourceCheckDir "cp.txt"
$sourceCp = "versions/26.1.2/build/classes/java/main;versions/26.1.2/build/classes/java/client;$($sourceCheckDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($sourceCpFile, "-cp `"$sourceCp`"", [System.Text.UTF8Encoding]::new($false))
$sourceJava = @'
package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.client.postprocessing.VFXFusedShaderGenerator.Generated;
import dev.vfxweaver.client.postprocessing.VFXFusedShaderGenerator.StageAsset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/** The golden-source fixtures: the shape of a generated fused program (spec §9.1). */
public final class FusionSourceCheck {
	private static final String POINT = """
		#version 330

		uniform sampler2D InSampler;

		in vec2 texCoord;

		layout(std140) uniform SamplerInfo {
			vec2 OutSize;
			vec2 InSize;
		};

		layout(std140) uniform Config {
			float strength;
			float tint;
		};

		out vec4 fragColor;

		void main() {
			vec4 color = texture(InSampler, texCoord);
			color.rgb *= strength;
			color.rgb += tint;
			fragColor = color;
		}
		""";
	private static final String MASK = """
		#version 330

		uniform sampler2D InSampler;
		uniform sampler2D HistSampler;
		uniform sampler2D CoverageSampler;

		in vec2 texCoord;

		layout(std140) uniform SamplerInfo {
			vec2 OutSize;
			vec2 InSize;
		};

		out vec4 fragColor;

		void main() {
			vec4 before = texture(HistSampler, texCoord);
			float cov = texture(CoverageSampler, texCoord).r;
			if (cov <= 0.0) { fragColor = before; return; }
			vec4 after = texture(InSampler, texCoord);
			fragColor = mix(before, after, cov);
		}
		""";
	private static final String FIELD_STAGE = """
		#version 330

		#moj_import <vfxweaver:field.glsl>

		uniform sampler2D InSampler;

		in vec2 texCoord;

		layout(std140) uniform SamplerInfo {
			vec2 OutSize;
			vec2 InSize;
		};

		layout(std140) uniform Config {
			float amount;
		};

		out vec4 fragColor;

		void main() {
			vec4 color = texture(InSampler, texCoord);
			color.rgb *= vec3(vfx_field_intensity(texCoord));
			color.rgb += fld_weight * amount;
			fragColor = color;
		}
		""";
	private static final String FIELD_INCLUDE = """
		#moj_import <vfxweaver:field_body.glsl>

		layout(std140) uniform FieldConfig {
			float fld_weight;
			vec2 fld_uv;
		};

		uniform sampler2D DepthSampler;
		uniform sampler2D fld_tex0;
		""";
	private static final String FIELD_BODY = """
		float vfx_field_sample(float x) {
			return texture(fld_tex0, vec2(x)).r;
		}

		float vfx_field_intensity(vec2 uv) {
			return fld_weight * vfx_field_sample(uv.x);
		}
		""";

	public static void main(final String[] args) {
		final Map<String, String> assets = new HashMap<>();
		assets.put("vfxweaver:post/fx_a", POINT);
		assets.put("vfxweaver:post/mask_apply", MASK);
		assets.put("vfxweaver:post/fx_field", FIELD_STAGE);
		assets.put("vfxweaver:include/field.glsl", FIELD_INCLUDE);
		assets.put("vfxweaver:include/field_body.glsl", FIELD_BODY);
		final Function<Identifier, String> lookup = id -> assets.get(id.toString());

		final List<StageAsset> plain = List.of(asset("vfxweaver:post/fx_a", false), asset("vfxweaver:post/mask_apply", true));
		final String source = generate(plain, lookup);
		contains(source, "vec4 fx0(", "fx0 present");
		contains(source, "vec4 fx0q(", "fx0q present");
		contains(source, "vec4 fx1(", "fx1 present");
		count(source, "texture(InSampler", 1, "the run input is read once (through vfxIn)");
		for (final String body : fxBodies(source)) {
			if (body.contains("InSampler")) {
				throw new AssertionError("a fused stage body still reads InSampler directly");
			}
		}
		declaredOnce(source);
		contains(source, "vfxQ8", "vfxQ8 present");
		System.out.println("  plain: fx0/fx0q/fx1, one texture(InSampler outside the fx* bodies, every s<k>_<Name> declared once, vfxQ8 present");

		final List<StageAsset> paired = List.of(
			asset("vfxweaver:post/fx_a", false), asset("vfxweaver:post/mask_apply", true),
			asset("vfxweaver:post/fx_a", false), asset("vfxweaver:post/mask_apply", true));
		final String pairedSource = generate(paired, lookup);
		contains(pairedSource, "vec4 fx2(", "fx2 present");
		contains(pairedSource, "uniform sampler2D s1_HistSampler;", "the run-head consumer keeps HistSampler");
		if (pairedSource.contains("s3_HistSampler")) {
			throw new AssertionError("an in-run consumer still declares HistSampler (it must read the register)");
		}
		System.out.println("  multi-consumer: only the run-head consumer binds HistSampler; the in-run one reads fx0q");

		final List<StageAsset> fieldChain = List.of(asset("vfxweaver:post/fx_field", false), asset("vfxweaver:post/mask_apply", true));
		final String fieldSource = generate(fieldChain, lookup);
		contains(fieldSource, "e0_amount", "e0_amount present");
		contains(fieldSource, "e0_fld_weight", "e0_fld_weight present");
		contains(fieldSource, "s0_fld_tex0", "s0_fld_tex0 present");
		contains(fieldSource, "vfx_field_intensity_s0", "vfx_field_intensity_s0 present");
		final int members = configMembers(fieldSource);
		if (members != 3) {
			throw new AssertionError("merged Config members = " + members + ", want 3 (1 param + 2 field members)");
		}
		System.out.println("  field: Config members = 3 (1 param + 2 field members), field renames present");
		System.out.println("post fusion source check OK: shape only - Gradle does not compile GLSL");
	}

	private static StageAsset asset(final String id, final boolean mask) {
		return new StageAsset(Identifier.fromNamespaceAndPath("vfxweaver", id.substring(id.indexOf(':') + 1)), mask);
	}

	private static String generate(final List<StageAsset> stages, final Function<Identifier, String> lookup) {
		final Generated generated = VFXFusedShaderGenerator.generateAssets(stages, lookup);
		if (generated == null) {
			throw new AssertionError("the generator returned null (UNUSABLE) for a fixture");
		}
		return generated.source();
	}

	private static List<String> fxBodies(final String source) {
		final java.util.List<String> bodies = new java.util.ArrayList<>();
		final Matcher matcher = Pattern.compile("vec4 fx\\d+\\(vec2 texCoord\\) \\{").matcher(source);
		while (matcher.find()) {
			int depth = 0;
			final int open = source.indexOf('{', matcher.start());
			for (int i = open; i < source.length(); i++) {
				if (source.charAt(i) == '{') { depth++; }
				else if (source.charAt(i) == '}') { depth--; if (depth == 0) { bodies.add(source.substring(open, i + 1)); break; } }
			}
		}
		return bodies;
	}

	private static int configMembers(final String source) {
		final int start = source.indexOf("uniform Config {");
		final int open = source.indexOf('{', start);
		final int close = source.indexOf('}', open);
		int count = 0;
		for (final String statement : source.substring(open + 1, close).split(";")) {
			if (!statement.trim().isEmpty()) { count++; }
		}
		return count;
	}

	private static void contains(final String source, final String needle, final String what) {
		if (!source.contains(needle)) {
			throw new AssertionError(what + ": missing '" + needle + "'");
		}
	}

	private static void count(final String source, final String needle, final int want, final String what) {
		int found = 0;
		int from = 0;
		while ((from = source.indexOf(needle, from)) >= 0) { found++; from += needle.length(); }
		if (found != want) {
			throw new AssertionError(what + ": found " + found + ", want " + want);
		}
	}

	private static void declaredOnce(final String source) {
		final Matcher matcher = Pattern.compile("uniform sampler2D (s\\d+_\\w+);").matcher(source);
		final java.util.Set<String> seen = new java.util.HashSet<>();
		while (matcher.find()) {
			if (!seen.add(matcher.group(1))) {
				throw new AssertionError("sampler " + matcher.group(1) + " declared more than once");
			}
		}
		if (seen.isEmpty()) {
			throw new AssertionError("no per-stage s<k>_<Name> sampler was declared");
		}
	}
}
'@
[System.IO.File]::WriteAllText($sourceSrc, $sourceJava, [System.Text.UTF8Encoding]::new($false))

Write-Host "Post fusion source check"
Push-Location $repoRoot
try {
	& $javac "@$sourceCpFile" -d $sourceCheckDir $sourceSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$sourceCpFile" dev.vfxweaver.client.postprocessing.FusionSourceCheck
	if ($LASTEXITCODE -ne 0) { throw "FusionSourceCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Post fusion source check OK."
exit 0

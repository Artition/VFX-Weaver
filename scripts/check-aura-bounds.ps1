# Dev-only guard for the aura-mask skip/box bounds plugin contract (Task 1) and for the per-leaf
# dispatchers the mask shader variants emit for them (Task 2).
#
# The spec (2026-10-01-aura-mask-budget-and-bounds-design.md, P0) fixes two contracts that an
# earlier revision got wrong, so they are asserted here as literal text:
#   * a containment box CONTAINS the whole region where field <= 0 (a box around a hole is the
#     inversion that made the earlier revision a lie);
#   * a skip box lies ENTIRELY inside the field > 0 region and must be INSCRIBED in the hole - the
#     AABB of a set of circles does not qualify, because its corners lie outside the circles, i.e.
#     inside the coverage region, and would skip real coverage.
#
# Both methods are `default` and both signal "not provided" by returning false with nothing appended,
# so every existing plugin (a lambda over String glsl()) keeps compiling and behaves exactly as
# before. VFXAPI's two registerMaskShapeGlsl overloads must be untouched, and it must NOT gain a
# steps overload: the per-leaf march cap rides a reserved UBO slot (spec P1).
#
# Task 2 asserts the generator side: the two variant-level defines are emitted only when some plugin
# provided the function, and the two per-leaf dispatchers take the leaf index, publish that leaf's
# globals and then call the plugin - so a variant of two plugins where only one provides a box still
# emits the define, and the other leaf reports "not provided" rather than borrowing the box.
#
# Task 3 asserts the shared slab helper in the coverage shader: the four spec steps (clamp the
# sentinel axes to the reachable range, pad by the near-miss band, invert the direction robustly,
# take tNear/tFar from min/max pairs) in the order the spec fixes, and the sphere broad phase beside
# it byte-identical to HEAD. Those four are pure arithmetic, so they are also simulated on the CPU
# below - sentinel_clamp, cull_pad, robust_inverse, skip_tail_clamp - because Gradle does not compile
# GLSL and there is no GPU here, so nothing here claims a shader compiles.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-aura-bounds.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$contractPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\mask\VFXMaskShapeGlsl.java"
$apiPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\api\VFXAPI.java"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($path in @($contractPath, $apiPath)) {
	if (-not (Test-Path -LiteralPath $path)) {
		Write-Error "aura bounds check: missing $($path.Substring($repoRoot.Length + 1))"
		exit 1
	}
}
$contract = [System.IO.File]::ReadAllText($contractPath)
$api = [System.IO.File]::ReadAllText($apiPath)

function Get-Body([string]$text, [string]$signature) {
	$start = $text.IndexOf($signature)
	if ($start -lt 0) { return $null }
	$open = $text.IndexOf('{', $start)
	if ($open -lt 0) { return $null }
	$depth = 0
	for ($i = $open; $i -lt $text.Length; $i++) {
		if ($text[$i] -eq '{') { $depth++ }
		elseif ($text[$i] -eq '}') { $depth--; if ($depth -eq 0) { return $text.Substring($open + 1, $i - $open - 1) } }
	}
	return $null
}

# 1) both methods exist, are `default` (so every existing plugin lambda still compiles), and take the
#    StringBuilder the variant generator appends plugin GLSL into.
$methods = @(
	@('customBoundsBox', 'vfx_shape_custom_bounds_box'),
	@('customSkipBounds', 'vfx_shape_custom_skip_bounds'))
foreach ($method in $methods) {
	$name = $method[0]
	$fn = $method[1]
	if ($contract -notmatch "(?m)^\s*default boolean $name\(final StringBuilder out\) \{") {
		$problems.Add("VFXMaskShapeGlsl has no 'default boolean $name(final StringBuilder out)' - the method must be optional so existing plugins keep compiling")
		continue
	}
	$body = Get-Body $contract "default boolean $name(final StringBuilder out) {"
	if ($body -eq $null) {
		$problems.Add("VFXMaskShapeGlsl.$name has no readable body")
		continue
	}
	# 2) the default signals "not provided": false returned, nothing appended, no source of its own.
	if ($body -notmatch 'return false;') {
		$problems.Add("VFXMaskShapeGlsl.$name default does not 'return false' (the not-provided signal)")
	}
	$statements = ($body -replace '\s*//[^\n]*', '' -replace '\s', '')
	if ($statements -ne 'returnfalse;') {
		$problems.Add("VFXMaskShapeGlsl.$name default is not exactly 'return false' with nothing appended (got: $($body.Trim()))")
	}
	if ($body -match 'out\.append|\{\{|"') {
		$problems.Add("VFXMaskShapeGlsl.$name default appends to `out` or carries GLSL - an unprovided plugin must append nothing")
	}
	# 3) the Javadoc on each method states both contracts: which GLSL function to define, and what the
	#    box must cover. The function name and the out parameters live in the Javadoc, as they are the
	#    contract with the shader, not runtime state.
	$doc = [regex]::Match($contract, "(?s)/\*\*.*?\*/\s*default boolean $name\(").Value
	if ($doc -eq '') {
		$problems.Add("VFXMaskShapeGlsl.$name has no Javadoc")
	} else {
		# {@code x} and <p> are markup, not contract text: normalise them away before matching.
		$text = ($doc -replace '\{@code\s*', '' -replace '\}', '' -replace '<[a-z]+>', '')
		if ($text -notmatch [regex]::Escape($fn)) {
			$problems.Add("VFXMaskShapeGlsl.$name Javadoc does not name the GLSL function it must define ($fn)")
		}
		if ($text -notmatch 'out vec3 bmin, out vec3 bmax') {
			$problems.Add("VFXMaskShapeGlsl.$name Javadoc does not document the exact out parameters (out vec3 bmin, out vec3 bmax)")
		}
		if ($text -notmatch 'field (is )?<= 0' -or $text -notmatch 'field (is )?> 0') {
			$problems.Add("VFXMaskShapeGlsl.$name Javadoc does not state both contracts (the region where the field is <= 0 and the region where it is > 0)")
		}
		if ($text -notmatch 'inscrib') {
			$problems.Add("VFXMaskShapeGlsl.$name Javadoc does not state the inscribed-in-the-hole requirement")
		}
		if ($text -notmatch 'AABB|corner') {
			$problems.Add("VFXMaskShapeGlsl.$name Javadoc does not rule out the AABB of a set of circles (its corners lie in the coverage region)")
		}
	}
}

# 4) the containment box is a CONTAINMENT box, not a box around a hole (the rev-2 inversion), and the
#    skip box lies ENTIRELY inside the field > 0 region. Checked on each method's own Javadoc.
$boxDoc = [regex]::Match($contract, '(?s)/\*\*.*?\*/\s*default boolean customBoundsBox\(').Value
if ($boxDoc -ne '' -and $boxDoc -notmatch 'contain') {
	$problems.Add("VFXMaskShapeGlsl.customBoundsBox Javadoc does not say the box CONTAINS the coverage region")
}
$skipDoc = [regex]::Match($contract, '(?s)/\*\*.*?\*/\s*default boolean customSkipBounds\(').Value
if ($skipDoc -ne '' -and $skipDoc -notmatch 'entirely|wholly') {
	$problems.Add("VFXMaskShapeGlsl.customSkipBounds Javadoc does not say the box lies ENTIRELY inside the field > 0 region")
}

# 5) the interface still has exactly one abstract method (glsl()), so @FunctionalInterface and every
#    existing lambda plugin are untouched.
$abstract = @([regex]::Matches($contract, '(?m)^\s*(?:public\s+)?(?!default|static)[A-Za-z<>\[\]\., ]+\s+\w+\([^)]*\)\s*;'))
if ($abstract.Count -ne 1 -or $abstract[0].Value -notmatch 'String glsl\(\)') {
	$problems.Add("VFXMaskShapeGlsl has $($abstract.Count) abstract method(s) - it must stay @FunctionalInterface with only String glsl()")
}
if ($contract -notmatch '@FunctionalInterface') {
	$problems.Add("VFXMaskShapeGlsl lost @FunctionalInterface")
}
if ($contract -notmatch 'String glsl\(\);') {
	$problems.Add("VFXMaskShapeGlsl lost 'String glsl()' - an existing plugin's single abstract method must not change")
}

# 6) VFXAPI: the two existing registerMaskShapeGlsl overloads are present with their exact signatures.
$overloads = @(
	'public static boolean registerMaskShapeGlsl\(final Identifier id, final VFXMaskShapeGlsl plugin\) \{',
	'public static boolean registerMaskShapeGlsl\(final Identifier id, final VFXMaskShapeGlsl plugin, final float lipschitz\) \{')
foreach ($signature in $overloads) {
	if ($api -notmatch $signature) {
		$problems.Add("VFXAPI is missing the unchanged overload: $signature")
	}
}
$registerCount = [regex]::Matches($api, 'public static boolean registerMaskShapeGlsl\(').Count
if ($registerCount -ne 2) {
	$problems.Add("VFXAPI declares $registerCount registerMaskShapeGlsl overloads, expected exactly 2 (a third would change the public surface)")
}

# 7) VFXAPI must NOT gain a steps/aura_steps overload: the per-leaf cap rides a reserved UBO slot
#    (spec P1 - an API overload would cap every leaf of a variant, which is worse than a slot).
if ($api -match 'registerMaskShapeGlsl\([^)]*\bsteps\b') {
	$problems.Add("VFXAPI gained a registerMaskShapeGlsl overload taking steps - the march cap rides a UBO slot, not the API (spec P1)")
}
if ($api -match 'aura_steps') {
	$problems.Add("VFXAPI mentions aura_steps - the cap is a datapack leaf field plus a UBO slot, not an API parameter")
}

# 8) the variant generator (Task 2): the two defines, and the per-leaf dispatchers. The define is
#    per variant - emitted as soon as ONE plugin in the variant provided the function - while the
#    dispatch is per leaf: the dispatcher takes the leaf index, republishes that leaf's globals and
#    only then calls the plugin, and returns "not provided" for a leaf with no plugin bounds.
$variantsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXMaskShaderVariants.java"
if (-not (Test-Path -LiteralPath $variantsPath)) {
	Write-Error "aura bounds check: missing src\client\java\dev\vfxweaver\client\postprocessing\VFXMaskShaderVariants.java"
	exit 1
}
$variants = [System.IO.File]::ReadAllText($variantsPath)

# kind, the define, the emitted-source constant, the plugin method, the plugin GLSL function.
$dispatchers = @(
	@('box', 'VFX_CUSTOM_HAS_BOX_BOUNDS', 'BOX_DISPATCHER', 'customBoundsBox', 'vfx_shape_custom_bounds_box'),
	@('skip', 'VFX_CUSTOM_HAS_SKIP_BOUNDS', 'SKIP_DISPATCHER', 'customSkipBounds', 'vfx_shape_custom_skip_bounds'))
foreach ($dispatcher in $dispatchers) {
	$kind = $dispatcher[0]
	$define = $dispatcher[1]
	$constant = $dispatcher[2]
	$method = $dispatcher[3]
	$pluginFunction = $dispatcher[4]

	# a) the define is emitted conditionally, exactly like the sphere define it sits beside: a variant
	#    whose plugins provide neither function must compile byte-identically to today.
	if ($variants -notmatch ([regex]::Escape($define) + ' 1"\s*:\s*""')) {
		$problems.Add("VFXMaskShaderVariants: $define is not emitted conditionally (a variant with no such plugin must not see it)")
	}
	# b) the per-plugin answer is OR-ed over EVERY plugin of the variant, so two plugins of which only
	#    one provides the function still emit the define (spec P0: the define is per variant, the
	#    dispatch is per leaf - the per-leaf "not provided" path is what makes that safe).
	if ($variants -notmatch ('(?m)^\s*\w+ \|= shape\.' + $method + '\(plugin\);')) {
		$problems.Add("VFXMaskShaderVariants: the per-plugin $method result is not OR-ed into the variant (two plugins, one providing, must still emit $define)")
	}
	# c) the dispatcher source is emitted only alongside its own define (an emitted dispatcher whose
	#    define is absent would reference an undeclared plugin function).
	if ($variants -notmatch ([regex]::Escape($constant) + '\s*:\s*""')) {
		$problems.Add("VFXMaskShaderVariants: $constant is not emitted conditionally - it must travel with $define")
	}

	$declaration = "private static final String $constant ="
	$start = $variants.IndexOf($declaration)
	if ($start -lt 0) {
		$problems.Add("VFXMaskShaderVariants: no '$constant' emitted-source constant")
		continue
	}
	# The statement ends on the closing brace line of the emitted GLSL, so the emitted text can be
	# asserted as written rather than reconstructed.
	$end = $variants.IndexOf('"}\n";', $start)
	if ($end -lt 0) {
		$problems.Add("VFXMaskShaderVariants: $constant has no readable end (the emitted GLSL must close its brace)")
		continue
	}
	$emitted = $variants.Substring($start, $end + 5 - $start)

	# d) the dispatcher takes the leaf index and the leaf's params/data globals - the convention the
	#    coverage loop already sets up for vfx_shape_custom.
	$signature = "bool vfx_custom_${kind}_bounds(int leaf, out vec3 bmin, out vec3 bmax)"
	if (-not $emitted.Contains('"' + $signature + ' {\n"')) {
		$problems.Add("VFXMaskShaderVariants: $constant does not declare '$signature'")
	}
	$reach = @(
		'vfx_shape_data_base = leaf * (MASK_MAX_LEAF_DATA_VEC4 * 4);',
		'vfx_shape_params0 = shape_params0[leaf];',
		'vfx_shape_params1 = shape_params1[leaf];',
		"return $pluginFunction(bmin, bmax);")
	$previous = -1
	foreach ($line in $reach) {
		$at = $emitted.IndexOf($line)
		if ($at -lt 0) {
			$problems.Add("VFXMaskShaderVariants: $constant does not emit '$line'")
			break
		}
		if ($at -le $previous) {
			$problems.Add("VFXMaskShaderVariants: $constant emits '$line' after the plugin call - the leaf's globals must be set BEFORE the plugin runs")
			break
		}
		$previous = $at
	}
	# e) the per-leaf "not provided" path: a leaf that is not a plugin leaf has no plugin bounds to
	#    report, and the out parameters are defined on it (the sphere wrapper's own fallback does the
	#    same). It must be decided before the plugin call, never after it.
	$notProvided = $emitted.IndexOf('return false;')
	if ($notProvided -lt 0) {
		$problems.Add("VFXMaskShaderVariants: $constant has no 'return false;' - a leaf whose plugin provides no bounds must report 'not provided'")
	} elseif ($notProvided -gt $emitted.IndexOf('return vfx_shape_custom_')) {
		$problems.Add("VFXMaskShaderVariants: $constant reaches the plugin before deciding 'not provided'")
	}
	if (-not $emitted.Contains('"    bmin = vec3(0.0);\n"') -or -not $emitted.Contains('"    bmax = vec3(0.0);\n"')) {
		$problems.Add("VFXMaskShaderVariants: $constant leaves bmin/bmax undefined on the 'not provided' path")
	}
}

# 9) the pre-existing sphere and Lipschitz emission is untouched, byte for byte, against HEAD: this
#    task is additive, and the sphere (vfx_shape_custom_bounds) keeps its meaning.
$headSource = @(& git -C $repoRoot show "HEAD:src/client/java/dev/vfxweaver/client/postprocessing/VFXMaskShaderVariants.java")
if ($headSource.Count -eq 0) {
	$problems.Add("aura bounds check: cannot read VFXMaskShaderVariants.java at HEAD (is git available?)")
} else {
	foreach ($needle in @('CUSTOM_BOUNDS_PATTERN = Pattern.compile', 'final String boundsDefine =', 'final String lipschitzDefine =')) {
		$before = @($headSource | Where-Object { $_.Contains($needle) } | ForEach-Object { $_.Trim() })
		if ($before.Count -eq 0) {
			$problems.Add("VFXMaskShaderVariants at HEAD has no line containing '$needle' - the untouched-handling check cannot run")
			continue
		}
		foreach ($line in $before) {
			if (-not $variants.Contains($line)) {
				$problems.Add("VFXMaskShaderVariants changed existing sphere/Lipschitz handling: $line")
			}
		}
	}
}

# 10) Task 3: the shared padded slab helper in the coverage shader. Its four steps are the spec's four
#     mandatory conditions (P0), asserted as literal text AND in the order the spec fixes (clamp the
#     sentinels -> expand -> slab test), and the sphere broad phase beside it must be byte-identical to
#     HEAD: this task only adds a helper, so any edit to the sphere maths is an accident.
$shaderPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"
if (-not (Test-Path -LiteralPath $shaderPath)) {
	Write-Error "aura bounds check: missing src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"
	exit 1
}
$shader = ([System.IO.File]::ReadAllText($shaderPath)) -replace "`r`n", "`n"
$helperSignature = 'void vfx_aura_box_interval(vec3 bmin, vec3 bmax, vec3 viewDir, float softness, out float tNear, out float tFar)'
$helperBody = Get-Body $shader $helperSignature
if ($helperBody -eq $null) {
	$problems.Add("mask_coverage.fsh has no '$helperSignature' - one padded slab helper serves both the containment branch and the skip branch")
} else {
	# name, then the literals of that step. The clamp needs its reach named so the reachable range is
	# the shader's own MAX_RANGE constant and not a number typed twice.
	$steps = @(
		@('the sentinel clamp to the reachable range',
			'vec3 reach = vec3(VFX_PLUGIN_AURA_MAX_RANGE);',
			'clamp(bmin, camPos.xyz - reach, camPos.xyz + reach)',
			'clamp(bmax, camPos.xyz - reach, camPos.xyz + reach)'),
		@('the near-miss pad',
			'float pad = 0.5 * softness;',
			'lo -= vec3(pad);',
			'hi += vec3(pad);'),
		@('the robust direction inversion',
			'invDir = 1.0 / mix(viewDir, vec3(1.0e-8), lessThan(abs(viewDir), vec3(1.0e-8)))'),
		@('the min/max tNear and tFar pairs',
			'tNear = max(max(', ', tn.z);',
			'tFar = min(min(', ', tf.z);'))
	$spans = @()
	$stepIndex = 0
	foreach ($step in $steps) {
		$name = $step[0]
		$first = -1
		$last = -1
		for ($literalIndex = 1; $literalIndex -lt $step.Count; $literalIndex++) {
			$literal = $step[$literalIndex]
			$at = $helperBody.IndexOf($literal)
			if ($at -lt 0) {
				$problems.Add("mask_coverage.fsh vfx_aura_box_interval is missing ${name}: '$literal'")
				continue
			}
			if ($first -lt 0 -or $at -lt $first) { $first = $at }
			if ($at -gt $last) { $last = $at }
		}
		if ($first -ge 0) { $spans += , @($stepIndex, $first, $last, $name) }
		$stepIndex++
	}
	for ($i = 1; $i -lt $spans.Count; $i++) {
		if ($spans[$i][1] -le $spans[$i - 1][2]) {
			$problems.Add("mask_coverage.fsh vfx_aura_box_interval does $($spans[$i][3]) after $($spans[$i - 1][3]) - the spec fixes the order clamp sentinels -> expand -> slab test")
		}
	}
	# The comment above the helper must carry the four reasons; a bare helper invites the next edit to
	# drop the pad or the robust inversion as "noise".
	$head = $shader.Substring(0, $shader.IndexOf($helperSignature)).TrimEnd()
	$headLines = $head -split "`n"
	$doc = @()
	for ($i = $headLines.Count - 1; $i -ge 0; $i--) {
		if ($headLines[$i].TrimStart().StartsWith('//')) { $doc = @($headLines[$i].Trim()) + $doc } else { break }
	}
	$doc = $doc -join ' '
	foreach ($phrase in @('lossless', 'softness/2', 'widen')) {
		if (-not $doc.Contains($phrase)) {
			$problems.Add("the comment above vfx_aura_box_interval does not say '$phrase' - each step is there for a reason and the reason is the contract")
		}
	}
}

# 11) the sphere broad phase (boundOffset / boundB / boundC) is byte-identical to HEAD. The padded box
#     is a new path beside it, not a rewrite of it: vfx_shape_custom_bounds() keeps its meaning.
$headLines = @(& git -C $repoRoot show "HEAD:src/client/resources/assets/vfxweaver/shaders/post/mask_coverage.fsh")
$sphereFrom = '                    vec4 bounds = vfx_custom_bounds();'
$sphereTo = '                    if (!boundsHit || tStart >= tLimit) {'
if ($headLines.Count -eq 0) {
	$problems.Add("aura bounds check: cannot read mask_coverage.fsh at HEAD (is git available?)")
} else {
	$headFrom = [Array]::IndexOf($headLines, $sphereFrom)
	$headTo = [Array]::IndexOf($headLines, $sphereTo)
	if ($headFrom -lt 0 -or $headTo -le $headFrom) {
		$problems.Add("mask_coverage.fsh at HEAD has no sphere broad phase between its two anchors - the untouched-sphere check cannot run")
	} else {
		$block = ($headLines[$headFrom..($headTo - 1)]) -join "`n"
		if (-not $shader.Contains($block)) {
			$problems.Add("the sphere broad phase in mask_coverage.fsh is no longer byte-identical to HEAD (boundOffset / boundB / boundC through the end of its if)")
		}
	}
}

Write-Host "Aura bounds plugin check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "aura bounds check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  both bounds methods default to 'not provided' (false, nothing appended), so every existing plugin keeps compiling"
Write-Host "  Javadoc: the box CONTAINS the whole field <= 0 region; the skip box lies ENTIRELY in field > 0 and is inscribed in the hole (a circles' AABB does not qualify)"
Write-Host "  VFXMaskShapeGlsl is still @FunctionalInterface (String glsl() only); VFXAPI keeps exactly its two registerMaskShapeGlsl overloads and no steps overload"
Write-Host "  the variant generator emits VFX_CUSTOM_HAS_BOX_BOUNDS / VFX_CUSTOM_HAS_SKIP_BOUNDS per variant, and vfx_custom_box_bounds(int leaf, out vec3 bmin, out vec3 bmax) / vfx_custom_skip_bounds(...) per leaf, each publishing the leaf's globals before calling the plugin"
Write-Host "  the existing sphere (VFX_CUSTOM_HAS_BOUNDS) and VFX_CUSTOM_FIELD_LIPSCHITZ emission is byte-identical to HEAD"
Write-Host "  mask_coverage.fsh: vfx_aura_box_interval does clamp sentinels -> pad by 0.5 * softness -> robust inversion -> min/max tNear/tFar, in that order, and says why in a comment"
Write-Host "  the sphere broad phase in mask_coverage.fsh (boundOffset / boundB / boundC) is byte-identical to HEAD"
Write-Host "Aura bounds plugin check OK."

# --- runnable: the four slab fixtures, the arithmetic of vfx_aura_box_interval on the CPU ---------
# The helper is pure arithmetic, so it is simulated here rather than in GLSL: Gradle does not compile
# GLSL and there is no GPU on this box, so the shape of the source above is all the shader can be
# held to, and the numbers are all these four can be held to.
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "aura bounds check: no JDK found (set JAVA_HOME); the static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path $mainClasses)) {
	Write-Error "aura bounds check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$slabDir = Join-Path $env:TEMP "vfxweaver-aura-slab-check"
New-Item -ItemType Directory -Force -Path $slabDir | Out-Null
$slabSrc = Join-Path $slabDir "SlabIntervalCheck.java"
$slabCpFile = Join-Path $slabDir "cp.txt"
$slabCp = "versions/26.1.2/build/classes/java/main;$($slabDir.Replace('\', '/'))"
[System.IO.File]::WriteAllText($slabCpFile, "-cp `"$slabCp`"", [System.Text.UTF8Encoding]::new($false))
$slabJava = @'
/** The four spec fixtures for the padded slab arithmetic of vfx_aura_box_interval (Task 3). */
public final class SlabIntervalCheck {
	private static final double MAX_RANGE = 1024.0;
	private static final double DIR_EPS = 1.0e-8;

	/**
	 * The helper's arithmetic, step for step: clamp the sentinels to the reachable range, pad, invert
	 * the direction, take tNear/tFar from min/max pairs. Returns {tNear, tFar}; a miss is tFar &lt; tNear
	 * (or tFar &lt; 0 when the box is behind the camera).
	 */
	private static double[] interval(final double[] cam, final double[] bmin, final double[] bmax,
			final double[] viewDir, final double softness) {
		final double pad = 0.5 * softness;
		final double[] near = new double[3];
		final double[] far = new double[3];
		for (int a = 0; a < 3; a++) {
			final double lo = clamp(bmin[a], cam[a] - MAX_RANGE, cam[a] + MAX_RANGE) - pad;
			final double hi = clamp(bmax[a], cam[a] - MAX_RANGE, cam[a] + MAX_RANGE) + pad;
			final double dir = Math.abs(viewDir[a]) < DIR_EPS ? DIR_EPS : viewDir[a];
			final double invDir = 1.0 / dir;
			final double t0 = (lo - cam[a]) * invDir;
			final double t1 = (hi - cam[a]) * invDir;
			near[a] = Math.min(t0, t1);
			far[a] = Math.max(t0, t1);
		}
		return new double[] {Math.max(Math.max(near[0], near[1]), near[2]),
			Math.min(Math.min(far[0], far[1]), far[2])};
	}

	public static void main(final String[] args) {
		sentinel_clamp();
		cull_pad();
		robust_inverse();
		skip_tail_clamp();
	}

	/** Spec fixture sentinel_clamp: a box with y = -1e9 is the post-clamp box [camY - 1024, top]. */
	private static void sentinel_clamp() {
		final double[] cam = {0.0, 64.0, 0.0};
		final double[] down = {0.0, -1.0, 0.0};
		final double[] bmin = {-50.0, -1.0e9, -50.0};
		final double[] bmax = {50.0, 40.0, 50.0};
		final double[] got = interval(cam, bmin, bmax, down, 0.0);
		final double[] clamped = interval(cam, new double[] {-50.0, cam[1] - MAX_RANGE, -50.0}, bmax, down, 0.0);
		same(got, clamped, "sentinel_clamp: y = -1e9 must equal the post-clamp box");
		near(got[0], 24.0, "sentinel_clamp tNear (the box top, 24 below the camera)");
		near(got[1], MAX_RANGE, "sentinel_clamp tFar (the reachable range, not the sentinel)");
		// The same box with XZ sentinels instead: the XZ faces must not be reachable either.
		final double[] xz = interval(cam, new double[] {-1.0e9, -1.0e9, -1.0e9}, new double[] {1.0e9, 40.0, 1.0e9}, down, 0.0);
		near(xz[1], MAX_RANGE, "sentinel_clamp tFar with XZ sentinels");
		System.out.println("  sentinel_clamp: y = -1e9 == [camY - 1024, 40]; a downward ray gets ["
			+ got[0] + ", " + got[1] + "] instead of a million blocks");
	}

	/**
	 * Spec fixture cull_pad: a ray whose closest approach to a dense box face is g &lt; softness/2 has
	 * real coverage (0.5 - g/softness) and must not be culled; culling on a bare box would cut that
	 * cliff, and tStart/tLimit padding cannot help because it is unreachable for a culled ray.
	 */
	private static void cull_pad() {
		final double softness = 4.0;
		final double pad = 0.5 * softness;
		final double[] dir = {1.0, 0.0, 0.0};
		final double[] bmin = {0.0, 0.0, 0.0};
		final double[] bmax = {10.0, 10.0, 10.0};
		for (final double g : new double[] {0.5, 1.0, 0.9 * pad}) {
			final double[] ray = {-20.0, 10.0 + g, 5.0};
			if (interval(ray, bmin, bmax, dir, 0.0)[1] >= 0.0) {
				throw new AssertionError("cull_pad: the bare box must cull a miss at g = " + g);
			}
			if (culled(interval(ray, bmin, bmax, dir, softness))) {
				throw new AssertionError("cull_pad: the padded box culls g = " + g
					+ " - a real coverage of " + (0.5 - g / softness) + " would be cut to 0");
			}
		}
		// The ray that grazes the face exactly is a hit either way, and just past the pad the padded
		// box may cull again - the pad is a near-miss band, not a blanket.
		if (culled(interval(new double[] {-20.0, 10.0, 5.0}, bmin, bmax, dir, softness))) {
			throw new AssertionError("cull_pad: a ray exactly on the face must not be culled");
		}
		if (!culled(interval(new double[] {-20.0, 10.0 + 1.01 * pad, 5.0}, bmin, bmax, dir, softness))) {
			throw new AssertionError("cull_pad: a miss past softness/2 must still be culled");
		}
		System.out.println("  cull_pad: bare culls a g < " + pad + " miss, padded does not, past " + pad + " it culls again");
	}

	/** Spec fixture robust_inverse: an exact zero direction component widens the interval, never NaN. */
	private static void robust_inverse() {
		final double[] cam = {0.0, 64.0, 0.0};
		final double[] side = {1.0, 0.0, 0.0};
		// A face exactly on the camera: the naive 1.0/viewDir is 0 * Infinity there (NaN), the 1e-8
		// inversion is a plain 0, and the box is entered at t = 0.
		final double[] onFace = interval(cam, new double[] {-10.0, 64.0, -10.0}, new double[] {10.0, 74.0, 10.0}, side, 0.0);
		finite(onFace, "a face exactly on the camera");
		near(onFace[0], 0.0, "on-face tNear");
		near(onFace[1], 10.0, "on-face tFar");
		// A box far along x with the camera inside its y/z range, so the parallel axis is a pure
		// constraint: a larger epsilon turns it into one and rejects a ray that really hits, which is
		// the expensive direction - it costs pixels, where a false accept only costs a march.
		final double[] far = interval(cam, new double[] {100.0, 54.0, -10.0}, new double[] {110.0, 74.0, 10.0}, side, 0.0);
		finite(far, "a far box with a parallel component");
		near(far[0], 100.0, "far tNear");
		near(far[1], 110.0, "far tFar");
		// The other orientations: finite, and never narrowed to a miss by the widened axis.
		for (final double[] dir : new double[][] {{0.0, 0.6, -0.8}, {-1.0, 0.0, 0.0}}) {
			final double[] t = interval(cam, new double[] {-10.0, 54.0, -10.0}, new double[] {10.0, 74.0, 10.0}, dir, 2.0);
			finite(t, "dir (" + dir[0] + ", " + dir[1] + ", " + dir[2] + ")");
			if (t[1] < t[0]) {
				throw new AssertionError("robust_inverse: dir (" + dir[0] + ", " + dir[1] + ", " + dir[2]
					+ ") narrowed to a miss [" + t[0] + ", " + t[1] + "] - a zero component may only widen");
			}
		}
		System.out.println("  robust_inverse: a face on the camera and a far box stay finite (never NaN), a zero component only widens");
	}

	/**
	 * Spec fixture skip_tail_clamp: clamp(tFarSkip - softness, 0, max(tLimit - softness, 0)) never
	 * exceeds tLimit - softness, so the last softness before tLimit is still sampled - the boundary at
	 * a skip exit can sit within near-miss reach of tLimit.
	 */
	private static void skip_tail_clamp() {
		final double softness = 4.0;
		for (final double tLimit : new double[] {0.0, 1.0, 3.9, 8.0, 400.0, MAX_RANGE}) {
			final double tail = Math.max(tLimit - softness, 0.0);
			for (final double tFarSkip : new double[] {-1.0e9, -5.0, 0.0, 1.0, tLimit, tLimit + 1.0, 1.0e9}) {
				final double advance = clamp(tFarSkip - softness, 0.0, tail);
				if (advance > tail || advance < 0.0) {
					throw new AssertionError("skip_tail_clamp: tFarSkip " + tFarSkip + ", tLimit " + tLimit
						+ " -> " + advance + ", outside [0, " + tail + "]");
				}
			}
		}
		near(clamp(MAX_RANGE - softness, 0.0, Math.max(8.0 - softness, 0.0)), 4.0, "skip_tail_clamp advance");
		near(clamp(1.0 - softness, 0.0, Math.max(1.0 - softness, 0.0)), 0.0, "skip_tail_clamp under a short tLimit");
		System.out.println("  skip_tail_clamp: the advance stays in [0, max(tLimit - softness, 0)] for every tFarSkip");
	}

	private static boolean culled(final double[] t) {
		return t[1] < 0.0 || t[1] < t[0];
	}

	private static void finite(final double[] t, final String what) {
		for (final double v : t) {
			if (Double.isNaN(v) || Double.isInfinite(v)) {
				throw new AssertionError("robust_inverse: " + what + " produced " + v);
			}
		}
	}

	private static double clamp(final double v, final double lo, final double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static void same(final double[] a, final double[] b, final String what) {
		for (int i = 0; i < a.length; i++) {
			near(a[i], b[i], what);
		}
	}

	private static void near(final double actual, final double want, final String what) {
		if (Double.isNaN(actual) || Math.abs(actual - want) > 1.0e-9) {
			throw new AssertionError(what + " = " + actual + ", want " + want);
		}
	}
}
'@
[System.IO.File]::WriteAllText($slabSrc, $slabJava, [System.Text.UTF8Encoding]::new($false))

Write-Host "Aura slab interval check (CPU simulation)"
Push-Location $repoRoot
try {
	& $javac "@$slabCpFile" -d $slabDir $slabSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$slabCpFile" SlabIntervalCheck
	if ($LASTEXITCODE -ne 0) { throw "SlabIntervalCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Aura slab interval check OK."
exit 0
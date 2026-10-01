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
# Static assertions only: Gradle does not compile GLSL and there is no GPU here, so nothing here
# claims a shader compiles.
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
Write-Host "Aura bounds plugin check OK."
exit 0
# Dev-only guard for the aura-mask skip/box bounds plugin contract (Task 1).
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

Write-Host "Aura bounds plugin check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "aura bounds check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  both bounds methods default to 'not provided' (false, nothing appended), so every existing plugin keeps compiling"
Write-Host "  Javadoc: the box CONTAINS the whole field <= 0 region; the skip box lies ENTIRELY in field > 0 and is inscribed in the hole (a circles' AABB does not qualify)"
Write-Host "  VFXMaskShapeGlsl is still @FunctionalInterface (String glsl() only); VFXAPI keeps exactly its two registerMaskShapeGlsl overloads and no steps overload"
Write-Host "Aura bounds plugin check OK."
exit 0
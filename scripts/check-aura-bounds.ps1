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
# Task 4 asserts the broad phase wired into the march: both branches sit after the occDist/tLimit
# computation and before the march loop (the occlusion opt-out raises MAX_RANGE first, so a bounds
# branch above it would clamp the wrong range), in the priority order skip -> box -> sphere -> none,
# the skip advance only from inside the box, and the cull on the PADDED interval - a bare-box cull
# would cut a cliff of up to 0.5 coverage at a face, which no tStart/tLimit padding can repair
# because it is unreachable for a culled ray. The five ray classes are simulated on the CPU below,
# with SDF evaluation counts as the metric.
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
$sphereTo = '                    }'
if ($headLines.Count -eq 0) {
	$problems.Add("aura bounds check: cannot read mask_coverage.fsh at HEAD (is git available?)")
} else {
	$headFrom = [Array]::IndexOf($headLines, $sphereFrom)
	# The block ends at its own closing brace - the first line indented exactly like the `if` that opens
	# it, every inner brace being deeper. Anchoring on the line after the block would not survive this
	# task's own commit, which changes what follows it.
	$headTo = -1
	for ($i = $headFrom + 1; $i -lt $headLines.Count; $i++) {
		if ($headLines[$i] -eq $sphereTo) {
			$headTo = $i
			break
		}
	}
	if ($headFrom -lt 0 -or $headTo -le $headFrom) {
		$problems.Add("mask_coverage.fsh at HEAD has no sphere broad phase between its two anchors - the untouched-sphere check cannot run")
	} else {
		$block = ($headLines[$headFrom..($headTo - 1)]) -join "`n"
		if (-not $shader.Contains($block)) {
			$problems.Add("the sphere broad phase in mask_coverage.fsh is no longer byte-identical to HEAD (boundOffset / boundB / boundC through the end of its if)")
		}
	}
}

# 12) Task 4: the broad phase inside the march. Ordered anchors first, because the whole point of this
#     section is where the branches sit: after the tLimit computation (the occlusion opt-out raises
#     MAX_RANGE, so a bounds branch placed above it would clamp the wrong range) and before the march
#     loop, in the spec's priority order skip -> box -> sphere -> none.
$occLimitLine = '                        tLimit = min(sceneDist + sceneDist * 2.0e-3 + 0.5 * softness, VFX_PLUGIN_AURA_MAX_RANGE);'
$skipBranchLine = '                    if (vfx_custom_skip_bounds(i, skipMin, skipMax)) {'
$boxBranchLine = '                    if (vfx_custom_box_bounds(i, boxMin, boxMax)) {'
$sphereLine = '                    vec4 bounds = vfx_custom_bounds();'
$marchLine = '                        for (int s = 0; s < VFX_PLUGIN_AURA_ENTRY_STEPS + VFX_PLUGIN_AURA_EXIT_STEPS; s++) {'
$order = @(
	@('the occDist/tLimit computation', $occLimitLine),
	@('the skip branch', $skipBranchLine),
	@('the box branch', $boxBranchLine),
	@('the sphere broad phase', $sphereLine),
	@('the march loop', $marchLine))
$previousAt = -1
foreach ($step in $order) {
	$at = $shader.IndexOf($step[1])
	if ($at -lt 0) {
		$problems.Add("mask_coverage.fsh has no $($step[0]) - looked for: $($step[1])")
		continue
	}
	if ($at -le $previousAt) {
		$problems.Add("mask_coverage.fsh $($step[0]) does not sit after the previous step - the order is fixed: occDist/tLimit -> skip -> box -> sphere -> march")
	}
	$previousAt = $at
}

# 13) the two #ifdef names are the ones Task 2's generator emits, read back from it rather than
#     retyped here: a renamed define would leave the branch silently uncompiled.
$boxDefineName = [regex]::Match($variants, '#define (VFX_CUSTOM_HAS_\w*BOX_BOUNDS) 1').Groups[1].Value
$skipDefineName = [regex]::Match($variants, '#define (VFX_CUSTOM_HAS_\w*SKIP_BOUNDS) 1').Groups[1].Value
foreach ($defined in @(@('box', $boxDefineName), @('skip', $skipDefineName))) {
	if ($defined[1] -eq '') {
		$problems.Add("VFXMaskShaderVariants emits no $($defined[0]) bounds define - the shader's #ifdef name cannot be checked")
		continue
	}
	if (-not $shader.Contains("#ifdef $($defined[1])")) {
		$problems.Add("mask_coverage.fsh has no '#ifdef $($defined[1])' - the $($defined[0]) branch must compile only for a variant that declares it")
	}
}

# 14) the skip advance: only from inside, and with the tail clamp exactly as the spec writes it. A
#     camera outside the box may have a coverage segment BEFORE it, which a bare advance jumps over;
#     the clamp keeps the last softness before tLimit sampled.
foreach ($literal in @(
	'if (tNearSkip <= 0.0 && tFarSkip > 0.0) {',
	'tStart = max(tStart, clamp(tFarSkip - softness, 0.0, max(tLimit - softness, 0.0)));')) {
	if (-not $shader.Contains($literal)) {
		$problems.Add("mask_coverage.fsh is missing the skip advance literal: $literal")
	}
}

# 15) the box branch: the padded helper, both interval ends used, and the two span formulas. The cull
#     must read the helper's tNear/tFar - a second, bare slab test in the branch would cut the
#     near-miss cliff - so the helper call is asserted whole, softness included.
foreach ($literal in @(
	'vfx_aura_box_interval(boxMin, boxMax, viewDir, softness, tNearBox, tFarBox);',
	'if (tFarBox < 0.0 || tFarBox < tNearBox) {',
	'tStart = max(tNearBox - softness, 0.0);',
	'tLimit = min(tLimit, tFarBox + softness);')) {
	if (-not $shader.Contains($literal)) {
		$problems.Add("mask_coverage.fsh is missing the box branch literal: $literal")
	}
}
$sphereAt = $shader.IndexOf($sphereLine)
$marchAt = $shader.IndexOf($marchLine)
$boxAt = $shader.IndexOf($boxBranchLine)
$skipAt = $shader.IndexOf($skipBranchLine)
if ($boxAt -ge 0 -and $sphereAt -gt $boxAt) {
	if (($shader.Substring($boxAt, $sphereAt - $boxAt)) -notmatch "(?m)^#else$") {
		$problems.Add("mask_coverage.fsh: the sphere path is not the #else of the box branch - the priority is skip -> box -> sphere -> none, so a declared containment box replaces the sphere broad phase")
	}
	if ($skipAt -gt 0) {
		# The skip branch is a pure tStart advance: it may not cull and it may not shorten the range -
		# a hole is not a bound, the coverage region can be anywhere.
		$skipBranch = $shader.Substring($skipAt, $boxAt - $skipAt)
		if ($skipBranch -match 'tLimit =' -or $skipBranch -match '= false') {
			$problems.Add("mask_coverage.fsh: the skip branch culls or shortens the range - a skip box lies in the hole and can only advance tStart")
		}
	}
}
if ($boxAt -ge 0 -and $marchAt -gt $sphereAt) {
	if (($shader.Substring($sphereAt, $marchAt - $sphereAt)) -notmatch "(?m)^#endif$") {
		$problems.Add("mask_coverage.fsh: the sphere path is not closed by an #endif - a variant with no box bounds must still compile")
	}
}
$boxIfdefAt = $shader.IndexOf("#ifdef $boxDefineName")
$skipIfdefAt = $shader.IndexOf("#ifdef $skipDefineName")
if ($skipIfdefAt -ge 0 -and $boxIfdefAt -ge 0) {
	if ($skipIfdefAt -gt $boxIfdefAt) {
		$problems.Add("mask_coverage.fsh: the skip branch is nested after the box branch - skip is consulted first")
	}
	if ($shader.IndexOf('#endif', $skipIfdefAt) -gt $boxIfdefAt) {
		$problems.Add("mask_coverage.fsh: the skip branch's #endif comes after the box #ifdef - the two branches are siblings")
	}
}

# 16) the single broad-phase verdict. Each branch compiles alone (a variant may declare either bound,
#     both or neither), so the verdict cannot live inside one of them: the box branch sets it, the
#     sphere path folds its own boundsHit into it, and the emptiness test below is what turns a miss
#     into cov 0 with no march.
foreach ($literal in @(
	'                    bool broadHit = true;',
	'                            broadHit = false;',
	'                    broadHit = boundsHit;',
	'                    if (!broadHit || tStart >= tLimit) {')) {
	if (-not $shader.Contains($literal)) {
		$problems.Add("mask_coverage.fsh is missing the broad-phase verdict literal: $literal")
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
Write-Host "  mask_coverage.fsh runs the skip and box branches after occDist/tLimit and before the march loop, in that order, with the sphere path as the box branch's #else"
Write-Host "  the skip advance is guarded by 'tNearSkip <= 0.0 && tFarSkip > 0.0' and clamps to max(tLimit - softness, 0.0); the box branch culls on the PADDED interval only"
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

# --- runnable: the five ray classes, the broad phase and the march over synthetic fields ----------
# The march, the cone envelope, the step boost and the cover factors below mirror mask_coverage.fsh;
# the occlusion term is a constant 1 because every ray here runs the occlusion opt-out (occDist =
# 1.0e9), which is also the case the branch ordering exists for: the opt-out raises tLimit to
# MAX_RANGE before the bounds minimum is applied. The metric is SDF evaluation counts per ray.
$broadSrc = Join-Path $slabDir "AuraBroadPhaseCheck.java"
$broadJava = @'
/**
 * The Task 4 ray-class fixtures: the aura march and the broad phase Task 4 wires into it, over
 * synthetic fields, with SDF evaluation counts as the metric.
 */
public final class AuraBroadPhaseCheck {
	private static final double MAX_RANGE = 1024.0;
	private static final int STEPS = 40 + 16;
	private static final double MIN_STEP = 0.5;
	private static final double MAX_STEP = 64.0;
	private static final double STEP_BOOST = 0.2;
	private static final double SOFTNESS = 4.0;
	private static final double CHORD_EPS = 1.0e-4;
	/**
	 * Two rays that sample the same field from different starts may differ by the envelope's own fat:
	 * a bracket with both samples outside keeps dCross &gt;= -B/2 and the Lipschitz envelope never
	 * reports thinner than the truth, so the ceiling on a coverage delta is STEP_BOOST/2.
	 */
	private static final double COVER_EPS = 0.5 * STEP_BOOST;

	/** A plugin's field: the raw world-space SDF the aura march samples (f &lt;= 0 is coverage). */
	private interface Field {
		double sdf(double x, double y, double z);
	}

	/** One marched ray: its coverage, its cost, and the first entry the occlusion ramp would use. */
	private static final class Result {
		double cov;
		int evals;
		double tEnter = -1.0;
		boolean saturated;
	}

	/** The broad phase's verdict for one ray, as the shader's branches leave it. */
	private static final class Span {
		double tStart;
		double tLimit;
		boolean hit = true;
	}

	/**
	 * The reporter's wall: coverage is the exterior of two overlapping discs, cut from above by a cap.
	 * Both terms are one-Lipschitz, like the built-in aura fields. Their overlap (a lens around the
	 * origin) is the hole, and an honest containment box for this field is unbounded in XZ - the
	 * reporter's sentinel box - so this is the field where the skip box is the lever and a containment
	 * box buys nothing. The travel ray below is exactly the reporter's shape of spend.
	 */
	private static Field wall() {
		return (x, y, z) -> Math.max(50.0 - Math.min(Math.hypot(x, z - 45.0), Math.hypot(x, z + 45.0)), y - 40.0);
	}

	/** A bounded slab, so the containment box has a region it can actually cull. */
	private static Field slab() {
		return (x, y, z) -> {
			final double qx = Math.abs(x) - 4.0;
			final double qy = Math.abs(y) - 4.0;
			final double qz = Math.abs(z) - 4.0;
			final double ox = Math.max(qx, 0.0);
			final double oy = Math.max(qy, 0.0);
			final double oz = Math.max(qz, 0.0);
			return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0.0);
		};
	}

	/** A plugin's skip box: the inscribed part of the hole, with the reporter's y sentinel. */
	private static final double[] WALL_SKIP_MIN = {-12.0, -1.0e9, -1.0};
	private static final double[] WALL_SKIP_MAX = {12.0, 40.0, 1.0};
	/** A plugin's containment box for the wall: unbounded in XZ, capped above. */
	private static final double[] WALL_BOX_MIN = {-1.0e9, -1.0e9, -1.0e9};
	private static final double[] WALL_BOX_MAX = {1.0e9, 40.0, 1.0e9};
	/** A plugin's containment box for the slab: the slab itself. */
	private static final double[] SLAB_BOX_MIN = {-4.0, -4.0, -4.0};
	private static final double[] SLAB_BOX_MAX = {4.0, 4.0, 4.0};

	/** The padded slab arithmetic of vfx_aura_box_interval: clamp the sentinels, pad, invert, min/max. */
	private static double[] interval(final double[] cam, final double[] bmin, final double[] bmax,
			final double[] viewDir, final double softness) {
		final double pad = 0.5 * softness;
		double tNear = -1.0e30;
		double tFar = 1.0e30;
		for (int a = 0; a < 3; a++) {
			final double lo = clamp(bmin[a], cam[a] - MAX_RANGE, cam[a] + MAX_RANGE) - pad;
			final double hi = clamp(bmax[a], cam[a] - MAX_RANGE, cam[a] + MAX_RANGE) + pad;
			final double dir = Math.abs(viewDir[a]) < 1.0e-8 ? 1.0e-8 : viewDir[a];
			final double t0 = (lo - cam[a]) / dir;
			final double t1 = (hi - cam[a]) / dir;
			tNear = Math.max(tNear, Math.min(t0, t1));
			tFar = Math.min(tFar, Math.max(t0, t1));
		}
		return new double[] {tNear, tFar};
	}

	/** The march loop of mask_coverage.fsh (lip = 1 on these fields) plus the cover factors after it. */
	private static Result march(final Field field, final double[] cam, final double[] dir,
			final double tStart, final double tLimit) {
		final Result r = new Result();
		double t = tStart;
		double tPrev = tStart;
		double dPrev = 0.0;
		boolean havePrev = false;
		boolean inside = false;
		double dBound = 1.0e9;
		double tBound = tStart;
		final double stepBoost = STEP_BOOST * SOFTNESS;
		for (int s = 0; s < STEPS; s++) {
			final double d = field.sdf(cam[0] + dir[0] * t, cam[1] + dir[1] * t, cam[2] + dir[2] * t);
			r.evals++;
			if (havePrev) {
				final double dCross = 0.5 * (dPrev + d - (t - tPrev));
				if (dCross < dBound) {
					dBound = dCross;
					tBound = tPrev + (dPrev - dCross);
				}
			}
			if (d < dBound) {
				dBound = d;
				tBound = t;
			}
			if (d <= 0.0) {
				if (!inside) {
					inside = true;
					if (r.tEnter < 0.0) {
						r.tEnter = havePrev ? tPrev + (t - tPrev) * dPrev / Math.max(dPrev - d, 1.0e-4) : t;
					}
				}
				if (d <= -0.5 * SOFTNESS) {
					r.saturated = true;
					break;
				}
			} else if (inside) {
				inside = false;
			}
			final double dive = d - (tLimit - t);
			if (dive > 0.5 * SOFTNESS || dive >= dBound) {
				break;
			}
			havePrev = true;
			tPrev = t;
			dPrev = d;
			t += clamp(Math.abs(d) + stepBoost, MIN_STEP, MAX_STEP);
			if (t >= tLimit) {
				break;
			}
		}
		// A miss is the envelope bound on a chord shrunk to its closest approach; an entered ray keeps
		// the still-inside chord, so tExit is the limit (the refine loop only shortens a chord that
		// ended, which no fixture here does).
		r.cov = r.tEnter < 0.0 ? cover(dBound, tBound) : cover(dBound, tLimit);
		return r;
	}

	/** vfx_aura_cover with the occlusion term at its constant 1 (the occlusion opt-out). */
	private static double cover(final double d, final double tExit) {
		if (tExit <= CHORD_EPS) {
			return 0.0;
		}
		return clamp(0.5 - d / SOFTNESS, 0.0, 1.0) * clamp((tExit - CHORD_EPS) / SOFTNESS, 0.0, 1.0);
	}

	/**
	 * The broad phase exactly as the shader orders it: the skip advance first, then the box, which also
	 * holds the cull (a null pair means the plugin declared nothing of that kind). The box overwrites
	 * tStart, which is what "box before sphere" and "skip before box" both mean here.
	 */
	private static Span broad(final double[] cam, final double[] dir, final double tLimit,
			final double[] skipMin, final double[] skipMax, final double[] boxMin, final double[] boxMax) {
		final Span span = new Span();
		span.tStart = 0.0;
		span.tLimit = tLimit;
		if (skipMin != null) {
			final double[] skip = interval(cam, skipMin, skipMax, dir, SOFTNESS);
			if (skip[0] <= 0.0 && skip[1] > 0.0) {
				span.tStart = Math.max(span.tStart,
					clamp(skip[1] - SOFTNESS, 0.0, Math.max(span.tLimit - SOFTNESS, 0.0)));
			}
		}
		if (boxMin != null) {
			final double[] box = interval(cam, boxMin, boxMax, dir, SOFTNESS);
			if (box[1] < 0.0 || box[1] < box[0]) {
				span.hit = false;
			} else {
				span.tStart = Math.max(box[0] - SOFTNESS, 0.0);
				span.tLimit = Math.min(span.tLimit, box[1] + SOFTNESS);
			}
		}
		return span;
	}

	/** The shader's if/else: a culled or empty span contributes nothing and never marches. */
	private static Result run(final Field field, final double[] cam, final double[] dir, final Span span) {
		if (!span.hit || span.tStart >= span.tLimit) {
			return new Result();
		}
		return march(field, cam, dir, span.tStart, span.tLimit);
	}

	/** The field's true closest approach along the ray, sampled finely; coverage is 0 at or below softness/2. */
	private static double gap(final Field field, final double[] cam, final double[] dir, final double tLimit) {
		double best = 1.0e30;
		for (double t = 0.0; t <= tLimit; t += 0.02) {
			best = Math.min(best, field.sdf(cam[0] + dir[0] * t, cam[1] + dir[1] * t, cam[2] + dir[2] * t));
		}
		return best;
	}

	public static void main(final String[] args) {
		inside();
		away();
		travel();
		boxCullZeroCoverage();
		skipOnlyFromInside();
	}

	/** Ray class inside: the camera starts in the volume, so the first sample saturates and the bounds change nothing. */
	private static void inside() {
		final Field field = slab();
		final double[] cam = {0.0, 0.0, 0.0};
		final double[] dir = unit(0.3, 0.9, 0.3);
		final Result ref = march(field, cam, dir, 0.0, MAX_RANGE);
		final Span span = broad(cam, dir, MAX_RANGE, null, null, SLAB_BOX_MIN, SLAB_BOX_MAX);
		final Result got = run(field, cam, dir, span);
		if (ref.evals > 2 || !ref.saturated) {
			throw new AssertionError("inside: the reference ray must saturate on its first sample, got "
				+ ref.evals + " evaluations, saturated " + ref.saturated);
		}
		if (span.tStart != 0.0) {
			throw new AssertionError("inside: the containment box moved tStart to " + span.tStart);
		}
		if (got.evals != ref.evals || got.cov != ref.cov || got.saturated != ref.saturated) {
			throw new AssertionError("inside: the bounds changed a saturated ray (" + ref.evals + "/" + ref.cov
				+ " -> " + got.evals + "/" + got.cov + ")");
		}
		System.out.println("  inside: " + got.evals + " evaluation(s), cov " + got.cov
			+ " - a saturated ray is identical with and without the bounds (reference " + ref.evals + ")");
	}

	/**
	 * Ray class away: the camera sits above a bounded coverage region and the ray never re-enters it.
	 * Today the march crawls to the range cap; the containment box culls it before the first sample.
	 */
	private static void away() {
		final Field field = slab();
		final double[] dir = {0.0, 1.0, 0.0};
		int culledRays = 0;
		int cheapest = Integer.MAX_VALUE;
		int dearest = 0;
		for (final double clearance : new double[] {2.5, 3.0, 4.0, 6.0, 9.0}) {
			final double[] cam = {0.0, 4.0 + clearance, 0.0};
			final Result ref = march(field, cam, dir, 0.0, MAX_RANGE);
			final Span span = broad(cam, dir, MAX_RANGE, null, null, SLAB_BOX_MIN, SLAB_BOX_MAX);
			final Result got = run(field, cam, dir, span);
			if (span.hit) {
				throw new AssertionError("away: a ray " + clearance + " above the slab must be culled by the padded box");
			}
			if (got.evals != 0 || got.cov != 0.0) {
				throw new AssertionError("away: a culled ray marched " + got.evals + " times for cov " + got.cov);
			}
			if (ref.evals < 4) {
				throw new AssertionError("away: the reference ray cost only " + ref.evals
					+ " evaluations - the fixture is meant to measure a crawl, not an inside-class ray");
			}
			culledRays++;
			cheapest = Math.min(cheapest, ref.evals);
			dearest = Math.max(dearest, ref.evals);
		}
		System.out.println("  away: " + culledRays + " rays above the slab culled to 0 evaluations, against " + cheapest
			+ "-" + dearest + " today - the containment box is what buys this");
	}

	/**
	 * Ray class travel: the camera sits inside the hole, inside the skip box, and the ray crosses it
	 * toward a distant coverage region - the reporter's case. The sweep then covers the rim and the
	 * rays that never enter: no ray may lose coverage beyond the envelope's own fat, a saturated ray
	 * must stay saturated, and no ray may cost more than before.
	 */
	private static void travel() {
		final Field field = wall();
		final double[] cam = {11.0, 0.0, 0.0};
		final double[] dir = {-1.0, 0.0, 0.0};
		final Result ref = march(field, cam, dir, 0.0, MAX_RANGE);
		final Span span = broad(cam, dir, MAX_RANGE, WALL_SKIP_MIN, WALL_SKIP_MAX, null, null);
		final Result got = run(field, cam, dir, span);
		if (span.tStart <= 0.0) {
			throw new AssertionError("travel: the skip advance did not move tStart off the camera");
		}
		if (got.evals >= ref.evals) {
			throw new AssertionError("travel: " + got.evals + " evaluations against " + ref.evals + " before the skip");
		}
		if (Math.abs(got.tEnter - ref.tEnter) > SOFTNESS) {
			throw new AssertionError("travel: tEnter moved by " + Math.abs(got.tEnter - ref.tEnter)
				+ " blocks, more than the softness " + SOFTNESS + " the pad covers");
		}
		int rays = 0;
		int saturated = 0;
		int misses = 0;
		double worstDelta = 0.0;
		int cheapest = Integer.MAX_VALUE;
		int dearest = 0;
		for (final double pitch : new double[] {0.8, 0.4, 0.12, 0.0, -0.12, -0.4, -0.8}) {
			for (int yawStep = 0; yawStep < 8; yawStep++) {
				final double yaw = Math.PI * yawStep / 4.0;
				final double[] ray = unit(Math.cos(yaw), pitch, Math.sin(yaw));
				final Result before = march(field, cam, ray, 0.0, MAX_RANGE);
				final Result after = run(field, cam, ray,
					broad(cam, ray, MAX_RANGE, WALL_SKIP_MIN, WALL_SKIP_MAX, null, null));
				final double delta = Math.abs(after.cov - before.cov);
				if (delta > COVER_EPS) {
					throw new AssertionError("travel: a rim/saturated ray lost " + delta + " of coverage ("
						+ before.cov + " -> " + after.cov + ") - the ceiling is " + COVER_EPS);
				}
				if (before.saturated && !after.saturated) {
					throw new AssertionError("travel: a saturated ray stopped saturating");
				}
				if (after.evals > before.evals) {
					throw new AssertionError("travel: a ray cost more (" + after.evals + " against " + before.evals + ")");
				}
				if (before.saturated) {
					saturated++;
				} else {
					misses++;
				}
				worstDelta = Math.max(worstDelta, delta);
				cheapest = Math.min(cheapest, after.evals);
				dearest = Math.max(dearest, after.evals);
				rays++;
			}
		}
		if (saturated < 4 || misses < 4 || rays < 40) {
			throw new AssertionError("travel: the sweep covered " + rays + " rays (" + saturated + " saturated, "
				+ misses + " never entering) - too few to say anything");
		}
		System.out.println("  travel: " + ref.evals + " -> " + got.evals + " evaluations, tStart 0 -> " + span.tStart
			+ ", tEnter " + fmt(ref.tEnter) + " -> " + fmt(got.tEnter) + " (within the softness " + SOFTNESS + ")");
		System.out.println("         " + rays + " swept hole rays (" + saturated + " saturated, " + misses
			+ " never entering): worst coverage delta " + fmt(worstDelta) + " (ceiling " + COVER_EPS
			+ "), evaluations " + cheapest + "-" + dearest);
	}

	/**
	 * The envelope's floor on the reported distance for a ray whose true closest approach is g:
	 * lip * step &lt;= |d| + B gives dCross &gt;= 0.5 * (g - B), and the envelope never reports
	 * thinner than the truth, so that is where dBound can bottom out - a cull has to preserve the
	 * coverage that comes with it. Past g &gt;= softness + B that floor is past softness/2, so the
	 * silhouette is exactly 0 and the march must report 0.
	 */
	private static double envelopeCeiling(final double trueGap) {
		return 0.5 - 0.5 * (trueGap - STEP_BOOST * SOFTNESS) / SOFTNESS;
	}

	/**
	 * Fixture box_cull_zero_coverage: every ray the padded slab culls must have the full march's
	 * coverage at ~0, and every near-miss ray it keeps must keep its coverage. A coverage predicate,
	 * not a silhouette predicate: the near-miss band inside the pad has no hit at all, which a
	 * silhouette test is blind to, and a bare-box cull would cut it to 0.
	 */
	private static void boxCullZeroCoverage() {
		final Field field = slab();
		final double[] up = {0.0, 1.0, 0.0};
		int cleared = 0;
		int boundary = 0;
		int kept = 0;
		double worstCleared = 0.0;
		double worstBoundary = 0.0;
		double worstKept = 0.0;
		// Vertical rays at a known clearance over the top face: the field reads exactly that clearance,
		// so what each ray really covers is known and the tiers below are exact.
		for (final double clearance : new double[] {0.5, 1.0, 1.5, 1.9, 2.1, 2.5, 3.0, 4.0, 6.0, 9.0}) {
			final double[] cam = {0.0, 4.0 + clearance, 0.0};
			final double trueGap = gap(field, cam, up, MAX_RANGE);
			if (broad(cam, up, MAX_RANGE, null, null, SLAB_BOX_MIN, SLAB_BOX_MAX).hit) {
				if (trueGap >= SOFTNESS / 2) {
					throw new AssertionError("box_cull_zero_coverage: a ray at gap " + trueGap
						+ " is outside the pad and must be culled");
				}
				continue;
			}
			final double cov = checkCull(field, cam, up, trueGap);
			if (trueGap >= SOFTNESS + STEP_BOOST * SOFTNESS) {
				cleared++;
				worstCleared = Math.max(worstCleared, cov);
			} else {
				boundary++;
				worstBoundary = Math.max(worstBoundary, cov);
			}
		}
		// Diagonals from outside, where a ray misses the padded box by a corner rather than by a face.
		for (int yawStep = 0; yawStep < 12; yawStep++) {
			final double yaw = 2.0 * Math.PI * yawStep / 12.0;
			for (final double pitch : new double[] {-0.5, -0.2, 0.2, 0.5}) {
				final double[] cam = {14.0 + 3.0 * Math.cos(yaw), 9.0, 3.0 * Math.sin(yaw)};
				final double[] ray = unit(-Math.cos(yaw), pitch, -Math.sin(yaw));
				if (broad(cam, ray, MAX_RANGE, null, null, SLAB_BOX_MIN, SLAB_BOX_MAX).hit) {
					continue;
				}
				final double trueGap = gap(field, cam, ray, MAX_RANGE);
				final double cov = checkCull(field, cam, ray, trueGap);
				if (trueGap >= SOFTNESS + STEP_BOOST * SOFTNESS) {
					cleared++;
					worstCleared = Math.max(worstCleared, cov);
				} else {
					boundary++;
					worstBoundary = Math.max(worstBoundary, cov);
				}
			}
		}
		// The near-miss band inside the pad: real coverage, no hit, and the box branch must keep all of
		// it. These rays graze the slab's corner, so their true closest approach sits in the middle of
		// the march - a ray whose minimum is AT the camera fades out on tExit <= chordEps and has no
		// coverage to lose, which would make the fixture vacuous.
		for (final double g : new double[] {0.25, 0.5, 1.0, 1.5, 1.9}) {
			final double[] cam = grazingRay(g);
			final Span span = broad(cam, GRAZE_DIR, MAX_RANGE, null, null, SLAB_BOX_MIN, SLAB_BOX_MAX);
			if (!span.hit) {
				throw new AssertionError("box_cull_zero_coverage: a ray whose closest approach is " + g
					+ " was culled - its coverage is 0.5 - " + g + "/" + SOFTNESS + " = " + (0.5 - g / SOFTNESS));
			}
			final Result ref = march(field, cam, GRAZE_DIR, 0.0, MAX_RANGE);
			final Result got = run(field, cam, GRAZE_DIR, span);
			if (ref.cov <= 0.0) {
				throw new AssertionError("box_cull_zero_coverage: the fixture ray at gap " + g
					+ " has no coverage to preserve");
			}
			if (Math.abs(got.cov - ref.cov) > COVER_EPS) {
				throw new AssertionError("box_cull_zero_coverage: a near-miss ray lost coverage ("
					+ ref.cov + " -> " + got.cov + ") - the cliff the padded cull exists to avoid");
			}
			worstKept = Math.max(worstKept, Math.abs(got.cov - ref.cov));
			kept++;
		}
		// Grazing rays past the pad: culled, and their coverage is 0 by construction.
		for (final double g : new double[] {3.0, 4.0, 6.0, 9.0}) {
			final double[] cam = grazingRay(g);
			if (broad(cam, GRAZE_DIR, MAX_RANGE, null, null, SLAB_BOX_MIN, SLAB_BOX_MAX).hit) {
				throw new AssertionError("box_cull_zero_coverage: a ray whose closest approach is " + g
					+ " is outside the pad (softness/2 = " + (SOFTNESS / 2) + ") and must be culled");
			}
			final double cov = checkCull(field, cam, GRAZE_DIR, gap(field, cam, GRAZE_DIR, MAX_RANGE));
			if (g >= SOFTNESS + STEP_BOOST * SOFTNESS) {
				cleared++;
				worstCleared = Math.max(worstCleared, cov);
			} else {
				boundary++;
				worstBoundary = Math.max(worstBoundary, cov);
			}
		}
		if (cleared < 3 || boundary < 6 || kept < 5) {
			throw new AssertionError("box_cull_zero_coverage: only " + cleared + " cleared, " + boundary
				+ " boundary and " + kept + " kept rays - the fixture must exercise both sides of the pad");
		}
		System.out.println("  box_cull_zero_coverage: " + cleared
			+ " rays at least " + (SOFTNESS + STEP_BOOST * SOFTNESS)
			+ " blocks away reach cov 0.0 after the full march (worst " + fmt(worstCleared)
			+ "), " + boundary + " culled inside the envelope's fat reach stay under its ceiling "
			+ fmt(envelopeCeiling(SOFTNESS / 2)) + " (worst " + fmt(worstBoundary) + "), " + kept
			+ " near-miss rays inside the pad keep their coverage (worst delta " + fmt(worstKept) + ")");
	}

	/**
	 * One culled ray, checked both ways: the pad guarantees a true gap of at least softness/2, so its
	 * coverage is exactly 0, and what is left to bound is the envelope's own fat. Returns the coverage
	 * the full march reported for it.
	 */
	private static double checkCull(final Field field, final double[] cam, final double[] dir, final double trueGap) {
		if (trueGap < SOFTNESS / 2) {
			throw new AssertionError("box_cull_zero_coverage: a ray culled at gap " + trueGap
				+ " has real coverage (0.5 - " + trueGap + "/" + SOFTNESS + ") - the pad is softness/2");
		}
		final double cov = march(field, cam, dir, 0.0, MAX_RANGE).cov;
		if (trueGap >= SOFTNESS + STEP_BOOST * SOFTNESS) {
			if (cov > 1.0e-9) {
				throw new AssertionError("box_cull_zero_coverage: a ray " + (SOFTNESS + STEP_BOOST * SOFTNESS)
					+ " blocks away (gap " + trueGap + ") marched to cov " + cov + " - culling it costs nothing");
			}
		} else if (cov > envelopeCeiling(trueGap) + 1.0e-9) {
			throw new AssertionError("box_cull_zero_coverage: a ray culled at gap " + trueGap + " marched to cov "
				+ cov + ", past the envelope's own ceiling " + envelopeCeiling(trueGap));
		}
		return cov;
	}

	/** Fixture skip_only_from_inside: with the camera outside the skip box, tStart must not move. */
	private static void skipOnlyFromInside() {
		final Field field = wall();
		// Inside the coverage region, outside the skip box, with the box ahead on the ray: a naive
		// unconditional advance would jump this saturated ray's whole chord.
		final double[] cam = {-30.0, 0.0, 0.0};
		final double[] dir = {1.0, 0.0, 0.0};
		final Result ref = march(field, cam, dir, 0.0, MAX_RANGE);
		final Span span = broad(cam, dir, MAX_RANGE, WALL_SKIP_MIN, WALL_SKIP_MAX, null, null);
		final Result got = run(field, cam, dir, span);
		final double[] padded = interval(cam, WALL_SKIP_MIN, WALL_SKIP_MAX, dir, SOFTNESS);
		// What the branch would have advanced to without the inside test - the hazard the test exists
		// for: the box is ahead of the camera, so its exit sits in the middle of a live chord.
		final double wouldAdvance = clamp(padded[1] - SOFTNESS, 0.0, MAX_RANGE - SOFTNESS);
		if (padded[0] <= 0.0) {
			throw new AssertionError("skip_only_from_inside: the camera is inside the padded skip box - the fixture no longer poses the hazard");
		}
		if (span.tStart != 0.0) {
			throw new AssertionError("skip_only_from_inside: tStart moved to " + span.tStart
				+ " with the camera outside the skip box");
		}
		if (got.evals != ref.evals || got.cov != ref.cov || got.tEnter != ref.tEnter) {
			throw new AssertionError("skip_only_from_inside: the ray changed (" + ref.evals + "/" + ref.cov
				+ " -> " + got.evals + "/" + got.cov + ")");
		}
		if (wouldAdvance <= 0.0) {
			throw new AssertionError("skip_only_from_inside: the fixture no longer poses the hazard - an unconditional advance would skip nothing");
		}
		System.out.println("  skip_only_from_inside: camera outside the skip box, tStart stays 0.0 (an unconditional advance would have jumped to "
			+ fmt(wouldAdvance) + "); cov " + got.cov + " over " + got.evals + " evaluations");
	}

	private static double[] unit(final double x, final double y, final double z) {
		final double len = Math.sqrt(x * x + y * y + z * z);
		return new double[] {x / len, y / len, z / len};
	}

	/** A ray that grazes the slab's (4, 4, -4) corner at exactly g, reaching that closest approach at t = 20. */
	private static final double[] GRAZE_DIR = unit(-1.0, 1.0, 0.0);

	/** The camera that launches {@link #GRAZE_DIR} so the ray's closest approach to the slab is exactly g. */
	private static double[] grazingRay(final double g) {
		final double c = g / Math.sqrt(2.0);
		return new double[] {4.0 + c + 10.0 * Math.sqrt(2.0), 4.0 + c - 10.0 * Math.sqrt(2.0), -4.0};
	}

	private static double clamp(final double v, final double lo, final double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static String fmt(final double v) {
		return String.format(java.util.Locale.ROOT, "%.4f", v);
	}
}
'@
[System.IO.File]::WriteAllText($broadSrc, $broadJava, [System.Text.UTF8Encoding]::new($false))

Write-Host "Aura slab interval check (CPU simulation)"
Push-Location $repoRoot
try {
	& $javac "@$slabCpFile" -d $slabDir $slabSrc $broadSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$slabCpFile" SlabIntervalCheck
	if ($LASTEXITCODE -ne 0) { throw "SlabIntervalCheck failed" }
	Write-Host "Aura broad phase check (CPU simulation)"
	& $java "@$slabCpFile" AuraBroadPhaseCheck
	if ($LASTEXITCODE -ne 0) { throw "AuraBroadPhaseCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Aura slab interval check OK."
Write-Host "Aura broad phase check OK."
exit 0
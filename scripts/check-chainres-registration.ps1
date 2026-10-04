# Dev-only guard for the chain-resolution policy on the post program registry
# (chain-resolution, Tasks 1 and 3).
#
# The contract: ProgramInfo carries the resolution policy as three components after the fusion pair
# (scalable/pixelParams/taps; the screen_image flag was appended after them); a convenience
# constructor defaults them to false/Set.of()/0, so existing registrations stay non-scalable; and a
# scalable program may never be fusible (spec 3) - a violation is forced to a barrier, so the two
# mechanisms cannot disagree.
#
# Task 3 adds: the scalable set is exactly the effect types that pass a scalable true (asserted by the
# overload's `, true, Set.of(` argument shape, so widening the set means editing this list in the same
# commit); the pixel-parameter sets (spec 5); and each declared tap count against the shader's real loop
# width - a changed SAMPLES/ring must fail here, not silently shift the run threshold (spec 4).
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-chainres-registration.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$shadersDir = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post"

$problems = New-Object System.Collections.Generic.List[string]
$programs = [System.IO.File]::ReadAllText($programsPath)
$blur = [System.IO.File]::ReadAllText((Join-Path $shadersDir "blur.fsh"))
$bloom = [System.IO.File]::ReadAllText((Join-Path $shadersDir "bloom.fsh"))
$dof = [System.IO.File]::ReadAllText((Join-Path $shadersDir "depth_of_field.fsh"))

# the record carries the three new components, in order, after the fusion pair (the screen_image flag
# was appended after them)
if ($programs -notmatch 'VFXFusionClass fusionClass, int prefixEvals, boolean scalable, Set<String> pixelParams, int taps, boolean screenImage\)\s*\{') {
	$problems.Add("ProgramInfo does not carry (scalable, pixelParams, taps) after the fusion pair")
}
# a convenience constructor defaults them, so existing registrations stay non-scalable
if ($programs -notmatch 'this\([^;]*VFXFusionClass\.BARRIER, 1, false, Set\.of\(\), 0, false\);') {
	$problems.Add("no convenience constructor defaults the resolution policy")
}
# a scalable program may never be fusible (spec 3)
if ($programs -notmatch 'scalable && .*fusionClass != VFXFusionClass\.BARRIER') {
	$problems.Add("the scalable/fusible invariant is not enforced")
}

# exactly these effect types are scalable (spec 3): blur_x/blur_y, bloom, depth_of_field, plus - after
# the InSize audit (spec 5) - vhs and digital_glitch, whose 4 taps each clear the 8-tap threshold only
# beside each other. Every scalable registration passes a scalable true before its pixel set, so the
# set is read from that argument shape rather than by counting booleans; widening it means editing this
# list in the same commit, deliberately.
$scalable = @('BLUR', 'BLOOM', 'DEPTH_OF_FIELD', 'VHS', 'DIGITAL_GLITCH')
$actual = @(($programs -split "\r?\n") | Where-Object { $_ -match ', true, Set\.of\(' } |
	ForEach-Object { [regex]::Match($_, 'VFXEffectType\.([A-Z_]+)').Groups[1].Value } | Sort-Object)
$expected = @($scalable | Sort-Object)
if (($actual -join ',') -ne ($expected -join ',')) {
	$problems.Add("scalable set is [$($actual -join ', ')], expected [$($expected -join ', ')]")
}

# declared taps must match the shader's real loop width - a changed SAMPLES/ring must fail here,
# not silently shift the run threshold (spec 4)
if ($blur -notmatch 'const int SAMPLES = 12;') { $problems.Add("blur.fsh SAMPLES is not 12, so the declared 25 taps are stale") }
if ($blur -notmatch 'for \(int i = -SAMPLES; i <= SAMPLES; i\+\+\)') { $problems.Add("blur.fsh loop is not -SAMPLES..SAMPLES, so the declared 25 taps are stale") }
if ($programs -notmatch 'Set\.of\("radius"\), 25\)') { $problems.Add("blur_x/blur_y do not declare 25 taps") }
if ($bloom -notmatch 'for \(int i = 0; i < 8; i\+\+\)') { $problems.Add("bloom.fsh ring is not 8 lobes, so the declared 9 taps are stale") }
if ($programs -notmatch 'Set\.of\("radius"\), 9\)') { $problems.Add("bloom does not declare 9 taps") }
if ($dof -notmatch 'for \(int i = 0; i < 8; i\+\+\)') { $problems.Add("depth_of_field.fsh loop is not 8 iterations, so the declared 16 taps are stale") }
if ($programs -notmatch 'Set\.of\("intensity"\), 16\)') { $problems.Add("depth_of_field does not declare 16 taps") }
# vhs and digital_glitch each fetch four times and carry no pixel-unit parameter
if ($programs -notmatch 'VFXEffectType\.VHS,[^\r\n]*true, Set\.of\(\), 4\)') { $problems.Add("vhs does not declare 4 taps as a scalable program") }
if ($programs -notmatch 'VFXEffectType\.DIGITAL_GLITCH,[^\r\n]*true, Set\.of\(\), 4\)') { $problems.Add("digital_glitch does not declare 4 taps as a scalable program") }

# pixel-unit parameters (spec 5): blur and bloom radius, depth_of_field intensity
foreach ($need in @('Set.of("radius")', 'Set.of("intensity")')) { if ($programs -notmatch [regex]::Escape($need)) { $problems.Add("missing pixel parameter set $need") } }

Write-Host "Chain-resolution registration check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "chain-resolution registration check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  the resolution policy follows the fusion pair in the record, a convenience constructor defaults it, and a scalable program is forced to a barrier"
Write-Host "  the scalable set is exactly blur/bloom/depth_of_field/vhs/digital_glitch, with the shader-backed taps and pixel parameters"
Write-Host "Chain-resolution registration check OK."
exit 0

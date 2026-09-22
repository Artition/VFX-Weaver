# Dev-only guard for the two-pass blur kernel (2026-09-22).
#
# Defect fixed: blur_x/blur_y derived the tap count from the radius
# (`int samples = int(clamp(r * 0.5 + 2.0, 1.0, MAX_SAMPLES))`), so when an animated radius
# crossed a threshold the loop added/removed a symmetric sample pair in one frame and the
# normalised result stepped - a sharp jump in strength while the animation itself was smooth.
# The kernel must use a FIXED tap count whose weights are constants; only the sample spacing may
# scale with the radius. This check also asserts the weights are normalised by their own sum.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-blur-kernel.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the kernel is fixed-tap.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$shaderDir = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post"

$problems = New-Object System.Collections.Generic.List[string]
$tapCounts = @{}

foreach ($name in @("blur_x", "blur_y")) {
	$path = Join-Path $shaderDir "$name.fsh"
	$text = [System.IO.File]::ReadAllText($path)
	$code = [regex]::Replace($text, "//[^\r\n]*", "")

	# 1. A fixed tap count declared as a compile-time constant.
	$tap = [regex]::Match($code, 'const\s+int\s+TAPS\s*=\s*(\d+)\s*;')
	if (-not $tap.Success) {
		$problems.Add("$name.fsh has no fixed tap count 'const int TAPS = N;'")
		continue
	}
	$tapCounts[$name] = [int]$tap.Groups[1].Value

	# 2. The loop bound must be that constant, never an expression over the radius.
	$loop = [regex]::Match($code, 'for\s*\(\s*int\s+(\w+)\s*=\s*1\s*;\s*\1\s*<=\s*TAPS\s*;\s*\1\+\+\s*\)')
	if (-not $loop.Success) {
		$problems.Add("$name.fsh loop bound is not the fixed constant (expected 'for (int i = 1; i <= TAPS; i++)')")
	}

	# 3. No early break / dynamic sample count: the number of fetched taps is constant.
	if ($code -match '\bbreak\s*;') {
		$problems.Add("$name.fsh has a dynamic 'break' in the tap loop (tap count can change per frame)")
	}
	if ($code -match 'int\s+\w+\s*=\s*int\s*\(\s*clamp\s*\([^)]*radius') {
		$problems.Add("$name.fsh derives an int sample count from 'radius' again")
	}
	if ($code -match '\bMAX_SAMPLES\b') {
		$problems.Add("$name.fsh still uses the old adaptive MAX_SAMPLES pattern")
	}

	# 4. The weight must be a constant function of the tap index only (no radius in the weight).
	$weight = [regex]::Match($code, 'float\s+w\s*=\s*([^;]+);')
	if (-not $weight.Success) {
		$problems.Add("$name.fsh has no 'float w = ...;' weight")
	} elseif ($weight.Groups[1].Value -match '\br\b|radius') {
		$problems.Add("$name.fsh weight depends on the radius ('$($weight.Groups[1].Value.Trim())')")
	}

	# 5. The weights must be normalised by their own accumulated sum (symmetric two-sided taps).
	if ($code -notmatch 'total\s*\+=\s*2\.0\s*\*\s*w\s*;') {
		$problems.Add("$name.fsh does not accumulate both symmetric taps into 'total'")
	}
	if ($code -notmatch 'fragColor\s*=\s*color\s*/\s*total\s*;') {
		$problems.Add("$name.fsh does not normalise by the weight sum (expected 'fragColor = color / total;')")
	}
}

# 6. Both passes must use the same fixed tap count.
if ($tapCounts.ContainsKey("blur_x") -and $tapCounts.ContainsKey("blur_y") -and $tapCounts["blur_x"] -ne $tapCounts["blur_y"]) {
	$problems.Add("blur_x/blur_y tap counts differ ($($tapCounts['blur_x']) vs $($tapCounts['blur_y']))")
}

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "blur kernel check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Blur kernel OK: fixed tap count $($tapCounts['blur_x']) per side, constant normalised weights, radius scales spacing only."
exit 0

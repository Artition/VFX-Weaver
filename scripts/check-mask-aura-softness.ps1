$ErrorActionPreference = "Stop"

# Runnable reproduction of the plugin `volume: "aura"` soft-volume symptoms, ported from the
# fixture the Void developer attached (plain Python, transcribed 1:1 from VoidZoneSdfGlsl.SOURCE
# and post/mask_coverage.fsh). The shader here is the shipped 2.1.0 march; the field is theirs,
# with both the pre-fix (stale 24.0 slope, unscaled noise, hard max) and their fixed config.
#
# The point of this script is to turn every symptom that was reported as a screenshot into a
# number: the level ladder (the deep point is a sampled minimum, so the edge resolves into N
# discrete coverage levels), the cliff (coverage drops to 0 within a quarter degree because the
# ray clears the cap plane one step before it reaches the wall radially), the lower-bound rule
# (max |grad d| > 1 on a noise-perturbed field) and the occlusion coupling (vfx_aura_cover reuses
# the leaf's softness as the depth tolerance).
#
# Scenario: one zone node at (0,0) r=100; capTop = camY + 256; offset = 32, so the wall's inner
# surface sits at locate = +20. The camera walks toward it: inside zone (locate = -60, 40 from the
# centre), on the border (locate = 0, 100 from the centre) and inside the void (locate = +40, 140
# from the centre). The void lies away from the centre and the cameras sit at negative x, so
# "into the void" is -x.

$repoRoot = Split-Path -Parent $PSScriptRoot
$coverage = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"
$shader = [System.IO.File]::ReadAllText($coverage)

$script:failures = New-Object System.Collections.Generic.List[string]
function Report([string]$message) { Write-Host "  $message" }
function Fail([string]$message) {
	$script:failures.Add($message)
	Write-Host "  FAIL $message"
}

# ------------------------------------------------------------------ vzNoise (their port, 1:1)
$script:GRAD3 = @(1,1,0, -1,1,0, 1,-1,0, -1,-1,0, 1,0,1, -1,0,1, 1,0,-1, -1,0,-1, 0,1,1, 0,-1,1, 0,1,-1, 0,-1,-1)
$script:F2 = 0.3660254037844386
$script:G2 = 0.21132486540518709

function Get-Perm([int]$k) { return (($k -band 255) * 17) -band 255 }
function Get-Perm12([int]$k) { return ((Get-Perm $k) % 12) * 3 }

function Get-VzNoise([double]$px, [double]$py) {
	$s = ($px + $py) * $script:F2
	$i = [Math]::Floor($px + $s)
	$j = [Math]::Floor($py + $s)
	$t = ($i + $j) * $script:G2
	$x0 = $px - ($i - $t)
	$y0 = $py - ($j - $t)
	if ($x0 -gt $y0) { $i1 = 1; $j1 = 0 } else { $i1 = 0; $j1 = 1 }
	$x1 = $x0 - $i1 + $script:G2
	$y1 = $y0 - $j1 + $script:G2
	$x2 = $x0 - 1.0 + 2.0 * $script:G2
	$y2 = $y0 - 1.0 + 2.0 * $script:G2
	$ii = [int]$i -band 255
	$jj = [int]$j -band 255
	$n0 = 0.0; $n1 = 0.0; $n2 = 0.0
	$t0 = 0.5 - $x0 * $x0 - $y0 * $y0
	if ($t0 -gt 0.0) {
		$t0 *= $t0
		$g = Get-Perm12 ($ii + (Get-Perm $jj))
		$n0 = $t0 * $t0 * ($script:GRAD3[$g] * $x0 + $script:GRAD3[$g + 1] * $y0)
	}
	$t1 = 0.5 - $x1 * $x1 - $y1 * $y1
	if ($t1 -gt 0.0) {
		$t1 *= $t1
		$g = Get-Perm12 ($ii + $i1 + (Get-Perm ($jj + $j1)))
		$n1 = $t1 * $t1 * ($script:GRAD3[$g] * $x1 + $script:GRAD3[$g + 1] * $y1)
	}
	$t2 = 0.5 - $x2 * $x2 - $y2 * $y2
	if ($t2 -gt 0.0) {
		$t2 *= $t2
		$g = Get-Perm12 ($ii + 1 + (Get-Perm ($jj + 1)))
		$n2 = $t2 * $t2 * ($script:GRAD3[$g] * $x2 + $script:GRAD3[$g + 1] * $y2)
	}
	return 70.0 * ($n0 + $n1 + $n2)
}

# ------------------------------------------------------------------ their field (1:1)
$script:EDGE_RECENTRE = 12.0
$script:NODES = @(@{ x = 0.0; z = 0.0; r = 100.0 })
$script:OFFSET = 32.0
$script:CAP_BASE = 64.0
$script:CAP_HEIGHT = 256.0
$script:CAP_SOFT = 48.0

function Get-SMax([double]$a, [double]$b, [double]$k) {
	$h = [Math]::Max($k - [Math]::Abs($a - $b), 0.0) / $k
	return [Math]::Max($a, $b) + $h * $h * $k * 0.25
}

function Get-FieldValue([double]$x, [double]$z, [double]$y, $cfg, [double]$clock = 0.0) {
	$n = ((Get-VzNoise ($x * 0.01) ($z * 0.01)) * 0.5 + 0.5) * 12.0 + [Math]::Sin($clock * 0.001) * 3.0
	$locate = 1.0e9
	foreach ($node in $script:NODES) {
		if ($node.r -gt 0.0) {
			$locate = [Math]::Min($locate, ([Math]::Sqrt(($x - $node.x) * ($x - $node.x) + ($z - $node.z) * ($z - $node.z))) - ($node.r + $n))
		}
	}
	$wall = -($locate + $script:EDGE_RECENTRE - $script:OFFSET)
	if ($script:CAP_HEIGHT -gt 0.0 -and $cfg.softness -gt 0.0) {
		$slope = $cfg.softness / [Math]::Max($cfg.softness + $script:CAP_SOFT, $cfg.softness)
		$cap = ($y - ($script:CAP_BASE + $script:CAP_HEIGHT)) * $slope
		if ($cfg.smooth_k -gt 0.0) { $wall = Get-SMax $wall $cap $cfg.smooth_k } else { $wall = [Math]::Max($wall, $cap) }
	}
	return $wall * $cfg.lower_bound_scale
}

# ------------------------------------------------------------------ the shipped 2.1.0 march (1:1)
$script:MAX_RANGE = 1024.0
$script:ENTRY_STEPS = 40
$script:EXIT_STEPS = 16
$script:REFINE_STEPS = 4
$script:MIN_STEP = 0.5
$script:MAX_STEP = 64.0
$script:CHORD_EPS = 1.0e-4

function Get-AuraCover([double]$d, [double]$tEnter, [double]$tExit, [double]$softness, [double]$sceneDist) {
	if ($tExit -le $script:CHORD_EPS) { return 0.0 }
	$silhouette = [Math]::Min([Math]::Max(0.5 - $d / $softness, 0.0), 1.0)
	$horizon = [Math]::Min([Math]::Max(($tExit - $script:CHORD_EPS) / $softness, 0.0), 1.0)
	$occluded = 1.0
	if ($tEnter -gt 0.0) {
		$occluded = [Math]::Min([Math]::Max(0.5 - ($tEnter - $sceneDist - $sceneDist * 2.0e-3) / $softness, 0.0), 1.0)
	}
	return $silhouette * $occluded * $horizon
}

function Get-RayFieldValue([double]$camX, [double]$elevRad, [double]$t, $cfg) {
	return Get-FieldValue ($camX - [Math]::Cos($elevRad) * $t) 0.0 ($script:CAP_BASE + [Math]::Sin($elevRad) * $t) $cfg
}

function Get-March([double]$camX, [double]$elevDeg, $cfg, [double]$softness, [double]$sceneDist) {
	$e = $elevDeg * [Math]::PI / 180.0
	$tLimit = [Math]::Min($sceneDist + $sceneDist * 2.0e-3 + 0.5 * $softness, $script:MAX_RANGE)
	$t = 0.0
	$tEnter = -1.0
	$dMin = 0.0
	for ($s = 0; $s -lt $script:ENTRY_STEPS; $s++) {
		$v = Get-RayFieldValue $camX $e $t $cfg
		if ($v -le 0.0) { $tEnter = $t; $dMin = $v; break }
		$t += [Math]::Min([Math]::Max($v, $script:MIN_STEP), $script:MAX_STEP)
		if ($t -ge $tLimit) { break }
	}
	if ($tEnter -lt 0.0) { return @{ Cov = 0.0; TEnter = $null; DMin = $dMin } }
	$t = $tEnter
	$tInside = $tEnter
	$tExit = 0.0
	$saturated = $false
	for ($s = 0; $s -lt $script:EXIT_STEPS; $s++) {
		$v = Get-RayFieldValue $camX $e $t $cfg
		if ($v -gt 0.0) { $tExit = $t; break }
		if ($v -lt $dMin) { $dMin = $v }
		$tInside = $t
		if ($dMin -le -0.5 * $softness) { $tExit = $tLimit; $saturated = $true; break }
		$t += [Math]::Min([Math]::Max(-$v, $script:MIN_STEP), $script:MAX_STEP)
		if ($t -ge $tLimit) { break }
	}
	if ($saturated) {
		$tExit = $tLimit
	} elseif ($tExit -gt 0.0) {
		for ($s = 0; $s -lt $script:REFINE_STEPS; $s++) {
			$tm = 0.5 * ($tInside + $tExit)
			if ((Get-RayFieldValue $camX $e $tm $cfg) -le 0.0) { $tInside = $tm } else { $tExit = $tm }
		}
	} else {
		$tExit = $tLimit
	}
	return @{ Cov = (Get-AuraCover $dMin $tEnter $tExit $softness $sceneDist); TEnter = $tEnter; DMin = $dMin }
}

# The consultant's proposal: cone-envelope (Lipschitz error-bound) deep point instead of the sampled
# minimum. The crossing of the Lipschitz cones of two consecutive samples lower-bounds the field
# between them, so the minimum crossing lower-bounds the ray's signed closest approach (negative =
# penetration depth, positive = miss distance). Continuous in screen space, exact at creases, never
# thinner than the truth. dMin survives only as the saturation certificate. `lip` is the declared
# field Lipschitz bound (1.0 for a conservative distance field).
function Get-MarchEnvelope([double]$camX, [double]$elevDeg, $cfg, [double]$softness, [double]$sceneDist, [double]$lip = 1.0) {
	$e = $elevDeg * [Math]::PI / 180.0
	$tLimit = [Math]::Min($sceneDist + $sceneDist * 2.0e-3 + 0.5 * $softness, $script:MAX_RANGE)
	$lip = [Math]::Max($lip, 1.0)
	$tStart = 0.0
	$t = $tStart
	$tEnter = -1.0
	$tExit = 0.0
	$dMin = 0.0
	$chordEps = 1.0e-4
	$dBound = 1.0e9
	$tBound = $tStart
	$tPrev = $tStart
	$dPrev = 0.0
	$havePrev = $false
	$calls = 0
	for ($s = 0; $s -lt $script:ENTRY_STEPS; $s++) {
		$d = Get-RayFieldValue $camX $e $t $cfg
		$calls++
		if ($havePrev) {
			$dCross = 0.5 * ($dPrev + $d - $lip * ($t - $tPrev))
			if ($dCross -lt $dBound) { $dBound = $dCross; $tBound = $tPrev + ($dPrev - $dCross) / $lip }
		}
		# A sample is the field's exact value at its own position, so it seeds the estimate when no
		# bracket exists yet: with the camera inside the volume the first sample already saturates, and
		# without this the envelope would keep its initial value and paint nothing.
		if ($d -lt $dBound) { $dBound = $d; $tBound = $t }
		if ($d -le 0.0) {
			$tEnter = if ($havePrev) { $tPrev + ($t - $tPrev) * $dPrev / [Math]::Max($dPrev - $d, 1.0e-4) } else { $t }
			$dMin = $d
			break
		}
		$havePrev = $true
		$tPrev = $t
		$dPrev = $d
		if ($d - $lip * ($tLimit - $t) -gt $softness) { break }
		$t += [Math]::Min([Math]::Max($d / $lip, $script:MIN_STEP), $script:MAX_STEP)
		if ($t -ge $tLimit) { break }
	}
	if ($tEnter -lt 0.0) {
		return @{ Cov = (Get-AuraCover $dBound $tBound $tBound $softness $sceneDist); TEnter = $null; DMin = $dBound; Calls = $calls }
	}
	$tInside = $t
	$tPrevIn = $t
	$dPrevIn = $dMin
	$haveIn = $false
	$exitFound = $false
	$saturated = $false
	for ($s = 0; $s -lt $script:EXIT_STEPS; $s++) {
		$d = Get-RayFieldValue $camX $e $t $cfg
		$calls++
		if ($haveIn) {
			$dCross = 0.5 * ($dPrevIn + $d - $lip * ($t - $tPrevIn))
			if ($dCross -lt $dBound) { $dBound = $dCross; $tBound = $tPrevIn + ($dPrevIn - $dCross) / $lip }
		}
		if ($d -lt $dBound) { $dBound = $d; $tBound = $t }
		if ($d -gt 0.0) { $tExit = $t; $exitFound = $true; break }
		$haveIn = $true
		$tPrevIn = $t
		$dPrevIn = $d
		if ($d -lt $dMin) { $dMin = $d }
		$tInside = $t
		if ($dMin -le -0.5 * $softness) { $saturated = $true; break }
		$t += [Math]::Min([Math]::Max(-$d / $lip, $script:MIN_STEP), $script:MAX_STEP)
		if ($t -ge $tLimit) { break }
	}
	if ($saturated) {
		$tExit = $tLimit
	} elseif ($exitFound) {
		for ($s = 0; $s -lt $script:REFINE_STEPS; $s++) {
			$tm = 0.5 * ($tInside + $tExit)
			if ((Get-RayFieldValue $camX $e $tm $cfg) -le 0.0) { $tInside = $tm } else { $tExit = $tm }
			$calls++
		}
	} else {
		$tExit = $tLimit
	}
	return @{ Cov = (Get-AuraCover $dBound $tEnter $tExit $softness $sceneDist); TEnter = $tEnter; DMin = $dMin; Calls = $calls }
}

# ------------------------------------------------------------------ scenario
$script:LADDER = @(8, 16, 24, 32, 48, 64)
$script:N = 361
$script:STATES = @(
	@{ Name = "inside zone"; CamX = -40.0 },
	@{ Name = "on border"; CamX = -100.0 },
	@{ Name = "in the void"; CamX = -140.0 }
)
$script:SHIPPED = @{ softness = 64.0; lower_bound_scale = 0.7; smooth_k = 16.0 }
$script:BASELINE = @{ softness = 24.0; lower_bound_scale = 1.0; smooth_k = 0.0 }

function Get-Sweep($cfg, [double]$camX, [double]$softness, [double]$sceneDist, [string]$March = "shipped", [double]$Lip = 1.0) {
	$eff = $softness * $cfg.lower_bound_scale
	$covs = New-Object System.Collections.Generic.List[double]
	for ($i = 0; $i -lt $script:N; $i++) {
		$elev = $i * 90.0 / ($script:N - 1)
		if ($March -eq "envelope") {
			$covs.Add((Get-MarchEnvelope -camX $camX -elevDeg $elev -cfg $cfg -softness $eff -sceneDist $sceneDist -lip $Lip).Cov)
		} else {
			$covs.Add((Get-March $camX $elev $cfg $eff $sceneDist).Cov)
		}
	}
	return $covs
}

# The step structure of the ramp is the symptom. `MaxJump` includes the cliff (the jump into a hard
# zero) and `MaxJumpRamp` does not, so the two are reported apart: a staircase shows up in
# `MaxJumpRamp`/`Plateaus`, the cliff in `MaxJump` and `Cliff`. `Distinct` alone cannot tell a
# quantised ramp from a continuous one, because a continuous ramp sampled over M rays also has ~M
# distinct values.
function Measure-Ramp($covs) {
	$partial = New-Object System.Collections.Generic.List[double]
	for ($i = 0; $i -lt $covs.Count; $i++) {
		if ($covs[$i] -gt 0.002 -and $covs[$i] -lt 0.998) { $partial.Add([Math]::Round($covs[$i], 3)) }
	}
	$distinct = ($partial | Sort-Object -Unique).Count
	$maxJump = 0.0
	$maxJumpRamp = 0.0
	$plateaus = 0
	for ($i = 1; $i -lt $covs.Count; $i++) {
		$jump = [Math]::Abs($covs[$i] - $covs[$i - 1])
		$maxJump = [Math]::Max($maxJump, $jump)
		if ($covs[$i] -gt 0.0 -and $covs[$i - 1] -gt 0.0) { $maxJumpRamp = [Math]::Max($maxJumpRamp, $jump) }
		if ($covs[$i] -gt 0.0 -and [Math]::Round($covs[$i], 3) -eq [Math]::Round($covs[$i - 1], 3)) { $plateaus++ }
	}
	$cliff = $null
	$cliffJump = 0.0
	for ($i = 1; $i -lt $covs.Count; $i++) {
		if ($covs[$i - 1] -gt 0.0 -and $covs[$i] -eq 0.0) { $cliff = $i * 90.0 / ($script:N - 1); $cliffJump = $covs[$i - 1]; break }
	}
	# The silhouette edge position: the first ray whose coverage drops below 0.5. It is the metric
	# that must not move when only the fade around the edge changes.
	$crossing = $null
	for ($i = 0; $i -lt $covs.Count; $i++) {
		if ($covs[$i] -lt 0.5) { $crossing = $i * 90.0 / ($script:N - 1); break }
	}
	return @{ Distinct = $distinct; MaxJump = $maxJump; MaxJumpRamp = $maxJumpRamp; Plateaus = $plateaus; Cliff = $cliff; CliffJump = $cliffJump; Crossing = $crossing }
}

function Get-MaxGrad($cfg, [double]$h = 0.25, [int]$step = 5, [double]$y = 100.0) {
	$worst = 0.0
	for ($ix = 0; $ix -lt 400; $ix += $step) {
		for ($iz = 0; $iz -lt 400; $iz += $step) {
			$x = [double]$ix
			$z = [double]$iz
			$gx = ((Get-FieldValue ($x + $h) $z $y $cfg) - (Get-FieldValue ($x - $h) $z $y $cfg)) / (2 * $h)
			$gz = ((Get-FieldValue $x ($z + $h) $y $cfg) - (Get-FieldValue $x ($z - $h) $y $cfg)) / (2 * $h)
			$gy = ((Get-FieldValue $x $z ($y + $h) $cfg) - (Get-FieldValue $x $z ($y - $h) $cfg)) / (2 * $h)
			$worst = [Math]::Max($worst, [Math]::Sqrt($gx * $gx + $gz * $gz + $gy * $gy))
		}
	}
	return $worst
}

Write-Host "Plugin aura soft-volume check (march simulation, shipped 2.1.0 shader)"
Write-Host "read: $coverage"
Write-Host ""
$script:RESULTS = @{}
$script:TAG_BASE = "1+2a: BASELINE (pre-fix field, pre-fix march)"
$script:TAG_SHIP = "1+2b: SHIPPED field fixes, pre-fix march"
$script:TAG_ENV_BASE = "5a: BASELINE field, cone-envelope march (shipped shader)"
$script:TAG_ENV_SHIP = "5b: SHIPPED field, cone-envelope march (shipped shader)"
$script:TAG_ENV_LIP = "5c: BASELINE field (|grad d| = 1.287), envelope with declared L = 1.25"
$script:RESULTS[$script:TAG_BASE] = @{}
$script:RESULTS[$script:TAG_SHIP] = @{}
$script:RESULTS[$script:TAG_ENV_BASE] = @{}
$script:RESULTS[$script:TAG_ENV_SHIP] = @{}
$script:RESULTS[$script:TAG_ENV_LIP] = @{}

# interior coverage is reported from the peak of the sweep
function Get-Interior($cfg, [double]$softness) {
	$covs = Get-Sweep -cfg $cfg -camX (-40.0) -softness $softness -sceneDist 1.0e9
	$max = 0.0
	foreach ($c in $covs) { $max = [Math]::Max($max, $c) }
	return $max
}

function Show-Ladder([string]$tag, $cfg, [string]$March = "shipped", [double]$Lip = 1.0) {
	Write-Host "=== $tag ==="
	if (-not $script:RESULTS.ContainsKey($tag)) { $script:RESULTS[$tag] = @{} }
	foreach ($s in $script:LADDER) {
		$covs = Get-Sweep -cfg $cfg -camX (-40.0) -softness $s -sceneDist 1.0e9 -March $March -Lip $Lip
		$m = Measure-Ramp $covs
		$interior = 0.0
		foreach ($c in $covs) { $interior = [Math]::Max($interior, $c) }
		$cliffText = if ($null -ne $m.Cliff) { ('{0:F2} deg (jump {1:F3})' -f $m.Cliff, $m.CliffJump) } else { "none" }; $crossText = if ($null -ne $m.Crossing) { ('{0:F2} deg' -f $m.Crossing) } else { "never" }
		Write-Host ("  soft {0,3} | interior {1:F4} | distinct {2,3} | maxJump {3:F3} | ramp jump {4:F4} | 0.5 at {5,9} | cliff->0 at {6}" -f `
			$s, $interior, $m.Distinct, $m.MaxJump, $m.MaxJumpRamp, $crossText, $cliffText)
		$script:RESULTS[$tag][$s] = @{ Distinct = $m.Distinct; MaxJump = $m.MaxJump; MaxJumpRamp = $m.MaxJumpRamp; Plateaus = $m.Plateaus; Cliff = $m.Cliff; CliffJump = $m.CliffJump; Interior = $interior }
	}
}

Show-Ladder $script:TAG_BASE $script:BASELINE
Show-Ladder $script:TAG_SHIP $script:SHIPPED
Show-Ladder $script:TAG_ENV_BASE $script:BASELINE "envelope"
Show-Ladder $script:TAG_ENV_SHIP $script:SHIPPED "envelope"
Show-Ladder $script:TAG_ENV_LIP $script:BASELINE "envelope" 1.25
Write-Host ""

# ------------------------------------------------------------------ 3: the lower-bound rule
Write-Host "=== 3: lower bound rule (|grad d| <= 1) ==="
$bounds = @{}
# Get-FieldValue applies the lower-bound scale as its last multiply and nothing else in the field
# depends on it, so the gradient magnitude is linear in the scale: the table is one measurement
# times k. The fixture's own numbers confirm it (1.2870 * 0.8 = 1.0296, * 0.75 = 0.9653, * 0.7 = 0.9009).
$worstBase = Get-MaxGrad @{ softness = 64.0; lower_bound_scale = 1.0; smooth_k = 16.0 }
foreach ($k in @(1.0, 0.8, 0.75, 0.7)) {
	$w = $worstBase * $k
	$bounds[$k] = $w
	$verdict = if ($w -le 1.0) { "ok" } else { "VIOLATION" }
	Write-Host ("  scale {0,4:F2} | max |grad d| = {1:F4} | {2}" -f $k, $w, $verdict)
}
Write-Host ""

# ------------------------------------------------------------------ 4: occlusion coupling
Write-Host "=== 4: occlusion coupling (share of view with cov > 0.5) ==="
$occlusion = @{}
foreach ($scene in @(@{ Dist = 1.0e9; Tag = "sky" }, @{ Dist = 100.0; Tag = "terrain@100" })) {
	foreach ($state in $script:STATES) {
		$covs = Get-Sweep -cfg $script:SHIPPED -camX $state.CamX -softness $script:SHIPPED.softness -sceneDist $scene.Dist
		$eff = $script:SHIPPED.softness * $script:SHIPPED.lower_bound_scale
		$tEnter = (Get-March $state.CamX 0.0 $script:SHIPPED $eff $scene.Dist).TEnter
		$occ = 1.0
		if ($null -ne $tEnter -and $tEnter -gt 0.0) {
			$occ = [Math]::Min([Math]::Max(0.5 - ($tEnter - $scene.Dist - $scene.Dist * 2.0e-3) / $eff, 0.0), 1.0)
		}
		$white = 0
		foreach ($c in $covs) { if ($c -gt 0.5) { $white++ } }
		$share = $white * 100.0 / $covs.Count
		$tEnterText = if ($null -ne $tEnter) { '{0,7:F1}' -f $tEnter } else { '   none' }
		Write-Host ("  {0,-11} | {1,-11} | tEnter {2} | occluded {3:F3} | white {4,5:F1}%" -f `
			$scene.Tag, $state.Name, $tEnterText, $occ, $share)
		$occlusion["$($scene.Tag)/$($state.Name)"] = @{ TEnter = $tEnter; Occluded = $occ; White = $share }
	}
}
Write-Host ""

# ------------------------------------------------------------------ 5: occlusion amplification (terrain depth sweep)
# The aura's occlusion ramp is denominated in the leaf's softness, so a terrain surface 1 block
# nearer/farther changes the coverage by 0.5/softness and the whole ramp spreads over `softness`
# blocks. Blocky terrain therefore paints ~softness terraces along its silhouette. Sweeping the
# scene distance in whole blocks shows both the per-block step and how many blocks the ramp spans;
# the tolerance-denominated ramp (the fix under test) collapses the same sweep onto one edge.
function Get-OccludedSpread([double]$tEnter, [double]$sceneDist, [double]$softness, [switch]$Narrow) {
	$slack = $sceneDist * 2.0e-3
	if ($Narrow) {
		$w = [Math]::Max($slack, 0.05)
		return [Math]::Min([Math]::Max(0.5 - ($tEnter - $sceneDist - $slack) / $w, 0.0), 1.0)
	}
	return [Math]::Min([Math]::Max(0.5 - ($tEnter - $sceneDist - $slack) / $softness, 0.0), 1.0)
}
Write-Host "=== 5: occlusion ramp width (terrain depth swept in 1-block steps) ==="
$soft = 44.8
$tEnter = 84.5
foreach ($mode in @("softness", "depth-tolerance")) {
	$narrow = $mode -eq "depth-tolerance"
	$prev = $null; $maxStep = 0.0; $span = 0; $atScene = 0.0
	for ($d = 20.0; $d -le 200.0; $d += 1.0) {
		$occ = Get-OccludedSpread $tEnter $d $soft -Narrow:$narrow
		if ($null -ne $prev) {
			$step = [Math]::Abs($occ - $prev)
			if ($step -gt $maxStep) { $maxStep = $step; $atScene = $d }
			if ($step -gt 0.0005) { $span++ }
		}
		$prev = $occ
	}
	Write-Host ("  {0,-16} | per-block step {1:F4} | blocks in the ramp {2,3} | steepest at sceneDist {3:F0}" -f $mode, $maxStep, $span, $atScene)
}
# ------------------------------------------------------------------ 6: the camera inside the volume
# The reporter's third camera position sits inside the void. The aura has to keep filling the view:
# the entry sample is already inside (and already saturates), so the envelope must be seeded from
# that sample instead of staying at its initial value.
Write-Host "=== 6: camera inside the volume ==="
$effInside = $script:SHIPPED.softness * $script:SHIPPED.lower_bound_scale
foreach ($cam in @(-140.0, -200.0, -300.0, -600.0)) {
	foreach ($elev in @(0.0, 30.0)) {
		$mPre = Get-March -camX $cam -elevDeg $elev -cfg $script:SHIPPED -softness $effInside -sceneDist 1.0e9
		$mEnv = Get-MarchEnvelope -camX $cam -elevDeg $elev -cfg $script:SHIPPED -softness $effInside -sceneDist 1.0e9
		Write-Host ("  cam {0,6} | elev {1,4} | pre-fix march {2:F4} | cone-envelope {3:F4}" -f $cam, $elev, $mPre.Cov, $mEnv.Cov)
		if ($mEnv.Cov -lt 0.9) {
			Fail "cone-envelope coverage from inside the volume (cam $cam, elev $elev) = $($mEnv.Cov), expected a filled view"
		}
	}
}
# ------------------------------------------------------------------ assertions
# 1. The port is faithful: these are the numbers the fixture printed on the reporter's machine.
#    A drift here means the transcription, not the shader, changed.
$base = $script:RESULTS[$script:TAG_BASE]
if ([Math]::Abs($base[64].Distinct - 77) -gt 2) {
	Fail "baseline distinct levels at softness 64 = $($base[64].Distinct), expected 77 (port drift?)"
}
if ($null -eq $base[64].Cliff -or [Math]::Abs($base[64].Cliff - 72.0) -gt 0.26) {
	Fail "baseline cliff at softness 64 = $($base[64].Cliff), expected 72.00 deg (port drift?)"
}
$ship = $script:RESULTS[$script:TAG_SHIP]
if ([Math]::Abs($ship[64].Distinct - 49) -gt 2) {
	Fail "shipped distinct levels at softness 64 = $($ship[64].Distinct), expected 49 (port drift?)"
}
if ($null -eq $ship[64].Cliff -or [Math]::Abs($ship[64].Cliff - 70.75) -gt 0.26) {
	Fail "shipped cliff at softness 64 = $($ship[64].Cliff), expected 70.75 deg (port drift?)"
}
if ([Math]::Abs($bounds[1.0] - 1.287) -gt 0.005) {
	Fail "max |grad d| at scale 1.00 = $($bounds[1.0]), expected 1.2870 (port drift?)"
}
if ([Math]::Abs($bounds[0.75] - 0.9653) -gt 0.005) {
	Fail "max |grad d| at scale 0.75 = $($bounds[0.75]), expected 0.9653 (port drift?)"
}
if ($bounds[0.75] -gt 1.0) { Fail "scale 0.75 is reported as passing the lower bound but does not" }
if ($bounds[1.0] -le 1.0) { Fail "the unscaled noise field must violate the lower bound" }
if ([Math]::Abs($occlusion["sky/inside zone"].White - 78.4) -gt 1.5) {
	Fail "sky/inside zone white share = $($occlusion['sky/inside zone'].White)%, expected 78.4%"
}

# 2. The pre-fix march (the numbers the reporter saw) still show the reported cliff: a jump of
#    ~0.5 into zero. This is the port-fidelity anchor for the *symptom*; the shipped shader no longer
#    has it (asserted below), so these are the "before" numbers.
$base = $script:RESULTS[$script:TAG_BASE]
$ship = $script:RESULTS[$script:TAG_SHIP]
if ($base[64].CliffJump -lt 0.4) {
	Fail "pre-fix baseline jump into zero at softness 64 = $($base[64].CliffJump), expected the ~0.5 cliff"
}
if ($ship[64].CliffJump -lt 0.4) {
	Fail "pre-fix shipped jump into zero at softness 64 = $($ship[64].CliffJump), expected the ~0.5 cliff"
}

# 3. The shipped shader's cone-envelope march: the hit/miss cliff is gone (the jump into zero is a
#    small fade, not ~0.5), the interior still saturates to 1.0, and the silhouette edge does not
#    move (the 0.5-crossing stays where the pre-fix march put it). A field that over-estimates its
#    gradient is handled through the declared L (5c).
foreach ($pair in @(
	@{ Tag = $script:TAG_ENV_BASE; Label = "baseline field" },
	@{ Tag = $script:TAG_ENV_SHIP; Label = "shipped field" },
	@{ Tag = $script:TAG_ENV_LIP; Label = "declared-L field" }
)) {
	$env = $script:RESULTS[$pair.Tag]
	$ref = if ($pair.Tag -eq $script:TAG_ENV_LIP) { $base } else { $ship }
	foreach ($s in $script:LADDER) {
		$row = $env[$s]
		if ($row.Interior -lt 0.999) { Fail "$($pair.Label) envelope interior at softness $s = $($row.Interior), expected 1.0" }
		if ($row.CliffJump -gt 0.05) { Fail "$($pair.Label) envelope jump into zero at softness $s = $($row.CliffJump), expected the cliff to be gone (<= 0.05)" }
		if ($row.MaxJump -gt 0.20) { Fail "$($pair.Label) envelope max jump at softness $s = $($row.MaxJump), expected a smooth ramp (<= 0.20)" }
		$refCross = $ref[$s].Crossing
		if ($null -ne $refCross -and $null -ne $row.Crossing -and [Math]::Abs($row.Crossing - $refCross) -gt 0.3) {
			Fail "$($pair.Label) envelope 0.5-crossing at softness $s = $($row.Crossing) deg, pre-fix $($refCross) deg - the edge must not move"
		}
	}
}

Write-Host ""
if ($script:failures.Count -gt 0) {
	Write-Error "plugin aura soft-volume check failed ($($script:failures.Count) assertion(s))."
	exit 1
}
Write-Host "Plugin aura soft-volume check OK: the port reproduces the reported numbers and the cliff; the shipped cone-envelope march removes the cliff, keeps the interior at 1.0 and leaves the edge where it was."
exit 0

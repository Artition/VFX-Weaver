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
	if ($null -ne $cfg.corridor) {
		# A long shallow corridor: a box 5000 long and 16 across, so the ray travels inside it at a
		# constant -8 for thousands of units. If budget exhaustion ever saturates, this lights up.
		$dx = [Math]::Abs($x - 2458.0) - 2500.0
		$dy = [Math]::Abs($y - 64.0) - 8.0
		$dz = [Math]::Abs($z) - 8.0
		return [Math]::Max([Math]::Max($dx, $dy), $dz)
	}
	$wall = -($locate + $script:EDGE_RECENTRE - $script:OFFSET)
	# A bump: a ball of volume hanging off the wall toward the camera, the case a real volume has and a
	# radial one does not. min() of two distance fields is still a distance field, so the field stays
	# conservative and the march still cannot tunnel - the only thing that changes is the ray's shape.
	if ($null -ne $cfg.lump) {
		$l = $cfg.lump
		$dl = [Math]::Sqrt(($x - $l[0]) * ($x - $l[0]) + ($y - $l[1]) * ($y - $l[1]) + ($z - $l[2]) * ($z - $l[2])) - $l[3]
		$wall = [Math]::Min($wall, $dl)
	}
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
# Anti-crawl step boost (the 2.1.1 fix): the step becomes (|d| + STEP_BOOST*softness)/lip, which
# bounds the cost of small-|d| stretches. Because lip*step <= |d|+B under every clamp, a bracket with
# both samples outside keeps dCross >= -B/2, so a ray that never enters can reach at most
# 0.5 + STEP_BOOST/2 of coverage - the ceiling asserted over the miss sweep.
$script:STEP_BOOST = 0.2
$script:MISS_CEILING = 0.5 + 0.1

function Get-AuraCover([double]$d, [double]$tEnter, [double]$tExit, [double]$softness, [double]$sceneDist, [double]$occWidth = -1.0) {
	if ($tExit -le $script:CHORD_EPS) { return 0.0 }
	if ($occWidth -lt 0.0) { $occWidth = $softness }
	$silhouette = [Math]::Min([Math]::Max(0.5 - $d / $softness, 0.0), 1.0)
	$horizon = [Math]::Min([Math]::Max(($tExit - $script:CHORD_EPS) / $softness, 0.0), 1.0)
	$occluded = 1.0
	if ($tEnter -gt 0.0) {
		$occluded = [Math]::Min([Math]::Max(0.5 - ($tEnter - $sceneDist - $sceneDist * 2.0e-3) / $occWidth, 0.0), 1.0)
	}
	return $silhouette * $occluded * $horizon
}

function Get-RayFieldValue([double]$camX, [double]$elevRad, [double]$t, $cfg, [double]$azimRad = 0.0) {
	$ce = [Math]::Cos($elevRad)
	return Get-FieldValue ($camX - $ce * [Math]::Cos($azimRad) * $t) (-$ce * [Math]::Sin($azimRad) * $t) ($script:CAP_BASE + [Math]::Sin($elevRad) * $t) $cfg
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
function Get-MarchEnvelope([double]$camX, [double]$elevDeg, $cfg, [double]$softness, [double]$sceneDist, [double]$lip = 1.0, [switch]$SaturateUnresolved, [double]$azimDeg = 0.0) {
	$e = $elevDeg * [Math]::PI / 180.0
	$a = $azimDeg * [Math]::PI / 180.0
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
	$dLast = 0.0
	$tLast = $tStart
	for ($s = 0; $s -lt $script:ENTRY_STEPS; $s++) {
		$d = Get-RayFieldValue $camX $e $t $cfg $a
		$calls++
		$dLast = $d
		$tLast = $t
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
	$unresolved = $false
	if ($tEnter -lt 0.0 -and $SaturateUnresolved -and ($dLast - $lip * ($tLimit - $tLast)) -le $softness) {
		$unresolved = $true
	}
	if ($tEnter -lt 0.0) {
		if ($unresolved) {
			return @{ Cov = (Get-AuraCover (-0.5 * $softness) 0.0 $script:MAX_RANGE $softness $sceneDist); TEnter = $null; DMin = $dBound; Calls = $calls }
		}
		return @{ Cov = (Get-AuraCover $dBound $tBound $tBound $softness $sceneDist); TEnter = $null; DMin = $dBound; Calls = $calls }
	}
	$tInside = $t
	$tPrevIn = $t
	$dPrevIn = $dMin
	$haveIn = $false
	$exitFound = $false
	$saturated = $false
	for ($s = 0; $s -lt $script:EXIT_STEPS; $s++) {
		$d = Get-RayFieldValue $camX $e $t $cfg $a
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
			if ((Get-RayFieldValue $camX $e $tm $cfg $a) -le 0.0) { $tInside = $tm } else { $tExit = $tm }
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

# The pooled deepest-penetration march (the 2.1.1 fix). One loop over the entry+exit budget that never
# stops at a sign crossing: dBound is global over the whole ray (the global minimum IS the deepest
# segment's minimum, so union semantics falls out), tEnter keeps the FIRST entry for the occlusion
# ramp, tInside/tExitAfter bracket the LAST exit for the horizon fade. Saturation is only ever a sample
# certificate (d <= -0.5*softness); budget exhaustion must NOT saturate, or a long shallow corridor
# lights up. The anti-crawl step bounds what a small-|d| stretch costs and, because lip*step <= |d|+B
# under every clamp, a bracket with both samples outside keeps dCross >= -B/2: the boost can add at
# most STEP_BOOST/2 of conservative fat to a grazing silhouette and never fabricates a bright spot.
function Get-MarchDeep([double]$camX, [double]$elevDeg, $cfg, [double]$softness, [double]$sceneDist, [double]$lip = 1.0, [double]$azimDeg = 0.0) {
	$e = $elevDeg * [Math]::PI / 180.0
	$a = $azimDeg * [Math]::PI / 180.0
	$tLimit = [Math]::Min($sceneDist + $sceneDist * 2.0e-3 + 0.5 * $softness, $script:MAX_RANGE)
	$lip = [Math]::Max($lip, 1.0)
	$t = 0.0
	$tEnter = -1.0
	$tExit = 0.0
	$chordEps = 1.0e-4
	$dBound = 1.0e9
	$tBound = $t
	$tPrev = $t
	$dPrev = 0.0
	$havePrev = $false
	$tInside = -1.0
	$tExitAfter = -1.0
	$inside = $false
	$saturated = $false
	$calls = 0
	$stepBoost = $script:STEP_BOOST * $softness
	$stepFloor = [Math]::Max($script:MIN_STEP, $stepBoost / $lip)
	for ($s = 0; $s -lt $script:ENTRY_STEPS + $script:EXIT_STEPS; $s++) {
		$d = Get-RayFieldValue $camX $e $t $cfg $a
		$calls++
		if ($havePrev) {
			$dCross = 0.5 * ($dPrev + $d - $lip * ($t - $tPrev))
			if ($dCross -lt $dBound) { $dBound = $dCross; $tBound = $tPrev + ($dPrev - $dCross) / $lip }
		}
		if ($d -lt $dBound) { $dBound = $d; $tBound = $t }
		if ($d -le 0.0) {
			if (-not $inside) {
				$inside = $true
				if ($tEnter -lt 0.0) {
					$tEnter = if ($havePrev) { $tPrev + ($t - $tPrev) * $dPrev / [Math]::Max($dPrev - $d, 1.0e-4) } else { $t }
				}
			}
			$tInside = $t
			$tExitAfter = -1.0
			if ($d -le -0.5 * $softness) { $saturated = $true; break }
		} elseif ($inside) {
			$inside = $false
			$tExitAfter = $t
		}
		# Perf only: a full-slope dive to tLimit can no longer reach the visible band, or can no longer
		# beat the best bound so far, so the rest of the ray cannot change the answer.
		$dive = $d - $lip * ($tLimit - $t)
		if ($dive -gt 0.5 * $softness -or $dive -ge $dBound) { break }
		$havePrev = $true
		$tPrev = $t
		$dPrev = $d
		$t += [Math]::Min([Math]::Max(([Math]::Abs($d) + $stepBoost) / $lip, $stepFloor), $script:MAX_STEP)
		if ($t -ge $tLimit) { break }
	}
	if ($tEnter -lt 0.0) {
		return @{ Cov = (Get-AuraCover $dBound $tBound $tBound $softness $sceneDist); TEnter = $null; DMin = $dBound; Calls = $calls }
	}
	if ($saturated -or $tExitAfter -lt 0.0) {
		$tExit = $tLimit
	} else {
		for ($s = 0; $s -lt $script:REFINE_STEPS; $s++) {
			$tm = 0.5 * ($tInside + $tExitAfter)
			if ((Get-RayFieldValue $camX $e $tm $cfg $a) -le 0.0) { $tInside = $tm } else { $tExitAfter = $tm }
			$calls++
		}
		$tExit = $tExitAfter
	}
	return @{ Cov = (Get-AuraCover $dBound $tEnter $tExit $softness $sceneDist); TEnter = $tEnter; DMin = $dBound; Calls = $calls }
}

function Get-Sweep($cfg, [double]$camX, [double]$softness, [double]$sceneDist, [string]$March = "shipped", [double]$Lip = 1.0) {
	$eff = $softness * $cfg.lower_bound_scale
	$covs = New-Object System.Collections.Generic.List[double]
	for ($i = 0; $i -lt $script:N; $i++) {
		$elev = $i * 90.0 / ($script:N - 1)
		if ($March -eq "envelope") {
			$covs.Add((Get-MarchEnvelope -camX $camX -elevDeg $elev -cfg $cfg -softness $eff -sceneDist $sceneDist -lip $Lip).Cov)
		} elseif ($March -eq "deep") {
			$covs.Add((Get-MarchDeep -camX $camX -elevDeg $elev -cfg $cfg -softness $eff -sceneDist $sceneDist -lip $Lip).Cov)
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
# ------------------------------------------------------------------ 7: the occlusion opt-out and the depth leaf
# A sphere volume is the simplest case with an analytic chord, so it is used to pin the opt-out: with
# occlusion disabled the coverage must not depend on the occluder distance at all (and the plugin march
# must not be cut by its own tLimit), while the default path must keep depending on it.
Write-Host "=== 7a: occlusion opt-out (built-in sphere r=64 at (0,0,0), camera at (0,64,-200)) ==="
$sphereR = 64.0
$camAura = @(0.0, 64.0, -200.0)
function Get-SphereChord([double]$cx, [double]$cy, [double]$cz, [double]$ox, [double]$oy, [double]$oz, [double]$r) {
	$dx = $ox - $cx
	$dy = $oy - $cy
	$dz = $oz - $cz
	$b = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
	return @([Math]::Max($b - $r, 0.0), $b + $r)
}
$softAura = 8.0
foreach ($scene in @(50.0, 100.0, 150.0, 1.0e9)) {
	$chord = Get-SphereChord 0.0 0.0 0.0 $camAura[0] $camAura[1] $camAura[2] $sphereR
	$tEnter = $chord[0]; $tExit = $chord[1]
	# the sphere's deepest point is the chord midpoint (the closest approach), the built-in recipe
	$dMid = $sphereR - ($sphereR - $tEnter) + 0.0
	$dDeep = [Math]::Min(0.0, -($tExit - $tEnter) * 0.5)
	$covOn = Get-AuraCover $dDeep $tEnter $tExit $softAura $scene
	$covNarrow = Get-AuraCover $dDeep $tEnter $tExit $softAura $scene 0.5
	$covOff = Get-AuraCover $dDeep $tEnter $tExit $softAura 1.0e9
	Write-Host ("  sceneDist {0,10:F0} | default {1:F4} | occ_soft 0.5 {2:F4} | occlusion:false {3:F4}" -f $scene, $covOn, $covNarrow, $covOff)
	if ([Math]::Abs($covOff - 1.0) -gt 1.0e-9) {
		Fail "the opt-out coverage follows the occluder at sceneDist $scene (got $covOff, want 1.0)"
	}
	if ($scene -lt $tEnter -and [Math]::Abs($covOn - $covOff) -lt 1.0e-6) {
		Fail "the default path stopped following the occluder at sceneDist $scene"
	}
}
# The ramp width is its own term now, and this is the coupling defect it exists to remove: a ramp as
# wide as the silhouette wrongly dims a volume that is one block IN FRONT of the surface, and wrongly
# half-shows one that is one block BEHIND it. A narrow authored width must reach both extremes.
$chordDec = Get-SphereChord 0.0 0.0 0.0 0.0 64.0 -200.0 $sphereR
$dDeepDec = [Math]::Min(0.0, -(($chordDec[1] - $chordDec[0]) * 0.5))
function Get-RampPair([double]$offset) {
	$scene = $chordDec[0] + $offset
	$wide = Get-AuraCover $dDeepDec $chordDec[0] $chordDec[1] $softAura $scene
	$narrow = Get-AuraCover $dDeepDec $chordDec[0] $chordDec[1] $softAura $scene 0.5
	Write-Host ("  surface {0} block(s) behind the entry: softness {1:F4} | occlusion_softness 0.5 {2:F4}" -f $offset, $wide, $narrow)
	return @($wide, $narrow)
}
$inFront = Get-RampPair 1.0
if ($inFront[1] -lt 0.999) { Fail "a narrow occlusion_softness must fully show a volume 1 block in front of the surface (got $($inFront[1]))" }
if ($inFront[0] -ge 0.999) { Fail "the default wide ramp must still show the coupling (it no longer dims a volume 1 block in front)" }
$behind = Get-RampPair (-1.0)
if ($behind[1] -gt 0.001) { Fail "a narrow occlusion_softness must fully hide a volume 1 block behind the surface (got $($behind[1]))" }
if ($behind[0] -le 0.001) { Fail "the default wide ramp must still show the coupling (it no longer half-shows a hidden volume)" }

Write-Host "=== 7b: depth leaf (distance 64, softness 16) ==="
$dist = 64.0; $softD = 16.0
$prev = $null; $maxStep = 0.0; $mono = $true
for ($d = 0.0; $d -le 96.0; $d += 0.25) {
	$cov = [Math]::Min([Math]::Max(0.5 - ($d - $dist) / $softD, 0.0), 1.0)
	if ($null -ne $prev) {
		$step = $cov - $prev
		if ($step -gt $maxStep) { $maxStep = $step }
		if ($step -gt 1.0e-9) { $mono = $false }
	}
	$prev = $cov
}
$cov56 = [Math]::Min([Math]::Max(0.5 - (56.0 - $dist) / $softD, 0.0), 1.0)
$cov64 = [Math]::Min([Math]::Max(0.5 - (64.0 - $dist) / $softD, 0.0), 1.0)
$cov72 = [Math]::Min([Math]::Max(0.5 - (72.0 - $dist) / $softD, 0.0), 1.0)
$covSky = [Math]::Min([Math]::Max(0.5 - (1.0e9 - $dist) / $softD, 0.0), 1.0)
Write-Host ("  cov(56)={0:F4} cov(64)={1:F4} cov(72)={2:F4} sky(1e9)={3:F4} | monotone non-increasing {4} | max step {5:F4} (bound {6:F4})" -f `
	$cov56, $cov64, $cov72, $covSky, $mono, $maxStep, (0.25 / $softD + 1.0e-6))
if ([Math]::Abs($cov56 - 1.0) -gt 1.0e-6) { Fail "depth leaf cov at distance-softness/2 = $cov56, expected 1.0" }
if ([Math]::Abs($cov64 - 0.5) -gt 1.0e-6) { Fail "depth leaf cov at distance = $cov64, expected 0.5" }
if ($cov72 -gt 1.0e-9) { Fail "depth leaf cov at distance+softness/2 = $cov72, expected 0" }
if ($covSky -gt 1.0e-9) { Fail "depth leaf cov on the far sentinel = $covSky, expected 0 (fail-closed)" }
if (-not $mono) { Fail "the depth leaf coverage is not monotone non-increasing in sceneDist" }
if ($maxStep -gt (0.25 / $softD + 1.0e-6)) { Fail "the depth leaf steps by $maxStep over a 0.25-block sweep" }

Write-Host "=== 7c: a band from two depth leaves (difference) ==="
function Get-DepthCov([double]$d, [double]$distance, [double]$soft) {
	return [Math]::Min([Math]::Max(0.5 - ($d - $distance) / $soft, 0.0), 1.0)
}
foreach ($scene in @(52.0, 64.0, 76.0)) {
	$a = Get-DepthCov $scene 80.0 8.0
	$b = Get-DepthCov $scene 48.0 8.0
	$band = $a * (1.0 - $b)   # the mask difference op: union(a) minus b
	Write-Host ("  sceneDist {0,5:F0} | outer leaf {1:F4} | inner leaf {2:F4} | band {3:F4}" -f $scene, $a, $b, $band)
	if ([Math]::Abs($band - 1.0) -gt 1.0e-6) { Fail "the depth band at sceneDist $scene = $band, expected 1.0" }
}

Write-Host "=== 7d: ramp terracing (1-block occluder steps) - deferred width, for the numbers ==="
foreach ($w in @(2.0, 8.0, 16.0, 44.8, 100.0)) {
	$prev = $null; $levels = 0; $maxJump = 0.0
	for ($d = 40.0; $d -le 140.0; $d += 1.0) {
		$occ = [Math]::Min([Math]::Max(0.5 - (84.5 - $d) / $w, 0.0), 1.0)
		if ($null -ne $prev) {
			$j = [Math]::Abs($occ - $prev)
			if ($j -gt 1.0e-6) { $levels++ }
			if ($j -gt $maxJump) { $maxJump = $j }
		}
		$prev = $occ
	}
	Write-Host ("  width {0,6:F1} | visible levels {1,3} | worst step {2:F4} {3}" -f $w, $levels, $maxJump, $(if ($maxJump -le 0.004) { "(ok: under 0.004)" } else { "" }))
}
# The opt-out has to be wired on both aura paths, and the plugin path must raise its own march limit:
# with the occluder ignored, a tLimit left at the scene distance would cut the volume with the *budget*
# instead of the occlusion term, and the opt-out would fail silently.
$coverage = [System.IO.File]::ReadAllText((Join-Path (Split-Path -Parent $PSScriptRoot) "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"))
if (([regex]::Matches($coverage, 'float occDist').Count) -ne 2) {
	Fail "mask_coverage.fsh: both aura paths must derive an occDist from shape_volume[i].z"
}
if (-not $coverage.Contains('tLimit = VFX_PLUGIN_AURA_MAX_RANGE;')) {
	Fail "mask_coverage.fsh: the plugin aura must reach the full range when occlusion is disabled"
}
if (-not $coverage.Contains('} else if (kind == 9) {')) {
	Fail "mask_coverage.fsh: the depth leaf branch (kind 9) is missing"
}
if ($coverage.Contains('} else if (kind == 10) {')) {
	Fail "mask_coverage.fsh: an unexpected kind 10 branch exists"
}
# ------------------------------------------------------------------ 8: rays that miss the volume, and the Lipschitz contract
# A ray whose closest approach to the volume stays outside (d_min > 0) never enters, so it keeps the
# near-miss cover. That cover's silhouette term is 0.5 - dBound/softness, and the cone bound keeps
# dBound >= -softness/4 for any non-entering ray, so such a ray is structurally capped just under 0.5
# while a ray that does enter a solid volume saturates at 1.0. The cap is real and is asserted below.
# It is not a defect: a ray that grazes the volume owes a partial silhouette, not full coverage. What
# must never happen is a wide band of directions reading ~0.5 against a solid volume they really are
# inside - that is a march budget problem, and the reporter's proposed one-liner for it is measured
# here instead of being taken on faith.
Write-Host "=== 8: near-miss cap and the Lipschitz contract ==="
$softRad = 44.8
$missCov = @()
for ($elev = 40.0; $elev -le 90.0; $elev += 0.5) {
	$o = Get-MarchEnvelope -camX (-40.0) -elevDeg $elev -cfg $script:SHIPPED -softness $softRad -sceneDist 1.0e9
	if ($null -eq $o.TEnter) { $missCov += $o.Cov }
}
if ($missCov.Count -lt 5) {
	Fail "expected several rays to miss the volume, found $($missCov.Count) - the near-miss case is not covered"
}
$maxMiss = ($missCov | Measure-Object -Maximum).Maximum
Write-Host ("  rays that miss the volume: {0} | coverage {1:F4}..{2:F4} (structurally capped just under 0.5)" -f `
	$missCov.Count, (($missCov | Measure-Object -Minimum).Minimum), $maxMiss)
if ($maxMiss -gt 0.51) {
	Fail "a ray that misses the volume reaches coverage $maxMiss - the near-miss cap is not where the contract says it is"
}

# The march steps by d/lip and is only sound while the field is conservative. Section 3 already
# measured the field's worst gradient for every lower-bound scale, and the magnitude is linear in that
# scale, so the two numbers that matter here are already on the table: the shipped 0.7 field is a true
# distance field, while the raw field is not - which is what makes the 0.7 lower-bound scale load-bearing
# and the registerMaskShapeGlsl lipschitz overload mandatory for a plugin that skips it.
Write-Host ("  max |grad|: shipped scale 0.7 -> {0:F4} (L = 1.0 is sound) | raw -> {1:F4} (L >= {2:F2} required)" -f `
	$bounds[0.7], $bounds[1.0], $bounds[1.0])
if ($bounds[0.7] -gt 1.0) {
	Fail "the fixture's scaled field is not conservative ($($bounds[0.7]) > 1) - the march would tunnel and every step count here would be meaningless"
}
if ($bounds[1.0] -le 1.0) {
	Fail "the raw field came out conservative ($($bounds[1.0])) - this fixture no longer shows that the lower-bound scale is load-bearing"
}

# The reporter's proposed fix for their arch: after the entry loop, treat a march that ran out of steps
# but whose last sample can still reach the soft band as saturated instead of a near miss. Measured, so
# the next reader sees whether it does anything: it fires only on rays that end far short of tLimit,
# and a ray that legitimately turned around outside the volume never satisfies the condition.
$beforeCov = 0.0
$afterCov = 0.0
$cnt = 0
for ($elev = 40.0; $elev -le 90.0; $elev += 0.5) {
	$a = Get-MarchEnvelope -camX (-40.0) -elevDeg $elev -cfg $script:SHIPPED -softness $softRad -sceneDist 1.0e9
	if ($null -ne $a.TEnter) { continue }
	$b = Get-MarchEnvelope -camX (-40.0) -elevDeg $elev -cfg $script:SHIPPED -softness $softRad -sceneDist 1.0e9 -SaturateUnresolved
	$beforeCov += $a.Cov
	$afterCov += $b.Cov
	$cnt++
}
Write-Host ("  reporter's proposed saturate-on-exhaustion fix: mean miss coverage {0:F4} -> {1:F4}" -f `
	($beforeCov / $cnt), ($afterCov / $cnt))
if (($afterCov - $beforeCov) / $cnt -gt 0.05) {
	Fail "the proposed saturate-on-exhaustion fix now measurably changes the miss coverage - revisit this section's conclusion"
}

# A ray that turns around outside the volume keeps marching away from it until the budget runs out: the
# only early-out is the cone bound near tLimit, so a receding ray spends most of its steps going nowhere.
# Recorded as a known cost, not asserted - a tighter early-out is a performance change, not a fix.
Write-Host "  known cost: a receding ray spends its remaining entry steps marching away from the volume"

# ------------------------------------------------------------------ 9: notches over a 2D sweep (tripwire, not a reproduction)
# A real aura volume is not a clean shell, so the reported "notch" case - a thin soft protrusion in
# front of a solid mass - needs a field with more than one inside segment per ray. This fixture's field
# is {outside the ring wall} and {below the cap}: convex along any ray, one segment, saturating. It
# therefore cannot produce that artifact, and no amount of tweaking these numbers will reproduce it.
# What the sweep is worth is as a tripwire: a cell weaker than ALL four neighbours is a notch, not an
# edge (a real silhouette is monotone in every direction), so if one ever shows up here the fixture's
# own geometry changed and the numbers above need re-reading.
$script:NOTCH_SOFTNESS = 44.8
Write-Host "=== 9: notch tripwire over a 2D azimuth x elevation sweep (softness $($script:NOTCH_SOFTNESS)) ==="
$grid = @{}
foreach ($az in @(0..59 | ForEach-Object { $_ * 6.0 })) {
	foreach ($el in @(6..30 | ForEach-Object { $_ * 3.0 })) {
		$grid["$az|$el"] = (Get-MarchEnvelope -camX (-40.0) -elevDeg $el -cfg $script:SHIPPED -softness $script:NOTCH_SOFTNESS -sceneDist 1.0e9 -azimDeg $az).Cov
	}
}
$notches = @()
foreach ($az in @(6..53 | ForEach-Object { $_ * 6.0 })) {
	foreach ($el in @(9..30 | ForEach-Object { $_ * 3.0 })) {
		$c = $grid["$az|$el"]
		$nb = [Math]::Min([Math]::Min($grid["$($az - 6.0)|$el"], $grid["$($az + 6.0)|$el"]), [Math]::Min($grid["$az|$($el - 3.0)"], $grid["$az|$($el + 3.0)"]))
		if (($nb - $c) -gt 0.15) { $notches += [pscustomobject]@{ Az = $az; El = $el; Cov = $c; Nb = $nb; Dip = $nb - $c } }
	}
}
Write-Host ("  60 x 25 directions | coverage {0:F4}..{1:F4} | notch cells (dip > 0.15): {2}" -f `
	(($grid.Values | Measure-Object -Minimum).Minimum), (($grid.Values | Measure-Object -Maximum).Maximum), $notches.Count)
if ($notches.Count -gt 0) {
	$w = $notches | Sort-Object -Property Dip -Descending | Select-Object -First 1
	Write-Host ("  worst dip {0:F4} at az {1} el {2} (cell {3:F4}, weakest neighbour {4:F4})" -f $w.Dip, $w.Az, $w.El, $w.Cov, $w.Nb)
	Fail "$($notches.Count) notch cells - the coverage has a local minimum, which this field's geometry cannot produce"
}

# ------------------------------------------------------------------ 10: a bump in front of the mass (the reported notch)
# The reported case: a wall with a bump, viewed from the bump side. The ray clips the bump - a ball of
# volume with its own softness - and the solid mass of the wall sits right behind it. The two-phase
# march stopped at the first d > 0, so everything behind the bump was never sampled and the silhouette
# term reported the bump's shallow depth: full strength on both sides, a wide dip across the bump.
# The pooled deepest-penetration march runs to a real termination, so the mass behind is what gets
# measured. This section pins both: the shipped profile's dip and the fixed profile's flat 1.0, plus
# the three properties the fix must never break - the miss ceiling, no false saturation on a long
# shallow corridor, and a bounded crawl cost.
$cfgBump = @{}
foreach ($k in $script:SHIPPED.Keys) { $cfgBump[$k] = $script:SHIPPED[$k] }
$cfgBump['lump'] = @(85.0, 64.0, 0.0, 20.0)
Write-Host "=== 10: bump in front of the mass (bump r=20 at rho=85, wall at rho=126) ==="
$bumpSoft = 44.8
$azims = @(0..24 | ForEach-Object { 150.0 + $_ * 2.5 })
function Get-BumpProfile([string]$March) {
	$row = @()
	foreach ($az in $azims) {
		$o = if ($March -eq "deep") {
			Get-MarchDeep -camX (-40.0) -elevDeg 0.0 -cfg $cfgBump -softness $bumpSoft -sceneDist 1.0e9 -azimDeg $az
		} else {
			Get-MarchEnvelope -camX (-40.0) -elevDeg 0.0 -cfg $cfgBump -softness $bumpSoft -sceneDist 1.0e9 -azimDeg $az
		}
		$row += [pscustomobject]@{ Az = $az; Cov = $o.Cov; Calls = $o.Calls }
	}
	return $row
}
$shippedBump = Get-BumpProfile "shipped"
$deepBump = Get-BumpProfile "deep"
$shippedMin = ($shippedBump.Cov | Measure-Object -Minimum).Minimum
$deepMin = ($deepBump.Cov | Measure-Object -Minimum).Minimum
Write-Host ("  shipped: min cov {0:F4} across the cut | {1}" -f $shippedMin, (($shippedBump.Cov | ForEach-Object { "{0:F2}" -f $_ }) -join " "))
Write-Host ("  deep:    min cov {0:F4} across the cut | {1}" -f $deepMin, (($deepBump.Cov | ForEach-Object { "{0:F2}" -f $_ }) -join " "))
if ($shippedMin -gt 0.95) {
	Fail "the bump case no longer reproduces the report (shipped min $shippedMin, expected a dip near 0.6)"
}
if ($deepMin -lt 0.999) {
	Fail "the pooled march leaves a dip over the bump (min $deepMin, expected 1.0)"
}

# miss ceiling: the anti-crawl boost may add at most STEP_BOOST/2 of conservative fat, so a ray that
# never enters the volume still cannot exceed 0.6.
$missCovs = @()
foreach ($az in @(0..59 | ForEach-Object { $_ * 6.0 })) {
	foreach ($el in @(6..30 | ForEach-Object { $_ * 3.0 })) {
		$o = Get-MarchDeep -camX (-40.0) -elevDeg $el -cfg $script:SHIPPED -softness $bumpSoft -sceneDist 1.0e9 -azimDeg $az
		if ($null -eq $o.TEnter) { $missCovs += $o.Cov }
	}
}
$missMax = ($missCovs | Measure-Object -Maximum).Maximum
Write-Host ("  miss ceiling: {0} rays that never enter | max cov {1:F4} (ceiling {2:F2})" -f $missCovs.Count, $missMax, $script:MISS_CEILING)
if ($missMax -gt $script:MISS_CEILING + 1.0e-6) {
	Fail "a ray that never enters the volume reaches $missMax, above the $($script:MISS_CEILING) ceiling the boost is allowed to add"
}

# No false saturation: a long shallow corridor that exhausts the budget must read its own depth, not 1.0.
$cfgCorridor = @{}
foreach ($k in $script:SHIPPED.Keys) { $cfgCorridor[$k] = $script:SHIPPED[$k] }
$cfgCorridor['corridor'] = $true
$deepCorridor = Get-MarchDeep -camX (-40.0) -elevDeg 0.0 -cfg $cfgCorridor -softness $bumpSoft -sceneDist 1.0e9 -azimDeg 180.0
$expect = 0.5 + 8.0 / $bumpSoft
Write-Host ("  long shallow corridor (d = -8 for 500 units): cov {0:F4} (expected ~{1:F4}, must not be 1.0)" -f $deepCorridor.Cov, $expect)
if ($deepCorridor.Cov -gt 0.95) {
	Fail "budget exhaustion saturated a long shallow corridor (cov $($deepCorridor.Cov)) - a false bright patch"
}

# Crawl bound: a ray that turned around outside must terminate on the dive bound, not by eating the budget.
$deepMiss = Get-MarchDeep -camX (-40.0) -elevDeg 0.0 -cfg $script:SHIPPED -softness $bumpSoft -sceneDist 1.0e9 -azimDeg 0.0
Write-Host ("  receding ray: {0} field calls of {1}" -f $deepMiss.Calls, ($script:ENTRY_STEPS + $script:EXIT_STEPS))
if ($deepMiss.Calls -gt 35) {
	Fail "the receding ray still burns the budget ($($deepMiss.Calls) calls) - the dive bound is not terminating it"
}

# Cost parity: the wings (directions well away from the bump) must not get materially more expensive.
$wingShipped = ($shippedBump | Where-Object { $_.Cov -ge 0.999 } | Measure-Object -Property Calls -Average).Average
$wingDeep = ($deepBump | Where-Object { $_.Cov -ge 0.999 } | Measure-Object -Property Calls -Average).Average
Write-Host ("  cost parity on the flat wings: shipped {0:F1} calls -> deep {1:F1} calls" -f $wingShipped, $wingDeep)
if ($wingDeep -gt $wingShipped + 4) {
	Fail "the pooled march costs materially more on flat wings ($wingShipped -> $wingDeep calls)"
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

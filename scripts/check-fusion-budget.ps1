# Dev-only guard for the post-chain fusion budget (Task 3).
#
# VFXFusionBudget is MC-free arithmetic, so it is checked the AGENTS.md way: static source contracts,
# then a throwaway main() compiled against a built node and run. This guard pins the four spec bounds
# and, above all, the boundaries - a budget that is off by one lets the planner emit a fused pass
# that evaluates the prefix 33 times, which is the bug this whole design exists to prevent.
#
# The pass-level model (how many stages and samplers a masked pointwise effect really costs, and the
# run cuts at 12/15/18 evaluations) is the planner's to assert, with the planner, in Task 4 - it
# cannot be pinned here without a planner to compute it.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-fusion-budget.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$budgetPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXFusionBudget.java"
$problems = New-Object System.Collections.Generic.List[string]

if (-not (Test-Path -LiteralPath $budgetPath)) {
	Write-Error "fusion budget check: VFXFusionBudget.java is missing."
	exit 1
}
$budget = [System.IO.File]::ReadAllText($budgetPath)

# --- static: the four spec bounds, and the product being separate from the sum ---------------------
foreach ($bound in @(
	@('MAX_STAGE_EVALS', 32),
	@('MAX_REMAP_PRODUCT', 3),
	@('MAX_SAMPLERS', 12),
	@('MAX_PARAMS', 384))) {
	if ($budget -notmatch ("public static final int $($bound[0]) = $($bound[1]);")) {
		$problems.Add("VFXFusionBudget: $($bound[0]) is not $($bound[1])")
	}
}
if ($budget -notmatch 'remapProduct \* next\.remaps <= MAX_REMAP_PRODUCT') {
	$problems.Add("VFXFusionBudget: the remap bound is not a product of the run's remaps and the stage's")
}
foreach ($sum in @('linearTotal \+ next\.linear <= MAX_STAGE_EVALS', 'samplersTotal \+ next\.samplers <= MAX_SAMPLERS', 'paramsTotal \+ next\.params <= MAX_PARAMS')) {
	if ($budget -notmatch $sum) { $problems.Add("VFXFusionBudget: missing bound check '$sum'") }
}
if ($budget -notmatch 'public int remaps = 1;') {
	$problems.Add("VFXFusionBudget.Cost: remaps does not default to 1 (a pointwise stage must not multiply)")
}
if ($budget -notmatch 'private VFXFusionBudget\(\)') {
	$problems.Add("VFXFusionBudget: no private constructor (stateless helper)")
}

Write-Host "Fusion budget check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "fusion budget check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  linear 32 / remap product 3 / samplers 12 / params 384; remaps defaults to 1"

# --- runnable: the boundaries (pure arithmetic, so no Minecraft on the classpath) ------------------
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) { Write-Error "fusion budget check: no JDK found (set JAVA_HOME); static contracts passed."; exit 1 }
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXFusionBudget.class"))) { Write-Error "fusion budget check: build :26.1.2 first (missing $mainClasses)."; exit 1 }
$checkDir = Join-Path $env:TEMP "vfxweaver-fusion-budget-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "FusionBudgetCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'))"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))
$checkJava = @'
import dev.vfxweaver.effect.VFXFusionBudget;

/** The fusion budget boundaries: every bound holds exactly at its limit and fails one past it. */
public final class FusionBudgetCheck {
	public static void main(String[] args) {
		// Defaults: a stage nobody described is one linear evaluation and one remap, costing nothing.
		VFXFusionBudget.Cost plain = new VFXFusionBudget.Cost();
		is(plain.linear, 0, "fresh cost linear");
		is(plain.remaps, 1, "fresh cost remaps");
		is(plain.samplers, 0, "fresh cost samplers");
		is(plain.params, 0, "fresh cost params");

		// Linear: exactly MAX_STAGE_EVALS fits, one more does not.
		is(VFXFusionBudget.MAX_STAGE_EVALS, 32, "MAX_STAGE_EVALS");
		is(VFXFusionBudget.MAX_REMAP_PRODUCT, 3, "MAX_REMAP_PRODUCT");
		is(VFXFusionBudget.MAX_SAMPLERS, 12, "MAX_SAMPLERS");
		is(VFXFusionBudget.MAX_PARAMS, 384, "MAX_PARAMS");
		yes(VFXFusionBudget.fits(30, 1, 0, 0, cost(2, 1, 0, 0)), "linear at the limit");
		no(VFXFusionBudget.fits(30, 1, 0, 0, cost(3, 1, 0, 0)), "linear one past the limit");
		no(VFXFusionBudget.fits(32, 1, 0, 0, cost(1, 1, 0, 0)), "linear on a full run");

		// Samplers: 11 + 1 fits, 11 + 2 does not.
		yes(VFXFusionBudget.fits(0, 1, 11, 0, cost(1, 1, 1, 0)), "samplers at the limit");
		no(VFXFusionBudget.fits(0, 1, 11, 0, cost(1, 1, 2, 0)), "samplers one past the limit");

		// Params: 383 + 1 fits, and a stage's own params count.
		yes(VFXFusionBudget.fits(0, 1, 0, 383, cost(1, 1, 0, 1)), "params at the limit");
		no(VFXFusionBudget.fits(0, 1, 0, 384, cost(1, 1, 0, 1)), "params on a full run");

		// Remap product: 3 x 1 fits, 2 x 2 does not, 1 x 3 fits exactly.
		yes(VFXFusionBudget.fits(0, 3, 0, 0, cost(1, 1, 0, 0)), "remap 3 x 1 at the limit");
		yes(VFXFusionBudget.fits(0, 1, 0, 0, cost(1, 3, 0, 0)), "remap 1 x 3 at the limit");
		no(VFXFusionBudget.fits(0, 2, 0, 0, cost(1, 2, 0, 0)), "remap 2 x 2 past the limit");
		no(VFXFusionBudget.fits(0, 1, 0, 0, cost(1, 4, 0, 0)), "remap 1 x 4 past the limit");

		// A rejected stage must not have been absorbed: the check is pure and reads, never writes.
		VFXFusionBudget.Cost rejected = cost(1, 1, 1, 1);
		no(VFXFusionBudget.fits(32, 3, 12, 384, rejected), "a full run rejects anything");
		is(rejected.linear, 1, "the rejected stage is untouched (linear)");
		is(rejected.remaps, 1, "the rejected stage is untouched (remaps)");
		is(rejected.samplers, 1, "the rejected stage is untouched (samplers)");
		is(rejected.params, 1, "the rejected stage is untouched (params)");

		// The one stage that would have blown the budget is the remap: a run near the linear limit
		// still takes a pointwise stage only while there is room for its evaluation.
		yes(VFXFusionBudget.fits(31, 1, 12, 384, cost(1, 1, 0, 0)), "a pointwise stage on an otherwise full run");
		no(VFXFusionBudget.fits(31, 3, 12, 384, cost(1, 2, 0, 0)), "a remap on an otherwise full run");

		System.out.println("fusion budget check OK: linear " + VFXFusionBudget.MAX_STAGE_EVALS
			+ ", remap product " + VFXFusionBudget.MAX_REMAP_PRODUCT
			+ ", samplers " + VFXFusionBudget.MAX_SAMPLERS
			+ ", params " + VFXFusionBudget.MAX_PARAMS + " - every bound holds at its limit only");
	}

	private static VFXFusionBudget.Cost cost(int linear, int remaps, int samplers, int params) {
		VFXFusionBudget.Cost c = new VFXFusionBudget.Cost();
		c.linear = linear;
		c.remaps = remaps;
		c.samplers = samplers;
		c.params = params;
		return c;
	}

	private static void yes(boolean got, String what) {
		if (!got) { throw new AssertionError(what + ": expected the stage to fit"); }
	}

	private static void no(boolean got, String what) {
		if (got) { throw new AssertionError(what + ": expected the stage to be rejected"); }
	}

	private static void is(int actual, int want, String what) {
		if (actual != want) { throw new AssertionError(what + " = " + actual + ", want " + want); }
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" FusionBudgetCheck
	if ($LASTEXITCODE -ne 0) { throw "FusionBudgetCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Fusion budget check OK."
exit 0

# Dev-only guard for the post-chain fusion budget (Task 3).
#
# VFXFusionBudget is MC-free arithmetic, so it is checked the AGENTS.md way: static source contracts,
# then a throwaway main() compiled against a built node and run. This guard pins the four spec bounds
# and, above all, the boundaries - a budget that is off by one lets the planner emit a fused pass
# that evaluates the prefix 33 times, which is the bug this whole design exists to prevent.
#
# The pass-level model (how many stages and samplers a masked pointwise effect really costs, and the
# run cuts at 12/15/18 evaluations) is pinned by the planner fixtures below, which compile a harness
# against the built client classes and run VFXFusionPlanner.runs/plan directly.
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

# --- chain resolution (Task 4): the switch, the threshold and the new step kinds ------------------
$resolutionPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXChainResolution.java"
$settingsPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\util\VFXSettings.java"
$resolutionProblems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $resolutionPath)) {
	Write-Error "chain resolution check: VFXChainResolution.java is missing."
	exit 1
}
if (-not (Test-Path -LiteralPath $settingsPath)) {
	Write-Error "chain resolution check: VFXSettings.java is missing."
	exit 1
}
$resolution = [System.IO.File]::ReadAllText($resolutionPath)
$settings = [System.IO.File]::ReadAllText($settingsPath)
if ($resolution -notmatch 'public static float scale\(\)') { $resolutionProblems.Add("VFXChainResolution has no scale() accessor") }
if ($resolution -notmatch 'VFXSettings\.get\(\)\.chainResolution\(\)') { $resolutionProblems.Add("scale() does not read VFXSettings.chainResolution()") }
if ($resolution -notmatch 'public static boolean half\(\)') { $resolutionProblems.Add("VFXChainResolution has no half() accessor") }
if ($resolution -notmatch 'return scale\(\) < 1\.0F;') { $resolutionProblems.Add("half() is not scale() < 1.0F") }
if ($resolution -notmatch 'MIN_RUN_TAPS = 8;') { $resolutionProblems.Add("MIN_RUN_TAPS is not 8") }
if ($resolution -match 'static final float SCALE' -or $resolution -match 'static final boolean HALF') { $resolutionProblems.Add("VFXChainResolution still exposes the SCALE/HALF constants") }
if ($settings -notmatch 'System\.getProperty\("vfxweaver\.chainres"\)') { $resolutionProblems.Add("VFXSettings does not read -Dvfxweaver.chainres") }
if ($settings -notmatch '"1\.0"\.equals\(raw\)') { $resolutionProblems.Add("VFXSettings does not accept exactly 1.0") }
if ($settings -notmatch '"0\.5"\.equals\(raw\)') { $resolutionProblems.Add("VFXSettings does not accept exactly 0.5") }
if ($settings -notmatch 'DEFAULT_CHAIN_RESOLUTION = 0\.5F') { $resolutionProblems.Add("the chain resolution default is not 0.5F") }
if ($settings -notmatch 'VFXLog\.warnOnce') { $resolutionProblems.Add("an unknown chainres value is not refused with warn-once") }
$plannerSource = [System.IO.File]::ReadAllText((Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusionPlanner.java"))
if ($plannerSource -notmatch 'permits Step\.Single, Step\.Fused, Step\.Down, Step\.Up') { $resolutionProblems.Add("Step does not permit Single/Fused/Down/Up") }
if ($plannerSource -notmatch 'record Single\(StageRef stage, float resScale\)') { $resolutionProblems.Add("Step.Single has no float resScale component") }
if ($plannerSource -notmatch 'record Down\(\) implements Step') { $resolutionProblems.Add("Step.Down is missing") }
if ($plannerSource -notmatch 'record Up\(\) implements Step') { $resolutionProblems.Add("Step.Up is missing") }
Write-Host "Chain resolution check (static)"
if ($resolutionProblems.Count -gt 0) {
	$resolutionProblems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "chain resolution check failed ($($resolutionProblems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXChainResolution.scale()/half() read VFXSettings (chainres default 0.5, 1.0/0.5 only), MIN_RUN_TAPS 8, Step.Down/Up + Step.Single(resScale)"

# --- planner fixtures (Task 4): the pass-level model, computed by the planner itself ---------------
$plannerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusionPlanner.java"
$policyPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusionPolicy.java"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXFusedPrograms.java"
foreach ($file in @(
	@($plannerPath, 'VFXFusionPlanner.java'),
	@($policyPath, 'VFXFusionPolicy.java'),
	@($programsPath, 'VFXFusedPrograms.java'))) {
	if (-not (Test-Path -LiteralPath $file[0])) {
		Write-Error "fusion planner check: missing $($file[1]) (Task 4 not implemented)."
		exit 1
	}
}
$clientClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\client"
if (-not (Test-Path (Join-Path $clientClasses "dev\vfxweaver\client\postprocessing\VFXFusionPlanner.class"))) {
	Write-Error "fusion planner check: build :26.1.2 first (missing $clientClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "fusion planner check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$plannerCheckDir = Join-Path $env:TEMP "vfxweaver-fusion-planner-check"
New-Item -ItemType Directory -Force -Path $plannerCheckDir | Out-Null
$plannerSrc = Join-Path $plannerCheckDir "FusionPlannerCheck.java"
$plannerCpFile = Join-Path $plannerCheckDir "cp.txt"
$plannerCp = "versions/26.1.2/build/classes/java/main;versions/26.1.2/build/classes/java/client;$($plannerCheckDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($plannerCpFile, "-cp `"$plannerCp`"", [System.Text.UTF8Encoding]::new($false))
$plannerJava = @'
package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.client.postprocessing.VFXFusionPlanner.StageRef;
import dev.vfxweaver.client.postprocessing.VFXFusionPlanner.Step;
import dev.vfxweaver.client.postprocessing.VFXShaderPrograms.PassRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The planner fixtures: the pass-level model the budget error would have broken. */
public final class FusionPlannerCheck {
	private static final VFXShaderPrograms.ProgramInfo POINT = new VFXShaderPrograms.ProgramInfo(
		null, new String[] {"a", "b"}, 8, PassRole.NORMAL, true, null, false, false, false, VFXFusionClass.POINT, 1);
	private static final VFXShaderPrograms.ProgramInfo CONSUMER = new VFXShaderPrograms.ProgramInfo(
		null, new String[0], 0, PassRole.NORMAL, false, null, true, false, false, VFXFusionClass.BARRIER, 1);

	public static void main(final String[] args) {
		four_masked_pointwise_plans_as_one_run();
		at_most_one_run_head_consumer_per_run();
		sampler_cut_at_six_effects();
		if (VFXChainResolution.half()) {
			heavy_scene_splits_into_two_runs();
			heavy_scene_with_mask_on_blur_splits_into_three_runs();
			tap_threshold_is_not_wrapped();
			System.out.println("fusion planner check OK: fixtures hold, half-resolution runs split");
		} else {
			default_scene_stays_full_resolution();
			System.out.println("fusion planner check OK: fixtures hold, default path emits no conversions");
		}
	}

	/** The spec section 1 scene, modeled at the pre-audit scalability of spec section 4. */
	private static List<StageRef> heavyScene() {
		final List<StageRef> chain = new ArrayList<>();
		chain.add(new StageRef(scalable(25), null, false, false)); // blur_x
		chain.add(new StageRef(scalable(25), null, false, false)); // blur_y
		chain.add(new StageRef(scalable(9), null, false, false));  // bloom
		chain.add(new StageRef(plain(4), null, false, false));     // vhs
		chain.add(new StageRef(plain(0), null, false, false));     // afterimage
		chain.add(new StageRef(scalable(16), null, false, false)); // depth_of_field
		chain.add(new StageRef(plain(4), null, false, false));     // digital_glitch
		chain.add(new StageRef(plain(3), null, false, false));     // chromatic_aberration
		return chain;
	}

	/** The same scene with a mask consumer right after the blur passes. */
	private static List<StageRef> maskedHeavyScene() {
		final List<StageRef> chain = new ArrayList<>();
		chain.add(new StageRef(scalable(25), null, false, false)); // blur_x
		chain.add(new StageRef(scalable(25), null, false, false)); // blur_y
		chain.add(new StageRef(plain(0), null, true, false));      // mask consumer
		chain.add(new StageRef(scalable(9), null, false, false));  // bloom
		chain.add(new StageRef(plain(4), null, false, false));     // vhs
		chain.add(new StageRef(plain(0), null, false, false));     // afterimage
		chain.add(new StageRef(scalable(16), null, false, false)); // depth_of_field
		chain.add(new StageRef(plain(4), null, false, false));     // digital_glitch
		chain.add(new StageRef(plain(3), null, false, false));     // chromatic_aberration
		return chain;
	}

	private static void heavy_scene_splits_into_two_runs() {
		sequence(heavyScene(), "Down S(25@2.0) S(25@2.0) S(9@2.0) Up S(4@1.0) S(0@1.0) Down S(16@2.0) Up S(4@1.0) S(3@1.0)");
		System.out.println("  heavy_scene_splits_into_two_runs: Down blur_x blur_y bloom Up | vhs afterimage | Down dof Up | glitch chromatic_aberration");
	}

	private static void heavy_scene_with_mask_on_blur_splits_into_three_runs() {
		sequence(maskedHeavyScene(), "Down S(25@2.0) S(25@2.0) Up S(0@1.0) Down S(9@2.0) Up S(4@1.0) S(0@1.0) Down S(16@2.0) Up S(4@1.0) S(3@1.0)");
		System.out.println("  heavy_scene_with_mask_on_blur_splits_into_three_runs: blur run, consumer, bloom run, dof run");
	}

	private static void tap_threshold_is_not_wrapped() {
		sequence(List.of(new StageRef(scalable(4), null, false, false), new StageRef(scalable(4), null, false, false)),
			"Down S(4@2.0) S(4@2.0) Up");
		sequence(List.of(new StageRef(scalable(3), null, false, false), new StageRef(plain(4), null, false, false)),
			"S(3@1.0) S(4@1.0)");
		sequence(List.of(new StageRef(scalable(3), null, false, false), new StageRef(scalable(4), null, false, false)),
			"S(3@1.0) S(4@1.0)");
		System.out.println("  tap_threshold_is_not_wrapped: 4+4 wraps, 3+plain and 3+4 stay full resolution");
	}

	private static void default_scene_stays_full_resolution() {
		final List<Step> steps = VFXFusionPlanner.plan(heavyScene(), ignored -> new VFXFusedPrograms.FusedProgram(null, "check", List.of(), 0, 0));
		for (final Step step : steps) {
			if (step instanceof Step.Down || step instanceof Step.Up) {
				throw new AssertionError("the default path emitted a conversion");
			}
			if (step instanceof Step.Single single && single.resScale() != 1.0F) {
				throw new AssertionError("the default path scaled a stage to " + single.resScale());
			}
		}
		is(steps.size(), 8, "default scene passes");
		System.out.println("  default_scene_stays_full_resolution: 8 plain passes, no Down/Up");
	}

	private static VFXShaderPrograms.ProgramInfo scalable(final int taps) {
		return new VFXShaderPrograms.ProgramInfo(null, new String[0], 0, PassRole.NORMAL, false, null, false, false, false, VFXFusionClass.BARRIER, 1, true, Set.of(), taps);
	}

	private static VFXShaderPrograms.ProgramInfo plain(final int taps) {
		return new VFXShaderPrograms.ProgramInfo(null, new String[0], 0, PassRole.NORMAL, false, null, false, false, false, VFXFusionClass.BARRIER, 1, false, Set.of(), taps);
	}

	private static void sequence(final List<StageRef> chain, final String want) {
		final List<Step> steps = VFXFusionPlanner.plan(chain, ignored -> new VFXFusedPrograms.FusedProgram(null, "check", List.of(), 0, 0));
		final String got = describe(steps);
		if (!got.equals(want)) {
			throw new AssertionError("plan = [" + got + "], want [" + want + "]");
		}
	}

	private static String describe(final List<Step> steps) {
		final StringBuilder sb = new StringBuilder();
		for (final Step step : steps) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			if (step instanceof Step.Down) {
				sb.append("Down");
			} else if (step instanceof Step.Up) {
				sb.append("Up");
			} else if (step instanceof Step.Single single) {
				sb.append("S(").append(single.stage().info().taps()).append('@').append(single.resScale()).append(')');
			} else {
				sb.append("Fused");
			}
		}
		return sb.toString();
	}

	private static void four_masked_pointwise_plans_as_one_run() {
		final List<StageRef> chain = maskedPointwise(4);
		final List<VFXFusionPlanner.Run> runs = VFXFusionPlanner.runs(chain);
		is(runs.size(), 1, "four masked pointwise runs");
		is(runs.get(0).stages().size(), 8, "four masked pointwise stages");
		is(runs.get(0).linear(), 12, "four masked pointwise linear");
		is(runs.get(0).samplers(), 10, "four masked pointwise samplers");
		final List<Step> steps = VFXFusionPlanner.plan(chain, ignored -> new VFXFusedPrograms.FusedProgram(null, "check", List.of(), 0, 0));
		int fused = 0;
		for (final Step step : steps) {
			if (step instanceof Step.Fused) {
				fused++;
			}
		}
		is(fused, 1, "four masked pointwise fused steps");
		System.out.println("  four_masked_pointwise_plans_as_one_run: 1 Fused, 8 stages, linear 12, samplers 10");
	}

	private static void at_most_one_run_head_consumer_per_run() {
		final List<StageRef> chain = new ArrayList<>();
		chain.add(new StageRef(CONSUMER, null, true, false));
		chain.add(new StageRef(CONSUMER, null, true, false));
		final List<VFXFusionPlanner.Run> runs = VFXFusionPlanner.runs(chain);
		is(runs.size(), 2, "two standalone consumers split into two runs");
		for (final VFXFusionPlanner.Run run : runs) {
			int heads = 0;
			for (int i = 0; i < run.stages().size(); i++) {
				if (i == 0 && run.stages().get(i).mask()) {
					heads++;
				}
			}
			if (heads > 1) {
				throw new AssertionError("run carries more than one run-head consumer");
			}
		}
		System.out.println("  at_most_one_run_head_consumer_per_run: 2 runs, at most one head consumer each");
	}

	private static void sampler_cut_at_six_effects() {
		final List<VFXFusionPlanner.Run> four = VFXFusionPlanner.runs(maskedPointwise(4));
		final List<VFXFusionPlanner.Run> five = VFXFusionPlanner.runs(maskedPointwise(5));
		final List<VFXFusionPlanner.Run> six = VFXFusionPlanner.runs(maskedPointwise(6));
		is(linearTotal(four), 12, "four linear");
		is(five.size(), 1, "five runs");
		is(linearTotal(five), 15, "five linear");
		is(five.get(0).samplers(), 12, "five samplers");
		is(linearTotal(six), 18, "six linear");
		is(six.size(), 2, "six runs");
		System.out.println("  sampler_cut_at_six_effects: 12->1, 15->1, 18->2");
	}

	private static int linearTotal(final List<VFXFusionPlanner.Run> runs) {
		int total = 0;
		for (final VFXFusionPlanner.Run run : runs) {
			total += run.linear();
		}
		return total;
	}

	private static List<StageRef> maskedPointwise(final int effects) {
		final List<StageRef> chain = new ArrayList<>();
		for (int i = 0; i < effects; i++) {
			chain.add(new StageRef(POINT, null, false, true));
			chain.add(new StageRef(CONSUMER, null, true, false));
		}
		return chain;
	}

	private static void is(final int actual, final int want, final String what) {
		if (actual != want) {
			throw new AssertionError(what + " = " + actual + ", want " + want);
		}
	}
}
'@
[System.IO.File]::WriteAllText($plannerSrc, $plannerJava, [System.Text.UTF8Encoding]::new($false))

Write-Host "Fusion planner check"
Push-Location $repoRoot
try {
	& $javac "@$plannerCpFile" -d $plannerCheckDir $plannerSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	foreach ($chainres in @($null, "0.75", "0.5")) {
		$props = @()
		if ($chainres) { $props = @("-Dvfxweaver.chainres=$chainres") }
		& $java @props "@$plannerCpFile" dev.vfxweaver.client.postprocessing.FusionPlannerCheck
		if ($LASTEXITCODE -ne 0) { throw "FusionPlannerCheck failed (chainres=$(if ($chainres) { $chainres } else { 'default' }))" }
	}
} finally {
	Pop-Location
}
Write-Host "Fusion planner check OK."
exit 0

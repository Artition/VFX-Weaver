$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$renderer = [System.IO.File]::ReadAllText((Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\render\VFXWorldOverlayRenderer.java"))
$basis = [System.IO.File]::ReadAllText((Join-Path $repoRoot "src\main\java\dev\vfxweaver\util\VFXBeamBasis.java"))
$problems = New-Object System.Collections.Generic.List[string]

# --- static: the renderer reads the direction params and emits along the basis ---------------------
if ($renderer -notmatch 'effect\.getParam\("dir_x", 0\.0F\)') { $problems.Add("VFXWorldOverlayRenderer: dir_x with default 0.0 is missing") }
if ($renderer -notmatch 'effect\.getParam\("dir_y", 1\.0F\)') { $problems.Add("VFXWorldOverlayRenderer: dir_y with default 1.0 (up) is missing") }
if ($renderer -notmatch 'effect\.getParam\("dir_z", 0\.0F\)') { $problems.Add("VFXWorldOverlayRenderer: dir_z with default 0.0 is missing") }
if ($renderer -notmatch 'VFXBeamBasis\.of\(') { $problems.Add("VFXWorldOverlayRenderer: the beam basis is not built through VFXBeamBasis") }
if ($renderer -notmatch 'emitConeShell\(buffer, pose, cx, cy, cz, basis,') { $problems.Add("VFXWorldOverlayRenderer: emitConeShell does not take the base point and the basis") }
if ($renderer -match 'cx \+ cos0 \* r0, y0s, cz \+ sin0 \* r0') { $problems.Add("VFXWorldOverlayRenderer: the old Y-axis-only ring emission is still present") }
if ($renderer -notmatch 'effect\.getParam\("end_at_x", Float\.NaN\)') { $problems.Add("VFXWorldOverlayRenderer: the end_at_x/y/z tip params are missing") }
if ($renderer -notmatch 'endX - basis\[0\] \* height') { $problems.Add("VFXWorldOverlayRenderer: the anchor is not derived from the tip (end - axis * height)") }
if ($basis -notmatch '1\.0F, 0\.0F, 0\.0F, 0\.0F, 0\.0F, 1\.0F\}') { $problems.Add("VFXBeamBasis: the pinned vertical (right +X, up +Z) basis is missing") }

Write-Host "Beam basis check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "beam basis check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable: the basis math (pure, so no Minecraft on the classpath) -----------------------------
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) { Write-Error "beam basis check: no JDK found (set JAVA_HOME); static contracts passed."; exit 1 }
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\util\VFXBeamBasis.class"))) { Write-Error "beam basis check: build :26.1.2 first (missing $mainClasses)."; exit 1 }
$checkDir = Join-Path $env:TEMP "vfxweaver-beam-basis-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "BeamBasisCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'))"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))
$checkJava = @'
import dev.vfxweaver.util.VFXBeamBasis;

/** The beam-basis contracts: the shipped default, the fallback, and orthonormality. */
public final class BeamBasisCheck {
	public static void main(String[] args) {
		// The default direction must reproduce the shipped vertical geometry exactly: the pinned
		// basis (right = +X, up = +Z) is what the old cx + cos*r / cz + sin*r emission used.
		float[] up = VFXBeamBasis.of(0.0F, 1.0F, 0.0F);
		near(up[0], 0.0F, "default axis x");
		near(up[1], 1.0F, "default axis y");
		near(up[2], 0.0F, "default axis z");
		near(up[3], 1.0F, "default right x");
		near(up[4], 0.0F, "default right y");
		near(up[5], 0.0F, "default right z");
		near(up[6], 0.0F, "default up x");
		near(up[7], 0.0F, "default up y");
		near(up[8], 1.0F, "default up z");

		near(VFXBeamBasis.of(0.0F, 0.0F, 0.0F)[1], 1.0F, "zero direction fallback");
		near(VFXBeamBasis.of(0.0F, 7.0F, 0.0F)[1], 1.0F, "scaled up normalised");

		float[] down = VFXBeamBasis.of(0.0F, -1.0F, 0.0F);
		near(down[1], -1.0F, "down axis y");
		near(down[3], 1.0F, "down right x");
		near(down[8], 1.0F, "down up z");

		orthonormal(1.0F, 0.0F, 0.0F);
		orthonormal(0.0F, 0.3F, 0.9F);
		orthonormal(1.0F, 2.0F, 3.0F);
		orthonormal(-4.0F, 0.5F, 2.0F);
		System.out.println("beam basis check OK: the default is the pinned vertical basis and every axis is orthonormal");
	}

	private static void orthonormal(float dx, float dy, float dz) {
		float[] b = VFXBeamBasis.of(dx, dy, dz);
		near(len(b, 0), 1.0F, "axis unit (" + dx + "," + dy + "," + dz + ")");
		near(len(b, 3), 1.0F, "right unit");
		near(len(b, 6), 1.0F, "up unit");
		near(dot(b, 0, 3), 0.0F, "axis . right");
		near(dot(b, 0, 6), 0.0F, "axis . up");
		near(dot(b, 3, 6), 0.0F, "right . up");
	}

	private static float len(float[] b, int o) {
		return (float) Math.sqrt(b[o] * b[o] + b[o + 1] * b[o + 1] + b[o + 2] * b[o + 2]);
	}

	private static float dot(float[] b, int a, int c) {
		return b[a] * b[c] + b[a + 1] * b[c + 1] + b[a + 2] * b[c + 2];
	}

	private static void near(float actual, float want, String what) {
		if (Math.abs(actual - want) > 1.0e-4F) {
			throw new AssertionError(what + " = " + actual + ", want " + want);
		}
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" BeamBasisCheck
	if ($LASTEXITCODE -ne 0) { throw "BeamBasisCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Beam basis check OK."
exit 0

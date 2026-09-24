$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver\mask"
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing"
$shaders = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$space = Read-Source (Join-Path $main "VFXMaskSpace.java")
$kind = Read-Source (Join-Path $main "VFXMaskShapeKind.java")
$mask = Read-Source (Join-Path $main "VFXMask.java")
$parser = Read-Source (Join-Path $main "VFXMaskParser.java")
$uniforms = Read-Source (Join-Path $client "VFXMaskUniforms.java")
$camera = Read-Source (Join-Path $shaders "include\camera.glsl")
$coverage = Read-Source (Join-Path $shaders "post\mask_coverage.fsh")
$problems = New-Object System.Collections.Generic.List[string]

if ($space -notmatch 'DOME\("dome"\)') { $problems.Add('VFXMaskSpace: DOME("dome") is missing') }
if ($kind -notmatch 'SKY\("sky",\s*VFXMaskSpace\.DOME,\s*true') { $problems.Add("VFXMaskShapeKind: SKY must be dome-only and depth-needing") }
if ($mask -notmatch 'public boolean needsDepth\(\)') { $problems.Add("VFXMask: needsDepth() is missing") }
if ($parser -notmatch 'must be .screen., .world. or .dome.') { $problems.Add("VFXMaskParser: dome space validation is missing") }
if ($parser -notmatch 'sky.*dome') { $problems.Add("VFXMaskParser: sky dome-only validation is missing") }
if ($parser -notmatch 'worldOnly.*DOME|DOME.*worldOnly') { $problems.Add("VFXMaskParser: dome world-only validation is missing") }
if ($uniforms -notmatch 'depth_valid') { $problems.Add("VFXMaskUniforms: appended depth_valid field is missing") }
if ($camera -notmatch '#define VFX_DEPTH_FAR_RAW 0\.0') { $problems.Add("camera.glsl: reversed VFX_DEPTH_FAR_RAW is missing") }
if ($camera -notmatch '#define VFX_DEPTH_FAR_RAW 1\.0') { $problems.Add("camera.glsl: standard VFX_DEPTH_FAR_RAW is missing") }
if (-not (Test-Path (Join-Path $shaders "include\dome.glsl"))) { $problems.Add("include/dome.glsl is missing") }
if ($coverage -notmatch '#moj_import <vfxweaver:dome.glsl>') { $problems.Add("mask_coverage.fsh: dome.glsl import is missing") }
if ($coverage -notmatch 'vfx_view_dir\(') { $problems.Add("mask_coverage.fsh: vfx_view_dir dispatch is missing") }
if ($coverage -notmatch 'vfx_dome_uv\(') { $problems.Add("mask_coverage.fsh: vfx_dome_uv dispatch is missing") }
if ($coverage -notmatch 'VFX_DEPTH_IS_SKY\(depthRaw\)') { $problems.Add("mask_coverage.fsh: sky test is missing") }
if ($coverage -notmatch 'leafSpace == 2') { $problems.Add("mask_coverage.fsh: dome space code 2 is missing") }
if ($coverage -notmatch 'depth_valid') { $problems.Add("mask_coverage.fsh: fail-closed depth gate is missing") }

Write-Host "Mask sky check (static)"
if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "  - $_" }
    Write-Error "mask sky check failed ($($problems.Count) problem(s))."
    exit 1
}
Write-Host "  static contracts OK"

$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
    $candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
    if ($candidate) { $javac = Join-Path $candidate.FullName "bin\javac.exe"; $java = Join-Path $candidate.FullName "bin\java.exe" }
}
if (-not $javac -or -not (Test-Path $javac)) { Write-Error "mask sky check: no JDK found (set JAVA_HOME)"; exit 1 }
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\mask\VFXMask.class"))) { Write-Error "mask sky check: build :26.1.2 first"; exit 1 }
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) { Write-Error "mask sky check: 26.1.2 Minecraft jar not found"; exit 1 }
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } | ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'
$checkDir = Join-Path $env:TEMP "vfxweaver-mask-sky-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "MaskSkyCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))
$checkJava = @'
import com.google.gson.JsonParser;
import dev.vfxweaver.mask.VFXMask;

public final class MaskSkyCheck {
    public static void main(String[] args) {
        VFXMask sky = VFXMask.parse("check", JsonParser.parseString("{\"shape\":\"sky\",\"space\":\"dome\"}").getAsJsonObject());
        require(sky.primitives().get(0).space() == dev.vfxweaver.mask.VFXMaskSpace.DOME, "sky space");
        require(sky.primitives().get(0).shape() == dev.vfxweaver.mask.VFXMaskShapeKind.SKY, "sky shape");
        require(sky.needsDepth(), "sky needsDepth");
        rejected("mask: shape 'sky' is dome-only", "{\"shape\":\"sky\"}");
        rejected("mask: shape 'sphere' is world-only ('sphere'/'box' classify a 3D world volume)", "{\"shape\":\"sphere\",\"space\":\"dome\",\"center\":[0.0,0.0,0.0]}");
        System.out.println("mask sky check OK: dome sky parses and needs depth; invalid space combinations are rejected");
    }
    private static void rejected(String expected, String json) {
        try { VFXMask.parse("check", JsonParser.parseString(json).getAsJsonObject()); throw new AssertionError("accepted: " + json); }
        catch (IllegalArgumentException e) { require(expected.equals(e.getMessage()), "message = " + e.getMessage() + ", want " + expected); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))
Push-Location $repoRoot
try {
    & $javac "@$cpFile" -d $checkDir $checkSrc
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }
    & $java "@$cpFile" MaskSkyCheck
    if ($LASTEXITCODE -ne 0) { throw "MaskSkyCheck failed" }
} finally { Pop-Location }
Write-Host "Mask sky check OK."
exit 0

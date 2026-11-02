# Deploy the freshly built jars into every PrismLauncher instance that runs VFX-Weaver.
# All six Stonecutter nodes are built in ONE gradlew invocation, then each jar is installed into its
# instance. Older vfxweaver-*.jar files are deleted first: two versions of the same mod in one
# instance is a duplicate-mod crash. Re-running is safe (same jar name is overwritten).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/deploy.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# The mod version comes from gradle.properties, so a version bump needs no edit here.
$props = Get-Content (Join-Path $root "gradle.properties")
$versionMatch = [regex]::Match(($props -join "`n"), "(?m)^\s*mod_version\s*=\s*(\S+)\s*$")
if (-not $versionMatch.Success) {
    throw "mod_version is not set in $root\gradle.properties"
}
$modVersion = $versionMatch.Groups[1].Value

# PrismLauncher instance name -> Gradle node -> jar file name.
# Note 26.1fabric runs the 26.1.2 node; any other instance is unrelated and left alone.
$targets = @(
    [pscustomobject]@{ Instance = "26.2fabric";     Node = "26.2";             Jar = "vfxweaver-$modVersion+26.2.jar" }
    [pscustomobject]@{ Instance = "26.2neoforge";   Node = "26.2-neoforge";    Jar = "vfxweaver-$modVersion+26.2-neoforge.jar" }
    [pscustomobject]@{ Instance = "26.1fabric";     Node = "26.1.2";           Jar = "vfxweaver-$modVersion+26.1.2.jar" }
    [pscustomobject]@{ Instance = "26.1.2neoforge"; Node = "26.1.2-neoforge";  Jar = "vfxweaver-$modVersion+26.1.2-neoforge.jar" }
    [pscustomobject]@{ Instance = "1.21.11fabric";  Node = "1.21.11";          Jar = "vfxweaver-$modVersion+1.21.11.jar" }
    [pscustomobject]@{ Instance = "1.21.11neoforge"; Node = "1.21.11-neoforge"; Jar = "vfxweaver-$modVersion+1.21.11-neoforge.jar" }
)

# One Gradle invocation for all six nodes.
$buildTasks = @($targets | ForEach-Object { ":$($_.Node):build" })
Write-Host "building: $($buildTasks -join ' ')"
& "$root\gradlew.bat" @buildTasks -x test
if ($LASTEXITCODE -ne 0) {
    throw "gradle build failed (exit code $LASTEXITCODE)"
}

# A build that "succeeds" without the jars is a silent bad deploy, so check before touching any instance.
$missing = @()
foreach ($t in $targets) {
    $t | Add-Member -NotePropertyName Source -NotePropertyValue (Join-Path $root "versions\$($t.Node)\build\libs\$($t.Jar)") -Force
    if (-not (Test-Path $t.Source)) { $missing += $t.Source }
}
if ($missing.Count -gt 0) {
    throw "build finished but these jars are missing:`n$($missing -join "`n")"
}

$instancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
$skipped = @()
foreach ($t in $targets) {
    $mods = Join-Path $instancesRoot "$($t.Instance)\minecraft\mods"
    if (-not (Test-Path $mods -PathType Container)) {
        Write-Warning "$($t.Instance): no minecraft\mods directory - skipped (not creating one)"
        $skipped += $t.Instance
        continue
    }
    Get-ChildItem $mods -Filter "vfxweaver-*.jar" | Where-Object { $_.Name -ne $t.Jar } | Remove-Item -Force
    Copy-Item $t.Source (Join-Path $mods $t.Jar) -Force
    Write-Host "deployed -> $mods\$($t.Jar)"
}

$deployed = $targets.Count - $skipped.Count
Write-Host "done: $deployed of $($targets.Count) instances updated (mod version $modVersion)"
if ($skipped.Count -gt 0) {
    Write-Warning "skipped instances without a mods directory: $($skipped -join ', ')"
}

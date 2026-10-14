# Deploy the 26.1.2 Fabric jar into the PrismLauncher 26.1fabric instance.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/deploy-26.1fabric.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "versions\26.1.2\build\libs\vfxweaver-2.4.0+26.1.2.jar"
$mods = Join-Path $env:APPDATA "PrismLauncher\instances\26.1fabric\minecraft\mods"
& "$root\gradlew.bat" ":26.1.2:build" -x test
Copy-Item $jar (Join-Path $mods "vfxweaver-2.4.0+26.1.2.jar") -Force
Write-Host "deployed -> $mods\vfxweaver-2.4.0+26.1.2.jar"

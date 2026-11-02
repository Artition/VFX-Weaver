# Install the vfx_demos showcase datapack into a PrismLauncher world.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/deploy-showcase.ps1
#        powershell -ExecutionPolicy Bypass -File scripts/deploy-showcase.ps1 -Instance 26.2fabric -World "New World"
param(
    [string]$Instance = "26.2fabric",
    [string]$World = "New World"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$zip = Join-Path $root "docs\assets\downloads\vfx-demos-showcase.zip"
$saves = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft\saves"
$worldDir = Join-Path $saves $World
$datapacks = Join-Path $worldDir "datapacks"
$dest = Join-Path $datapacks "vfx_demos"

if (-not (Test-Path $zip)) {
    throw "showcase zip not found: $zip"
}
if (-not (Test-Path $datapacks)) {
    throw "world datapacks folder not found: $datapacks"
}

# The installed pack is generated from the zip, never hand-edited: wipe it so stale
# effect files from an older build cannot survive the install.
if (Test-Path $dest) {
    Remove-Item $dest -Recurse -Force
}
New-Item -ItemType Directory -Path $dest -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)

$meta = Join-Path $dest "pack.mcmeta"
$demo = Join-Path $dest "data\vfx_demos\vfx\show_text_overlay.json"
if (-not (Test-Path $meta)) {
    throw "install incomplete, missing pack.mcmeta in $dest"
}
if (-not (Test-Path $demo)) {
    throw "install incomplete, missing show_text_overlay.json in $dest"
}

$count = (Get-ChildItem (Join-Path $dest "data\vfx_demos\vfx") -Filter "*.json").Count
Write-Host "deployed -> $dest"
Write-Host "effects: $count"

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

# The installed pack is generated from the zip, so stale effect files from an older build
# must not survive the install. It is still the only copy of anything edited in place inside
# the world, so it is never deleted: it is moved aside first and the newest few are kept.
$backupsKept = 3
if (Test-Path $dest) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $backup = Join-Path $datapacks "vfx_demos.bak-$stamp"
    # Two runs inside the same second must not land on the same folder.
    $n = 1
    while (Test-Path -LiteralPath $backup) {
        $backup = Join-Path $datapacks "vfx_demos.bak-$stamp-$n"
        $n++
    }
    Move-Item -LiteralPath $dest -Destination $backup
    Write-Host "backed up -> $backup"

    # Keep the newest three snapshots so repeated runs cannot pile up. The timestamp leads
    # the name, so a descending name sort is oldest-last.
    $old = Get-ChildItem -LiteralPath $datapacks -Directory |
        Where-Object { $_.Name -like "vfx_demos.bak-*" } |
        Sort-Object Name -Descending |
        Select-Object -Skip $backupsKept
    foreach ($b in $old) {
        Remove-Item -LiteralPath $b.FullName -Recurse -Force
        Write-Host "pruned old backup -> $($b.FullName)"
    }
} else {
    Write-Host "nothing to back up: $dest does not exist"
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
Write-Host "show_text_overlay.json: present"

$count = (Get-ChildItem (Join-Path $dest "data\vfx_demos\vfx") -Filter "*.json").Count
Write-Host "deployed -> $dest"
Write-Host "effects: $count"

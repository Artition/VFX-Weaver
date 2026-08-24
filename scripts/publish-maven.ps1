# Publishes build/maven contents into the orphan "maven" branch of this repository,
# making the artifacts available via:
#   https://raw.githubusercontent.com/Artition/VFX-Weaver/maven
#
# Usage: run `gradlew publish` first, then this script.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$mavenOut = Join-Path $repoRoot "build\maven"
if (-not (Test-Path (Join-Path $mavenOut "dev"))) {
	Write-Error "build/maven is empty - run 'gradlew publish' first."
	exit 1
}

$tmp = Join-Path $env:TEMP "vfxweaver-maven-branch"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp | Out-Null

Push-Location $tmp
try {
	git init | Out-Null
	git remote add origin "https://github.com/Artition/VFX-Weaver.git"
	git fetch origin maven 2>$null
	if ($LASTEXITCODE -eq 0) {
		git checkout maven | Out-Null
	} else {
		git checkout --orphan maven | Out-Null
	}
	Copy-Item -Path (Join-Path $mavenOut "*") -Destination $tmp -Recurse -Force
	git add -A
	git commit -m "publish artifacts"
	git push origin maven
} finally {
	Pop-Location
	Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

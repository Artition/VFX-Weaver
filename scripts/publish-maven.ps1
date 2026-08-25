# Publishes build/maven contents into the orphan "maven" branch of this repository,
# making the artifacts available via:
#   https://raw.githubusercontent.com/Artition/VFX-Weaver/maven
#
# Usage: run `gradlew publish` first, then this script.
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
	git init > $null 2>&1
	git remote add origin "https://github.com/Artition/VFX-Weaver.git"
	git fetch origin maven > $null 2>&1
	if ($LASTEXITCODE -eq 0) {
		git checkout -B maven origin/maven > $null 2>&1
	} else {
		git checkout --orphan maven > $null 2>&1
	}
	if ($LASTEXITCODE -ne 0) {
		Write-Error "could not prepare the maven branch"
		exit 1
	}
	Copy-Item -Path (Join-Path $mavenOut "*") -Destination $tmp -Recurse -Force
	git add -A
	git commit -m "publish artifacts" > $null 2>&1
	git push origin maven 2>&1 | ForEach-Object { "$_" }
	if ($LASTEXITCODE -ne 0) {
		Write-Error "push to origin/maven failed"
		exit 1
	}
	Write-Host "maven branch updated."
} finally {
	Pop-Location
	Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# Dev-only showcase-datapack parse sweep (2026-10-30).
#
# Parses every JSON of the published showcase datapack (showcase/data/vfx_demos/vfx) through
# VFXDefinition.parse and fails on any bad definition: the pack is what the guide links to, so it
# must never ship a definition the mod cannot load.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-showcase-pack-parse.ps1 [-Dir <pack>/data/<ns>/vfx
# Exits 1 on any failed definition; 0 when every showcase definition parses.
param([string]$Dir = "showcase/data/vfx_demos/vfx")

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$definitionsDir = $Dir

if (-not (Test-Path (Join-Path $repoRoot $definitionsDir))) {
	Write-Error "showcase parse sweep: $definitionsDir not found."
	exit 1
}

$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) {
		$javac = Join-Path $candidate.FullName "bin\javac.exe"
		$java = Join-Path $candidate.FullName "bin\java.exe"
	}
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "showcase parse sweep: no JDK found (set JAVA_HOME)."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXDefinition.class"))) {
	Write-Error "showcase parse sweep: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
# 26.x splits the game into a common and a client artifact, and Loom keeps them in the repository's
# own cache instead of the merged-deobf jar the pre-26 lines use; take every 26.1.2 jar from there,
# and fall back to the old path for an older Loom.
$mcJars = @(Get-ChildItem (Join-Path $repoRoot ".gradle\loom-cache\minecraftMaven\net\minecraft") -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -match '\\26\.1\.2\\' })
if ($mcJars.Count -eq 0) {
	$merged = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
	if ($merged) { $mcJars = @($merged) }
}
if ($mcJars.Count -eq 0) {
	Write-Error "showcase parse sweep: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
# Gradle module jars, minus the mod's own published jars and Flashback (stale shadows of our classes).
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

# one temp dir per target, so two packs can be checked in a row
$checkDir = Join-Path $env:TEMP ("vfxweaver-pack-check-" + ($definitionsDir -replace "[^A-Za-z0-9]", "_"))
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$sweepSrc = Join-Path $checkDir "ParseSweep.java"
$cpFile = Join-Path $checkDir "cp.txt"
# The jars live under the repository path, which contains non-ASCII characters here; a Java @argfile
# is read in the platform charset, so a Cyrillic path reaches java mangled and the game classes are
# silently missing. Copy them into the ASCII temp dir and put those copies on the classpath.
$mcClasspath = ($mcJars | ForEach-Object {
	$copy = Join-Path $checkDir ('mc-' + $_.Name)
	if (-not (Test-Path $copy)) { Copy-Item $_.FullName $copy -Force }
	$copy.Replace('\', '/')
}) -join ';'
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$mcClasspath;$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$sweepJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;

/** Parses every showcase definition JSON through VFXDefinition.parse. */
public final class ParseSweep {
	public static void main(final String[] args) throws Exception {
		final Path dir = Path.of(args[0]);
		int ok = 0;
		int bad = 0;
		try (Stream<Path> files = Files.walk(dir)) {
			for (final Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				final String name = file.getFileName().toString().replace(".json", "");
				final Identifier id = Identifier.fromNamespaceAndPath("vfxweaver", name);
				try {
					final JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
					VFXDefinition.parse(id, json);
					ok++;
				} catch (final Exception e) {
					bad++;
					System.out.println("FAIL " + name + ": " + e);
				}
			}
		}
		System.out.println("showcase parsed: " + ok + ", failed: " + bad);
		if (bad > 0) {
			System.exit(1);
		}
	}
}
'@
[System.IO.File]::WriteAllText($sweepSrc, $sweepJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $sweepSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" ParseSweep $definitionsDir
	if ($LASTEXITCODE -ne 0) { throw "showcase parse sweep failed" }
} finally {
	Pop-Location
}
Write-Host "Showcase pack parse sweep OK."
exit 0

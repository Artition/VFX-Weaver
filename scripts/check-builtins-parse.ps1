# Dev-only built-in definition parse sweep (2026-09-20).
#
# Parses every JSON under src/main/resources/data/vfxweaver/vfx/ through VFXDefinition.parse and
# fails on the first parse error. Built-ins ship stable; a datapack-format regression (or a new
# parse-time validation such as a malformed texture id) must surface here, before a jar is built.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-builtins-parse.ps1
# Exits 1 on any failed definition; 0 when every built-in parses.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$definitionsDir = "src/main/resources/data/vfxweaver/vfx"

if (-not (Test-Path (Join-Path $repoRoot $definitionsDir))) {
	Write-Error "built-in parse sweep: $definitionsDir not found."
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
	Write-Error "built-in parse sweep: no JDK found (set JAVA_HOME)."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXDefinition.class"))) {
	Write-Error "built-in parse sweep: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "built-in parse sweep: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
# Gradle module jars, minus the mod's own published jars and Flashback (stale shadows of our classes).
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-builtins-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$sweepSrc = Join-Path $checkDir "ParseSweep.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$sweepJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.VFXDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;

/** Parses every built-in definition JSON through VFXDefinition.parse. */
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
		System.out.println("builtins parsed: " + ok + ", failed: " + bad);
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
	if ($LASTEXITCODE -ne 0) { throw "built-in parse sweep failed" }
} finally {
	Pop-Location
}
Write-Host "Built-in parse sweep OK."
exit 0

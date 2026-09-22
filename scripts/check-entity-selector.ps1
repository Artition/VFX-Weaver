# Dev-only guard for the client-side entity-selector grammar (2026-09-22).
#
# Compiles SelectorCheck against the built :26.1.2 main classes and asserts the selector subset a
# datapack binding may use: the natural forms (@e[tag=...], @e[type=...,limit=1], @p/@a/@s/@r, a
# bare name, distance/limit/sort, negation, quoted values) parse, and unsupported or malformed
# syntax (@e[nbt=...], @e[gamemode=...], @x, an unclosed bracket, a bad value) is rejected instead
# of silently matching every entity. This is the grammar VFXClientEntityReader applies client-side.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-entity-selector.ps1
# Exits 1 on any mismatch; 0 when the grammar holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

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
	Write-Error "entity-selector check: no JDK found (set JAVA_HOME)."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXEntitySelector.class"))) {
	Write-Error "entity-selector check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}

# Gradle module jars (jspecify annotations, etc.), minus the mod's own published jars.
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-selector-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$selectorSrc = Join-Path $checkDir "SelectorCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$selectorJava = @'
import dev.vfxweaver.effect.VFXEntitySelector;
import dev.vfxweaver.effect.VFXEntitySelector.Base;
import dev.vfxweaver.effect.VFXEntitySelector.Selector;
import dev.vfxweaver.effect.VFXEntitySelector.Sort;

/** Asserts the client-side selector grammar VFXClientEntityReader relies on. */
public final class SelectorCheck {
	private static int checks = 0;

	public static void main(final String[] args) {
		final Selector tag = VFXEntitySelector.parse("@e[tag=vfx_showcase,limit=1]");
		require(tag.base() == Base.ALL_ENTITIES, "@e[tag=...] base");
		require("vfx_showcase".equals(tag.tag()), "@e[tag=...] tag value");
		require(!tag.tagInverted(), "@e[tag=...] not inverted");
		require(tag.limit() == 1, "@e[tag=...] limit");

		final Selector typed = VFXEntitySelector.parse("@e[type=minecraft:villager,limit=1]");
		require("minecraft:villager".equals(typed.type()), "namespaced type");
		require("minecraft:villager".equals(VFXEntitySelector.parse("@e[type=villager]").type()), "type namespace default");

		require(VFXEntitySelector.parse("@p").base() == Base.NEAREST_PLAYER, "@p base");
		require(VFXEntitySelector.parse("@p").sort() == Sort.NEAREST, "@p sort");
		require(VFXEntitySelector.parse("@s").base() == Base.SELF, "@s base");
		require(VFXEntitySelector.parse("@a").base() == Base.ALL_PLAYERS, "@a base");
		require(VFXEntitySelector.parse("@r").base() == Base.RANDOM_ENTITY, "@r base");
		require(VFXEntitySelector.parse("@r").sort() == Sort.RANDOM, "@r sort");
		require("team".equals(VFXEntitySelector.parse("@a[tag=team]").tag()), "@a[tag=...]");

		require(VFXEntitySelector.parse("@e[tag=!foo]").tagInverted(), "tag negation");
		require(VFXEntitySelector.parse("@e[type=!minecraft:villager]").typeInverted(), "type negation");
		require(VFXEntitySelector.parse("@e[name=\"Bob\"]").name().equals("Bob"), "quoted name");
		require(VFXEntitySelector.parse("@e[name=!Bob]").nameInverted(), "name negation");
		require(VFXEntitySelector.parse("Bob").base() == Base.NAME, "bare name base");
		require("Bob".equals(VFXEntitySelector.parse("Bob").name()), "bare name value");

		final Selector upTo = VFXEntitySelector.parse("@e[distance=..10]");
		require(upTo.hasDistance() && upTo.minDistance() == 0.0 && upTo.maxDistance() == 10.0, "distance ..10");
		final Selector range = VFXEntitySelector.parse("@e[distance=5..15]");
		require(range.minDistance() == 5.0 && range.maxDistance() == 15.0, "distance 5..15");
		final Selector from = VFXEntitySelector.parse("@e[distance=8..]");
		require(from.minDistance() == 8.0 && from.maxDistance() == Double.POSITIVE_INFINITY, "distance 8..");
		require(VFXEntitySelector.parse("@e[sort=furthest,limit=1]").sort() == Sort.FURTHEST, "sort furthest");
		require(VFXEntitySelector.parse("@e[sort=random]").sort() == Sort.RANDOM, "sort random");
		require(VFXEntitySelector.parse("@e[sort=arbitrary]").sort() == Sort.ARBITRARY, "sort arbitrary");

		rejects("@e[nbt={a:1}]", "nbt");
		rejects("@e[nbt={a:1,b:2},tag=x]", "nbt with a nested comma");
		rejects("@e[gamemode=creative]", "gamemode");
		rejects("@e[scores={a=1}]", "scores");
		rejects("@e[x=0,y=0,z=0]", "position arguments");
		rejects("@e[team=red]", "team");
		rejects("@x", "unknown base");
		rejects("@e[tag=foo", "unclosed bracket");
		rejects("@e[limit=abc]", "bad limit");
		rejects("@e[type=]", "empty type");
		rejects("@e[sort=sideways]", "bad sort");
		rejects("@e[distance=abc]", "bad distance");
		rejects("", "blank");

		System.out.println("selector check OK: " + checks + " assertions");
	}

	private static void require(final boolean condition, final String label) {
		checks++;
		if (!condition) {
			throw new AssertionError("selector check failed: " + label);
		}
	}

	private static void rejects(final String selector, final String label) {
		checks++;
		try {
			VFXEntitySelector.parse(selector);
		} catch (final IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError("selector check failed: '" + selector + "' should be rejected (" + label + ")");
	}
}
'@
[System.IO.File]::WriteAllText($selectorSrc, $selectorJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $selectorSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" SelectorCheck
	if ($LASTEXITCODE -ne 0) { throw "selector check failed" }
} finally {
	Pop-Location
}
Write-Host "Entity-selector check OK."
exit 0

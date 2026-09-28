# Dev-only guard for the field include split (post-chain fusion, step 1).
#
# field.glsl declares per-effect state (`FieldConfig`, `DepthSampler`, `fld_tex0`) and, after the
# split, imports `field_body.glsl` which holds the declaration-free maths. That split only ships if it
# is provably inert for the standalone path, so this guard resolves the preprocessor for a real field
# consumer twice - once against the split tree, once against the same tree with field.glsl taken from
# HEAD - and requires the code lines to be identical, in order. Comments and blanks are ignored on
# both sides (the split adds comments by design), everything else must match exactly.
#
# Also asserts the inventory: the body declares nothing, the shell still declares all three names.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-field-include-split.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$shaders = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders"
$include = Join-Path $shaders "include"
$bodyPath = Join-Path $include "field_body.glsl"
$shellPath = Join-Path $include "field.glsl"

$problems = New-Object System.Collections.Generic.List[string]

if (-not (Test-Path -LiteralPath $bodyPath)) {
	$problems.Add("include/field_body.glsl is missing")
}
if (-not (Test-Path -LiteralPath $shellPath)) {
	$problems.Add("include/field.glsl is missing")
}

function Resolve-Shader([string]$root, [string]$rel) {
	$path = Join-Path $root $rel
	if (-not (Test-Path -LiteralPath $path)) { throw "missing include: $rel (from $root)" }
	$out = New-Object System.Collections.Generic.List[string]
	foreach ($line in [System.IO.File]::ReadAllLines($path)) {
		$m = [regex]::Match($line.Trim(), '^#moj_import\s+<([\w.-]+):([\w./-]+)>$')
		if ($m.Success) {
			# the loader's `<ns:file>` means shaders/include/<file>
			foreach ($nested in (Resolve-Shader $root (Join-Path 'include' $m.Groups[2].Value))) { $out.Add($nested) }
			continue
		}
		$out.Add($line)
	}
	return $out
}

function Get-CodeLines($lines) {
	@($lines | Where-Object { $t = $_.Trim(); $t -ne '' -and -not $t.StartsWith('//') })
}

# A temp tree: the real shaders, except field.glsl comes from HEAD (and the body is absent, because the
# HEAD version had no body import - the resolver will fail loudly if that ever stops being true).
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("vfx-split-" + [Guid]::NewGuid().ToString("N"))
$tmpInclude = Join-Path $tmp "include"
Copy-Item -Recurse -Force -LiteralPath $shaders -Destination $tmp
$head = & git -C $repoRoot show "HEAD:src/client/resources/assets/vfxweaver/shaders/include/field.glsl"
[System.IO.File]::WriteAllLines((Join-Path $tmpInclude "field.glsl"), $head, (New-Object System.Text.UTF8Encoding($false)))
# HEAD's field.glsl had no body import; remove the split's body so the "before" resolution cannot
# silently depend on it.
Remove-Item -Force -LiteralPath (Join-Path $tmpInclude "field_body.glsl")

$consumer = "post\color_grade.fsh"
$after = Get-CodeLines (Resolve-Shader $shaders $consumer)
$before = Get-CodeLines (Resolve-Shader $tmp $consumer)

if ($after.Count -ne $before.Count) {
	$problems.Add("resolved $consumer has $($after.Count) code lines after the split, $($before.Count) before")
} else {
	for ($i = 0; $i -lt $after.Count; $i++) {
		if ($after[$i] -ne $before[$i]) {
			$problems.Add("resolved $consumer differs at code line $($i + 1): before '$($before[$i].Trim())' after '$($after[$i].Trim())'")
			break
		}
	}
}
Remove-Item -Recurse -Force $tmp

if (Test-Path -LiteralPath $bodyPath) {
	$bodyLines = [System.IO.File]::ReadAllLines($bodyPath)
	foreach ($line in $bodyLines) {
		$t = $line.Trim()
		if ($t.StartsWith('//')) { continue }
		if ($t -match '^(uniform|layout\s*\()') {
			$problems.Add("field_body.glsl declares state ('$t') - the body must be declaration-free")
			break
		}
	}
}
foreach ($decl in 'layout(std140) uniform FieldConfig', 'uniform sampler2D DepthSampler;', 'uniform sampler2D fld_tex0;', '#moj_import <vfxweaver:field_body.glsl>') {
	$text = [System.IO.File]::ReadAllText($shellPath)
	if (-not $text.Contains($decl)) {
		$problems.Add("field.glsl no longer contains '$decl'")
	}
}

Write-Host "Field include split check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "field include split check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  body is declaration-free, shell declares FieldConfig/DepthSampler/fld_tex0 and imports the body, and a real consumer resolves to identical code lines before and after"
Write-Host "Field include split check OK."
exit 0

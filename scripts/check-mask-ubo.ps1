# Dev-only standalone check for the mask coverage UBO. It parses the std140 `Config` block declared
# in post/mask_coverage.fsh and the writer's declared layout (VFXMaskUniforms.CONFIG_LAYOUT),
# computes each field's std140 byte offset and vec4 slot index independently from the two
# declarations, and fails when they disagree in field, order, array length, slot or total size.
# This is the guard for the past interleaved-write bug (std140 grouped arrays).
#
# Usage: powershell -File scripts/check-mask-ubo.ps1
# Exits 1 (after listing the mismatch) when the writer's layout does not match the shader; 0 when
# they agree. Not shipped in the jar.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$shaderPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"
$writerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXMaskUniforms.java"

$shader = [System.IO.File]::ReadAllText($shaderPath)
$writer = [System.IO.File]::ReadAllText($writerPath)

function Resolve-Length([string]$expr, [hashtable]$symbols) {
	$e = $expr.Trim()
	if ($e -match '^\d+$') { return [int]$e }
	foreach ($key in $symbols.Keys) { $e = $e.Replace($key, [string]$symbols[$key]) }
	if ($e -match '^\s*(\d+)\s*\*\s*(\d+)\s*$') { return ([int]$Matches[1]) * ([int]$Matches[2]) }
	if ($e -match '^\s*(\d+)\s*$') { return [int]$Matches[1] }
	throw "cannot resolve array length expression: $expr"
}

function Get-ElementSize([string]$type) {
	if ($type -eq 'mat4') { return 64 }
	if ($type -eq 'vec4') { return 16 }
	if ($type -eq 'float') { return 4 }
	throw "unsupported Config field type: $type"
}

# --- shader declaration: #defines + the std140 Config block -------------------------------
$defines = @{}
foreach ($m in [regex]::Matches($shader, '#define\s+(?<n>MASK_MAX_\w+)\s+(?<v>\d+)')) {
	$defines[$m.Groups['n'].Value] = [int]$m.Groups['v'].Value
}

$configMatch = [regex]::Match($shader, 'layout\(std140\)\s+uniform\s+Config\s*\{(?<body>.*?)\}', 'Singleline')
if (-not $configMatch.Success) { throw "no std140 Config block found in $shaderPath" }

$shaderFields = New-Object System.Collections.Generic.List[object]
foreach ($line in ($configMatch.Groups['body'].Value -split "\r?\n")) {
	$code = ($line -replace '//.*$', '').Trim()
	if ($code -eq '' -or $code -eq ';') { continue }
	$m = [regex]::Match($code, '^(?<type>mat4|vec4|float)\s+(?<name>\w+)\s*(?:\[\s*(?<len>[^\]]+)\s*\])?\s*;')
	if (-not $m.Success) { throw "unrecognised Config declaration line: $code" }
	$len = if ($m.Groups['len'].Success) { Resolve-Length $m.Groups['len'].Value $defines } else { 1 }
	$shaderFields.Add([pscustomobject]@{ Name = $m.Groups['name'].Value; Type = $m.Groups['type'].Value; Len = $len })
}

# --- writer declaration: VFXMaskUniforms.CONFIG_LAYOUT ------------------------------------
$javaSymbols = @{
	'VFXMask.MAX_PRIMITIVES'          = $defines['MASK_MAX_PRIMITIVES']
	'VFXCustomShape.MAX_CUSTOM_LEAVES' = $defines['MASK_MAX_CUSTOM_LEAVES']
	'VFXCustomShape.MAX_CUSTOM_PARTS'  = $defines['MASK_MAX_CUSTOM_PARTS']
}
$layoutMatch = [regex]::Match($writer, 'CONFIG_LAYOUT\s*=\s*List\.of\((?<body>.*?)\);', 'Singleline')
if (-not $layoutMatch.Success) { throw "no CONFIG_LAYOUT list found in $writerPath" }

$writerFields = New-Object System.Collections.Generic.List[object]
foreach ($m in [regex]::Matches($layoutMatch.Groups['body'].Value, 'new ConfigField\("(?<name>[^"]+)",\s*"(?<type>[^"]+)",\s*(?<len>[^)]+)\)')) {
	$len = Resolve-Length $m.Groups['len'].Value $javaSymbols
	$writerFields.Add([pscustomobject]@{ Name = $m.Groups['name'].Value; Type = $m.Groups['type'].Value; Len = $len })
}

# --- compute std140 offsets independently and compare -------------------------------------
$problems = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]
$shaderOffset = 0
$writerOffset = 0
$count = [Math]::Max($shaderFields.Count, $writerFields.Count)
for ($i = 0; $i -lt $count; $i++) {
	$s = if ($i -lt $shaderFields.Count) { $shaderFields[$i] } else { $null }
	$w = if ($i -lt $writerFields.Count) { $writerFields[$i] } else { $null }
	if ($null -eq $s) { $problems.Add("slot ${i}: writer declares '$($w.Name)' but the shader does not"); }
	elseif ($null -eq $w) { $problems.Add("slot ${i}: shader declares '$($s.Name)' but the writer does not"); }
	else {
		if ($s.Name -ne $w.Name) { $problems.Add("slot ${i}: name shader '$($s.Name)' vs writer '$($w.Name)'"); }
		if ($s.Type -ne $w.Type) { $problems.Add("slot ${i} '$($s.Name)': type shader '$($s.Type)' vs writer '$($w.Type)'"); }
		if ($s.Len -ne $w.Len) { $problems.Add("slot ${i} '$($s.Name)': array length shader $($s.Len) vs writer $($w.Len)"); }
		if ($shaderOffset -ne $writerOffset) { $problems.Add("slot ${i} '$($s.Name)': byte offset shader $shaderOffset vs writer $writerOffset"); }
	}
	if ($null -ne $s) { $shaderOffset += (Get-ElementSize $s.Type) * $s.Len }
	if ($null -ne $w) { $writerOffset += (Get-ElementSize $w.Type) * $w.Len }
	$name = if ($null -ne $s) { $s.Name } else { $w.Name }
	$type = if ($null -ne $s) { $s.Type } else { $w.Type }
	$len = if ($null -ne $s) { $s.Len } else { $w.Len }
	$rows.Add([pscustomobject]@{ Slot = $i; Name = $name; Type = $type; Len = $len; Bytes = (Get-ElementSize $type) * $len })
}
if ($shaderOffset -ne $writerOffset) {
	$problems.Add("total size shader $shaderOffset vs writer $writerOffset")
}

Write-Host "Mask coverage UBO check"
Write-Host "  shader: post/mask_coverage.fsh ($($shaderFields.Count) fields)"
Write-Host "  writer: VFXMaskUniforms.CONFIG_LAYOUT ($($writerFields.Count) fields)"
Write-Host "  slot  field                type    len    bytes"
foreach ($row in $rows) {
	Write-Host ("  {0,-5} {1,-20} {2,-7} {3,-6} {4}" -f $row.Slot, $row.Name, $row.Type, $row.Len, $row.Bytes)
}
Write-Host "  total std140 size: shader $shaderOffset bytes, writer $writerOffset bytes"

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "Mask coverage UBO layout mismatch ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "UBO OK: writer and shader agree on field order, slots and size."
exit 0

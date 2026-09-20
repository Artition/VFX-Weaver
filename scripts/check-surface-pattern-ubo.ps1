# Dev-only guard for the textured surface_pattern Config UBO (2026-09-20).
#
# The pass Config is a std140 UBO whose offsets are positional: the float names in
# post/surface_pattern.fsh must equal the names in VFXShaderPrograms.registerDepthPost in the same
# order, and the manager must resolve every reserved (texture) name. A mismatch silently shifts
# values. This check compares the shader's Config block against registerDepthPost, asserts the
# texture include is shared by both consumers and that the channel codes agree positionally.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-surface-pattern-ubo.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$patternPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\surface_pattern.fsh"
$fieldPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\field.glsl"
$textureGlslPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\texture.glsl"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$textureJavaPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\field\VFXTexture.java"

$pattern = [System.IO.File]::ReadAllText($patternPath)
$field = [System.IO.File]::ReadAllText($fieldPath)
$textureGlsl = [System.IO.File]::ReadAllText($textureGlslPath)
$programs = [System.IO.File]::ReadAllText($programsPath)
$manager = [System.IO.File]::ReadAllText($managerPath)
$textureJava = [System.IO.File]::ReadAllText($textureJavaPath)

$problems = New-Object System.Collections.Generic.List[string]

# --- the shader's Config block: mat4 inv_view_proj first, then the float names in order --------
$config = [regex]::Match($pattern, 'layout\(std140\)\s+uniform\s+Config\s*\{(?<body>.*?)\}', 'Singleline')
if (-not $config.Success) { throw "no std140 Config block in $patternPath" }
$shaderNames = New-Object System.Collections.Generic.List[string]
$hasMat4 = $false
foreach ($line in ($config.Groups['body'].Value -split "\r?\n")) {
	$code = ($line -replace '//.*$', '').Trim()
	if ($code -eq '' -or $code -eq ';') { continue }
	if ($code -match '^mat4\s+inv_view_proj\s*;$') { $hasMat4 = $true; continue }
	if ($code -match '^float\s+(\w+)\s*;$') { $shaderNames.Add($Matches[1]); continue }
	throw "unrecognised Config declaration line: $code"
}
if (-not $hasMat4) { $problems.Add("surface_pattern.fsh Config block lost the leading 'mat4 inv_view_proj;'") }

# --- registerDepthPost(SURFACE_PATTERN, ...) names, in order -----------------------------------
$call = [regex]::Match($programs, 'registerDepthPost\(VFXEffectType\.SURFACE_PATTERN\s*,(?<body>.*?)\);', 'Singleline')
if (-not $call.Success) { throw "no registerDepthPost(SURFACE_PATTERN, ...) call in $programsPath" }
$javaNames = New-Object System.Collections.Generic.List[string]
$body = $call.Groups['body'].Value -replace '//[^\r\n]*', ''
foreach ($m in [regex]::Matches($body, '"([^"]+)"')) { $javaNames.Add($m.Groups[1].Value) }

if ($shaderNames.Count -ne $javaNames.Count) {
	$problems.Add("Config float count: shader $($shaderNames.Count) vs registerDepthPost $($javaNames.Count)")
}
# The positional contract has a fixed float count (44 floats after mat4 inv_view_proj, plus the
# appended 'stitch' = 45). A hard count catches a field added to one side only or both sides
# silently forgetting one; bump this when a real field is appended.
$expectedFloatCount = 45
if ($shaderNames.Count -ne $expectedFloatCount) {
	$problems.Add("Config float count: shader $($shaderNames.Count) vs expected $expectedFloatCount")
}
$count = [Math]::Min($shaderNames.Count, $javaNames.Count)
for ($i = 0; $i -lt $count; $i++) {
	if ($shaderNames[$i] -ne $javaNames[$i]) {
		$problems.Add("Config slot ${i}: shader '$($shaderNames[$i])' vs registerDepthPost '$($javaNames[$i])'")
	}
}

# --- the manager resolves every new (texture) reserved name, and does not reserve texture_tint --
$reserved = [regex]::Match($manager, 'isReservedDepthParam[\s\S]*?return switch \(param\) \{(?<body>.*?)\};', 'Singleline')
if (-not $reserved.Success) { throw "no isReservedDepthParam body in $managerPath" }
$reservedBody = $reserved.Groups['body'].Value
$resolved = [regex]::Match($manager, 'isReservedDepthParam\(param\)\) \{(?<body>.*?)default ->', 'Singleline')
$switchBody = if ($resolved.Success) { $resolved.Groups['body'].Value } else { $manager }
foreach ($name in @('shape_present', 'tex_u0', 'tex_v0', 'tex_u1', 'tex_v1', 'tex_aspect', 'tex_cols', 'tex_rows', 'tex_frame', 'tex_flags', 'tex_channel', 'tex_px_w', 'tex_px_h')) {
	if ($shaderNames -notcontains $name) { $problems.Add("surface_pattern.fsh Config block is missing '$name'") }
	if ($reservedBody -notmatch ('"' + [regex]::Escape($name) + '"')) {
		$problems.Add("isReservedDepthParam does not list '$name'")
	}
	if ($switchBody -notmatch ('case\s+"' + [regex]::Escape($name) + '"')) {
		$problems.Add("VFXPostProcessingManager does not resolve the '$name' Config name")
	}
}
if ($reservedBody -match '"texture_tint"') {
	$problems.Add("isReservedDepthParam must not reserve 'texture_tint' (it is a fade-weighted param)")
}
# stitch is appended after tex_px_h and resolved from the surface block, not the timeline.
if ($shaderNames -notcontains 'stitch') {
	$problems.Add("surface_pattern.fsh Config block is missing 'stitch'")
}
if ($reservedBody -notmatch '"stitch"') {
	$problems.Add("isReservedDepthParam does not list 'stitch'")
}
if ($switchBody -notmatch 'case\s+"stitch"') {
	$problems.Add("VFXPostProcessingManager does not resolve the 'stitch' Config name")
}
if ($switchBody -notmatch 'case\s+"tex_frame"\s*->\s*effect\.getParam\("frame"') {
	$problems.Add("the manager does not read the animatable 'frame' param into tex_frame")
}

# --- the shared include is imported by both consumers and ships --------------------------------
if (-not (Test-Path -LiteralPath $textureGlslPath)) {
	$problems.Add("include/texture.glsl does not exist")
} else {
	if ($pattern -notmatch '#moj_import\s*<vfxweaver:texture\.glsl>') {
		$problems.Add("post/surface_pattern.fsh does not import <vfxweaver:texture.glsl>")
	}
	if ($field -notmatch '#moj_import\s*<vfxweaver:texture\.glsl>') {
		$problems.Add("include/field.glsl does not import <vfxweaver:texture.glsl>")
	}
	if ($textureGlsl -notmatch 'vec4\s+vfx_texture_sample\s*\(' -or $textureGlsl -notmatch 'float\s+vfx_texture_channel\s*\(') {
		$problems.Add("include/texture.glsl does not declare the shared sample/channel helpers")
	}
}

# --- the channel codes agree positionally between the Java enum and the shader -----------------
if ($textureJava -notmatch 'R\(0\),\s*G\(1\),\s*B\(2\),\s*ALPHA\(3\),\s*LUMINANCE\(4\)') {
	$problems.Add("VFXTexture.Channel codes are not R=0,G=1,B=2,ALPHA=3,LUMINANCE=4")
}
if ($textureGlsl -notmatch 'r = 0, g = 1, b = 2, alpha = 3, luminance = 4') {
	$problems.Add("include/texture.glsl channel-code comment does not match the positional contract")
}

Write-Host "Textured surface_pattern Config UBO check"
Write-Host "  shader Config floats: $($shaderNames.Count), registerDepthPost names: $($javaNames.Count)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "textured surface_pattern Config check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Config UBO OK: shader, registerDepthPost and the manager resolver agree; texture include shared by both consumers."
exit 0

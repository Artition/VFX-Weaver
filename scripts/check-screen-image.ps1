# Dev-only guard for the screen_image effect (Task 6).
#
# Pins the feature as literal text:
#   * VFXEffectType declares SCREEN_IMAGE("screen_image"), so the datapack `type` string resolves;
#   * screen_image.fsh declares its Config block in the exact order the manager writes it (rect,
#     frame_uv, opacity, flags - std140 offsets are positional, so a reordering silently shifts
#     values), binds the picture's own sampler and writes fragColor on every path (the three
#     early-outs are passthroughs, the last line composites);
#   * registerScreenImagePost binds that sampler on both API generations (withSampler on <26.2,
#     IMAGE_SAMPLER_LAYOUT on >=26.2), registers the pass as a BARRIER (it samples a texture the
#     chain does not own, so no fusion run may swallow it) and declares no per-param names, because
#     the manager fills the block positionally from the running effect;
#   * the manager resolves the picture per frame (VFXScreenImageTextures.get().resolve(effect)),
#     binds ImageSampler, and sizes the rect against the smaller side of the current target;
#   * the picture's frame table comes from the sibling .mcmeta through fromMetadata(), whose frame
#     size is the vanilla calculateFrameSize(imageWidth, imageHeight);
#   * interpolate: true is out of scope, so the interpolatedFrames flag must never be read.
# GLSL is never compiled and there is no GPU here, so nothing here claims a pixel or that the
# shader links - it pins the text the two halves must agree on.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-screen-image.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$typePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$shaderPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\screen_image.fsh"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$texturesPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXScreenImageTextures.java"
$framesPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowFrames.java"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($path in @($typePath, $shaderPath, $programsPath, $managerPath, $texturesPath, $framesPath)) {
	if (-not (Test-Path -LiteralPath $path)) {
		Write-Error "screen_image check: missing $path"
		exit 1
	}
}
$type = [System.IO.File]::ReadAllText($typePath)
$shader = [System.IO.File]::ReadAllText($shaderPath)
$programs = [System.IO.File]::ReadAllText($programsPath)
$manager = [System.IO.File]::ReadAllText($managerPath)
$textures = [System.IO.File]::ReadAllText($texturesPath)
$frames = [System.IO.File]::ReadAllText($framesPath)

function Assert-Contains([string]$text, [string]$literal, [string]$message) {
	if ($text -eq $null -or -not $text.Contains($literal)) {
		$problems.Add($message)
	}
}

# Asserts the literals appear in this order, each after the previous one: the Config block is a
# std140 UBO whose offsets are positional, so a shuffled declaration still "contains" every field
# and silently reads the wrong values.
function Assert-Order([string]$text, [string[]]$literals, [string]$message) {
	$at = 0
	foreach ($literal in $literals) {
		$next = $text.IndexOf($literal, $at)
		if ($next -lt 0) {
			$problems.Add("$message ('$literal' missing or out of order)")
			return
		}
		$at = $next + $literal.Length
	}
}

# 1) the effect type, so "type": "screen_image" resolves.
Assert-Contains $type 'SCREEN_IMAGE("screen_image")' "VFXEffectType.java is missing 'SCREEN_IMAGE(`"screen_image`")'"

# 2) the shader: the Config block in manager-write order, its own sampler, and an output on
#    every path (an early return without a write leaves fragColor undefined for that pixel).
Assert-Order $shader @('vec4 rect;', 'vec4 frame_uv;', 'float opacity;', 'float flags;') `
	"screen_image.fsh does not declare its Config block as rect, frame_uv, opacity, flags in that order"
foreach ($literal in @('uniform sampler2D InSampler;', 'uniform sampler2D ImageSampler;', 'out vec4 fragColor;')) {
	Assert-Contains $shader $literal "screen_image.fsh is missing '$literal'"
}
foreach ($literal in @('mix(frame_uv.xy, frame_uv.zw, local)', 'clamp(img.a * opacity, 0.0, 1.0)', 'if (flags < 0.5)')) {
	Assert-Contains $shader $literal "screen_image.fsh is missing '$literal'"
}
$shaderLines = $shader -split "\r?\n"
for ($i = 0; $i -lt $shaderLines.Count; $i++) {
	if ($shaderLines[$i] -match '^\s*return;') {
		$prev = if ($i -gt 0) { $shaderLines[$i - 1] } else { '' }
		if ($prev -notmatch 'fragColor\s*=') {
			$problems.Add("screen_image.fsh returns at line $($i + 1) without writing fragColor")
		}
	}
}

# 3) the registration: the extra sampler on both API generations, a BARRIER pass (it samples a
#    texture the chain does not own) and no per-param names (the manager writes the block
#    positionally, so a name list here would silently be ignored).
$registration = ''
$regAt = $programs.IndexOf('private static void registerScreenImagePost()')
if ($regAt -lt 0) {
	$problems.Add("VFXShaderPrograms.java is missing 'private static void registerScreenImagePost()'")
} else {
	$nextReg = $programs.IndexOf('private static void ', $regAt + 1)
	$registration = if ($nextReg -lt 0) { $programs.Substring($regAt) } else { $programs.Substring($regAt, $nextReg - $regAt) }
	foreach ($literal in @('post/screen_image', 'withSampler("InSampler")', 'withSampler("ImageSampler")',
			'IMAGE_SAMPLER_LAYOUT', 'SAMPLER_INFO_CONFIG_LAYOUT', 'PROGRAMS.put(VFXEffectType.SCREEN_IMAGE',
			'new String[0]', 'VFXFusionClass.BARRIER')) {
		Assert-Contains $registration $literal "registerScreenImagePost() is missing '$literal'"
	}
}

# 4) the manager: resolve the picture, size the rect against the smaller side of the live target
#    (so size_w:size_h is the picture's own proportion, not the window's aspect), bind the sampler
#    and write the four Config values in the shader's order.
foreach ($literal in @(
	'VFXScreenImageTextures.get().resolve(effect)',
	'Math.min(output.width, output.height)',
	'Mth.clamp(effect.getParam("size_w", 1.0F), 0.0F, 1.0F) * base',
	'Mth.clamp(effect.getParam("size_h", 1.0F), 0.0F, 1.0F) * base',
	'Mth.clamp(effect.getParam("pos_x", 0.0F), 0.0F, 1.0F) * Math.max(0.0F, output.width - picW)',
	'Mth.clamp(effect.getParam("pos_y", 0.0F), 0.0F, 1.0F) * Math.max(0.0F, output.height - picH)',
	'final float flags = image.view() != null ? 1.0F : 0.0F;',
	'renderPass.bindTexture("ImageSampler"')) {
	Assert-Contains $manager $literal "VFXPostProcessingManager.java is missing '$literal'"
}
Assert-Order $manager @('putVec4(rx0, ry0, rx1, ry1)', 'putVec4(u0, v0, u1, v1)', 'putFloat(opacity)', 'putFloat(flags)') `
	"VFXPostProcessingManager.java does not write the screen_image Config as rect, frame_uv, opacity, flags in that order"

# 5) the frame table: the sibling .mcmeta through the resource metadata, sized by vanilla.
foreach ($literal in @(
	'resource.get().metadata().getSection(AnimationMetadataSection.TYPE)',
	'VFXWindowFrames.fromMetadata(',
	'VFXWindowFrames.strip(',
	'VFXWindowFrames.still(',
	'Math.max(1, (int) effect.getParam("frames", 1.0F))')) {
	Assert-Contains $textures $literal "VFXScreenImageTextures.java is missing '$literal'"
}
Assert-Contains $frames 'calculateFrameSize(imageWidth, imageHeight)' `
	"VFXWindowFrames.java is missing 'calculateFrameSize(imageWidth, imageHeight)'"

# 6) interpolation is out of scope: the flag must never be read.
if ($shader -match 'interpolatedFrames' -or $textures -match 'interpolatedFrames') {
	$problems.Add("the interpolatedFrames flag is read - interpolate: true is out of scope")
}

Write-Host "screen_image check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "screen_image check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXEffectType declares SCREEN_IMAGE(`"screen_image`")"
Write-Host "  screen_image.fsh declares rect/frame_uv/opacity/flags in the manager's write order, binds ImageSampler and writes fragColor on every path"
Write-Host "  registerScreenImagePost binds ImageSampler (withSampler / IMAGE_SAMPLER_LAYOUT) as a BARRIER with no per-param names"
Write-Host "  the manager resolves the picture per frame, binds ImageSampler and sizes the rect against the smaller target side"
Write-Host "  the frame table comes from the sibling .mcmeta (AnimationMetadataSection.TYPE -> fromMetadata -> calculateFrameSize); interpolation is not used"
Write-Host "screen_image check OK."
exit 0
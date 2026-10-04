# Dev-only guard for the picture content of the custom-windows subsystem (Task 3).
#
# VFXWindowContent is the one place that owns an aux window's picture: it reuses the surface_pattern
# ground-image addressing (the Identifier plus the cols/rows/frame -> UV-rect math, row-major and
# frame 0 top-left), decodes the resource and uploads it into the aux context's own GL texture once
# per load/reload, and drawFrame only binds that texture, shifts the UV rect to the requested frame
# and scales the quad into the requested normalized sub-rect of the canvas. Its shape is pinned here
# as literal text:
#   * the produced interface is exactly load(Identifier, int),
#     drawFrame(int, float, float, float, float) and reload();
#   * load/reload funnel through one private uploadSource() that decodes (NativeImage) and uploads
#     (glGenTextures/glTexImage2D); neither drawFrame nor drawCurrent decodes or uploads - no
#     per-frame readback/upload (both bodies are scanned, so a moved upload is caught);
#   * the four rect values are normalized [0, 1] window coordinates with the origin at the
#     bottom-left, mapped to NDC with rect * 2 - 1, so the picture lands in the mapped sub-canvas
#     rectangle instead of filling the canvas;
#   * a closed window makes load/reload/drawFrame no-ops, so nothing calls GLFW/GL on a destroyed
#     handle;
#   * every GL call happens with the aux context current: glfwGetCurrentContext is saved and
#     glfwMakeContextCurrent restores it, so the game's context is never leaked;
#   * a frame is selected row-major (frame % columns, frame / columns) and mapped to a UV rect, so the
#     addressing is the same math the shader's vfx_texture_sheet_uv uses;
#   * never 0x0: the viewport draws from the actual framebuffer size clamped away from 0, and an image
#     too small for its frame count is refused rather than uploaded;
#   * a missing/undecodable source keeps the last uploaded frame and warns once, never crashes.
# It also rejects any loader import (src/client stays loader-agnostic), because GLSL is never compiled
# and there is no GPU here, nothing here claims a pixel or a shader works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-content.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowContent.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	Write-Error "window content check: missing src\client\java\dev\vfxweaver\client\window\VFXWindowContent.java"
	exit 1
}
$source = [System.IO.File]::ReadAllText($classPath)

function Get-Body([string]$text, [string]$signature) {
	$start = $text.IndexOf($signature)
	if ($start -lt 0) { return $null }
	$open = $text.IndexOf('{', $start)
	if ($open -lt 0) { return $null }
	$depth = 0
	for ($i = $open; $i -lt $text.Length; $i++) {
		if ($text[$i] -eq '{') { $depth++ }
		elseif ($text[$i] -eq '}') { $depth--; if ($depth -eq 0) { return $text.Substring($open + 1, $i - $open - 1) } }
	}
	return $null
}

function Assert-Contains([string]$body, [string]$literal, [string]$message) {
	if ($body -eq $null -or -not $body.Contains($literal)) {
		$problems.Add($message)
	}
}

# 0) the content is a final per-window owner and stays loader-agnostic.
if ($source -notmatch '(?m)^public final class VFXWindowContent \{') {
	$problems.Add("VFXWindowContent is not 'public final class'")
}
if ($source -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXWindowContent imports a loader package - src/client must stay loader-agnostic")
}

# 1) the exact produced interface.
foreach ($signature in @(
	'public void load(final Identifier textureId, final int frames) {',
	'public void drawFrame(final int frameIndex, final float rectX, final float rectY, final float rectW, final float rectH) {',
	'public void reload() {')) {
	if ($source.IndexOf($signature) -lt 0) {
		$problems.Add("VFXWindowContent is missing '$($signature.TrimEnd(' {'))'")
	}
}

# 2) the addressing is reused: an Identifier and the cols/rows/frame -> UV-rect math.
if ($source -notmatch 'Identifier') {
	$problems.Add("VFXWindowContent does not address the source by an Identifier")
}
$draw = Get-Body $source 'public void drawFrame(final int frameIndex, final float rectX, final float rectY, final float rectW, final float rectH) {'
if ($draw -eq $null) {
	$problems.Add("VFXWindowContent has no readable 'public void drawFrame(final int frameIndex, final float rectX, final float rectY, final float rectW, final float rectH)'")
} else {
	# drawFrame only flips the GL context and delegates; the actual quad lives in drawCurrent.
	Assert-Contains $draw 'drawCurrent(frameIndex, rectX, rectY, rectW, rectH)' "drawFrame() does not draw through drawCurrent(frameIndex, rectX, rectY, rectW, rectH)"
	Assert-Contains $draw 'GLFW.glfwGetCurrentContext()' "drawFrame() does not save the previously current context (glfwGetCurrentContext)"
	Assert-Contains $draw 'GLFW.glfwMakeContextCurrent(this.window.handle());' "drawFrame() does not make the window's own context current"
	Assert-Contains $draw 'GLFW.glfwMakeContextCurrent(previous);' "drawFrame() does not restore the previously current context - the game's context would leak"
}

# 3) the selected frame is addressed row-major and mapped to a UV rect (the surface_pattern math).
$current = Get-Body $source 'private void drawCurrent(final int frameIndex, final float rectX, final float rectY, final float rectW, final float rectH) {'
if ($current -eq $null) {
	$problems.Add("VFXWindowContent has no readable 'private void drawCurrent(final int frameIndex, final float rectX, final float rectY, final float rectW, final float rectH)'")
} else {
	foreach ($literal in @(
		'this.frameCount',
		'this.columns',
		'this.rows',
		'Math.floorMod(frameIndex, this.frameCount)',
		'frame % this.columns',
		'frame / this.columns',
		'(float) column / (float) this.columns',
		'(float) row / (float) this.rows',
		'(float) (column + 1) / (float) this.columns',
		'(float) (row + 1) / (float) this.rows')) {
		Assert-Contains $current $literal "drawCurrent() is missing the addressing literal: $literal"
	}
	# the requested normalized rect (bottom-left origin) is scaled into NDC with rect * 2 - 1.
	foreach ($literal in @(
		'rectW <= 0.0F',
		'rectH <= 0.0F',
		'rectX * 2.0F - 1.0F',
		'(rectX + rectW) * 2.0F - 1.0F',
		'rectY * 2.0F - 1.0F',
		'(rectY + rectH) * 2.0F - 1.0F',
		'GL11.glVertex2f(left, bottom);',
		'GL11.glVertex2f(right, bottom);',
		'GL11.glVertex2f(right, top);',
		'GL11.glVertex2f(left, top);')) {
		Assert-Contains $current $literal "drawCurrent() is missing the rect-to-NDC literal: $literal"
	}
	# never 0x0: the viewport comes from the actual framebuffer size, clamped away from 0.
	Assert-Contains $current 'GLFW.glfwGetFramebufferSize(this.window.handle(), width, height);' "drawCurrent() does not read the actual framebuffer size"
	Assert-Contains $current 'GL11.glViewport(0, 0, Math.max(1, width[0]), Math.max(1, height[0]));' "drawCurrent() does not clamp the viewport away from 0x0"
	Assert-Contains $current 'GL11.glBindTexture(' "drawCurrent() does not bind the uploaded texture"
}

# 4) load/reload funnel through one uploadSource() that decodes and uploads; drawFrame must not.
$load = Get-Body $source 'public void load(final Identifier textureId, final int frames) {'
if ($load -eq $null) {
	$problems.Add("VFXWindowContent has no readable 'public void load(final Identifier textureId, final int frames)'")
} else {
	Assert-Contains $load 'uploadSource()' "load() does not decode/upload once through uploadSource()"
	Assert-Contains $load 'Math.max(1, frames)' "load() does not keep the frame count away from 0 (Math.max(1, frames))"
}
$reload = Get-Body $source 'public void reload() {'
if ($reload -eq $null) {
	$problems.Add("VFXWindowContent has no readable 'public void reload()'")
} else {
	Assert-Contains $reload 'uploadSource()' "reload() does not re-decode/re-upload through uploadSource()"
}
$upload = Get-Body $source 'private void uploadSource() {'
if ($upload -eq $null) {
	$problems.Add("VFXWindowContent has no readable 'private void uploadSource()'")
} else {
	# the resource is read + decoded + uploaded exactly here.
	Assert-Contains $upload 'Minecraft.getInstance().getResourceManager().getResource(' "uploadSource() does not read the resource through the resource manager"
	Assert-Contains $upload 'NativeImage.read(' "uploadSource() does not decode the resource (NativeImage.read)"
	Assert-Contains $upload 'GL11.glGenTextures()' "uploadSource() does not create the GL texture"
	Assert-Contains $upload 'GL11.glTexImage2D(' "uploadSource() does not upload the GL texture"
	# created/uploaded with the aux context current, restored around it.
	Assert-Contains $upload 'GLFW.glfwGetCurrentContext()' "uploadSource() does not save the previously current context"
	Assert-Contains $upload 'GLFW.glfwMakeContextCurrent(this.window.handle());' "uploadSource() does not make the window's own context current"
	Assert-Contains $upload 'GLFW.glfwMakeContextCurrent(previous);' "uploadSource() does not restore the previously current context"
	# missing/short source: refused, keeping the last frame instead of crashing.
	Assert-Contains $upload 'resource.isEmpty()' "uploadSource() does not handle a missing resource (resource.isEmpty()) without crashing"
	Assert-Contains $upload 'imageWidth < columns' "uploadSource() does not refuse an image too small for its frame count (imageWidth < columns)"
	Assert-Contains $upload 'imageHeight < rows' "uploadSource() does not refuse an image too small for its frame count (imageHeight < rows)"
	# a caller-supplied image (VFXAPI.registerImage) has no pack file: it is resolved from the
	# registry first so a registered id wins, and it uploads through this same path.
	Assert-Contains $upload 'VFXImageRegistry.get().get(' "uploadSource() does not resolve a caller-supplied image from the registry before the pack"
}

# 5) the resource is never decoded or uploaded on either per-frame body: drawFrame delegates and
#    drawCurrent does the actual GL work, so the forbidden set must be absent from BOTH (checking
#    only drawFrame would let a moved upload through).
foreach ($pair in @(
	@('drawFrame', $draw),
	@('drawCurrent', $current))) {
	$name = $pair[0]
	$body = $pair[1]
	if ($body -eq $null) { continue }
	foreach ($forbidden in @('NativeImage', 'glGenTextures', 'glTexImage2D', 'uploadSource')) {
		if ($body.Contains($forbidden)) {
			$problems.Add("$name() contains '$forbidden' - the resource must be decoded/uploaded once in uploadSource(), never per frame")
		}
	}
}

# 6) a closed window is a no-op: no GLFW/GL call may run on a destroyed handle.
foreach ($entry in @(
	@('load', $load),
	@('reload', $reload),
	@('drawFrame', $draw))) {
	$name = $entry[0]
	$body = $entry[1]
	if ($body -ne $null -and -not $body.Contains('this.window.closed()')) {
		$problems.Add("$name() does not return early when the window is closed (this.window.closed()) - a post-close call would hit a destroyed handle")
	}
}

# 7) the render-thread-only rule is documented, because GL/GLFW are not thread-safe.
if ($source -notmatch 'render thread') {
	$problems.Add("VFXWindowContent does not document that its GL/GLFW calls are render-thread only")
}

Write-Host "Window content check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window content check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowContent is a final loader-agnostic per-window picture owner"
Write-Host "  load(Identifier, int)/drawFrame(int, float, float, float, float)/reload() are the produced interface"
Write-Host "  the frame is addressed row-major (frame % columns, frame / columns) into a UV rect, reusing the surface_pattern sheet math"
Write-Host "  the picture is drawn into the normalized bottom-left sub-rect (rect * 2 - 1), not the full canvas"
Write-Host "  load()/reload() decode and upload once through uploadSource(); neither drawFrame() nor drawCurrent() decodes or uploads"
Write-Host "  a closed window is a no-op for load()/reload()/drawFrame() - no GLFW/GL call on a destroyed handle"
Write-Host "  every GL call runs with the aux context current and restores the previous one; the viewport is never 0x0"
Write-Host "  a missing/undecodable/too-small source keeps the last frame and warns once"
Write-Host "Window content check OK."
exit 0

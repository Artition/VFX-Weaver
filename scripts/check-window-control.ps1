# Dev-only guard for the controller of the custom-windows subsystem (Task 5).
#
# VFXWindowController is the per-window applier: it maps the effect's 0..1 work-area-relative
# geometry onto the canvas (the free-space formula x = wa.x + pos_x * max(0, wa.w - w)), applies the
# clamped animated opacity, draws the selected frame into the mapped sub-canvas rectangle and
# presents. Its shape is pinned here as literal text:
#   * the produced interface is exactly apply(VFXWindow w, float posX, float posY, float sizeW,
#     float sizeH, float opacity, int frameIndex);
#   * the geometry is the free-space mapping over the window's own logical (screen) work-area rect,
#     read with glfwGetWindowPos/glfwGetWindowSize - never glfwSetWindowPos/glfwSetWindowSize, so
#     the canvas is never moved or resized per frame (all animation is quad geometry inside it);
#   * pos and size are each clamped into [0, 1] before mapping, so the picture can never be placed
#     outside the window, and the mapped rectangle is converted into a normalized window rect
#     (bottom-left origin: x from left, y from bottom);
#   * that rect is actually CONSUMED by the draw call - the controller passes
#     drawFrame(frameIndex, rectX, rectY, drawW, drawH) to the content, not merely a log of it;
#   * glfwSetWindowOpacity runs only when the value changed (a per-controller last-opacity cache);
#   * the frame is drawn through the window's content and then the window is presented with
#     VFXWindow.present() (which saves and restores the game's GL context around
#     glfwMakeContextCurrent + glfwSwapBuffers); present is documented to run after the game's own
#     present, and the controller owns no raw GL present call of its own;
#   * the controller reads the content and the window it is handed; it does not own either;
#   * never 0x0: the picture rectangle is scaled by the work area and clamped into it.
# It also rejects any loader import (src/client stays loader-agnostic) and any OS-window mutation or
# raw swap. GLSL is never compiled and there is no GPU here, so nothing here claims a pixel or a
# shader works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-control.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowController.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	Write-Error "window control check: missing src\client\java\dev\vfxweaver\client\window\VFXWindowController.java"
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

# 0) the controller is a final per-window owner and stays loader-agnostic.
if ($source -notmatch '(?m)^public final class VFXWindowController \{') {
	$problems.Add("VFXWindowController is not 'public final class'")
}
if ($source -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXWindowController imports a loader package - src/client must stay loader-agnostic")
}

# 1) the exact produced interface (R4 binding order and meaning).
$applySig = 'public void apply(final VFXWindow w, final float posX, final float posY, final float sizeW, final float sizeH, final float opacity, final int frameIndex) {'
if ($source.IndexOf($applySig) -lt 0) {
	$problems.Add("VFXWindowController is missing 'apply(VFXWindow w, float posX, float posY, float sizeW, float sizeH, float opacity, int frameIndex)'")
}

# 2) no OS-window mutation and no raw present: the canvas is never moved, resized or swapped here.
foreach ($forbidden in @(
	'glfwSetWindowPos',
	'glfwSetWindowSize',
	'glfwSetWindowMonitor',
	'glfwCreateWindow',
	'glfwDestroyWindow',
	'glfwFocusWindow',
	'glfwSwapBuffers',
	'glfwMakeContextCurrent',
	'glfwSwapInterval')) {
	if ($source.Contains($forbidden)) {
		$problems.Add("VFXWindowController contains '$forbidden' - the canvas is never moved/resized and present is delegated to VFXWindow.present()")
	}
}

$apply = Get-Body $source $applySig
if ($apply -eq $null) {
	$problems.Add("VFXWindowController has no readable apply(VFXWindow, float, float, float, float, float, int)")
} else {
	# 3) the geometry formula is the free-space work-area mapping, read from the canvas's own rect,
	#    with pos and size clamped into [0, 1].
	Assert-Contains $apply 'GLFW.glfwGetWindowPos(w.handle(), waX, waY);' "apply() does not read the canvas work-area origin (glfwGetWindowPos)"
	Assert-Contains $apply 'GLFW.glfwGetWindowSize(w.handle(), waW, waH);' "apply() does not read the canvas work-area size (glfwGetWindowSize)"
	Assert-Contains $apply 'final float rectW = clampUnit(sizeW);' "apply() does not clamp the picture width into [0, 1]"
	Assert-Contains $apply 'final float rectH = clampUnit(sizeH);' "apply() does not clamp the picture height into [0, 1]"
	Assert-Contains $apply 'final float base = Math.max(1.0F, Math.min(waW[0], waH[0]));' "apply() does not size both axes in one common unit (the smaller work-area side) - size_w:size_h must be the picture's own proportions, not fractions of the monitor's axes"
	Assert-Contains $apply 'final float picW = rectW * base;' "apply() does not scale the picture width by the common base"
	Assert-Contains $apply 'final float picH = rectH * base;' "apply() does not scale the picture height by the common base"
	Assert-Contains $apply 'waX[0] + clampUnit(posX) * Math.max(0.0F, waW[0] - picW)' "apply() is missing the clamped free-space x mapping (waX + clampUnit(posX) * max(0, waW - picW))"
	Assert-Contains $apply 'waY[0] + clampUnit(posY) * Math.max(0.0F, waH[0] - picH)' "apply() is missing the clamped free-space y mapping (waY + clampUnit(posY) * max(0, waH - picH))"

	# 4) the mapped rect is normalized (bottom-left origin) so the content can consume it.
	Assert-Contains $apply 'final float drawW = picW / (float) waW[0];' "apply() does not draw the picture width as the common-base pixel box (drawW = picW / waW) - passing size_w directly would stretch on a non-square monitor"
	Assert-Contains $apply 'final float drawH = picH / (float) waH[0];' "apply() does not draw the picture height as the common-base pixel box (drawH = picH / waH)"
	Assert-Contains $apply 'final float rectX = (picX - waX[0]) / (float) waW[0];' "apply() does not normalize the picture left edge into the canvas rectX"
	Assert-Contains $apply 'final float rectTop = (picY - waY[0]) / (float) waH[0];' "apply() does not normalize the picture top edge into the canvas"
	Assert-Contains $apply 'final float rectY = 1.0F - rectTop - drawH;' "apply() does not flip the top edge into a bottom-left rectY (1 - rectTop - drawH)"

	# 5) opacity is clamped and glfwSetWindowOpacity runs only when the value changed.
	Assert-Contains $apply 'final float clampedOpacity = clampUnit(opacity);' "apply() does not clamp the animated opacity (clampUnit(opacity))"
	Assert-Contains $apply '!this.hasOpacity || clampedOpacity != this.lastOpacity' "apply() does not gate setOpacity on a changed value (a last-opacity cache)"
	Assert-Contains $apply 'w.setOpacity(clampedOpacity);' "apply() does not apply the opacity through the window (w.setOpacity)"
	Assert-Contains $apply 'this.lastOpacity = clampedOpacity;' "apply() does not remember the last opacity - the set-call would repeat every frame"

	# 6) the mapped rect is CONSUMED by the draw call - all four components are passed, then the
	#    window is presented (draw before present). A logged-only rect fails here.
	Assert-Contains $apply 'this.content.drawFrame(frameIndex, rectX, rectY, drawW, drawH);' "apply() does not consume the mapped rect - it must call this.content.drawFrame(frameIndex, rectX, rectY, drawW, drawH)"
	Assert-Contains $apply 'w.present();' "apply() does not present the window (w.present())"
	$drawAt = $apply.IndexOf('this.content.drawFrame(frameIndex, rectX, rectY, drawW, drawH);')
	$presentAt = $apply.IndexOf('w.present();')
	if ($drawAt -ge 0 -and $presentAt -ge 0 -and $drawAt -gt $presentAt) {
		$problems.Add("apply() presents before drawing the frame - the picture would lag by a frame")
	}

	# 7) a closed/destroyed window is a no-op: no GLFW/GL on a dead handle.
	Assert-Contains $apply 'w == null || w.closed()' "apply() does not no-op on a null or closed window"
}

# 8) the opacity clamp is one unit clamp that the animated overshoot is squeezed through.
$clamp = Get-Body $source 'private static float clampUnit(final float value) {'
if ($clamp -eq $null) {
	$problems.Add("VFXWindowController has no readable 'private static float clampUnit(final float value)'")
} else {
	Assert-Contains $clamp 'Math.max(0.0F, Math.min(1.0F, value))' "clampUnit() does not clamp into [0, 1]"
}

# 9) the render-thread-only and post-present rules are documented, because GLFW/GL are not thread-safe.
if ($source -notmatch 'render thread') {
	$problems.Add("VFXWindowController does not document that its GLFW/GL calls are render-thread only")
}
if ($source -notmatch "after the game's own present") {
	$problems.Add("VFXWindowController does not document that present runs after the game's own present")
}

Write-Host "Window control check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window control check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowController is a final loader-agnostic per-window applier"
Write-Host "  apply(VFXWindow, float, float, float, float, float, int) is the produced interface (R4 order)"
Write-Host "  the geometry is the free-space work-area mapping with pos/size clamped into [0, 1]; the canvas is never moved/resized"
Write-Host "  the mapped rect is normalized (bottom-left origin) and CONSUMED by content.drawFrame(frameIndex, rectX, rectY, drawW, drawH)"
Write-Host "  opacity is clamped and glfwSetWindowOpacity runs only when the value changed"
Write-Host "  the frame is drawn through VFXWindowContent.drawFrame, then VFXWindow.present() runs after the game's present"
Write-Host "  a null or closed window is a no-op; no raw swap/context call, no loader type"
Write-Host "Window control check OK."
exit 0

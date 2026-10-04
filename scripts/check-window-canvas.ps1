# Dev-only guard for the borderless transparent click-through canvas window of the custom-windows
# subsystem (Task 2).
#
# VFXWindow is the one place that owns an aux GLFW window, so its shape is pinned here as literal
# text and in the order the setup has to happen:
#   * hidden until ready: GLFW_VISIBLE=false is hinted before glfwCreateWindow, and glfwShowWindow is
#     called only after the context/hints/attribs are set up (R2 - the plan created a hidden window
#     and never showed it);
#   * a pure canvas: undecorated, non-resizable and floating, sized to the pinned monitor work area
#     and never 0x0;
#   * the capability-gated features are gated through VFXWindowPlatform: GLFW_TRANSPARENT_FRAMEBUFFER
#     only when hasTransparentFramebuffer(), GLFW_FOCUS_ON_SHOW=false only when hasFocusOnShow() (the
#     focus-stealing footgun), and GLFW_MOUSE_PASSTHROUGH set as a window ATTRIB /after/ creation and
#     only when hasPassthrough() (the headline requirement - a full-work-area window that swallowed
#     input would block the desktop);
#   * an own GL context: glfwMakeContextCurrent on create with the previously current context saved
#     with glfwGetCurrentContext and restored, and glfwSwapInterval(0) so a foreign vsync cannot stall
#     the render thread;
#   * close() calls glfwDestroyWindow; present() swaps the aux buffers and restores the game's context;
#     setOpacity() clamps and setTitle() sets the title.
# It also rejects the forbidden forms: glfwFocusWindow (never steal focus), glfwSetWindowMonitor (no
# exclusive-fullscreen path), a decorated window, and any loader import (src/client stays
# loader-agnostic). Gradle does not compile GLSL and there is no GPU here, so nothing here claims a
# window works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-canvas.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindow.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	Write-Error "window canvas check: missing src\client\java\dev\vfxweaver\client\window\VFXWindow.java"
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

# 0) the class is a final per-window owner and stays loader-agnostic.
if ($source -notmatch '(?m)^public final class VFXWindow \{') {
	$problems.Add("VFXWindow is not 'public final class'")
}
if ($source -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXWindow imports a loader package - src/client must stay loader-agnostic")
}
if ($source -match 'glfwFocusWindow') {
	$problems.Add("VFXWindow calls glfwFocusWindow - an aux window must never steal the game's focus")
}
if ($source -match 'glfwSetWindowMonitor') {
	$problems.Add("VFXWindow calls glfwSetWindowMonitor - there is no exclusive-fullscreen path")
}
if ($source -match 'GLFW_DECORATED,\s*GLFW_TRUE') {
	$problems.Add("VFXWindow hints a decorated window - the frame cannot be enabled")
}

# 1) create(): the binding signature for the registry's open().
$create = Get-Body $source 'public static VFXWindow create(final String id, final int workX, final int workY, final int workW, final int workH, final String title) {'
if ($create -eq $null) {
	$problems.Add("VFXWindow has no readable 'public static VFXWindow create(final String id, final int workX, final int workY, final int workW, final int workH, final String title)'")
} else {
	# 2) never 0x0, and the window is the work area: the clamp to 1 feeds the create call.
	Assert-Contains $create 'Math.max(1, workW)' "create() does not clamp the width away from 0 (Math.max(1, workW)) - a 0x0 window is forbidden"
	Assert-Contains $create 'Math.max(1, workH)' "create() does not clamp the height away from 0 (Math.max(1, workH)) - a 0x0 window is forbidden"
	Assert-Contains $create 'GLFW.glfwCreateWindow(width, height, title, 0L, 0L)' "create() does not create the window at the work-area size (glfwCreateWindow(width, height, title, 0L, 0L))"

	# 3) hidden at creation, shown only once setup completes (R2): VISIBLE=false hinted before the
	#    create call, glfwShowWindow after it.
	$visibleHint = $create.IndexOf('GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);')
	$createAt = $create.IndexOf('GLFW.glfwCreateWindow(')
	$showAt = $create.IndexOf('GLFW.glfwShowWindow(handle);')
	if ($visibleHint -lt 0) {
		$problems.Add("create() does not hint GLFW_VISIBLE=false before creation")
	} elseif ($createAt -lt 0 -or $visibleHint -gt $createAt) {
		$problems.Add("create() hints GLFW_VISIBLE=false after glfwCreateWindow - the window must be created hidden")
	}
	if ($showAt -lt 0) {
		$problems.Add("create() never calls glfwShowWindow(handle) - a hidden window would never appear (R2)")
	} elseif ($createAt -lt 0 -or $showAt -le $createAt) {
		$problems.Add("create() calls glfwShowWindow before glfwCreateWindow - the window must be shown only after setup completes")
	}

	# 4) the canvas hints: undecorated, non-resizable, floating, with focus-on-show off only when the
	#    platform has it.
	foreach ($hint in @(
		'GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);',
		'GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);',
		'GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);')) {
		Assert-Contains $create $hint "create() is missing the canvas hint: $hint"
	}
	if (-not $create.Contains('if (VFXWindowPlatform.hasFocusOnShow())') -or -not $create.Contains('GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);')) {
		$problems.Add("create() does not gate GLFW_FOCUS_ON_SHOW=false on VFXWindowPlatform.hasFocusOnShow() - the default focus-on-show steals focus from the game")
	}
	if (-not $create.Contains('if (VFXWindowPlatform.hasTransparentFramebuffer())') -or -not $create.Contains('GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);')) {
		$problems.Add("create() does not gate GLFW_TRANSPARENT_FRAMEBUFFER on VFXWindowPlatform.hasTransparentFramebuffer()")
	}

	# 5) clicks pass through: the attribute is set /after/ creation, gated on hasPassthrough(). A hint
	#    form would be silently ignored (GLFW_MOUSE_PASSTHROUGH is an attribute).
	$passthrough = $create.IndexOf('GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);')
	if (-not $create.Contains('if (VFXWindowPlatform.hasPassthrough())') -or $passthrough -lt 0) {
		$problems.Add("create() does not set GLFW_MOUSE_PASSTHROUGH on the handle under VFXWindowPlatform.hasPassthrough() - passthrough is mandatory")
	} elseif ($createAt -ge 0 -and $passthrough -lt $createAt) {
		$problems.Add("create() sets GLFW_MOUSE_PASSTHROUGH before the window exists - it is a window attribute, set after creation")
	}

	# 6) the monitor is pinned by the work-area position the registry computed (read-only contact with
	#    the game window's monitor; the canvas is never moved per frame).
	Assert-Contains $create 'GLFW.glfwSetWindowPos(handle, workX, workY);' "create() does not position the window at the pinned work area (glfwSetWindowPos(handle, workX, workY))"

	# 7) own GL context: make it current to set the swap interval, then restore whatever was current.
	foreach ($literal in @(
		'final long previous = GLFW.glfwGetCurrentContext();',
		'GLFW.glfwMakeContextCurrent(handle);',
		'GLFW.glfwMakeContextCurrent(previous);')) {
		Assert-Contains $create $literal "create() does not save/restore the GL context: $literal"
	}
	Assert-Contains $create 'GLFW.glfwSwapInterval(0);' "create() does not set glfwSwapInterval(0) on the aux context - a foreign vsync can halve the game's FPS"
	# LWJGL resolves GL entry points per context: the game loaded them for its own context and this
	# fresh one has none until createCapabilities runs. Without it the first GL11 call aborts the JVM
	# ("No context is current or a function that is not available"); the game's caps are restored.
	Assert-Contains $create 'GL.createCapabilities()' "create() does not load the aux context's GL capabilities (GL.createCapabilities) - every GL call would abort"
	Assert-Contains $create 'GLCapabilities' "create() does not keep the aux context's GLCapabilities for the GL callers"
	Assert-Contains $create 'GL.setCapabilities(previousCaps);' "create() does not restore the game's GL capabilities after loading the aux ones"
}

# 8) the accessors the later tasks consume.
foreach ($signature in @(
	'public long handle() {',
	'public void setTitle(final String title) {',
	'public void setOpacity(final float opacity) {',
	'public void present() {',
	'public void close() {')) {
	if ($source.IndexOf($signature) -lt 0) {
		$problems.Add("VFXWindow is missing the accessor '$($signature.TrimEnd(' {'))'")
	}
}

# 9) present() swaps the aux buffers with the game's context save/restored around it.
$present = Get-Body $source 'public void present() {'
if ($present -ne $null) {
	Assert-Contains $present 'GLFW.glfwGetCurrentContext()' "present() does not read the previously current context (glfwGetCurrentContext)"
	Assert-Contains $present 'GLFW.glfwMakeContextCurrent(this.handle);' "present() does not make the aux context current"
	Assert-Contains $present 'GLFW.glfwSwapBuffers(this.handle);' "present() does not swap the aux buffers"
	Assert-Contains $present 'GLFW.glfwMakeContextCurrent(previous);' "present() does not restore the previously current context"
}

# 10) close() releases the OS window.
$close = Get-Body $source 'public void close() {'
if ($close -eq $null) {
	$problems.Add("VFXWindow has no readable 'public void close()'")
} else {
	Assert-Contains $close 'GLFW.glfwDestroyWindow(this.handle);' "close() does not call glfwDestroyWindow on its handle - the OS window would leak"
}

# 11) the two setters do their job: opacity is clamped into [0, 1] and the title is set on the handle.
$opacity = Get-Body $source 'public void setOpacity(final float opacity) {'
if ($opacity -eq $null) {
	$problems.Add("VFXWindow has no readable 'public void setOpacity(final float opacity)'")
} else {
	Assert-Contains $opacity 'GLFW.glfwSetWindowOpacity(this.handle' "setOpacity() does not call glfwSetWindowOpacity"
	Assert-Contains $opacity 'Math.max(0.0F, Math.min(1.0F, opacity))' "setOpacity() does not clamp into [0, 1]"
}
$title = Get-Body $source 'public void setTitle(final String title) {'
if ($title -eq $null) {
	$problems.Add("VFXWindow has no readable 'public void setTitle(final String title)'")
} else {
	Assert-Contains $title 'GLFW.glfwSetWindowTitle(this.handle' "setTitle() does not call glfwSetWindowTitle"
}

# 12) the render-thread-only rule is documented, because GLFW is not thread-safe.
if ($source -notmatch 'render thread') {
	$problems.Add("VFXWindow does not document that its GLFW/GL calls are render-thread only")
}

Write-Host "Window canvas check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window canvas check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindow is a final loader-agnostic per-window owner"
Write-Host "  create() is the binding 'create(String id, int workX, int workY, int workW, int workH, String title)' and clamps away 0x0"
Write-Host "  the canvas is hidden at creation (GLFW_VISIBLE=false), undecorated/non-resizable/floating, transparent and focus-on-show off only when the gate allows, and shown once setup completes"
Write-Host "  GLFW_MOUSE_PASSTHROUGH is set as a window attribute after creation, under VFXWindowPlatform.hasPassthrough()"
Write-Host "  the own GL context is made current, glfwSwapInterval(0) is set, and glfwGetCurrentContext()/glfwMakeContextCurrent restore the game's context"
Write-Host "  present() swaps the aux buffers and restores the previous context; close() calls glfwDestroyWindow; setOpacity() clamps; setTitle() titles"
Write-Host "Window canvas check OK."
exit 0

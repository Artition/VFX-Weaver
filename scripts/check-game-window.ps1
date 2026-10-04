# Dev-only guard for the reserved window id "0" - the Minecraft game window itself (Task 9).
#
# This task reverses the earlier spec rule "never modifies the game window": window id "0" is
# RESERVED and drives the real game window instead of opening an aux canvas. The routing belongs in
# VFXWindowManager (it owns the id -> window binding) and the driving itself in VFXGameWindow. Both
# shapes are pinned here as literal text:
#   * VFXWindowManager.GAME_WINDOW_ID is the literal "0"; open() binds a VFXGameWindow for that id
#     and never touches VFXWindowRegistry for it, the close path (reconcile's owner sweep and
#     closeAll) skips it, and apply() hands the frame's geometry to VFXGameWindow instead of the
#     controller. Every other id keeps the aux-window path unchanged.
#   * the geometry only reaches the game window when the driving effect DECLARES one of the four
#     geometry params (pos_x/pos_y/size_w/size_h, in the definition or live-set as an override), so a
#     title-only id "0" effect never moves the game window;
#   * VFXGameWindow maps the four 0..1 params into the monitor work area pinned at capture: pos_x/pos_y
#     place the window's CENTRE at that fraction of the work area and size_w/size_h grow/shrink evenly
#     about it, so a resize is symmetric and never pins a corner (the whole point of a window resize);
#     when the id "0" creator opened (the aux canvas pinning rule - it must not follow the window it
#     is resizing, that would feed back into itself);
#   * the resize is split by mode: while FULLSCREEN the size is left through Minecraft's own
#     setWindowed(w, h) (javap -c shows it writes windowedWidth/Height, clears the fullscreen flag and
#     calls the private setMode(), whose windowed branch calls
#     glfwSetWindowMonitor(handle, 0L, x, y, w, h, GLFW_DONT_CARE) on all three nodes), because going
#     through the game keeps its fullscreen flag truthful and the next F11 toggle correct; once
#     WINDOWED the size is raw GLFW glfwSetWindowSize on handle(), because setWindowed only writes the
#     cached windowed size and does NOT resize a window that is already windowed - so a continuous
#     windowed shrink/move (the whole point of id "0") needs glfwSetWindowSize;
#   * the position push is raw GLFW glfwSetWindowPos on handle(), because javap shows Window has no
#     position setter on any node; it runs only when the target differs from Window.getX()/getY(),
#     which the glfwSetWindowPosCallback MC registers keeps live;
#   * never 0x0: the size is clamped into MIN_WIDTH x MIN_HEIGHT and into the work area;
#   * the game window stays an ORDINARY window: the driver may not create, destroy, monitor-switch,
#     show, focus, decorate, float, fade or retitle it - no glfwCreateWindow / glfwDestroyWindow /
#     glfwSetWindowMonitor / glfwSetWindowAttrib / glfwShowWindow / glfwSetWindowOpacity / setTitle /
#     setOpacity anywhere in it (glfwSetWindowSize and glfwSetWindowPos are the two allowed calls);
#   * Monitor.getMonitor() (26.1.2, 1.21.11) vs Monitor.monitor() (26.2) is the one real per-node API
#     split on this path, so it is guarded in place.
# The stop behaviour is pinned too: dropping the id "0" binding must NOT restore or move the window -
# the window stays where the effect left it (no restore call, no restore field).
# It also rejects any loader import (src/client stays loader-agnostic). GLSL is never compiled and
# there is no GPU here, so nothing here claims a pixel or a shader works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-game-window.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$driverPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXGameWindow.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowManager.java"
$docPath = Join-Path $repoRoot "docs\guide\effects\window\custom-windows.md"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($path in @($managerPath, $docPath)) {
	if (-not (Test-Path -LiteralPath $path)) {
		Write-Error "game window check: missing $path"
		exit 1
	}
}
if (-not (Test-Path -LiteralPath $driverPath)) {
	Write-Error "game window check: missing src\client\java\dev\vfxweaver\client\window\VFXGameWindow.java"
	exit 1
}
$driver = [System.IO.File]::ReadAllText($driverPath)
$manager = [System.IO.File]::ReadAllText($managerPath)
$doc = [System.IO.File]::ReadAllText($docPath)
# The forbidden-call and restore checks look at code, not at the javadoc that documents which GLFW
# calls Window itself makes - a token in a comment is documentation, a token in code is a call.
$driverCode = [regex]::Replace([regex]::Replace($driver, '(?s)/\*.*?\*/', ''), '(?m)//.*$', '')

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

# 0) the driver is a final per-bind owner and stays loader-agnostic.
if ($driver -notmatch '(?m)^public final class VFXGameWindow \{') {
	$problems.Add("VFXGameWindow is not 'public final class'")
}
if ($driver -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXGameWindow imports a loader package - src/client must stay loader-agnostic")
}
if ($manager -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXWindowManager imports a loader package - src/client must stay loader-agnostic")
}

# 1) the game window stays an ORDINARY Minecraft window: nothing here creates, destroys, resizes,
#    monitor-switches, shows, focuses, decorates, floats or retitles it.
foreach ($forbidden in @(
	'glfwCreateWindow',
	'glfwDestroyWindow',
	'glfwSetWindowMonitor',
	'glfwSetWindowAttrib',
	'glfwShowWindow',
	'glfwFocusWindow',
	'glfwSetWindowOpacity',
	'glfwMaximizeWindow',
	'window.setTitle(',
	'window.setOpacity(')) {
	if ($driverCode.Contains($forbidden)) {
		$problems.Add("VFXGameWindow contains '$forbidden' - the game window must stay an ordinary decorated Minecraft window")
	}
}

# 2) the reserved id is the literal "0" and the manager routes it to the game driver.
if ($manager -notmatch 'public static final String GAME_WINDOW_ID = "0";') {
	$problems.Add("VFXWindowManager is missing the reserved id constant 'public static final String GAME_WINDOW_ID = " + '"0"' + ";'")
}
if ($manager -notmatch 'VFXGameWindow\.capture\(\)') {
	$problems.Add("VFXWindowManager does not capture a VFXGameWindow for the reserved id")
}
if ($manager -notmatch 'GAME_WINDOW_ID\.equals\(name\)') {
	$problems.Add("VFXWindowManager does not route on the reserved id (GAME_WINDOW_ID.equals(name))")
}

# 3) open() binds the driver for id "0" instead of an aux window, and the aux path is untouched.
$openSig = 'private void open(final String name, final VFXActiveEffect effect) {'
$openBody = Get-Body $manager $openSig
if ($openBody -eq $null) {
	$problems.Add("VFXWindowManager has no readable open(String, VFXActiveEffect)")
} else {
	Assert-Contains $openBody 'if (GAME_WINDOW_ID.equals(name)) {' "open() does not take the reserved id before the aux path"
	Assert-Contains $openBody 'VFXGameWindow.capture()' "open() does not capture the game-window driver for the reserved id"
	Assert-Contains $openBody 'VFXWindowRegistry.get().open(' "open() lost the aux-window registry open"
}

# 4) the close side skips the reserved id in both places that close aux windows, and never restores
#    the game window: dropping the binding leaves it exactly where the effect put it.
$reconcileSig = 'public void reconcile(final List<VFXActiveEffect> active) {'
$reconcile = Get-Body $manager $reconcileSig
if ($reconcile -eq $null) {
	$problems.Add("VFXWindowManager has no readable reconcile(List<VFXActiveEffect>)")
} else {
	Assert-Contains $reconcile 'if (!GAME_WINDOW_ID.equals(name)) {' "reconcile()'s owner sweep does not skip the reserved id - it would close a non-existent aux window named '0'"
	Assert-Contains $reconcile 'VFXWindowRegistry.get().close(name);' "reconcile() does not close the aux windows"
}
$closeAllSig = 'public void closeAll() {'
$closeAll = Get-Body $manager $closeAllSig
if ($closeAll -eq $null) {
	$problems.Add("VFXWindowManager has no readable closeAll()")
} else {
	Assert-Contains $closeAll 'if (GAME_WINDOW_ID.equals(name)) {' "closeAll() does not skip the reserved id - quitting would warn about a missing aux window named '0'"
}
if ($driverCode -match 'restore|Restore') {
	$problems.Add("VFXGameWindow restores the game window on stop - a stop must leave it where it is")
}

# 5) apply() drives the game window from the same four params and only when geometry is declared.
$applySig = 'public void apply() {'
$applyBody = Get-Body $manager $applySig
if ($applyBody -eq $null) {
	$problems.Add("VFXWindowManager has no readable apply()")
} else {
	Assert-Contains $applyBody 'if (GAME_WINDOW_ID.equals(name)) {' "apply() does not branch on the reserved id"
	Assert-Contains $applyBody 'declaresGeometry(effect)' "apply() does not gate the game window on the effect declaring a geometry"
	Assert-Contains $applyBody 'binding.gameWindow().apply(posX, posY, sizeW, sizeH);' "apply() does not hand the four geometry params to VFXGameWindow.apply(posX, posY, sizeW, sizeH)"
	Assert-Contains $applyBody 'binding.controller().apply(binding.window(), posX, posY, sizeW, sizeH, opacity, frame);' "apply() lost the aux-window controller call for every other id"
	$declares = Get-Body $manager 'private static boolean declaresGeometry(final VFXActiveEffect effect) {'
	if ($declares -eq $null) {
		$problems.Add("VFXWindowManager has no readable declaresGeometry(VFXActiveEffect)")
	} else {
		Assert-Contains $declares 'effect.getTimeline().getValues().containsKey(param)' "declaresGeometry() does not look at the definition's declared params"
		Assert-Contains $declares 'effect.getTimeline().getOverrideNames().contains(param)' "declaresGeometry() does not look at live overrides (/vfx set on an undeclared param)"
	}
}

# 6) the driver: the shared work-area free-space formula, the minimum size, fullscreen out first,
#    then the raw position push on handle() (Window has no position setter on any node).
$driverApplySig = 'public void apply(final float posX, final float posY, final float sizeW, final float sizeH) {'
$driverApply = Get-Body $driver $driverApplySig
if ($driverApply -eq $null) {
	$problems.Add("VFXGameWindow has no readable apply(float, float, float, float)")
} else {
	Assert-Contains $driverApply 'final Window window = Minecraft.getInstance().getWindow();' "apply() does not read the Minecraft game window"
	Assert-Contains $driverApply 'final int width = size(clampUnit(sizeW), this.area[2], MIN_WIDTH);' "apply() does not size the width from size_w over the pinned work area with the MIN_WIDTH floor"
	Assert-Contains $driverApply 'final int height = size(clampUnit(sizeH), this.area[3], MIN_HEIGHT);' "apply() does not size the height from size_h over the pinned work area with the MIN_HEIGHT floor"
	Assert-Contains $driverApply 'final int x = center(this.area[0], this.area[2], clampUnit(posX), width);' "apply() does not place the window by its CENTRE (center(wa.x, wa.w, clampUnit(pos_x), width))"
	Assert-Contains $driverApply 'final int y = center(this.area[1], this.area[3], clampUnit(posY), height);' "apply() does not place the window by its CENTRE (center(wa.y, wa.h, clampUnit(pos_y), height))"
	Assert-Contains $driverApply 'if (window.isFullscreen()) {' "apply() does not check the game window's fullscreen state"
	Assert-Contains $driverApply 'window.setWindowed(width, height);' "apply() does not leave exclusive fullscreen through the game's own setWindowed(width, height)"
	Assert-Contains $driverApply 'else if (window.getWidth() != width || window.getHeight() != height) {' "apply() does not resize an already-windowed game window (setWindowed only writes the field, so a windowed shrink needs glfwSetWindowSize)"
	Assert-Contains $driverApply 'GLFW.glfwSetWindowSize(window.handle(), width, height);' "apply() does not resize the game window with raw GLFW on handle() when it is already windowed"
	Assert-Contains $driverApply 'if (window.getX() != x || window.getY() != y) {' "apply() does not gate the move on the window already being at the target (a per-frame SetWindowPos)"
	Assert-Contains $driverApply 'GLFW.glfwSetWindowPos(window.handle(), x, y);' "apply() does not move the game window with raw GLFW on handle() - Window has no position setter"
	$fullscreenAt = $driverApply.IndexOf('if (window.isFullscreen()) {')
	$windowedAt = $driverApply.IndexOf('window.setWindowed(width, height);')
	$moveAt = $driverApply.IndexOf('GLFW.glfwSetWindowPos(window.handle(), x, y);')
	if ($fullscreenAt -ge 0 -and $windowedAt -ge 0 -and $moveAt -ge 0 -and -not ($fullscreenAt -lt $windowedAt -and $windowedAt -lt $moveAt)) {
		$problems.Add("apply() must leave fullscreen (isFullscreen -> setWindowed) BEFORE the position push")
	}
}

# 7) never 0x0: the size helper clamps into the minimum and into the work area.
$size = Get-Body $driver 'private static int size(final float fraction, final int extent, final int minimum) {'
if ($size -eq $null) {
	$problems.Add("VFXGameWindow has no readable 'private static int size(float, int, int)'")
} else {
	Assert-Contains $size 'Math.min(extent, Math.max(minimum, Math.round(fraction * extent)))' "size() does not clamp the request into [minimum, work area] - the game window could be driven to 0x0"
}
# 7b) a resize is anchored on the window's CENTRE, not a corner: the origin is the centre minus half
#     the size, clamped back into the work area.
$center = Get-Body $driver 'private static int center(final int origin, final int extent, final float fraction, final int size) {'
if ($center -eq $null) {
	$problems.Add("VFXGameWindow has no readable 'private static int center(int, int, float, int)' - a resize must be anchored on the window's centre")
} else {
	Assert-Contains $center 'final int centre = origin + Math.round(fraction * extent);' "center() does not place the window centre at the 0..1 fraction of the work area"
	Assert-Contains $center 'centre - size / 2' "center() does not split the size evenly about the centre - a resize would pin a corner"
	Assert-Contains $center 'Math.max(origin, Math.min(origin + extent - size, centre - size / 2))' "center() does not clamp the window back inside the work area"
}
$clamp = Get-Body $driver 'private static float clampUnit(final float value) {'
if ($clamp -eq $null) {
	$problems.Add("VFXGameWindow has no readable 'private static float clampUnit(final float value)'")
} else {
	Assert-Contains $clamp 'Math.max(0.0F, Math.min(1.0F, value))' "clampUnit() does not clamp into [0, 1]"
}
if ($driver -notmatch 'MIN_WIDTH = 320;') {
	$problems.Add("VFXGameWindow does not declare the MIN_WIDTH floor")
}
if ($driver -notmatch 'MIN_HEIGHT = 240;') {
	$problems.Add("VFXGameWindow does not declare the MIN_HEIGHT floor")
}

# 8) the work area is the monitor's, pinned into a final field at capture - it must not be the rect of
#    the window being resized (that is a feedback loop: size 0.5 of the current window, every frame).
if ($driver -notmatch 'private final int\[\] area;') {
	$problems.Add("VFXGameWindow does not pin the work area into a final field (it would follow the window it resizes)")
}
$workArea = Get-Body $driver 'private static int[] workArea() {'
if ($workArea -eq $null) {
	$problems.Add("VFXGameWindow has no readable workArea()")
} else {
	Assert-Contains $workArea 'window.findBestMonitor()' "workArea() does not resolve the monitor the game window is on"
	Assert-Contains $workArea 'GLFW.glfwGetMonitorWorkarea(handle, x, y, width, height);' "workArea() does not read the monitor work area"
	Assert-Contains $workArea 'window.getScreenWidth()' "workArea() has no fallback to the game window's screen size"
}

# 9) the one per-node API split on this path is guarded in place.
if ($driver -notmatch 'monitor\.getMonitor\(\)' -or $driver -notmatch 'monitor\.monitor\(\)') {
	$problems.Add("VFXGameWindow does not carry both sides of the per-node Monitor split (getMonitor on <26.2, monitor on >=26.2)")
}
if ($driver -notmatch '//\? if <26\.2 \{') {
	$problems.Add("VFXGameWindow does not guard the per-node Monitor split with a Stonecutter guard")
}

# 10) the render-thread-only rule is documented, because GLFW and the game's window mode are not
#     thread-safe and the game's own window may only be touched on the render thread.
if ($driver -notmatch 'render thread') {
	$problems.Add("VFXGameWindow does not document that its GLFW calls are render-thread only")
}
if ($driver -notmatch "after the game's own present") {
	$problems.Add("VFXGameWindow does not document that it runs after the game's own present")
}

# 11) the guide documents the reserved id, its override of the aux behaviour, the ordinary window and
#     the leave-it-where-it-is stop.
if (-not $doc.Contains('`"0"`')) {
	$problems.Add('custom-windows.md does not document the reserved id `"0"`')
}
if ($doc -notmatch 'nothing about the Minecraft\s+window changes') {
	$problems.Add("custom-windows.md no longer states the aux-window 'nothing about the Minecraft window changes' rule")
}
if ($doc -notmatch 'leaves it where it is') {
	$problems.Add("custom-windows.md does not document that stopping the effect leaves the game window where it is")
}
if ($doc -notmatch 'check-game-window\.ps1') {
	$problems.Add("custom-windows.md does not list check-game-window.ps1 among the guards")
}

Write-Host "Game window check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "game window check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  the reserved id is the literal '0' and VFXWindowManager routes it to VFXGameWindow, never to the aux registry"
Write-Host "  every other id keeps the aux path: registry open/close, controller apply, prune"
Write-Host "  the game window is only driven when the effect declares pos_x/pos_y/size_w/size_h (declared or live override)"
Write-Host "  the four params map into the pinned work area: pos is the window CENTRE, size grows/shrinks evenly about it"
Write-Host "  fullscreen is left first through Window.setWindowed (the game's own path), then glfwSetWindowPos pushes the position"
Write-Host "  never 0x0: the size is clamped into 320x240 and into the work area"
Write-Host "  the game window stays ordinary: only glfwSetWindowSize/glfwSetWindowPos on handle(); no create/destroy/monitor/show/focus/attribute/title/opacity call"
Write-Host "  Monitor.getMonitor() (<26.2) vs Monitor.monitor() (>=26.2) is guarded in place"
Write-Host "  a stop drops the binding and leaves the window where it is - no restore"
Write-Host "  the guide documents the reserved id, the ordinary window and the stop behaviour"
Write-Host "Game window check OK."
exit 0
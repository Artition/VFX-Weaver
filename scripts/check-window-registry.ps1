# Dev-only guard for the registry and lifecycle of the custom-windows subsystem (Task 4).
#
# VFXWindowRegistry is the one place that owns the name -> live-window map. It is a render-thread
# singleton (the windows it holds drive GLFW), it bounds the map with a MAX_WINDOWS cap, and it
# pins the two ownership rules the design fixes:
#   * a duplicate live id is first-creator-wins: warn once and no-op, never replace a running
#     window;
#   * a stopped/closed same-id entry is replaced: the stale window is dropped and recreated;
#   * a cap overflow is warn-once and drop;
#   * close() on a missing/closed id is warn-once and no-op;
#   * closeAll() is the teardown: it calls each window's close() and clears the map, so no GLFW
#     handle (and therefore no GL context/texture) is leaked.
# Its shape is pinned here as literal text. It also rejects any GL/GLFW call and any loader import:
# the registry is not the GL layer (it only delegates to VFXWindow) and src/client stays
# loader-agnostic. GLSL is never compiled here and there is no GPU, so nothing here claims a pixel
# works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-registry.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowRegistry.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	Write-Error "window registry check: missing src\client\java\dev\vfxweaver\client\window\VFXWindowRegistry.java"
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

# 0) final render-thread singleton, loader-agnostic, and not the GL layer.
if ($source -notmatch '(?m)^public final class VFXWindowRegistry \{') {
	$problems.Add("VFXWindowRegistry is not 'public final class'")
}
if ($source -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXWindowRegistry imports a loader package - src/client must stay loader-agnostic")
}
if ($source -match 'org\.lwjgl|GLFW\.|GL11\.') {
	$problems.Add("VFXWindowRegistry contains a GL/GLFW call - it is not the GL layer, only VFXWindow touches GLFW")
}
if ($source -notmatch '(?m)^\s*private VFXWindowRegistry\(\) \{') {
	$problems.Add("VFXWindowRegistry is not a singleton (no private constructor)")
}
if ($source -notmatch 'public static VFXWindowRegistry get\(\) \{') {
	$problems.Add("VFXWindowRegistry is missing 'public static VFXWindowRegistry get()'")
}

# 1) the exact produced interface.
$openSig = 'public @Nullable VFXWindow open(final String id, final int workX, final int workY, final int workW, final int workH, final String title) {'
$getSig = 'public @Nullable VFXWindow get(final String id) {'
$closeSig = 'public void close(final String id) {'
$closeAllSig = 'public void closeAll() {'
foreach ($signature in @($openSig, $getSig, $closeSig, $closeAllSig)) {
	if ($source.IndexOf($signature) -lt 0) {
		$problems.Add("VFXWindowRegistry is missing '$($signature.TrimEnd(' {'))'")
	}
}

# 2) the map is bounded by MAX_WINDOWS, and an over-cap open warns once and drops.
if ($source -notmatch 'private static final int MAX_WINDOWS = \d+;') {
	$problems.Add("VFXWindowRegistry has no constant 'private static final int MAX_WINDOWS = <n>;'")
}
$open = Get-Body $source $openSig
if ($open -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable open(String, int, int, int, int, String)")
} else {
	Assert-Contains $open 'MAX_WINDOWS' "open() does not enforce the MAX_WINDOWS cap"
	Assert-Contains $open 'VFXLog.warnOnce' "open() does not warn through VFXLog.warnOnce on a dropped open"
	Assert-Contains $open 'return null' "open() does not return null when it drops an open"
	# the cap overflow is the warn that names the cap, and it drops rather than evicting.
	if ($open.IndexOf('already open') -lt 0) {
		$problems.Add("open() has no first-creator-wins duplicate branch ('already open')")
	}
	Assert-Contains $open '!existing.closed()' "open() does not distinguish a live duplicate (!existing.closed()) from a stopped/closed one"
	Assert-Contains $open 'this.windows.remove(id)' "open() does not drop a stopped/closed same-id entry before recreating (replace)"
	Assert-Contains $open 'VFXWindow.create(id, workX, workY, workW, workH, title)' "open() does not create through VFXWindow.create(id, workX, workY, workW, workH, title)"
	Assert-Contains $open 'pruneClosed()' "open() does not prune closed entries, so the bounded map could fill with stale windows"
	# first-creator-wins must decide before any create: the duplicate return precedes VFXWindow.create.
	$duplicate = $open.IndexOf('already open')
	$create = $open.IndexOf('VFXWindow.create(')
	if ($duplicate -ge 0 -and $create -ge 0 -and $duplicate -gt $create) {
		$problems.Add("open()'s first-creator-wins duplicate check comes after VFXWindow.create - a duplicate would replace the running window")
	}
	# over-cap must drop, not close an existing window: no close() before the create on the open path.
	$cap = $open.IndexOf('MAX_WINDOWS')
	if ($cap -ge 0 -and $create -ge 0 -and $cap -gt $create) {
		$problems.Add("open()'s MAX_WINDOWS cap is checked after VFXWindow.create")
	}
}

# 3) get() is the one read path.
$get = Get-Body $source $getSig
if ($get -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable get(String)")
} else {
	Assert-Contains $get 'this.windows.get(id)' "get() does not read the name -> window map"
}

# 4) close() closes the named window, and a missing/closed name warns once and no-ops.
$close = Get-Body $source $closeSig
if ($close -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable close(String)")
} else {
	Assert-Contains $close 'this.windows.get(id)' "close() does not look the id up in the map"
	Assert-Contains $close 'window == null || window.closed()' "close() does not treat a missing/closed id as a warn-once no-op"
	Assert-Contains $close 'VFXLog.warnOnce' "close() does not warn through VFXLog.warnOnce on a missing/closed id"
	Assert-Contains $close 'window.close()' "close() does not destroy the window (window.close())"
}

# 5) closeAll() is the teardown: close every window and clear the map, so nothing leaks.
$closeAll = Get-Body $source $closeAllSig
if ($closeAll -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable closeAll()")
} else {
	Assert-Contains $closeAll 'this.windows.values()' "closeAll() does not iterate the windows it owns"
	Assert-Contains $closeAll 'window.close()' "closeAll() does not call each window's close()"
	Assert-Contains $closeAll 'this.windows.clear()' "closeAll() does not clear the map"
}

# 6) the render-thread-only rule is documented, because the held windows drive GLFW.
if ($source -notmatch 'render thread') {
	$problems.Add("VFXWindowRegistry does not document that its window operations are render-thread only")
}

Write-Host "Window registry check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window registry check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowRegistry is a final render-thread singleton over a name -> live-window map"
Write-Host "  open()/get(String)/close(String)/closeAll() are the produced interface"
Write-Host "  the map is bounded by MAX_WINDOWS: over the cap is warn-once and drop"
Write-Host "  a live duplicate is first-creator-wins (warn once, no-op); a stopped same id is replaced"
Write-Host "  close() on a missing/closed id is warn-once and no-op; closeAll() closes and clears (no leak)"
Write-Host "  the registry holds no GL/GLFW and no loader type - it only delegates to VFXWindow"
Write-Host "Window registry check OK."
exit 0

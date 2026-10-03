# Dev-only guard for the registry and lifecycle of the custom-windows subsystem (Task 4).
#
# VFXWindowRegistry is the one place that owns the name -> window map. It is a render-thread
# singleton (the windows it holds drive GLFW), it bounds the map with a pinned MAX_WINDOWS cap, and
# it pins the ownership rules the design fixes:
#   * a duplicate live id is first-creator-wins: warn once and no-op, never replace a running
#     window;
#   * a stopped same-id entry is replaced: the stale window is dropped and recreated;
#   * a cap overflow is warn-once and drop, never an eviction of a running window;
#   * get() still reports a stopped window (its methods are no-ops once closed), so a controller
#     can see a "closed name" and no-op; prune() is what sweeps those stopped entries, on a datapack
#     reload or a client dispose;
#   * close() on a missing/closed id is warn-once and no-op;
#   * prune() is public (the brief's interface) and drops the dead entries.
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

# 1) the exact produced interface, including the public prune() the brief lists (and no closeAll).
$openSig = 'public @Nullable VFXWindow open(final String id, final int workX, final int workY, final int workW, final int workH, final String title) {'
$getSig = 'public @Nullable VFXWindow get(final String id) {'
$closeSig = 'public void close(final String id) {'
$pruneSig = 'public void prune() {'
foreach ($signature in @($openSig, $getSig, $closeSig, $pruneSig)) {
	if ($source.IndexOf($signature) -lt 0) {
		$problems.Add("VFXWindowRegistry is missing '$($signature.TrimEnd(' {'))'")
	}
}
if ($source.Contains('closeAll')) {
	$problems.Add("VFXWindowRegistry still exposes closeAll - the brief lists prune(), not closeAll")
}

# 2) the map is bounded by a pinned MAX_WINDOWS, and an over-cap open warns once and drops.
if ($source -notmatch 'private static final int MAX_WINDOWS = 8;') {
	$problems.Add("VFXWindowRegistry does not pin 'private static final int MAX_WINDOWS = 8;'")
}
$open = Get-Body $source $openSig
if ($open -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable open(String, int, int, int, int, String)")
} else {
	Assert-Contains $open 'MAX_WINDOWS' "open() does not enforce the MAX_WINDOWS cap"
	Assert-Contains $open 'VFXLog.warnOnce' "open() does not warn through VFXLog.warnOnce on a dropped open"
	Assert-Contains $open 'return null' "open() does not return null when it drops an open"
	# first-creator-wins: a running same-id window is never replaced.
	if ($open.IndexOf('already open') -lt 0) {
		$problems.Add("open() has no first-creator-wins duplicate branch ('already open')")
	}
	Assert-Contains $open '!existing.closed()' "open() does not distinguish a live duplicate (!existing.closed()) from a stopped one"
	# replace: a stopped same-id entry is dropped before the recreate.
	Assert-Contains $open 'this.windows.remove(id)' "open() does not drop a stopped same-id entry before recreating (replace)"
	# prune wiring: open sweeps the dead entries before its own create.
	Assert-Contains $open 'prune()' "open() is not wired to prune()"
	Assert-Contains $open 'VFXWindow.create(id, workX, workY, workW, workH, title)' "open() does not create through VFXWindow.create(id, workX, workY, workW, workH, title)"
	# ordering: the duplicate return precedes any create; the cap and prune precede the create.
	$duplicate = $open.IndexOf('already open')
	$create = $open.IndexOf('VFXWindow.create(')
	$cap = $open.IndexOf('MAX_WINDOWS')
	$prune = $open.IndexOf('prune()')
	if ($duplicate -ge 0 -and $create -ge 0 -and $duplicate -gt $create) {
		$problems.Add("open()'s first-creator-wins duplicate check comes after VFXWindow.create - a duplicate would replace the running window")
	}
	if ($cap -ge 0 -and $create -ge 0 -and $cap -gt $create) {
		$problems.Add("open()'s MAX_WINDOWS cap is checked after VFXWindow.create")
	}
	if ($prune -ge 0 -and $create -ge 0 -and $prune -gt $create) {
		$problems.Add("open()'s prune() runs after VFXWindow.create")
	}
}

# 3) get() reports a stopped window too (no closed() filter), so a controller can no-op on it.
$get = Get-Body $source $getSig
if ($get -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable get(String)")
} else {
	Assert-Contains $get 'this.windows.get(id)' "get() does not read the name -> window map"
	if ($get.Contains('closed()')) {
		$problems.Add("get() filters out a stopped window - a controller must be able to see a closed name (its methods are no-ops once closed)")
	}
}

# 4) close() closes the named window and leaves its stopped entry for prune(); a missing/closed id
#    is warn-once and no-op.
$close = Get-Body $source $closeSig
if ($close -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable close(String)")
} else {
	Assert-Contains $close 'this.windows.get(id)' "close() does not look the id up in the map"
	Assert-Contains $close 'window == null || window.closed()' "close() does not treat a missing/closed id as a warn-once no-op"
	Assert-Contains $close 'VFXLog.warnOnce' "close() does not warn through VFXLog.warnOnce on a missing/closed id"
	Assert-Contains $close 'window.close()' "close() does not destroy the window (window.close())"
	if ($close.Contains('this.windows.remove')) {
		$problems.Add("close() removes the entry - it must leave the stopped entry for prune() (and for get())")
	}
}

# 5) prune() is the sweep: it drops the stopped entries; it is public.
$prune = Get-Body $source $pruneSig
if ($prune -eq $null) {
	$problems.Add("VFXWindowRegistry has no readable prune()")
} else {
	Assert-Contains $prune 'this.windows.values()' "prune() does not iterate the windows it owns"
	Assert-Contains $prune 'removeIf' "prune() does not remove the stopped entries (removeIf)"
	Assert-Contains $prune 'closed' "prune() does not test window.closed() to find the dead entries"
}

# 6) the render-thread-only rule is documented, because the held windows drive GLFW.
if ($source -notmatch 'render thread') {
	$problems.Add("VFXWindowRegistry does not document that its window operations are render-thread only")
}

# 7) the chosen live-duplicate rule names the spec it comes from, for the owner's sign-off.
if ($source -notmatch '2026-10-03-custom-windows-design\.md') {
	$problems.Add("VFXWindowRegistry does not cite the design spec for the live-duplicate rule")
}
if ($source -notmatch 'first-creator-wins') {
	$problems.Add("VFXWindowRegistry does not name the chosen first-creator-wins rule for a live duplicate")
}

Write-Host "Window registry check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window registry check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowRegistry is a final render-thread singleton over a name -> window map"
Write-Host "  open()/get(String)/close(String)/prune() are the produced interface (no closeAll)"
Write-Host "  the map is bounded by the pinned MAX_WINDOWS = 8 cap: over the cap is warn-once and drop"
Write-Host "  a live duplicate is first-creator-wins (warn once, no-op); a stopped same id is replaced"
Write-Host "  get() reports a stopped window; close() on a missing/closed id is warn-once no-op"
Write-Host "  prune() is public and drops the stopped entries on a reload/dispose"
Write-Host "  the registry holds no GL/GLFW and no loader type - it only delegates to VFXWindow"
Write-Host "Window registry check OK."
exit 0

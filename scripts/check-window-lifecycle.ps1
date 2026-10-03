# Dev-only guard for the effect -> window lifecycle wiring of the custom-windows subsystem (Task 7).
#
# The Task 6 surface added the two effect types; this task is the wiring that makes them own a
# window's life and drive it every frame. The shape is pinned here as literal text:
#   * VFXWindowManager.reconcile(active) - driven from VFXEffectManager.update(), the one point
#     where a play, a stop and an expiry all converge each frame - opens a window for every active
#     window_create (through VFXWindowRegistry.open, using the Task-6 spec's id/title and the
#     definition's texture/frames) and closes every window whose creating effect is gone (through
#     VFXWindowRegistry.close). VFXWindowRegistry.prune() then reaps the stopped entries.
#   * VFXWindowManager.apply() - called once per frame from the post-present client hook - reads
#     the live window_create/window_control parameters with effect.getParam(...) (creator first,
#     then each control in active order: last writer wins) and hands them to
#     VFXWindowController.apply(...). It no-ops immediately when no window is bound, so with no
#     window effect active the game frame is untouched.
#   * MinecraftMixin injects at TAIL of the game's own present method (renderFrame on >=26.1,
#     runTick on <26.1), which is the point after the game has presented its own frame; the call
#     is documented render-thread-only because it drives GLFW/GL.
# It also rejects any loader import (src/client stays loader-agnostic) and any raw swap/context
# call in the wiring: presenting is delegated to VFXWindow.present() through the controller.
# GLSL is never compiled and there is no GPU here, so nothing here claims a pixel or a shader works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-lifecycle.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowManager.java"
$mixinPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\mixin\MinecraftMixin.java"
$effectManagerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\effect\VFXEffectManager.java"
$renderHooksPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\platform\VFXClientRenderHooks.java"
$mixinsJsonPath = Join-Path $repoRoot "src\client\resources\vfxweaver.client.mixins.json"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($path in @($managerPath, $mixinPath, $effectManagerPath, $renderHooksPath)) {
	if (-not (Test-Path -LiteralPath $path)) {
		Write-Error "window lifecycle check: missing $path"
		exit 1
	}
}
$manager = [System.IO.File]::ReadAllText($managerPath)
$mixin = [System.IO.File]::ReadAllText($mixinPath)
$effectManager = [System.IO.File]::ReadAllText($effectManagerPath)
$renderHooks = [System.IO.File]::ReadAllText($renderHooksPath)
$mixinsJson = [System.IO.File]::ReadAllText($mixinsJsonPath)

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

# 0) the wiring is a final render-thread singleton and stays loader-agnostic.
if ($manager -notmatch '(?m)^public final class VFXWindowManager \{') {
	$problems.Add("VFXWindowManager is not 'public final class'")
}
if ($manager -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXWindowManager imports a loader package - src/client must stay loader-agnostic")
}
if ($mixin -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("MinecraftMixin imports a loader package - src/client must stay loader-agnostic")
}
if ($manager -notmatch '(?m)^\s*private VFXWindowManager\(\) \{') {
	$problems.Add("VFXWindowManager is not a singleton (no private constructor)")
}
if ($manager -notmatch 'public static VFXWindowManager get\(\) \{') {
	$problems.Add("VFXWindowManager is missing 'public static VFXWindowManager get()'")
}
if ($manager -notmatch 'public void reconcile\(final List<VFXActiveEffect> active\)') {
	$problems.Add("VFXWindowManager is missing 'public void reconcile(List<VFXActiveEffect> active)'")
}
if ($manager -notmatch 'public void apply\(\) \{') {
	$problems.Add("VFXWindowManager is missing 'public void apply()'")
}
if ($manager -notmatch 'public void closeAll\(\) \{') {
	$problems.Add("VFXWindowManager is missing 'public void closeAll()'")
}

# 1) no raw swap/context/create/close in the wiring: the registry owns open/close and the window
#    owns present, so the wiring only reads the monitor work area through GLFW.
foreach ($forbidden in @('glfwSwapBuffers', 'glfwMakeContextCurrent', 'glfwCreateWindow', 'glfwDestroyWindow', 'glfwSwapInterval', 'glfwFocusWindow')) {
	if ($manager.Contains($forbidden)) {
		$problems.Add("VFXWindowManager contains '$forbidden' - presenting is delegated to VFXWindow.present() and open/close to the registry")
	}
}

# 2) creating a window_create opens the registry window with the spec's id and the definition's
#    texture/frames; reconcile is the lifecycle driver.
$reconcileSig = 'public void reconcile(final List<VFXActiveEffect> active) {'
$reconcile = Get-Body $manager $reconcileSig
if ($reconcile -eq $null) {
	$problems.Add("VFXWindowManager has no readable reconcile(List<VFXActiveEffect>)")
} else {
	Assert-Contains $reconcile 'effect.getType() != VFXEffectType.WINDOW_CREATE' "reconcile() does not gate the creator on VFXEffectType.WINDOW_CREATE"
	Assert-Contains $reconcile 'open(entry.getKey(), entry.getValue());' "reconcile() does not open a window for an active window_create"
	Assert-Contains $reconcile 'VFXWindowRegistry.get().close(' "reconcile() does not close a window through VFXWindowRegistry.close(...)"
	Assert-Contains $reconcile 'VFXWindowRegistry.get().prune()' "reconcile() does not reap stopped windows through VFXWindowRegistry.prune()"
}
# the open helper resolves the spec and opens through the registry.
if ($manager -notmatch 'VFXWindowRegistry\.get\(\)\.open\(') {
	$problems.Add("VFXWindowManager does not open a window through VFXWindowRegistry.open(...)")
}

# 3) the open path uses the Task-6 spec (id/texture) and the definition-resolved spec.
if ($manager -notmatch 'definition\.getWindow\(\)' -and $manager -notmatch '\.getWindow\(\)') {
	$problems.Add("VFXWindowManager does not resolve the window spec through VFXDefinition.getWindow()")
}
if ($manager -notmatch 'spec\.texture\(\)' -and $manager -notmatch '\.texture\(\)') {
	$problems.Add("VFXWindowManager does not read the spec's texture() to load the picture")
}
if ($manager -notmatch 'content\.load\(') {
	$problems.Add("VFXWindowManager does not load the picture into the content (content.load)")
}
if ($manager -notmatch 'new VFXWindowController\(') {
	$problems.Add("VFXWindowManager does not build a VFXWindowController for the window")
}

# 4) window_control drives an existing window each frame: the apply loop reads the per-frame params
#    and calls the controller.
$applySig = 'public void apply() {'
$apply = Get-Body $manager $applySig
if ($apply -eq $null) {
	$problems.Add("VFXWindowManager has no readable apply()")
} else {
	Assert-Contains $apply 'this.bindings.isEmpty()' "apply() is not a no-op when no window is bound (the frame must stay untouched)"
	Assert-Contains $apply 'this.bindings' "apply() does not read the window bindings it owns"
	Assert-Contains $apply 'VFXEffectManager.get().getActive()' "apply() does not read the live effects to find the driving window_control"
	Assert-Contains $apply 'VFXEffectType.WINDOW_CONTROL' "apply() does not drive windows from window_control effects"
	Assert-Contains $apply 'effect.getParam(' "apply() does not read the animated window parameters (effect.getParam)"
	Assert-Contains $apply '.apply(' "apply() does not hand the frame to the controller"
	$controlAt = $apply.IndexOf('VFXEffectType.WINDOW_CONTROL')
	$applyAt = $apply.IndexOf('.apply(')
	if ($controlAt -ge 0 -and $applyAt -ge 0 -and $applyAt -lt $controlAt) {
		$problems.Add("apply() calls the controller before checking for a window_control effect")
	}
}

# 5) reconcile must be reachable on a level-less frame: VFXEffectManager.update() only runs while a
#    level exists, so the close side lives in apply() (the per-frame post-present hook), which runs
#    every frame. A window whose creator stopped - including on a world exit - is closed.
$applySig = 'public void apply() {'
$apply = Get-Body $manager $applySig
if ($apply -eq $null) {
	$problems.Add("VFXWindowManager has no readable apply()")
} else {
	Assert-Contains $apply 'reconcile(active);' "VFXWindowManager.apply() does not reconcile the bindings - a stopped creator would leave its window open over the menu"
	$reconcileAt = $apply.IndexOf('reconcile(active);')
	$emptyAt = $apply.IndexOf('this.bindings.isEmpty()')
	if ($reconcileAt -ge 0 -and $emptyAt -ge 0 -and $reconcileAt -gt $emptyAt) {
		$problems.Add("VFXWindowManager.apply() returns early on an empty binding set before reconciling - a creator that just stopped is never closed")
	}
}
if ($effectManager -match 'VFXWindowManager\.get\(\)\.reconcile\(') {
	$problems.Add("VFXEffectManager still drives reconcile() from update(), which only runs while a level exists - the close side must live in apply()")
}

# 6) the per-frame application runs after the game's own present, on the render thread: a mixin at
#    TAIL of the frame's present method (renderFrame on >=26.1, runTick on <26.1) calls apply().
if ($mixin -notmatch '@Mixin\(Minecraft\.class\)') {
	$problems.Add("MinecraftMixin does not target Minecraft.class")
}
if ($mixin -notmatch 'method = "renderFrame\(Z\)V"') {
	$problems.Add("MinecraftMixin does not inject into the 26.x frame present method 'renderFrame(Z)V'")
}
if ($mixin -notmatch 'method = "runTick\(Z\)V"') {
	$problems.Add("MinecraftMixin does not cover the 1.21.11 frame present method 'runTick(Z)V'")
}
if ($mixin -notmatch 'VFXWindowManager\.get\(\)\.apply\(\);') {
	$problems.Add("MinecraftMixin does not call VFXWindowManager.get().apply()")
}
# every @Inject in this mixin must sit at TAIL (after the game's own present), never before it.
$tailCount = ([regex]::Matches($mixin, 'at = @At\("TAIL"\)')).Count
$injectCount = ([regex]::Matches($mixin, '@Inject\(')).Count
if ($tailCount -lt $injectCount) {
	$problems.Add("MinecraftMixin has an @Inject that is not at TAIL - the aux present must run after the game's own present")
}
if ($mixin -notmatch 'render thread') {
	$problems.Add("MinecraftMixin does not document that the present runs on the render thread")
}
if ($mixin -notmatch "after the game's own present|after the game has presented|post-present") {
	$problems.Add("MinecraftMixin does not document that the aux present runs after the game's own present")
}

# 7) the mixin is registered in the client mixin config.
if ($mixinsJson -notmatch '"MinecraftMixin"') {
	$problems.Add("vfxweaver.client.mixins.json does not register MinecraftMixin")
}

# 8) dispose prunes the windows: the wiring is cleared with the client lifecycle, not leaked.
if ($renderHooks -notmatch 'VFXWindowManager\.get\(\)\.closeAll\(\)') {
	$problems.Add("VFXClientRenderHooks.onClientStopping does not close/prune the aux windows (VFXWindowManager.closeAll)")
}

Write-Host "Window lifecycle check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window lifecycle check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowManager is a final render-thread singleton, loader-agnostic"
Write-Host "  reconcile(active) opens for every active window_create and closes when the creator is gone; prune() reaps stopped windows"
Write-Host "  apply() reads the live params and drives window_control windows through the controller; no-op with no bound window"
Write-Host "  apply() reconciles every frame, so a stopped creator closes its window even with no level"
Write-Host "  MinecraftMixin presents the aux windows at TAIL of the game's own present (renderFrame/runTick), on the render thread"
Write-Host "  MinecraftMixin is registered; the client dispose closes and prunes the aux windows"
Write-Host "Window lifecycle check OK."
exit 0

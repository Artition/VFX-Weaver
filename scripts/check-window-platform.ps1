# Dev-only guard for the per-node platform capability gate of the custom-windows subsystem (Task 1).
#
# The gate is the only place that decides whether a node may create an aux window at all, and which
# GLFW features it may use, so its shape is pinned here as literal text:
#   * wayland() answers with GLFW.glfwGetPlatform() compared against GLFW_PLATFORM_WAYLAND, and falls
#     back to the XDG_SESSION_TYPE environment variable only when that call cannot answer (the
#     bundle/version differences cannot be assumed);
#   * every probe that touches a GLFW symbol wraps its body in try { ... } catch (Throwable) and has
#     a return false; failure path, so an older LWJGL degrades instead of throwing;
#   * the three has* probes gate on the bundled GLFW version returned by GLFW.glfwGetVersion, so an
#     attribute the running GLFW does not implement is reported unsupported. glfwGetWindowAttrib
#     cannot do that: it returns 0 for an unknown attribute instead of throwing, so a supported and
#     an unsupported attribute are indistinguishable.
# Gradle does not compile GLSL and there is no GPU here, so nothing here claims a window works.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-window-platform.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$classPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowPlatform.java"

$problems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $classPath)) {
	Write-Error "window platform check: missing src\client\java\dev\vfxweaver\client\window\VFXWindowPlatform.java"
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

# 0) the gate is a stateless helper: a final class with a private constructor and no instance state.
if ($source -notmatch '(?m)^public final class VFXWindowPlatform \{') {
	$problems.Add("VFXWindowPlatform is not 'public final class'")
}
if ($source -notmatch 'private VFXWindowPlatform\(\)') {
	$problems.Add("VFXWindowPlatform has no private constructor - it must stay a stateless helper")
}

# 1) wayland(): the GLFW platform query compared to GLFW_PLATFORM_WAYLAND, with the env fallback, and
#    the GLFW call itself inside a catch so a binding that cannot answer does not throw.
$wayland = Get-Body $source 'public static boolean wayland() {'
if ($wayland -eq $null) {
	$problems.Add("VFXWindowPlatform has no readable 'public static boolean wayland()'")
} else {
	if ($wayland -notmatch 'GLFW\.glfwGetPlatform\(\)') {
		$problems.Add("wayland() does not call GLFW.glfwGetPlatform()")
	}
	if ($wayland -notmatch 'GLFW\.GLFW_PLATFORM_WAYLAND') {
		$problems.Add("wayland() does not compare against GLFW.GLFW_PLATFORM_WAYLAND")
	}
	if ($wayland -notmatch 'System\.getenv\("XDG_SESSION_TYPE"\)') {
		$problems.Add("wayland() has no System.getenv(`"XDG_SESSION_TYPE`") fallback for a binding that cannot answer")
	}
	if ($wayland -notmatch 'catch\s*\(final Throwable') {
		$problems.Add("wayland() does not catch Throwable around the GLFW call")
	}
}

# 2) the three has* probes: each reads the bundled GLFW version through GLFW.glfwGetVersion and
#    gates on the minor version that introduced its attribute (focus-on-show 3.3; transparent
#    framebuffer and mouse passthrough 3.4), inside a try whose catch returns false.
$probes = @(
	@('hasPassthrough', 4),
	@('hasTransparentFramebuffer', 4),
	@('hasFocusOnShow', 3))
foreach ($probe in $probes) {
	$name = $probe[0]
	$minor = $probe[1]
	$body = Get-Body $source "public static boolean $name() {"
	if ($body -eq $null) {
		$problems.Add("VFXWindowPlatform has no readable 'public static boolean $name()'")
		continue
	}
	if ($body -notmatch 'GLFW\.glfwGetVersion\(') {
		$problems.Add("$name() does not read the bundled GLFW version through GLFW.glfwGetVersion")
	}
	if ($body -notmatch [regex]::Escape("major[0] == 3 && minor[0] >= $minor")) {
		$problems.Add("$name() does not gate on GLFW >= 3.$minor - a version gate is the only form that tells an unsupported attribute from a supported one")
	}
	if ($body -notmatch 'catch\s*\(final Throwable') {
		$problems.Add("$name() is not guarded by catch (Throwable) - an older LWJGL would throw instead of returning false")
	}
	if ($body -notmatch 'return false;') {
		$problems.Add("$name() has no 'return false;' failure path")
	}
}
# 3) the always-true attribute form is gone: glfwGetWindowAttrib returns 0 for an unknown attribute,
#    so it can never distinguish a supported attribute from an unsupported one.
if ($source -match 'GLFW\.glfwGetWindowAttrib\(') {
	$problems.Add("VFXWindowPlatform still probes attributes with glfwGetWindowAttrib - an unknown attribute returns 0, not an error, so that form cannot tell support from non-support")
}

Write-Host "Window platform check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "window platform check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXWindowPlatform is a final stateless helper with a private constructor"
Write-Host "  wayland() compares GLFW.glfwGetPlatform() to GLFW_PLATFORM_WAYLAND, with the XDG_SESSION_TYPE fallback"
Write-Host "  hasPassthrough()/hasTransparentFramebuffer()/hasFocusOnShow() gate on the bundled GLFW version (>= 3.4 / >= 3.4 / >= 3.3) under catch (Throwable), degrading to false"
Write-Host "Window platform check OK."
exit 0

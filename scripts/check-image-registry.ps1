# Dev-only guard for the caller-supplied image registry (VFXImageRegistry).
#
# A mod can hand over a rendered image (an item icon, a live preview) that is not a packed texture.
# The one id then serves two consumers with two mechanisms:
#   * post-processing (surface_pattern / sky_pattern / field textures) resolves it through the game's
#     TextureManager (getTexture(id) -> getTextureView), so the registry must register a DynamicTexture
#     under the same id - and release the previous one before replacing it, or the GPU texture leaks;
#   * an aux window has its own GL context (not shared with the game), so it cannot use that texture
#     and reads the CPU copy the registry keeps (get(id)).
# This guard pins that shape as literal text:
#   * VFXImageRegistry is a final client class, a singleton (get()), loader-agnostic;
#   * the CPU layer and the GPU texture share one source: register() stores the pixels AND uploads a
#     DynamicTexture (DynamicTexture + upload() + TextureManager.register);
#   * replacing an id releases the old texture first (TextureManager.release) and both layers are
#     bounded (MAX_IMAGES);
#   * unregister() drops both layers;
#   * the public surface stays client-only: the common-source VFXLocalDispatcher declares
#     registerImage/unregisterImage (no NativeImage in the signature), VFXAPI forwards to it,
#     VFXClientAPI forwards to this registry;
#   * VFXWindowContent consults the registry BEFORE the resource pack, so a registered id wins.
# GLSL is never compiled and there is no GPU here; nothing here claims a pixel or a texture renders.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-image-registry.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$registryPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\render\VFXImageRegistry.java"
$dispatcherPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\api\VFXLocalDispatcher.java"
$apiPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\api\VFXAPI.java"
$clientApiPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\VFXClientAPI.java"
$contentPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\window\VFXWindowContent.java"

$problems = New-Object System.Collections.Generic.List[string]
foreach ($p in @($registryPath, $dispatcherPath, $apiPath, $clientApiPath, $contentPath)) {
	if (-not (Test-Path -LiteralPath $p)) {
		Write-Error "image registry check: missing $p"
		exit 1
	}
}
$registry = [System.IO.File]::ReadAllText($registryPath)
$dispatcher = [System.IO.File]::ReadAllText($dispatcherPath)
$api = [System.IO.File]::ReadAllText($apiPath)
$clientApi = [System.IO.File]::ReadAllText($clientApiPath)
$content = [System.IO.File]::ReadAllText($contentPath)

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

# 0) the registry is a final client class, a singleton, loader-agnostic.
if ($registry -notmatch '(?m)^public final class VFXImageRegistry \{') {
	$problems.Add("VFXImageRegistry is not 'public final class'")
}
if ($registry -notmatch 'static final VFXImageRegistry INSTANCE') {
	$problems.Add("VFXImageRegistry is not a singleton (INSTANCE)")
}
if ($registry -notmatch 'public static VFXImageRegistry get\(\)') {
	$problems.Add("VFXImageRegistry does not expose 'public static VFXImageRegistry get()'")
}
if ($registry -match 'net\.fabricmc|net\.neoforged') {
	$problems.Add("VFXImageRegistry imports a loader package - src/client must stay loader-agnostic")
}
if ($registry -notmatch 'render thread') {
	$problems.Add("VFXImageRegistry does not document that its upload is render-thread only")
}

# 1) two layers from one source: the CPU copy (for the aux window) and a DynamicTexture (for the game).
$register = Get-Body $registry 'public boolean register(final Identifier id, final int width, final int height, final int[] argb) {'
if ($register -eq $null) {
	$problems.Add("VFXImageRegistry has no readable 'public boolean register(final Identifier id, final int width, final int height, final int[] argb)'")
} else {
	Assert-Contains $register 'new NativeImage(' "register() does not build a NativeImage for the game texture"
	Assert-Contains $register 'new DynamicTexture(' "register() does not build a DynamicTexture"
	Assert-Contains $register 'texture.upload()' "register() does not upload the DynamicTexture (no GPU view would exist)"
	Assert-Contains $register 'manager.register(id, texture)' "register() does not register the texture with the TextureManager (the post-pattern paths would never resolve it)"
	# replacing an id must release the old GPU texture or it leaks.
	Assert-Contains $register 'manager.release(id)' "register() does not release the previous texture before replacing it (leak)"
	# the CPU copy is what the aux window reads.
	Assert-Contains $register 'new Image(width, height, copy)' "register() does not store the CPU image the aux window reads"
	Assert-Contains $register 'this.images.size() >= MAX_IMAGES' "register() does not bound the registry (MAX_IMAGES)"
	Assert-Contains $register 'System.arraycopy(' "register() does not copy the caller's pixels (the caller may reuse the array)"
}

# 2) unregister drops both layers.
$unregister = Get-Body $registry 'public boolean unregister(final Identifier id) {'
if ($unregister -eq $null) {
	$problems.Add("VFXImageRegistry has no readable 'public boolean unregister(final Identifier id)'")
} else {
	Assert-Contains $unregister 'this.images.remove(id)' "unregister() does not drop the CPU image"
	Assert-Contains $unregister 'release(id)' "unregister() does not release the GPU texture"
}

# 3) get() serves the aux window's CPU copy.
if ($registry.IndexOf('public @Nullable Image get(final Identifier id) {') -lt 0) {
	$problems.Add("VFXImageRegistry has no 'public @Nullable Image get(final Identifier id)' for the aux window's CPU copy")
}

# 4) the common-source dispatcher declares the bridge with plain Java types (no client class).
$dispatcherRegister = Get-Body $dispatcher 'default boolean registerImage(final Identifier id, final int width, final int height, final int[] argb) {'
if ($dispatcherRegister -eq $null) {
	$problems.Add("VFXLocalDispatcher has no 'default boolean registerImage(final Identifier id, final int width, final int height, final int[] argb)' bridge")
}
if ($dispatcher.IndexOf('default boolean unregisterImage(final Identifier id) {') -lt 0) {
	$problems.Add("VFXLocalDispatcher has no 'default boolean unregisterImage(final Identifier id)' bridge")
}
if ($dispatcher -match '(?m)^import .*NativeImage') {
	$problems.Add("VFXLocalDispatcher imports NativeImage - the common source set must not reference a client-only class")
}

# 5) VFXAPI forwards to the dispatcher; VFXClientAPI forwards to the registry.
foreach ($literal in @(
	'public static boolean registerImage(final Identifier id, final int width, final int height, final int[] argb) {',
	'localDispatcher.registerImage(id, width, height, argb)',
	'public static boolean unregisterImage(final Identifier id) {')) {
	Assert-Contains $api $literal "VFXAPI is missing the forwarding literal: $literal"
}
foreach ($literal in @(
	'public boolean registerImage(final Identifier id, final int width, final int height, final int[] argb) {',
	'VFXImageRegistry.get().register(id, width, height, copy)',
	'public boolean unregisterImage(final Identifier id) {',
	'VFXImageRegistry.get().unregister(id)')) {
	Assert-Contains $clientApi $literal "VFXClientAPI is missing the registry literal: $literal"
}

# 6) VFXWindowContent consults the registry BEFORE the resource pack (a registered id wins).
$upload = Get-Body $content 'private void uploadSource() {'
if ($upload -eq $null) {
	$problems.Add("VFXWindowContent has no readable 'private void uploadSource()'")
} else {
	Assert-Contains $upload 'VFXImageRegistry.get().get(' "uploadSource() does not consult the image registry for a caller-supplied image"
	$registryAt = $upload.IndexOf('VFXImageRegistry.get().get(')
	$resourceAt = $upload.IndexOf('getResource(')
	if ($registryAt -lt 0 -or ($resourceAt -ge 0 -and $registryAt -gt $resourceAt)) {
		$problems.Add("uploadSource() does not check the registry BEFORE the resource pack - a registered image must win over a same-id pack file")
	}
}

Write-Host "Image registry check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "image registry check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  VFXImageRegistry is a final client singleton holding one CPU copy and one DynamicTexture per id"
Write-Host "  register() uploads a DynamicTexture and releases a replaced texture; both layers are bounded by MAX_IMAGES"
Write-Host "  the common-source VFXLocalDispatcher declares registerImage/unregisterImage with plain Java types (no NativeImage)"
Write-Host "  VFXAPI forwards to the dispatcher; VFXClientAPI forwards to the registry on the render thread"
Write-Host "  VFXWindowContent consults the registry before the resource pack, so a registered id wins"
Write-Host "Image registry check OK."
exit 0

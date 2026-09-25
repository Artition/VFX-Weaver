# Dev-only guard for the sky_pattern effect type (stage S3, spec 2026-09-22; sky_mode atlas fix).
#
# sky_pattern is surface_pattern's sibling: the same `pattern` block (VFXShape / shape.glsl /
# texture.glsl / VFXTexture), the same shape and texture addressing, but the projection target is
# the sky dome instead of a depth-reconstructed world surface. It is registered as a depth post so
# the per-node VFX_DEPTH_REVERSED define is injected, and the whole pass is gated on
# VFX_DEPTH_IS_SKY so it can only ever paint far-depth sky pixels (never geometry, the hand or the
# GUI). This check asserts, statically, that the effect type, the registration, the shader gate and
# the Config order all agree, and runnably that a sky_pattern definition parses with its pattern,
# texture and dome params.
#
# sky_mode selects between two local-chart projections: `patch` (gnomonic decal, 0, the default)
# and `fill` (three orthographic charts, 1). The legacy single-chart `dome` (equirectangular) mode
# was removed before release. sky_mode is appended LAST to the shader Config and the
# registerDepthPost list (a std140 positional append is safe for every existing offset) and needs a
# resolver case; this check asserts that order, the three dome.glsl helpers and the two mode
# branches, and that the equirect path is gone from sky_pattern (include/dome.glsl's vfx_dome_uv
# stays for the dome mask path, asserted below).
#
# No built-in clip: a `patch` is a flat sign on the tangent plane with no cell-radius bound - the
# owner rejected the unit-disc clip (it visibly cut the figure); a developer who needs a clean edge
# adds a mask. The patch branch evaluates the pattern on the distorted cell, bounded only by the
# `facing > 1.0e-3` horizon gate, and this check rejects a re-added cell-radius bound. The runnable
# phase parses the built-in and the demo pack, asserts each uses the mode it is authored for (patch
# for the ring and nine dots, fill for the cracks and the whole-sky texture), and asserts the ring
# demo does not animate dome_rotation (which would move the decal around the sky).
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-sky-pattern.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$effectTypePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$programsPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXShaderPrograms.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$shaderPath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\sky_pattern.fsh"
$domePath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\include\dome.glsl"
$maskCoveragePath = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh"
$builtinPath = Join-Path $repoRoot "src\main\resources\data\vfxweaver\vfx\sky_pattern.json"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$effectType = Read-Source $effectTypePath
$programs = Read-Source $programsPath
$manager = Read-Source $managerPath
$dome = Read-Source $domePath
$problems = New-Object System.Collections.Generic.List[string]

# --- 1. the effect type exists with its neutral values and is a post pass ------------------------
if ($effectType -notmatch 'SKY_PATTERN\("sky_pattern"\)') {
	$problems.Add('VFXEffectType does not declare SKY_PATTERN("sky_pattern")')
}
$neutral = 'case SKY_PATTERN -> "opacity".equals(parameter) || "frame".equals(parameter) || "texture_tint".equals(parameter) ? 0.0F : Float.NaN;'
if (-not $effectType.Contains($neutral)) {
	$problems.Add("VFXEffectType.neutralValue does not give SKY_PATTERN the surface_pattern texture-surface neutrals (opacity/frame/texture_tint -> 0)")
}
if ($effectType -match 'this != SKY_PATTERN') {
	$problems.Add("SKY_PATTERN is excluded from isPostProcessing(), but it renders a fullscreen post pass")
}

# --- 2. registered as a depth post (per-node depth define, cam_pos prefix) -----------------------
if ($programs -notmatch 'registerDepthPost\(VFXEffectType\.SKY_PATTERN,\s*true,') {
	$problems.Add("VFXShaderPrograms does not register sky_pattern through the camPos depth-post path")
}
if ($programs -notmatch 'withShaderDefine\("VFX_DEPTH_REVERSED", depthReversedDefine\(\)\)') {
	$problems.Add("the depth-post builder does not inject the per-node VFX_DEPTH_REVERSED define")
}
if ($programs -notmatch 'static int depthConfigSize\(final int nameCount, final boolean hasCamPos\)') {
	$problems.Add("VFXShaderPrograms has no depthConfigSize(nameCount, hasCamPos) overload for the vec4 cam_pos prefix")
}
if ($programs -notmatch 'depthConfigSize\(params\.length, true\)') {
	$problems.Add("the camPos depth post does not size its Config with the cam_pos prefix")
}
if ($programs -notmatch 'boolean depthConfig, boolean depthHasCamPos') {
	$problems.Add("ProgramInfo does not carry the depthHasCamPos flag the writer needs")
}

# --- 3. the shader: dome projection, sky gate, Config order --------------------------------------
if (-not (Test-Path -LiteralPath $shaderPath)) {
	$problems.Add("post/sky_pattern.fsh does not exist")
	$shader = ""
} else {
	$shader = Read-Source $shaderPath
}
if ($dome -notmatch 'vec3\s+vfx_view_dir\s*\(' -or $dome -notmatch 'vec2\s+vfx_dome_uv\s*\(') {
	$problems.Add("include/dome.glsl does not expose the shared vfx_view_dir / vfx_dome_uv projection")
}
# The sky_mode atlas helpers: one gnomonic decal chart and the three-chart fill partition.
foreach ($fn in @('vfx_dome_anchor_dir', 'vfx_dome_patch_cell', 'vfx_dome_fill_cells')) {
	if ($dome -notmatch ([regex]::Escape($fn) + '\s*\(')) {
		$problems.Add("include/dome.glsl does not expose the sky_mode chart helper '$fn'")
	}
}
# The real star lock: include/dome.glsl must expose the vanilla star sphere's local-frame transform
# (the inverse of SkyRenderer's Ry(-90)*Rx(starAngle)), used only for the stars coupling.
if ($dome -notmatch 'vec3\s+vfx_star_local_dir\s*\(') {
	$problems.Add("include/dome.glsl does not expose vfx_star_local_dir(dir, starAngleDegrees)")
}
# include/dome.glsl's vfx_dome_uv stays: the dome MASK path (post/mask_coverage.fsh) still needs it,
# even though the sky_pattern equirect branch is gone.
if (-not (Test-Path -LiteralPath $maskCoveragePath)) {
	$problems.Add("post/mask_coverage.fsh does not exist")
} else {
	$maskCoverage = Read-Source $maskCoveragePath
	if ($maskCoverage -notmatch 'vfx_dome_uv\s*\(') {
		$problems.Add("post/mask_coverage.fsh no longer calls vfx_dome_uv - the dome mask path needs it")
	}
}
if ($shader -ne "") {
	if ($shader -notmatch '#moj_import\s*<vfxweaver:dome\.glsl>') {
		$problems.Add("post/sky_pattern.fsh does not import <vfxweaver:dome.glsl>")
	}
		if ($shader -notmatch 'vfx_view_dir\s*\(texCoord, inv_view_proj, cam_pos\.xyz\)') {
			$problems.Add("post/sky_pattern.fsh does not reconstruct the view ray with vfx_view_dir(texCoord, inv_view_proj, cam_pos.xyz)")
		}
		# The legacy equirectangular mode is gone: sky_pattern must not address the dome with the
		# single global chart (vfx_dome_uv) or carry its anchorUv computation.
		if ($shader -match 'vfx_dome_uv') {
			$problems.Add("post/sky_pattern.fsh still calls vfx_dome_uv - the legacy equirect (dome) mode must be removed")
		}
		if ($shader -match 'anchorUv') {
			$problems.Add("post/sky_pattern.fsh still computes anchorUv - the legacy equirect (dome) mode must be removed")
		}
		if ($shader -match 'mode\s*==\s*2' -or $shader -match 'mode\s*==\s*0') {
			$problems.Add("post/sky_pattern.fsh still has a three-way mode branch (the modes are patch = 0 default, fill = 1)")
		}
		if ($shader -notmatch 'dome_rotation') {
			$problems.Add("post/sky_pattern.fsh has no dome_rotation handling")
		}
		# The stars coupling must spin the sampled direction into the star sphere's own frame, and
		# only for the stars anchor (dome_rotation stays a world-Y spin, applied first).
		if ($shader -notmatch 'vfx_star_local_dir\s*\(\s*spun\s*,\s*star_angle\s*\)') {
			$problems.Add("post/sky_pattern.fsh does not apply vfx_star_local_dir(spun, star_angle) for the stars anchor")
		}
		if ($shader -notmatch 'anchor_stars\s*>\s*0\.5') {
			$problems.Add("post/sky_pattern.fsh does not gate the star lock on anchor_stars")
		}
		# The shared pattern evaluation factored out of main, and the two mode branches.
		if ($shader -notmatch 'vec4\s+vfx_sky_pattern_eval\s*\(') {
			$problems.Add("post/sky_pattern.fsh does not factor the pattern into vfx_sky_pattern_eval(cell)")
		}
		foreach ($fn in @('vfx_dome_anchor_dir', 'vfx_dome_patch_cell', 'vfx_dome_fill_cells')) {
			if ($shader -notmatch ([regex]::Escape($fn) + '\s*\(')) {
				$problems.Add("post/sky_pattern.fsh does not use the sky_mode chart helper '$fn'")
			}
		}
		if ($shader -notmatch 'int\s+mode\s*=\s*int\(sky_mode') {
			$problems.Add("post/sky_pattern.fsh does not branch on the sky_mode param")
		}
		# Two modes: fill (mode == 1) and the default patch (the else branch, mode 0). fill must use
		# the three-chart partition; patch must use the gnomonic decal.
		if ($shader -notmatch 'if\s*\(\s*mode\s*==\s*1\s*\)') {
			$problems.Add("post/sky_pattern.fsh has no fill (mode == 1) branch")
		}
		if ($shader -notmatch 'else\s*\{[^}]*vfx_dome_patch_cell') {
			$problems.Add("post/sky_pattern.fsh has no default patch (else) branch using vfx_dome_patch_cell")
		}
	if ($shader -notmatch 'VFX_DEPTH_IS_SKY\s*\(sceneDepth\)') {
		$problems.Add("post/sky_pattern.fsh does not gate on VFX_DEPTH_IS_SKY(sceneDepth)")
	}
	# the gate must be a passthrough of the untouched base, not a zero-coverage paint
	if ($shader -notmatch 'if\s*\(!VFX_DEPTH_IS_SKY\(sceneDepth\)\)\s*\{\s*fragColor = base;\s*return;\s*\}') {
		$problems.Add("post/sky_pattern.fsh does not pass non-sky pixels through untouched")
	}
	# the sky test must not be hard-coded per convention
	if ($shader -match 'sceneDepth <= 1\.0e-6' -or $shader -match 'sceneDepth >= 1\.0 - 1\.0e-6') {
		$problems.Add("post/sky_pattern.fsh hard-codes a depth convention instead of using VFX_DEPTH_IS_SKY")
	}
}

# --- 3c. patch mode has NO cell-radius bound: the `facing` gate is the only bound ----------------
# The owner rejected the unit-disc clip (it visibly cut the figure): a `patch` is a flat sign on the
# tangent plane with no built-in clip - a developer who needs a clean edge adds a mask. The patch
# branch must evaluate the pattern on the distorted cell, bounded only by the `facing > 1.0e-3`
# horizon gate (the hard backstop that keeps the tangent-plane horizon out). This asserts the
# negative: a re-added cell-radius bound is caught here.
if ($shader -ne "") {
	if ($shader -match 'vfx_sky_pattern_patch_falloff') {
		$problems.Add("post/sky_pattern.fsh re-introduced the vfx_sky_pattern_patch_falloff cell-radius bound (the owner rejected it; a mask is how you clip)")
	}
	if ($shader -match 'length\(\s*cell\s*\)' -or $shader -match 'length\(\s*warped\s*\)') {
		$problems.Add("post/sky_pattern.fsh measures a cell radius (length(cell)) - the patch branch must have no cell-radius bound")
	}
	if ($shader -match 'smoothstep\(1\.0\s*-\s*fade') {
		$problems.Add("post/sky_pattern.fsh has a radius-1 soft falloff - the patch must have no built-in clip")
	}
	# The facing gate is the only bound; the pattern is evaluated directly on the distorted cell.
	if ($shader -notmatch 'if\s*\(\s*facing\s*>\s*1\.0e-3\s*\)') {
		$problems.Add("post/sky_pattern.fsh lost the `facing > 1.0e-3` horizon gate (the only patch bound)")
	}
	if ($shader -notmatch 'vfx_sky_pattern_eval\(\s*vfx_sky_pattern_distort\(\s*cell\s*\)\s*\)') {
		$problems.Add("post/sky_pattern.fsh patch branch does not evaluate the pattern on the distorted cell")
	}
}

# --- 3b. Config block vs registerDepthPost: same set, same order ---------------------------------
$shaderNames = New-Object System.Collections.Generic.List[string]
$hasMat4 = $false
$hasCamPos = $false
if ($shader -ne "") {
	$config = [regex]::Match($shader, 'layout\(std140\)\s+uniform\s+Config\s*\{(?<body>.*?)\}', 'Singleline')
	if (-not $config.Success) {
		$problems.Add("post/sky_pattern.fsh has no std140 Config block")
	} else {
		foreach ($line in ($config.Groups['body'].Value -split "\r?\n")) {
			$code = ($line -replace '//.*$', '').Trim()
			if ($code -eq '' -or $code -eq ';') { continue }
			if ($code -match '^mat4\s+inv_view_proj\s*;$') { $hasMat4 = $true; continue }
			if ($code -match '^vec4\s+cam_pos\s*;$') { $hasCamPos = $true; continue }
			if ($code -match '^float\s+(\w+)\s*;$') { $shaderNames.Add($Matches[1]); continue }
			$problems.Add("post/sky_pattern.fsh Config has an unrecognised declaration: $code")
		}
	}
	if (-not $hasMat4) { $problems.Add("post/sky_pattern.fsh Config block lacks the leading 'mat4 inv_view_proj;'") }
	if (-not $hasCamPos) { $problems.Add("post/sky_pattern.fsh Config block lacks the 'vec4 cam_pos;' the view ray starts from") }
	if ($shaderNames.Count -gt 0 -and $shaderNames[$shaderNames.Count - 1] -ne 'star_angle') {
		$problems.Add("post/sky_pattern.fsh Config does not declare 'star_angle' last (appended field)")
	}
}
$call = [regex]::Match($programs, 'registerDepthPost\(VFXEffectType\.SKY_PATTERN,\s*true\s*,(?<body>.*?)\);', 'Singleline')
if (-not $call.Success) {
	$problems.Add("no registerDepthPost(SKY_PATTERN, true, ...) call to compare the Config order against")
	$javaNames = New-Object System.Collections.Generic.List[string]
} else {
	$javaNames = New-Object System.Collections.Generic.List[string]
	$body = $call.Groups['body'].Value -replace '//[^\r\n]*', ''
	foreach ($m in [regex]::Matches($body, '"([^"]+)"')) { $javaNames.Add($m.Groups[1].Value) }
	if ($javaNames.Count -gt 0 -and $javaNames[$javaNames.Count - 1] -ne 'star_angle') {
		$problems.Add("registerDepthPost(SKY_PATTERN, ...) does not append 'star_angle' last")
	}
}
if ($shaderNames.Count -ne $javaNames.Count) {
	$problems.Add("Config float count: shader $($shaderNames.Count) vs registerDepthPost $($javaNames.Count)")
}
# The sky_pattern Config is the figure + texture surface: 41 floats after mat4 + vec4 cam_pos
# (39 before the real star lock + the appended anchor_stars/star_angle).
# Bump this when a real field is appended to both sides.
$expectedFloatCount = 41
if ($shaderNames.Count -ne $expectedFloatCount) {
	$problems.Add("Config float count: shader $($shaderNames.Count) vs expected $expectedFloatCount")
}
$count = [Math]::Min($shaderNames.Count, $javaNames.Count)
for ($i = 0; $i -lt $count; $i++) {
	if ($shaderNames[$i] -ne $javaNames[$i]) {
		$problems.Add("Config slot ${i}: shader '$($shaderNames[$i])' vs registerDepthPost '$($javaNames[$i])'")
	}
}
# the texture tail must be present and the surface-only concepts absent
foreach ($name in @('shape_present', 'tex_u0', 'tex_v0', 'tex_u1', 'tex_v1', 'tex_aspect', 'tex_cols', 'tex_rows', 'tex_frame', 'tex_flags', 'tex_channel', 'texture_tint', 'tex_px_w', 'tex_px_h', 'anchor_yaw', 'anchor_pitch', 'dome_rotation', 'sky_mode', 'anchor_stars', 'star_angle')) {
	if ($shaderNames -notcontains $name) { $problems.Add("post/sky_pattern.fsh Config is missing '$name'") }
}
foreach ($name in @('normal_mask', 'face_mask', 'band_min', 'band_max', 'band_softness', 'stitch', 'center_x', 'center_y', 'center_z')) {
	if ($shaderNames -contains $name) { $problems.Add("post/sky_pattern.fsh Config carries the surface-only field '$name'") }
}

# --- 4. the writer: cam_pos after inv_view_proj, camPos-aware layout guard, resolver cases -------
if ($manager -notmatch 'if \(this\.depthHasCamPos\)\s*\{\s*builder\.putVec4\(VFXFieldEnv\.cameraX\(\), VFXFieldEnv\.cameraY\(\), VFXFieldEnv\.cameraZ\(\), 0\.0F\);\s*\}') {
	$problems.Add("VFXPostProcessingManager does not write vec4 cam_pos after inv_view_proj for a camPos depth post")
}
if ($manager -notmatch 'this\.depthHasCamPos = info\.depthHasCamPos\(\);') {
	$problems.Add("VFXPass does not carry the ProgramInfo depthHasCamPos flag")
}
if ($manager -notmatch 'final int prefixBytes = hasCamPos \? 80 : 64;') {
	$problems.Add("the std140 layout guard does not offset by the vec4 cam_pos prefix for a camPos pass")
}
$resolver = [regex]::Match($manager, 'resolveDepthValue\([\s\S]*?final float raw = switch \(param\) \{(?<body>.*?)\};', 'Singleline')
$switchBody = if ($resolver.Success) { $resolver.Groups['body'].Value } else { "" }
if ($switchBody -eq "") {
	$problems.Add("VFXPostProcessingManager has no resolveDepthValue switch to enumerate")
}
foreach ($name in @('anchor_yaw', 'anchor_pitch', 'dome_rotation', 'sky_mode', 'anchor_stars', 'star_angle')) {
	if ($switchBody -notmatch ('case\s+"' + [regex]::Escape($name) + '"')) {
		$problems.Add("resolveDepthValue has no case for the sky_pattern param '$name'")
	}
}
# The default is `patch` (0), the owner's call for the common decal case.
if ($switchBody -notmatch 'case\s+"sky_mode"\s*->\s*effect\.getParam\("sky_mode",\s*0\.0F\)') {
	$problems.Add('resolveDepthValue does not default sky_mode to patch (effect.getParam("sky_mode", 0.0F))')
}

# --- 5. the shipped built-in exists -----------------------------------------------------------------
if (-not (Test-Path -LiteralPath $builtinPath)) {
	$problems.Add("data/vfxweaver/vfx/sky_pattern.json (the stable built-in) does not exist")
}

# --- 7. S4 celestial anchors: enum + parse + CPU resolver + fail-closed (spec §4.4 / §6.1-6.2) -----
# A top-level `anchor` ("dome" default | "sun" | "moon" | "stars") makes the effect follow the
# vanilla sky body. The pass is unchanged - the CPU resolves the body's [yaw, pitch] (and, for
# `stars`, `dome_rotation`) from the client's SkyRenderState and writes the SAME uniforms, so no
# shader and no mixin are added (this is what keeps it Iris-safe: a post pass composes over the
# pack's sky). When the sky state is unavailable the effect must contribute NOTHING (opacity 0)
# rather than paint at a guessed spot, reported once through VFXLog.warnOnce.
$anchorEnumPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\CelestialAnchor.java"
$skyAnchorPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\util\VFXSkyAnchor.java"
$bindingsPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXWorldBindings.java"
$mixinPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\mixin\GameRendererMixin.java"
$hooksPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\platform\VFXClientRenderHooks.java"
$definitionPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXDefinition.java"
$definitionSrc = Read-Source $definitionPath
$bindingsSrc = Read-Source $bindingsPath

if (-not (Test-Path -LiteralPath $anchorEnumPath)) {
	$problems.Add("CelestialAnchor.java (the sun/moon/stars anchor enum) does not exist")
} else {
	$anchorEnum = Read-Source $anchorEnumPath
	foreach ($value in @('SUN("sun")', 'MOON("moon")', 'STARS("stars")', 'DOME("dome")')) {
		if (-not $anchorEnum.Contains($value)) { $problems.Add("CelestialAnchor does not declare the value $value") }
	}
	if ($anchorEnum -notmatch 'fromString') { $problems.Add("CelestialAnchor has no fromString resolver") }
	if ($anchorEnum -notmatch 'dome, sun, moon or stars') { $problems.Add("CelestialAnchor's unknown-value error does not name the accepted values (dome, sun, moon or stars)") }
	if ($anchorEnum -notmatch 'isCelestial') { $problems.Add("CelestialAnchor has no isCelestial() predicate") }
}
if (-not (Test-Path -LiteralPath $skyAnchorPath)) {
	$problems.Add("util/VFXSkyAnchor.java (the MC-free angle -> [yaw, pitch] helper) does not exist")
} else {
	$skyAnchorSrc = Read-Source $skyAnchorPath
	foreach ($fn in @('yawPitch', 'bodyAnchor', 'starAngleDegrees')) {
		if ($skyAnchorSrc -notmatch ([regex]::Escape($fn) + '\s*\(')) { $problems.Add("VFXSkyAnchor does not expose '$fn'") }
	}
}
# parse: the top-level `anchor` field is accepted (and rejected on a non-sky type)
if ($definitionSrc -notmatch 'json\.has\("anchor"\)') { $problems.Add("VFXDefinition.parse does not read the top-level 'anchor' field") }
if ($definitionSrc -notmatch 'CelestialAnchor\.fromString') { $problems.Add("VFXDefinition.parse does not resolve 'anchor' through CelestialAnchor.fromString") }
if ($definitionSrc -notmatch 'only supported by sky_pattern') { $problems.Add("VFXDefinition.parse does not reject 'anchor' on a non-sky_pattern type") }
if ($definitionSrc -notmatch 'getAnchor') { $problems.Add("VFXDefinition has no getAnchor() accessor") }
# state: the per-frame sky-angle snapshot the client publishes
if ($bindingsSrc -notmatch 'updateSkyState') { $problems.Add("VFXWorldBindings has no updateSkyState(...) snapshot") }
if ($bindingsSrc -notmatch 'skyReady') { $problems.Add("VFXWorldBindings has no skyReady() readiness flag") }
foreach ($fn in @('skySunAngle', 'skyMoonAngle', 'skyStarAngle')) {
	if ($bindingsSrc -notmatch [regex]::Escape($fn)) { $problems.Add("VFXWorldBindings does not expose $fn()") }
}
# resolver: the celestial anchor drives the EXISTING uniforms, from the sky state
if ($manager -notmatch 'VFXSkyAnchor\.bodyAnchor\(') { $problems.Add("resolveDepthValue does not write anchor_yaw/anchor_pitch from VFXSkyAnchor.bodyAnchor") }
if ($manager -notmatch '"anchor_yaw"\s*->\s*yp\[0\]') { $problems.Add("resolveDepthValue does not map a sun/moon anchor's yaw into the anchor_yaw uniform") }
if ($manager -notmatch '"anchor_pitch"\s*->\s*yp\[1\]') { $problems.Add("resolveDepthValue does not map a sun/moon anchor's pitch into the anchor_pitch uniform") }
if ($manager -notmatch 'VFXSkyAnchor\.starAngleDegrees\(') { $problems.Add("resolveDepthValue does not pass the star angle through VFXSkyAnchor.starAngleDegrees for a stars anchor") }
if ($manager -notmatch '"anchor_stars"\s*->\s*1\.0F') { $problems.Add("resolveDepthValue does not set anchor_stars to 1 for a stars anchor") }
if ($manager -notmatch '"star_angle"\s*->\s*VFXSkyAnchor\.starAngleDegrees\(') { $problems.Add("resolveDepthValue does not write star_angle from VFXSkyAnchor.starAngleDegrees for a stars anchor") }
# fail-closed: not-ready sky -> no contribution, warned once
if ($manager -notmatch 'skyReady\(\)') { $problems.Add("VFXPostProcessingManager does not consult skyReady() (the fail-closed path)") }
if ($manager -notmatch 'VFXLog\.warnOnce\(LOGGER, "celestial') { $problems.Add("VFXPostProcessingManager has no warnOnce report for a non-ready celestial anchor") }
# per-node sky state: the public render-state chain on >=26.1, the render-context accessor on <26.1
if (-not (Test-Path -LiteralPath $mixinPath)) {
	$problems.Add("GameRendererMixin.java is missing")
} else {
	$mixinSrc = Read-Source $mixinPath
	if ($mixinSrc -notmatch 'getGameRenderState\(\)') { $problems.Add("GameRendererMixin does not read getGameRenderState() (the 26.1.2 sky state)") }
	if ($mixinSrc -notmatch 'gameRenderState\(\)') { $problems.Add("GameRendererMixin does not read gameRenderState() (the 26.2 sky state)") }
	if ($mixinSrc -notmatch 'SkyRenderState') { $problems.Add("GameRendererMixin does not read SkyRenderState") }
	if ($mixinSrc -notmatch 'updateSkyState') { $problems.Add("GameRendererMixin does not publish the sky state to VFXWorldBindings") }
}
if (-not (Test-Path -LiteralPath $hooksPath)) {
	$problems.Add("VFXClientRenderHooks.java is missing")
} else {
	$hooksSrc = Read-Source $hooksPath
	if ($hooksSrc -notmatch 'worldState\(\)\.skyRenderState') { $problems.Add("VFXClientRenderHooks (Fabric <26.1) does not read context.worldState().skyRenderState") }
	if ($hooksSrc -notmatch 'getLevelRenderState\(\)\.skyRenderState') { $problems.Add("VFXClientRenderHooks (NeoForge <26.1) does not read event.getLevelRenderState().skyRenderState") }
}
# The anchor is resolved on the CPU: sun/moon reuse anchor_yaw/anchor_pitch; the real stars lock
# appends anchor_stars + star_angle (the only S4 fields). The Config float count and the shader
# tail are asserted above.
if ($shaderNames -contains 'anchor_present') { $problems.Add("post/sky_pattern.fsh has a stray 'anchor_present' field") }
foreach ($name in @('anchor_stars', 'star_angle')) {
	if ($shaderNames -notcontains $name) { $problems.Add("post/sky_pattern.fsh Config is missing the stars-lock field '$name'") }
}

Write-Host "sky_pattern check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "sky_pattern check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK: type, depth-post registration, dome gate, Config order, writer cam_pos"

# --- 6. runnable: a sky_pattern definition parses with its pattern / texture / dome params --------
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$candidate = Get-ChildItem (Join-Path $env:ProgramFiles "Java") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) {
		$javac = Join-Path $candidate.FullName "bin\javac.exe"
		$java = Join-Path $candidate.FullName "bin\java.exe"
	}
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "sky_pattern check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\effect\VFXDefinition.class"))) {
	Write-Error "sky_pattern check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$mcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2") -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $mcJar) {
	Write-Error "sky_pattern check: minecraft merged-deobf 26.1.2 jar not found."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-sky-pattern-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "SkyPatternCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

# The demo pack the runnable phase parses: the sky demos live in a datapack, not in the repo (AGENTS.md),
# so locate the vfx_demos pack under the Prism instances (the six test instances share the same files).
# Prefer the canonical 26.2fabric pack; fall back to any matching pack on the box.
$instancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
$demoDir = Join-Path $instancesRoot "26.2fabric\minecraft\saves\New World\datapacks\vfx_demos\data\vfx_demos\vfx"
if (-not (Test-Path (Join-Path $demoDir "show_sky_pattern_texture.json"))) {
	$demoDir = Get-ChildItem -Path $instancesRoot -Recurse -File -Filter "show_sky_pattern_texture.json" -ErrorAction SilentlyContinue |
		Where-Object { $_.DirectoryName -match '\\vfx_demos\\data\\vfx_demos\\vfx$' } |
		Select-Object -First 1 -ExpandProperty DirectoryName
}
if (-not $demoDir) {
	Write-Error "sky_pattern check: no vfx_demos pack with the sky demos found under $instancesRoot."
	exit 1
}

$checkJava = @'
import com.google.gson.JsonParser;
import dev.vfxweaver.effect.CelestialAnchor;
import dev.vfxweaver.effect.Keyframe;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.field.VFXTexture;
import dev.vfxweaver.util.VFXSkyAnchor;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;

/** Standalone assertions for the sky_pattern effect type (stage S3, the sky_mode atlas + the no-clip patch). */
public final class SkyPatternCheck {
	public static void main(final String[] args) throws Exception {
		final VFXDefinition builtin = parse("vfxweaver", "sky_pattern", Files.readString(Path.of(args[0])));
		require(builtin.getType() == VFXEffectType.SKY_PATTERN, "the built-in's type is not SKY_PATTERN");
		require(builtin.getType().isPostProcessing(), "SKY_PATTERN is not a post-processing type");
		require(builtin.getPattern() != null, "the built-in has no pattern block");
		require(builtin.getPattern().texture() == null, "the built-in unexpectedly references a texture");
		require(builtin.getParams().containsKey("sky_mode"), "the built-in does not set sky_mode explicitly");
		require(builtin.getParam("sky_mode", -1.0F) == 0.0F, "the built-in sky_mode is not patch (0)");
		require(builtin.getParam("tile_scale", -1.0F) > 0.0F && builtin.getParam("tile_scale", -1.0F) <= 0.6F,
			"the built-in tile_scale is not a small patch (0 < tile_scale <= 0.6)");

		final String json = "{\"type\":\"sky_pattern\",\"params\":{\"screen_layer\":0,\"anchor_yaw\":12.0,"
			+ "\"anchor_pitch\":-30.0,\"dome_rotation\":15.0,\"tile_scale\":0.5,\"opacity\":0.9,\"frame\":3,"
			+ "\"sky_mode\":\"fill\"},"
			+ "\"pattern\":{\"figure\":\"circle\",\"radius\":0.5,\"texture\":{\"id\":\"minecraft:block/stone\","
			+ "\"source\":\"block\",\"channel\":\"alpha\",\"sheet\":[4,4],\"aspect\":\"preserve\"}}}";
		final VFXDefinition textured = parse("vfx_demos", "check_sky_pattern", json);
		require(textured.getParam("sky_mode", -1.0F) == 1.0F, "the sky_mode string 'fill' did not map to 1");

		// The legacy `dome` mode is removed before release: it is now a per-file parse error naming
		// the accepted values (patch / fill).
		boolean badMode = false;
		try {
			parse("vfx_demos", "check_sky_mode_dome",
				"{\"type\":\"sky_pattern\",\"params\":{\"sky_mode\":\"dome\"}}");
		} catch (final IllegalArgumentException e) {
			badMode = e.getMessage() != null && e.getMessage().contains("patch") && e.getMessage().contains("fill");
		}
		require(badMode, "the sky_mode string 'dome' is not a parse error naming patch/fill");
		require(textured.getType() == VFXEffectType.SKY_PATTERN, "the textured type is not SKY_PATTERN");
		require(textured.getPattern() != null && textured.getPattern().texture() != null, "the textured pattern lost its texture");
		final VFXTexture texture = textured.getPattern().texture();
		require(texture.source() == VFXTexture.Source.BLOCK, "the texture source is not BLOCK");
		require(texture.sheetCols() == 4 && texture.sheetRows() == 4, "the sheet grid is not 4x4");
		require(textured.getParams().containsKey("anchor_yaw"), "the anchor_yaw param is missing");
		require(textured.getParams().containsKey("anchor_pitch"), "the anchor_pitch param is missing");
		require(textured.getParams().containsKey("dome_rotation"), "the dome_rotation param is missing");
		require(textured.getParams().containsKey("frame"), "the animatable frame param is missing");

		// --- the demo pack: every sky demo parses and uses the mode it is authored for -------------
		final Path demos = Path.of(args[1]);
		final VFXDefinition ring = parse("vfx_demos", "show_sky_pattern",
			Files.readString(demos.resolve("show_sky_pattern.json")));
		require(ring.getType() == VFXEffectType.SKY_PATTERN, "show_sky_pattern is not a sky_pattern");
		require(ring.getParam("sky_mode", -1.0F) == 0.0F, "show_sky_pattern (the ring) is not a patch");
		require(maxTile(ring) <= 0.6F, "show_sky_pattern (the ring) tile_scale is not small (max > 0.6)");
		// dome_rotation moves a patch decal along its latitude (around the sky) - the ring must not
		// animate it, or it circles the player instead of staying put.
		final VFXDefinition.ParamSpec ringSpin = ring.getParams().get("dome_rotation");
		require(ringSpin == null || ringSpin.keyframes().isEmpty(),
			"show_sky_pattern (the ring) animates dome_rotation, which moves the decal around the sky");

		final VFXDefinition dots = parse("vfx_demos", "nine_red_pixels",
			Files.readString(demos.resolve("nine_red_pixels.json")));
		require(dots.getParam("sky_mode", -1.0F) == 0.0F, "nine_red_pixels is not a patch");
		final float dotScale = dots.getParam("tile_scale", -1.0F);
		require(dotScale >= 0.25F && dotScale <= 0.35F,
			"nine_red_pixels tile_scale is not a small patch (0.25..0.35), got " + dotScale);

		final VFXDefinition cracks = parse("vfx_demos", "sky_cracks",
			Files.readString(demos.resolve("sky_cracks.json")));
		require(cracks.getParam("sky_mode", -1.0F) == 1.0F, "sky_cracks is not a fill");

		final VFXDefinition textureDemo = parse("vfx_demos", "show_sky_pattern_texture",
			Files.readString(demos.resolve("show_sky_pattern_texture.json")));
		require(textureDemo.getParam("sky_mode", -1.0F) == 1.0F, "show_sky_pattern_texture is not a fill");
		require(textureDemo.getPattern() != null && textureDemo.getPattern().texture() != null,
			"show_sky_pattern_texture lost its texture");

		// --- S4: the `anchor` field, one definition per value, and a bad value is a parse error -----
		require(parse("vfx_demos", "a_sun", "{\"type\":\"sky_pattern\",\"anchor\":\"sun\"}").getAnchor() == CelestialAnchor.SUN,
			"anchor 'sun' did not resolve to CelestialAnchor.SUN");
		require(parse("vfx_demos", "a_moon", "{\"type\":\"sky_pattern\",\"anchor\":\"moon\"}").getAnchor() == CelestialAnchor.MOON,
			"anchor 'moon' did not resolve to CelestialAnchor.MOON");
		require(parse("vfx_demos", "a_stars", "{\"type\":\"sky_pattern\",\"anchor\":\"stars\"}").getAnchor() == CelestialAnchor.STARS,
			"anchor 'stars' did not resolve to CelestialAnchor.STARS");
		require(parse("vfx_demos", "a_dome", "{\"type\":\"sky_pattern\",\"anchor\":\"dome\"}").getAnchor() == CelestialAnchor.DOME,
			"anchor 'dome' did not resolve to CelestialAnchor.DOME");
		final CelestialAnchor absent = parse("vfx_demos", "a_dome2", "{\"type\":\"sky_pattern\"}").getAnchor();
		require(absent == null || !absent.isCelestial(),
			"an absent anchor is not the world-fixed default (null / DOME)");
		require(builtin.getAnchor() == null || !builtin.getAnchor().isCelestial(),
			"the built-in unexpectedly carries a celestial anchor");

		boolean badAnchor = false;
		try {
			parse("vfx_demos", "a_bad", "{\"type\":\"sky_pattern\",\"anchor\":\"pluto\"}");
		} catch (final IllegalArgumentException e) {
			// The per-file parse error must name the accepted values, so a typo is actionable.
			badAnchor = e.getMessage() != null && e.getMessage().contains("sun")
				&& e.getMessage().contains("moon") && e.getMessage().contains("stars");
		}
		require(badAnchor, "an unknown anchor value is not a parse error naming sun/moon/stars");

		boolean anchorOnWrongType = false;
		try {
			parse("vfx_demos", "a_wrong", "{\"type\":\"color_grade\",\"anchor\":\"sun\"}");
		} catch (final IllegalArgumentException e) {
			anchorOnWrongType = true;
		}
		require(anchorOnWrongType, "'anchor' on a non-sky_pattern type is not a parse error");

		// --- S4: the demo pack carries one definition per anchor value ------------------------------
		final VFXDefinition demoSun = parse("vfx_demos", "sky_anchor_sun",
			Files.readString(demos.resolve("sky_anchor_sun.json")));
		require(demoSun.getAnchor() == CelestialAnchor.SUN, "sky_anchor_sun is not anchored to the sun");
		require(demoSun.getParam("sky_mode", -1.0F) == 0.0F, "sky_anchor_sun is not a patch");
		final VFXDefinition demoMoon = parse("vfx_demos", "sky_anchor_moon",
			Files.readString(demos.resolve("sky_anchor_moon.json")));
		require(demoMoon.getAnchor() == CelestialAnchor.MOON, "sky_anchor_moon is not anchored to the moon");
		require(demoMoon.getParam("sky_mode", -1.0F) == 0.0F, "sky_anchor_moon is not a patch");
		final VFXDefinition demoStars = parse("vfx_demos", "sky_anchor_stars",
			Files.readString(demos.resolve("sky_anchor_stars.json")));
		require(demoStars.getAnchor() == CelestialAnchor.STARS, "sky_anchor_stars is not anchored to the stars");
		require(demoStars.getParam("sky_mode", -1.0F) == 1.0F, "sky_anchor_stars is not a fill");

		// --- S4: the angle -> [yaw, pitch] maths, runnable and MC-free ------------------------------
		// Known directions in the authoring convention (yaw 0 = +Z south, 90 = -X west; pitch -90 = up).
		assertYawPitch(0.0F, 0.0F, 1.0F, 0.0F, 0.0F, "due south at the horizon");
		near(VFXSkyAnchor.yawPitch(0.0F, 1.0F, 0.0F)[1], -90.0F, "the zenith pitch");
		assertYawPitch(-1.0F, 0.0F, 0.0F, 90.0F, 0.0F, "due west");
		// Round-trip: anchor_dir(yawPitch(d)) must reproduce the direction the shader's
		// vfx_dome_anchor_dir builds, for the fixed and for a tilted direction alike.
		roundTrip(0.0F, 0.0F, 1.0F);
		roundTrip(0.0F, 1.0F, 0.0F);
		roundTrip(-1.0F, 0.0F, 0.0F);
		roundTrip(0.3F, 0.5F, -0.8F);
		// The sun/moon body direction from the celestial angle (radians) is (-sin, cos, 0): at angle
		// 0 the body is at the zenith, at -pi/2 it is due east, at +pi/2 due west.
		final float[] zenith = VFXSkyAnchor.bodyAnchor(0.0F);
		near(zenith[1], -90.0F, "the angle-0 body is not at the zenith");
		final float[] east = VFXSkyAnchor.bodyAnchor(-(float) (Math.PI / 2.0));
		near(east[0], -90.0F, "the -pi/2 body is not due east");
		near(east[1], 0.0F, "the -pi/2 body is not on the horizon");
		final float[] west = VFXSkyAnchor.bodyAnchor((float) (Math.PI / 2.0));
		near(west[0], 90.0F, "the +pi/2 body is not due west");
		// Tilted body position: still round-trips to the (-sin, cos, 0) direction.
		final float tilt = 0.7F;
		final float[] tilted = VFXSkyAnchor.bodyAnchor(tilt);
		final float[] tiltedDir = anchorDir(tilted[0], tilted[1]);
		near(tiltedDir[0], -(float) Math.sin(tilt), "tilted body dir x");
		near(tiltedDir[1], (float) Math.cos(tilt), "tilted body dir y");
		near(tiltedDir[2], 0.0F, "tilted body dir z");
		// The real star lock: the star angle is the vanilla SkyRenderState.starAngle in degrees.
		near(VFXSkyAnchor.starAngleDegrees((float) (Math.PI / 2.0)), 90.0F, "star angle at pi/2");
		near(VFXSkyAnchor.starAngleDegrees(0.0F), 0.0F, "star angle at 0");
		// The shader's star-local transform must be the exact inverse of the vanilla star sphere's
		// pose, verified with javap in SkyRenderer.renderSunMoonAndStars on all three lines:
		// world = Ry(-90) * Rx(starAngle), so local = Rx(-starAngle) * Ry(90).
		for (final float angle : new float[]{0.0F, 0.7F, -(float) (Math.PI / 3.0), (float) Math.PI}) {
			for (final float[] v : new float[][]{{0.0F, 0.0F, 1.0F}, {1.0F, 0.0F, 0.0F}, {0.0F, 1.0F, 0.0F}, {0.3F, -0.5F, 0.8F}}) {
				final float[] w = vanillaStarWorld(v, angle);
				final float[] l = shaderStarLocal(w, angle);
				near(l[0], v[0], "star lock round-trip x at angle " + angle);
				near(l[1], v[1], "star lock round-trip y at angle " + angle);
				near(l[2], v[2], "star lock round-trip z at angle " + angle);
			}
		}

		System.out.println("sky_pattern check OK: built-in + demos parse; no patch clip, demo sky_mode + ring spin asserted; celestial anchors + angle maths asserted");
	}

	/** near-assert for a yaw/pitch pair against the known convention. */
	private static void assertYawPitch(final float dx, final float dy, final float dz, final float yaw, final float pitch, final String what) {
		final float[] yp = VFXSkyAnchor.yawPitch(dx, dy, dz);
		near(yp[0], yaw, what + " yaw");
		near(yp[1], pitch, what + " pitch");
	}

	/** Assert anchor_dir(yawPitch(d)) == normalize(d), the exact convention vfx_dome_anchor_dir uses. */
	private static void roundTrip(final float dx, final float dy, final float dz) {
		final float[] yp = VFXSkyAnchor.yawPitch(dx, dy, dz);
		final float[] back = anchorDir(yp[0], yp[1]);
		final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		near(back[0], dx / len, "round-trip x of (" + dx + "," + dy + "," + dz + ")");
		near(back[1], dy / len, "round-trip y of (" + dx + "," + dy + "," + dz + ")");
		near(back[2], dz / len, "round-trip z of (" + dx + "," + dy + "," + dz + ")");
	}

	/** The Java mirror of include/dome.glsl's vfx_dome_anchor_dir. */
	private static float[] anchorDir(final float yawDeg, final float pitchDeg) {
		final double y = Math.toRadians(yawDeg);
		final double p = Math.toRadians(pitchDeg);
		final double cp = Math.cos(p);
		return new float[]{(float) (-Math.sin(y) * cp), (float) (-Math.sin(p)), (float) (Math.cos(y) * cp)};
	}

	/** The vanilla star sphere's pose (SkyRenderer.renderSunMoonAndStars): world = Ry(-90)*Rx(angle). */
	private static float[] vanillaStarWorld(final float[] v, final float angle) {
		final double c = Math.cos(angle);
		final double s = Math.sin(angle);
		final double ax = v[0];
		final double ay = c * v[1] - s * v[2];
		final double az = s * v[1] + c * v[2];
		final double yc = Math.cos(-Math.PI / 2.0);
		final double ys = Math.sin(-Math.PI / 2.0);
		return new float[]{(float) (yc * ax + ys * az), (float) ay, (float) (-ys * ax + yc * az)};
	}

	/** The shader's star-local transform (include/dome.glsl: vfx_star_local_dir), the exact inverse. */
	private static float[] shaderStarLocal(final float[] d, final float angle) {
		final double c = Math.cos(angle);
		final double s = Math.sin(angle);
		final double y90x = d[2];
		final double y90y = d[1];
		final double y90z = -d[0];
		return new float[]{(float) y90x, (float) (c * y90y + s * y90z), (float) (-s * y90y + c * y90z)};
	}

	private static void near(final float actual, final float want, final String what) {
		if (Math.abs(actual - want) > 1.0e-4F) {
			throw new AssertionError(what + " = " + actual + ", want " + want);
		}
	}

	/** The largest `tile_scale` value a definition reaches (a constant, or the top of its keyframes). */
	private static float maxTile(final VFXDefinition definition) {
		final VFXDefinition.ParamSpec spec = definition.getParams().get("tile_scale");
		require(spec != null, "no tile_scale param");
		if (spec.keyframes().isEmpty()) {
			return spec.constant();
		}
		float max = 0.0F;
		for (final Keyframe keyframe : spec.keyframes()) {
			max = Math.max(max, keyframe.value());
		}
		return max;
	}

	private static VFXDefinition parse(final String namespace, final String name, final String json) {
		return VFXDefinition.parse(Identifier.fromNamespaceAndPath(namespace, name),
			JsonParser.parseString(json).getAsJsonObject());
	}

	private static void require(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkJava, [System.Text.UTF8Encoding]::new($false))

Push-Location $repoRoot
try {
	& $javac "@$cpFile" -d $checkDir $checkSrc
	if ($LASTEXITCODE -ne 0) { throw "javac failed" }
	& $java "@$cpFile" SkyPatternCheck $builtinPath $demoDir
	if ($LASTEXITCODE -ne 0) { throw "SkyPatternCheck failed" }
} finally {
	Pop-Location
}
Write-Host "sky_pattern check OK."
exit 0

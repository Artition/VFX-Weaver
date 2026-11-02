# Dev-only guard for the text_overlay effect.
#
# Pins the wiring as literal text, because none of it is exercised by a build:
#   * VFXEffectType declares TEXT_OVERLAY("text_overlay"), so the datapack `type` resolves, and
#     leaves it out of the post-processing set, because text is drawn, not composited;
#   * VFXDefinition carries the optional `text` block through parse, through withParams and out
#     through a getter - the same three places screenImage goes through, because a definition that
#     loses its spec when params are merged renders nothing;
#   * the schema registers the type with exactly the params the renderer reads, and bounds the
#     enum-like ones so the editor cannot send nonsense;
#   * the renderer keeps the per-character exit wiring - the body a falling unit is handed, the
#     detach schedule, the per-character stagger and the finished-record lifecycle - because none of
#     it compiles into anything a build would notice if it went missing;
#   * the call-time text override stays wired: the payload's textOverride field, the
#     VFXTextSpec.withContent() method, the command's MAX_TEXT_OVERRIDE cap and the one place the
#     manager applies it.
#
# Nothing here compiles GLSL or draws a pixel. It pins the text the two halves must agree on.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-text-overlay.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

$typePath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXEffectType.java"
$specPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXTextSpec.java"
$defPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\effect\VFXDefinition.java"
$schemaPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\editor\VFXEffectSchema.java"
$rendererPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\render\VFXTextOverlayRenderer.java"

$failures = New-Object System.Collections.Generic.List[string]

function Require-Pattern([string]$path, [string]$pattern, [string]$why) {
	if (-not (Test-Path $path)) { $script:failures.Add("missing file: $path"); return }
	$text = Get-Content -Raw -Path $path
	if ($text -notmatch $pattern) { $script:failures.Add("$([IO.Path]::GetFileName($path)): $why") }
}

# --- the type -------------------------------------------------------------------------------
Require-Pattern $typePath 'TEXT_OVERLAY\("text_overlay"\)' "TEXT_OVERLAY is not declared"
Require-Pattern $typePath 'this != TEXT_OVERLAY' "TEXT_OVERLAY is not excluded from isPostProcessing()"

# --- the structural spec --------------------------------------------------------------------
Require-Pattern $specPath 'class VFXTextSpec' "VFXTextSpec is missing"
foreach ($field in 'content', 'mode', 'font', 'align', 'anchor', 'split') {
	Require-Pattern $specPath ('"' + $field + '"') "the spec does not read '$field'"
}

# --- carried through the definition ---------------------------------------------------------
Require-Pattern $defPath 'VFXTextSpec text' "VFXDefinition does not hold the text spec"
Require-Pattern $defPath 'VFXTextSpec\.parse\(' "VFXDefinition does not parse the text spec"
Require-Pattern $defPath 'getText\(' "VFXDefinition has no getText()"

# --- the schema ------------------------------------------------------------------------------
Require-Pattern $schemaPath 'TEXT_OVERLAY' "the schema does not register TEXT_OVERLAY"
foreach ($param in 'reveal', 'exit', 'exit_at', 'exit_stagger', 'exit_speed', 'gravity',
		'spin_x', 'spin_y', 'spin_z', 'tumble', 'collision', 'bounce', 'friction', 'hold',
		'fade_out',
		'rot_x', 'rot_y', 'rot_z', 'angle', 'per_char_anim', 'stagger', 'wrap_width', 'gradient',
		'shadow', 'outline', 'billboard', 'brightness', 'two_sided', 'through_blocks') {
	Require-Pattern $schemaPath ('"' + $param + '"') "the schema is missing param '$param'"
}

# --- the exit wiring in the renderer ----------------------------------------------------------
# 'createFall' is what hands a detaching unit to its body; the body itself is what the fall is now,
# so both names are pinned along with the interpolation fraction that draws the 20 Hz step smoothly.
foreach ($pattern in 'createFall\(', 'VFXTextBody', 'lastFraction', 'detachTime', 'exit_stagger',
		'finished', 'setBillboardConstraints',
		'setBrightnessOverride', 'billboard\(', 'brightnessLevel\(', 'two_sided',
		'FLAG_SEE_THROUGH', 'through_blocks', 'holdOf\(') {
	Require-Pattern $rendererPath $pattern "the renderer lost '$pattern' from the per-character exit"
}

# --- the guide page is added in Task 6; until then the guard must not demand it ---------------

# --- the call-time text override (Task B): the field, the spec method and its cap -------------
$payloadPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\network\VFXTriggerPayload.java"
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\effect\VFXEffectManager.java"
$commandPath = Join-Path $repoRoot "src\main\java\dev\vfxweaver\command\VFXCommand.java"

Require-Pattern $payloadPath 'textOverride' "the payload lost the textOverride field"
Require-Pattern $payloadPath 'MAX_TEXT_OVERRIDE' "the payload lost the MAX_TEXT_OVERRIDE cap"
Require-Pattern $specPath 'withContent\(' "VFXTextSpec has no withContent()"
Require-Pattern $managerPath 'withContent\(' "VFXEffectManager no longer applies the override with withContent()"
Require-Pattern $commandPath 'MAX_TEXT_OVERRIDE' "the command no longer caps the text override"

if ($failures.Count -gt 0) {
	$failures | ForEach-Object { "FAIL  $_" }
	exit 1
}
"text_overlay guard OK"

# Dev-only guard for the mask-pipeline batch (2026-09-20). Static assertions over the exact
# contracts this batch fixed, plus a compiled standalone check:
#   * MaskParserCheck - the parser must reject a right-nested composition, a third custom leaf, a
#                       second block leaf and an invalid top-level space, and must keep the
#                       parse-time softness default on the .soft slot.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-mask-pipeline.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$main = Join-Path $repoRoot "src\main\java\dev\vfxweaver"
$client = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client"

function Read-Source([string]$path) { return [System.IO.File]::ReadAllText($path) }

$parser = Read-Source (Join-Path $main "mask\VFXMaskParser.java")
$mask = Read-Source (Join-Path $main "mask\VFXMask.java")
$shapeKind = Read-Source (Join-Path $main "mask\VFXMaskShapeKind.java")
$customShape = Read-Source (Join-Path $main "mask\VFXCustomShape.java")
$registry = Read-Source (Join-Path $main "mask\VFXShapeRegistry.java")
$uniforms = Read-Source (Join-Path $client "postprocessing\VFXMaskUniforms.java")
$geometry = Read-Source (Join-Path $client "postprocessing\VFXMaskBlockGeometry.java")
$fieldEnv = Read-Source (Join-Path $client "postprocessing\VFXFieldEnv.java")
$gameRendererMixin = Read-Source (Join-Path $client "mixin\GameRendererMixin.java")
$variants = Read-Source (Join-Path $client "postprocessing\VFXMaskShaderVariants.java")
$post = Read-Source (Join-Path $client "postprocessing\VFXPostProcessingManager.java")
$fill = Read-Source (Join-Path $main "mask\VFXMaskFill.java")
$selection = Read-Source (Join-Path $main "mask\VFXMaskBlockSelection.java")
$blockShader = Read-Source (Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_block_geometry.fsh")
$blockVertexShader = Read-Source (Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\world\mask_block_geometry.vsh")
$coverage = Read-Source (Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders\post\mask_coverage.fsh")

# The geometry source with its comment lines removed, for the pins that must match code and not the
# javadoc prose that explains the same contract (the UV javadoc names the vanilla bake calls on
# purpose, so a plain text pin would trip on its own documentation).
$geometryCode = (($geometry -split "\r?\n") | Where-Object { $_ -notmatch '^\s*(\*|//)' }) -join "`n"
# Same for the vertex stage: its header explains why ModelViewMat is not read, so the pin must look
# at the code only.
$blockVertexCode = (($blockVertexShader -split "\r?\n") | Where-Object { $_ -notmatch '^\s*//' }) -join "`n"
# And the field env's, for the pins that must not match its javadoc prose.
$fieldEnvCode = (($fieldEnv -split "\r?\n") | Where-Object { $_ -notmatch '^\s*(\*|//)' }) -join "`n"

$problems = New-Object System.Collections.Generic.List[string]

# 1) Animated softness: the writer must read the .soft slot, not the parse-time default.
if ($uniforms -notmatch 'slotValue\(mask, effect, primitive\.softnessSlot\(\), primitive\.softnessDefault\(\)\)') {
	$problems.Add("VFXMaskUniforms: rows[i][0].z does not read the animated softness slot")
}

# 2) Composition nesting: a right operand that is itself a composition must be rejected.
if ($parser -notmatch 'if \(!right\.ops\(\)\.isEmpty\(\)\)') {
	$problems.Add("VFXMaskParser: a right-nested composition is not rejected")
}

# 3) Caps: the custom-leaf and block-leaf counts must be enforced at parse.
if ($parser -notmatch 'customLeaves > VFXCustomShape\.MAX_CUSTOM_LEAVES') {
	$problems.Add("VFXMaskParser: the custom-leaf cap is not enforced")
}
if ($parser -notmatch 'blockLeaves > 1') {
	$problems.Add("VFXMaskParser: a second block leaf is not rejected")
}

# 3b) Shader guard: a bad custom row must not alias row 0.
if ($coverage -notmatch 'row < 0 \|\| row >= MASK_MAX_CUSTOM_LEAVES') {
	$problems.Add("mask_coverage.fsh: the custom-row range guard is missing")
}

# 5) Composed leaf falloff: the leaf softness must replace the hard-coded 0.25.
if ($coverage -match 'clamp\(0\.5 - d / 0\.25') {
	$problems.Add("mask_coverage.fsh: vfx_composed_leaf still hard-codes the 0.25 falloff")
}
if ($coverage -notmatch 'vfx_composed_leaf\(row, world, texCoord, so\.z\)') {
	$problems.Add("mask_coverage.fsh: vfx_composed_leaf does not receive the leaf softness")
}

# 4) Block centre: the scan centre must resolve the animated centre slots, not just the literal.
if ($geometry -notmatch 'blockCenter\(final VFXMask mask, final VFXMaskPrimitive primitive, final VFXActiveEffect effect, final VFXMaskBlockSelection selection\)') {
	$problems.Add("VFXMaskBlockGeometry.blockCenter: the mask/effect slot resolution is missing")
}
if ($geometry -notmatch 'VFXMaskUniforms\.slotValue\(mask, effect, slots\[0\], defaults\[0\]\)') {
	$problems.Add("VFXMaskBlockGeometry.blockCenter: the centre slots are not resolved through slotValue")
}

# 7) The block selection must be cached and its model quads resolved once.
if ($geometry -notmatch 'private static final Map<VFXMaskBlockSelection, SelectionCache> selectionCaches') {
	$problems.Add("VFXMaskBlockGeometry: the selection cache is missing")
}
if ($geometry -match 'hasBlockModelGeometry\(state\)') {
	$problems.Add("VFXMaskBlockGeometry.select: the double model resolve (hasBlockModelGeometry) is still present")
}

# 8) clearGeometry must not pretend to fail.
if ($geometry -match 'private static boolean clearGeometry\(') {
	$problems.Add("VFXMaskBlockGeometry.clearGeometry: still boolean (the caller's failure branch checks nothing)")
}

# 9) Variant re-validation: reseed must rebuild from the live base; the dead error accessor is gone.
if ($variants -notmatch 'injectedSource\(key\)') {
	$problems.Add("VFXMaskShaderVariants: reseed/compileVariant do not rebuild the injected source")
}
if ($variants -match 'public static @Nullable String error\(final String key\)') {
	$problems.Add("VFXMaskShaderVariants.error: the dead accessor is still present")
}
if ($registry -notmatch 'public void setChangeListener\(' -or $registry -notmatch 'notifyChanged\(\);') {
	$problems.Add("VFXShapeRegistry: the registration change listener is missing (stale variants on re-registration)")
}

# 6) Depth gate: the coverage prepass must fail closed when depth is not trusted; this matches the
#    surface_pattern gate and disables the depth-tested block pass.
if ($post -notmatch 'mask\.needsDepth\(\) && !depthReady') {
	$problems.Add("VFXPostProcessingManager.runCoveragePrepass: the coverage prepass is not depth-gated")
}
if ($post -notmatch 'VFXMaskBlockGeometry\.render\(encoder, geometry, mainTarget, entryMask, entry\.getValue\(\), depthReady\)') {
	$problems.Add("VFXPostProcessingManager: the block geometry pass is not depth-gated")
}

# 11) A freshly created coverage target must be cleared (an effect starting after layer 0).
if ($post -notmatch '(?s)mask coverage.*?clearTarget\(created\)') {
	$problems.Add("VFXPostProcessingManager.ensureMaskTargets: a new coverage target is not cleared")
}

# 14) Arena retirement: retired rings must survive one extra endFrame.
if ($post -notmatch '(?s)for \(final MappableRingBuffer old : this\.retiring\) \{\s*old\.close\(\);\s*\}\s*this\.retiring\.clear\(\);\s*this\.retiring\.addAll\(this\.retired\);') {
	$problems.Add("VFXPostProcessingManager.UniformArena.endFrame: retired rings are not kept one extra frame")
}

# 16) Dead code removed.
if ($mask -match 'public float softness\(\)') {
	$problems.Add("VFXMask.softness(): the unreferenced method is still present")
}
if ($shapeKind -match 'integerParameter') {
	$problems.Add("VFXMaskShapeKind.integerParameter(): the unreferenced method is still present")
}

# 10) The composed custom shape is literal-only (javadoc corrected, leaf params not threaded).
if ($customShape -notmatch 'a composed leaf''s parts are literal-only') {
	$problems.Add("VFXCustomShape: the javadoc still claims the leaf params drive the composed parts")
}

# 17) The block list: an authored selection that shares the block leaf's scratch, its factor-y
#     rule and its kind code, but reads the block texture's alpha and can ask for the outline band.
if ($fill -notmatch 'HOLLOW\("hollow"\)') {
	$problems.Add("VFXMaskFill: the HOLLOW fill is missing (the block list's outline band)")
}
if ($fill -notmatch 'SOLID=0, STROKE=1, HOLLOW=2') {
	$problems.Add("VFXMaskFill: the ordinal contract (SOLID=0, STROKE=1, HOLLOW=2) is not stated")
}
if ($parser -notmatch '"block_list"\.equalsIgnoreCase') {
	$problems.Add("VFXMaskParser: the 'block_list' shape name is not parsed")
}
if ($parser -notmatch 'hasPositions == hasRegion') {
	$problems.Add("VFXMaskParser: a block list does not require exactly one of positions/region")
}
if ($parser -notmatch 'VFXMaskBlockSelection\.MAX_REGION_BLOCKS') {
	$problems.Add("VFXMaskParser: the block list region volume is not bounded")
}
if ($parser -notmatch '(?s)fill == VFXMaskFill\.HOLLOW && !blockListLeaf') {
	$problems.Add("VFXMaskParser: 'hollow' is not rejected outside a block list")
}
if ($parser -notmatch '(?s)fill == VFXMaskFill\.STROKE && blockListLeaf') {
	$problems.Add("VFXMaskParser: 'stroke' is not rejected on a block list (the band is 'hollow')")
}
if ($selection -notmatch 'public boolean listed\(\)') {
	$problems.Add("VFXMaskBlockSelection: the positions/region discriminator is missing")
}

# 18) Alpha-aware coverage: the geometry pass bakes the list, samples the block atlas and writes the
#     outer/inner channels in at most two draws; the coverage shader picks the channel from the fill.
if ($geometry -notmatch 'private static List<SelectedBlock> bakeListed\(') {
	$problems.Add("VFXMaskBlockGeometry: the authored block list is not baked/cached")
}
if ($geometry -notmatch 'getAtlasOrThrow\(net\.minecraft\.data\.AtlasIds\.BLOCKS\)') {
	$problems.Add("VFXMaskBlockGeometry: the block texture atlas is not resolved")
}
if ($geometry -notmatch 'UVPair\.unpackU\(') {
	$problems.Add("VFXMaskBlockGeometry: the baked atlas UV is not carried to the vertex")
}
# The baked UV is already atlas-space (FaceBakery packs sprite.getU(localU)); re-mapping it through
# the sprite rect the way surface_pattern must for its 0..1 datapack rect samples a sub-crop of the
# sprite and mis-places the cutout alpha. Pin both halves: the raw unpack, and the absence of any
# sprite-rect remap. Every remap form needs the rect from somewhere, so forbidding the accessors (and
# the lerp helper, and naming the sprite type at all) catches `getU0() + u * du`, a local `u0/u1`
# pair, `sprite.getU(u)` and a stored sprite alike. Matched against code only, so the javadoc that
# spells the rect out in prose does not trip it.
if ($geometryCode -match 'getU0|getU1|getV0|getV1|\.getU\(|\.getV\(|Mth\.lerp\(|spriteRect|TextureAtlasSprite') {
	$problems.Add("VFXMaskBlockGeometry: the atlas UV is being re-mapped through the sprite rect; baked quad UVs are already atlas-space")
}
if ($geometry -notmatch 'the model bakery already') {
	$problems.Add("VFXMaskBlockGeometry: the javadoc no longer states that the baked UV is already atlas-space")
}
if ($geometry -notmatch '(?s)if \(selection\.listed\(\)\) \{\s*return true;') {
	$problems.Add("VFXMaskBlockGeometry.SelectionCache: a listed selection is still keyed by an animated centre/radius")
}
# One baked selection per mask (two masked effects must not evict each other) with a frame-scoped
# eviction: the manager marks the frame, the pass drops what the previous frame did not use.
if ($geometry -notmatch 'private static final Map<VFXMaskBlockSelection, SelectionCache> selectionCaches') {
	$problems.Add("VFXMaskBlockGeometry: the selection cache is a single slot again (two block masks thrash it)")
}
if ($geometry -notmatch 'public static void beginFrame\(\)') {
	$problems.Add("VFXMaskBlockGeometry: the per-frame cache eviction hook is missing")
}
# The eviction predicate itself: drop what the previous frame did not stamp. A regression to
# "rebuild whenever the entry is older than this call" or to no eviction at all fails here.
if ($geometryCode -notmatch 'lastFrame < frameIndex - 1') {
	$problems.Add("VFXMaskBlockGeometry.beginFrame: the eviction predicate is not 'lastFrame < frameIndex - 1'")
}
# An empty bake is retried only for an explicit positions list: an all-air/water region is
# legitimately empty, and re-walking up to MAX_REGION_BLOCKS every frame would be the per-frame world
# walk this pass exists to avoid. Matched against code only.
if ($geometryCode -notmatch '!selection\.positions\(\)\.isEmpty\(\) && blocks\.isEmpty\(\)' -and $geometryCode -notmatch 'positionListed && blocks\.isEmpty\(\)') {
	$problems.Add("VFXMaskBlockGeometry.select: the empty-bake retry is not limited to an explicit positions list")
}
if ($post -notmatch 'VFXMaskBlockGeometry\.beginFrame\(\)') {
	$problems.Add("VFXPostProcessingManager: the block-geometry pass is not told where a frame starts (cache never evicts)")
}
# One block-coordinate convention for the parse-time region budget and the geometry walk.
if ($selection -notmatch 'public static int blockCoordinate\(final float value\)') {
	$problems.Add("VFXMaskBlockSelection: the shared block-coordinate convention is missing")
}
if ($parser -notmatch 'VFXMaskBlockSelection\.blockCoordinate\(region\[3\]\)') {
	$problems.Add("VFXMaskParser: the region budget does not use the shared block-coordinate convention")
}
if ($geometry -notmatch 'VFXMaskBlockSelection\.blockCoordinate\(coordinates\[offset\]\)') {
	$problems.Add("VFXMaskBlockGeometry: the region/position walk does not use the shared block-coordinate convention")
}
if (([regex]::Matches($geometry, 'drawPass\(encoder, geometry, mainTarget, program, blocks')).Count -ne 2) {
	$problems.Add("VFXMaskBlockGeometry.render: expected exactly two drawPass calls (outer always, inner for the band)")
}
if ($blockShader -notmatch 'uniform sampler2D BlockAtlasSampler;') {
	$problems.Add("mask_block_geometry.fsh: the block atlas sampler is not declared")
}
if ($blockShader -notmatch 'vertexColor\.r > 0\.5 \? 1\.0 : 0\.0') {
	$problems.Add("mask_block_geometry.fsh: the outer/inner channel encoding is missing")
}
if ($coverage -notmatch 'vec2 blockCoverage = texture\(GeometryCoverageSampler, texCoord\)\.rg') {
	$problems.Add("mask_coverage.fsh: the block leaf does not read both geometry channels")
}
if ($coverage -notmatch 'shape_misc\[i\]\.x > 0\.5 \? blockCoverage\.g : blockCoverage\.r') {
	$problems.Add("mask_coverage.fsh: the block leaf does not take the band channel for a non-solid fill")
}

# 19) World-locked draw: the pass must carry its own transform (projection * viewRotation over the
#     camera-relative vertices) and must never read the frame's ModelViewMat, whose camera translation
#     would be applied a second time - the mask would then slide across the blocks as the camera moves.
if ($blockVertexCode -match 'ModelViewMat|dynamictransforms\.glsl') {
	$problems.Add("world/mask_block_geometry.vsh: the vertex stage reads ModelViewMat again (the camera is subtracted twice)")
}
if ($geometryCode -match 'setUniform\("DynamicTransforms"') {
	$problems.Add("VFXMaskBlockGeometry: the pass overrides the frame's DynamicTransforms again (arena + camera coupling)")
}
if ($geometryCode -notmatch 'gl_Position = ProjMat \* vec4\(' -and $blockVertexCode -notmatch 'vec4 clip = ProjMat \* vec4\(Position, 1\.0\);') {
	$problems.Add("world/mask_block_geometry.vsh: the vertex stage does not use the pass's own Projection uniform")
}
if ($geometryCode -notmatch 'private static GpuBufferSlice writeTransform\(final CommandEncoder encoder\)') {
	$problems.Add("VFXMaskBlockGeometry: the per-frame transform writer is missing")
}
if ($geometryCode -notmatch 'renderPass\.setUniform\("Projection", transform\)') {
	$problems.Add("VFXMaskBlockGeometry: the pass does not bind its own transform as Projection")
}

# 23) The mask must win at its own surface WITHOUT moving: the bias is a clip-space depth offset, so
#     x/y are byte-identical to the unbiased projection (a world-space nudge changes the projected
#     silhouette as the camera moves and reads as the mask sliding over the blocks). The sign follows
#     the node's depth convention, and the x-ray path must still skip the compare entirely.
if ($blockVertexCode -notmatch 'VFX_MASK_DEPTH_BIAS 1\.0e-6') {
	$problems.Add("world/mask_block_geometry.vsh: the clip-space depth bias is gone (the mask loses to its own blocks)")
}
if ($blockVertexCode -match 'VFX_MASK_DEPTH_NUDGE|nudged') {
	$problems.Add("world/mask_block_geometry.vsh: a world-space position nudge is back (that is the sideways parallax)")
}
if ($blockVertexCode -notmatch '#if VFX_DEPTH_REVERSED\s*clip\.z \+= VFX_MASK_DEPTH_BIAS \* clip\.w;\s*#else\s*clip\.z -= VFX_MASK_DEPTH_BIAS \* clip\.w;') {
	$problems.Add("world/mask_block_geometry.vsh: the bias does not follow the per-node depth convention (+= reversed, -= standard)")
}
if ($blockVertexCode -notmatch '#ifndef VFX_DEPTH_REVERSED') {
	$problems.Add("world/mask_block_geometry.vsh: the depth-convention define has no default (VFX_DEPTH_REVERSED is injected per node)")
}
if ($blockVertexCode -match 'clip\.(x|y) *[-+]?=') {
	$problems.Add("world/mask_block_geometry.vsh: the bias touches clip x/y (it must be depth only)")
}
if ($blockVertexCode -notmatch 'gl_Position = clip;') {
	$problems.Add("world/mask_block_geometry.vsh: the biased clip position is not what is emitted")
}
if ($blockShader -match '1\.0e-4') {
	$problems.Add("mask_block_geometry.fsh: the fixed 1.0e-4 depth bias is back")
}
if ($blockShader -notmatch '#define VFX_MASK_DEPTH_EPS 1\.0e-6') {
	$problems.Add("mask_block_geometry.fsh: the depth tolerance is not the quantisation-sized VFX_MASK_DEPTH_EPS")
}
if ($blockShader -notmatch 'if \(vertexColor\.a > 0\.5\) \{') {
	$problems.Add("mask_block_geometry.fsh: the x-ray path (occlude false) no longer bypasses the depth test")
}

# 24) The view-projection must include the frame's view-bob pose, in the same place vanilla puts it
#     (GameRenderer.renderLevel: `projectionMatrix * bobPose`, then the rotation). Without it the mask
#     oscillates sideways against the blocks while walking, because the state's viewRotationMatrix is
#     rotation-only. The pose is vanilla's own bobHurt/bobView, invoked rather than re-implemented.
if ($fieldEnvCode -notmatch '\.mul\(VFXWorldBob\.pose\(\)\)') {
	$problems.Add("VFXFieldEnv: the view-projection does not include the frame's view-bob pose")
}
if ($fieldEnvCode -notmatch '(?s)VIEW_ROTATION_PROJECTION\.set\(state\.projectionMatrix\)\s*\.mul\(VFXWorldBob\.pose\(\)\)\s*\.mul\(state\.viewRotationMatrix\)') {
	$problems.Add("VFXFieldEnv: the bob is not composed between the projection and the rotation (the world's own order)")
}
if ($gameRendererMixin -notmatch '@Invoker\("bobHurt"\)') {
	$problems.Add("GameRendererMixin: vanilla bobHurt is not invoked for the mask pose (a re-write would drift)")
}
if ($gameRendererMixin -notmatch '@Invoker\("bobView"\)') {
	$problems.Add("GameRendererMixin: vanilla bobView is not invoked for the mask pose")
}
if ($gameRendererMixin -notmatch 'optionsRenderState\.bobView') {
	$problems.Add("GameRendererMixin: the bob pose ignores the View Bobbing option (the world checks it)")
}
if ($gameRendererMixin -notmatch 'VFXWorldBob\.publish\(') {
	$problems.Add("GameRendererMixin: the bob pose is never published to the mask")
}

# 20) Draw order: the geometry must be rasterised before the coverage prepass samples the scratch, or
#     the mask shows last frame's geometry (which also reads as motion while the camera moves).
$geometryPass = $post.IndexOf('VFXMaskBlockGeometry.beginFrame()')
$coveragePass = $post.IndexOf('runCoveragePrepass(encoder, samplerCache, mainTarget')
if ($geometryPass -lt 0 -or $coveragePass -lt 0 -or $geometryPass -gt $coveragePass) {
	$problems.Add("VFXPostProcessingManager: the block-geometry draw does not run before the coverage prepass")
}

# 22) The camera must come from the frame's own camera render state, read directly: the live
#     projection differs whenever the FOV moves, and the event-scoped hook is null on this path (the
#     pass runs from the mixin, outside the event callback). One state provides the matrices AND the
#     position, and a missing state warns instead of silently drifting.
# The direct read, per node: 26.1.2 calls GameRenderer#getGameRenderState(), 26.2 calls
# GameRenderer#gameRenderState(). Both spellings are pinned, and the *active* getter must be live code
# in this checkout (the other branch is a stonecutter comment), so deleting the call cannot pass on a
# commented line.
$getterPattern = 'renderer\.(get)?GameRenderState\(\)'
$getterActive = @(($fieldEnv -split "\r?\n") | Where-Object { $_ -match $getterPattern -and $_ -notmatch '^\s*(/\*|\*|//)' })
if ($getterActive.Count -eq 0) {
	$problems.Add("VFXFieldEnv: the active camera render state read is gone (only a commented line matches)")
}
if ($fieldEnv -notmatch 'renderer\.getGameRenderState\(\)') {
	$problems.Add("VFXFieldEnv: the 26.1.2 branch does not call GameRenderer#getGameRenderState()")
}
if ($fieldEnv -notmatch 'renderer\.gameRenderState\(\)') {
	$problems.Add("VFXFieldEnv: the 26.2 branch does not call GameRenderer#gameRenderState()")
}
if ($fieldEnv -notmatch 'game\.levelRenderState\.cameraRenderState') {
	$problems.Add("VFXFieldEnv: the camera state is not taken from levelRenderState.cameraRenderState")
}
if ($fieldEnvCode -notmatch 'if \(game == null \|\| game\.levelRenderState == null\)') {
	$problems.Add("VFXFieldEnv.frameCameraState: the render state chain is dereferenced without a null guard")
}
if ($fieldEnvCode -notmatch 'private static boolean adopt\(') {
	$problems.Add("VFXFieldEnv: no single-source adopt() for the state's matrices + position")
}
if ($fieldEnvCode -notmatch '(?s)VIEW_ROTATION_PROJECTION\.set\(state\.projectionMatrix\)\s*\.mul\(VFXWorldBob\.pose\(\)\)\s*\.mul\(state\.viewRotationMatrix\)') {
	$problems.Add("VFXFieldEnv: the frame matrices are not composed as projection * bob * viewRotation")
}
if ($fieldEnvCode -notmatch 'CAMERA\.set\(state\.pos\.x, state\.pos\.y, state\.pos\.z\)') {
	$problems.Add("VFXFieldEnv: the camera position is not taken from the same state as its matrices")
}
if ($fieldEnvCode -notmatch 'VFXFrameFov\.get\(\)' -or $fieldEnvCode -notmatch 'capturedFov > 0\.0F \? capturedFov') {
	$problems.Add("VFXFieldEnv: the 1.21.11 fallback does not use the captured (modulated) FOV")
}
if ($gameRendererMixin -notmatch 'VFXFrameFov\.set\(fov\)') {
	$problems.Add("GameRendererMixin: the rendered FOV is no longer captured for the 1.21.11 projection")
}
if ($fieldEnvCode -notmatch 'VFXLog\.warnOnce\(LOGGER, "fieldenv:camera-state"') {
	$problems.Add("VFXFieldEnv: a missing camera state falls back silently (no one-time warning)")
}
if ($fieldEnv -notmatch '(?s)//\? if >=26\.1 \{\s*(?://[^\n]*\r?\n\s*)*if \(Minecraft\.getInstance\(\)\.level != null\) \{\s*VFXLog\.warnOnce\(LOGGER, "fieldenv:camera-state"') {
	$problems.Add("VFXFieldEnv: the missing-state warning is not scoped to >=26.1 (1.21.11 would warn every session)")
}
if ($fieldEnv -notmatch 'getViewRotationProjectionMatrix\(') {
	$problems.Add("VFXFieldEnv: the live-camera fallback is gone (no matrices outside a published render event)")
}
# 21) A live positions list re-checks its own coordinates: the bake stores each listed state and any
#     difference re-bakes, so a placed/removed/changed block reaches the mask without a re-parse.
if ($geometryCode -notmatch 'private static boolean statesChanged\(final Level level, final VFXMaskBlockSelection selection, final BlockState\[\] states\)') {
	$problems.Add("VFXMaskBlockGeometry: the per-frame block-state re-check of a positions list is missing")
}
if ($geometryCode -notmatch 'level\.getBlockState\(blockPos\(positions\.get\(i\), 0\)\) != states\[i\]') {
	$problems.Add("VFXMaskBlockGeometry.statesChanged: a listed coordinate's state is not compared")
}
if ($geometryCode -notmatch 'states\[i\] = level\.getBlockState\(pos\)') {
	$problems.Add("VFXMaskBlockGeometry.bakeListed: the listed states are not recorded (air included)")
}

Write-Host "Mask pipeline check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "mask pipeline check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  static contracts OK"

# --- runnable check: compile and run MaskParserCheck ----------------------------------------
$jdkHome = $env:JAVA_HOME
$javac = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\javac.exe"))) { Join-Path $jdkHome "bin\javac.exe" } else { $null }
$java = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\java.exe"))) { Join-Path $jdkHome "bin\java.exe" } else { $null }
if (-not $javac) {
	$programFiles = $env:ProgramFiles
	$candidate = Get-ChildItem (Join-Path $programFiles "Java") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidate) {
		$javac = Join-Path $candidate.FullName "bin\javac.exe"
		$java = Join-Path $candidate.FullName "bin\java.exe"
	}
}
if (-not $javac -or -not (Test-Path $javac)) {
	Write-Error "mask pipeline check: no JDK found (set JAVA_HOME); static contracts passed."
	exit 1
}
$mainClasses = Join-Path $repoRoot "versions\26.1.2\build\classes\java\main"
if (-not (Test-Path (Join-Path $mainClasses "dev\vfxweaver\mask\VFXMaskParser.class"))) {
	Write-Error "mask pipeline check: build :26.1.2 first (missing $mainClasses)."
	exit 1
}
$loomCache = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom"
# The 26.1.2 node's deobfuscated jar, wherever this machine's Loom put it: the per-version
# merged-deobf directory on a 26.1.2 checkout, or the version-less `<major>` working cache Loom
# materialises for an unobfuscated node.
$mcJarCandidates = @(
	(Join-Path $loomCache "minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2"),
	(Join-Path $loomCache "minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1"),
	(Join-Path $loomCache "26.1")
)
$mcJar = $null
foreach ($candidate in $mcJarCandidates) {
	if (-not (Test-Path $candidate)) { continue }
	$mcJar = Get-ChildItem $candidate -Filter "*.jar" -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match "merged" } | Select-Object -First 1
	if ($mcJar) { break }
}
if (-not $mcJar) {
	Write-Error "mask pipeline check: the 26.1.2 minecraft merged jar was not found under $loomCache."
	exit 1
}
$jars = (Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch 'dev\.vfxweaver|maven\.modrinth' } |
	ForEach-Object { $_.FullName.Replace('\', '/') }) -join ';'

$checkDir = Join-Path $env:TEMP "vfxweaver-mask-check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "MaskParserCheck.java"
$cpFile = Join-Path $checkDir "cp.txt"
$cp = "versions/26.1.2/build/classes/java/main;$($checkDir.Replace('\', '/'));$($mcJar.FullName.Replace('\', '/'));$jars"
[System.IO.File]::WriteAllText($cpFile, "-cp `"$cp`"", [System.Text.UTF8Encoding]::new($false))

$checkJava = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskFill;
import dev.vfxweaver.mask.VFXMaskPrimitive;

/** The mask parser's batch contracts: composition nesting, family caps, space validation, softness. */
public final class MaskParserCheck {
	public static void main(final String[] args) {
		// A right-nested composition cannot be flattened into the shader's left fold -> reject.
		rejected("right-nested composition", "{\"op\":\"union\",\"a\":{\"shape\":\"circle\",\"center\":[0.5,0.5]},\"b\":{\"op\":\"intersection\",\"a\":{\"shape\":\"circle\",\"center\":[0.5,0.5]},\"b\":{\"shape\":\"rect\",\"center\":[0.5,0.5]}}}");
		// Left-nesting is the supported form.
		final VFXMask leftNested = parse("{\"op\":\"intersection\",\"a\":{\"op\":\"union\",\"a\":{\"shape\":\"circle\",\"center\":[0.5,0.5]},\"b\":{\"shape\":\"rect\",\"center\":[0.5,0.5]}},\"b\":{\"shape\":\"polygon\",\"center\":[0.5,0.5]}}");
		require(leftNested.primitives().size() == 3, "left-nested mask leaves = " + leftNested.primitives().size() + ", want 3");
		require(leftNested.ops().size() == 2, "left-nested mask ops = " + leftNested.ops().size() + ", want 2");
		// Third custom leaf exceeds MAX_CUSTOM_LEAVES.
		rejected("third custom leaf", "{\"op\":\"union\",\"a\":{\"op\":\"union\",\"a\":{\"shape\":\"vfxweaver:ringed_volume\",\"center\":[0,64,0]},\"b\":{\"shape\":\"vfxweaver:ringed_volume\",\"center\":[0,64,0]}},\"b\":{\"shape\":\"vfxweaver:ringed_volume\",\"center\":[0,64,0]}}");
		// Second block leaf shares one scratch.
		rejected("second block leaf", "{\"op\":\"union\",\"a\":{\"shape\":\"block\",\"blocks\":[\"minecraft:stone\"],\"center\":[0,0,0],\"radius\":4},\"b\":{\"shape\":\"block\",\"blocks\":[\"minecraft:dirt\"],\"center\":[0,0,0],\"radius\":4}}");
		// A present-but-invalid top-level space must throw, not default.
		rejected("invalid top-level space", "{\"space\":\"wrold\",\"shape\":\"circle\",\"center\":[0.5,0.5]}");
		// The parse-time softness default must land on the .soft slot.
		final VFXMask explicit = parse("{\"shape\":\"circle\",\"center\":[0.5,0.5],\"softness\":0.5}");
		require(explicit.slots().get("mask.p0.soft").defaultValue() == 0.5F, "explicit softness slot default = " + explicit.slots().get("mask.p0.soft").defaultValue());
		final VFXMask implicit = parse("{\"shape\":\"circle\",\"center\":[0.5,0.5]}");
		require(implicit.slots().get("mask.p0.soft").defaultValue() == 0.01F, "screen default softness = " + implicit.slots().get("mask.p0.soft").defaultValue());
		final VFXMask world = parse("{\"shape\":\"sphere\",\"center\":[0,64,0]}");
		require(world.slots().get("mask.p0.soft").defaultValue() == 0.25F, "world default softness = " + world.slots().get("mask.p0.soft").defaultValue());

		// The block list: an authored selection that is still a block leaf (one scratch, kind 6),
		// with the outline width as a normal animatable slot instead of a scanned radius.
		final VFXMask listed = parse("{\"shape\":\"block_list\",\"positions\":[[1,2,3],[4,5,6],[1,2,3]]}");
		require(listed.primitives().size() == 1, "block_list leaves = " + listed.primitives().size());
		require(listed.primitives().get(0).family() == VFXMaskPrimitive.Family.BLOCK, "block_list family = " + listed.primitives().get(0).family());
		require(listed.primitives().get(0).kindCode() == 6, "block_list kindCode = " + listed.primitives().get(0).kindCode());
		require(listed.primitives().get(0).blockSelection().positions().size() == 3, "block_list positions = " + listed.primitives().get(0).blockSelection().positions().size());
		require(listed.primitives().get(0).blockSelection().region() == null, "block_list must not also carry a region");
		require(listed.primitives().get(0).blockSelection().listed(), "block_list selection is not marked as listed");
		require(listed.primitives().get(0).parameterSlots().length == 0, "block_list carries a scan radius slot");
		require(listed.hasBlockLeaf(), "block_list is not counted as a block leaf");
		require(listed.slots().get("mask.p0.stroke").defaultValue() == 0.05F, "block_list default stroke = " + listed.slots().get("mask.p0.stroke").defaultValue());
		require(VFXMaskFill.HOLLOW.ordinal() == 2 && VFXMaskFill.STROKE.ordinal() == 1, "fill ordinals moved");
		// An inclusive region box, normalised and band-filled with an authored width.
		final VFXMask band = parse("{\"shape\":\"block_list\",\"region\":[3,3,3,0,0,0],\"fill\":\"hollow\",\"stroke_width\":0.2}");
		require(band.primitives().get(0).fill() == VFXMaskFill.HOLLOW, "hollow was not parsed on a block_list");
		require(band.primitives().get(0).blockSelection().region()[0] == 0.0F, "region corners are not normalised");
		require(band.primitives().get(0).blockSelection().region()[3] == 3.0F, "region corners are not normalised");
		require(band.slots().get("mask.p0.stroke").defaultValue() == 0.2F, "authored stroke_width = " + band.slots().get("mask.p0.stroke").defaultValue());
		// Both selections share the single geometry scratch, so the one-block-leaf rule spans them.
		rejected("block + block_list", "{\"op\":\"union\",\"a\":{\"shape\":\"block\",\"blocks\":[\"minecraft:stone\"],\"center\":[0,0,0],\"radius\":4},\"b\":{\"shape\":\"block_list\",\"positions\":[[0,0,0]]}}");
		rejected("block_list with positions and region", "{\"shape\":\"block_list\",\"positions\":[[0,0,0]],\"region\":[0,0,0,0,0,0]}");
		rejected("block_list with neither", "{\"shape\":\"block_list\"}");
		rejected("block_list with a scan radius", "{\"shape\":\"block_list\",\"positions\":[[0,0,0]],\"radius\":4}");
		rejected("hollow on a circle", "{\"shape\":\"circle\",\"center\":[0.5,0.5],\"fill\":\"hollow\"}");
		rejected("stroke on a block_list", "{\"shape\":\"block_list\",\"positions\":[[0,0,0]],\"fill\":\"stroke\",\"stroke_width\":0.1}");
		rejected("oversized block_list region", "{\"shape\":\"block_list\",\"region\":[0,0,0,4096,4096,4096]}");
		// The block-coordinate convention is floor (the block that contains the point), and the region
		// budget counts with it: -0.5 .. 0.5 is the two-block box -1..0, not a one-block box.
		require(dev.vfxweaver.mask.VFXMaskBlockSelection.blockCoordinate(-0.5F) == -1, "blockCoordinate(-0.5) = " + dev.vfxweaver.mask.VFXMaskBlockSelection.blockCoordinate(-0.5F));
		require(dev.vfxweaver.mask.VFXMaskBlockSelection.blockCoordinate(0.5F) == 0, "blockCoordinate(0.5) = " + dev.vfxweaver.mask.VFXMaskBlockSelection.blockCoordinate(0.5F));
		final VFXMask negative = parse("{\"shape\":\"block_list\",\"region\":[-0.5,-0.5,-0.5,0.5,0.5,0.5]}");
		require(negative.primitives().get(0).blockSelection().region()[0] == -0.5F, "a negative fractional corner is not kept for the shared floor convention");


		System.out.println("mask parser check OK: right-nested/third-custom/second-block/bad-space rejected; block_list positions/region/hollow parsed; left-nesting and softness defaults hold");
	}

	private static VFXMask parse(final String json) {
		return VFXMask.parse("check", JsonParser.parseString(json).getAsJsonObject());
	}

	private static void rejected(final String what, final String json) {
		try {
			parse(json);
			throw new AssertionError(what + " was accepted; it must throw");
		} catch (final IllegalArgumentException expected) {
			// expected
		}
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
	& $java "@$cpFile" MaskParserCheck
	if ($LASTEXITCODE -ne 0) { throw "MaskParserCheck failed" }
} finally {
	Pop-Location
}
Write-Host "Mask pipeline check OK."
exit 0

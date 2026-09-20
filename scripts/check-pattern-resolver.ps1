# Dev-only guard for the textured surface_pattern client resolver (2026-09-20).
#
# The 26.x AtlasManager keeps TWO maps: `atlasById`, keyed by the atlas *definition* id
# (AtlasIds.BLOCKS = minecraft:blocks, AtlasIds.ITEMS = minecraft:items) which serves
# getAtlasOrThrow, and `atlasByTexture`, keyed by the atlas *texture* id
# (TextureAtlas.LOCATION_BLOCKS = minecraft:textures/atlas/blocks.png). The sprite lookup
# (`get(SpriteId)`) is keyed by the *texture* id — spriteLookup is populated with
# `new SpriteId(config.textureId, spriteId)`. So: getAtlasOrThrow takes the definition id,
# the SpriteId takes `TextureAtlas.location()` (the texture id). Passing the definition id to
# the SpriteId misses the lookup and throws "Invalid atlas texture id: minecraft:blocks"; passing
# the texture id to getAtlasOrThrow throws "Invalid atlas id" — both are the bugs this check
# exists to prevent from creeping back. It also asserts the fail-closed contract: an
# authored-but-unresolved texture must return a null view (coverage 0), never bind a procedural
# figure or a full-screen fill, and the missing-sprite test must use the shared missing location
# (TextureAtlasSprite has no isMissing() on 26.1.2). The standalone resolver must complete the
# authored `…/textures/…` id with `.png` (TextureManager passes the id straight to the resource
# manager, which looks for the extensionless path otherwise).
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-pattern-resolver.ps1
# Exits 1 (after listing the problem) on a mismatch; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$manager = [System.IO.File]::ReadAllText($managerPath)

$problems = New-Object System.Collections.Generic.List[string]

# The atlas id constants must name AtlasIds, not the LOCATION_* texture ids.
if ($manager -notmatch 'case BLOCK -> net\.minecraft\.data\.AtlasIds\.BLOCKS') {
	$problems.Add("the BLOCK source does not resolve through AtlasIds.BLOCKS")
}
if ($manager -notmatch 'case ITEM -> net\.minecraft\.data\.AtlasIds\.ITEMS') {
	$problems.Add("the ITEM source does not resolve through AtlasIds.ITEMS")
}
if ($manager -match 'case (BLOCK|ITEM) -> net\.minecraft\.client\.renderer\.texture\.TextureAtlas\.LOCATION_') {
	$problems.Add("a block/item source still resolves through TextureAtlas.LOCATION_* (the atlas texture id, which throws 'Invalid atlas id')")
}

# getAtlasOrThrow takes the definition id, but the SpriteId must be keyed by the atlas texture id
# (TextureAtlas.location()), or the sprite lookup misses and throws "Invalid atlas texture id".
if ($manager -notmatch 'getAtlasOrThrow\(atlasDefinition\)') {
	$problems.Add("the resolver does not getAtlasOrThrow the atlas definition id")
}
if ($manager -notmatch 'atlas\.location\(\)') {
	$problems.Add("the resolver does not take the atlas texture id from TextureAtlas.location()")
}
if ($manager -notmatch 'findSprite\(atlasManager, atlasTextureId, textureId\)') {
	$problems.Add("findSprite is not keyed by the atlas texture id (atlasTextureId), so the sprite lookup misses")
}
if ($manager -match 'new net\.minecraft\.client\.resources\.model\.sprite\.SpriteId\(\s*atlasDefinition') {
	$problems.Add("a SpriteId is keyed by the atlas definition id (must be the texture id from atlas.location())")
}

# A standalone id carries its `.png` extension on 26.x; the resolver must complete it.
if ($manager -notmatch 'private static net\.minecraft\.resources\.Identifier withPng\(') {
	$problems.Add("the resolver has no withPng helper for standalone texture ids")
}
if ($manager -notmatch 'withPng\(textureId\)') {
	$problems.Add("the standalone branch does not complete the texture id with .png")
}

# The fail-closed contract: every unresolved branch must hand the shader a null view.
$unresolved = @(
	"is missing from atlas",
	"could not be resolved"
)
foreach ($needle in $unresolved) {
	if ($manager -notmatch [regex]::Escape($needle)) {
		$problems.Add("the resolver has no '$needle' failure message")
	}
}
if ($manager -notmatch 'return new PatternTexture\(null,') {
	$problems.Add("no fail-closed PatternTexture(null, ...) branch in the resolver")
}

# The missing-sprite test must not call TextureAtlasSprite.missing()/isMissing() (absent on 26.1.2).
if ($manager -match 'sprite\.(missing|isMissing)\(\)') {
	$problems.Add("the resolver calls TextureAtlasSprite.missing()/isMissing(), which does not exist on 26.1.2")
}
if ($manager -notmatch 'MissingTextureAtlasSprite\.getLocation\(\)') {
	$problems.Add("the missing-sprite test does not use MissingTextureAtlasSprite.getLocation()")
}
if ($manager -notmatch 'private static boolean isMissingSprite\(') {
	$problems.Add("the resolver has no isMissingSprite helper")
}

# The sprite lookup and its helpers are 26.1+ only (the AtlasManager/SpriteId package does not exist on 1.21.11).
if ($manager -notmatch '//\? if >=26\.1 \{\r?\n\t\t/\*\*\r?\n\t\t \* Finds a block/item sprite') {
	$problems.Add("findSprite is not inside a >=26.1 guard (the sprite package does not exist on 1.21.11)")
}

Write-Host "Textured surface_pattern resolver check"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "textured surface_pattern resolver check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "Resolver OK: getAtlasOrThrow by definition id, SpriteId by atlas.location() texture id, .png-completed standalone ids, fail-closed null views, shared missing-sprite test, 26.1-only helpers."
exit 0

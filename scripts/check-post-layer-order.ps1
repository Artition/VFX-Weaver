# Dev-only guard for the per-effect compositing order (the "order" param).
#
# Checks the static contracts of ordering screen post-effects inside their screen layer:
#   * orderedRank() reads the param as `getParam("order", 0.0F)`, so an effect that never sets it
#     keeps the default 0 and therefore the behaviour it had before this existed;
#   * it sanitises non-finite values, because an `expr` can produce NaN and an inconsistent
#     comparator makes List.sort throw;
#   * the sort runs on the layer's already-filtered list and compares orderedRank, so "screen_layer"
#     stays the outer axis and equal orders keep the manager's order - List.sort is stable;
#   * the sort sits BETWEEN the screen_layer filter and the chain build, so it can only reorder the
#     effects that layer already selected;
#   * the screen_layer filter itself is untouched (still clamped to 0..2 by the same expression).
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-post-layer-order.ps1
# Exits 1 (after listing the problem) on a regression; 0 when the contract holds.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$managerPath = Join-Path $repoRoot "src\client\java\dev\vfxweaver\client\postprocessing\VFXPostProcessingManager.java"
$manager = [System.IO.File]::ReadAllText($managerPath)

$problems = New-Object System.Collections.Generic.List[string]

# 1) The rank reader: the param name, the default, and the non-finite fallback.
if ($manager -notmatch 'private static float orderedRank\(final VFXActiveEffect effect\)') {
	$problems.Add("orderedRank(VFXActiveEffect) is missing")
}
if ($manager -notmatch 'effect\.getParam\("order",\s*0\.0F\)') {
	$problems.Add("orderedRank does not read the param as getParam(""order"", 0.0F) - the default 0 is what keeps every existing pack unchanged")
}
if ($manager -notmatch 'Float\.isFinite\(order\) \? order : 0\.0F') {
	$problems.Add("orderedRank does not sanitise a non-finite order - a NaN from an expr would make the comparator inconsistent and List.sort would throw")
}

# 2) The sort itself: on the layer list, by the cached rank, and stable (List.sort, not a heap or a
#    tree). The rank is read once per effect, never inside the comparator.
$sortMatch = [regex]::Match($manager, 'active\.sort\(\(a, b\) -> Float\.compare\(this\.rankCache\.get\(a\), this\.rankCache\.get\(b\)\)\);')
if (-not $sortMatch.Success) {
	$problems.Add("the active list is not sorted by the cached rank (rankCache.get(a) vs rankCache.get(b))")
}
if ($manager -match 'Float\.compare\(orderedRank') {
	$problems.Add("the comparator evaluates orderedRank directly again - it is called ~2 * n log n times per frame and getParam can run a graph/binding/expr")
}
if ($manager -notmatch 'this\.rankCache\.put\(effect, orderedRank\(effect\)\);') {
	$problems.Add("the rank cache is not filled once per effect before the sort")
}
if ($manager -notmatch 'this\.rankCache\.clear\(\);') {
	$problems.Add("the rank cache is never cleared - an effect that stopped would keep its slot")
}
if ($manager -notmatch 'private final Map<VFXActiveEffect, Float> rankCache = new IdentityHashMap<>\(\);') {
	$problems.Add("rankCache is missing or is not an IdentityHashMap (the key is the live effect object)")
}
if ($manager -notmatch 'import java\.util\.IdentityHashMap;') {
	$problems.Add("java.util.IdentityHashMap is not imported")
}
if ($manager -match 'PriorityQueue|TreeSet|TreeMap<[^>]*VFXActiveEffect') {
	$problems.Add("ordering was moved onto a non-stable structure; an equal-order tie must keep the manager's order")
}

# 3) Position: after the screen_layer filter, before the chain build.
$filterAt = $manager.IndexOf('Math.round(Mth.clamp(effect.getParam("screen_layer", defaultLayer), 0.0F, 2.0F)) == layer')
$sortAt = $manager.IndexOf('active.sort((a, b) -> Float.compare(this.rankCache.get(a), this.rankCache.get(b)))')
$chainAt = $manager.IndexOf('boolean anyField = false;')
if ($filterAt -lt 0) {
	$problems.Add("the screen_layer filter expression changed - the layer semantics are what the order is scoped to")
} elseif ($sortAt -lt 0) {
	$problems.Add("the order sort is gone")
} elseif (-not ($filterAt -lt $sortAt)) {
	$problems.Add("the order sort does not run after the screen_layer filter - it would rank effects other layers own")
} elseif ($chainAt -gt 0 -and -not ($sortAt -lt $chainAt)) {
	$problems.Add("the order sort does not run before the chain/field scan - the chain would keep the unsorted order")
}

# 4) The layer filter's clamping range is untouched: 0..2, three semantic frame positions.
if ($manager -notmatch 'Mth\.clamp\(effect\.getParam\("screen_layer", defaultLayer\), 0\.0F, 2\.0F\)') {
	$problems.Add("the screen_layer clamp no longer matches 0..2")
}
if ($manager -notmatch 'VFXEffectType\.SURFACE_PATTERN \? 0\.0F : 1\.0F') {
	$problems.Add("the screen_layer default (0 for surface_pattern, 1 otherwise) changed")
}

Write-Host "Post layer order check (static)"
if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host "  - $_" }
	Write-Error "post layer order check failed ($($problems.Count) problem(s))."
	exit 1
}
Write-Host "  order param read via getParam with a finite fallback, stable sort between the screen_layer filter and the chain, layer semantics untouched"
Write-Host "Post layer order check OK."
exit 0

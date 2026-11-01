# Remove the scene: effects, entities and every block in the bounding box.
# Persistent effects only begin fading on the first stop; the second one removes them,
# so a second /function rift:go never stacks a second copy of the whole collection.
function rift:stop
function rift:stop
kill @e[tag=rift_debris]
kill @e[tag=rift_spirit]
fill 2997 197 2983 3003 220 3034 minecraft:air
fill 2982 190 2982 3018 220 3018 minecraft:air

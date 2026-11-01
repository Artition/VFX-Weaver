# Remove the scene: effects, entities and every block in the bounding box.
function rift:stop
kill @e[tag=rift_shard]
kill @e[tag=rift_spirit]
fill 2997 197 2983 3003 220 3026 minecraft:air
fill 2982 190 2982 3018 220 3018 minecraft:air

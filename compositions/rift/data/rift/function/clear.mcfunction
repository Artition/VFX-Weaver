# Remove the scene: effects, entities and every block in the bounding box.
function rift:stop
kill @e[tag=rift_shard]
kill @e[tag=rift_spirit]
fill 2998 197 2983 3002 222 3029 minecraft:air
fill 2983 197 2983 3017 222 3017 minecraft:air

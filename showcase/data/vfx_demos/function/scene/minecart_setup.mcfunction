# Showcase scene: the villager rides a minecart around a closed rail loop.
# Loop = 7x7 rectangle, corners at 1997 and 2003, rails at y=100 (floor is y=99).
# Every rail gets an explicit shape and the corners are curves, so the track cannot break;
# the clear area stops short of the wall (z=2006) and the corner decorations.
kill @e[tag=vfx_showcase_cart]
fill 1997 100 1997 2003 100 2003 air
fill 1997 99 1997 2003 99 2003 smooth_stone
setblock 1997 100 1997 rail[shape=south_east]
setblock 2003 100 1997 rail[shape=south_west]
setblock 2003 100 2003 rail[shape=north_west]
setblock 1997 100 2003 rail[shape=north_east]
setblock 1998 100 1997 rail[shape=east_west]
setblock 1999 99 1997 redstone_block
setblock 1999 100 1997 powered_rail[shape=east_west,powered=true]
setblock 2000 100 1997 rail[shape=east_west]
setblock 2001 100 1997 rail[shape=east_west]
setblock 2002 100 1997 rail[shape=east_west]
setblock 1998 99 2003 redstone_block
setblock 1998 100 2003 powered_rail[shape=east_west,powered=true]
setblock 1999 100 2003 rail[shape=east_west]
setblock 2000 100 2003 rail[shape=east_west]
setblock 2001 100 2003 rail[shape=east_west]
setblock 2002 99 2003 redstone_block
setblock 2002 100 2003 powered_rail[shape=east_west,powered=true]
setblock 1997 100 1998 rail[shape=north_south]
setblock 1997 100 1999 rail[shape=north_south]
setblock 1997 100 2000 rail[shape=north_south]
setblock 1997 99 2001 redstone_block
setblock 1997 100 2001 powered_rail[shape=north_south,powered=true]
setblock 1997 100 2002 rail[shape=north_south]
setblock 2003 100 1998 rail[shape=north_south]
setblock 2003 100 1999 rail[shape=north_south]
setblock 2003 99 2000 redstone_block
setblock 2003 100 2000 powered_rail[shape=north_south,powered=true]
setblock 2003 100 2001 rail[shape=north_south]
setblock 2003 100 2002 rail[shape=north_south]
summon minecraft:minecart 2000 100 1997 {Tags:["vfx_showcase_cart"],Invulnerable:1b,Silent:1b,Motion:[0.08d,0.0d,0.0d],Passengers:[{id:"minecraft:villager",Tags:["vfx_showcase"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomName:'"Showcase Subject"',CustomNameVisible:1b}]}
tp @s 2000 100 1994 0 0

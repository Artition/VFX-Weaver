tellraw @s {"text":"[VFX showcase] scene built at 2000,100,2000","color":"aqua"}
fill 1994 99 1994 2006 99 2006 minecraft:smooth_stone
fill 1994 100 2006 2006 104 2006 minecraft:white_concrete
fill 1996 100 1996 1996 102 1996 minecraft:red_concrete
setblock 2004 100 1996 minecraft:blue_concrete
kill @e[tag=vfx_showcase]
summon minecraft:villager 2000 100 2000 {Tags:["vfx_showcase"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomName:'"Showcase Subject"',CustomNameVisible:1b}
tp @s 2000 100 1994 0 0
tellraw @s {"text":"Look south (+Z) at the wall; the villager is straight ahead. /vfx play vfx_demos:show_...","color":"gray"}

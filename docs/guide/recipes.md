# Triggering effects

Every recipe here is a complete, copy-pasteable way to fire an effect from a game event. They build
on [Your first datapack, from zero](first-datapack.md): the files live in the same `mymap` pack, and
the namespace is your own. Run `/reload` after adding or editing any of them.

> **Permissions.** Functions run at **permission level 2** (gamemaster) by default, exactly what the
> mutating `/vfx` commands (`play`, `playat`, `playentity`, `stop`, `set`) require. That is why a
> function can play an effect *for a player who is not an operator* — the permission belongs to the
> function, not the viewer. Typing the same command in chat still needs op.

## 1. Play an effect from a function

`data/mymap/function/effects/blur.mcfunction`:

```mcfunction
vfx play vfxweaver:blur {[radius:8]} @s
```

Run it in game with `/function mymap:effects/blur` (it affects the executing player; run it from a
command block or `execute as` to pick someone else).

## 2. Play an effect when the world loads

Handy for smoke tests. Register a `load` function tag so the game runs it once on world load.

`data/mymap/function/setup.mcfunction`:

```mcfunction
tellraw @a {"text":"[mymap] VFX effect pack loaded","color":"green"}
```

`data/minecraft/tags/function/load.json`:

```json
{ "values": ["mymap:setup"] }
```

## 3. Play an effect on first join (advancement)

An advancement with the `minecraft:tick` trigger is granted on the first tick a player is in the
world; its reward function runs once, as that player.

`data/mymap/advancement/first_join.json`:

```json
{
	"criteria": {
		"tick": { "trigger": "minecraft:tick" }
	},
	"rewards": { "function": "mymap:on_first_join" }
}
```

`data/mymap/function/on_first_join.mcfunction`:

```mcfunction
vfx play vfxweaver:screen_flash {[color_r:0.2],[color_g:0.8],[color_b:1.0],[alpha:0.4]} @s
```

## 4. Play an effect when the player takes damage (scoreboard tick check)

This fires **every** time health drops, so it repeats. It needs two scoreboard objectives and three
functions.

`data/mymap/function/setup.mcfunction` (extend the load recipe's file):

```mcfunction
scoreboard objectives add vfx.health dummy
scoreboard objectives add vfx.prev dummy
```

`data/mymap/function/tick.mcfunction` (runs every tick):

```mcfunction
execute as @a at @s run function mymap:check_damage
```

`data/mymap/function/check_damage.mcfunction` (runs as one player):

```mcfunction
scoreboard players operation @s vfx.prev = @s vfx.health
execute store result score @s vfx.health run data get entity @s Health
execute if score @s vfx.health < @s vfx.prev run function mymap:on_damage
```

`data/mymap/function/on_damage.mcfunction`:

```mcfunction
vfx play vfxweaver:screen_flash {[color_r:1.0],[color_g:0.1],[color_b:0.1],[alpha:0.5]} @s
```

Register the tick function in `data/minecraft/tags/function/tick.json`:

```json
{ "values": ["mymap:tick"] }
```

`vfx.health` holds the health read this tick, `vfx.prev` the value from the tick before; when the
current value drops below the previous one, the player was hurt.

## 5. Play an effect when the player kills an entity (repeatable advancement)

Advancements are normally granted once, so the reward function revokes it again — that makes the
trigger repeatable.

`data/mymap/advancement/zombie_kill.json`:

```json
{
	"criteria": {
		"killed": {
			"trigger": "minecraft:player_killed_entity",
			"conditions": { "entity": { "type": "minecraft:zombie" } }
		}
	},
	"rewards": { "function": "mymap:on_zombie_kill" }
}
```

`data/mymap/function/on_zombie_kill.mcfunction`:

```mcfunction
vfx play vfxweaver:camera_shake {[amplitude_y:0.3],[frequency:20]} @s
advancement revoke @s only mymap:zombie_kill
```

Change the `entity.type` to match any mob, or drop the `conditions` block to fire on any kill.

## 6. Play on a player, at a world position, or on an entity

The three targets are three different commands.

```mcfunction
# 1) On a player (screen-space / world overlays that follow them)
vfx play vfxweaver:blur {[radius:8]} @s

# 2) On every connected player
vfx play vfxweaver:blur {[radius:8]} @a

# 3) At an exact world position (light_beam, block_tint, pulse_ring, ...)
vfx playat vfxweaver:light_beam 8 70 8 {[radius:2],[top_scale:2]}

# 4) At an entity's position (relative coordinates from execute)
execute at @e[type=minecraft:zombie,limit=1] run vfx playat vfxweaver:light_beam ~ ~ ~ {[radius:2]}

# 5) On an entity (entity_tint / entity_outline / entity_displace, targeted by selector)
vfx playentity vfxweaver:entity_outline @e[type=minecraft:zombie,limit=1] {[width:0.12]}
```

`/vfx playat` takes absolute or `~` relative coordinates, so recipe 4 works wherever the entity
stands. `/vfx playentity` sends the effect to the executing player by default; add a trailing player
selector to show it to others (e.g. `... @e[...] @a`).

## 7. Drive a param from a scoreboard

A definition can read a scoreboard value live; the parameter follows the score with no command loop.
Put this in `data/mymap/vfx/tension_blur.json`:

```json
{
	"type": "blur",
	"persistent": true,
	"fade_ticks": 10,
	"params": {
		"radius": { "bind": "scoreboard", "objective": "mymap.tension", "range": 10, "scale": 20 }
	}
}
```

Then:

```mcfunction
scoreboard objectives add mymap.tension dummy
scoreboard players set @s mymap.tension 5
vfx play mymap:tension_blur
```

The blur radius tracks the score from 0 to 20 as the score runs 0 to 10 (`score / range × scale`),
and updates on its own while the effect is persistent. Stop it with
`vfx stop mymap:tension_blur`. On a dedicated server the value is pushed to clients, so it works for
players who do not have the objective displayed.

## 8. Stop what you started

```mcfunction
vfx stop vfxweaver:blur
vfx stop mymap:tension_blur @a
vfx stop @a
```

`/vfx stop <effect>` stops every instance of that effect; `/vfx stop [<player>]` stops every active
effect of a player (default: the executor). See [Commands](commands.md) for the full surface.

## See also

- [Your first datapack, from zero](first-datapack.md) — the datapack layout these recipes assume.
- [Commands](commands.md) — every `/vfx` subcommand and its arguments.
- [Datapack format](datapack/format.md) — definition fields, `positions`, validation.

# Commands

Every command is under `/vfx`. **Permissions:** `/vfx play`, `/vfx playat`, `/vfx playentity`,
`/vfx stop` and `/vfx set` need operator rights (gamemaster level); `/vfx list` is open to everyone.

## `/vfx play <effect> [{[param:value],...}] [players]`

Play an effect (default: to yourself). The optional param-map overrides the definition's default
params, including world coordinates (`pos_x/y/z`) - like `overrides` in the Java API. Tab
autocompletes.

```
/vfx play vfxweaver:chromatic_aberration
/vfx play vfxweaver:blur {[radius:10]}
/vfx play mymap:flash {[color_r:1],[alpha:0.8]} @a
```

Repeated `/vfx play` of the same effect **does not replace** the playing instance - it adds another
independent one (several dents on screen at once). Up to 64 effects play at once in total.

## `/vfx playat <effect> <x> <y> <z> [{[...]}] [players]`

Play an effect anchored to world coordinates. The client re-anchors spatial bindings (`screen_x/y`,
`proximity`) to that point and uses it for the effect's positions (e.g. `block_tint`/`block_outline`).

```
/vfx playat vfxweaver:light_beam 8 70 8 {[radius:2],[top_scale:2]}
```

## `/vfx playentity <effect> [{[...]}] <targets> [players]`

Play an effect on selected entities (a selector, e.g. `@e[type=!player,distance=..10]`). Targets are
passed by UUID (up to 16) and apply to `entity_tint`/`entity_outline`/`entity_displace`. An effect
definition may instead carry an `entity_selector` field, in which case plain `/vfx play` finds its own
targets.

```
/vfx playentity vfxweaver:entity_outline @e[type=pig,limit=1] {[width:0.1]}
/vfx playentity vfxweaver:entity_tint @e[type=!player,distance=..10]
```

## `/vfx set <effect> {[param:value],...} [players]`

Live override of params of a **running** effect, without restarting the timeline. If the effect is not
running, a new instance starts with those values and the definition's own `duration` (it ends on
schedule like a normal play). Tab walks the syntax: `{` → `[` → param name → `:` value → `]` → `,`
(new pair) or `}`.

```
/vfx set vfxweaver:blur {[radius:12]}
```

## `/vfx stop <effect> [players]`

Stop the effect (all its instances). Effects with `fade_ticks > 0` fade out smoothly; otherwise they
are removed instantly. Stopping one instance of an effect is only possible through the Java API
([`sendStop`](../API.md)).

## `/vfx stop [<player>]`

Stop **every** active effect of a player (default: the executing player). Also clears effects the
executor's own client played locally. A bare token is parsed as an `<effect>` first, so target a
player with a selector (`@p`/`@a`) or a name that is not a valid effect id.

```
/vfx stop vfxweaver:blur
/vfx stop @a
```

## `/vfx list`

List all loaded definitions (built-ins + datapack).

## `/vfx validate [namespace]`

Dry-run health check: prints how many definitions loaded and lists every broken datapack file with
its parse error (optionally filtered by namespace, tab-completed). Operator-only. Useful for datapack
development and server admin checks without digging through logs.

## Notes

- **Param-map limits:** the param-map overrides *params only* - `duration` and `fade_ticks` are
  definition fields and cannot be changed from a command. To get a persistent built-in effect with a
  smooth exit, wrap it in a datapack definition ([Datapack format](datapack/format.md)).
- **`expr` is datapack-only:** command param-maps accept floats, not expressions
  ([Expressions](datapack/expr.md)).
- Effects are defined in datapacks - see the [Datapack format](datapack/format.md) and the
  [effect pages](effects/index.md). Collections (several effects from one play) are a definition type,
  not a command: [collection](effects/collection.md).

## See also

- [Getting started](index.md) - install and your first effect.
- [Datapack format](datapack/format.md) - files, definition fields, positions.
- [Java API](../API.md) - the same triggers from code.

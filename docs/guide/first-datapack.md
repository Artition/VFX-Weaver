# Your first datapack, from zero

This page assumes you know nothing about datapacks. By the end you will have a working VFX effect
that plays in your own world, built from scratch. If you already make datapacks, jump straight to
the [complete example](#complete-working-example).

## What a datapack is

A **datapack** is a folder (or a `.zip`) of data files that Minecraft loads into a world: recipes,
loot tables, functions, advancements — and, for this mod, VFX **effect definitions**. A datapack is
not a mod: it needs no launcher changes and no reload of the game, only `/reload` (or re-entering the
world).

VFX Weaver reads effect JSON from **every** datapack loaded in the world, plus its own built-ins. So
"making a VFX effect" means "put a JSON file in a datapack".

Minecraft looks for datapacks in two places:

| Where you play | Datapack folder |
|---|---|
| Singleplayer | `saves/<world>/datapacks/<name>/` |
| Dedicated server | `<server folder>/<world>/datapacks/<name>/` (the world folder is `level-name` in `server.properties`, `world` by default) |

Find the right folder from the game: on the world-select screen, click **Edit → Open world folder**
(singleplayer). The path above is relative to your Minecraft instance folder.

**Folder or zip?** Both work. Put the `<name>/` folder directly inside `datapacks/`, or zip the
folder's *contents* (so `pack.mcmeta` sits at the zip root, not inside a wrapper folder) and name the
archive `<name>.zip`. A zip that contains a folder which contains `pack.mcmeta` will not load.

## The minimal pack

Every datapack needs one file at its root: `pack.mcmeta`. That is the only required file.

```
saves/New World/datapacks/mymap/
├── pack.mcmeta
└── data/
    └── mymap/
        └── vfx/
            └── first_blur.json
```

`pack.mcmeta`:

```json
{
	"pack": {
		"description": "My VFX effects",
		"pack_format": 107
	}
}
```

`pack_format` must match your game version. Use the number for your Minecraft line:

| Minecraft line | `pack_format` |
|---|---|
| 26.2 | 107 |
| 26.1 / 26.1.1 / 26.1.2 | 101 |
| 1.21.11 | 94 |

If your pack shows as **incompatible** in the datapack list, the `pack_format` number does not match
your version — fix it from the table. An incompatible pack can still be enabled with a warning, but
matching the number is the clean way. On 26.x you may additionally bound the accepted range with
`min_format: [<N>, 1]` and `max_format: <N>`; plain `pack_format` is enough.

## Add an effect file

Effect definitions live under `data/<namespace>/vfx/`. The **namespace** is your pack's own name —
use `mymap`, your project name, anything except `vfxweaver` (which is reserved for built-ins; a
`vfxweaver:` file would silently *replace* a shipped effect).

```
data/mymap/vfx/first_blur.json
```

The **effect id** is the file path with `/` turned into `:` and `.json` dropped:
`data/mymap/vfx/first_blur.json` → `mymap:first_blur`.

```json
{ "type": "blur", "duration": 100, "params": { "radius": 8 } }
```

That is a complete definition: `type` picks the effect, `duration` is in ticks (20 = 1 second), and
`params` sets its inputs. Every field is documented on the
[Datapack format](datapack/format.md) page and each effect has its own page under
[Effects](effects/index.md).

## Load it

1. In game, run `/datapack list`. Your pack appears as `[file/mymap]` if it loaded. If it shows as
   *available* instead, enable it with `/datapack enable "file/mymap"`.
2. Run `/reload` (datapacks also reload when you re-enter the world). This re-reads every definition
   file — no restart.
3. Run `/vfx list`. `mymap:first_blur` should be in the list alongside the built-ins.
4. Run `/vfx play mymap:first_blur`. The screen blurs for five seconds and fades back.

That is the whole loop: **file → `/reload` → `/vfx play`**. Editing the JSON and re-running `/reload`
picks up your changes immediately.

## Complete working example

Copy this exactly. The tree:

```
saves/New World/datapacks/mymap/
├── pack.mcmeta
└── data/
    └── mymap/
        └── vfx/
            └── first_blur.json
```

`pack.mcmeta`:

```json
{
	"pack": {
		"description": "My VFX effects",
		"pack_format": 107
	}
}
```

`data/mymap/vfx/first_blur.json`:

```json
{
	"type": "blur",
	"duration": 100,
	"params": { "radius": 8 }
}
```

Commands, in order:

```
/datapack list
/reload
/vfx list
/vfx play mymap:first_blur
```

## When it does not work

- `/vfx list` does not show your id → the file is not where the game looks, the namespace is empty, or
  the JSON did not parse.
- `/vfx validate mymap` prints a dry-run health report: how many definitions loaded and the exact
  parse error for every broken file (operator-only, `/vfx validate` for all namespaces).
- `/datapack list` shows your pack as *available* → it is not enabled; `/datapack enable "file/mymap"`.
- `/datapack list` shows it as *incompatible* → wrong `pack_format` for your Minecraft line.
- `/vfx play` says the effect is unknown → you ran it before `/reload`, or the id has a typo (the
  namespace is required: `mymap:first_blur`, never `first_blur`).

## Next

- [Triggering effects](recipes.md) — fire an effect from a function, an advancement or a game event.
- [Effects](effects/index.md) — every effect type and its fields.
- [Datapack format](datapack/format.md) — the full definition schema.

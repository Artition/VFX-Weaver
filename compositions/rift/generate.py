"""Generate the RIFT composition datapack.

RIFT is one still frame made in game: a broken disc of stone hanging in dusk, four leaning pillars
whose beams converge above an amethyst spire, a halo of turned plates and shards, and a rift of
violet light that the two masks cut out of a grey world.

Everything is assembled by `/function rift:go`; nothing is placed by hand. Every effect value is a
constant, because the piece has to read in a single screenshot, not over time.

Usage: python compositions/rift/generate.py
"""
import io
import json
import math
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACK = os.path.join(ROOT, "compositions", "rift")

OX, OY, OZ = 3000, 200, 3000
R_OUTER = 16
R_GLOW = 11
PLATFORM_Z = 23            # the viewer stands here
TAG_SHARD = "rift_shard"
TAG_SPIRIT = "rift_spirit"

VIOLET = (0.62, 0.44, 1.0)     # the rift
COLD_WHITE = (0.84, 0.90, 1.0)  # light
APEX = (OX + 0.5, OY + 40, OZ + 0.5)


def h(x, z, salt=0):
    n = (x * 73856093) ^ (z * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177
    return abs(n ^ (n >> 16))


def write(path, lines):
    full = os.path.join(PACK, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with io.open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"  {path}: {len(lines)} lines")


def setblock(x, y, z, name):
    return f"setblock {x} {y} {z} minecraft:{name}"


def quat(axis, degrees):
    """[x, y, z, w] for a rotation about a unit axis."""
    half = math.radians(degrees) / 2.0
    s = math.sin(half)
    return [axis[0] * s, axis[1] * s, axis[2] * s, math.cos(half)]


def display(name, x, y, z, scale, rotation, tag=TAG_SHARD):
    nbt = (
        'block_state:{Name:"minecraft:%s"},'
        'transformation:{left_rotation:[%.4ff,%.4ff,%.4ff,%.4ff],scale:[%.2ff,%.2ff,%.2ff]},'
        'brightness:{sky:15,block:15},Tags:["%s"]'
        % (name, rotation[0], rotation[1], rotation[2], rotation[3], scale[0], scale[1], scale[2], tag)
    )
    return f"summon minecraft:block_display {x:.2f} {y:.2f} {z:.2f} {{{nbt}}}"


def floor_block(dx, dz, r):
    if r <= 1.8:
        return "polished_blackstone"
    if r > R_OUTER:
        return None
    if r > 14.0 and h(dx, dz) % 100 < (r - 14.0) * 46:
        return None
    if r > 15.0 and h(dx, dz, 3) % 100 < 50:
        return None

    ang = math.atan2(dz, dx)
    step = math.pi / 6.0
    spoke = abs(((ang + step / 2) % step) - step / 2) < 0.85 / max(r, 1.0)

    if abs(r - R_GLOW) < 0.65:
        return "sea_lantern"
    if r <= R_GLOW:
        if spoke:
            return "polished_blackstone"
        return "polished_blackstone" if round(r) % 4 == 0 else "deepslate_tiles"
    if r <= 13.8:
        return "cobbled_deepslate" if h(dx, dz, 11) % 97 < 8 else "deepslate"
    return "blackstone"


def main():
    data = os.path.join(PACK, "data")
    if os.path.isdir(data):
        shutil.rmtree(data)
        print("rift: cleared the previous pack output")
    # ------------------------------------------------------------------ island
    print("rift: island")
    lines = ["# The disc and the rock under it: a cone, so the island reads as a mass and not a coin."]
    for dy in range(0, -8, -1):
        rmax = 16.0 - abs(dy) * 1.9
        for dx in range(-R_OUTER, R_OUTER + 1):
            for dz in range(-R_OUTER, R_OUTER + 1):
                r = math.hypot(dx, dz)
                if r > rmax:
                    continue
                if dy == 0:
                    name = floor_block(dx, dz, r)
                elif dy >= -1:
                    name = "deepslate" if h(dx, dz, dy) % 11 else "cobbled_deepslate"
                else:
                    # the underside frays where it tapers
                    if h(dx, dz, dy + 40) % 100 < 26:
                        continue
                    name = "cobbled_deepslate" if h(dx, dz, dy + 7) % 3 else "deepslate"
                if name:
                    lines.append(setblock(OX + dx, OY + dy, OZ + dz, name))
    write("data/rift/function/scene/island.mcfunction", lines)

    # ------------------------------------------------------------------ spire
    print("rift: spire")
    spire = ["# A 5x5 base narrowing twice, then a column of amethyst: the light comes out of the stone."]
    for y in range(0, 4):
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                corner = abs(dx) == 2 and abs(dz) == 2
                ring = max(abs(dx), abs(dz)) == 2
                name = "polished_blackstone" if corner else ("deepslate_tiles" if ring else "deepslate")
                spire.append(setblock(OX + dx, OY + y, OZ + dz, name))
    for y in range(4, 8):
        for dx in range(-1, 2):
            for dz in range(-1, 2):
                name = "amethyst_block" if (dx == 0 and dz == 0) else "deepslate"
                spire.append(setblock(OX + dx, OY + y, OZ + dz, name))
    for y in range(8, 12):
        spire.append(setblock(OX, OY + y, OZ, "amethyst_block" if y < 11 else "budding_amethyst"))
    write("data/rift/function/scene/spire.mcfunction", spire)

    # ------------------------------------------------------------------ pillars
    print("rift: pillars")
    pillars = ["# Four leaning pillars: block_display entities stretched into monoliths, tilted inward."]
    pillar_tops = []
    for k in range(4):
        a = math.radians(45 + 90 * k)
        px, pz = 9.5 * math.cos(a), 9.5 * math.sin(a)
        cx, cz = OX + px, OZ + pz
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                pillars.append(setblock(int(round(cx)) + dx, OY, int(round(cz)) + dz, "polished_blackstone"))
        axis = (-math.sin(a), 0.0, math.cos(a))
        rot = quat(axis, 9.0)
        pillars.append(display("polished_blackstone", cx, OY + 5.5, cz, (1.35, 11.0, 1.35), rot))
        lean = math.sin(math.radians(9.0)) * 5.5
        pillar_tops.append((cx - lean * math.cos(a), OY + 11.0, cz - lean * math.sin(a)))
    write("data/rift/function/scene/pillars.mcfunction", pillars)

    # ------------------------------------------------------------------ crown
    print("rift: crown")
    crown = ["# A halo of turned plates and shards: the only geometry in the piece that blocks cannot make."]
    for k in range(12):
        a = math.radians(30 * k)
        r = 4.6 + 0.35 * math.sin(3 * a)
        x, z = OX + r * math.cos(a), OZ + r * math.sin(a)
        y = OY + 12.2 + 1.5 * math.cos(2 * a)
        block = "sea_lantern" if k % 3 == 0 else "amethyst_block"
        axis = (-math.sin(a), 0.0, math.cos(a))
        crown.append(display(block, x, y, z, (1.9, 0.22, 1.1), quat(axis, 22.0)))
    for k in range(3):
        a = math.radians(120 * k + 20)
        crown.append(display(
            "budding_amethyst",
            OX + 2.4 * math.cos(a), OY + 13.4, OZ + 2.4 * math.sin(a),
            (0.7, 3.2, 0.7), quat((-math.sin(a), 0.0, math.cos(a)), 14.0),
        ))
    crown.append(
        f"summon minecraft:allay {OX + 0.5:.2f} {OY + 13.0:.2f} {OZ + 0.5:.2f} "
        '{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["rift_spirit"]}'
    )
    write("data/rift/function/scene/crown.mcfunction", crown)

    # ------------------------------------------------------------------ approach
    print("rift: approach")
    approach = ["# Bridge, platform, and two dark columns that frame the shot from the near edge."]
    for z in range(16, 20):
        for x in (-1, 0, 1):
            approach.append(setblock(OX + x, OY, OZ + z, "polished_blackstone" if x == 0 else "deepslate_tiles"))
    for dx in range(-2, 3):
        for dz in range(20, PLATFORM_Z + 1):
            name = "polished_blackstone" if (abs(dx) == 2 or dz == PLATFORM_Z) else "deepslate_tiles"
            approach.append(setblock(OX + dx, OY, OZ + dz, name))
    for dx in (-5, 5):
        for y in range(1, 10):
            approach.append(setblock(OX + dx, OY + y, OZ + 12, "deepslate" if y % 3 else "polished_blackstone"))
        approach.append(setblock(OX + dx, OY + 10, OZ + 12, "sea_lantern"))
    write("data/rift/function/scene/approach.mcfunction", approach)

    # ------------------------------------------------------------------ effects
    print("rift: effects")
    vfx = os.path.join(PACK, "data", "rift", "vfx")
    os.makedirs(vfx, exist_ok=True)

    def definition(effect, params, positions=None, mask=None, extra=None):
        d = {
            "type": effect,
            "duration": 200,
            "persistent": True,
            "loop": True,
            "fade_ticks": 20,
            "params": params,
        }
        if positions is not None:
            d["positions"] = positions
        if mask is not None:
            d["mask"] = mask
        if extra:
            d.update(extra)
        return d

    core = {
        "shape": "sphere",
        "space": "world",
        "volume": "aura",
        "center": [float(OX), float(OY + 8), float(OZ)],
        "radius": 11.0,
        "softness": 2.5,
    }

    effects = {}

    # Long, soft, slightly violet columns that meet above the spire.
    effects["beam"] = definition("light_beam", {
        "radius": 0.55,
        "height": 26.0,
        "top_scale": 2.2,
        "softness": 3.2,
        "top_fade": 0.05,
        "bottom_fade": 0.55,
        "red": 0.80, "green": 0.76, "blue": 1.0,
        "intensity": 0.42,
        "through_blocks": 0.0,
    })

    effects["rift_light"] = definition("light_beam", {
        "radius": 0.35,
        "height": 30.0,
        "top_scale": 3.2,
        "softness": 3.6,
        "top_fade": 0.0,
        "bottom_fade": 0.85,
        "red": VIOLET[0], "green": VIOLET[1], "blue": VIOLET[2],
        "intensity": 0.55,
        "through_blocks": 0.0,
    }, positions=[[OX + 0.5, OY + 11, OZ + 0.5]])

    effects["dust"] = definition("particles", {
        "rate": 70.0,
        "radius": 2.0,
        "height": 13.0,
        "turns": 3.0,
        "spin": 0.35,
        "vel_y": 0.010,
        "size": 1.2,
        "color_r": VIOLET[0], "color_g": VIOLET[1], "color_b": VIOLET[2],
    }, positions=[[OX + 0.5, OY + 1.0, OZ + 0.5]],
        extra={"particle": "dust", "shape": "helix"})

    effects["motes"] = definition("particles", {
        "rate": 30.0,
        "radius": 7.0,
        "speed": 0.006,
        "vel_y": 0.010,
        "spread": 0.5,
    }, positions=[[OX + 0.5, OY + 5.0, OZ + 0.5]],
        extra={"particle": "minecraft:end_rod", "shape": "sphere"})

    effects["outline"] = definition("block_outline", {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": 0.55,
        "width": 0.05,
        "through_blocks": 0.0,
    }, positions=[[int(round(tx)), OY + 1, int(round(tz))] for tx, _, tz in pillar_tops])

    effects["spirit"] = definition("entity_outline", {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": 0.85,
        "width": 0.06,
        "through_blocks": 0.0,
    }, extra={"entity_selector": f"@e[tag={TAG_SPIRIT},limit=1]"})

    # The two grades are one sphere, one inverted: outside goes grey and cold, inside goes violet.
    effects["grade_out"] = definition("color_grade", {
        "saturation": 0.55,
        "contrast": 1.03,
        "brightness": 0.94,
        "tint_r": 0.92, "tint_g": 0.96, "tint_b": 1.08,
    }, mask={**core, "invert": True})

    effects["grade_in"] = definition("color_grade", {
        "saturation": 1.5,
        "contrast": 1.08,
        "brightness": 1.04,
        "tint_r": 1.10, "tint_g": 0.92, "tint_b": 1.16,
    }, mask=core)

    effects["spectral"] = definition("spectral", {
        "amount": 0.14,
        "mode": 1.0,
        "center_x": 0.5,
        "center_y": 0.5,
        "mid": 0.56,
        "core": 0.86,
    }, mask=dict(core))

    effects["bloom"] = definition("bloom", {
        "intensity": 0.5,
        "threshold": 0.5,
        "radius": 5.0,
    })

    effects["vignette"] = definition("vignette", {"intensity": 0.5})

    effects["dof"] = definition("depth_of_field", {
        "intensity": 0.32,
        "focus_center": 0.56,
        "focus_range": 0.20,
    })

    effects["fov"] = definition("fov_modifier", {"fov_delta": -16.0})

    for name, data in effects.items():
        with io.open(os.path.join(vfx, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        print(f"  data/rift/vfx/{name}.json")

    # ------------------------------------------------------------------ the play
    children = [
        {"effect": "rift:grade_out", "delay": 0, "duration": -1},
        {"effect": "rift:grade_in", "delay": 0, "duration": -1},
        {"effect": "rift:vignette", "delay": 0, "duration": -1},
        {"effect": "rift:bloom", "delay": 0, "duration": -1},
        {"effect": "rift:dof", "delay": 0, "duration": -1},
        {"effect": "rift:fov", "delay": 0, "duration": -1},
        {"effect": "rift:spectral", "delay": 0, "duration": -1},
        {"effect": "rift:rift_light", "delay": 0, "duration": -1},
        {"effect": "rift:dust", "delay": 0, "duration": -1},
        {"effect": "rift:motes", "delay": 0, "duration": -1},
        {"effect": "rift:spirit", "delay": 0, "duration": -1},
        {"effect": "rift:outline", "delay": 0, "duration": -1},
    ]
    for (tx, ty, tz) in pillar_tops:
        d = (APEX[0] - tx, APEX[1] - ty, APEX[2] - tz)
        length = math.sqrt(d[0] ** 2 + d[1] ** 2 + d[2] ** 2)
        children.append({
            "effect": "rift:beam",
            "delay": 0,
            "duration": -1,
            "params": {
                "pos_x": tx, "pos_y": ty, "pos_z": tz,
                "dir_x": d[0] / length, "dir_y": d[1] / length, "dir_z": d[2] / length,
                "height": length,
            },
        })

    scene = {
        "type": "collection",
        "duration": 400,
        "persistent": True,
        "loop": False,
        "fade_ticks": 0,
        "effects": children,
    }
    with io.open(os.path.join(vfx, "scene.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(scene, f, ensure_ascii=False, separators=(",", ":"))
    print("  data/rift/vfx/scene.json")

    # ------------------------------------------------------------------ functions
    print("rift: functions")
    write("data/rift/function/build.mcfunction", [
        "# Assemble the geometry. Safe to run twice: every block is placed, nothing is additive.",
        "function rift:scene/island",
        "function rift:scene/spire",
        "function rift:scene/pillars",
        "function rift:scene/approach",
    ])

    write("data/rift/function/spawn.mcfunction", [
        "# Entities last, so a rebuild never leaves two crowns in the air.",
        f"kill @e[tag={TAG_SHARD}]",
        f"kill @e[tag={TAG_SPIRIT}]",
        "function rift:scene/crown",
    ])

    write("data/rift/function/tp.mcfunction", [
        "# The viewer stands on the platform at the end of the bridge, looking north and slightly up.",
        f"tp @s {OX + 0.5} {OY + 1.0} {OZ + PLATFORM_Z + 0.5} 180 -12",
    ])

    write("data/rift/function/atmosphere.mcfunction", [
        "# Dusk: the sky does the work a night sky cannot do in a still frame.",
        "time set 12400",
        "weather clear",
    ])

    write("data/rift/function/go.mcfunction", [
        "# Assemble and start everything.",
        "function rift:clear",
        "function rift:build",
        "function rift:spawn",
        "function rift:tp",
        "function rift:atmosphere",
        "vfx play rift:scene",
    ])

    write("data/rift/function/stop.mcfunction", [
        "# Children of a collection are their own plays, so each one is stopped by name.",
        "vfx stop rift:beam",
        "vfx stop rift:rift_light",
        "vfx stop rift:dust",
        "vfx stop rift:motes",
        "vfx stop rift:outline",
        "vfx stop rift:spirit",
        "vfx stop rift:grade_out",
        "vfx stop rift:grade_in",
        "vfx stop rift:spectral",
        "vfx stop rift:bloom",
        "vfx stop rift:vignette",
        "vfx stop rift:dof",
        "vfx stop rift:fov",
        "vfx stop rift:scene",
    ])

    write("data/rift/function/clear.mcfunction", [
        "# Remove the scene: effects, entities and every block in the bounding box.",
        "function rift:stop",
        f"kill @e[tag={TAG_SHARD}]",
        f"kill @e[tag={TAG_SPIRIT}]",
        f"fill {OX - 7} {OY - 3} {OZ - 17} {OX + 7} {OY + 20} {OZ + 26} minecraft:air",
        f"fill {OX - 18} {OY - 10} {OZ - 18} {OX + 18} {OY + 20} {OZ + 18} minecraft:air",
    ])

    print("rift: pack.mcmeta")
    mcmeta = {
        "pack": {
            "description": "RIFT - a VFX Weaver composition (blocks, entities, effects)",
            "pack_format": 107,
            "min_format": [107, 1],
            "max_format": 107,
        }
    }
    with io.open(os.path.join(PACK, "pack.mcmeta"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(mcmeta, f, ensure_ascii=False, indent="\t")

    print("done")


if __name__ == "__main__":
    main()

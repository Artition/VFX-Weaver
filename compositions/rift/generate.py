"""Generate the RIFT composition datapack.

RIFT is one still frame made in game: an island of dark stone at dusk, four leaning monoliths whose
beams converge above an amethyst spire, a ring of turned plates around it, and a rift the masks cut
out of the world.

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
PLATFORM_Z = 23
TAG_SHARD = "rift_shard"
TAG_SPIRIT = "rift_spirit"

VIOLET = (0.62, 0.44, 1.0)
COLD_WHITE = (0.84, 0.90, 1.0)
APEX = (OX + 0.5, OY + 40, OZ + 0.5)

PILLAR_RADIUS = 7.5
PILLAR_HEIGHT = 12.0
PILLAR_TILT = 11.0
SPIRE_TOP = 212


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
                    if h(dx, dz, dy + 40) % 100 < 26:
                        continue
                    name = "cobbled_deepslate" if h(dx, dz, dy + 7) % 3 else "deepslate"
                if name:
                    lines.append(setblock(OX + dx, OY + dy, OZ + dz, name))
    write("data/rift/function/scene/island.mcfunction", lines)

    # ------------------------------------------------------------------ spire
    print("rift: spire")
    spire = ["# A slim plinth and an amethyst column: the light comes out of the stone."]
    for y in range(0, 3):
        for dx in range(-1, 2):
            for dz in range(-1, 2):
                corner = abs(dx) == 1 and abs(dz) == 1
                name = "polished_blackstone" if corner else "deepslate_tiles"
                spire.append(setblock(OX + dx, OY + y, OZ + dz, name))
    for y in range(3, 12):
        spire.append(setblock(OX, OY + y, OZ, "amethyst_block" if y < 11 else "budding_amethyst"))
    write("data/rift/function/scene/spire.mcfunction", spire)

    # ------------------------------------------------------------------ monoliths
    print("rift: monoliths")
    monoliths = ["# Four leaning monoliths: one block stretched twelve times, tilted inward. This is the frame."]
    pillar_tops = []
    for k in range(4):
        a = math.radians(45 + 90 * k)
        px, pz = PILLAR_RADIUS * math.cos(a), PILLAR_RADIUS * math.sin(a)
        cx, cz = OX + px, OZ + pz
        # a square footing, so the leaning shape has something to stand on
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                monoliths.append(setblock(int(round(cx)) + dx, OY, int(round(cz)) + dz, "polished_blackstone"))
        axis = (-math.sin(a), 0.0, math.cos(a))
        rot = quat(axis, PILLAR_TILT)
        monoliths.append(display("polished_blackstone", cx, OY + PILLAR_HEIGHT / 2.0, cz,
                                 (1.3, PILLAR_HEIGHT, 1.3), rot))
        lean = math.sin(math.radians(PILLAR_TILT)) * (PILLAR_HEIGHT / 2.0)
        pillar_tops.append((cx - lean * math.cos(a), OY + PILLAR_HEIGHT, cz - lean * math.sin(a)))
    write("data/rift/function/scene/monoliths.mcfunction", monoliths)

    # ------------------------------------------------------------------ crown
    print("rift: crown")
    crown = ["# One ring, one height, one tilt: a halo. The shards are the accents, not more debris."]
    for k in range(12):
        a = math.radians(30 * k)
        x, z = OX + 4.2 * math.cos(a), OZ + 4.2 * math.sin(a)
        block = "sea_lantern" if k % 3 == 0 else "amethyst_block"
        axis = (-math.sin(a), 0.0, math.cos(a))
        crown.append(display(block, x, OY + 14.6, z, (1.55, 0.20, 0.95), quat(axis, 18.0)))
    for k in range(4):
        a = math.radians(90 * k + 45)
        crown.append(display(
            "budding_amethyst",
            OX + 2.9 * math.cos(a), OY + 16.2, OZ + 2.9 * math.sin(a),
            (0.55, 2.6, 0.55), quat((-math.sin(a), 0.0, math.cos(a)), 8.0),
        ))
    crown.append(
        f"summon minecraft:allay {OX + 0.5:.2f} {OY + 13.2:.2f} {OZ + 0.5:.2f} "
        '{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["rift_spirit"]}'
    )
    write("data/rift/function/scene/crown.mcfunction", crown)

    # ------------------------------------------------------------------ approach
    print("rift: approach")
    approach = ["# Bridge and platform. No framing columns: the monoliths are the frame."]
    for z in range(16, 20):
        for x in (-1, 0, 1):
            approach.append(setblock(OX + x, OY, OZ + z, "polished_blackstone" if x == 0 else "deepslate_tiles"))
    for dx in range(-2, 3):
        for dz in range(20, PLATFORM_Z + 1):
            name = "polished_blackstone" if (abs(dx) == 2 or dz == PLATFORM_Z) else "deepslate_tiles"
            approach.append(setblock(OX + dx, OY, OZ + dz, name))
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

    # `surface`, not `aura`: the rift colours the stone that stands in it, and leaves no visible
    # dome hanging in the air.
    core = {
        "shape": "sphere",
        "space": "world",
        "volume": "surface",
        "center": [float(OX), float(OY + 8), float(OZ)],
        "radius": 9.5,
        "softness": 1.4,
    }

    effects = {}

    effects["beam"] = definition("light_beam", {
        "radius": 0.30,
        "height": 26.0,
        "top_scale": 2.0,
        "softness": 3.5,
        "top_fade": 0.0,
        "bottom_fade": 0.65,
        "red": 0.86, "green": 0.84, "blue": 1.0,
        "intensity": 0.30,
        "through_blocks": 0.0,
    })

    effects["rift_light"] = definition("light_beam", {
        "radius": 0.28,
        "height": 30.0,
        "top_scale": 3.4,
        "softness": 4.0,
        "top_fade": 0.0,
        "bottom_fade": 0.9,
        "red": VIOLET[0], "green": VIOLET[1], "blue": VIOLET[2],
        "intensity": 0.5,
        "through_blocks": 0.0,
    }, positions=[[OX + 0.5, OY + 12, OZ + 0.5]])

    effects["dust"] = definition("particles", {
        "rate": 55.0,
        "radius": 1.7,
        "height": 12.0,
        "turns": 3.0,
        "spin": 0.25,
        "vel_y": 0.008,
        "size": 1.0,
        "color_r": VIOLET[0], "color_g": VIOLET[1], "color_b": VIOLET[2],
    }, positions=[[OX + 0.5, OY + 1.0, OZ + 0.5]],
        extra={"particle": "dust", "shape": "helix"})

    effects["motes"] = definition("particles", {
        "rate": 22.0,
        "radius": 6.5,
        "speed": 0.005,
        "vel_y": 0.008,
        "spread": 0.5,
    }, positions=[[OX + 0.5, OY + 5.0, OZ + 0.5]],
        extra={"particle": "minecraft:end_rod", "shape": "sphere"})

    effects["outline"] = definition("block_outline", {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": 0.5,
        "width": 0.045,
        "through_blocks": 0.0,
    }, positions=[[int(round(tx)), OY + 1, int(round(tz))] for tx, _, tz in pillar_tops])

    effects["spirit"] = definition("entity_outline", {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": 0.8,
        "width": 0.055,
        "through_blocks": 0.0,
    }, extra={"entity_selector": f"@e[tag={TAG_SPIRIT},limit=1]"})

    effects["grade_out"] = definition("color_grade", {
        "saturation": 0.58,
        "contrast": 1.03,
        "brightness": 0.95,
        "tint_r": 0.94, "tint_g": 0.97, "tint_b": 1.06,
    }, mask={**core, "invert": True})

    effects["grade_in"] = definition("color_grade", {
        "saturation": 1.45,
        "contrast": 1.06,
        "brightness": 1.03,
        "tint_r": 1.08, "tint_g": 0.94, "tint_b": 1.14,
    }, mask=core)

    effects["spectral"] = definition("spectral", {
        "amount": 0.12,
        "mode": 1.0,
        "center_x": 0.5,
        "center_y": 0.5,
        "mid": 0.58,
        "core": 0.88,
    }, mask=dict(core))

    # Depth: the air is not empty, and a little violet fog gives the frame a far plane.
    effects["fog"] = definition("fog_modifier", {
        "fog_start_scale": 0.72,
        "fog_end_scale": 1.0,
        "fog_r": 0.42, "fog_g": 0.38, "fog_b": 0.72,
        "fog_color_amount": 0.42,
    })

    effects["bloom"] = definition("bloom", {
        "intensity": 0.42,
        "threshold": 0.55,
        "radius": 5.0,
    })

    effects["vignette"] = definition("vignette", {"intensity": 0.48})

    effects["dof"] = definition("depth_of_field", {
        "intensity": 0.30,
        "focus_center": 0.56,
        "focus_range": 0.22,
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
        {"effect": "rift:fog", "delay": 0, "duration": -1},
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
        "function rift:scene/monoliths",
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
        "vfx stop rift:fog",
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
        f"fill {OX - 3} {OY - 3} {OZ - 17} {OX + 3} {OY + 20} {OZ + 26} minecraft:air",
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

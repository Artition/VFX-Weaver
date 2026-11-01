"""Generate the RIFT composition datapack.

RIFT is one scene: a dark disc hanging at y=200, a broken obelisk of deepslate and amethyst, six
standing stones with beams, seven turned blocks floating above (block_display entities), a spirit
inside the light (an allay, outlined by the mod), and a watching platform on a bridge.

Everything is assembled by `/function rift:go`: no block is placed by hand. The effect work is
staged as one `collection`, so a single play starts the whole scene; the two colour grades are cut
by the same world-sphere mask, one normal and one inverted, which is what makes the rift the only
coloured thing in the frame.

Usage: python compositions/rift/generate.py
"""
import io
import json
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACK = os.path.join(ROOT, "compositions", "rift")

OX, OY, OZ = 3000, 200, 3000
R_OUTER = 16
R_GLOW = 11
TAG_SHARD = "rift_shard"
TAG_SPIRIT = "rift_spirit"

# The palette has three colours and each one means one thing.
VIOLET = (0.58, 0.42, 1.0)
COLD_WHITE = (0.84, 0.90, 1.0)


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


def floor_block(dx, dz, r):
    if r <= 1.6:
        return "polished_blackstone"
    if r > R_OUTER:
        return None

    # a rim that has been eaten away, more towards the outside
    if r > 14.0 and h(dx, dz) % 100 < (r - 14.0) * 52:
        return None
    if r > 15.2 and h(dx, dz, 3) % 100 < 55:
        return None

    if R_GLOW - 0.62 < r < R_GLOW + 0.45:
        return "sea_lantern" if h(dx, dz, 7) % 100 > 12 else "cracked_polished_blackstone_bricks"

    ang = math.atan2(dz, dx)
    step = math.pi / 6.0
    spoke = abs(((ang + step / 2) % step) - step / 2) < 0.9 / max(r, 1.0)

    if r <= R_GLOW:
        if spoke:
            return "polished_blackstone"
        return "polished_blackstone" if round(r) % 3 == 0 else "deepslate_tiles"

    if r <= 13.6:
        return "cobbled_deepslate" if h(dx, dz, 11) % 97 < 7 else "deepslate"
    return "blackstone"


def main():
    print("rift: floor")
    floor = [f"# A {R_OUTER}-block disc at y={OY}. The rim is broken on purpose; the ring at r={R_GLOW} is the one line that survives."]
    for dy in (0, -1, -2):
        for dx in range(-R_OUTER, R_OUTER + 1):
            for dz in range(-R_OUTER, R_OUTER + 1):
                r = math.hypot(dx, dz)
                if dy == 0:
                    name = floor_block(dx, dz, r)
                elif dy == -1:
                    name = "deepslate" if 12.6 <= r <= 16.2 else None
                else:
                    name = "deepslate" if 13.2 <= r <= 15.4 else None
                if name:
                    floor.append(setblock(OX + dx, OY + dy, OZ + dz, name))
    write("data/rift/function/scene/floor.mcfunction", floor)

    print("rift: obelisk")
    obelisk = ["# A 5x5 base narrowing to a single amethyst column; the light comes out of the stone."]
    for y in range(0, 4):
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                edge = max(abs(dx), abs(dz)) == 2
                corner = abs(dx) == 2 and abs(dz) == 2
                name = "polished_blackstone" if corner else ("deepslate_tiles" if edge else "deepslate")
                obelisk.append(setblock(OX + dx, OY + y, OZ + dz, name))
    for y in range(4, 8):
        for dx in range(-1, 2):
            for dz in range(-1, 2):
                name = "amethyst_block" if (dx == 0 and dz == 0) else "deepslate"
                obelisk.append(setblock(OX + dx, OY + y, OZ + dz, name))
    for y in range(8, 13):
        obelisk.append(setblock(OX, OY + y, OZ, "amethyst_block" if y < 11 else "budding_amethyst"))
    obelisk.append(setblock(OX, OY + 13, OZ, "sea_lantern"))
    write("data/rift/function/scene/obelisk.mcfunction", obelisk)

    print("rift: standing stones")
    stones = ["# Six stones on a 12-block circle, one every 60 degrees; each is a beam anchor."]
    stone_pos = []
    for k in range(6):
        a = k * math.pi / 3
        sx = OX + round(12 * math.cos(a))
        sz = OZ + round(12 * math.sin(a))
        stone_pos.append((sx, sz))
        for y in range(0, 7):
            stones.append(setblock(sx, OY + y, sz, "deepslate" if y < 6 else "sea_lantern"))
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx or dz:
                    stones.append(setblock(sx + dx, OY, sz + dz, "polished_blackstone"))
    write("data/rift/function/scene/stones.mcfunction", stones)

    print("rift: approach")
    approach = ["# A 3-wide bridge to a small watching platform: the only place the player stands."]
    for z in range(16, 24):
        for x in (-1, 0, 1):
            approach.append(setblock(OX + x, OY, OZ + z, "polished_blackstone" if x == 0 else "deepslate_tiles"))
        approach.append(setblock(OX - 2, OY, OZ + z, "deepslate"))
        approach.append(setblock(OX + 2, OY, OZ + z, "deepslate"))
    for dx in range(-2, 3):
        for dz in range(24, 29):
            name = "polished_blackstone" if (abs(dx) == 2 or dz == 28) else "deepslate_tiles"
            approach.append(setblock(OX + dx, OY, OZ + dz, name))
    # a low parapet, so the platform reads as a place to stand rather than a dead end
    for dx in range(-2, 3):
        approach.append(setblock(OX + dx, OY + 1, OZ + 28, "deepslate_wall"))
    for dz in range(24, 29):
        approach.append(setblock(OX - 2, OY + 1, OZ + dz, "deepslate_wall"))
        approach.append(setblock(OX + 2, OY + 1, OZ + dz, "deepslate_wall"))
    write("data/rift/function/scene/approach.mcfunction", approach)

    print("rift: shards")
    shards = ["# Seven turned blocks above the obelisk: block_display entities, no physics, any tilt."]
    pieces = [
        ("amethyst_block", 5.4, 16.2, 1.9, 32, 0.55),
        ("deepslate", 4.2, 18.6, 1.5, 68, 0.30),
        ("sea_lantern", 6.0, 18.2, 1.1, 12, 0.22),
        ("amethyst_block", 3.2, 15.4, 1.2, 104, 0.85),
        ("cobbled_deepslate", 4.8, 20.8, 1.7, 46, 0.45),
        ("budding_amethyst", 1.7, 17.6, 1.0, 20, 1.10),
        ("deepslate_tiles", 6.8, 16.6, 1.3, 88, 0.65),
    ]
    for name, rad, hy, scale, ang, tilt in pieces:
        a = math.radians(ang)
        x = OX + rad * math.cos(a)
        z = OZ + rad * math.sin(a)
        y = OY + hy
        half = math.radians(20) / 2
        qx, qy = math.sin(tilt / 2) * math.cos(half), math.cos(tilt / 2) * math.sin(half)
        w = math.cos(tilt / 2) * math.cos(half)
        xx = math.sin(tilt / 2) * math.cos(half)
        yy = math.cos(tilt / 2) * math.sin(half)
        zz = -math.sin(tilt / 2) * math.sin(half)
        nbt = (
            'block_state:{Name:"minecraft:%s"},'
            'transformation:{left_rotation:[%.4ff,%.4ff,%.4ff,%.4ff],scale:[%.2ff,%.2ff,%.2ff]},'
            'brightness:{sky:15,block:15},Tags:["%s"]'
            % (name, xx, yy, zz, w, scale, scale, scale * 0.42, TAG_SHARD)
        )
        shards.append(f"summon minecraft:block_display {x:.2f} {y:.2f} {z:.2f} {{{nbt}}}")
    shards.append(
        f"summon minecraft:allay {OX + 0.5:.2f} {OY + 15.4:.2f} {OZ + 0.5:.2f} "
        '{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["rift_spirit"]}'
    )
    write("data/rift/function/scene/shards.mcfunction", shards)

    # ------------------------------------------------------------------ effects
    print("rift: effects")
    vfx = os.path.join(PACK, "data", "rift", "vfx")
    os.makedirs(vfx, exist_ok=True)

    def kf(points):
        return {"keyframes": [{"time": t, "value": v} for t, v in points]}

    def definition(effect, duration, params, positions=None, mask=None, extra=None):
        d = {
            "type": effect,
            "duration": duration,
            "easing": "ease_in_out_cubic",
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

    core_mask = {
        "a": {
            "shape": "sphere",
            "space": "world",
            "volume": "aura",
            "center": [float(OX), float(OY + 8), float(OZ)],
            "radius": 9.0,
            "softness": 1.8,
        }
    }

    effects = {}

    effects["beam"] = definition("light_beam", 240, {
        "radius": kf([(0, 0.7), (120, 1.0), (240, 0.7)]),
        "height": 40.0,
        "top_scale": 2.6,
        "softness": 2.8,
        "top_fade": 0.1,
        "bottom_fade": 0.3,
        "red": COLD_WHITE[0], "green": COLD_WHITE[1], "blue": COLD_WHITE[2],
        "through_blocks": 0.0,
        "intensity": kf([(0, 0.28), (120, 0.72), (240, 0.28)]),
    })

    # A flat ring: rot_x 90 lies it on the floor, and the radius carries it out over the rim.
    effects["pulse"] = definition("pulse_ring", 120, {
        "radius": kf([(0, 0.8), (96, 15.5), (120, 15.5)]),
        "thickness": 0.3,
        "billboard": 0.0,
        "rot_x": 90.0,
        "red": VIOLET[0], "green": VIOLET[1], "blue": VIOLET[2],
        "through_blocks": 0.0,
        "intensity": kf([(0, 0.95), (70, 0.5), (120, 0.0)]),
    }, positions=[[OX, OY + 0.2, OZ]])

    effects["dust"] = definition("particles", 240, {
        "rate": kf([(0, 60), (120, 95), (240, 60)]),
        "radius": 2.2,
        "height": 12.0,
        "turns": 3.5,
        "spin": kf([(0, 0.0), (240, 1.0)]),
        "vel_y": 0.012,
        "size": 1.1,
        "color_r": VIOLET[0], "color_g": VIOLET[1], "color_b": VIOLET[2],
    }, positions=[[OX, OY + 1.0, OZ]], extra={"particle": "dust", "shape": "helix"})

    effects["motes"] = definition("particles", 240, {
        "rate": 22.0,
        "radius": 6.5,
        "speed": 0.008,
        "vel_y": 0.012,
        "spread": 0.4,
    }, positions=[[OX, OY + 4.0, OZ]], extra={"particle": "minecraft:end_rod", "shape": "sphere"})

    effects["outline"] = definition("block_outline", 240, {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": kf([(0, 0.2), (120, 0.7), (240, 0.2)]),
        "width": 0.045,
        "through_blocks": 0.0,
    }, positions=[[sx, OY + 6, sz] for sx, sz in stone_pos])

    effects["spirit"] = definition("entity_outline", 240, {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": kf([(0, 0.35), (120, 0.95), (240, 0.35)]),
        "width": 0.05,
        "through_blocks": 0.0,
    }, extra={"entity_selector": f"@e[tag={TAG_SPIRIT},limit=1]"})

    # The two grades are the same sphere, one inverted: the world loses its colour, the rift keeps it.
    effects["grade_out"] = definition("color_grade", 240, {
        "saturation": 0.42,
        "contrast": 1.06,
        "brightness": 0.96,
        "tint_r": 0.94, "tint_g": 0.98, "tint_b": 1.06,
    }, mask={"a": core_mask["a"], "invert": True})

    effects["bloom"] = definition("bloom", 240, {
        "intensity": 0.34,
        "threshold": 0.62,
        "radius": 4.0,
    })

    effects["vignette"] = definition("vignette", 240, {"intensity": 0.44})

    effects["spectral"] = definition("spectral", 240, {
        "amount": kf([(0, 0.03), (120, 0.11), (240, 0.03)]),
        "mode": 1.0,
        "center_x": 0.5,
        "center_y": 0.5,
        "mid": 0.58,
        "core": 0.84,
    }, mask=core_mask)

    for name, data in effects.items():
        with io.open(os.path.join(vfx, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        print(f"  data/rift/vfx/{name}.json")

    # one collection starts the whole scene, staged in the order the eye should find it
    scene_children = [
        {"effect": "rift:grade_out", "delay": 0, "duration": -1},
        {"effect": "rift:vignette", "delay": 2, "duration": -1},
        {"effect": "rift:bloom", "delay": 4, "duration": -1},
    ]
    for i, (sx, sz) in enumerate(stone_pos):
        scene_children.append({
            "effect": "rift:beam",
            "delay": 8 + (i // 2) * 2,
            "duration": -1,
            "params": {"pos_x": float(sx), "pos_y": float(OY + 6), "pos_z": float(sz)},
        })
    scene_children += [
        {"effect": "rift:pulse", "delay": 14, "duration": -1},
        {"effect": "rift:dust", "delay": 18, "duration": -1},
        {"effect": "rift:motes", "delay": 22, "duration": -1},
        {"effect": "rift:spirit", "delay": 24, "duration": -1},
        {"effect": "rift:outline", "delay": 26, "duration": -1},
        {"effect": "rift:spectral", "delay": 30, "duration": -1},
    ]
    scene = {
        "type": "collection",
        "duration": 400,
        "persistent": True,
        "loop": False,
        "fade_ticks": 0,
        "effects": scene_children,
    }
    with io.open(os.path.join(vfx, "scene.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(scene, f, ensure_ascii=False, separators=(",", ":"))
    print("  data/rift/vfx/scene.json")

    # ------------------------------------------------------------------ functions
    print("rift: functions")
    write("data/rift/function/build.mcfunction", [
        "# Assemble the geometry. Safe to run twice: every block is placed, nothing is additive.",
        "function rift:scene/floor",
        "function rift:scene/obelisk",
        "function rift:scene/stones",
        "function rift:scene/approach",
    ])

    write("data/rift/function/spawn.mcfunction", [
        "# Entities last, so a rebuild never leaves two crowns in the air.",
        f"kill @e[tag={TAG_SHARD}]",
        f"kill @e[tag={TAG_SPIRIT}]",
        "function rift:scene/shards",
    ])

    write("data/rift/function/tp.mcfunction", [
        "# The viewer stands on the platform at the end of the bridge, looking north at the obelisk.",
        f"tp @s {OX + 0.5} {OY + 1.2} {OZ + 27.5} 180 -6",
    ])

    write("data/rift/function/night.mcfunction", [
        "# Optional: the scene is built for a dark sky. This sets the time once; it does not freeze it.",
        "time set midnight",
        "weather clear",
    ])

    write("data/rift/function/go.mcfunction", [
        "# Assemble and start everything.",
        "function rift:clear",
        "function rift:build",
        "function rift:spawn",
        "function rift:tp",
        "function rift:night",
        "vfx play rift:scene",
    ])

    write("data/rift/function/stop.mcfunction", [
        "# Children of a collection are their own plays, so each one is stopped by name.",
        "vfx stop rift:beam",
        "vfx stop rift:pulse",
        "vfx stop rift:dust",
        "vfx stop rift:motes",
        "vfx stop rift:outline",
        "vfx stop rift:spirit",
        "vfx stop rift:grade_out",
        "vfx stop rift:bloom",
        "vfx stop rift:vignette",
        "vfx stop rift:spectral",
        "vfx stop rift:scene",
    ])

    write("data/rift/function/clear.mcfunction", [
        "# Remove the scene: effects, entities and every block in the bounding box.",
        "function rift:stop",
        f"kill @e[tag={TAG_SHARD}]",
        f"kill @e[tag={TAG_SPIRIT}]",
        f"fill {OX - 2} {OY - 3} {OZ - 17} {OX + 2} {OY + 22} {OZ + 29} minecraft:air",
        f"fill {OX - 17} {OY - 3} {OZ - 17} {OX + 17} {OY + 22} {OZ + 17} minecraft:air",
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

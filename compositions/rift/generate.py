"""Generate the RIFT composition datapack.

RIFT is one still frame made in game around a single idea: a sphere of inverted world hanging over a
dark island, with everything around it spiralling in. The sphere is a **mask**, not blocks - a world
sphere with `volume: aura` cuts a disc out of the frame - and the inversion, the swirl, the dent and
the converging streaks are all driven by that same point on screen.

Everything is assembled by `/function rift:go`; every effect value is a constant, because the piece
has to read in a single screenshot.

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
PLATFORM_Z = 22
TAG_DEBRIS = "rift_debris"
TAG_SPIRIT = "rift_spirit"

# The sphere: its centre is the composition. Everything else is placed against it.
SX, SY, SZ = OX + 0.5, OY + 15.0, OZ + 0.5
R_SPHERE = 9.0
CAM_Z = OZ + PLATFORM_Z + 0.5
CAM_Y = OY + 2.0
CAM_PITCH = -18.0   # degrees; negative looks up
BASE_FOV = 70.0
FOV_DELTA = -18.0

VIOLET = (0.62, 0.44, 1.0)
COLD_WHITE = (0.86, 0.92, 1.0)


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


def display(name, x, y, z, scale, rotation, tag=TAG_DEBRIS):
    nbt = (
        'block_state:{Name:"minecraft:%s"},'
        'transformation:{left_rotation:[%.4ff,%.4ff,%.4ff,%.4ff],scale:[%.2ff,%.2ff,%.2ff]},'
        'brightness:{sky:15,block:15},Tags:["%s"]'
        % (name, rotation[0], rotation[1], rotation[2], rotation[3], scale[0], scale[1], scale[2], tag)
    )
    return f"summon minecraft:block_display {x:.2f} {y:.2f} {z:.2f} {{{nbt}}}"


def floor_block(dx, dz, r):
    if r <= 1.6:
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
    lines = ["# The disc, the rock under it, and a well in the middle that the sphere is fed from."]
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
    # the well: a shallow shaft with a glowing floor, right under the sphere
    for dy in range(0, -4, -1):
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if math.hypot(dx, dz) > 2.6:
                    continue
                if dy == 0:
                    lines.append(setblock(OX + dx, OY, OZ + dz, "air"))
                elif dy == -3:
                    lines.append(setblock(OX + dx, OY + dy, OZ + dz, "sea_lantern"))
                else:
                    lines.append(setblock(OX + dx, OY + dy, OZ + dz, "deepslate_tiles"))
    write("data/rift/function/scene/island.mcfunction", lines)

    # ------------------------------------------------------------------ stones
    print("rift: stones")
    stones = ["# Eight stones leaning towards the sphere: the ring that feeds it."]
    stone_top = []
    stone_cells = []
    for k in range(8):
        a = math.radians(45 * k)
        sx = OX + round(13 * math.cos(a))
        sz = OZ + round(13 * math.sin(a))
        for y in range(0, 5):
            stones.append(setblock(sx, OY + y, sz, "deepslate" if y < 4 else "sea_lantern"))
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx or dz:
                    stones.append(setblock(sx + dx, OY, sz + dz, "polished_blackstone"))
        stone_top.append((sx + 0.5, OY + 5.0, sz + 0.5))
        stone_cells.append((sx, OY + 2, sz))
    write("data/rift/function/scene/stones.mcfunction", stones)

    # ------------------------------------------------------------------ debris
    print("rift: debris")
    debris = ["# Debris on its way in: turned plates and shards, each one tilted towards the sphere."]
    materials = ["amethyst_block", "deepslate", "sea_lantern", "budding_amethyst", "polished_blackstone"]
    for k in range(22):
        a = math.radians(360.0 / 22 * k + 7.0)
        r = 10.6 + 6.4 * ((k * 7) % 11) / 11.0
        y = OY + 6.0 + 16.0 * ((k * 5) % 13) / 13.0
        x, z = OX + r * math.cos(a), OZ + r * math.sin(a)
        name = materials[k % len(materials)]
        scale = (0.7 + 0.9 * ((k * 3) % 7) / 7.0, 0.22, 0.5 + 0.8 * ((k * 5) % 7) / 7.0)
        axis = (-math.sin(a), 0.0, math.cos(a))
        debris.append(display(name, x, y, z, scale, quat(axis, 30.0 + 40.0 * ((k * 3) % 5) / 5.0)))
    debris.append(
        f"summon minecraft:allay {SX:.2f} {SY + 0.4:.2f} {SZ:.2f} "
        '{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["rift_spirit"]}'
    )
    write("data/rift/function/scene/debris.mcfunction", debris)

    # ------------------------------------------------------------------ approach
    print("rift: approach")
    approach = ["# Bridge and platform; the viewer's eye ends up level with the sphere's lower third."]
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

    # The sphere is this mask, and only this mask: `aura` fills the volume including the air, so it
    # cuts a disc out of the frame instead of painting the objects inside it.
    sphere_mask = {
        "shape": "sphere",
        "space": "world",
        "volume": "aura",
        "center": [float(SX), float(SY), float(SZ)],
        "radius": R_SPHERE,
        "softness": 0.8,
    }
    # where the sphere lands on screen: the screen-space effects must be centred there, not on the
    # frame, or the swirl happens around empty sky
    elev = math.degrees(math.atan2(SY - CAM_Y, CAM_Z - SZ))
    fov = BASE_FOV + FOV_DELTA
    uv_y = 0.5 - (elev - (-CAM_PITCH)) / fov
    screen_centre = {"center_x": 0.5, "center_y": round(uv_y, 4)}
    print(f"rift: sphere at screen uv (0.5, {uv_y:.3f}), fov {fov:.0f}")

    effects = {}

    # --- the sphere itself
    effects["invert_in"] = definition("invert", {"intensity": 1.0,
                                                 "screen_layer": 0}, mask=sphere_mask)
    effects["grade_in"] = definition("color_grade", {
        "saturation": 0.75,
        "contrast": 1.12,
        "brightness": 1.06,
        "tint_r": 0.92, "tint_g": 0.96, "tint_b": 1.10,
    }, mask=sphere_mask)

    # --- the pull
    effects["swirl"] = definition("vortex", {
        "strength": 1.05,
        "radius": 0.62,
        **screen_centre,
    })
    effects["dent"] = definition("dent", {
        "strength": 0.55,
        "radius": 0.40,
        **screen_centre,
    })
    effects["streaks"] = definition("speed_lines", {
        "count": 84.0,
        "length": 0.62,
        "length_rand": 0.85,
        "pos_rand": 1.0,
        "width": 0.32,
        "seed": 3.0,
        "color_r": 0.80, "color_g": 0.76, "color_b": 1.0,
        "intensity": 0.30,
        **screen_centre,
    })
    effects["chroma"] = definition("chromatic_aberration", {
        "intensity": 0.55,
        "radius": 0.85,
    })

    # --- light and air
    effects["rim"] = definition("pulse_ring", {
        "radius": R_SPHERE + 0.18,
        "thickness": 0.11,
        "billboard": 1.0,
        "red": 0.78, "green": 0.72, "blue": 1.0,
        "through_blocks": 1.0,
        "intensity": 0.85,
    }, positions=[[SX, SY, SZ]])

    effects["column"] = definition("light_beam", {
        "radius": 0.7,
        "height": 13.0,
        "top_scale": 2.6,
        "softness": 3.0,
        "top_fade": 0.0,
        "bottom_fade": 0.1,
        "red": VIOLET[0], "green": VIOLET[1], "blue": VIOLET[2],
        "intensity": 0.45,
        "through_blocks": 0.0,
    }, positions=[[SX, OY + 1.0, SZ]])

    # the ring's stones fire into the sphere
    effects["feed"] = definition("light_beam", {
        "radius": 0.16,
        "height": 12.0,
        "top_scale": 1.6,
        "softness": 2.4,
        "top_fade": 0.0,
        "bottom_fade": 0.8,
        "red": COLD_WHITE[0], "green": COLD_WHITE[1], "blue": COLD_WHITE[2],
        "intensity": 0.35,
        "through_blocks": 0.0,
    })

    # --- the matter falling in: a shell of particles aimed at the centre
    effects["infall"] = definition("particles", {
        "rate": 150.0,
        "radius": 15.0,
        "speed": 0.055,
        "aim": 1.0,
        "spread": 0.18,
        "lifetime": 90.0,
        "size": 0.9,
        "color_r": VIOLET[0], "color_g": VIOLET[1], "color_b": VIOLET[2],
    }, positions=[[SX, SY, SZ], [SX, SY, SZ]],
        extra={"particle": "dust", "shape": "sphere"})

    effects["fallback_glow"] = definition("particles", {
        "rate": 40.0,
        "radius": 5.0,
        "height": 9.0,
        "turns": 2.0,
        "spin": 0.2,
        "vel_y": 0.012,
    }, positions=[[SX, OY + 1.0, SZ]],
        extra={"particle": "minecraft:end_rod", "shape": "helix"})


    effects["spectral"] = definition("spectral", {
        "amount": 0.22,
        "mode": 1.0,
        "center_x": 0.5,
        "center_y": 0.5,
        "mid": 0.5,
        "core": 0.86,
    }, mask=sphere_mask)

    effects["grain"] = definition("film_grain", {
        "intensity": 0.16,
        "size": 2.0,
        "chroma": 0.3,
    })

    effects["bars"] = definition("letterbox", {
        "height": 0.10,
        "color_r": 0.0, "color_g": 0.0, "color_b": 0.0,
    })

    effects["roll"] = definition("camera_roll", {"angle": 2.5, "wobble": 0.0})

    effects["tear"] = definition("slice_shift", {
        "angle": 12.0,
        "offset": 0.18,
        "shift": 0.012,
        "mirror": 1.0,
    })

    effects["ground_ring"] = definition("pulse_ring", {
        "radius": 5.4,
        "thickness": 0.10,
        "billboard": 0.0,
        "rot_x": 90.0,
        "red": VIOLET[0], "green": VIOLET[1], "blue": VIOLET[2],
        "through_blocks": 1.0,
        "intensity": 0.55,
    }, positions=[[SX, OY + 0.15, SZ]])

    effects["orbit"] = definition("particles", {
        "rate": 55.0,
        "radius": 10.6,
        "speed": 0.0,
        "spread": 0.2,
        "size": 0.8,
        "color_r": VIOLET[0], "color_g": VIOLET[1], "color_b": VIOLET[2],
    }, positions=[[SX, SY, SZ]],
        extra={"particle": "dust", "shape": "ring"})

    effects["tint"] = definition("block_tint", {
        "color_r": VIOLET[0], "color_g": VIOLET[1], "color_b": VIOLET[2],
        "alpha": 0.28,
        "through_blocks": 1.0,
    }, positions=[list(c) for c in stone_cells])

    effects["outline"] = definition("block_outline", {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": 0.45,
        "width": 0.04,
        "through_blocks": 0.0,
    }, positions=[list(c) for c in stone_cells])

    effects["spirit"] = definition("entity_outline", {
        "color_r": COLD_WHITE[0], "color_g": COLD_WHITE[1], "color_b": COLD_WHITE[2],
        "alpha": 0.9,
        "width": 0.06,
        "through_blocks": 1.0,
    }, extra={"entity_selector": f"@e[tag={TAG_SPIRIT},limit=1]"})

    # --- the world outside the sphere
    effects["grade_out"] = definition("color_grade", {
        "saturation": 0.45,
        "contrast": 1.04,
        "brightness": 0.92,
        "tint_r": 0.94, "tint_g": 0.97, "tint_b": 1.08,
    }, mask={**sphere_mask, "invert": True})

    effects["fog"] = definition("fog_modifier", {
        "fog_start_scale": 0.7,
        "fog_end_scale": 1.0,
        "fog_r": 0.36, "fog_g": 0.32, "fog_b": 0.62,
        "fog_color_amount": 0.5,
    })

    effects["bloom"] = definition("bloom", {
        "intensity": 0.55,
        "threshold": 0.5,
        "radius": 5.0,
    })

    effects["vignette"] = definition("vignette", {"intensity": 0.55})

    effects["fov"] = definition("fov_modifier", {"fov_delta": FOV_DELTA})

    for name, data in effects.items():
        with io.open(os.path.join(vfx, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        print(f"  data/rift/vfx/{name}.json")

    # the play order is also the stop order: one list, so the two can never drift apart
    order = [
        "grade_out", "invert_in", "grade_in", "spectral", "swirl", "dent", "streaks",
        "chroma", "tear", "grain", "bars", "roll", "fog", "bloom", "vignette", "fov",
        "rim", "column", "ground_ring", "orbit", "infall", "fallback_glow",
        "tint", "outline", "spirit",
    ]
    children = [{"effect": f"rift:{name}", "delay": 0, "duration": -1} for name in order]
    for (sx, sy, sz) in stone_top:
        d = (SX - sx, SY - sy, SZ - sz)
        length = math.sqrt(d[0] ** 2 + d[1] ** 2 + d[2] ** 2)
        children.append({
            "effect": "rift:feed",
            "delay": 0,
            "duration": -1,
            "params": {
                "pos_x": sx, "pos_y": sy, "pos_z": sz,
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
        "function rift:scene/stones",
        "function rift:scene/approach",
    ])

    write("data/rift/function/spawn.mcfunction", [
        "# Entities last, so a rebuild never leaves two rings of debris in the air.",
        f"kill @e[tag={TAG_DEBRIS}]",
        f"kill @e[tag={TAG_SPIRIT}]",
        "function rift:scene/debris",
    ])

    # camera: level with the sphere's lower third, aimed at its centre
    write("data/rift/function/tp.mcfunction", [
        "# The viewer stands on the platform: the sphere in the upper half, the island across the bottom.",
        f"tp @s {SX:.1f} {CAM_Y:.1f} {CAM_Z:.1f} 180 {CAM_PITCH:.1f}",
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

    stop_lines = ["# Children of a collection are their own plays, so each one is stopped by name."]
    stop_lines += [f"vfx stop rift:{name}" for name in order]
    stop_lines += ["vfx stop rift:feed", "vfx stop rift:scene"]
    write("data/rift/function/stop.mcfunction", stop_lines)

    write("data/rift/function/clear.mcfunction", [
        "# Remove the scene: effects, entities and every block in the bounding box.",
        "function rift:stop",
        f"kill @e[tag={TAG_DEBRIS}]",
        f"kill @e[tag={TAG_SPIRIT}]",
        f"fill {OX - 3} {OY - 3} {OZ - 17} {OX + 3} {OY + 20} {OZ + 24} minecraft:air",
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

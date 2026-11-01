"""Static checks for a composition datapack (compositions/<name>).

Nothing here can run Minecraft, so it checks the things that are checkable from the files:
every function target exists, every effect id a function or a collection names exists, every
command has the right arity, every block/summon sits inside the box that `clear` erases (so a
rebuild never leaves debris), and no definition references a param the mod would reject.

Usage: python scripts/check-composition.py [compositions/rift]
"""
import io
import json
import os
import re
import sys

COMMANDS = {
    "setblock": (5, 5),
    "fill": (8, 8),
    "summon": (4, 6),
    "tp": (4, 7),
    "kill": (2, 2),
    "vfx": (2, 4),
    "function": (2, 2),
    "time": (2, 3),
    "weather": (2, 3),
    "schedule": (3, 4),
    "gamerule": (3, 3),
    "playsound": (3, 6),
}
BLOCK_RE = re.compile(r"^minecraft:[a-z0-9_]+$")


def fail(problems, msg):
    problems.append(msg)


def split(line):
    """Split a command into tokens, keeping {...} and "..." together (NBT may contain spaces)."""
    tokens, buf, depth, quoted = [], "", 0, False
    for ch in line:
        if quoted:
            buf += ch
            if ch == '"':
                quoted = False
        elif ch == '"':
            quoted = True
            buf += ch
        elif ch in "{[":
            depth += 1
            buf += ch
        elif ch in "}]":
            depth = max(0, depth - 1)
            buf += ch
        elif ch == " " and depth == 0:
            if buf:
                tokens.append(buf)
                buf = ""
        else:
            buf += ch
    if buf:
        tokens.append(buf)
    return tokens


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pack = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "compositions", "rift"))
    ns_dirs = os.path.join(pack, "data")
    if not os.path.isdir(ns_dirs):
        sys.exit(f"no data/ in {pack}")

    problems = []
    functions = {}     # rift:scene/floor -> path
    effects = {}       # rift:beam -> path
    for namespace in sorted(os.listdir(ns_dirs)):
        ns = os.path.join(ns_dirs, namespace)
        for dirpath, _, names in os.walk(ns):
            for name in names:
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, ns).replace("\\", "/")
                if name.endswith(".mcfunction"):
                    parts = rel.split("/")
                    functions[namespace + ":" + "/".join(parts[1:])[:-len(".mcfunction")]] = full
                elif name.endswith(".json") and "/vfx/" in "/" + rel:
                    effects[namespace + ":" + name[:-5]] = full

    if not functions:
        fail(problems, "no .mcfunction files")
    if not effects:
        fail(problems, "no vfx definitions")

    boxes = []
    placed = []

    for fid, path in sorted(functions.items()):
        for lineno, raw in enumerate(io.open(path, encoding="utf-8").read().splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "\t" in raw:
                fail(problems, f"{fid}:{lineno}: tab in a command line")
            if line.count('"') % 2:
                fail(problems, f"{fid}:{lineno}: odd number of quotes")
            tokens = split(line)
            head = tokens[0]
            if head not in COMMANDS:
                fail(problems, f"{fid}:{lineno}: unknown command {head!r}")
                continue
            lo, hi = COMMANDS[head]
            argc = len(tokens)
            if not lo <= argc <= hi:
                fail(problems, f"{fid}:{lineno}: {head} takes {lo}..{hi} tokens, has {argc}: {line[:90]}")
                continue

            args = tokens
            if head == "setblock":
                if not BLOCK_RE.match(args[4]):
                    fail(problems, f"{fid}:{lineno}: bad block id {args[4]!r}")
                try:
                    placed.append((fid, lineno, tuple(int(v) for v in args[1:4])))
                except ValueError:
                    fail(problems, f"{fid}:{lineno}: setblock coordinates are not integers")
            elif head == "fill":
                if not BLOCK_RE.match(args[7]):
                    fail(problems, f"{fid}:{lineno}: bad block id {args[7]!r}")
                try:
                    x1, y1, z1, x2, y2, z2 = (int(v) for v in args[1:7])
                    if args[7] == "minecraft:air":
                        boxes.append((min(x1, x2), min(y1, y2), min(z1, z2), max(x1, x2), max(y1, y2), max(z1, z2)))
                    else:
                        fail(problems, f"{fid}:{lineno}: a fill that is not air - the composition is built by setblock")
                except ValueError:
                    fail(problems, f"{fid}:{lineno}: fill coordinates are not integers")
            elif head == "summon":
                try:
                    placed.append((fid, lineno, tuple(int(float(v)) for v in args[2:5])))
                except ValueError:
                    fail(problems, f"{fid}:{lineno}: summon coordinates are not numbers")
            elif head == "function":
                if args[1] not in functions:
                    fail(problems, f"{fid}:{lineno}: calls missing function {args[1]}")
            elif head == "vfx":
                if args[1] not in ("play", "stop", "playat"):
                    fail(problems, f"{fid}:{lineno}: unexpected vfx subcommand {args[1]!r}")
                elif len(args) > 2 and args[2] not in effects:
                    fail(problems, f"{fid}:{lineno}: names missing effect {args[2]}")

    # every placed block must be erasable by one of the clear boxes
    for fid, lineno, (x, y, z) in placed:
        if not any(bx1 <= x <= bx2 and by1 <= y <= by2 and bz1 <= z <= bz2
                   for bx1, by1, bz1, bx2, by2, bz2 in boxes):
            fail(problems, f"{fid}:{lineno}: ({x}, {y}, {z}) is outside every clear box")

    # a param is legal for an effect type if any shipped definition of that type uses it: the mod
    # registers every param of a type, so a definition that does not mention one can still be
    # overridden by a collection child.
    known_params = {}
    for extra_dir in (os.path.join(root, "src", "main", "resources", "data", "vfxweaver", "vfx"),
                      os.path.join(root, "showcase", "data", "vfx_demos", "vfx")):
        if not os.path.isdir(extra_dir):
            continue
        for name in os.listdir(extra_dir):
            if not name.endswith(".json"):
                continue
            try:
                d = json.load(io.open(os.path.join(extra_dir, name), encoding="utf-8"))
            except ValueError:
                continue
            known_params.setdefault(d.get("type"), set()).update((d.get("params") or {}).keys())

    # collection children must exist, and their overrides must be real params of the effect
    for eid, path in sorted(effects.items()):
        data = json.load(io.open(path, encoding="utf-8"))
        if data.get("type") != "collection":
            continue
        for i, child in enumerate(data.get("effects", [])):
            target = child.get("effect")
            if target not in effects:
                fail(problems, f"{eid}: child {i} names missing effect {target}")
                continue
            child_def = json.load(io.open(effects[target], encoding="utf-8"))
            child_type = child_def.get("type")
            legal = set((child_def.get("params") or {}).keys()) | known_params.get(child_type, set())
            overrides = child.get("params") or {}
            if not isinstance(overrides, dict):
                fail(problems, f"{eid}: child {i} params is not an object")
                continue
            for key in overrides:
                if key.startswith("mask.") or key in ("pos_x", "pos_y", "pos_z", "screen_layer") or key in legal:
                    continue
                fail(problems, f"{eid}: child {i} overrides {key!r}, which is not a param of {child_type}")

    print(f"composition: {os.path.basename(pack)} - {len(functions)} functions, {len(effects)} definitions, "
          f"{len(placed)} placed blocks, {len(boxes)} clear boxes")
    if problems:
        print(f"{len(problems)} problem(s):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print("composition check OK")


if __name__ == "__main__":
    main()

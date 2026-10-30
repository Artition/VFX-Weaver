"""Build the published showcase datapack zip from showcase/.

Usage: python scripts/build-showcase-pack.py

The pack is committed as a plain directory (showcase/) so it can be edited and reviewed like any
other source; this script packs it into the two places the docs serve it from, with forward-slash
entry names (a backslash name makes the whole pack invisible to a Java datapack loader) and fixed
timestamps so an unchanged pack produces a byte-identical zip.
"""
import io
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "showcase")
DEST = os.path.join(ROOT, "docs", "assets", "downloads", "vfx-demos-showcase.zip")
DATE = (2026, 10, 30, 0, 0, 0)


def main():
    if not os.path.isdir(SRC):
        sys.exit("showcase/ not found")

    files = []
    for dirpath, _, names in os.walk(SRC):
        for name in names:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, SRC).replace("\\", "/")
            files.append((rel, full))
    files.sort()

    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    with zipfile.ZipFile(DEST, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, full in files:
            info = zipfile.ZipInfo(rel, DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            with io.open(full, "rb") as f:
                z.writestr(info, f.read())

    size = os.path.getsize(DEST)
    print(f"packed {len(files)} files -> {os.path.relpath(DEST, ROOT)} ({size} bytes)")


if __name__ == "__main__":
    main()

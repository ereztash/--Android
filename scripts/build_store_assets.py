#!/usr/bin/env python3
"""Render the Play store icon and feature graphic FROM the shipped app resources.

### Why generate rather than draw
Play needs a 512x512 icon and a 1024x500 feature graphic as PNGs. The app already carries an
adaptive icon as a vector, and the keyboard already carries its palette in `colors.xml`. Two
hand-made PNGs beside those would be a third source of truth that drifts the first time anyone
changes a colour -- and nobody would notice, because a store asset is looked at once.

So both are rendered from the same files the app ships, and `GATE-STORE-1` re-renders them and
compares bytes. A store icon that no longer matches the app's icon fails the build.

### What this does NOT produce
Screenshots. Play screenshots must show the app as it actually is, and nothing here has been
rendered by Android. A faithful re-draw would still be a fabrication, and this repository does
not publish one. They need a device.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
FOREGROUND = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
COLORS = os.path.join(RES, "values", "colors.xml")
OUT_DIR = os.path.join(ROOT, "store")

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Adaptive icons are 108x108dp with the outer 18dp on each side reserved for masking, so only
# the middle 72x72 is guaranteed visible. The store icon is NOT masked, so it is rendered as a
# full square with the safe area scaled to fill a conventional icon margin instead.
VIEWPORT = 108.0
SAFE = 72.0


def read_colors() -> dict[str, str]:
    tree = ET.parse(COLORS)
    return {c.get("name"): c.text.strip() for c in tree.getroot().findall("color")}


def parse_paths(path: str) -> list[tuple[str, list[tuple[float, float]]]]:
    """Parse the vector drawable's paths.

    Deliberately supports only M, L and Z -- the commands the shipped icon uses. Anything else
    raises rather than being silently skipped, because an icon quietly missing a curve is
    exactly the kind of difference nobody looks closely enough to catch.
    """
    tree = ET.parse(path)
    out = []
    for node in tree.getroot().findall("path"):
        data = node.get(f"{ANDROID}pathData")
        fill = node.get(f"{ANDROID}fillColor")
        tokens = re.findall(r"([MLZmlz])|(-?\d+(?:\.\d+)?)", data)
        points: list[tuple[float, float]] = []
        nums: list[float] = []
        command = None
        for cmd, num in tokens:
            if cmd:
                if command in ("M", "L") and len(nums) >= 2:
                    points.extend(
                        (nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2))
                nums = []
                command = cmd.upper()
                if command not in ("M", "L", "Z"):
                    raise SystemExit(f"unsupported path command {cmd!r} in {path}")
            else:
                nums.append(float(num))
        if command in ("M", "L") and len(nums) >= 2:
            points.extend((nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2))
        out.append((fill, points))
    return out


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    if len(value) == 8:          # AARRGGBB
        value = value[2:]
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def render_icon(size: int) -> Image.Image:
    colors = read_colors()
    background = hex_to_rgb(colors["ic_launcher_background"])
    image = Image.new("RGB", (size, size), background)
    draw = ImageDraw.Draw(image)

    # Fit the adaptive icon's SAFE area to a 76% square, which is the proportion a launcher
    # ends up showing after masking. Rendering the full 108 viewport instead would make the
    # store icon visibly smaller than the one on the phone.
    scale = (size * 0.76) / SAFE
    offset = (VIEWPORT - SAFE) / 2.0
    centre = size / 2.0 - (SAFE / 2.0) * scale

    for fill, points in parse_paths(FOREGROUND):
        if len(points) < 3:
            continue
        pixels = [((x - offset) * scale + centre, (y - offset) * scale + centre)
                  for x, y in points]
        draw.polygon(pixels, fill=hex_to_rgb(fill))
    return image


def render_feature_graphic(width: int = 1024, height: int = 500) -> Image.Image:
    """The 1024x500 banner: the icon's mark on the keyboard's own background.

    No text. Play renders the app name over the feature graphic on some surfaces and not on
    others, so baked-in text is either duplicated or cropped, and a graphic that says the same
    thing twice reads as a mistake rather than as emphasis.
    """
    colors = read_colors()
    image = Image.new("RGB", (width, height), hex_to_rgb(colors["ic_launcher_background"]))
    draw = ImageDraw.Draw(image)

    # The mark on the left, the keyboard's own key grid on the right.
    key = hex_to_rgb(colors["key_background_function"])
    mark_size = int(height * 0.62)
    margin = int(height * 0.10)
    grid_left = margin * 2 + mark_size

    # Rows of 8, 10 and 9 -- the real Hebrew layout -- sized so the WIDEST row fits the space
    # that is left, and every row centred on that shared grid. Same arithmetic as
    # KeyGeometry.layout: one unit for the whole keyboard, short rows centred in the leftover.
    # A banner whose key widths disagreed with the product's would be a small lie in the one
    # image most people see before installing.
    widest = 10
    unit = (width - grid_left - margin) / widest
    gap = unit * 0.09
    for row, count in enumerate((8, 10, 9)):
        left = grid_left + (widest - count) * unit / 2.0
        top = height / 2.0 - 1.5 * unit + row * unit
        for i in range(count):
            x = left + i * unit
            draw.rounded_rectangle(
                [x + gap, top + gap, x + unit - gap, top + unit - gap],
                radius=unit * 0.18, fill=key,
            )

    image.paste(render_icon(mark_size), (margin, (height - mark_size) // 2))
    return image


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=OUT_DIR)
    ap.add_argument("--inject-defect", action="store_true",
                    help="PLANT A DEFECT. Positive control for GATE-STORE-1; must go red.")
    ap.add_argument("--check", action="store_true",
                    help="render to a temporary location and compare bytes with what is "
                         "committed; exit non-zero on any difference")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    wanted = {
        "play_icon_512.png": render_icon(512),
        "play_feature_graphic_1024x500.png": render_feature_graphic(),
    }
    if args.inject_defect:
        # PLANTED DEFECT: one pixel of the icon's background. This is what a store asset left
        # behind by a palette change looks like -- not a visible difference, which is the
        # point: a check that only caught obvious drift would not be worth running.
        icon = wanted["play_icon_512.png"]
        r, g, b = icon.getpixel((0, 0))
        icon.putpixel((0, 0), (r ^ 1, g, b))
    ok = True
    for name, image in wanted.items():
        path = os.path.join(args.out, name)
        if args.check:
            if not os.path.isfile(path):
                print(f"MISSING {name}", file=sys.stderr)
                ok = False
                continue
            import io
            buffer = io.BytesIO()
            image.save(buffer, "PNG", optimize=True)
            if buffer.getvalue() != open(path, "rb").read():
                print(f"DIFFERS {name}: the committed asset is not what the app's resources "
                      f"render to", file=sys.stderr)
                ok = False
            else:
                print(f"ok {name}")
        else:
            image.save(path, "PNG", optimize=True)
            print(f"wrote {path} ({image.size[0]}x{image.size[1]})")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

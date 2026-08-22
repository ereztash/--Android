#!/usr/bin/env python3
"""Generate the launcher icon's foreground from the app's own shipped typeface.

### Why the icon is generated and not drawn
The previous foreground's comment said "stylised alef". It was a Latin capital N. A hand-drawn
replacement came out as an X. An icon is looked at once, at 48dp, by someone who already knows
what the app is -- which is exactly the condition under which a wrong letterform survives.

So the letter is not drawn at all. It is the **alef from the typeface the keyboard already
ships**, the one `scripts/build_keyboard_font.py` selected by measuring 351 Hebrew letter pairs
for confusability. The icon is then the app's own letterform by construction, and it cannot be
a letter from some other alphabet.

Quadratic curves are flattened to line segments so the vector drawable needs only M/L/Z. At the
sizes a launcher icon is drawn, the flattening error is far below one pixel, and it keeps
`build_store_assets.py`'s renderer simple enough to be obviously correct.
"""
from __future__ import annotations

import argparse
import os

from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "keyboard_label.ttf")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable",
                   "ic_launcher_foreground.xml")

LETTER = "א"          # ALEF
FILL = "#F2F4F8"           # the keyboard's dark-theme key label colour
VIEWPORT = 108.0

# Adaptive icons reserve the outer 18dp on every side for masking; only the middle 72 is
# guaranteed visible. The letter is fitted inside that, leaving room for the key bar beneath it.
SAFE_MIN, SAFE_MAX = 18.0, 90.0
LETTER_TOP, LETTER_BOTTOM = 24.0, 76.0
BAR_TOP, BAR_BOTTOM = 82.0, 88.0
BAR_LEFT, BAR_RIGHT = 28.0, 80.0

SEGMENTS = 8               # per quadratic; error at 512px is well under a pixel


def flatten(font_path: str, letter: str) -> list[list[tuple[float, float]]]:
    font = TTFont(font_path)
    name = font.getBestCmap().get(ord(letter))
    if name is None:
        raise SystemExit(f"{font_path} has no glyph for U+{ord(letter):04X}")
    pen = RecordingPen()
    font.getGlyphSet()[name].draw(pen)

    contours: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    for op, args in pen.value:
        if op == "moveTo":
            if current:
                contours.append(current)
            current = [args[0]]
        elif op == "lineTo":
            current.append(args[0])
        elif op == "qCurveTo":
            # TrueType quadratics: the last point is on-curve (or None for a closed run of
            # off-curve points), everything before it is a control point. Consecutive control
            # points imply an on-curve point midway between them.
            points = list(args)
            if points[-1] is None:
                points = points[:-1]
                start = ((points[0][0] + points[-1][0]) / 2.0,
                         (points[0][1] + points[-1][1]) / 2.0)
                current.append(start)
            on = current[-1]
            controls = points[:-1]
            end = points[-1]
            for i, control in enumerate(controls):
                if i < len(controls) - 1:
                    nxt = controls[i + 1]
                    segment_end = ((control[0] + nxt[0]) / 2.0, (control[1] + nxt[1]) / 2.0)
                else:
                    segment_end = end
                for s in range(1, SEGMENTS + 1):
                    t = s / SEGMENTS
                    u = 1.0 - t
                    current.append((
                        u * u * on[0] + 2 * u * t * control[0] + t * t * segment_end[0],
                        u * u * on[1] + 2 * u * t * control[1] + t * t * segment_end[1],
                    ))
                on = segment_end
        elif op == "closePath":
            if current:
                contours.append(current)
                current = []
    if current:
        contours.append(current)
    return contours


def fit(contours):
    """Scale the glyph into the letter box, preserving aspect and centring horizontally."""
    xs = [p[0] for c in contours for p in c]
    ys = [p[1] for c in contours for p in c]
    width, height = max(xs) - min(xs), max(ys) - min(ys)
    scale = min((SAFE_MAX - SAFE_MIN) / width, (LETTER_BOTTOM - LETTER_TOP) / height)
    ox = (VIEWPORT - width * scale) / 2.0 - min(xs) * scale
    # Font y grows upward; the vector viewport's grows downward.
    oy = LETTER_BOTTOM + min(ys) * scale
    return [[(x * scale + ox, oy - y * scale) for x, y in c] for c in contours]


def path_data(contour) -> str:
    head = f"M{contour[0][0]:.2f},{contour[0][1]:.2f}"
    rest = "".join(f" L{x:.2f},{y:.2f}" for x, y in contour[1:])
    return head + rest + " Z"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    contours = fit(flatten(FONT, LETTER))
    paths = "\n".join(
        f'    <path\n        android:fillColor="{FILL}"\n'
        f'        android:pathData="{path_data(c)}" />'
        for c in contours
    )
    bar = (f'    <path\n        android:fillColor="{FILL}"\n'
           f'        android:pathData="M{BAR_LEFT},{BAR_TOP} L{BAR_RIGHT},{BAR_TOP} '
           f'L{BAR_RIGHT},{BAR_BOTTOM} L{BAR_LEFT},{BAR_BOTTOM} Z" />')

    xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!--
      GENERATED by scripts/build_launcher_icon.py. Do not edit by hand.

      The letter is ALEF, taken from app/src/main/res/font/keyboard_label.ttf, the typeface
      scripts/build_keyboard_font.py selected by measuring 351 Hebrew letter pairs. The icon is
      therefore the app's own letterform by construction.

      It is generated because the two hand-made versions before it were both wrong. The first
      was a Latin capital N whose comment claimed it was an alef, and it shipped that way. The
      second, drawn to replace it, came out as an X. An icon is looked at once, at 48dp, by
      someone who already knows what the app is, which is exactly the condition under which a
      wrong letterform survives.

      Quadratics are flattened to line segments; the error at 512px is far below one pixel.
    -->
{paths}
    <!-- The key the letter sits on, so the mark reads as a keyboard and not only a letter. -->
{bar}
</vector>
'''
    open(args.out, "w", encoding="utf-8").write(xml)
    print(f"wrote {args.out} ({len(contours)} contours from {LETTER!r})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

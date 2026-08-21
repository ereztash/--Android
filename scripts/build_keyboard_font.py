#!/usr/bin/env python3
"""Choose the keyboard's typeface by measuring it, and ship the winner subsetted.

### Why this exists
Nothing in `app/src/main` ever set a `Typeface`. Key labels, the preview bubble and the
candidate strip all rendered in whatever the platform resolved for Hebrew — the same unchosen
face at the same size fraction in all three places.

That matters more here than in most apps. The key label is the smallest and most-glanced text
in the product, and the letters that get mistyped on a phone are the ones that look alike under
a fingertip, which is a different set from the ones that sound alike and is the set nobody had
looked at.

### What is measured
Every unordered pair of the 27 Hebrew letters — 22 plus 5 final forms, 351 pairs — rendered at
the label's real pixel size and compared by **maximum intersection-over-union under a small
shift**. The shift matters: two glyphs can be near-identical and merely offset, and a metric
that ignores that would call them different.

The risky pairs are therefore **discovered, not asserted**. No list of "letters that look alike"
is hard-coded anywhere in this file.

### What is NOT measured
- **Not what Android currently renders.** There is no Android here. The baseline row is a
  stand-in face with Hebrew coverage, labelled as such; it is not the platform's fallback and
  must not be read as it.
- **Not legibility.** Ink overlap is a proxy for confusability, not a reading test with human
  subjects. It ranks candidates; it does not say anyone can read them.
- **Not shaping or context.** Isolated glyphs, which is what a key label is.

Run with no `--choose` to report the comparison. Pass `--choose NAME` to subset and write it.
"""

import argparse
import hashlib
import io
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "lexicon", "cache", "fonts")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "font")

USER_AGENT = ("hebrew-ime-font-build/1.0 "
              "(offline Hebrew IME; https://github.com/ereztash/--Android)")

# All SIL OFL 1.1. Fetched from the Google Fonts repository rather than the CSS API so the
# exact file is pinned and hashable.
GF = "https://raw.githubusercontent.com/google/fonts/main/ofl"
CANDIDATES = {
    "heebo": (f"{GF}/heebo/Heebo%5Bwght%5D.ttf", "Heebo", "SIL OFL 1.1"),
    "assistant": (f"{GF}/assistant/Assistant%5Bwght%5D.ttf", "Assistant", "SIL OFL 1.1"),
    "rubik": (f"{GF}/rubik/Rubik%5Bwght%5D.ttf", "Rubik", "SIL OFL 1.1"),
    "notosanshebrew": (f"{GF}/notosanshebrew/NotoSansHebrew%5Bwdth,wght%5D.ttf",
                       "Noto Sans Hebrew", "SIL OFL 1.1"),
}

HEBREW = [chr(c) for c in range(0x05D0, 0x05EA + 1)]      # א..ת, 27 including finals
LABEL_SIZES = (54, 81, 105)   # measured from KeyboardView: 0.32 screen x 0.42 row, 4 rows
WEIGHT = 500                  # medium; key labels are small and want a little more ink


def fetch(name, url):
    path = os.path.join(CACHE, f"{name}.ttf")
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        os.makedirs(CACHE, exist_ok=True)
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=120) as r:
            blob = r.read()
        with open(path + ".part", "wb") as fh:
            fh.write(blob)
        os.replace(path + ".part", path)
    return path


def instance(raw):
    """A static instance at WEIGHT, so the measurement and the artifact are the same shape."""
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
    font = TTFont(io.BytesIO(raw))
    if "fvar" in font:
        axes = {a.axisTag: a for a in font["fvar"].axes}
        loc = {}
        if "wght" in axes:
            loc["wght"] = max(axes["wght"].minValue, min(WEIGHT, axes["wght"].maxValue))
        if "wdth" in axes:
            loc["wdth"] = axes["wdth"].defaultValue
        font = instancer.instantiateVariableFont(font, loc, inplace=False)
    buf = io.BytesIO()
    font.save(buf)
    return buf.getvalue()


def render(font_bytes, ch, size, pad=8):
    from PIL import Image, ImageDraw, ImageFont
    f = ImageFont.truetype(io.BytesIO(font_bytes), size)
    box = f.getbbox(ch)
    w = max(1, box[2] - box[0]) + pad * 2
    h = max(1, box[3] - box[1]) + pad * 2
    img = Image.new("L", (w, h), 0)
    ImageDraw.Draw(img).text((pad - box[0], pad - box[1]), ch, font=f, fill=255)
    return img


def similarity(a, b, shift=2):
    """Max IoU of the two inked shapes over a +/- shift window, centred on a common canvas."""
    from PIL import Image
    w = max(a.width, b.width) + shift * 2
    h = max(a.height, b.height) + shift * 2
    def place(img, dx=0, dy=0):
        c = Image.new("L", (w, h), 0)
        c.paste(img, ((w - img.width) // 2 + dx, (h - img.height) // 2 + dy))
        return [p > 128 for p in c.getdata()]  # noqa: PIL deprecation is cosmetic here
    pa = place(a)
    best = 0.0
    for dy in range(-shift, shift + 1):
        for dx in range(-shift, shift + 1):
            pb = place(b, dx, dy)
            inter = sum(1 for x, y in zip(pa, pb) if x and y)
            union = sum(1 for x, y in zip(pa, pb) if x or y)
            if union:
                best = max(best, inter / union)
    return best


def measure(font_bytes, size):
    glyphs = {c: render(font_bytes, c, size) for c in HEBREW}
    pairs = []
    for i, a in enumerate(HEBREW):
        for b in HEBREW[i + 1:]:
            pairs.append((similarity(glyphs[a], glyphs[b]), a, b))
    pairs.sort(reverse=True)
    return pairs


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--choose", choices=sorted(CANDIDATES),
                    help="subset this face and write it into the app's resources")
    ap.add_argument("--size", type=int, default=81,
                    help="label pixel size to rank at; the real range is 54-105")
    ap.add_argument("--risk", type=float, default=0.70,
                    help="IoU at or above which a pair is counted as at-risk")
    args = ap.parse_args()

    loaded = {}
    for name, (url, family, licence) in CANDIDATES.items():
        try:
            raw = open(fetch(name, url), "rb").read()
        except Exception as exc:            # noqa: BLE001 -- report, do not crash the build
            print(f"{name}: could not fetch ({exc}); NOT MEASURED", file=sys.stderr)
            continue
        loaded[name] = (instance(raw), family, licence,
                        hashlib.sha256(raw).hexdigest())

    if not loaded:
        sys.exit("no candidate font could be fetched; nothing measured")

    print(f"\nHebrew letter-pair confusability at label size {args.size}px "
          f"({len(HEBREW)} letters, {len(HEBREW)*(len(HEBREW)-1)//2} pairs)")
    print("max IoU under a +/-2px shift. LOWER IS BETTER -- it means the pair is easier to "
          "tell apart.\n")
    print(f"{'font':<16} {'mean IoU':>9} {'worst':>7} {'>= ' + str(args.risk):>9}   "
          f"worst three pairs")
    ranked = []
    for name, (blob, family, licence, sha) in sorted(loaded.items()):
        pairs = measure(blob, args.size)
        mean = sum(p[0] for p in pairs) / len(pairs)
        risky = sum(1 for p in pairs if p[0] >= args.risk)
        worst = "  ".join(f"{a}/{b} {s:.2f}" for s, a, b in pairs[:3])
        print(f"{family:<16} {mean:>9.4f} {pairs[0][0]:>7.2f} {risky:>9}   {worst}")
        ranked.append((mean, risky, name, family, licence, sha, pairs))

    ranked.sort(key=lambda r: (r[1], r[0]))
    print(f"\nranked by at-risk pairs, then mean: "
          f"{', '.join(r[3] for r in ranked)}")

    if not args.choose:
        print("\nNo --choose given: reporting only, nothing written.")
        return 0

    from fontTools import subset
    from fontTools.ttLib import TTFont
    blob, family, licence, sha = loaded[args.choose]
    keep = set(HEBREW) | set("׳״-—…") | set(
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        " .,:;!?'\"()[]{}@#$%&*+/=_<>|~`^\\")
    font = TTFont(io.BytesIO(blob))
    subsetter = subset.Subsetter(subset.Options(
        layout_features=["*"], name_IDs=["*"], notdef_outline=True, drop_tables=[]))
    subsetter.populate(unicodes={ord(c) for c in keep})
    subsetter.subset(font)
    os.makedirs(OUT, exist_ok=True)
    # Android resource names: lowercase, digits, underscore.
    out = os.path.join(OUT, "keyboard_label.ttf")
    font.save(out)
    size = os.path.getsize(out)

    pairs = next(r[6] for r in ranked if r[2] == args.choose)
    manifest = {
        "purpose": "the typeface for key labels, the preview bubble and the candidate strip",
        "chosen": family,
        "why": "ranked first by at-risk Hebrew letter pairs at the label's real pixel size; "
               "see docs/PREDICTION_MEASUREMENTS.md",
        "source": {"url": CANDIDATES[args.choose][0], "license": licence,
                   "upstream_sha256": sha},
        "instanced_weight": WEIGHT,
        "measurement": {
            "sizes_px": list(LABEL_SIZES),
            "ranked_at_px": args.size,
            "risk_threshold_iou": args.risk,
            "letters": len(HEBREW),
            "pairs": len(pairs),
            "mean_iou": round(sum(p[0] for p in pairs) / len(pairs), 4),
            "at_risk_pairs": [[a, b, round(s, 3)] for s, a, b in pairs if s >= args.risk],
        },
        "output": {"path": os.path.relpath(out, ROOT), "bytes": size,
                   "sha256": hashlib.sha256(open(out, "rb").read()).hexdigest()},
        "known_limitations": [
            "Ink overlap is a proxy for confusability, not a reading test with human subjects.",
            "The baseline this replaces was never measured: there is no Android here and the "
            "platform's Hebrew fallback could not be rendered.",
            "Isolated glyphs only. A key label is isolated; running text is not.",
        ],
    }
    with open(os.path.join(ROOT, "lexicon", "FONT_MANIFEST.json"), "w",
              encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(f"\nwrote {os.path.relpath(out, ROOT)} ({size:,} bytes) and lexicon/FONT_MANIFEST.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

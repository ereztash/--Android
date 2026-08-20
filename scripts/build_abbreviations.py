#!/usr/bin/env python3
"""Mine Hebrew abbreviations (ראשי תיבות) from the Wikipedia dump, with counts.

### Why this needs its own fetch
Both existing lexicon sources are letters-only: the inflected-verb CSV and the frequency list
contain **zero** abbreviation-shaped entries, checked. And the cached bigram corpus cannot help
either — `HEBREW_RUN_RE = [א-ת]+` splits on the gershayim, so `כ"כ` was already destroyed into
two tokens before it was written. Abbreviations have to come from raw article text.

### What counts as one
A Hebrew abbreviation is written with a **gershayim** ־ ״ (U+05F4), or an ASCII `"` in practice —
before its final letter: `כ"כ`, `אח"כ`, `צה"ל`, `עו"ד`. Single-word shortenings take a **geresh**
׳ (U+05F3) or an apostrophe at the end: `וכו'`, `מר'`.

Both Unicode and ASCII spellings are counted and folded together, because people type the ASCII
ones and Wikipedia contains both.

### The threshold comes from the counts
`--min-count` defaults to nothing and the script reports the distribution instead of guessing,
the same discipline as `build_bigrams.py` and `build_skipgrams.py`.
"""
from __future__ import annotations

import argparse
import bz2
import collections
import gzip
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_bigrams import (  # noqa: E402
    DATA_BYTES, DATA_URL, DUMP_DATE, OUT_DIR, ROOT, TEXT_RE,
    chunk_starts, http_get, load_index_offsets, strip_wiki_markup,
)

GERSHAYIM = "״"
GERESH = "׳"
TRAINING_PHASE = 0.5

# A run of Hebrew letters that CONTAINS a gershayim-like mark before its last letter, or ends
# with a geresh-like mark. Deliberately not anchored on the ASCII/Unicode distinction.
ABBREV_RE = re.compile(
    r"(?<![\wא-ת])"
    r"([א-ת]{1,6}[\"״][א-ת]{1,4}"
    r"|[א-ת]{2,8}['׳])"
    r"(?![\wא-ת])"
)


def canonical(form: str) -> str:
    """Fold ASCII quote spellings onto the Hebrew punctuation this app will emit."""
    return form.replace('"', GERSHAYIM).replace("'", GERESH)


# Letters that a geresh MODIFIES phonetically rather than abbreviates: ג׳ = /dʒ/, ז׳ = /ʒ/,
# צ׳ = /tʃ/, ת׳ = /θ/. A word ending in one of these is usually a transliteration caught
# mid-name — the mined data contains `ורג׳` (half of ג׳ורג׳) and `סמית׳` (Smith) — not an
# abbreviation. Excluding them costs a few genuine abbreviations that happen to end in those
# letters, which is the right side to err on: offering `ורג׳` for `ורג` is visible nonsense,
# while a missing abbreviation is merely a missing suggestion.
PHONETIC_GERESH_LETTERS = set("גזצת")


def is_phonetic_transliteration(form: str) -> bool:
    return (form.endswith(GERESH) and len(form) >= 2
            and form[-2] in PHONETIC_GERESH_LETTERS)


def bare(form: str) -> str:
    """The letters alone — what a user types before the marks are added."""
    return re.sub(r"[\"'׳״]", "", form)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunks", type=int, default=6)
    ap.add_argument("--chunk-bytes", type=int, default=12 * 1024 * 1024)
    ap.add_argument("--min-count", type=int, default=None,
                    help="prune below this count. Omit to REPORT the distribution only.")
    ap.add_argument("--out", default=os.path.join(OUT_DIR, "he_abbreviations.txt.gz"))
    args = ap.parse_args()

    offsets = load_index_offsets()
    starts = chunk_starts(offsets, args.chunks, phase=TRAINING_PHASE)
    print(f"fetching {args.chunks} chunks of {args.chunk_bytes} bytes at phase "
          f"{TRAINING_PHASE}", file=sys.stderr)

    counts: collections.Counter = collections.Counter()
    blocks = 0
    for start in starts:
        end = min(start + args.chunk_bytes, DATA_BYTES - 1)
        try:
            raw = http_get(DATA_URL, (start, end))
        except Exception as exc:  # noqa: BLE001
            print(f"  fetch failed at {start} ({exc}), skipped", file=sys.stderr)
            continue
        inner = [o for o in offsets if start <= o < end]
        for i, off in enumerate(inner):
            stop = inner[i + 1] if i + 1 < len(inner) else end + 1
            piece = raw[off - start: stop - start]
            if not piece:
                continue
            try:
                xml = bz2.decompress(piece).decode("utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                continue
            blocks += 1
            for m in TEXT_RE.finditer(xml):
                body = strip_wiki_markup(m.group(1))
                for a in ABBREV_RE.findall(body):
                    counts[canonical(a)] += 1
        print(f"  {blocks} blocks, {len(counts)} distinct abbreviations", file=sys.stderr)

    if not counts:
        print("NO_DATA: no abbreviations found; refusing to write", file=sys.stderr)
        return 1

    print("\ncount distribution -- the threshold comes from THIS:", file=sys.stderr)
    total = sum(counts.values())
    for t in (1, 2, 5, 10, 25, 50, 100, 250):
        kept = {k: v for k, v in counts.items() if v >= t}
        mass = sum(kept.values())
        print(f"  min-count >= {t:>4}: {len(kept):>7} forms, {100.0*mass/total:5.1f}% of mass",
              file=sys.stderr)
    print("\n  most frequent: " + ", ".join(w for w, _ in counts.most_common(25)),
          file=sys.stderr)

    if args.min_count is None:
        print("\nNo --min-count given: reporting only, nothing written.", file=sys.stderr)
        return 0

    kept = {k: v for k, v in counts.items() if v >= args.min_count}
    phonetic = {k: v for k, v in kept.items() if is_phonetic_transliteration(k)}
    kept = {k: v for k, v in kept.items() if k not in phonetic}
    print(f"\n  dropped {len(phonetic)} phonetic-geresh forms "
          f"(e.g. {', '.join(list(phonetic)[:6])})", file=sys.stderr)

    # A bare form that maps to MORE THAN ONE abbreviation is ambiguous and is dropped rather
    # than guessed at: offering the wrong expansion of someone's abbreviation is worse than
    # offering nothing, and this app never auto-replaces anyway.
    by_bare: dict[str, list[tuple[str, int]]] = collections.defaultdict(list)
    for form, count in kept.items():
        by_bare[bare(form)].append((form, count))
    ambiguous = {b: v for b, v in by_bare.items() if len(v) > 1}
    unambiguous = {b: v[0] for b, v in by_bare.items() if len(v) == 1}

    print(f"\n  {len(kept)} forms -> {len(unambiguous)} unambiguous bare keys, "
          f"{len(ambiguous)} ambiguous and dropped", file=sys.stderr)
    if ambiguous:
        sample = list(ambiguous.items())[:5]
        for b, v in sample:
            print(f"    dropped '{b}': {[f for f, _ in v]}", file=sys.stderr)

    lines = [f"{b}\t{form}\t{count}"
             for b, (form, count) in sorted(unambiguous.items(), key=lambda kv: -kv[1][1])]
    blob = ("\n".join(lines) + "\n").encode("utf-8")
    packed = gzip.compress(blob, compresslevel=9, mtime=0)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(packed)

    manifest = {
        "purpose": "Hebrew abbreviations (rashei tevot), so the keyboard stops calling them "
                   "misspellings and can offer the punctuated form",
        "source": {"wiki": "hewiki", "dump_date": DUMP_DATE, "license": "CC BY-SA 4.0",
                   "phase": TRAINING_PHASE, "chunks": args.chunks,
                   "chunk_bytes": args.chunk_bytes, "blocks": blocks},
        "why_a_separate_fetch": "Both lexicon sources are letters-only (checked: zero "
                                "abbreviation-shaped entries), and the cached bigram corpus "
                                "was tokenised with [alef-tav]+ which splits on the gershayim.",
        "table": {
            "distinct_found": len(counts),
            "min_count": args.min_count,
            "kept": len(kept),
            "phonetic_geresh_dropped": len(phonetic),
            "unambiguous_bare_keys": len(unambiguous),
            "ambiguous_dropped": len(ambiguous),
            "format": "bare<TAB>canonical<TAB>count, sorted by count descending",
            "raw_bytes": len(blob),
            "gzip_bytes": len(packed),
            "sha256": hashlib.sha256(blob).hexdigest(),
        },
        "known_limitations": [
            "Wikipedia abbreviations skew encyclopedic (organisations, ranks, place names) "
            "and under-represent the conversational ones people type in messages.",
            "A bare form with more than one expansion is dropped, not disambiguated.",
            "ASCII quote spellings are folded onto the Hebrew punctuation, so the counts mix "
            "two typing conventions.",
            "Forms ending in a phonetically-modified letter (gimel, zayin, tsadi, tav + geresh) "
            "are excluded wholesale, which also removes any genuine abbreviation ending that "
            "way.",
        ],
    }
    with open(os.path.join(ROOT, "lexicon", "ABBREVIATION_MANIFEST.json"), "w",
              encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(f"\nwrote {args.out}: {len(unambiguous)} entries, {len(packed)} bytes gzipped",
          file=sys.stderr)
    print(json.dumps({"entries": len(unambiguous), "gzip_bytes": len(packed)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

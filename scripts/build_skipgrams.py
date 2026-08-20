#!/usr/bin/env python3
"""Build the DISTANCE-2 table: which words appear two positions apart.

### Why this exists
`RealWordErrorDetector` decides between confusable words from the evidence around them, and
that evidence is adjacent bigrams only. Measured on the test slice, in **30.2% of confusable
positions neither candidate has any adjacent evidence at all** — the window is blind, and no
margin or weight can help because there is nothing in it to weigh.

That limit was found by the operator typing on a phone: `עוגת גבינה אם הרבה שוקולד`, where the
deciding word arrives after the window has passed.

### Same format as the bigram table, on purpose
The output is byte-compatible with `he_bigrams.bin`, so `BigramModel` loads it unchanged and
there is no second model class to keep correct. A skip table IS a table of `(first, second,
count)`; only the definition of "second" differs, and that belongs in the builder rather than
in the reader.

### The threshold
`--min-count` defaults to nothing and the script reports the distribution instead of guessing,
exactly as `build_bigrams.py` does. 10 was chosen from the counted distribution because it is
the largest table that fits the remaining asset budget without moving `GATE-SIZE-1`; see
docs/CONFUSION_MEASUREMENTS.md.
"""
from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import io
import json
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_bigrams import (  # noqa: E402
    DEFAULT_CHUNKS, DEFAULT_CHUNK_BYTES, DUMP_DATE, LEXICON, OUT_DIR, ROOT, fetch_corpus,
)

DISTANCE = 2


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunks", type=int, default=DEFAULT_CHUNKS)
    ap.add_argument("--chunk-bytes", type=int, default=DEFAULT_CHUNK_BYTES)
    ap.add_argument("--min-count", type=int, default=None,
                    help="prune below this count. Omit to REPORT the distribution only.")
    ap.add_argument("--out", default=os.path.join(OUT_DIR, "he_skipgrams.bin.gz"))
    args = ap.parse_args()

    with gzip.open(LEXICON, "rb") as fh:
        words = fh.read().decode("utf-8").split("\n")
    if words and words[-1] == "":
        words.pop()
    index = {w: i for i, w in enumerate(words)}
    print(f"lexicon: {len(words)} forms", file=sys.stderr)

    sentences, provenance = fetch_corpus(args.chunks, args.chunk_bytes)
    tokens = sum(len(s) for s in sentences)
    print(f"corpus: {len(sentences)} sentences, {tokens} tokens", file=sys.stderr)
    if tokens < 1_000_000:
        print(f"SHORT_DENOMINATOR: {tokens} tokens", file=sys.stderr)
        return 1

    skips: collections.Counter = collections.Counter()
    oov = 0
    for s in sentences:
        ids = []
        for t in s:
            i = index.get(t)
            if i is None:
                oov += 1
            ids.append(i)
        for a, b in zip(ids, ids[DISTANCE:]):
            # A pair straddling an out-of-lexicon word is still a pair of THESE two words --
            # unlike the adjacent case, where the unknown word sits between them and breaks the
            # adjacency being modelled. Here the gap is the point.
            if a is not None and b is not None:
                skips[(a, b)] += 1

    print(f"distinct distance-{DISTANCE} pairs: {len(skips)}  (oov tokens: {oov})",
          file=sys.stderr)
    print("\ncount distribution -- the threshold comes from THIS:", file=sys.stderr)
    total = sum(skips.values())
    for threshold in (1, 2, 3, 5, 10, 20, 50, 100):
        kept = {k: v for k, v in skips.items() if v >= threshold}
        mass = sum(kept.values())
        groups = len({a for a, _ in kept})
        approx = groups * 6 + len(kept) * 5
        print(f"  min-count >= {threshold:>3}: {len(kept):>9} pairs, "
              f"{100.0 * mass / total:5.1f}% of mass, ~{approx / 1048576:5.2f} MiB raw",
              file=sys.stderr)

    if args.min_count is None:
        print("\nNo --min-count given: reporting only, nothing written.", file=sys.stderr)
        return 0

    kept = {k: v for k, v in skips.items() if v >= args.min_count}
    groups: dict[int, list[tuple[int, int]]] = collections.defaultdict(list)
    for (a, b), c in kept.items():
        groups[a].append((b, c))
    for a in groups:
        groups[a].sort(key=lambda t: -t[1])

    buf = io.BytesIO()
    buf.write(struct.pack("<I", len(groups)))
    for a in sorted(groups):
        conts = groups[a]
        buf.write(struct.pack("<IH", a, min(len(conts), 0xFFFF)))
        for b, c in conts[:0xFFFF]:
            buf.write(struct.pack("<IB", b, min(255, round(math.log2(c + 1) * 8))))
    blob = buf.getvalue()
    packed = gzip.compress(blob, compresslevel=9, mtime=0)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(packed)

    manifest = {
        "purpose": f"distance-{DISTANCE} co-occurrence, for real-word errors whose deciding "
                   f"evidence lies outside the adjacent window",
        "source": {"wiki": "hewiki", "dump_date": DUMP_DATE, "license": "CC BY-SA 4.0"},
        "provenance": provenance,
        "corpus": {"sentences": len(sentences), "tokens": tokens,
                   "out_of_lexicon_tokens": oov},
        "model": {
            "distance": DISTANCE,
            "min_count": args.min_count,
            "distinct_before_pruning": len(skips),
            "kept": len(kept),
            "groups": len(groups),
            "raw_bytes": len(blob),
            "gzip_bytes": len(packed),
            "raw_sha256": hashlib.sha256(blob).hexdigest(),
            "encoding": "identical to he_bigrams.bin, so BigramModel loads it unchanged",
        },
        "known_limitations": [
            "Wikipedia prose; the register is wrong for phone typing.",
            "Distance-2 pairs are counted ACROSS out-of-lexicon words, unlike the adjacent "
            "table, because the gap is what is being modelled.",
            "A pair two apart is weaker evidence than an adjacent one and is not interchangeable "
            "with it; the detector weighs them separately.",
        ],
    }
    with open(os.path.join(ROOT, "lexicon", "SKIPGRAM_MANIFEST.json"), "w",
              encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"\nwrote {args.out}: {len(kept)} pairs, {len(groups)} groups, "
          f"{len(packed)} bytes gzipped", file=sys.stderr)
    print(json.dumps({"kept": len(kept), "groups": len(groups),
                      "gzip_bytes": len(packed)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

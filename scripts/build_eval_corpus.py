#!/usr/bin/env python3
"""Build the held-out evaluation corpus for prediction (M10) and confusion sets (M11).

### The one property that makes this corpus worth anything
It must not overlap the text the bigram model was trained on. A language model evaluated on its
own training data reports how well it memorised, not how well it predicts, and the number looks
excellent either way.

So this samples the multistream dump on a **different phase of the same grid**: training chunks
sit at `(i + 0.5) / n` of the file, evaluation chunks at `(i + 0.0) / n`. The script then
**proves** the two sets of byte ranges are disjoint and refuses to write anything if they are
not — the separation is asserted, not assumed from the arithmetic.

Sentence structure is preserved, unlike `lexicon/heldout/hewiki_sample.txt.gz`, which is a flat
token list. Bigram evaluation and confusion-set scoring both need to know which words were
adjacent, and a flat list has thrown that away.
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_bigrams import (  # noqa: E402
    BOUNDARY_RE,
    DATA_BYTES,
    DATA_URL,
    DUMP_DATE,
    HEBREW_RUN_RE,
    HEBREW_WORD_RE,
    ROOT,
    TEXT_RE,
    chunk_starts,
    http_get,
    load_index_offsets,
    strip_wiki_markup,
)

OUT_DIR = os.path.join(ROOT, "lexicon", "eval")
BIGRAM_MANIFEST = os.path.join(ROOT, "lexicon", "BIGRAM_MANIFEST.json")

TRAINING_PHASE = 0.5
EVAL_PHASE = 0.0


def ranges(starts: list[int], size: int) -> list[tuple[int, int]]:
    return [(s, min(s + size, DATA_BYTES - 1)) for s in starts]


def overlapping(a: list[tuple[int, int]], b: list[tuple[int, int]]) -> list[tuple]:
    hits = []
    for x in a:
        for y in b:
            if x[0] <= y[1] and y[0] <= x[1]:
                hits.append((x, y))
    return hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunks", type=int, default=6)
    ap.add_argument("--chunk-bytes", type=int, default=8 * 1024 * 1024)
    ap.add_argument("--out-dir", default=OUT_DIR)
    args = ap.parse_args()

    offsets = load_index_offsets()

    training = json.load(open(BIGRAM_MANIFEST, encoding="utf-8"))["provenance"]
    training_starts = training["chunk_starts"]
    training_size = training["chunk_bytes"]
    training_ranges = ranges(training_starts, training_size)

    eval_starts = chunk_starts(offsets, args.chunks, phase=EVAL_PHASE)
    eval_ranges = ranges(eval_starts, args.chunk_bytes)

    clashes = overlapping(eval_ranges, training_ranges)
    if clashes:
        print("TRAINING_OVERLAP: evaluation chunks intersect the bigram training data:",
              file=sys.stderr)
        for x, y in clashes:
            print(f"  eval {x} overlaps training {y}", file=sys.stderr)
        print("Refusing to write a corpus that would measure memorisation.", file=sys.stderr)
        return 1
    print(f"disjointness proven: {len(eval_ranges)} eval ranges vs "
          f"{len(training_ranges)} training ranges, 0 intersections", file=sys.stderr)

    sentences: list[list[str]] = []
    fetched = 0
    blocks = 0
    for start, end in eval_ranges:
        print(f"  fetching bytes {start}..{end}", file=sys.stderr)
        try:
            raw = http_get(DATA_URL, (start, end))
        except Exception as exc:  # noqa: BLE001
            print(f"    fetch failed ({exc}), skipped", file=sys.stderr)
            continue
        fetched += len(raw)
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
                for fragment in BOUNDARY_RE.split(body):
                    toks = [t for t in HEBREW_RUN_RE.findall(fragment)
                            if HEBREW_WORD_RE.match(t)]
                    if len(toks) >= 3:
                        sentences.append(toks)
        print(f"    {blocks} blocks, {len(sentences)} sentences", file=sys.stderr)

    if len(sentences) < 10_000:
        print(f"SHORT_DENOMINATOR: only {len(sentences)} sentences", file=sys.stderr)
        return 1

    blob = ("\n".join(" ".join(s) for s in sentences) + "\n").encode("utf-8")
    packed = gzip.compress(blob, compresslevel=9, mtime=0)
    os.makedirs(args.out_dir, exist_ok=True)
    out = os.path.join(args.out_dir, "hewiki_eval_sentences.txt.gz")
    with open(out, "wb") as fh:
        fh.write(packed)

    tokens = sum(len(s) for s in sentences)
    manifest = {
        "purpose": "held-out evaluation for prediction and confusion sets; NOT used to train "
                   "the bigram model",
        "source": {"wiki": "hewiki", "dump_date": DUMP_DATE, "license": "CC BY-SA 4.0"},
        "disjointness": {
            "training_phase": TRAINING_PHASE,
            "eval_phase": EVAL_PHASE,
            "training_ranges": training_ranges,
            "eval_ranges": eval_ranges,
            "intersections": 0,
            "proven_by": "scripts/build_eval_corpus.py refuses to write when any eval byte "
                         "range intersects a training byte range",
        },
        "corpus": {
            "sentences": len(sentences),
            "tokens": tokens,
            "blocks": blocks,
            "compressed_bytes_fetched": fetched,
            "uncompressed_bytes": len(blob),
            "sha256": hashlib.sha256(blob).hexdigest(),
        },
        "known_limitations": [
            "Wikipedia prose; the register is wrong for phone typing.",
            "Sentence splitting is punctuation-based and will merge or split some sentences.",
        ],
    }
    with open(os.path.join(args.out_dir, "MANIFEST.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"\neval corpus: {len(sentences)} sentences, {tokens} tokens, "
          f"sha256 {manifest['corpus']['sha256'][:16]}...", file=sys.stderr)
    print(json.dumps({"sentences": len(sentences), "tokens": tokens,
                      "sha256": manifest["corpus"]["sha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

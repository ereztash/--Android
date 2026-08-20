#!/usr/bin/env python3
"""Cut the committed evaluation slice out of the full held-out corpus.

### Why a slice is committed and the corpus is not
The full held-out corpus is 799,319 sentences and 28 MB compressed. The measurements use a
deterministic systematic sample of it — 6,000 sentences — and committing 28 MB to make 6,000
sentences reproducible in CI is a poor trade in a repository that is otherwise 34 MB.

So the **selection rule lives here**, in one place, and the slice it produces is what the tests
read. The full corpus stays reproducible from `scripts/build_eval_corpus.py`, whose manifest
records the byte ranges and the disjointness proof; this script records the parent's hash
beside the slice's, so a slice can always be traced to the corpus it came from.

### What this costs
A change to the sampling rule cannot be evaluated without re-fetching the full corpus. That is
the price of not committing 28 MB, and it is stated rather than discovered later. The rule is
not a tuning knob — it was fixed before the first measurement and has not moved.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVAL_DIR = os.path.join(ROOT, "lexicon", "eval")
FULL = os.path.join(EVAL_DIR, "hewiki_eval_sentences.txt.gz")
SLICE = os.path.join(EVAL_DIR, "hewiki_eval_sample.txt.gz")
MANIFEST = os.path.join(EVAL_DIR, "MANIFEST.json")

# THE SELECTION RULE. Fixed before the first measurement; not a tuning knob.
MIN_TOKENS = 4
MAX_TOKENS = 40
STRIDE = 37
COUNT = 6000


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", default=FULL)
    ap.add_argument("--out", default=SLICE)
    args = ap.parse_args()

    if not os.path.isfile(args.full):
        print(f"full corpus missing: {args.full}\n"
              f"run scripts/build_eval_corpus.py first", file=sys.stderr)
        return 1

    raw = gzip.open(args.full, "rb").read()
    parent_hash = hashlib.sha256(raw).hexdigest()
    lines = [ln for ln in raw.decode("utf-8").split("\n") if ln.strip()]

    eligible = [ln for ln in lines if MIN_TOKENS <= len(ln.split(" ")) <= MAX_TOKENS]
    chosen = eligible[::STRIDE][:COUNT]
    if len(chosen) < COUNT:
        print(f"SHORT_SLICE: only {len(chosen)} sentences met the rule, wanted {COUNT}",
              file=sys.stderr)
        return 1

    blob = ("\n".join(chosen) + "\n").encode("utf-8")
    with open(args.out, "wb") as fh:
        fh.write(gzip.compress(blob, compresslevel=9, mtime=0))

    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    manifest["sample"] = {
        "file": os.path.relpath(args.out, ROOT),
        "why": "The full corpus is 28 MB and is not committed; this deterministic slice is "
               "what the tests read. Re-cut it with scripts/slice_eval_corpus.py after "
               "rebuilding the corpus.",
        "rule": f"sentences of {MIN_TOKENS}..{MAX_TOKENS} tokens, every {STRIDE}th, "
                f"first {COUNT}",
        "parent_sha256": parent_hash,
        "sentences": len(chosen),
        "tokens": sum(len(s.split(" ")) for s in chosen),
        "eligible_in_parent": len(eligible),
        "sentences_in_parent": len(lines),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "uncompressed_bytes": len(blob),
    }
    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(json.dumps(manifest["sample"], indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

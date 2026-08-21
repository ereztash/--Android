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

# Named slices, each an offset into the same stride, so no sentence appears in two of them.
# Disjointness is PROVEN below from the actual index sets, not argued from the arithmetic.
#
# Why three rather than one: a threshold tuned on the same sentences it is then reported
# against is not a measurement, it is a fit. `bigramWeight` and the ordering policy were
# chosen on `sample`; the confusion margin is swept on `confusion_dev` and reported on
# `confusion_test`, which shares no sentence with either.
SLICES = {
    "sample": {
        "offset": 0,
        "file": "hewiki_eval_sample.txt.gz",
        "used_by": "M10 prediction: the weight sweep, the ordering sweep, and the locked "
                   "accuracy floors.",
    },
    "confusion_dev": {
        "offset": 1,
        "file": "hewiki_confusion_dev.txt.gz",
        "used_by": "M11 real-word errors: the margin sweep. Thresholds are chosen here and "
                   "nowhere else.",
    },
    "confusion_test": {
        "offset": 2,
        "file": "hewiki_confusion_test.txt.gz",
        "used_by": "M11 real-word errors: the reported recall and false-alarm numbers, "
                   "measured once with the thresholds already fixed.",
    },
}

# Adaptive learning needs a different SHAPE of slice, not just a different offset.
#
# A strided slice takes every 37th sentence, so consecutive entries come from unrelated parts of
# the dump. That is exactly right for measuring a static model, and useless for measuring one
# that adapts: a simulated user has to have topical consistency between what it "typed" earlier
# and what it types next, or there is nothing for the adaptive layer to pick up and the
# measurement answers a question nobody asked.
#
# So these slices are CONTIGUOUS BLOCKS, drawn from the pool of eligible sentences that no
# strided slice uses. Blocks are consecutive in that residual pool, which is consecutive in the
# corpus minus roughly one sentence in 37 -- topical locality is preserved and disjointness stays
# exact and provable rather than approximate.
#
# One pseudo-user is one block. A Wikipedia article is not a person; see
# docs/LEARNING_MEASUREMENTS.md, where that is the protocol's central limitation and is not
# argued away.
BLOCK_SLICES = {
    "learning_dev": {
        "order": 0,
        "file": "hewiki_learning_dev.txt.gz",
        "used_by": "Adaptive learning: the interpolation sweep and the cold-start curve. "
                   "Thresholds are chosen here and nowhere else.",
    },
    "learning_test": {
        "order": 1,
        "file": "hewiki_learning_test.txt.gz",
        "used_by": "Adaptive learning: the reported top-1/top-3 and offer rates, measured "
                   "once with the interpolation already fixed.",
    },
}

PSEUDO_USERS = 120
SENTENCES_PER_USER = 80


def cut_conversational(out_dir: str) -> dict | None:
    """Cut the conversational evaluation slice from held-out subtitle text.

    ### Why this is separate from everything above
    The slices above are all cut from Hebrew Wikipedia, and until now that was the ONLY register
    this project could measure in — which is how a keyboard came to be tuned entirely on
    encyclopedia prose.

    Disjointness here is not proven by index arithmetic but **by construction**:
    `build_subtitle_corpus.py` writes every 20th surviving sentence to the held-out file and
    every other one to the training file, so a sentence is in exactly one of them.
    """
    held = os.path.join(ROOT, "lexicon", "cache", "subtitle-corpus-heldout.txt.gz")
    if not os.path.isfile(held):
        print(f"  conversational slice SKIPPED: {held} absent "
              f"(run scripts/build_subtitle_corpus.py)", file=sys.stderr)
        return None
    eligible = []
    with gzip.open(held, "rt", encoding="utf-8") as fh:
        for line in fh:
            toks = line.split()
            if MIN_TOKENS <= len(toks) <= MAX_TOKENS:
                eligible.append(toks)
    idx = list(range(0, len(eligible), STRIDE))[:COUNT]
    if len(idx) < COUNT:
        print(f"  SHORT_SLICE conversational: {len(idx)} of {COUNT}", file=sys.stderr)
        return None
    chosen = [eligible[i] for i in idx]
    blob = ("\n".join(" ".join(s) for s in chosen) + "\n").encode("utf-8")
    out = os.path.join(out_dir, "he_conversational_test.txt.gz")
    with open(out, "wb") as fh:
        fh.write(gzip.compress(blob, compresslevel=9, mtime=0))
    return {
        "file": os.path.relpath(out, ROOT),
        "shape": "strided over held-out subtitle sentences",
        "register": "transcribed dialogue -- NOT written phone messages, which no corpus here "
                    "contains. A better proxy than an encyclopedia, and nothing more.",
        "used_by": "R1: every claim about conversational-register accuracy.",
        "disjoint_from_training_by": "construction -- build_subtitle_corpus.py writes each "
                                     "sentence to exactly one of train / held-out",
        "sentences": len(chosen),
        "tokens": sum(len(s) for s in chosen),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "uncompressed_bytes": len(blob),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", default=FULL)
    args = ap.parse_args()

    if not os.path.isfile(args.full):
        print(f"full corpus missing: {args.full}\n"
              f"run scripts/build_eval_corpus.py first", file=sys.stderr)
        return 1

    raw = gzip.open(args.full, "rb").read()
    parent_hash = hashlib.sha256(raw).hexdigest()
    lines = [ln for ln in raw.decode("utf-8").split("\n") if ln.strip()]

    eligible = [ln for ln in lines if MIN_TOKENS <= len(ln.split(" ")) <= MAX_TOKENS]

    picked: dict[str, list[int]] = {}
    for name, spec in SLICES.items():
        idx = list(range(spec["offset"], len(eligible), STRIDE))[:COUNT]
        if len(idx) < COUNT:
            print(f"SHORT_SLICE {name}: only {len(idx)} sentences, wanted {COUNT}",
                  file=sys.stderr)
            return 1
        picked[name] = idx

    # Block slices come out of what the strided slices left behind, so the pairwise check below
    # is a real proof and not a formality that the arithmetic already guaranteed.
    used = set()
    for idx in picked.values():
        used.update(idx)
    residual = [i for i in range(len(eligible)) if i not in used]
    per_slice = PSEUDO_USERS * SENTENCES_PER_USER
    need = per_slice * len(BLOCK_SLICES)
    if len(residual) < need:
        print(f"SHORT_RESIDUAL: {len(residual)} unused sentences, need {need}", file=sys.stderr)
        return 1
    # Blocks are spread evenly over the residual pool rather than taken from its head, so the
    # two learning slices are not both drawn from the same region of the dump.
    stride_between_blocks = len(residual) // (PSEUDO_USERS * len(BLOCK_SLICES))
    if stride_between_blocks < SENTENCES_PER_USER:
        print(f"BLOCKS_WOULD_OVERLAP: stride {stride_between_blocks} < block size "
              f"{SENTENCES_PER_USER}", file=sys.stderr)
        return 1
    for name, spec in BLOCK_SLICES.items():
        idx: list[int] = []
        for u in range(PSEUDO_USERS):
            # Interleaved: block b of dev, block b of test, block b+1 of dev, ... so neither
            # slice is systematically earlier in the corpus than the other.
            start = (u * len(BLOCK_SLICES) + spec["order"]) * stride_between_blocks
            idx.extend(residual[start:start + SENTENCES_PER_USER])
        if len(idx) < per_slice:
            print(f"SHORT_SLICE {name}: {len(idx)} of {per_slice}", file=sys.stderr)
            return 1
        picked[name] = idx

    # PROVE the slices share no sentence, from the index sets themselves. The stride makes it
    # true; the check is what makes it known.
    names = list(picked)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            overlap = set(picked[a]) & set(picked[b])
            if overlap:
                print(f"SLICE_OVERLAP: {a} and {b} share {len(overlap)} sentences; refusing "
                      f"to write slices that would let a threshold be tuned on its own test "
                      f"set", file=sys.stderr)
                return 1
    print(f"disjointness proven: {len(names)} slices, "
          f"{sum(len(v) for v in picked.values())} sentences, 0 shared", file=sys.stderr)

    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    manifest["slices"] = {
        "why": "The full corpus is 28 MB and is not committed; these deterministic slices are "
               "what the tests read. Re-cut them with scripts/slice_eval_corpus.py after "
               "rebuilding the corpus.",
        "rule": f"strided slices: sentences of {MIN_TOKENS}..{MAX_TOKENS} tokens, every "
                f"{STRIDE}th from a per-slice offset, first {COUNT}. Block slices: "
                f"{PSEUDO_USERS} contiguous blocks of {SENTENCES_PER_USER} sentences each, "
                f"drawn from the sentences the strided slices left unused.",
        "parent_sha256": parent_hash,
        "eligible_in_parent": len(eligible),
        "sentences_in_parent": len(lines),
        "pairwise_overlaps": 0,
        "proven_by": "scripts/slice_eval_corpus.py compares the index sets and refuses to "
                     "write when any two slices intersect",
    }
    for name, spec in {**SLICES, **BLOCK_SLICES}.items():
        chosen = [eligible[i] for i in picked[name]]
        blob = ("\n".join(chosen) + "\n").encode("utf-8")
        out = os.path.join(EVAL_DIR, spec["file"])
        with open(out, "wb") as fh:
            fh.write(gzip.compress(blob, compresslevel=9, mtime=0))
        manifest["slices"][name] = {
            "file": os.path.relpath(out, ROOT),
            "shape": "contiguous blocks" if name in BLOCK_SLICES else "strided",
            "offset": spec.get("offset"),
            "pseudo_users": PSEUDO_USERS if name in BLOCK_SLICES else None,
            "sentences_per_pseudo_user": SENTENCES_PER_USER if name in BLOCK_SLICES else None,
            "used_by": spec["used_by"],
            "sentences": len(chosen),
            "tokens": sum(len(s.split(" ")) for s in chosen),
            "sha256": hashlib.sha256(blob).hexdigest(),
            "uncompressed_bytes": len(blob),
        }

    conversational = cut_conversational(EVAL_DIR)
    if conversational:
        manifest["slices"]["conversational_test"] = conversational
        print(f"  conversational_test: {conversational['sentences']} sentences, "
              f"sha256 {conversational['sha256'][:16]}...", file=sys.stderr)

    # The M10 key is kept so nothing that already reads it breaks; it points at the same file.
    manifest["sample"] = manifest["slices"]["sample"]

    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(json.dumps(manifest["slices"], indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

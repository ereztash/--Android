#!/usr/bin/env python3
"""A1 — what alphabet does Hebrew actually get typed in, and what do our corpora contain?

### Why this exists
`docs/FRICTION_INVENTORY.md` reported script-mixing and geresh/gershayim at **0.00%** on both
evaluation corpora and said plainly that this was a hole in the evidence rather than a finding:
`build_subtitle_corpus.py` and `build_eval_corpus.py` keep only `[א-ת]+` runs. `docs/BIDI.md`
then measured, on 12,000 lines of those corpora, **exactly zero** direction divergence — a
corpus that cannot diverge could never have shown the one friction the market pass verified.

Both of those establish that the corpora are blind. Neither establishes **how much is behind
the blindness**, and that number decides whether removing the filter is a repair or a rounding
error.

This measures it, on two registers that differ in exactly one way that matters: one was
**transcribed by a professional**, the other was **typed by a person**.

### The two sources
- **Transcribed** — OPUS OpenSubtitles v2018 Hebrew monolingual, the source
  `build_subtitle_corpus.py` already uses. Streamed and never written to disk. A **prefix
  sample, not random**: it reads from the head of the file and stops.
- **Typed** — Amram et al. 2018, user comments on Ynet's Facebook page, MIT licensed
  (`github.com/omilab/Neural-Sentiment-Analyzer-for-Modern-Hebrew`, `data/token_*.tsv`).
  Facebook comments are **not phone messaging** either — that is stated wherever these numbers
  appear — but they are typed by humans rather than transcribed from speech, which is the one
  axis under test. The text is tokenized (punctuation spaced out); that does not move a
  character-presence rate.

### What is NOT claimed
Neither source is phone typing. `M10-REGISTER` stays NOT MEASURED and this file does not
change that. What is claimed is narrower and sufficient: **transcription and typing do not
share an alphabet**, so a corpus built from transcription cannot answer a question about
characters typists produce.
"""
from __future__ import annotations

import argparse
import gzip
import io
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SUBTITLE_URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz"

HEB_RUN = re.compile(r"[א-ת]+")
CLASSES = [
    ("a Latin letter", re.compile(r"[A-Za-z]")),
    ("a digit", re.compile(r"[0-9]")),
    ("Latin OR digit", re.compile(r"[A-Za-z0-9]")),
    ("geresh/gershayim", re.compile(r"[׳״]")),
    ("ASCII quote/apostrophe", re.compile(r"[\"']")),
    ("a bracket", re.compile(r"[()\[\]{}]")),
    ("an emoji", re.compile("[\U0001F000-\U0001FAFF☀-➿]")),
]

# The first two clauses of build_subtitle_corpus.clean(), which decide what reaches any model.
MAX_TOKEN_CHARS = 12
MIN_TOKENS = 4


def repo_keeps(line: str) -> bool:
    toks = HEB_RUN.findall(line)
    return len(toks) >= MIN_TOKENS and max((len(t) for t in toks), default=0) <= MAX_TOKEN_CHARS


def tally(lines):
    """-> (n, n_kept, {class: (rate_all, rate_kept)})"""
    n = n_kept = 0
    hits = {name: [0, 0] for name, _ in CLASSES}
    for line in lines:
        if not line:
            continue
        n += 1
        kept = repo_keeps(line)
        if kept:
            n_kept += 1
        for name, rx in CLASSES:
            if rx.search(line):
                hits[name][0] += 1
                if kept:
                    hits[name][1] += 1
    return n, n_kept, {k: (100.0 * v[0] / max(n, 1), 100.0 * v[1] / max(n_kept, 1))
                       for k, v in hits.items()}


def stream_subtitles(cap: int):
    req = urllib.request.Request(SUBTITLE_URL, headers={"User-Agent": "hebrew-ime-corpus/0.1"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        with gzip.GzipFile(fileobj=resp) as gz:
            for i, raw in enumerate(io.TextIOWrapper(gz, encoding="utf-8", errors="replace")):
                if i >= cap:
                    return
                yield raw.strip()


def read_typed(path: str):
    for name in ("token_train.tsv", "token_test.tsv"):
        f = os.path.join(path, name)
        if not os.path.isfile(f):
            print(f"NOT-MEASURED: {f} absent", file=sys.stderr)
            return
        with open(f, encoding="utf-8") as fh:
            for raw in fh:
                yield raw.split("\t")[0].strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--typed-dir", default=None,
                    help="directory holding token_train.tsv and token_test.tsv")
    ap.add_argument("--subtitle-lines", type=int, default=3_000_000,
                    help="prefix sample size; 0 skips the network entirely")
    args = ap.parse_args()

    rows = []
    if args.subtitle_lines:
        print(f"streaming up to {args.subtitle_lines:,} lines of {SUBTITLE_URL} ...", flush=True)
        rows.append(("TRANSCRIBED  OpenSubtitles", tally(stream_subtitles(args.subtitle_lines))))
    else:
        print("subtitle stream skipped (--subtitle-lines 0): NOT-MEASURED this run")

    if args.typed_dir:
        rows.append(("TYPED  Ynet comments (MIT)", tally(read_typed(args.typed_dir))))
    else:
        print("typed corpus not given (--typed-dir): NOT-MEASURED this run", file=sys.stderr)

    if len(rows) < 2:
        print("\nOne register only. The comparison is the measurement, so this is NOT-MEASURED.")
        return 2

    print()
    print("=" * 96)
    print("THE ALPHABET, BY REGISTER  (share of lines containing at least one such character)")
    print("=" * 96)
    print("Subtitle row is a PREFIX SAMPLE, NOT RANDOM. Neither register is phone typing.")
    print()
    hdr = "%-26s" % "class"
    for name, (n, nk, _) in rows:
        hdr += "%24s" % name.split("  ")[0]
    print(hdr + "%12s" % "ratio")
    sub = "%-26s" % ""
    for name, (n, nk, _) in rows:
        sub += "%24s" % f"n={n:,} kept={nk:,}"
    print(sub)
    print("-" * 96)
    for cls, _ in CLASSES:
        line = "%-26s" % cls
        keeps = []
        for _name, (_n, _nk, r) in rows:
            line += "%23.2f%%" % r[cls][1]
            keeps.append(r[cls][1])
        ratio = (keeps[1] / keeps[0]) if keeps[0] > 0 else float("inf")
        line += "%11s" % (f"x{ratio:,.0f}" if ratio == ratio and ratio != float("inf") else "-")
        print(line)
    print("-" * 96)
    print("Rates are over the lines the repo's own filter keeps (>=4 Hebrew tokens, none >12 chars).")
    print("=" * 96)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

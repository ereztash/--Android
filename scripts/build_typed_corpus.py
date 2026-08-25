#!/usr/bin/env python3
"""W1 — build the first evaluation slice in this repository that a person typed.

The two arms, the pairing rule, the disjointness plan and what is `NOT RUN` were committed
before this file existed. See `docs/TYPED_REGISTER.md`.

### Source
Amram et al. 2018, user comments on Ynet's Facebook page. MIT. Registered with hashes in
`docs/CORPUS_REGISTER.md`. Nothing in this repository is trained on it.

### The arms are two renderings of ONE selection
Selection runs once, on the Hebrew tokens, using `build_subtitle_corpus.py`'s own two clauses.
Both files then contain **the same comments in the same order** — `filtered` with `[א-ת]+`
extraction applied, `raw` with nothing removed. Selecting the arms separately would confound
alphabet with selection and destroy the only number this experiment exists to produce.

### Disjointness
Every selected comment is reduced to its Hebrew-token sequence and looked up against the same
reduction of every line of the OpenSubtitles source, streamed and never written to disk. That
normalisation is the point: the typed text is tokenized by its authors, so an exact string
compare would never collide and would prove nothing.

**The Wikipedia half is NOT RUN.** The hewiki source is not present in this container and the
training corpora were not retained. That half rests on provenance — Facebook comments share no
source with an encyclopedia — and provenance is an argument, not a proof.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVAL = os.path.join(ROOT, "lexicon", "eval")

SUBTITLE_URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz"

HEB_RUN = re.compile(r"[א-ת]+")
WS = re.compile(r"\s+")

# build_subtitle_corpus.py's own two clauses, so selection means the same thing here.
MAX_TOKEN_CHARS = 12
MIN_TOKENS = 4

SOURCE = {
    "name": "Amram et al. 2018, user comments on Ynet's Facebook page",
    "repo": "github.com/omilab/Neural-Sentiment-Analyzer-for-Modern-Hebrew",
    "files": ["data/token_train.tsv", "data/token_test.tsv"],
    "license": "MIT",
}


def read_comments(path: str) -> list[str]:
    out = []
    for name in ("token_train.tsv", "token_test.tsv"):
        f = os.path.join(path, name)
        if not os.path.isfile(f):
            print(f"NOT-MEASURED: {f} absent", file=sys.stderr)
            return []
        with open(f, encoding="utf-8") as fh:
            for raw in fh:
                text = WS.sub(" ", raw.split("\t")[0]).strip()
                if text:
                    out.append(text)
    return out


def key(line: str) -> str:
    """The Hebrew-token sequence, which is what two registers can be compared on."""
    return " ".join(HEB_RUN.findall(line))


def selected(comments: list[str]) -> list[str]:
    out = []
    for c in comments:
        toks = HEB_RUN.findall(c)
        if len(toks) < MIN_TOKENS:
            continue
        if max((len(t) for t in toks), default=0) > MAX_TOKEN_CHARS:
            continue
        out.append(c)
    return out


def collisions(keys: set[str], cap: int) -> tuple[set[str], int]:
    """Stream OpenSubtitles and return which of `keys` appear in it, and lines read."""
    hit: set[str] = set()
    req = urllib.request.Request(SUBTITLE_URL, headers={"User-Agent": "hebrew-ime-corpus/0.1"})
    n = 0
    with urllib.request.urlopen(req, timeout=600) as resp:
        with gzip.GzipFile(fileobj=resp) as gz:
            for raw in io.TextIOWrapper(gz, encoding="utf-8", errors="replace"):
                n += 1
                k = key(raw)
                if k and k in keys:
                    hit.add(k)
                if n % 5_000_000 == 0:
                    print(f"  {n:,} subtitle lines, {len(hit)} collisions so far", flush=True)
                if cap and n >= cap:
                    break
    return hit, n


def write(path: str, lines: list[str]) -> tuple[int, str]:
    body = ("\n".join(lines) + "\n").encode("utf-8")
    with open(path, "wb") as fh:
        fh.write(gzip.compress(body, mtime=0))
    return len(body), hashlib.sha256(open(path, "rb").read()).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--typed-dir", required=True)
    ap.add_argument("--subtitle-lines", type=int, default=0,
                    help="0 = stream the WHOLE source (the real check); N = cap and say so")
    ap.add_argument("--skip-disjointness", action="store_true",
                    help="write with the check recorded as NOT RUN. Never the default.")
    args = ap.parse_args()

    comments = read_comments(args.typed_dir)
    if not comments:
        return 2
    keep = selected(comments)
    print(f"{len(comments):,} comments; {len(keep):,} pass the selection rule "
          f"(>={MIN_TOKENS} Hebrew tokens, none >{MAX_TOKEN_CHARS} chars)")

    dis: dict = {"opensubtitles": None, "wikipedia": "NOT RUN - source absent in this container"}
    if args.skip_disjointness:
        dis["opensubtitles"] = "NOT RUN - --skip-disjointness"
        print("!!! disjointness NOT RUN. The slice is written with that recorded.", file=sys.stderr)
    else:
        keys = {key(c) for c in keep}
        print(f"streaming {SUBTITLE_URL} to check {len(keys):,} distinct Hebrew-token "
              f"sequences ...", flush=True)
        hit, n = collisions(keys, args.subtitle_lines)
        before = len(keep)
        keep = [c for c in keep if key(c) not in hit]
        dis["opensubtitles"] = {
            "subtitle_lines_read": n,
            "whole_source": args.subtitle_lines == 0,
            "distinct_keys_checked": len(keys),
            "colliding_keys": len(hit),
            "comments_dropped": before - len(keep),
        }
        print(f"  {n:,} lines read; {len(hit)} colliding key(s); "
              f"{before - len(keep)} comment(s) dropped -> {len(keep):,} remain")

    raw_path = os.path.join(EVAL, "he_typed_raw.txt.gz")
    filt_path = os.path.join(EVAL, "he_typed_filtered.txt.gz")
    raw_lines = keep
    filt_lines = [key(c) for c in keep]
    assert len(raw_lines) == len(filt_lines), "the arms must be paired"

    n_raw, h_raw = write(raw_path, raw_lines)
    n_filt, h_filt = write(filt_path, filt_lines)
    print(f"wrote {raw_path}  {n_raw:,} B uncompressed  sha256 {h_raw}")
    print(f"wrote {filt_path}  {n_filt:,} B uncompressed  sha256 {h_filt}")

    man_path = os.path.join(EVAL, "MANIFEST.json")
    man = json.load(open(man_path, encoding="utf-8"))
    man["typed_slice_w1"] = {
        "purpose": "W1 - the first evaluation slice a person typed. NOT used to train anything.",
        "source": SOURCE,
        "selection": {"min_hebrew_tokens": MIN_TOKENS, "max_token_chars": MAX_TOKEN_CHARS,
                      "comments_read": len(comments), "comments_kept": len(keep)},
        "arms": {
            "paired": "one selection, two renderings; the files are line-for-line the same comments",
            "he_typed_raw.txt.gz": {"sha256": h_raw, "uncompressed_bytes": n_raw,
                                    "filter": "none"},
            "he_typed_filtered.txt.gz": {"sha256": h_filt, "uncompressed_bytes": n_filt,
                                         "filter": "[א-ת]+ extraction, as build_subtitle_corpus.py"},
        },
        "disjointness": dis,
        "not_measured": "Facebook comments are not phone messaging. M10-REGISTER is narrowed, not closed. "
                        "Token boundaries are the source's, not this repository's, and every "
                        "tokenization-sensitive number inherits them.",
    }
    json.dump(man, open(man_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"updated {man_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

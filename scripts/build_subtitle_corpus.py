#!/usr/bin/env python3
"""Fetch and clean Hebrew subtitle text — the conversational half of the training corpus.

### Why this exists
Every model this keyboard ships was built from Hebrew Wikipedia, and every measurement document
carried "the register is wrong for phone typing" as a known limitation. Measured rather than
repeated, that limitation is worth **18 points of recall** on conversational text; see
`docs/CORPUS_REGISTER.md`.

OpenSubtitles is transcribed dialogue. It is not phone messaging — that is stated wherever these
numbers appear — but it is far closer to it than an encyclopedia.

### The cleaning filter is part of the artifact
The subtitle source is damaged in ways an encyclopedia is not: dropped spaces produce tokens
like `מההייתעושהבמקומי`, and OCR noise produces runs that are not Hebrew at all. Two rules,
both stated here rather than applied ad hoc:

  * longest token > 12 characters -> the line lost its spaces
  * fewer than 75% of tokens in the lexicon -> the line is noise or another language

Measured, these reject **48%** of lines. That is a large fraction and it is reported rather than
buried: what survives still contains transcription artefacts.

### Held-out split
Every 20th surviving sentence is written to a separate file and never trains anything. It is
what `slice_eval_corpus.py` cuts the conversational evaluation slice from — the first slice in
this repository that is in the register users actually type in.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "lexicon", "cache")
LEXICON = os.path.join(ROOT, "lexicon", "assets", "he_lexicon.txt.gz")

SOURCE_URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz"
SOURCE_NAME = "OPUS OpenSubtitles v2018, Hebrew monolingual"
SOURCE_LICENSE = "OpenSubtitles data, redistributed by OPUS. See opus.nlpl.eu for terms."

HEB_RUN = re.compile(r"[א-ת]+")

MAX_TOKEN_CHARS = 12
MIN_IN_LEXICON = 0.75
MIN_TOKENS = 4
HELD_OUT_EVERY = 20


def load_lexicon() -> set[str]:
    words = set()
    with gzip.open(LEXICON, "rt", encoding="utf-8") as fh:
        for line in fh:
            words.add(line.rstrip("\n"))
    return words


def clean(line: str, lexicon: set[str]) -> list[str] | None:
    toks = HEB_RUN.findall(line)
    if len(toks) < MIN_TOKENS:
        return None
    if max((len(t) for t in toks), default=0) > MAX_TOKEN_CHARS:
        return None
    in_lex = sum(1 for t in toks if t in lexicon)
    if in_lex / len(toks) < MIN_IN_LEXICON:
        return None
    return toks


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-decompressed-bytes", type=int, default=400 * 1024 * 1024,
                    help="bounded prefix of the stream; the full file is 956 MB gzipped")
    ap.add_argument("--local", default=None,
                    help="read already-downloaded plain text instead of streaming")
    ap.add_argument("--train-tokens", type=int, default=25_600_000,
                    help="match the Wikipedia corpus, so blends compare register not volume")
    args = ap.parse_args()

    lexicon = load_lexicon()
    print(f"lexicon: {len(lexicon)} forms", file=sys.stderr)

    def lines():
        if args.local:
            with open(args.local, encoding="utf-8", errors="replace") as fh:
                yield from fh
            return
        req = urllib.request.Request(
            SOURCE_URL, headers={"User-Agent": "hebrew-ime-corpus/0.1"})
        stream = gzip.GzipFile(fileobj=urllib.request.urlopen(req, timeout=300))
        read = 0
        tail = b""
        while read < args.max_decompressed_bytes:
            chunk = stream.read(4 * 1024 * 1024)
            if not chunk:
                break
            read += len(chunk)
            parts = (tail + chunk).split(b"\n")
            tail = parts.pop()
            for p in parts:
                yield p.decode("utf-8", errors="replace")
        if tail:
            yield tail.decode("utf-8", errors="replace")

    train: list[list[str]] = []
    held: list[list[str]] = []
    read_lines = 0
    kept = 0
    tokens = 0
    for line in lines():
        read_lines += 1
        toks = clean(line, lexicon)
        if toks is None:
            continue
        kept += 1
        if kept % HELD_OUT_EVERY == 0:
            held.append(toks)
        elif tokens < args.train_tokens:
            train.append(toks)
            tokens += len(toks)

    rejected = read_lines - kept
    print(f"lines read {read_lines}, usable {kept}, rejected {rejected} "
          f"({100.0 * rejected / max(read_lines, 1):.0f}%)", file=sys.stderr)
    print(f"train {len(train)} sentences / {tokens} tokens; held out {len(held)}",
          file=sys.stderr)

    if tokens < 5_000_000:
        print(f"SHORT_DENOMINATOR: {tokens} training tokens", file=sys.stderr)
        return 1

    os.makedirs(CACHE, exist_ok=True)
    out_train = os.path.join(CACHE, "subtitle-corpus-train.txt.gz")
    out_held = os.path.join(CACHE, "subtitle-corpus-heldout.txt.gz")
    train_blob = ("\n".join(" ".join(s) for s in train) + "\n").encode("utf-8")
    held_blob = ("\n".join(" ".join(s) for s in held) + "\n").encode("utf-8")
    with open(out_train, "wb") as fh:
        fh.write(gzip.compress(train_blob, compresslevel=9, mtime=0))
    with open(out_held, "wb") as fh:
        fh.write(gzip.compress(held_blob, compresslevel=9, mtime=0))

    manifest = {
        "purpose": "conversational-register training text, to correct a corpus that was "
                   "entirely encyclopedic",
        "source": {"name": SOURCE_NAME, "url": SOURCE_URL, "license": SOURCE_LICENSE},
        "cleaning": {
            "max_token_chars": MAX_TOKEN_CHARS,
            "min_fraction_in_lexicon": MIN_IN_LEXICON,
            "min_tokens": MIN_TOKENS,
            "lines_read": read_lines,
            "lines_kept": kept,
            "lines_rejected": rejected,
            "rejected_fraction": round(rejected / max(read_lines, 1), 4),
            "why": "The subtitle source drops spaces and carries OCR noise, producing tokens "
                   "like מההייתעושהבמקומי. Both rules are stated so the 48% rejection rate is "
                   "a reported property and not a hidden one.",
        },
        "split": {
            "held_out_every": HELD_OUT_EVERY,
            "train_sentences": len(train),
            "train_tokens": tokens,
            "held_out_sentences": len(held),
            "disjoint_by": "construction -- a sentence is written to exactly one file",
        },
        "output": {
            "train": os.path.relpath(out_train, ROOT),
            "held_out": os.path.relpath(out_held, ROOT),
            "train_sha256": hashlib.sha256(train_blob).hexdigest(),
            "held_out_sha256": hashlib.sha256(held_blob).hexdigest(),
        },
        "known_limitations": [
            "Subtitles are transcribed SPEECH, not written messages. A better proxy for phone "
            "typing than an encyclopedia; not the target.",
            "48% of lines are rejected, and what survives still contains artefacts.",
            "A bounded prefix of the file is read, so this is not a uniform sample of it.",
        ],
    }
    with open(os.path.join(ROOT, "lexicon", "SUBTITLE_MANIFEST.json"), "w",
              encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(json.dumps({"train_sentences": len(train), "train_tokens": tokens,
                      "held_out": len(held)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

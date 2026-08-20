#!/usr/bin/env python3
"""Build a Hebrew bigram model from Wikipedia, for prediction and confusion-set scoring.

### Why this exists at all
`he_full.txt` is a frequency list: word and count, no order. Prediction needs to know what
follows what, and that information is simply absent from every source the lexicon was built
from. It has to come from raw running text.

### Source
Hebrew Wikipedia, CC BY-SA 4.0, already credited in docs/LICENSES.md and already reachable
through the multistream machinery built in M1.

### Sampling, and its stated bias
Ten contiguous chunks spread evenly across the multistream file, rather than one large chunk.
A single contiguous chunk is a contiguous page-id range, which means one era of the
encyclopedia. Spreading the chunks costs ten HTTP requests instead of one and removes most of
that. It is still not a uniform sample of articles -- within each chunk the pages are
adjacent -- and for a bigram model that matters much less than it would for a coverage
measurement, because what a language model needs is sequences of COMMON words and those occur
everywhere. Stated rather than glossed.

### Pruning
Deliberately NOT decided here. The script reports the full count distribution and writes a
model at whatever `--min-count` it is given. The threshold is chosen after looking at the
distribution and at the size it implies, never before.
"""
from __future__ import annotations

import argparse
import bz2
import collections
import gzip
import hashlib
import html
import io
import json
import os
import re
import struct
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "lexicon", "cache")
LEXICON = os.path.join(ROOT, "lexicon", "assets", "he_lexicon.txt.gz")
OUT_DIR = os.path.join(ROOT, "lexicon", "assets")
MANIFEST = os.path.join(ROOT, "lexicon", "BIGRAM_MANIFEST.json")

DUMP_DATE = "20260801"
BASE = f"https://dumps.wikimedia.org/hewiki/{DUMP_DATE}"
DATA_URL = f"{BASE}/hewiki-{DUMP_DATE}-pages-articles-multistream.xml.bz2"
INDEX_URL = f"{BASE}/hewiki-{DUMP_DATE}-pages-articles-multistream-index.txt.bz2"
DATA_BYTES = 1169474250

# Wikimedia's robot policy rejects generic library User-Agents with HTTP 403.
USER_AGENT = ("hebrew-ime-lexicon-build/1.0 "
              "(offline Hebrew IME; https://github.com/ereztash/--Android)")

HEBREW_RUN_RE = re.compile("[א-ת]+")
HEBREW_WORD_RE = re.compile("^[א-ת]+$")
TEXT_RE = re.compile(r"<text\b[^>]*>(.*?)</text>", re.DOTALL)

# Sentence-ish boundaries. Bigrams must not straddle them, or the model learns that the last
# word of one sentence predicts the first word of the next.
BOUNDARY_RE = re.compile(r"[.!?;:\n׃]|--")

DEFAULT_CHUNKS = 10
DEFAULT_CHUNK_BYTES = 12 * 1024 * 1024


def http_get(url: str, byte_range: tuple[int, int] | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if byte_range:
        req.add_header("Range", f"bytes={byte_range[0]}-{byte_range[1]}")
    with urllib.request.urlopen(req, timeout=900) as r:
        return r.read()


def strip_wiki_markup(s: str) -> str:
    s = html.unescape(s)
    s = re.sub(r"<ref[^>]*/>", " ", s)
    s = re.sub(r"<ref[^>]*>.*?</ref>", " ", s, flags=re.DOTALL)
    s = re.sub(r"<!--.*?-->", " ", s, flags=re.DOTALL)
    s = re.sub(r"<(math|code|pre|syntaxhighlight|gallery|timeline)\b.*?</\1>", " ", s,
               flags=re.DOTALL)
    for _ in range(8):
        new = re.sub(r"\{\{[^{}]*\}\}", " ", s)
        new = re.sub(r"\{\|[^{}]*?\|\}", " ", new, flags=re.DOTALL)
        if new == s:
            break
        s = new
    s = re.sub(r"\[\[(?:קובץ|תמונה|File|Image|Category|קטגוריה):[^\]]*\]\]", " ", s)
    s = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[https?://\S+\s+([^\]]*)\]", r"\1", s)
    s = re.sub(r"https?://\S+", " ", s)
    s = re.sub(r"<[^>]+>", " ", s)
    s = re.sub(r"^[*#:;]+", " ", s, flags=re.MULTILINE)
    s = s.replace("'''", " ").replace("''", " ").replace("=", " ")
    return s


def load_index_offsets() -> list[int]:
    path = os.path.join(CACHE, f"hewiki-{DUMP_DATE}-index.txt.bz2")
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        payload = http_get(INDEX_URL)
        tmp = path + ".part"
        with open(tmp, "wb") as fh:
            fh.write(payload)
        os.replace(tmp, path)
    raw = open(path, "rb").read()
    offsets, seen = [], set()
    for line in bz2.decompress(raw).decode("utf-8").splitlines():
        parts = line.split(":", 2)
        if len(parts) != 3:
            continue
        off = int(parts[0])
        if off not in seen:
            seen.add(off)
            offsets.append(off)
    offsets.sort()
    return offsets


def chunk_starts(offsets: list[int], chunks: int, phase: float = 0.5) -> list[int]:
    """Deterministic chunk start offsets, snapped to real multistream block boundaries.

    `phase` shifts the sampling grid. The training corpus uses 0.5 (mid-cell); the held-out
    evaluation corpus uses a different phase so the two never draw from the same blocks.
    """
    starts: list[int] = []
    for i in range(chunks):
        target = int(DATA_BYTES * (i + phase) / chunks)
        snapped = max((o for o in offsets if o <= target), default=offsets[0])
        if snapped not in starts:
            starts.append(snapped)
    return starts


def fetch_corpus(chunks: int, chunk_bytes: int) -> tuple[list[list[str]], dict]:
    """Return sentences (lists of Hebrew tokens) plus provenance."""
    offsets = load_index_offsets()
    cache_path = os.path.join(CACHE, f"bigram-corpus-{DUMP_DATE}-{chunks}x{chunk_bytes}.txt.gz")

    # Chunk starts are a pure function of the index and the chunk count, so they are computed
    # BEFORE consulting the cache. They have to be in the manifest either way: the held-out
    # evaluation corpus proves it is disjoint from training by comparing against this list, and
    # a cached run that omitted it would make that proof impossible.
    starts = chunk_starts(offsets, chunks)

    if os.path.isfile(cache_path) and os.path.getsize(cache_path) > 0:
        print(f"using cached corpus {cache_path}", file=sys.stderr)
        with gzip.open(cache_path, "rt", encoding="utf-8") as fh:
            sentences = [ln.split() for ln in fh if ln.strip()]
        return sentences, {
            "cached": True,
            "path": os.path.relpath(cache_path, ROOT),
            "chunks_requested": chunks,
            "chunk_bytes": chunk_bytes,
            "chunk_starts": starts,
        }

    sentences: list[list[str]] = []
    fetched = 0
    blocks_ok = 0
    for start in starts:
        end = min(start + chunk_bytes, DATA_BYTES - 1)
        print(f"  fetching bytes {start}..{end}", file=sys.stderr)
        try:
            raw = http_get(DATA_URL, (start, end))
        except Exception as exc:  # noqa: BLE001
            print(f"    fetch failed ({exc}), skipped", file=sys.stderr)
            continue
        fetched += len(raw)

        # Walk the block offsets inside this range and decompress each independently.
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
            blocks_ok += 1
            for m in TEXT_RE.finditer(xml):
                body = strip_wiki_markup(m.group(1))
                for fragment in BOUNDARY_RE.split(body):
                    toks = [t for t in HEBREW_RUN_RE.findall(fragment)
                            if HEBREW_WORD_RE.match(t)]
                    if len(toks) >= 2:
                        sentences.append(toks)
        print(f"    {blocks_ok} blocks, {len(sentences)} sentences so far", file=sys.stderr)

    os.makedirs(CACHE, exist_ok=True)
    with gzip.open(cache_path, "wt", encoding="utf-8") as fh:
        for s in sentences:
            fh.write(" ".join(s) + "\n")

    return sentences, {
        "cached": False,
        "chunks_requested": chunks,
        "chunk_bytes": chunk_bytes,
        "chunk_starts": starts,
        "compressed_bytes_fetched": fetched,
        "blocks_decoded": blocks_ok,
        "path": os.path.relpath(cache_path, ROOT),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunks", type=int, default=DEFAULT_CHUNKS)
    ap.add_argument("--chunk-bytes", type=int, default=DEFAULT_CHUNK_BYTES)
    ap.add_argument("--min-count", type=int, default=None,
                    help="prune bigrams below this count. Omit to only REPORT the "
                         "distribution and write nothing.")
    ap.add_argument("--max-bigrams", type=int, default=None)
    ap.add_argument("--out", default=os.path.join(OUT_DIR, "he_bigrams.bin.gz"))
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
        print(f"SHORT_DENOMINATOR: {tokens} tokens is too few for a bigram model",
              file=sys.stderr)
        return 1

    bigrams: collections.Counter = collections.Counter()
    unigrams: collections.Counter = collections.Counter()
    oov = 0
    for s in sentences:
        ids = []
        for t in s:
            i = index.get(t)
            if i is None:
                oov += 1
                ids.append(None)
            else:
                ids.append(i)
                unigrams[i] += 1
        for a, b in zip(ids, ids[1:]):
            # A bigram straddling an out-of-lexicon word is not a bigram of the two words
            # that happen to surround it.
            if a is not None and b is not None:
                bigrams[(a, b)] += 1

    print(f"distinct bigrams: {len(bigrams)}  (out-of-lexicon tokens skipped: {oov})",
          file=sys.stderr)
    print("\ncount distribution -- the threshold is chosen from THIS, not before it:",
          file=sys.stderr)
    total = sum(bigrams.values())
    cumulative = 0
    for threshold in (1, 2, 3, 5, 10, 20, 50, 100):
        kept = {k: v for k, v in bigrams.items() if v >= threshold}
        mass = sum(kept.values())
        # Group-header + 5 bytes per continuation, as in the writer below.
        groups = len({a for a, _ in kept})
        approx = groups * 6 + len(kept) * 5
        print(f"  min-count >= {threshold:>3}: {len(kept):>9} bigrams, "
              f"{100.0 * mass / total:5.1f}% of token mass, ~{approx / 1048576:5.2f} MiB raw",
              file=sys.stderr)
    del cumulative

    if args.min_count is None:
        print("\nNo --min-count given: reporting only, nothing written.", file=sys.stderr)
        return 0

    kept = {k: v for k, v in bigrams.items() if v >= args.min_count}
    if args.max_bigrams and len(kept) > args.max_bigrams:
        kept = dict(sorted(kept.items(), key=lambda kv: -kv[1])[: args.max_bigrams])

    # Group by first word so a lookup is one binary search plus a contiguous scan.
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
            # Counts are log-scaled to a byte, like the unigram table.
            import math
            buf.write(struct.pack("<IB", b, min(255, round(math.log2(c + 1) * 8))))
    blob = buf.getvalue()
    packed = gzip.compress(blob, compresslevel=9, mtime=0)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(packed)

    manifest = {
        "source": {"wiki": "hewiki", "dump_date": DUMP_DATE, "url": DATA_URL,
                   "license": "CC BY-SA 4.0"},
        "provenance": provenance,
        "corpus": {"sentences": len(sentences), "tokens": tokens,
                   "out_of_lexicon_tokens": oov},
        "model": {
            "min_count": args.min_count,
            "max_bigrams": args.max_bigrams,
            "distinct_bigrams_before_pruning": len(bigrams),
            "bigrams_kept": len(kept),
            "groups": len(groups),
            "raw_bytes": len(blob),
            "gzip_bytes": len(packed),
            "raw_sha256": hashlib.sha256(blob).hexdigest(),
            "encoding": "u32 groupCount, then per group: u32 firstWordIndex, u16 n, "
                        "then n x (u32 secondWordIndex, u8 log2Count*8). Groups sorted by "
                        "firstWordIndex; continuations sorted by count descending.",
        },
        "known_limitations": [
            "Wikipedia prose. The register is wrong for phone typing.",
            "Chunks are contiguous within themselves, so the sample is not uniform over "
            "articles.",
            "Bigrams straddling an out-of-lexicon word are dropped rather than joined.",
        ],
    }
    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"\nwrote {len(kept)} bigrams in {len(groups)} groups, "
          f"{len(packed)} gzipped bytes -> {os.path.relpath(args.out, ROOT)}", file=sys.stderr)
    print(json.dumps({"bigrams": len(kept), "gzip_bytes": len(packed),
                      "raw_sha256": manifest["model"]["raw_sha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

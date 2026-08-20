#!/usr/bin/env python3
"""Build a hash-locked, genuinely held-out Hebrew corpus for measuring lexicon coverage.

WHY NOT JUST HOLD OUT PART OF SOURCE B: source B is *in* the lexicon. Measuring coverage on it
would inflate every number. The corpus has to come from text the lexicon has never seen.

WHY NOT THE FIRST N MB OF THE DUMP: pages in `pages-articles.xml.bz2` are ordered by ascending
page id, so a prefix is the oldest articles -- a sample skewed toward early core encyclopedic
topics. Cheap, but biased in a way that would quietly flatter coverage.

WHAT THIS DOES INSTEAD: the multistream dump is a concatenation of independently decompressible
bz2 blocks of ~100 pages each, and its index lists the byte offset of every page. We fetch the
index (~9.8 MB), pick block offsets by a seeded, version-independent hash ordering, and pull
just those blocks by HTTP Range. That is an unbiased sample across the whole encyclopedia for a
few MB of transfer instead of 1.13 GB, and it is reproducible because the dump date, the seed
and the resulting corpus sha256 are all committed.

Selection is deliberately NOT `random.sample`: that depends on CPython's Mersenne Twister
implementation details. Ordering by sha256("<seed>:<offset>") is stable across versions.
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import html
import io
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DUMP_DATE = "20260801"
BASE = f"https://dumps.wikimedia.org/hewiki/{DUMP_DATE}"
INDEX_URL = f"{BASE}/hewiki-{DUMP_DATE}-pages-articles-multistream-index.txt.bz2"
DATA_URL = f"{BASE}/hewiki-{DUMP_DATE}-pages-articles-multistream.xml.bz2"
DATA_BYTES = 1169474250

SEED = "hebrew-ime-heldout-v1"
DEFAULT_TARGET_TOKENS = 75000

HEBREW_WORD_RE = re.compile("^[א-ת]+$")
HEBREW_RUN_RE = re.compile("[א-ת]+")

TEXT_RE = re.compile(r"<text\b[^>]*>(.*?)</text>", re.DOTALL)
TITLE_RE = re.compile(r"<title>(.*?)</title>", re.DOTALL)


# Wikimedia's robot policy rejects generic library User-Agents with HTTP 403. A descriptive
# UA identifying the tool and a contact is required, not optional.
USER_AGENT = ("hebrew-ime-lexicon-build/1.0 "
              "(offline Hebrew IME; https://github.com/ereztash/--Android)")


def http_get(url: str, byte_range: tuple[int, int] | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if byte_range:
        req.add_header("Range", f"bytes={byte_range[0]}-{byte_range[1]}")
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()


def strip_wiki_markup(s: str) -> str:
    """Reduce MediaWiki source to running prose. Deliberately conservative."""
    s = html.unescape(s)
    s = re.sub(r"<ref[^>]*/>", " ", s)
    s = re.sub(r"<ref[^>]*>.*?</ref>", " ", s, flags=re.DOTALL)
    s = re.sub(r"<!--.*?-->", " ", s, flags=re.DOTALL)
    s = re.sub(r"<(math|code|pre|syntaxhighlight|gallery|timeline)\b.*?</\1>", " ", s,
               flags=re.DOTALL)
    # Templates and tables, innermost-out.
    for _ in range(8):
        new = re.sub(r"\{\{[^{}]*\}\}", " ", s)
        new = re.sub(r"\{\|[^{}]*?\|\}", " ", new, flags=re.DOTALL)
        if new == s:
            break
        s = new
    # File/image/category links dropped entirely; other links keep their display text.
    s = re.sub(r"\[\[(?:קובץ|תמונה|File|Image|Category|קטגוריה):[^\]]*\]\]", " ", s)
    s = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[https?://\S+\s+([^\]]*)\]", r"\1", s)
    s = re.sub(r"https?://\S+", " ", s)
    s = re.sub(r"<[^>]+>", " ", s)
    s = re.sub(r"^[*#:;]+", " ", s, flags=re.MULTILINE)
    s = s.replace("'''", " ").replace("''", " ").replace("=", " ")
    return s


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", default=os.path.join(ROOT, "lexicon", "heldout"))
    ap.add_argument("--cache-dir", default=os.path.join(ROOT, "lexicon", "cache"))
    ap.add_argument("--target-tokens", type=int, default=DEFAULT_TARGET_TOKENS)
    ap.add_argument("--max-blocks", type=int, default=60)
    args = ap.parse_args()

    os.makedirs(args.cache_dir, exist_ok=True)
    os.makedirs(args.out_dir, exist_ok=True)

    # --- 1. index ------------------------------------------------------------------------
    idx_path = os.path.join(args.cache_dir, f"hewiki-{DUMP_DATE}-index.txt.bz2")
    # Cache writes are atomic: download to a temp file and rename only on success. A partial
    # or empty file left behind by a failed run would otherwise be silently reused, and the
    # corpus would be built from nothing.
    if not os.path.isfile(idx_path) or os.path.getsize(idx_path) == 0:
        print(f"downloading index {INDEX_URL}", file=sys.stderr)
        payload = http_get(INDEX_URL)
        if not payload:
            print("EMPTY_DOWNLOAD: index fetch returned zero bytes", file=sys.stderr)
            return 1
        tmp = idx_path + ".part"
        with open(tmp, "wb") as fh:
            fh.write(payload)
        os.replace(tmp, idx_path)
    idx_raw = open(idx_path, "rb").read()
    if not idx_raw:
        print("EMPTY_INDEX: cached index is zero bytes", file=sys.stderr)
        return 1
    idx_sha = hashlib.sha256(idx_raw).hexdigest()
    print(f"index: {len(idx_raw)} bytes sha256={idx_sha}", file=sys.stderr)

    offsets: list[int] = []
    seen = set()
    pages = 0
    for line in bz2.decompress(idx_raw).decode("utf-8").splitlines():
        parts = line.split(":", 2)
        if len(parts) != 3:
            continue
        pages += 1
        off = int(parts[0])
        if off not in seen:
            seen.add(off)
            offsets.append(off)
    offsets.sort()
    print(f"index lists {pages} pages in {len(offsets)} multistream blocks", file=sys.stderr)

    # --- 2. deterministic, version-independent block selection ---------------------------
    ranked = sorted(
        range(len(offsets)),
        key=lambda i: hashlib.sha256(f"{SEED}:{offsets[i]}".encode()).hexdigest(),
    )

    # --- 3. pull blocks until the token target is met ------------------------------------
    tokens: list[str] = []
    used_blocks: list[dict] = []
    articles = 0
    bytes_fetched = 0
    for i in ranked:
        if len(tokens) >= args.target_tokens or len(used_blocks) >= args.max_blocks:
            break
        start = offsets[i]
        end = (offsets[i + 1] - 1) if i + 1 < len(offsets) else (DATA_BYTES - 1)
        try:
            raw = http_get(DATA_URL, (start, end))
        except Exception as exc:  # noqa: BLE001
            print(f"  block {start}: fetch failed ({exc}), skipped", file=sys.stderr)
            continue
        bytes_fetched += len(raw)
        try:
            xml = bz2.decompress(raw).decode("utf-8", errors="replace")
        except Exception as exc:  # noqa: BLE001
            print(f"  block {start}: bz2 decode failed ({exc}), skipped", file=sys.stderr)
            continue
        block_tokens = 0
        block_articles = 0
        for m in TEXT_RE.finditer(xml):
            body = strip_wiki_markup(m.group(1))
            found = [t for t in HEBREW_RUN_RE.findall(body) if HEBREW_WORD_RE.match(t)]
            if not found:
                continue
            block_articles += 1
            block_tokens += len(found)
            tokens.extend(found)
        articles += block_articles
        used_blocks.append({"offset": start, "compressed_bytes": len(raw),
                            "articles": block_articles, "tokens": block_tokens})
        print(f"  block {start}: {len(raw)} B -> {block_articles} articles, "
              f"{block_tokens} tokens (running {len(tokens)})", file=sys.stderr)

    if len(tokens) < args.target_tokens:
        print(f"\nZERO_OR_SHORT_DENOMINATOR: only {len(tokens)} tokens gathered, "
              f"target {args.target_tokens}.", file=sys.stderr)
        return 1

    tokens = tokens[: args.target_tokens]
    blob = ("\n".join(tokens) + "\n").encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(blob)
    packed = buf.getvalue()
    corpus_sha = hashlib.sha256(blob).hexdigest()

    out_corpus = os.path.join(args.out_dir, "hewiki_sample.txt.gz")
    with open(out_corpus, "wb") as fh:
        fh.write(packed)

    manifest = {
        "purpose": "held-out corpus for lexicon coverage measurement; NOT in the lexicon",
        "source": {
            "wiki": "hewiki", "dump_date": DUMP_DATE,
            "data_url": DATA_URL, "index_url": INDEX_URL,
            "index_sha256": idx_sha,
            "license": "CC BY-SA 4.0",
        },
        "sampling": {
            "method": "seeded sha256 ordering over multistream block offsets, fetched by "
                      "HTTP Range; unbiased across the encyclopedia, not a dump prefix",
            "seed": SEED,
            "blocks_available": len(offsets),
            "blocks_used": len(used_blocks),
            "compressed_bytes_fetched": bytes_fetched,
            "block_offsets_used": [b["offset"] for b in used_blocks],
        },
        "corpus": {
            "articles": articles,
            "tokens": len(tokens),
            "distinct_types": len(set(tokens)),
            "uncompressed_bytes": len(blob),
            "sha256": corpus_sha,
            "gzip_sha256": hashlib.sha256(packed).hexdigest(),
            "tokenization": "runs of U+05D0..U+05EA only; tokens containing maqaf, geresh, "
                            "digits or Latin letters are excluded",
        },
        "known_limitations": [
            "Encyclopedic prose. Not representative of keyboard input: no SMS register, no "
            "slang, no typos. Coverage measured here is a claim about encyclopedic Hebrew.",
            "Proper nouns are heavily over-represented relative to everyday typing.",
            "Tokens with internal punctuation (maqaf, geresh) are dropped by the tokenizer, "
            "so the denominator excludes them entirely.",
        ],
    }
    with open(os.path.join(args.out_dir, "MANIFEST.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"\ncorpus: {len(tokens)} tokens, {len(set(tokens))} types, "
          f"{articles} articles, {len(used_blocks)} blocks, "
          f"{bytes_fetched} compressed bytes fetched", file=sys.stderr)
    print(f"corpus sha256 {corpus_sha}", file=sys.stderr)
    print(json.dumps({"tokens": len(tokens), "types": len(set(tokens)),
                      "sha256": corpus_sha}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

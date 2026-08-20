#!/usr/bin/env python3
"""Measure lexicon coverage on the held-out corpus, with and without the prefix stripper.

Every number printed here is a claim about EXACTLY ONE THING: this lexicon, on this corpus,
with this tokenizer. The corpus sha256 is printed beside every table so the claim cannot be
quoted wider than the measurement that produced it.

The prefix grammar is the closed set from the build spec:
    W = {"", vav}   S = {"", sh, ksh, msh}   P = {"", b, k, l, m}   H = {"", h}
    prefix = W+S+P+H minus the empty string  ->  79 strings, max length 5
Accept rule: the token is in the lexicon literally, OR some prefix strips to a residual stem
of length >= MIN_STEM that is in the lexicon.
"""
from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

W = ["", "ו"]
S = ["", "ש", "כש", "מש"]
P = ["", "ב", "כ", "ל", "מ"]
H = ["", "ה"]


def prefixes() -> list[str]:
    out = {w + s + p + h for w in W for s in S for p in P for h in H}
    out.discard("")
    return sorted(out, key=lambda x: (-len(x), x))


def load_lexicon(path: str) -> set[str]:
    with gzip.open(path, "rb") as fh:
        return set(fh.read().decode("utf-8").split("\n")) - {""}


def load_corpus(path: str) -> tuple[list[str], str]:
    with gzip.open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8")
    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
    return [t for t in text.split("\n") if t], sha


def accepts(word: str, lex: set[str], pfx: list[str], min_stem: int | None) -> bool:
    if word in lex:
        return True
    if min_stem is None:
        return False
    for p in pfx:
        if word.startswith(p):
            stem = word[len(p):]
            if len(stem) >= min_stem and stem in lex:
                return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lexicon", default=os.path.join(ROOT, "lexicon", "he_lexicon.txt.gz"))
    ap.add_argument("--corpus",
                    default=os.path.join(ROOT, "lexicon", "heldout", "hewiki_sample.txt.gz"))
    ap.add_argument("--json-out")
    args = ap.parse_args()

    lex = load_lexicon(args.lexicon)
    tokens, corpus_sha = load_corpus(args.corpus)
    pfx = prefixes()

    assert len(pfx) == 79, f"prefix grammar produced {len(pfx)} strings, expected 79"
    assert max(len(p) for p in pfx) == 5

    print(f"lexicon        : {len(lex)} forms  ({os.path.relpath(args.lexicon, ROOT)})")
    print(f"corpus         : {len(tokens)} tokens, {len(set(tokens))} types")
    print(f"corpus sha256  : {corpus_sha}")
    print(f"prefix grammar : {len(pfx)} strings, max length {max(len(p) for p in pfx)}")
    print()

    rows = []
    cache: dict[tuple[str, object], bool] = {}
    for label, min_stem in (("none", None), ("2", 2), ("3", 3), ("4", 4), ("5", 5)):
        hit_tokens = 0
        hit_types = set()
        for t in tokens:
            key = (t, min_stem)
            v = cache.get(key)
            if v is None:
                v = accepts(t, lex, pfx, min_stem)
                cache[key] = v
            if v:
                hit_tokens += 1
                hit_types.add(t)
        types = set(tokens)
        rows.append({
            "min_stem": label,
            "token_coverage": hit_tokens / len(tokens),
            "type_coverage": len(hit_types) / len(types),
            "tokens_covered": hit_tokens,
            "tokens_total": len(tokens),
            "types_covered": len(hit_types),
            "types_total": len(types),
        })

    print(f"{'MIN_STEM':<10}{'token cov':>12}{'wrong-underline':>18}{'type cov':>12}"
          f"{'tokens hit':>13}")
    print("-" * 65)
    for r in rows:
        print(f"{r['min_stem']:<10}{r['token_coverage']*100:>11.2f}%"
              f"{(1-r['token_coverage'])*100:>17.2f}%"
              f"{r['type_coverage']*100:>11.2f}%"
              f"{r['tokens_covered']:>13}")
    print(f"\nDenominator: {len(tokens)} tokens / {len(set(tokens))} types, corpus "
          f"sha256 {corpus_sha[:16]}...")

    # What is still missed at the recommended MIN_STEM, by frequency.
    missed = collections.Counter(
        t for t in tokens if not accepts(t, lex, pfx, 4))
    print(f"\nTop 25 unrecognized types at MIN_STEM=4 "
          f"({len(missed)} distinct, {sum(missed.values())} tokens):")
    for w, c in missed.most_common(25):
        print(f"    {c:>4}  {w}")

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump({"lexicon_forms": len(lex), "corpus_sha256": corpus_sha,
                       "corpus_tokens": len(tokens), "corpus_types": len(set(tokens)),
                       "prefix_count": len(pfx), "rows": rows,
                       "top_missed": missed.most_common(50)},
                      fh, indent=2, ensure_ascii=False)
            fh.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

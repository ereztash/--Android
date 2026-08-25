#!/usr/bin/env python3
"""A2 — what is the out-of-lexicon gap actually made of, and what would close it?

### Why this exists
`W1` measured that **7.61% of the tokens a person types** are outside the shipped lexicon,
against 1.15% on transcribed dialogue. That number was immediately read as an argument for
external data: brand names, English words, emoji.

**Before sourcing for a gap, measure what the gap is made of.** This file does that, and the
answer reverses the intuition: Latin and emoji together are a rounding error, and the two
largest recoverable pieces need **no external data at all**.

### What it reports
1. the OOV decomposed by what the token actually is;
2. how much of the Hebrew OOV a prefix chain **the repository already ships** resolves;
3. how much of the gershayim OOV is **already in the shipped abbreviation table**, once the
   corpus's `""` is normalised to `״`;
4. how much of what is left a Hebrew Wikipedia title list would cover, and what it would cost.

### The prefix number is an UPPER BOUND, exactly as `H1` labelled its own
`ומחמוד → חמוד` "resolves" Mahmoud to the word for *cute*. A prefix chain that lands on a real
word is not proof that the word is the right analysis, so 53.6% is a ceiling and not a yield.
"""
from __future__ import annotations

import argparse
import collections
import gzip
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HEB = re.compile(r"^[א-ת]+$")
LAT = re.compile(r"^[A-Za-z]+$")
NUM = re.compile(r"^[0-9]+$")
PUNCT = re.compile(r"^[^\wא-ת]+$")
MIXED = re.compile(r"[A-Za-z].*[א-ת]|[א-ת].*[A-Za-z]")
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿]")
GERESH = re.compile(r"[׳״'\"]")

# core/src/main/kotlin/com/hebrewime/core/lexicon/PrefixStripper.kt
PREFIXES = ["ו", "ה", "ב", "כ", "ל", "מ", "ש", "וה", "וב", "וכ", "ול", "ומ", "ושה", "כש",
            "לכש", "מה", "שה", "שב", "שכ", "של", "שמ", "וכש", "מב", "מל", "הב", "הכ", "הל", "המ"]


def load_lines(path: str):
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            yield line


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", default="lexicon/eval/he_typed_raw.txt.gz")
    ap.add_argument("--titles", default=None,
                    help="hewiki-latest-all-titles-in-ns0.gz; omit and that row is NOT MEASURED")
    args = ap.parse_args()

    lex = {l.rstrip("\n") for l in load_lines(os.path.join(ROOT, "lexicon/assets/he_lexicon.txt.gz"))}
    marked = set()
    for l in load_lines(os.path.join(ROOT, "lexicon/assets/he_abbreviations.txt.gz")):
        p = l.rstrip("\n").split("\t")
        if len(p) > 1 and p[1]:
            marked.add(p[1])
    print(f"lexicon {len(lex):,} forms; abbreviation table {len(marked):,} gershayim forms")

    def strips(w: str) -> bool:
        return any(w.startswith(p) and len(w) - len(p) >= 2 and w[len(p):] in lex
                   for p in sorted(PREFIXES, key=len, reverse=True))

    oov: collections.Counter = collections.Counter()
    kinds: collections.Counter = collections.Counter()
    toks = 0
    for line in load_lines(os.path.join(ROOT, args.corpus)):
        for t in line.split():
            if len(t) < 3:
                continue
            toks += 1
            if t in lex:
                continue
            oov[t] += 1
            if EMOJI.search(t):
                kinds["emoji"] += 1
            elif NUM.match(t):
                kinds["pure digits"] += 1
            elif LAT.match(t):
                kinds["pure Latin word"] += 1
            elif PUNCT.match(t):
                kinds["pure punctuation"] += 1
            elif MIXED.search(t):
                kinds["mixed Hebrew+Latin"] += 1
            elif GERESH.search(t) and HEB.match(GERESH.sub("", t)):
                kinds["Hebrew + geresh/quote"] += 1
            elif HEB.match(t):
                kinds["Hebrew word not in lexicon"] += 1
            else:
                kinds["other (digits+letters, symbols)"] += 1

    n = sum(oov.values())
    print(f"\n{args.corpus}: {toks:,} tokens >=3 chars, {n:,} OOV ({100*n/toks:.2f}%), "
          f"{len(oov):,} distinct types")
    print(f"\n{'what the OOV actually is':<34}{'occurrences':>13}{'of OOV':>10}{'of tokens':>12}")
    for k, v in kinds.most_common():
        print(f"{k:<34}{v:>13,}{100*v/n:>9.1f}%{100*v/toks:>11.2f}%")

    print("\n" + "=" * 84)
    print("WHAT WOULD CLOSE IT  (percentage points of all tokens)")
    print("=" * 84)
    heb = {w: c for w, c in oov.items() if HEB.match(w)}
    res = sum(c for w, c in heb.items() if strips(w))
    print(f"{'lever':<44}{'cost':>20}{'buys':>10}")
    print(f"{'the prefix path ALREADY SHIPPED (upper bound)':<44}{'0 bytes':>20}{100*res/toks:>9.2f}pp")

    ab = {w: c for w, c in oov.items() if '""' in w or '״' in w}
    hit = sum(c for w, c in ab.items() if w.replace('""', '״') in marked)
    print(f"{chr(39)+chr(34)+chr(34)+chr(39)+' -> '+chr(39)+'״'+chr(39)+' normalisation':<44}"
          f"{'0 bytes':>20}{100*hit/toks:>9.2f}pp")

    rest = {w: c for w, c in heb.items() if not strips(w)}
    if args.titles:
        words = set()
        titles = 0
        for l in load_lines(args.titles):
            titles += 1
            for w in re.findall(r"[א-ת]+", l.replace("_", " ")):
                if len(w) >= 2:
                    words.add(w)
        cov = sum(c for w, c in rest.items() if w in words)
        print(f"{'hewiki title words (CC BY-SA 4.0)':<44}"
              f"{f'+{len(words - lex):,} forms':>20}{100*cov/toks:>9.2f}pp")
        print(f"    from {titles:,} titles; the lexicon would grow "
              f"{100*len(words - lex)/len(lex):.0f}%")
    else:
        print(f"{'hewiki title words':<44}{'--titles not given':>20}{'  NOT MEASURED':>10}")

    lat = kinds.get("pure Latin word", 0)
    print(f"{'an English lexicon':<44}{'large':>20}{100*lat/toks:>9.2f}pp")
    print(f"{'emoji':<44}{'small':>20}{100*kinds.get('emoji', 0)/toks:>9.2f}pp")
    print("=" * 84)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

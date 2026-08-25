#!/usr/bin/env python3
"""G1 — build the segmentation, and measure whether it keeps the language.

The design, the six predictions and the four-clause stopping rule were committed before this
file existed. See `docs/SEGMENTATION.md`.

### Four layers, most specific first, and only the last is learned
1. **Prefix** — the closed W x S x P x H grammar, the same 79 strings `PrefixStripper.ALL`
   builds. Morphology, given.
2. **Verb** — `surface -> lemma + features`, read from source A's `base_form` and `morphology`
   columns, which `build_lexicon.py` discards. Morphology, given.
3. **Subword** — BPE over what the first two do not reach. **Learned, and not morphology.**
4. **Character fallback** — so coverage is 100% by construction and can be asserted.

### One constant is deliberately not the shipped one
`PrefixStripper.DEFAULT_MIN_STEM` is 4, chosen to make *typo rejection* work in `accepts()`.
Segmentation has the opposite failure mode: refusing to split a real prefix costs exactly the
sharing this experiment is measuring, and `ha+bayit` has a three-character stem. `MIN_STEM`
here is therefore **2**, stated rather than silently inherited or silently changed.

### What this does not do
It does not touch the shipped lexicon, the shipped assets, or any gate. It writes a
segmentation and a measurement, and nothing reads them yet.
"""
from __future__ import annotations

import argparse
import collections
import csv
import gzip
import json
import os
import random
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NIQQUD_RE = re.compile(r"[֑-ׇ]")

# The W x S x P x H grammar, identical to PrefixStripper.ALL.
_W, _S, _P, _H = ["", "ו"], ["", "ש", "כש", "מש"], ["", "ב", "כ", "ל", "מ"], ["", "ה"]
PREFIXES = sorted(
    {w + s + p + h for w in _W for s in _S for p in _P for h in _H} - {""},
    key=lambda x: (-len(x), x),
)
MIN_STEM = 2                      # see the module docstring; the shipped constant is 4
VOCAB_TARGET = 16_384             # K1 shape B
END = "</w>"


def strip_niqqud(w: str) -> str:
    return NIQQUD_RE.sub("", unicodedata.normalize("NFC", w))


def load_lexicon() -> list[str]:
    raw = gzip.decompress(open(os.path.join(ROOT, "lexicon/assets/he_lexicon.txt.gz"), "rb").read())
    return [w for w in raw.decode("utf-8").split("\n") if w]


def load_frequency(n: int) -> list[int]:
    """One byte per word, `round(log2(count+1)*8)`, decoded back to an approximate count."""
    raw = gzip.decompress(open(os.path.join(ROOT, "lexicon/assets/he_freq.bin.gz"), "rb").read())
    return [max(1, int(2 ** (b / 8.0)) - 1) for b in raw[:n]]


def load_verbs() -> dict[str, tuple[str, str]]:
    """surface -> (lemma, features), from the columns build_lexicon.py throws away."""
    path = os.path.join(ROOT, "lexicon/cache/InflectedVerbsExtended.csv")
    if not os.path.isfile(path):
        print("NOT-MEASURED: source A is not cached; run scripts/check_lexicon.py --allow-fetch",
              file=sys.stderr)
        return {}
    out: dict[str, tuple[str, str]] = {}
    with open(path, encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            s = strip_niqqud(row.get("vocalized_inflection") or "")
            b = strip_niqqud(row.get("base_form") or "")
            m = row.get("morphology") or ""
            if s and b and s not in out:
                out[s] = (b, m)
    return out


# --- BPE ---------------------------------------------------------------------------------

def train_bpe(words: dict[str, int], merges: int) -> list[tuple[str, str]]:
    """Standard BPE with an inverted index, so a merge only touches the words carrying it."""
    seqs = {w: tuple(list(w) + [END]) for w in words}
    pair_count: collections.Counter = collections.Counter()
    pair_words: dict[tuple[str, str], set[str]] = collections.defaultdict(set)
    for w, seq in seqs.items():
        f = words[w]
        for a, b in zip(seq, seq[1:]):
            pair_count[(a, b)] += f
            pair_words[(a, b)].add(w)

    learned: list[tuple[str, str]] = []
    for _ in range(merges):
        if not pair_count:
            break
        best, cnt = pair_count.most_common(1)[0]
        if cnt <= 0:
            break
        learned.append(best)
        joined = best[0] + best[1]
        for w in list(pair_words[best]):
            seq, f = seqs[w], words[w]
            for a, b in zip(seq, seq[1:]):
                pair_count[(a, b)] -= f
                if pair_count[(a, b)] <= 0:
                    del pair_count[(a, b)]
                    pair_words.pop((a, b), None)
                else:
                    pair_words[(a, b)].discard(w)
            new: list[str] = []
            i = 0
            while i < len(seq):
                if i + 1 < len(seq) and (seq[i], seq[i + 1]) == best:
                    new.append(joined); i += 2
                else:
                    new.append(seq[i]); i += 1
            seqs[w] = tuple(new)
            for a, b in zip(seqs[w], seqs[w][1:]):
                pair_count[(a, b)] += f
                pair_words[(a, b)].add(w)
        pair_count.pop(best, None)
        pair_words.pop(best, None)
    return learned


def bpe_apply(word: str, ranks: dict[tuple[str, str], int]) -> list[str]:
    seq = list(word) + [END]
    while len(seq) > 1:
        best, at = None, -1
        for i, (a, b) in enumerate(zip(seq, seq[1:])):
            r = ranks.get((a, b))
            if r is not None and (best is None or r < best):
                best, at = r, i
        if at < 0:
            break
        seq[at:at + 2] = [seq[at] + seq[at + 1]]
    return [s for s in seq if s != END] or [word]


# --- the segmenter -----------------------------------------------------------------------

class Segmenter:
    def __init__(self, verbs, ranks, lexicon: set[str]):
        self.verbs, self.ranks, self.lex = verbs, ranks, lexicon

    def _stem(self, s: str) -> list[str]:
        hit = self.verbs.get(s)
        if hit:
            return [f"L:{hit[0]}", f"F:{hit[1]}"]
        return [f"S:{u}" for u in bpe_apply(s, self.ranks)]

    def segment(self, w: str) -> list[str]:
        for p in PREFIXES:
            if len(w) - len(p) >= MIN_STEM and w.startswith(p):
                rest = w[len(p):]
                if rest in self.lex or rest in self.verbs:
                    return [f"P:{p}"] + self._stem(rest)
        return self._stem(w)


# --- measurement -------------------------------------------------------------------------

def jaccard(a: list[str], b: list[str]) -> float:
    sa, sb = set(a), set(b)
    return len(sa & sb) / len(sa | sb) if (sa | sb) else 0.0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vocab", type=int, default=VOCAB_TARGET)
    ap.add_argument("--pairs", type=int, default=5_000)
    ap.add_argument("--out", default=os.path.join(ROOT, "lexicon/experimental/segmentation.json"))
    args = ap.parse_args()
    rng = random.Random(20260825)

    lex_list = load_lexicon()
    lex = set(lex_list)
    freq = load_frequency(len(lex_list))
    verbs = load_verbs()
    if not verbs:
        return 2

    # What the BPE layer must cover: every lexicon word, after the prefix and verb layers.
    residual: dict[str, int] = {}
    for w, f in zip(lex_list, freq):
        if w in verbs:
            continue
        s = w
        for p in PREFIXES:
            if len(w) - len(p) >= MIN_STEM and w.startswith(p) and w[len(p):] in lex:
                s = w[len(p):]
                break
        if s in verbs:
            continue
        residual[s] = residual.get(s, 0) + f

    chars = sorted({c for w in residual for c in w})
    lemmas = {v[0] for v in verbs.values()}
    feats = {v[1] for v in verbs.values()}
    fixed = len(PREFIXES) + len(lemmas) + len(feats) + len(chars)
    merges = max(0, args.vocab - fixed)
    print(f"residual types {len(residual):,}   fixed units {fixed:,} "
          f"(prefix {len(PREFIXES)}, lemma {len(lemmas):,}, feat {len(feats)}, char {len(chars)})")
    print(f"BPE merges to reach vocab {args.vocab:,}: {merges:,}")

    learned = train_bpe(residual, merges)
    ranks = {p: i for i, p in enumerate(learned)}
    seg = Segmenter(verbs, ranks, lex)
    total_vocab = fixed + len(learned)
    print(f"learned {len(learned):,} merges; TOTAL VOCABULARY {total_vocab:,}")

    # --- clause 1: coverage, asserted over the whole lexicon and both slices ---
    uncovered = 0
    for w in lex_list:
        if not seg.segment(w):
            uncovered += 1
    slices = {}
    for name in ("he_conversational_test.txt.gz", "hewiki_eval_sample.txt.gz"):
        raw = gzip.decompress(open(os.path.join(ROOT, "lexicon/eval", name), "rb").read())
        toks = [t for line in raw.decode("utf-8").split("\n") if line.strip()
                for t in line.split(" ")]
        slices[name] = toks
        for t in toks:
            if not seg.segment(t):
                uncovered += 1
    print(f"coverage: {uncovered} uncovered of {len(lex_list) + sum(len(v) for v in slices.values()):,}")

    # --- clause 3: compression ---
    conv = slices["he_conversational_test.txt.gz"]
    units = sum(len(seg.segment(t)) for t in conv)
    mean_units = units / len(conv)
    print(f"compression: {mean_units:.3f} units per token over {len(conv):,} conversational tokens")

    # --- clause 4: form sharing ---
    by_lemma: dict[str, list[str]] = collections.defaultdict(list)
    for s, (b, _m) in verbs.items():
        if s in lex:
            by_lemma[b].append(s)
    verb_pairs = []
    cands = [v for v in by_lemma.values() if len(v) > 1]
    while cands and len(verb_pairs) < args.pairs:
        g = cands[rng.randrange(len(cands))]
        a, b = rng.sample(g, 2)
        verb_pairs.append((a, b))

    prefix_pairs = []
    tries = 0
    while len(prefix_pairs) < args.pairs and tries < args.pairs * 200:
        tries += 1
        w = lex_list[rng.randrange(len(lex_list))]
        p = PREFIXES[rng.randrange(len(PREFIXES))]
        if p + w in lex and len(w) >= MIN_STEM:
            prefix_pairs.append((w, p + w))

    ktiv_pairs = []
    tries = 0
    while len(ktiv_pairs) < args.pairs and tries < args.pairs * 400:
        tries += 1
        w = lex_list[rng.randrange(len(lex_list))]
        idx = [i for i, c in enumerate(w) if c in "וי"]
        if not idx:
            continue
        i = idx[rng.randrange(len(idx))]
        v = w[:i] + w[i + 1:]
        if len(v) >= 2 and v in lex:
            ktiv_pairs.append((w, v))

    control = [(lex_list[rng.randrange(len(lex_list))], lex_list[rng.randrange(len(lex_list))])
               for _ in range(args.pairs)]

    results = {}
    for name, pairs in (("verb", verb_pairs), ("prefix", prefix_pairs),
                        ("ktiv", ktiv_pairs), ("control", control)):
        if not pairs:
            results[name] = (0.0, 0)
            continue
        j = sum(jaccard(seg.segment(a), seg.segment(b)) for a, b in pairs) / len(pairs)
        results[name] = (j, len(pairs))
        print(f"sharing {name:8} mean Jaccard {j:.4f}   n={len(pairs):,}")

    ktiv_j = results["ktiv"][0]
    ctrl_j = results["control"][0]
    ratio = ktiv_j / ctrl_j if ctrl_j > 0 else float("inf")

    print()
    print("=" * 92)
    print("AGAINST THE PRE-REGISTERED RULE")
    print(f"  coverage exactly 100% ........................ {'yes' if uncovered == 0 else 'NO'}")
    print(f"  total vocabulary <= 16,384 ................... "
          f"{'yes' if total_vocab <= 16_384 else 'NO'}  ({total_vocab:,})")
    print(f"  mean units per token <= 3.0 .................. "
          f"{'yes' if mean_units <= 3.0 else 'NO'}  ({mean_units:.3f})")
    print(f"  ktiv sharing >= 3x control, same run ......... "
          f"{'yes' if ratio >= 3.0 else 'NO'}  ({ktiv_j:.4f} / {ctrl_j:.4f} = {ratio:.2f}x)")
    print("=" * 92)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({
            "vocab": total_vocab, "merges": len(learned), "min_stem": MIN_STEM,
            "prefixes": len(PREFIXES), "lemmas": len(lemmas), "features": len(feats),
            "chars": len(chars), "mean_units_per_token": mean_units,
            "sharing": {k: {"jaccard": v[0], "n": v[1]} for k, v in results.items()},
        }, f, ensure_ascii=False, indent=2)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

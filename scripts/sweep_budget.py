#!/usr/bin/env python3
"""G3 — is `G2`'s compression penalty scarcity, or is it layer order?

The two dimensions, the five points and the five-clause rule were committed before this file
existed. See `docs/SEGMENTATION.md`, section **G3**.

### The admission rule, stated so it is not ambiguous
**All 17,236 noun/adjective lexemes are admitted at every budget**, and BPE takes whatever
remains — possibly nothing. That is what makes this a test of *budget* rather than a second
slot sweep: at 16,384 BPE is starved to zero, at 32,768 it keeps 11,716 of its 12,705 merges.

### The two modes
- **`layered`** — the noun layer wins whenever it has an analysis. What `G2` measured.
- **`shortest`** — both encodings are computed and the shorter is kept, ties going to the
  analysis. It cannot lose on compression by construction, which is precisely what separates
  the ordering effect from the scarcity effect.

### Baseline
`G1` is re-measured in this same run at budget 16,384 with no noun layer, and every bar is
against that row — never against the published 1.809 and 0.3207.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_segmentation import (  # noqa: E402
    MIN_STEM, PREFIXES, Segmenter, jaccard, load_frequency, load_lexicon, load_verbs, train_bpe,
)
from sweep_segmentation import (  # noqa: E402
    AFFORDABLE_BYTES, BUDGET_GRID, EMB, MODES, NOUN_ADJ, G2Segmenter, ideal_units_g2,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def residual_for(lex_list, freq_list, lex, verbs, nouns):
    out: dict[str, int] = {}
    for w, f in zip(lex_list, freq_list):
        if w in verbs or w in nouns:
            continue
        s = w
        for p in PREFIXES:
            if len(w) - len(p) >= MIN_STEM and w.startswith(p) and w[len(p):] in lex:
                s = w[len(p):]
                break
        if s in verbs or s in nouns:
            continue
        out[s] = out.get(s, 0) + f
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pairs", type=int, default=5_000)
    args = ap.parse_args()
    rng = random.Random(20260825)

    if not os.path.isfile(NOUN_ADJ):
        print(f"NOT-MEASURED: {NOUN_ADJ} absent; run scripts/extract_lexemes.py", file=sys.stderr)
        return 2

    lex_list = load_lexicon()
    lex = set(lex_list)
    freq_list = load_frequency(len(lex_list))
    verbs = load_verbs()
    if not verbs:
        return 2
    lexemes = json.load(open(NOUN_ADJ, encoding="utf-8"))["lexemes"]

    raw = gzip.decompress(
        open(os.path.join(ROOT, "lexicon/eval/he_conversational_test.txt.gz"), "rb").read())
    conv = [t for line in raw.decode("utf-8").split("\n") if line.strip() for t in line.split(" ")]

    all_nouns: dict[str, tuple[str, str]] = {}
    for v in lexemes.values():
        for rep, feats in v["forms"]:
            all_nouns.setdefault(rep, (v["lemma"], feats))
    n_lemmas = len({v["lemma"] for v in lexemes.values()})
    n_feats = len({f for v in lexemes.values() for _r, f in v["forms"]})
    verb_lemmas = {v[0] for v in verbs.values()}
    verb_feats = {v[1] for v in verbs.values()}

    def families(seg, nouns):
        res = {}
        for fam in ("nounadj", "ktiv", "control"):
            pairs, tries = [], 0
            if fam == "nounadj":
                groups = [[f[0] for f in v["forms"] if f[0] in lex] for v in lexemes.values()]
                groups = [g for g in groups if len(g) > 1]
                while groups and len(pairs) < args.pairs:
                    g = groups[rng.randrange(len(groups))]
                    a, b = rng.sample(g, 2)
                    pairs.append((a, b))
            elif fam == "ktiv":
                while len(pairs) < args.pairs and tries < args.pairs * 400:
                    tries += 1
                    w = lex_list[rng.randrange(len(lex_list))]
                    idx = [i for i, c in enumerate(w) if c in "וי"]
                    if not idx:
                        continue
                    i = idx[rng.randrange(len(idx))]
                    v = w[:i] + w[i + 1:]
                    if len(v) >= 2 and v in lex:
                        pairs.append((w, v))
            else:
                pairs = [(lex_list[rng.randrange(len(lex_list))],
                          lex_list[rng.randrange(len(lex_list))]) for _ in range(args.pairs)]
            if not pairs:
                res[fam] = {"jaccard": 0.0, "n": 0, "ideal": None}
                continue
            js = [jaccard(seg.segment(a), seg.segment(b)) for a, b in pairs]
            ideals = []
            if fam != "ktiv":
                for a, b in pairs:
                    ia = ideal_units_g2(a, verbs, nouns, lex)
                    ib = ideal_units_g2(b, verbs, nouns, lex)
                    if ia is not None and ib is not None:
                        ideals.append(jaccard(ia, ib))
            res[fam] = {"jaccard": sum(js) / len(js), "n": len(pairs),
                        "ideal": (sum(ideals) / len(ideals)) if ideals else None}
        return res

    rows = []

    # --- the baseline: G1 re-measured in this run, no noun layer, budget 16,384 ---
    residual = residual_for(lex_list, freq_list, lex, verbs, {})
    chars = sorted({c for w in residual for c in w})
    fixed0 = len(PREFIXES) + len(verb_lemmas) + len(verb_feats) + len(chars)
    print(f"baseline G1: fixed {fixed0:,}, BPE merges {16_384 - fixed0:,}", flush=True)
    learned0 = train_bpe(residual, 16_384 - fixed0)
    seg0 = Segmenter(verbs, {p: i for i, p in enumerate(learned0)}, lex)
    base_units = sum(len(seg0.segment(t)) for t in conv) / len(conv)
    base_fams = families(seg0, {})
    print(f"  units/token {base_units:.3f}   ktiv {base_fams['ktiv']['jaccard']:.4f}", flush=True)

    for budget in BUDGET_GRID:
        fixed = len(PREFIXES) + len(verb_lemmas) + len(verb_feats) + n_lemmas + n_feats
        residual = residual_for(lex_list, freq_list, lex, verbs, all_nouns)
        chars = sorted({c for w in residual for c in w}) or ["א"]
        fixed += len(chars)
        merges = max(0, budget - fixed)
        print(f"\n=== budget {budget:,}: fixed {fixed:,} "
              f"(all {n_lemmas:,} lemmas + {n_feats} features), BPE merges {merges:,}", flush=True)
        learned = train_bpe(residual, merges)
        ranks = {p: i for i, p in enumerate(learned)}
        vocab = fixed + len(learned)
        by = vocab * EMB

        for mode in MODES:
            seg = G2Segmenter(verbs, all_nouns, ranks, lex, mode=mode)
            uncovered = sum(1 for w in lex_list if not seg.segment(w))
            uncovered += sum(1 for t in conv if not seg.segment(t))
            units = sum(len(seg.segment(t)) for t in conv) / len(conv)
            fams = families(seg, all_nouns)
            na, kt = fams["nounadj"], fams["ktiv"]
            ji = (na["jaccard"] / na["ideal"]) if na["ideal"] else 0.0
            rows.append({"budget": budget, "mode": mode, "vocab": vocab, "bytes": by,
                         "uncovered": uncovered, "units": units, "noun_j": na["jaccard"],
                         "noun_ideal": na["ideal"], "noun_ji": ji, "ktiv": kt["jaccard"],
                         "control": fams["control"]["jaccard"]})
            print(f"  {mode:9} vocab {vocab:,} ({by:,} B)  units/token {units:.3f}  "
                  f"noun J/ideal {ji:.2f}  ktiv {kt['jaccard']:.4f}", flush=True)

    print()
    print("=" * 112)
    print("AGAINST THE PRE-REGISTERED RULE  (every bar against G1 re-measured in this run)")
    print(f"  G1 in this run: units/token {base_units:.3f}   ktiv {base_fams['ktiv']['jaccard']:.4f}")
    print()
    print(f"{'budget':>8} {'mode':>9} {'vocab':>8} {'bytes':>10} {'affordable':>11} {'uncov':>6} "
          f"{'units/tok':>10} {'vs G1':>8} {'noun J/id':>10} {'ktiv':>8} {'vs G1':>8}  all five?")
    any_pass = False
    for r in rows:
        aff = r["bytes"] <= AFFORDABLE_BYTES
        ok = (r["uncovered"] == 0 and r["units"] <= base_units + 1e-9
              and r["noun_ji"] >= 0.80
              and r["ktiv"] >= base_fams["ktiv"]["jaccard"] - 1e-9 and aff)
        any_pass = any_pass or ok
        print(f"{r['budget']:>8,} {r['mode']:>9} {r['vocab']:>8,} {r['bytes']:>10,} "
              f"{'yes' if aff else 'NO':>11} {r['uncovered']:>6} {r['units']:>10.3f} "
              f"{r['units'] - base_units:>+8.3f} {r['noun_ji']:>10.2f} {r['ktiv']:>8.4f} "
              f"{r['ktiv'] - base_fams['ktiv']['jaccard']:>+8.4f}  {'YES' if ok else 'no'}")
    print()
    print(f"  VERDICT: {'a cell meets every clause' if any_pass else 'NO cell meets every clause — NOTHING IS ADOPTED'}")
    print("=" * 112)

    out = os.path.join(ROOT, "lexicon/experimental/segmentation_g3.json")
    json.dump({"baseline": {"units": base_units, "ktiv": base_fams["ktiv"]["jaccard"]},
               "rows": rows}, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

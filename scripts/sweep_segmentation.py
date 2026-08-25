#!/usr/bin/env python3
"""G2 step 2 — sweep how many noun/adjective lexemes earn a slot inside a fixed budget.

The design, the five points and the five-clause stopping rule were committed before this file
existed. See `docs/SEGMENTATION.md`, section **G2**.

### Why this is a sweep and not an addition
`G1`'s fixed units are 3,679 of 16,384. The noun and adjective lemmas that survive extraction
are 17,236, plus 137 feature bundles. Admitting all of them totals **21,052 against a budget of
16,384** — over by 4,668 before one BPE unit. So the question is not whether the table helps.
It is **which lexemes earn a slot**, which is exactly the constraint `K1` named: vocabulary is
bought with bytes.

Lexemes are ranked by the shipped frequency of their forms, the top `N` admitted, and BPE fills
whatever is left so **every configuration totals 16,384**. `N = 0` is `G1` re-measured in the
same run, which is what the stopping rule compares against — never the published 1.809 and
0.3186.

### G1's builder is imported, not copied
`build_segmentation.py` is unchanged, so the numbers it published stay reproducible by the file
that produced them. This adds a layer between the verb layer and BPE and nothing else.

### One number differs from the pre-registration, and it is not hidden
G2 was registered against the Wikidata Query Service's counts: 19,389 noun and 4,279 adjective
lexemes, 23,668 together. Extraction keeps **17,236** — 13,034 nouns and 4,202 adjectives. The
6,432 difference is lexemes whose lemma or every form carries a space, a maqaf or a gershayim,
which `^[א-ת]+$` rejects. That is the same `H1-ALPHABET` hole seen from the other side: the
corpora these are measured against are Hebrew-letters-only by construction, so a multi-word
lexeme could not have been used even if it had been kept.
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_segmentation import (  # noqa: E402
    MIN_STEM, PREFIXES, Segmenter, bpe_apply, jaccard, load_frequency, load_lexicon,
    load_verbs, size_ceiling, train_bpe,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOUN_ADJ = os.path.join(ROOT, "lexicon/cache/he_noun_adj.json")
BUDGET = 16_384
SLOT_GRID = [0, 2_000, 4_000, 8_000, 12_000]      # fixed in the pre-registration


class G2Segmenter(Segmenter):
    """G1's segmenter with one layer inserted: given noun/adjective paradigms."""

    def __init__(self, verbs, nouns, ranks, lexicon):
        super().__init__(verbs, ranks, lexicon)
        self.nouns = nouns

    def _stem(self, s: str) -> list[str]:
        hit = self.verbs.get(s)
        if hit:
            return [f"L:{hit[0]}", f"F:{hit[1]}"]
        hit = self.nouns.get(s)
        if hit:
            return [f"N:{hit[0]}", f"G:{hit[1]}"]
        return [f"S:{u}" for u in bpe_apply(s, self.ranks)]


def ideal_units_g2(w, verbs, nouns, lex):
    """A perfect decomposition from either licensed table. None when neither has an analysis."""
    for p in PREFIXES:
        if len(w) - len(p) >= MIN_STEM and w.startswith(p):
            rest = w[len(p):]
            if rest in verbs:
                return [f"P:{p}", f"L:{verbs[rest][0]}", f"F:{verbs[rest][1]}"]
            if rest in nouns:
                return [f"P:{p}", f"N:{nouns[rest][0]}", f"G:{nouns[rest][1]}"]
    if w in verbs:
        return [f"L:{verbs[w][0]}", f"F:{verbs[w][1]}"]
    if w in nouns:
        return [f"N:{nouns[w][0]}", f"G:{nouns[w][1]}"]
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pairs", type=int, default=5_000)
    ap.add_argument("--slots", type=int, nargs="*", default=SLOT_GRID)
    args = ap.parse_args()
    rng = random.Random(20260825)

    if not os.path.isfile(NOUN_ADJ):
        print(f"NOT-MEASURED: {NOUN_ADJ} absent; run scripts/extract_lexemes.py", file=sys.stderr)
        return 2

    lex_list = load_lexicon()
    lex = set(lex_list)
    freq_list = load_frequency(len(lex_list))
    freq = dict(zip(lex_list, freq_list))
    verbs = load_verbs()
    if not verbs:
        return 2

    blob = json.load(open(NOUN_ADJ, encoding="utf-8"))
    lexemes = blob["lexemes"]
    print(f"noun/adjective lexemes extracted {len(lexemes):,} from {blob['dump']}")
    print(f"  dump sha256 {blob['dump_sha256']}")

    # Rank by the shipped frequency of the forms that are actually in the lexicon.
    scored = []
    for lid, v in lexemes.items():
        s = sum(freq.get(f[0], 0) for f in v["forms"] if f[0] in lex)
        scored.append((s, lid))
    scored.sort(key=lambda x: (-x[0], x[1]))
    in_lex_forms = len({f[0] for v in lexemes.values() for f in v["forms"] if f[0] in lex})
    print(f"  of their {len({f[0] for v in lexemes.values() for f in v['forms']}):,} distinct "
          f"forms, {in_lex_forms:,} are in the shipped lexicon "
          f"({100 * in_lex_forms / len(lex):.2f}% of it)")

    rows = []
    baseline = None
    for n_slots in args.slots:
        admitted = {lid for _s, lid in scored[:n_slots]}
        nouns: dict[str, tuple[str, str]] = {}
        for lid in admitted:
            v = lexemes[lid]
            for rep, feats in v["forms"]:
                nouns.setdefault(rep, (v["lemma"], feats))

        n_lemmas = len({lexemes[l]["lemma"] for l in admitted})
        n_feats = len({f for l in admitted for _r, f in lexemes[l]["forms"]})

        # Residual for BPE: everything the prefix, verb and noun layers do not reach.
        residual: dict[str, int] = {}
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
            residual[s] = residual.get(s, 0) + f

        chars = sorted({c for w in residual for c in w}) or ["א"]
        verb_lemmas = {v[0] for v in verbs.values()}
        verb_feats = {v[1] for v in verbs.values()}
        fixed = (len(PREFIXES) + len(verb_lemmas) + len(verb_feats)
                 + n_lemmas + n_feats + len(chars))
        merges = max(0, BUDGET - fixed)
        print(f"\n--- N={n_slots:,}: fixed {fixed:,} "
              f"(noun lemma {n_lemmas:,}, noun feat {n_feats}), BPE merges {merges:,}", flush=True)
        if merges == 0:
            print("    BPE gets zero slots; the budget is exhausted by given units alone.")

        learned = train_bpe(residual, merges)
        ranks = {p: i for i, p in enumerate(learned)}
        seg = G2Segmenter(verbs, nouns, ranks, lex)
        total_vocab = fixed + len(learned)

        uncovered = sum(1 for w in lex_list if not seg.segment(w))
        import gzip
        raw = gzip.decompress(
            open(os.path.join(ROOT, "lexicon/eval/he_conversational_test.txt.gz"), "rb").read())
        conv = [t for line in raw.decode("utf-8").split("\n") if line.strip()
                for t in line.split(" ")]
        uncovered += sum(1 for t in conv if not seg.segment(t))
        mean_units = sum(len(seg.segment(t)) for t in conv) / len(conv)

        # --- the families ---
        def sample_pairs(kind):
            out, tries = [], 0
            if kind == "nounadj":
                groups = [[f[0] for f in lexemes[l]["forms"] if f[0] in lex]
                          for l in admitted]
                groups = [g for g in groups if len(g) > 1]
                while groups and len(out) < args.pairs:
                    g = groups[rng.randrange(len(groups))]
                    a, b = rng.sample(g, 2)
                    out.append((a, b))
            elif kind == "ktiv":
                while len(out) < args.pairs and tries < args.pairs * 400:
                    tries += 1
                    w = lex_list[rng.randrange(len(lex_list))]
                    idx = [i for i, c in enumerate(w) if c in "וי"]
                    if not idx:
                        continue
                    i = idx[rng.randrange(len(idx))]
                    v = w[:i] + w[i + 1:]
                    if len(v) >= 2 and v in lex:
                        out.append((w, v))
            else:
                out = [(lex_list[rng.randrange(len(lex_list))],
                        lex_list[rng.randrange(len(lex_list))]) for _ in range(args.pairs)]
            return out

        res = {}
        for fam in ("nounadj", "ktiv", "control"):
            pairs = sample_pairs(fam)
            if not pairs:
                res[fam] = {"jaccard": 0.0, "n": 0, "ideal": None}
                continue
            js = [jaccard(seg.segment(a), seg.segment(b)) for a, b in pairs]
            sc = [size_ceiling(seg.segment(a), seg.segment(b)) for a, b in pairs]
            ideals = []
            if fam != "ktiv":
                for a, b in pairs:
                    ia = ideal_units_g2(a, verbs, nouns, lex)
                    ib = ideal_units_g2(b, verbs, nouns, lex)
                    if ia is not None and ib is not None:
                        ideals.append(jaccard(ia, ib))
            res[fam] = {
                "jaccard": sum(js) / len(js), "n": len(pairs),
                "size_ceiling": sum(sc) / len(sc),
                "ideal": (sum(ideals) / len(ideals)) if ideals else None,
                "ground_truth": len(ideals),
            }

        row = {"slots": n_slots, "vocab": total_vocab, "uncovered": uncovered,
               "mean_units": mean_units, "noun_lemmas": n_lemmas, "merges": len(learned), **res}
        rows.append(row)
        if n_slots == 0:
            baseline = row
        na, kt, ct = res["nounadj"], res["ktiv"], res["control"]
        print(f"    vocab {total_vocab:,}  uncovered {uncovered}  units/token {mean_units:.3f}")
        print(f"    noun/adj J {na['jaccard']:.4f} ideal "
              f"{('%.4f' % na['ideal']) if na['ideal'] else 'n/a':>6} "
              f"J/ideal {('%.2f' % (na['jaccard'] / na['ideal'])) if na['ideal'] else 'n/a'}  "
              f"n={na['n']:,}")
        print(f"    ktiv     J {kt['jaccard']:.4f}   control J {ct['jaccard']:.4f}   "
              f"ratio {kt['jaccard'] / ct['jaccard'] if ct['jaccard'] else 0:.1f}x")

    # --- the rule ---
    print()
    print("=" * 100)
    print("AGAINST THE PRE-REGISTERED RULE  (every bar against N=0, re-measured in this run)")
    print(f"  G1 baseline in this run: units/token {baseline['mean_units']:.3f}, "
          f"ktiv J {baseline['ktiv']['jaccard']:.4f}")
    print()
    print(f"{'N':>7} {'vocab':>8} {'uncov':>6} {'units/tok':>10} {'noun J/ideal':>13} "
          f"{'ktiv J':>8} {'vs G1':>8}  adopts?")
    any_adopt = False
    for r in rows:
        na, kt = r["nounadj"], r["ktiv"]
        ji = (na["jaccard"] / na["ideal"]) if na["ideal"] else 0.0
        d_ktiv = kt["jaccard"] - baseline["ktiv"]["jaccard"]
        ok = (r["uncovered"] == 0 and r["vocab"] <= BUDGET
              and r["mean_units"] <= baseline["mean_units"] + 1e-9
              and ji >= 0.80 and kt["jaccard"] >= baseline["ktiv"]["jaccard"] - 1e-9)
        if ok and r["slots"] > 0:
            any_adopt = True
        print(f"{r['slots']:>7,} {r['vocab']:>8,} {r['uncovered']:>6} {r['mean_units']:>10.3f} "
              f"{ji:>13.2f} {kt['jaccard']:>8.4f} {d_ktiv:>+8.4f}  "
              f"{'YES' if ok and r['slots'] > 0 else 'no'}")
    print()
    print(f"  VERDICT: {'a configuration meets every clause' if any_adopt else 'NO configuration meets every clause — NOTHING IS ADOPTED'}")
    print("=" * 100)

    out = os.path.join(ROOT, "lexicon/experimental/segmentation_g2.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"budget": BUDGET, "rows": rows}, open(out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

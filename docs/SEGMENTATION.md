# G1 — a segmentation that fits the budget without losing the language

**Prediction and stopping rule recorded before a line of the builder exists.**

## Where this comes from

`K1` measured that 355,587 surface forms cannot be embedded in any budget this project has, and
that ~16,384 units fit in the 576,837 free asset bytes today. `H1` measured that 42.81% of
conversational tokens carry a prefix chain and that nearly all of them are **also** stored as
their own surface form — the lexicon is largely an enumeration of what a rule would generate.

So the question is not whether to segment. It is whether a segmentation that fits the budget
still lets the keyboard recognise **the forms of one word as one word** — which under the
purpose fixed on 2026-08-25, *typing Hebrew without fighting the language*, is the whole point.
A segmentation that buys bytes and loses that has bought nothing.

## A licensed morphological table is already in this build, and the builder throws it away

`lexicon/MANIFEST.json` records source A as `InflectedVerbsExtended.csv`, CC BY 4.0, Eran Tomer
via NNLP-IL. `build_lexicon.py` reads **one** of its five columns — `vocalized_inflection` —
and discards the rest. Measured on the fetched file:

| column | what it carries |
|---|---|
| `vocalized_inflection` | the surface form — **the only one kept** |
| `base_form` | **the lemma** |
| `morphology` | the full feature bundle, e.g. `PAST+FIRST+MF+SINGULAR+COMPLETE` |
| `pattern` | the binyan class |
| `table_number` | the inflection table |

| | |
|---|---|
| rows | 241,798 |
| distinct surface forms | 102,564 |
| distinct lemmas | **3,520** |
| distinct feature bundles | **86** |
| patterns | 7 |
| mean forms per lemma | 29.1 |
| **of the shipped 355,587-form lexicon, present in this table** | **102,239 — 28.75%** |

**Those 102,239 surface forms are expressible as 3,520 + 86 = 3,606 units. A 28.4× collapse,
with no approximation and no learning — the mapping is given.**

This is the same shape as every other finding this week: the enumeration was kept and the
structure that generated it was thrown away.

## The design, fixed now

Four layers, most specific first. Every layer but the last is **given**, not learned.

1. **Prefix layer — morphology, closed.** The 79 strings of `PrefixStripper.ALL`, the W×S×P×H
   grammar the spec defines. Split when the remainder is coverable. 79 units.
2. **Verb layer — morphology, given.** A surface form in source A becomes `lemma + features`.
   3,520 + 86 = 3,606 units.
3. **Subword layer — learned, and not morphology.** BPE over everything the first two do not
   reach, trained on the lexicon weighted by the shipped frequency byte
   (`round(log2(count+1)*8)`, decoded back).
4. **Character fallback.** So coverage is 100% **by construction**, and asserted rather than
   claimed.

### What may and may not be called morphology

Layers 1 and 2 are morphology: a closed grammar and a licensed lemma table. **Layer 3 is not.**
BPE units are frequent substrings and calling them morphemes would be a claim wider than the
evidence. Nouns, adjectives and particles — the other 71.25% — get **no morphological treatment
at all here**, because no licensed table for them is in this build. Whether one should be
fetched is a separate question with its own licence work.

## The measurement that decides it

Coverage and compression are necessary and easy. The one that can fail is **form sharing**: given
two spellings or two inflections of the same word, how much of their unit sequence is shared —
Jaccard over unit sets — against a control of random in-lexicon pairs measured in the same run.

Three families, all derivable from what is already here:

| family | pairs | why |
|---|---|---|
| verb | two inflections of one `base_form` | layer 2 should make this near-total **by construction** — a wiring check, not a finding |
| prefix | `w` and `prefix+w`, both in the lexicon | layer 1, same |
| **ktiv** | `w` and `w` with one `ו`/`י` removed, both in the lexicon | **the real test.** The letter sits *inside* the stem, so only layer 3 can absorb it |
| control | random in-lexicon pairs | the floor everything is measured against |

`H1` measured that **50.78%** of conversational tokens have a ktiv-family neighbour. If the
segmentation does not connect them, half of Hebrew stays two unrelated things to the model.

## The prediction, written now

1. Total vocabulary lands **at or under 16,384**, with BPE taking roughly 12,600 of it.
2. Mean units per token on held-out conversational text: **2.0–3.0**.
3. Verb-pair sharing **≥ 0.90**. Near-tautological; recorded so that a wiring fault is visible
   rather than flattering.
4. Prefix-pair sharing **≥ 0.90**, for the same reason.
5. **Ktiv-pair sharing lands in 0.35–0.60** — clearly above the control, clearly below the two
   given layers. This is the number I am least sure of and the one worth running for.
6. Control below **0.15**.

If ktiv sharing comes back **above 0.80**, BPE has done something I did not expect and the
result gets checked before it is believed.

## The stopping rule

The segmentation is carried forward only if, in one run, all of:

| clause | bar |
|---|---|
| coverage | **exactly 100%**, asserted over both held-out slices and the whole lexicon |
| budget | total vocabulary **≤ 16,384** units, so `K1` shape B still fits |
| compression | mean units per token on held-out conversational text **≤ 3.0** |
| **the language** | **ktiv-pair sharing ≥ 3× the random control, measured in the same run** |

If the last clause fails, the segmentation **bought bytes and not the language**, and it is
recorded as that rather than carried forward. The bar is written against the control measured in
the same run and never against a remembered constant — the flaw `S1` exposed here, not repeated.

## What this does NOT cover

- **Accuracy.** No model is trained and none is implied. Nothing here says a model over these
  units would be any good.
- **Nouns and adjectives.** 71.25% of the lexicon gets subwords, not morphology.
- **Register.** The corpora are the same Hebrew-letters-only slices `H1` found blind to script
  mixing and gershayim.
- **Whether the discarded columns should change the shipped lexicon.** This reads source A for a
  segmentation; it does not propose rebuilding the lexicon, which is `GATE-LEX-1`'s subject and
  a separate decision.

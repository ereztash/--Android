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

---

## G1 RESULT — all four clauses pass, and my own sanity check says read them carefully

One run, `python3 scripts/build_segmentation.py`, every bar fixed beforehand.

| | |
|---|---|
| residual types the BPE layer must cover | **88,581** |
| given units | 79 prefix + 3,516 lemma + 57 feature + 27 char = **3,679** |
| learned merges | 12,705 |
| **total vocabulary** | **16,384** |

| clause | bar | measured | |
|---|---|---|---|
| coverage | exactly 100% | **0 uncovered of 481,861** | **pass** |
| budget | ≤ 16,384 units | 16,384 | **pass** |
| compression | ≤ 3.0 units per token | **1.809** | **pass** |
| the language | ktiv sharing ≥ 3× control, same run | 0.3186 / 0.0093 = **34.20×** | **pass** |

## But the wiring check fired, and it was pointing at me

The pre-registration says of the verb family: *"≥ 0.90. Near-tautological; recorded so that a
wiring fault is visible rather than flattering."* It came back at **0.2459**, and the honest
first move is to treat that as the check doing its job rather than as a curiosity.

It was. The fault is in the prediction, and it is arithmetic:

> Two inflections of one lemma both segment to **two units**, `[L:lemma, F:features]`. They
> share `L` and differ in `F`. Jaccard over unit **sets** is therefore
> `|{L}| / |{L, Fa, Fb}|` = **1/3 = 0.3333**, and no correct implementation can exceed it.

**I predicted 0.90 against a structural ceiling of 0.33.** The same error, in the same
direction, in the prefix family: a stem that segments to one unit gives
`|{S}| / |{P, S}|` = **0.5000**, and the measurement is **0.4962** — the prefix layer is
sitting essentially *on* its ceiling while my prediction said it would score twice it.

**The clause that decided is unaffected**, because it was written as a ratio to the control
measured in the same run rather than as an absolute. That was luck as much as judgement, and it
is worth saying which.

### The gap is closed, and closing it corrected me a second time

The harness now computes two ceilings per family and the run was repeated. **Every previously
published figure came back identical** — 0.2459, 0.4962, 0.3186, 0.0093, 1.809, 16,384, zero
uncovered — so the addition changed the reporting and not the measurement.

| family | n | mean J | size ceiling | J / size | **design ideal** | **J / ideal** | pairs with ground truth |
|---|---|---|---|---|---|---|---|
| verb | 5,000 | 0.2459 | 0.9004 | 0.27 | **0.2787** | **0.88** | 5,000 of 5,000 |
| prefix | 5,000 | 0.4962 | 0.7285 | 0.68 | **0.5934** | **0.84** | 2,134 of 5,000 |
| ktiv | 5,000 | 0.3186 | 0.8815 | **0.36** | n/a | n/a | 2,762 of 5,000 |
| control | 5,000 | 0.0093 | 0.7881 | 0.01 | 0.0162 | 0.57 | 1,112 of 5,000 |

- **size ceiling** — `min(|A|,|B|) / max(|A|,|B|)`, the most any two sets of those sizes could
  reach. Exact and family-agnostic.
- **design ideal** — what a *perfect* segmentation scores for this family, computed by
  decomposing each side with the licensed table rather than by assuming. Pairs the table cannot
  analyse are counted out rather than folded in at a guess.

**The ceilings I derived by hand, one section above, were wrong in both directions.** I wrote
0.3333 for verbs and 0.5000 for prefixes; measured against the actual pairs they are **0.2787**
and **0.5934**. Verbs come in *under* my figure because a perfect decomposition sometimes
carries a prefix unit too — `[P,L,F]` against `[L,F]` is 1/4, not 1/3 — and prefixes come in
*over* it because a stem often decomposes to two ideal units rather than one, giving 2/3.

So the corrected reading is **0.88 of ideal for verbs and 0.84 for prefixes**, not the 73.8% and
99% written above from arithmetic I did in my head. That arithmetic is left in place rather than
edited away, because the point of this section is that it was wrong.

### And the honest reading of the ktiv number is less flattering than 34×

The pre-registered clause compares ktiv sharing to the control, and at **34.20×** it passes
comfortably. Against the *sizes* of the two segmentations, though, ktiv reaches
**0.36 of what those sizes would allow** — the weakest of the three families. Both statements
are true. The first is the one the rule asked for; the second is the one that says what the
subword layer is actually doing for ktiv variants, which is **connecting them weakly**.

That is the number to carry forward, and `H1`'s warning stays attached to it: this family's
generator admits pairs that are two different words, where high sharing would be a defect.

## Where the prediction was wrong

Six were recorded. **Four are wrong.**

| # | predicted | measured | |
|---|---|---|---|
| 1 | vocabulary at or under 16,384, BPE ~12,600 | 16,384 with 12,705 merges | ✓ |
| 2 | 2.0–3.0 units per token | **1.809** — below the range | ✗ better than predicted |
| 3 | verb sharing ≥ 0.90 | 0.2459, against a ceiling of 0.3333 | ✗ **impossible as written** |
| 4 | prefix sharing ≥ 0.90 | 0.4962, against a ceiling of 0.5000 | ✗ **impossible as written** |
| 5 | ktiv sharing 0.35–0.60 | **0.3186** — just under | ✗ |
| 6 | control below 0.15 | 0.0093 | ✓ |

## Three findings the clauses do not carry

**1. The given layers absorb far more than I assumed.** The BPE layer had to cover **88,581**
residual types, not the ~250,000 I sized the run for. The closed prefix grammar and the licensed
verb table between them account for most of the lexicon's bulk before a single merge is learned.
That is the enumeration finding from `H1`, now quantified from the other side.

**2. Compression is better than predicted: 1.809 units per token.** A recurrent model over this
segmentation sees sequences under two units long on average, which is the quantity `K1` said
latency is set by.

**3. Ambiguity is being discarded, and this is the first place it is written down.** Source A
carries **86** distinct feature bundles; the segmenter reaches **57**. The map from a surface
form to an analysis is **many-to-one** — Hebrew verb forms are systematically ambiguous — and
`load_verbs()` keeps the **first** analysis it sees and drops the rest. For a keyboard that may
be the right trade or the wrong one; what it is not is a decision anybody made. It is
`build_lexicon.py`'s "read one column" one level down.

## What this still does not license

No model exists. No accuracy claim exists. The 71.25% of the lexicon that is not verbs still
receives subwords rather than morphology, the corpora are still the Hebrew-letters-only slices
`H1` found blind to script mixing, and nothing in the shipped app reads any of this.

---

# G2 — the noun and adjective layer. Prediction recorded before the dump is fetched.

`G1` left **71.25% of the lexicon on BPE**, because no licensed noun or adjective table was in
this build. One exists, and it is better licensed than anything here already.

## The source

**Wikidata Lexemes.** Wikidata's copyright page states verbatim: *"All structured data from the
main, Property, **Lexeme**, and EntitySchema namespaces is available under the Creative Commons
CC0 License."* CC0 requires **no attribution** — the first source in this project with no
downstream obligation at all, against source A's CC BY 4.0 and source B's CC BY-SA 4.0. It is
attributed anyway, in `docs/LICENSES.md`, because that is what this repository does.

Coverage, queried live from the Wikidata Query Service on 2026-08-25:

| lexical category | lexemes | forms |
|---|---|---|
| **noun** | **19,389** | **228,499** |
| **adjective** | **4,279** | **33,655** |
| verb | 4,709 | 167,673 |
| proper noun | 894 | 85 |

Fetched as a **dated** dump, not `latest`, so it is byte-stable and pinnable the way
`GATE-LEX-2` pins the other two:
`wikidata-20260819-lexemes.json.gz`, **604,534,688 bytes**, Last-Modified 2026-08-19.

Fetched 2026-08-25. The byte count came back **exactly** as the pre-registration recorded it,
and the digest is

```
7f3ef4fd45296f63e2facfbfbb6af5c5395e75d073100c6eb5fa9829f783df1c  wikidata-20260819-lexemes.json.gz
```

## This is not "add the table". The budget will not have it.

| | units |
|---|---|
| `G1` fixed units today (79 prefix + 3,516 lemma + 57 feature + 27 char) | 3,679 |
| noun + adjective lemmas, all of them | 23,668 |
| **total if all are admitted** | **27,347** |
| budget, `K1` shape B | **16,384** |
| **over by** | **10,963**, before one BPE unit or one noun feature |

So the experiment is not whether the table helps. It is **which lexemes earn a slot**, inside a
fixed 16,384 — exactly the constraint `K1` named: *you buy vocabulary with bytes and never with
milliseconds.* Lexemes are ranked by the shipped frequency of their forms, the top **N** are
admitted, and BPE fills whatever is left so the total is 16,384 in every configuration.

`N` is swept over **0 (the G1 baseline), 2,000, 4,000, 8,000, 12,000**. Fixed now; no sixth
value after seeing a result.

## The prediction, written now

Point 1 is arithmetic and is **established, not predicted**: no configuration admits all 23,668.

2. **Noun/adjective pair sharing reaches verb-level — ≥ 0.80 of its design ideal — at N ≥ 4,000.**
   The verb layer reaches 0.88 and there is no reason this layer should differ in kind.
3. **Compression improves.** Mean units per token falls below `G1`'s **1.809**, because a noun
   form becomes `[L, F]` instead of two or three BPE pieces.
4. **Ktiv sharing FALLS as N rises**, by **0.02–0.08 at N = 8,000**. BPE units are what currently
   connect `tochnit`/`tochniyot`, and admitting lemma units displaces them. This is the
   prediction that can kill the layer, and it is the one worth running for.
5. The best configuration is at an **intermediate N**, not at either end.

If ktiv sharing *rises* with N, Wikidata is listing ktiv variants as forms of one lexeme, which
would be a larger win than anything predicted here and gets checked before it is believed.

## The stopping rule

The layer is adopted only if, at some `N` in the grid, all of:

| clause | bar |
|---|---|
| coverage | exactly **100%**, asserted |
| budget | total vocabulary **≤ 16,384** |
| compression | mean units per token **≤ G1's, measured in the same run** |
| the new layer works | noun/adjective sharing **≥ 0.80 of its design ideal** |
| **the purpose is not traded away** | **ktiv sharing ≥ G1's, measured in the same run** |

The last clause is the guard. Under purpose ג, connecting the two spellings of one word is the
point; a layer that serves nouns by breaking that is a **trade to report, not a change to make**.
Both comparison bars are against `G1` re-measured in the same run, never against the published
1.809 and 0.3186 — the flaw `S1` exposed, not repeated.

## What this does NOT cover

- **Accuracy.** Still no model, still no claim that any of this predicts a word.
- **Proper nouns, adverbs, prepositions.** 894 + 114 + 60 lexemes, left on BPE.
- **Whether Wikidata's Hebrew coverage is any good.** 19,389 noun lexemes is a count, not a
  quality measure, and no one here has looked at a sample.
- **The shipped lexicon.** This reads a dump for a segmentation; it does not propose rebuilding
  `he_lexicon.txt.gz`, which is `GATE-LEX-1`'s subject and a separate decision.

## G2 RESULT — the layer works perfectly and is NOT ADOPTED

One run, five slot counts, every bar against `N = 0` re-measured in the same run. `N = 0` is
`G1`, and it reproduced: **1.809 units per token**, ktiv **0.3207** against the 0.3186 published
— the difference is the sampling seed reaching a different 5,000 pairs, not a change.

| N | vocab | uncovered | units/token | noun/adj J | its ideal | **J / ideal** | ktiv J | vs G1 |
|---|---|---|---|---|---|---|---|---|
| **0** | 16,384 | 0 | **1.809** | — | — | — | **0.3207** | — |
| 2,000 | 16,384 | 0 | 2.060 | 0.3197 | 0.3206 | **1.00** | 0.3162 | −0.0045 |
| 4,000 | 16,384 | 0 | 2.080 | 0.3561 | 0.3591 | **0.99** | 0.3237 | +0.0030 |
| 8,000 | 16,384 | 0 | 2.100 | 0.4040 | 0.4092 | **0.99** | 0.3196 | −0.0011 |
| 12,000 | 16,384 | 0 | 2.168 | 0.4615 | 0.4666 | **0.99** | 0.3326 | +0.0118 |

### Which clause killed it

Not the one I built the guard for.

| clause | bar | result |
|---|---|---|
| coverage | exactly 100% | passes everywhere |
| budget | ≤ 16,384 | passes everywhere, by construction |
| the new layer works | noun/adj ≥ 0.80 of its design ideal | **1.00 at N=2,000**, 0.99 after — it sits *on* its ceiling |
| the purpose is not traded away | ktiv ≥ G1's | passes at N=4,000 and N=12,000 |
| **compression** | **units/token ≤ G1's** | **fails at every N.** 1.809 → 2.060–2.168 |

**NOTHING IS ADOPTED**, on a clause I predicted would be the easy one.

### Where the prediction was wrong

Five points were recorded. **Three are wrong**, one is right, one was arithmetic.

| # | recorded | measured | |
|---|---|---|---|
| 1 | no configuration admits all of them | at N=12,000 BPE is down to **915** merges | established, not predicted |
| 2 | noun/adj sharing ≥ 0.80 of ideal at N ≥ 4,000 | **1.00 at N=2,000** | ✓, and earlier than predicted |
| 3 | compression **improves**, below 1.809 | **rises at every N** | ✗ — and this is what killed it |
| 4 | ktiv **falls** 0.02–0.08 at N=8,000 | **−0.0011**; the whole range is ±0.012 and trends *up* | ✗ |
| 5 | the best configuration is at an intermediate N | both metrics are monotone; there is no interior optimum | ✗ |

Point 4 is the instructive failure. I wrote *"this is the prediction that can kill the layer, and
it is the one worth running for"*, and built the fifth clause as its guard. **The guard was aimed
at a risk that does not exist.** Displacing 11,790 BPE merges did not break the connection
between two spellings of one word. What it broke was something I had predicted would improve.

### Why compression gets worse, which is the finding

A noun form under this layer becomes `[N:lemma, G:features]` — **two units, always**. BPE with
12,705 merges had already memorised the frequent ones as **one unit each**. So a correct
morphological analysis is *longer* than a memorised whole word, and swapping 11,790 merges for
11,664 lemmas trades short encodings of common words for exact analyses of them.

The layer is not failing at its job. Its `J / ideal` is **0.99–1.00**: it reaches its ceiling
essentially perfectly. The ceiling is simply **low** — two forms of one noun share the lemma and
must differ in the features, the same structural cap the verb family has — while BPE's whole-word
units cost one slot and one token.

**Given morphology is not automatically better than memorised subwords at this budget.** That is
the same shape as `D1`'s finding that *a table covers the head*, arriving from the opposite
direction: at 16,384 slots, memorisation wins the head of the distribution and analysis only pays
in the tail.

### The trade, named and left to the operator

The bar that failed is one I wrote, and it is strict: *no worse compression at all*. A different
bar — say, ≤ 5% worse — would adopt at N=12,000, which buys noun/adjective sharing of 0.4615
against nothing, and a ktiv gain of +0.0118, for 0.359 extra units per token.

**That bar is not being relaxed here.** `S1` is in this repository precisely because a rule
loosened after seeing the result is not a rule. The trade is reported with its numbers and the
decision is the operator's.

### What this does NOT establish

- **Not that the table is bad.** It is CC0, it is correct, and the layer built on it hits its
  own ceiling. What failed is a budget trade, not the data.
- **Nothing about accuracy.** Still no model, still no claim that any of this predicts a word.
- **Nothing about a larger budget.** Every configuration here totals 16,384 because `K1` shape B
  does. At a budget where BPE keeps its merges *and* the lemmas fit, this trade does not arise —
  and that budget has not been measured.

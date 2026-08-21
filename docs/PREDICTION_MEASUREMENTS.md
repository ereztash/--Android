# 

> **Updated by R1.** Every number below was re-measured after the training corpus changed to a
> 25% conversational blend. The previous figures described a model trained entirely on Hebrew
> Wikipedia and no longer describe what ships. See [`CORPUS_REGISTER.md`](CORPUS_REGISTER.md)
> for why the corpus changed and what it was worth.
Prediction measurements (M10)

Every number here is a claim about **exactly one thing**: this engine, on this corpus, with
this lexicon and this bigram table. Corpus hashes sit beside the tables. Latency figures are
**JVM numbers on the build host** and are never quoted as device numbers.

## What was added, and what it cost

| top-3 accuracy | context-free ranking | shipped engine |
|---|---|---|
| prefix 1 | 2.15% | **5.43%** |
| prefix 2 | 14.80% | **24.92%** |
| prefix 3 | 36.58% | **47.98%** |
| next-word | 0.00% — structurally impossible without context | **9.09%** |

The left column is control 1 below: the same lexicon and the same prefix constraint, ranked by
unigram frequency alone. It is a measured floor, not a hypothetical. It is **not** "the app
before M10" — before M10 the app offered no completions and no next-word at all, only
corrections for strings of 3+ characters that were not words. That prior behaviour was not
re-measured for this table and no number here is presented as it.

The cost, measured on the artifact:

| | bytes |
|---|---|
| release APK, when M10 measured it | 5,161,766 |
| of which the bigram table | 1,849,636 (36% of the APK, 61% of all assets) |
| budget headroom left for assets, then | 576,837 |

`GATE-SIZE-1` now holds that trade in place: assets may grow before the budget has to be
re-argued. The pre-M10 APK size was not re-measured, so no "grew by" figure is quoted — the
bigram table's 1,849,636 bytes is what is actually known.

**Two things have spent that headroom since**, and the current figures are what `GATE-SIZE-1`
reports today rather than what M10 recorded:

| | bytes |
|---|---|
| release APK, now | 5,457,116 of 6,500,000 |
| assets in the artifact | 3,250,074 of 3,600,000 |
| of which the bigram table (R1's 25% blend) | 1,682,421 |
| of which the distance-2 table (S1) | 387,300 |
| budget headroom left for assets | 349,926 |

The distance-2 table took 52% of the headroom that existed before it, for +0.11 points of
real-word-error recall. That trade is argued — and argued against — in
[`CONFUSION_MEASUREMENTS.md`](CONFUSION_MEASUREMENTS.md).

---

## The corpus, and why it is the only kind that counts here

A language model evaluated on its own training data reports how well it memorised, not how
well it predicts, and the number looks excellent either way. So the evaluation corpus is
sampled from a **different phase of the same grid** over the Wikipedia multistream dump:
training chunks at `(i + 0.5) / n` of the file, evaluation chunks at `(i + 0.0) / n`.

`scripts/build_eval_corpus.py` then **proves** the byte ranges are disjoint and refuses to
write anything if they are not. The separation is asserted against the actual ranges, not
inferred from the arithmetic that produced them.

| | value |
|---|---|
| sha256 (uncompressed) | `a1c14bb99932d38d8426b9455ca72385201db9ce2e197dca4b72cd82c55568da` |
| sentences | 799,319 |
| tokens | 10,660,060 |
| eval byte ranges | 6 |
| training byte ranges | 10 |
| **intersections** | **0**, proven, or nothing would have been written |
| source | hewiki 20260801 multistream, CC BY-SA 4.0 |

### The committed slice

The full corpus is 28 MB and is **not** committed — that would nearly double a 34 MB
repository to make 6,000 sentences reproducible. `scripts/slice_eval_corpus.py` owns the
selection rule and cuts the slice the tests actually read:

| | value |
|---|---|
| rule | sentences of 4–40 tokens, every 37th, first 6,000 |
| eligible in parent | 732,360 of 799,319 |
| sentences / tokens | 6,000 / 85,840 |
| sha256 | `cedfb5be743bc15c2b3db381011e2c74f31f512e9373ed4038f198dcb4b3d299` |
| parent sha256 | `a1c14bb9…`, recorded in `lexicon/eval/MANIFEST.json` |
| size | 262,088 bytes |

Every accuracy figure in this document was produced twice — once against the full corpus and
once against the slice — and came out **byte-identical**. That is the check that the slice is
faithful; it is not assumed from the fact that the same rule was applied.

The cost is stated rather than discovered later: a change to the sampling rule cannot be
evaluated without re-fetching the full corpus. The rule was fixed before the first measurement
and has not moved since.

Target words shorter than 3 characters are skipped. Each cell is capped at **n = 20,000**; the
cap is stated rather than hidden, and `PredictionAccuracyTest` fails if any cell falls under
15,000.

---

## Baseline, measured before any weight was chosen

`bigramWeight = 0.0`, which is the engine ranking completions by unigram frequency alone.

| metric | top-1 | top-3 | n |
|---|---|---|---|
| next-word | 5.55% | 9.09% | 20,000 |
| prefix 1 | 0.73% | 2.15% | 20,000 |
| prefix 2 | 6.71% | 15.80% | 20,000 |
| prefix 3 | 20.04% | 38.27% | 20,000 |

Next-word is unaffected by the weight — that path reads the bigram table directly and has no
unigram score to balance against — so its 9.09% is the same in every row of every table below.
It is listed once here rather than repeated as though it were varying.

**A next-word answer is offered at all in 86.64% of positions.** The other 11.64% are words
the pruned model has no continuation for, and the strip stays empty rather than guessing.

## The weight sweep, in full

Every value tried, including the ones that are worse. Top-3, at the shipped mix.

| bigramWeight | prefix 1 | prefix 2 | prefix 3 | µs/call |
|---|---|---|---|---|
| 0.0 (baseline) | 2.15% | 15.80% | 38.27% | 269 |
| 0.5 | 4.67% | 22.52% | 45.67% | 253 |
| 1.0 | 5.45% | 25.09% | 48.15% | 248 |
| **2.0 — shipped** | **5.43%** | **24.92%** | **47.98%** | 249 |
| 4.0 | 5.77% | 25.92% | 49.49% | 249 |

**2.0 was chosen after this table existed, not before it.** It takes almost all of the
available gain; 4.0 buys a further 0.04, 0.15 and 0.21 points while letting a single Wikipedia
bigram outrank a far commoner word on thin evidence. Latency is flat across the sweep, so it
did not enter the decision.

*µs/call is a JVM figure on the build host, over a mixture of next-word and completion calls.
It is not a device number and is not the input-path latency; that is measured through
`HebrewIme.suggest` by the macrobenchmark and is re-baselined in M12, because M10 changed what
happens inside that trace section.*

## The ordering sweep: a baseline that turned out to be dominated

When the typed string is not in the lexicon it is either an unfinished word or a misspelled
one, and nothing in the string itself says which. The strip has three slots and both readings
want them. The engine's original behaviour — corrections first, because the word is wrong as it
stands — was never measured; M10 measured it.

Each corpus answers only half the question and each has a degenerate winner, so **both are
scored for every policy and the choice is made on the pair**. A policy that never shows a
correction wins the completion corpus outright; one that never completes wins the typo corpus.

| mix | prefix 1 | prefix 2 | prefix 3 | typo top-1 | typo top-3 |
|---|---|---|---|---|---|
| CORRECTIONS_FIRST (baseline) | 5.43% | 24.92% | 42.90% | 52.95% | 66.68% |
| **COMPLETIONS_FIRST — shipped** | 5.43% | 24.92% | **47.98%** | **53.05%** | 67.28% |
| INTERLEAVED | 5.43% | 24.92% | 48.64% | 53.05% | **67.40%** |

Denominators: 20,000 per prefix cell (held-out corpus), 4,000 for the typo columns
(`lexicon/golden/a_uniform.tsv.gz`, sha256 `f9f4ed809b31bef0…`).

**There was no trade-off to make.** The baseline is worse on every column, on both corpora. It
was not a defensible position that lost narrowly; it was a guess that had never been checked.

Against `INTERLEAVED` the choice is closer and is stated as such: interleaving wins typo top-3
by 0.12 points — 5 items out of 4,000 — while completions-first wins prefix-3 by 0.64 points,
128 items out of 20,000. The larger effect on the larger denominator decided it.

Prefix 1 and 2 are identical under every policy because
`CorrectionEngine.Config.minimumLengthToCorrect` is 3: below that length there are no
corrections to order.

A fourth policy was written and deleted. "Completions first unless the string has no
completions at all" scored identically to `COMPLETIONS_FIRST` in every column, and on
inspection it is the same function — `if (finishes.isEmpty()) fixes else finishes + fixes` is
`finishes + fixes`. Keeping it would have meant an enum offering a choice that does not exist.

---

## The controls

### Control 1 — a context-free predictor, scored by the same harness

A prediction engine can look respectable by always offering the commonest words, because the
commonest words are common. This control gets the same prefix constraint the real engine has
and picks by unigram frequency within it, never looking at the previous word.

| | prefix 1 | prefix 2 | prefix 3 | next-word |
|---|---|---|---|---|
| context-free control | 2.15% | 14.80% | 36.58% | **0.00%** |
| real engine | 5.43% | 24.92% | 47.98% | 9.09% |

The gap is what the bigram model is worth. The 0.00% is structural — a context-free predictor
cannot answer the next-word question at all — and `PredictionAccuracyTest` asserts it exactly,
so a non-zero result there would be a bug in the harness rather than a result.

### Control 2 — the engine must not call correct words misspelled

The prediction analogue of corpus C1. Every accuracy number above is worthless if the strip is
simultaneously flagging correct words: a keyboard that marks real Hebrew as wrong is worse than
one that suggests nothing.

| | value |
|---|---|
| in-lexicon words checked | 20,000 |
| offered a CORRECTION | **0** |

### Control 3 — positive control for control 2

Zero false flags is only reassuring if a non-zero rate would be detected. The same counting
loop, run against an engine that flags everything, reports **100.00%**. The zero above is
therefore a measurement and not a property of the measuring code.

---

## Gates added in M10

| gate | what it proves | positive control |
|---|---|---|
| GATE-BIGRAM-1 | the bigram table inside the APK is byte-identical to the one every number here was measured on, and its header agrees with the manifest | one byte appended to the packaged table |
| GATE-SIZE-1 | the release artifact stays inside a budget written down after measuring it | assets measured 50% larger |
| (in `PredictionAccuracyTest`) | accuracy has not regressed below floors set from these numbers | an always-correcting engine, and a context-free predictor |

The accuracy floors are 5.4 / 24.5 / 48.0 / 9.3 for prefix 1, 2, 3 and next-word top-3. Each
sits under its measured value by a stated margin. The prefix-3 floor was **raised** from 42.0
to 48.0 when the ordering sweep improved the number — a floor moved up to lock in a measured
gain. A floor moved *down* to make a suite pass is the conflict the project's rules say to
report to the operator, not an edit to make.

---

## What these numbers are NOT

- **They are not a claim about what a user will experience.** The corpus is Wikipedia prose.
  Phone typing is shorter, more colloquial, full of names and abbreviations Wikipedia does not
  use in the same proportions, and heavily repetitive in ways a general corpus is not. The
  register is wrong and no amount of held-out discipline fixes that.
- **The next-word figure is measured with a known previous word and no punctuation.** In the
  app, `InputContextBuffer.previousWord` returns null across a sentence boundary, so the real
  offer rate at a sentence start is zero where this corpus reports 86.64%. That is deliberate —
  the model was never trained on pairs that straddle a boundary — but it means the app's
  aggregate next-word rate is lower than the table, by an amount not measured here.
- **They say nothing about latency on a device.** Every µs figure is a JVM number on the build
  host. `HebrewIme.suggest` now covers more work than it did in M7, so the M7 latency baseline
  does not describe this code and is re-measured in M12 rather than carried forward.
- **They say nothing about the quality of a correction the user accepts.** Top-3 containment
  is not the same as a suggestion being useful, and neither is measured against real users.
- **Bigrams are the entire context model.** Two words is the whole memory; the engine has no
  notion of a sentence, a topic, or the user's own vocabulary.

## Reproducing

```sh
python3 scripts/build_bigrams.py            # rebuilds the model, refuses to write silently
python3 scripts/build_eval_corpus.py        # refuses to write if it overlaps training data
python3 scripts/slice_eval_corpus.py        # re-cuts the committed slice from it
./gradlew :core:test --tests '*PredictionAccuracyTest*'
./gradlew :core:test --tests '*PredictionMeasurementTest*'
./gradlew :core:test --tests '*OrderingSweepTest*' -PrunWeightSweep=1
python3 scripts/check_size.py
```

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
| release APK, now | **5,069,695** of 6,500,000 |
| assets in the artifact | **2,862,144** of 3,600,000 |
| of which the bigram table (R1's 25% blend) | 1,682,421 — **59% of all assets** |
| of which the distance-2 table (S1) | **0 — withdrawn** |
| budget headroom left for assets | **737,856** |

The distance-2 table took 52% of the headroom for +0.11 points of real-word-error recall, and
has been withdrawn; the headroom came back. What remains is that **the bigram table is 59% of
all shipped assets**, which is what makes how those bytes are allocated inside it the question
worth asking.

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


---

## Re-run against the shipped table, and one number that was in the wrong document

Re-measured with `PredictionMeasurementTest` on the shipped artefacts:

| bigramWeight | next-word top-1 | next-word top-3 | offered |
|---|---|---|---|
| 0.0 – 4.0 | 5.12% | **9.09%** | 86.64% |

| | prefix 1 | prefix 2 | prefix 3 |
|---|---|---|---|
| weight 0.0 (no context) | 2.15% | 15.80% | 38.27% |
| **weight 2.0 (shipped)** | **5.43%** | **24.92%** | **47.98%** |

**`README.md` published 16.33% as the next-word figure. That number is real but belongs to a
different measurement**: it is `docs/LEARNING_MEASUREMENTS.md`'s next-word top-3 **with
adaptive learning switched on**, on the learning slice, n=58,343. Adaptive learning is off by
default and the README says so four rows above the table it appeared in. Corrected to 9.09%.

`RELEASE_READINESS.md`'s M10 row likewise carried pre-R1 completion figures. Corrected.

**The next-word curve is completely flat across bigramWeight.** That is by design, not a bug —
`predictNextWord` reads `continuationsOf` directly and never mixes a unigram score for this
weight to balance against — but it means the parameter is inert on that path, and any attempt
to improve next-word by tuning it will measure nothing.

---

# B1 — the allocation experiment. Prediction recorded before the first table is built.

## The question

The bigram table is **59% of all shipped assets**. Measured on the eval slice, its bytes are
allocated in near-inverse proportion to where prediction succeeds:

| previous word's group | % of positions | next-word top-3 | completion prefix-1 top-3 |
|---|---|---|---|
| 128+ continuations | **31%** | **6.45%** | 8.05% |
| 32–127 | 18% | 12.34% | 6.04% |
| 8–31 | 18% | **13.40%** | 4.22% |
| 1–7 | 19% | 12.10% | 3.84% |
| no group at all | 14% | **0.00%** | 2.19% |

| group size | groups | bytes | share of table |
|---|---|---|---|
| 512+ | **74** | 559,169 | **20.7%** |
| 128–511 | 352 | 408,057 | 15.1% |
| 1–3 | 36,535 | 483,280 | 17.9% |

426 words consume 36% of the table to serve the 31% of positions that score worst, and
`predictNextWord` reads `continuationsOf(limit = 8)` — it never looks past the eighth entry.

## The experiment

Hold the byte budget fixed at the shipped table's 2,697,304 raw bytes. Vary a **per-group cap**
on stored continuations, and lower `--min-count` until the budget refills with new groups.
Measure next-word and completion on the same held-out slice, same harness, same
`bigramWeight = 2.0`.

## The prediction, written now

1. **Next-word top-3 improves, by less than +2.0 points.** Capping at ≥8 costs that path
   nothing, since it reads only the top 8. The gain has to come from converting
   no-group positions — 14% of the total, currently scoring 0.00% — into small-group
   positions, and those score about 12%. 0.14 × 0.12 ≈ 1.7 points is the ceiling on the
   mechanism, and new groups will not cover every uncovered word.
2. **Completion top-3 at prefix 1 degrades, by at least 1.0 point.** It reads
   `logCountOf` for a *specific* pair, so a cap deletes exactly the deep entries the band
   table shows are worth 8.05% against 2.19%.
3. **The net is a trade, not a win.** If both paths improve at a fixed budget, the harness is
   wrong and gets checked before the result is believed.

## The stopping rule

Adopt a capped table only if **next-word top-3 rises by ≥ 1.0 point AND completion top-3 at
every prefix length falls by < 0.5 points**, at equal or smaller byte cost. Anything else is
reported as a trade and left to the operator, because the two paths do not have equal weight
to a user and nothing here has measured which one they would rather have.

`GATE-BIGRAM-1` pins the shipped table to its manifest, so any variant that wins has to be
rebuilt as the shipped artefact and re-hashed rather than swapped in.

## B1 RESULT — the lever is real, and it is tiny. NOT ADOPTED.

Five tables, one harness, one slice, `bigramWeight = 2.0`. Every variant holds at or under the
shipped table's byte budget.

| table | groups | pairs | next-3 | offered | p1-top3 | p2-top3 | p3-top3 | APK bytes vs shipped |
|---|---|---|---|---|---|---|---|---|
| **shipped** (mc5, no cap) | 51,900 | 477,180 | **9.09%** | 86.64% | **5.43%** | **24.92%** | **47.98%** | — |
| cap 64 / mc 5 | 51,900 | 477,180 | 9.09% | 86.64% | 5.14% | 22.57% | 44.43% | **−609,592** |
| cap 64 / mc 4 | 60,823 | 602,868 | 9.21% | 88.08% | 5.22% | 22.86% | 44.73% | −367,221 |
| cap 32 / mc 3 | 78,712 | 932,110 | 9.55% | 90.69% | 5.01% | 22.33% | 44.34% | −85,251 |
| cap 8 / mc 2 | **101,765** | **1,428,037** | **9.70%** | **92.32%** | 4.45% | 20.12% | 42.20% | −327,637 |

### Against the rule fixed beforehand

*Adopt only if next-word top-3 rises by ≥ 1.0 point AND completion top-3 at every prefix falls
by < 0.5.*

**All four variants fail, on both clauses.** Next-word never rises a full point. Completion at
prefixes 2 and 3 always falls by more than 0.5. **Nothing is adopted and the shipped table does
not change.**

### What it establishes

**Doubling the groups and tripling the pairs buys +0.61 points of next-word top-3.** That is the
coverage lever pushed as far as the byte budget allows — 51,900 → 101,765 first-words, 477,180
→ 1,428,037 pairs — and it is worth well under one point.

This is the same conclusion the human labelling reached for the real-word-error layer, now
measured independently on the prediction path: **reallocating bytes inside a count table over
surface forms does not move the numbers. The representation is the ceiling, not the allocation.**

### Where the prediction was wrong

It said completion at prefix 1 would fall **by at least 1.0 point**. It fell 0.29–0.98 and never
reached 1.0. **The real damage is at prefixes 2 and 3** — down 2.3 to 5.8 points — which the
prediction did not name. Direction right, location wrong.

### Two findings worth more than the experiment's own question

**1. The offer rate moves where accuracy does not.** 86.64% → **92.32%**, +5.68 points: far more
positions get *some* next-word suggestion. That was not in the stopping rule, it is user-visible,
and a strip that is empty a seventh of the time is a different product from one that is empty a
thirteenth of the time. It does not override the rule, and it is on the table for a rule of its
own.

**2. `cap 64 / mc 5` costs next-word exactly nothing and saves 609,592 APK bytes.** Groups,
next-word top-1, top-3 and offer rate are all *identical* to the shipped table, to the digit —
which independently confirms the mechanism, since `predictNextWord` never reads past the eighth
continuation. The whole cost lands on completion: −0.29 / −2.35 / −3.55. **If bytes ever become
the binding constraint, that is the measured lever**, and it is a product decision rather than a
tuning one.

### Reproducing

```sh
python3 scripts/build_bigrams.py --subtitle-weight 0.25 --min-count 5 --per-group-cap 64 \
  --out lexicon/experimental/he_bigrams_cap64_mc5.bin.gz
python3 scripts/build_bigrams.py --subtitle-weight 0.25 --min-count 4 --per-group-cap 64 \
  --out lexicon/experimental/he_bigrams_cap64_mc4.bin.gz
python3 scripts/build_bigrams.py --subtitle-weight 0.25 --min-count 3 --per-group-cap 32 \
  --out lexicon/experimental/he_bigrams_cap32_mc3.bin.gz
python3 scripts/build_bigrams.py --subtitle-weight 0.25 --min-count 2 --per-group-cap 8 \
  --out lexicon/experimental/he_bigrams_cap8_mc2.bin.gz
./gradlew :core:test --tests '*AllocationExperimentTest*' -PrunConfusionSweep=1
```

The variant binaries are ~5 MB and are **not committed**; their manifests are, carrying the
hashes and sizes above. `AllocationExperimentTest` skips any variant that is not on disk and
says which.

---

# F1 — the typeface, chosen by measurement

## What was there before

**Nothing in `app/src/main` set a `Typeface`.** Key labels, the preview bubble and the candidate
strip all rendered in whatever the platform resolved for Hebrew, at the same `0.42` size
fraction in all three places. It was the one visual decision nobody had made, on the smallest
and most-glanced text in the product.

## What was measured

All 351 unordered pairs of the 27 Hebrew letters, rendered at the label's **real pixel size** —
`KeyboardView` uses 0.32 of screen height over four rows at a 0.42 label fraction, which is
**54–105 px** on phones from 720p to 1440p — and compared by maximum intersection-over-union
under a ±2 px shift. The shift matters: two glyphs can be near-identical and merely offset.

**The risky pairs are discovered, not asserted.** No list of "letters that look alike" appears
anywhere in `scripts/build_keyboard_font.py`.

| face | mean IoU @81px | at-risk pairs (IoU ≥ 0.70) @54 / 81 / 105 px |
|---|---|---|
| **Noto Sans Hebrew** | 0.2995 | **12 / 10 / 11** |
| Assistant | **0.2694** | 13 / 12 / 9 |
| Heebo | 0.3104 | 15 / 14 / 13 |
| Rubik | 0.3559 | 23 / 22 / 18 |

Ranked by at-risk pairs then mean, which is the order the script sorted by before any number
existed: **Noto Sans Hebrew**. Assistant is better on mean at every size and within one pair on
the primary criterion — the two are close and Rubik is decisively last. Shipped subsetted to
Hebrew, Latin, digits and punctuation at weight 500: **16,480 bytes, 10,713 in the APK.**

## The finding worth more than the choice

The at-risk pairs at the shipped size are:

> **ה/ח · ח/ת · ט/ס · ג/נ · ח/ם · ם/ס · ב/כ · ד/ר · ט/ם · מ/ס**

The confusion set the corrector actually searches is:

> **א/ע · ח/כ · כ/ק · ת/ט · ב/ו · ס/ש**

**The two sets share no pair at all.** The letters that *sound* alike and the letters that *look*
alike are disjoint in Hebrew. Every correction feature this project has built addresses the
first set; the second was unaddressed until the font was chosen, and it is the one that governs
whether a finger lands on the right key.

That is a hypothesis about typing errors, not a measurement of them. It predicts that visually
confusable pairs should be over-represented in real typos — which is testable against a corpus
of authentic errors, and is a second reason to want the one this project still does not have.

## What this does NOT establish

- **Not what Android rendered before.** There is no Android in the build container and the
  platform's Hebrew fallback could not be rendered, so the baseline row does not exist. The
  claim is that the shipped face ranks best among four candidates, not that it beats what
  users saw yesterday.
- **Not legibility.** Ink overlap is a proxy for confusability, not a reading test with human
  subjects.
- **Not on a device.** `GATE-FONT-1` proves the measured bytes are in the APK. Whether the
  result is visible to a person is `M4-DEVICE`, still NOT RUN.

---

# O1 — the offer policy. Prediction recorded before anything is measured.

## The question

Every number above is about **ranking**: given that the strip speaks, is the right word in it?
Nothing here has ever measured the other half — **whether it should speak at all**.

The shipped policy speaks whenever the pruned table has any continuation for the previous word.
That is **86.64%** of positions, and `cap64/mc5` in B1 raised it to 92.32%, which was recorded
as a gain. From a user's side it is not obviously a gain, and the arithmetic is unflattering:

| | |
|---|---|
| next-word top-3, over all positions | **9.09%** |
| positions where the strip speaks | **86.64%** |
| **top-3 among the positions where it speaks** | **10.49%** |

A strip that speaks almost always and is right about one time in ten teaches a user, within a
day or two, to stop looking at it. Once they stop looking, the 10.49% is worth nothing at all —
the feature's value is not its accuracy but its accuracy **times** whether anyone is still
reading it.

B1 established that the ranking cannot be bought with bytes: *the representation is the
ceiling, not the allocation*. This asks whether the **offer decision** is a lever the ranking
is not.

## The experiment

The engine already computes a number that is a direct measure of evidence: a `NEXT_WORD`
prediction's `score` is the bigram table's quantized log-count for that pair, `round(log2(c) *
8)`. Withhold the offer when that evidence is weak, and measure what the strip is worth when it
does speak.

Three signals, **fixed now**, and no fourth will be tried after seeing a result:

1. **`s1`** — the top-ranked prediction's score.
2. **`margin`** — `s1 − s2`, with `s2 = 0` when the engine returned fewer than two.
3. **the conjunction** — `s1 ≥ a` **and** `margin ≥ b`.

Same harness, same shipped configuration, same eligibility rule as every cell above (a target
of at least 3 characters, `previous` the token before it).

### The slices, and which one is allowed to choose anything

| slice | sentences | role |
|---|---|---|
| committed Wikipedia eval slice, **even** indices | 3,000 | **dev** — the only slice a threshold may be chosen on |
| committed Wikipedia eval slice, **odd** indices | 3,000 | **test** — reported against, never selected on |
| `he_conversational_test.txt.gz` | 6,000 | **register check** — never selected on, and the register a user actually types in |

The two halves are disjoint by construction and the test asserts it rather than inferring it
from the rule that produced them.

## The prediction, written now

1. **Precision-among-offered rises monotonically with the threshold.** `s1` is an evidence
   count, and contexts whose best continuation was seen thousands of times are genuinely better
   predicted than ones whose best was seen six times. If this is flat, the ranking has already
   spent the signal and the answer is B1's answer again, arriving from a third direction.
2. **The rule below is NOT met.** Reaching 20% precision-among-offered will cost more than the
   rule allows: I expect retention at that point to land in **40–60%**, short of 70%.
3. **`margin` is the weaker signal of the two.** A large gap between the first and second
   continuation says the context is lopsided, not that it is well-evidenced; `s1` says both.
4. **The conjunction beats either alone by less than 2 points of precision** at equal
   retention, because the two signals are computed from the same counts and are not
   independent.

If precision-among-offered comes out **above 25%** anywhere with retention above 80%, the
harness is wrong and gets checked before the result is believed. That would mean the shipped
policy is discarding a very large, very cheap win, and this project has learned to distrust
those.

## The stopping rule

Adopt an offer threshold only if **all** of the following hold:

- on **dev**, some configuration reaches **precision-among-offered@3 ≥ 20.0%** — a doubling of
  the shipped 10.49% — while **retaining ≥ 70%** of the top-3 hits the shipped policy produces;
- that same configuration, on **test**, lands within **3.0 points** of its dev precision and
  still retains ≥ 70%.

Anything else is recorded and **nothing is adopted**. The 70% floor is there because withheld
offers are a real loss to the user and not a free saving: every hit removed is a word they
would have tapped.

**Prefix-1 completion is measured alongside and is explicitly NOT under this rule.** It is the
worst-scoring cell in the document (5.43% top-3) and the same question applies to it, but a
rule that can be satisfied by whichever of two metrics happens to cooperate is not a rule. It
is reported as an observation, and adopting on it would need its own pre-registration.

## Why this is worth running even though prediction 2 says it fails

Because "the strip speaks in 86.64% of positions" has been carried as a feature for two
milestones and the other side of it has never been on a page. If the rule fails, the result is
a third independent measurement of the same ceiling — after B1 on allocation and the human
labelling on the confusion layer's thresholds — and the finding stops being a suspicion.

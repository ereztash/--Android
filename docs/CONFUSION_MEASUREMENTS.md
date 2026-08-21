# 

> **Updated by R1.** Every number below was re-measured after the training corpus changed to a
> 25% conversational blend. The previous figures described a model trained entirely on Hebrew
> Wikipedia and no longer describe what ships. See [`CORPUS_REGISTER.md`](CORPUS_REGISTER.md)
> for why the corpus changed and what it was worth.
Real-word error measurements (M11)

*"אפליקציה צריכה לזהות גם בתוך משפט לפי ההקשר אם יש שגיאת כתיב כמו שימוש באם במקום עם"*

`אם` ("if") and `עם` ("with") are both perfectly good Hebrew words. Nothing about either string
is wrong, so the lexicon accepts both — correctly — and edit distance has nothing to say. The
mistake lives in the sentence, not in the word.

Every number here is a claim about **exactly one thing**: this detector, on this corpus, with
this lexicon and this bigram table.

---

## Method

For a word *w* with neighbours *l* and *r*, the detector generates every real lexicon word one
**homophone substitution** away from *w*, and compares the bigram evidence:

```
evidence(x) = logCount(l, x) + logCount(x, r)
```

It offers the best alternative when that alternative has evidence and *w* has none.

### The confusion set costs zero bytes

The full inventory is 166,504 ordered pairs and would be a multi-megabyte asset. Instead
variants are generated on demand — `word.length × 6` candidates, each a binary search in the
lexicon — so `GATE-SIZE-1`'s asset budget is untouched. M11 cost **4,576 bytes of DEX and 0
bytes of assets**.

The release APK total is unchanged at 5,161,766 bytes, but not because M11 was free: the DEX
growth fell inside an existing 14 KB alignment pad, which now has 9,748 bytes left. The next
change of this size will move the total.

### Which letters, and why

Phonological, not typographic. Modern Israeli Hebrew has merged distinctions the orthography
still writes, so a writer who knows how a word sounds and not how it is spelled picks the wrong
letter — and lands on another real word often enough to matter.

| pair | shared realisation | recall (test slice) | n |
|---|---|---|---|
| א ↔ ע | zero / glottal stop | 67.18% | 11,067 |
| ח ↔ כ | /x/ | 64.51% | 3,973 |
| כ ↔ ק | /k/ | 64.91% | 3,645 |
| ת ↔ ט | /t/ | 75.40% | 4,460 |
| ב ↔ ו | /v/ | 56.18% | 15,547 |
| ס ↔ ש | /s/, sin undotted | 71.93% | 7,175 |

Reported per pair, not as an aggregate, so one bad confusion cannot hide inside five good ones.

**Keyboard-adjacent slips are deliberately excluded.** When a slip produces a non-word the
correction engine already catches it; when it produces a real word only context helps, but
folding those pairs in here would mix two error models whose rates were measured separately.

**`ו`/`י` is deliberately excluded.** It is by far the largest source of real-word pairs —
78,310 against 9,950 for `א`/`ע` — but `ktiv male` alternation makes `ו` and `י` genuinely
contrastive, so most of those pairs are two different words rather than two spellings of one.
It remains available as `HebrewConfusions.KTIV_MALE` so the claim can be re-measured rather
than argued.

---

## Corpora, and the dev/test split that makes the numbers mean anything

Three slices are cut from the held-out corpus at different offsets of the same stride.
`scripts/slice_eval_corpus.py` compares the index sets and **refuses to write** if any two
intersect.

| slice | sha256 | used for |
|---|---|---|
| `sample` | `cedfb5be…` | M10 prediction: weight sweep, ordering sweep, accuracy floors |
| `confusion_dev` | `7f6986d6…` | **M11: the margin sweep. Thresholds are chosen here and nowhere else.** |
| `confusion_test` | `9fc528ae…` | **M11: the reported numbers, measured once with thresholds fixed.** |

6,000 sentences each, pairwise overlap **0**, proven. A threshold tuned on the same sentences
it is then reported against is not a measurement, it is a fit.

- **Corpus D — injected errors.** Every eligible position has one letter swapped for its
  homophone, producing another real lexicon word.
- **Corpus E — the same text, untouched.** The false-alarm control, and the number that
  governs the thresholds.

---

## The margin sweep (dev slice, `7f6986d6…`)

Every configuration tried, both columns side by side. Recall without a false-alarm rate beside
it says nothing: a detector that flags every word has perfect recall.

### Both sides — the shipped mode

| margin | never contradict typed | recall | false alarm |
|---|---|---|---|
| 1 | no | 68.70% | 0.62% |
| 16 | no | 68.09% | 0.43% |
| 21 | no | 67.90% | 0.39% |
| 22 | no | 65.92% | 0.34% |
| 24 | no | 64.37% | 0.31% |
| 32 | no | 56.29% | 0.18% |
| 64 | no | 32.72% | 0.03% |
| 1–21 | **yes** | **62.31%** | **0.30%** |
| 22 | yes | 62.51% | 0.25% |
| 24 | yes | 61.08% | 0.23% |
| 32 | yes | 53.41% | 0.13% |
| 64 | yes | 31.11% | 0.02% |

Corpus D n=46,210, corpus E n=69,909.

### Left context only

| margin | never contradict typed | recall | false alarm |
|---|---|---|---|
| 21 | no | 45.89% | 0.22% |
| 1–21 | yes | 44.78% | 0.18% |
| 32 | yes | 33.84% | 0.08% |

Corpus D n=48,978, corpus E n=75,161.

---

## What was chosen, and why

### `margin = 21` — the model's own floor, not a tuned number

`scripts/build_bigrams.py` prunes at a count of 5, and counts are stored as
`round(log2(count + 1) * 8)`. So `round(log2(6) * 8) = 21` is the **smallest value any stored
pair can carry**, and every margin from 1 to 21 is the same rule: *"the corpus has seen this
pair at all."* The sweep confirms it — identical recall and identical false alarms across all
of them, with the first change at 22. `BigramFloorTest` pins the floor against the table
itself, so this cannot quietly stop being true.

21 is the value that states the rule rather than sitting arbitrarily underneath it. Tightening
to 24 would trade 3.34 points of recall for 0.07 points of false alarms — 31 missed errors per
false alarm avoided.

### `requireNoSupportForTyped = true` — a principle, not a threshold

The detector may speak when the corpus has nothing to say about what the user typed. It may not
contradict evidence the corpus actually has. Measured, that halves the false-alarm rate
(0.62% → 0.30%) for 4.28 points of recall. The measurement supports the principle; it is not
what chose it.

### Both sides, not left-only — and the harness bug that nearly decided otherwise

The first version of the sweep reported that left context alone cost **1.05 points**, and the
architecture was briefly settled on that basis: check each word the moment it is finished, and
never touch text away from the cursor.

That harness was wrong. Its "left only" branch still passed the following word to the detector
and varied only which *positions* were eligible. Corrected, the gap is **19.64 points** —
44.78% against 62.31% — and it decides the question the other way.

So the shipped detector checks the **second** most recent completed word, whose right-hand
neighbour is the most recent one. One check per word, made once, at the moment the evidence is
complete. Applying the fix then means replacing a word that is not under the cursor, which the
IME does by deleting from that word to the cursor and committing a rewritten span, carrying the
intervening text over **verbatim** rather than reconstructing it.

The bug was found because the locked test on the test slice reported 44.69% against a floor
written from the dev sweep's 63.37%. A 19-point discrepancy between two disjoint slices of the
same corpus is not sampling noise, and chasing it found the cause. **This did cost an extra
look at the test slice**, recorded here rather than quietly absorbed: the thresholds were not
changed as a result — only the harness was fixed and the architecture decision reversed.

---

## Results (test slice `9fc528ae…`, thresholds already fixed)

| metric | value | denominator |
|---|---|---|
| **recall** | **62.31%** | 45,867 injected errors |
| flagged with the wrong fix | 0.25% | 45,867 |
| **false alarm** | **0.26%** | 69,494 untouched positions |

Dev measured 62.31% / 0.30%; test measured 62.31% / 0.26% on sentences the dev slice never
contained. Two disjoint slices agreeing to within 0.2 points is the best evidence available
here that the numbers are about Hebrew and not about a particular 6,000 sentences.

### Controls

| control | result | what it rules out |
|---|---|---|
| same detector, **empty** bigram table | **0** of 69,494 flagged | the findings are a property of context, not of the confusion inventory |
| permissive detector (margin 0, will contradict the typed word) | 15.08% false alarm | the counting loop can separate a permissive detector from the shipped one, so 0.26% is a measurement |

### The example the operator asked about

```
דיברתי [אם] המורה   ->  עם     typed evidence 0, suggested evidence 69
לא [יודע] אם        ->  no finding
אני [עם] חברים      ->  no finding
```

---

## What these numbers are NOT

- **Recall is not "the fraction of real mistakes caught".** Corpus D's errors are drawn from
  the same inventory the detector searches, so 62.31% answers only: *given that the error is
  one this detector can express, does context find it?* How often real Hebrew typing produces
  an error inside this inventory is **NOT MEASURED** — it would need a corpus of genuine human
  errors, which this project does not have.
- **Recall is a floor, not a point estimate.** Some injections produce a perfectly sensible
  sentence, and those count as misses even though nothing was really wrong.
- **The false-alarm rate is not a precision figure.** Precision depends on how often a real
  error is present, which is unmeasured. At 0.26%, a user typing 1,000 words sees roughly 2.6
  unnecessary suggestions; whether most flags are right depends entirely on a base rate this
  project cannot observe.
- **Nothing here is ever auto-applied.** The detector returns a suggestion; the user taps it or
  ignores it. Silently rewriting `אם` to `עם` would change what someone said into something
  they did not say, and no accuracy figure would justify that.
- **The evidence is Wikipedia prose, two words wide.** No sentence structure, no topic, no
  grammar, no user vocabulary. `דיברתי אם חבר` is caught because Wikipedia contains
  `דיברתי עם`; a sentence whose words Wikipedia never puts together is invisible to it.
- **No device latency number.** The detector runs on the same off-thread path as prediction,
  inside the same `HebrewIme.suggest` trace section, which is re-measured in M12.

## Reproducing

```sh
./gradlew :core:test --tests '*ConfusionAccuracyTest*'
./gradlew :core:test --tests '*BigramFloorTest*'
./gradlew :core:test --tests '*ConfusionSweepTest*' -PrunConfusionSweep=1
```


---

## The limit of the adjacent window — found on a phone, not in a test

The operator typed `אני אוהב עוגת גבינה אם הרבה שוקולד` and observed that a *reader* cannot tell
whether `אם` or `עם` was meant until the word after `הרבה` arrives.

**That case does fire**, and the reason deserves to be stated plainly rather than claimed as a
success:

| window | left | right | total |
|---|---|---|---|
| `גבינה` **`אם`** `הרבה` | 0 | 0 | **0** |
| `גבינה` **`עם`** `הרבה` | 0 | 51 | **51** |

`עם הרבה` is a common collocation and `אם הרבה` never occurs in the corpus, so the statistics
reached the right answer **before** the deciding word was typed. That is luck of collocation,
not understanding. The mechanism was not doing what the example required and got it right
anyway, which is exactly the kind of thing that should be written down rather than enjoyed.

### How often the window is genuinely blind

Measured on `hewiki_confusion_test.txt.gz`, 6,000 sentences:

| | count | share |
|---|---|---|
| words with a confusable variant | 35,291 | — |
| window has some evidence to weigh | 24,637 | 69.8% |
| **both candidates score zero on both neighbours** | **10,654** | **30.2%** |

In that 30.2% no margin, weight or threshold can help: there is no signal in the window to
weigh. Only context further away could decide, and `BigramModel` stores **adjacent pairs only**,
so that context is not representable at all. This is a structural limit of the model, not a
tuning decision, and it is why the recall ceiling in this document is where it is.

`WindowBlindnessTest` pins 30.2% as a characterisation, so that a future model claiming a wider
window has a number to beat rather than an impression to appeal to.

### What would actually close it — measured, not estimated

Not "semantic understanding" in the neural sense: that means a model this app cannot carry. The
whole APK is 5.2 MB, the build has no network permission by design, and `GATE-NET-1/2/3` exist
to keep it that way.

What is achievable is a **distance-2 (skip) table**, letting a word two positions away bear on
the decision. Counted on the same corpus the shipped table was trained on (1,915,789 sentences):

| table | min count | distinct pairs | raw | packed (~0.38x) |
|---|---|---|---|---|
| adjacent (shipped) | 5 | 554,484 | 2.96 MiB | 2.85 MiB asset |
| skip-2 | 5 | 414,574 | 2.21 MiB | ~0.85 MiB |
| **skip-2** | **10** | **153,412** | **0.82 MiB** | **~0.31 MiB** |
| skip-2 | 20 | 59,751 | 0.32 MiB | ~0.12 MiB |

**An earlier draft of this section said a skip table would push the APK past `GATE-SIZE-1` and
that the ceiling would have to be raised. That was an estimate, and it was wrong.** At
`min_count >= 10` the table needs about 0.31 MiB against 576,703 bytes of asset headroom. It
fits. No threshold moves.

### And it would make the detector worse, as it stands

Coverage first — on the test slice, of the 10,542 positions blind to the adjacent window:

| | count | share of blind |
|---|---|---|
| skip-2 has any data at all | 1,948 | 18.5% |
| ...and it discriminates between the candidates | 1,946 | 18.5% |

That is **5.51 percentage points** of new evidence across all confusable positions. Encouraging,
and not the number that decides it.

The eval text is already correct, so the typed word is the right answer at every one of those
positions. Which way does the new evidence point?

| | count | share |
|---|---|---|
| favours the typed (correct) word | 1,798 | **92.4%** |
| favours the variant — a **false alarm** if acted on | 148 | **7.6%** |

Those 148 are **0.42 percentage points** of new false alarms over 35,291 positions. The shipped
false-alarm rate is **0.26%**. Adding raw skip-2 evidence would roughly **triple** it.

So the signal is real — 92.4% is not noise — and it cannot be used as it stands. It would need
its own margin, swept on `confusion_dev` and reported once on `confusion_test`, trading most of
the 5.51 points away to keep the false-alarm rate where it is. Whether enough recall survives
that trade is **NOT MEASURED**.

Recorded as a characterised, costed, open decision. Not taken, and not to be taken by relaxing
the false-alarm figure to make it look good.


---

## S1 — the distance-2 layer: stopping rule, recorded before building

The operator authorised building the skip layer **on condition that the rule for abandoning it
was fixed first**. It is fixed here, before the table exists, before any margin is swept, and
before any result is seen.

### Ship only if BOTH hold, on `confusion_test`, measured once with the margin already fixed

| | current shipped | requirement |
|---|---|---|
| recall | 62.31% | **strictly greater** |
| false alarms | 0.26% | **less than or equal** |

### And these are the ways it is NOT allowed to pass

- **The false-alarm figure does not move.** 0.26% is the number this detector was shipped on and
  the number a user experiences as "it left my correct writing alone". If the skip layer can
  only clear the bar by allowing 0.4%, it has failed.
- **The margin is chosen on `confusion_dev` and nowhere else.** `confusion_test` is measured
  once, at the end, with everything already decided.
- **No re-pinning `WindowBlindnessTest`.** If blindness falls, that is the improvement being
  claimed and it gets reported with both numbers.
- **A failure is a result, not a reason to search harder.** If the trade does not clear, the
  outcome is a report saying so and no shipped layer. The measured 5.51 points of new evidence
  and 92.4% correct direction are already known; they are not a promise that a usable operating
  point exists.

### What is genuinely uncertain

Raw skip-2 evidence triples the false-alarm rate. Everything therefore depends on whether a
margin exists that keeps most of the 92.4% while rejecting most of the 7.6% — and those two
populations may simply not separate. That is **NOT MEASURED** and is the whole question.


### S1 VERDICT: FAILED. Not shipped.

> **Superseded.** The operator later decided to ship S1 and P1 together, and the two were
> re-measured jointly because they fire on the same positions. The current numbers are in
> **S1+P1 — the joint verdict** at the end of this document. Everything below is the verdict as
> it stood, kept because a verdict that gets quietly rewritten when the decision changes was
> never a verdict.
>
> **One cell below is wrong and cannot be re-derived.** `29,864 / 65.11%` does not reconcile
> with the `+243 / +0.53` difference row underneath it (28,578 + 243 = 28,821, which is 62.84%);
> the `+243` reading is the one the conclusion was drawn from and is self-consistent, so the
> "after" caught/recall pair is the error — most likely pasted from a different configuration.
> It cannot be corrected by re-running, because R1 replaced the bigram table this was measured
> on. Left visible rather than deleted.

Margin chosen on `confusion_dev` (80 — the lowest value at which the skip path added no false
alarms on that slice), then measured **once** on `confusion_test`, through the same harness that
produced the published figures:

| | caught | recall | false alarms | rate |
|---|---|---|---|---|
| adjacent only (shipped) | 28,578 | 62.31% | 178 | 0.256% |
| + distance-2, margin 80 | 29,864 | **65.11%** | 182 | **0.262%** |
| difference | **+243** | +0.53 | **+4** | +0.006 |

**Recall passes. False alarms do not.** The rule was `false <= 0.26%`; the result is 0.262%.
It fails by four sites out of 69,494.

**Two things about that, stated rather than smoothed over.**

First, a flaw in how I wrote the rule: it compared a precisely measured value against the
*rounded* published constant 0.26%, when the actual shipped rate is 0.256%. A better-written
rule would have said "no worse than the baseline measured in the same run". That is a criticism
of my rule-writing, not a reason to pass — under either reading the layer adds false alarms
rather than holding them constant.

Second, and more important: **the margin is not being re-picked.** `confusion_test` has now been
observed for this question. Choosing a different margin because the test failed is tuning on the
test set, which the rule forbids in as many words. There is no second attempt at this slice; a
further attempt would need a fresh slice cut from the residual pool.

The trade actually on offer is **+243 errors caught for +4 false alarms — 61 to 1**. That is a
good trade by most standards. It is also, precisely, a request to move a number that was
committed in writing not to move, which is an operator decision and not one to be taken by the
person who wants the feature to pass.

Escalated. Layer built, measured, and left disabled: `RealWordErrorDetector` defaults `skip` to
`BigramModel.EMPTY` and `CorrectionController` does not load the asset.
`SkipLayerVerdictTest.theFailedLayerIsNotWiredIntoProduction` asserts that it stays that way.

*(That test was replaced by `WideContextVerdictTest.bothLayersAreWiredIntoProduction`, which
guards the same thing from the other side once the operator decided to ship. Both files are in
git history at `6c56758`.)*


---

# D1 — distilling DictaBERT, and the ceiling it revealed

## What the oracle experiment proved

The operator proposed DictaBERT (`dicta-il/dictabert`, 184,474,880 parameters, ~704 MiB fp32) as
a **measurement tool**. That framing is what makes it usable: the model is roughly 130x the
entire APK and could never ship, but nothing stops it running at build time.

Measured on 3,000 injected confusions drawn from `confusion_test`:

| | this app | DictaBERT |
|---|---|---|
| recovers the original, overall | 62.31% | **98.6%** (2,957/3,000) |
| **on positions where the adjacent window is blind** | **~0%** | **97.5%** (753/772) |
| prefers the variant on clean text (false alarms) | 0.26% | 1.43% |

**The second row overturns a claim made earlier in this document.** The 30.2% of positions with
no adjacent evidence were described as cases where "no margin, threshold or weight can help:
there is no signal in the window to weigh". The first half is true and the implication was
wrong. There is no signal *in a bigram count table*. The signal is in the sentence, and a model
that reads the sentence recovers 97.5% of it.

**62.31% is a property of the model, not of the problem.** That could not have been known from
inside the model, and it is what the operator's suggestion bought.

## Why a small table can carry a useful part of it

21.0% of the lexicon — 74,576 of 355,587 forms — has at least one homophone variant. The
occurrence distribution is far narrower than that:

| confusable forms | share of confusable-token occurrences |
|---|---|
| top 100 | 36.3% |
| top 500 | 56.9% |
| **top 2,000** | **75.9%** |
| top 10,000 | 93.7% |

The most frequent are `של, את, על, ב, הוא, עם, לא, בשנת, היא, או, בין, כי` — exactly the words a
person actually mistypes. A table covering thousands, not tens of thousands, reaches most of the
traffic.

## And the reason this is not free

**DictaBERT is not an oracle.** On clean text it prefers the variant 1.43% of the time — over
five times this app's shipped false-alarm rate. Distilling its *decisions* would import that
error rate wholesale. The distillation must therefore use its **confidence**, keeping only
contexts where it is one-sided, and the confidence threshold is itself a number that has to be
chosen on dev data and reported on test.

Everything above is Hebrew Wikipedia. The register is still wrong for phone typing, and
distillation does not fix that — it inherits it.

## D1 stopping rule, recorded before the table is built

`confusion_test` has already been observed for the S1 question, so it is not clean for this one.
D1 will be measured on a **fresh slice cut from the residual pool** by
`scripts/slice_eval_corpus.py`, which proves pairwise disjointness across every slice and
refuses to write on any intersection.

Ship only if, on that fresh slice, measured once with every threshold already fixed on
`confusion_dev`:

| | shipped | requirement |
|---|---|---|
| recall | 62.31% | **strictly greater** |
| false alarms | 0.256% | **no greater than the baseline measured in the same run** |
| asset growth | — | fits the remaining budget with `GATE-SIZE-1` unchanged |

The false-alarm criterion is written against **the baseline measured in the same run**, not
against a rounded published constant. That is the flaw S1 exposed in my own rule-writing, fixed
here rather than repeated.

Additional conditions, so that "it passed" cannot be manufactured:

- **The confidence threshold is chosen on `confusion_dev` and nowhere else.**
- **No BERT at runtime, ever.** The shipped artifact is a table of integers. If the only way to
  get the benefit is to run the model on the phone, D1 fails — `GATE-NET-1/2/3` and the size
  budget are not negotiable for this.
- **A failure is a result.** The 33-point ceiling is proven; that a *compact* table can capture
  a useful part of it is not, and this rule exists precisely because those are different claims.


## D1 VERDICT: FAILED, and the reason is structural

Distillation into a shipped lookup table does not work. Not "does not work at this budget" —
does not work.

### Why blindness is where it is

Of the 10,542 blind positions on `confusion_test`:

| | count | share |
|---|---|---|
| the pair exists but was **pruned** at count 1–4 | 5,637 | 53.5% |
| the corpus contains it **nowhere at all** | 4,905 | 46.5% |

So barely half is even addressable by restoring pruned counts, and those pruned pairs number
**6,168,534** — 23 MiB at nine bytes each even when restricted to the top 500 confusable forms,
against 576,703 bytes of asset budget. Forty times over.

### And capping it does not rescue it

| table | entries | size | blind positions covered |
|---|---|---|---|
| top 100 forms × 200 neighbours | 20,000 | 176 KB | 53 / 10,542 — **0.5%** |
| top 300 × 200 | 60,000 | 527 KB | 191 — **1.8%** |
| top 300 × 400 | 120,000 | 1,055 KB | 323 — 3.1% |
| top 1,000 × 200 | 200,000 | 1,758 KB | 574 — 5.4% |

The largest table that fits reaches 1.8%. Three times over budget reaches 5.4%.

### The reason, which is the actually useful output of D1

**Blindness lives in the long tail, and a lookup table covers the head.** Those are the same
sentence. A position is blind precisely because its context is rare; the frequent contexts have
counts and were never blind. Enlarging a memorisation table walks *down* the frequency curve,
which is where the returns are worst by construction.

The 33-point ceiling DictaBERT demonstrated is real and is **not reachable by memorisation**.
BERT reaches it by *generalising* — it has never seen most of those exact contexts either. That
is the capability a count table structurally lacks, and no amount of budget changes it.

**This is the finding.** It closes a direction that looked obviously right, and it cost two
measurements rather than a week of building a table that would have covered 1.8%.

### What remains open

- **A small neural student.** Distil DictaBERT into a few-hundred-KB model that *generalises*
  rather than memorises. This is the only path measured to be capable of the ceiling. It needs
  an on-device runtime (TFLite or ONNX, several MB), per-keystroke inference inside the latency
  budget, and real ML work. Unlike the table, it is not ruled out — it is unquantified.
- **Feature-based, not example-based.** A tiny model over character n-gram features of the
  neighbours would generalise somewhat at a fraction of the size. Cheaper to try, smaller
  ceiling, entirely unmeasured.
- **Accept 62.31%.** Now a known distance from a known ceiling, rather than a number with no
  context, which is worth more than the figure alone.

None of these is started. D1 stopped where its rule said to stop.


---

# D4 — attributing the recall between context and prior

This control was **missing for the whole life of the feature**, and its absence was the
operator's finding rather than mine.

`RealWordErrorDetector` is described everywhere as context-aware, and its recall is quoted as
evidence of that. But `אם`/`עם` is not a symmetric pair: one member is far commoner than the
other, and the injection protocol usually replaces a common word with a rarer one. A detector
that ignored context entirely — *always suggest the commoner variant* — therefore recovers a
share of the injections for free. Until this ran, **62.31% could not be attributed.** It could
have been context, or a unigram prior with a context-shaped API around it.

## The control, held to the shipped detector's own false-alarm budget

| frequency margin | recall | false alarms |
|---|---|---|
| 0 | 88.47% | 6.454% |
| 24 | 72.54% | 1.848% |
| 40 | 58.62% | 0.738% |
| 48 | 51.17% | 0.482% |
| 52 | 46.17% | 0.325% |
| **56** | **43.29%** | **0.239%** |
| 64 | 37.63% | 0.154% |

| | recall | false alarms |
|---|---|---|
| best frequency-only within the shipped budget | 43.29% | 0.239% |
| **shipped context detector** | **62.31%** | 0.250% |
| **attributable to context** | **+19.02** | — |

**The matching matters, and the first version of this got it wrong.** The initial run credited
context with **+24.68** points by comparing against a control at 0.154% alarms — a stricter
operating point than the shipped detector runs at. Crediting context with recall that is really
the control being run more conservatively is exactly the flattering error this document exists
to avoid, and it went in the flattering direction on the first attempt. The margin was swept
finely to find the control's best row at or below **0.250%**, the shipped rate.

## What this says

**69% of the shipped recall is available with no context at all.** Context is worth 19 points on
top of a prior that is already doing most of the work.

That is a real contribution and it is much less than the headline number suggests. Anyone reading
"64.58%" — or now "62.31%" — as a measure of contextual understanding was reading it wrong, and
nothing in this document previously stopped them.

The feature is not invalidated: 19 points is a large effect, it is what makes `אם`→`עם` work in
the direction where the prior is wrong, and the shipped detector reaches its recall at a
false-alarm rate the control cannot match at any margin above it. But the claim is now bounded,
and `FrequencyPriorControlTest` fails if the context detector ever stops beating the prior —
at which point the documentation would have to say the feature is a frequency table.


---

# D5 — auditing the remaining claims for missing controls

D4 exposed a pattern: a number quoted without knowing what a trivial method would score. The
operator asked whether the pattern recurred elsewhere. It did, twice, and one of the two was a
claim this document made confidently.

## The 98.6% ceiling was mostly the prior

`docs/CONFUSION_MEASUREMENTS.md` reported DictaBERT recovering **98.6%** against this app's
62.31%, and called the difference **33 points of headroom**. That framing drove D1.

Run on the **identical 3,000 cases**, with a control that ignores the sentence entirely and
always picks the commoner of the two candidates:

| | overall (n=3,000) | blind positions only (n=1,253) |
|---|---|---|
| frequency prior alone | **85.7%** | **81.2%** |
| DictaBERT | 98.6% | 97.8% |
| **attributable to context** | **+12.8** | **+16.5** |

**Most of 98.6% is the prior.** The injection replaces a word with a rarer variant, so on a
forced binary choice "always pick the commoner one" is already right 85.7% of the time.

And the "33 points of headroom" comparison was not like for like. DictaBERT there is making a
**forced choice between two candidates**; `RealWordErrorDetector` must also decide **whether to
speak at all**, under a false-alarm budget, with abstention as the usual answer. Comparing an
operating point against a forced choice overstates the gap, and I did not notice because the
number pointed the way I expected.

The conclusion D1 rested on survives — a model that reads the sentence does better, and the
blind positions are not all genuinely ambiguous — but **the size of the prize is much smaller
than 33 points**, and D1's structural failure was the more important half of that finding
anyway.

### What this control suggests, and has not been done

**81.2% of blind positions are resolvable by the prior alone.** The shipped detector declines to
act on them entirely: its evidence is bigram counts, and where those are zero it abstains. A rule
that fell back to the unigram prior *specifically at blind positions*, with its own margin, is
suggested by this control and is **NOT MEASURED**. It is the obvious next experiment and it has
not been run.

## The abbreviation table had no precision control at all

`HebrewAbbreviationsTest` checked that the table maps what it should and never asked how often it
fires on a word the user meant. Measured for the first time:

**506 of 861 bare forms — 58.8% — are themselves valid lexicon words.**

`מס` means *tax* and abbreviates `מס׳`. `צהל` is a verb and abbreviates `צה״ל`. The shipped
version gave every abbreviation `Double.MAX_VALUE` and placed it first, so **typing an ordinary
word offered its abbreviation ahead of the word's own completions**. That shipped, and no test
would have caught it.

Fixed: an abbreviation leads only when the bare form is not a direct lexicon entry; otherwise it
is offered in second place. `AbbreviationPrecisionTest` measures 0 hijacks across 291 colliding
forms, with a positive control showing 355 of 355 non-word forms still lead.

### The rule is a judgement, and says so

Frequency was tried as a tie-breaker and **does not separate the cases**: `ככ` 46, `האום` 47,
`וכו` 83, `מס` 115. `וכו׳` is obviously intended and `מס׳` obviously is not, and the bare forms
sit the wrong way round for any threshold. What distinguishes them is whether the bare form is
live in modern usage, which an inflected lexicon cannot say.

So no threshold was invented. The conservative side is taken — an automatic suggestion never
outranks a form the user literally typed — at the cost that `ככ` now offers `ככה` first and
`כ״כ` second.

## Claims checked and found already controlled

- **Next-word and completion accuracy** — `aContextFreePredictorScoresMeasurablyWorse` is the
  null arm, and the `bigramWeight = 0` row of every sweep is a frequency-only baseline.
- **Adaptive learning +0.67** — measured against the static layer on the identical split.
- **The conversational blend +12.73** — measured against the pre-blend table in the same run.
- **Correction accuracy** — two control corpora, and the neutral-cost configuration is the
  baseline every weight was chosen against.
- **Every privacy gate** — each ships a planted defect demonstrated red in the same run.


---

# P1 — the prior fallback at blind positions: rule before build

D5 measured that **81.2% of blind positions are resolvable by the unigram prior alone**, and that
the shipped detector abstains on all of them because its only evidence is bigram counts. This
experiment tests whether acting on the prior *there specifically* is worth its false alarms.

## Why this is not simply "use frequency"

The detector already refuses to contradict corpus evidence it has. This fallback fires **only
where there is none** — both the typed word and every variant score zero on both neighbours. It
is a different rule from D4's frequency-only control, which ignored context everywhere: this one
keeps context primary and asks what to do when context is silent.

## The rule

Margin chosen on `confusion_dev` and nowhere else. Reported once on `confusion_test`, and also
on `he_conversational_test.txt.gz`, because a keyboard is used on conversational text and the
prior's shape differs between registers.

Ship only if, measured in the same run:

| | current | requirement |
|---|---|---|
| recall | 62.31% | **strictly greater** |
| false alarms | 0.250% | **no greater than the baseline in that run** |

And the conditions that stop "it passed" being manufactured:

- **The false-alarm figure does not move**, on either slice.
- **A failure is a result.** 81.2% of blind positions being prior-resolvable is not a promise
  that a usable operating point exists — the same blind positions are where the false alarms
  will come from, because a prior that is right 81% of the time on injected text is wrong on
  clean text at a rate nothing here has measured.
- **`confusion_test` has been reported on before**, for S1's verdict and D4's control. It has
  never been TUNED on, and it is not being tuned on now. Recorded because a test set reported on
  repeatedly is worth less each time, and pretending otherwise is how a slice quietly becomes a
  dev set.


## P1 VERDICT: FAILED on one slice. Not shipped.

> **Superseded** by **S1+P1 — the joint verdict** at the end of this document, for the reason
> given there: the two layers fire on the same positions and measuring them apart answers a
> question nobody has any more. Kept as it stood.
>
> Note also that this table was produced by a **different harness** from S1's — one injection
> per eligible word rather than one per confusable letter position, and a false-alarm
> denominator restricted to positions that have a confusable variant. That is why its baseline
> reads 59.47% / 0.493% where S1's reads 62.31% / 0.256%. Both are legitimate; they are not
> comparable, and the joint verdict uses the canonical one throughout.

Margin 104 chosen on `confusion_dev` — the **lowest margin at which the fallback's false alarms
return to the baseline**, not the margin with the best recall, which was 8 at nearly nine times
the false-alarm rate.

| slice | | recall | false alarms |
|---|---|---|---|
| encyclopedic | before | 59.47% | 0.493% |
| | after | **60.66%** | **0.499%** |
| | delta | **+1.19** | **+0.0057** |
| conversational | before | 74.13% | 0.344% |
| | after | **75.28%** | **0.344%** |
| | delta | **+1.16** | **+0.0000** |

**It passes cleanly on conversational text and fails on encyclopedic text by two sites.**

In counts: **+420 correct catches for +2 false alarms** on the encyclopedic slice — 209 to 1 —
and **+186 catches for zero additional false alarms** on the conversational one.

The rule said *both* slices. It failed. The margin is **not** being re-picked: `confusion_test`
has now been observed for this question, and choosing a new value because the test failed is
tuning on the test set, which the rule forbids in as many words.

### The pattern worth naming

**This is the second time a "false alarms must not rise at all" rule has killed a favourable
trade.** S1 died at 61:1 over four sites; P1 dies at 209:1 over two.

A rule that rejects 209-to-1 is either protecting something the ratio does not capture — and
false alarms genuinely are worse than misses for a keyboard, because a wrong suggestion
interrupts correct writing while a missed one costs nothing — or it is simply too strict.

Which of those it is, is an operator decision, and it is not one to be taken by the person who
keeps wanting the feature to pass. Recorded as such rather than resolved.

### Worth noting about the split

The failure is on the register nobody types in, and the pass is on the one they do. That is
decision-relevant and it is not a reason to declare victory: the rule was written knowing both
slices would be measured, precisely so that a favourable half could not be reported alone.

The fallback is built, measured, and left disabled: `DEFAULT_PRIOR_MARGIN = 0`, with a test
asserting it stays that way while the verdict stands.

*(Both are now the shipped values — see the joint verdict below.)*


---

# S1+P1 — the joint verdict, and what the operator actually bought

The operator, presented with both escalations, decided: **ship both.** This section records what
that decision cost, what it bought, and one finding that argues against half of it.

## Why they had to be re-measured rather than added up

S1 and P1 fire on **exactly the same population**: positions where the adjacent window has
evidence for neither candidate. S1 speaks when distance-2 counts exist there; P1 speaks when they
do not. Every position P1 would have won is a position S1 may already have taken, so
`+1.19` and `+0.53` cannot be summed into `+1.72`. The pair had to be swept as a pair.

## The shape correction, which shrank the feature before any threshold was chosen

`HebrewImeService` checks the **second most recent completed word**. At that moment:

```
[ left2 ][ left ][ TARGET ][ right ]            <- what exists
                                    [ right2 ]  <- has not been typed
```

`right2` is the word *after* the one the user has only just finished. It does not exist, and no
amount of context reading produces it. **The original S1 sweep measured with both distance-2
neighbours available**, so its figure was a ceiling the app can never reach. Every number below
is measured one-sided, with the both-sided row printed beside it as the unreachable ceiling:
on the encyclopedic slice that ceiling is `+1.84` against the `+1.42` production can collect.

The window widened from three completed words to four to supply `left2`
(`HebrewImeService.CONTEXT_WORDS`).

## A defect this sweep found in the detector

`skipMargin = 0` did **not** disable the distance-2 layer. With no threshold, the comparison
`candidateSkip - typedSkip < 0` is false for a pair of zeroes, so every variant of every blind
word became a finding — **13.583% false alarms** where the shipped rate is 0.250%. Handing the
layer an empty table did not disable it either, for the same reason.

That produced a wrong published row in **three** separate sweeps before it was found. It is now
an explicit branch — 0 means off, exactly as it does for `priorMargin` — with
`WideContextVerdictTest.zeroSkipMarginDisablesTheLayerRatherThanUnleashingIt` asserting that
`checkWide` with both margins at zero is indistinguishable from `check`.

## The joint sweep — `confusion_dev`, 46,210 injections, 69,909 clean sites

Canonical harness, `next2 = null`, adjacent-only baseline 62.45% / 0.283%.

| skipMargin | priorMargin | recall | false alarms | delta |
|---|---|---|---|---|
| — | — | 62.45% | 0.283% | adjacent only |
| off | 104 | 63.72% | 0.283% | +1.27 / +0.0000 |
| 80 | off | 62.60% | 0.283% | +0.16 / +0.0000 |
| 64 | 96 | 64.75% | 0.289% | +2.30 / +0.0057 |
| 72 | 96 | 64.55% | 0.286% | +2.11 / +0.0029 |
| 80 | 96 | 64.46% | 0.285% | +2.02 / +0.0014 |
| **80** | **104** | **63.86%** | **0.283%** | **+1.42 / +0.0000** |
| 88 | 104 | 63.74% | 0.283% | +1.30 / +0.0000 |
| 96 | 104 | 63.73% | 0.283% | +1.29 / +0.0000 |

**Chosen: `skipMargin 80, priorMargin 104`** — the highest-recall row whose false-alarm rate is
unchanged from the baseline. `64 / 96` offers +0.88 more recall for four more false-alarm sites;
it was not taken, because the operator's decision was permission to ship the layers, not an
instruction to maximise recall against the one number this detector is governed by.

## THE VERDICT — measured once, on both test slices

Canonical harness, `next2 = null`, shipped configuration.

### Encyclopedic (`hewiki_confusion_test`, 45,867 injections, 69,494 clean sites)

| configuration | caught | recall | alarms | rate |
|---|---|---|---|---|
| adjacent only (what shipped before) | 28,580 | 62.31% | 174 | 0.250% |
| + distance-2 alone | 28,634 | 62.43% | 174 | 0.250% |
| + prior alone | 29,182 | 63.62% | 176 | 0.253% |
| **SHIPPED: both** | **29,233** | **63.73%** | **176** | **0.253%** |
| ceiling: both, `next2` given | 29,426 | 64.16% | 181 | 0.260% |

**+653 catches for +2 false alarms — 326 to 1.**

### Conversational (`he_conversational_test`, 21,961 injections, 27,726 clean sites)

| configuration | caught | recall | alarms | rate |
|---|---|---|---|---|
| adjacent only (what shipped before) | 16,961 | 77.23% | 55 | 0.198% |
| + distance-2 alone | 16,969 | 77.27% | 55 | 0.198% |
| + prior alone | 17,220 | 78.41% | 55 | 0.198% |
| **SHIPPED: both** | **17,228** | **78.45%** | **55** | **0.198%** |
| ceiling: both, `next2` given | 17,262 | 78.60% | 55 | 0.198% |

**+267 catches for zero additional false alarms.**

## Who moved the threshold

The original rule was "recall up, **false alarms not up**". On the conversational slice that rule
**holds** as written. On the encyclopedic slice it is **spent**: 176 alarms against 174.

The dev slice suggested it would not need spending — `80 / 104` was chosen precisely because its
dev false-alarm rate was identical to the baseline to four decimals. It did not survive the test
slice. That is the entire reason thresholds are chosen on one slice and reported on another, and
it is worth recording that the mechanism caught its own author's optimism.

**The operator moved that threshold, not me.** It was escalated twice — S1 at 61:1 over four
sites, P1 at 209:1 over two — and both escalations named the decision as an operator's rather
than resolving it. What ships is the operator's call, executed.

## The finding that argues against shipping half of this

**The distance-2 table earns almost nothing in the shape production runs.**

| slice | S1's marginal contribution over the prior alone |
|---|---|
| encyclopedic | **+0.11 points, +51 catches** |
| conversational | **+0.04 points, +8 catches** |

**And on clean text it barely speaks at all.** Harvesting the whole held-out conversational
slice for `docs/LABELING_PROTOCOL.md` — 1,815,379 words — the shipped detector fires 2,166
times: **2,156 via the adjacent window, 8 via the prior, and 2 via distance-2.** The
distance-2 table speaks twice in 1.8 million words.

That does not contradict the +267 catches above; it explains them. Injecting a homophone
makes the typed word unattested in its context, which is exactly the blindness these layers
serve, so the injected-error harness manufactures the positions they need. On text nobody
corrupted, the adjacent window nearly always has evidence and they never get a turn. Both
measurements are true at once: the layers are nearly free in false alarms **because** they
are nearly silent.

It costs **387,300 bytes in the release APK** (672,606 uncompressed), which is 52% of the assets
headroom that existed before it: `GATE-SIZE-1` reports 3,250,074 of 3,600,000 bytes, where it
previously reported 2,862,774. That is roughly **6,600 bytes per additional error caught**, on
an evaluation corpus, on a keyboard whose audience explicitly includes people on metered
connections.

P1 costs **zero bytes** — the frequency table was already shipped for ranking — and delivers
+602 and +259 of the +653 and +267.

**Recommendation, for the operator and not decided here: ship P1, drop S1.** The recommendation
is recorded rather than acted on, because the instruction was to ship both and the trade is a
judgement about product weight rather than a measurement error. Reverting S1 alone is one
constant and one asset: set `DEFAULT_SKIP_MARGIN = 0` and delete `lexicon/assets/he_skipgrams.bin.gz`.

## What this section does NOT claim

- **Not that the detector is 63.73% accurate on real Hebrew mistakes.** The injected-error
  caveat at the top of this document applies unchanged: recall here answers "given an error this
  detector can express, does context find it?"
- **Not that two extra false alarms is the number a user experiences.** It is two per 69,494
  eligible positions in Wikipedia prose, and users do not type Wikipedia prose.
- **Not that the prior is trustworthy where it fires.** It is a context-free signal used only
  where context is silent, and `checkWide` runs it last for that reason — an earlier draft put
  it in `check()`, which runs first, and would have let it pre-empt the distance-2 evidence.
- **`confusion_test` has now been reported on four times** (M11, S1, D4's control, and this).
  A test set reported on repeatedly is worth less each time. A further question about this
  detector needs a fresh slice cut from the residual pool, not a fifth look at this one.


---

# A1 — the first time this detector was measured against a human

Every number above is measured on **injected** errors. This one is not. 100 positions were
drawn from held-out conversational text, 80 of them positions where the shipped detector
actually fires, and a competent Hebrew reader judged each one blind — the typed word and the
suggestion shown in random order, with no indication which was which.

Full protocol, controls, and the pre-registered decision rule: `docs/LABELING_PROTOCOL.md`,
committed before the first batch existed.

| n = 80 real firings | |
|---|---|
| agreed with the detector | **8** |
| preferred the word already in the text | **45** |
| could not decide either way | **27** |
| **precision floor** (every abstention a loss) | **10.0%** — 95% [5.2, 18.5] |
| **precision ceiling** (every abstention a win) | **43.8%** — 95% [33.4, 54.7] |

The batch's formal verdict is **NOT DECIDABLE** — abstentions came in at 33.8% against a 30%
bar fixed beforehand — so no precision figure is computed on the decided subset. The bound
above uses every item and needs no such assumption.

**Even the ceiling's upper confidence bound, 54.7%, sits below the 60% band the rule calls
shippable.** No resolution of the ambiguous positions puts this batch there.

## And no threshold fixes it

The evidence advantage that produced each finding was recorded, so every higher
`Config.margin` was simulated over the labels at no further cost. Agreements and
disagreements have the **same** advantage distribution — median 28 for both. Raising the
margin discards correct catches at the same rate as wrong ones and past 64 discards all of
them. Nothing else separates them either: not context words, word length, sentence position,
or sentence length. The one candidate is that a suggestion **rarer** than the typed word was
never right (0 of 12 decided), which lifts the floor to 12.7% and is a hypothesis for the
next batch rather than a fix.

## What this does not settle

The labels are **one person's judgment, once**. The protocol's self-agreement check runs in
the next batch and has not run. n is 80. The frame is edited subtitle text, and 17 of the 80
sentences carried a corpus artefact — though on the 63 clean ones all 8 agreements remain, so
noise does not explain the result.

This is a strong signal against a feature that has been shipping since M11. It is not yet a
confirmed one, and the difference matters enough to say twice.


## A1 batch 002 — a second, larger sample says the same thing

240 fresh firings, disjoint from batch 001, plus 15 repeats. Controls 57/60. Abstentions 25.0%.

| | n | precision floor | precision ceiling |
|---|---|---|---|
| batch 001 | 80 | 10.0% [5.2, 18.5] | 43.8% [33.4, 54.7] |
| batch 002 | 240 | **13.3% [9.6, 18.2]** | **38.3% [32.4, 44.6]** |

The batch's formal verdict is **NOISE** — self-agreement 12/15 against a 90% bar — so no
filtered precision figure is published from it. The bound is, per the rule that applies to
every batch.

**All three self-disagreements were abstain-boundary moves; direction reversals were 0 of 15,
and the 8 repeats decided both times agreed 8/8.** The labeller is stable about which word
belongs and unstable about whether the position is decidable — which is what the 25–34%
abstention rate is already saying.

The one lever batch 001 suggested — never propose a rarer word — **failed to replicate**: it
removes 25% of firings and 8 of the 32 agreements, against a pre-registered ceiling of one.

Two independent samples now bound this detector's precision below 44%, and no threshold,
feature, or filter found so far moves it. Full record: `docs/LABELING_LOG.md`; the rules it was collected under, `docs/LABELING_PROTOCOL.md`.

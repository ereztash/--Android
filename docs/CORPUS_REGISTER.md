# The training corpus is the wrong register, and it is measurable

Every model this keyboard ships — the bigram table, the frequency weighting, the real-word error
detector, the abbreviation list — is built from **Hebrew Wikipedia**. Every measurement document
in this repository carries some version of the sentence *"the register is wrong for phone
typing"* as a known limitation.

It was written fifteen times and acted on zero times. This document is what happened when it was
finally measured instead of repeated.

## The experiment

`OpenSubtitles` Hebrew (OPUS v2018, 956 MB gzipped, reachable) is transcribed **dialogue**. It
is not phone messaging, but it is far closer to it than an encyclopedia.

A bounded 404 MB prefix was streamed and cleaned — lines whose longest token exceeds 12
characters, or where under 75% of tokens are in the lexicon, are dropped as damaged by the
subtitle source, which produces things like `מההייתעושהבמקומי`. 7,657,050 lines read, 3,989,126
usable, 48% rejected.

Training sets are **size-matched at 25.6M tokens**, the same as the Wikipedia corpus, so this
compares register and not volume. Every 20th cleaned sentence is held out for evaluation and the
rest train, so the two are disjoint by construction.

## The result

| trained on | evaluated on | recall | false alarms |
|---|---|---|---|
| wikipedia | wikipedia | 62.51% | 0.524% |
| wikipedia | **subtitles** | **60.77%** | **0.739%** |
| subtitles | wikipedia | 41.74% | 0.918% |
| subtitles | **subtitles** | **78.78%** | **0.342%** |

**On conversational text, changing the training corpus is worth +18.01 points of recall and cuts
the false-alarm rate by more than half.** Both axes at once, which almost never happens.

For scale, against everything else measured on this project:

| change | gain |
|---|---|
| adaptive learning (shipped) | +0.57 points |
| distance-2 layer (failed its rule) | +0.53 points |
| **training corpus register** | **+18.01 points** |

Thirty times the next largest, and it costs nothing to ship — it is the same table built from
different text.

## The blend, because it is not a straight swap

A keyboard's user writes messages, and occasionally writes formally. Blending the two corpora
before pruning:

| blend | subtitles recall | subtitles false | wikipedia recall | wikipedia false |
|---|---|---|---|---|
| 0% subtitles | 60.77% | 0.739% | 62.51% | 0.524% |
| **25%** | **72.31%** | **0.526%** | 60.06% | 0.521% |
| 50% | 75.71% | 0.434% | 57.76% | 0.561% |
| 75% | 77.38% | 0.361% | 53.16% | 0.584% |
| 100% | 78.78% | 0.342% | 41.74% | 0.918% |

25% subtitles buys **+11.54 points** on conversational text and **lowers** its false-alarm rate,
while encyclopedic performance moves 62.51% → 60.06% with its false alarms unchanged at 0.52%.
Most of the gain, almost none of the loss.

## What these numbers are NOT

- **Not the published figures.** This ran in a simplified Python harness with a different
  eligibility rule; its Wikipedia baseline is 62.51% / 0.524%, while
  `docs/CONFUSION_MEASUREMENTS.md` reports 64.58% / 0.26% from the Kotlin harness on hash-locked
  slices. The comparison is valid **within** this harness and the absolute values are not
  interchangeable with the published ones.
- **Not a measurement on phone messages.** Subtitles are transcribed *speech*. Whether they are
  the right target for a *typing* keyboard is **UNVERIFIED** — they are demonstrably a better
  proxy than an encyclopedia, and that is all this shows.
- **Not free of the corpus's own problems.** 48% of subtitle lines were discarded as damaged,
  and the filter is a heuristic. What survives still contains transcription artefacts.
- **Not a measurement of typing, still.** Both evaluations inject errors synthetically. Nobody's
  real typing was measured, because no such corpus exists here.

## Why this was missed

The limitation was **known, documented, and repeated in every measurement file**. What was
missing was not awareness — it was ever putting a number on it. A caveat repeated often enough
starts to read like a thing that has been dealt with, and this one had been repeated until it
sounded handled.

The operator asked whether every available resource had been used. That question is what
produced this document.


---

# R1 — shipping the 25% blend

The operator chose **25% subtitles**. What follows is the plan and, first, the prediction, so
that the real pipeline has something to be checked against rather than merely producing numbers
that are then declared good.

## Prediction, recorded before building

The Python A/B ran in a simplified harness. The Kotlin pipeline uses different eligibility rules
and hash-locked slices, so the **absolute** values will differ. What should carry across is the
**direction and rough magnitude**:

| | Python harness said | Kotlin pipeline should show |
|---|---|---|
| conversational recall | 60.77% → 72.31% (**+11.5**) | a large gain, same sign, order of +8 to +15 |
| conversational false alarms | 0.739% → 0.526% (**down**) | must not rise |
| encyclopedic recall | 62.51% → 60.06% (**−2.5**) | a small loss, order of −1 to −5 |
| encyclopedic false alarms | 0.524% → 0.521% (flat) | must not rise materially |

**If the Kotlin pipeline disagrees with this in sign, or by more than roughly double the
magnitude, something is wrong with the port and it gets reported rather than shipped.** A
prediction written afterwards to match whatever came out is not a check.

## What is being built

1. `scripts/build_subtitle_corpus.py` — stream OpenSubtitles Hebrew, clean, tokenise, cache with
   provenance. The cleaning filter is part of the artifact, not an ad-hoc step.
2. `scripts/build_bigrams.py --subtitle-weight 0.25` — blend counts before pruning.
3. A **conversational evaluation slice**, hash-locked and disjoint from subtitle training, so
   that from here on every claim about conversational text has a slice behind it. The repository
   has never had one.
4. Re-measure everything in the Kotlin harness and rewrite every number in every document.

## What does not change

The lexicon, the frequency table, and the abbreviation list are untouched. Only the **bigram
table** is rebuilt, so `GATE-LEX-1/2/3` still verify the same artifacts and only
`GATE-BIGRAM-1`'s hash moves.


---

## R1 RESULT: the prediction held

Measured in the Kotlin pipeline, on hash-locked slices, with the blend already fixed at 0.25.

### Conversational register — the reason for the change

`he_conversational_test.txt.gz`, sha256 `d4cec6cf2c0241a6…`, 6,000 sentences, 16,010 injections.
Both tables measured **in the same run on the same sentences**, so this is a comparison and not
a recollection.

| | recall | false alarms |
|---|---|---|
| wikipedia only (before) | 61.40% | 0.568% |
| **25% subtitles (shipped)** | **74.13%** | **0.344%** |
| delta | **+12.73** | **−0.225** |

Predicted before building: **+8 to +15 points, false alarms must not rise.** Actual: **+12.73**,
false alarms down 39%. The Python experiment transferred.

### Encyclopedic register — what it cost

| | before | after |
|---|---|---|
| recall | 64.58% | **62.31%** |
| false alarms | 0.26% | **0.25%** |

Predicted: −1 to −5 recall, false alarms must not rise materially. Actual: **−2.27**, and false
alarms **improved**.

### Everything else that moved

| | before | after |
|---|---|---|
| next-word top-3 | 9.80% | 9.09% |
| prefix-3 completion top-3 | 49.28% | 47.98% |
| adaptive learning gain | +0.57 | **+0.67** |
| bigram table | 532,168 pairs, 2.85 MiB | 477,180 pairs, 1.60 MiB |

Two of these deserve a note rather than a cheer.

**Adaptive learning appears to improve, and mostly has not.** Its gain is measured against the
static baseline, and that baseline dropped on the Wikipedia eval slice. A larger gap over a
weaker starting point is not the same as helping the user more.

**The prediction figures fell, and are still measured on Wikipedia.** `PredictionAccuracyTest`
and `PredictionMeasurementTest` evaluate on `hewiki_eval_sample.txt.gz`, so they now report a
partly-conversational model judged on encyclopedic text — the worst case for it. The honest
reading is that **those numbers have become less relevant, not that prediction got worse**, and
cutting a conversational slice for prediction as well is left open and NOT DONE.

### What was found on the way

- **The test task did not declare the lexicon assets as inputs.** Assets are read through
  absolute paths, so Gradle served a full CACHED PASS after the shipped table changed
  underneath it. A data change could have shipped with every test reporting green on the old
  data. Now declared.
- **The bigram manifest was written to a fixed path.** Building the comparison table second left
  `BIGRAM_MANIFEST.json` describing a table that is not shipped — and `GATE-BIGRAM-1` could not
  catch it, because the gate verifies the APK against that same manifest. The manifest now lives
  beside the artifact it describes.
- **Building with `--subtitle-weight 0` reproduces the pre-R1 table byte-for-byte**
  (sha256 `90cd6525…`, 532,168 pairs), so the blend code is provably a no-op at zero.

### Re-pinned numbers

Several test floors moved. Stated plainly because this repository treats moving a threshold as a
serious act: these were re-pinned because **the training data changed under an operator
decision**, and the change was checked against a prediction written before the table existed.
That is a different act from adjusting a constant until a suite goes green, which remains
forbidden.

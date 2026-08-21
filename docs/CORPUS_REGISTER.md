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

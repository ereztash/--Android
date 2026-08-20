# Correction engine measurements

Every number here is a claim about **exactly one thing**: this engine, on this corpus, with
this lexicon. Corpus hashes sit beside the tables. Latency figures are **JVM numbers on the
build host** and are never quoted as device numbers.

## Corpora

Built by `scripts/build_golden_corpus.py`, deterministic under a counter-based sha256 stream
(not `random.Random`, whose sequence is a CPython implementation detail).

| Corpus | n | sha256 | What it measures |
|---|---|---|---|
| A — uniform typos | 4,000 | `f9f4ed809b31bef0…` | **The headline accuracy number.** One edit chosen uniformly from insert/delete/substitute/transpose, substitute characters drawn uniformly from the 27 Hebrew letters. |
| B — adjacency typos | 3,998 | `75bddc92ff6e6d3d…` | The adjacency discount measured against **its own assumption**. Partly circular by construction. Never merged into A. |
| C1 — control, known-correct | 4,000 | `6e13ffd6d2f70cc8…` | False-correction rate on words that are definitely right. |
| C2 — control, raw held-out tokens | 4,000 | `c2e89437ece615da…` | The realistic false-correction rate, including proper nouns the lexicon lacks. |

**1,676** uniform corruptions and **1,174** adjacency corruptions were discarded because they
landed on another real lexicon word. "Correcting" such a form is not clearly right, and leaving
them in would depress the numbers for a reason unrelated to the engine.

## Baseline, measured before any weight was chosen

Neutral costs (every edit = 1 unit), frequency used only to break ties, auto-replace limited to
a sole candidate one edit away.

| Metric | Value |
|---|---|
| A top-1 | **52.60%** (2,104 / 4,000) |
| A top-3 | **66.23%** (2,649 / 4,000) |
| A no suggestion offered | 12.08% |
| A auto-replaced | 24.25%, of which **22.35% correct / 1.90% wrong** |
| B top-1 | 45.35% |
| **C1 false auto-replace** | **0.00%** |
| **C2 false auto-replace** | **0.68%** |
| Latency p50 / p95 / p99 | 1.43 / 2.88 / 13.08 ms — *JVM on the build host, NOT a device* |

C1 at exactly 0.00% is the check that makes the rest meaningful: the engine never proposes a
change to a word it recognises, so the accuracy figures are not an artifact of an
indiscriminate replacer.

## The weight sweep, in full

Every configuration tried is listed, including the ones that are worse. Corpus A n=4,000,
B n=3,998, C2 n=4,000.

| configuration | A top-1 | A top-3 | B top-1 | C2 false | A auto-wrong | p95 ms |
|---|---|---|---|---|---|---|
| **BASELINE neutral, freqW=0** | **52.60%** | **66.23%** | 45.35% | **0.68%** | **1.90%** | 2.75 |
| neutral, freqW=25 | 52.60% | 66.23% | 45.35% | 0.68% | 1.90% | 2.37 |
| neutral, freqW=50 | 52.60% | 66.23% | 45.35% | 0.68% | 1.90% | 2.35 |
| neutral, freqW=75 | 52.60% | 66.23% | 45.35% | 0.68% | 1.90% | 2.37 |
| neutral, freqW=100 | 52.60% | 66.23% | 45.35% | 0.68% | 1.90% | 2.40 |
| neutral, freqW=150 | 52.60% | 66.23% | 45.35% | 0.68% | 1.90% | 2.38 |
| neutral, freqW=300 | 51.93% | 65.23% | 44.82% | 0.93% | 5.03% | 2.91 |
| neutral, freqW=400 | 49.15% | 63.05% | 41.92% | 0.93% | 6.68% | 2.73 |
| neutral, freqW=600 | 39.40% | 55.05% | 32.89% | 0.80% | 7.03% | 2.89 |
| adjSub=80 as search cost | 44.63% | 64.15% | **65.71%** | 1.33% | 15.93% | 6.15 |
| adjSub=60 as search cost | 44.63% | 63.70% | 65.08% | 1.33% | 16.15% | 6.18 |
| adjSub=40 as search cost | 32.15% | 48.70% | 49.65% | 1.95% | 24.50% | 7.65 |
| adjSub=80, freqW=100 | 48.03% | 64.98% | 64.56% | 1.38% | 13.35% | 6.05 |
| adjSub=60, transpose=60, freqW=100 | 50.63% | 64.55% | 64.46% | 1.40% | 15.13% | 6.03 |
| rerank adj=80, freqW=0 | 44.63% | 64.15% | **65.71%** | 1.33% | 15.93% | 2.87 |
| rerank adj=60, freqW=0 | 44.63% | 64.15% | 65.71% | 1.33% | 15.93% | 2.92 |
| rerank adj=40, freqW=0 | 32.28% | 49.95% | 65.71% | 1.68% | 22.83% | 2.88 |
| rerank adj=60, freqW=300 | 47.50% | 62.65% | 59.85% | 1.45% | 11.65% | 2.91 |
| rerank adj=60, freqW=400 | 44.78% | 59.68% | 55.15% | 1.13% | 11.23% | 2.86 |
| rerank adj=40, freqW=300 | 40.60% | 57.68% | 61.51% | 1.58% | 19.75% | 2.77 |
| rerank adj=40, freqW=400 | 39.15% | 55.38% | 56.35% | 1.35% | 16.93% | 2.85 |

**No configuration beat the baseline on corpus A.** The baseline was therefore kept, and no
weight was adopted.

---

## Finding 1 — the keyboard-adjacency discount makes correction WORSE. It is not enabled.

This is a conflict with the build spec, which prescribes a "keyboard-adjacency discount" as
part of M5. Reported rather than quietly implemented, and reported rather than quietly dropped.

On the unbiased corpus, adjacency costs **8 points of top-1 accuracy** (52.60% → 44.63%) and
multiplies wrong auto-replacements **more than eightfold** (1.90% → 15.93%). The one column it
improves is corpus B — the corpus generated from adjacency's own assumption — where it adds 20
points (45.35% → 65.71%).

That is exactly the pattern the three-corpus design existed to expose. Had accuracy been
measured only on adjacency-generated typos, the feature would have looked like a large win and
would have shipped while making real correction worse.

**Why it hurts:** on uniform typos the substituted letter is random, so the true correction
usually differs by a *non-adjacent* substitution. Discounting adjacent substitutions therefore
promotes precisely the wrong candidates for this error distribution.

**A prediction of mine that the measurement refuted.** I expected the harm to come from the
discount *widening the candidate set* — three cheap substitutions fitting inside a two-edit
budget — and separated retrieval from ranking to fix it. The re-ranked configuration produced
**identical** accuracy (44.63% / 64.15% / 65.71% / 1.33% / 15.93%) and only improved latency
(p95 6.15 ms → 2.87 ms). So the set-widening hypothesis was wrong: the accuracy harm is
entirely in the ranking. The retrieval/ranking separation was kept anyway, because halving p95
for free is worth having, but it did not do what I built it to do.

**What would change this answer:** a corpus of *real* Hebrew typing errors. The true error
distribution lies somewhere between corpus A (uniform) and corpus B (pure adjacency), and
nothing in this project knows where. The adjacency model is implemented, tested and available
behind `Config.rankingCosts`; it is off because the only unbiased evidence available says it
should be. **Recorded as NOT MEASURED: the real Hebrew typing error distribution.**

## Finding 2 — frequency weighting is arithmetically incapable of doing anything below w=300

`frequencyWeight` had *identical* results at 0, 25, 50, 75, 100 and 150 — not similar,
identical. That was investigated as a suspected bug before being reported as a finding.

The frequency table is healthy: 355,587 entries, 298,162 attested, 57,425 zeros (matching the
A-only word count exactly), 147 distinct values, range 21–178, mean 33.1. Spot checks rank
sensibly — *shalom* 135, *bayit* 129, *miklede*t 64, *oniya* 60.

The cause is arithmetic. The score is `cost − w · (logFreq / 255)`, so overturning a one-edit
gap of 100 needs a frequency gap of `25500 / w`:

| w | required frequency gap | observed range |
|---|---|---|
| 100 | 255.0 | 0–178 — impossible |
| 150 | 170.0 | 0–178 — needs the two extremes |
| 300 | 85.0 | possible |
| 600 | 42.5 | common |

Below w≈300 the weight cannot change any ranking, and above it the effect is actively harmful
(top-1 falls to 51.93%, 49.15%, 39.40% at w = 300, 400, 600). Frequency remains as the sort's
secondary key, where it orders equal-cost candidates and does useful work.

## Finding 3 — 12.08% of typos get no suggestion at all

Two causes, not separated by this measurement: the corrupted form was accepted as valid by the
prefix stripper (a false accept), or no lexicon word lies within two edits. Separating them
needs per-case adjudication and is **NOT MEASURED**.

---

## What these measurements do NOT cover

- **Latency on a device.** Everything here is `x86_64, 4 vCPU, OpenJDK 17.0.19` with a warm
  JIT. A phone will differ, possibly by a lot. The real number needs the `TraceSectionMetric`
  harness of M7 and is **NOT RUN**.
- **Real typing errors.** The corpora are synthetic. Real errors include whole-word
  substitutions, phonetic spellings and dropped spaces, none of which this generator makes.
- **Register.** Source words come from encyclopedic prose, not from the messages people
  actually type.
- **Context.** Ranking uses edit cost, unigram frequency and prefix analysis. No n-gram, no
  sentence context, no personalisation.
- **Corpus B is never the headline number** and must not be quoted as this engine's accuracy.

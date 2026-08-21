# A1 — the labeling rule, written before a single label exists

This document is committed **before** the first batch is generated. Everything it fixes —
the estimand, the sampling frame, the control bars, the decision thresholds, and how
abstentions are counted — is fixed here so that none of it can be chosen after seeing a
result. That is the same discipline S1, P1 and R1 were run under, applied to a measurement
whose instrument is a person.

## Why this measurement exists

`docs/QA_MATRIX.md` carries M11-BASERATE as NOT MEASURED, and
`docs/RELEASE_READINESS.md` states the consequence in its own words: *"without it no
precision figure can be derived from the 0.253% false-alarm rate."*

Every recall number this project publishes is measured on **injected** errors drawn from the
detector's own confusion inventory. That answers "given an error this detector can express,
does context find it?" — a real question, and not the one a user asks. The user asks:
**when the keyboard tells me I am wrong, how often is it right?**

That question cannot be answered by any automatic check, because a real-word error is
defined precisely as one no automatic check can see. It needs a human reader.

## The estimand, stated exactly

> **Precision** = P( a competent Hebrew reader picks the detector's suggestion over the word
> actually in the text | the detector fired at that position ).

Three things this is **not**:

- Not recall, and not a substitute for it. A separate, more expensive measurement.
- Not "is the sentence better". The reader picks which of two words belongs, nothing else.
- Not a claim about phone typing. The frame below is edited subtitle dialogue, which is
  closer to phone typing than Wikipedia and is still not phone typing.

## The sampling frame

- **Corpus**: `lexicon/cache/subtitle-corpus-heldout.txt.gz`, sha256
  `1c2d9ce35ee763ed657df8fb90fc289b79ac18df4369b3ea9ea1381b9db61e3a`, 267,386 sentences /
  1,815,379 words. Held out **by construction** — `scripts/build_subtitle_corpus.py` writes
  every sentence to exactly one of train and held-out — so no sampled position contributed
  to the bigram table the detector reads.
- **Population**: every position where the shipped detector, in the shipped configuration
  and the shipped shape (`checkWide` with `next2 = null`), returns a finding.
- **Sampling**: uniform without replacement from that population, under a recorded seed.
  Not "the interesting ones", not the first N in file order.

## What is in a batch

| stratum | share of 100 | correct answer is known how |
|---|---|---|
| real firings | 80 | unknown — this is what is being measured |
| clean controls | 10 | the detector did **not** fire; the word in the text is the answer |
| injected controls | 10 | we injected the error; the original is the answer |

Controls are shuffled in among the real items and are indistinguishable on screen.

**Controls prove attention, not expertise.** A clean control where the detector stayed
silent is usually easy, and so is an injected one. Passing them means the labeller was
reading; it does not mean the labeller is a good judge of hard cases. Stated so the control
result is not read as more than it is.

## Presentation rules

1. **Blind.** The word in the text and the detector's suggestion are shown in **random
   order**, unmarked. The screen never indicates which is which, and the answer key is
   written to a file the screen does not load.
2. **Forced choice with two escapes.** `1` and `2` are the two words; `3` is *both are
   fine*; `4` is *neither / unclear*. The escapes exist because unpointed Hebrew genuinely
   admits both readings often enough that a labeller forced to choose would manufacture
   certainty.
3. **One position per screen**, with the sentence shown and the target blanked.

## How abstentions are counted — decided now, not later

Let *a* = picked the detector's suggestion, *b* = picked the word in the text,
*c* = both fine, *d* = neither / unclear.

- **Precision = a / (a + b).** Items answered `3` or `4` are excluded from both numerator
  and denominator.
- **The abstention rate, (c + d) / (a + b + c + d), is reported beside it, always.** A
  precision of 70% at a 10% abstention rate and a precision of 70% at a 45% abstention rate
  are different results, and reporting the first number alone would hide the second.
- If abstentions exceed **30%**, precision is reported as NOT DECIDABLE for that batch
  rather than computed on what is left. At that point the question is not "is the detector
  right" but "is this position decidable at all", which is a different measurement.

## The bars, fixed here

| | bar | if it fails |
|---|---|---|
| controls | **≥ 18 of 20 correct** | the batch is **void**. Not rescored, not partially used. |
| abstentions | **≤ 30%** | precision reported as NOT DECIDABLE |
| self-agreement (batch 2+) | **≥ 90%** on repeated items | labels are noise relative to the effect; no number is published |

Scoring rules for controls, so "correct" is not decided later:

- **Injected control**: correct only if the labeller picks the original word. `3` and `4`
  count as misses — the injected error makes one reading clearly better.
- **Clean control**: correct if the labeller picks the word in the text, **or** answers `3`.
  Picking the variant is a miss. `4` is a miss. Unpointed Hebrew makes "both fine" a
  defensible reading of an unmodified sentence; it is not defensible on an injected one.

## Self-agreement, because there is one labeller

The literature measures agreement between annotators (κ ≥ 0.8 is the usual floor). One
labeller cannot produce that. The substitute: **15% of batch *n*'s items reappear inside
batch *n+1***, at least 24 hours later, in a different order and with the two words
re-shuffled independently. Disagreement with oneself above 10% means the labelling noise is
large relative to the effect being measured, and no precision figure is published from that
run.

Batch 1 carries **no** repeats. Its purpose is also to establish the real per-item time,
which is currently a guess of 15 seconds.

## The decision, fixed before the number exists

Read against the lower bound of the 95% Wilson interval, not the point estimate.

| precision (lower bound) | what happens |
|---|---|
| **≥ 60%** | the layer works. Report the figure with its interval and its abstention rate; ship unchanged. |
| **40% – 60%** | roughly a coin flip. Tighten thresholds from the existing sweep tables and re-measure on a **fresh** batch — never on this one. |
| **< 40%** | most flags interrupt correct writing. Raise the margin substantially or withdraw the layer, per the asymmetry at the head of `RealWordErrorDetector`. |

**The 40% floor is not arbitrary.** The keyboard never auto-replaces, so a wrong flag costs
a glance and an ignored suggestion rather than damaged text. That is why the floor is not
50%. It is also why it is not 20%: a strip that is wrong four times out of five trains the
user to stop reading it, and then the four correct catches per hundred are lost too.

## What will be reported no matter what it says

The expected result is **low**. The arithmetic is not hidden: the detector fires on 0.136%
of words in conversational text (measured), and if the true real-word error rate is 0.05%
(**not** measured) then precision is capped at 37% even with perfect recall. Subtitle text
is edited and therefore cleaner than a phone message, so this frame most likely yields a
**lower bound** on what happens on a phone.

A result below every bar above is a finding, published with the same prominence as a result
above them. The two prior escalations in this repository — S1 at 61:1 and P1 at 209:1 —
were recorded as failures and left to the operator. This is the same commitment.

## What this measurement does NOT touch

No keystroke log. No collection from a device. No change to what the app stores. The
labelling runs on a public corpus, on a build host, outside the product.
`GATE-LEARN-1` and `GATE-NET-1` are unaffected, and nothing in `app/src/main` changes.

---

# Addendum, written after the harvest and before any label

**This is an observation, not a rule change.** Nothing above moved. It is here because the
pool the batch was drawn from turned out to say something the eval slices could not, and
because it bounds what batch 1 can possibly answer.

## What the detector actually does on 1.8 million words of clean conversational text

`./gradlew :core:harvestLabelCandidates`, over the whole held-out subtitle slice:

| | |
|---|---|
| words | 1,815,379 |
| eligible sites | 716,292 |
| **firings** | **2,166** — 1.19 per 1,000 words |
| via the adjacent window | 2,156 (99.54%) |
| via the **prior** fallback (P1) | **8** (0.37%) |
| via the **distance-2** table (S1) | **2** (0.09%) |

The distance-2 table — 387,300 bytes in the release APK — **speaks twice in 1.8 million
words** of unmodified conversational text. The prior fallback speaks eight times.

## This does not contradict the S1+P1 verdict. It explains it.

The recall figures for both layers (+267 catches on the conversational test slice) were
measured on **injected** errors, and injection is what manufactures the positions these
layers serve. Replacing a word with a homophone makes the typed word unattested in its
context, which is exactly the condition `requireNoSupportForTyped` looks for. On text nobody
corrupted, the adjacent window almost always has something to say, and the two new layers
never get a turn.

Both facts hold together, and both were already measured:

- On injected errors the layers add **+653 / +267 catches** — they fire where injection
  created blindness.
- On clean text they add **+2 / +0 false alarms** — they almost never fire at all.

So they are close to free. They are also close to silent. That is the same coin.

**It sharpens the recommendation already recorded in `docs/CONFUSION_MEASUREMENTS.md`**:
387,300 bytes for a layer that speaks twice per 1.8 million words is a worse trade than the
+0.11 recall points made it look, and it is the operator's call rather than one to be taken
here.

## What batch 1 can and cannot answer

Sampling is uniform, as the protocol fixes it, and 99.54% of the pool is the adjacent path.
The chance that 80 uniform draws contain even one non-adjacent item is **31%**.

- **Batch 1 measures the precision of the adjacent detector** — the layer that has been
  shipping since M11, and the one responsible for essentially every flag a user will ever
  see. That is the right first question and it is worth the 75 minutes.
- **Batch 1 says nothing about S1 or P1.** Reporting it as though it did would be a claim
  wider than the measurement.

Measuring the added layers would need a **stratified** batch and therefore its own
pre-registered rule, because stratifying changes what the control bar and the decision band
mean. It would also need a much larger corpus: the entire held-out slice contains **ten**
such positions, which is not a denominator anything can be concluded from.

---

# Batch 001 — result, and one rule added afterwards

**Labelled 2026-08-21 by the operator. 100 screens, 10.5 minutes, median 4.5 s per item.**
The protocol guessed 15 seconds. It was **3.3× too pessimistic**, which changes the economics
of every future batch: 300 items is about 23 minutes, not 75.

## What the scorer said

| | | |
|---|---|---|
| controls | **18 / 20** | passes, at the bar exactly |
| abstentions | **27 / 80 = 33.8%** | above the 30% bar |
| verdict | **NOT DECIDABLE** | precision not computed, as the rule requires |

Both control misses are attributable to corpus noise rather than inattention, and both are
worth naming because they are a property of the frame:

- `b001-046` — *"ג ואנה ואה לכאן אל תדאג"*. That is `ג'ואנה` with its geresh stripped by the
  corpus cleaner. The item is a garbled string, not a Hebrew sentence.
- `b001-079` — a 20-word run-on of two merged subtitle lines. Answered "unclear", which is
  the honest answer to it.

## A rule added AFTER seeing the data, and why that is not threshold-shopping

The abstention bar stops precision being computed on the decided subset, because that subset
is the easy half by construction. It does **not** stop a bound computed over the **full**
denominator:

> **floor** = agreed / n — every abstention counted as a loss
> **ceiling** = (agreed + abstained) / n — every abstention counted as a win

This bracket is not an estimate of precision under a favourable assumption. It is arithmetic:
for any a, b, c, d, `a/n ≤ a/(a+b) ≤ (a+c+d)/n`. **The bound always contains the filtered
precision and is therefore a strictly weaker claim than the one the bar forbids.** Adding it
cannot make a result look better than the forbidden number, which is the test for whether a
rule added after the fact is self-serving. It is added here, in writing, on the day it was
added, rather than applied silently.

**The 30% abstention bar does not move, and neither does anything else above.**

Two clarifications of what was already written, not changes to it:

- The control bar "18 of 20" is **90% of controls**, and scales with batch size.
- Every batch reports the bound alongside its verdict, including a NOT DECIDABLE one.

## Batch 001's numbers

| | n=80 real firings |
|---|---|
| agreed with the detector | **8** |
| preferred the word in the text | **45** |
| could not decide | **27** |
| **precision floor** | **10.0%** — 95% Wilson [5.2, 18.5] |
| **precision ceiling** | **43.8%** — 95% Wilson [33.4, 54.7] |

**The "ship as is" band starts at 60% on the lower bound. It is excluded at every reading of
this batch**, including the one that hands the detector every ambiguous position it could
possibly want.

## The margin sweep, run over the labels rather than over new labels

The decision rule's middle band says *tighten thresholds and re-measure*. The labels are a
fixed asset and can answer that for free, so they were asked first. Each labelled firing
carries the evidence advantage that produced it, so any higher `Config.margin` can be
simulated exactly:

| margin | fires | agreed | preferred text | abstained | floor | ceiling |
|---|---|---|---|---|---|---|
| **21 (shipped)** | 80 | 8 | 45 | 27 | 10.0% | 43.8% |
| 24 | 64 | 6 | 35 | 23 | 9.4% | 45.3% |
| 28 | 43 | 4 | 24 | 15 | 9.3% | 44.2% |
| 32 | 35 | 3 | 18 | 14 | 8.6% | 48.6% |
| 40 | 21 | 2 | 10 | 9 | 9.5% | 52.4% |
| 56 | 9 | 2 | 4 | 3 | 22.2% | 55.6% |
| 64 | 4 | 0 | 3 | 1 | 0.0% | 25.0% |
| 80 | 2 | 0 | 2 | 0 | 0.0% | 0.0% |

**There is no margin to tighten to.** Agreements and disagreements have the *same* evidence
distribution — median advantage 28 for both, ranges 21–63 and 21–104. Raising the bar discards
correct catches at the same rate as wrong ones, and past 64 it discards all of them.

The middle band of the decision rule assumed a threshold existed that traded recall for
precision. On this evidence it does not.

## Nothing else separates them either

Every feature the detector could condition on, tested against the labels:

| feature | separates agreement from disagreement? |
|---|---|
| evidence advantage | **no** — 16.0% / 12.5% / 16.7% across three bands |
| context words available | no — 79 of 80 had both neighbours |
| word length | no monotone signal |
| position in sentence | no — 18.2% vs 12.9% |
| sentence length | no |
| **suggestion rarer than the typed word** | **the only candidate: 0 agreements in 12 decided items** |

The last row is the one lever this batch found: when the detector proposes a word **less
frequent** than the one already written, it was never right. Filtering those out removes 17 of
80 firings and none of the 8 agreements. It lifts the floor from 10.0% to 12.7% — real, and
nowhere near enough. On 12 decided items it is suggestive, not established, and it is a
hypothesis for batch 002 rather than a change to make now.

## What batch 001 does NOT establish

- **The labels are unrepeated.** The protocol's self-agreement check runs in batch *n+1* and
  has not run. Every number above rests on one person's judgment, once. This is the largest
  open weakness and it is not a small one.
- **n = 80.** The bound is wide.
- **The frame is edited subtitle text**, 17 of 80 items of which carried a corpus artefact.
  On the 63 artefact-free items all 8 agreements remain and abstention falls to 29%, so noise
  does not explain the result — but the frame is still not phone typing.
- **Nothing about S1 or P1**, for the reason in the addendum above.

## Batch 002 — what it is for

Not to tune. There is nothing to tune to. It exists to test the two things batch 001 could not:

1. **Are the labels reliable?** 15 repeats from batch 001, per the rule.
2. **Does the rarer-suggestion filter hold up?** Pre-registered here, before the batch is cut:
   *the filter is worth adopting only if it removes at least 15% of firings while removing no
   more than one agreement in the batch.*

240 real + 30 clean + 30 injected + 15 repeats = 315 screens, about 24 minutes at the pace
batch 001 actually measured.

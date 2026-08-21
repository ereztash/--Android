# A1 — the labelling log

Every batch, every result, every finding, in the order they happened. The **rules** these were
collected under are in [`LABELING_PROTOCOL.md`](LABELING_PROTOCOL.md), which is kept free of
results so that its hash means "the rules" and nothing else.

Nothing in this file is ever rewritten when a later batch disagrees with an earlier one.

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

---

# Batch 002 — NOISE, a failed replication, and a bug in the scorer

**315 screens, 30 minutes, median 4.0 s per item.** Controls **57 / 60** against a bar of 54 —
passed comfortably, unlike batch 001 which scraped it. Abstentions **25.0%**, under the 30%
bar this time.

## Verdict: NOISE. No precision figure is published from this run.

Self-agreement on the 15 repeats from batch 001 came in at **12 / 15 = 80%**, below the 90%
bar. The rule says no precision figure, and there is none here.

**The bar was flagged as underpowered before the batch was cut**, and it is: the 95% interval
on 12/15 runs from roughly 55% to 93%, so "below 90%" is itself barely distinguishable from
"above". That was written down in advance and it does not change the verdict. A bar that only
binds when it is convenient is not a bar.

## What the three disagreements actually were

| before → after | count |
|---|---|
| **direction reversal** (agreed ↔ overruled) | **0** |
| moved across the abstain boundary | 3 |
| both-fine ↔ unclear | 0 |

Among the 8 repeats decided both times, agreement was **8 / 8**.

```
unclear → text   שזה [הביט] עליהם כמחלה מדבקת בכמה גלקסיות     (offered: הבית)
text → both      אתה יודע יום אחד [יספרו] את הסיפור            (offered: ישפרו)
text → unclear   החברה שלו [מולי] חולה                          (offered: מבלי)
```

So the labeller was **perfectly stable about which word belongs** and unstable only about
whether the position is decidable at all — which is what unpointed Hebrew does to a careful
reader, and is the same instability the 25–34% abstention rate is already reporting.

This matters for which statistic survives. The **floor** counts agreements over the full
denominator, so it moves only on a direction reversal — of which there were none in 15
repeats. The **filtered precision** excludes exactly the items whose classification is
unstable, so it is the statistic the noise attacks. The rule suppressing the filtered figure
and publishing the bound turns out to be suppressing the right one, for a reason nobody knew
when it was written.

**That is an observation, not a licence.** It is not grounds for publishing a figure from a
NOISE run, and none is published.

## The pre-registered filter test: FAILED to replicate

Batch 001 found that a suggestion **rarer** than the typed word was never right — 0 of 12
decided. The rule, fixed before batch 002 was cut: *adopt only if it removes at least 15% of
firings while removing no more than one agreement.*

| | batch 002 | required |
|---|---|---|
| firings removed | 60 of 240 = **25.0%** | ≥ 15% ✓ |
| agreements lost | **8** | ≤ 1 ✗ |

**Not adopted.** The batch-001 signal was 0 of 12 on a sample too small to carry it, and a
fresh sample dissolved it. This is what pre-registration and a held-out batch are for, and it
is the second time in this project that a promising lever failed on the slice it had not been
chosen on.

## The bound, from both batches

Independent samples, disjoint items, same detector, same frame:

| | n | floor | ceiling |
|---|---|---|---|
| batch 001 | 80 | 10.0% [5.2, 18.5] | 43.8% [33.4, 54.7] |
| **batch 002** | **240** | **13.3% [9.6, 18.2]** | **38.3% [32.4, 44.6]** |

Neither batch's ceiling reaches the 60% band at any confidence. They are not pooled: each
failed a different bar, and pooling two batches that each failed to reach a verdict in order
to manufacture one is the exact move this protocol exists to prevent.

## A bug in the scorer, found by this batch

The self-agreement gate ran **after** precision had already been printed, so a NOISE run
leaked the figure the verdict exists to suppress — the same defect this project keeps finding
in other clothes: a check placed where it cannot do its job. The gate now runs first, and the
verdict text says which statistic survives and why.

## What would settle it — proposed, not adopted

The self-agreement rule as written compares the full four-way bucket, so an abstain-boundary
move costs exactly as much as a direction reversal. For the floor, they are not remotely the
same, and the data shows the two happen at very different rates.

**Proposal for batch 003, requiring the operator's approval and a commit before any labels are
collected:** measure the two separately, and let the direction bar govern whether the floor may
be published, while the four-way bar continues to govern the filtered figure.

If approved, the cheapest possible test of it is **40 repeats drawn from batches 001 and 002
and nothing else — about 3 minutes** — which is enough to distinguish a direction-reversal rate
near 0% from one near 10%. No fresh items are needed, because the question is about the
labeller and not about the detector.

**This proposal is recorded here and is not in force.** Batch 002's verdict stands at NOISE.

---

### Batch 003 as cut

`seed 20260824`, 50 screens, drawn exactly as amendment 2 specifies:

| | |
|---|---|
| repeats from batch 001 | 12 |
| repeats from batch 002 | 28 |
| of which decided the first time | **30** (26 `in-text`, 4 `suggested`) |
| of which abstained the first time | **10** (8 `unclear`, 2 `both-fine`) |
| clean controls | 5 |
| injected controls | 5 |
| control bar | 9 of 10 |

Verified before release: no item overlaps batch 002's repeats, no item appears twice inside
the batch, and the two words were re-shuffled independently — the order changed on 15 of 40,
which is what an independent coin flip looks like.

The scorer's `--self-test` now exercises amendment 2's two new paths against this key: a
labeller who repeats themselves must come back **STABLE**, and one who reverses half their
decided items must come back **UNSTABLE**. Both were demonstrated before the batch was sent.
Code reached only by a batch shape that had never existed would otherwise have shipped
unexercised.

---

# Batch 003 — the instrument holds. The floors are publishable, and they decide it.

**50 screens, 4 minutes, median 4.4 s.** 40 repeats (12 from batch 001, 28 from batch 002)
and 10 controls. No fresh firings: this batch measured the labeller, not the detector.

| | | |
|---|---|---|
| controls | **10 / 10** | bar was 9 |
| **direction stability** | **25 / 26 = 96.2%** | bar ≥ 90% — **CLEARS** |
| four-way agreement | 30 / 40 = 75.0% | bar ≥ 90% — fails, as before |
| reversals | **1** | |
| abstain-boundary moves | 9 | |

The split amendment 2 pre-registered is exactly what the data does. The labeller is stable
about **which word belongs** and unstable about **whether the position is decidable** — and the
second kind of instability does not touch the floor.

## The one reversal, and which way it points

> `גבר יכול ⟦לטבוע⟧ בעיניים האלו` — the detector proposed `לתבוע`.

*A man could drown in those eyes.* The first pass endorsed the detector; the second preferred
the text, which is right — `לטבוע בעיניים` is the idiom and `לתבוע` (to sue) is not.

**The single observed reversal moves against the detector.** Label noise, as actually measured
here, inflates the agreement count rather than deflating it, so the floors below are if
anything generous.

## The floors, now publishable

| | n | floor | 95% | ceiling |
|---|---|---|---|---|
| batch 001 | 80 | 10.0% | [5.2, 18.5] | 43.8% |
| batch 002 | 240 | 13.3% | [9.6, 18.2] | 38.3% |
| *pooled* | *320* | *12.5%* | *[9.3, 16.6]* | *39.7%* |

The pooled row is *italic* because the refusal to pool, recorded under batch 002, was made
when neither batch had a publishable statistic at all. That has changed for the floor
specifically, and two disjoint uniform samples of one population are poolable. It is shown as
secondary rather than headline because the conclusion does not depend on it: **10.0%, 13.3%
and 12.5% are the same answer.**

**The decision rule's floor is 40%. Its ship band starts at 60%. The 95% upper bound of every
reading above is under 19%.**

## The last stone: no confusion pair is different

Restricting the layer to its best-performing letters was the only untested escape. Pooled,
n=320:

| pair | n | agreed | floor | 95% upper |
|---|---|---|---|---|
| ע/א | 43 | 10 | 23.3% | 37.7% |
| ח/כ | 22 | 5 | 22.7% | 43.4% |
| כ/ק | 12 | 2 | 16.7% | 44.8% |
| ש/ס | 21 | 3 | 14.3% | 34.6% |
| ק/כ | 10 | 1 | 10.0% | 40.4% |
| ט/ת | 19 | 2 | 10.5% | 31.4% |
| ב/ו | 51 | 5 | 9.8% | 21.0% |
| ו/ב | 50 | 4 | 8.0% | 18.8% |
| א/ע | 53 | 4 | 7.5% | 17.9% |
| **ס/ש** | 24 | **0** | **0.0%** | 13.8% |

`ת/ט` reads 42.9% on **seven** items and is not a result.

The best pair with a usable denominator is `ע/א` at 23.3%, whose 95% upper bound is 37.7% —
below the rule's 40% floor, let alone the 60% ship band. Restricting to it would mean a
keyboard that is wrong roughly three times in four when it speaks, on one sixth of the
positions it currently speaks at. `ס/ש` is 0 for 24.

## Where that leaves A1

Everything that could have rescued this layer has now been tested and has failed:

| escape | result |
|---|---|
| raise the evidence margin | agreements and disagreements share one distribution; past 64 all agreements are gone |
| never propose a rarer word | passed in batch 001 on 12 items, **failed to replicate** on 240 |
| restrict to the best letters | best usable pair's upper bound is 37.7% |
| blame the labeller | **direction stability 96.2%**, and the one reversal favours the text |
| blame corpus noise | on artefact-free sentences all agreements remain; abstention falls 5 points |

Amendment 2 fixed the consequence before these labels existed: *the layer is withdrawn or
restricted to a configuration that has yet to be found.* No such configuration was found.

**This is a recommendation to the operator and not a change made here.** The layer has shipped
since M11, three separate escalations in this project have been left to the operator rather
than resolved by the person who kept wanting the feature to pass, and this is the fourth.
Withdrawing is one constant: `RealWordErrorDetector` is no longer passed to `PredictiveEngine`
in `CorrectionController.build`, and `he_skipgrams.bin` leaves the assets with it.

## What is still NOT measured

- **Recall on authentic errors.** These 320 positions are ones the detector *spoke* at. What
  it stays silent on is untouched, and M11-BASERATE remains open.
- **Phone typing.** Edited subtitle dialogue is the frame throughout.
- **A second annotator.** Everything here is one person, checked against themselves.

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

# Rule changes

Every change to the rules above, in the order they were made, each committed **before** the
batch it first governed was cut. Results, batch records and findings are **not** here — they
are in [`LABELING_LOG.md`](LABELING_LOG.md).

## Why they are in a different file

This document's sha256 is recorded in every batch's answer key, and the scorer compares it
against the file on disk so that labels collected under one revision cannot be read under
another. While results lived here too, appending a batch's numbers changed the hash and fired
that warning for a reason that had nothing to do with the rules. It fired twice that way. A
warning that is usually benign is a warning people learn to skip, which is the same argument
this repository makes against a permanently red build — so the file the hash covers now
contains rules and nothing else.

**Batches 001–003 recorded the hash of the combined file** and will therefore report drift
against this one. That is expected and is not a rule change; the diff that produced it is this
split.

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

# Amendment 2 — self-agreement splits in two. Written before batch 003 exists.

Approved by the operator after batch 002. **No labels for batch 003 have been collected, and
the batch has not been cut, at the time this is committed.**

## What changes

The self-agreement bar compares the full four-way bucket, so an abstain-boundary move
(`in-text → unclear`) costs exactly as much as a direction reversal (`in-text → suggested`).
For the two statistics this project reports, they are not remotely equivalent:

| statistic | moves when… |
|---|---|
| **filtered precision** = a/(a+b) | either kind of change — abstentions are excluded, so an item crossing the boundary enters or leaves the denominator |
| **floor** = a/n | **only** a direction reversal — an item that becomes "unclear" was not an agreement either way |

Batch 002 measured 0 direction reversals and 3 boundary moves in 15 repeats. That is a
suggestive split on a sample too small to carry it, which is exactly the position the
rarer-suggestion filter was in one batch before it failed to replicate. So it gets its own
pre-registered test rather than being believed.

## The two bars, from batch 003 onward

| bar | governs | threshold |
|---|---|---|
| **four-way agreement** | the filtered precision figure | **≥ 90%**, unchanged |
| **direction stability** = 1 − reversals / (items decided **both** times) | whether the **floor** may be published | **≥ 90%** |

Both are reported on every batch that carries repeats. Neither replaces the other, and the
four-way bar is not being relaxed — a batch that fails it still publishes no filtered figure.

## Batch 003, and how its repeats are drawn

**50 screens: 40 repeats, 5 clean controls, 5 injected controls.** Control bar **9 of 10**,
which is the same 90% every batch has used. No fresh firings: the question is about the
labeller, not the detector.

The 40 are drawn **stratified by what was answered the first time**:

- **30** from items **decided** the first time (`suggested` or `in-text`)
- **10** from items **abstained** the first time (`both-fine` or `unclear`)

**Stratifying is deliberate and it is not sampling toward a result.** Direction stability is
only defined on items decided twice, and an unstratified draw wastes most of its items on
pairs that can never contribute to it. From batch 002's repeats, an item decided once was
decided again 8 times in 9, so 30 first-decided items should yield roughly 27 usable pairs —
against roughly 21 from an unstratified 40. The 10 abstain-first items measure movement in the
other direction and stop the design from only being able to see stability.

Items already repeated in batch 002 are excluded, so every batch-003 repeat is a clean second
observation of an item seen exactly once before.

## What each outcome means — fixed now, not after

**If direction stability ≥ 90%:** the floors from batches 001 and 002 become publishable
figures. Their lower bounds are **5.2%** and **9.6%**. Both sit far below the 40% floor of the
decision rule, and the margin sweep has already established that no threshold trades recall
for precision here. **That combination means the layer is withdrawn or restricted to a
configuration that has yet to be found — and this consequence is written down before the
labels exist precisely so it cannot be relitigated afterwards.**

**If direction stability < 90%:** the labeller is not reliable about direction, and *nothing*
from batches 001, 002 or 003 supports any claim about this detector's precision. A1 returns
**NOT MEASURED**, the shipped feature stays exactly as it is, and the honest conclusion is that
this question needs a second annotator rather than a third batch.

**Either way, the detector is not changed on the strength of batch 003 alone.** It is a check
on the instrument, not on the product.

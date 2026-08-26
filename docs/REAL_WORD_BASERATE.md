# R2 — does the withdrawn layer even see a dyslexic writer's errors?

**Pre-registration. Written before the detector has been run on a single one of these tokens.**

## Why this measurement exists

`P7` withdrew the adjacent real-word error layer: precision **[12.5%, 39.7%]** against a 40% rule
registered before the labels existed, and **4.83 false alarms per true positive**. That decision
stands and nothing here reopens it.

But `R1` changed one input to it. The writer is **dyslexic**, and **14 of his 25 errors are
real-word errors** — the typed form is itself a valid lexicon word, so the correction engine
returns `emptyList()` and says nothing. In `CORRECTION_MEASUREMENTS.md`'s corpus A, roughly 30% of
*generated* corruptions landed on a real word and were discarded as unscoreable. Here it is the
majority of what actually happens.

Precision is a function of base rate. `P7` was measured on the corpora this repository uses,
which are not this population. So the question is not *"was P7 wrong to be withdrawn"* — it was
not — but *"was it measured on the wrong people"*.

## What this can and cannot answer

**It cannot produce a precision number.** With 14 positives and 119 negatives, any precision
figure carries an interval wide enough to contain both 12.5% and 90%. **No precision value from
this set will be compared to `[12.5%, 39.7%]`**, and that is decided now.

**It is a screening test with an asymmetric read**, exactly like `R1`:

- **Low recall kills the idea outright.** If the detector does not fire on these errors at all,
  then a higher base rate of real-word errors cannot help it — the layer is blind to this
  population's errors regardless of how common they are. That is readable at n = 14.
- **High recall establishes nothing.** It would only mean the question deserves a real corpus.

## Predictions, committed now

| # | prediction | falsified if |
|---|---|---|
| 1 | the detector flags **≤ 5 of the 14** real-word errors — these are phonetic substitutions, not the classic `אם`/`עם` confusion pairs it was built around | it flags 6 or more |
| 2 | it flags **≤ 2 of the 119** correctly-spelled tokens, consistent with `W1`'s 0.33% false-alarm rate on typed text (which predicts ~0.4 here) | it flags 3 or more |

## Positive control

**PC-1.** Take a correct token from the messages, substitute it with a real-word neighbour known
to be in the detector's reach, and confirm the detector **fires**. If it cannot be made to fire at
all, a recall of zero below means nothing about the population and everything about the harness.

## Stopping rule

One pass. **Nothing is un-withdrawn by this.** `GATE-WITHDRAWN-1` continues to fail the build if
`RealWordErrorDetector` is constructed in the shipped path, and that stays true whatever comes
out. The only thing at stake is whether a future corpus is worth collecting.

## Not measured

- **One dyslexic writer.** Generalising to dyslexic typists needs more than one, and generalising
  to olim is a second extrapolation on top of the first.
- **The 119 negatives are his correct tokens**, not a false-alarm corpus. `C2` in
  `CORRECTION_MEASUREMENTS.md` exists for that and is 4,000 tokens.

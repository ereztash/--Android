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

---

# Result

```
PC-1  'אני הולך אם הכלב'  ->  FIRES, suggests 'עם' (advantage 98)

recall on real-word errors  :  2 / 14 flagged, both with the right suggestion
    אנכנו -> אנחנו   (advantage 86)
    מחור  -> מכור    (advantage 23)

false alarms on correct text:  1 / 119
    אוטו  -> אותו    (advantage 40)
```

| # | prediction | measured | |
|---|---|---|---|
| 1 | flags ≤ 5 of the 14 real-word errors | **2** | **HELD** |
| 2 | flags ≤ 2 of the 119 correct tokens | **1** | **HELD** |

## The verdict the stopping rule requires

**Low recall. The idea is dead in this form.**

Fourteen percent recall on the error class that *defines* this population. Reviving the layer
because real-word errors are common for dyslexic writers does not survive contact with the fact
that **the layer does not see them.** A higher base rate cannot rescue a detector that stays
silent, and that was written down before the number was known.

The one false alarm is worth reading, because it is the withdrawal in miniature. `אוטו` — *car* —
correctly typed, flagged as though the writer meant `אותו`, *it*. The bigram table has simply seen
`מוציא אותו` more often than `מוציא אוטו`. This is precisely the 4.83-false-alarms-per-true-positive
behaviour that `P7` withdrew, reproducing on the third correctly-spelled token of the set.

## Why it missed twelve, which is the useful part

The misses are not random. They fall into two nameable groups, and neither is a tuning problem.

**1. Three of this writer's substitution classes are not in the table.** `HOMOPHONE_PAIRS` holds
א/ע, ח/כ, כ/ק, ת/ט, ב/ו, ס/ש. `R1` also produced:

| pair | example | in the table? |
|---|---|---|
| ה ↔ ח | `בנוסה` → `בנוסח` | **no** |
| ה ↔ א | `החר` → `אחר` | **no** |
| ו ↔ ה | `איפו` → `איפה` ×2 | **no** |
| ו ↔ י | `בסיגנונות`, `שמיצרים`, `ליפניי` | held out **deliberately** as `KTIV_MALE`, off by default |

**2. A third of the errors are not substitutions at all.** `מוצי`→`מוציא`, `לבו`→`לבוא`,
`יבו`→`אבוא` are dropped silent **א**; `אהבתה`→`אהבת` is an inserted **ה**. The detector generates
variants only by *substituting* letters from a pair table. It has no deletion or insertion model,
so this class is outside its reach by construction, not by threshold.

**Two more were in reach and still missed** — `מוחנים`→`מוכנים` and `מורקב`→`מורכב` are both
table pairs. Those failed on evidence, not on reach, and are the only two of the twelve that a
margin change could touch.

## What must not happen next

The obvious move — add ה/ח, ה/א, ו/ה to the table, add a silent-א deletion arm, re-run on `R1`,
watch recall climb — is **exactly** the mistake `CORRECTION_MEASUREMENTS.md` finding 1 documents.
Adjacency gained 20 points on the corpus generated from its own assumption and lost 8 on the
unbiased one. Deriving the pairs from `R1` and scoring them on `R1` rebuilds corpus B, and this
time from 25 items belonging to one person.

`R1` says which pairs a dyslexic writer produces. Only a corpus `R1` did not produce can say
whether weighting them helps.

## Not measured

- **One dyslexic writer.** Two flagged, twelve missed, one false alarm: every count here is small
  enough that the next writer could reorder it entirely.
- **The 119 negatives are his own correct tokens**, not a false-alarm corpus. `C2` in
  `CORRECTION_MEASUREMENTS.md` is 4,000 tokens and exists for that.
- **Nothing is un-withdrawn.** `GATE-WITHDRAWN-1` still fails the build if the detector is
  constructed in the shipped path, and this result argues for keeping it that way.

# W8 — spelling correction, measured on words a person typed

**Written before any code.** Predictions, the control and the kill condition below were
committed in the commit that created this file.

## Why

`docs/CORRECTION_MEASUREMENTS.md` publishes this project's **best number**: **52.60% top-1**,
**66.23% top-3** on 4,000 uniform typos. That document names its own limits, and one of them is
now testable:

> **Register.** Source words come from encyclopedic prose, not from the messages people
> actually type.

`W1` then measured what register is worth elsewhere: completion top-3 moved by a factor of 2.3
between transcribed and typed text, and the repository's headline was overstated because of it.
**The correction headline has never been measured on typed Hebrew, and the typed slice now
exists.**

## What changes, and what deliberately does not

Only the **source words**. `build_golden_corpus.py` draws them from held-out Wikipedia; corpus D
draws them from `he_typed_raw.txt.gz`. The corruption generator, the discard rule, the counter-based
seed stream, the minimum word length and the engine are **byte-identical** — otherwise the
comparison measures the harness.

The source-word filter is unchanged too, and it matters: a source word must **already be in the
lexicon**. So this does *not* measure proper nouns or slang — those are excluded by construction
in both corpora. What differs is the **frequency and length profile** of ordinary Hebrew words.

## Predictions, committed now

- **W8-P1** — top-1 on typed source words is **at least 3 points lower** than on wiki source
  words measured in the same run. Typed Hebrew's in-lexicon vocabulary is shorter and more
  common, and short words have denser edit neighbourhoods.
- **W8-P2** — the **discard rate** — corruptions thrown away for landing on another real word —
  is at least **1.5×** the wiki rate. `H1` measured 50.78% of conversational tokens sitting one
  `ו`/`י` deletion from another real word; that density should show up here.
- **W8-P3** — false auto-replace on a control of raw typed tokens is **higher** than `C2`'s
  **0.68%**.

## Control

**PC-1** — the harness must reproduce corpus A at **52.60% / 66.23%** to within **0.1 points**.
If it does not, this is not the measurement those numbers came from and nothing is comparable.

## Kill condition

If corpus D lands within noise of corpus A on every metric, **register does not matter for
correction**, the published headline stands unqualified, and `W8` buys nothing. That is written
here in advance so it cannot later be reported as something else.

## What this cannot say

`M5-REAL-TYPOS` stays **NOT MEASURED**. Both corpora are **synthetic** — one uniform edit per
word. Real errors include whole-word substitutions, phonetic spellings and dropped spaces, and
this generator makes none of them. Changing the source register does not make a synthetic typo
real.

`M10-REGISTER` stays **NOT MEASURED**: Ynet comments are typed, and they are not phone messaging.

---

## Result

`./gradlew :core:test --tests '*CorrectionRegisterTest*' -PrunCorrectionRegister=1`.

**PC-1 — PASS.** Corpus A reproduced **52.60% / 66.23%** exactly. A second published cell,
`C2` false auto-replace, reproduced at **0.68%** exactly.

| source register | top-1 | top-3 | no suggestion | avg word length | false auto-replace |
|---|---|---|---|---|---|
| **A — encyclopedic (wiki)** | 52.60% | 66.23% | 12.08% | 5.52 | 0.68% |
| **D — typed (Ynet comments)** | **51.15%** | **64.48%** | 11.93% | 5.27 | **0.60%** |
| delta | **−1.45** | **−1.75** | −0.15 | −0.25 | **−0.08** |

### All three predictions falsified, and all in the same direction

| | prediction | outcome |
|---|---|---|
| **W8-P1** | typed top-1 ≥ 3 points lower | **FALSIFIED — −1.45** |
| **W8-P2** | discard rate ≥ 1.5× | **FALSIFIED** — 1,676 → 1,716, **×1.02** |
| **W8-P3** | false auto-replace higher on typed | **FALSIFIED** — it is **lower**, 0.68% → 0.60% |

I predicted typed Hebrew would be materially harder to correct. It is very slightly harder to
rank and very slightly **safer** to auto-replace.

---

## What was learned

**Correction is register-robust. Completion is not.** That asymmetry is the finding:

| | between registers |
|---|---|
| prefix-1 completion top-3 (`W1`) | 5.43% → 23.72% → 10.33% — a factor of **2.3** on the correction that mattered |
| correction top-1 (`W8`) | 52.60% → 51.15% — **1.45 points** |

The mechanism is visible in what each depends on. **Correction is a local edit-distance search
over the lexicon**, and the lexicon covers both registers about equally — `H1` measured 99.16%
of conversational tokens present as surface forms against 95.01% of wiki. **Completion depends
on the bigram and frequency model**, which is built from a particular corpus and carries that
corpus's register with it.

**So the published 52.60% survives.** It needs a qualifier — *measured on encyclopedic source
words; on typed source words it is 51.15%* — and not a correction. That is the opposite of what
happened to the completion headline, and it is worth saying plainly: **this number was not
overstated.**

## A correction inside this run

The first version of this harness measured `!isValid(w) && suggest(w).isNotEmpty()` and called
it *false auto-replace*. **It is not that quantity.** That counts the engine *offering*
something, which is far more common and strictly weaker. It reported **4.63%** on `C2` — which
against a published **0.68%** would have read as a 7× regression in a number measuring something
else entirely.

Fixed to call `shouldAutoReplace`, it reproduces 0.68% exactly, which is what turned the mistake
into a passing control. The weaker event is now reported beside it rather than instead of it:
the engine offers *something* on a correct word **4.63%** of the time on wiki and **3.95%** on
typed.

## What this still cannot say

`M5-REAL-TYPOS` stays **NOT MEASURED**. Both corpora are **synthetic** — one uniform edit per
word — and real errors include whole-word substitutions, phonetic spellings and dropped spaces
that this generator does not make. **Changing the source register does not make a synthetic typo
real**, and that limit is untouched by anything above.

`M10-REGISTER` stays **NOT MEASURED**.

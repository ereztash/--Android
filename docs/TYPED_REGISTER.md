# W1 — the first evaluation slice in this repository that a person typed

**Written before the builder and the harness exist.** Predictions, controls, the statistical
rule and the kill condition below were committed in the commit that created this file. If a
number here disagrees with a number the harness later printed, the harness is right and this
file is the record of what was expected.

`docs/PROGRAM.md` W1. Follows from `A1`, which measured that transcription and typing do not
share an alphabet — geresh/gershayim ×604, brackets ×27, emoji ×458 — and from `O1-REGISTER`,
which measured that swapping encyclopedic prose for transcribed dialogue moved prefix-1
completion top-3 from 5.35% to 23.72%.

Both of those leave the same question open. **`O1-REGISTER` compared two registers neither of
which anyone typed.** Wikipedia is written and edited; OpenSubtitles is transcribed from
speech. This is the first slice where a human being sat at a keyboard and produced the text.

---

## The corpus

Amram et al. 2018, user comments on Ynet's Facebook page. **MIT.** Fetched, hashed and registered
in `docs/CORPUS_REGISTER.md`. 12,804 comments; 10,746 pass this repository's own selection rule
(≥ 4 Hebrew tokens, none over 12 characters).

**What it is not, stated before it is used.** Facebook comments are not phone messaging.
`M10-REGISTER` is **narrowed by this and not closed by it.** The text is also tokenized by its
authors, with punctuation spaced out; that does not move a character-presence rate, which is all
`A1` used it for, and it *does* move a tokenization-sensitive measurement, which this is. Said
plainly: the token boundaries here are someone else's, and every number below inherits them.

## The two arms, and why they must be paired

| arm | what it is |
|---|---|
| **FILTERED** | the same comments with `[א-ת]+` extraction applied — exactly what `build_subtitle_corpus.py` and `build_eval_corpus.py` would have produced |
| **RAW** | the same comments with **nothing removed** |

**The arms are the same comments.** Selection is decided once, on the Hebrew tokens, and the two
files are two renderings of one selection. If the arms were selected separately, the difference
between them would confound alphabet with selection, and the one number this experiment exists
to produce would be uninterpretable.

**FILTERED is comparable to the existing corpora, so it isolates *register*.** The difference
between RAW and FILTERED is the **cost of the alphabet**, which nothing in this repository has
measured.

Within RAW, two denominators are reported rather than one:

- **Hebrew targets, polluted context** — attempts only on Hebrew-word targets, with the
  punctuation and Latin left in the context. This isolates context pollution.
- **all targets** — attempts on every whitespace token, which is what a keyboard actually faces
  and which the engine never claimed to handle. Reported so the fair comparison cannot be
  mistaken for the user-facing one.

## Disjointness

Nothing was trained on this corpus: it is external, and its provenance — Facebook comments —
shares no source with the training mix of Hebrew Wikipedia and OpenSubtitles.

**Provenance is an argument, not a proof**, so the check is run where it can be: every selected
line is hashed and looked up against a full stream of the OpenSubtitles source. **Any collision
is reported and the line dropped.**

**The Wikipedia half of the check is `NOT RUN`** and is recorded as such rather than waved past:
the hewiki source is not present in this container and the training corpora were not retained.
That half rests on provenance alone.

---

## Predictions, committed now

Every bar is against the wiki and conversational numbers **re-measured in the same run**, never
against the published 5.35% and 23.72%.

- **W1-P1** — typed **FILTERED** prefix-1 completion top-3 lands **strictly between** the wiki
  and the conversational numbers measured in that run.
- **W1-P2** — typed **RAW** (Hebrew targets, polluted context) prefix-1 completion top-3 is at
  least **3 percentage points below** typed FILTERED.
- **W1-P3** — the share of targets outside the lexicon is at least **2×** the conversational rate
  on RAW, and strictly higher than it on FILTERED.
- **W1-P4** — the real-word error detector's trigger rate on correct text is **higher** on typed
  FILTERED than on conversational.

## The statistical rule, committed before any spread is seen

The independent unit is the **comment**, not the position: positions inside one comment are
correlated, and a per-position interval would be too narrow by construction. Every rate is
reported with a **95% percentile bootstrap interval over comments, 1,000 resamples**, seeded.

A confidence interval chosen after seeing the spread is not a confidence interval. This is why
the rule is here and not in the results section.

## The kill condition

**If typed FILTERED falls inside the 95% interval of the conversational number on every metric,
then the subtitle blend already handled register and W1 buys nothing beyond `A1`'s alphabet
result.** That outcome is written down in advance so it cannot later be reported as something
else.

---

## Positive controls

**PC-1 — the harness must reproduce the numbers it is being compared against.** Run on
`hewiki_eval_sample` and `he_conversational_test`, it must return the published 5.35% and 23.72%
prefix-1 completion top-3 to within **0.1 points**. If it does not, this harness is not the one
that produced those numbers, nothing below is comparable to anything, and the run is
`NOT-MEASURED`.

**PC-2 — the context must be doing work.** Replacing the previous word with a uniformly random
lexicon word must drop next-word top-3 substantially. If a random context scores like the real
one, the metric is not measuring prediction and no register claim can be drawn from it.

---

## What this cannot say

It cannot say anything about phone typing. It cannot say anything about Hebrew typed on a
touchscreen, with autocorrect already interfering, under time pressure. It is one register step
closer than anything here has had, and one step is what it is.

`M10-REGISTER` stays **NOT MEASURED**.

---

## Result

*Not run yet. This section is filled in by the commit that runs the harness, and by no other.*

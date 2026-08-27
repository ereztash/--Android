# R1 — fifteen messages a person actually wrote

**Pre-registration. Written before a single message has been read, and before any of them has
been run through any code in this repository.**

## Why fifteen messages is not a measurement, and what it is instead

Every correction number this project publishes was measured on **injected** errors: take clean
text, corrupt a letter, ask whether the model returns the original. `78.34%` top-1 is that. It is
a legitimate answer to one question and it is stated everywhere as the wrong register for phone
messages. **`M10-REGISTER` is NOT MEASURED** because no sample of that register has ever existed
here.

Fifteen messages do not change that. At `n = 15` a proportion carries a 95% interval of roughly
**±25 points**, so no percentage computed from this set can be compared to `78.34%`, or to
anything else. **No percentage from this set will be published as a headline, and no bar is set
against one.** That is decided now, in advance, so that a favourable-looking fraction cannot be
promoted to a finding after the fact.

What the set is good for is three things, and they are the only three claimed:

1. **Falsification, never confirmation.** The asymmetry is real: a failure on 9 of 15 says
   something at any sample size; a success on 13 of 15 says nothing. Only failures will be read
   as evidence here, and that rule is written before the failures are known.
2. **A regression fixture built from reality.** `B1` reported *"the bracket complaint does not
   reproduce — 0 of 8"* on eight strings I hand-built, and `B2` then found it reproduces on 77%
   of 463 real typed lines. Hand-built material cannot exhibit what its author did not think of.
   Fifteen real messages are strictly better than eight invented ones.
3. **The character set of a register nobody here has sampled.** `A1` measured typed Hebrew as
   carrying ×604 the geresh/gershayim of transcribed Hebrew — but that was Ynet comments, not
   phone messages. What characters appear in these fifteen is observable at `n = 15` even though
   accuracy is not.

**Autocorrect was off when these were written.** That is recorded because it decides what the
errors are: with it on, the surviving errors would be the residue that other keyboards failed to
catch — a different population entirely. These are the writer's own errors.

## The labelling protocol, and the order it must happen in

The intended word is what makes this data worth anything: without it there is an error *rate* and
no correction *accuracy*. The writer is not available to state intent, so I infer it, and where I
am unsure I ask the operator — who knows the writer.

**The order is the whole protocol:**

1. Messages arrive raw. Nothing is normalised, cleaned or filtered.
2. Every suspected error is extracted and my reading of the intended word is **written down and
   committed** — before any code in this repository has been run on any message.
3. Where I am not confident, the operator is asked, and the answer is recorded with the item.
4. **Only then** is the shipped path run.

Step 4 comes last because I am not a neutral labeller: I built the thing being judged. If I
decide what the writer "meant" after seeing what the app suggested, I will pick the intent that
makes the app look right, and I will not notice myself doing it. Freezing the labels first is the
only cheap defence, and it costs nothing.

**A limitation this does not fix:** the operator's belief about intent may itself be shaped by
knowing what the keyboard would do. At `n = 15` there is no affordable way around that. It is
recorded rather than corrected for.

## Positive controls, designed before the checks they control

A probe that cannot report failure has not been shown to be a probe.

**PC-1 — the harness can see an error.** Take a message the writer spelled correctly, inject a
known single-character error, and run it. The harness must report that token as an error. If it
comes back clean, the harness is measuring nothing and no result below it counts.

**PC-2 — the harness does not invent errors.** Take the corrected form of a message and run it.
The harness must report **no** error. If correct text produces findings, the probe measures noise
and its failures are not the app's.

Both run **before** the fifteen.

## Predictions, committed now

| # | prediction | falsified if |
|---|---|---|
| 1 | **At least 3 of 15** messages contain a character the lexicon cannot represent — geresh, gershayim, Latin, digit or emoji | fewer than 3 do |
| 2 | Out-of-lexicon rate on correctly-spelled tokens is **at or above 5.52%**, the figure `W7` measured on Ynet comments — phone messaging should be further from Wikipedia, not closer | it comes in below 5.52% |
| 3 | Top-1 correction on the writer's real errors lands **materially below 78.34%**, the injected-error figure — real errors are harder than corrupted-letter errors | it lands **at or above** 78.34% |
| 4 | **At least one** failure mode appears that no existing eval corpus contains | every failure is already represented |

Prediction 3 is readable in one direction only, and that is stated rather than hidden: at this
sample size a gap smaller than about 25 points cannot be distinguished from noise. A result at or
above `78.34%` falsifies it; a result far below it is a signal; a result slightly below it is
**nothing**, and will be reported as nothing.

## Stopping rule

One pass. The probe ends when the four predictions have been looked at once.

**No weight, threshold or cost model moves as a result of this set.** A tuned parameter fitted to
fifteen messages is fitted to one person on one day. Anything that fails becomes a documented
finding and a regression fixture; it does not become a reason to adjust the engine. That rule
exists because the repository's oldest standing commitment is that a bar is not renegotiated by
the party it constrains.

## What this cannot become

- **Not a correction-accuracy number.** Nothing here supersedes `78.34%`, and nothing here
  supports a replacement for it.
- **Not `M10-REGISTER`.** Fifteen messages from one writer are not the register; they are the
  first sample of it. The criterion stays **NOT MEASURED**.
- **Not lexicon additions.** `A2` found the out-of-vocabulary residual is dominated by
  **misspellings** — the errors the keyboard exists to correct. A word that appears here is not
  thereby a word, and nothing from this set is added to the lexicon.
- **One writer.** `n = 1` on the dimension that matters most. Whatever appears may be this
  person's habits rather than anyone else's, and no result will be written as though it were
  general.

---

# Observed during labelling, before the harness ran

Recorded here because it was visible from the labels alone. It changes no prediction above —
those are frozen — but it names the mechanism they were guessing at.

## The errors are phonetic, not geometric: 21 of 25

| substitution | occurrences | why |
|---|---|---|
| ח ↔ כ | 5 | both /χ/ |
| ת ↔ ט | 3 | both /t/ |
| א ↔ ע | 3 | both null |
| ק ↔ כ | 2 | both /k/ |
| ו ↔ ה | 2 | word-final /o/ vs silent ה |
| ה ↔ ח, ה ↔ א | 2 | |
| silent א dropped | 3 | מוציא, לבוא, אבוא |
| whole word respelled by sound | 1 | `עכשיו` → `אחשב`, which is /axshav/ letter by letter |

**This writer does not miss the key. He misses the letter.** Every pair above is two ways to
write one sound in an abjad where the choice is not derivable from pronunciation — it has to be
remembered.

That is a different population of errors from the one every published number here was measured
on. An **injected** error corrupts a letter, which produces a random or neighbouring
substitution. It cannot produce `אחשב` for `עכשיו`.

## The shipped cost model discounts none of them

`AdjacencyCostModel` has exactly one substitution discount and it is geometric. Its own KDoc
states the assumption: *"typing qof where resh was meant is a slip of the thumb; typing qof where
tav was meant is a different word."*

Asked directly, via `scripts`-free probe `HomophoneAdjacency` against the real layout geometry:

```
ח/כ  ק/כ  ת/ט  א/ע  ו/ה  ה/ח  ה/א   ->  discounted: 0 of 7
thumb-slip control (ק/ר, ש/ד, ב/ה)  ->  3 of 3 adjacent  (probe works)
```

**Zero.** The control is green, so the zero is the model's answer and not a broken probe. Every
one of this writer's systematic errors is charged the maximum a substitution can cost, and the
single discount the engine has is aimed at a mistake he does not make.

This is a finding about the model, established from the layout alone. **It is not a result about
the fifteen messages** — the shipped path still has not been run on them.

---

# Result

`R1Probe`, shipped configuration — `NeutralCostModel` with default `Config`, which is what
`CorrectionController` constructs. Not the adjacency model: `CORRECTION_MEASUREMENTS.md` finding
1 measured that as 8 points of top-1 worse and it is deliberately off.

## The controls ran first and both behaved

```
PC-1  injected error 'מרלדת'  -> 3 suggestions  : RED    (the harness can see an error)
PC-2  correct word  'מקלדת'   -> 0 suggestions  : SILENT (it does not invent one)
```

## What the engine did with twenty-five real errors

| | count | |
|---|---|---|
| **SILENT — no suggestion at all** | **15 / 25** | 14 because the typed form is **already a valid word**; 1 because a geresh makes `שתנץ'` not a Hebrew word to `isHebrewWord` |
| top-1 correct | 3 / 25 | |
| top-3 correct | 6 / 25 | |
| retrieved but wrong | 4 / 25 | |

**Sixty percent of the time the keyboard says nothing.** Not a wrong suggestion — no suggestion.
`suggest()` returns at `if (isValid(normalized)) return emptyList()` before it reaches the trie.
`לבו`, `מחור`, `אנכנו`, `איפו`, `מורקב`, `אהבתה`, `אחשב` are all words. The engine is working
exactly as designed and is structurally blind to most of what this person types wrong.

The two it did retrieve and rank third are worth reading:

```
השגיעות  ->  הפגיעות, השגיאות, המגיעות
שגיעות   ->  מגיעות,  פגיעות,  שגיאות
```

The right answer is **in the set** and loses to candidates that share no sound with it. Retrieval
is not the failure. Ranking is.

## The four predictions, scored

| # | prediction | measured | |
|---|---|---|---|
| 1 | ≥3 of 15 messages carry a character the lexicon cannot represent | **1 of 15** | **FALSIFIED** |
| 2 | out-of-lexicon on correct tokens ≥ 5.52% | **0.00%** of 119 | **FALSIFIED** |
| 3 | top-1 materially below 78.34% | **12%** (3/25) | **HELD** |
| 4 | ≥1 failure mode no eval corpus contains | the silent 60% | **HELD** |

**Two of four falsified, and both in the direction that flatters the lexicon.** I expected phone
messages to be full of Latin, digits and emoji the way `A1` found Ynet comments were. These are
almost pure Hebrew letters. And I expected worse coverage than Ynet; every one of the 119
correctly-spelled Hebrew tokens is accepted. `H1`'s 99.16% is if anything understated for this
register.

**Prediction 3 needs its like-for-like form or it is unfair.** The 78.34% was measured on corpus
A, which **discarded 1,676 corruptions that landed on another real lexicon word**. Comparing 12%
against it counts cases corpus A deleted. On the 10 items the engine actually engages with, top-1
is **3 of 10 — 30%**. Still 48 points below, so the finding survives the fair comparison; the
headline gap is the honest one only if the exclusion is stated with it.

## The finding

`CORRECTION_MEASUREMENTS.md` discarded **1,676 uniform and 1,174 adjacency corruptions because
they landed on another real lexicon word** — reasonably, since "correcting" such a form is not
clearly right. Roughly 30% of generated corruptions.

**In real typing it is 60%, and it is the dominant failure.** The eval corpora removed by
construction the thing that goes wrong most.

That is not a flaw in those corpora — they answer the question they were built for. It is a
statement about what the headline number covers. **78.34% is top-1 on the errors that remain
after the most common real failure has been filtered out.**

## What R1 supplies that the repository asked for in advance

`CORRECTION_MEASUREMENTS.md` finding 1 ends:

> *"What would change this answer: a corpus of real Hebrew typing errors. The true error
> distribution lies somewhere between corpus A (uniform) and corpus B (pure adjacency), and
> nothing in this project knows where. **Recorded as NOT MEASURED: the real Hebrew typing error
> distribution.**"*

R1 is 25 items of exactly that, and the answer is **neither A nor B**. Not uniform, not
adjacency — **phonetic**. 21 of 25 are two ways to write one sound.

**This does not license a phonetic cost weight.** Finding 1's whole lesson is that a weighting
measured on a corpus generated from its own assumption looks like a large win and ships harm:
adjacency gained 20 points on corpus B and lost 8 on corpus A. **Building a homophone discount
and scoring it on R1 would rebuild corpus B.** R1 generates the hypothesis. Testing it needs a
corpus R1 did not produce.

## Not measured

- **One writer, 25 items, and his status is unknown** — whether he is dyslexic, an oleh, or a
  fluent native who types fast was asked and not established. Every number here is his.
- **No bar was cleared or missed**, because none was set. The stopping rule holds: no weight,
  threshold or cost model moves because of this set.
- **`M10-REGISTER` stays NOT MEASURED.** Fifteen messages are the first sample of the register,
  not the register.

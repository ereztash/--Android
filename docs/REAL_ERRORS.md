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

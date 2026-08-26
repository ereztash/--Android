# W7 — prefix-aware completion, and the correction that produced it

**Written before the harness exists.** Predictions, guards, the adoption rule and the
NOT-ADOPTED condition below were committed in the commit that created this file.

---

## The correction that comes first

`A2` published a table of levers with two entries costing **0 bytes**: a prefix chain *"already
shipped"* worth 2.04pp, and a `""`→`״` normalisation worth 0.38pp. The operator asked for both
to be built.

**Both are already built, and `A2` measured them against the wrong test.**

`A2` asked `token in he_lexicon.txt.gz`. That is **not** the shipped acceptance path.
`CorrectionEngine.isValid` calls `PrefixStripper.accepts(word, validWords, minStem)`, and
abbreviations resolve through `HebrewText.stripAbbreviationMarks` into a bare-letter table.
`A2` also used a hand-written prefix list at `minStem = 2`, where the repository ships the
79-string `W × S × P × H` product at **`DEFAULT_MIN_STEM = 4`**.

Re-measured against what actually ships, on the same 214,844 typed tokens:

| | occurrences | of tokens |
|---|---|---|
| literal lexicon hit | 197,401 | 91.88% |
| **accepted by `PrefixStripper` (minStem 4)** | 4,137 | **1.93%** |
| **accepted as an abbreviation, bare table** | 997 | **0.46%** |
| accepted after stripping marks | 449 | 0.21% |
| **genuinely unknown to the shipped engine** | 11,860 | **5.52%** |

**The out-of-lexicon rate on typed Hebrew is 5.52%, not the 8.12% `A2` reported.** 2.60 points
of that gap were never a gap: the engine already handles them.

`A2`'s Rule 1 is *check what you already have before searching outside*. I applied it to the
**data** — is the abbreviation table there, is the prefix list there — and not to the **code
path**. Both assets existed *and were already wired*. The rule was right and I stopped one level
too shallow.

## What survives, and it is narrower

`PredictiveEngine` references `PrefixStripper` **zero times**, and its trie is built from
lexicon words. So the two paths differ, and nobody had written that down:

- **correction** — *"is this a misspelling?"* — is prefix-aware. Built.
- **completion** — *"can this be offered as a candidate?"* — is **not**. A prefixed form that is
  not literally in the lexicon can never be offered, however obviously it decomposes.

So `בהתבוללות` is *accepted* as correctly spelled and can never be *suggested*.

### The ceiling, measured before anything is built

Of 208,806 completion-eligible targets in the typed slice:

| | count | share |
|---|---|---|
| literal lexicon hit — completable today | 191,554 | 91.74% |
| **prefix + stem where the stem is in the lexicon — not completable** | **4,124** | **1.98%** |
| neither | 13,128 | 6.29% |

**1.98% of targets is the ceiling.** Not the yield — the ceiling.

---

## The arm

When the typed string yields fewer than `limit` completions, also try each prefix `p` from
`PrefixStripper.ALL` that the typed string starts with, complete the remainder in the trie, and
offer `p + completion`.

### Where it cannot help, stated before the run

**At prefix length 1 this arm does almost nothing**, and predicting otherwise would be an error
this file exists to prevent. If the user has typed `ל` and the target is `להתלהמות`, stripping
`ל` leaves an empty remainder and "complete the empty string" is every word in the lexicon. The
arm needs the user to have typed the prefix **and** part of the stem, which starts at length 2
and is only useful from 3.

### The noise it will generate, also stated before the run

Seven of the 79 prefixes are a single character. Every one-letter opening that happens to be one
of them opens a second completion space. And the analysis is not always right: the measurement
that produced the ceiling contains `כשכתבים = כשכ + תבים`, which is not the decomposition of
that word. **A prefix chain landing on a real word is not proof of the right analysis**, and this
arm converts that from a counting error into a suggestion a user sees.

---

## Predictions, committed now

- **W7-P1** — prefix-**3** completion top-3 on the typed slice improves by **≥ 0.5 points**.
- **W7-P2** — prefix-**1** completion top-3 changes by **less than 0.1 points** in either
  direction, because the arm has nothing to work with there.
- **W7-P3** — the arm fires on **fewer than 15%** of positions. An arm that fires everywhere is
  not a fallback, it is a second engine.

## The adoption rule, committed now

**ADOPTED** only if all four hold, every bar re-measured in the same run:

1. prefix-3 top-3 improves by **≥ 0.5 points**;
2. **no more than 0.1 points** of positions that were **top-1 correct** become top-1 wrong —
   this is the clause the arm is most likely to fail, because a wrong prefix analysis outranking
   a right literal completion is exactly what it risks;
3. prefix-1 top-3 does not **fall** by more than 0.1 points;
4. the same measurements on the **transcribed** and **encyclopedic** slices do not regress by
   more than 0.1 points — a fix for one register that costs another is not a fix.

If any clause fails: **NOT ADOPTED**, the arm stays in the harness, and nothing changes in the
shipped engine.

## Where this is built

**In the harness first, not in `PredictiveEngine`.** `O1`, `G2`, `G3` and `B1` were all measured
before adoption and four of them were never adopted. An arm that ships before it clears its bar
is the thing the definition of done exists to prevent.

---

## Result

`./gradlew :core:test --tests '*PrefixCompletionTest*' -PrunPrefixCompletion=1`.

### `append` — the arm fires on two thirds of keystrokes and changes nothing

| slice | prefix | base top-3 | arm top-3 | delta | arm fires | broke top-1 |
|---|---|---|---|---|---|---|
| typed | 1 | 9.84% | 9.84% | **+0.00** | 0.0% | 0.00% |
| typed | 2 | 36.62% | 36.62% | **+0.00** | 62.8% | 0.00% |
| **typed** | **3** | **55.09%** | **55.10%** | **+0.00** | **66.2%** | 0.00% |
| transcribed | 3 | 73.87% | 73.87% | +0.00 | 62.3% | 0.00% |
| encyclopedic | 3 | 47.98% | 47.98% | +0.00 | 71.1% | 0.00% |

**Zero, everywhere, to two decimal places** — while generating candidates on **66.2%** of
positions. The reason is `limit = 3`: the baseline already returns a full strip on effectively
every position, so an appended candidate has nowhere to go. *Fill to `limit`* is not a mechanism
when `limit` is already met.

### `interleave` — letting them compete is catastrophic

| slice | prefix | base top-3 | arm top-3 | delta | base top-1 | arm top-1 | broke top-1 |
|---|---|---|---|---|---|---|---|
| **typed** | **3** | **55.09%** | **45.51%** | **−9.58** | 39.62% | 28.72% | **11.54%** |
| typed | 2 | 36.62% | 29.62% | −7.00 | 24.39% | 18.44% | 6.57% |
| transcribed | 3 | 73.87% | 64.97% | **−8.91** | 55.60% | 46.14% | 9.79% |
| encyclopedic | 3 | 47.98% | 39.90% | **−8.08** | 32.90% | 25.16% | 8.34% |

It destroys **11.54%** of the positions that were top-1 correct and costs nine points of top-3 on
every register. The scale mismatch written into the pre-registration — a prefixed candidate is
scored over the *remainder*, not over what the user typed — is not a caveat. It is the result.

### Against the rule

| clause | append | interleave |
|---|---|---|
| 1. prefix-3 typed top-3 improves ≥ 0.50pp | **+0.00 FAIL** | **−9.58 FAIL** |
| 2. ≤ 0.10pp of top-1-correct broken | 0.00 PASS *(cannot fail — proves nothing)* | **11.54 FAIL** |
| 3. prefix-1 does not fall > 0.10pp | +0.00 PASS | +0.00 PASS |
| 4. other registers do not regress > 0.10pp | +0.00 PASS | **−8.91 / −8.08 FAIL** |

**W7-P1 falsified** (no improvement at prefix-3). **W7-P2 held** — prefix-1 moved by exactly
0.00, as written down before the run, because stripping a prefix from a one-character string
leaves nothing to complete. **W7-P3 falsified, and badly**: the arm fires on **66.2%** of
positions against a bar of 15%. *An arm that fires everywhere is not a fallback, it is a second
engine* — that sentence was in the pre-registration and it is what happened.

**VERDICT: NO mode meets every clause — NOTHING IS ADOPTED.**

---

## What was learned

**1. The gap is real and this arm cannot reach it.** The ceiling was measured at 1.98% of
targets before anything was built. `append` captured 0.00 of it; `interleave` captured it and
paid nine points for the privilege. Reaching those 1.98% requires *displacing* a correct literal
completion, and the measurement prices that trade at roughly **−5 points for every +1**.

**2. `limit = 3` is the binding constraint, not the lexicon.** The strip is already full on
essentially every position. Any "fallback" mechanism that appends is inert by construction, and
this is worth knowing before the next one is proposed.

**3. Clause 2 was designed to be unfalsifiable under one mode and it was.** `append` cannot break
top-1, so its 0.00 is not evidence. That is why both modes were run: the clause only became a
clause under `interleave`, where it failed by 115×.

**4. Both of the "0-byte fixes" that started this were already shipped.** Recorded at the top of
this file, and the real out-of-lexicon rate on typed Hebrew is **5.52%**, not the 8.12% `A2`
published.

## What is NOT concluded

That prefix-aware completion is impossible. A different mechanism — generating prefixed forms
into the *lexicon* rather than at query time, or a score calibrated across the two spaces — is
untested, and this run says nothing about it beyond pricing what it must beat.

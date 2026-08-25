# H1 — the friction inventory

**Purpose, fixed by the operator on 2026-08-25: *typing Hebrew without fighting the language*.**

Every measurement in this repository before this one asks the same question — *given a position
in a corpus, is the target word in the top three*. That question was never derived from a
purpose. It was inherited from what keyboards do.

This is the first measurement in the project that starts from the purpose instead: it counts
**where the language actually makes a person stumble**, and how much of that the shipped
keyboard absorbs. Reproduce with `python3 scripts/measure_friction.py`.

Nothing is chosen here and nothing is adopted. It is an inventory.

---

## The counts

Denominator: Hebrew tokens, on the two held-out slices everything else in this repository is
measured on.

| friction | conversational (40,434 tok) | wiki (85,840 tok) |
|---|---|---|
| already in the lexicon as a surface form | **99.16%** | 95.01% |
| not in the lexicon at all | 0.84% | 4.99% |
| …of those, a prefix chain accounts for it | 51.03% of OOV | 50.13% of OOV |
| carries an agglutinated prefix *(upper bound)* | **42.81%** | 53.51% |
| …and is **also** stored as its own surface form | 42.38% | 51.00% |
| one `ו`/`י` deletion away from another real word *(upper bound)* | **50.78%** | 47.30% |
| ends in a final form | 20.38% | 23.24% |
| ends in a non-final letter where the final form is a word | 0.31% | 0.58% |
| **mixes script (Latin or digit)** | **0.00%** | **0.00%** |
| **contains geresh or gershayim** | **0.00%** | **0.00%** |

---

## The last two rows are not findings. They are a hole in the evidence.

Both corpora are **Hebrew-letters-only by construction**. `build_subtitle_corpus.py` keeps only
`[א-ת]+` runs; `build_eval_corpus.py` does the same. Latin characters, digits, geresh, gershayim
and every punctuation mark are discarded before a single token is written.

So those zeros are a property of a regular expression, not of Hebrew.

**Every friction that involves a character which is not a Hebrew letter is invisible to every
corpus in this repository.** Under the purpose now fixed, that is the most consequential
sentence in this document, because it covers:

- **Script mixing** — a Latin brand name, a URL, a phone number or a digit inside a Hebrew
  sentence, and the cursor and punctuation behaviour around it. This is among the most commonly
  reported frictions in Hebrew phone typing and its frequency here is **NOT MEASURED**, not low.
- **Gershayim and abbreviations** — the app ships **861 abbreviation forms** and is evaluated on
  a corpus containing **zero** instances of the character they require.
- **Punctuation adjacency** entirely.

This is the same failure as `M10-REGISTER`, one level deeper: the register was wrong, and the
*alphabet* was narrowed too.

---

## What the measured rows say

**1. The lexicon is not the bottleneck.** 99.16% of conversational tokens are already in it as
surface forms. Correction and completion are not failing for lack of words.

**2. Agglutination is handled by brute force, and that is where the bytes went.** 42.81% of
conversational tokens carry a prefix chain, and 42.38% — nearly all of them — are *also* stored
as their own surface form. The 355,587-form lexicon is largely an enumeration of what a
morphological rule would generate. That is a representation choice with a price tag attached,
and the price is the asset budget.

**3. Half of conversational Hebrew has a real-word neighbour one letter away.** 50.78% of tokens
are a single `ו`/`י` deletion from another lexicon word. This is the structural reason the
real-word error layer measures 12.5–39.7% precision: the candidate space is not a small
confusion inventory, it is half the language. No threshold over a signal this dense will
separate them, which is what `docs/LABELING_LOG.md` found empirically and this explains.

**4. Final-form errors are rare in correct text and cheap to check.** 0.31% of conversational
tokens end in a non-final letter whose final form is a word. What this does **not** measure is
how often a *typist* produces one — correct corpus text is the wrong place to look for that,
and the right place is a device.

---

## What this changes about where to look

Ranked by measured frequency, against what the keyboard does today:

| capability | friction frequency | currently |
|---|---|---|
| choose between real-word neighbours | **50.78%** of tokens have one | precision 12.5–39.7%; `D1` proved the ceiling is the representation, not the problem |
| absorb agglutination | **42.81%** | enumerated into the lexicon, at the cost of the byte budget |
| survive script mixing and gershayim | **NOT MEASURED** | nothing, and nothing can see it |
| know the word at all | 0.84% miss | solved |

The top row and the bottom row are the two ends of the same finding: **the keyboard already
knows the words, and cannot choose between them.**

---

## What would have to exist to close the hole

A corpus of Hebrew as it is actually typed — with the Latin, the digits, the gershayim and the
punctuation left in. Nothing in this repository has one, and the cleaning rules that removed
them are load-bearing for the corpora that do exist, so this is a new corpus rather than a
re-run. Openly licensed candidates that are typed rather than transcribed, and that this project
has never touched, are recorded in `docs/CORPUS_REGISTER.md`.

Until then this document names the quantity and does not estimate it.

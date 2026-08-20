# Real-word error measurements (M11)

*"אפליקציה צריכה לזהות גם בתוך משפט לפי ההקשר אם יש שגיאת כתיב כמו שימוש באם במקום עם"*

`אם` ("if") and `עם` ("with") are both perfectly good Hebrew words. Nothing about either string
is wrong, so the lexicon accepts both — correctly — and edit distance has nothing to say. The
mistake lives in the sentence, not in the word.

Every number here is a claim about **exactly one thing**: this detector, on this corpus, with
this lexicon and this bigram table.

---

## Method

For a word *w* with neighbours *l* and *r*, the detector generates every real lexicon word one
**homophone substitution** away from *w*, and compares the bigram evidence:

```
evidence(x) = logCount(l, x) + logCount(x, r)
```

It offers the best alternative when that alternative has evidence and *w* has none.

### The confusion set costs zero bytes

The full inventory is 166,504 ordered pairs and would be a multi-megabyte asset. Instead
variants are generated on demand — `word.length × 6` candidates, each a binary search in the
lexicon — so `GATE-SIZE-1`'s asset budget is untouched. M11 cost **4,576 bytes of DEX and 0
bytes of assets**.

The release APK total is unchanged at 5,161,766 bytes, but not because M11 was free: the DEX
growth fell inside an existing 14 KB alignment pad, which now has 9,748 bytes left. The next
change of this size will move the total.

### Which letters, and why

Phonological, not typographic. Modern Israeli Hebrew has merged distinctions the orthography
still writes, so a writer who knows how a word sounds and not how it is spelled picks the wrong
letter — and lands on another real word often enough to matter.

| pair | shared realisation | recall (test slice) | n |
|---|---|---|---|
| א ↔ ע | zero / glottal stop | 67.18% | 11,067 |
| ח ↔ כ | /x/ | 64.51% | 3,973 |
| כ ↔ ק | /k/ | 64.91% | 3,645 |
| ת ↔ ט | /t/ | 75.40% | 4,460 |
| ב ↔ ו | /v/ | 56.18% | 15,547 |
| ס ↔ ש | /s/, sin undotted | 71.93% | 7,175 |

Reported per pair, not as an aggregate, so one bad confusion cannot hide inside five good ones.

**Keyboard-adjacent slips are deliberately excluded.** When a slip produces a non-word the
correction engine already catches it; when it produces a real word only context helps, but
folding those pairs in here would mix two error models whose rates were measured separately.

**`ו`/`י` is deliberately excluded.** It is by far the largest source of real-word pairs —
78,310 against 9,950 for `א`/`ע` — but `ktiv male` alternation makes `ו` and `י` genuinely
contrastive, so most of those pairs are two different words rather than two spellings of one.
It remains available as `HebrewConfusions.KTIV_MALE` so the claim can be re-measured rather
than argued.

---

## Corpora, and the dev/test split that makes the numbers mean anything

Three slices are cut from the held-out corpus at different offsets of the same stride.
`scripts/slice_eval_corpus.py` compares the index sets and **refuses to write** if any two
intersect.

| slice | sha256 | used for |
|---|---|---|
| `sample` | `cedfb5be…` | M10 prediction: weight sweep, ordering sweep, accuracy floors |
| `confusion_dev` | `7f6986d6…` | **M11: the margin sweep. Thresholds are chosen here and nowhere else.** |
| `confusion_test` | `9fc528ae…` | **M11: the reported numbers, measured once with thresholds fixed.** |

6,000 sentences each, pairwise overlap **0**, proven. A threshold tuned on the same sentences
it is then reported against is not a measurement, it is a fit.

- **Corpus D — injected errors.** Every eligible position has one letter swapped for its
  homophone, producing another real lexicon word.
- **Corpus E — the same text, untouched.** The false-alarm control, and the number that
  governs the thresholds.

---

## The margin sweep (dev slice, `7f6986d6…`)

Every configuration tried, both columns side by side. Recall without a false-alarm rate beside
it says nothing: a detector that flags every word has perfect recall.

### Both sides — the shipped mode

| margin | never contradict typed | recall | false alarm |
|---|---|---|---|
| 1 | no | 68.70% | 0.62% |
| 16 | no | 68.09% | 0.43% |
| 21 | no | 67.90% | 0.39% |
| 22 | no | 65.92% | 0.34% |
| 24 | no | 64.37% | 0.31% |
| 32 | no | 56.29% | 0.18% |
| 64 | no | 32.72% | 0.03% |
| 1–21 | **yes** | **64.42%** | **0.30%** |
| 22 | yes | 62.51% | 0.25% |
| 24 | yes | 61.08% | 0.23% |
| 32 | yes | 53.41% | 0.13% |
| 64 | yes | 31.11% | 0.02% |

Corpus D n=46,210, corpus E n=69,909.

### Left context only

| margin | never contradict typed | recall | false alarm |
|---|---|---|---|
| 21 | no | 45.89% | 0.22% |
| 1–21 | yes | 44.78% | 0.18% |
| 32 | yes | 33.84% | 0.08% |

Corpus D n=48,978, corpus E n=75,161.

---

## What was chosen, and why

### `margin = 21` — the model's own floor, not a tuned number

`scripts/build_bigrams.py` prunes at a count of 5, and counts are stored as
`round(log2(count + 1) * 8)`. So `round(log2(6) * 8) = 21` is the **smallest value any stored
pair can carry**, and every margin from 1 to 21 is the same rule: *"the corpus has seen this
pair at all."* The sweep confirms it — identical recall and identical false alarms across all
of them, with the first change at 22. `BigramFloorTest` pins the floor against the table
itself, so this cannot quietly stop being true.

21 is the value that states the rule rather than sitting arbitrarily underneath it. Tightening
to 24 would trade 3.34 points of recall for 0.07 points of false alarms — 31 missed errors per
false alarm avoided.

### `requireNoSupportForTyped = true` — a principle, not a threshold

The detector may speak when the corpus has nothing to say about what the user typed. It may not
contradict evidence the corpus actually has. Measured, that halves the false-alarm rate
(0.62% → 0.30%) for 4.28 points of recall. The measurement supports the principle; it is not
what chose it.

### Both sides, not left-only — and the harness bug that nearly decided otherwise

The first version of the sweep reported that left context alone cost **1.05 points**, and the
architecture was briefly settled on that basis: check each word the moment it is finished, and
never touch text away from the cursor.

That harness was wrong. Its "left only" branch still passed the following word to the detector
and varied only which *positions* were eligible. Corrected, the gap is **19.64 points** —
44.78% against 64.42% — and it decides the question the other way.

So the shipped detector checks the **second** most recent completed word, whose right-hand
neighbour is the most recent one. One check per word, made once, at the moment the evidence is
complete. Applying the fix then means replacing a word that is not under the cursor, which the
IME does by deleting from that word to the cursor and committing a rewritten span, carrying the
intervening text over **verbatim** rather than reconstructing it.

The bug was found because the locked test on the test slice reported 44.69% against a floor
written from the dev sweep's 63.37%. A 19-point discrepancy between two disjoint slices of the
same corpus is not sampling noise, and chasing it found the cause. **This did cost an extra
look at the test slice**, recorded here rather than quietly absorbed: the thresholds were not
changed as a result — only the harness was fixed and the architecture decision reversed.

---

## Results (test slice `9fc528ae…`, thresholds already fixed)

| metric | value | denominator |
|---|---|---|
| **recall** | **64.58%** | 45,867 injected errors |
| flagged with the wrong fix | 0.25% | 45,867 |
| **false alarm** | **0.26%** | 69,494 untouched positions |

Dev measured 64.42% / 0.30%; test measured 64.58% / 0.26% on sentences the dev slice never
contained. Two disjoint slices agreeing to within 0.2 points is the best evidence available
here that the numbers are about Hebrew and not about a particular 6,000 sentences.

### Controls

| control | result | what it rules out |
|---|---|---|
| same detector, **empty** bigram table | **0** of 69,494 flagged | the findings are a property of context, not of the confusion inventory |
| permissive detector (margin 0, will contradict the typed word) | 15.08% false alarm | the counting loop can separate a permissive detector from the shipped one, so 0.26% is a measurement |

### The example the operator asked about

```
דיברתי [אם] המורה   ->  עם     typed evidence 0, suggested evidence 69
לא [יודע] אם        ->  no finding
אני [עם] חברים      ->  no finding
```

---

## What these numbers are NOT

- **Recall is not "the fraction of real mistakes caught".** Corpus D's errors are drawn from
  the same inventory the detector searches, so 64.58% answers only: *given that the error is
  one this detector can express, does context find it?* How often real Hebrew typing produces
  an error inside this inventory is **NOT MEASURED** — it would need a corpus of genuine human
  errors, which this project does not have.
- **Recall is a floor, not a point estimate.** Some injections produce a perfectly sensible
  sentence, and those count as misses even though nothing was really wrong.
- **The false-alarm rate is not a precision figure.** Precision depends on how often a real
  error is present, which is unmeasured. At 0.26%, a user typing 1,000 words sees roughly 2.6
  unnecessary suggestions; whether most flags are right depends entirely on a base rate this
  project cannot observe.
- **Nothing here is ever auto-applied.** The detector returns a suggestion; the user taps it or
  ignores it. Silently rewriting `אם` to `עם` would change what someone said into something
  they did not say, and no accuracy figure would justify that.
- **The evidence is Wikipedia prose, two words wide.** No sentence structure, no topic, no
  grammar, no user vocabulary. `דיברתי אם חבר` is caught because Wikipedia contains
  `דיברתי עם`; a sentence whose words Wikipedia never puts together is invisible to it.
- **No device latency number.** The detector runs on the same off-thread path as prediction,
  inside the same `HebrewIme.suggest` trace section, which is re-measured in M12.

## Reproducing

```sh
./gradlew :core:test --tests '*ConfusionAccuracyTest*'
./gradlew :core:test --tests '*BigramFloorTest*'
./gradlew :core:test --tests '*ConfusionSweepTest*' -PrunConfusionSweep=1
```


---

## The limit of the adjacent window — found on a phone, not in a test

The operator typed `אני אוהב עוגת גבינה אם הרבה שוקולד` and observed that a *reader* cannot tell
whether `אם` or `עם` was meant until the word after `הרבה` arrives.

**That case does fire**, and the reason deserves to be stated plainly rather than claimed as a
success:

| window | left | right | total |
|---|---|---|---|
| `גבינה` **`אם`** `הרבה` | 0 | 0 | **0** |
| `גבינה` **`עם`** `הרבה` | 0 | 51 | **51** |

`עם הרבה` is a common collocation and `אם הרבה` never occurs in the corpus, so the statistics
reached the right answer **before** the deciding word was typed. That is luck of collocation,
not understanding. The mechanism was not doing what the example required and got it right
anyway, which is exactly the kind of thing that should be written down rather than enjoyed.

### How often the window is genuinely blind

Measured on `hewiki_confusion_test.txt.gz`, 6,000 sentences:

| | count | share |
|---|---|---|
| words with a confusable variant | 35,291 | — |
| window has some evidence to weigh | 24,637 | 69.8% |
| **both candidates score zero on both neighbours** | **10,654** | **30.2%** |

In that 30.2% no margin, weight or threshold can help: there is no signal in the window to
weigh. Only context further away could decide, and `BigramModel` stores **adjacent pairs only**,
so that context is not representable at all. This is a structural limit of the model, not a
tuning decision, and it is why the recall ceiling in this document is where it is.

`WindowBlindnessTest` pins 30.2% as a characterisation, so that a future model claiming a wider
window has a number to beat rather than an impression to appeal to.

### What would actually close it, and what it costs

Not "semantic understanding" in the neural sense — that means a model this app cannot carry:
the whole APK is 5.2 MB, the build has no network permission by design, and `GATE-NET-1/2/3`
exist to keep it that way.

What is achievable is a **wider n-gram**, which buys part of the same effect statistically:

| approach | what it adds | rough cost |
|---|---|---|
| skip-bigrams at distance 2 | lets `שוקולד` bear on `אם` | a second table, ~2–3 MB |
| trigrams | full three-word context | larger still, and sparser at the same threshold |

Either would push the APK past `GATE-SIZE-1`'s 6,500,000-byte ceiling, which is a threshold, and
**thresholds in this repository do not move to accommodate a feature**. Raising it is an
operator decision, and the alternative — pruning harder to fit — trades away exactly the rare
pairs that the blind 30.2% consists of.

Recorded as an open decision. Not taken.

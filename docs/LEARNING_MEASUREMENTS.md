# Adaptive learning measurements

> **Updated by R1.** Every number below was re-measured after the training corpus changed to a
> 25% conversational blend. The previous figures described a model trained entirely on Hebrew
> Wikipedia and no longer describe what ships. See [`CORPUS_REGISTER.md`](CORPUS_REGISTER.md)
> for why the corpus changed and what it was worth.


Every number here is a claim about **exactly one thing**: this layer, on this corpus, under a
simulated-user protocol whose limitations are stated below and not argued away. Slice hashes sit
beside the tables.

**Nothing in this document has ever run on an Android device.**

---

## What ships

A second, mutable n-gram layer beside `BigramModel`, which is untouched and stays
byte-verifiable. Counts over lexicon-index pairs; **off by default**.

| | measured on `learning_test` |
|---|---|
| static baseline | top-1 10.11%, top-3 15.86%, offered 87.48% |
| **adaptive** | **top-1 10.79%, top-3 16.33%, offered 87.59%** |
| delta | **+0.67 points top-1 (+392 cases), +0.48 top-3 (+279)** |
| denominator | 58,343 next-word positions, 120 pseudo-users |

That is the whole size of the effect. It is a real improvement — measured on sentences the
thresholds never saw, against the static model on the identical split — and it is small.

---

## The protocol, and why a Wikipedia article is not a person

There is no corpus of one person's typing. The substitute:

- `learning_dev` and `learning_test` are each **120 contiguous blocks of 80 sentences**. One
  block is one pseudo-user. Blocks are contiguous so a pseudo-user has topical consistency —
  the thing an adaptive layer can exploit — which the strided slices used elsewhere in this
  repository deliberately destroy.
- The first *H* sentences are replayed as typing history into a fresh model, one **session per
  sentence**. Accuracy is measured on the remaining sentences, against the static model on the
  identical split.

| slice | sha256 | role |
|---|---|---|
| `learning_dev` | `0eaa1be095e069026392914a615022d620d599ec3a81e59c8cc7831caedc75ea` | every threshold chosen here |
| `learning_test` | `d8177a78eace8701da1f5af3371b1b6508db16e4a96a22a2b7656b24006cc2f9` | reported once, thresholds fixed |

`scripts/slice_eval_corpus.py` proves all five slices in `lexicon/eval/` are pairwise disjoint —
**37,200 sentences, 0 shared** — and refuses to write when any two intersect. The block slices
are drawn from sentences the strided slices left unused, which is what makes that check a real
proof here: blocks of consecutive indices *would* have collided with a stride-37 slice.

### The central limitation

**A block of encyclopedia sentences is not a person.** Real typing repeats greetings, names,
verbs of address and idiom at rates no encyclopedia approaches, and repeats them *across*
topics rather than within one.

The direction of the resulting bias is **UNVERIFIED**. It is tempting to assume this understates
the benefit, since real users repeat themselves more — but an encyclopedia's topical vocabulary
is also unusually predictable within a block, which pushes the other way. Nothing here measures
which effect is larger, so nothing here claims one.

### One sentence, one session

`UserNgramModel.minimumSessions` counts separate sessions; a session in the app is one focused
field. The proxy here is one sentence, which is **generous to the model**: a real field often
holds several sentences, so a pair repeated within one message counts once in the app and twice
here. The eligibility protection is therefore *weaker* in this simulation than in the product,
never stronger.

---

## The interpolation weight: chosen against the accuracy table

Dev slice, `minimumSessions=2`, history 40, n=62,018.

| weight | top-1 | top-3 | offered |
|---|---|---|---|
| 0.0 — static only | 10.40% | 16.44% | 88.01% |
| 0.5 | 10.58% | 16.64% | 88.12% |
| 1.0 | 10.77% | 16.72% | 88.12% |
| **2.0 — shipped** | **10.87%** | **16.77%** | 88.12% |
| 4.0 | 11.07% | 17.03% | 88.12% |
| 8.0 | 11.24% | 17.17% | 88.12% |
| 16.0 | 11.22% | 17.16% | 88.12% |

**The peak is 8.0 and it was rejected.** The learned layer's largest possible contribution is
`userWeight × userEvidenceCap`, added to a static log-count capped at 255:

| weight | max contribution | overturns corpus pairs seen up to |
|---|---|---|
| 1.0 | 32 | 15 times |
| **2.0** | **64** | **255 times** |
| 4.0 | 128 | 65,535 times |
| 8.0 | 256 | **anything at all** |

At 8.0 a pair the user typed fifteen times outranks *every* corpus pair including the strongest.
That is not interpolation; it is replacement wearing interpolation's clothes. At 4.0 it overturns
pairs seen 65,535 times in a 25.6M-token corpus — overwhelming evidence losing to fifteen
observations.

At 2.0 the ceiling is a corpus pair seen 255 times, roughly one occurrence in 100,000 tokens.
Beating that with fifteen personal sightings is what "it has learned how *you* write" should
mean.

**Cost of that choice: 0.37 points of top-1 and 0.40 of top-3 against the peak.** Paid
deliberately.

---

## The once-seen protection, and what it costs

A pair is not suggestible until seen in `minimumSessions` **separate** sessions. Dev slice, at
weight 4.0:

| minimumSessions | top-1 | top-3 | offered |
|---|---|---|---|
| 1 | 11.47% | 17.47% | 88.70% |
| **2 — shipped** | **11.07%** | **17.03%** | 88.12% |
| 3 | 10.90% | 16.83% | 88.06% |
| 5 | 10.69% | 16.63% | 88.02% |

On the test slice at the shipped weight, the protection costs **0.32 points of top-1 and 0.39 of
top-3**.

**1 scores best and is not shipped.** The floor of 2 is the smallest value that has the property
at all — 1 makes eligibility meaningless — and the property is the whole reason the parameter
exists. It does not move on the strength of an accuracy table. `LearningAccuracyTest` pins the
price so that nobody later "optimises" it away without seeing what they are selling.

That the protection costs accuracy is the evidence it actually withholds something.

### How much it withholds

Measured on the test slice, per pseudo-user after 80 sentences:

| | |
|---|---|
| distinct pairs learned | mean **888**, max 1,341 |
| **eligible to be suggested** | mean **52 — 5.8% of what was stored** |
| pairs touching the OOV sentinel | 8.3% |
| largest user vs the 40,000-pair cap | 3.4% |

**94.2% of what this layer learns can never be suggested back.** That is not waste; it is the
mechanism. A card number, an address or a name typed once into a chat field sits in that 94.2%,
is never offerable, and ages out under eviction without ever having been surfaced.

It is also most of why the accuracy gain is small, and those two facts are the same fact.

---

## Cold start

The benefit is zero with no history and grows. Test slice, shipped configuration:

| sentences of history | delta top-1 | adaptive top-1 | n |
|---|---|---|---|
| 0 | **+0.00** | 10.31% | 117,988 |
| 10 | +0.14 | 10.49% | 102,924 |
| 40 | +0.57 | 11.02% | 58,343 |

The zero at zero history is **asserted**, not observed: with no history the adaptive layer must
be exactly static, and anything else would mean it was inventing evidence it does not have.

Dev slice at weight 4.0 extends the curve further — +0.36 at 20 sentences, +0.67 at 40, +1.10 at
60 — but that is a weight that does not ship, and the test-slice column above is the one to read.

Note the denominators shrink as history grows: more history means fewer sentences left to score
on. The 60-sentence row on dev rests on 31,172 positions, not 117,988.

---

## What these numbers should NOT be misread as

- **Not a claim about phone typing.** The corpus is Hebrew Wikipedia and the pseudo-users are
  contiguous article text. The register is wrong and the sampling discipline does not fix that.
- **Not a claim about one person.** "Pseudo-user" means a block of sentences that share a topic.
  Nobody's actual writing was measured, because no such corpus exists here.
- **+0.67 points is not "the keyboard gets noticeably better".** It is roughly one additional
  correct first suggestion in every 175 words, under a protocol that is generous about sessions
  and pessimistic about vocabulary. Whether a person would notice it is **NOT MEASURED** and
  would need real users.
- **The offer rate is reported because the hit rate alone can lie.** A layer that improved
  accuracy by falling silent on hard cases would look identical on top-1. Offer rate went
  slightly *up* (88.68% → 88.78%), and `LearningAccuracyTest` asserts it never goes down.
- **No device latency number.** The learned lookup is a `HashMap` probe on the same off-thread
  path as prediction, inside the same `HebrewIme.suggest` trace section — which has never been
  measured on hardware, and is not measured here either.
- **The OOV share is a floor.** 8.3% of learned pairs touch the sentinel on Wikipedia text. Real
  typing has more names and handles in it, so the real share is higher by an amount that is NOT
  MEASURED.
- **This says nothing about whether the encrypted file on a device contains what the codec
  wrote.** Nothing has run on a device.

## Reproducing

```sh
./gradlew :core:test --tests '*LearningAccuracyTest*'    # the reported numbers, test slice
./gradlew :core:test --tests '*LearningShapeTest*'       # pairs, eligibility, OOV share
./gradlew :core:test --tests '*LearningNeutralityTest*'  # an empty model changes nothing
./gradlew :core:test --tests '*LearningSweepTest*' -PrunLearningSweep=1   # the dev sweep
python3 scripts/check_learning.py                        # GATE-LEARN-1 / GATE-LEARN-2
python3 scripts/check_learning.py --inject-defect schema # control: must exit 1
python3 scripts/check_learning.py --inject-defect guard  # control: must exit 1
```


---

# L2 — four directions for adaptive learning, and the rule for judging them

The shipped layer learns **word pairs, and nothing else**. Measured, only **5.8%** of what it
learns is ever eligible to be suggested. That is the once-seen protection working as designed,
and it is also most of the reason the gain is +0.67 points rather than something a person would
notice.

Four directions are built and measured here. The rule is fixed first, before any of them is run.

## The rule

A direction ships only if, on `learning_test` with everything already chosen on `learning_dev`:

1. It beats the **current adaptive layer** on top-1, not merely the static baseline. Beating
   static is not the bar; the current layer already does that.
2. Its **offer rate does not fall**. A gain bought by going quiet on hard cases is not a gain.
3. It does not weaken any privacy property: counts over ids, session-gated eligibility, no
   stored text, and nothing on disk that identifies where the user was typing.
4. Its cost in size and per-keystroke work is stated.

**A direction that fails is reported and not shipped**, and the four are judged independently —
"the best of four" is not a result if none of them clears the bar.

## What is NOT measurable here, stated before the numbers

**Per-app bias** cannot be measured honestly: the simulated-user protocol has no app dimension,
and inventing one would produce a number about my own fabrication. It is measured instead as a
**generic context bucket** — the same mechanism with the app identity removed — which answers
the mechanical question (does conditioning on a sub-context help?) without pretending to answer
the product one.

That substitution is also what would ship. Storing learned counts keyed by package name puts a
record on disk saying *what you write in which app*. `ImeDiagnostics` already refuses to record
package names for weaker reasons than this.


## L2 RESULTS

Measured on `learning_test`, 120 pseudo-users, 40 sentences of history, n=58,343 next-word
positions and n=45,031 completion positions.

| direction | result | verdict |
|---|---|---|
| **D1 personal word frequency** | **+1.98 top-1, +2.59 top-3** on completions | **SHIPS** |
| D2 context bucket | +0.146 top-1, +0.161 top-3 | measured, not shipped |
| D3 endorsed pairs weigh more | +0.017 top-1, +0.000 top-3 | fails |
| D4 out-of-lexicon words keyed individually | ceiling ≈ 0 | refusal costs nothing |

### D1 — and the measurement that was in the wrong place

The first run scored personal word frequency on **next-word** prediction, where it lost **1.62
points**, and that nearly ended it. The idea was fine; the measurement was in the wrong place.

Personal frequency is **context-free**. Added to next-word ranking — a question that already has
a context — it promotes words the person uses often whether or not they belong after the
preceding word, drowning the pair evidence that makes the prediction worth anything. Added to
**completion** ranking, where the question is "which of the words starting with these letters did
they mean", it is exactly the right evidence:

| 2-letter prefix, n=45,031 | top-1 | top-3 |
|---|---|---|
| lexicon frequency only | 3.66% | 9.75% |
| + personal word frequency | **5.64%** | **12.34%** |
| delta | **+1.98** | **+2.59** |

**Three times the entire pair-learning layer**, which is worth +0.67. It ships in `completions`
and is deliberately absent from `nextWord`, and both numbers are in the KDoc so that the absence
reads as a decision rather than an oversight.

A bug found on the way: personal counts were added to the model before the codec, so they worked
inside one session and reset on every restart — invisible to every in-memory test.
`personalWordFrequencySurvivesTheRoundTrip` is the regression, and the format is now v2.

### D2 — real, and not worth it

+0.146 points is about 85 additional correct first suggestions in 58,343. It is above noise and
it is not worth a second storage table, a second eligibility interaction, and a mechanism whose
natural next step is keying by application. **Measured, reported, not shipped.**

### D3 — fails

+0.017 points. Weighting endorsed observations more heavily does approximately nothing here,
and the simulated endorsement (a pair the corpus strongly attests standing in for one the user
tapped) is a weak proxy for a real tap. The honest reading is that this was **not really
tested**, and the number is too small to justify testing it properly.

### D4 — the privacy refusal is nearly free, and now has a price tag

The shipped model collapses every out-of-lexicon word to one sentinel, so it can never learn a
name. Measured in the pseudo-users' histories: **3,133 out-of-lexicon occurrences across 2,519
distinct forms — a mean of 1.24 occurrences each.**

They almost never repeat. With the two-session eligibility rule they would be withheld anyway,
so storing their text would buy close to nothing while creating exactly the record the design
refuses to create.

**That is the number this direction was for.** It does not reopen the decision; it prices it,
and the price is approximately zero.

---

# What the learning DID, counted on the device

The settings screen used to report **"N word pairs learned"**. That number rises whether or not
anything got better — it counts what was stored, not what was delivered. It is the same shape as
the abbreviation table that `D5` found had no precision control at all.

It now reports what the learning **did**: how many accepted completions were on screen *only
because* of it.

## Causal, not "influenced"

`Prediction.fromUserModel` is a **counterfactual**, not a contribution test. It is set when the
suggestion would not have been in the returned list at all with the user model removed —
computed by re-ranking the same candidates with the user terms subtracted.

The distinction is the whole point. Once someone has typed for a while the model contributes
*some* evidence to nearly every completion, so a flag meaning "the model touched this" would be
true of almost everything, and the screen would report activity dressed as benefit.

Two defects the positive control caught while this was being built, both of which would have
made the number quietly wrong rather than obviously broken:

1. **The counterfactual was skipped for a model holding only personal-frequency evidence.** The
   guard tested `pairCount == 0`, and personal frequency lives in the unigram table. A user who
   had taught the keyboard words but no pairs would have been credited nothing, forever.
2. **The user model was breaking its own tie-breaks.** The counterfactual was sorted from the
   already-with-model-sorted list, so where two candidates tied on score, a word the model had
   lifted kept its position and appeared to have been there all along. Both rankings now derive
   from one unsorted list, and the counterfactual never consults the model — not even to break a
   tie.

`UserModelAttributionTest` pins all three directions: an empty model credits nothing, a word the
model lifted onto the strip is credited, and a word that was already being offered is **not**.

## What is stored

Two integers in `SharedPreferences`: completions accepted, and completions accepted that were
attributable. Not which words, not when, not in which app. `GATE-LEARN-1` refuses a `String`
anywhere in the learning path and these are counts. Both are cleared by *Forget what you
learned* — a count of what was forgotten is still a record of it.

## The screen says what the measurement supports

Under the number, in the UI and not only here: *"Measured on a held-out corpus, the learning is
worth about one extra correct completion for every 149 words — this shows what it did for you,
not a score."*

That sentence exists because a bare rising counter implies a benefit curve, and the measured
effect is **+0.67 top-1 points**. A screen that implied more would be a claim wider than its
measurement, in the place a user actually reads rather than in a document they never will.

**Deliberately not a game.** No streak, no badge, no progress bar. This keyboard's promise is
that it does not watch you; a score that goes up as it records more of your writing inverts that
promise however local the data stays.

# K1 — can Kotlin do the arithmetic inside the budget?

**Prediction and stopping rule recorded before a line of the harness exists.**

## Where this comes from

Three measurements point at the same place, from three directions:

- **`H1`** — 99.16% of conversational tokens are already in the lexicon, and **50.78%** are one
  `ו`/`י` deletion from another real word. *The keyboard knows the words and cannot choose
  between them.*
- **`D1`** — on positions where the adjacent window is blind, the shipped table recovers **~0%**
  and a model that reads the sentence recovers **97.5%**. *62.31% is a property of the model,
  not of the problem.*
- **`B1`, `O1`, the human labelling** — allocation is not a lever, the offer decision is not a
  lever, and no threshold rescues the confusion layer. *The representation is the ceiling.*

`D1`'s stopping rule closed the only door those three point at, in one sentence — *"No BERT at
runtime, ever"* — citing `GATE-NET-1/2/3` and the size budget. Both were checked against the
artifacts on 2026-08-25 (see `docs/CONFUSION_MEASUREMENTS.md`, *D1's constraint, re-examined*):
no gate bans a parametric model, the budget has room for one, and the latency constraint that
would actually bind **has never been measured**.

The real constraint the sentence was protecting is **auditability**: a native runtime ships a
`.so`, and the no-network proof — manifest, source scan, DEX scan — reaches none of it. Both
scanners say so in their own `NOT_COVERED`. That constraint is satisfied by inference written in
**Kotlin, inside `:core`**, where every existing gate still reaches and no `.so` is added.

So the question is no longer *may we*. It is **can Kotlin do the arithmetic inside the budget** —
and that is measurable today.

## What this experiment is, and what it is not

It measures **arithmetic, not a model**. Weights are random; nothing is trained; **no accuracy
claim of any kind can come out of it.** It answers one question: for model shapes that fit the
byte budget, what does one keystroke's forward step cost.

It runs on the **build host, not a phone** — so it is deliberately **one-sided**:

> **A result here can kill the branch. It cannot bless it.**

A shape that does not fit on a 4-core x86 host with a warm JIT will not fit on a phone. A shape
that does fit here has proven nothing about a phone, and the question moves to **`M7-LAT`**,
which has a harness built and has never run.

## The shapes, fixed now

Anchored to the real budget from `tools/size_budget.json`: **576,837** free asset bytes today,
or **2,426,473** for a model that *replaces* the 1,849,636-byte bigram table. int8 weights, so
one parameter is one byte.

| id | vocab | emb | hidden | layers | ≈ int8 bytes | fits |
|---|---|---|---|---|---|---|
| A | 8,192 | 32 | 128 | 1 | ~344 K | in today's free assets |
| B | 16,384 | 32 | 128 | 1 | ~606 K | only if the table goes |
| C | 32,768 | 48 | 192 | 1 | ~1.76 M | only if the table goes |
| D | 65,536 | 64 | 256 | 2 | ~4.8 M | **over budget — included to find the wall** |
| **E** | **355,587 (the whole lexicon)** | 32 | 128 | 1 | **~11.4 M** | **far over — this is the control** |

Two modes per shape, because they are two different tasks:

- **`score-k`** — encode one step, then dot the hidden state against **k = 8** candidate output
  embeddings. This is the purpose-ג׳ task: *choose between real-word neighbours*. The output
  softmax is not needed and is not computed.
- **`full-softmax`** — encode one step, then project over the entire vocabulary. This is the
  purpose-א׳ task: *predict the next word*.

## The positive control

**Shape E under `full-softmax` must fail the latency bar.** 355,587 × 128 is ~45 million
multiply-accumulates per keystroke; if the harness reports that inside the budget, it is not
measuring what it claims and the whole run is void. This is the same rule `run_gates.py` applies
to every gate — *a check that cannot fail has proven nothing* — applied to a measurement.

## The prediction, written now

1. **The arithmetic is not the problem.** `score-k` at shapes A–C will come in at **p95 between
   0.05 ms and 0.50 ms** on the build host. A 128×128 int8 step is ~16 K MACs, which the JVM does
   in microseconds, and eight dot products are noise beside it.
2. **The two modes will differ by more than two orders of magnitude**, and `full-softmax` at
   shape C or larger will breach the bar. **The task decides feasibility, not the model size.**
3. **The binding constraint will turn out to be the vocabulary, not the speed.** 355,587 surface
   forms cannot be embedded in 2.4 MB at any useful dimension — which forces a morpheme or
   subword vocabulary, the same representation `H1` said would collapse the sparsity. Two
   independent constraints arriving at one answer.
4. **Cold load will be the real finding.** Reading and dequantizing 1–2 MB at startup, beside the
   148 ms the trie already takes, is where I expect this to hurt.

If `score-k` at shape A comes back **above 2 ms** on this host, prediction 1 is badly wrong and
the branch should be treated as dead until someone explains why.

## The stopping rule

Measured once, with every shape and mode fixed above, on the build host:

| clause | bar |
|---|---|
| **kill** | if **no** shape that fits the asset budget achieves `score-k` p95 **at or below the whole current suggestion path's p95, measured in the same run**, the branch is dead |
| **control** | shape E `full-softmax` **must** breach that bar, or the run is void |
| **memory** | resident footprint ≤ **2×** the serialized bytes, or a byte budget means nothing |
| **load** | cold load and dequantize ≤ the trie build time **measured in the same run** |

The latency bar is written against **the baseline measured in the same run**, never against the
published 2.88 ms. That is the flaw `S1` exposed in this project's rule-writing, and it is not
being repeated.

**Passing every clause adopts nothing.** It licenses exactly one thing: asking `M7-LAT` on a
phone. A trained model, where its weights come from, and whether it is any good are separate
questions with separate rules, and none of them is opened by this.

## What this does NOT cover

- **Accuracy.** Random weights. No claim about quality is derivable from any number this
  produces.
- **A phone.** JVM only, and one-sided by design.
- **Segmentation.** The sub-lexicon vocabularies above are hypothetical shapes; no Hebrew
  morpheme or subword segmentation exists in this repository, and building one is its own work
  with its own licence questions.
- **Training.** Where weights would come from, and under what licence, is untouched here.
- **The rest of the keystroke path.** It times a forward step in isolation, not the path around
  it. GATE-TRACE-2 exists because a suspending call once sat inside a traced region for four
  milestones, and an isolated microbenchmark is exactly the shape that hides that.

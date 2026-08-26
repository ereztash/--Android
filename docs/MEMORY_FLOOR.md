# E2 — where the memory actually is

`E1` measured the bigram table as the largest thing the app **ships**: 1,682,421 B in the APK,
32.8% of it, with about 0.66 MB recoverable by a better encoding. The next question was whether
compressing it frees **runtime memory**, because runtime memory is what a new feature needs.

**It does not. The measurement inverts the premise.**

Reproduce with `./gradlew -q :core:printTestClasspath` then
`CP=<that> bash scripts/measure_memory_floor.sh`.

## The instrument, and why it is not a retained size

`K1` tried to measure resident bytes by differencing `totalMemory − freeMemory` around an
allocation and got **−408,760 bytes**, which is impossible, so it recorded `NOT-MEASURED`. A
GC-delta is taken at the JVM's discretion and is not an instrument.

This binary-searches **the smallest `-Xmx` at which each load completes**, one JVM per (stage,
heap) pair, launched directly rather than through Gradle — Gradle would add its own heap to
every measurement, which is the thing being measured.

A heap floor **over-states** the structure's retained size by whatever headroom the collector
needs. That is the useful direction: it is a **lower bound on what a device must supply**.

## The result

Stages are cumulative, so each cost is its own floor minus the previous one's.

| stage | heap floor | its own cost | what it is |
|---|---|---|---|
| lexicon | 17 MB | **17 MB** | 355,587 words |
| **+ trie** | **45 MB** | **+28 MB** | `LexiconTrie`, **567,767 nodes** |
| + frequency | 45 MB | +0 MB | 355,587 counts |
| **+ bigrams** | **47 MB** | **+2 MB** | 477,180 continuations |

**The bigram table costs 2 MB of heap. The trie costs 28 MB — fourteen times more.**

## So the two budgets have different targets, and they are not the same work

| | largest item | recoverable | technique |
|---|---|---|---|
| **APK bytes** | bigram table, 1.68 MB | ~0.66 MB (`E1`) | entropy-coded gaps, Elias–Fano |
| **runtime RAM** | **`LexiconTrie`, 28 MB of 47** | unmeasured, but this is where it is | succinct trie — LOUDS, DAFSA, MARISA |

**Compressing the bigram table would have freed almost no memory.** That was the plan an hour
ago, and it was aimed at the wrong structure.

## Why the `E1` trap does not apply here

`E1`'s lesson was that a smaller *raw* encoding can be no smaller *shipped*, because the APK
already deflates its assets — which is what makes 19-bit index packing worthless.

**The trie has no shipped form at all.** It is built at runtime from the lexicon text, so a
succinct representation competes only against 28 MB of pointers and objects, never against
deflate. The published figures for succinct tries against object-graph tries are large — the
literature quotes reductions of an order of magnitude and more — and the comparison here is the
favourable one.

## What this would buy, in the units that matter

`K1` measured an int8 candidate model at **548,992 bytes** for 16,384 units, scoring in 0.018 ms.

**The trie alone is roughly 51× that entire budget.** A succinct representation that recovered
even a fifth of it would free more than forty times what `K1`'s model needs.

## Not measured

- **This is a build-host JVM with `-XX:+UseSerialGC`, not Android's ART.** A different collector
  or runtime gives a different floor. **`M7-LAT` has still never run**, and neither has any
  device memory figure.
- **The 28 MB is a floor, not a retained size.** How much of it is collector headroom is
  unknown, and separating them needs a heap dump nobody has taken.
- **No succinct trie has been built here.** The reduction above is what the literature reports
  for the technique, not something this project has measured on this lexicon. **That is the
  experiment `E2` justifies pre-registering, and it is not evidence yet.**

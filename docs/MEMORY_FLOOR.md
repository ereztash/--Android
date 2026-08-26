# E2 — where the memory actually is

`E1` measured the bigram table as the largest thing the app **ships**: 1,682,421 B in the APK,
32.8% of it, with about 0.66 MB recoverable by a better encoding. The next question was whether
compressing it frees **runtime memory**, because runtime memory is what a new feature needs.

**It does not.** That conclusion survives. The numbers under it did not.

> ## Corrected — the first published figures measured a path the app does not take
>
> The first version of this probe copied the lexicon into an `ArrayList<String>` before building
> the trie. **`CorrectionController` does not.** It calls `LexiconTrie.build(lexicon.asWordList())`,
> and `asWordList()` is a non-copying view: one word string is alive at a time, decoded on `get`
> and immediately garbage.
>
> That copy — not the trie — was most of what the probe was weighing. Correcting it moves every
> published number:
>
> | | first published | corrected |
> |---|---|---|
> | total floor | 47 MB | **27 MB** |
> | trie's own cost | +28 MB | **+6 MB** |
> | bigram table's own cost | +2 MB | **+4 MB** |
> | headline | "the trie is fourteen times the table" | **the trie is 1.5× the table, and the lexicon load is larger than both** |
>
> This is the same error as `A2` and `W7`: a harness that measured something adjacent to what
> ships. It is recorded here rather than quietly rewritten because the failure mode is the
> point — three times now, the harness was wrong in the direction that made the result
> interesting.
>
> The discarded arm is kept as the `trie-copy` stage rather than deleted, so the difference is
> visible instead of asserted. It is also a **positive control on the instrument**: `asWordList()`'s
> own KDoc records the copy as costing ~34 MB, measured independently and long before this probe
> existed. The probe puts it at +22 MB of floor. Same structure, same direction, and the
> disagreement is itself informative — see *What a delta is not*.

Reproduce with `./gradlew -q :core:printTestClasspath` then
`CP=<that> bash scripts/measure_memory_floor.sh`.

## The instrument, and why it is not a retained size

`K1` tried to measure resident bytes by differencing `totalMemory − freeMemory` around an
allocation and got **−408,760 bytes**, which is impossible, so it recorded `NOT-MEASURED`. A
GC-delta is taken at the JVM's discretion and is not an instrument.

This binary-searches **the smallest `-Xmx` at which each load completes**, one JVM per (stage,
heap) pair, launched directly rather than through Gradle — Gradle would add its own heap to
every measurement, which is the thing being measured.

## The result

`empty` is the zero arm: a JVM that reaches `main` and loads nothing. Without it, `lexicon`'s
17 MB conflates the lexicon with whatever a JVM costs to start, and nothing distinguishes them.

| stage | heap floor | its own cost | structure's own arrays | what it is |
|---|---|---|---|---|
| empty | 2 MB | — | — | zero arm: JVM reaches `main` |
| **+ lexicon** | **17 MB** | **+15 MB** | 6,029,781 B (5.75 MB) | 355,587 words: blob + offset index |
| **+ trie** | **23 MB** | **+6 MB** | 7,948,738 B (7.58 MB) | `LexiconTrie`, 567,767 nodes |
| + frequency | 23 MB | +0 MB | — | 355,587 counts |
| **+ bigrams** | **27 MB** | **+4 MB** | — | 477,180 continuations |
| *(trie-copy)* | *45 MB* | *+22 MB over `trie`* | — | **not a shipped path**; the discarded arm |

**The whole shipped warm-up fits in 27 MB, and the largest single step is loading the lexicon.**

## What a delta is not

Two rows contradict the reading "delta = retained size", and both matter:

- **The trie's delta (+6 MB) is *smaller* than its own arrays (7.58 MB).** A structure cannot cost
  less than the memory it holds. What this actually says is that the 17 MB the lexicon stage
  needed already contained at least 1.6 MB of slack the trie stage then reused. A delta is the
  **marginal** cost of adding a stage to a JVM already sized for the previous one — nothing more.
- **The lexicon's delta (+15 MB) is 2.6× its own arrays (5.75 MB).** Here the gap runs the other
  way, and it is the loader: `readBytes()` grows a `ByteArrayOutputStream` by doubling and then
  `toByteArray()` copies it into a second, exactly-sized array, so both are live at once. The
  **transient peak of the load** sets the floor, not the structure the load leaves behind.

So the deltas bound what a device must supply for each step. They are not retained sizes, and
this document does not use them as such.

## So the two budgets have different targets, and they are not the same work

| | largest item | recoverable | technique |
|---|---|---|---|
| **APK bytes** | bigram table, 1.68 MB | ~0.66 MB (`E1`) | entropy-coded gaps, Elias–Fano |
| **runtime RAM** | lexicon **load**, +15 MB of 27 | unmeasured | inflate into an exactly-sized array |
| | `LexiconTrie`, +6 MB / 7.95 MB held | unmeasured | succinct trie — LOUDS, DAFSA, MARISA |

**Compressing the bigram table would have freed almost no memory.** That was the plan, and it was
aimed at the wrong structure. It still is — but the structure it should have been aimed at is not
the one the first version of this document named.

## Why the `E1` trap does not apply here

`E1`'s lesson was that a smaller *raw* encoding can be no smaller *shipped*, because the APK
already deflates its assets — which is what makes 19-bit index packing worthless.

**The trie has no shipped form at all.** It is built at runtime from the lexicon text, so a
succinct representation competes only against 7.95 MB of arrays, never against deflate. That
reasoning is unchanged by the correction; only the size of the prize moved.

## What this would buy, in the units that matter

`K1` measured an int8 candidate model at **548,992 bytes** for 16,384 units, scoring in 0.018 ms.

The trie's arrays are **7,948,738 B — about 14× that budget**, not the 51× first published. A
succinct representation that recovered three quarters of it would free roughly **11×** what `K1`'s
model needs. Still worth having; no longer the dominant term.

## Not measured

- **This is a build-host JVM with `-XX:+UseSerialGC`, not Android's ART.** A different collector
  or runtime gives a different floor. **`M7-LAT` has still never run**, and neither has any
  device memory figure.
- **The loader-transient reading of the lexicon's +15 MB is inferred from the source, not
  measured.** No arm has yet loaded the lexicon by inflating into an exactly-sized array, so the
  claim that it would drop the floor is a hypothesis.
- **No succinct trie has been built here.** The reduction above is what the literature reports
  for the technique, not something this project has measured on this lexicon.

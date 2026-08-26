# E1 — how much of the app is one table, and how much of it is compressible

`A2` established that external **data** has little measured leverage left: the completion model
is the only metric it can move, and the corpus it needs does not exist. This asks the other
question — whether external **method**, the succinct-data-structure and quantised-LM literature,
has leverage on the **bytes**.

Reproduce with `python3 scripts/measure_bigram_entropy.py`.

---

## The correction this measurement forced, before any of its own results

I said, in the sentence that started this: *"2.7 MB of the app is one bigram table."*

**That is wrong, and the error is instructive.** The APK is a zip and deflates its assets. What
the app actually pays:

| asset | unpacked | **in the APK** |
|---|---|---|
| `he_bigrams.bin` | 2,697,304 | **1,682,421** |
| `he_lexicon.txt` | 4,607,433 | 926,882 |
| `he_freq.bin` | 355,587 | 242,579 |
| `he_abbreviations.txt` | 19,518 | 6,710 |
| **total assets** | 7,684,045 | **2,862,795** |

The table is **1.68 MB of a 5,121,195 B APK — 32.8%.** Still the largest single item, and not
the number I quoted. **Every saving below has to be counted against the deflated figure**, which
roughly halves the apparent prize.

## The format, and the obvious optimisation that is worthless

    u32 groupCount
    per group:  u32 firstWordIndex, u16 continuationCount
    per cont.:  u32 secondWordIndex, u8 logCount      ← 5 bytes each

51,900 groups, **477,180 continuations**, 5.653 bytes per entry raw. The lexicon holds 355,587
words, so an index needs **19 bits, not 32** — a third of every entry is guaranteed zero.

**Packing indices to 19 bits saves essentially nothing shipped.** It reaches 3.375 B/entry raw
against gzip's 3.523 — and bit-packed data compresses badly, so the *deflated* result lands
around where the file already is. **Deflate is already removing those zero bytes.** The obvious
fix is worthless, and only measuring against the compressed figure shows it.

## The floors

| encoding | bytes/entry | implied shipped size |
|---|---|---|
| today, raw | 5.653 | — |
| **today, as the APK ships it** | **3.523** | **1,682,421** |
| 19-bit index + 8-bit count, bit-packed | 3.375 | ~1.6 MB — **no real gain** |
| Elias–Fano index + entropy-coded count | 2.401 | ~1,146,000 |
| **gap-entropy + count-entropy** | **2.139** | **~1,021,000** |

Component entropies: the log-count byte carries **4.579 bits** of 8 (95 distinct values); index
gaps sorted within a group carry **12.533 bits**. Group headers are a further **311,404 bytes,
11.5% of the file**, at 6 bytes per group for a `u32` that is monotone and a `u16` that is
almost always small.

**These are floors, not proposals.** No encoder reaches its source entropy. But a near-entropy
format is essentially incompressible, so its raw size *is* its shipped size — which is why the
comparison is legitimate.

## So the prize is about 0.66 MB, or 13% of the APK

**1,682,421 → ~1,021,000.** Real, and a third of what the naive framing suggested.

## What is NOT established, and it is the whole cost side

**The trade is bytes against lookup latency, and this project has never measured latency on a
device.** `M7-LAT` has never run. `BigramModel.logCountFor` currently does a **linear scan over
a group** of plain 5-byte records; a gap-coded format keeps that access pattern but adds a
decode step per record, and Elias–Fano changes it to a select operation.

Neither is obviously fatal and **neither is priced**. A byte saving bought with a latency cost
this project cannot see is not an improvement — it is a trade made blind, which is what
`ARM-EDGE`'s waiver already says about shipping without `M7-LAT`.

**Nothing is adopted here.** This is the measurement that decides whether an experiment is worth
pre-registering, and what its bar would have to be: **beat 1,682,421 shipped bytes without
losing on a latency number that does not yet exist.**

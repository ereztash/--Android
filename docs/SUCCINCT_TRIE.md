# E3 — a succinct trie on this lexicon

**Pre-registration. Written before the harness exists, and before any of the numbers below the
line were measured.** `E2` justified this experiment while overstating its prize by 4×; the
corrected prize is what is being bet on here.

## What is being tested

`LexiconTrie` is already flat and array-backed — four parallel arrays, no per-node object. Its
size is not estimated, it is arithmetic:

| array | width | bytes for 567,767 nodes |
|---|---|---|
| `charOf` | `Char` | 1,135,534 |
| `firstChild` | `Int` | 2,271,068 |
| `nextSibling` | `Int` | 2,271,068 |
| `wordIndex` | `Int` | 2,271,068 |
| | | **7,948,738** |

**86% of that is three arrays of node ids.** A succinct representation replaces those ids with a
bit string plus rank/select, which is what the technique is for. The question is whether it pays
on *this* lexicon at *this* size, not whether the literature says it pays in general.

## Predictions, committed now

1. **Structure size < 1,500,000 B.** Bar: **at least a 4× reduction** on 7,948,738. Below 4×, the
   added rank/select machinery is not worth the complexity and this is reported as not worth
   shipping, whatever the absolute saving.
2. **The node alphabet is ≤ 64 distinct characters**, so labels fit a `ByteArray` rather than a
   `CharArray`. Hebrew has 27 letters counting finals; the rest should be punctuation. If it
   exceeds 256 the label array cannot narrow and prediction 1 is much harder to meet.
3. **The heap floor drops, but by much less than the structure shrinks.** The lexicon's own 17 MB
   floor dominates and the trie's marginal cost was only +6 MB. Predicted succinct floor:
   **18–21 MB**, against 23 MB. A floor that does not move at all falsifies the claim that the
   trie's arrays are what that +6 MB was.
4. **Exact equivalence.** For every word in the lexicon the succinct trie accepts it, and
   `search()` returns byte-identical results to `LexiconTrie.search()` on every sampled query.
   **Any mismatch fails the experiment.** Equivalence is not something to be traded against size.

## The positive control, designed before the check it controls

The repo's rule is that a check which has never failed has not been shown to be a check.

**PC-1.** Plant an off-by-one in LOUDS child enumeration (`select0(i) + 1` becomes `select0(i)`).
The equivalence check must go **RED**. If it stays green it is `NOT-A-CHECK` and no size number
from this experiment means anything.

**PC-2.** Plant a defect in the terminal marking (drop the last terminal). The whole-lexicon
acceptance check must go **RED**.

**PC-3.** Plant a defect that a whole-lexicon acceptance check *cannot* see — accept a word that
is not in the lexicon — and confirm acceptance stays green while the equivalence check goes red.
This is what establishes that the two checks are not the same check wearing two names.

## Stopping rule

The experiment ends when the four predictions have been measured once. It does **not** iterate on
the encoding until a number clears the bar. If the structure lands above 2,000,000 B the answer is
"the technique does not pay here", and that is the result, not a starting point.

No shipping decision is part of this experiment. `LexiconTrie` is on the input path and correct;
replacing it is a separate change with its own latency evidence, and `M7-LAT` has still never run.

---

# Result

Run with `./gradlew :core:test --tests '*SuccinctTrieTest*' -PrunSuccinctTrie=1`, and the heap
floors with `CP=<classpath> bash scripts/measure_memory_floor.sh`.

## The controls ran first, and all three went red

```
PC-1 off-by-one child walk   -> equivalence RED
PC-2 dropped last terminal   -> acceptance  RED
PC-3 extra accepted word     -> acceptance  GREEN, equivalence RED
```

`PC-3` is the one that earns the other two. An interior node marked terminal accepts a string
that is not a word, and **whole-lexicon acceptance cannot see it** — every real word still
resolves. Only equivalence goes red. Without that arm, "acceptance" and "equivalence" could have
been one check wearing two names, and the pair would have proven half of what it appears to.

## The four predictions, scored

| # | prediction | bar | measured | |
|---|---|---|---|---|
| 1 | structure size | < 1,500,000 B and ≥ 4× | **802,925 B, 9.90×** | **HELD** |
| 2 | node alphabet | ≤ 64 symbols | **27** | **HELD** |
| 3 | heap floor for the trie stage | 18–21 MB, from 23 | **17 MB** | **FALSIFIED** |
| 4 | equivalence | exact, no exceptions | 2,000 queries identical; 355,587 / 355,587 accepted | **HELD** |

**Prediction 3 is recorded as falsified even though it missed in the favourable direction.** The
succinct trie's marginal floor cost is not "smaller than predicted", it is **zero** — the whole
structure fits inside slack the lexicon load already required. The reasoning behind the 18–21 MB
guess, that the trie's arrays would still show up as a visible step, was simply wrong.

## What the structure is

| | `LexiconTrie` | `SuccinctTrie` |
|---|---|---|
| labels | `charOf`, 1,135,534 B | `labelOf` bytes, 567,767 B |
| shape | `firstChild` + `nextSibling`, 4,542,136 B | balanced parens, 2 bits/node, 141,944 B + 17,752 B block index |
| word ids | `wordIndex`, 2,271,068 B | terminal bitvector + rank, 75,462 B |
| | **7,948,738 B** | **802,925 B** |

The three id arrays do not get *compressed*; they get **deleted**. Pre-order numbering makes
`firstChild[v]` always `v+1`, `nextSibling[v]` the end of `v`'s subtree, and `wordIndex` the rank
of a terminal — all three are the tree's shape restated, and balanced parentheses hold the shape
in 2 bits per node while keeping the pre-order numbering the third one depends on.

**This is why the encoding is balanced parens and not LOUDS.** LOUDS renumbers into BFS order,
which breaks the terminal-rank property and forces an explicit 355,587-entry map back to word
indices — about 1.42 MB, more than the entire shape it replaced. The obvious textbook choice was
the wrong one here, for a reason specific to how `LexiconTrie.build` numbers its nodes.

## The full chain, which is the number a decision would rest on

| | floor |
|---|---|
| lexicon + `LexiconTrie` + frequency + bigrams | **27 MB** |
| lexicon + `SuccinctTrie` + frequency + bigrams | **19 MB** |

**8 MB, a 30% reduction of the whole shipped warm-up floor** — more than the 7.15 MB the structure
itself saves, because the trie's arrays also forced collector headroom around them.

## Unregistered: what it costs on the input path

No latency bar was pre-registered, so none is applied. It is measured anyway, because a 10×
memory win that costs 20× on the input path is not a win, and leaving the obvious objection
unmeasured is the mistake `E2` already made once.

| | ms per search |
|---|---|
| `LexiconTrie` | 1.415 |
| `SuccinctTrie` | 3.550 |
| | **2.51×** |

Every node visit that was an array index is now a `rank1`, and every sibling step is a
`findClose`. That is the trade the technique makes, and here it is 2.51× on a build host over 300
perturbed queries, three repetitions after warm-up.

## Verdict

**The technique pays, and it is not shipping on this evidence.**

Size and equivalence both cleared their bars by a wide margin, and the floor did better than
predicted. But the input path got 2.51× slower, there is **no pre-registered latency bar to
clear** because **`M7-LAT` has still never run**, and these are build-host figures from a
`SerialGC` JVM rather than ART on a device. Replacing a correct structure on the input path with
a slower one, to free memory no feature is currently asking for, on host numbers, is a trade
nothing here has earned.

What this experiment establishes is that the 8 MB is **real and available**, at a known price,
whenever something needs it.

## A side finding the alphabet forced out

The node alphabet is **exactly 27 characters: U+05D0–U+05EA, the 22 Hebrew letters and the 5
final forms, contiguous, and nothing else.** No geresh, no gershayim, no hyphen, no apostrophe.

That is not a property of the trie; it is a property of the shipped lexicon, and it is
load-bearing elsewhere. `A2` measured 65.4% of gershayim abbreviation forms as out of vocabulary
against the shipped path. **The lexicon cannot represent them at all** — no quantity of added
words would fix it while the alphabet excludes the character. That gap is structural, not
coverage, and `A2` should be read that way.

## Not measured

- **No device figure.** Build-host JVM, `-XX:+UseSerialGC`. `M7-LAT` has still never run.
- **Construction time** for the succinct form was not measured, only the search time it serves.
- **`completions` / `completionsTopK` were not ported.** Equivalence covers `search` and
  `contains` only, so this is not a drop-in replacement even if the latency question were settled.
- **The controls ran on a 20,000-word subset**, chosen so three extra builds stay affordable.
  They show the checks *can* go red, which is what a control is for; they do not show the
  full-scale build is defect-free — the full-scale equivalence and acceptance arms do that.

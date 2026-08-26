# B2 — the bidi arms `B1` never tested, on real material

**Written before the harness exists.** The arms, the corpus rule, the four predictions, the
controls and the adoption rule below were committed in the commit that created this file.

`docs/PROGRAM.md` W3. Follows from `B1`, which measured on 37 hand-built strings that:

- the **bracket** complaint does not reproduce as a property of committed text — 0 of 8;
- **Hebrew mixed with Latin** diverges on **100%** of items, with or without digits;
- `ARM-SWAP` fixes nothing, breaks one, and **corrupts** the round-trip;
- `ARM-RLM` (a mark after *every* neutral) fixes 4 of 13 and breaks 3;
- `ARM-ISOLATE` (wrapping the **whole** committed run) fixes 0 and **breaks 22**.

## The mechanism `B1` exposed, and what it predicts

`ARM-ISOLATE` failed because **an isolate is opaque from the outside and takes its own
*placement* from the paragraph direction.** Wrapping the whole line converts a string whose
internal order was stable into a single unit whose position is not.

That immediately says what to try instead — wrap only the **embedded foreign run**, which is
what isolates were designed for — and it says where that will fail: a foreign run **at the edge
of the line** sits between a strong Hebrew character and *sot/eot*, and *sot/eot* is the
paragraph direction. `B1`'s own worked example is edge-placed: in `גרסה 5 של Android`, *Android*
ends the line.

So the interesting question is not "does wrapping the foreign run work" but **"does it work
everywhere except the edges, and can the edges be pinned separately"**.

## The arms

| arm | what it does |
|---|---|
| **ARM-NONE** | what ships today |
| **ARM-FSI** | each maximal Latin/digit run wrapped in U+2068 FSI … U+2069 PDI |
| **ARM-LRI** | the same with U+2066 LRI, which forces LTR instead of inferring it |
| **ARM-RLM-AFTER** | U+200F after each foreign run only — not after every neutral, which is what `B1`'s `ARM-RLM` did |
| **ARM-RLM-AROUND** | U+200F before **and** after each foreign run |
| **ARM-EDGE** | U+200F at the very start and end of a line containing Hebrew, pinning *sot* and *eot* and nothing else |
| **ARM-FSI-EDGE** | ARM-FSI and ARM-EDGE together — the only arm that addresses both halves of the mechanism |

## The corpus — real lines, not hand-built

`B1` hand-built 37 strings. This draws from `he_typed_raw.txt.gz`, the slice a person typed,
where `A1` measured 2.91% of lines carrying Latin and 5.71% carrying a bracket.

| family | rule |
|---|---|
| **MIXED** | lines containing at least one Latin letter or digit |
| **BRACKET** | lines containing a bracket |
| **CONTROL-HE** | lines whose only classes are Hebrew letters and whitespace |

Capped per family and the cap reported, never silently applied.

## Predictions, committed now

- **B2-P1** — **ARM-FSI reduces divergence and stays *below* the 90% bar.** Wrapping the foreign
  run cannot fix a foreign run that touches the line edge.
- **B2-P2** — the residual is **edge-placed**: at least **80%** of the MIXED items ARM-FSI fails
  to fix have their foreign run touching the start or the end of the line.
- **B2-P3** — every new arm passes the round-trip clause, because all of them only *add* format
  characters. `ARM-SWAP` remains the only arm here that changes a character.
- **B2-P4** — **ARM-FSI-EDGE clears the 90% bar**, because it is the only arm addressing both
  halves of the mechanism.

## The adoption rule — the same four clauses as `B1`

An arm is **ADOPTED** only if all four hold:

1. divergence drops to 0 for **≥ 90%** of the items ARM-NONE diverges on;
2. **zero** items convergent under ARM-NONE become divergent;
3. **round-trip** — removing every `Cf` character reproduces ARM-NONE's logical string exactly;
4. the controls below still hold.

If no arm satisfies all four: **NOTHING IS ADOPTED**, and the 100% divergence on mixed-script
Hebrew becomes a documented limitation that no keyboard-side fix reaches — which is a finding,
and a candidate for the public artifact rather than a defect to hide.

## Controls

**PC-1 — CONTROL-HE must be 0% divergent under ARM-NONE**, on real lines rather than on the
three hand-built strings `B1` used. If Hebrew-only text drawn from a typed corpus diverges, the
instrument is measuring something other than script mixing.

**PC-2 — the planted defect.** Skipping rule L4 must change the measured result. Carried over
from `B1`, where it went red on 8 of 8.

**PC-3 — an arm must not be inert.** Any arm whose divergence column is identical to ARM-NONE's
on every family is reported as **NOT-A-GATE for its own hypothesis**: it did not fail, it never
fired. `W7` produced exactly that outcome — `+0.00` on every cell while firing on 66% of
positions — and it is worth catching by rule rather than by reading.

## What this cannot say

Nothing about rendering. `java.text.Bidi` is the algorithm, not Android's `TextView`;
`M13-BIDI-RENDER` stays **NOT MEASURED**. Nothing about Gboard or SwiftKey:
`M12-GBOARD-CODEPOINT` stays **NOT MEASURED** and still needs a device.

And nothing about **cost**. An arm that passes here inserts invisible characters into the user's
text on every keystroke near a foreign run; whether an `InputConnection` can maintain that
without visible latency is `M7-LAT`, which has never run.

---

## Result

`./gradlew :core:test --tests '*BidiArmsTest*' -PrunBidiArms=1`.
Corpus: **3,257 real typed lines** — MIXED 794, BRACKET 463, CONTROL-HE 2,000.

### The controls

- **PC-1 — PASS.** 0 of 2,000 Hebrew-only typed lines diverge under ARM-NONE.
- **PC-2 — RED.** Skipping rule L4 changes 161 of 794 MIXED lines.
- **PC-3 — fired on its first outing.** `ARM-RLM-AFTER`'s column is **identical to ARM-NONE's**
  on every family: it is **INERT**. It did not fail, it never fired. That control exists because
  `W7` produced the same shape and it had to be read by eye.

### Divergence by arm

| arm | MIXED (794) | BRACKET (463) | CONTROL-HE (2,000) | ALL |
|---|---|---|---|---|
| ARM-NONE | **597 (75%)** | **355 (77%)** | 0 (0%) | 952 (29%) |
| ARM-FSI | 616 (78%) | 355 (77%) | 0 | 971 (30%) |
| ARM-LRI | 616 (78%) | 355 (77%) | 0 | 971 (30%) |
| ARM-RLM-AFTER | 597 (75%) | 355 (77%) | 0 | 952 (29%) *(inert)* |
| ARM-RLM-AROUND | 594 (75%) | 355 (77%) | 0 | 949 (29%) |
| **ARM-EDGE** | 311 (39%) | **0 (0%)** | 0 | 311 (10%) |
| **ARM-FSI-EDGE** | **0 (0%)** | **0 (0%)** | 0 | **0 (0%)** |

### Against the rule

| arm | fixed | broke | round-trip | ADOPTED? |
|---|---|---|---|---|
| ARM-FSI | 16/952 (2%) | **35** | clean | no |
| ARM-LRI | 16/952 (2%) | **35** | clean | no |
| ARM-RLM-AFTER | 0/952 (0%) | 0 | clean | no — **inert** |
| ARM-RLM-AROUND | 3/952 (0%) | 0 | clean | no |
| ARM-EDGE | 641/952 (67%) | **0** | clean | no — below the 90% bar |
| **ARM-FSI-EDGE** | **952/952 (100%)** | **0** | clean | **YES** |

**By the rule committed before the run, `ARM-FSI-EDGE` is ADOPTED.**

| prediction | outcome |
|---|---|
| **B2-P1** ARM-FSI below the bar | **HELD** — 2%, and it *breaks* 35 |
| **B2-P2** ≥80% of ARM-FSI's failures edge-placed | **FALSIFIED** — 49% |
| **B2-P3** every new arm round-trips | **HELD** |
| **B2-P4** ARM-FSI-EDGE clears 90% | **HELD** — at 100% |

---

## The correction that matters most: `B1` was wrong about brackets

`B1` concluded, on 8 hand-built strings, that **the bracket complaint does not reproduce as a
property of committed text — 0 of 8.** On **463 bracket-bearing lines a person actually typed,
it reproduces at 77%.**

`B1`'s strings were balanced, fully enclosing, and free of anything else — `(שלום)`,
`אמרתי (בקול) שלום`. Real typed Hebrew puts brackets at line edges, unbalanced, beside
punctuation runs and digits. **`B1` committed the exact defect it had diagnosed in the evaluation
corpora one file earlier**: it measured on material that could not exhibit the thing it was
looking for, and reported the absence as a finding.

The verified market complaint — parentheses coming out reversed, quoted against both Gboard and
SwiftKey — **is reproducible after all**, and `ARM-EDGE` alone removes 100% of it.

## Post hoc, and it is not a rescue: consistency is not correctness

Not pre-registered. Divergence measures whether the two renderings **agree**, not whether either
is **right** — two identical-but-wrong renderings converge. The reference for "right" is
ARM-NONE under an RTL paragraph: what a Hebrew-locale app shows today, where the complaint does
not arise.

| arm | preserves the Hebrew-locale rendering | changes it |
|---|---|---|
| ARM-FSI / LRI / RLM-AFTER / RLM-AROUND | 2,918 of 3,257 (89.6%) | 339 |
| **ARM-EDGE** | **3,257 of 3,257 (100.0%)** | **0** |
| **ARM-FSI-EDGE** | 2,918 of 3,257 (89.6%) | **339** |

**The arm that clears the bar changes what 339 lines look like for users who had no problem.**
The arm that costs nobody anything fixes 67% and misses the bar.

**This does not un-adopt `ARM-FSI-EDGE`, and it must not.** The rule was registered before the
run; renegotiating it after seeing the data is precisely what `P7` refused to do and what
pre-registration exists to prevent. What it establishes is that **the rule was incomplete**: a
fifth clause — *preserves the rendering users already get where nothing was wrong* — belonged in
it and was not there. That clause is registered **now, for the next run**, and is not applied
backwards.

**Adopted here means cleared its bar in the harness.** Nothing ships on this. `W7` was built in
the harness for the same reason, and shipping `ARM-FSI-EDGE` is a decision with a trade the rule
did not price — which makes it the operator's, with a date and a reason, the way `P7` was.

## SHIPPED — `ARM-EDGE`, on the operator's decision, 2026-08-25

**`ARM-EDGE` ships. It is not the arm the rule adopted.**

The rule adopted `ARM-FSI-EDGE` at 100%. The operator chose `ARM-EDGE` at 67% because the
post-hoc check priced what the rule had not: `ARM-FSI-EDGE` changes the rendering for **339 of
3,257** lines belonging to users who had no problem, and `ARM-EDGE` changes it for **none**
while still removing **100% of the bracket divergence** — the complaint verified against both
Gboard and SwiftKey.

That is a decision made against a rule's verdict, so it is recorded as one: **`WAIVER-3` in
[`docs/DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md)**, with a date, an owner, a reason and its
compensating controls. `P7` exists for exactly this and stays MET because the override is
written down.

### Both marks, because each alone is worse than nothing

Measured before implementing, because the two halves cost very different things to build:

| arm | fixed | broke | preserves the Hebrew-locale rendering |
|---|---|---|---|
| `ARM-EDGE-LEAD` — leading mark only | 3/952 (0%) | **115** | 100% |
| `ARM-EDGE-TRAIL` — trailing mark only | 275/952 (29%) | **115** | 100% |
| **`ARM-EDGE` — both** | **641/952 (67%)** | **0** | **100%** |

**Neither half is safe alone.** The cheap implementation — one mark, committed once and never
maintained — was measured and is not available.

### How the pair is placed at zero steady-state cost

The obvious reading of "a mark at each end" is a treadmill: delete and re-commit the trailing
mark on every keypress, an extra IPC per press on a path this project keeps deliberately free of
round-trips. Instead **both marks are placed once**, on the first Hebrew character committed into
a field the IME knows to be empty, with the cursor left **between** them. Every later character
lands inside the pair.

`core/src/main/kotlin/com/hebrewime/core/text/BidiPin.kt`. Guards, each with a test:

- the preceding context must be **known** and **empty** — `null` means the IME cannot see the
  field, and unknown means no, never "probably empty";
- never twice in one session;
- never for a field the user has not typed Hebrew into, so an untouched field stays genuinely
  empty rather than holding two invisible characters that would make a *required field* check
  pass on nothing;
- `U+200F` is not a word character, so the leading mark is a word boundary and `currentWord` is
  the Hebrew the user typed. **Every first-word lookup in every field depends on that**, and
  `BidiPinTest` pins it.

A **presence** is protected by a test; an **absence** needed `GATE-WITHDRAWN-1`. Removing this
fails `BidiPinTest`, which is why it gets no gate of its own.

## What is still not measured

**Cost.** Both surviving arms insert invisible characters into the user's text on every keystroke
near a foreign run or a line edge. An `InputConnection` would have to delete and re-commit them
continuously. **`M7-LAT` has never run**, and no number here says whether that is affordable.

`M13-BIDI-RENDER` — `java.text.Bidi` is the algorithm, not Android's `TextView`.
`M12-GBOARD-CODEPOINT` — still needs a device.

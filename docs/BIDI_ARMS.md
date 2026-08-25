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

*Not run yet. This section is filled in by the commit that runs the harness, and by no other.*

# B1 — does the bracket complaint reproduce, and is it ours?

**Written before the harness exists.** Predictions, controls and the adoption rule below were
committed in the commit that created this file; the harness arrived in the next one. If a
number here disagrees with a number the harness later printed, the harness is right and this
file is the record of what was expected.

`docs/MARKET_EVIDENCE.md` records the one Hebrew typing complaint that is verified, verbatim,
against **both** Gboard and SwiftKey: parentheses come out reversed. It also records that our
two evaluation corpora keep only `[א-ת]+` runs, so every character the complaint is about was
deleted before any measurement in this repository ever ran. `docs/milestones/M3.md` flagged the
same hole four milestones ago and nothing was done about it.

This is the first measurement in the project that looks at a character the keyboard commits
which is not a Hebrew letter.

---

## What is under test, stated so it cannot drift

**The logical string the keyboard commits.** Not rendering — we do not own the text view. Not
Gboard — we cannot run it here.

A keyboard has no control over the **paragraph direction** of the field it types into. That
direction follows the *app's* layout direction, which follows the *app's* locale, not the
user's language. A Hebrew speaker typing into an English-locale app gets an LTR paragraph; the
same keypresses in a Hebrew-locale app get an RTL one.

So the measurable question is:

> **Does the same sequence of keypresses produce two different-looking texts depending on which
> app it is typed into?**

Call that **divergence**. Divergence is exactly the complaint, expressed as a property of the
committed code points rather than of anyone's renderer.

### The oracle

`java.text.Bidi` — the JDK's UAX #9 implementation, ICU-derived, the same algorithm family
Android renders with. For a logical string `S` and paragraph direction `d`:

1. resolve levels with `Bidi(S, d)`,
2. reorder to visual order with `Bidi.reorderVisually`,
3. apply rule **L4** — mirror a character iff its resolved level is odd and its
   `Bidi_Mirrored` property is Yes,

giving a visual glyph string `V(S, d)`. **Divergence** is `V(S, LTR) ≠ V(S, RTL)`.

### The four arms

| arm | what it does |
|---|---|
| **ARM-NONE** | what ships today: the key labelled `(` commits U+0028 |
| **ARM-SWAP** | in the Hebrew layout, `(` commits U+0029 and `)` commits U+0028 — the "make it look right" fix people ask for |
| **ARM-RLM** | U+200F RIGHT-TO-LEFT MARK after each committed neutral run |
| **ARM-ISOLATE** | wrap the committed run in U+2067 RLI … U+2069 PDI |

---

## The corpus, specified before it is built

Seven categories. Every string must be **producible from `Layouts`** — if a character is not on
a shipped key, it is out of scope by construction, not a finding.

1. Hebrew + parentheses — the primary complaint
2. Hebrew + a Latin word
3. Hebrew + digits
4. Hebrew + Latin + digits — the second verified complaint (*"משבשת את סדר והרצף של מה שאני כותב"*)
5. Hebrew + geresh / gershayim / ASCII quotes
6. Hebrew + `. , ? ! : ; - / @ ₪ &` — every remaining neutral the numeric layout can emit
7. **controls:** pure Hebrew letters, pure Latin letters

---

## Positive controls

The repository's rule is that a gate which has never failed has not been shown to be a gate.
Three controls, and the first is the one that matters:

**PC-1 — the instrument must be blind where the corpora are blind.** Run the harness over the
two shipped evaluation corpora, `he_conversational_test` and the wiki slice. Both are
Hebrew-letters-only by construction. **Measured divergence must be exactly 0.** If it is not,
the harness is reporting noise and every number below it is void. This control doubles as the
quantitative restatement of the hole `FRICTION_INVENTORY` found: a corpus that cannot diverge
cannot ever have shown us this.

**PC-2 — the planted defect.** A mutant that skips the L4 mirroring step must change the
measured result on the bracket category. If skipping mirroring changes nothing, the harness is
not measuring mirroring, and the suite is `NOT-A-GATE` regardless of what else it prints.

**PC-3 — reachability.** Every test string must be emittable by the shipped `Layouts`. A string
that is not is a harness defect and is reported as one, not counted.

---

## Predictions, committed now

- **P1** — ARM-NONE diverges on **≥ 80%** of the bracket category.
- **P2** — ARM-NONE diverges on **≥ 1** string in the Hebrew+Latin+digits category.
- **P3** — ARM-SWAP **fails the round-trip clause**: stripping format characters from its output
  does *not* reproduce the intended logical string, because it changed a character rather than
  adding a control. ARM-RLM and ARM-ISOLATE pass round-trip.
- **P4** — ARM-ISOLATE drives divergence to 0 on **≥ 90%** of the divergent items and introduces
  divergence on **none** of the convergent ones.

## The adoption rule, committed now

An arm is **ADOPTED** only if **all four** hold:

1. divergence drops to 0 for **≥ 90%** of the items ARM-NONE diverges on;
2. **zero** items that were convergent under ARM-NONE become divergent;
3. **round-trip** — removing every `Cf` (format) character from the arm's output reproduces
   ARM-NONE's logical string **exactly**, for every item. This is the clause that decides whether
   a fix is a fix or a corruption: text that looks right in the field but comes out wrong when
   copied has not been fixed, it has been hidden;
4. PC-1 still holds.

If no arm satisfies all four: **NOTHING IS ADOPTED.** The divergence is then recorded as a
measured property of the shipped keyboard with no fix adopted, which is a result.

## The NOT-A-GATE condition

If ARM-NONE's divergence is **0 across the whole corpus**, then the committed code points are
direction-safe, the complaint belongs to specific renderers rather than to keyboards, and this
entire suite is **`NOT-A-GATE`**. That outcome is written here in advance so it cannot later be
reported as something else.

---

## What this cannot establish, stated before it is run

**It cannot measure Gboard or SwiftKey.** There is no device here and no way to type into them.

If divergence turns out to be a property of the committed code points, then *any* keyboard
emitting U+0028 into a Hebrew field inherits it — Gboard and SwiftKey included. That is an
argument by construction, **not a measurement**, and it depends on a premise this repository has
not checked: that Gboard and SwiftKey commit U+0028 rather than U+0029 from their Hebrew symbol
layouts. **`M12-GBOARD-CODEPOINT` — NOT MEASURED.** It needs a device, and it is the single
check that would convert the argument into evidence.

And if divergence is real, it is *not* a defect in the sense of a bug in someone's code — UAX #9
is doing exactly what it specifies. It is an opportunity: the keyboard is the only component in
the stack that knows the user is typing Hebrew, and it is the only one positioned to pin the
direction of what it commits. No keyboard measured in `MARKET_EVIDENCE.md` does this.

---

## Result

*Not run yet. This section is filled in by the commit that runs the harness, and by no other.*

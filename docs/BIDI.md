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

Run with `./gradlew :core:test --tests '*BidiDivergenceProbe*' -PrunBidiProbe=1`.
Harness: `core/src/test/kotlin/com/hebrewime/core/text/BidiDivergenceProbe.kt`.

### The controls first, because without them nothing below counts

| control | outcome |
|---|---|
| **PC-1** — divergence on the two Hebrew-letters-only eval corpora | **PASS — exactly 0 on 12,000 lines** (6,000 conversational + 6,000 wiki) |
| **PC-2** — planted defect, rule L4 skipped | **RED — 8 of 8 bracket items change.** The harness measures mirroring |
| **PC-3** — reachability | **caught a defect in itself** (below) |

**PC-1 is the number that quantifies the hole.** Twelve thousand lines of the corpora every
accuracy claim in this repository rests on, and **not one of them can diverge**. They were never
capable of showing this. That is the same hole `FRICTION_INVENTORY` reported as 0.00%
script-mixing, now stated as a count of lines rather than a rate.

**PC-3 caught the harness, not the corpus.** Its first version built the set of emittable
characters by re-reading `Key.output`, and reported `A G P I O` unreachable. They are reachable:
`KeyPressPlanner.plan` uppercases when shift is latched. The control was checking a *copy* of the
rule instead of the rule — the exact defect it exists to catch. It now runs every key of every
layout through the shipped `KeyPressPlanner` in both shift states.

### Divergence by arm — `V(LTR) ≠ V(RTL)`, the same keypresses in two apps

| category | ARM-NONE | ARM-SWAP | ARM-RLM | ARM-ISOLATE | n |
|---|---|---|---|---|---|
| 1 brackets | **0 (0%)** | 1 (13%) | 3 (38%) | 8 (100%) | 8 |
| 2 he+latin | **4 (100%)** | 4 (100%) | 4 (100%) | 4 (100%) | 4 |
| 3 he+digits | 0 (0%) | 0 (0%) | 0 (0%) | 4 (100%) | 4 |
| 4 he+latin+digits | **4 (100%)** | 4 (100%) | 4 (100%) | 4 (100%) | 4 |
| 5 he+marks | 2 (50%) | 2 (50%) | 0 (0%) | 4 (100%) | 4 |
| 6 he+neutrals | 3 (38%) | 3 (38%) | 1 (13%) | 8 (100%) | 8 |
| 7 control he-only | 0 (0%) | 0 (0%) | 0 (0%) | 3 (100%) | 3 |
| 7 control latin-only | 0 (0%) | 0 (0%) | 0 (0%) | 0 (0%) | 2 |
| **ALL** | **13 (35%)** | 14 (38%) | 12 (32%) | 35 (95%) | 37 |

### Against the four predictions

| | prediction | outcome |
|---|---|---|
| **P1** | ARM-NONE diverges on **≥ 80%** of the bracket category | **FALSIFIED. 0%.** Not one bracket string diverges |
| **P2** | ARM-NONE diverges on **≥ 1** Hebrew+Latin+digits string | **CONFIRMED — 4 of 4, 100%** |
| **P3** | ARM-SWAP fails round-trip; ARM-RLM and ARM-ISOLATE pass | **CONFIRMED** |
| **P4** | ARM-ISOLATE fixes ≥ 90% and breaks none | **FALSIFIED, and backwards.** Fixed 0, broke 22 |

### Against the adoption rule

    ARM-NONE diverges on 13 of 37 items; 24 converge.

| arm | fixed | broke | round-trip | PC-1 | ADOPTED? |
|---|---|---|---|---|---|
| ARM-SWAP | 0/13 (0%) | 1 | **CORRUPTS** | ok | no |
| ARM-RLM | 4/13 (31%) | 3 | clean | ok | no |
| ARM-ISOLATE | 0/13 (0%) | **22** | clean | ok | no |

**VERDICT: no arm meets all four clauses — NOTHING IS ADOPTED.**

---

## What was actually learned, including where the pre-registration was wrong

**1. The bracket complaint does not reproduce as a property of the committed text.** Zero of
eight. `(שלום)` renders as `(םולש)` under an LTR paragraph and under an RTL paragraph alike. Two
of the four predictions were wrong and this is the one that matters: the thing the market
research found verified against both Gboard and SwiftKey is **not** something a keyboard's
output can be blamed for.

**2. What reproduces at 100% is the *other* complaint.** Hebrew mixed with Latin — with or
without digits — diverges on every single item. `גרסה 5 של Android` puts "Android" at opposite
ends of the line depending on the app's locale:

    logical        גרסה 5 של Android
    LTR paragraph  לש 5 הסרג Android
    RTL paragraph  Android לש 5 הסרג

That is the SwiftKey reviewer's *"משבשת את סדר והרצף של מה שאני כותב"*, reproduced from first
principles, and it is the complaint we were **not** aiming at.

**3. The fix everyone asks for is the worst of the three.** ARM-SWAP — make the `(` key emit
`)` so it "looks right" — fixed **nothing**, broke one item, and is the only arm that fails
round-trip: it changes a character rather than adding a control, so the text copied out of the
field is not the text the user meant. It hides the symptom by corrupting the data.

**4. Isolates are actively harmful here, which I had backwards.** ARM-ISOLATE was predicted to
fix ≥ 90%. It fixed 0 and broke 22 — including all three pure-Hebrew control items, which were
safe before. An isolate is opaque from the outside and takes its *placement* from the paragraph
direction, so wrapping the content converts a safe string into a direction-dependent one. The
pre-registration reasoned about what happens inside an isolate and never asked what happens
around it.

**5. ARM-RLM is the only arm that helps at all** — 31% of the divergent set, clean round-trip —
and it is nowhere near the 90% bar, and it breaks 3. Below the bar is below the bar.

---

## Post hoc, and labelled as such

Not pre-registered. A hypothesis, not a result; it is written down because P1's falsification
demands an explanation and this is the one the data supports.

The key labelled `(` commits U+0028. Rule L4 mirrors it inside an RTL run, so **a `)`-shaped
glyph appears the moment the key is pressed.** The text is correct. The key label contradicts
what the screen shows. If the user "fixes" that by pressing `)` instead — which is exactly what
a reasonable person does — the logical string inverts:

    intended     (שלום)   →   (םולש)
    "corrected"  )שלום(   →   )םולש(

**8 of 8 bracket items change meaning if the user follows the key label.** So the complaint is
real, the users are not imagining it, and it is **an affordance problem in the key labels rather
than a text problem in the output** — which is a different fix, in a different place, at a
different cost, from anything the four arms tried.

The instrument dating: the JDK recognises the isolate directionality class, so its Unicode bidi
tables are 6.3 or later and N0/BD16 (bracket pairs) is present. That dates the tables; it does
not by itself explain the bracket result. A first attempt to discriminate N0 by comparing a
matched `(HE)` against an unmatched `(HE` is **kept in the harness as a note rather than
deleted: it does not discriminate.** Worked by hand, N0-present and N0-absent produce the same
visual string for that input. A control that cannot come out two ways was never a control.

---

## What this still cannot say

`M12-GBOARD-CODEPOINT` remains **NOT MEASURED**, and after this run it matters more, not less.
The finding is now that the *labels* are the trap. Whether Gboard and SwiftKey label and commit
the same way is a device question, and no line of this run touched a device.

`M13-BIDI-RENDER` is added and is **NOT MEASURED**: `java.text.Bidi` is the algorithm, not
Android's `TextView`. Everything above is a claim about conformant rendering. A field that
renders non-conformantly is outside what this measured.

# W4 — a key's label must be the glyph it produces, or the key must say why not

`docs/PROGRAM.md` W4. Follows from `B1`'s post-hoc finding and `B2`'s correction of it.

## The defect

The key labelled `(` commits U+0028. Rule L4 of UAX #9 mirrors a bracket inside a right-to-left
run, so in Hebrew **a `)`-shaped glyph appears at the caret**. The text is correct. The label
contradicts the screen.

`B1` measured the cost: **8 of 8 bracket items change meaning** when a user "corrects" it by
pressing `)` instead — which is what a reasonable person does. `B2` then measured that the
complaint is real on typed Hebrew at **77% of bracket-bearing lines**, against `B1`'s *0 of 8*
on hand-built strings.

Nothing in the codebase noticed the mismatch, and nothing would have noticed the next one.

## The scope, measured before deciding anything

Every character the three shipped layouts can emit, rendered between Hebrew letters:

**Exactly two mirror: `(` and `)`.** Every other key — `- / : ; ₪ & @ " . , ? ! '` and all ten
digits — renders as its own label. The problem is two keys wide.

## The rule, and why it is not "match"

**Either the label is the glyph, or the key carries a written reason.** That is the bargain
`GATE-CORPUS-2` strikes, and for the same reason: a machine can find a mismatch; only a person
can decide it may stand.

Forcing a match would mean relabelling `(` to `)`. **That was considered and rejected**, because
`KeyboardModel.kt` already records this project shipping exactly that class of mistake:

> An earlier version of this class had an `rtl` flag that `KeyGeometry` used to mirror each row,
> on the reasoning that Hebrew reads right-to-left so a Hebrew keyboard must too. **That is
> wrong, it shipped, and a user opened the keyboard and said it looked like a mirror.**

Every Hebrew typist carries SI-1452 in muscle memory from a physical keyboard, where this key
has been labelled `(` for decades. Swapping the labels to match the glyph would break that for a
benefit **nobody has measured**.

**`L2-LABEL` — whether matching a label to its glyph helps a user — is NOT MEASURED and needs a
user.** Until there is one, the mismatch stands, documented, rather than being fixed on a hunch.

## What was built

- `Key.labelDiffersBecause` — null on every key but two.
- `Layouts.BRACKET_LABEL_REASON` — the reason those two carry, naming L4, `B1`'s 8-of-8, and the
  mirrored-layout precedent that decided against relabelling.
- The numeric layout's punctuation row is now built explicitly rather than through the `row()`
  helper, because a helper that stamps out identical keys cannot express "these two are
  different and here is why".
- `KeyLabelGlyphTest`.

**The requirement is enforced by the test, deliberately not by `Key.init`.** A constructor that
refused an undocumented mismatch would make the check unable to fail — and a check that cannot
fail is not a check.

## Both directions demonstrated red

| control | result |
|---|---|
| the reason removed from a mismatching key | **FAILED** |
| a reason added to a key whose label and glyph agree | **FAILED** |

The second matters as much as the first: a reason recorded for a mismatch that does not exist is
a false statement about what a key does, and it would accumulate silently.

The mismatch **count** is pinned at 2. A new mirrored character entering a layout fails the test
rather than being quietly absorbed into the documented set.

## Why a test and not a gate

The oracle is `java.text.Bidi`, which lives in the JVM. `run_gates.py` is Python and has no bidi
implementation, so a gate would have to re-implement UAX #9 to check a two-key rule.
`scripts/assert_tests_ran.py` covers the *did it run* half.

This is the same distinction `B2`'s ship recorded: a **presence** is protected by a test, an
**absence** needed `GATE-WITHDRAWN-1`.

## What this does not do

It does not fix the trap. A user who presses `)` because the screen showed one still inverts
their text, and this repository has **no measurement of how often that happens** — `B1`'s 8 of 8
is what the inversion costs when it occurs, not how often anyone does it.

`L2-LABEL` and `M13-BIDI-RENDER` are both **NOT MEASURED**. Both need a device or a user.

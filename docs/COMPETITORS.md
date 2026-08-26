# W6 — the competitor check, and what it found in the incumbent

`docs/PROGRAM.md` W6. Budgeted at twenty minutes to answer one question: **is *offline and
actually good at Hebrew* an open position, or an occupied one?**

It is open. And answering it surfaced something larger.

---

## FUTO Keyboard — the closest positional competitor

$11.99 on Play, honorware, fully offline, 100K+ installs. The only paid offline keyboard
`MARKET_EVIDENCE` found.

**It has no Hebrew dictionary.** Hebrew is not listed among the dictionaries FUTO publishes
(<https://keyboard.futo.tech/dictionaries>), which the page confirms by pointing users at
third-party community dictionaries instead. No dictionary means no Hebrew correction and no
Hebrew prediction.

Its release notes mention Hebrew exactly once, in **v0.1.27, 25 December 2024**:

> *"Fixed some behavior when it comes to typing parentheses in Hebrew"*

**So the position is open**: offline *and* good at Hebrew is not occupied. What that one release
note points at, however, is not a FUTO decision at all.

---

## What AOSP actually ships for Hebrew brackets

Two facts, both verified from source rather than inferred:

**1. The key specification format is `keyLabel|keyOutputText`.** From `KeySpecParser`'s own
class javadoc: *"Each key specification is one of the following: - Label optionally followed by
keyOutputText (keyLabel|keyOutputText)."* The part **before** the pipe is drawn on the key; the
part **after** it is committed.

**2. AOSP LatinIME's Hebrew resources define:**

    keyspec_left_parenthesis  = "(|)"
    keyspec_right_parenthesis = ")|("

in `tools/make-keyboard-text/res/values-iw/donottranslate-more-keys.xml`. FUTO's fork carries
the same values in `locales/he.json` and `locales/iw.json`.

**Read together: the key labelled `(` commits U+0029.** That is not a rendering trick and not a
label change — **it changes the character in the user's text.**

`B1` measured that transformation as `ARM-SWAP` and found it the only arm to fail the
round-trip clause. This is the first time this repository has measured it against **real typed
Hebrew**, on the 3,257-line corpus `B2` built.

## The result

| arm | bracket lines diverging | fixed | broke | round-trip | changes the Hebrew-locale rendering |
|---|---|---|---|---|---|
| **ARM-NONE** — commit what the label says | **355 of 463 (77%)** | — | — | — | — |
| **ARM-SWAP — what AOSP ships** | **399 of 463 (86%)** | **0 of 952** | **60** | **CORRUPTS** | **599 of 3,257 (18.4%)** |
| `ARM-EDGE` — what this keyboard now ships | **0 of 463 (0%)** | 641 of 952 | **0** | clean | **0** |

**Every measured axis moves the wrong way.** Bracket divergence rises from 77% to 86%. Overall
divergence rises from 29% to 31%. It repairs nothing, breaks sixty lines that were stable, and
it is the only arm here that alters a character rather than adding a format control — so text
copied out of the field is not the text the user meant, and a search for it will not match.

It also disturbs the Hebrew-locale rendering more than any other arm measured: **18.4%** of
lines, against 10.4% for `ARM-FSI-EDGE` and **zero** for `ARM-EDGE`.

## What this explains

The market pass recorded this complaint verbatim against SwiftKey, at 2 stars:

> *"בעקבות עדכונים אחרונים זה הפך להיות בלתי אפשרי לשימוש בעברית, מכיוון שהסוגריים תמיד יוצאים
> הפוכים"*

**The hypothesis this measurement supports is that the complaint is caused by the fix, not by
its absence.** A keyboard that swaps the committed character makes bracketed Hebrew look right
inside a Hebrew-locale app and wrong everywhere else — which is what "always come out reversed"
describes.

**It is a hypothesis, not a measurement of anyone's product.** See the limits below.

---

## Limits, and they are real

**Gboard is not measured.** Gboard descends from LatinIME but is closed source and may differ.
`M12-GBOARD-CODEPOINT` was registered for exactly this question before any of this was known,
and it stays **NOT MEASURED**. It needs a device.

**The oracle is `java.text.Bidi`**, the algorithm — not Android's `TextView`.
`M13-BIDI-RENDER` stays **NOT MEASURED**.

**The corpus's own provenance limits one row.** These are real comments, typed on whatever
keyboards those people had — quite possibly swapping ones. So the *"changes the Hebrew-locale
rendering"* column, which compares against the corpus as written, is ambiguous in a way the
divergence column is not: **divergence measures a property of the transformation** and holds
whatever the text's origin, while *"changes the rendering"* assumes the corpus is the intended
text. The 18.4% should be read with that caveat; the 77% → 86% should not.

**And this is not a claim that the incumbents are careless.** The swap makes bracketed Hebrew
render correctly in the case that was overwhelmingly common when it was written — a Hebrew app,
Hebrew paragraph direction. What changed is that people now type Hebrew into apps whose locale
is English, and a fix that lives in the committed character cannot travel.

# The program — turning what the research found into changes in this repository

Three research passes (`docs/MARKET_EVIDENCE.md`), one pre-registered experiment on the one
verified complaint (`docs/BIDI.md`), and one measurement made for this document and reported
below. This file is what follows from them: what to change, in what order, with the check that
decides each item and the thing that would kill it.

It is a plan, not a result. Every item names the gate or pre-registration it needs, because a
plan whose items cannot fail is a wish list.

---

## A1 — the measurement made for this plan, because it reorders it

`FRICTION_INVENTORY` established that both evaluation corpora are Hebrew-letters-only by
construction. `BIDI` established that 12,000 lines of them produce **exactly zero** direction
divergence. Both say the corpora are blind. **Neither says how much is behind the blindness**,
and that number decides whether removing the filter is a repair or a rounding error.

Reproduce with `python3 scripts/measure_alphabet.py --typed-dir <dir> --subtitle-lines 3000000`.

**Share of lines containing at least one such character**, over the lines the repo's own filter
keeps (≥ 4 Hebrew tokens, none over 12 characters):

| class | **transcribed** — OpenSubtitles<br>n=3,000,000, kept 1,556,192 | **typed** — Ynet comments (MIT)<br>n=12,804, kept 10,746 | ratio |
|---|---|---|---|
| a Latin letter | 0.52% | 2.91% | **×6** |
| a digit | 3.22% | 6.20% | ×2 |
| Latin OR digit | 3.66% | 7.41% | ×2 |
| **geresh / gershayim** | **0.01%** | **3.57%** | **×604** |
| ASCII quote / apostrophe | 9.31% | 33.99% | ×4 |
| **a bracket** | **0.21%** | **5.71%** | **×27** |
| an emoji | 0.00% | 1.27% | ×458 |

Sources: OPUS OpenSubtitles v2018 Hebrew monolingual, streamed and never written to disk —
a **prefix sample, not random**. Amram et al. 2018, user comments on Ynet's Facebook page, MIT.
**Neither register is phone typing**; `M10-REGISTER` stays NOT MEASURED and this does not change
that. The claim is narrower and sufficient: **transcription and typing do not share an
alphabet.**

### What this changes about the plan

**Removing the `[א-ת]+` filter is not the fix.** On the corpus we have, it would recover 3.66%
of lines for script-mixing and 0.21% for brackets. The filter is not the problem; **the source
is**. A corpus transcribed by a professional does not contain the characters a person types,
and no amount of un-filtering puts them back.

**Three of this project's own features are evaluated against text that cannot contain their
subject.** The abbreviation handling built around geresh and gershayim — including the long-press
keys added specifically so `כ״כ` could be typed at all — is measured on a corpus where the
correct character appears in **1 line in 17,000**, against 1 in 28 of typed Hebrew. The bracket
category `BIDI` had to hand-build appears in 5.71% of typed comments. Emoji, which
`GraphemeSegmenter` and the backspace-width logic exist to handle, appear **never**.

**So the binding constraint is the register, and it is sharper than "wrong word choice."** It is
an alphabet. That is a measurable, fixable thing, and it is item W1.

---

## What the research established, compressed, with the corrections

**The market pass.** Zero mentions of ktiv male/haser in 2,700 Hebrew reviews of Gboard and
SwiftKey; four mentions of privacy. What users do report is bidi bracket behaviour, Hebrew mixed
with Latin and digits, weak Hebrew correction, and missing nikud in SwiftKey. No third-party
keyboard has earned standalone revenue in a small-language market; keyboard SDK licensing is a
graveyard including the Israeli one; the lexicon prices in the CELEX2 band with ShareAlike
removing exclusivity; compliance prescribes banning this product category. The only path with
precedent is a free verifiable artifact converted into teaching and assessment — with the caveat
that every precedent inherited institutional standing rather than manufacturing it.

**The experiment.** `B1` falsified two of its four predictions. The bracket complaint does **not**
reproduce as a property of the committed text — 0 of 8. Hebrew mixed with Latin diverges on
**100%** of items. All three candidate mitigations failed a rule committed beforehand, and the
popular one corrupts the text. Post hoc, the mechanism looks like the key labels.

**A correction I owe.** When I proposed this item I said it would manufacture standing by
demonstrating a reproducible defect that Google and Microsoft ship. **It did not find that.** The
bracket complaint is not a defect in anyone's code; UAX #9 is doing what it specifies. What `B1`
found is different and, on that axis, weaker. The stronger candidate for a public artifact is
now **A1 above**, which was not on the table when I made that claim.

---

## The program

Ordered by what unblocks what, not by ambition. Each item states what would kill it.

### W1 — a typed-Hebrew evaluation slice — **DONE.** See [`docs/TYPED_REGISTER.md`](TYPED_REGISTER.md). Corrected the headline register claim from 23.72% to **10.33%**

**Change.** `scripts/build_typed_corpus.py`, on the Amram et al. comments (MIT, already
identified in `CORPUS_REGISTER` as *the only candidate that is typed rather than transcribed* and
never fetched). Hash and register it. Hold out a slice and **prove disjointness rather than
assert it**, as `slice_eval_corpus.py` already does. **Do not filter to `[א-ת]+`** — that filter
is the reason this item exists.

Then re-run the headline measurements on it: prefix-1 completion top-3, next-word top-3, and the
real-word detector's false-alarm rate.

**What kills it.** If the numbers on typed text land within confidence intervals of the subtitle
numbers, register was already handled and this buys nothing beyond the alphabet.

**Registered risk, before any number comes out.** 12,804 comments is **small** — two orders of
magnitude under the existing slices. A held-out cut of it is smaller still. **Confidence
intervals, not point estimates**, or the result is not reportable. And Facebook comments are not
phone messaging: this narrows `M10-REGISTER`, it does not close it.

**Cost.** Hours. The corpus is 2.5 MB.

### W2 — `GATE-CORPUS-1/2` — **DONE.** Failed on 9 of 9 corpora before a declaration existed

**Change.** Every `build_*_corpus.py` declares in its `MANIFEST.json` which character classes it
keeps and which it drops. The gate checks the declaration against what the artifact actually
contains.

**Positive control.** Plant a builder that strips digits without declaring it — the gate must go
red. And it must go red **today** on the declaration-free builders, which is the point.

**Why.** The `[א-ת]+` decision was never registered as a decision, and no gate noticed for four
milestones. This is the gate that would have caught it.

**What kills it.** If a declaration cannot be checked against the artifact without re-running the
builder, this is documentation with a test around it and should be called that.

**Cost.** Small — a Python gate in the existing framework.

### W3 — `B2` — **DONE.** See [`docs/BIDI_ARMS.md`](BIDI_ARMS.md). `ARM-FSI-EDGE` cleared the bar at 100%; `B1`'s bracket conclusion was corrected from *0 of 8* to **77% of 463 real lines**

**Change.** `B1` tested wrapping the *whole* committed run in an isolate, and it broke 22 of 24
convergent items. The textbook arm — wrapping only the **embedded foreign run** in
`LRI…PDI` or `FSI…PDI`, which is what isolates were designed for — was never tried. Neither was
an `RLM` placed *after* the Latin run rather than after every neutral.

Pre-register before code, same shape as `B1`, with the corpus drawn from W1's typed slice rather
than hand-built — at 2.91% Latin and 5.71% brackets there is real material.

**What kills it.** The same four-clause rule. If no arm passes, the 100% divergence on
mixed-script Hebrew becomes a documented limitation — and a candidate for the artifact, since a
100%-reproducible failure that no keyboard-side fix addresses is a finding.

**Cost.** Days. Blocked on W1 only for the corpus; can be pre-registered now.

### W4 — **DONE**, and narrower than proposed. See [`docs/KEY_LABELS.md`](KEY_LABELS.md). The rule is *match **or say why***, not *match* — relabelling was rejected on this repository's own documented precedent

**Change.** For every character key, the glyph rendered from `output` in that layout's
`scriptDirection` must equal `label`. The `Key` data class **already separates the two fields**,
so this needs no new types — only a per-context numeric layout, since one `Layouts.numeric` is
currently shared between the Hebrew and English paths.

**Positive control.** Plant a key whose label disagrees with its rendered glyph. It should also
go red on the shipped numeric layout as it stands, which is the finding `B1` recorded post hoc:
**8 of 8 bracket items change meaning if the user follows the key label.**

**The limit, stated rather than hidden.** The gate enforces internal consistency, which is
checkable. Whether matching labels **helps a user** is `L2-LABEL`, **NOT MEASURED**, and needs a
user. It also makes this keyboard differ from every other keyboard and from the physical Israeli
layout, where `(` is labelled `(`. That is a real risk and it belongs in the pre-registration,
not in a footnote after shipping.

**Cost.** Small for the gate; the layout split is a contained change.

### W5 — **DONE.** See [`docs/FINDINGS.md`](FINDINGS.md) and `scripts/reproduce.sh`. The headline order below is **superseded** — `W6` displaced the alphabet finding as the strongest claim

**Change.** One reproducible entry point that regenerates every headline number from committed
inputs, plus `docs/FINDINGS.md` written for a reader who does not work here — the existing docs
are written for the operator.

Headline claims, in the order the evidence supports them:

1. **A1 — transcription and typing do not share an alphabet.** ×604 on geresh/gershayim, ×27 on
   brackets, ×458 on emoji. Any Hebrew benchmark built on transcription or encyclopedia text is
   blind to characters typists produce constantly. This is the newest claim and the one aimed
   squarely at an ecosystem that spent 180M NIS building Wikipedia-derived Hebrew resources.
2. **The register finding** — prefix-1 completion top-3 5.35% → 23.72%, ×4.43, with
   `M10-REGISTER` stated on its face.
3. **The precision floor** — 12.5%, 4.83 false alarms per true positive, against a human judge on
   465 blind-labelled screens.
4. **`B1`** — the popular bidi fix corrupts the text; mixed-script Hebrew diverges on 100%.

**What kills it.** If W1 shows the alphabet gap does not move any accuracy number, claim 1 is a
curiosity about corpora and not a finding about keyboards, and the artifact is claims 2–4 only.

**Cost.** Days, after W1.

### W6 — **DONE**, and it stopped being a checkbox. See [`docs/COMPETITORS.md`](COMPETITORS.md). The position is open, and **AOSP's own Hebrew fix makes every measured axis worse**

**FUTO Keyboard's Hebrew support** — 20 minutes, and it decides whether *offline and actually
good* is an open position or an occupied one. Still not run.

**`M12-GBOARD-CODEPOINT`** — whether Gboard and SwiftKey label and commit the way we assume.
Needs a device. It matters more after `B1`, not less, because the finding is now about labels.

---

## Deliberately not in the program

| not doing | because |
|---|---|
| more work on the parametric / morphological line | `O1`, `G1`, `G2`, `G3` all ended NOT ADOPTED, and the market pass found **zero** users articulating the property they optimised. Closed pending a friction it can be tied to — not closed forever, closed **by default** |
| removing the `[א-ת]+` filter as a fix in itself | A1: it recovers 3.66% and 0.21%. The source is the problem, not the filter |
| nikud | A real gap — SwiftKey lacks it and users ask. But **Gboard already ships it**, so it is table stakes rather than a differentiator, and it is a large build |
| any monetization build — paid app, SDK, lexicon sale, compliance | `MARKET_EVIDENCE` prices all four against evidence and records five reasons to stop |
| shipping ARM-SWAP because it "looks right" | It fixed nothing, broke one item, and is the only arm that fails round-trip. It hides the symptom by corrupting the copied text |

---

## What the program cannot fix

Everything above runs on a build host. **No item here touches a phone.** `M7-LAT`,
`L1-REALUSER`, `L2-LABEL`, `M12-GBOARD-CODEPOINT` and `M13-BIDI-RENDER` all need a device or a
user, and none of them becomes MET because the work around them got better.

`M10-REGISTER` is **narrowed** by W1 and not closed by it. Facebook comments are typed, which is
the axis that matters here, and they are still not phone messaging.

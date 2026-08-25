# Play store listing

Draft copy for the Play Console. Nothing here is published; it is written so the operator has
something concrete to edit rather than a blank form.

**Every factual claim below is one the repository can substantiate.** Where a claim would be
stronger than the evidence, it has been weakened rather than the evidence stretched.

---

## App name

**Hebrew Keyboard — Offline**

(30-character limit. "Hebrew Keyboard — Offline" is 25.)

## Short description (80 characters)

> A Hebrew keyboard with no internet permission. Your typing stays on your phone.

78 characters. It states a checkable fact rather than a promise: the app genuinely declares no
network permission, and three gates keep it that way.

## Full description

> **A Hebrew keyboard that cannot send your typing anywhere.**
>
> Most keyboards ask you to trust them. This one removes the need to.
>
> It has no internet permission. Not "we don't use it" — the permission is not in the app at
> all, so there is no code path, and no future update can quietly add one without it appearing
> in the permission list you can see.
>
> **What that means in practice**
>
> • Nothing you type is uploaded, analysed, or sold. There is no server to send it to.
> • No analytics. No crash reporting. No advertising. No account.
> • Works identically on a plane, in a tunnel, or with mobile data switched off.
>
> **Careful with sensitive fields**
>
> In password, email, phone, payment and one-time-code fields the keyboard switches itself off
> as a suggestion engine entirely: no reading the surrounding text, no suggestions, no
> autocorrect, no learning, no history. It types the characters and nothing else.
>
> Name and address fields are in between: you still get suggestions there, but nothing from them
> is ever remembered.
>
> **A real Hebrew dictionary**
>
> 355,587 Hebrew word forms, including the rare verb conjugations that most spellcheckers
> underline by mistake. Hebrew builds words with prefixes — *and-the-book* is one word — and
> the keyboard understands them instead of flagging them.
>
> Both the older and newer spellings are recognised, so *tochnit* and *tochnyt* are both fine.
>
> **It finishes your words, and catches the ones that look right**
>
> The keyboard suggests how a word ends as you type it, and what usually comes next when you
> finish one — all from a language model stored on your phone, with nothing looked up anywhere.
>
> It also catches a mistake a spellchecker cannot: *im* where *am* was meant. Both are real
> Hebrew words, so nothing about either is misspelled — only the sentence around them shows
> which one belongs. The keyboard reads that sentence and offers the other word. **It is wrong
> about this more often than it is right** — see *What to expect* below, which is there so that
> is not a surprise.
>
> **What to expect**
>
> • **Nothing is ever replaced automatically.** Every correction, completion and fix is a
> suggestion you tap. There is no autocorrect working behind you, and no setting that turns one
> on. If you have come from a keyboard that rewrites words as you type, this will feel quiet at
> first — that is the design, not a fault.
>
> • **It does not try to catch a correctly spelled word in the wrong place.** That check was
> built, measured against a Hebrew speaker's blind judgement on 320 real cases, and **removed**:
> it was right in at most 39.7% of them and interrupted roughly five times for every time it
> helped. A suggestion that wrong teaches you to stop reading suggestions, so it is not here.
>
> • **It knows written Hebrew better than it knows slang.** The dictionary is built from openly
> licensed word lists and Hebrew text. It is strongest on ordinary written Hebrew and weakest on
> slang, brand names and abbreviations it has never met — and you can add any of those yourself,
> in one tap, encrypted.
>
> **Your own words, encrypted**
>
> You can add words yourself — a name, a place, a word the dictionary has never heard of. The
> keyboard stops underlining them and starts completing them. They are encrypted on your device
> with a key held in the Android Keystore, and you can delete every one of them — and the key —
> in a single tap.
>
> Nothing is ever added automatically.
>
> **If you want it to learn — and only if you say so**
>
> There is a switch in settings, off until you turn it on, that lets the keyboard notice which
> words you tend to put next to each other and order its suggestions accordingly.
>
> What it counts stays on this phone, encrypted, and there is nowhere for it to go: this app has
> no internet permission.
>
> It never stores what you wrote — only how often one word followed another, as numbers. A word
> it does not recognise is recorded as "something unknown", never as letters, so a name or a
> code you type is not kept even in encrypted form. It never learns in password, payment, email,
> phone, name or address fields. And something you typed only once is never suggested back to
> you: a pair has to come back in a later message before the keyboard will offer it.
>
> One tap forgets all of it, along with the key that unlocks it.
>
> **Open about what it does**
>
> The dictionary is built from openly licensed sources, credited in the settings screen, using
> a build script published with the app.

## Category

Tools

## Tags

keyboard, Hebrew, privacy, offline, IME

## Content rating questionnaire

- User-generated content: **No** — nothing is shared with anyone.
- Interacts with users: **No**.
- Shares location: **No**.
- Digital purchases: **No**.
- Expected rating: **Everyone**.

## Assets still required from the operator

These cannot be produced here and are **NOT DONE**:

| Asset | Status |
|---|---|
| App icon, 512 × 512 PNG | **DONE** — `store/play_icon_512.png`, generated by `scripts/build_store_assets.py` from the app's own resources. |
| Feature graphic, 1024 × 500 PNG | **DONE** — `store/play_feature_graphic_1024x500.png`, same generator. |
| Phone screenshots | **NOT DONE.** 2–8, minimum 320 px on the short side. Needs a device. |
| Privacy policy URL | **Text DONE** — [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md). Play requires a hosted URL, and a contact address inside it; both are the operator's. |

### The icon was wrong, and generating it is how that was found

The launcher icon shipped for months as a **Latin capital N**. Its own comment in the vector
said "stylised alef". Nothing caught it because an icon is looked at once, at 48dp, by someone
who already knows what the app is — and the store PNG that would have forced a second look had
never been rendered.

A hand-drawn replacement came out as an **X**. So the letter is no longer drawn at all:
`scripts/build_launcher_icon.py` extracts the alef's outline from
`app/src/main/res/font/keyboard_label.ttf` — the typeface `build_keyboard_font.py` selected by
measuring 351 Hebrew letter pairs for confusability — and emits the vector drawable. The icon
is the app's own letterform by construction and cannot be a letter from another alphabet.

`GATE-STORE-1` re-renders both PNGs and compares bytes, so a palette change that leaves a store
asset behind fails the build rather than shipping.

## A note on the screenshots

They cannot be faked from mockups here, and should not be: Play screenshots must show the app
as it actually is. This repository can render the keyboard's exact geometry, colours and
typeface — everything but the fact that Android drew it — and a render that faithful passed off
as a capture would be a fabrication, which is worse than a gap.

The operator's device screenshots exist and are not usable either: they are of real
conversations with a named contact. Store screenshots need a device **and** a scratch app to
type into.

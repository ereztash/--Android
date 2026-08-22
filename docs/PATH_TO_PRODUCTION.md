# Path to production

`docs/RELEASE_READINESS.md` says **NOT READY** and says why. This says what would change that,
in order, with the person who has to do each step named.

It exists because "production ready" was being carried as a mood. It is a list.

**Nothing in this document changes the verdict.** The verdict changes when the boxes are
ticked, and it is ticked by evidence, not by finishing the list.

---

## Where it actually stands

| | |
|---|---|
| Gates, each with a positive control demonstrated red | **23** |
| JVM tests | **286**, 0 failures, 0 skipped |
| Android lint on the release build | 0 errors, 7 style warnings |
| Release APK + AAB | build, survive R8, carry no network permission |
| Signing pipeline | **proven end to end** with a throwaway key, then destroyed |
| Store icon + feature graphic | generated from the app's own resources, gated |
| Privacy policy text | written |
| Checks that need a physical device | **22** |

Of those 22, **9 the phone can now measure itself** and **13 need a human to look**.

---

## 1. The only thing with a clock on it — OPERATOR, ~10 minutes

**File the Play targetSdk extension. Deadline 2026-08-31.**

Google Play requires targetSdk 36 from that date for new apps and updates. This app already
targets 36, so it is not a code problem — but the extension to **2026-11-01** is free, is filed
under **Policy status** in Play Console, and converts a hard deadline into a soft one. It costs
nothing and cannot be filed retroactively.

It needs a Play Console developer account, which is the same prerequisite as step 4.

See `docs/OPERATOR_NOTICES.md` NOTICE 1.

---

## 2. One device session — OPERATOR, ~25 minutes

This is the highest-value time available to the project and has been for four milestones.

Install the debug APK, enable the keyboard, then:

**a. Run the self-check.** Keyboard settings → **Device self-check** → Run → Copy the report.
That alone measures nine of the twenty-two: gesture insets, network permission, Keystore
security level, contrast on the colours your phone resolved, **p95 keystroke latency**, the
typeface, label fit, dictionary reload, and the micro-interaction counters.

Some checks report NOT MEASURED until you have given them something to measure. To make them
measurable first:

| Do this | Makes measurable |
|---|---|
| Type one character into any password field | `M4-DEVICE` |
| Add a word in the settings screen | `M6-KEYSTORE` |
| Turn learning on and type a few sentences | `L1-KEYSTORE`, `L2-BENEFIT` |
| Type ~30 words normally | `M7-LAT` (a p95 needs 20+ samples) |
| Hold backspace; long-press the quote key | `MI-COUNTERS` |
| Add a word in Settings, then type in another app | `M12-RELOAD` |

**b. Then look at the thirteen a report cannot answer.** These need eyes:

- Does the bottom key row mis-tap near the gesture bar? (`M2-INSETS` measures clearance; only
  you can say whether it *feels* wrong)
- Does the system spell-checker's red underline still appear? (`M2-SPELLCHECK`)
- Does the key-preview bubble flip below on the top row, and is it readable? (`MI-PREVIEW`)
- Is accelerating backspace usable, and does a tap ever trigger it? (`MI-REPEAT`)
- Does the gershayim long-press **replace** rather than append? (`MI-LONGPRESS`)
- Is the away-from-cursor confirmation noticeable? (`MI-CONFIRM`)
- Does the dictionary screen display correctly? (`M6-UI`)
- Do the learning switch, count and "forget" behave? (`L1-SWITCH`)
- Is the keyboard usable with TalkBack on? (`M7-TALKBACK`)
- Do suggestions feel useful in real messages? (`R1-FEEL`)
- Is personal word frequency noticeable? (`L2-PERSONAL`)
- Does the benefit counter show a real number? (`L2-BENEFIT`)
- Does typing ever stutter on the debounced encrypted write? (`L1-DEBOUNCE`)

**c. Take store screenshots** while you are there — 2 to 8, into a scratch note app, not a real
conversation. See step 4.

**What comes back to me:** the self-check report, pasted, plus anything from (b) that was
wrong. Then the matrix stops saying NOT RUN and starts saying what happened.

---

## 3. Supply the signing key — OPERATOR, ~15 minutes

Either generate an upload key, or choose **Play App Signing** and let Play generate one.

The pipeline is proven: a throwaway key was generated, a release APK and AAB built and verified
with `apksigner` and `jarsigner`, and both destroyed. What you supply will build.

Write `keystore.properties` at the repository root — it is gitignored, along with `*.jks` and
`*.keystore`:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew :app:bundleRelease` produces the signed AAB.

See `docs/OPERATOR_NOTICES.md` NOTICE 4, including what proving the pipeline exposed.

---

## 4. Play Console — OPERATOR

| Item | State |
|---|---|
| Developer account | needed; blocks steps 1 and 4 |
| App entry created | blocks step 1 |
| Icon 512×512 | **done** — `store/play_icon_512.png` |
| Feature graphic 1024×500 | **done** — `store/play_feature_graphic_1024x500.png` |
| Screenshots | **needs step 2c** |
| Listing copy | **drafted** — `docs/STORE_LISTING.md` |
| Data Safety answers | **drafted** — `docs/DATA_SAFETY.md` |
| Privacy policy | **text done** — `docs/PRIVACY_POLICY.md`; needs hosting at a URL, and a contact address filled in |
| Content rating questionnaire | answers drafted in `docs/STORE_LISTING.md` |

---

## 5. Two licensing emails — OPERATOR, ~5 minutes

`docs/OPERATOR_NOTICES.md` NOTICE 2. The lexicon ships under CC BY 4.0 and CC BY-SA 4.0 and the
attribution is already in the settings screen; the emails are courtesy and provenance, not a
blocker.

---

## 6. What I do when the report comes back — ME

- Record every device result in `docs/QA_MATRIX.md`, narrow, with what it does **not** establish.
- Fix whatever the session finds.
- Re-run the whole gate suite and every control.
- **Then, and only then**, revisit the verdict in `docs/RELEASE_READINESS.md`.

---

## What is deliberately still open, and is not a checkbox

Two things below are not blockers. They are known, measured, and recorded so that shipping does
not quietly mean forgetting them.

**The real-word error detector's precision is bounded at [12.5%, 39.7%].** 465 blind-labelled
screens, direction stability 96.2%. The pre-registered rule calls a detector withdrawable below
40% and **no ceiling here reaches it**. Margin, letter-pair and frequency restrictions were all
tested; none rescues it. The distance-2 layer was withdrawn on that evidence. The adjacent
layer still ships, and the question against it is open on the operator's desk, not closed.

**`M8-NETCAPTURE` has never run.** "No network capability" is proven by static analysis of the
source and by scanning the built release artifact, with a deliberately broken APK proving each
scanner can fail. It has never been confirmed by a packet capture on a phone, and the privacy
policy says so in those words.

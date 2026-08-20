# Release readiness

## Verdict: NOT READY

The app is not release-ready, and this document does not qualify that. There is no
"ready except for".

Two independent reasons, either of which is sufficient:

1. **Nothing has ever run on an Android device.** Not one keystroke, not one screen, not one
   suggestion. Everything asserted in this repository is a static check or a JVM measurement.
2. **The release artifact is unsigned**, and the signing secrets are the operator's to provide.

---

## What IS finished, and what that is worth

The build is complete through M12 in the sense that every milestone's code exists, compiles,
and is covered by tests and gates:

- **16 gates**, each with a committed positive control demonstrated red in the same run.
  `scripts/run_gates.py` reports a gate as `NOT-A-GATE` and fails the build if its control ever
  comes back green — verified by neutering a control by hand and watching the orchestrator
  catch it.
- **179 JVM tests**, 0 failures, 0 skipped.
- A **release AAB (5,940,182 bytes)** and APK that build, survive R8 with everything the system
  instantiates by name intact, and carry no network permission or network class reference.
- The lexicon reproduces the build spec's counts to **+0.000%** on all four figures.
- Prediction and real-word error detection measured on corpora **proven** disjoint from the
  data the model was trained on, with the thresholds chosen on a dev slice that shares no
  sentence with the slice they were reported against.

That is a real body of evidence about the things it covers. It is **not** evidence that the
keyboard works, because none of it involves a keyboard being used.

### What changed after the operator first ran it on a phone

The operator installed the app, typed on it, and reported three things. All three are done:

| Reported | Milestone | Status |
|---|---|---|
| The keys are laid out "as if in a mirror" | M9 | **Fixed.** `Layouts.hebrew.rtl = true` mirrored every row. Hebrew SI-1452 maps letters onto physical QWERTY positions, which run left to right. The test that should have caught it asserted the mirrored behaviour instead — the wrong assumption sat in both the implementation and its assertion, so both looked green. |
| The app must be predictive and notice spelling errors | M10 | **Done and measured.** Completion top-3 rises from 2.15%/14.80%/36.58% to 5.73%/25.77%/49.28% at 1/2/3-letter prefixes; next-word top-3 9.80%, offered in 88.36% of positions. |
| It must catch `אם` where `עם` was meant, from sentence context | M11 | **Done and measured.** 64.58% recall at a 0.26% false-alarm rate on a held-out slice the thresholds never saw. |

M12 then closed two gaps that only surface once the features exist: the personal dictionary
from M6 was never read by anything, so a word the user added stayed underlined; and an undo
path for automatic replacement was unreachable, because nothing ever performed one.

## What would have to happen next, in order

### 1. Put it on a phone (nobody else can start until this happens)

Install the debug APK, enable the IME in Settings, select it, and type. That single session
would resolve twelve NOT RUN rows and is the highest-value hour available to this project.

Watch specifically for:
- the bottom key row sitting under the gesture bar (targetSdk 36 forces edge-to-edge with no
  IME exemption — the inset handling is written but never exercised);
- state loss on rotation, and on configuration changes outside the declared `configChanges`
  list, where the view is still recreated;
- whether the lexicon load stutters the first suggestion — 148 ms to build the trie on a
  4-core x86 host is not a phone number, and M10 added a 2.95 MiB bigram table to that load;
- **the non-adjacent replacement.** Tapping a real-word suggestion deletes from the flagged
  word to the cursor and commits a rewritten span. The span arithmetic is unit-tested; the
  Binder round-trip that carries it out is not, and getting it wrong eats text the user typed.
- **whether a word added in Settings stops being underlined** without restarting the app. The
  reload runs in `onStartInput`; both components have to be live for that path to matter.

### 2. Run the latency harness

`:hostapp` and `:benchmark` are built and committed. The keyboard must be **enabled and
selected as the active IME first** — the benchmark cannot do that itself without
`WRITE_SECURE_SETTINGS`. Until it runs, the sub-50 ms target is unverified. GATE-TRACE-1
guarantees the harness is asking for section names the app really emits, so a zero result would
mean "no data", not "fast".

### 3. ~~Decide minSdk 30 vs 31~~ — DONE

**Resolved: minSdk 31**, on operator instruction. Both API-30 deficiencies are gone:
`configChanges` is honoured and `suppressesSpellChecker` actually suppresses. Lint went from 1
warning to 0, all four built APKs report `minSdkVersion:'31'`, and the DEX baselines were
unchanged, so no re-baselining was warranted.

The version bump also made `InputConnection.getSurroundingText` (API 31) reachable — the same
blocking-Binder hazard the project had designed around. `GATE-API-1` was extended to ban it in
the same change, rather than leaving a gap the bump had quietly opened.

### 4. Supply signing secrets

The release build already reads an untracked `keystore.properties` and falls back to unsigned
when it is absent, so the pipeline is complete and tested. It needs the key.

### 5. File the Play extension — this one is time-critical

targetSdk 36 is required from **2026-08-31**. The extension to 2026-11-01 is free and filed
under Policy status in Play Console. See `docs/OPERATOR_NOTICES.md` NOTICE 1.

---

## Things a reader should not misread as stronger than they are

- **"52.60% top-1 correction accuracy"** is on 4,000 *synthetic* typos generated by uniform
  random edits over Wikipedia prose. Real typing errors are distributed differently, and the
  register is wrong for a phone. It is a floor for comparing versions, not a user-facing
  quality claim.
- **"p95 2.88 ms"** is a JVM figure on a 4-core x86 build host with a warm JIT. It says nothing
  about a phone and is never quoted as though it does.
- **"96.73% lexicon coverage"** is encyclopedic Hebrew. Proper nouns are over-represented and
  there is no SMS register, slang, or typos in that corpus.
- **"No network capability"** is proven by static analysis and by scanning the built artifact.
  It has never been confirmed by a packet capture, because that needs a device.
- **The keyboard-adjacency discount is implemented, measured, and deliberately switched off.**
  It costs 8 points of top-1 accuracy on unbiased typos. Anyone reading the spec and expecting
  it to be on should read `docs/CORRECTION_MEASUREMENTS.md` finding 1 first — including the
  part where my own explanation of *why* it hurt turned out to be wrong.
- **"49.28% completion top-3"** is Wikipedia prose, not phone typing. The register is wrong and
  no amount of held-out discipline fixes that. The next-word figure is also measured with a
  known previous word and no punctuation; in the app, context stops at a sentence boundary, so
  the real aggregate offer rate is lower than 88.36% by an amount **not measured**.
- **"64.58% real-word error recall"** answers only: *given that the error is one this detector
  can express, does context find it?* The errors were injected from the detector's own
  inventory. How often real Hebrew typing produces an error inside that inventory is NOT
  MEASURED, and without it no precision figure can be derived from the 0.26% false-alarm rate.
- **This keyboard never replaces anything by itself.** `shouldAutoReplace` exists and is
  measured; it is not called. Every change to the user's text is a tap. On the golden corpus
  the shipped configuration would auto-replace 24.25% of misspellings with **1.90% wrong** —
  text the user meant, silently replaced. One tap is the cheaper error.
- **Personal-dictionary words are ranked above lexicon words with no measurement behind it.**
  There is no corpus of personal dictionaries. It follows from the user having typed the word
  in deliberately, and it is recorded as a design decision rather than a derived weight.

## The one conflict with the build spec, restated

The spec prescribes a keyboard-adjacency discount. Measured on an unbiased corpus it makes
correction **worse**, and it is not enabled. Both numbers are reported with the corpus hash
beside each, the feature remains implemented and tested behind a flag, and what would change
the answer — a corpus of real Hebrew typing errors — is named and recorded as NOT MEASURED.

The threshold was not moved, the corpus was not reweighted, and the feature was not shipped on
the strength of the one corpus that flattered it.
